@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.*
import com.eareyereading.domain.repository.*
import com.eareyereading.tts.EmbeddedTtsEngine
import com.eareyereading.tts.hidesProgressValue
import com.eareyereading.tts.toProgressUi
import com.eareyereading.tts.AVAILABLE_MODELS
import com.eareyereading.ui.theme.*
import com.eareyereading.util.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * TTS 引擎引导域：内置模型下载/初始化引导、按需朗读、预热与音色匹配提示。
 */
/**
 * 显示 TTS 引导弹窗——已下线系统 TTS 探测，只剩"提醒下载内置引擎"一种场景。
 */
private suspend fun ReaderViewModel.showTtsInstallPrompt(force: Boolean = false) {
    val embeddedEngine = ttsHelper.getEmbeddedEngine()
    val embeddedNotDownloaded = AVAILABLE_MODELS.none {
        embeddedEngine.isModelDownloaded(it)
    }
    // 防抖：未下载内置模型时强制每次弹出（用户关掉后仍能找到入口）；
    // 已下载则不再骚扰
    if (!force && !embeddedNotDownloaded && ttsPromptShownThisSession) {
        android.util.Log.d("ReaderViewModel", "TTS install prompt suppressed (already shown this session)")
        return
    }

    val embeddedModelInfo = embeddedEngine.resolveModelForLanguage(_uiState.value.book?.language)
    val embeddedDownloaded = embeddedEngine.isModelDownloaded(embeddedModelInfo)
    val embeddedSizeText = formatBytes(embeddedModelInfo.sizeBytes)

    android.util.Log.i(
        "ReaderViewModel",
        "Showing TTS prompt (embedded-only): embeddedDownloaded=$embeddedDownloaded",
    )
    _ttsInstallPrompt.tryEmit(
        TtsInstallPrompt(
            embeddedModelDownloaded = embeddedDownloaded,
            embeddedModelDisplayName = embeddedModelInfo.displayName,
            embeddedModelSizeText = embeddedSizeText,
        )
    )
    ttsPromptShownThisSession = true
}

/**
 * 用户对 TTS 引导弹窗的操作。
 */
fun ReaderViewModel.onTtsInstallAction(action: TtsInstallAction) {
    when (action) {
        is TtsInstallAction.DownloadEmbeddedTts -> {
            downloadEmbeddedTtsModel()
        }
        is TtsInstallAction.Dismiss -> { /* no-op */ }
        // "启用"按钮：当前 no-op（见 TtsInstallAction.RetryWithEngine 注释）
        is TtsInstallAction.RetryWithEngine -> {}
    }
}

/**
 * 下载内置 TTS 模型，下载完后自动初始化并启用。
 */
private fun ReaderViewModel.downloadEmbeddedTtsModel() {
    // 防重入：弹窗按钮在下载期间仍可点，连点会并发下载同一个模型
    if (downloadJob?.isActive == true) {
        showToast("下载进行中，请稍候")
        return
    }
    val embeddedEngine = ttsHelper.getEmbeddedEngine()
    // 清掉上次下载被取消后残留的中间态进度，避免 collect 立即收到
    // Downloading/Extracting/Initializing 显示残留进度条
    embeddedEngine.resetStaleDownloadProgress()
    // 按本书语言下载对应模型（兼容旧调用，当前实现只下 Piper）
    val bookLanguage = _uiState.value.book?.language
    val modelInfo = embeddedEngine.resolveModelForLanguage(bookLanguage)
    showToast("开始下载内置 TTS 模型（约 ${modelInfo.sizeBytes / 1_000_000}MB），请保持网络...")
    downloadJob = viewModelScope.launch {
        // 页内进度可见：引擎的 downloadProgress 流镜像进 uiState，
        // 引导弹窗保持打开并显示进度条（原实现进度只 log，弹窗直接关闭，
        // 想看进度只能去设置页）。按整百分比节流，避免高频重组
        // 注意：embeddedEngine.downloadProgress 现在是 sealed Progress，不是 Float?；
        // 我们把 fraction 和 stage 都映射进 uiState 让 UI 既能画进度条又能显示阶段文案。
        var lastEmittedPct = -999
        var lastEmittedStage: String? = null
        val progressJob = launch {
            embeddedEngine.downloadProgress.collect { progress ->
                val ui = progress.toProgressUi()
                // 空文案归 null：Reader 侧的"无任务"语义（进度条收敛）由 Idle 空文案承载
                val stage = ui.stageText.ifBlank { null }
                val pctInt = (ui.fraction * 100).toInt()
                // 把 fraction 转成 Float?（让已有 UI 字段继续工作）
                // null 表示"无任务"，由 UI 层处理
                // Completed 也置 null：阅读页弹窗在下载成功后由 showToast 提示，
                // 进度条该消失而非停在 100%（与设置页不同，设置页有持续状态卡片）
                val fracOut: Float? = if (progress.hidesProgressValue) null else ui.fraction
                // 去重只看整百分比与阶段文案：旧条件里
                // `fracOut != _uiState.value.embeddedDownloadProgress` 拿原始
                // 浮点比较，下载期间进度小数每 100ms 都在变 → 条件恒真，
                // 阅读页（整本书的渲染列表）每秒重组 10 次，肉眼可见掉帧
                if (pctInt != lastEmittedPct || stage != lastEmittedStage) {
                    lastEmittedPct = pctInt
                    lastEmittedStage = stage
                    _uiState.update {
                        it.copy(
                            embeddedDownloadProgress = fracOut,
                            embeddedDownloadStage = stage,
                        )
                    }
                }
            }
        }
        try {
            val ok = embeddedEngine.downloadModel(modelInfo) { progress ->
                android.util.Log.d("ReaderViewModel", "Embedded TTS download progress: ${(progress * 100).toInt()}%")
            }
            if (ok) {
                showToast("下载完成，正在启用内置 TTS...")
                // initialize() 内部会写 Progress.Completed，collect 会把 fracOut 置 null，
                // 进度条自然消失；此处不再手动维持 1f
                val initOk = ttsHelper.initializeEmbeddedForced(bookLanguage)
                if (initOk) {
                    showToast("✅ 内置 TTS 已启用！现在可以朗读了")
                } else {
                    showToast("模型下载完成但初始化失败")
                }
            } else {
                // 带上引擎的具体失败原因（存储空间不足/镜像均不可用/解压失败等），
                // 不再是笼统的"检查网络"——空间不足时那条提示会误导用户反复重试
                // 引擎统一入口给出裸失败原因（存储空间不足/镜像均不可用/
                // 解压失败等），展示层只负责加前缀——不再是笼统的"检查网络"
                val reason = embeddedEngine.downloadFailureReasonOrNull()
                showToast(
                    if (reason.isNullOrBlank()) "下载失败，请检查网络后重试"
                    else "下载失败：$reason",
                )
                // 失败才把进度清掉，让 UI 退出"下载中"
                _uiState.update { it.copy(embeddedDownloadProgress = null) }
            }
        } finally {
            progressJob.cancel()
        }
    }
}

/**
 * 播放前统一的 TTS 初始化闸：已初始化直接放行；未初始化则按本书语言初始化
 * 并同步 ttsInitialized 状态。自动朗读 / RSVP / 速读 / 单段朗读四个播放入口
 * 此前各自复制同一段 try-catch 模板（DRY 违规），收敛于此。
 *
 * @return true = 引擎就绪（本就初始化过，或本次初始化成功）
 */
internal suspend fun ReaderViewModel.ensureTtsInitialized(): Boolean {
    if (_uiState.value.ttsInitialized) return true
    val ok = try {
        ttsHelper.initialize(_uiState.value.book?.language ?: "en")
    } catch (e: TimeoutCancellationException) {
        android.util.Log.w("ReaderViewModel", "TTS init timed out", e)
        false
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e("ReaderViewModel", "TTS init failed", e)
        false
    }
    _uiState.update { it.copy(ttsInitialized = ok) }
    return ok
}

/**
 * 处理 TTS 初始化失败：系统 TTS 失败现已不可能发生（TtsHelper 不再尝试系统 TTS），
 * 所以这条路径只会触发"内置 TTS 未下载 / 模型损坏"，直接引导用户去设置页下载。
 */
internal suspend fun ReaderViewModel.handleTtsInitFailure(prefix: String) {
    _uiState.update { it.copy(ttsInitialized = false) }
    val embeddedEngine = ttsHelper.getEmbeddedEngine()
    val embeddedNotDownloaded = !embeddedEngine.isModelDownloaded()
    val message = if (embeddedNotDownloaded) {
        val sizeMB = embeddedEngine.resolveModelForLanguage(_uiState.value.book?.language).sizeBytes / 1_000_000
        "$prefix：需要下载内置语音模型（约 ${sizeMB}MB）"
    } else {
        "$prefix：内置 TTS 初始化失败"
    }
    showToast(message)
    // 总是弹引导（让用户能进设置页下载/重试）
    showTtsInstallPrompt()
}

/**
 * 内置音色与本书语言不匹配时给一次性提示（会话内只提示一次）：
 * 英文书落在纯中文声 → 口音重、数字带中文音；
 * 中文书落在纯英文声 → 中文字读不出（静音）。都引导去设置切换模型。
 *
 * 2026-09-04: 双模型时代重写判定——ModelInfo.language 现在是逗号分隔的
 * 支持语言集合（Kokoro = "zh,en"），按"本书语言是否在模型支持集合里"判断；
 * 双语模型读任何书都不算 mismatch。
 */
internal fun ReaderViewModel.hintEmbeddedVoiceMismatchIfNeeded() {
    if (embeddedVoiceMismatchHintShown) return
    if (ttsHelper.ttsMode != TtsHelper.TtsMode.EMBEDDED) return
    val model = ttsHelper.getEmbeddedEngine().getCurrentModelInfo()
    embeddedVoiceMismatchHintShown = true
    val bookLanguage = (_uiState.value.book?.language ?: "en").lowercase()
    val supported = model.language.split(",").map { it.trim().lowercase() }
    if (bookLanguage !in supported) {
        showToast("当前内置音色（${model.displayName}）不支持本书语言，建议在设置中切换语音模型")
    }
}

/**
 * 单词/句子弹窗里的"播放发音"按钮：对给定文本执行一次朗读。
 * TTS 未初始化则先初始化（用当前书语言），失败静默告警不打断弹窗。
 */
fun ReaderViewModel.speakOnDemand(text: String) {
    if (text.isBlank()) return
    viewModelScope.launch {
        try {
            if (!_uiState.value.ttsInitialized) {
                try {
                    ttsHelper.initialize(_uiState.value.book?.language ?: "en")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("ReaderViewModel", "TTS init for on-demand speak failed", e)
                }
            }
            hintTtsWarmUpIfNeeded()
            ttsHelper.speak(text)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "on-demand speak failed", e)
        }
    }
}

/**
 * 引擎未完成首次推理预热时给一次性提示：此时 speak 会挂锁等启动预热
 * （app 启动时后台跑的长句冷启动合成，~8s）完成——无声等待是预期行为，
 * 无提示时用户会误判"没声音/卡死"并反复连点（连点还会取消重排，
 * 进一步拖后出声，2026-09-05 实测）。预热完成后此方法零成本 no-op。
 */
internal fun ReaderViewModel.hintTtsWarmUpIfNeeded() {
    if (ttsWarmUpHintShown) return
    if (ttsHelper.getEmbeddedEngine().isWarmedUp()) return
    ttsWarmUpHintShown = true
    showToast("语音引擎首次准备中，需等待几秒…")
}
