package com.eareyereading.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 书库视图模式。 */
enum class LibraryViewMode(val value: String) {
    LIST("list"),
    GRID("grid");

    companion object {
        fun fromValue(value: String?): LibraryViewMode =
            entries.firstOrNull { it.value == value } ?: LIST
    }
}

/**
 * 书库排布视图持久化（列表 / 封面网格）。
 *
 * 复用全局 DataStore<Preferences>（与 CategoryPrefs 同实例），
 * 单键字符串存储；非法值容错回 LIST。
 */
@Singleton
class LibraryViewPrefs @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private companion object {
        val KEY = stringPreferencesKey("library_view_mode_v1")
    }

    /** 当前视图模式流（默认列表）。 */
    val viewModeFlow: Flow<LibraryViewMode> = dataStore.data.map { prefs ->
        LibraryViewMode.fromValue(prefs[KEY])
    }

    /** 切换并持久化视图模式。 */
    suspend fun setViewMode(mode: LibraryViewMode) {
        dataStore.edit { prefs -> prefs[KEY] = mode.value }
    }
}
