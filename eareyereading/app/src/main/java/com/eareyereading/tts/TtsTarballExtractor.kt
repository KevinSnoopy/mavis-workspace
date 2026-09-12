package com.eareyereading.tts

import android.util.Log
import com.eareyereading.tts.EmbeddedTtsEngine.Progress
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** 日志 tag 与引擎一致，便于 logcat 统一过滤。 */
private const val TAG = EmbeddedTtsEngine.TAG

/**
 * tarball 归档的下载、解压与落盘校验。
 *
 * 从 `TtsModelDownloader.kt` 抽出的单一职责模块（SRP）：把"归档形态的模型包"
 * 这条路径（下载 → 流式解压 → 路径安全校验 → manifest 校验 → 残片清理）
 * 与逐文件下载路径分离。两条路径的失败语义与清理口径不同，混在一个文件里
 * 会让任何一个改动都可能波及另一条路径。
 *
 * 全部为 [EmbeddedTtsEngine] 的 internal 扩展函数，调用方
 * ([downloadModelLocked]) 的代码与行为保持不变。
 */

/**
 * 解压总字节上限：正常 Piper 归档解压后 ~120MB，给 2 倍余量。
 * 防 bzip2 解压炸弹（恶意/损坏归档写满整盘）。（1.3：512MB → 256MB）
 */
private const val MAX_EXTRACT_BYTES = 256L * 1_000_000L

/**
 * 解压 IO 缓冲：读（BufferedInputStream 归并 BZip2 位读取器的小粒度
 * read）与写（整轮复制的复制缓冲）共用 256KB，减少 syscall 与 GC 压力。
 */
private const val EXTRACTION_IO_BUFFER_BYTES = 256 * 1024

/** 协作式取消的检查粒度（解压期间每累积这么多字节检查一次协程状态） */
private const val CANCEL_CHECK_CHUNK_BYTES = 262144L

/**
 * 下载 tarball 并解压到 models 目录。
 * tarball 内顶层目录应为模型 id（如 vits-piper-en_US-lessac-medium/），
 * 解压后路径与 files.relativePath 对齐。
 * 下载到临时文件（支持断点续传），解压成功后删除 tarball 并为每个文件写 .complete 标记。
 */
internal suspend fun EmbeddedTtsEngine.downloadAndExtractTarball(
    modelInfo: ModelInfo,
    modelsDir: File,
    onProgress: (Float) -> Unit,
): Boolean {
    val tarballFile = File(modelsDir, "${modelInfo.id}.tar.bz2")
    val tarballComplete = File(modelsDir, "${modelInfo.id}.tar.bz2$COMPLETE_SUFFIX")
    // 若已解压完成（所有文件 .complete 存在），直接返回
    if (modelInfo.files.all { f ->
            File(modelsDir, f.relativePath + COMPLETE_SUFFIX).exists() &&
                File(modelsDir, f.relativePath).exists()
        }) {
        return true
    }

    if (!downloadTarball(modelInfo, tarballFile, tarballComplete, onProgress)) {
        Log.e(TAG, "所有 tarball 镜像均不可用")
        return false
    }

    if (!extractTarball(modelInfo, modelsDir, tarballFile, onProgress)) {
        cleanExtractionPartials(modelsDir, modelInfo, tarballFile, tarballComplete)
        return false
    }

    if (!verifyExtractedFiles(modelInfo, modelsDir)) {
        // 归档缺文件：同样清掉 tarball 与残片，强制下次重新下载而不是重复解压
        cleanExtractionPartials(modelsDir, modelInfo, tarballFile, tarballComplete)
        return false
    }

    // 删除 tarball 释放空间
    tarballFile.delete()
    tarballComplete.delete()
    return true
}

/**
 * 多镜像回退下载 tarball（支持断点续传），成功后写完成标记。
 *
 * @return true 表示本地已有一份完整的 tarball
 */
private suspend fun EmbeddedTtsEngine.downloadTarball(
    modelInfo: ModelInfo,
    tarballFile: File,
    tarballComplete: File,
    onProgress: (Float) -> Unit,
): Boolean {
    val totalSize = modelInfo.sizeBytes
    var tarballTotalSize = 0L    // 响应 Content-Length，由 onTotalSizeKnown 回填
    var lastNotifyMs = 0L
    var lastProgressEmitMs = 0L

    for (url in modelInfo.tarballAllUrls()) {
        // 若已有完整 tarball 标记，跳过下载直接解压
        if (tarballComplete.exists() && tarballFile.exists() && tarballFile.length() > 0) {
            return true
        }
        val ok = downloadFileWithResume(
            url = url,
            target = tarballFile,
            onChunkDownloaded = { _, totalBytes ->
                // 按 tarball 自身大小算分母：用 Content-Length，否则用 modelInfo.sizeBytes
                val denominator = if (tarballTotalSize > 0) tarballTotalSize else totalSize
                val now = System.currentTimeMillis()
                // 状态流节流（与逐文件路径同款）：8KB/chunk 全量发射是重组风暴
                if (now - lastProgressEmitMs >= 100) {
                    lastProgressEmitMs = now
                    val p = if (denominator > 0) {
                        (totalBytes.toFloat() / denominator.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    _downloadProgress.value = Progress.Downloading(totalBytes, denominator)
                    onProgress(p)
                    if (now - lastNotifyMs > 500) {
                        lastNotifyMs = now
                        val downloadedMB = totalBytes / 1_000_000
                        val totalMB = if (denominator > 0) denominator / 1_000_000 else 0
                        showDownloadNotification(p, "下载中（${downloadedMB}/${totalMB}MB）")
                    }
                }
            },
            onTotalSizeKnown = { totalBytes ->
                if (totalBytes > 0) {
                    Log.i(TAG, "tarball total size from Content-Length: $totalBytes bytes")
                    tarballTotalSize = totalBytes
                }
            },
        )
        if (ok && tarballFile.length() > 0) {
            tarballComplete.createNewFile()
            return true
        }
        Log.w(TAG, "tarball 下载失败，尝试下一个镜像：$url")
    }
    return false
}

/**
 * 流式解压 tarball 到 models 目录。
 *
 * 失败/取消时清理半截产物并返回 false（取消则向上传播）。
 */
private suspend fun EmbeddedTtsEngine.extractTarball(
    modelInfo: ModelInfo,
    modelsDir: File,
    tarballFile: File,
    onProgress: (Float) -> Unit,
): Boolean {
    showDownloadNotification(null, "解压中...")
    Log.i(TAG, "extraction: starting, tarball=${tarballFile.length()} bytes at ${tarballFile.absolutePath}")
    // 立刻把阶段推到 Extracting，让 UI 立即切到"解压中"，不要在 Downloading(100%) 停住。
    // 1.3：不再预扫（旧 countTarEntries 会完整解压一遍 bzip2 数条目，白费一整个解压时长）。
    // 分母改用"解压放大系数 × 归档大小"估算总字节；末态强制分子对齐到 100%。
    val extractionStartMs = System.currentTimeMillis()
    // 估算解压总字节：tar.bz2 ~66MB → 解压 ~120MB（约 1.8 倍），取 1.5 倍保守留余地
    val estimatedTotalBytes = (modelInfo.sizeBytes * 3L) / 2L
    val tracker = ExtractionTracker(estimatedTotalBytes, extractionStartMs, _downloadProgress, onProgress)
    _downloadProgress.value = Progress.Extracting(
        bytesDone = 0,
        bytesTotal = 1,
        currentEntryName = null,
        elapsedMs = 0,
    )
    onProgress(0f)
    try {
        val tarballCanonical = tarballFile.canonicalPath
        Log.d(TAG, "extraction: opening BZip2+Tar streams on $tarballCanonical")
        val canonicalRoot = modelsDir.canonicalPath + File.separator
        // 协作式取消检查的字节水位：条目级与文件内共用同一计数器
        var copiedSinceCheck = 0L
        // 解压性能优化：
        // 1. BufferedInputStream：commons-compress 的 BZip2 位读取器对底层流做
        //    大量小粒度 read，裸 FileInputStream 时每次都是一次 syscall；
        //    66MB 归档能放大成百万级系统调用，缓冲后归并成 256KB 级读取
        // 2. 复制缓冲整轮解压只分配一次：旧实现每个条目 new 一个 256KB 数组，
        //    espeak-ng-data 几百个小文件 = 几百次大对象分配的 GC 压力
        // 3. 已建目录 HashSet 缓存：跳过同目录连续文件重复 mkdirs 的 stat 调用
        // 4. 条目级 Log.d 撤掉（每文件 2-3 条 × 几百文件），只保留采样日志
        // 解压总量上限：正常归档解压后 ~120MB，256MB 上限防 bzip2 解压炸弹（1.3）
        BufferedInputStream(FileInputStream(tarballFile), EXTRACTION_IO_BUFFER_BYTES).use { bis ->
            BZip2CompressorInputStream(bis).use { bzis ->
                TarArchiveInputStream(bzis).use { tis ->
                    val copyBuf = ByteArray(EXTRACTION_IO_BUFFER_BYTES)
                    val createdDirs = HashSet<String>()
                    var entryCount = 0
                    var entry = tis.nextEntry
                    while (entry != null) {
                        // 协作式取消：~100MB 归档的解压没有天然挂起点，
                        // 旧实现离开页面后还要解压几十秒并留下半截文件
                        copiedSinceCheck += entry.size.coerceAtLeast(0)
                        if (copiedSinceCheck >= CANCEL_CHECK_CHUNK_BYTES) {
                            copiedSinceCheck = 0
                            kotlin.coroutines.coroutineContext[Job]?.ensureActive()
                        }
                        val name = entry.name
                        // 安全：entry 名来自远端 CDN 归档，不可信。
                        // 只接受常规文件/目录（跳过符号链接/硬链接等特殊条目），
                        // 并用 canonical 路径校验落点必须在 modelsDir 内，
                        // 替代只查 ".." 子串的旧检查（会误杀 foo..bar、漏掉符号链接）。
                        // 过滤口径与预扫 countTarEntries 共用，分子分母不漂移
                        if (!shouldCountTarEntry(entry, modelsDir, canonicalRoot)) {
                            entry = tis.nextEntry
                            continue
                        }
                        val outFile = File(modelsDir, name)
                        tracker.currentEntry = name
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                            createdDirs.add(outFile.canonicalPath)
                        } else {
                            val parentPath = outFile.parentFile?.canonicalPath
                            if (parentPath != null && createdDirs.add(parentPath)) {
                                outFile.parentFile?.mkdirs()
                            }
                            BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { out ->
                                while (true) {
                                    val n = tis.read(copyBuf)
                                    if (n == -1) break
                                    out.write(copyBuf, 0, n)
                                    tracker.addBytes(n.toLong())
                                    copiedSinceCheck += n
                                    if (copiedSinceCheck >= CANCEL_CHECK_CHUNK_BYTES) {
                                        copiedSinceCheck = 0
                                        kotlin.coroutines.coroutineContext[Job]?.ensureActive()
                                    }
                                }
                            }
                        }
                        // 解压进度按字节推进（已解压字节在 tracker 内累计），每个条目推一次，
                        // report 内部按 100ms 节流
                        tracker.report(isFinal = false)
                        entryCount++
                        if (entryCount == 1 || entryCount % 100 == 0) {
                            Log.d(
                                TAG,
                                "extraction: $entryCount entries done, " +
                                    "${tracker.totalBytes} bytes so far",
                            )
                        }
                        entry = tis.nextEntry
                    }
                    // 流结束：强制推一次末态，保证 UI 看到解压 100%
                    tracker.report(isFinal = true)
                    Log.i(
                        TAG,
                        "extraction: tar stream fully consumed ($entryCount entries), " +
                            "took ${(System.currentTimeMillis() - extractionStartMs) / 1000}s",
                    )
                }
            }
        }
    } catch (e: CancellationException) {
        // 被取消：清掉半截解压产物和 tarball，避免下次误用残文件
        Log.w(TAG, "extraction cancelled, cleaning partial files")
        cancelDownloadNotification()
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "解压失败", e)
        // 删掉损坏的 tarball、完成标记与半截解压产物：否则下次进来标记检查
        // 直接跳过下载、反复解压同一个坏归档，失败回退路径还可能让
        // ~100MB tarball 与几十 MB 残片（espeak-ng-data 半拉子目录）永久驻盘
        cancelDownloadNotification()
        return false
    }
    return true
}

/**
 * 按 manifest 校验解压产物并为每个文件写 .complete 标记。
 *
 * 目录存在即可；文件要求存在且非空。任一缺失即判定失败（调用方负责清理）。
 */
private fun verifyExtractedFiles(modelInfo: ModelInfo, modelsDir: File): Boolean {
    Log.i(TAG, "extraction: verifying ${modelInfo.files.size} files from manifest")
    for (f in modelInfo.files) {
        val target = File(modelsDir, f.relativePath)
        // 目录：存在即可；文件：存在且非空
        val ok = if (target.isDirectory) target.exists() else target.exists() && target.length() > 0
        if (!ok) {
            Log.e(TAG, "解压后文件缺失或为空：${f.relativePath}")
            return false
        }
        File(modelsDir, f.relativePath + COMPLETE_SUFFIX).createNewFile()
    }
    return true
}

/**
 * 解压进度上报器：累计已解压字节并按 100ms 节流推送到进度流。
 *
 * 抽成小类是因为进度上报需要跨"条目循环"保持可变状态（累计字节、上次推送
 * 时间、当前条目名），散落在解压函数里会与解压逻辑耦合。
 */
private class ExtractionTracker(
    private val estimatedTotalBytes: Long,
    private val extractionStartMs: Long,
    private val progressFlow: kotlinx.coroutines.flow.MutableStateFlow<Progress>,
    private val onProgress: (Float) -> Unit,
) {
    private var extractedBytes = 0L
    private var lastPushMs = 0L

    var currentEntry: String? = null
    val totalBytes: Long get() = extractedBytes

    fun addBytes(delta: Long) {
        extractedBytes += delta
        if (extractedBytes > MAX_EXTRACT_BYTES) {
            throw IOException(
                "归档解压总量超过 ${MAX_EXTRACT_BYTES / 1_000_000}MB，已中止（疑似损坏归档）",
            )
        }
    }

    /**
     * 推送一次进度。
     *
     * 进行中分母恒比进度略大（estimated、至少 done+1），末态强制分子分母对齐，
     * 否则估算偏差会让最后一帧停在 99.x% 永不收敛。
     */
    fun report(isFinal: Boolean) {
        val doneBytes = extractedBytes.coerceAtLeast(0L)
        val total = if (isFinal) maxOf(doneBytes, 1L) else maxOf(estimatedTotalBytes, doneBytes + 1)
        val now = System.currentTimeMillis()
        if (!isFinal && now - lastPushMs < 100) return
        lastPushMs = now
        progressFlow.value = Progress.Extracting(
            bytesDone = doneBytes,
            bytesTotal = total,
            currentEntryName = currentEntry,
            elapsedMs = now - extractionStartMs,
        )
        onProgress(progressFlow.value.fractionOrZero())
    }
}

/**
 * 预扫与解压共用的条目过滤口径：只数会真正落盘的常规文件/目录，
 * 且落点必须在 modelsDir 内。两处口径不一致时（预扫把 symlink 也计入，
 * 解压循环却跳过），分母 > 分子 → 进度永远停在 99.x%。
 */
private fun shouldCountTarEntry(
    entry: TarArchiveEntry,
    modelsDir: File,
    canonicalRoot: String,
): Boolean =
    (entry.isFile || entry.isDirectory) &&
        File(modelsDir, entry.name).canonicalPath.startsWith(canonicalRoot)

/**
 * 解压失败/取消/校验缺文件时的统一清理：
 * 删 tarball + 完成标记 + manifest 声明的全部产物。
 *
 * 用 deleteRecursively 而不是 delete()：Piper 的 espeak-ng-data 是几百个
 * 文件的目录树，File.delete() 对非空目录静默失败，会留下最多 ~30MB 残片
 * 白占存储（没有 .complete 标记不会误用，但空间泄漏）。
 */
private fun cleanExtractionPartials(
    modelsDir: File,
    modelInfo: ModelInfo,
    tarballFile: File,
    tarballComplete: File,
) {
    modelInfo.files.forEach { f ->
        File(modelsDir, f.relativePath).let { if (it.exists()) it.deleteRecursively() }
        File(modelsDir, f.relativePath + COMPLETE_SUFFIX).let { if (it.exists()) it.delete() }
    }
    if (tarballFile.exists()) tarballFile.delete()
    if (tarballComplete.exists()) tarballComplete.delete()
}
