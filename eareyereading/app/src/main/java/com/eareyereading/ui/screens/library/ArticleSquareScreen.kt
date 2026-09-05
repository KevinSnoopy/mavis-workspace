package com.eareyereading.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eareyereading.domain.model.ArticleSource
import com.eareyereading.ui.components.shimmer
import com.eareyereading.ui.theme.*
import com.eareyereading.util.RssParser

/**
 * 文章广场二级页：文章源卡片、文章列表、难度徽章与加载骨架屏。
 */
@Composable
internal fun ArticleItemSkeleton() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmer(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmer(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmer(),
            )
        }
    }
}

// ── 文章广场 ──────────────────────────────────────
@Composable
fun ArticleSquareScreen(
    sources: List<ArticleSource>,
    selectedSource: ArticleSource?,
    articles: List<RssParser.RssArticle>,
    articlesLoading: Boolean,
    articlesError: String?,
    addedLinks: Set<String> = emptySet(),
    onSourceClick: (ArticleSource) -> Unit,
    onBackFromSource: () -> Unit,
    onAddArticle: (RssParser.RssArticle) -> Unit,
) {
    if (selectedSource != null) {
        // 文章列表
        Column(modifier = Modifier.fillMaxSize()) {
            // 返回栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBackFromSource) {
                    Icon(Icons.Default.ArrowBack, "返回")
                }
                Text(
                    text = selectedSource.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                articlesLoading -> Column(modifier = Modifier.fillMaxSize()) {
                    // 骨架屏占位，替代居中转圈
                    Spacer(modifier = Modifier.height(8.dp))
                    repeat(4) {
                        ArticleItemSkeleton()
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }
                articlesError != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CloudOff,
                            null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(articlesError, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(onClick = { onSourceClick(selectedSource) }) {
                            Text("重试")
                        }
                    }
                }
                articles.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("该源暂无文章", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Text(
                        "文章列表",
                        style = SectionTitle,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(articles, key = { index, article ->
                            // 复合 key：index + link + title，确保唯一性
                            // 即使 ViewModel 去重遗漏，index 维度也能兜底防崩溃
                            "$index:${article.link}:${article.title}"
                        }) { index, article ->
                            ArticleItemCard(
                                article = article,
                                sourceName = selectedSource.name,
                                isAdded = article.link in addedLinks,
                                onAdd = { onAddArticle(article) },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            }
        }
    } else {
        // 来源列表
        Column {
            Text(
                "订阅源",
                style = SectionTitle,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val grouped = sources.groupBy { it.category }
                grouped.forEach { (category, srcs) ->
                    item {
                        // 分组头：描线图标 + 文字（与卡片主题图标风格统一，
                        // 不再用 emoji 当图标）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            Icon(
                                imageVector = sourceCategoryIcon(category),
                                contentDescription = null,
                                tint = OnSurfaceTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = category.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    items(srcs, key = { it.id }) { source ->
                        ArticleSourceCard(
                            source = source,
                            onClick = { onSourceClick(source) },
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
fun ArticleSourceCard(
    source: ArticleSource,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),

    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标方块：surface-container-high 底 + 主题色描线图标，
            // 替代原先按分类散落的 5 种彩色 hue + emoji（与墨绿主系割裂）
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceHover,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = sourceCategoryIcon(source.category),
                        contentDescription = source.category.label,
                        tint = Primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!source.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = source.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DifficultyChip(source.difficulty)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = InfoBg,
                    ) {
                        Text(
                            text = if (source.isRss) "📡 RSS" else "🌐 Web",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = Info,
                        )
                    }
                }
            }
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 订阅源分类 → 单色描线图标（反模式 #11：emoji 不作为图标使用）。 */
internal fun sourceCategoryIcon(category: com.eareyereading.domain.model.SourceCategory) =
    when (category) {
        com.eareyereading.domain.model.SourceCategory.LEARNING -> Icons.Default.Headphones
        com.eareyereading.domain.model.SourceCategory.NEWS -> Icons.Default.Newspaper
        com.eareyereading.domain.model.SourceCategory.TECH -> Icons.Default.Computer
        com.eareyereading.domain.model.SourceCategory.SCIENCE -> Icons.Default.Science
        com.eareyereading.domain.model.SourceCategory.CULTURE -> Icons.Default.Public
        com.eareyereading.domain.model.SourceCategory.CUSTOM -> Icons.Default.Star
    }

@Composable
fun ArticleItemCard(
    article: RssParser.RssArticle,
    sourceName: String,
    isAdded: Boolean,
    onAdd: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),

    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    fontWeight = FontWeight.Medium,
                )
                if (article.pubDate != null) {
                    Text(
                        text = article.pubDate.take(16),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!article.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = article.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (isAdded) {
                    AssistChip(
                        onClick = {},
                        label = { Text("已添加", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp))
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = SuccessBg,
                            labelColor = Success,
                        ),
                    )
                } else {
                    Button(
                        onClick = { onAdd() },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("加入书库", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
fun DifficultyChip(level: Int) {
    // 难度 chip 用语义状态色 10% 透明底 + 同色文字（§4.4.2）：
    // 简单=mastered 绿 / 中等=learning 橙 / 困难=error 红，
    // 替代原先 L1-L5 五种相近淡色（色相过近难扫读）
    val difficultyColors = listOf(
        Success to "⭐ 入门",
        Success to "⭐⭐ 简单",
        StateLearning to "⭐⭐⭐ 中等",
        Error to "⭐⭐⭐⭐ 较难",
        Error to "⭐⭐⭐⭐⭐ 困难",
    )
    val (color, label) = difficultyColors.getOrElse(level - 1) { Accent to "⭐ 入门" }
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.10f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
