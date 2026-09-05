package com.eareyereading.ui.screens.reader

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.eareyereading.util.notificationPermissionGranted
import com.eareyereading.util.rememberNotificationPermissionRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.eareyereading.domain.model.ReadingMode
import com.eareyereading.domain.model.ReadingTheme
import com.eareyereading.ui.components.shimmer
import com.eareyereading.ui.theme.*
import com.eareyereading.util.BookImages
import com.eareyereading.util.ClozeWord
import com.eareyereading.util.CollinsClassifier
import com.eareyereading.util.PosTagger
import com.eareyereading.util.PosTag
import com.eareyereading.util.WordAnalyzer
import com.eareyereading.util.CollinsClassifier.WordLevel

/**
 * 阅读器正文字体（衬线切换）：ReaderScreen 顶层 provide，
 * 所有阅读模式视图经 [readerParagraphStyle] 消费，一处切换全局生效。
 */
private val LocalReaderFontFamily = androidx.compose.runtime.staticCompositionLocalOf {
    FontFamily.Default
}

/**
 * 阅读器正文强调色（译文/高亮底/模式标签）：随（书内主题 + 系统深色）变化——
 * 深色下用更亮的赤陶 Accent，浅色/护眼用暖棕 Primary。
 * 深层视图（ReaderParagraphBlock/SplitReadingView 等）经
 * `LocalReaderAccent.current` 消费，免逐层透传参数。
 */
private val LocalReaderAccent = androidx.compose.runtime.staticCompositionLocalOf { Primary }

/**
 * 段落正文样式统一入口：字号 + 行高（倍数）+ 可选衬线。
 * 保证普通/分栏/回译/成分分析等渲染视图的字形一致切换。
 */
@Composable
private fun readerParagraphStyle(fontSize: Int, lineMultiplier: Float = 1.8f): TextStyle = TextStyle(
    fontSize = fontSize.sp,
    lineHeight = (fontSize * lineMultiplier).sp,
    fontFamily = LocalReaderFontFamily.current,
)

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
    var ttsPrompt by remember { mutableStateOf<com.eareyereading.ui.screens.reader.TtsInstallPrompt?>(null) }
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
    val statusBarView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (statusBarView.context as? android.app.Activity)?.window ?: return@SideEffect
        window.statusBarColor = backgroundColor.toArgb()
        androidx.core.view.WindowCompat.getInsetsController(window, statusBarView)
            .isAppearanceLightStatusBars = !systemDark && uiState.theme != ReadingTheme.DARK
    }

    // issue 3.8：阅读沉浸态。点正文空白切换显隐；滚动短暂显示后自动收起；
    // 弹窗/选词/朗读等需要操作时强制常亮。
    // 进书默认显示：旧值 false 时用户打开书只看到纯正文，顶栏的翻译/阅读
    // 模式入口与底栏快捷设置全部不可见，且"点空白唤出"无任何提示——
    // 用户反馈"仿书页左右翻译等设置丢了"即此。改为进书先展示，
    // 开始滚动阅读后自动收起进沉浸态，可发现性与沉浸感兼得。
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    val autoHideScope = rememberCoroutineScope()
    // 自动隐藏延迟任务：滚动/临时操作后无动作，延时收起 chrome（回到沉浸态）
    var autoHideJob by remember { mutableStateOf<Job?>(null) }

    // forceChrome：需要常亮 chrome 的强状态（弹窗/选词/朗读/自动朗读/速读/章节目录）
    val forceChrome = uiState.showWordDialog || uiState.showModeSelector || uiState.showSettings ||
        uiState.showChapterNav || uiState.isTtsPlaying || uiState.isAutoReading || uiState.isPlaying
    // NestedScrollConnection 是 remember 一次创建，只能拿到"创建当下"的引用，
    // 用 rememberUpdatedState 让它始终读到最新的 forceChrome / reveal 函数。
    val currentForceChrome by rememberUpdatedState(forceChrome)

    // 滚动短暂显示 chrome：每次滚动都重置自动隐藏计时器（scroll-driven reveal）
    val revealChromeTemporarily: () -> Unit = {
        chromeVisible = true
        autoHideJob?.cancel()
        // 自动朗读/弹窗等强状态期间的滚动也被强制显示，但收起交给 force 分支统一控制
        autoHideJob = autoHideScope.launch {
            delay(2500)
            if (!currentForceChrome) chromeVisible = false
        }
    }
    val currentReveal by rememberUpdatedState(revealChromeTemporarily)

    val chromeScrollReveal = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!currentForceChrome && available.y != 0f) currentReveal()
                return Offset.Zero
            }
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!currentForceChrome && (consumed.y != 0f || available.y != 0f)) currentReveal()
                return Offset.Zero
            }
        }
    }

    // issue 3.8（保全诉求 #5）：弹窗 / 选词 / TTS 播放 / 自动朗读 / 速读等需要操作时强制常亮 chrome，
    // 且取消自动隐藏；强状态解除后显隐恢复由滚动/点击驱动。
    LaunchedEffect(forceChrome) {
        if (forceChrome) {
            autoHideJob?.cancel()
            autoHideJob = null
            chromeVisible = true
        }
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
    androidx.compose.runtime.CompositionLocalProvider(
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
        topBar = {
            // issue 3.8：chrome 显隐用 AnimatedVisibility（200ms 滑动+淡入，不瞬切）
            AnimatedVisibility(
                visible = chromeVisible,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            ) {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.book?.title ?: "加载中...",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // 跟随阅读主题的背景必须同时指定内容色：
                // 深色主题下默认内容色是深色墨，标题/返回/操作图标会整个看不见
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backgroundColor,
                    titleContentColor = textColor,
                    navigationIconContentColor = textColor,
                    actionIconContentColor = textColor,
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.saveProgress()
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                actions = {
                    // TTS
                    IconButton(onClick = viewModel::toggleTts) {
                        Icon(
                            if (uiState.isTtsPlaying) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            "朗读"
                        )
                    }
                    // 翻译
                    IconButton(onClick = viewModel::toggleTranslation) {
                        if (uiState.isTranslating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = LocalReaderAccent.current,
                            )
                        } else {
                            Icon(
                                if (uiState.showTranslation) Icons.Default.Translate else Icons.Outlined.Translate,
                                "翻译",
                                tint = if (uiState.showTranslation) LocalReaderAccent.current else LocalContentColor.current,
                            )
                        }
                    }
                    // 播放 / 暂停（NORMAL 模式下等价于从当前段开始自动朗读）
                    // §4.6.2 关键重设计：播放是主操作——放大到 28dp + 强调色 +
                    // 实心图标，与其余 24dp 中性图标拉开视觉权重（原图 6 个图标
                    // 权重完全一致，用户无法判断哪个是主操作）
                    IconButton(onClick = { viewModel.togglePlay() }) {
                        Icon(
                            // isTtsPlaying 也要算播放中：挖空/听写等模式走单段朗读
                            if (uiState.isPlaying || uiState.isAutoReading || uiState.isTtsPlaying)
                                Icons.Default.Pause else Icons.Default.PlayArrow,
                            "播放",
                            tint = LocalReaderAccent.current,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    // 书签
                    IconButton(
                        onClick = { viewModel.toggleBookmark(uiState.currentParagraphIndex) },
                    ) {
                        Icon(
                            if (uiState.currentParagraphIndex in uiState.bookmarkedParagraphs)
                                Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            "书签",
                            tint = if (uiState.currentParagraphIndex in uiState.bookmarkedParagraphs)
                                Secondary else LocalContentColor.current,
                        )
                    }
                    // 阅读模式
                    IconButton(onClick = viewModel::showModeSelector) {
                        Icon(Icons.Default.MenuBook, "阅读模式")
                    }
                    // 更多（溢出菜单）
                    var showOverflowMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, "更多")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (uiState.isAutoReading) "停止自动朗读" else "自动朗读")
                                },
                                leadingIcon = { Icon(Icons.Default.Headphones, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.toggleAutoRead()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("目录") },
                                leadingIcon = { Icon(Icons.Default.List, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.toggleChapterNav()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (uiState.showWordLevelColors) "关闭词频颜色" else "开启词频颜色"
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.ColorLens, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.toggleWordLevelColors()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (uiState.showKnownWordsHighlight) "关闭生词高亮" else "开启生词高亮"
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.toggleKnownWordsHighlight()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.toggleSettings()
                                },
                            )
                        }
                    }
                },
            )
            } // 关闭 AnimatedVisibility(topBar)
        },
        bottomBar = {
            // issue 3.8：底部 chrome 同款显隐
            AnimatedVisibility(
                visible = chromeVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
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
        },
    ) { padding ->
        // 沉浸态系统栏避让：chrome 收起后 topBar/bottomBar 槽位高度归零，
        // 正文会顶进状态栏/手势导航条区域，首行文字被时钟电量图标压住。
        // 这里按 chrome 显隐补回系统栏内边距；animateDpAsState 与
        // AnimatedVisibility 的收起动画同节奏过渡，收起过程不跳变。
        val insetsDensity = LocalDensity.current
        val immersiveTopPad by animateDpAsState(
            targetValue = if (chromeVisible) 0.dp
            else with(insetsDensity) { WindowInsets.statusBars.getTop(this).toDp() },
            label = "immersiveTopPad",
        )
        val immersiveBottomPad by animateDpAsState(
            targetValue = if (chromeVisible) 0.dp
            else with(insetsDensity) { WindowInsets.navigationBars.getBottom(this).toDp() },
            label = "immersiveBottomPad",
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                // issue 3.8：滚动短暂显示 chrome（scroll-driven reveal）：nestedScroll 接到
                // 当前渲染态 LazyColumn 的滚动增量，统一触发"显示 + 延时自动收起"
                .nestedScroll(chromeScrollReveal)
                // issue 3.8：轻击正文空白切换 chrome 显隐（段落文字上的点选/挖空事件由
                // 各渲染态的内层手势先消费，这里只收到未被消费的"空白处点击"，符合方案 C）
                .pointerInput(Unit) {
                    detectTapGestures { chromeVisible = !chromeVisible }
                }
                .padding(padding)
                // 沉浸态补回系统栏避让（chrome 显示时这两段为 0，由 TopAppBar/
                // ReadingBottomBar 自己处理 insets，不会叠加双份）
                .padding(top = immersiveTopPad, bottom = immersiveBottomPad)
                .padding(horizontal = 20.dp),
        ) {
            if (uiState.isLoading) {
                // 骨架屏：按正文排版预演段落形状（Spotify 式微光），
                // 替代居中转圈，感知加载更快
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 24.dp),
                ) {
                    repeat(9) { i ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (i % 4 == 3) 0.62f else 1f)
                                .height(18.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .shimmer(),
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }
            } else {
                when (uiState.readingMode) {
                    ReadingMode.NORMAL -> if (uiState.pageMode) {
                        PagedReadingView(
                            paragraphs = uiState.paragraphs,
                            currentIndex = uiState.currentParagraphIndex,
                            fontSize = uiState.fontSize,
                            textColor = textColor,
                            showTranslation = uiState.showTranslation,
                            paragraphTranslations = uiState.paragraphTranslations,
                            translationAlpha = uiState.translationAlpha,
                            showWordLevelColors = uiState.showWordLevelColors,
                            showKnownWordsHighlight = uiState.showKnownWordsHighlight,
                            knownWords = uiState.knownWords,
                            learnedWords = uiState.learnedWords,
                            // 单段朗读（isTtsPlaying）现也走句链播放：一并启用
                            // 句子级同步高亮，与自动朗读同一套渲染
                            isAutoReading = uiState.isAutoReading || uiState.isTtsPlaying,
                            currentSentences = uiState.currentSentences,
                            currentSentenceIndex = uiState.currentSentenceIndex,
                            onWordClick = viewModel::selectWord,
                            onSentenceDoubleTap = viewModel::translateSentence,
                            onVisibleParagraphChanged = viewModel::onVisibleParagraphChanged,
                            bookmarkedParagraphs = uiState.bookmarkedParagraphs,
                            highlights = uiState.highlights,
                            // 仿电子书：页眉书名 + 中键点击切换 chrome（左右 30% 为翻页热区）
                            bookTitle = uiState.book?.title ?: "",
                            onCenterTap = { chromeVisible = !chromeVisible },
                            classifier = viewModel.wordClassifier,
                            bookId = uiState.book?.id ?: 0L,
                        )
                    } else {
                        NormalReadingView(
                            paragraphs = uiState.paragraphs,
                            currentIndex = uiState.currentParagraphIndex,
                            fontSize = uiState.fontSize,
                            textColor = textColor,
                            showTranslation = uiState.showTranslation,
                            paragraphTranslations = uiState.paragraphTranslations,
                            translationAlpha = uiState.translationAlpha,
                            showWordLevelColors = uiState.showWordLevelColors,
                            showKnownWordsHighlight = uiState.showKnownWordsHighlight,
                            knownWords = uiState.knownWords,
                            learnedWords = uiState.learnedWords,
                            // 同 PagedReadingView：单段朗读也启用句级同步高亮
                            isAutoReading = uiState.isAutoReading || uiState.isTtsPlaying,
                            currentSentences = uiState.currentSentences,
                            currentSentenceIndex = uiState.currentSentenceIndex,
                            onWordClick = viewModel::selectWord,
                            onSentenceDoubleTap = viewModel::translateSentence,
                            onVisibleParagraphChanged = viewModel::onVisibleParagraphChanged,
                            bookmarkedParagraphs = uiState.bookmarkedParagraphs,
                            highlights = uiState.highlights,
                            onAddHighlight = { pIdx, start, end, text ->
                                viewModel.addHighlight(pIdx, start, end, text)
                            },
                            onRemoveHighlight = viewModel::removeHighlight,
                            classifier = viewModel.wordClassifier,
                            bookId = uiState.book?.id ?: 0L,
                        )
                    }
                    ReadingMode.RSVP -> RsvpReadingView(
                        paragraph = uiState.paragraphs.getOrNull(uiState.currentParagraphIndex) ?: "",
                        currentWordIndex = uiState.currentWordIndex,
                        fontSize = uiState.fontSize,
                        textColor = textColor,
                        isPlaying = uiState.isPlaying,
                        rsvpStrength = uiState.rsvpStrength,
                    )
                    ReadingMode.SPEED -> SpeedReadingView(
                        paragraph = uiState.paragraphs.getOrNull(uiState.currentParagraphIndex) ?: "",
                        fontSize = uiState.fontSize,
                        textColor = textColor,
                        isPlaying = uiState.isPlaying,
                        // VM 的速读链本来就按句驱动（切句、回调、索引都有），
                        // 原视图却只渲染一个"●"，把同步数据全部丢弃；接上
                        currentSentences = uiState.currentSentences,
                        currentSentenceIndex = uiState.currentSentenceIndex,
                    )
                    ReadingMode.CLOZE -> ClozeReadingView(
                        clozeWords = uiState.clozeWords,
                        answer = uiState.hiddenWordAnswer,
                        fontSize = uiState.fontSize,
                        textColor = textColor,
                        showTranslation = uiState.showTranslation,
                        translationAlpha = uiState.translationAlpha,
                        currentTranslation = uiState.paragraphTranslations[uiState.currentParagraphIndex],
                        onReveal = viewModel::hideWord,
                        onWordClick = viewModel::selectWord,
                    )
                    ReadingMode.FUZZY -> FuzzyReadingView(
                        fuzzyWords = uiState.fuzzyWords,
                        fontSize = uiState.fontSize,
                        textColor = textColor,
                    )
                    ReadingMode.DICTATION -> DictationReadingView(
                        clozeWords = uiState.clozeWords,
                        answer = uiState.hiddenWordAnswer,
                        fontSize = uiState.fontSize,
                        textColor = textColor,
                        paragraph = uiState.paragraphs.getOrNull(uiState.currentParagraphIndex) ?: "",
                        onCheckAnswer = viewModel::checkDictationAnswer,
                        onStartDictation = { viewModel.startDictation(uiState.currentParagraphIndex) },
                    )
                    ReadingMode.SPLIT -> SplitReadingView(
                        paragraphs = uiState.paragraphs,
                        translations = uiState.paragraphTranslations,
                        currentIndex = uiState.currentParagraphIndex,
                        fontSize = uiState.fontSize,
                        textColor = textColor,
                        translationAlpha = uiState.translationAlpha,
                        onWordClick = viewModel::selectWord,
                        onVisibleParagraphChanged = viewModel::onVisibleParagraphChanged,
                        isTranslating = uiState.isTranslating,
                        onRetryTranslate = viewModel::retryTranslation,
                        bookId = uiState.book?.id ?: 0L,
                    )
                    ReadingMode.BACK_TRANSLATION -> BackTranslationView(
                        paragraphs = uiState.paragraphs,
                        translations = uiState.paragraphTranslations,
                        currentIndex = uiState.currentParagraphIndex,
                        fontSize = uiState.fontSize,
                        textColor = textColor,
                        primaryColor = accentColor,
                        translationAlpha = uiState.translationAlpha,
                        isTranslating = uiState.isTranslating,
                        onRetryTranslate = viewModel::retryTranslation,
                        onVisibleParagraphChanged = viewModel::onVisibleParagraphChanged,
                        bookId = uiState.book?.id ?: 0L,
                    )
                    ReadingMode.POS_ANALYSIS -> PosAnalysisView(
                        paragraphs = uiState.paragraphs,
                        currentIndex = uiState.currentParagraphIndex,
                        fontSize = uiState.fontSize,
                        // 原实现完全不接阅读主题色：深色主题下深色墨字配深底不可读
                        textColor = textColor,
                        onWordClick = viewModel::selectWord,
                        onVisibleParagraphChanged = viewModel::onVisibleParagraphChanged,
                    )
                }
            }
        }
    } // 关闭 Scaffold
    } // 关闭 MaterialTheme(阅读主题配色)
    } // 关闭 CompositionLocalProvider(衬线字体/强调色)

    // 模式选择弹窗
    if (uiState.showModeSelector) {
        ModeSelectorDialog(
            currentMode = uiState.readingMode,
            onSelect = viewModel::setReadingMode,
            onDismiss = viewModel::dismissModeSelector,
        )
    }

    // 设置弹窗
    if (uiState.showSettings) {
        ReaderSettingsDialog(
            fontSize = uiState.fontSize,
            rsvpSpeed = uiState.rsvpSpeed,
            rsvpStrength = uiState.rsvpStrength,
            translationAlpha = uiState.translationAlpha,
            showWordLevelColors = uiState.showWordLevelColors,
            showKnownWordsHighlight = uiState.showKnownWordsHighlight,
            pageMode = uiState.pageMode,
            onFontSizeChange = viewModel::setFontSize,
            onSpeedChange = viewModel::setRsvpSpeed,
            onStrengthChange = viewModel::setRsvpStrength,
            onTranslationAlphaChange = viewModel::setTranslationAlpha,
            onWordLevelColorsToggle = viewModel::toggleWordLevelColors,
            onKnownWordsHighlightToggle = viewModel::toggleKnownWordsHighlight,
            onTogglePageMode = viewModel::togglePageMode,
            onDismiss = viewModel::toggleSettings,
        )
    }

    // 目录导航弹窗
    if (uiState.showChapterNav) {
        ChapterNavDialog(
            paragraphs = uiState.paragraphs,
            currentIndex = uiState.currentParagraphIndex,
            onSelect = viewModel::goToParagraph,
            onDismiss = viewModel::toggleChapterNav,
        )
    }

    // 单词弹窗
    // P0 修复: 用 ?.let { } 替代 !! —— Compose lambda 内编译器看不到 smart cast,
    // 当时序在 null 检查与 lambda 执行之间变化(罕见但理论上存在)会 NPE。
    uiState.selectedVocab?.let { vocab ->
        if (uiState.showWordDialog) {
            WordDetailDialog(
                word = vocab.word,
                definition = uiState.wordDefinition,
                wordLevel = uiState.selectedWordLevel,
                onAddToVocabulary = { viewModel.addToVocabulary(vocab.word, null) },
                onSpeak = { viewModel.speakOnDemand(vocab.word) },
                onDismiss = viewModel::dismissWordDialog,
            )
        }
    }

    // 选句翻译弹窗
    val selectedSentence by viewModel.selectedSentence.collectAsState()
    val sentenceTranslation by viewModel.sentenceTranslation.collectAsState()
    // P0 修复: 同上,避免 !! 在 by 委托后丢失智能转换
    selectedSentence?.let { sentence ->
        SentenceTranslationDialog(
            sentence = sentence,
            translation = sentenceTranslation,
            isLoading = sentenceTranslation == null,
            onSpeak = { viewModel.speakOnDemand(sentence) },
            onRetry = viewModel::retrySentenceTranslation,
            onDismiss = viewModel::dismissSentenceTranslation,
        )
    }

    // TTS 引擎引导弹窗
    ttsPrompt?.let { prompt ->
        TtsInstallDialog(
            prompt = prompt,
            downloadProgress = uiState.embeddedDownloadProgress,
            downloadStage = uiState.embeddedDownloadStage,
            onAction = { action ->
                viewModel.onTtsInstallAction(action)
                // 下载内置模型时保持弹窗打开，页内直接显示下载进度
                // （原实现点下载立即关弹窗，进度只在设置页可见）；
                // 下载结束后进度归空，弹窗回到常规按钮态由用户关闭
                if (action !is com.eareyereading.ui.screens.reader.TtsInstallAction.DownloadEmbeddedTts) {
                    ttsPrompt = null
                }
            },
            onDismiss = {
                viewModel.onTtsInstallAction(com.eareyereading.ui.screens.reader.TtsInstallAction.Dismiss)
                ttsPrompt = null
            },
        )
    }
}

// ── TTS 引擎引导弹窗 ───────────────────────────
@Composable
private fun TtsInstallDialog(
    prompt: com.eareyereading.ui.screens.reader.TtsInstallPrompt,
    downloadProgress: Float? = null,
    downloadStage: String? = null,
    onAction: (com.eareyereading.ui.screens.reader.TtsInstallAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    @Suppress("UNUSED_VARIABLE") val unusedCtx = context  // 旧版本用于 TtsEngineHelper 调用，移除后保留位

    // 2026-08-30: 系统 TTS 完全下线，对话框只剩"下载内置模型"一种 CTA。
    val downloadButton: @Composable () -> Unit = {
        val progress = downloadProgress
        if (progress != null) {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.Text(
                    // 阶段缺失时的 fallback 不再提"请保持网络"：
                    // 该文案只在阶段切换瞬间短暂出现，解压/初始化阶段显示
                    // 网络提示会自相矛盾（issue 1.1）
                    text = downloadStage
                        ?: "正在准备内置 TTS 模型 ${(progress * 100).toInt()}%…",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = androidx.compose.ui.Modifier.padding(top = 4.dp),
                )
            }
        } else {
            androidx.compose.material3.Button(
                onClick = {
                    onAction(com.eareyereading.ui.screens.reader.TtsInstallAction.DownloadEmbeddedTts)
                },
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.Text(
                    text = "🚀 下载内置 TTS（${prompt.embeddedModelDisplayName}，${prompt.embeddedModelSizeText}）"
                )
            }
        }
    }

    val title = if (prompt.embeddedModelDownloaded) "启用内置 TTS" else "下载内置 TTS"

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.material3.Text(
                text = title,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        },
        text = {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.Text(
                    text = if (prompt.embeddedModelDownloaded) {
                        "内置 TTS 模型已下载。点下方按钮启用。"
                    } else {
                        "内置 TTS 完全离线、不依赖系统服务，能保证英文朗读稳定性。" +
                            "模型下载约 ${prompt.embeddedModelSizeText}。"
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Column {
                if (prompt.embeddedModelDownloaded) {
                    androidx.compose.material3.Button(
                        onClick = {
                            onAction(com.eareyereading.ui.screens.reader.TtsInstallAction.RetryWithEngine("__EMBEDDED__"))
                        },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Text("✅ 启用内置 TTS")
                    }
                } else {
                    downloadButton()
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("取消")
            }
        },
    )
}

// ── 普通阅读视图 ───────────────────────────────

@Composable
fun NormalReadingView(
    paragraphs: List<String>,
    currentIndex: Int,
    fontSize: Int,
    textColor: Color,
    showTranslation: Boolean,
    paragraphTranslations: Map<Int, String>,
    translationAlpha: Float = 0.85f,
    showWordLevelColors: Boolean = false,
    showKnownWordsHighlight: Boolean = true,
    knownWords: Set<String> = emptySet(),
    learnedWords: Set<String> = emptySet(),
    isAutoReading: Boolean = false,
    currentSentences: List<String> = emptyList(),
    currentSentenceIndex: Int = 0,
    onWordClick: (String) -> Unit,
    onSentenceDoubleTap: (String) -> Unit,
    onVisibleParagraphChanged: (Int) -> Unit = {},
    bookmarkedParagraphs: Set<Int> = emptySet(),
    highlights: Map<Int, List<HighlightData>> = emptyMap(),
    onAddHighlight: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    onRemoveHighlight: (Long) -> Unit = {},
    // VM 注入的 CollinsClassifier 单例：词表全 App 一份，避免视图内手动
    // new 造成双份内存 + 组合期构建卡首帧
    classifier: CollinsClassifier,
    // 插图渲染用：[[IMG:n]] 标记解析到本书的落盘图片目录
    bookId: Long = 0L,
) {
    // LazyColumn：只布局可见段落。原实现整书 eager Column + 每次重组全文重排版，
    // 播放时每个句子 tick 都是 O(book) 开销。
    // LaunchedEffect 让视口跟随当前段落：滑杆/章节/上下段跳转与自动朗读推进
    // 都会滚动到目标段（此前跳转只改索引，视口从不移动）
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        // 目标段已在可见窗口内就不发起程序化滚动：反向同步把用户滑动
        // 经过的段落写回 currentIndex 后，这里若再 animateScrollToItem
        // 会在甩动（fling）途中反复打断惯性、把视口拽回段首
        if (currentIndex in paragraphs.indices &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == currentIndex }
        ) {
            listState.animateScrollToItem(currentIndex)
        }
    }
    // 反向同步：用户滑动阅读时把可见段落回报给 VM，
    // 让底栏/滑杆/进度/统计跟上视口（播放中由播放循环主导，VM 侧会忽略）
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx -> onVisibleParagraphChanged(idx) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(
            items = paragraphs,
            key = { index, _ -> index }, // 段落按书加载后不可变，index 是稳定身份
        ) { index, para ->
            ReaderParagraphBlock(
                index = index,
                para = para,
                isCurrent = index == currentIndex,
                isBookmarked = index in bookmarkedParagraphs,
                paraHighlights = highlights[index] ?: emptyList(),
                alpha = if (index == currentIndex) 1f else if (index < currentIndex) 0.4f else 0.7f,
                fontSize = fontSize,
                textColor = textColor,
                showTranslation = showTranslation,
                translation = paragraphTranslations[index],
                translationAlpha = translationAlpha,
                showWordLevelColors = showWordLevelColors,
                showKnownWordsHighlight = showKnownWordsHighlight,
                knownWords = knownWords,
                learnedWords = learnedWords,
                isAutoReading = isAutoReading,
                currentSentences = currentSentences,
                currentSentenceIndex = currentSentenceIndex,
                onWordClick = onWordClick,
                onSentenceDoubleTap = onSentenceDoubleTap,
                classifier = classifier,
                bookId = bookId,
            )
        }
    }
}

/**
 * 段落插图渲染：`[[IMG:n]]`（EPUB 落盘降采样 JPEG）/ `[[IMG:url]]`（文章/RSS 在线图）。
 *
 * 性能（用户确认"可以展示模糊一些"）：
 *  - Coil ImageRequest 固定 size(720)——按需解码 720px 宽的缩略位图，
 *    原图尺寸再大也不在阅读滚动路径上进出内存；
 *  - EPUB 图导入期已重编码为小 JPEG，文章图按 720 解码，memoryCacheKey 稳定
 *    派生，Coil 磁盘缓存默认开启——单图解码内存 ~≤2MB，翻页/滚动时缓存直接命中。
 *
 * 版式（issue：真实阅读源图片加载时文字错位）：
 *  - 加载中先占位（浅底 + minHeight），段落高度不再从 0 突变到图片高度，
 *    后文不会先"上移占位"再被图片顶下去；
 *  - 失败显示紧凑占位条（图片加载失败），保留段落节奏，后文不塌陷。
 */
@Composable
private fun ReaderImageBlock(
    ref: String,
    bookId: Long,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 旧导入数据可能残留 JS 占位 src（resolve 后变成 …/undefined），这些
    // URL 拉回 HTML 错误页 → BitmapFactory null → "图片加载失败"。
    // 渲染期兜底跳过，不渲染图片也不渲染标记文本。
    if (!BookImages.isLoadableImageRef(ref)) return
    val model = remember(ref, bookId) {
        ref.toIntOrNull()
            ?.let { BookImages.localImageFile(context, bookId, it) }
            ?: ref
    }
    // null = 首帧还没回调（视为加载中）：占位先顶住段落高度
    var imageState by remember(ref, bookId) {
        mutableStateOf<AsyncImagePainter.State?>(null)
    }
    val failed = imageState is AsyncImagePainter.State.Error
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 加载中/失败占位：Success 前 minHeight 先占住版式高度，后文不被
        // 突然弹出的图片顶下去（错位感）；失败保留紧凑提示条不塌陷
        if (imageState !is AsyncImagePainter.State.Success) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (failed) 48.dp else 160.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (failed) 0.06f else 0.10f,
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (failed) {
                    Text(
                        text = "图片加载失败",
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current.copy(alpha = 0.55f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(model)
                .size(720)
                // MIUI/HyperOS HWUI image decoder 原生 AImageDecoder_Create 返回
                // "unimplemented"，硬件位图（Bitmap.Config.HARDWARE）虽解码成功但
                // RenderThread 无法上传 GPU 纹理 → 空白/报错。allowHardware(false)
                // 强制软件位图（ARGB_8888），绕开该原生路径。
                .allowHardware(false)
                .memoryCacheKey("reader_img_${bookId}_${ref.takeLast(64)}")
                .crossfade(180)
                .build(),
            contentDescription = "插图",
            contentScale = ContentScale.Fit,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) {
                    android.util.Log.e(
                        "ReaderImage",
                        "image load failed: ref=$ref model=$model",
                        state.result.throwable,
                    )
                }
                imageState = state
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}

/**
 * 单个段落的完整渲染块：书签标记行 + 正文（朗读句子级同步 / Collins 词色 /
 * 生词高亮 / 用户高亮四分支）+ 译文。从滚动视图的 LazyColumn item 抽出，
 * 供滚动（NormalReadingView）与左右翻页（PagedReadingView）两视图共用，
 * 保证两种阅读方式的段落渲染完全一致。
 * 插图标记段（[[IMG:n]]）直接渲染为图片，不走文本分支。
 */
@Composable
private fun ReaderParagraphBlock(
    index: Int,
    para: String,
    isCurrent: Boolean,
    isBookmarked: Boolean,
    paraHighlights: List<HighlightData>,
    alpha: Float,
    fontSize: Int,
    textColor: Color,
    showTranslation: Boolean,
    translation: String?,
    translationAlpha: Float,
    showWordLevelColors: Boolean,
    showKnownWordsHighlight: Boolean,
    knownWords: Set<String>,
    learnedWords: Set<String>,
    isAutoReading: Boolean,
    currentSentences: List<String>,
    currentSentenceIndex: Int,
    onWordClick: (String) -> Unit,
    onSentenceDoubleTap: (String) -> Unit,
    classifier: CollinsClassifier,
    bookId: Long = 0L,
) {
    // 插图段：整块渲染为图片（书签标记照常保留），不参与词色/高亮/译文
    val imageRef = BookImages.markerRef(para)
    if (imageRef != null) {
        ReaderImageBlock(ref = imageRef, bookId = bookId)
        return
    }
    // 朗读中的当前段落：背景直接加在内容容器上。
    // 原实现额外放了一个包 Text("") 的 Surface —— 零高度，背景永远不可见
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCurrent && isAutoReading) {
                    Modifier
                        .background(LocalReaderAccent.current.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                } else {
                    Modifier
                }
            ),
    ) {
        // 书签段落标记行
        if (isBookmarked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Bookmark,
                    "已书签",
                    modifier = Modifier.size(16.dp),
                    tint = Secondary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Divider(
                    modifier = Modifier.weight(1f),
                    thickness = 1.dp,
                    color = Secondary.copy(alpha = 0.3f),
                )
            }
        }

        // 句子级声文同步高亮（朗读中）
        if (isCurrent && isAutoReading && currentSentences.isNotEmpty()) {
            // 显示已读/当前/未读句子。每句独立组件 + remember：句索引推进时
            // 只有"刚读完"与"刚开始"两句的档位变化会重建 AnnotatedString，
            // 其余句子全部命中缓存（此前每句 tick 全段句子重新分词+编译正则）
            currentSentences.forEachIndexed { sIdx, sentence ->
                val sAlpha = when {
                    sIdx < currentSentenceIndex -> 0.45f  // 已读完
                    sIdx == currentSentenceIndex -> 1f      // 当前读
                    else -> 0.6f                           // 未读
                }
                val bgColor = if (sIdx == currentSentenceIndex)
                    LocalReaderAccent.current.copy(alpha = 0.10f) else Color.Transparent

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    color = bgColor,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    // 朗读中的句子也走 TappableParagraphText：朗读时点词查义
                    // 是核心功能，此前该分支只渲染纯 Text 完全不可点（issue 3.4）
                    AutoReadingSentenceText(
                        sentence = sentence,
                        sAlpha = sAlpha,
                        fontSize = fontSize,
                        textColor = textColor,
                        showWordLevelColors = showWordLevelColors,
                        classifier = classifier,
                        onWordClick = onWordClick,
                        onSentenceDoubleTap = onSentenceDoubleTap,
                    )
                }
            }
        } else {
            // 词色/生词高亮/用户高亮统一构建（与翻页切片共用同一构建器，
            // 保证滚屏/翻页两种阅读方式渲染一致）
            val annotatedText = remember(
                para, textColor, showWordLevelColors,
                showKnownWordsHighlight, knownWords, learnedWords, paraHighlights,
            ) {
                buildReaderAnnotated(
                    text = para,
                    textColor = textColor,
                    showWordLevelColors = showWordLevelColors,
                    showKnownWordsHighlight = showKnownWordsHighlight,
                    knownWords = knownWords,
                    learnedWords = learnedWords,
                    highlights = paraHighlights,
                    classifier = classifier,
                )
            }
            TappableParagraphText(
                text = annotatedText,
                paragraph = para,
                onWordClick = onWordClick,
                onSentenceDoubleTap = onSentenceDoubleTap,
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .alpha(alpha),
                style = readerParagraphStyle(fontSize),
            )
        }

        // 翻译（透明度可调）
        if (showTranslation && !translation.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = translation,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .alpha(alpha),
                style = readerParagraphStyle(fontSize - 2, 1.5f).copy(
                    color = LocalReaderAccent.current.copy(alpha = translationAlpha),
                ),
            )
            // 只有实际有译文才留间距：原实现把 Spacer 放在判空之外，
            // 未翻译段落也多出一截空白，节奏不齐
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * 朗读中的单个句子渲染：词级配色 AnnotatedString 按
 * （句子文本, 档位透明度, 配色开关）缓存——句索引推进时只有档位
 * 变化的两句重建，其余命中 remember。
 */
@Composable
private fun AutoReadingSentenceText(
    sentence: String,
    sAlpha: Float,
    fontSize: Int,
    textColor: Color,
    showWordLevelColors: Boolean,
    classifier: CollinsClassifier,
    onWordClick: (String) -> Unit,
    onSentenceDoubleTap: (String) -> Unit,
) {
    val sentenceText = if (showWordLevelColors) {
        remember(sentence, sAlpha, textColor, classifier) {
            buildAnnotatedString {
                val words = WordSplitRegex.findAll(sentence)
                words.forEach { match ->
                    val word = match.value
                    if (PureWordRegex.matches(word)) {
                        val level = classifier.classify(word)
                        val color = when (level) {
                            WordLevel.CORE -> WordLevelCore
                            WordLevel.INTERMEDIATE -> WordLevelIntmd
                            WordLevel.UPPER_INTERMEDIATE -> WordLevelUpper
                            WordLevel.ADVANCED -> WordLevelAdv
                            WordLevel.RARE -> WordLevelRare
                            WordLevel.UNKNOWN -> textColor.copy(alpha = 0.5f)
                        }
                        withStyle(SpanStyle(color = color.copy(alpha = sAlpha))) { append(word) }
                    } else {
                        withStyle(SpanStyle(color = textColor.copy(alpha = sAlpha * 0.6f))) { append(word) }
                    }
                }
            }
        }
    } else {
        remember(sentence, sAlpha, textColor) {
            buildAnnotatedString {
                withStyle(SpanStyle(color = textColor.copy(alpha = sAlpha))) { append(sentence) }
            }
        }
    }
    TappableParagraphText(
        text = sentenceText,
        paragraph = sentence,
        onWordClick = onWordClick,
        onSentenceDoubleTap = onSentenceDoubleTap,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
        style = readerParagraphStyle(fontSize),
    )
}

// ── 左右翻页阅读视图（仿书页） ────────────────────

/**
 * 分页切片：一个段落可按 StaticLayout 的行边界拆成多个切片跨页渲染，
 * "放不下的行"自然流到下一页（真书式排版，替代旧的整段独占页 + 页内滚动）。
 */
private data class PageSlice(
    val paraIndex: Int,
    val charStart: Int,
    val charEnd: Int,
    val isFirstOfPara: Boolean,
    val isLastOfPara: Boolean,
)

/**
 * 仿书页横向翻页阅读：HorizontalPager 逐页渲染。
 *
 * 分页排版（[paginateBook]）：后台线程用 StaticLayout 按"行"精确测量整书，
 * 段落可跨页按行拆分——放不下的内容自动流到下一页（而非旧行为的
 * 整段塞进一页 + 页内滚动兜底）；只有估算与渲染的极小偏差才落入
 * 页内 verticalScroll 兜底。段落渲染与滚动视图共用 [ReaderParagraphBlock]
 * ——词色/生词高亮/用户高亮/译文/朗读同步完全一致；被拆分的段用
 * [ReaderSliceParagraphBlock]（同款渲染，offset 平移）。
 *
 * 同步语义（与滚动视图对齐）：
 *  - 翻页 settle 后把该页首切片的段落回报 VM（底栏滑杆/进度/统计跟上）；
 *  - 程序推进（朗读/滑杆/章节跳转）时翻到目标段首个切片所在页；
 *  - 相邻页动画翻页，跨页跳转（如续读恢复）瞬时定位。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PagedReadingView(
    paragraphs: List<String>,
    currentIndex: Int,
    fontSize: Int,
    textColor: Color,
    showTranslation: Boolean,
    paragraphTranslations: Map<Int, String>,
    translationAlpha: Float,
    showWordLevelColors: Boolean,
    showKnownWordsHighlight: Boolean,
    knownWords: Set<String>,
    learnedWords: Set<String>,
    isAutoReading: Boolean = false,
    currentSentences: List<String> = emptyList(),
    currentSentenceIndex: Int = 0,
    onWordClick: (String) -> Unit,
    onSentenceDoubleTap: (String) -> Unit,
    onVisibleParagraphChanged: (Int) -> Unit = {},
    bookmarkedParagraphs: Set<Int> = emptySet(),
    highlights: Map<Int, List<HighlightData>> = emptyMap(),
    // 仿电子书装饰：页眉书名 + 页脚页码；中键点击回调（左右边缘被翻页区占用）
    bookTitle: String = "",
    onCenterTap: () -> Unit = {},
    // VM 注入的 CollinsClassifier 单例（与滚动视图共用，见 NormalReadingView 注释）
    classifier: CollinsClassifier,
    // 插图渲染用：[[IMG:n]] 标记解析到本书的落盘图片目录
    bookId: Long = 0L,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val contentWidthPx = with(density) { maxWidth.roundToPx() }
        // 页高预算：视口高 - 上下 8dp 留白 - 仿电子书页眉页脚预留
        // （书名 running header ~18sp + 页码 footer ~16sp + 上下间距）
        val pageBudgetPx = with(density) { (maxHeight - 16.dp).roundToPx() } -
            with(density) { 52.dp.roundToPx() }
        val serif = LocalReaderFontFamily.current != FontFamily.Default
        // 各项 px 尺寸（密度/字号变化时 produceState 的 key 一起变）
        val fontSizePx = with(density) { fontSize.sp.toPx() }
        val transFontSizePx = with(density) { (fontSize - 2).sp.toPx() }
        val paragraphPadPx = with(density) { 6.dp.toPx() }      // 段首/段尾切片的纵向 padding
        val bookmarkRowPx = with(density) { 25.dp.toPx() }      // 书签标记行
        val transBlockPadPx = with(density) { 20.dp.toPx() }    // 译文 4 + 2*2 + 12
        // 插图段固定估高：720px 解码宽 × 常见屏宽 → 约 200dp 显示高 + 边距，
        // 独占一页过浪费，给中等预算让图文同页
        val imageBlockPx = with(density) { 220.dp.toPx() }

        // 分页排版：整书 StaticLayout 按行测量 + 贪心装箱（行粒度）。
        // 放 Default 调度器：大书几百段的测量同步做在组合期会顶掉帧
        // （字号滑杆逐像素回调时尤甚），后台算完一次性替换，期间保留旧分页不闪空
        val pages by produceState(
            initialValue = emptyList<List<PageSlice>>(),
            paragraphs, fontSizePx, transFontSizePx, showTranslation,
            paragraphTranslations, contentWidthPx, pageBudgetPx, serif,
            bookmarkedParagraphs,
        ) {
            value = withContext(Dispatchers.Default) {
                paginateBook(
                    paragraphs = paragraphs,
                    contentWidthPx = contentWidthPx,
                    pageBudgetPx = pageBudgetPx.toFloat(),
                    fontSizePx = fontSizePx,
                    transFontSizePx = transFontSizePx,
                    serif = serif,
                    showTranslation = showTranslation,
                    translations = paragraphTranslations,
                    bookmarked = bookmarkedParagraphs,
                    imageBlockPx = imageBlockPx,
                    bookmarkRowPx = bookmarkRowPx,
                    paragraphPadPx = paragraphPadPx,
                    transBlockPadPx = transBlockPadPx,
                )
            }
        }

        val pagerState = rememberPagerState(pageCount = { pages.size })

        // 翻页回报：页 settle 后把该页首切片的段落回报 VM
        // （底栏滑杆/进度/阅读统计跟上视口，播放中由播放循环主导，VM 侧会忽略）
        LaunchedEffect(pagerState, pages) {
            snapshotFlow { pagerState.currentPage }
                .collect { page ->
                    pages.getOrNull(page)?.firstOrNull()?.let { onVisibleParagraphChanged(it.paraIndex) }
                }
        }
        // 程序推进跟随：朗读/滑杆/章节跳转把 currentIndex 推走时翻到
        // 目标段首个切片所在页。远距离（续读恢复/跳章）瞬时定位，相邻页动画翻页
        LaunchedEffect(currentIndex, pages) {
            if (pages.isEmpty()) return@LaunchedEffect
            val target = pages.indexOfFirst { page -> page.any { it.paraIndex == currentIndex } }
            if (target >= 0 && target != pagerState.currentPage && !pagerState.isScrollInProgress) {
                if (kotlin.math.abs(target - pagerState.currentPage) > 1) {
                    pagerState.scrollToPage(target)
                } else {
                    pagerState.animateScrollToPage(target)
                }
            }
        }

        if (pages.isEmpty()) {
            // 分页排版计算中（后台整书测量，通常 <100ms）：保持空白防跳变
            Box(modifier = Modifier.fillMaxSize())
        } else {
            // 仿电子书点击翻页协程作用域
            val pageFlipScope = rememberCoroutineScope()
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    // 仿电子书点击翻页区：左 30% 上一页、右 30% 下一页、
                    // 中间 40% 切换顶底栏显隐（Kindle 式热区）。段落文字上的
                    // 点词/双击翻译由内层手势先消费，只有空白处的点击落到这里
                    .pointerInput(pages.size) {
                        detectTapGestures { offset ->
                            val edge = size.width * 0.3f
                            val cur = pagerState.currentPage
                            when {
                                offset.x < edge && cur > 0 -> pageFlipScope.launch {
                                    pagerState.animateScrollToPage(cur - 1)
                                }
                                offset.x > size.width - edge && cur < pages.size - 1 ->
                                    pageFlipScope.launch {
                                        pagerState.animateScrollToPage(cur + 1)
                                    }
                                else -> onCenterTap()
                            }
                        }
                    },
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 8.dp),
                ) {
                    // 仿电子书页眉：书名 running header（纸书式页顶书名）
                    if (bookTitle.isNotBlank()) {
                        Text(
                            text = bookTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.45f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 2.dp),
                        )
                    }
                    // verticalScroll 兜底：行高估算与 Compose 实际渲染的
                    // 极小偏差导致的内容溢出仍可滚动查看
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        pages[page].forEach { slice ->
                            val para = paragraphs[slice.paraIndex]
                            when {
                                // 插图段：整块渲染为图片（与滚动视图一致，不参与分片）
                                BookImages.isImageMarker(para) -> ReaderImageBlock(
                                    ref = BookImages.markerRef(para).orEmpty(),
                                    bookId = bookId,
                                )
                                // 整段都在本页：走与滚动视图完全一致的段落渲染
                                slice.charStart == 0 && slice.charEnd >= para.length ->
                                    ReaderParagraphBlock(
                                        index = slice.paraIndex,
                                        para = para,
                                        isCurrent = slice.paraIndex == currentIndex,
                                        isBookmarked = slice.paraIndex in bookmarkedParagraphs,
                                        paraHighlights = highlights[slice.paraIndex] ?: emptyList(),
                                        alpha = if (slice.paraIndex == currentIndex) 1f
                                        else if (slice.paraIndex < currentIndex) 0.4f else 0.7f,
                                        fontSize = fontSize,
                                        textColor = textColor,
                                        showTranslation = showTranslation,
                                        translation = paragraphTranslations[slice.paraIndex],
                                        translationAlpha = translationAlpha,
                                        showWordLevelColors = showWordLevelColors,
                                        showKnownWordsHighlight = showKnownWordsHighlight,
                                        knownWords = knownWords,
                                        learnedWords = learnedWords,
                                        isAutoReading = isAutoReading,
                                        currentSentences = currentSentences,
                                        currentSentenceIndex = currentSentenceIndex,
                                        onWordClick = onWordClick,
                                        onSentenceDoubleTap = onSentenceDoubleTap,
                                        classifier = classifier,
                                        bookId = bookId,
                                    )
                                // 跨页切片：行级拆分渲染
                                else -> ReaderSliceParagraphBlock(
                                    para = para,
                                    charStart = slice.charStart,
                                    charEnd = slice.charEnd,
                                    isCurrent = slice.paraIndex == currentIndex,
                                    isAutoReading = isAutoReading,
                                    currentSentences = currentSentences,
                                    currentSentenceIndex = currentSentenceIndex,
                                    alpha = if (slice.paraIndex == currentIndex) 1f
                                    else if (slice.paraIndex < currentIndex) 0.4f else 0.7f,
                                    fontSize = fontSize,
                                    textColor = textColor,
                                    showTranslation = showTranslation,
                                    translation = paragraphTranslations[slice.paraIndex],
                                    translationAlpha = translationAlpha,
                                    showWordLevelColors = showWordLevelColors,
                                    showKnownWordsHighlight = showKnownWordsHighlight,
                                    knownWords = knownWords,
                                    learnedWords = learnedWords,
                                    onWordClick = onWordClick,
                                    onSentenceDoubleTap = onSentenceDoubleTap,
                                    classifier = classifier,
                                    sliceHighlights = highlights[slice.paraIndex] ?: emptyList(),
                                    showBookmarkMark = slice.isFirstOfPara &&
                                        slice.paraIndex in bookmarkedParagraphs,
                                )
                            }
                        }
                    }
                    // 仿电子书页脚：页码 + 全书进度百分比
                    Text(
                        text = "${page + 1} / ${pages.size} · ${(page + 1) * 100 / pages.size}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * 整书分页：StaticLayout 按行测量（与 Compose 渲染同一文本、同一宽度、
 * 同一字体），行高按 readerParagraphStyle 的倍数 + 2% 安全余量估算，
 * 贪心装箱到每页预算内。段落可跨页拆分（行粒度），书签行/译文高度
 * 计入所属切片（书签在段首切片、译文在段尾切片）。
 *
 * 必须在后台线程调用（数百次 StaticLayout 构建耗时几十毫秒）。
 */
private fun paginateBook(
    paragraphs: List<String>,
    contentWidthPx: Int,
    pageBudgetPx: Float,
    fontSizePx: Float,
    transFontSizePx: Float,
    serif: Boolean,
    showTranslation: Boolean,
    translations: Map<Int, String>,
    bookmarked: Set<Int>,
    imageBlockPx: Float,
    bookmarkRowPx: Float,
    paragraphPadPx: Float,
    transBlockPadPx: Float,
): List<List<PageSlice>> {
    val typeface = if (serif) android.graphics.Typeface.SERIF else android.graphics.Typeface.DEFAULT
    val bodyPaint = android.text.TextPaint().apply {
        isAntiAlias = true
        this.textSize = fontSizePx
        this.typeface = typeface
    }
    val transPaint = android.text.TextPaint().apply {
        isAntiAlias = true
        textSize = transFontSizePx
        this.typeface = typeface
    }
    // 行高对齐 readerParagraphStyle：正文 1.8 倍、译文 1.5 倍；
    // 2% 余量宁可页尾略空，也不让渲染高度反超估算
    val bodyLineH = fontSizePx * 1.8f * 1.02f
    val transLineH = transFontSizePx * 1.5f * 1.02f

    fun lineBounds(text: String, paint: android.text.TextPaint): List<IntArray> {
        if (text.isEmpty()) return emptyList()
        val layout = android.text.StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentWidthPx)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
        return (0 until layout.lineCount).map { i ->
            intArrayOf(layout.getLineStart(i), layout.getLineEnd(i))
        }
    }

    fun translationHeight(idx: Int): Float {
        val t = if (showTranslation) translations[idx] else null
        if (t.isNullOrBlank()) return 0f
        val lines = lineBounds(t, transPaint).size.coerceAtLeast(1)
        return lines * transLineH + transBlockPadPx
    }

    val pages = mutableListOf<List<PageSlice>>()
    var current = mutableListOf<PageSlice>()
    var used = 0f

    fun closePage() {
        if (current.isNotEmpty()) {
            pages.add(current)
            current = mutableListOf()
            used = 0f
        }
    }

    paragraphs.forEachIndexed { idx, para ->
        // 插图标记段按固定图块高度整块参与装箱（无文本可测）
        if (BookImages.isImageMarker(para)) {
            if (used + imageBlockPx > pageBudgetPx) closePage()
            current.add(PageSlice(idx, 0, para.length, isFirstOfPara = true, isLastOfPara = true))
            used += imageBlockPx
            return@forEachIndexed
        }
        val lines = lineBounds(para, bodyPaint)
        if (lines.isEmpty()) {
            // 空段占位（维持段落节奏）
            if (used + fontSizePx > pageBudgetPx) closePage()
            current.add(PageSlice(idx, 0, 0, isFirstOfPara = true, isLastOfPara = true))
            used += fontSizePx
            return@forEachIndexed
        }
        val transH = translationHeight(idx)
        var i = 0
        var first = true
        while (i < lines.size) {
            val isLastChunk = i + 1 >= lines.size
            // 本切片的附加高度：段首/段尾 padding、书签行（段首切片）、译文（段尾切片）
            val extra = (if (first) paragraphPadPx else 0f) +
                (if (isLastChunk) paragraphPadPx else 0f) +
                (if (first && idx in bookmarked) bookmarkRowPx else 0f) +
                (if (isLastChunk) transH else 0f)
            var fit = (((pageBudgetPx - used) - extra).coerceAtLeast(0f) / bodyLineH).toInt()
            if (fit <= 0) {
                // 当前页连一行都放不下：换页重算；仍放不下（附加块超高）则
                // 单行兜底——该页 verticalScroll 可滚动查看
                if (current.isNotEmpty()) {
                    closePage()
                    fit = (((pageBudgetPx - used) - extra).coerceAtLeast(0f) / bodyLineH).toInt()
                }
                if (fit <= 0) fit = 1
            }
            val take = minOf(fit, lines.size - i)
            current.add(
                PageSlice(
                    paraIndex = idx,
                    charStart = lines[i][0],
                    charEnd = lines[i + take - 1][1],
                    isFirstOfPara = first,
                    isLastOfPara = i + take >= lines.size,
                ),
            )
            used += extra + take * bodyLineH
            i += take
            first = false
        }
    }
    closePage()
    return pages
}

/**
 * 跨页段落切片渲染：渲染 [para] 的 [charStart, charEnd) 行片段。
 * 词色/生词高亮/用户高亮（offset 平移到切片坐标系）/译文（段尾切片）/
 * 朗读句级同步（句子与切片求交，跨页句子在两页各显示各自片段）——
 * 与 [ReaderParagraphBlock] 同一套逻辑。
 */
@Composable
private fun ReaderSliceParagraphBlock(
    para: String,
    charStart: Int,
    charEnd: Int,
    isCurrent: Boolean,
    isAutoReading: Boolean,
    currentSentences: List<String>,
    currentSentenceIndex: Int,
    alpha: Float,
    fontSize: Int,
    textColor: Color,
    showTranslation: Boolean,
    translation: String?,
    translationAlpha: Float,
    showWordLevelColors: Boolean,
    showKnownWordsHighlight: Boolean,
    knownWords: Set<String>,
    learnedWords: Set<String>,
    onWordClick: (String) -> Unit,
    onSentenceDoubleTap: (String) -> Unit,
    classifier: CollinsClassifier,
    sliceHighlights: List<HighlightData>,
    showBookmarkMark: Boolean,
) {
    val start = charStart.coerceIn(0, para.length)
    val end = charEnd.coerceIn(start, para.length)
    val sliceText = remember(para, start, end) { para.substring(start, end) }
    val isFirst = start == 0
    val isLast = end >= para.length
    // 切片级高亮：原段落坐标系 → 切片坐标系（求交后平移）
    val shiftedHighlights = remember(para, sliceHighlights, start, end) {
        sliceHighlights.mapNotNull { h ->
            val s = h.startOffset.coerceIn(0, para.length)
            val e = h.endOffset.coerceIn(s, para.length)
            val ns = (s - start).coerceAtLeast(0)
            val ne = (e - start).coerceAtMost(sliceText.length)
            if (ne > ns) HighlightData(h.id, ns, ne, h.text, h.color) else null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCurrent && isAutoReading) {
                    Modifier
                        .background(LocalReaderAccent.current.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                } else {
                    Modifier
                }
            ),
    ) {
        if (showBookmarkMark) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Bookmark,
                    "已书签",
                    modifier = Modifier.size(16.dp),
                    tint = Secondary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Divider(
                    modifier = Modifier.weight(1f),
                    thickness = 1.dp,
                    color = Secondary.copy(alpha = 0.3f),
                )
            }
        }

        // 朗读句级同步：句子范围与切片求交，逐句分档透明度渲染
        val sentenceRanges = remember(para, currentSentences) {
            var from = 0
            val ranges = mutableListOf<IntArray>()
            for (s in currentSentences) {
                val i = para.indexOf(s, from)
                if (i < 0) return@remember null
                ranges.add(intArrayOf(i, i + s.length))
                from = i + s.length
            }
            ranges
        }

        if (isCurrent && isAutoReading && !sentenceRanges.isNullOrEmpty()) {
            val accent = LocalReaderAccent.current
            val annotated = remember(
                sliceText, sentenceRanges, currentSentenceIndex,
                showWordLevelColors, textColor, accent,
            ) {
                buildAutoReadingSliceAnnotated(
                    para = para,
                    sliceStart = start,
                    sliceEnd = end,
                    sentenceRanges = sentenceRanges,
                    currentSentenceIndex = currentSentenceIndex,
                    showWordLevelColors = showWordLevelColors,
                    textColor = textColor,
                    accent = accent,
                    classifier = classifier,
                )
            }
            TappableParagraphText(
                text = annotated,
                paragraph = sliceText,
                onWordClick = onWordClick,
                onSentenceDoubleTap = onSentenceDoubleTap,
                // 句子可能跨页：双击时用全局 offset 在完整段落里找整句
                sentenceLookup = { local -> findSentenceAtGlobalOffset(para, start + local) },
                modifier = Modifier.alpha(1f),
                style = readerParagraphStyle(fontSize),
            )
        } else {
            val annotatedText = remember(
                sliceText, textColor, showWordLevelColors,
                showKnownWordsHighlight, knownWords, learnedWords, shiftedHighlights,
            ) {
                buildReaderAnnotated(
                    text = sliceText,
                    textColor = textColor,
                    showWordLevelColors = showWordLevelColors,
                    showKnownWordsHighlight = showKnownWordsHighlight,
                    knownWords = knownWords,
                    learnedWords = learnedWords,
                    highlights = shiftedHighlights,
                    classifier = classifier,
                )
            }
            TappableParagraphText(
                text = annotatedText,
                paragraph = sliceText,
                onWordClick = onWordClick,
                onSentenceDoubleTap = onSentenceDoubleTap,
                sentenceLookup = { local -> findSentenceAtGlobalOffset(para, start + local) },
                modifier = Modifier
                    .padding(
                        top = if (isFirst) 6.dp else 0.dp,
                        bottom = if (isLast) 6.dp else 0.dp,
                    )
                    .alpha(alpha),
                style = readerParagraphStyle(fontSize),
            )
        }

        // 译文跟随段尾切片（与整段渲染一致）
        if (isLast && showTranslation && !translation.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = translation,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .alpha(alpha),
                style = readerParagraphStyle(fontSize - 2, 1.5f).copy(
                    color = LocalReaderAccent.current.copy(alpha = translationAlpha),
                ),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * 朗读中的切片文本：句子范围 ∩ 切片范围的分段 AnnotatedString。
 * 每段按句子档位（已读 0.45 / 当前 1f / 未读 0.6）上色，当前句带
 * 强调色底；词频着色开启时在句子档位之上再叠词色（与
 * [AutoReadingSentenceText] 同一套规则）。
 */
private fun buildAutoReadingSliceAnnotated(
    para: String,
    sliceStart: Int,
    sliceEnd: Int,
    sentenceRanges: List<IntArray>,
    currentSentenceIndex: Int,
    showWordLevelColors: Boolean,
    textColor: Color,
    accent: Color,
    classifier: CollinsClassifier,
): AnnotatedString = buildAnnotatedString {
    var cursor = sliceStart
    sentenceRanges.forEachIndexed { sIdx, range ->
        val sStart = range[0]
        val sEnd = range[1]
        if (sEnd <= sliceStart || sStart >= sliceEnd) return@forEachIndexed
        // 句间空白（切片内部分）
        if (cursor < sStart) {
            val gapEnd = sStart.coerceAtMost(sliceEnd)
            if (gapEnd > cursor) {
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.6f))) {
                    append(para.substring(cursor, gapEnd))
                }
            }
        }
        val fragStart = sStart.coerceAtLeast(sliceStart).coerceAtLeast(cursor)
        val fragEnd = sEnd.coerceAtMost(sliceEnd)
        if (fragEnd > fragStart) {
            val sAlpha = when {
                sIdx < currentSentenceIndex -> 0.45f
                sIdx == currentSentenceIndex -> 1f
                else -> 0.6f
            }
            val fragment = para.substring(fragStart, fragEnd)
            val fragOffset = length
            if (showWordLevelColors) {
                WordSplitRegex.findAll(fragment).forEach { match ->
                    val word = match.value
                    if (PureWordRegex.matches(word)) {
                        val level = classifier.classify(word)
                        val color = when (level) {
                            WordLevel.CORE -> WordLevelCore
                            WordLevel.INTERMEDIATE -> WordLevelIntmd
                            WordLevel.UPPER_INTERMEDIATE -> WordLevelUpper
                            WordLevel.ADVANCED -> WordLevelAdv
                            WordLevel.RARE -> WordLevelRare
                            WordLevel.UNKNOWN -> textColor.copy(alpha = 0.5f)
                        }
                        withStyle(SpanStyle(color = color.copy(alpha = sAlpha))) { append(word) }
                    } else {
                        withStyle(SpanStyle(color = textColor.copy(alpha = sAlpha * 0.6f))) { append(word) }
                    }
                }
            } else {
                withStyle(SpanStyle(color = textColor.copy(alpha = sAlpha))) { append(fragment) }
            }
            if (sIdx == currentSentenceIndex) {
                addStyle(
                    SpanStyle(background = accent.copy(alpha = 0.10f)),
                    fragOffset, length,
                )
            }
            cursor = sEnd
        }
    }
    // 尾部空白
    if (cursor < sliceEnd) {
        withStyle(SpanStyle(color = textColor.copy(alpha = 0.6f))) {
            append(para.substring(cursor, sliceEnd))
        }
    }
}

/**
 * 段落/切片通用的词色 AnnotatedString 构建器：
 *  - 词频着色开 → Collins 词色（已认识词优先绿色）+ 用户高亮底色叠加；
 *  - 仅生词高亮 → 已认识/已学词着色 + 高亮叠加；
 *  - 都关 → 纯文本 + 用户高亮。
 * ReaderParagraphBlock 与 ReaderSliceParagraphBlock 共用，保证滚屏/翻页同款渲染。
 */
private fun buildReaderAnnotated(
    text: String,
    textColor: Color,
    showWordLevelColors: Boolean,
    showKnownWordsHighlight: Boolean,
    knownWords: Set<String>,
    learnedWords: Set<String>,
    highlights: List<HighlightData>,
    classifier: CollinsClassifier,
): AnnotatedString = buildAnnotatedString {
    if (showWordLevelColors) {
        WordSplitRegex.findAll(text).forEach { match ->
            val word = match.value
            if (PureWordRegex.matches(word)) {
                val level = classifier.classify(word)
                val lower = word.lowercase()
                // 生词本优先：已认识的词用绿色
                val color = when {
                    showKnownWordsHighlight && lower in knownWords -> Success
                    else -> when (level) {
                        WordLevel.CORE -> WordLevelCore
                        WordLevel.INTERMEDIATE -> WordLevelIntmd
                        WordLevel.UPPER_INTERMEDIATE -> WordLevelUpper
                        WordLevel.ADVANCED -> WordLevelAdv
                        WordLevel.RARE -> WordLevelRare
                        WordLevel.UNKNOWN -> textColor.copy(alpha = 0.5f)
                    }
                }
                withStyle(SpanStyle(color = color)) { append(word) }
            } else {
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.6f))) { append(word) }
            }
        }
        // 词色之上叠加用户高亮背景
        highlights.forEach { h ->
            val s = h.startOffset.coerceIn(0, text.length)
            val e = h.endOffset.coerceIn(s, text.length)
            if (e > s) addStyle(SpanStyle(background = h.color.copy(alpha = 0.25f)), s, e)
        }
    } else if (showKnownWordsHighlight && knownWords.isNotEmpty()) {
        WordSplitRegex.findAll(text).forEach { match ->
            val word = match.value
            if (PureWordRegex.matches(word)) {
                val lower = word.lowercase()
                val color = when {
                    lower in knownWords -> Success
                    lower in learnedWords -> KnownWord
                    else -> textColor
                }
                withStyle(SpanStyle(color = color)) { append(word) }
            } else {
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.6f))) { append(word) }
            }
        }
        highlights.forEach { h ->
            val s = h.startOffset.coerceIn(0, text.length)
            val e = h.endOffset.coerceIn(s, text.length)
            if (e > s) addStyle(SpanStyle(background = h.color.copy(alpha = 0.25f)), s, e)
        }
    } else {
        // 纯文本 + 高亮渲染：按 offset 顺序处理高亮区域；重叠高亮不重复
        // 输出重叠段，负值/反向/越界脏数据收敛后跳过
        var cursor = 0
        val sortedHighlights = highlights.sortedBy { it.startOffset }
        for (highlight in sortedHighlights) {
            val start = highlight.startOffset.coerceAtLeast(cursor)
            val end = highlight.endOffset.coerceIn(start, text.length)
            if (end <= start) continue
            if (cursor < start) {
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.8f))) {
                    append(text.substring(cursor, start))
                }
            }
            withStyle(SpanStyle(
                background = highlight.color.copy(alpha = 0.25f),
                color = highlight.color,
            )) {
                append(text.substring(start, end))
            }
            cursor = end
        }
        if (cursor < text.length) {
            withStyle(SpanStyle(color = textColor.copy(alpha = 0.8f))) {
                append(text.substring(cursor))
            }
        }
    }
}

// ── RSVP 仿生阅读视图 ──────────────────────────
@Composable
fun RsvpReadingView(
    paragraph: String,
    currentWordIndex: Int,
    fontSize: Int,
    textColor: Color,
    isPlaying: Boolean,
    rsvpStrength: Int = 3,
) {
    val wordAnalyzer = remember { WordAnalyzer() }
    // 必须与 ReaderViewModel.getCurrentParagraphWords()（wordAnalyzer.extractWords，
    // 即 [a-zA-Z]+ 分词）使用完全相同的分词器：原实现按空白切分，
    // 遇到 "don't" 这类缩写时两边词数不一致，播放中显示空白且进度条超过 100%
    val words = remember(paragraph) { wordAnalyzer.extractWords(paragraph) }
    val currentWord = words.getOrNull(currentWordIndex) ?: ""

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (currentWord.isNotEmpty()) {
            val (bold, normal) = wordAnalyzer.processRsvpWord(currentWord, rsvpStrength)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = textColor)) {
                        append(bold)
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = textColor.copy(alpha = 0.7f))) {
                        append(normal)
                    }
                },
                fontSize = (fontSize * 3).sp,
                textAlign = TextAlign.Center,
            )
            // 强度指示：纯展示徽章。原实现是 onClick={} 的 AssistChip，
            // TalkBack 会把它读成"没反应的按钮"
            Surface(
                shape = RoundedCornerShape(50),
                color = LocalReaderAccent.current.copy(alpha = 0.1f),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    "强度 $rsvpStrength",
                    color = LocalReaderAccent.current,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        } else {
            Text("点击播放按钮开始", color = textColor.copy(alpha = 0.5f))
        }
        Spacer(modifier = Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = if (words.isNotEmpty()) {
                ((currentWordIndex + 1).toFloat() / words.size).coerceIn(0f, 1f)
            } else 0f,
            modifier = Modifier.width(200.dp),
        )
        if (words.isNotEmpty()) {
            Text(
                text = "${(currentWordIndex + 1).coerceAtMost(words.size)} / ${words.size}",
                color = textColor.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ── 快速阅读视图 ───────────────────────────────
@Composable
fun SpeedReadingView(
    paragraph: String,
    fontSize: Int,
    textColor: Color,
    isPlaying: Boolean,
    currentSentences: List<String> = emptyList(),
    currentSentenceIndex: Int = 0,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isPlaying && currentSentences.isNotEmpty()) {
            // VM 的速读链按句驱动（切句/回调/索引都有），视图按句渲染：
            // 已读句变淡、当前句高亮、未读句正常。原实现播放中只显示一个"●"
            Column(modifier = Modifier.fillMaxWidth()) {
                currentSentences.forEachIndexed { idx, sentence ->
                    val isCurrent = idx == currentSentenceIndex
                    val alpha = when {
                        idx < currentSentenceIndex -> 0.45f
                        isCurrent -> 1f
                        else -> 0.6f
                    }
                    Text(
                        text = sentence,
                        color = textColor.copy(alpha = alpha),
                        fontSize = fontSize.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .then(
                                if (isCurrent) {
                                    Modifier
                                        .background(LocalReaderAccent.current.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                } else Modifier
                            ),
                    )
                }
            }
        } else {
            Text(
                text = paragraph.take(80),
                color = textColor,
                fontSize = fontSize.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── 挖空练习视图 ────────────────────────────────
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ClozeReadingView(
    clozeWords: List<ClozeWord>,
    answer: String?,
    fontSize: Int,
    textColor: Color,
    showTranslation: Boolean,
    translationAlpha: Float = 0.85f,
    currentTranslation: String?,
    onReveal: () -> Unit,
    onWordClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // FlowRow 行内排布：原实现把每个词放进纵向 Column，
        // 一段话被渲染成一列单词，完全不可读。
        // 揭示是渐进的：VM 每按一次"显示答案"清除一个隐藏词标记
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            clozeWords.forEach { clozeWord ->
                if (clozeWord.isWord) {
                    if (clozeWord.isHidden) {
                        Text(
                            text = "____",
                            color = textColor.copy(alpha = 0.5f),
                            fontSize = fontSize.sp,
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                // 挖空词本身可点击揭示，扩大可点区并补上此前缺的
                                // 点击入口（issue 3.5）
                                .clickable { onReveal() },
                        )
                    } else {
                        Text(
                            text = clozeWord.text,
                            color = textColor,
                            fontSize = fontSize.sp,
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .clickable { onWordClick(clozeWord.text) },
                        )
                    }
                } else {
                    // 分隔符 token 原样输出，保证词间距/标点自然
                    Text(
                        text = clozeWord.text,
                        color = textColor.copy(alpha = 0.7f),
                        fontSize = fontSize.sp,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        val remainingHidden = clozeWords.count { it.isWord && it.isHidden }
        FilledTonalButton(
            onClick = onReveal,
            enabled = remainingHidden > 0,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Default.Visibility, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (remainingHidden > 0) "显示答案（剩 $remainingHidden 空）" else "已全部揭示")
        }

        if (showTranslation && !currentTranslation.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = LocalReaderAccent.current.copy(alpha = 0.1f)),
            ) {
                Text(
                    text = currentTranslation,
                    modifier = Modifier.padding(12.dp),
                    color = LocalReaderAccent.current.copy(alpha = translationAlpha),
                    fontSize = (fontSize - 2).sp,
                )
            }
        }
    }
}

// ── 模糊阅读视图 ────────────────────────────────
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FuzzyReadingView(
    fuzzyWords: List<com.eareyereading.util.FuzzyWord>,
    fontSize: Int,
    textColor: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // 同挖空视图：FlowRow 行内排布，不再一词一行
        FlowRow(modifier = Modifier.fillMaxWidth()) {
            fuzzyWords.forEach { fuzzyWord ->
                Text(
                    text = fuzzyWord.text,
                    color = if (fuzzyWord.isBlurred) textColor.copy(alpha = 0.15f) else textColor,
                    fontSize = fontSize.sp,
                    modifier = if (fuzzyWord.isBlurred) Modifier.blur(8.dp) else Modifier,
                )
            }
        }
    }
}

// ── 分栏对照阅读视图 ──────────────────────────────
@Composable
fun SplitReadingView(
    paragraphs: List<String>,
    translations: Map<Int, String>,
    currentIndex: Int,
    fontSize: Int,
    textColor: Color,
    translationAlpha: Float = 0.85f,
    onWordClick: (String) -> Unit,
    onVisibleParagraphChanged: (Int) -> Unit = {},
    isTranslating: Boolean = false,
    onRetryTranslate: () -> Unit = {},
    // 插图渲染用：[[IMG:n]] 标记解析到本书的落盘图片目录
    bookId: Long = 0L,
) {
    // 单滚动容器 + 逐段并排：原实现左右两个独立滚动列，
    // 滚一边另一边不动，原文第 N 段会对上译文第 M 段。
    // LazyColumn 化：整书 eager Column 只布局可见段（与 NORMAL 同型修复）；
    // 视口跟随当前段，滑动阅读反向回报 VM
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        // item 0 是表头，段落索引 +1；目标已可见则不打断用户滚动
        val target = currentIndex + 1
        if (currentIndex in paragraphs.indices &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == target }
        ) {
            listState.animateScrollToItem(target)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx -> onVisibleParagraphChanged((idx - 1).coerceAtLeast(0)) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "原文",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalReaderAccent.current,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "译文",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalReaderAccent.current,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            // 与回译视图同款失败/加载态：setReadingMode 对本模式也会自动触发
            // 全书翻译，全空失败时 toast 一闪而过，这里给可发现的重试入口
            // issue 8.3：空白段会写 "" 占位，isEmpty() 不再是可靠失败信号
            if (translations.values.none { it.isNotBlank() }) {
                Spacer(modifier = Modifier.height(4.dp))
                if (isTranslating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = LocalReaderAccent.current,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "正在获取译文...",
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.5f),
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "译文不可用",
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.5f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = onRetryTranslate,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(
                                "点击重试",
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalReaderAccent.current,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        itemsIndexed(
            items = paragraphs,
            key = { index, _ -> index }, // 段落按书加载后不可变，index 是稳定身份
        ) { index, para ->
            val alpha = if (index == currentIndex) 1f else 0.5f
            val translation = translations[index]
            // 插图标记段：整宽渲染插图（无文本可对照，也不参与译文列）
            val imageRef = BookImages.markerRef(para)
            if (imageRef != null) {
                ReaderImageBlock(ref = imageRef, bookId = bookId)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TappableParagraphText(
                        text = AnnotatedString(para),
                        paragraph = para,
                        onWordClick = onWordClick,
                        onSentenceDoubleTap = {},
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        style = readerParagraphStyle(fontSize).copy(
                            color = textColor.copy(alpha = alpha),
                        ),
                    )
                    Text(
                        text = translation ?: "（无译文）",
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        style = readerParagraphStyle(fontSize).copy(
                            color = if (translation != null) {
                                LocalReaderAccent.current.copy(alpha = alpha * translationAlpha)
                            } else {
                                textColor.copy(alpha = alpha * 0.4f)
                            },
                        ),
                    )
                }
            }
            if (index < paragraphs.lastIndex) {
                Divider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = textColor.copy(alpha = 0.1f),
                )
            }
        }
    }
}

// ── 成分分析视图（词性着色）───────────────────────
@Composable
fun PosAnalysisView(
    paragraphs: List<String>,
    currentIndex: Int,
    fontSize: Int,
    textColor: Color,
    onWordClick: (String) -> Unit,
    onVisibleParagraphChanged: (Int) -> Unit = {},
) {
    // 非词性着色（非单词 token、图例文字）跟随阅读主题色：
    // 原实现硬编码 app 级浅色 onSurface，深色主题下深底深字不可读
    fun posColor(tag: PosTag): Color = when (tag) {
        PosTag.NOUN -> Info      // 青灰 - 名词
        PosTag.VERB -> Error     // 赤褐 - 动词
        PosTag.ADJECTIVE -> Warning // 暖金 - 形容词
        PosTag.ADVERB -> Primary  // 暖棕 - 副词
        else -> textColor.copy(alpha = 0.85f)
    }

    // LazyColumn 化：整书 eager Column 每次重组都重排版全文；
    // 词性标注串按 (段落, 透明度, 主题色) 缓存，可见窗口外不参与布局
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex in paragraphs.indices &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == currentIndex }
        ) {
            listState.animateScrollToItem(currentIndex)
        }
    }
    // 反向同步（与 NORMAL/SPLIT 同款）：本视图无表头项，段落索引即 item 索引。
    // 缺失时用户在成分分析模式里滑多远，退出后进度/统计都停在旧位置
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx -> onVisibleParagraphChanged(idx) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(
            items = paragraphs,
            key = { index, _ -> index },
        ) { index, para ->
            val isCurrent = index == currentIndex
            val alpha = if (isCurrent) 1f else 0.5f

            // 词性着色文本（remember 缓存：原实现在组合里裸建，
            // 任何状态变化都重新切词+分类整本书）
            val annotatedText = remember(para, alpha, textColor) {
                buildAnnotatedString {
                    val allMatches = Regex("([a-zA-Z]+)|([^a-zA-Z]+)").findAll(para).toList()
                    allMatches.forEach { match ->
                        val token = match.value
                        if (Regex("^[a-zA-Z]+$").matches(token)) {
                            val word = token.lowercase()
                            val tag = wordPosMap[word] ?: classifyBySuffix(word)
                            val color = posColor(tag).copy(alpha = alpha)
                            withStyle(SpanStyle(color = color)) { append(token) }
                        } else {
                            withStyle(SpanStyle(color = textColor.copy(alpha = alpha * 0.5f))) {
                                append(token)
                            }
                        }
                    }
                }
            }
            TappableParagraphText(
                text = annotatedText,
                paragraph = para,
                onWordClick = onWordClick,
                onSentenceDoubleTap = {},
                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                style = readerParagraphStyle(fontSize),
            )

            if (index < paragraphs.lastIndex) {
                Divider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = textColor.copy(alpha = 0.15f),
                )
            }
        }

        // 底部图例
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PosLegendItem("青灰", Info, "名词")
                PosLegendItem("赤褐", Error, "动词")
                PosLegendItem("暖金", Warning, "形容词")
                PosLegendItem("暖棕", Primary, "副词")
            }
        }
    }
}

@Composable
private fun PosLegendItem(colorName: String, color: Color, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(12.dp),
            shape = CircleShape,
            color = color,
        ) {}
        Spacer(modifier = Modifier.width(4.dp))
        Text("$tag", style = MaterialTheme.typography.labelSmall)
    }
}

// 简单规则后缀分类（供 composable 使用）
private val wordPosMap = mapOf(
    "the" to PosTag.DETERMINER, "a" to PosTag.DETERMINER, "an" to PosTag.DETERMINER,
    "is" to PosTag.VERB, "am" to PosTag.VERB, "are" to PosTag.VERB,
    "was" to PosTag.VERB, "were" to PosTag.VERB, "be" to PosTag.VERB,
    "been" to PosTag.VERB, "being" to PosTag.VERB,
    "do" to PosTag.VERB, "does" to PosTag.VERB, "did" to PosTag.VERB,
    "have" to PosTag.VERB, "has" to PosTag.VERB, "had" to PosTag.VERB,
    "will" to PosTag.VERB, "would" to PosTag.VERB,
    "can" to PosTag.VERB, "could" to PosTag.VERB, "should" to PosTag.VERB,
    "and" to PosTag.CONJUNCTION, "but" to PosTag.CONJUNCTION, "or" to PosTag.CONJUNCTION,
    "in" to PosTag.PREPOSITION, "on" to PosTag.PREPOSITION, "at" to PosTag.PREPOSITION,
    "by" to PosTag.PREPOSITION, "for" to PosTag.PREPOSITION, "with" to PosTag.PREPOSITION,
    "to" to PosTag.PREPOSITION, "of" to PosTag.PREPOSITION, "from" to PosTag.PREPOSITION,
    "not" to PosTag.ADVERB, "no" to PosTag.ADVERB, "very" to PosTag.ADVERB,
    "also" to PosTag.ADVERB, "just" to PosTag.ADVERB, "only" to PosTag.ADVERB,
    "i" to PosTag.PRONOUN, "you" to PosTag.PRONOUN, "he" to PosTag.PRONOUN,
    "she" to PosTag.PRONOUN, "it" to PosTag.PRONOUN, "we" to PosTag.PRONOUN,
    "they" to PosTag.PRONOUN, "this" to PosTag.PRONOUN, "that" to PosTag.PRONOUN,
    "my" to PosTag.PRONOUN, "your" to PosTag.PRONOUN, "his" to PosTag.PRONOUN,
    "our" to PosTag.PRONOUN, "their" to PosTag.PRONOUN,
)

private fun classifyBySuffix(word: String): PosTag {
    val suffixes = listOf(
        "tion" to PosTag.NOUN, "sion" to PosTag.NOUN, "ment" to PosTag.NOUN,
        "ness" to PosTag.NOUN, "ity" to PosTag.NOUN, "ance" to PosTag.NOUN,
        "ence" to PosTag.NOUN, "er" to PosTag.NOUN, "or" to PosTag.NOUN, "ist" to PosTag.NOUN,
        "ing" to PosTag.VERB, "ed" to PosTag.VERB, "ify" to PosTag.VERB,
        "ful" to PosTag.ADJECTIVE, "less" to PosTag.ADJECTIVE,
        "ous" to PosTag.ADJECTIVE, "ive" to PosTag.ADJECTIVE,
        "able" to PosTag.ADJECTIVE, "ible" to PosTag.ADJECTIVE,
        "al" to PosTag.ADJECTIVE, "ical" to PosTag.ADJECTIVE,
        "ly" to PosTag.ADVERB,
    )
    for ((suffix, tag) in suffixes) {
        if (word.length > suffix.length + 2 && word.endsWith(suffix)) return tag
    }
    return PosTag.NOUN
}

// ── 中译英回译视图 ───────────────────────────────
@Composable
fun BackTranslationView(
    paragraphs: List<String>,
    translations: Map<Int, String>,
    currentIndex: Int,
    fontSize: Int,
    textColor: Color,
    primaryColor: Color,
    translationAlpha: Float = 0.85f,
    isTranslating: Boolean = false,
    onRetryTranslate: () -> Unit = {},
    onVisibleParagraphChanged: (Int) -> Unit = {},
    // 插图渲染用：[[IMG:n]] 标记解析到本书的落盘图片目录
    bookId: Long = 0L,
) {
    // 直接派生即可，无需 remember + LaunchedEffect 多一次组合跳转
    // issue 8.3：失败段不再写 "" 占位，但空白段会写 ""——全空白 Map
    // 同样视为"无译文"，重试按钮才可达
    val hasTranslation = translations.values.any { it.isNotBlank() }
    // 揭示是视图本地状态：原实现"查看原文"会 setReadingMode(NORMAL)，
    // 把用户踢出回译模式还持久化了模式切换。
    // 以段落列表为 key：换书后 revealed 必须复位，否则新书直接继承
    // 上一本书"已揭示"的状态（issue 3.3）
    var revealed by rememberSaveable(paragraphs) { mutableStateOf(false) }

    // 单 LazyColumn 逐段并排（译文 | 原文）：原实现左右两个独立滚动容器
    // 整书 eager 渲染，滚动不同步时原文第 N 段对上译文第 M 段（与分栏视图
    // 同型缺陷），且未揭示时全书每段都挂 blur 渲染层。改单列后段落严格对齐、
    // 只布局可见段、视口跟随当前段
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        val target = currentIndex + 1
        if (currentIndex in paragraphs.indices &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == target }
        ) {
            listState.animateScrollToItem(target)
        }
    }
    // 反向同步（与 SPLIT 同款）：item 0 是表头，段落索引 = item 索引 - 1。
    // 缺失时用户在回译模式里滑多远，退出后进度/统计都停在旧位置
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx -> onVisibleParagraphChanged((idx - 1).coerceAtLeast(0)) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        // 顶部说明
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = primaryColor.copy(alpha = 0.08f)),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "中译英回译练习",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "左侧阅读中文译文，尝试翻译成英文，然后点击查看原文对照",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.7f),
                )
                if (!hasTranslation) {
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isTranslating) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = primaryColor,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "正在获取译文...",
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.5f),
                            )
                        }
                    } else {
                        // 翻译失败/未触发时不转假圈：给出明确状态 + 重试入口
                        // （旧实现只看 translations.isEmpty()，失败后再无动静，
                        // 用户对着永远转不完的 spinner 没有任何可做的事）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "译文不可用",
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.5f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = onRetryTranslate,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    "点击重试",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = primaryColor,
                                )
                            }
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "中文译文",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "英文原文",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = textColor.copy(alpha = 0.6f),
                        )
                        TextButton(
                            onClick = { revealed = !revealed },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Icon(
                                if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (revealed) "隐藏原文" else "查看原文",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            itemsIndexed(
                items = paragraphs,
                key = { index, _ -> index },
            ) { index, para ->
                val translation = translations[index]
                val alpha = if (index == currentIndex) 1f else 0.5f
                // 插图标记段：整宽渲染插图（无译文/原文可对照）
                val imageRef = BookImages.markerRef(para)
                if (imageRef != null) {
                    ReaderImageBlock(ref = imageRef, bookId = bookId)
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = translation ?: "...",
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 6.dp),
                            style = readerParagraphStyle(fontSize).copy(
                                color = primaryColor.copy(alpha = alpha * translationAlpha),
                            ),
                        )
                        Text(
                            text = para,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 6.dp)
                                // 揭示后或译文还没加载完（没东西可挡）时不模糊
                                .blur(if (revealed || !hasTranslation) 0.dp else 6.dp),
                            style = readerParagraphStyle(fontSize).copy(
                                color = textColor.copy(alpha = if (revealed) alpha else alpha * 0.4f),
                            ),
                        )
                    }
                }
                if (index < paragraphs.lastIndex) {
                    Divider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = textColor.copy(alpha = 0.1f),
                    )
                }
            }
        }
    }
}

// ── 听写练习视图 ─────────────────────────────────
@Composable
fun DictationReadingView(
    clozeWords: List<ClozeWord>,
    answer: String?,
    fontSize: Int,
    textColor: Color,
    paragraph: String,
    onCheckAnswer: (String) -> Boolean,
    onStartDictation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (clozeWords.isEmpty()) {
            // 还没开始听写
            Icon(
                Icons.Default.RecordVoiceOver,
                null,
                modifier = Modifier.size(64.dp),
                tint = LocalReaderAccent.current,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "听写练习",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "点击下方按钮开始，\n系统会随机隐藏段落中的部分单词",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(onClick = onStartDictation) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("开始听写")
            }
        } else {
            // 听写进行中
            Text(
                "请填写划线单词",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = LocalReaderAccent.current,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = buildAnnotatedString {
                    clozeWords.forEach { word ->
                        if (word.isWord) {
                            if (word.isHidden) {
                                // 填空占位：原实现直接把答案单词加下划线原样输出，
                                // 等于把答案写在题面上
                                withStyle(SpanStyle(
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.Bold,
                                    color = LocalReaderAccent.current,
                                )) { append("____") }
                            } else {
                                withStyle(SpanStyle(color = textColor)) { append(word.text) }
                            }
                        } else {
                            withStyle(SpanStyle(color = textColor.copy(alpha = 0.7f))) { append(word.text) }
                        }
                    }
                },
                style = readerParagraphStyle(fontSize, 2f),
            )
            Spacer(modifier = Modifier.height(24.dp))

            // 核对答案：输入与下一个隐藏词匹配才揭示（VM 侧判定并反馈）
            val hiddenWords = clozeWords.filter { it.isHidden }.map { it.text }
            if (hiddenWords.isNotEmpty()) {
                var inputWord by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = inputWord,
                    onValueChange = { inputWord = it },
                    label = { Text("填写答案") },
                    modifier = Modifier.fillMaxWidth(0.8f),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        if (inputWord.isNotBlank() && onCheckAnswer(inputWord)) {
                            inputWord = ""
                        }
                    }) {
                        Text("核对答案")
                    }
                    OutlinedButton(onClick = {
                        inputWord = ""
                        onStartDictation()
                    }) {
                        Text("重新出题")
                    }
                }
                if (answer != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Success.copy(alpha = 0.1f)),
                    ) {
                        Text(
                            "答案: $answer",
                            modifier = Modifier.padding(12.dp),
                            color = Success,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

// ── 底部导航栏 ──────────────────────────────────
@Composable
fun ReadingBottomBar(
    uiState: ReaderUiState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Int) -> Unit,
    textColor: Color,
    onFontDelta: (Int) -> Unit = {},
    onCycleTheme: () -> Unit = {},
    onToggleSerif: () -> Unit = {},
) {
    Surface(
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
        // Scaffold contentWindowInsets=0：底栏必须自己避让手势导航条，
        // 否则上一段/下一段按钮整行被系统手势区压住
        modifier = Modifier.navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            // 快捷设置行（微信读书式）：字号 ±、主题循环、衬线切换。
            // 沉浸阅读最高频的三个调整一步直达，不再进设置弹窗
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    ) {
                        // 44dp 触摸目标（原 36dp 低于最小可点标准，相邻易误触）
                        IconButton(onClick = { onFontDelta(-1) }, modifier = Modifier.size(44.dp)) {
                            Icon(
                                Icons.Default.TextDecrease,
                                contentDescription = "减小字号",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            "${uiState.fontSize}sp",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { onFontDelta(1) }, modifier = Modifier.size(44.dp)) {
                            Icon(
                                Icons.Default.TextIncrease,
                                contentDescription = "增大字号",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                IconButton(onClick = onCycleTheme) {
                    Icon(
                        when (uiState.theme) {
                            ReadingTheme.LIGHT -> Icons.Default.LightMode
                            ReadingTheme.SEPIA -> Icons.Default.Contrast
                            ReadingTheme.DARK -> Icons.Default.DarkMode
                        },
                        contentDescription = "切换阅读主题（当前：${uiState.theme.displayName}）",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggleSerif) {
                    Icon(
                        if (uiState.serifFont) Icons.Default.TextFields else Icons.Default.FormatSize,
                        contentDescription = if (uiState.serifFont) "衬线字体（开）" else "衬线字体（关）",
                        tint = if (uiState.serifFont) LocalReaderAccent.current else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 进度条：拖动期间只更新本地值，松手才提交。
            // 原实现逐像素调 goToParagraph：每像素都触发 stopAllPlayback +
            // saveProgress，挖空/模糊模式下还会逐像素重生成整段词序列
            var seekValue by remember { mutableStateOf(uiState.currentParagraphIndex.toFloat()) }
            // 拖动中标志：播放中程序化推进 currentIndex 时不再回写滑杆——
            // 旧实现用户拖到一半会被 LaunchedEffect 拉回当前段，拖动永远完不成
            var isSeeking by remember { mutableStateOf(false) }
            // 索引被程序化推进（播放/上下段/跳转）时同步滑杆位置
            LaunchedEffect(uiState.currentParagraphIndex) {
                if (!isSeeking) seekValue = uiState.currentParagraphIndex.toFloat()
            }
            Slider(
                value = seekValue.coerceIn(0f, (uiState.paragraphs.size - 1).coerceAtLeast(1).toFloat()),
                onValueChange = {
                    seekValue = it
                    isSeeking = true
                },
                onValueChangeFinished = {
                    isSeeking = false
                    onSeek(seekValue.toInt())
                },
                valueRange = 0f..(uiState.paragraphs.size - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = LocalReaderAccent.current,
                    activeTrackColor = LocalReaderAccent.current,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrev, enabled = uiState.currentParagraphIndex > 0) {
                    Icon(Icons.Default.NavigateBefore, "上一段")
                }
                // 拖动中实时显示目标位置：旧实现显示 currentParagraphIndex，
                // 长书拖动全程纹丝不动，松手前不知道会跳到哪
                Text(
                    text = if (isSeeking) {
                        "松开跳到第 ${(seekValue.toInt() + 1).coerceAtMost(uiState.paragraphs.size)} 段"
                    } else {
                        "${uiState.readingMode.displayName} · " +
                            "${(uiState.currentParagraphIndex + 1)}/${uiState.paragraphs.size}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSeeking) LocalReaderAccent.current else MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = onNext, enabled = uiState.currentParagraphIndex < uiState.paragraphs.size - 1) {
                    Icon(Icons.Default.NavigateNext, "下一段")
                }
            }
        }
    }
}

// ── 模式选择（底部抽屉：M3 规范的拇指可达区弹层） ────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelectorDialog(
    currentMode: ReadingMode,
    onSelect: (ReadingMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            "选择阅读模式",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.heightIn(max = 480.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(ReadingMode.entries) { _, mode ->
                ListItem(
                    headlineContent = { Text(mode.displayName) },
                    supportingContent = { Text(getModeDescription(mode)) },
                    leadingContent = {
                        RadioButton(
                            selected = mode == currentMode,
                            onClick = { onSelect(mode) },
                        )
                    },
                    modifier = Modifier.clickable { onSelect(mode) },
                )
            }
        }
    }
}

private fun getModeDescription(mode: ReadingMode): String = when (mode) {
    ReadingMode.NORMAL -> "普通阅读，点击单词查释义"
    ReadingMode.RSVP -> "仿生阅读，快速捕捉关键词"
    ReadingMode.SPEED -> "逐句闪现，训练阅读速度"
    ReadingMode.CLOZE -> "挖空练习，隐藏单词填空"
    ReadingMode.FUZZY -> "模糊听读，训练听力复述"
    ReadingMode.DICTATION -> "听写练习，随机隐藏单词听写"
    ReadingMode.SPLIT -> "分栏对照，左原文右译文"
    ReadingMode.BACK_TRANSLATION -> "中译英练习，看译文回译英文"
    ReadingMode.POS_ANALYSIS -> "成分分析，词性着色标注"
}

// ── 阅读器设置对话框 ────────────────────────────

// 设置弹窗文案表：提到顶层，避免每次重组都重新分配
private val RSVP_STRENGTH_LABELS = listOf("30%", "40%", "50%", "60%", "70%")

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsDialog(
    fontSize: Int,
    rsvpSpeed: Int,
    rsvpStrength: Int,
    translationAlpha: Float,
    showWordLevelColors: Boolean,
    showKnownWordsHighlight: Boolean,
    pageMode: Boolean = false,
    onFontSizeChange: (Int) -> Unit,
    onSpeedChange: (Int) -> Unit,
    onStrengthChange: (Int) -> Unit,
    onTranslationAlphaChange: (Float) -> Unit,
    onWordLevelColorsToggle: () -> Unit,
    onKnownWordsHighlightToggle: () -> Unit,
    onTogglePageMode: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "阅读设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            // ── 排版 ──────────────────────────────
            SettingsGroupLabel("排版")
            // 这些值来自 DataStore 持久化，历史版本可能写入越界值；
            // Slider 要求 value 在 valueRange 内，列表索引也要收敛，否则抽屉一开就崩
            SettingSliderRow(
                label = "字体大小",
                valueText = "${fontSize}sp",
                value = fontSize.toFloat().coerceIn(12f, 32f),
                onValueChange = { onFontSizeChange(it.toInt()) },
                valueRange = 12f..32f,
                steps = 19,
                preview = SliderPreview.FONT_SIZE,
            )
            SwitchSettingRow(
                title = "左右翻页",
                subtitle = if (pageMode) "仿书页横向翻页阅读" else "当前：上下滚动阅读",
                checked = pageMode,
                onToggle = onTogglePageMode,
            )

            // ── 仿生阅读 ──────────────────────────
            SettingsGroupLabel("仿生阅读")
            // steps=13：50 字/分钟一档，避免逐像素连续值带来的无意义精度抖动
            SettingSliderRow(
                label = "RSVP 速度",
                valueText = "$rsvpSpeed 字/分钟",
                value = rsvpSpeed.toFloat().coerceIn(100f, 800f),
                onValueChange = { onSpeedChange(it.toInt()) },
                valueRange = 100f..800f,
                steps = 13,
                preview = SliderPreview.RSVP_SPEED,
            )
            SettingSliderRow(
                label = "加粗强度",
                valueText = RSVP_STRENGTH_LABELS[rsvpStrength.coerceIn(1, 5) - 1],
                value = rsvpStrength.toFloat().coerceIn(1f, 5f),
                onValueChange = { onStrengthChange(it.toInt()) },
                valueRange = 1f..5f,
                steps = 3,
                preview = SliderPreview.BOLD,
            )

            // ── 翻译 ──────────────────────────────
            SettingsGroupLabel("翻译")
            SettingSliderRow(
                label = "译文透明度",
                valueText = "${(translationAlpha * 100).toInt()}%",
                value = translationAlpha,
                onValueChange = onTranslationAlphaChange,
                valueRange = 0.3f..1f,
                // 5% 一档（30%→100% 共 14 档），thumb 吸附刻度点
                steps = 13,
                preview = SliderPreview.ALPHA,
            )

            // ── 词色 ──────────────────────────────
            SettingsGroupLabel("词色")
            SwitchSettingRow(
                title = "Collins 词频色彩",
                checked = showWordLevelColors,
                onToggle = onWordLevelColorsToggle,
            )
            SwitchSettingRow(
                title = "生词高亮",
                checked = showKnownWordsHighlight,
                onToggle = onKnownWordsHighlightToggle,
            )
            // Collins 词级颜色图例：纯展示徽章（原 AssistChip 空点击有水波纹，
            // 且 weight 等分在窄屏会挤压截断），FlowRow 自动换行。
            // §4.6.1：等级 chip 用难度色 50% 透明底 + 药丸圆角 + 对应 on 色
            if (showWordLevelColors) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        L1 to OnSurface,
                        L2 to OnSurface,
                        L3 to OnSurface,
                        L4 to OnPrimaryContainer,
                        L5 to OnPrimaryContainer,
                    ).forEachIndexed { index, (bg, fg) ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = bg.copy(alpha = 0.5f),
                        ) {
                            Text(
                                listOf("核心", "进阶", "提高", "高阶", "学术")[index],
                                style = MaterialTheme.typography.labelMedium,
                                color = fg,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/** 设置弹窗分组小标题：视觉分节，降低四滑杆三开关平铺的密度 */
@Composable
private fun SettingsGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = LocalReaderAccent.current,
        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
    )
}

/**
 * 滑块预览条类型（§4.6.3「三个滑块视觉完全一致」修复）：
 * 每个滑块上方有实时预览，用预览区分滑块用途——
 * Readwise Reader 的实时预览 + Kindle Aa 菜单结合。
 */
private enum class SliderPreview {
    /** 无预览 */
    NONE,
    /** 字号滑块："Aa" 样本（当前字号） */
    FONT_SIZE,
    /** RSVP 滑块：箭头密度（当前速度） */
    RSVP_SPEED,
    /** 加粗滑块：加粗 "Aa" */
    BOLD,
    /** 译文透明度滑块：带透明度的方块 */
    ALPHA,
}

/**
 * 「标签左 + 当前值右 + 预览条 + 滑块 + 刻度点」滑杆行（改版B）。
 *
 * - 拖动时视线不必上移找数值；
 * - 上方预览条实时反映该滑块的效果（区分四个用途相同的滑块）；
 * - 下方等距刻度点 + 当前值位置放大高亮（12×12dp primary，
 *   300ms spring 弹性），thumb 松手自动吸附最近刻度（Slider steps）。
 */
@Composable
private fun SettingSliderRow(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    preview: SliderPreview = SliderPreview.NONE,
) {
    val accent = LocalReaderAccent.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
        // 预览条：居中 28dp 高，内容随当前值实时变化
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (preview) {
                SliderPreview.FONT_SIZE -> Text(
                    "Aa",
                    fontSize = value.coerceIn(12f, 26f).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SliderPreview.RSVP_SPEED -> Text(
                    // 箭头密度随速度增加：100-200 一档 / 300-600 两档 / 700+ 三档
                    buildString {
                        repeat(((value - 100f) / 300f).toInt().coerceIn(0, 2) + 1) {
                            if (it > 0) append(" ")
                            append("→")
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SliderPreview.BOLD -> Text(
                    "Aa",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SliderPreview.ALPHA -> Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent.copy(alpha = value.coerceIn(0f, 1f))),
                )
                SliderPreview.NONE -> Unit
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = MaterialTheme.colorScheme.outline,
            ),
        )
        // 刻度点行：steps>0 时显示 steps+2 个等距 dot（§5.2 带刻度滑块）。
        // 当前值 dot 放大为 12dp primary，弹性缩放（spring ~300ms）
        if (steps > 0) {
            val tickCount = steps + 2
            val fraction = (
                (value - valueRange.start) /
                    (valueRange.endInclusive - valueRange.start)
                ).coerceIn(0f, 1f)
            val currentIndex = Math.round(fraction * (tickCount - 1))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 与 M3 Slider thumb 行程对齐：两端各留半个 thumb 宽
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(tickCount) { index ->
                    val isCurrent = index == currentIndex
                    val dotSize by animateDpAsState(
                        targetValue = if (isCurrent) 12.dp else 4.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "tickSize",
                    )
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .clip(CircleShape)
                            .background(
                                if (isCurrent) accent else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/**
 * 开关行：整行可点（toggleable + Role.Switch）。旧实现只有右侧 Switch
 * 可点，点文字无反应——移动端高发误操作点。
 */
@Composable
private fun SwitchSettingRow(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = { onToggle() },
            )
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

// ── 单词详情（底部抽屉：点词查义高频操作，拇指可达） ────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordDetailDialog(
    word: String,
    definition: String?,
    wordLevel: WordLevel = WordLevel.UNKNOWN,
    onAddToVocabulary: () -> Unit,
    onSpeak: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = onSpeak) {
                    Icon(Icons.Default.PlayCircleFilled, "播放发音", tint = LocalReaderAccent.current)
                }
                if (wordLevel != WordLevel.UNKNOWN) {
                    val badgeColor = when (wordLevel) {
                        WordLevel.CORE -> WordLevelCore
                        WordLevel.INTERMEDIATE -> WordLevelIntmd
                        WordLevel.UPPER_INTERMEDIATE -> WordLevelUpper
                        WordLevel.ADVANCED -> WordLevelAdv
                        WordLevel.RARE -> WordLevelRare
                        WordLevel.UNKNOWN -> Color.Gray
                    }
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                wordLevel.displayName,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = badgeColor.copy(alpha = 0.15f),
                            labelColor = badgeColor,
                        ),
                        border = null,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 超长释义限高 + 可滚：旧实现无约束，长释义把 BottomSheet 顶满屏
            Text(
                text = definition ?: "未找到释义",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            )
            if (wordLevel != WordLevel.UNKNOWN) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = wordLevel.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    onAddToVocabulary()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("加入生词本")
            }
        }
    }
}

// ── 章节目录导航（底部抽屉：长列表在抽屉里更接近拇指） ────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterNavDialog(
    paragraphs: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 打开即定位到当前段：长书原来停在第 0 段，用户得自己翻找。
    // 当前段落在视口中央（原 top 对齐在长列表里更难感知上下文）
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        listState.scrollToItem((currentIndex - 3).coerceAtLeast(0))
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            "段落导航",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = 480.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(paragraphs) { idx, para ->
                // 显示段落前60字预览（插图标记段显示占位）
                val isImage = BookImages.isImageMarker(para)
                val preview = if (isImage) {
                    "🖼 插图"
                } else {
                    para.take(60).replace("\n", " ") +
                        if (para.length > 60) "…" else ""
                }
                ListItem(
                    headlineContent = {
                        Text(
                            preview,
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    trailingContent = {
                        if (idx == currentIndex) {
                            Icon(Icons.Default.PlayArrow, "当前", tint = LocalReaderAccent.current)
                        }
                    },
                    modifier = Modifier.clickable {
                        onSelect(idx)
                        onDismiss()
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (idx == currentIndex)
                            LocalReaderAccent.current.copy(alpha = 0.1f) else Color.Transparent,
                    ),
                )
            }
        }
    }
}

// ── 选句翻译（底部抽屉） ─────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentenceTranslationDialog(
    sentence: String,
    translation: String?,
    isLoading: Boolean,
    onSpeak: () -> Unit,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())   // 超长句子+译文不再溢出被裁
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("句子翻译", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onSpeak) {
                    Icon(Icons.Default.PlayCircleFilled, "播放朗读", tint = LocalReaderAccent.current)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = sentence,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("翻译中...")
                }
            } else if (!translation.isNullOrBlank()) {
                Text(
                    text = translation,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalReaderAccent.current,
                )
            } else {
                // issue 8.9：null 语义是"词典/模型都没翻出来"，
                // 与"请求出错"区分开，避免误导用户以为网络故障。
                // 中性色 + 重试入口：旧实现红色文案且无重试，只能关抽屉重双击
                Text(
                    text = "暂无翻译结果",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("重试")
                }
            }
        }
    }
}

// ── 段落点击辅助：把点击位置解析为单词 / 句子 ──────────────
private val WordRegex = Regex("[a-zA-Z]+")
// 词色分词与纯词判定：原本散落在各渲染分支的组合期内反复编译，
// 提为文件级常量后每次调用复用同一 Pattern
private val WordSplitRegex = Regex("([a-zA-Z]+)|([^a-zA-Z]+)")
private val PureWordRegex = Regex("^[a-zA-Z]+$")
// issue 8.6：同时认 ASCII 与 CJK 句末标点——旧实现只认 [.!?]，
// 双击中文/日文句子只会截到第一个英文句号，整句后半段丢失
private val SentenceEndRegex = Regex("[.!?。！？；]")

/**
 * 根据 TextLayoutResult 把点击位置反查成段落中命中位置的单词。
 * 若点击位置落在标点 / 空白，返回 null。
 */
private fun findWordAtOffset(paragraph: String, offset: Offset, layout: TextLayoutResult): String? {
    val charIndex = layout.getOffsetForPosition(offset)
    return WordRegex.findAll(paragraph).find { it.range.contains(charIndex) }?.value
}

/**
 * 根据 TextLayoutResult 把点击位置反查成包含该位置的句子。
 * 若无句子边界，返回整段。
 */
private fun findSentenceAtOffset(paragraph: String, offset: Offset, layout: TextLayoutResult): String {
    val charIndex = layout.getOffsetForPosition(offset)
    return findSentenceAtGlobalOffset(paragraph, charIndex)
}

/**
 * 按字符 offset 在段落里找包含该位置的完整句子（跨页切片双击翻译用：
 * 句子可能被分页切开，这里始终在完整段落文本上定位整句，避免拿到残句）。
 */
private fun findSentenceAtGlobalOffset(paragraph: String, charOffset: Int): String {
    val matches = SentenceEndRegex.findAll(paragraph).toList()
    val start = matches.lastOrNull { it.range.first < charOffset }?.range?.last?.plus(1) ?: 0
    val end = matches.firstOrNull { charOffset < it.range.first }?.range?.first?.plus(1)
        ?: paragraph.length
    return paragraph.substring(start, end).trim()
}

/**
 * 支持"点击单词查释义 / 双击句子翻译"的段落 Text。
 * 使用 TextLayoutResult 反查命中位置，避免把整段当成一个单词。
 */
@Composable
private fun TappableParagraphText(
    text: AnnotatedString,
    paragraph: String,
    onWordClick: (String) -> Unit,
    onSentenceDoubleTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle(),
    // 跨页切片用：双击时把切片内局部 offset 换算到完整段落坐标系找整句
    // （句子可能被分页切开，直接在切片文本上找只会得到残句）
    sentenceLookup: ((localCharOffset: Int) -> String)? = null,
) {
    // issue 3.7：remember(paragraph) 而非 remember{}——LazyColumn 会对滚出又滚回的
    // 可见 item 复用同一组合实例，不带 key 时会在换段后残留上一段的 TextLayoutResult，
    // 点击仍用旧布局反查坐标 → 段滚出再回来点击失效。
    val textLayoutResult = remember(paragraph) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        modifier = modifier
            .pointerInput(paragraph) {
                detectTapGestures(
                    onTap = { offset ->
                        textLayoutResult.value?.let { layout ->
                            findWordAtOffset(paragraph, offset, layout)?.let { onWordClick(it) }
                        }
                    },
                    onDoubleTap = { offset ->
                        textLayoutResult.value?.let { layout ->
                            val sentence = if (sentenceLookup != null) {
                                sentenceLookup(layout.getOffsetForPosition(offset))
                            } else {
                                findSentenceAtOffset(paragraph, offset, layout)
                            }
                            if (sentence.isNotBlank()) onSentenceDoubleTap(sentence)
                        }
                    },
                )
            },
        style = style,
        onTextLayout = { textLayoutResult.value = it },
    )
}
