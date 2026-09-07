@file:Suppress("SwallowedException", "UnsafeCast")

package com.eareyereading.util

import java.text.SimpleDateFormat
import java.util.Locale

/** 日期格式模式。SimpleDateFormat 非线程安全，parseDate 每次调用时按模式新建实例。 */
internal val RssDatePatterns = listOf(
    "EEE, dd MMM yyyy HH:mm:ss Z",
    "EEE, dd MMM yyyy HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm:ssZ",
    "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd",
)

internal val RssTzOffset = Regex("([+-]\\d{2}):(\\d{2})")
internal val RssTzName = Regex("\\s+(GMT|UTC|UT)$")

internal fun parseRssDate(dateStr: String?): Long {
    if (dateStr.isNullOrBlank()) return System.currentTimeMillis()
    // SimpleDateFormat.parse 容忍尾部未消费的文本：`... HH:mm:ss Z` 遇到
    // 文本 "GMT" 时区段解析失败后，会把 "12:00:00" 按设备本地时区解释并
    // 静默忽略 " GMT" 尾巴，RSS 最常见的 GMT 时间整体偏移数小时。
    // 先把文本时区归一成数字偏移再解析。
    val normalized = dateStr.trim()
        .replace(RssTzOffset, "$1$2") // +00:00 -> +0000（RFC3339 偏移归一）
        .replace("Z", "+0000")
        .replace(RssTzName, " +0000")
    // SimpleDateFormat 非线程安全：每次调用新建实例，避免并发刷新时互相污染
    for (pattern in RssDatePatterns) {
        try {
            return SimpleDateFormat(pattern, Locale.ENGLISH).parse(normalized)?.time ?: 0L
        } catch (_: java.text.ParseException) {
            // 尝试下一个格式
        }
    }
    return System.currentTimeMillis()
}
