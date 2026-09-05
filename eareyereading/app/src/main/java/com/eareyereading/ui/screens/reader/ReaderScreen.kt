package com.eareyereading.ui.screens.reader

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.domain.model.ReadingMode
import com.eareyereading.domain.model.ReadingTheme
import com.eareyereading.ui.components.shimmer
import com.eareyereading.ui.theme.*
import com.eareyereading.util.notificationPermissionGranted
import com.eareyereading.util.rememberNotificationPermissionRequester
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 阅读页主入口：顶栏 + 各阅读模式视图调度 + 弹窗/抽屉编排。
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
