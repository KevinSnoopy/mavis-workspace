package com.eareyereading.util

/**
 * HTML 实体单遍解码：文章（ArticleParser）与 EPUB（EpubParser）两条导入路径共用，
 * 实体表与解码行为只维护这一份，避免两条路径行为分叉。
 *
 * 命名实体与数字实体一次扫描完成：顺序 replace 会先解 `&amp;` 再让
 * `&lt;`/`&#39;` 二次解码（`&amp;lt;` → `<` 串扰）。
 * 数字实体用 toIntOrNull + 码点范围校验，恶意超大值不抛异常崩溃；
 * Character.toChars 正确处理 >0xFFFF 的增补平面字符。
 */
object HtmlEntities {

    private val NAMED_ENTITIES = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">",
        "quot" to "\"", "apos" to "'",
        "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026",
    )

    // 预编译：decode 被 ArticleParser/EpubParser 逐段落调用，
    // 旧实现每次调用都 Pattern.compile，是两条导入路径共享的热点
    private val ENTITY_REGEX = Regex("&(#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")

    fun decode(text: String): String {
        if (!text.contains('&')) return text
        return ENTITY_REGEX.replace(text) { m ->
            val inner = m.groupValues[1]
            when {
                inner.startsWith("#x", ignoreCase = true) ->
                    codePointToString(inner.substring(2).toIntOrNull(16)) ?: m.value
                inner.startsWith("#") ->
                    codePointToString(inner.substring(1).toIntOrNull()) ?: m.value
                else -> NAMED_ENTITIES[inner] ?: m.value
            }
        }
    }

    private fun codePointToString(codePoint: Int?): String? {
        if (codePoint == null || codePoint !in 0..0x10FFFF) return null
        return String(Character.toChars(codePoint))
    }
}
