package com.eareyereading.ui.screens.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.domain.model.ArticleSource
import com.eareyereading.domain.model.Book
import com.eareyereading.util.RssParser
import com.eareyereading.ui.theme.KnownWord
import com.eareyereading.ui.theme.NewWord
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.Secondary
import com.eareyereading.ui.theme.Success
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (Long) -> Unit,
    onNavigateToVocabulary: () -> Unit,
    onNavigateToReview: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { viewModel.importBook(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("📖 听阅", fontWeight = FontWeight.Bold)
                        Text(
                            "生词 ${uiState.learnedWordCount}/${uiState.totalWordCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    // 复习按钮
                    FilledTonalButton(
                        onClick = onNavigateToReview,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Secondary.copy(alpha = 0.15f),
                            contentColor = Secondary,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(Icons.Default.School, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (uiState.dueReviewCount > 0) "复习 ${uiState.dueReviewCount}"
                            else "复习",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = onNavigateToVocabulary) {
                        Icon(Icons.Default.MenuBook, contentDescription = "生词本")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                // 小按钮：URL 导入
                SmallFloatingActionButton(
                    onClick = viewModel::showUrlDialog,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(Icons.Default.Link, "导入网址", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(modifier = Modifier.height(12.dp))
                // 主按钮：文件导入
                ExtendedFloatingActionButton(
                    onClick = { filePicker.launch(arrayOf("application/epub+zip", "text/plain", "*/*")) },
                    icon = { Icon(Icons.Default.Add, "导入") },
                    text = { Text("导入书籍") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 搜索栏
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索书籍...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(Icons.Default.Clear, "清除")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            // Tab 切换
            TabRow(
                selectedTabIndex = uiState.selectedTab,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                indicator = {},
                divider = {},
            ) {
                Tab(
                    selected = uiState.selectedTab == 0,
                    onClick = { viewModel.setTab(0) },
                    text = { Text("📚 书库") },
                    selectedContentColor = Primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Tab(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.setTab(1) },
                    text = { Text("📰 文章") },
                    selectedContentColor = Primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (uiState.selectedTab == 0) {
                // 今日阅读统计面板
                val stats = uiState.readingStats
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatCard(
                        icon = Icons.Default.Timer,
                        value = "${stats.todayMinutes}",
                        label = "今日阅读(min)",
                        color = Primary,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        icon = Icons.Default.LocalFireDepartment,
                        value = "${stats.streakDays}",
                        label = "连续打卡(天)",
                        color = Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        icon = Icons.Default.MenuBook,
                        value = "${stats.totalBooks}",
                        label = "累计书籍",
                        color = Success,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (uiState.isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            if (uiState.loadingMessage.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(uiState.loadingMessage, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                } else if (uiState.books.isEmpty()) {
                    EmptyLibrary(onImport = { filePicker.launch(arrayOf("application/epub+zip", "text/plain", "*/*")) })
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.books, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                onClick = { onBookClick(book.id) },
                                onDelete = { viewModel.deleteBook(book.id) },
                                onArchive = { viewModel.archiveBook(book.id) },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            } else {
                ArticleSquareScreen(
                    sources = uiState.articleSources,
                    selectedSource = uiState.selectedSource,
                    articles = uiState.articles,
                    articlesLoading = uiState.articlesLoading,
                    articlesError = uiState.articlesError,
                    onSourceClick = viewModel::selectSource,
                    onBackFromSource = viewModel::clearSelectedSource,
                    onAddArticle = viewModel::addArticleToLibrary,
                )
            }
        }
    }

    // URL 导入弹窗
    if (uiState.showUrlDialog) {
        AlertDialog(
            onDismissRequest = viewModel::hideUrlDialog,
            title = { Text("🌐 导入网址文章") },
            text = {
                Column {
                    Text("输入英文文章网址，自动抓取正文", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = uiState.urlInput,
                        onValueChange = viewModel::onUrlInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://bbc.com/...") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        leadingIcon = { Icon(Icons.Default.Link, null) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::importFromUrl,
                    enabled = uiState.urlInput.isNotBlank(),
                ) { Text("抓取") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::hideUrlDialog) { Text("取消") }
            },
        )
    }
}

@Composable
fun BookCard(
    book: Book,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面占位
            Box(
                modifier = Modifier
                    .size(72.dp, 96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = book.title.take(2).uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 进度条
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { book.readProgress },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = KnownWord,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${(book.readProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (book.totalWords > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${book.totalWords} 词",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "更多")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("移至归档") },
                        onClick = { onArchive(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Archive, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { onDelete(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyLibrary(onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("📚", style = MaterialTheme.typography.displayLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "书架为空",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "导入一本 EPUB 或 TXT 格式的英文书籍，开始阅读之旅",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        FilledTonalButton(onClick = onImport) {
            Icon(Icons.Default.Add, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("导入书籍")
        }
    }
}

// ── 统计卡片组件 ────────────────────────────────
@Composable
private fun StatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, label, tint = color, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBackFromSource) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
                Text(
                    text = selectedSource.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            when {
                articlesLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("正在加载文章...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                articlesError != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(articles, key = { it.link + it.title }) { article ->
                        ArticleItemCard(
                            article = article,
                            sourceName = selectedSource.name,
                            onAdd = { onAddArticle(article) },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    } else {
        // 来源列表
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 按分类分组
            val grouped = sources.groupBy { it.category }
            grouped.forEach { (category, srcs) ->
                item {
                    Text(
                        text = "${category.emoji} ${category.label}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
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

@Composable
fun ArticleSourceCard(
    source: ArticleSource,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(source.color).copy(alpha = 0.15f),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = source.icon ?: source.name.take(1),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (!source.description.isNullOrBlank()) {
                    Text(
                        text = source.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DifficultyChip(source.difficulty)
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Primary.copy(alpha = 0.1f),
                    ) {
                        Text(
                            text = if (source.isRss) "📡 RSS" else "🌐 Web",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Primary,
                        )
                    }
                }
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ArticleItemCard(
    article: RssParser.RssArticle,
    sourceName: String,
    onAdd: () -> Unit,
) {
    var added by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                )
                if (article.pubDate != null) {
                    Text(
                        text = article.pubDate.take(16),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!article.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = article.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (added) {
                    AssistChip(
                        onClick = {},
                        label = { Text("已添加", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Success.copy(alpha = 0.15f),
                            labelColor = Success,
                        ),
                    )
                } else {
                    FilledTonalButton(
                        onClick = {
                            onAdd()
                            added = true
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Primary.copy(alpha = 0.15f),
                            contentColor = Primary,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("加入书库", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun DifficultyChip(level: Int) {
    val (color, label) = when (level) {
        1 -> Success to "⭐ 入门"
        2 -> Success to "⭐⭐ 简单"
        3 -> Secondary to "⭐⭐⭐ 中等"
        4 -> Color(0xFFFF9800) to "⭐⭐⭐⭐ 较难"
        else -> Color(0xFFE53935) to "⭐⭐⭐⭐⭐ 困难"
    }
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.12f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
