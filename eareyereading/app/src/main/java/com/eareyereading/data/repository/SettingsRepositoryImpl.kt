package com.eareyereading.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.eareyereading.domain.model.ReadingTheme
import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.util.ReminderPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    companion object {
        val RSVP_SPEED = intPreferencesKey("rsvp_speed")
        val FONT_SIZE = intPreferencesKey("font_size")
        val THEME = stringPreferencesKey("reading_theme")
        val LANGUAGE = stringPreferencesKey("app_language")
        val TRANSLATION_ALPHA = floatPreferencesKey("translation_alpha")
        val RSVP_STRENGTH = intPreferencesKey("rsvp_strength")      // 1-5 强度
        // rsvp_interval（加粗间隔）已移除：持久化+展示齐全但从未被消费的死设置，
        // 语义无产品定义。旧版本写入的 rsvp_interval 键留在文件里不被读取，无害
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val NOTIFICATION_DOWNLOAD_PROGRESS = booleanPreferencesKey("notification_download_progress")
        val NOTIFICATION_DOWNLOAD_COMPLETE = booleanPreferencesKey("notification_download_complete")
        val COLLINS_HIGHLIGHT = booleanPreferencesKey("collins_highlight")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
    }

    override fun getRsvpSpeed(): Flow<Int> =
        dataStore.data.map { it[RSVP_SPEED] ?: 300 }

    override fun getFontSize(): Flow<Int> =
        dataStore.data.map { it[FONT_SIZE] ?: 18 }

    override fun getTheme(): Flow<ReadingTheme> =
        dataStore.data.map {
            val value = it[THEME] ?: "light"
            ReadingTheme.entries.find { t -> t.value == value } ?: ReadingTheme.LIGHT
        }

    override fun getLanguage(): Flow<String> =
        dataStore.data.map { it[LANGUAGE] ?: "en" }

    override suspend fun setRsvpSpeed(speed: Int) {
        dataStore.edit { it[RSVP_SPEED] = speed }
    }

    override suspend fun setFontSize(size: Int) {
        dataStore.edit { it[FONT_SIZE] = size }
    }

    override suspend fun setTheme(theme: ReadingTheme) {
        dataStore.edit { it[THEME] = theme.value }
    }

    override fun getTranslationAlpha(): Flow<Float> =
        dataStore.data.map { it[TRANSLATION_ALPHA] ?: 0.85f }

    override suspend fun setTranslationAlpha(alpha: Float) {
        dataStore.edit { it[TRANSLATION_ALPHA] = alpha }
    }

    override fun getRsvpStrength(): Flow<Int> =
        dataStore.data.map { it[RSVP_STRENGTH] ?: 3 }

    override suspend fun setRsvpStrength(strength: Int) {
        dataStore.edit { it[RSVP_STRENGTH] = strength.coerceIn(1, 5) }
    }

    override fun getDarkMode(): Flow<Boolean> =
        dataStore.data.map { it[DARK_MODE] ?: false }

    override fun getNotifications(): Flow<Boolean> =
        dataStore.data.map { it[NOTIFICATIONS] ?: true }

    override fun getNotificationDownloadProgress(): Flow<Boolean> =
        dataStore.data.map { it[NOTIFICATION_DOWNLOAD_PROGRESS] ?: true }

    override fun getNotificationDownloadComplete(): Flow<Boolean> =
        dataStore.data.map { it[NOTIFICATION_DOWNLOAD_COMPLETE] ?: true }

    override fun getCollinsHighlight(): Flow<Boolean> =
        dataStore.data.map { it[COLLINS_HIGHLIGHT] ?: true }

    override fun getTtsSpeed(): Flow<Float> =
        dataStore.data.map { it[TTS_SPEED] ?: 1.0f }

    override suspend fun setDarkMode(enabled: Boolean) {
        dataStore.edit { it[DARK_MODE] = enabled }
    }

    override suspend fun setNotifications(enabled: Boolean) {
        dataStore.edit { it[NOTIFICATIONS] = enabled }
        // 同步镜像：开机/时区广播里的 Receiver 无法挂起读 DataStore
        ReminderPrefs.setEnabled(context, enabled)
    }

    override suspend fun setNotificationDownloadProgress(enabled: Boolean) {
        dataStore.edit { it[NOTIFICATION_DOWNLOAD_PROGRESS] = enabled }
    }

    override suspend fun setNotificationDownloadComplete(enabled: Boolean) {
        dataStore.edit { it[NOTIFICATION_DOWNLOAD_COMPLETE] = enabled }
    }

    override suspend fun setCollinsHighlight(enabled: Boolean) {
        dataStore.edit { it[COLLINS_HIGHLIGHT] = enabled }
    }

    override suspend fun setTtsSpeed(speed: Float) {
        // 限幅：0.5x - 2.0x，避免 TTS 引擎收到极端值导致崩溃或无输出
        dataStore.edit { it[TTS_SPEED] = speed.coerceIn(0.5f, 2.0f) }
    }

    override suspend fun clearAll() {
        dataStore.edit { it.clear() }
        // clear 后 getNotifications 回退默认 true，镜像保持一致
        ReminderPrefs.setEnabled(context, true)
    }
}
