package com.eareyereading.data.repository
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName", "EmptyFunctionBlock", "UnusedPrivateProperty")

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.eareyereading.domain.model.ReadingTheme
import com.eareyereading.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    companion object {
        val RSVP_SPEED = intPreferencesKey("rsvp_speed")
        val FONT_SIZE = intPreferencesKey("font_size")
        val THEME = stringPreferencesKey("reading_theme")
        val LANGUAGE = stringPreferencesKey("app_language")
        val TRANSLATION_ALPHA = floatPreferencesKey("translation_alpha")
        val RSVP_STRENGTH = intPreferencesKey("rsvp_strength")      // 1-5 强度
        val RSVP_INTERVAL = intPreferencesKey("rsvp_interval")      // 1-3 间隔
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

    override fun getRsvpInterval(): Flow<Int> =
        dataStore.data.map { it[RSVP_INTERVAL] ?: 1 }

    override suspend fun setRsvpStrength(strength: Int) {
        dataStore.edit { it[RSVP_STRENGTH] = strength.coerceIn(1, 5) }
    }

    override suspend fun setRsvpInterval(interval: Int) {
        dataStore.edit { it[RSVP_INTERVAL] = interval.coerceIn(1, 3) }
    }
}
