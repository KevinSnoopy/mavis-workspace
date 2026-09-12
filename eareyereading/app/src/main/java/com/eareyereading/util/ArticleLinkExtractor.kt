@file:Suppress("ReturnCount")

package com.eareyereading.util

/**
 * 列表页 → 文章链接列表的纯解析器。
 *
 * 从 [ArticleParser] 抽出的单一职责模块（SRP）：链接提取有自己独立的
 * 过滤规则（排除导航/登录/资源链接、相对 URL 补全、去重、条数上限），
 * 与正文提取的策略演进互不影响。
 */
internal object ArticleLinkExtractor {

    /** 排除导航、登录、注册等非文章链接（预编译常量，链接提取是高热路径）。 */
    private val EXCLUDE_LINK_PATTERN = Regex(
        """(login|sign[-]?in|sign[-]?up|register|about|contact|privacy|terms|category|tag|author|profile|feed|rss|xml|sitemap|css|js|png|jpg|gif|svg|ico|pdf|zip)""",
        RegexOption.IGNORE_CASE,
    )

    private val ANCHOR_REGEX = Regex(
        """<a[^>]+href=["']([^"']+)["'][^>]*>([^<]+)</a>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** 单页最多收录的链接数 */
    private const val MAX_LINKS = 30

    /** 链接文本的最大展示长度 */
    private const val MAX_LINK_TITLE_CHARS = 120

    /**
     * 从页面 HTML 提取文章链接（列表页 / 首页场景）。
     *
     * @return 无可用链接时返回 null
     */
    internal fun extractLinksFromHtml(html: String, baseUrl: String): ArticleLinkResult? {
        val title = ArticleHtmlExtractor.extractTitle(html)
        // 过滤规则：排除导航、登录、注册等非文章链接（正则已预编译为常量）
        val excludePattern = EXCLUDE_LINK_PATTERN
        val seen = mutableSetOf<String>()
        val links = mutableListOf<ArticleLink>()

        for (match in ANCHOR_REGEX.findAll(html)) {
            val href = match.groupValues[1].trim()
            val text = match.groupValues[2].trim()

            // 过滤：URL 必须有效、非排除项、文本有内容
            if (href.isBlank() || text.isBlank()) continue
            if (excludePattern.containsMatchIn(href) || excludePattern.containsMatchIn(text)) continue
            // issue 10.2：非 http scheme 的链接直接挡掉（javascript:/#/data: 等）
            if (href.startsWith("#") || href.startsWith("javascript:")) continue
            if (seen.contains(href)) continue

            // 补全相对 URL：以列表页 URL 为 base 解析；解析失败时保留原值
            val absoluteUrl = if (href.startsWith("http")) {
                href
            } else {
                try {
                    java.net.URI(baseUrl).resolve(href).toString()
                } catch (_: Exception) {
                    href
                }
            }
            // issue 10.2：补全后再校验一次——解析失败保留原值的分支可能是坏链
            if (!absoluteUrl.startsWith("http://") && !absoluteUrl.startsWith("https://")) continue
            seen.add(href)

            links.add(ArticleLink(
                title = text.take(MAX_LINK_TITLE_CHARS),
                url = absoluteUrl,
            ))

            if (links.size >= MAX_LINKS) break
        }

        return if (links.isEmpty()) null
        else ArticleLinkResult(title = title, links = links)
    }
}
