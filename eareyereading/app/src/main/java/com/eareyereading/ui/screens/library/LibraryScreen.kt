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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.ui.components.BookCover
import com.eareyereading.ui.components.EmptyState
import com.eareyereading.ui.components.StatCard
import com.eareyereading.ui.components.category.AddBookFlowSheet
import com.eareyereading.ui.components.category.Category
import com.eareyereading.ui.components.category.CategoryEditSheet
import com.eareyereading.ui.components.category.CategoryIcon
import com.eareyereading.ui.components.category.CategoryManageSheet
import com.eareyereading.ui.components.category.CategoryStrip
import com.eareyereading.ui.components.category.derivedColorFor
import com.eareyereading.ui.components.category.derivedIconFor
import com.eareyereading.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 书架主屏：搜索、分类条、书籍/文章 Tab、导入流程（URL/文件/经典书）与分类管理入口。
 */
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

    // v2 新增：分类管理 / 新建分类 sheet 状态
    var showCategoryManage by rememberSaveable { mutableStateOf(false) }
    var showCategoryEdit by rememberSaveable { mutableStateOf(false) }
    // 正在编辑的分类（null = 新建模式）
    var editingCategory by remember { mutableStateOf<Category?>(null) }

    // v2：真实分类合成 = 派生分类（来自书籍 category 字段）+ 用户自建（meta 有但书无）。
    // 图标/颜色取用户元数据，缺省按名称派生稳定默认（同分类永远同色同图标）
    val allCategories = remember(
        uiState.categories,
        uiState.customCategories,
        uiState.categoryMeta,
        uiState.books,
    ) {
        val derived = uiState.categories
        val custom = uiState.customCategories.filter { it !in derived }
        (derived + custom).map { name ->
            val meta = uiState.categoryMeta[name]
            Category(
                name = name,
                bookCount = uiState.books.count { it.category == name },
                icon = meta?.let { m ->
                    runCatching { CategoryIcon.valueOf(m.icon) }.getOrDefault(derivedIconFor(name))
                } ?: derivedIconFor(name),
                color = meta?.let { m -> Color(m.color) } ?: derivedColorFor(name),
            )
        }.sortedWith(
            // 拖动排序：有 order 元数据的按 order 升序；无元数据的排最后按名称稳定排列
            compareBy(
                { uiState.categoryMeta[it.name]?.order ?: Int.MAX_VALUE },
                { it.name },
            ),
        )
    }

    // 分类名 → 书籍数（「移至分类」对话框卡片上显示计数用）
    val bookCountByName = remember(uiState.books) {
        uiState.books.groupingBy { it.category.ifBlank { "未分类" } }.eachCount()
    }

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
                    // v2：分类管理入口（菜单图标）
                    IconButton(onClick = { showCategoryManage = true }) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "分类管理",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
            // 改版A（M3 Extended FAB 规范）：primary-container 配色（比 primary
            // 柔和），文案由当前 Tab 决定——原实现两个 Tab 共用"导入书籍"，
            // 文章 Tab 下语义错位（P4 反馈的阻塞级问题）
            ExtendedFloatingActionButton(
                onClick = {
                    if (uiState.selectedTab == 0) {
                        filePicker.launch(arrayOf("application/epub+zip", "text/plain", "*/*"))
                    } else {
                        viewModel.showUrlDialog()
                    }
                },
                icon = { Icon(Icons.Default.Add, "导入") },
                text = { Text(if (uiState.selectedTab == 0) "导入书籍" else "添加文章") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
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
                                        coverStyle = book.coverStyle,
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

            // v2：分类胶囊条（搜索框下方，Tab 之前）
            // 真实分类 = 派生 + 自建；选中态接入 ViewModel 筛选（再点一次取消）
            CategoryStrip(
                categories = allCategories,
                selected = uiState.selectedCategory,
                onSelect = { name ->
                    viewModel.setCategory(
                        if (name == null || uiState.selectedCategory == name) null else name,
                    )
                },
                onAddCategory = {
                    editingCategory = null
                    showCategoryEdit = true
                },
                totalCount = uiState.books.size,
            )

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
                            // streak 卡底色升为 primary-container：色彩承担语义
                            //（绿=活跃），是用户情感焦点（多邻国火焰心理学）
                            StatCard(
                                icon = Icons.Default.LocalFireDepartment,
                                value = stats.streakDays,
                                unit = "天",
                                label = "连续打卡",
                                color = Warning,
                                pulse = stats.streakDays > 0,
                                highlight = true,
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
                                    bookCounts = bookCountByName,
                                    categoryMeta = uiState.categoryMeta,
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
                                        bookCounts = bookCountByName,
                                        categoryMeta = uiState.categoryMeta,
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

                    // 改版A：FAB 遮挡修复——列表末尾留 96dp（FAB 56dp + 安全距离），
                    // 最后一项的进度百分比不再被悬浮按钮盖住
                    item(key = "bottom_space") { Spacer(modifier = Modifier.height(96.dp)) }
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

    // v2：分类管理 sheet（TopAppBar 菜单按钮触发）
    if (showCategoryManage) {
        CategoryManageSheet(
            categories = allCategories,
            onEdit = { cat ->
                showCategoryManage = false
                editingCategory = cat
                showCategoryEdit = true
            },
            onAdd = {
                showCategoryManage = false
                editingCategory = null
                showCategoryEdit = true
            },
            onDelete = { cat ->
                // 仅删除分类元数据；书籍的 category 字符串保留
                viewModel.deleteCategoryMeta(cat.name)
                if (uiState.selectedCategory == cat.name) viewModel.setCategory(null)
            },
            onReorder = { reordered ->
                // 拖动排序：按新顺序持久化 order 元数据
                viewModel.reorderCategories(reordered.map { it.name })
            },
            onDismiss = { showCategoryManage = false },
        )
    }

    // v2：新建 / 编辑分类 sheet（「+ 新建分类」或管理列表编辑触发）
    if (showCategoryEdit) {
        CategoryEditSheet(
            initial = editingCategory,
            onSave = { cat ->
                viewModel.saveCategoryMeta(
                    name = cat.name,
                    icon = cat.icon.name,
                    color = cat.color.toArgb().toLong(),
                )
                showCategoryEdit = false
            },
            onDismiss = { showCategoryEdit = false },
        )
    }

    // v2：导入后完善信息（选分类 + 选封面），EPUB 文件导入成功后触发
    val pendingRefine = uiState.pendingRefineBook
    if (pendingRefine != null) {
        AddBookFlowSheet(
            categories = allCategories,
            initialTitle = pendingRefine.title,
            initialAuthor = pendingRefine.author,
            onComplete = { title, _, _, categoryName, coverId ->
                // 书名已在步骤 1 预填并可校对；分类/封面写库
                viewModel.finishBookRefine(
                    category = categoryName ?: "",
                    coverStyle = coverId,
                )
            },
            onDismiss = { viewModel.skipBookRefine() },
        )
    }
}
