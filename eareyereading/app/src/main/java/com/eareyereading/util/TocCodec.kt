package com.eareyereading.util

import com.eareyereading.domain.model.TocEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * 目录条目 ↔ JSON 字符串编解码（books.tocJson 列的持久化格式）。
 *
 * 项目未引入序列化框架，用 Android 自带 org.json 零依赖实现。
 * 解析对脏数据完全容错：任何异常都降级为空列表（目录功能缺失
 * 不应阻断进书，UI 会回落段落导航）。
 */
object TocCodec {

    private const val KEY_TITLE = "t"
    private const val KEY_INDEX = "p"

    /** 序列化。空目录返回 null（列存 NULL，与"无目录"语义一致）。 */
    fun encode(entries: List<TocEntry>): String? {
        if (entries.isEmpty()) return null
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put(KEY_TITLE, entry.title)
                    .put(KEY_INDEX, entry.paragraphIndex),
            )
        }
        return array.toString()
    }

    /** 反序列化。非法/越界条目逐条跳过，解析失败返回空列表。 */
    fun decode(json: String?): List<TocEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val title = obj.optString(KEY_TITLE).trim()
                    val index = obj.optInt(KEY_INDEX, -1)
                    if (title.isEmpty() || index < 0) continue
                    add(TocEntry(title = title, paragraphIndex = index))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
