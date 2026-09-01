package com.eareyereading.ui.screens.reader

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.domain.model.ReadingMode
import com.eareyereading.domain.model.ReadingTheme
import com.eareyereading.ui.theme.*
import com.eareyereading.util.ClozeWord
import com.eareyereading.util.CollinsClassifier
import com.eareyereading.util.PosTagger
import com.eareyereading.util.PosTag
import com.eareyereading.util.WordAnalyzer
import com.eareyereading.util.CollinsClassifier.WordLevel

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

    val backgroundColor = when (uiState.theme) {
        ReadingTheme.LIGHT -> MaterialTheme.colorScheme.background
        ReadingTheme.DARK -> DarkBg
        ReadingTheme.SEPIA -> SepiaBg
    }
    val textColor = when (uiState.theme) {
        ReadingTheme.LIGHT -> MaterialTheme.colorScheme.onBackground
        ReadingTheme.DARK -> DarkText
        ReadingTheme.SEPIA -> SepiaText
    }

    Scaffold(
        topBar = {
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
                                color = Primary,
                            )
                        } else {
                            Icon(
                                if (uiState.showTranslation) Icons.Default.Translate else Icons.Outlined.Translate,
                                "翻译",
                                tint = if (uiState.showTranslation) Primary else LocalContentColor.current,
                            )
                        }
                    }
                    // 播放 / 暂停（NORMAL 模式下等价于从当前段开始自动朗读）
                    IconButton(onClick = { viewModel.togglePlay() }) {
                        Icon(
                            // isTtsPlaying 也要算播放中：挖空/听写等模式走单段朗读
                            if (uiState.isPlaying || uiState.isAutoReading || uiState.isTtsPlaying)
                                Icons.Default.Pause else Icons.Default.PlayArrow,
                            "播放",
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
        },
        bottomBar = {
            ReadingBottomBar(
                uiState = uiState,
                onPrev = viewModel::prevParagraph,
                onNext = viewModel::nextParagraph,
                onSeek = viewModel::goToParagraph,
                textColor = textColor,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                when (uiState.readingMode) {
                    ReadingMode.NORMAL -> NormalReadingView(
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
                        isAutoReading = uiState.isAutoReading,
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
                    )
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
                    )
                    ReadingMode.BACK_TRANSLATION -> BackTranslationView(
                        paragraphs = uiState.paragraphs,
                        translations = uiState.paragraphTranslations,
                        currentIndex = uiState.currentParagraphIndex,
                        fontSize = uiState.fontSize,
                        textColor = textColor,
                        primaryColor = Primary,
                        translationAlpha = uiState.translationAlpha,
                        isTranslating = uiState.isTranslating,
                        onRetryTranslate = viewModel::retryTranslation,
                        onVisibleParagraphChanged = viewModel::onVisibleParagraphChanged,
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
    }

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
            onFontSizeChange = viewModel::setFontSize,
            onSpeedChange = viewModel::setRsvpSpeed,
            onStrengthChange = viewModel::setRsvpStrength,
            onTranslationAlphaChange = viewModel::setTranslationAlpha,
            onWordLevelColorsToggle = viewModel::toggleWordLevelColors,
            onKnownWordsHighlightToggle = viewModel::toggleKnownWordsHighlight,
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

/**
 * 把用户高亮以背景色形式叠加到已按词着色的 AnnotatedString 上。
 *
 * 词级着色（Collins 词频/生词本）与用户高亮是正交两层：先按词上色，
 * 再用 addStyle 叠背景，词色保持不变。此前只有"普通模式"分支渲染高亮，
 * 而 showKnownWordsHighlight 默认开——任何生词本非空的用户加的高亮
 * 全部入库成功但永远不画出来。
 * 偏移按段落坐标系，越界/反向脏数据收敛后跳过（不落异常）。
 */
private fun AnnotatedString.Builder.overlayParagraphHighlights(
    para: String,
    highlights: List<HighlightData>,
) {
    highlights.forEach { h ->
        val start = h.startOffset.coerceIn(0, para.length)
        val end = h.endOffset.coerceIn(start, para.length)
        if (end > start) {
            addStyle(SpanStyle(background = h.color.copy(alpha = 0.25f)), start, end)
        }
    }
}

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
    classifier: CollinsClassifier = remember { CollinsClassifier() },
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
            val isCurrent = index == currentIndex
            val isBookmarked = index in bookmarkedParagraphs
            val paraHighlights = highlights[index] ?: emptyList()
            val alpha = if (isCurrent) 1f else if (index < currentIndex) 0.4f else 0.7f

            // 朗读中的当前段落：背景直接加在内容容器上。
            // 原实现额外放了一个包 Text("") 的 Surface —— 零高度，背景永远不可见
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isCurrent && isAutoReading) {
                            Modifier
                                .background(Primary.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
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
                // 显示已读/当前/未读句子
                currentSentences.forEachIndexed { sIdx, sentence ->
                    val sAlpha = when {
                        sIdx < currentSentenceIndex -> 0.45f  // 已读完
                        sIdx == currentSentenceIndex -> 1f      // 当前读
                        else -> 0.6f                           // 未读
                    }
                    val bgColor = if (sIdx == currentSentenceIndex)
                        Primary.copy(alpha = 0.10f) else Color.Transparent

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                        color = bgColor,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        // 朗读中的句子也走 TappableParagraphText：朗读时点词查义
                        // 是核心功能，此前该分支只渲染纯 Text 完全不可点（issue 3.4）
                        val sentenceText = if (showWordLevelColors) {
                            buildAnnotatedString {
                                val words = Regex("([a-zA-Z]+)|([^a-zA-Z]+)").findAll(sentence)
                                words.forEach { match ->
                                    val word = match.value
                                    if (Regex("^[a-zA-Z]+$").matches(word)) {
                                        val level = classifier.classify(word)
                                        val color = when (level) {
                                            WordLevel.CORE -> WordLevelCore
                                            WordLevel.INTERMEDIATE -> WordLevelIntmd
                                            WordLevel.UPPER_INTERMEDIATE -> WordLevelUpper
                                            WordLevel.ADVANCED -> WordLevelAdv
                                            WordLevel.RARE -> WordLevelRare
                                            WordLevel.UNKNOWN -> textColor.copy(alpha = sAlpha * 0.5f)
                                        }
                                        withStyle(SpanStyle(color = color.copy(alpha = sAlpha))) { append(word) }
                                    } else {
                                        withStyle(SpanStyle(color = textColor.copy(alpha = sAlpha * 0.6f))) { append(word) }
                                    }
                                }
                            }
                        } else {
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = textColor.copy(alpha = sAlpha))) { append(sentence) }
                            }
                        }
                        TappableParagraphText(
                            text = sentenceText,
                            paragraph = sentence,
                            onWordClick = onWordClick,
                            onSentenceDoubleTap = onSentenceDoubleTap,
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                            style = TextStyle(
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.8).sp,
                            ),
                        )
                    }
                }
            } else {
                // Collins 词频色彩（非朗读中）
                if (showWordLevelColors) {
                    // 排版结果按真正影响产物的键缓存：播放句级状态变化不再重切全部可见段落
                    val annotatedText = remember(para, alpha, textColor, showKnownWordsHighlight, knownWords, paraHighlights) {
                        buildAnnotatedString {
                            val words = Regex("([a-zA-Z]+)|([^a-zA-Z]+)").findAll(para)
                            words.forEach { match ->
                                val word = match.value
                                if (Regex("^[a-zA-Z]+$").matches(word)) {
                                    val level = classifier.classify(word)
                                    val lower = word.lowercase()
                                    // 生词本优先：已认识的词用绿色
                                    val color = when {
                                        showKnownWordsHighlight && lower in knownWords -> Success.copy(alpha = alpha)
                                        else -> when (level) {
                                            WordLevel.CORE -> WordLevelCore
                                            WordLevel.INTERMEDIATE -> WordLevelIntmd
                                            WordLevel.UPPER_INTERMEDIATE -> WordLevelUpper
                                            WordLevel.ADVANCED -> WordLevelAdv
                                            WordLevel.RARE -> WordLevelRare
                                            WordLevel.UNKNOWN -> textColor.copy(alpha = alpha * 0.5f)
                                        }.let { it.copy(alpha = alpha) }
                                    }
                                    withStyle(SpanStyle(color = color)) { append(word) }
                                } else {
                                    withStyle(SpanStyle(color = textColor.copy(alpha = alpha * 0.6f))) { append(word) }
                                }
                            }
                            // 词色之上叠加用户高亮背景（原实现此分支完全不画高亮）
                            overlayParagraphHighlights(para, paraHighlights)
                        }
                    }
                    TappableParagraphText(
                        text = annotatedText,
                        paragraph = para,
                        onWordClick = onWordClick,
                        onSentenceDoubleTap = onSentenceDoubleTap,
                        modifier = Modifier.padding(vertical = 6.dp),
                        style = TextStyle(
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.8).sp,
                        ),
                    )
                } else if (showKnownWordsHighlight && knownWords.isNotEmpty()) {
                    // 生词本高亮模式（Collins 关）
                    val annotatedText = remember(para, alpha, textColor, knownWords, learnedWords, paraHighlights) {
                        buildAnnotatedString {
                            val words = Regex("([a-zA-Z]+)|([^a-zA-Z]+)").findAll(para)
                            words.forEach { match ->
                                val word = match.value
                                if (Regex("^[a-zA-Z]+$").matches(word)) {
                                    val lower = word.lowercase()
                                    val color = when {
                                        lower in knownWords -> Success.copy(alpha = alpha)
                                        lower in learnedWords -> KnownWord.copy(alpha = alpha)
                                        else -> textColor.copy(alpha = alpha)
                                    }
                                    withStyle(SpanStyle(color = color)) { append(word) }
                                } else {
                                    withStyle(SpanStyle(color = textColor.copy(alpha = alpha * 0.6f))) { append(word) }
                                }
                            }
                            // 词色之上叠加用户高亮背景（默认配置就走本分支，
                            // 原实现高亮在这里完全不画）
                            overlayParagraphHighlights(para, paraHighlights)
                        }
                    }
                    TappableParagraphText(
                        text = annotatedText,
                        paragraph = para,
                        onWordClick = onWordClick,
                        onSentenceDoubleTap = onSentenceDoubleTap,
                        modifier = Modifier.padding(vertical = 6.dp),
                        style = TextStyle(
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.8).sp,
                        ),
                    )
                } else {
                    // 普通模式 + 高亮渲染
                    val annotatedText = remember(para, alpha, textColor, paraHighlights) {
                        buildAnnotatedString {
                            var cursor = 0
                            // 按 offset 顺序处理高亮区域；对每条高亮按当前 cursor 收敛：
                            // 重叠高亮不再重复输出重叠段，负值/反向/越界的脏数据
                            // （startOffset > endOffset、endOffset > 段落长）也不会让
                            // substring 抛 IllegalArgumentException
                            val sortedHighlights = paraHighlights.sortedBy { it.startOffset }
                            for (highlight in sortedHighlights) {
                                val start = highlight.startOffset.coerceAtLeast(cursor)
                                val end = highlight.endOffset.coerceIn(start, para.length)
                                if (end <= start) continue
                                // 插入高亮前的文本
                                if (cursor < start) {
                                    withStyle(SpanStyle(color = textColor.copy(alpha = alpha * 0.8f))) {
                                        append(para.substring(cursor, start))
                                    }
                                }
                                // 高亮文本
                                withStyle(SpanStyle(
                                    background = highlight.color.copy(alpha = 0.25f),
                                    color = highlight.color,
                                )) {
                                    append(para.substring(start, end))
                                }
                                cursor = end
                            }
                            // 剩余文本
                            if (cursor < para.length) {
                                withStyle(SpanStyle(color = textColor.copy(alpha = alpha * 0.8f))) {
                                    append(para.substring(cursor))
                                }
                            }
                        }
                    }
                    TappableParagraphText(
                        text = annotatedText,
                        paragraph = para,
                        onWordClick = onWordClick,
                        onSentenceDoubleTap = onSentenceDoubleTap,
                        modifier = Modifier.padding(vertical = 6.dp),
                        style = TextStyle(
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.8).sp,
                        ),
                    )
                }
            }

            // 翻译（透明度可调）
            if (showTranslation && paragraphTranslations.isNotEmpty()) {
                val translation = paragraphTranslations[index]
                if (!translation.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = translation,
                        modifier = Modifier.padding(vertical = 2.dp),
                        style = TextStyle(
                            fontSize = (fontSize - 2).sp,
                            color = Primary.copy(alpha = alpha * translationAlpha),
                            lineHeight = (fontSize * 1.5).sp,
                        ),
                    )
                    // 只有实际有译文才留间距：原实现把 Spacer 放在判空之外，
                    // 未翻译段落也多出一截空白，节奏不齐
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
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
                color = Primary.copy(alpha = 0.1f),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    "强度 $rsvpStrength",
                    color = Primary,
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
                                        .background(Primary.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
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
                colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.1f)),
            ) {
                Text(
                    text = currentTranslation,
                    modifier = Modifier.padding(12.dp),
                    color = Primary.copy(alpha = translationAlpha),
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
                    color = Primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "译文",
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
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
                            color = Primary,
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
                                color = Primary,
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
                    style = TextStyle(
                        fontSize = fontSize.sp,
                        color = textColor.copy(alpha = alpha),
                        lineHeight = (fontSize * 1.8).sp,
                    ),
                )
                Text(
                    text = translation ?: "（无译文）",
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                    style = TextStyle(
                        fontSize = fontSize.sp,
                        color = if (translation != null) {
                            Primary.copy(alpha = alpha * translationAlpha)
                        } else {
                            textColor.copy(alpha = alpha * 0.4f)
                        },
                        lineHeight = (fontSize * 1.8).sp,
                    ),
                )
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
                style = TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * 1.8).sp),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = translation ?: "...",
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 6.dp),
                        style = TextStyle(
                            fontSize = fontSize.sp,
                            color = primaryColor.copy(alpha = alpha * translationAlpha),
                            lineHeight = (fontSize * 1.8).sp,
                        ),
                    )
                    Text(
                        text = para,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 6.dp)
                            // 揭示后或译文还没加载完（没东西可挡）时不模糊
                            .blur(if (revealed || !hasTranslation) 0.dp else 6.dp),
                        style = TextStyle(
                            fontSize = fontSize.sp,
                            color = textColor.copy(alpha = if (revealed) alpha else alpha * 0.4f),
                            lineHeight = (fontSize * 1.8).sp,
                        ),
                    )
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
                tint = Primary,
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
                color = Primary,
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
                                    color = Primary,
                                )) { append("____") }
                            } else {
                                withStyle(SpanStyle(color = textColor)) { append(word.text) }
                            }
                        } else {
                            withStyle(SpanStyle(color = textColor.copy(alpha = 0.7f))) { append(word.text) }
                        }
                    }
                },
                style = TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * 2).sp),
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
) {
    Surface(
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // 进度条：拖动期间只更新本地值，松手才提交。
            // 原实现逐像素调 goToParagraph：每像素都触发 stopAllPlayback +
            // saveProgress，挖空/模糊模式下还会逐像素重生成整段词序列
            var seekValue by remember { mutableStateOf(uiState.currentParagraphIndex.toFloat()) }
            // 索引被程序化推进（播放/上下段/跳转）时同步滑杆位置
            LaunchedEffect(uiState.currentParagraphIndex) {
                seekValue = uiState.currentParagraphIndex.toFloat()
            }
            Slider(
                value = seekValue,
                onValueChange = { seekValue = it },
                onValueChangeFinished = { onSeek(seekValue.toInt()) },
                valueRange = 0f..(uiState.paragraphs.size - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Primary,
                    activeTrackColor = Primary,
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
                Text(
                    text = "${uiState.readingMode.displayName} · " +
                            "${(uiState.currentParagraphIndex + 1)}/${uiState.paragraphs.size}",
                    style = MaterialTheme.typography.labelMedium,
                )
                IconButton(onClick = onNext, enabled = uiState.currentParagraphIndex < uiState.paragraphs.size - 1) {
                    Icon(Icons.Default.NavigateNext, "下一段")
                }
            }
        }
    }
}

// ── 模式选择对话框 ─────────────────────────────
@Composable
fun ModeSelectorDialog(
    currentMode: ReadingMode,
    onSelect: (ReadingMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择阅读模式") },
        text = {
            Column {
                ReadingMode.entries.forEach { mode ->
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
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
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

@Composable
fun ReaderSettingsDialog(
    fontSize: Int,
    rsvpSpeed: Int,
    rsvpStrength: Int,
    translationAlpha: Float,
    showWordLevelColors: Boolean,
    showKnownWordsHighlight: Boolean,
    onFontSizeChange: (Int) -> Unit,
    onSpeedChange: (Int) -> Unit,
    onStrengthChange: (Int) -> Unit,
    onTranslationAlphaChange: (Float) -> Unit,
    onWordLevelColorsToggle: () -> Unit,
    onKnownWordsHighlightToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("阅读设置") },
        text = {
            Column {
                // 这些值来自 DataStore 持久化，历史版本可能写入越界值；
                // Slider 要求 value 在 valueRange 内，列表索引也要收敛，否则弹窗一开就崩
                Text("字体大小: ${fontSize}sp")
                Slider(
                    value = fontSize.toFloat().coerceIn(12f, 32f),
                    onValueChange = { onFontSizeChange(it.toInt()) },
                    valueRange = 12f..32f,
                    steps = 19,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("RSVP 速度: ${rsvpSpeed} 字/分钟")
                Slider(
                    value = rsvpSpeed.toFloat().coerceIn(100f, 800f),
                    onValueChange = { onSpeedChange(it.toInt()) },
                    valueRange = 100f..800f,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("仿生阅读强度: $rsvpStrength（加粗占比 ${RSVP_STRENGTH_LABELS[rsvpStrength.coerceIn(1, 5) - 1]}）")
                Slider(
                    value = rsvpStrength.toFloat().coerceIn(1f, 5f),
                    onValueChange = { onStrengthChange(it.toInt()) },
                    valueRange = 1f..5f,
                    steps = 3,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("翻译透明度: ${(translationAlpha * 100).toInt()}%")
                Slider(
                    value = translationAlpha,
                    onValueChange = onTranslationAlphaChange,
                    valueRange = 0.3f..1f,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Collins 词频色彩", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = showWordLevelColors,
                        onCheckedChange = { onWordLevelColorsToggle() },
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("生词高亮", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = showKnownWordsHighlight,
                        onCheckedChange = { onKnownWordsHighlightToggle() },
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                // Collins 词级颜色图例
                if (showWordLevelColors) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                            WordLevelCore to "核心",
                            WordLevelIntmd to "进阶",
                            WordLevelUpper to "提高",
                            WordLevelAdv to "高阶",
                            WordLevelRare to "学术",
                        ).forEach { (color, label) ->
                            AssistChip(
                                onClick = {},
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = color.copy(alpha = 0.15f),
                                    labelColor = color,
                                ),
                                border = null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

// ── 单词详情对话框 ─────────────────────────────
@Composable
fun WordDetailDialog(
    word: String,
    definition: String?,
    wordLevel: WordLevel = WordLevel.UNKNOWN,
    onAddToVocabulary: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
                Text(
                    text = definition ?: "未找到释义",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "点击下方按钮添加到生词本，方便复习。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (wordLevel != WordLevel.UNKNOWN) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = wordLevel.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAddToVocabulary()
                onDismiss()
            }) {
                Icon(Icons.Default.Add, null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("加入生词本")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

// ── 章节目录导航对话框 ─────────────────────────
@Composable
fun ChapterNavDialog(
    paragraphs: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 打开即定位到当前段：长书原来停在第 0 段，用户得自己翻找
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        listState.scrollToItem(currentIndex)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("目录导航") },
        text = {
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 400.dp),
            ) {
                itemsIndexed(paragraphs) { idx, para ->
                    // 显示段落前60字预览
                    val preview = para.take(60).replace("\n", " ") +
                        if (para.length > 60) "…" else ""
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
                                Icon(Icons.Default.PlayArrow, "当前", tint = Primary)
                            }
                        },
                        modifier = Modifier.clickable {
                            onSelect(idx)
                            onDismiss()
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (idx == currentIndex)
                                Primary.copy(alpha = 0.1f) else Color.Transparent,
                        ),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ── 选句翻译对话框 ──────────────────────────────
@Composable
fun SentenceTranslationDialog(
    sentence: String,
    translation: String?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("句子翻译") },
        text = {
            Column {
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
                        color = Primary,
                    )
                } else {
                    // issue 8.9：null 语义是"词典/模型都没翻出来"，
                    // 与"请求出错"区分开，避免误导用户以为网络故障
                    Text(
                        text = "句子未翻译",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

// ── 段落点击辅助：把点击位置解析为单词 / 句子 ──────────────
private val WordRegex = Regex("[a-zA-Z]+")
private val SentenceEndRegex = Regex("[.!?]")

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
    val matches = SentenceEndRegex.findAll(paragraph).toList()
    val start = matches.lastOrNull { it.range.first < charIndex }?.range?.last?.plus(1) ?: 0
    val end = matches.firstOrNull { charIndex < it.range.first }?.range?.first?.plus(1)
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
) {
    val textLayoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
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
                            val sentence = findSentenceAtOffset(paragraph, offset, layout)
                            if (sentence.isNotBlank()) onSentenceDoubleTap(sentence)
                        }
                    },
                )
            },
        style = style,
        onTextLayout = { textLayoutResult.value = it },
    )
}
