package com.eareyereading.ui.screens.reader

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier.size
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
    val scrollState = rememberScrollState()

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.cleanup() }
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
                    Column {
                        Text(
                            text = uiState.book?.title ?: "加载中...",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Text(
                            text = "${uiState.currentParagraphIndex + 1} / ${uiState.paragraphs.size}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.saveProgress()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
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
                                if (uiState.showTranslation) Icons.Default.Translate else Icons.Default.Translate,
                                "翻译",
                                tint = if (uiState.showTranslation) Primary else LocalContentColor.current,
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.togglePlay() }) {
                        Icon(
                            if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            "播放"
                        )
                    }
                    // 自动全文朗读
                    IconButton(onClick = viewModel::toggleAutoRead) {
                        if (uiState.isAutoReading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = Primary,
                            )
                        } else {
                            Icon(
                                Icons.Default.Headphones,
                                "自动朗读",
                                tint = Primary,
                            )
                        }
                    }
                    IconButton(onClick = viewModel::toggleChapterNav) {
                        Icon(Icons.Default.List, "目录")
                    }
                    IconButton(onClick = viewModel::toggleWordLevelColors) {
                        Icon(
                            Icons.Default.ColorLens,
                            "词频颜色",
                            tint = if (uiState.showWordLevelColors) Primary else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = viewModel::toggleKnownWordsHighlight) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            "生词高亮",
                            tint = if (uiState.showKnownWordsHighlight) Success else LocalContentColor.current,
                        )
                    }
                    // 书签按钮
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
                    IconButton(onClick = { viewModel._uiState.update { it.copy(showModeSelector = true) } }) {
                        Icon(Icons.Default.MenuBook, "阅读模式")
                    }
                    IconButton(onClick = { viewModel._uiState.update { it.copy(showSettings = true) } }) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backgroundColor,
                ),
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
                .padding(horizontal = 20.dp)
                .padding(bottom = if (uiState.selectedWord != null && !uiState.showWordDialog) 72.dp else 0.dp),
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
                    )
                    ReadingMode.CLOZE -> ClozeReadingView(
                        clozeWords = uiState.clozeWords,
                        answer = uiState.hiddenWordAnswer,
                        fontSize = uiState.fontSize,
                        textColor = textColor,
                        showTranslation = uiState.showTranslation,
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
                        onReveal = viewModel::hideWord,
                        onStartDictation = { viewModel.startDictation(uiState.currentParagraphIndex) },
                    )
                    ReadingMode.SPLIT -> SplitReadingView(
                        paragraphs = uiState.paragraphs,
                        translations = uiState.paragraphTranslations,
                        currentIndex = uiState.currentParagraphIndex,
                        fontSize = uiState.fontSize,
                        textColor = textColor,
                        onWordClick = viewModel::selectWord,
                    )
                    ReadingMode.BACK_TRANSLATION -> BackTranslationView(
                        paragraphs = uiState.paragraphs,
                        translations = uiState.paragraphTranslations,
                        currentIndex = uiState.currentParagraphIndex,
                        fontSize = uiState.fontSize,
                        textColor = textColor,
                        primaryColor = Primary,
                        onRevealAll = viewModel::revealAllFuzzy,
                    )
                    ReadingMode.POS_ANALYSIS -> PosAnalysisView(
                        paragraphs = uiState.paragraphs,
                        currentIndex = uiState.currentParagraphIndex,
                        fontSize = uiState.fontSize,
                        onWordClick = viewModel::selectWord,
                    )
                }
            }
        },
        // 词汇栏：点击单词后底部显示释义
        bottomBar = {
            if (uiState.selectedWord != null && !uiState.showWordDialog) {
                VocabularyBar(
                    word = uiState.selectedWord!!,
                    definition = uiState.wordDefinition,
                    wordLevel = uiState.selectedWordLevel,
                    onAddToVocabulary = { viewModel.addToVocabulary(uiState.selectedWord!!, null) },
                    onClose = { viewModel._uiState.update { it.copy(showWordDialog = false, selectedWord = null) } },
                )
            }
        },
    )

    // 模式选择弹窗
    if (uiState.showModeSelector) {
        ModeSelectorDialog(
            currentMode = uiState.readingMode,
            onSelect = viewModel::setReadingMode,
            onDismiss = { viewModel._uiState.update { it.copy(showModeSelector = false) } },
        )
    }

    // 设置弹窗
    if (uiState.showSettings) {
        ReaderSettingsDialog(
            fontSize = uiState.fontSize,
            rsvpSpeed = uiState.rsvpSpeed,
            rsvpStrength = uiState.rsvpStrength,
            rsvpInterval = uiState.rsvpInterval,
            translationAlpha = uiState.translationAlpha,
            showWordLevelColors = uiState.showWordLevelColors,
            showKnownWordsHighlight = uiState.showKnownWordsHighlight,
            onFontSizeChange = viewModel::setFontSize,
            onSpeedChange = viewModel::setRsvpSpeed,
            onStrengthChange = viewModel::setRsvpStrength,
            onIntervalChange = viewModel::setRsvpInterval,
            onTranslationAlphaChange = viewModel::setTranslationAlpha,
            onWordLevelColorsToggle = viewModel::toggleWordLevelColors,
            onKnownWordsHighlightToggle = viewModel::toggleKnownWordsHighlight,
            onDismiss = { viewModel._uiState.update { it.copy(showSettings = false) } },
        )
    }

    // 目录导航弹窗
    if (uiState.showChapterNav) {
        ChapterNavDialog(
            paragraphs = uiState.paragraphs,
            currentIndex = uiState.currentParagraphIndex,
            onSelect = viewModel::goToParagraph,
            onDismiss = { viewModel._uiState.update { it.copy(showChapterNav = false) } },
        )
    }

    // 单词弹窗
    if (uiState.showWordDialog && uiState.selectedWord != null) {
        WordDetailDialog(
            word = uiState.selectedWord!!,
            definition = uiState.wordDefinition,
            wordLevel = uiState.selectedWordLevel,
            onAddToVocabulary = { viewModel.addToVocabulary(uiState.selectedWord!!, null) },
            onDismiss = { viewModel._uiState.update { it.copy(showWordDialog = false, selectedWord = null) } },
        )
    }

    // 选句翻译弹窗
    val selectedSentence by viewModel.selectedSentence.collectAsState()
    val sentenceTranslation by viewModel.sentenceTranslation.collectAsState()
    if (selectedSentence != null) {
        SentenceTranslationDialog(
            sentence = selectedSentence!!,
            translation = sentenceTranslation,
            isLoading = sentenceTranslation == null && selectedSentence != null,
            onDismiss = viewModel::dismissSentenceTranslation,
        )
    }
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
    bookmarkedParagraphs: Set<Int> = emptySet(),
    highlights: Map<Int, List<HighlightData>> = emptyMap(),
    onAddHighlight: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    onRemoveHighlight: (Long) -> Unit = {},
    classifier: CollinsClassifier = remember { CollinsClassifier() },
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        paragraphs.forEachIndexed { index, para ->
            val isCurrent = index == currentIndex
            val isBookmarked = index in bookmarkedParagraphs
            val paraHighlights = highlights[index] ?: emptyList()
            val alpha = when {
                isCurrent && isAutoReading -> 1f
                isCurrent -> 1f
                index < currentIndex -> 0.4f
                else -> 0.7f
            }

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
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        thickness = 1.dp,
                        color = Secondary.copy(alpha = 0.3f),
                    )
                }
            }

            // 朗读中当前段落高亮背景
            if (isCurrent && isAutoReading) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    color = Primary.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("") }
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
                        if (showWordLevelColors) {
                            Text(
                                text = buildAnnotatedString {
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
                                                WordLevel.UNKNOWN -> textColor.copy(alpha = sAlpha * 0.5)
                                            }
                                            withStyle(SpanStyle(color = color.copy(alpha = sAlpha))) { append(word) }
                                        } else {
                                            withStyle(SpanStyle(color = textColor.copy(alpha = sAlpha * 0.6))) { append(word) }
                                        }
                                    }
                                },
                                modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                                style = TextStyle(
                                    fontSize = fontSize.sp,
                                    lineHeight = (fontSize * 1.8).sp,
                                ),
                            )
                        } else {
                            Text(
                                text = sentence,
                                modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                                style = TextStyle(
                                    fontSize = fontSize.sp,
                                    color = textColor.copy(alpha = sAlpha),
                                    lineHeight = (fontSize * 1.8).sp,
                                ),
                            )
                        }
                    }
                }
            } else {
                // Collins 词频色彩（非朗读中）
                if (showWordLevelColors) {
                    Text(
                        text = buildAnnotatedString {
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
                                            WordLevel.UNKNOWN -> textColor.copy(alpha = alpha * 0.5)
                                        }.let { it.copy(alpha = alpha) }
                                    }
                                    withStyle(SpanStyle(color = color)) { append(word) }
                                } else {
                                    withStyle(SpanStyle(color = textColor.copy(alpha = alpha * 0.6))) { append(word) }
                                }
                            }
                        },
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onWordClick(para) },
                                    onDoubleTap = { onSentenceDoubleTap(para) },
                                )
                            },
                        style = TextStyle(
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.8).sp,
                        ),
                    )
                } else if (showKnownWordsHighlight && knownWords.isNotEmpty()) {
                    // 生词本高亮模式（Collins 关）
                    val annotatedText = buildAnnotatedString {
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
                                withStyle(SpanStyle(color = textColor.copy(alpha = alpha * 0.6))) { append(word) }
                            }
                        }
                    }
                    Text(
                        text = annotatedText,
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onWordClick(para) },
                                    onDoubleTap = { onSentenceDoubleTap(para) },
                                )
                            },
                        style = TextStyle(
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.8).sp,
                        ),
                    )
                } else {
                    // 普通模式 + 高亮渲染
                    val annotatedText = buildAnnotatedString {
                        var cursor = 0
                        // 按 offset 顺序合并高亮区域
                        val sortedHighlights = paraHighlights
                            .sortedBy { it.startOffset }
                            .filter { it.startOffset < para.length && it.endOffset <= para.length }
                        for (highlight in sortedHighlights) {
                            // 插入高亮前的文本
                            if (cursor < highlight.startOffset) {
                                withStyle(SpanStyle(color = textColor.copy(alpha = alpha * 0.8f))) {
                                    append(para.substring(cursor, highlight.startOffset))
                                }
                            }
                            // 高亮文本
                            withStyle(SpanStyle(
                                background = highlight.color.copy(alpha = 0.25f),
                                color = highlight.color,
                            )) {
                                append(para.substring(highlight.startOffset, highlight.endOffset))
                            }
                            cursor = highlight.endOffset
                        }
                        // 剩余文本
                        if (cursor < para.length) {
                            withStyle(SpanStyle(color = textColor.copy(alpha = alpha * 0.8f))) {
                                append(para.substring(cursor))
                            }
                        }
                    }
                    Text(
                        text = annotatedText,
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onWordClick(para) },
                                    onDoubleTap = { onSentenceDoubleTap(para) },
                                )
                            },
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
                }
                Spacer(modifier = Modifier.height(12.dp))
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
    val words = remember(paragraph) { paragraph.split(Regex("\\s+")).filter { it.isNotBlank() } }
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
            // 强度指示
            AssistChip(
                onClick = {},
                label = { Text("强度 $rsvpStrength") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Primary.copy(alpha = 0.1f),
                    labelColor = Primary,
                ),
                border = null,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Text("点击播放按钮开始", color = textColor.copy(alpha = 0.5f))
        }
        Spacer(modifier = Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = { if (words.isNotEmpty()) currentWordIndex.toFloat() / words.size else 0f },
            modifier = Modifier.width(200.dp),
        )
        Text(
            text = "${currentWordIndex + 1} / ${words.size}",
            color = textColor.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ── 快速阅读视图 ───────────────────────────────
@Composable
fun SpeedReadingView(
    paragraph: String,
    fontSize: Int,
    textColor: Color,
    isPlaying: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (isPlaying) "●" else paragraph.take(50),
            color = textColor,
            fontSize = fontSize.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ── 挖空练习视图 ────────────────────────────────
@Composable
fun ClozeReadingView(
    clozeWords: List<ClozeWord>,
    answer: String?,
    fontSize: Int,
    textColor: Color,
    showTranslation: Boolean,
    currentTranslation: String?,
    onReveal: () -> Unit,
    onWordClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        clozeWords.forEach { clozeWord ->
            if (clozeWord.isWord) {
                if (clozeWord.isHidden) {
                    if (answer != null && clozeWord.text == answer) {
                        Text(
                            text = "__${clozeWord.text}__",
                            color = Secondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = fontSize.sp,
                            modifier = Modifier.clickable { onWordClick(clozeWord.text) },
                        )
                    } else {
                        Text(
                            text = "____",
                            color = textColor.copy(alpha = 0.5f),
                            fontSize = fontSize.sp,
                        )
                    }
                } else {
                    Text(
                        text = clozeWord.text,
                        color = textColor,
                        fontSize = fontSize.sp,
                        modifier = Modifier.clickable { onWordClick(clozeWord.text) },
                    )
                }
            } else {
                Text(
                    text = clozeWord.text,
                    color = textColor.copy(alpha = 0.7f),
                    fontSize = fontSize.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        FilledTonalButton(onClick = onReveal, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Default.Visibility, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("显示答案")
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
                    color = Primary,
                    fontSize = (fontSize - 2).sp,
                )
            }
        }
    }
}

// ── 模糊阅读视图 ────────────────────────────────
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
            .padding(vertical = 16.dp),
    ) {
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

// ── 分栏对照阅读视图 ──────────────────────────────
@Composable
fun SplitReadingView(
    paragraphs: List<String>,
    translations: Map<Int, String>,
    currentIndex: Int,
    fontSize: Int,
    textColor: Color,
    onWordClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 左栏：原文
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
        ) {
            Text(
                "原文",
                style = MaterialTheme.typography.labelSmall,
                color = Primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            paragraphs.forEachIndexed { index, para ->
                val alpha = if (index == currentIndex) 1f else 0.5f
                Text(
                    text = para,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .clickable { onWordClick(para) },
                    style = TextStyle(
                        fontSize = fontSize.sp,
                        color = textColor.copy(alpha = alpha),
                        lineHeight = (fontSize * 1.8).sp,
                    ),
                )
                if (index < paragraphs.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = textColor.copy(alpha = 0.1f),
                    )
                }
            }
        }

        // 中间分隔线
        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            thickness = 1.dp,
            color = textColor.copy(alpha = 0.2f),
        )

        // 右栏：译文
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
        ) {
            Text(
                "译文",
                style = MaterialTheme.typography.labelSmall,
                color = Primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            paragraphs.forEachIndexed { index, para ->
                val translation = translations[index]
                val alpha = if (index == currentIndex) 1f else 0.5f
                Text(
                    text = translation ?: "（无译文）",
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = TextStyle(
                        fontSize = fontSize.sp,
                        color = if (translation != null) Primary.copy(alpha = alpha) else textColor.copy(alpha = alpha * 0.4f),
                        lineHeight = (fontSize * 1.8).sp,
                    ),
                )
                if (index < paragraphs.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = textColor.copy(alpha = 0.1f),
                    )
                }
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
    onWordClick: (String) -> Unit,
    posTagger: PosTagger = remember { PosTagger() },
) {
    // POS 颜色映射
    fun posColor(tag: PosTag): Color = when (tag) {
        PosTag.NOUN -> Color(0xFF5B7FFF)      // 蓝 - 名词
        PosTag.VERB -> Color(0xFFE91E63)     // 粉 - 动词
        PosTag.ADJECTIVE -> Color(0xFFFF9800) // 橙 - 形容词
        PosTag.ADVERB -> Color(0xFF9C27B0)   // 紫 - 副词
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        paragraphs.forEachIndexed { index, para ->
            val isCurrent = index == currentIndex
            val alpha = if (isCurrent) 1f else 0.5f

            // 词性着色文本
            Text(
                text = buildAnnotatedString {
                    val allMatches = Regex("([a-zA-Z]+)|([^a-zA-Z]+)").findAll(para).toList()
                    allMatches.forEach { match ->
                        val token = match.value
                        if (Regex("^[a-zA-Z]+$").matches(token)) {
                            val word = token.lowercase()
                            val tag = wordPosMap[word] ?: classifyBySuffix(word)
                            val color = posColor(tag).copy(alpha = alpha)
                            withStyle(SpanStyle(color = color)) { append(token) }
                        } else {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha * 0.5f))) {
                                append(token)
                            }
                        }
                    }
                },
                modifier = Modifier
                    .padding(vertical = 6.dp, horizontal = 4.dp)
                    .clickable { onWordClick(para) },
                style = TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * 1.8).sp),
            )

            if (index < paragraphs.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
            }
        }

        // 底部图例
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PosLegendItem("蓝色", Color(0xFF5B7FFF), "名词")
            PosLegendItem("粉色", Color(0xFFE91E63), "动词")
            PosLegendItem("橙色", Color(0xFFFF9800), "形容词")
            PosLegendItem("紫色", Color(0xFF9C27B0), "副词")
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
    onRevealAll: () -> Unit,
) {
    var hasTranslation by remember { mutableStateOf(false) }

    LaunchedEffect(translations) {
        hasTranslation = translations.isNotEmpty()
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
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 左栏：中文译文
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "中文译文",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                )
                Spacer(modifier = Modifier.height(8.dp))
                paragraphs.forEachIndexed { index, para ->
                    val translation = translations[index]
                    val alpha = if (index == currentIndex) 1f else 0.5f
                    Text(
                        text = translation ?: "...",
                        modifier = Modifier.padding(vertical = 6.dp),
                        style = TextStyle(
                            fontSize = fontSize.sp,
                            color = primaryColor.copy(alpha = alpha),
                            lineHeight = (fontSize * 1.8).sp,
                        ),
                    )
                    if (index < paragraphs.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = textColor.copy(alpha = 0.1f),
                        )
                    }
                }
            }

            // 右栏：模糊英文原文
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        onClick = onRevealAll,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.Visibility, null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("查看原文", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                paragraphs.forEachIndexed { index, para ->
                    val alpha = if (index == currentIndex) 1f else 0.5f
                    Text(
                        text = para,
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .blur(if (hasTranslation) 6.dp else 0.dp),
                        style = TextStyle(
                            fontSize = fontSize.sp,
                            color = textColor.copy(alpha = alpha * 0.4f),
                            lineHeight = (fontSize * 1.8).sp,
                        ),
                    )
                    if (index < paragraphs.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = textColor.copy(alpha = 0.1f),
                        )
                    }
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
    onReveal: (String) -> Unit,
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
                                // 划线填空
                                withStyle(SpanStyle(
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.Bold,
                                    color = Primary,
                                )) { append(word.text) }
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

            // 提示答案按钮
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
                        hiddenWords.firstOrNull()?.let { onReveal(it) }
                        inputWord = ""
                    }) {
                        Text("查看答案")
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
            // 进度条
            Slider(
                value = uiState.currentParagraphIndex.toFloat(),
                onValueChange = { onSeek(it.toInt()) },
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
                    Icon(Icons.AutoMirrored.Filled.NavigateBefore, "上一段")
                }
                Text(
                    text = "${uiState.readingMode.displayName} · " +
                            "${(uiState.currentParagraphIndex + 1)}/${uiState.paragraphs.size}",
                    style = MaterialTheme.typography.labelMedium,
                )
                IconButton(onClick = onNext, enabled = uiState.currentParagraphIndex < uiState.paragraphs.size - 1) {
                    Icon(Icons.AutoMirrored.Filled.NavigateNext, "下一段")
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
@Composable
fun ReaderSettingsDialog(
    fontSize: Int,
    rsvpSpeed: Int,
    rsvpStrength: Int,
    rsvpInterval: Int,
    translationAlpha: Float,
    showWordLevelColors: Boolean,
    showKnownWordsHighlight: Boolean,
    onFontSizeChange: (Int) -> Unit,
    onSpeedChange: (Int) -> Unit,
    onStrengthChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
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
                Text("字体大小: ${fontSize}sp")
                Slider(
                    value = fontSize.toFloat(),
                    onValueChange = { onFontSizeChange(it.toInt()) },
                    valueRange = 12f..32f,
                    steps = 19,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("RSVP 速度: ${rsvpSpeed} 字/分钟")
                Slider(
                    value = rsvpSpeed.toFloat(),
                    onValueChange = { onSpeedChange(it.toInt()) },
                    valueRange = 100f..800f,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("仿生阅读强度: $rsvpStrength（加粗占比 ${listOf("30%","40%","50%","60%","70%")[rsvpStrength - 1]}）")
                Slider(
                    value = rsvpStrength.toFloat(),
                    onValueChange = { onStrengthChange(it.toInt()) },
                    valueRange = 1f..5f,
                    steps = 3,
                )
                Text("加粗间隔: ${listOf("小间隔", "中间隔", "大间隔")[rsvpInterval - 1]}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = rsvpInterval.toFloat(),
                    onValueChange = { onIntervalChange(it.toInt()) },
                    valueRange = 1f..3f,
                    steps = 1,
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("目录导航") },
        text = {
            LazyColumn(
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
                    Text(
                        text = "翻译失败，请重试",
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

// ── 词汇栏（底部常驻提示）──────────────────────────
@Composable
fun VocabularyBar(
    word: String,
    definition: String?,
    wordLevel: WordLevel,
    onAddToVocabulary: () -> Unit,
    onClose: () -> Unit,
) {
    val levelColor = when (wordLevel) {
        WordLevel.CORE -> WordLevelCore
        WordLevel.INTERMEDIATE -> WordLevelIntmd
        WordLevel.UPPER_INTERMEDIATE -> WordLevelUpper
        WordLevel.ADVANCED -> WordLevelAdv
        WordLevel.RARE -> WordLevelRare
        WordLevel.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 单词 + 级别标签
            AssistChip(
                onClick = {},
                label = { Text(word, fontWeight = FontWeight.Bold) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = levelColor.copy(alpha = 0.15f),
                    labelColor = levelColor,
                ),
                border = null,
            )
            Spacer(modifier = Modifier.width(8.dp))

            // 释义
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = definition ?: "...",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 加入生词本
            IconButton(onClick = onAddToVocabulary) {
                Icon(
                    Icons.Default.StarBorder,
                    "加入生词本",
                    tint = Secondary,
                )
            }

            // 关闭
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
