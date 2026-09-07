@file:Suppress("SwallowedException", "UnsafeCast")

package com.eareyereading.util

import java.nio.charset.Charset

internal val RssContentTypeCharset = Regex(";\\s*charset\\s*=\\s*[\"']?([^\"';\\s]+)", RegexOption.IGNORE_CASE)
internal val RssXmlEncodingAttr = Regex("""encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/** 从 `application/rss+xml; charset=UTF-8` 这类 Content-Type 里取 charset。 */
internal fun charsetFromContentType(contentType: String?): String? {
    if (contentType == null) return null
    return RssContentTypeCharset.find(contentType)?.groupValues?.get(1)
}

/**
 * HTTP Content-Type 优先，其次 BOM / XML 声明里的 encoding，最后回退 UTF-8。
 * 包可见以便单元测试直接覆盖 charset 决策，无需走网络。
 */
internal fun resolveCharset(contentType: String?, bytes: ByteArray): Charset {
    val headerCharset = charsetFromContentType(contentType)
    // UTF-16 BOM：声明嗅探按 US-ASCII 解码，NUL 交错的 UTF-16 声明永远匹配不到，
    // 只能靠 BOM 识别，否则会按 UTF-8 解出乱码
    if (bytes.size >= 2) {
        if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charset.forName("UTF-16BE")
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charset.forName("UTF-16LE")
    }
    val head = String(
        bytes.copyOfRange(0, minOf(bytes.size, 512)),
        Charset.forName("US-ASCII")
    )
    val xmlEncoding = RssXmlEncodingAttr.find(head)?.groupValues?.get(1)
    return listOfNotNull(headerCharset, xmlEncoding, "UTF-8").firstNotNullOfOrNull { name ->
        try {
            Charset.forName(name.trim())
        } catch (_: Exception) {
            null
        }
    } ?: Charset.forName("UTF-8")
}
