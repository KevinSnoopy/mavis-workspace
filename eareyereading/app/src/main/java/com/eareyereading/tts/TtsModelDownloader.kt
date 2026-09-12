package com.eareyereading.tts

import android.util.Log
import com.eareyereading.tts.EmbeddedTtsEngine.EngineState
import com.eareyereading.tts.EmbeddedTtsEngine.Progress
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 模型下载入口（EmbeddedTtsEngine 的扩展函数）：磁盘预检、归档路径优先、
 * 逐文件镜像回退、进度/状态流上报与失败清理。状态与互斥量仍由引擎持有。
 *
 * ── 重构说明（13 条软件设计原则）──
 * 本文件原本 477 行，同时承载"逐文件下载"与"tarball 下载解压"两条差异很大的
 * 路径（后者解压逻辑近 240 行），违反 SRP。现已按路径拆分：
 *   - [TtsTarballExtractor]：归档路径（下载 → 流式解压 → 校验 → 清理）
 *   - 本文件：入口编排 + 逐文件镜像回退路径
 *
 * 行为与拆分前完全一致。
 */

/**
 * 日志 tag 与引擎一致，便于 logcat 统一过滤。
 * 顶层 `private` 常量按文件隔离，同包多个文件各自持有同名 TAG 不冲突。
 */
private const val TAG = EmbeddedTtsEngine.TAG

/** 模型在 app 私有目录下的根目录名（引擎与下载链共用）。 */
internal const val MODELS_DIR_NAME = "sherpa_tts_models"

/** 文件完整下载标记后缀。存在表示该文件已完整下载，避免误用残缺文件。 */
internal const val COMPLETE_SUFFIX = ".complete"

internal suspend fun EmbeddedTtsEngine.downloadModelLocked(
    modelInfo: ModelInfo,
    onProgress: (Float) -> Unit,
): Boolean = withContext(Dispatchers.IO) {
    // 已完整下载（全部 .complete 标记在位）：跳过网络阶段直接进"初始化"语义。
    // 旧实现无条件置 DOWNLOADING(0%)，用户重进页面点下载会闪一下"下载中 0%"
    // 才跳初始化，像是又下了一遍
    if (isModelDownloaded(modelInfo)) {
        Log.i(TAG, "downloadModel: already complete on disk, skip to initialize")
        _downloadProgress.value = Progress.Initializing
        return@withContext true
    }
    _state.value = EngineState.DOWNLOADING
    _downloadProgress.value = Progress.Downloading(0L, modelInfo.sizeBytes)
    showDownloadNotification(0f, "准备下载 ${modelInfo.displayName}")
    try {
        val dir = File(context.filesDir, MODELS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()

        if (!hasEnoughDiskSpace(modelInfo, dir)) {
            val usable = dir.usableSpace
            val needed = modelInfo.sizeBytes * 3
            val msg = "存储空间不足（需要约 ${needed / 1_000_000}MB，仅剩 ${usable / 1_000_000}MB）"
            Log.e(TAG, "downloadModel: $msg")
            _state.value = EngineState.DOWNLOAD_FAILED(msg)
            _downloadProgress.value = Progress.Failed(msg)
            cancelDownloadNotification()
            return@withContext false
        }

        // 优先路径：下载 GitHub release tarball 并解压（国内可达性优于 HuggingFace）
        if (modelInfo.tarballAllUrls().isNotEmpty()) {
            val ok = downloadAndExtractTarball(modelInfo, dir, onProgress)
            if (ok) {
                Log.i(TAG, "downloadModel: tarball extracted + verified, initializing OfflineTts…")
                _downloadProgress.value = Progress.Initializing
                showDownloadCompleteNotification("下载完成，正在启用...")
                return@withContext true
            }
            Log.w(TAG, "tarball 下载/解压失败，回退到逐文件下载")
            showDownloadNotification(0f, "回退到逐文件下载...")
        }

        // 仅归档模型（Piper 类：整目录树、无逐文件镜像）：
        // 归档失败即下载失败，不进逐文件路径（空 URL 只会逐个报错）
        if (modelInfo.files.all { f -> f.allUrls().all { it.isBlank() } }) {
            _state.value = EngineState.DOWNLOAD_FAILED("模型归档下载失败，请检查网络后重试")
            _downloadProgress.value = Progress.Failed("模型归档下载失败")
            cancelDownloadNotification()
            return@withContext false
        }

        if (!downloadFilesFallback(modelInfo, dir, onProgress)) return@withContext false

        _downloadProgress.value = Progress.Initializing
        showDownloadCompleteNotification("下载完成，正在启用...")
        true
    } catch (e: CancellationException) {
        // 调用方取消（离开页面等）：清掉 ongoing 通知、复位状态后向上传播。
        // 旧实现状态流永远停在 DOWNLOADING，UI 显示"下载中"直到进程重启
        cancelDownloadNotification()
        _downloadProgress.value = Progress.Idle
        _state.value = EngineState.MODEL_NOT_FOUND
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "downloadModel failed", e)
        _state.value = EngineState.DOWNLOAD_FAILED(e.message ?: "未知错误")
        _downloadProgress.value = Progress.Failed(e.message ?: "下载失败")
        cancelDownloadNotification()
        false
    }
}

/**
 * 磁盘空间预检：tarball 本体 + 解压产物（解压后通常比 bz2 大）+ 余量。
 * 空间不足时 fail-fast 给明确原因，而不是下到一半/解压到一半抛
 * 不可读的 IOException，还留下几十 MB 残片。
 */
private fun hasEnoughDiskSpace(modelInfo: ModelInfo, dir: File): Boolean {
    val usable = dir.usableSpace
    if (usable <= 0) return true  // 拿不到容量信息时不做拦截
    return usable >= modelInfo.sizeBytes * 3
}

/**
 * 回退路径：逐文件下载（HuggingFace，国内可能不可达），每个文件多镜像回退。
 *
 * @return true 表示全部文件下载完成；失败时已写好失败状态并撤销通知
 */
private suspend fun EmbeddedTtsEngine.downloadFilesFallback(
    modelInfo: ModelInfo,
    dir: File,
    onProgress: (Float) -> Unit,
): Boolean {
    val totalSize = modelInfo.sizeBytes
    var downloadedTotal = 0L
    var lastNotifyMs = 0L
    var lastProgressEmitMs = 0L

    for (file in modelInfo.files) {
        val targetFile = File(dir, file.relativePath)
        val completeFile = File(dir, file.relativePath + COMPLETE_SUFFIX)
        targetFile.parentFile?.mkdirs()

        // 已完整下载则跳过
        if (completeFile.exists() && targetFile.exists() && targetFile.length() > 0) {
            downloadedTotal += targetFile.length()
            _downloadProgress.value = Progress.Downloading(downloadedTotal, totalSize)
            onProgress(_downloadProgress.value.fractionOrZero())
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
                        _downloadProgress.value = Progress.Downloading(downloadedTotal, denom)
                        onProgress(p)
                        if (now - lastNotifyMs > 500) {
                            lastNotifyMs = now
                            showDownloadNotification(
                                p,
                                "${(p * 100).toInt()}% · ${file.relativePath.substringAfterLast('/')}",
                            )
                        }
                    }
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
            _state.value = EngineState.DOWNLOAD_FAILED("下载失败：${file.relativePath}（所有镜像均不可用）")
            _downloadProgress.value = Progress.Failed("下载失败：${file.relativePath}")
            // 终态通知必须可划掉：showDownloadNotification 是 ongoing 的，
            // 失败时留着一条划不掉的"下载失败"通知只能杀进程消失
            cancelDownloadNotification()
            return false
        }
    }
    return true
}

/** 当 sealed Progress 没有 fraction 时返回 0f；仅给 onProgress 兼容旧回调用 */
internal fun EmbeddedTtsEngine.Progress.fractionOrZero(): Float = when (this) {
    is Progress.Downloading -> fraction
    is Progress.Extracting -> fraction
    Progress.Initializing -> 0.99f
    Progress.Completed -> 1f
    is Progress.Failed -> 0f
    Progress.Idle -> 0f
}
