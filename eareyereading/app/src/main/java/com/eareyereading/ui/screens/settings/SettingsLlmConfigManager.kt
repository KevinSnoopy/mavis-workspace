package com.eareyereading.ui.screens.settings

import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.util.TranslationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AI 翻译（LLM 通道）配置管理：开关、API Key、端点、模型、预设与测试。
 *
 * 从 [SettingsViewModel] 抽出的单一职责类（SRP）：LLM 配置的持久化与
 * 翻译测试逻辑集中在本类，ViewModel 只负责委托。
 *
 * @param scope ViewModel 协程作用域
 * @param settingsRepository 设置仓库（DataStore 持久化）
 * @param translationHelper 翻译助手（测试翻译用）
 * @param uiState UI 状态流
 */
internal class SettingsLlmConfigManager(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val translationHelper: TranslationHelper,
    private val uiState: MutableStateFlow<SettingsUiState>,
) {
    fun setLlmTranslateEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepository.setLlmTranslateEnabled(enabled)
        }
    }

    fun setLlmApiKey(apiKey: String) {
        scope.launch {
            settingsRepository.setLlmApiKey(apiKey)
        }
    }

    fun setLlmBaseUrl(baseUrl: String) {
        scope.launch {
            settingsRepository.setLlmBaseUrl(baseUrl)
        }
    }

    fun setLlmModel(model: String) {
        scope.launch {
            settingsRepository.setLlmModel(model)
        }
    }

    /** 应用服务商预设（端点 + 模型一键切换）。 */
    fun applyLlmPreset(baseUrl: String, model: String) {
        scope.launch {
            settingsRepository.setLlmBaseUrl(baseUrl)
            settingsRepository.setLlmModel(model)
        }
    }

    /** 测试翻译：无视开关直接用当前 Key 送翻一句样例（配置期校验）。 */
    fun testLlmTranslation() {
        if (uiState.value.llmTesting) return
        if (uiState.value.llmApiKey.isBlank()) {
            uiState.update { it.copy(snackbarMessage = "请先配置 API Key") }
            return
        }
        scope.launch {
            uiState.update { it.copy(llmTesting = true) }
            try {
                val result = translationHelper.testLlmTranslation()
                uiState.update {
                    it.copy(
                        snackbarMessage = if (result != null) "测试成功：$result" else "测试失败：请检查 API Key、端点与网络",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState.update { it.copy(snackbarMessage = "测试失败: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                uiState.update { it.copy(llmTesting = false) }
            }
        }
    }
}
