package com.eareyereading.domain.model

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

/** 预置文章来源 + RSS 源（已验证 2024-08 可用） */
object ArticleSources {
    val sources = listOf(
        // ── 学习类（语速适中、适合英语学习者）──
        ArticleSource(
            id = "npr_learning",
            name = "NPR News",
            url = "https://feeds.npr.org/1001/rss.xml",
            category = SourceCategory.LEARNING,
            difficulty = 2,
            icon = "🎧",
            description = "美国国家公共电台，语速适中，适合中高级学习者",
            isRss = true,
        ),
        ArticleSource(
            id = "france24_learning",
            name = "France 24 English",
            url = "https://www.france24.com/en/rss",
            category = SourceCategory.LEARNING,
            difficulty = 3,
            icon = "📺",
            description = "法国 24 小时英语新闻，国际视角，词汇规范",
            isRss = true,
        ),
        ArticleSource(
            id = "chinadaily_world",
            name = "China Daily World",
            url = "https://www.chinadaily.com.cn/rss/world_rss.xml",
            category = SourceCategory.LEARNING,
            difficulty = 2,
            icon = "🌏",
            description = "中国日报英文版国际新闻，词汇量适中，配图丰富",
            isRss = true,
        ),

        // ── 新闻类（国际新闻）──
        ArticleSource(
            id = "npr_world",
            name = "NPR World",
            url = "https://feeds.npr.org/1001/rss.xml",
            category = SourceCategory.NEWS,
            difficulty = 3,
            icon = "📻",
            description = "美国国家公共电台，深度报道国际新闻",
            isRss = true,
        ),
        ArticleSource(
            id = "korea_herald",
            name = "Korea Herald",
            url = "https://www.koreaherald.com/rss/3",
            category = SourceCategory.NEWS,
            difficulty = 4,
            icon = "🇰🇷",
            description = "韩国先驱报，亚洲视角看世界新闻",
            isRss = true,
        ),
        ArticleSource(
            id = "chinadaily_china",
            name = "China Daily China",
            url = "https://www.chinadaily.com.cn/rss/china_rss.xml",
            category = SourceCategory.NEWS,
            difficulty = 3,
            icon = "🇨🇳",
            description = "中国日报英文版中国新闻，官方权威",
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
        ArticleSource(
            id = "theverge",
            name = "The Verge",
            url = "https://www.theverge.com/rss/index.xml",
            category = SourceCategory.TECH,
            difficulty = 3,
            icon = "📱",
            description = "科技文化媒体，前沿科技与生活方式",
            isRss = true,
        ),
        ArticleSource(
            id = "sspai",
            name = "少数派",
            url = "https://sspai.com/feed",
            category = SourceCategory.TECH,
            difficulty = 4,
            icon = "✍️",
            description = "少数派中文科技博客，深度评测与教程",
            isRss = true,
        ),

        // ── 科普类 ──
        ArticleSource(
            id = "sciencedaily",
            name = "ScienceDaily",
            url = "https://www.sciencedaily.com/rss/all.xml",
            category = SourceCategory.SCIENCE,
            difficulty = 4,
            icon = "🔬",
            description = "最新科学研究进展，涵盖各学科",
            isRss = true,
        ),
        ArticleSource(
            id = "phys_org",
            name = "Phys.org",
            url = "https://phys.org/rss-feed/",
            category = SourceCategory.SCIENCE,
            difficulty = 4,
            icon = "⚛️",
            description = "物理与科学技术新闻，每日更新",
            isRss = true,
        ),

        // ── 文化类 ──
        ArticleSource(
            id = "chinadaily_culture",
            name = "China Daily Culture",
            url = "https://www.chinadaily.com.cn/rss/culture_rss.xml",
            category = SourceCategory.CULTURE,
            difficulty = 3,
            icon = "🎭",
            description = "中国日报文化版，中西方文化报道",
            isRss = true,
        ),
        ArticleSource(
            id = "zaobao_world",
            name = "联合早报 国际",
            url = "https://www.zaobao.com/rss/realtime/world",
            category = SourceCategory.CULTURE,
            difficulty = 3,
            icon = "📰",
            description = "新加坡联合早报国际新闻，华语视角",
            isRss = true,
        ),
        ArticleSource(
            id = "inverse",
            name = "Inverse",
            url = "https://www.inverse.com/rss",
            category = SourceCategory.CULTURE,
            difficulty = 4,
            icon = "🧠",
            description = "科学文化深度报道，前沿思想与生活方式",
            isRss = true,
        ),
    )

    fun getById(id: String) = sources.find { it.id == id }
}
