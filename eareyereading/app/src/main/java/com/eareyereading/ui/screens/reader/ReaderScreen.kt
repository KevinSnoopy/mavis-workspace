package com.eareyereading.ui.screens.reader

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.domain.model.ReadingTheme
import com.eareyereading.ui.theme.*
import com.eareyereading.util.notificationPermissionGranted
import com.eareyereading.util.rememberNotificationPermissionRequester

/**
 * 阅读页主入口：顶栏 + 各阅读模式视图调度 + 弹窗/抽屉编排。
 *
 * 重构后职责仅剩"编排"：ViewModel 接线、主题/配色推导、状态栏着色、
 * chrome 沉浸态控制、Scaffold 叠加层布局。具体 UI 块拆到同包 internal 文件：
 *  - [ReaderTopBar]：顶部工具栏与动作
 *  - [ReaderContentDispatcher]：正文层加载骨架 + 阅读模式视图分发
 *  - [ReaderDialogsSection]：弹窗/抽屉编排
 *  - [ReaderChromeController]：chrome 显隐状态与滚动联动
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    // 收集 ViewModel 的一次性提示（TTS 初始化失败等）
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.toastMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 收集 TTS 引擎引导弹窗事件
    var ttsPrompt by remember { mutableStateOf<TtsInstallPrompt?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.ttsInstallPrompt.collect { prompt ->
            ttsPrompt = prompt
        }
    }

    // 配置变更（旋转/深色切换）也会触发 onDispose，但 VM 并不销毁：
    // 此时跳过 cleanup，朗读不再被旋转打断；真退出（返回/VM 销毁）
    // 仍由 onCleared -> cleanup() 兜底落库停播
    val activity = context as? android.app.Activity
    DisposableEffect(Unit) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                viewModel.cleanup()
            }
        }
    }

    // 书籍不存在（深链失效/已删除）：提示已由 VM 发出，这里自动返回，
    // 不停留在"加载中..."的死页面
    LaunchedEffect(uiState.book, uiState.isLoading) {
        if (uiState.book == null && !uiState.isLoading) {
            onBack()
        }
    }

    // 书内阅读主题 + 系统深色共同决定整套 Material 配色：
    // 弹窗/菜单/滑杆等组件颜色与纸面一致（此前只换正文背景，
    // 暗色纸面上会弹出纯白对话框）
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val readerScheme = readingColorScheme(uiState.theme, systemDark)
    val accentColor = readerAccentColor(uiState.theme, systemDark)

    val backgroundColor = when (uiState.theme) {
        ReadingTheme.LIGHT -> readerScheme.background
        ReadingTheme.DARK -> DarkBg
        ReadingTheme.SEPIA -> SepiaBg
    }
    val textColor = when (uiState.theme) {
        ReadingTheme.LIGHT -> readerScheme.onBackground
        ReadingTheme.DARK -> DarkText
        ReadingTheme.SEPIA -> SepiaText
    }

    // 状态栏与阅读纸面同色：App 级主题的 SideEffect 只看全局设置，
    // 书内切 DARK/SEPIA 时状态栏会残留全局背景色，顶部一条色带割裂
    val statusBarView = LocalView.current
    SideEffect {
        val window = (statusBarView.context as? android.app.Activity)?.window ?: return@SideEffect
        window.statusBarColor = backgroundColor.toArgb()
        androidx.core.view.WindowCompat.getInsetsController(window, statusBarView)
            .isAppearanceLightStatusBars = !systemDark && uiState.theme != ReadingTheme.DARK
    }

    // issue 3.8：阅读沉浸态。点正文空白切换显隐；滚动短暂显示后自动收起；
    // 弹窗/选词/朗读等需要操作时强制常亮。
    // forceChrome：需要常亮 chrome 的强状态（弹窗/选词/朗读/自动朗读/速读/章节目录）
    val forceChrome = uiState.showWordDialog || uiState.showModeSelector || uiState.showSettings ||
        uiState.showChapterNav || uiState.isTtsPlaying || uiState.isAutoReading || uiState.isPlaying
    val chromeController = rememberReaderChromeController(forceChrome)
    LaunchedEffect(forceChrome) {
        chromeController.applyForceChrome()
    }

    // issue 5.1：阅读完成（读到书末）也作为通知权限申请入口。
    // 到达最后一篇时弹一条 Snackbar，带"开启通知"动作；已授权则不打扰。
    val snackbarHostState = remember { SnackbarHostState() }
    val requestNotifications = rememberNotificationPermissionRequester()
    // 每本书会话只提示一次（到达书末即记录），切换书时重置
    var bookEndPrompted by remember(uiState.book?.id) { mutableStateOf(false) }
    LaunchedEffect(uiState.currentParagraphIndex, uiState.book?.id) {
        val paras = uiState.paragraphs
        if (!bookEndPrompted && paras.isNotEmpty() &&
            uiState.currentParagraphIndex == paras.size - 1
        ) {
            bookEndPrompted = true
            if (notificationPermissionGranted(context)) return@LaunchedEffect
            val action = snackbarHostState.showSnackbar(
                message = "已读到本书末尾，开启通知不错过每日复习",
                actionLabel = "开启通知",
                duration = SnackbarDuration.Short,
            )
            if (action == SnackbarResult.ActionPerformed) requestNotifications()
        }
    }

    // 衬线字体 + 主题强调色注入：uiState.serifFont 驱动全阅读器正文字形，
    // MaterialTheme 覆盖让弹窗/菜单/滑杆等组件配色跟随书内阅读主题
    CompositionLocalProvider(
        LocalReaderFontFamily provides if (uiState.serifFont) FontFamily.Serif else FontFamily.Default,
        LocalReaderAccent provides accentColor,
    ) {
    MaterialTheme(
        colorScheme = readerScheme,
        typography = MaterialTheme.typography,
    ) {
    Scaffold(
        // issue 3.8（地基）：正文占满物理边缘，不再被根 Scaffold 额外预留系统栏。
        contentWindowInsets = WindowInsets(0),
        // issue 5.1：阅读完成入口依托 Snackbar 展示
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // 详情页始终保持沉浸态布局：正文区域恒定预留系统栏避让空间，
        // 不随 chrome 显隐变化。工具栏（TopAppBar/BottomBar）作为叠加层
        // （overlay）在正文之上显示/隐藏，不再占用 Scaffold 的 topBar/
        // bottomBar 槽位——否则槽位高度会随 AnimatedVisibility 变化，
        // 驱动 Scaffold 传入的 padding 变化 → BoxWithConstraints.maxHeight
        // 变化 → pageBudgetPx 变化 → paginateBook 整书重新分页（重排）。
        // 上下滚动模式（NormalReadingView）本就自适应视口大小，亦不受影响。
        val insetsDensity = LocalDensity.current
        val statusBarPad = with(insetsDensity) { WindowInsets.statusBars.getTop(this).toDp() }
        val navBarPad = with(insetsDensity) { WindowInsets.navigationBars.getBottom(this).toDp() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                // issue 3.8：滚动短暂显示 chrome（scroll-driven reveal）：nestedScroll 接到
                // 当前渲染态 LazyColumn 的滚动增量，统一触发"显示 + 延时自动收起"
                .nestedScroll(chromeController.scrollReveal)
                // issue 3.8：轻击正文空白切换 chrome 显隐（段落文字上的点选/挖空事件由
                // 各渲染态的内层手势先消费，这里只收到未被消费的"空白处点击"，符合方案 C）
                .pointerInput(Unit) {
                    detectTapGestures { chromeController.toggle() }
                }
                .padding(padding),
        ) {
            // 正文层：恒定沉浸态系统栏避让，不随 chrome 显隐变化，避免触发重排。
            // chrome 显示时工具栏叠加在此空间上方，不遮挡正文首行/末行。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = statusBarPad, bottom = navBarPad)
                    .padding(horizontal = 20.dp),
            ) {
                ReaderContentDispatcher(
                    uiState = uiState,
                    textColor = textColor,
                    accentColor = accentColor,
                    viewModel = viewModel,
                    onToggleChrome = chromeController::toggle,
                )
            }
            // 顶部工具栏叠加层：AnimatedVisibility 滑动+淡入，不占用布局尺寸
            // （overlay），不会驱动正文 BoxWithConstraints.maxHeight 变化 → 不重排。
            // TopAppBar 自带 statusBars inset 避让，无需额外 padding。
            AnimatedVisibility(
                visible = chromeController.visible,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                ReaderTopBar(
                    title = uiState.book?.title ?: "加载中...",
                    backgroundColor = backgroundColor,
                    textColor = textColor,
                    isTtsPlaying = uiState.isTtsPlaying,
                    isTranslating = uiState.isTranslating,
                    showTranslation = uiState.showTranslation,
                    isPlaying = uiState.isPlaying,
                    isAutoReading = uiState.isAutoReading,
                    isBookmarked = uiState.currentParagraphIndex in uiState.bookmarkedParagraphs,
                    onBack = {
                        viewModel.saveProgress()
                        onBack()
                    },
                    onToggleTts = viewModel::toggleTts,
                    onToggleTranslation = viewModel::toggleTranslation,
                    onTogglePlay = { viewModel.togglePlay() },
                    onToggleBookmark = { viewModel.toggleBookmark(uiState.currentParagraphIndex) },
                    onShowModeSelector = viewModel::showModeSelector,
                    onToggleAutoRead = viewModel::toggleAutoRead,
                    onToggleChapterNav = viewModel::toggleChapterNav,
                    onToggleWordLevelColors = viewModel::toggleWordLevelColors,
                    showWordLevelColors = uiState.showWordLevelColors,
                    onToggleKnownWordsHighlight = viewModel::toggleKnownWordsHighlight,
                    showKnownWordsHighlight = uiState.showKnownWordsHighlight,
                    onToggleSettings = viewModel::toggleSettings,
                )
            }
            // 底部工具栏叠加层：ReadingBottomBar 自带 navigationBarsPadding 避让。
            AnimatedVisibility(
                visible = chromeController.visible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                ReadingBottomBar(
                    uiState = uiState,
                    onPrev = viewModel::prevParagraph,
                    onNext = viewModel::nextParagraph,
                    onSeek = viewModel::goToParagraph,
                    textColor = textColor,
                    onFontDelta = viewModel::adjustFontSize,
                    onCycleTheme = viewModel::cycleReadingTheme,
                    onToggleSerif = viewModel::toggleSerifFont,
                )
            }
        }
    } // 关闭 Scaffold
    } // 关闭 MaterialTheme(阅读主题配色)
    } // 关闭 CompositionLocalProvider(衬线字体/强调色)

    ReaderDialogsSection(
        uiState = uiState,
        viewModel = viewModel,
        ttsPrompt = ttsPrompt,
        onTtsPromptAction = { action ->
            viewModel.onTtsInstallAction(action)
            // 下载内置模型时保持弹窗打开，页内直接显示下载进度
            // （原实现点下载立即关弹窗，进度只在设置页可见）；
            // 下载结束后进度归空，弹窗回到常规按钮态由用户关闭
            if (action !is TtsInstallAction.DownloadEmbeddedTts) {
                ttsPrompt = null
            }
        },
        onTtsPromptDismiss = {
            viewModel.onTtsInstallAction(TtsInstallAction.Dismiss)
            ttsPrompt = null
        },
    )
}
