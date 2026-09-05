package com.eareyereading.tts

import android.util.Log
import com.eareyereading.util.NotificationService
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * 模型下载器：模型目录的下载 / 解压 / 校验 / 清理与磁盘查询。
 *
 * 组合进 [EmbeddedTtsEngine]（组合优于继承）：状态流与通知服务以构造端口
 * 注入，下载链只经端口回写进度/状态——引擎的 mutable 状态保持私有，
 * 不再被扩展函数直接触达（迪米特法则）。引擎对外保留
 * downloadModel / isModelDownloaded / deleteModel 等门面方法。
 */
internal class TtsModelDownloader(
    private val modelsDir: File,
    private val state: MutableStateFlow<EmbeddedTtsEngine.EngineState>,
    private val progress: MutableStateFlow<EmbeddedTtsEngine.Progress>,
    private val notificationService: NotificationService,
) {

    /** 文件完整下载标记后缀。存在表示该文件已完整下载，避免误用残缺文件。 */
    private val completeSuffix = ".complete"

    /**
     * 检查模型是否已下载（且每个文件有 .complete 标记，确保完整）。
     */
    fun isModelDownloaded(modelInfo: ModelInfo): Boolean =
        modelInfo.files.all { file ->
            val f = File(modelsDir, file.relativePath)
            val contentOk = if (f.isDirectory) f.exists() else f.exists() && f.length() > 0
            contentOk && File(modelsDir, file.relativePath + completeSuffix).exists()
        }

    /** 已下载模型的磁盘占用（字节）。 */
    fun downloadedSizeBytes(): Long {
        if (!modelsDir.exists()) return 0L
        return modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** 删除模型全部产物与完成标记（磁盘部分；引擎状态复位于引擎侧）。 */
    fun deleteModelFiles(modelInfo: ModelInfo) {
        modelInfo.files.forEach { file ->
            // deleteRecursively：Piper 的 espeak-ng-data 是目录，
            // File.delete() 对非空目录静默失败会留下 ~5MB 残留
            File(modelsDir, file.relativePath).let { if (it.exists()) it.deleteRecursively() }
            File(modelsDir, file.relativePath + completeSuffix).let { if (it.exists()) it.delete() }
        }
    }

    /**
     * 下载模型文件（带进度回调、多镜像回退、断点续传）。
     * 调用方（引擎）负责 downloadMutex 互斥——设置页与阅读页弹窗是两个
     * 独立入口，两个下载协程交错写同一批文件会产出损坏模型。
     */
    suspend fun download(modelInfo: ModelInfo, onProgress: (Float) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            // 已完整下载（全部 .complete 标记在位）：跳过网络阶段直接进"初始化"语义。
            // 旧实现无条件置 DOWNLOADING(0%)，用户重进页面点下载会闪一下"下载中 0%"
            // 才跳初始化，像是又下了一遍
            if (isModelDownloaded(modelInfo)) {
                Log.i(TAG, "downloadModel: already complete on disk, skip to initialize")
                progress.value = EmbeddedTtsEngine.Progress.Initializing
                return@withContext true
            }
            state.value = EmbeddedTtsEngine.EngineState.DOWNLOADING
            progress.value = EmbeddedTtsEngine.Progress.Downloading(0L, modelInfo.sizeBytes)
            notificationService.showTtsDownloadProgress(0f, "准备下载 ${modelInfo.displayName}")
            try {
                if (!modelsDir.exists()) modelsDir.mkdirs()

                // 磁盘空间预检：tarball 本体 + 解压产物（解压后通常比 bz2 大）+ 余量。
                // 空间不足时 fail-fast 给明确原因，而不是下到一半/解压到一半抛
                // 不可读的 IOException，还留下几十 MB 残片
                val usable = modelsDir.usableSpace
                val needed = modelInfo.sizeBytes * 3
                if (usable > 0 && usable < needed) {
                    val msg = "存储空间不足（需要约 ${needed / 1_000_000}MB，仅剩 ${usable / 1_000_000}MB）"
                    Log.e(TAG, "downloadModel: $msg")
                    state.value = EmbeddedTtsEngine.EngineState.DOWNLOAD_FAILED(msg)
                    progress.value = EmbeddedTtsEngine.Progress.Failed(msg)
                    notificationService.cancelTtsDownloadNotification()
                    return@withContext false
                }

                // 优先路径：下载 GitHub release tarball 并解压（国内可达性优于 HuggingFace）
                if (modelInfo.tarballAllUrls().isNotEmpty()) {
                    val ok = downloadAndExtractTarball(modelInfo, onProgress)
                    if (ok) {
                        Log.i(TAG, "downloadModel: tarball extracted + verified, initializing OfflineTts…")
                        progress.value = EmbeddedTtsEngine.Progress.Initializing
                        notificationService.showTtsDownloadComplete("下载完成，正在启用...")
                        return@withContext true
                    }
                    Log.w(TAG, "tarball 下载/解压失败，回退到逐文件下载")
                    notificationService.showTtsDownloadProgress(0f, "回退到逐文件下载...")
                }

                // 仅归档模型（Piper 类：整目录树、无逐文件镜像）：
                // 归档失败即下载失败，不进逐文件路径（空 URL 只会逐个报错）
                if (modelInfo.files.all { f -> f.allUrls().all { it.isBlank() } }) {
                    state.value = EmbeddedTtsEngine.EngineState.DOWNLOAD_FAILED("模型归档下载失败，请检查网络后重试")
                    progress.value = EmbeddedTtsEngine.Progress.Failed("模型归档下载失败")
                    notificationService.cancelTtsDownloadNotification()
                    return@withContext false
                }

                // 回退路径：逐文件下载（HuggingFace，国内可能不可达）
                val totalSize = modelInfo.sizeBytes
                var downloadedTotal = 0L
                var lastNotifyMs = 0L
                var lastProgressEmitMs = 0L

                for (file in modelInfo.files) {
                    val targetFile = File(modelsDir, file.relativePath)
                    val completeFile = File(modelsDir, file.relativePath + completeSuffix)
                    targetFile.parentFile?.mkdirs()

                    // 已完整下载则跳过
                    if (completeFile.exists() && targetFile.exists() && targetFile.length() > 0) {
                        downloadedTotal += targetFile.length()
                        progress.value = EmbeddedTtsEngine.Progress.Downloading(downloadedTotal, totalSize)
                        onProgress(progress.value.fractionOrZero())
                        continue
                    }
                    // 未完成的残片会计入已下载量：断点续传从残片长度继续，
                    // 不把存量计入分子会让进度条在续传期间停滞、完成时突跳
                    if (targetFile.exists() && targetFile.length() > 0) {
                        downloadedTotal += targetFile.length()
                    }

                    // 多镜像回退：依次尝试所有 URL，任一成功即可
                    var fileOk = false
                    for (url in file.allUrls()) {
                        val ok = downloadFileWithResume(
                            url = url,
                            target = targetFile,
                            onChunkDownloaded = { bytesRead, totalBytes ->
                                // bytesRead 是本批增量；totalBytes 是当前已下载（含 previous part）。
                                // 这里仍按"累计"口径算分母，与 onTotalSizeKnown 配合
                                downloadedTotal += bytesRead
                                val denom = if (totalSize > 0) totalSize else totalBytes
                                val now = System.currentTimeMillis()
                                // 状态流节流：每 8KB chunk 发射一次 = 66MB 模型 8000+ 次
                                // 发射、收集端每秒数百次重组；100ms 粒度对进度视觉无差别
                                if (now - lastProgressEmitMs >= 100) {
                                    lastProgressEmitMs = now
                                    val p = if (denom > 0) {
                                        (downloadedTotal.toFloat() / denom.toFloat()).coerceIn(0f, 1f)
                                    } else 0f
                                    progress.value = EmbeddedTtsEngine.Progress.Downloading(downloadedTotal, denom)
                                    onProgress(p)
                                    if (now - lastNotifyMs > 500) {
                                        lastNotifyMs = now
                                        notificationService.showTtsDownloadProgress(
                                            p,
                                            "${(p * 100).toInt()}% · ${file.relativePath.substringAfterLast('/')}",
                                        )
                                    }
                                }
                                // totalBytes 在此回调里也只是参考值，留着供调试使用
                                @Suppress("UNUSED_VARIABLE")
                                val unusedTotal = totalBytes
                            },
                        )
                        if (ok && targetFile.length() > 0) {
                            completeFile.createNewFile()
                            fileOk = true
                            break
                        }
                        Log.w(TAG, "下载失败，尝试下一个镜像：$url")
                    }
                    if (!fileOk) {
                        state.value = EmbeddedTtsEngine.EngineState.DOWNLOAD_FAILED("下载失败：${file.relativePath}（所有镜像均不可用）")
                        progress.value = EmbeddedTtsEngine.Progress.Failed("下载失败：${file.relativePath}")
                        // 终态通知必须可划掉：showDownloadNotification 是 ongoing 的，
                        // 失败时留着一条划不掉的"下载失败"通知只能杀进程消失
                        notificationService.cancelTtsDownloadNotification()
                        return@withContext false
                    }
                }
                progress.value = EmbeddedTtsEngine.Progress.Initializing
                notificationService.showTtsDownloadComplete("下载完成，正在启用...")
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 调用方取消（离开页面等）：清掉 ongoing 通知、复位状态后向上传播。
                // 旧实现状态流永远停在 DOWNLOADING，UI 显示"下载中"直到进程重启
                notificationService.cancelTtsDownloadNotification()
                progress.value = EmbeddedTtsEngine.Progress.Idle
                state.value = EmbeddedTtsEngine.EngineState.MODEL_NOT_FOUND
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "downloadModel failed", e)
                state.value = EmbeddedTtsEngine.EngineState.DOWNLOAD_FAILED(e.message ?: "未知错误")
                progress.value = EmbeddedTtsEngine.Progress.Failed(e.message ?: "下载失败")
                notificationService.cancelTtsDownloadNotification()
                false
            }
        }

    /** 当 sealed Progress 没有 fraction 时返回 0f；仅给 onProgress 兼容旧回调用 */
    private fun EmbeddedTtsEngine.Progress.fractionOrZero(): Float = when (this) {
        is EmbeddedTtsEngine.Progress.Downloading -> fraction
        is EmbeddedTtsEngine.Progress.Extracting -> fraction
        EmbeddedTtsEngine.Progress.Initializing -> 0.99f
        EmbeddedTtsEngine.Progress.Completed -> 1f
        is EmbeddedTtsEngine.Progress.Failed -> 0f
        EmbeddedTtsEngine.Progress.Idle -> 0f
    }

    /**
     * 预扫与解压共用的条目过滤口径：只数会真正落盘的常规文件/目录，
     * 且落点必须在 modelsDir 内。两处口径不一致时（预扫把 symlink 也计入，
     * 解压循环却跳过），分母 > 分子 → 进度永远停在 99.x%。
     */
    private fun shouldCountTarEntry(
        entry: TarArchiveEntry,
        canonicalRoot: String,
    ): Boolean =
        (entry.isFile || entry.isDirectory) &&
            File(modelsDir, entry.name).canonicalPath.startsWith(canonicalRoot)

    /**
     * 下载 tarball 并解压到 models 目录。
     * tarball 内顶层目录应为模型 id（如 vits-piper-en_US-lessac-medium/），
     * 解压后路径与 files.relativePath 对齐。
     * 下载到临时文件（支持断点续传），解压成功后删除 tarball 并为每个文件写 .complete 标记。
     */
    private suspend fun downloadAndExtractTarball(
        modelInfo: ModelInfo,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val tarballFile = File(modelsDir, "${modelInfo.id}.tar.bz2")
        val tarballComplete = File(modelsDir, "${modelInfo.id}.tar.bz2$completeSuffix")
        // 若已解压完成（所有文件 .complete 存在），直接返回
        if (modelInfo.files.all { f ->
                File(modelsDir, f.relativePath + completeSuffix).exists() &&
                    File(modelsDir, f.relativePath).exists()
            }) {
            return true
        }

        val totalSize = modelInfo.sizeBytes
        var tarballTotalSize = 0L    // 响应 Content-Length，由 onTotalSizeKnown 回填
        var lastNotifyMs = 0L
        var lastProgressEmitMs = 0L

        // 下载 tarball（多镜像回退 + 断点续传）
        var downloaded = false
        for (url in modelInfo.tarballAllUrls()) {
            // 若已有完整 tarball 标记，跳过下载直接解压
            if (tarballComplete.exists() && tarballFile.exists() && tarballFile.length() > 0) {
                downloaded = true
                break
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
                        progress.value = EmbeddedTtsEngine.Progress.Downloading(totalBytes, denominator)
                        onProgress(p)
                        if (now - lastNotifyMs > 500) {
                            lastNotifyMs = now
                            val downloadedMB = totalBytes / 1_000_000
                            val totalMB = if (denominator > 0) denominator / 1_000_000 else 0
                            notificationService.showTtsDownloadProgress(p, "下载中（${downloadedMB}/${totalMB}MB）")
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
                downloaded = true
                break
            }
            Log.w(TAG, "tarball 下载失败，尝试下一个镜像：$url")
        }
        if (!downloaded) {
            Log.e(TAG, "所有 tarball 镜像均不可用")
            return false
        }

        // 解压
        notificationService.showTtsDownloadProgress(null, "解压中...")
        Log.i(TAG, "extraction: starting, tarball=${tarballFile.length()} bytes at ${tarballFile.absolutePath}")
        // 立刻把阶段推到 Extracting，让 UI 立即切到"解压中"，不要在 Downloading(100%) 停住。
        // 1.3：不再预扫（旧 countTarEntries 会完整解压一遍 bzip2 数条目，白费一整个解压时长）。
        // 分母改用"解压放大系数 × 归档大小"估算总字节；末态强制分子对齐到 100%。
        val extractionStartMs = System.currentTimeMillis()
        // 估算解压总字节：tar.bz2 ~66MB → 解压 ~120MB（约 1.8 倍），取 1.5 倍保守留余地
        val estimatedTotalBytes = (modelInfo.sizeBytes * 3L) / 2L
        var totalExtractedBytes = 0L
        var lastExtractionEntry: String? = null
        var lastProgressPushMs = 0L
        progress.value = EmbeddedTtsEngine.Progress.Extracting(bytesDone = 0, bytesTotal = 1, currentEntryName = null, elapsedMs = 0)
        onProgress(0f)
        // 解压进度按字节节流：每 100ms 至多推一次；isFinal=true 强制推末态保证收敛。
        fun pushExtractionProgress(isFinal: Boolean = false) {
            val doneBytes = totalExtractedBytes.coerceAtLeast(0L)
            // 进行中分母恒比进度略大（estimated、至少 done+1），末态强制分子分母对齐，
            // 否则估算偏差会让最后一帧停在 99.x% 永不收敛
            val totalBytes = if (isFinal) maxOf(doneBytes, 1L) else maxOf(estimatedTotalBytes, doneBytes + 1)
            val now = System.currentTimeMillis()
            if (!isFinal && now - lastProgressPushMs < 100) return
            lastProgressPushMs = now
            progress.value = EmbeddedTtsEngine.Progress.Extracting(
                bytesDone = doneBytes,
                bytesTotal = totalBytes,
                currentEntryName = lastExtractionEntry,
                elapsedMs = now - extractionStartMs,
            )
            onProgress(progress.value.fractionOrZero())
        }
        try {
            val tarballCanonical = tarballFile.canonicalPath
            Log.d(TAG, "extraction: opening BZip2+Tar streams on $tarballCanonical")
            val canonicalRoot = modelsDir.canonicalPath + File.separator
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
            java.io.BufferedInputStream(
                java.io.FileInputStream(tarballFile),
                EXTRACTION_IO_BUFFER_BYTES,
            ).use { bis ->
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
                            if (copiedSinceCheck >= 262144) {
                                copiedSinceCheck = 0
                                kotlin.coroutines.coroutineContext[Job]
                                    ?.ensureActive()
                            }
                            val name = entry.name
                            // 安全：entry 名来自远端 CDN 归档，不可信。
                            // 只接受常规文件/目录（跳过符号链接/硬链接等特殊条目），
                            // 并用 canonical 路径校验落点必须在 modelsDir 内，
                            // 替代只查 ".." 子串的旧检查（会误杀 foo..bar、漏掉符号链接）。
                            // 过滤口径与预扫 countTarEntries 共用，分子分母不漂移
                            if (!shouldCountTarEntry(entry, canonicalRoot)) {
                                entry = tis.nextEntry
                                continue
                            }
                            val outFile = File(modelsDir, name)
                            lastExtractionEntry = name
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                                createdDirs.add(outFile.canonicalPath)
                            } else {
                                val parentPath = outFile.parentFile?.canonicalPath
                                if (parentPath != null && createdDirs.add(parentPath)) {
                                    outFile.parentFile?.mkdirs()
                                }
                                java.io.BufferedOutputStream(
                                    FileOutputStream(outFile),
                                    64 * 1024,
                                ).use { out ->
                                    var fileBytes = 0L
                                    while (true) {
                                        val n = tis.read(copyBuf)
                                        if (n == -1) break
                                        out.write(copyBuf, 0, n)
                                        fileBytes += n
                                        copiedSinceCheck += n
                                        totalExtractedBytes += n
                                        if (totalExtractedBytes > MAX_EXTRACT_BYTES) {
                                            throw java.io.IOException(
                                                "归档解压总量超过 ${MAX_EXTRACT_BYTES / 1_000_000}MB，已中止（疑似损坏归档）",
                                            )
                                        }
                                        if (copiedSinceCheck >= 262144) {
                                            copiedSinceCheck = 0
                                            kotlin.coroutines.coroutineContext[Job]
                                                ?.ensureActive()
                                        }
                                    }
                                }
                            }
                            // 解压进度按字节推进（已解压字节在上层累计），每个条目推一次，
                            // pushExtractionProgress 按 100ms 节流
                            pushExtractionProgress(isFinal = false)
                            entryCount++
                            if (entryCount == 1 || entryCount % 100 == 0) {
                                Log.d(
                                    TAG,
                                    "extraction: $entryCount entries done, " +
                                        "$totalExtractedBytes bytes so far",
                                )
                            }
                            entry = tis.nextEntry
                        }
                        // 流结束：强制推一次末态，保证 UI 看到解压 100%
                        pushExtractionProgress(isFinal = true)
                        Log.i(
                            TAG,
                            "extraction: tar stream fully consumed ($entryCount entries), " +
                                "took ${(System.currentTimeMillis() - extractionStartMs) / 1000}s",
                        )
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 被取消：清掉半截解压产物和 tarball，避免下次误用残文件
            Log.w(TAG, "extraction cancelled, cleaning partial files")
            cleanExtractionPartials(modelInfo, tarballFile, tarballComplete)
            notificationService.cancelTtsDownloadNotification()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "解压失败", e)
            // 删掉损坏的 tarball、完成标记与半截解压产物：否则下次进来标记检查
            // 直接跳过下载、反复解压同一个坏归档，失败回退路径还可能让
            // ~100MB tarball 与几十 MB 残片（espeak-ng-data 半拉子目录）永久驻盘
            cleanExtractionPartials(modelInfo, tarballFile, tarballComplete)
            notificationService.cancelTtsDownloadNotification()
            return false
        }

        // 为所有文件写 .complete 标记
        Log.i(TAG, "extraction: verifying ${modelInfo.files.size} files from manifest")
        for (f in modelInfo.files) {
            val target = File(modelsDir, f.relativePath)
            // 目录：存在即可；文件：存在且非空
            val ok = if (target.isDirectory) target.exists() else target.exists() && target.length() > 0
            if (ok) {
                File(modelsDir, f.relativePath + completeSuffix).createNewFile()
            } else {
                Log.e(TAG, "解压后文件缺失或为空：${f.relativePath}")
                // 归档缺文件：同样清掉 tarball 与残片，强制下次重新下载而不是重复解压
                cleanExtractionPartials(modelInfo, tarballFile, tarballComplete)
                return false
            }
        }

        // 删除 tarball 释放空间
        tarballFile.delete()
        tarballComplete.delete()
        return true
    }

    /**
     * 解压失败/取消/校验缺文件时的统一清理：
     * 删 tarball + 完成标记 + manifest 声明的全部产物。
     *
     * 用 deleteRecursively 而不是 delete()：Piper 的 espeak-ng-data 是几百个
     * 文件的目录树，File.delete() 对非空目录静默失败，会留下最多 ~30MB 残片
     * 白占存储（没有 .complete 标记不会误用，但空间泄漏）。
     */
    private fun cleanExtractionPartials(
        modelInfo: ModelInfo,
        tarballFile: File,
        tarballComplete: File,
    ) {
        deleteModelFiles(modelInfo)
        if (tarballFile.exists()) tarballFile.delete()
        if (tarballComplete.exists()) tarballComplete.delete()
    }

    companion object {
        /** 日志 tag 与引擎一致，便于 logcat 统一过滤。 */
        private const val TAG = "EmbeddedTtsEngine"

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
    }
}
