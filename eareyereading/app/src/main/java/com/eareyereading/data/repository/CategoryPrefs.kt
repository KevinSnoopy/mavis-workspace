package com.eareyereading.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 分类元数据持久化（v2 分类自定义，SPEC §4.9）
 *
 * 设计判断：
 * - Book.category 保持 String 不动（零 DB 迁移）；「图标 + 颜色」作为
 *   分类的**元数据**存 DataStore，UI 层按 name 合成完整 Category。
 * - 复用全局 DataStore<Preferences>（与 SettingsRepositoryImpl 同实例），
 *   单键 JSON 存储，Gson 序列化（项目已有依赖）。
 * - 用户自建但还没有书挂上的分类也存这里：meta 存在即分类存在，
 *   避免在 Book 表插占位行。
 */
@Singleton
class CategoryPrefs @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /** 单条分类元数据（DTO：color 用 Long 存 ARGB，icon 用枚举 name） */
    data class Meta(
        val icon: String = "BOOK",
        val color: Long = 0xFF0E6B5E,
        val order: Int = 0,
    )

    private companion object {
        val KEY = stringPreferencesKey("category_meta_map_v1")
        val gson = Gson()
        val mapType = object : TypeToken<Map<String, Meta>>() {}.type
    }

    /** 分类元数据流：name → Meta（空 map 表示全用派生默认值） */
    val metaFlow: Flow<Map<String, Meta>> = dataStore.data.map { prefs ->
        val json = prefs[KEY] ?: return@map emptyMap()
        runCatching { gson.fromJson<Map<String, Meta>>(json, mapType) }
            .getOrDefault(emptyMap())
    }

    /** 保存/更新分类元数据；分类名是主键，重名即编辑 */
    suspend fun setMeta(name: String, meta: Meta) {
        dataStore.edit { prefs ->
            val current = readMap(prefs)
            prefs[KEY] = gson.toJson(current + (name to meta))
        }
    }

    /** 删除分类元数据：书籍的 category 字符串保留，UI 回退到派生默认显示 */
    suspend fun removeMeta(name: String) {
        dataStore.edit { prefs ->
            val current = readMap(prefs)
            prefs[KEY] = gson.toJson(current - name)
        }
    }

    /** 拖动排序：批量写入 order（一次 edit 原子生效，避免半套 order 状态） */
    suspend fun setOrders(orders: Map<String, Int>) {
        dataStore.edit { prefs ->
            val current = readMap(prefs)
            val updated = current.mapValues { (name, meta) ->
                if (orders.containsKey(name)) meta.copy(order = orders.getValue(name)) else meta
            }
            prefs[KEY] = gson.toJson(updated)
        }
    }

    private fun readMap(prefs: Preferences): Map<String, Meta> {
        val json = prefs[KEY] ?: return emptyMap()
        return runCatching { gson.fromJson<Map<String, Meta>>(json, mapType) }
            .getOrDefault(emptyMap())
    }
}
