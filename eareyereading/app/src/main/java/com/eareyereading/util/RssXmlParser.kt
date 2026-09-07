@file:Suppress("SwallowedException", "UnsafeCast")

package com.eareyereading.util

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/** 需要采集文本内容的元素（本地名；namespaceAware 下 `parser.name` 已去前缀）。 */
internal val RssTextTags = setOf(
    "title", "subtitle", "link", "guid",
    "description", "summary", "content", "encoded",
    "pubdate", "date", "published", "updated",
)

internal const val RssMaxItems = 50
internal const val RssMaxTitle = 300
internal const val RssMaxDesc = 500
internal const val RssMaxRawField = 32_000

/** 一个尚未落盘的 <item> / <entry>。 */
internal class RssItemState {
    var title: String = ""
    var link: String? = null
    val description = StringBuilder()
    val content = StringBuilder()
    var pubDate: String? = null

    fun reset() {
        title = ""
        link = null
        description.setLength(0)
        content.setLength(0)
        pubDate = null
    }
}

/** 推进解析器；遇到畸形 XML 时停止（保留已解析到的内容）而不是抛异常。 */
private fun safeNext(parser: XmlPullParser): Int {
    return try {
        parser.next()
    } catch (e: XmlPullParserException) {
        android.util.Log.w(
            "RssParser",
            "Malformed feed XML at line ${parser.lineNumber}: ${e.message}"
        )
        XmlPullParser.END_DOCUMENT
    }
}

private fun newParser(): XmlPullParser {
    val factory = XmlPullParserFactory.newInstance()
    // 故意不开启 namespace 处理。开启后 KXml2 遇到未声明的前缀（很多 feed 的
    // <content:encoded> / <dc:date> 都没写 xmlns:xxx）会直接抛
    // "undefined prefix" 让整个 feed 解析失败；关闭后前缀作为元素名的一部分保留，
    // 再用 localName() 归一化，两种写法都能匹配。
    factory.isNamespaceAware = false
    return factory.newPullParser()
}

/** 取元素名的本地部分：`content:encoded` / `dc:date` / `link` -> `encoded` / `date` / `link`。 */
private fun localName(name: String): String {
    val i = name.lastIndexOf(':')
    return if (i >= 0) name.substring(i + 1) else name
}

/** 落盘一条文章；缺 title 或 link 时丢弃。 */
private fun flushItem(articles: MutableList<RssParser.RssArticle>, item: RssItemState) {
    val title = item.title.trim()
    val link = item.link?.trim().orEmpty()
    if (title.isEmpty() || link.isEmpty()) return
    articles.add(
        RssParser.RssArticle(
            title = title,
            link = link,
            description = item.description.toString().trim().take(RssMaxDesc).ifEmpty { null },
            pubDate = item.pubDate,
            pubTimestamp = parseRssDate(item.pubDate),
            content = item.content.toString().trim().ifEmpty { null },
        )
    )
    item.reset()
}

private fun appendDesc(sb: StringBuilder, cleaned: String) {
    val room = RssMaxDesc - sb.length
    if (room <= 0) return
    if (cleaned.length <= room) sb.append(cleaned) else sb.append(cleaned, 0, room)
}

/**
 * 解析已获取的 XML 文本（容错事件状态机）。
 * 包可见以便单元测试直接覆盖解析逻辑，无需走网络。
 */
internal fun parseRssXml(xml: String): RssParser.RssFeed {
    if (xml.isBlank()) return RssParser.RssFeed("RSS Feed", null, null, emptyList())

    val parser = newParser()
    parser.setInput(StringReader(xml))

    var feedTitle = ""
    var feedDescription: String? = null
    var feedLink: String? = null

    var inItem = false
    var inEntry = false
    val item = RssItemState()

    // 正在采集文本的元素本地名；null 表示当前不在任何文本元素内
    var collecting: String? = null
    var textBuf = StringBuilder()
    // 采集过程中打开的嵌套元素数量
    var nestedDepth = 0

    val articles = mutableListOf<RssParser.RssArticle>()

    // 把采集到的原文写入当前上下文的对应字段
    fun applyField(tag: String, raw: String) {
        val inArticle = inItem || inEntry
        when (tag) {
            "title" -> {
                val text = stripHtml(raw).take(RssMaxTitle)
                if (inArticle) item.title = text
                else if (feedTitle.isEmpty()) feedTitle = text
            }
            "subtitle" -> {
                if (!inArticle && feedDescription == null) feedDescription = stripHtml(raw)
            }
            "link" -> {
                if (raw.isNotBlank()) {
                    val url = raw.trim()
                    if (inArticle) item.link = url
                    else if (feedLink == null) feedLink = url
                }
            }
            "guid" -> {
                if (inArticle && item.link == null && raw.isNotBlank()) item.link = raw.trim()
            }
            "pubdate", "date", "published", "updated" -> {
                if (inArticle && raw.isNotBlank()) item.pubDate = raw.trim()
            }
            // issue 7.1：content:encoded 是 feed 自带的完整正文，
            // 独立保存且不截断——此前与 description 混流并被 MAX_DESC 砍掉
            "content", "encoded" -> {
                // 插图先行：<img> → [[IMG:绝对URL]] 标记（以文章 link 为基准
                // 解析相对地址；链接缺失时退回 feed 链接）。此前 stripHtml
                // 把 <img> 一并剥掉，真实阅读源正文里的图片全部丢失，
                // 阅读页只有文字、与原文版式错位。标记独立成段，
                // 导入书库后渲染层按插图整块加载
                val withImages = BookImages.replaceImgTagsWithMarkers(raw, item.link ?: feedLink)
                // 保留段落结构（</p> 等块级标签 → 空行），导入书库时按段切分
                val cleaned = stripHtmlKeepParagraphs(withImages)
                if (inArticle) {
                    if (cleaned.isNotEmpty()) item.content.append(cleaned)
                } else if (feedDescription == null) {
                    feedDescription = stripHtml(raw)
                }
            }
            else -> {
                // description / summary
                val cleaned = stripHtml(raw)
                if (inArticle) appendDesc(item.description, cleaned)
                else if (feedDescription == null) feedDescription = cleaned
            }
        }
    }

    var event = safeNext(parser)
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                if (collecting != null) {
                    // 嵌套标签：只记深度，继续采集其文本（RSS 的 <description> /
                    // <content:encoded> 里几乎总是嵌着 <p>/<em> 这类标签）
                    nestedDepth++
                } else {
                    val name = localName(parser.name).lowercase()
                    when (name) {
                        "item", "entry" -> {
                            // 先落盘上一条：应对缺少 </item> 的畸形 feed
                            flushItem(articles, item)
                            item.reset()
                            inItem = name == "item"
                            inEntry = name == "entry"
                        }
                        "link" -> {
                            // Atom: <link rel="alternate" href="..."/>；RSS: <link>url</link>
                            val href = parser.getAttributeValue(null, "href")?.trim()
                            val rel = parser.getAttributeValue(null, "rel")?.lowercase()
                            if (!href.isNullOrEmpty() && (rel == null || rel == "alternate")) {
                                if (inItem || inEntry) item.link = href
                                else if (feedLink == null) feedLink = href
                            }
                            // 自闭合 <link .../>（KXml2 不会发 END_TAG）不进入采集，
                            // 否则后续所有标签都会被当作嵌套文本，字段全部错位
                            if (!parser.isEmptyElementTag) {
                                collecting = "link"
                                textBuf.setLength(0)
                                nestedDepth = 0
                            }
                        }
                        else -> if (name in RssTextTags && !parser.isEmptyElementTag) {
                            collecting = name
                            textBuf.setLength(0)
                            nestedDepth = 0
                        }
                    }
                }
            }

            // KXml2 实际把 CDATA 以 TEXT 报告；CDSECT 分支保留作为防御
            XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                val chunk = parser.text ?: ""
                if (collecting != null && chunk.isNotEmpty() && textBuf.length < RssMaxRawField) {
                    textBuf.append(chunk, 0, minOf(chunk.length, RssMaxRawField - textBuf.length))
                }
            }

            XmlPullParser.END_TAG -> {
                val name = localName(parser.name).lowercase()
                val closingArticle = name == "item" || name == "entry"

                if (collecting != null) {
                    val closingNested = nestedDepth > 0
                    when {
                        // 文章边界到达：把已采集的内容落进字段，再结束采集
                        closingArticle -> applyField(collecting, textBuf.toString())
                        // </p> 之类：只是嵌套标签闭合，继续采集
                        closingNested -> nestedDepth--
                        name == collecting -> applyField(collecting, textBuf.toString())
                        else ->
                            // 畸形 XML：文本元素未闭合就遇到了别的结束标签，丢弃该字段
                            android.util.Log.w(
                                "RssParser",
                                "Unclosed <$collecting> before </$name> at line ${parser.lineNumber}; field dropped"
                            )
                    }
                    if (!closingNested || closingArticle) {
                        collecting = null
                        textBuf.setLength(0)
                        nestedDepth = 0
                    }
                }

                if (closingArticle) {
                    inItem = false
                    inEntry = false
                    flushItem(articles, item)
                }
            }
        }
        event = safeNext(parser)
    }

    // 最后再落盘一次：feed 在最后一个 </item> 之前就被截断也能拿到那条
    flushItem(articles, item)

    return RssParser.RssFeed(
        title = feedTitle.ifBlank { "RSS Feed" },
        description = feedDescription,
        link = feedLink,
        items = articles.take(RssMaxItems),
    )
}
