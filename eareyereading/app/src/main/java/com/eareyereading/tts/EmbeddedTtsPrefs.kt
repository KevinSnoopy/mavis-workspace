package com.eareyereading.tts

import android.content.Context

/**
 * 内嵌 TTS 模型选择与音色偏好的持久化。
 *
 * 从 [EmbeddedTtsEngine] 抽出的单一职责类（SRP）：引擎主类不再关心
 * 偏好如何存储，只通过本类读写。SharedPreferences 私有文件，无 Android 权限。
 *
 * @param context 应用上下文
 */
internal class EmbeddedTtsPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSelectedModelId(): String =
        prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID

    fun setSelectedModelId(id: String) {
        prefs.edit().putString(KEY_SELECTED_MODEL, id).apply()
    }

    /** 当前模型下用户选中的音色 sid（仅 Kokoro 有意义；越界/未设置回退 0） */
    fun getSelectedSid(model: ModelInfo = getCurrentModelInfo()): Int {
        val saved = prefs.getInt(KEY_SELECTED_VOICE_PREFIX + model.id, 0)
        return if (saved in 0 until KOKORO_VOICES.size) saved else 0
    }

    fun setSelectedSid(modelId: String, sid: Int) {
        prefs.edit().putInt(KEY_SELECTED_VOICE_PREFIX + modelId, sid).apply()
    }

    /** 当前选中模型的音色信息（非 Kokoro 模型返回 null） */
    fun getSelectedVoice(): VoiceInfo? {
        val model = getCurrentModelInfo()
        return if (model.isKokoro) KOKORO_VOICES.getOrNull(getSelectedSid(model)) else null
    }

    fun getCurrentModelInfo(): ModelInfo {
        // firstOrNull 全程兜底：持久化的模型 id 可能已被新版本移除，
        // first{} 会直接抛 NoSuchElementException（且本方法会在 Compose 组合期被调用）
        //
        // 优先级必须是"用户选择 > 已加载模型"：切换模型时 setSelectedModelId 先落盘、
        // currentModelName 还是旧模型——若旧模型优先，setEmbeddedModel 拿到的仍是旧
        // ModelInfo（initialize 快路径直接复用旧实例），设置页单选还会被
        // refreshEmbeddedStatus 翻回旧模型——引擎永远切不过去（2026-09-05 修复）
        return AVAILABLE_MODELS.firstOrNull { it.id == getSelectedModelId() }
            ?: AVAILABLE_MODELS.first()
    }

    companion object {
        private const val PREFS_NAME = "embedded_tts_prefs"
        private const val KEY_SELECTED_MODEL = "selected_model"
        /** 用户选中的音色 sid，按模型分别持久化（Piper 无多音色，仅 Kokoro 使用） */
        private const val KEY_SELECTED_VOICE_PREFIX = "selected_voice_"
    }
}
