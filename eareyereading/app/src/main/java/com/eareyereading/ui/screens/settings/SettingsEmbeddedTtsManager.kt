package com.eareyereading.ui.screens.settings

import com.eareyereading.tts.AVAILABLE_MODELS
import com.eareyereading.tts.EmbeddedTtsEngine
import com.eareyereading.tts.KOKORO_VOICES
import com.eareyereading.util.TtsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内置 TTS（sherpa-onnx）模型管理：模型选择、下载/删除、音色试听、
 * 状态刷新与下载进度收集。
 *
 * 从 [SettingsViewModel] 抽出的单一职责类（SRP）：ViewModel 只负责委托调度，
 * 引擎交互、磁盘检查、进度去重等逻辑集中在本类。
 *
 * @param scope ViewModel 协程作用域（生命周期跟随 ViewModel）
 * @param embeddedTts 内置 TTS 引擎
 * @param ttsHelper TTS 协调器（停朗读 / 刷新引擎类型）
 * @param uiState UI 状态流（本类直接 update，避免 ViewModel 中转）
 */
internal class SettingsEmbeddedTtsManager(
    private val scope: CoroutineScope,
    private val embeddedTts: EmbeddedTtsEngine,
    private val ttsHelper: TtsHelper,
    private val uiState: MutableStateFlow<SettingsUiState>,
) {
    /** 启动引擎状态与下载进度的流收集（在 init 中调用一次）。 */
    fun startCollecting() {
        // 内置 TTS 状态：模型信息 + 下载进度 + 引擎状态
        scope.launch {
            try {
                embeddedTts.state.collect { state ->
                    uiState.update {
                        it.copy(
                            embeddedReady = state is EmbeddedTtsEngine.EngineState.READY,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "embedded state collect failed", e)
            }
        }
        scope.launch {
            try {
                // 整百分比/阶段去重（与 ReaderViewModel 同款）：引擎侧虽已按
                // 100ms 节流发射，但设置页此前每条都 update uiState，下载/解压
                // 的几十秒里整屏每秒重组 10 次；去重后只在整百分比或阶段文案
                // 变化时才更新（66MB 下载全程 ≤100 次 + 解压文件名变化）
                var lastEmittedPct = -999
                var lastEmittedStage: String? = null
                var lastEmittedInitializing: Boolean? = null
                embeddedTts.downloadProgress.collect { progress ->
                    // sealed Progress → (fraction, stage文案, isInitializing) 三通道
                    // isInitializing 单独抽出：Initializing/Completed 阶段 UI 要显示
                    // "初始化中"而非"下载中"，且 Completed 后要清掉 initializing 标志
                    val (frac, stage, isInitializing) = when (progress) {
                        is com.eareyereading.tts.EmbeddedTtsEngine.Progress.Downloading ->
                            Triple(progress.fraction, "下载中 ${(progress.fraction * 100).toInt()}%", false)
                        is com.eareyereading.tts.EmbeddedTtsEngine.Progress.Extracting -> {
                            // 1.3：不再预扫统计文件数，进度按字节推进并附 ETA。
                            // 显示当前正在解压的文件名，让用户看到进展而非只看数字跳
                            val entry = progress.currentEntryName
                            val shortEntry = entry?.substringAfterLast('/')
                            Triple(
                                progress.fraction,
                                formatExtractingStage(progress, shortEntry),
                                false,
                            )
                        }
                        com.eareyereading.tts.EmbeddedTtsEngine.Progress.Initializing ->
                            Triple(0.99f, "正在初始化模型…", true)
                        com.eareyereading.tts.EmbeddedTtsEngine.Progress.Completed ->
                            Triple(1f, "✅ 已启用", false)
                        is com.eareyereading.tts.EmbeddedTtsEngine.Progress.Failed ->
                            Triple(0f, "下载失败：${progress.reason}", false)
                        com.eareyereading.tts.EmbeddedTtsEngine.Progress.Idle ->
                            Triple(0f, "", false)
                    }
                    // 是否处于"进行中"（显示进度条）：基于 Progress 类型而非 frac 值判断，
                    // 避免 Extracting(0, total, null) 时 frac=0 被误判为"未下载"
                    val isInProgress = when (progress) {
                        is com.eareyereading.tts.EmbeddedTtsEngine.Progress.Downloading,
                        is com.eareyereading.tts.EmbeddedTtsEngine.Progress.Extracting -> true
                        com.eareyereading.tts.EmbeddedTtsEngine.Progress.Initializing -> true
                        else -> false
                    }
                    val pctInt = (frac * 100).toInt()
                    if (pctInt != lastEmittedPct || stage != lastEmittedStage ||
                        isInitializing != lastEmittedInitializing
                    ) {
                        lastEmittedPct = pctInt
                        lastEmittedStage = stage
                        lastEmittedInitializing = isInitializing
                        uiState.update {
                            // Completed 时模型必然已落盘，同步置 embeddedModelDownloaded=true，
                            // 避免 initialize() 写 Completed 后、downloadEmbeddedTts() 还没执行到
                            // refreshEmbeddedStatus 的窗口期里 UI 闪现"未下载"
                            val downloadedOverride = when (progress) {
                                com.eareyereading.tts.EmbeddedTtsEngine.Progress.Completed -> true
                                is com.eareyereading.tts.EmbeddedTtsEngine.Progress.Failed -> null
                                else -> null
                            }
                            it.copy(
                                embeddedDownloading = isInProgress && !isInitializing,
                                embeddedDownloadProgress = frac,
                                embeddedDownloadStage = stage,
                                embeddedInitializing = isInitializing,
                                embeddedModelDownloaded = downloadedOverride ?: it.embeddedModelDownloaded,
                            )
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "download progress collect failed", e)
            }
        }
        // 清掉上次下载协程被取消后残留的中间态进度，否则 collect 立即收到
        // Downloading/Extracting/Initializing → isInProgress=true → UI 卡在
        // "正在下载..."，下载按钮不可点（用户报告"点击下载没反应"）
        embeddedTts.resetStaleDownloadProgress()
        refreshEmbeddedStatus()
    }

    /** 刷新当前选中模型的磁盘状态与可选模型列表（IO 线程查文件存在性）。 */
    fun refreshEmbeddedStatus() {
        scope.launch {
            val selected = embeddedTts.getCurrentModelInfo()
            // isModelDownloaded 做多文件存在性检查（含 .complete 标记），
            // 属于磁盘遍历：不得在 Main 调度器上跑
            val models = withContext(Dispatchers.IO) {
                AVAILABLE_MODELS.map { m ->
                    EmbeddedModelUi(
                        id = m.id,
                        displayName = m.displayName,
                        sizeText = formatBytes(m.sizeBytes),
                        downloaded = embeddedTts.isModelDownloaded(m),
                        selected = m.id == selected.id,
                    )
                }
            }
            val selectedDownloaded = models.firstOrNull { it.selected }?.downloaded ?: false
            val voice = embeddedTts.getSelectedVoice()
            uiState.update {
                it.copy(
                    embeddedModelName = selected.displayName,
                    embeddedModelSizeText = formatBytes(selected.sizeBytes),
                    embeddedModelDownloaded = selectedDownloaded,
                    embeddedModels = models,
                    embeddedSelectedModelIsKokoro = selected.isKokoro,
                    embeddedVoiceDisplay = voice?.displayName ?: "",
                )
            }
        }
    }

    /**
     * 切换内置语音模型。已下载的模型立即换引擎（朗读即时生效）；
     * 未下载的只记住选择，由现有"下载内置语音模型"按钮引导下载。
     *
     * 切换前先停止当前朗读：initialize 会挂在 speakMutex 上等朗读结束，
     * 用户会感觉"点了切换没反应"。先 stop() 让锁立即释放，切换才即时生效。
     * 切换后触发 warmUp：否则首次朗读要付 ~10 秒冷启动开销（Kokoro 首块）。
     */
    fun setEmbeddedModel(id: String) {
        scope.launch {
            if (id == embeddedTts.getSelectedModelId()) return@launch
            // 先停止当前朗读：initialize 需要 speakMutex，正在朗读时锁被持有
            ttsHelper.stop()
            embeddedTts.setSelectedModelId(id)
            val model = embeddedTts.getCurrentModelInfo()
            val downloaded = withContext(Dispatchers.IO) { embeddedTts.isModelDownloaded(model) }
            if (downloaded) {
                // 已下载：立即初始化换引擎（构造在锁外、替换在锁内，朗读不受影响）
                val ok = embeddedTts.initialize(model)
                uiState.update {
                    it.copy(
                        embeddedReady = ok,
                        snackbarMessage = if (ok) "已切换到 ${model.displayName}" else "切换失败：模型初始化异常",
                    )
                }
                // 切换成功后预热：与 TtsHelper.initializeEmbeddedForced 一致，
                // 把首次推理冷启动开销挪到切换后的空闲期，而非下次朗读的首声
                if (ok) {
                    launch {
                        try { embeddedTts.warmUp() } catch (_: Exception) {}
                    }
                }
            } else {
                uiState.update {
                    it.copy(
                        snackbarMessage = "已选择 ${model.displayName}，请先下载模型",
                    )
                }
            }
            refreshEmbeddedStatus()
        }
    }

    /**
     * 选择 Kokoro 音色（sid）并立即试听一句。
     * 引擎未就绪/加载的仍是其他模型时先尝试初始化（模型已下载则换引擎）；
     * 未下载时只保存选择。
     *
     * 试听前用 ttsHelper.stop() 而非 embeddedTts.stop()：TtsHelper 层的
     * sentenceChainJob 也需取消，否则自动朗读的 onAllDone 回调不触发、
     * ReaderViewModel 的朗读循环状态不一致。
     */
    fun selectEmbeddedVoice(sid: Int) {
        scope.launch {
            val model = embeddedTts.getCurrentModelInfo()
            embeddedTts.setSelectedSid(model.id, sid)
            uiState.update {
                it.copy(embeddedVoiceDisplay = KOKORO_VOICES.getOrNull(sid)?.displayName ?: "")
            }
            // 引擎未就绪、或加载的还是别的模型（如用户刚从 Piper 切过来）：
            // 先初始化把引擎换到选中的 Kokoro，否则试听会落在英文声上
            val engineReady = embeddedTts.state.value is EmbeddedTtsEngine.EngineState.READY
            if (!engineReady || !embeddedTts.isKokoroActive) {
                // 初始化前先停朗读：initialize 需要 speakMutex，正在朗读时锁被持有
                ttsHelper.stop()
                val ok = embeddedTts.initialize(model)
                if (!ok) {
                    uiState.update { it.copy(snackbarMessage = "已保存音色，下载并启用模型后生效") }
                    return@launch
                }
            }
            // 试听：停掉正在播的（含上一次试听 + 自动朗读链），再读一句中英混合样例
            ttsHelper.stop()
            val previewText = when {
                sid >= 58 -> "你好，这是中文男声音色试听。Hello!"
                sid >= 3 -> "你好，这是中文女声音色试听。Hello!"
                else -> "Hello, this is a voice preview. 你好！"
            }
            embeddedTts.speak(previewText, speed = 1.0f)
        }
    }

    /** 下载内置 TTS 模型（带进度），下载完成后自动初始化。 */
    fun downloadEmbeddedTts() {
        // initializing 窗口同样纳入互斥：下载结束 → progress 置空 →
        // 旧标志翻回"未下载"，此时点下载会与 initialize 并发
        if (uiState.value.embeddedDownloading || uiState.value.embeddedInitializing) return
        scope.launch {
            val model = embeddedTts.getCurrentModelInfo()
            // 注意：所有 UI 状态（progress / stage / downloading 标记 / initializing）
            // 由 viewModelScope 启动的 downloadProgress.collect 统一管理，本函数只负责
            // 触发下载 + 调度 initialize。不再在本函数内手动写 embeddedDownloadProgress
            // —— 之前的旧实现里 "下载 100% 立即 reset 0f" 就是因为 downloadProgress.collect
            // 推送的 Progress.Completed 被本函数 line 326 的"embeddedDownloadProgress = 0f"
            // 立刻覆盖，用户看到的就是"100% 一瞬间又变 0%"。
            val ok = embeddedTts.downloadModel(model) { progress ->
                // 旧 callback 仍传 Float（仅作日志），不再写 uiState
                android.util.Log.d("SettingsViewModel", "download progress callback: ${(progress * 100).toInt()}%")
            }
            if (ok) {
                // 下载/解压阶段结束，初始化 OfflineTts（仍处于"占用中"语义，避免并发）
                // 让 downloadProgress.collect 自然把 stage 切到"正在初始化模型"再变"已启用"
                val initOk = embeddedTts.initialize(model)
                embeddedTts.cancelDownloadNotification()
                refreshEmbeddedStatus()
                uiState.update {
                    it.copy(
                        embeddedModelDownloaded = true,
                        embeddedReady = initOk,
                        snackbarMessage = if (initOk) "内置语音已下载并启用" else "下载完成但初始化失败",
                    )
                }
            } else {
                refreshEmbeddedStatus()
                // 引擎统一入口给出裸失败原因（空间不足/镜像不可用/解压失败），
                // 展示层只负责加前缀——笼统的"检查网络"在空间不足等场景会误导重试
                val reason = embeddedTts.downloadFailureReasonOrNull()
                uiState.update {
                    it.copy(
                        snackbarMessage = if (reason.isNullOrBlank()) {
                            "下载失败，请检查网络后重试（已下载部分下次会续传）"
                        } else {
                            "下载失败：$reason"
                        },
                    )
                }
            }
        }
    }

    /** 删除已下载的内置 TTS 模型（释放空间）。 */
    fun deleteEmbeddedTts() {
        scope.launch {
            // 顺序：先停并释放引擎（会等完正在播的句子），再通知 TtsHelper
            // 复位状态并退回系统模式，最后才删文件。
            // 旧实现先删文件再 release，且 TtsHelper 的 ttsMode/isInitialized
            // 完全不知情——之后所有朗读静默失效直到进程重启
            embeddedTts.release()
            ttsHelper.onEmbeddedReleased()
            embeddedTts.deleteModel()
            refreshEmbeddedStatus()
            uiState.update {
                it.copy(embeddedReady = false, snackbarMessage = "已删除内置语音模型")
            }
        }
    }

    /** 字节大小格式化（B / KB / MB）。 */
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        return "%.0f MB".format(kb / 1024.0)
    }
}

/**
 * 1.3：解压进度文案 —— 按已解压字节百分比 + ETA 估算（替代旧"entriesDone/entriesTotal"）。
 */
private fun formatExtractingStage(
    p: com.eareyereading.tts.EmbeddedTtsEngine.Progress.Extracting,
    shortEntry: String?,
): String {
    val pct = (p.fraction * 100).toInt().coerceIn(0, 100)
    val eta = when {
        p.fraction <= 0.01f || p.fraction >= 0.99f || p.elapsedMs <= 0 -> ""
        else -> {
            val remainingMs = (p.elapsedMs / p.fraction * (1f - p.fraction)).toLong()
            if (remainingMs > 0) " · 剩余约${(remainingMs / 1000).coerceAtMost(999)}s" else ""
        }
    }
    return if (shortEntry != null) "解压中 $pct%$eta $shortEntry" else "解压中 $pct%$eta"
}
