package com.eareyereading.util

import android.util.Log
import com.eareyereading.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * AI（LLM）翻译通道的准入与熔断。
 *
 * 从 [TranslationHelper] 抽出的单一职责类（SRP）：把"AI 通道是否可用"
 * （配置读取、启用开关、熔断状态）从翻译调度中分离。翻译链路只需问
 * [tryTranslate]——它内部完成「读配置 → 送翻 → 维护熔断计数」。
 *
 * 熔断语义：连续失败后 [LlmCircuitBreaker.isOpen] 为真，直接返回 null
 * 让调用方回退机翻，避免每条译文都白等一次网络超时。
 */
@Singleton
class LlmTranslationGate @Inject constructor(
    private val llmTranslator: LlmTranslator,
    private val settingsRepository: SettingsRepository,
) {
    private val circuit = LlmCircuitBreaker()

    /**
     * 读取 LLM 翻译配置；[checkEnabled]=false 时只看 Key（设置页
     * "测试翻译"在开关打开前就要能校验 Key 是否可用）。
     * DataStore 首次加载后常驻内存，first() 每次调用开销可忽略。
     */
    suspend fun readConfig(checkEnabled: Boolean): LlmTranslator.Config? {
        return try {
            if (checkEnabled && !settingsRepository.getLlmTranslateEnabled().first()) return null
            val apiKey = settingsRepository.getLlmApiKey().first()
            if (apiKey.isBlank()) return null
            LlmTranslator.Config(
                baseUrl = settingsRepository.getLlmBaseUrl().first(),
                apiKey = apiKey,
                model = settingsRepository.getLlmModel().first(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "read llm config failed: ${e.message}")
            null
        }
    }

    /** 当前是否已配置并启用 AI 翻译（用于缓存键分层判断）。 */
    suspend fun isEnabled(): Boolean = readConfig(checkEnabled = true) != null

    /**
     * 带熔断的 LLM 翻译尝试：成功/失败都维护熔断计数，失败返回 null
     * 由调用方回退机翻。
     */
    suspend fun tryTranslate(text: String, sourceLang: String, targetLang: String): String? {
        if (circuit.isOpen()) return null
        val config = readConfig(checkEnabled = true) ?: return null
        val result = llmTranslator.translate(text, sourceLang, targetLang, config)
        if (result == null) circuit.recordFailure() else circuit.recordSuccess()
        return result
    }

    /**
     * 设置页"测试翻译"：无视开关，直接以当前 Key/端点/模型送翻一句样例，
     * 用于配置期校验（非 null 即 Key 可用）。不走任何缓存与回退链。
     */
    suspend fun testTranslate(
        sample: String = "The old man sat by the harbor, watching the boats drift home as the sun melted into the sea.",
    ): String? {
        val config = readConfig(checkEnabled = false) ?: return null
        return llmTranslator.translate(sample, "en", "zh", config)
    }

    private companion object {
        const val TAG = "LlmTranslationGate"
    }
}
