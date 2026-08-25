package com.eareyereading.util

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RSS / Atom 订阅源解析器
 * 支持 RSS 2.0 和 Atom 1.0
 */
@Singleton
class RssParser @Inject constructor() {

    companion object {
        private val DATE_FORMATS = listOf(
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ENGLISH),
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH),
        )
    }

    data class RssFeed(
        val title: String,
        val description: String?,
        val link: String?,
        val items: List<RssArticle>,
    )

    data class RssArticle(
        val title: String,
        val link: String,
        val description: String?,
        val pubDate: String?,
        val pubTimestamp: Long,
    )

    /**
     * 解析 RSS/Atom 源 URL
     * @return 解析后的 Feed，失败返回 null
     */
    fun parse(urlStr: String): RssFeed? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "EareyeReader/1.0")
                setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
                connectTimeout = 12000
                readTimeout = 12000
            }
            val xml = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseXml(xml)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseXml(xml: String): RssFeed {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var feedTitle = ""
        var feedDescription: String? = null
        var feedLink: String? = null
        val articles = mutableListOf<RssArticle>()

        var eventType = parser.eventType
        var inItem = false
        var inChannel = false
        var inEntry = false    // Atom
        var currentTag = ""
        var currentTitle = ""
        var currentLink = ""
        var currentDesc: String? = null
        var currentPubDate: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (parser.name.lowercase()) {
                        "channel" -> inChannel = true
                        "item" -> { inItem = true; currentTitle = ""; currentLink = ""; currentDesc = null; currentPubDate = null }
                        "entry" -> { inEntry = true; currentTitle = ""; currentLink = ""; currentDesc = null; currentPubDate = null }
                        "link" -> {
                            val href = parser.getAttributeValue(null, "href")
                            if (href != null) {
                                if (inItem || inEntry) currentLink = href
                                else if (inChannel || feedLink == null) feedLink = href
                            }
                        }
                        "title" -> if (!inItem && !inEntry) feedTitle = readText(parser)
                        "subtitle" -> if (!inItem && !inEntry) feedDescription = readText(parser)
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        when (currentTag.lowercase()) {
                            "title" -> if (inItem || inEntry) currentTitle = text
                            "link" -> if ((inItem || inEntry) && currentLink.isEmpty()) currentLink = text
                            "description", "summary", "content" -> {
                                if (inItem || inEntry) {
                                    currentDesc = (currentDesc ?: "") + stripHtml(text).take(300)
                                }
                            }
                            "pubdate", "published", "updated", "dc:date" -> if (inItem || inEntry) currentPubDate = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase()) {
                        "item" -> {
                            if (currentTitle.isNotBlank() && currentLink.isNotBlank()) {
                                articles.add(RssArticle(
                                    title = currentTitle.trim(),
                                    link = currentLink.trim(),
                                    description = currentDesc?.trim()?.take(200),
                                    pubDate = currentPubDate,
                                    pubTimestamp = parseDate(currentPubDate),
                                ))
                            }
                            inItem = false
                        }
                        "entry" -> {
                            if (currentTitle.isNotBlank() && currentLink.isNotBlank()) {
                                articles.add(RssArticle(
                                    title = currentTitle.trim(),
                                    link = currentLink.trim(),
                                    description = currentDesc?.trim()?.take(200),
                                    pubDate = currentPubDate,
                                    pubTimestamp = parseDate(currentPubDate),
                                ))
                            }
                            inEntry = false
                        }
                        "channel", "feed" -> { inChannel = false; inEntry = false }
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }

        return RssFeed(
            title = feedTitle.ifBlank { "RSS Feed" },
            description = feedDescription,
            link = feedLink,
            items = articles.take(50),
        )
    }

    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text?.trim() ?: ""
            parser.require(XmlPullParser.END_TAG, null, parser.name)
        }
        return result
    }

    private fun stripHtml(html: String): String {
        return html.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return System.currentTimeMillis()
        for (fmt in DATE_FORMATS) {
            try { return fmt.parse(dateStr.replace("Z", "+0000"))?.time ?: 0L } catch (_: Exception) {}
        }
        return System.currentTimeMillis()
    }
}
