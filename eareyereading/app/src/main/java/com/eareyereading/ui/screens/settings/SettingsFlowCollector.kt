package com.eareyereading.ui.screens.settings

import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.domain.repository.VocabularyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** AI 翻译配置快照（combine 中转，避免 Any 装箱后强转）。 */
private data class LlmSettingsSnapshot(
    val enabled: Boolean,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
)

/**
 * 设置项 DataStore 流订阅：通知、动态取色、LLM 翻译配置、TTS 引擎配置、
 * 阅读统计与词汇计数。
 *
 * 从 [SettingsViewModel] 抽出的单一职责类（SRP）：所有 DataStore flow 的
 * collect 与 uiState 映射集中在本类，ViewModel 的 init 只需调用 [start]。
 *
 * @param scope ViewModel 协程作用域
 * @param settingsRepository 设置仓库（DataStore 流源）
 * @param readingStatsDao 阅读统计 DAO（加载 streak）
 * @param vocabularyRepository 词汇仓库（加载总词数）
 * @param uiState UI 状态流
 */
internal class SettingsFlowCollector(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val readingStatsDao: ReadingStatsDao,
    private val vocabularyRepository: VocabularyRepository,
    private val uiState: MutableStateFlow<SettingsUiState>,
) {
    /** 启动所有设置项的流收集（在 ViewModel init 中调用一次）。 */
    fun start() {
        // 通知总开关（阅读相关设置——字号/RSVP/主题/衬线等——已全部收敛到
        // 阅读页内的设置弹窗与底栏快捷设置，本页不再重复提供入口）
        scope.launch {
            try {
                settingsRepository.getNotifications().collect { notifications ->
                    uiState.update { it.copy(notifications = notifications) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "notifications collect failed", e)
            }
        }

        scope.launch {
            try {
                settingsRepository.getDynamicColor().collect { dynamicColor ->
                    uiState.update { it.copy(dynamicColor = dynamicColor) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "dynamic color collect failed", e)
            }
        }

        scope.launch {
            try {
                settingsRepository.getNotificationDownloadProgress().collect { enabled ->
                    uiState.update { it.copy(notificationDownloadProgress = enabled) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "download progress pref collect failed", e)
            }
        }

        scope.launch {
            try {
                settingsRepository.getNotificationDownloadComplete().collect { enabled ->
                    uiState.update { it.copy(notificationDownloadComplete = enabled) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "download complete pref collect failed", e)
            }
        }

        // AI 翻译（LLM 通道）配置：四项合成一个流，collect 一处更新
        scope.launch {
            try {
                combine(
                    settingsRepository.getLlmTranslateEnabled(),
                    settingsRepository.getLlmApiKey(),
                    settingsRepository.getLlmBaseUrl(),
                    settingsRepository.getLlmModel(),
                ) { enabled, apiKey, baseUrl, model ->
                    LlmSettingsSnapshot(enabled, apiKey, baseUrl, model)
                }.collect { s ->
                    uiState.update {
                        it.copy(
                            llmTranslateEnabled = s.enabled,
                            llmApiKey = s.apiKey,
                            llmBaseUrl = s.baseUrl,
                            llmModel = s.model,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "llm settings collect failed", e)
            }
        }

        // TTS 引擎类型 + 腾讯云配置
        scope.launch {
            try {
                kotlinx.coroutines.flow.combine(
                    settingsRepository.getTtsEngineType(),
                    settingsRepository.getTencentSecretId(),
                    settingsRepository.getTencentSecretKey(),
                    settingsRepository.getTencentVoiceId(),
                ) { type, sid, skey, vid ->
                    arrayOf(type, sid, skey, vid.toString())
                }.collect { arr ->
                    val type = arr[0]
                    val sid = arr[1]
                    val skey = arr[2]
                    val vid = arr[3].toInt()
                    val voice = com.eareyereading.tts.TENCENT_VOICES.find { it.id == vid }
                    uiState.update {
                        it.copy(
                            ttsEngineType = type,
                            tencentSecretId = sid,
                            tencentSecretKey = skey,
                            tencentVoiceId = vid,
                            tencentVoiceDisplay = voice?.displayName ?: "音色 $vid",
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "tts engine config collect failed", e)
            }
        }

        // 加载统计数据
        scope.launch {
            try {
                val allStats = readingStatsDao.getAllStats()
                val streak = calculateStreak(allStats)
                uiState.update { it.copy(streakDays = streak) }
            } catch (_: Exception) { /* DB empty */ }
        }

        scope.launch {
            try {
                vocabularyRepository.getTotalCount().collect { count ->
                    uiState.update { it.copy(totalWords = count) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "vocab count collect failed", e)
            }
        }
    }

    /** Streak calc converged into ReadingStreak: single-source-of-truth for the
     * calendar-day rule shared by Home/Library/Settings. */
    private fun calculateStreak(stats: List<com.eareyereading.data.local.entity.ReadingStatsEntity>): Int =
        com.eareyereading.util.ReadingStreak.calculate(stats)
}
