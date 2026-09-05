package com.eareyereading.tts

import android.content.Context

/**
 * TTS 模型偏好的持久化：用户选中的模型 id 与音色 sid。
 *
 * 纯 SharedPreferences 存取，从 [EmbeddedTtsEngine] 抽出（SRP）：
 * 偏好读写与引擎的合成/播放/初始化生命周期零耦合。
 * 模型解析（用户选择 > 已加载 > 兜底）仍留在引擎——它依赖加载态。
 */
internal class TtsModelPreferences(context: Context) {
    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 用户当前选中的模型 ID（默认 Piper）。 */
    fun selectedModelId(): String =
        prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID

    fun setSelectedModelId(id: String) {
        prefs.edit().putString(KEY_SELECTED_MODEL, id).apply()
    }

    /** 指定模型下用户选中的音色 sid（仅 Kokoro 有意义；越界/未设置回退 0）。 */
    fun selectedSid(model: ModelInfo): Int {
        val saved = prefs.getInt(KEY_SELECTED_VOICE_PREFIX + model.id, 0)
        return if (saved in 0 until KOKORO_VOICES.size) saved else 0
    }

    fun setSelectedSid(modelId: String, sid: Int) {
        prefs.edit().putInt(KEY_SELECTED_VOICE_PREFIX + modelId, sid).apply()
    }

    private companion object {
        private const val PREFS_NAME = "embedded_tts_prefs"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_SELECTED_VOICE_PREFIX = "selected_voice_"
    }
}
