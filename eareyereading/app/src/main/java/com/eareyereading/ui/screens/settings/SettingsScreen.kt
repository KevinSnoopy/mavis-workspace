package com.eareyereading.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.data.local.dao.VocabularyDao
import com.eareyereading.domain.model.ReadingTheme
import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.domain.repository.VocabularyRepository
import com.eareyereading.ui.theme.*
import com.eareyereading.util.NotificationHelper
import com.eareyereading.util.NotificationService
import com.eareyereading.util.TtsHelper
import com.eareyereading.tts.EmbeddedTtsEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class SettingsUiState(
    val fontSize: Int = 18,
    val rsvpSpeed: Int = 300,
    val theme: ReadingTheme = ReadingTheme.LIGHT,
    val streakDays: Int = 0,
    val totalWords: Int = 0,
    val darkMode: Boolean = false,
    val notifications: Boolean = true,
    val notificationDownloadProgress: Boolean = true,
    val notificationDownloadComplete: Boolean = true,
    val collinsHighlight: Boolean = true,
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
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val notificationHelper = NotificationHelper(context)

    // 设置滑杆逐像素写 DataStore 的防抖（与阅读器设置弹窗 persistSettingDebounced 同型，
    // Round 6/8 延期项收口）：UI 状态立即更新保证滑杆跟手，持久化合并到拖停后一次。
    // 按设置项分 key，互不取消；onCleared 兜底冲刷，防抖窗口内退出不丢最终值
    private val settingsPersistJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val settingsPendingWrites = mutableMapOf<String, suspend () -> Unit>()
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun persistSettingDebounced(key: String, write: suspend () -> Unit) {
        settingsPersistJobs[key]?.cancel()
        settingsPendingWrites[key] = write
        settingsPersistJobs[key] = viewModelScope.launch {
            delay(SETTINGS_PERSIST_DEBOUNCE_MS)
            try {
                write()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // DataStore 写失败不得炸到未捕获处理器崩 App：
                // 提示用户，设置值仍保留在本会话内存态
                android.util.Log.e("SettingsViewModel", "persist setting failed: $key", e)
                _uiState.update { it.copy(snackbarMessage = "设置保存失败") }
            }
            // 按身份移除：只清自己这条，不误删并发排队的同名写入
            if (settingsPendingWrites[key] === write) {
                settingsPendingWrites.remove(key)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope 已随 onCleared 取消：用独立 scope 冲刷待写项，
        // 防抖窗口内退出设置页不丢最后一次滑杆位置。
        // 逐条隔离异常：一条写入失败不得连累其余待写项被丢弃
        val pending = settingsPendingWrites.values.toList()
        settingsPendingWrites.clear()
        if (pending.isNotEmpty()) {
            val flushJob = flushScope.launch {
                pending.forEach { write ->
                    try {
                        write()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("SettingsViewModel", "flush pending write failed", e)
                    }
                }
            }
            flushJob.invokeOnCompletion { flushScope.cancel() }
        } else {
            flushScope.cancel()
        }
    }

    init {
        // 加载阅读设置
        viewModelScope.launch {
            try {
                combine(
                    settingsRepository.getFontSize(),
                    settingsRepository.getRsvpSpeed(),
                    settingsRepository.getTheme(),
                    settingsRepository.getDarkMode(),
                    settingsRepository.getNotifications(),
                ) { fontSize, speed, theme, darkMode, notifications ->
                    _uiState.update {
                        it.copy(
                            fontSize = fontSize,
                            rsvpSpeed = speed,
                            theme = theme,
                            darkMode = darkMode,
                            notifications = notifications,
                        )
                    }
                }.collect()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "settings combine failed", e)
            }
        }

        viewModelScope.launch {
            try {
                settingsRepository.getCollinsHighlight().collect { collinsHighlight ->
                    _uiState.update { it.copy(collinsHighlight = collinsHighlight) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "collins collect failed", e)
            }
        }

        viewModelScope.launch {
            try {
                settingsRepository.getNotificationDownloadProgress().collect { enabled ->
                    _uiState.update { it.copy(notificationDownloadProgress = enabled) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "download progress pref collect failed", e)
            }
        }

        viewModelScope.launch {
            try {
                settingsRepository.getNotificationDownloadComplete().collect { enabled ->
                    _uiState.update { it.copy(notificationDownloadComplete = enabled) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "download complete pref collect failed", e)
            }
        }

        // 加载统计数据
        viewModelScope.launch {
            try {
                val allStats = readingStatsDao.getAllStats()
                val streak = calculateStreak(allStats)
                _uiState.update { it.copy(streakDays = streak) }
            } catch (_: Exception) { /* DB empty */ }
        }

        viewModelScope.launch {
            try {
                vocabularyRepository.getTotalCount().collect { count ->
                    _uiState.update { it.copy(totalWords = count) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "vocab count collect failed", e)
            }
        }

        // 内置 TTS 状态：模型信息 + 下载进度 + 引擎状态
        viewModelScope.launch {
            try {
                embeddedTts.state.collect { state ->
                    _uiState.update {
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
        viewModelScope.launch {
            try {
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
                    _uiState.update {
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
        refreshCacheSize()
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
            val model = embeddedTts.getCurrentModelInfo()
            // isModelDownloaded 做多文件存在性检查（含 .complete 标记），
            // 属于磁盘遍历：不得在 Main 调度器上跑
            val downloaded = withContext(Dispatchers.IO) {
                embeddedTts.isModelDownloaded(model)
            }
            _uiState.update {
                it.copy(
                    embeddedModelName = model.displayName,
                    embeddedModelSizeText = formatBytes(model.sizeBytes),
                    embeddedModelDownloaded = downloaded,
                )
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        return "%.0f MB".format(kb / 1024.0)
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

    fun setFontSize(size: Int) {
        _uiState.update { it.copy(fontSize = size) }
        persistSettingDebounced("fontSize") { settingsRepository.setFontSize(size) }
    }

    fun setRsvpSpeed(speed: Int) {
        _uiState.update { it.copy(rsvpSpeed = speed) }
        persistSettingDebounced("rsvpSpeed") { settingsRepository.setRsvpSpeed(speed) }
    }

    fun setTheme(theme: ReadingTheme) {
        viewModelScope.launch { settingsRepository.setTheme(theme) }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDarkMode(enabled) }
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

    fun setCollinsHighlight(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCollinsHighlight(enabled) }
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
                // 先取消防抖窗口内的待写项：否则拖过滑杆立刻重置时，
                // 300ms 内排队的旧值会在 clearAll 之后落盘，设置"复活"
                settingsPersistJobs.values.forEach { it.cancel() }
                settingsPersistJobs.clear()
                settingsPendingWrites.clear()
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

    private companion object {
        // 与阅读器设置弹窗同款窗口（ReaderViewModel.SETTINGS_PERSIST_DEBOUNCE_MS）
        const val SETTINGS_PERSIST_DEBOUNCE_MS = 300L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToDictionaryManager: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 版本号从包信息动态取：buildConfig 未开启，写死字符串会随发布漂移。
    // 此前页脚固定 "v1.9.0"，用户看到的版本与实际安装包不一致
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "dev"
        } catch (_: Exception) {
            "dev"
        }
    }

    // Android 13+ 通知权限申请
    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.setNotifications(true)
        } else {
            scope.launch { snackbarHostState.showSnackbar("通知权限被拒绝，无法发送提醒") }
        }
    }

    // 文件选择器：用于导入数据
    val importFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        uri?.let {
            // openInputStream 可能在选完文件后被拒（文档删除/权限失效），
            // 不能裸奔在回调里；拷贝放 IO 线程，大备份文件不冻 UI
            scope.launch {
                val tempFile = java.io.File(context.cacheDir, "import_temp.json")
                try {
                    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val inputStream = context.contentResolver.openInputStream(it)
                            ?: return@withContext false
                        inputStream.use { input ->
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        true
                    }
                    if (ok) {
                        viewModel.importFromFile(tempFile)
                    } else {
                        viewModel.showSnackbarMessage("无法读取所选文件")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e

                } catch (e: Exception) {
                    viewModel.showSnackbarMessage("导入失败: ${e.message}")
                } finally {
                    tempFile.delete()
                }
            }
        }
    }

    // Snackbar 直接挂起等待展示结束再清状态：原先 launch+立即 dismiss
    // 会让两条消息并发抢同一个 SnackbarHostState，后到的消息被吞
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // ── Profile Card ──────────────────────────────
            item {
                Spacer(modifier = Modifier.height(8.dp))
                ProfileCard(
                    streakDays = uiState.streakDays,
                    totalWords = uiState.totalWords,
                )
                Spacer(modifier = Modifier.height(28.dp))
            }

            // ── 阅读 ──────────────────────────────────
            item {
                SettingsSectionTitle("阅读")
            }
            item {
                SettingsListCard {
                    SettingRow(
                        icon = Icons.Default.FormatSize,
                        iconBg = PrimaryLight,
                        iconColor = Primary,
                        title = "默认字体大小",
                        subtitle = "${uiState.fontSize}sp",
                    )
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Slider(
                            value = uiState.fontSize.toFloat(),
                            onValueChange = { viewModel.setFontSize(it.toInt()) },
                            valueRange = 12f..32f,
                            steps = 19,
                            colors = SliderDefaults.colors(
                                thumbColor = Primary,
                                activeTrackColor = Primary,
                            ),
                        )
                    }

                    Divider(modifier = Modifier.padding(horizontal = 20.dp))

                    SettingRow(
                        icon = Icons.Default.VolumeUp,
                        iconBg = PrimaryLight,
                        iconColor = Info,
                        title = "RSVP 默认速度",
                        subtitle = "${uiState.rsvpSpeed} 字/分钟",
                    )
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Slider(
                            value = uiState.rsvpSpeed.toFloat(),
                            onValueChange = { viewModel.setRsvpSpeed(it.toInt()) },
                            valueRange = 100f..800f,
                            steps = 13,
                            colors = SliderDefaults.colors(
                                thumbColor = Info,
                                activeTrackColor = Info,
                            ),
                        )
                    }

                    Divider(modifier = Modifier.padding(horizontal = 20.dp))

                    SettingRow(
                        icon = Icons.Default.Visibility,
                        iconBg = SuccessBg,
                        iconColor = Accent,
                        title = "阅读主题",
                        subtitle = uiState.theme.displayName,
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.padding(horizontal = 0.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ReadingTheme.entries.forEach { theme ->
                                FilterChip(
                                    selected = theme == uiState.theme,
                                    onClick = { viewModel.setTheme(theme) },
                                    label = { Text(theme.displayName) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Primary.copy(alpha = 0.12f),
                                        selectedLabelColor = Primary,
                                    ),
                                )
                            }
                        }
                    }

                    Divider(modifier = Modifier.padding(horizontal = 20.dp))

                    SettingRowClickable(
                        icon = Icons.Default.LibraryBooks,
                        iconBg = PrimaryLight,
                        iconColor = Primary,
                        title = "词典管理",
                        subtitle = "下载分级词典（四级/六级/考研/托福/GRE/雅思）",
                        onClick = onNavigateToDictionaryManager,
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── 语音 ──────────────────────────────────
            // 内置 TTS（sherpa-onnx）下载/管理入口。
            // 国产手机系统 TTS 不可用时，这是唯一可用路径，必须在设置里暴露独立入口。
            item {
                SettingsSectionTitle("语音")
            }
            item {
                SettingsListCard {
                    SettingRow(
                        icon = Icons.Default.RecordVoiceOver,
                        iconBg = PrimaryLight,
                        iconColor = Primary,
                        title = "内置语音引擎",
                        subtitle = uiState.embeddedModelName,
                    )
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        when {
                            uiState.embeddedDownloading || uiState.embeddedInitializing -> {
                                LinearProgressIndicator(
                                    progress = uiState.embeddedDownloadProgress,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = uiState.embeddedDownloadStage.ifEmpty {
                                        if (uiState.embeddedInitializing) "初始化中..."
                                        else "下载中 ${(uiState.embeddedDownloadProgress * 100).toInt()}%"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            uiState.embeddedModelDownloaded && uiState.embeddedReady -> {
                                Text(
                                    text = "✅ 已下载并启用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            uiState.embeddedModelDownloaded -> {
                                Text(
                                    text = "已下载（未启用）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> {
                                Text(
                                    text = "未下载（约 ${uiState.embeddedModelSizeText}）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Divider(modifier = Modifier.padding(horizontal = 20.dp))

                    if (uiState.embeddedDownloading || uiState.embeddedInitializing) {
                        // 阶段细分（issue 1.1）：解压/初始化阶段不再显示
                        // "正在下载…请保持网络连接"的自相矛盾文案
                        val stage = uiState.embeddedDownloadStage
                        val isExtracting = stage.contains("解压")
                        SettingRow(
                            icon = Icons.Default.Downloading,
                            iconBg = SurfaceSecondary,
                            iconColor = OnSurfaceTertiary,
                            title = when {
                                uiState.embeddedInitializing -> "正在初始化..."
                                isExtracting -> "正在解压..."
                                else -> "正在下载..."
                            },
                            subtitle = when {
                                // 解压/初始化是纯本地操作，网络提示反而误导
                                isExtracting || uiState.embeddedInitializing ->
                                    stage.ifEmpty { "无需联网，请稍候" }
                                else -> "请保持网络连接"
                            },
                        )
                    } else if (!uiState.embeddedModelDownloaded) {
                        SettingRowClickable(
                            icon = Icons.Default.Download,
                            iconBg = PrimaryLight,
                            iconColor = Primary,
                            title = "下载内置语音模型",
                            subtitle = "完全离线，不依赖系统 TTS",
                            onClick = { viewModel.downloadEmbeddedTts() },
                        )
                    } else {
                        SettingRowClickable(
                            icon = Icons.Default.Delete,
                            iconBg = SurfaceSecondary,
                            iconColor = OnSurfaceTertiary,
                            title = "删除语音模型",
                            subtitle = "释放 ${uiState.embeddedModelSizeText} 空间",
                            onClick = { viewModel.deleteEmbeddedTts() },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── 通知偏好 ────────────────────────────────
            item {
                SettingsSectionTitle("通知偏好")
            }
            item {
                SettingsListCard {
                    SettingRowToggle(
                        icon = Icons.Default.Notifications,
                        iconBg = SuccessBg,
                        iconColor = Accent,
                        title = "复习提醒",
                        checked = uiState.notifications,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                val granted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                                        android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    // issue 5.3：区分"暂拒（可解释）"与"永久拒（don't ask again）"。
                                    // 永久拒后再 launch 也只会被系统路由到设置页，不如直接跳系统通知设置
                                    val activity = context as? android.app.Activity
                                    val rationale = activity != null &&
                                        androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                                            activity, android.Manifest.permission.POST_NOTIFICATIONS,
                                        )
                                    if (rationale) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("开启通知需要授予通知权限，用于每日复习提醒")
                                        }
                                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        openAppNotificationSettings(context)
                                        scope.launch {
                                            snackbarHostState.showSnackbar("已为你打开系统通知设置，请在设置中允许通知后重试")
                                        }
                                    }
                                    return@SettingRowToggle
                                }
                            }
                            viewModel.setNotifications(enabled)
                        },
                    )
                    Divider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowToggle(
                        icon = Icons.Default.Download,
                        iconBg = PrimaryLight,
                        iconColor = Primary,
                        title = "下载进度提醒",
                        checked = uiState.notificationDownloadProgress,
                        onCheckedChange = viewModel::setNotificationDownloadProgress,
                    )
                    Divider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowToggle(
                        icon = Icons.Default.CheckCircle,
                        iconBg = SuccessBg,
                        iconColor = Accent,
                        title = "下载完成提醒",
                        checked = uiState.notificationDownloadComplete,
                        onCheckedChange = viewModel::setNotificationDownloadComplete,
                    )
                    Divider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowClickable(
                        icon = Icons.Default.Settings,
                        iconBg = SurfaceSecondary,
                        iconColor = OnSurfaceTertiary,
                        title = "去系统通知设置",
                        subtitle = "管理应用的通知权限与分类",
                        onClick = { openAppNotificationSettings(context) },
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── 数据 ──────────────────────────────────
            item {
                SettingsSectionTitle("数据")
            }
            item {
                SettingsListCard {
                    SettingRowClickable(
                        icon = Icons.Default.Download,
                        iconBg = PrimaryLight,
                        iconColor = Primary,
                        title = "导出数据",
                        subtitle = if (uiState.isExporting) "导出中..." else "导出词汇和阅读数据",
                        onClick = { viewModel.exportData() },
                    )
                    Divider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowClickable(
                        icon = Icons.Default.Upload,
                        iconBg = PrimaryLight,
                        iconColor = Primary,
                        title = "导入数据",
                        subtitle = if (uiState.isImporting) "导入中..." else "从备份文件导入词汇",
                        onClick = { importFilePicker.launch(arrayOf("application/json", "text/plain")) },
                    )
                    Divider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowClickable(
                        icon = Icons.Default.Delete,
                        iconBg = SurfaceSecondary,
                        iconColor = OnSurfaceTertiary,
                        title = "清除缓存",
                        subtitle = if (uiState.isClearing) "清除中..." else {
                            String.format(java.util.Locale.getDefault(), "%.1f MB", uiState.cacheSizeMb)
                        },
                        onClick = { viewModel.clearCache() },
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── 危险区域 ────────────────────────────────
            item {
                SettingsSectionTitle("危险区域")
            }
            item {
                SettingsListCard {
                    SettingRowClickable(
                        icon = Icons.Default.Refresh,
                        iconBg = ErrorBg,
                        iconColor = Error,
                        title = "恢复默认设置",
                        subtitle = "清除所有设置（不影响数据）",
                        titleColor = Error,
                        onClick = { viewModel.resetToDefaults() },
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── 版本 ──────────────────────────────────
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "听阅 EareyeReading · v$versionName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

// ── 组件 ─────────────────────────────────────────────

/**
 * issue 5.3：跳转到系统通知设置页（POST_NOTIFICATIONS 被"don't ask again"永久拒后，
 * 应用内再弹权限框也只会被系统静默路由，唯一的恢复入口就是系统设置）。
 */
private fun openAppNotificationSettings(context: Context) {
    try {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.util.Log.w("SettingsScreen", "open notification settings failed", e)
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

@Composable
private fun ProfileCard(
    streakDays: Int,
    totalWords: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar
            Surface(
                modifier = Modifier.size(60.dp),
                shape = RoundedCornerShape(30.dp),
                color = Primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "K",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "学习者",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "共学习 $totalWords 词",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = WarningBg,
                    ) {
                        Text(
                            "🔥 $streakDays 天连胜",
                            style = MaterialTheme.typography.labelSmall,
                            color = Warning,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            "📚 $totalWords 词",
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        style = SectionTitle,
        modifier = Modifier.padding(vertical = 10.dp),
    )
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun SettingsListCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    iconBg: Color,
    iconColor: Color,
    title: String,
    subtitle: String,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: @Composable ColumnScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(9.dp),
            color = iconBg,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = titleColor,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column { trailing() }
        }
    }
}

@Composable
private fun SettingRowToggle(
    icon: ImageVector,
    iconBg: Color,
    iconColor: Color,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(9.dp),
            color = iconBg,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = SurfaceHover,
            ),
        )
    }
}

@Composable
private fun SettingRowClickable(
    icon: ImageVector,
    iconBg: Color,
    iconColor: Color,
    title: String,
    subtitle: String,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(9.dp),
            color = iconBg,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = titleColor,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
