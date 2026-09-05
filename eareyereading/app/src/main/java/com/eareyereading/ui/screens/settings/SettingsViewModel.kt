package com.eareyereading.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.data.local.dao.VocabularyDao
import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.domain.repository.VocabularyRepository
import com.eareyereading.tts.AVAILABLE_MODELS
import com.eareyereading.tts.EmbeddedTtsEngine
import com.eareyereading.tts.KOKORO_VOICES
import com.eareyereading.tts.isActiveStage
import com.eareyereading.tts.toProgressUi
import com.eareyereading.ui.theme.*
import com.eareyereading.util.NotificationHelper
import com.eareyereading.util.NotificationService
import com.eareyereading.util.TtsHelper
import com.eareyereading.util.formatBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.*
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 ViewModel：DataStore 设置流订阅、内置 TTS 模型状态/下载、
 * LLM 翻译配置、数据导入导出与缓存管理。
 */
data class SettingsUiState(
    val streakDays: Int = 0,
    val totalWords: Int = 0,
    // Material You 动态取色（Android 12+）
    val dynamicColor: Boolean = false,
    val notifications: Boolean = true,
    val notificationDownloadProgress: Boolean = true,
    val notificationDownloadComplete: Boolean = true,
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val isClearing: Boolean = false,
    /** 缓存目录大小（MB）。在 ViewModel 里异步算，避免在组合期遍历磁盘 */
    val cacheSizeMb: Double = 0.0,
    val snackbarMessage: String? = null,
    // 内置 TTS（sherpa-onnx）状态
    val embeddedModelName: String = "",
    val embeddedModelSizeText: String = "",
    val embeddedModelDownloaded: Boolean = false,
    val embeddedDownloading: Boolean = false,
    val embeddedDownloadProgress: Float = 0f,  // 0..1
    // 阶段文案（"下载中 65%" / "解压中 (2/3) tokens.txt" / "正在初始化…"）
    val embeddedDownloadStage: String = "",
    // 下载完成后的初始化窗口：同样占用"不可再下载"语义，
    // 防止 progress 置空后 UI 翻回未下载态诱导并发下载
    val embeddedInitializing: Boolean = false,
    val embeddedReady: Boolean = false,         // 引擎已加载就绪
    // ── 语音模型 / 音色选择（Kokoro 多音色）──
    /** 可选模型列表（含各自的下载状态），模型选择弹窗用 */
    val embeddedModels: List<EmbeddedModelUi> = emptyList(),
    /** 当前选中模型是否为 Kokoro（决定是否展示音色行） */
    val embeddedSelectedModelIsKokoro: Boolean = false,
    /** 当前选中音色展示名（如 "zf_001 · 中文女声"）；非 Kokoro 为空 */
    val embeddedVoiceDisplay: String = "",
    // ── AI 翻译（LLM 通道）──
    val llmTranslateEnabled: Boolean = false,
    val llmApiKey: String = "",
    val llmBaseUrl: String = "",
    val llmModel: String = "",
    /** "测试翻译"进行中（防止连点并发请求） */
    val llmTesting: Boolean = false,
)

/** AI 翻译配置快照（combine 中转，避免 Any 装箱后强转）。 */
private data class LlmSettingsSnapshot(
    val enabled: Boolean,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
)

/** 模型选择弹窗的单个模型条目。 */
data class EmbeddedModelUi(
    val id: String,
    val displayName: String,
    val sizeText: String,
    val downloaded: Boolean,
    val selected: Boolean,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val readingStatsDao: ReadingStatsDao,
    private val vocabularyDao: VocabularyDao,
    private val database: com.eareyereading.data.local.database.AppDatabase,
    private val embeddedTts: EmbeddedTtsEngine,
    private val ttsHelper: TtsHelper,
    private val notificationService: NotificationService,
    private val translationHelper: com.eareyereading.util.TranslationHelper,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val notificationHelper = NotificationHelper(context)

    override fun onCleared() {
        super.onCleared()
        // 阅读类滑杆设置已收敛到阅读页，本页不再有防抖待写项，
        // 无需退出冲刷逻辑
    }

    init {
        observeNotificationPrefs()
        observeLlmSettings()
        observeVocabAndStats()
        observeEmbeddedTts()
        // 清掉上次下载协程被取消后残留的中间态进度，否则 collect 立即收到
        // Downloading/Extracting/Initializing → isInProgress=true → UI 卡在
        // "正在下载..."，下载按钮不可点（用户报告"点击下载没反应"）
        embeddedTts.resetStaleDownloadProgress()
        refreshEmbeddedStatus()
        refreshCacheSize()
    }

    /**
     * 统一的设置流订阅模板：取消向上传播、其余异常记日志
     * （与全仓异步边界 catch 约定一致）。此前 init 里 7 段同款模板复制（DRY 违规）。
     */
    private fun <T> collectSafely(tag: String, flow: Flow<T>, onUpdate: (T) -> Unit) {
        viewModelScope.launch {
            try {
                flow.collect { onUpdate(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "$tag collect failed", e)
            }
        }
    }

    /**
     * 通知与外观偏好开关（阅读相关设置——字号/RSVP/主题/衬线等——已全部
     * 收敛到阅读页内的设置弹窗与底栏快捷设置，本页不再重复提供入口）。
     */
    private fun observeNotificationPrefs() {
        collectSafely("notifications", settingsRepository.getNotifications()) { enabled ->
            _uiState.update { it.copy(notifications = enabled) }
        }
        collectSafely("dynamic color", settingsRepository.getDynamicColor()) { enabled ->
            _uiState.update { it.copy(dynamicColor = enabled) }
        }
        collectSafely("download progress pref", settingsRepository.getNotificationDownloadProgress()) { enabled ->
            _uiState.update { it.copy(notificationDownloadProgress = enabled) }
        }
        collectSafely("download complete pref", settingsRepository.getNotificationDownloadComplete()) { enabled ->
            _uiState.update { it.copy(notificationDownloadComplete = enabled) }
        }
    }

    /** AI 翻译（LLM 通道）配置：四项合成一个流，collect 一处更新。 */
    private fun observeLlmSettings() {
        collectSafely(
            "llm settings",
            combine(
                settingsRepository.getLlmTranslateEnabled(),
                settingsRepository.getLlmApiKey(),
                settingsRepository.getLlmBaseUrl(),
                settingsRepository.getLlmModel(),
            ) { enabled, apiKey, baseUrl, model ->
                LlmSettingsSnapshot(enabled, apiKey, baseUrl, model)
            },
        ) { s ->
            _uiState.update {
                it.copy(
                    llmTranslateEnabled = s.enabled,
                    llmApiKey = s.apiKey,
                    llmBaseUrl = s.baseUrl,
                    llmModel = s.model,
                )
            }
        }
    }

    /** 词汇总数与连续打卡统计。 */
    private fun observeVocabAndStats() {
        viewModelScope.launch {
            try {
                val allStats = readingStatsDao.getAllStats()
                val streak = calculateStreak(allStats)
                _uiState.update { it.copy(streakDays = streak) }
            } catch (_: Exception) { /* DB empty */ }
        }
        collectSafely("vocab count", vocabularyRepository.getTotalCount()) { count ->
            _uiState.update { it.copy(totalWords = count) }
        }
    }

    /** 内置 TTS：引擎状态 + 下载/解压/初始化进度。 */
    private fun observeEmbeddedTts() {
        collectSafely("embedded state", embeddedTts.state) { state ->
            _uiState.update {
                it.copy(embeddedReady = state is EmbeddedTtsEngine.EngineState.READY)
            }
        }
        viewModelScope.launch {
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
                    // 映射收敛在 TtsProgressUi（与阅读页引导弹窗同源）；
                    // isInitializing 单独抽出：Initializing/Completed 阶段 UI 要显示
                    // "初始化中"而非"下载中"，且 Completed 后要清掉 initializing 标志
                    val ui = progress.toProgressUi()
                    // 是否处于"进行中"（显示进度条）：基于 Progress 类型而非 frac 值判断，
                    // 避免 Extracting(0, total, null) 时 frac=0 被误判为"未下载"
                    val isInProgress = progress.isActiveStage
                    val pctInt = (ui.fraction * 100).toInt()
                    if (pctInt != lastEmittedPct || ui.stageText != lastEmittedStage ||
                        ui.isInitializing != lastEmittedInitializing
                    ) {
                        lastEmittedPct = pctInt
                        lastEmittedStage = ui.stageText
                        lastEmittedInitializing = ui.isInitializing
                        _uiState.update {
                            // Completed 时模型必然已落盘，同步置 embeddedModelDownloaded=true，
                            // 避免 initialize() 写 Completed 后、downloadEmbeddedTts() 还没执行到
                            // refreshEmbeddedStatus 的窗口期里 UI 闪现"未下载"
                            val downloadedOverride = when (progress) {
                                EmbeddedTtsEngine.Progress.Completed -> true
                                is EmbeddedTtsEngine.Progress.Failed -> null
                                else -> null
                            }
                            it.copy(
                                embeddedDownloading = isInProgress && !ui.isInitializing,
                                embeddedDownloadProgress = ui.fraction,
                                embeddedDownloadStage = ui.stageText,
                                embeddedInitializing = ui.isInitializing,
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
    }

    /** 异步统计缓存目录大小（磁盘遍历不能放在 Compose 组合期做）。 */
    private fun refreshCacheSize() {
        viewModelScope.launch {
            val sizeMb = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.cacheDir.walkTopDown().sumOf { it.length() } / (1024.0 * 1024.0)
                }
            } catch (_: Exception) {
                0.0
            }
            _uiState.update { it.copy(cacheSizeMb = sizeMb) }
        }
    }

    private fun refreshEmbeddedStatus() {
        viewModelScope.launch {
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
            _uiState.update {
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
        viewModelScope.launch {
            if (id == embeddedTts.getSelectedModelId()) return@launch
            // 先停止当前朗读：initialize 需要 speakMutex，正在朗读时锁被持有
            ttsHelper.stop()
            embeddedTts.setSelectedModelId(id)
            val model = embeddedTts.getCurrentModelInfo()
            val downloaded = withContext(Dispatchers.IO) { embeddedTts.isModelDownloaded(model) }
            if (downloaded) {
                // 已下载：立即初始化换引擎（构造在锁外、替换在锁内，朗读不受影响）
                val ok = embeddedTts.initialize(model)
                _uiState.update {
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
                _uiState.update {
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
        viewModelScope.launch {
            val model = embeddedTts.getCurrentModelInfo()
            embeddedTts.setSelectedSid(model.id, sid)
            _uiState.update {
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
                    _uiState.update { it.copy(snackbarMessage = "已保存音色，下载并启用模型后生效") }
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
        if (_uiState.value.embeddedDownloading || _uiState.value.embeddedInitializing) return
        viewModelScope.launch {
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
                _uiState.update {
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
                _uiState.update {
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
        viewModelScope.launch {
            // 顺序：先停并释放引擎（会等完正在播的句子），再通知 TtsHelper
            // 复位状态并退回系统模式，最后才删文件。
            // 旧实现先删文件再 release，且 TtsHelper 的 ttsMode/isInitialized
            // 完全不知情——之后所有朗读静默失效直到进程重启
            embeddedTts.release()
            ttsHelper.onEmbeddedReleased()
            embeddedTts.deleteModel()
            refreshEmbeddedStatus()
            _uiState.update {
                it.copy(embeddedReady = false, snackbarMessage = "已删除内置语音模型")
            }
        }
    }

    /** Streak calc converged into ReadingStreak: single-source-of-truth for the
     * calendar-day rule shared by Home/Library/Settings. */
    private fun calculateStreak(stats: List<com.eareyereading.data.local.entity.ReadingStatsEntity>): Int =
        com.eareyereading.util.ReadingStreak.calculate(stats)

    /** Material You 动态取色开关（Android 12+）。 */
    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settingsRepository.setDynamicColor(enabled)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "setDynamicColor failed", e)
                _uiState.update { it.copy(snackbarMessage = "设置保存失败") }
            }
        }
    }

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNotifications(enabled)
            // channel 重要性随开关重建（关闭→静默）
            notificationService.rebuildReviewReminderChannel(enabled)
            if (enabled) {
                notificationHelper.scheduleReviewReminder()
            } else {
                notificationHelper.cancelReminder()
            }
        }
    }

    fun setNotificationDownloadProgress(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNotificationDownloadProgress(enabled)
            notificationService.rebuildTtsDownloadChannel(enabled)
        }
    }

    fun setNotificationDownloadComplete(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNotificationDownloadComplete(enabled)
            notificationService.rebuildTtsCompleteChannel(enabled)
        }
    }

    // ── AI 翻译（LLM 通道）配置 ─────────────────────

    fun setLlmTranslateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLlmTranslateEnabled(enabled)
        }
    }

    fun setLlmApiKey(apiKey: String) {
        viewModelScope.launch {
            settingsRepository.setLlmApiKey(apiKey)
        }
    }

    fun setLlmBaseUrl(baseUrl: String) {
        viewModelScope.launch {
            settingsRepository.setLlmBaseUrl(baseUrl)
        }
    }

    fun setLlmModel(model: String) {
        viewModelScope.launch {
            settingsRepository.setLlmModel(model)
        }
    }

    /** 应用服务商预设（端点 + 模型一键切换）。 */
    fun applyLlmPreset(baseUrl: String, model: String) {
        viewModelScope.launch {
            settingsRepository.setLlmBaseUrl(baseUrl)
            settingsRepository.setLlmModel(model)
        }
    }

    /** 测试翻译：无视开关直接用当前 Key 送翻一句样例（配置期校验）。 */
    fun testLlmTranslation() {
        if (_uiState.value.llmTesting) return
        if (_uiState.value.llmApiKey.isBlank()) {
            showSnackbarMessage("请先配置 API Key")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(llmTesting = true) }
            try {
                val result = translationHelper.testLlmTranslation()
                showSnackbarMessage(
                    if (result != null) "测试成功：$result" else "测试失败：请检查 API Key、端点与网络",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showSnackbarMessage("测试失败: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _uiState.update { it.copy(llmTesting = false) }
            }
        }
    }

    fun exportData() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isExporting = true) }
                // 写到应用外部私有目录（文件管理器 Android/data/... 可见，用户可取走），
                // 而不是 cacheDir —— 旧实现放 cacheDir 会被"清除缓存"删掉唯一副本。
                // 外部存储不可用时回退内部 filesDir（至少不会被清缓存误删）
                val exportDir = context.getExternalFilesDir(null) ?: context.filesDir
                val file = File(exportDir, "eareye_backup_${System.currentTimeMillis()}.json")
                val vocabList = vocabularyRepository.getAllVocabulary().first()
                val statsList = readingStatsDao.getAllStats()
                // 用 org.json 序列化：正确转义所有特殊字符。
                // 旧实现手写 JSON 只转义双引号，反斜杠/换行直接裸写，产物是非法 JSON
                val root = org.json.JSONObject()
                root.put("version", 1)
                root.put("exportedAt", System.currentTimeMillis())
                val vocabArr = org.json.JSONArray()
                for (v in vocabList) {
                    val o = org.json.JSONObject()
                    o.put("word", v.word)
                    o.put("definition", v.definition ?: "")
                    o.put("level", v.level)
                    o.put("isLearned", v.isLearned)
                    o.put("note", v.note ?: "")
                    o.put("example", v.example ?: "")
                    vocabArr.put(o)
                }
                root.put("vocabulary", vocabArr)
                val statsArr = org.json.JSONArray()
                for (s in statsList) {
                    val o = org.json.JSONObject()
                    o.put("bookId", s.bookId)
                    o.put("date", s.date)
                    o.put("readingMinutes", s.readingMinutes)
                    o.put("charsRead", s.charsRead)
                    statsArr.put(o)
                }
                root.put("stats", statsArr)
                // issue 11.8：导出复习记录（SM-2 进度），否则换机恢复后
                // 所有词的记忆曲线清零、全部重新 from-scratch。
                // 按 word 导出（vocabularyId 跨机不可复用），导入端按词重链接。
                val reviewRecords = database.reviewRecordDao().getAllReviews().first()
                val reviewArr = org.json.JSONArray()
                for (r in reviewRecords) {
                    val o = org.json.JSONObject()
                    o.put("word", r.word)
                    o.put("easeFactor", r.easeFactor.toDouble())
                    o.put("interval", r.interval)
                    o.put("repetitions", r.repetitions)
                    o.put("nextReviewDate", r.nextReviewDate)
                    o.put("lastReviewDate", r.lastReviewedAt)
                    o.put("lastQuality", r.lastQuality)
                    reviewArr.put(o)
                }
                root.put("reviewRecords", reviewArr)
                // 序列化结果写盘放 IO 线程，大备份不冻 UI
                withContext(Dispatchers.IO) { file.writeText(root.toString()) }
                _uiState.update { it.copy(isExporting = false, snackbarMessage = "已导出: ${file.name}") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e

            } catch (e: Exception) {
                _uiState.update { it.copy(isExporting = false, snackbarMessage = "导出失败: ${e.message}") }
            }
        }
    }

    suspend fun importFromFile(file: File) {
        try {
            _uiState.update { it.copy(isImporting = true) }
            // 大备份的读盘与 JSON 解析都放 IO 线程：解析留在 Main 会冻 UI。
            // 用 org.json 结构化解析。旧实现按字段正则扫描再按索引配对：
            // 释义里一旦出现 "word":"..." 之类的文本就会产生额外匹配，
            // 索引整体错位，后续每个词都配到错误的释义（批量数据损坏）
            val root = withContext(Dispatchers.IO) {
                org.json.JSONObject(file.readText())
            }
            val vocabArr = root.optJSONArray("vocabulary") ?: org.json.JSONArray()
            val statsArr = root.optJSONArray("stats")
            // issue 11.8：复习记录按导入的新词重链接（旧词跳过保本地进度）
            val reviewArr = root.optJSONArray("reviewRecords")
            val reviewByWord = LinkedHashMap<String, org.json.JSONObject>()
            if (reviewArr != null) {
                for (i in 0 until reviewArr.length()) {
                    val o = reviewArr.optJSONObject(i) ?: continue
                    val w = o.optString("word").trim()
                    if (w.isNotEmpty()) reviewByWord[w.lowercase(java.util.Locale.ROOT)] = o
                }
            }
            var imported = 0
            var skipped = 0
            var statsImported = 0
            var statsSkipped = 0
            var reviewImported = 0
            // 事务化导入：中途失败整体回滚，不再留下半成品；
            // REPLACE 冲突策略会用备份字段覆盖本地行的复习进度/书籍关联，
            // 已存在的词一律跳过保留本地状态
            database.withTransaction {
                // 判存集合一次性预加载：循环内逐词 getWord 是 LOWER 全表扫描，
                // n 词备份 × 全表 = O(n²)，大备份会拉长事务持锁时间
                val existingWords = vocabularyDao.getAllWordsLowercase().toHashSet()
                // issue 11.8：记录本备份新插入词的 id，供下方重链接复习记录
                val newIdByWord = HashMap<String, Long>()
                for (i in 0 until vocabArr.length()) {
                    val obj = vocabArr.optJSONObject(i) ?: continue
                    val word = obj.optString("word").trim()
                    if (word.isEmpty()) continue
                    if (word.lowercase(java.util.Locale.ROOT) in existingWords) {
                        skipped++
                        continue
                    }
                    val newId = vocabularyDao.insert(com.eareyereading.data.local.entity.VocabularyEntity(
                        word = word,
                        definition = obj.optString("definition", ""),
                        level = obj.optInt("level", 0),
                        isLearned = obj.optBoolean("isLearned", false),
                        note = obj.optString("note", "").ifBlank { null },
                        example = obj.optString("example", "").ifBlank { null },
                        bookId = 0,
                        bookTitle = "Imported",
                        context = null,
                        translation = null,
                        reviewCount = 0,
                        lastReviewTime = 0L,
                        dateAdded = System.currentTimeMillis(),
                    ))
                    newIdByWord[word.lowercase(java.util.Locale.ROOT)] = newId
                    imported++
                }
                // issue 11.8：恢复复习记录（SM-2 进度）。只恢复"本备份新增词汇"的
                // 记录——已存在词跳过并保留本地进度，与词汇的"保留本地"语义一致。
                // insertReview 用 IGNORE（vocabularyId 唯一），竞态重复插入变幂等。
                for ((wordLower, reviewObj) in reviewByWord) {
                    val vid = newIdByWord[wordLower] ?: continue
                    database.reviewRecordDao().insertReview(
                        com.eareyereading.data.local.entity.ReviewRecordEntity(
                            vocabularyId = vid,
                            word = reviewObj.optString("word"),
                            easeFactor = reviewObj.optDouble("easeFactor", 2.5).toFloat(),
                            interval = reviewObj.optInt("interval", 1),
                            repetitions = reviewObj.optInt("repetitions", 0),
                            nextReviewDate = reviewObj.optLong("nextReviewDate", System.currentTimeMillis()),
                            lastReviewedAt = reviewObj.optLong("lastReviewDate", System.currentTimeMillis()),
                            lastQuality = reviewObj.optInt("lastQuality", 0),
                        ),
                    )
                    reviewImported++
                }
                // 恢复一并导出的阅读统计：旧导入只读 vocabulary，
                // "导出词汇和阅读数据"的承诺恢复时静默丢一半。
                // 与词汇同款的"保留本地"语义：(bookId,date) 已有本地记录则跳过——
                // insertStat 是 REPLACE 冲突策略，直接插会静默覆盖本地当天真实数据
                if (statsArr != null) {
                    for (i in 0 until statsArr.length()) {
                        val obj = statsArr.optJSONObject(i) ?: continue
                        val date = obj.optString("date")
                        val bookId = obj.optLong("bookId", 0)
                        if (date.isEmpty() || bookId == 0L) continue
                        if (readingStatsDao.getStatForBookAndDate(bookId, date) != null) {
                            statsSkipped++
                            continue
                        }
                        readingStatsDao.insertStat(
                            com.eareyereading.data.local.entity.ReadingStatsEntity(
                                bookId = bookId,
                                date = date,
                                readingMinutes = obj.optInt("readingMinutes", 0),
                                charsRead = obj.optInt("charsRead", 0),
                                paragraphsRead = obj.optInt("paragraphsRead", 0),
                            )
                        )
                        statsImported++
                    }
                }
            }
            val skipNote = if (skipped > 0) "（$skipped 条已有词汇跳过，保留本地进度）" else ""
            val statsNote = if (statsImported > 0 || statsSkipped > 0) {
                "，阅读统计导入 $statsImported 条" + if (statsSkipped > 0) "、跳过 $statsSkipped 条" else ""
            } else ""
            val reviewNote = if (reviewImported > 0) "，复习记录导入 $reviewImported 条" else ""
            _uiState.update { it.copy(isImporting = false, snackbarMessage = "已导入 $imported 条词汇$skipNote$statsNote$reviewNote") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e

        } catch (e: Exception) {
            _uiState.update { it.copy(isImporting = false, snackbarMessage = "导入失败: ${e.message}") }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearing = true) }
            try {
                // 清理缓存目录（临时文件等）；导出备份不在 cacheDir，不受影响。
                // 目录遍历+批量删除是磁盘活，放 IO 调度器避免冻 UI
                withContext(Dispatchers.IO) {
                    context.cacheDir.walkTopDown().forEach { it.delete() }
                }
                _uiState.update { it.copy(isClearing = false, snackbarMessage = "缓存已清除") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e

            } catch (e: Exception) {
                _uiState.update { it.copy(isClearing = false, snackbarMessage = "清除失败: ${e.message}") }
            }
            refreshCacheSize()
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            try {
                settingsRepository.clearAll()
                // 默认值是"开启提醒"：重置后必须补排闹钟而不是取消，
                // 否则开关显示开启却永远不提醒（只能靠拨开关/重启救活）
                notificationHelper.scheduleReviewReminder()
                _uiState.update { it.copy(snackbarMessage = "已恢复默认设置") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e

            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = "重置失败: ${e.message}") }
            }
        }
    }

    /** 供 Composable 侧回调直接弹提示（如导入文件读取失败）。 */
    fun showSnackbarMessage(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun dismissSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
