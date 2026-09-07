package com.eareyereading.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.data.local.dao.VocabularyDao
import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.domain.repository.VocabularyRepository
import com.eareyereading.tts.EmbeddedTtsEngine
import com.eareyereading.util.NotificationService
import com.eareyereading.util.TtsHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel：DataStore 设置流订阅、内置 TTS 模型状态/下载、
 * LLM 翻译配置、数据导入导出与缓存管理。
 *
 * ── 重构说明（SRP 拆分）──
 * 原本 795 行，混合了设置流订阅、TTS 引擎管理、翻译配置、通知配置、
 * 数据导入导出与 UI 状态管理等多个职责。现已按职责拆分到同包 internal 文件：
 *   - [SettingsFlowCollector]：DataStore 设置流订阅与 uiState 映射
 *   - [SettingsEmbeddedTtsManager]：内置 TTS 模型选择/下载/删除/音色/进度
 *   - [SettingsTtsConfigManager]：TTS 引擎类型与腾讯云凭证配置
 *   - [SettingsLlmConfigManager]：AI 翻译（LLM 通道）配置与测试
 *   - [SettingsNotificationManager]：通知偏好与 Channel 重建
 *   - [SettingsDataPorter]：数据导入/导出与缓存管理（此前已拆出）
 * 本类只保留委托调度与 UI 状态持有，业务行为完全不变。
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
    /** 当前选中模型是否为 Kokoro（Kokoro 已下线，永远 false，保留字段兼容） */
    val embeddedSelectedModelIsKokoro: Boolean = false,
    /** 当前选中音色展示名（Kokoro 已下线，永远空） */
    val embeddedVoiceDisplay: String = "",
    // ── TTS 引擎类型（离线/在线 Edge TTS）──
    /** "embedded"（离线 sherpa-onnx）或 "tencent"（在线腾讯云 TTS） */
    val ttsEngineType: String = "embedded",
    /** 腾讯云 SecretId */
    val tencentSecretId: String = "",
    /** 腾讯云 SecretKey */
    val tencentSecretKey: String = "",
    /** 腾讯云音色 id */
    val tencentVoiceId: Int = 101001,
    /** 腾讯云音色展示名 */
    val tencentVoiceDisplay: String = "",
    // ── AI 翻译（LLM 通道）──
    val llmTranslateEnabled: Boolean = false,
    val llmApiKey: String = "",
    val llmBaseUrl: String = "",
    val llmModel: String = "",
    /** "测试翻译"进行中（防止连点并发请求） */
    val llmTesting: Boolean = false,
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

    // ── 拆分出的职责持有者（SRP）──
    private val dataPorter = SettingsDataPorter(
        context = context,
        vocabularyRepository = vocabularyRepository,
        readingStatsDao = readingStatsDao,
        vocabularyDao = vocabularyDao,
        database = database,
    )
    private val flowCollector = SettingsFlowCollector(
        scope = viewModelScope,
        settingsRepository = settingsRepository,
        readingStatsDao = readingStatsDao,
        vocabularyRepository = vocabularyRepository,
        uiState = _uiState,
    )
    private val embeddedTtsManager = SettingsEmbeddedTtsManager(
        scope = viewModelScope,
        embeddedTts = embeddedTts,
        ttsHelper = ttsHelper,
        uiState = _uiState,
    )
    private val ttsConfigManager = SettingsTtsConfigManager(
        scope = viewModelScope,
        settingsRepository = settingsRepository,
        ttsHelper = ttsHelper,
        uiState = _uiState,
    )
    private val llmConfigManager = SettingsLlmConfigManager(
        scope = viewModelScope,
        settingsRepository = settingsRepository,
        translationHelper = translationHelper,
        uiState = _uiState,
    )
    private val notificationManager = SettingsNotificationManager(
        scope = viewModelScope,
        settingsRepository = settingsRepository,
        notificationService = notificationService,
        context = context,
        uiState = _uiState,
    )

    override fun onCleared() {
        super.onCleared()
        // 阅读类滑杆设置已收敛到阅读页，本页不再有防抖待写项，
        // 无需退出冲刷逻辑
    }

    init {
        flowCollector.start()
        embeddedTtsManager.startCollecting()
        refreshCacheSize()
    }

    /** 异步统计缓存目录大小（磁盘遍历不能放在 Compose 组合期做）。 */
    private fun refreshCacheSize() {
        viewModelScope.launch {
            val sizeMb = try {
                dataPorter.cacheSizeMb()
            } catch (_: Exception) {
                0.0
            }
            _uiState.update { it.copy(cacheSizeMb = sizeMb) }
        }
    }

    // ── TTS 引擎类型与腾讯云配置（委托给 SettingsTtsConfigManager）──

    /** 切换 TTS 引擎类型（"embedded" / "tencent"） */
    fun setTtsEngineType(type: String) = ttsConfigManager.setTtsEngineType(type)

    /** 设置腾讯云凭证 */
    fun setTencentCredentials(id: String, key: String) = ttsConfigManager.setTencentCredentials(id, key)

    /** 切换腾讯云音色 */
    fun setTencentVoiceId(voiceId: Int) = ttsConfigManager.setTencentVoiceId(voiceId)

    // ── 内置 TTS 模型管理（委托给 SettingsEmbeddedTtsManager）──

    fun setEmbeddedModel(id: String) = embeddedTtsManager.setEmbeddedModel(id)

    fun selectEmbeddedVoice(sid: Int) = embeddedTtsManager.selectEmbeddedVoice(sid)

    /** 下载内置 TTS 模型（带进度），下载完成后自动初始化。 */
    fun downloadEmbeddedTts() = embeddedTtsManager.downloadEmbeddedTts()

    /** 删除已下载的内置 TTS 模型（释放空间）。 */
    fun deleteEmbeddedTts() = embeddedTtsManager.deleteEmbeddedTts()

    // ── 外观 ─────────────────────────────────

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

    // ── 通知配置（委托给 SettingsNotificationManager）──

    fun setNotifications(enabled: Boolean) = notificationManager.setNotifications(enabled)

    fun setNotificationDownloadProgress(enabled: Boolean) = notificationManager.setNotificationDownloadProgress(enabled)

    fun setNotificationDownloadComplete(enabled: Boolean) = notificationManager.setNotificationDownloadComplete(enabled)

    // ── AI 翻译（LLM 通道）配置（委托给 SettingsLlmConfigManager）──

    fun setLlmTranslateEnabled(enabled: Boolean) = llmConfigManager.setLlmTranslateEnabled(enabled)

    fun setLlmApiKey(apiKey: String) = llmConfigManager.setLlmApiKey(apiKey)

    fun setLlmBaseUrl(baseUrl: String) = llmConfigManager.setLlmBaseUrl(baseUrl)

    fun setLlmModel(model: String) = llmConfigManager.setLlmModel(model)

    /** 应用服务商预设（端点 + 模型一键切换）。 */
    fun applyLlmPreset(baseUrl: String, model: String) = llmConfigManager.applyLlmPreset(baseUrl, model)

    /** 测试翻译：无视开关直接用当前 Key 送翻一句样例（配置期校验）。 */
    fun testLlmTranslation() = llmConfigManager.testLlmTranslation()

    // ── 数据导入/导出与缓存管理（委托给 SettingsDataPorter）──

    fun exportData() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isExporting = true) }
                val result = dataPorter.export()
                _uiState.update {
                    it.copy(isExporting = false, snackbarMessage = result.message)
                }
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
            val r = dataPorter.importFromFile(file)
            val skipNote = if (r.skipped > 0) "（${r.skipped} 条已有词汇跳过，保留本地进度）" else ""
            val statsNote = if (r.statsImported > 0 || r.statsSkipped > 0) {
                "，阅读统计导入 ${r.statsImported} 条" + if (r.statsSkipped > 0) "、跳过 ${r.statsSkipped} 条" else ""
            } else ""
            val reviewNote = if (r.reviewImported > 0) "，复习记录导入 ${r.reviewImported} 条" else ""
            _uiState.update { it.copy(isImporting = false, snackbarMessage = "已导入 ${r.imported} 条词汇$skipNote$statsNote$reviewNote") }
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
                dataPorter.clearCache()
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
                notificationManager.scheduleReviewReminder()
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
