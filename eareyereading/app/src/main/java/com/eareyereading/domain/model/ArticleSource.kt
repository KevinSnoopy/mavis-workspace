package com.eareyereading.domain.model
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName", "EmptyFunctionBlock", "UnusedPrivateProperty")

/**
 * 文章来源（RSS 订阅或推荐站点）
 */
data class ArticleSource(
    val id: String,
    val name: String,
    val url: String,
    val category: SourceCategory,
    val difficulty: Int,          // 1-5 难度
    val icon: String? = null,     // emoji 或 URL
    val description: String? = null,
    val isRss: Boolean = true,    // 是否为 RSS 源
)

enum class SourceCategory(val label: String, val emoji: String) {
    LEARNING("学习", "📚"),
    NEWS("新闻", "📰"),
    TECH("科技", "💻"),
    SCIENCE("科普", "🔬"),
    CULTURE("文化", "🌍"),
    CUSTOM("自定义", "⭐"),
}

enum class RssItem {
    TITLE, LINK, DESCRIPTION, PUB_DATE
}

/** 预置文章来源 + RSS 源 */
object ArticleSources {
    val sources = listOf(
        // ── 学习类 ──
        ArticleSource(
            id = "bbc_le",
            name = "BBC Learning English",
            url = "https://podcasts.files.bbci.co.uk/p02pc9zn.rss",
            category = SourceCategory.LEARNING,
            difficulty = 2,
            icon = "📺",
            description = "专为英语学习者设计，内容通俗易懂，配音频",
            isRss = true,
        ),
        ArticleSource(
            id = "voa_le",
            name = "VOA Learning English",
            url = "https://learningenglish.voanews.com/api/epiSS",
            category = SourceCategory.LEARNING,
            difficulty = 2,
            icon = "🎙️",
            description = "Voice of America 学习频道，语速适中，含新闻",
            isRss = true,
        ),
        ArticleSource(
            id = "eltimes",
            name = "English Today",
            url = "https://www.englishclub.com/news/",
            category = SourceCategory.LEARNING,
            difficulty = 2,
            icon = "📰",
            description = "EnglishClub 新闻阅读，词汇分级标注",
            isRss = false,
        ),
        // ── 新闻类 ──
        ArticleSource(
            id = "npr",
            name = "NPR News",
            url = "https://feeds.npr.org/1001/rss.xml",
            category = SourceCategory.NEWS,
            difficulty = 3,
            icon = "🎧",
            description = "美国国家公共电台，报道深度新闻，篇幅适中",
            isRss = true,
        ),
        ArticleSource(
            id = "apnews",
            name = "AP News",
            url = "https://apnews.com/rss",
            category = SourceCategory.NEWS,
            difficulty = 3,
            icon = "📡",
            description = "美联社新闻，客观中立，句子简洁清晰",
            isRss = true,
        ),
        ArticleSource(
            id = "guardian",
            name = "The Guardian",
            url = "https://www.theguardian.com/world/rss",
            category = SourceCategory.NEWS,
            difficulty = 4,
            icon = "🛡️",
            description = "英国主流媒体，观点性强，词汇丰富",
            isRss = true,
        ),
        ArticleSource(
            id = "reuters",
            name = "Reuters World",
            url = "https://feeds.reuters.com/reuters/worldnews",
            category = SourceCategory.NEWS,
            difficulty = 4,
            icon = "🔴",
            description = "全球知名通讯社，文章简洁专业，适合提升阅读",
            isRss = true,
        ),
        // ── 科技类 ──
        ArticleSource(
            id = "arstechnica",
            name = "Ars Technica",
            url = "https://feeds.arstechnica.com/arstechnica/index",
            category = SourceCategory.TECH,
            difficulty = 4,
            icon = "💻",
            description = "科技新闻与深度分析，IT 行业必读",
            isRss = true,
        ),
        ArticleSource(
            id = "techcrunch",
            name = "TechCrunch",
            url = "https://techcrunch.com/feed/",
            category = SourceCategory.TECH,
            difficulty = 4,
            icon = "🚀",
            description = "硅谷科技媒体，创业与科技动态",
            isRss = true,
        ),
        // ── 科普类 ──
        ArticleSource(
            id = "natgeo",
            name = "National Geographic",
            url = "https://api.allorigins.win/raw?url=https://www.nationalgeographic.com/our-grid/rss",
            category = SourceCategory.SCIENCE,
            difficulty = 3,
            icon = "🌍",
            description = "国家地理杂志，科学与自然主题，配图精美",
            isRss = false,
        ),
        ArticleSource(
            id = "smithsonian",
            name = "Smithsonian Magazine",
            url = "https://www.smithsonianmag.com/rss/science-nature/",
            category = SourceCategory.SCIENCE,
            difficulty = 3,
            icon = "🏛️",
            description = "史密森尼学会杂志，历史、科学、文化主题",
            isRss = true,
        ),
        // ── 评论类 ──
        ArticleSource(
            id = "economist",
            name = "The Economist",
            url = "https://www.economist.com/rss",
            category = SourceCategory.CULTURE,
            difficulty = 5,
            icon = "📊",
            description = "经济学人，深度评论文章，词汇难度较高",
            isRss = true,
        ),
        ArticleSource(
            id = "medium",
            name = "Medium",
            url = "https://medium.com/feed/@topic/science",
            category = SourceCategory.CULTURE,
            difficulty = 3,
            icon = "✍️",
            description = "各类主题博客文章，选择范围广，难度不一",
            isRss = true,
        ),
    )

    fun getById(id: String) = sources.find { it.id == id }
}
