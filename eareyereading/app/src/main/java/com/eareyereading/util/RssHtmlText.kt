@file:Suppress("SwallowedException", "UnsafeCast")

package com.eareyereading.util

// ── 正则预编译：stripHtml 系列被每个 item 的每个字段调用，
// 旧实现单次 feed 解析要编译几百次正则 ──
internal val RssAnyTag = Regex("<[^>]+>")
internal val RssWhitespace = Regex("\\s+")
internal val RssBlockEnd = Regex("(?i)</(p|div|li|h[1-6]|blockquote|tr|section|article)>|<br\\s*/?>")
internal val RssLineWs = Regex("[ \\t\\x0B\\f]+")
internal val RssExcessNewlines = Regex("\n{3,}")
internal val RssEntity = Regex("(&#[xX]?[0-9a-fA-F]+;|&[a-zA-Z][a-zA-Z0-9]*;)")

internal val RssNamedEntities = mapOf(
    "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">",
    "quot" to "\"", "apos" to "'", "hellip" to "…",
    "mdash" to "—", "ndash" to "–",
    "lsquo" to "\u2018", "rsquo" to "\u2019",
    "ldquo" to "\u201C", "rdquo" to "\u201D",
)

internal fun stripHtml(html: String): String {
    return RssWhitespace.replace(decodeEntities(html.replace(RssAnyTag, " ")), " ").trim()
}

/**
 * 清 HTML 但保留段落结构：块级标签边界换行成空行，行内空白折叠。
 * [stripHtml] 会把全文压成一行，正文导入书库后没法按段阅读（issue 7.1/7.3）。
 */
internal fun stripHtmlKeepParagraphs(html: String): String {
    val withBreaks = html
        .replace(RssBlockEnd, "\n\n")
        .replace(RssAnyTag, " ")
    val decoded = decodeEntities(withBreaks)
    // RssLineWs 原写在 joinToString 的 lambda 内：每行编译一次正则
    return decoded.lines()
        .joinToString("\n") { RssLineWs.replace(it, " ").trim() }
        .replace(RssExcessNewlines, "\n\n")
        .trim()
}

/** 解码 HTML 实体（主要服务于 CDATA 里的原始 HTML，以及 `<p>`/`<em>` 之外的转义字符）。 */
internal fun decodeEntities(s: String): String {
    val idx = s.indexOf('&')
    if (idx < 0) return s
    return s.replace(RssEntity) { m ->
        decodeEntity(m.value)
    }
}

private fun decodeEntity(raw: String): String {
    val inner = raw.substring(1, raw.length - 1) // 去掉 '&' 和 ';'
    return try {
        when {
            inner.startsWith("#x", ignoreCase = true) ->
                Character.toChars(inner.substring(2).toInt(16)).concatToString()
            inner.startsWith('#') ->
                Character.toChars(inner.substring(1).toInt(10)).concatToString()
            else -> RssNamedEntities[inner.lowercase()] ?: raw
        }
    } catch (_: Exception) {
        raw
    }
}
