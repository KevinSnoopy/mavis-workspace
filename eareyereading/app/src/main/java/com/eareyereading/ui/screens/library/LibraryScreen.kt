package com.eareyereading.ui.screens.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.domain.model.ArticleSource
import com.eareyereading.domain.model.Book
import com.eareyereading.domain.model.ClassicBook
import com.eareyereading.util.RssParser
import com.eareyereading.ui.components.BookCover
import com.eareyereading.ui.components.EmptyState
import com.eareyereading.ui.components.StatCard
import com.eareyereading.ui.components.shimmer
import com.eareyereading.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val scope = rememberCoroutineScope()

    // 导入的最终结果（成功/失败）用 Snackbar 呈现：loadingMessage 原先只在
    // isLoading 为 true 的转圈分支里渲染，加载结束后设置的成功/失败消息
    // 用户永远看不到（URL 抓取失败完全静默）
    val snackbarHostState = remember { SnackbarHostState() }
    // issue 11.16：Snackbar 同字符串去重——连续两次相同文案（如"导入成功"）若以
    // loadingMessage 值作为 LaunchedEffect 的 key，第二次不会重新触发、第二条不显示。
    // 改为以 isLoading + messageEventId（每条消息自增）为 key：文案相同但 eventId 不同
    // 也能重新触发，且结果消息在 endImportOp 后才 `!isLoading` 通过、正常展示。
    LaunchedEffect(uiState.isLoading, uiState.messageEventId) {
        if (!uiState.isLoading && uiState.loadingMessage.isNotBlank()) {
            snackbarHostState.showSnackbar(uiState.loadingMessage)
            viewModel.dismissLoadingMessage()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { viewModel.importBook(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "书库",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { filePicker.launch(arrayOf("application/epub+zip", "text/plain", "*/*")) },
                icon = { Icon(Icons.Default.Add, "导入") },
                text = { Text("导入书籍") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
        ) {
            // M3 SearchBar（Google 式收起/展开）：收起时是常驻药丸输入框，
            // 点击展开后接管全屏，content 内渲染实时过滤结果。
            // 替代原先的 OutlinedTextField（web 风格输入框）
            var searchActive by rememberSaveable { mutableStateOf(false) }
            SearchBar(
                query = uiState.searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                onSearch = { searchActive = false },
                active = searchActive,
                onActiveChange = { searchActive = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = {
                    Text("搜索书籍 / 作者...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(Icons.Default.Clear, "清除", modifier = Modifier.size(18.dp))
                        }
                    } else if (searchActive) {
                        IconButton(onClick = { searchActive = false }) {
                            Icon(Icons.Default.Close, "收起", modifier = Modifier.size(18.dp))
                        }
                    }
                },
            ) {
                // 展开态：实时搜索结果（uiState.books 已按 searchQuery 过滤）
                if (uiState.books.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (uiState.searchQuery.isBlank()) "输入关键词搜索书架"
                            else "没有匹配「${uiState.searchQuery}」的书籍",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(uiState.books, key = { it.id }) { book ->
                            ListItem(
                                headlineContent = {
                                    Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                leadingContent = {
                                    BookCover(
                                        title = book.title,
                                        coverPath = book.coverPath,
                                        author = book.author,
                                        modifier = Modifier.size(38.dp, 52.dp),
                                        cornerRadius = 6.dp,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    // 点击搜索结果进书后收起搜索态：旧实现从阅读器
                                    // 返回时仍停在展开的搜索页，列表还是旧关键词的子集
                                    searchActive = false
                                    onBookClick(book.id)
                                },
                            )
                        }
                    }
                }
            }

            // Tab 切换：标准 M3 TabRow（下划线指示器 + 分隔线）。
            // 原实现抹掉指示器/分隔线做成透明胶囊，是 iOS/web 分段控件风格
            TabRow(
                selectedTabIndex = uiState.selectedTab,
            ) {
                Tab(
                    selected = uiState.selectedTab == 0,
                    onClick = { viewModel.setTab(0) },
                    text = { Text("书籍") },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Tab(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.setTab(1) },
                    text = { Text("文章") },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.selectedTab == 0) {
                val stats = uiState.readingStats
                // 有效分类：选中分类被清空（最后一本书改走）时自动回"全部"
                val effectiveCategory = uiState.selectedCategory
                    ?.takeIf { it in uiState.categories }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 今日阅读统计面板（紧凑）
                    item(key = "stats") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StatCard(
                                icon = Icons.Default.Timer,
                                value = stats.todayMinutes,
                                unit = "min",
                                label = "今日阅读",
                                color = Primary,
                                modifier = Modifier.weight(1f),
                            )
                            StatCard(
                                icon = Icons.Default.LocalFireDepartment,
                                value = stats.streakDays,
                                unit = "天",
                                label = "连续打卡",
                                color = Warning,
                                pulse = stats.streakDays > 0,
                                modifier = Modifier.weight(1f),
                            )
                            StatCard(
                                icon = Icons.Outlined.MenuBook,
                                value = stats.totalBooks,
                                label = "累计书籍",
                                color = Primary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // ── 书架分类筛选（横滑 chips）──
                    if (uiState.books.isNotEmpty()) {
                        item(key = "category_chips") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 2.dp),
                            ) {
                                item(key = "cat_all") {
                                    FilterChip(
                                        selected = effectiveCategory == null,
                                        onClick = { viewModel.setCategory(null) },
                                        label = { Text("全部 ${uiState.books.size}") },
                                    )
                                }
                                items(
                                    uiState.categories,
                                    key = { "cat_$it" },
                                ) { cat ->
                                    FilterChip(
                                        selected = effectiveCategory == cat,
                                        onClick = {
                                            viewModel.setCategory(
                                                if (effectiveCategory == cat) null else cat,
                                            )
                                        },
                                        label = {
                                            Text("$cat ${uiState.books.count { it.category == cat }}")
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.isLoading) {
                        // 骨架屏：与 BookCard 同构的占位 + 微光扫过，
                        // 替代居中转圈（感知加载速度更快）
                        items(3, key = { "skeleton_$it" }) { BookCardSkeleton() }
                        if (uiState.loadingMessage.isNotBlank()) {
                            item {
                                Text(
                                    uiState.loadingMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }
                    } else if (uiState.books.isEmpty()) {
                        item(key = "empty") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                EmptyState(
                                    icon = Icons.Outlined.MenuBook,
                                    title = "书架为空",
                                    subtitle = "导入 EPUB/TXT，或从下方一键下载英文经典名著",
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { filePicker.launch(arrayOf("application/epub+zip", "text/plain", "*/*")) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("导入书籍", fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                TextButton(onClick = viewModel::showUrlDialog) {
                                    Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("从网址导入")
                                }
                            }
                        }
                    } else if (effectiveCategory != null) {
                        // ── 单分类视图 ──
                        val catBooks = uiState.books.filter { it.category == effectiveCategory }
                        if (catBooks.isEmpty()) {
                            item(key = "cat_empty") {
                                EmptyState(
                                    icon = Icons.Outlined.MenuBook,
                                    title = "「$effectiveCategory」暂无书籍",
                                    subtitle = "通过书卡右侧菜单把书移到这个分类",
                                )
                            }
                        } else {
                            items(catBooks, key = { it.id }) { book ->
                                BookCard(
                                    book = book,
                                    onClick = { onBookClick(book.id) },
                                    onDelete = { viewModel.deleteBook(book.id) },
                                    onArchive = {
                                        viewModel.archiveBook(book.id)
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = "已归档《${book.title.take(12)}》",
                                                actionLabel = "撤销",
                                                duration = SnackbarDuration.Short,
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                viewModel.unarchiveBook(book.id)
                                            }
                                        }
                                    },
                                    onCategorize = { cat ->
                                        viewModel.updateBookCategory(book.id, cat)
                                    },
                                    categories = uiState.categories,
                                    modifier = Modifier.animateItemPlacement(),
                                )
                            }
                        }
                    } else {
                        // ── 全部分类：分组展示（书架式）──
                        uiState.categories.forEach { cat ->
                            val catBooks = uiState.books.filter { it.category == cat }
                            if (catBooks.isNotEmpty()) {
                                item(key = "cat_header_$cat") {
                                    CategoryHeader(
                                        category = cat,
                                        count = catBooks.size,
                                    )
                                }
                                items(catBooks, key = { it.id }) { book ->
                                    BookCard(
                                        book = book,
                                        onClick = { onBookClick(book.id) },
                                        onDelete = { viewModel.deleteBook(book.id) },
                                        onArchive = {
                                            viewModel.archiveBook(book.id)
                                            scope.launch {
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "已归档《${book.title.take(12)}》",
                                                    actionLabel = "撤销",
                                                    duration = SnackbarDuration.Short,
                                                )
                                                if (result == SnackbarResult.ActionPerformed) {
                                                    viewModel.unarchiveBook(book.id)
                                                }
                                            }
                                        },
                                        onCategorize = { c ->
                                            viewModel.updateBookCategory(book.id, c)
                                        },
                                        categories = uiState.categories,
                                        modifier = Modifier.animateItemPlacement(),
                                    )
                                }
                            }
                        }
                    }

                    // ── 英文经典名著：横滑卡片（一屏内收起，不再整列铺开）──
                    item(key = "classics_header") {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("英文经典名著 · Project Gutenberg", style = SectionTitle)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "免费公版英文长篇小说，下载后可离线阅读",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    item(key = "classics_row") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(uiState.classics, key = { it.id }) { classic ->
                                ClassicBookCard(
                                    classic = classic,
                                    downloading = classic.id in uiState.downloadingClassicIds,
                                    owned = classic.id in uiState.ownedClassicIds,
                                    onDownload = { viewModel.downloadClassic(classic) },
                                )
                            }
                        }
                    }

                    item(key = "bottom_space") { Spacer(modifier = Modifier.height(80.dp)) }
                }
            } else {
                ArticleSquareScreen(
                    sources = uiState.articleSources,
                    selectedSource = uiState.selectedSource,
                    articles = uiState.articles,
                    articlesLoading = uiState.articlesLoading,
                    articlesError = uiState.articlesError,
                    addedLinks = viewModel.addedArticleLinks.collectAsState().value,
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
                    Text(
                        "输入英文文章网址，自动抓取正文",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookCard(
    book: Book,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    onCategorize: (String) -> Unit = {},
    categories: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    // 删除是永久操作（级联清书签/高亮/进度/统计）：二次确认防误触
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 分类编辑弹窗（书架分类入口）
    var showCategoryDialog by remember { mutableStateOf(false) }

    // 滑动归档（Gmail 式）：左右滑均可归档，动作走可撤销入口（调用方配 Snackbar 撤销）。
    // confirmValueChange 返回 false 让卡片弹回原位——真正的移除由数据流刷新驱动
    val dismissState = rememberDismissState(
        confirmValueChange = { value ->
            if (value != DismissValue.Default) {
                onArchive()
            }
            false
        },
    )

    SwipeToDismiss(
        state = dismissState,
        background = {
            // 滑动背景：品牌绿 + 归档图标（两个方向共用同一动作）
            val direction = dismissState.dismissDirection
            val alignment = if (direction == DismissDirection.StartToEnd) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Success.copy(alpha = 0.18f))
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Archive,
                        contentDescription = "归档",
                        tint = Success,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("归档", color = Success, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        dismissContent = {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),

        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 封面：EPUB 内嵌封面优先，缺失时用书名哈希生成的插图封面
                BookCover(
                    title = book.title,
                    coverPath = book.coverPath,
                    author = book.author,
                    modifier = Modifier.size(58.dp, 80.dp),
                )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 进度条
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = book.readProgress,
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${(book.readProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Primary,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // 分类标签 + 字数（一行收起，节省纵向空间）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = Primary.copy(alpha = 0.10f),
                    ) {
                        Text(
                            text = book.category.ifBlank { "未分类" },
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (book.totalWords > 0) {
                        Text(
                            text = "${book.totalWords} 词",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // issue 9.2：EPUB 因 MAX_TOTAL_CHARS 被截断时，卡片给明确提示，不再静默丢正文
                if (book.isTruncated) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (book.originalCharCount > 0) {
                            "正文超上限，已截断（保留约 ${book.originalCharCount / 1000}k 字）"
                        } else {
                            "正文超上限，已截断"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Error,
                    )
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("移至分类（当前：${book.category.ifBlank { "未分类" }}）") },
                        onClick = { showMenu = false; showCategoryDialog = true },
                        leadingIcon = { Icon(Icons.Default.Category, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("移至归档") },
                        onClick = { onArchive(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Archive, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { showMenu = false; showDeleteConfirm = true },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    )
                }
            }
        }
        }
    }
    ) // 关闭 SwipeToDismiss

    if (showCategoryDialog) {
        CategoryEditDialog(
            current = book.category.ifBlank { "未分类" },
            categories = categories,
            onDismiss = { showCategoryDialog = false },
            onConfirm = { cat ->
                showCategoryDialog = false
                onCategorize(cat)
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除《${book.title}》？") },
            text = { Text("将同时删除该书的书签、高亮、阅读进度和统计，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 经典名著卡片（横滑书架用）：竖排紧凑卡片——图标 + 书名 + 作者 +
 * 下载状态，宽度固定参与 LazyRow。
 */
@Composable
fun ClassicBookCard(
    classic: ClassicBook,
    downloading: Boolean,
    owned: Boolean,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier.width(216.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (owned) Primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = PrimaryLight,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("📖", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        classic.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        classic.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!classic.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    classic.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(min = 30.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            when {
                downloading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Primary,
                    )
                    Text(
                        "下载中...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                owned -> Text(
                    "已下载 ✓",
                    style = MaterialTheme.typography.labelMedium,
                    color = Primary,
                )
                else -> Button(
                    onClick = onDownload,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("下载", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 分类组头：书架分组标题（分类名 + 数量 + 分隔线）。 */
@Composable
private fun CategoryHeader(category: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Category,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = category,
            style = SectionTitle,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$count 本",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Divider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
    }
}

/**
 * 分类编辑弹窗：预设常用分类 + 已有分类 chips + 自定义输入。
 * 确认回调最终分类名（空输入由仓库层归一化为"未分类"）。
 */
@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
private fun CategoryEditDialog(
    current: String,
    categories: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val presets = remember(categories) {
        (listOf("未分类", "经典名著", "小说", "非虚构", "文章", "教材", "科技") + categories)
            .distinct()
    }
    var custom by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移至分类") },
        text = {
            Column {
                Text(
                    "点选已有分类，或输入自定义分类名",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = custom == preset,
                            onClick = { custom = preset },
                            label = { Text(preset) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.take(12) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("分类名（12 字内）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = { Icon(Icons.Default.Category, null) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(custom) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ── 骨架屏组件（加载态占位，与真实卡片同构） ──────────
@Composable
private fun BookCardSkeleton() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp, 80.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .shimmer(),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.65f)
                        .height(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmer(),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmer(),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .shimmer(),
                )
            }
        }
    }
}

@Composable
private fun ArticleItemSkeleton() {
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
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when (source.category) {
                    com.eareyereading.domain.model.SourceCategory.NEWS -> Color(0xFF5B7FFF)
                    com.eareyereading.domain.model.SourceCategory.TECH -> Color(0xFF00C853)
                    com.eareyereading.domain.model.SourceCategory.SCIENCE -> Color(0xFFFF9800)
                    com.eareyereading.domain.model.SourceCategory.CULTURE -> Color(0xFFE91E63)
                    else -> Color(0xFF9E9E9E)
                }.copy(alpha = 0.15f),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = source.icon ?: source.name.take(1),
                        style = MaterialTheme.typography.titleMedium,
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
    val difficultyColors = listOf(
        L4 to "⭐ 入门",
        L2 to "⭐⭐ 简单",
        Warning to "⭐⭐⭐ 中等",
        L1 to "⭐⭐⭐⭐ 较难",
        L1 to "⭐⭐⭐⭐⭐ 困难",
    )
    val (color, label) = difficultyColors.getOrElse(level - 1) { Accent to "⭐ 入门" }
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.12f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
