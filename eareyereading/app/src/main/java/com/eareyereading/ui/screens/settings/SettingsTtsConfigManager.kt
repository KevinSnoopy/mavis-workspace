package com.eareyereading.ui.screens.settings

import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.util.TtsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * TTS 引擎类型与腾讯云凭证配置管理。
 *
 * 从 [SettingsViewModel] 抽出的单一职责类（SRP）：引擎类型切换、
 * 腾讯云 SecretId/SecretKey/音色 id 的持久化与 TtsHelper 刷新集中在本类。
 *
 * @param scope ViewModel 协程作用域
 * @param settingsRepository 设置仓库（DataStore 持久化）
 * @param ttsHelper TTS 协调器（切换引擎时停朗读 + 刷新引擎类型）
 * @param uiState UI 状态流
 */
internal class SettingsTtsConfigManager(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val ttsHelper: TtsHelper,
    private val uiState: MutableStateFlow<SettingsUiState>,
) {
    /** 切换 TTS 引擎类型（"embedded" / "tencent"） */
    fun setTtsEngineType(type: String) {
        scope.launch {
            ttsHelper.stop()
            settingsRepository.setTtsEngineType(type)
            ttsHelper.refreshEngineType()
            uiState.update {
                it.copy(
                    ttsEngineType = type,
                    snackbarMessage = if (type == "tencent") "已切换到在线腾讯云 TTS" else "已切换到离线 TTS",
                )
            }
        }
    }

    /** 设置腾讯云凭证 */
    fun setTencentCredentials(id: String, key: String) {
        scope.launch {
            settingsRepository.setTencentSecretId(id)
            settingsRepository.setTencentSecretKey(key)
            ttsHelper.refreshEngineType()
            uiState.update { it.copy(snackbarMessage = "腾讯云凭证已保存") }
        }
    }

    /** 切换腾讯云音色 */
    fun setTencentVoiceId(voiceId: Int) {
        scope.launch {
            settingsRepository.setTencentVoiceId(voiceId)
            ttsHelper.refreshEngineType()
        }
    }
}
