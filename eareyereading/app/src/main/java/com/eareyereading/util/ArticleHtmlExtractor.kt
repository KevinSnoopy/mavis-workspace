@file:Suppress("ReturnCount")

package com.eareyereading.util

/**
 * HTML → 文章结构（标题 + 正文段落）的纯解析器。
 *
 * 从 [ArticleParser] 抽出的单一职责模块（SRP）：把"给定 HTML 文本，产出标题与
 * 正文段落"这组**无网络、无状态**的纯函数与 HTTP 抓取逻辑分离。解析策略的调整
 * （新增策略、修正正则）不再需要触碰网络层代码。
 *
 * 所有正则预编译为常量：文章解析是高频路径，原先写在调用处的正则会每次重新编译。
 */
internal object ArticleHtmlExtractor {

    // ── 正则：标签与噪音清理 ─────────────────────────────

    // HTML 标签清理正则
    private val HTML_TAG = Regex("<[^>]+>")

    // issue 7.4：策略 5 命中 <body> 时页眉/页脚仍是噪音，先剥掉再取正文
    private val HEADER_FOOTER_TAG = Regex(
        "<(header|footer)\\b[^>]*>[\\s\\S]*?</\\1>",
        RegexOption.IGNORE_CASE,
    )
    // issue 7.5/10.10：块级标签边界 → 空行，保留原文段落结构（cleanText 分段用）
    private val BLOCK_END_TAG = Regex(
        "</(p|div|li|h[1-6]|blockquote|tr|section|article|figure|figcaption|header|footer|aside|nav)>|<br\\s*/?>",
        RegexOption.IGNORE_CASE,
    )

    // 性能：script/style/comment 的"整块删除"合并为单趟扫描（原 3 遍全文
    // 各一次全量拷贝，5MB 页面最坏 3×10MB 级临时分配）。三者模式均无
    // 分组/反向引用，直接拼 alternation 语义等价
    private val STRIP_ALL_BLOCK = Regex(
        "<script[^>]*>.*?</script>|<style[^>]*>.*?</style>|<!--.*?-->",
        RegexOption.DOT_MATCHES_ALL,
    )

    // 性能：三种噪音剔除（issue 7.2 配对块 / 按 class 的容器 / 残标签）合并为
    // 单趟扫描。回溯引用改用命名组——组合后匿名组号会漂移，\1 会指错组
    // 注意：figure 容器不整块删除（其内的 <img> 已被替换成 [[IMG:]] 标记，
    // 整块删除会把插图一起吞掉）；仅去掉 figure 开闭标签本身 + 图注文字。
    private val NOISE_ALL_BLOCK = Regex(
        "<(?<noiseTag>aside|nav|iframe|button|form|select|textarea|ins)\\b[^>]*>[\\s\\S]*?</\\k<noiseTag>" +
            "|<figcaption\\b[^>]*>[\\s\\S]*?</figcaption>" +
            "|<\\s*(?<classTag>div|section)\\b[^>]*class\\s*=\\s*[\"'][^\"']*\\b" +
            "(?:share|social|newsletter|subscribe|ad-|advert|promo|related|recommend|sidebar|breadcrumb|byline|author-bio|comments)" +
            "\\b[^\"']*[\"'][^>]*>[\\s\\S]*?</\\k<classTag>" +
            "|<(?:aside|nav|figcaption|iframe|button|form|input|select|textarea|ins)\\b[^>]*/?>",
    )

    // ── 正则：标题与正文提取策略 ─────────────────────────

    private val OG_TITLE_CONTENT = Regex("""og:title["\s]+content=["']([^"']+)["']""")
    private val OG_TITLE_META = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""")
    private val TITLE_TAG = Regex("""<title[^>]*>([^<]+)</title>""")
    private val H1_TAG = Regex("""<h1[^>]*>([^<]+)</h1>""")

    private val ARTICLE_TAG = Regex("""<article[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL)
    private val MAIN_TAG = Regex("""<main[^>]*>(.*?)</main>""", RegexOption.DOT_MATCHES_ALL)
    private val JSON_LD_ARTICLE_BODY = Regex(
        """"articleBody"\s*:\s*"((?:\\.|[^"\\]){100,})"""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val CONTENT_DIV = Regex(
        """<div[^>]+class=["'][^"']*(?:content|article|body|text|story|entry)[^"']*["'][^>]*>(.*?)</div>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val CONTENT_SECTION = Regex(
        """<section[^>]+class=["'][^"']*(?:content|article|body|text|story|entry)[^"']*["'][^>]*>(.*?)</section>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val BODY_TAG = Regex("""<body[^>]*>(.*?)</body>""", RegexOption.DOT_MATCHES_ALL)

    private val PARAGRAPH_SPLIT = Regex("\n{2,}")
    private val WHITESPACE = Regex("\\s+")
    private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")
    private val SENTENCE_SPLIT_CAPITAL = Regex("(?<=[.!?])\\s+(?=[A-Z])")

    /**
     * 从 HTML 中提取文章内容（internal 供单元测试直接覆盖，无需走网络）
     * @param baseUrl 文章页 URL：插图 src 相对路径解析基准（测试直调时可空）
     */
    internal fun extractArticle(html: String, baseUrl: String? = null): ArticleResult {
        // 插图先行：<img> → [[IMG:绝对 URL]] 标记（必须在噪音剔除前替换，
        // 且 figure 容器不再整块删除，否则正文插图会被一并清掉）
        val withImages = replaceImgWithMarkers(html, baseUrl)
        // 清理脚本和样式 + issue 7.2 噪音元素（侧栏/导航/图注/iframe/按钮/
        // 表单/按 class 名的分享·订阅·广告·相关推荐容器）——策略命中容器前
        // 先剔除，否则 cleanText 的标签替换会把它们留进正文
        val text = withImages
            .replace(STRIP_ALL_BLOCK, "")
            .replace(NOISE_ALL_BLOCK, " ")

        // 提取标题
        val title = extractTitle(text)

        // 提取正文（多种策略）
        val content = BookImages.expandInlineMarkers(extractContent(text))

        return ArticleResult(title = title, paragraphs = content)
    }

    /** <img> → 段落级 [[IMG:绝对URL]] 标记；噪音图（追踪/徽章/svg/data URI）直接丢弃。 */
    private fun replaceImgWithMarkers(html: String, baseUrl: String?): String =
        BookImages.replaceImgTagsWithMarkers(html, baseUrl)

    /**
     * 提取文章标题
     */
    internal fun extractTitle(html: String): String {
        // og:title
        OG_TITLE_CONTENT.find(html)?.let { return it.groupValues[1].trim() }
        OG_TITLE_META.find(html)?.let { return it.groupValues[1].trim() }

        // <title> tag
        TITLE_TAG.find(html)?.let { return it.groupValues[1].trim() }

        // h1
        H1_TAG.find(html)?.let { return it.groupValues[1].trim() }

        return "Untitled Article"
    }

    /**
     * 提取正文内容（多种策略依次尝试）
     */
    private fun extractContent(html: String): List<String> {
        // 策略1: article tag
        ARTICLE_TAG.find(html)?.let {
            return cleanText(it.groupValues[1])
        }

        // 策略2: main tag
        MAIN_TAG.find(html)?.let {
            return cleanText(it.groupValues[1])
        }

        // 策略3: JSON-LD structured data
        // 匹配时跳过转义序列（\" 等），否则正文里第一个转义引号就会截断匹配
        JSON_LD_ARTICLE_BODY.find(html)?.let {
            val raw = unescapeJson(it.groupValues[1])
            return raw.split(SENTENCE_SPLIT).filter { s -> s.length > 20 }.map { it.trim() }
        }

        // 策略4: 找最大的文本块（content div）
        val candidates = listOf(CONTENT_DIV, CONTENT_SECTION)
        for (regex in candidates) {
            regex.findAll(html).maxByOrNull { match -> match.value.length }?.let { match ->
                val cleaned = cleanText(match.groupValues[1])
                if (cleaned.sumOf { it.length } > 200) return cleaned
            }
        }

        // 策略5: body 正文（issue 7.4：先剥页眉/页脚，否则整页文本都算正文）
        BODY_TAG.find(html)?.let {
            val cleaned = cleanText(it.groupValues[1].replace(HEADER_FOOTER_TAG, " "))
            if (cleaned.sumOf { s -> s.length } > 100) return cleaned
        }

        return cleanText(html)
    }

    /**
     * 清理 HTML 标签，提取纯文本并分段。
     *
     * issue 7.5 / 10.10：旧实现先把全文压成一行再"每 4 句硬切一段"，
     * 原文的段落边界（<p>/<div> 等）被丢弃，且切句正则要求句子首字母大写，
     * 小写开头的句子（引号/编号/缩写后）全被吞。改为保留块级标签边界：
     * 块级标签闭合 → 空行，再按空行自然分段；纯文本页才回退到"每 4 句合并"。
     */
    private fun cleanText(html: String): List<String> {
        // 块级标签边界 → 空行（先于通用标签替换，否则 </p> 会被替换成空格）
        val text = HtmlEntities.decode(
            html.replace(BLOCK_END_TAG, "\n\n").replace(HTML_TAG, " ")
        )
        // 段落级正则预编译：\s+ 原写在 map lambda 内，每个段落编译一次
        // 图片标记段不受长度/字母过滤约束（URL 就是它的内容）
        val paragraphs = text.split(PARAGRAPH_SPLIT)
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter {
                (it.isNotBlank() && it.length > 20 && it.any { c -> c.isLetter() }) ||
                    BookImages.isImageMarker(it)
            }
        if (paragraphs.isNotEmpty()) return paragraphs

        // 回退：页面没有块级标签（纯句子流）时，保持旧的"每 4 句一段"合并，
        // 不返回空列表导致策略判定失败
        val normalized = text.replace(WHITESPACE, " ").trim()
        val sentences = normalized
            .split(SENTENCE_SPLIT_CAPITAL)
            .map { it.trim() }
            .filter { it.length > 30 && it.any { c -> c.isLetter() } }
        if (sentences.isEmpty()) return listOf(normalized)
        val merged = mutableListOf<String>()
        for (i in sentences.indices step 4) {
            merged.add(sentences.subList(i, minOf(i + 4, sentences.size)).joinToString(" "))
        }
        return merged
    }

    /** 还原 JSON 字符串里最常见的转义序列（articleBody 场景）。 */
    internal fun unescapeJson(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    '"', '\\', '/' -> { sb.append(next); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    'u' -> {
                        val hex4 = s.substring(i + 2, minOf(i + 6, s.length))
                        val code = hex4.toIntOrNull(16)
                        if (code != null && hex4.length == 4) {
                            sb.append(code.toChar()); i += 6
                        } else {
                            sb.append(c); i += 1
                        }
                    }
                    else -> { sb.append(c); i += 1 }
                }
            } else {
                sb.append(c); i += 1
            }
        }
        return sb.toString()
    }
}
