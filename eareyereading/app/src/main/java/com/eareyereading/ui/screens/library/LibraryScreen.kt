package com.eareyereading.ui.screens.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.ui.components.category.Category
import com.eareyereading.ui.components.category.CategoryStrip

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

    // v2 新增：分类管理 / 新建分类 sheet 状态
    var showCategoryManage by rememberSaveable { mutableStateOf(false) }
    var showCategoryEdit by rememberSaveable { mutableStateOf(false) }
    // 正在编辑的分类（null = 新建模式）
    var editingCategory by remember { mutableStateOf<Category?>(null) }

    // v2：真实分类合成 = 派生分类（来自书籍 category 字段）+ 用户自建（meta 有但书无）。
    val allCategories = rememberMergedCategories(
        derivedCategories = uiState.categories,
        customCategories = uiState.customCategories,
        categoryMeta = uiState.categoryMeta,
        books = uiState.books,
    )

    // 导入的最终结果（成功/失败）用 Snackbar 呈现
    val snackbarHostState = remember { SnackbarHostState() }
    // issue 11.16：Snackbar 同字符串去重——以 isLoading + messageEventId 为 key
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
            LibraryTopBar(onNavigateToSettings = onNavigateToSettings) {
                showCategoryManage = true
            }
        },
        floatingActionButton = {
            LibraryFab(
                selectedTab = uiState.selectedTab,
                onPickFile = {
                    filePicker.launch(arrayOf("application/epub+zip", "text/plain", "*/*"))
                },
                onShowUrlDialog = viewModel::showUrlDialog,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
        ) {
            // M3 SearchBar
            LibrarySearchBar(
                searchQuery = uiState.searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                books = uiState.books,
                onBookClick = onBookClick,
            )

            // v2：分类胶囊条（搜索框下方，Tab 之前）
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

            // Tab 切换：标准 M3 TabRow
            LibraryTabRow(
                selectedTab = uiState.selectedTab,
                onTabSelected = viewModel::setTab,
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.selectedTab == 0) {
                LibraryBookTabContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    onBookClick = onBookClick,
                    snackbarHostState = snackbarHostState,
                    onPickFile = {
                        filePicker.launch(arrayOf("application/epub+zip", "text/plain", "*/*"))
                    },
                )
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
        LibraryUrlImportDialog(
            urlInput = uiState.urlInput,
            onUrlInputChange = viewModel::onUrlInputChange,
            onImport = viewModel::importFromUrl,
            onDismiss = viewModel::hideUrlDialog,
        )
    }

    // v2：分类管理 sheet
    if (showCategoryManage) {
        LibraryCategoryManageSheetWrapper(
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
                viewModel.deleteCategoryMeta(cat.name)
                if (uiState.selectedCategory == cat.name) viewModel.setCategory(null)
            },
            onReorder = { reordered ->
                viewModel.reorderCategories(reordered.map { it.name })
            },
            onDismiss = { showCategoryManage = false },
        )
    }

    // v2：新建 / 编辑分类 sheet
    if (showCategoryEdit) {
        LibraryCategoryEditSheetWrapper(
            initial = editingCategory,
            onSave = { name, icon, color ->
                viewModel.saveCategoryMeta(name, icon, color)
                showCategoryEdit = false
            },
            onDismiss = { showCategoryEdit = false },
        )
    }

    // v2：导入后完善信息（选分类 + 选封面）
    val pendingRefine = uiState.pendingRefineBook
    if (pendingRefine != null) {
        LibraryBookRefineSheetWrapper(
            categories = allCategories,
            pendingBook = pendingRefine,
            onComplete = { categoryName, coverId ->
                viewModel.finishBookRefine(
                    category = categoryName ?: "",
                    coverStyle = coverId,
                )
            },
            onDismiss = { viewModel.skipBookRefine() },
        )
    }
}

// ── TopAppBar ────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    onNavigateToSettings: () -> Unit,
    onShowCategoryManage: () -> Unit,
) {
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
            IconButton(onClick = onShowCategoryManage) {
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
}

// ── FAB ──────────────────────────────────────────────

@Composable
private fun LibraryFab(
    selectedTab: Int,
    onPickFile: () -> Unit,
    onShowUrlDialog: () -> Unit,
) {
    // 改版A（M3 Extended FAB 规范）：primary-container 配色（比 primary 柔和），
    // 文案由当前 Tab 决定——原实现两个 Tab 共用"导入书籍"，
    // 文章 Tab 下语义错位（P4 反馈的阻塞级问题）
    ExtendedFloatingActionButton(
        onClick = {
            if (selectedTab == 0) {
                onPickFile()
            } else {
                onShowUrlDialog()
            }
        },
        icon = { Icon(Icons.Default.Add, "导入") },
        text = { Text(if (selectedTab == 0) "导入书籍" else "添加文章") },
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

// ── TabRow ───────────────────────────────────────────

@Composable
private fun LibraryTabRow(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedTab,
    ) {
        Tab(
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            text = { Text("书籍") },
            selectedContentColor = MaterialTheme.colorScheme.primary,
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Tab(
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            text = { Text("文章") },
            selectedContentColor = MaterialTheme.colorScheme.primary,
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
