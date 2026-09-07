package com.eareyereading.ui.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eareyereading.domain.model.ReadingMode
import com.eareyereading.ui.components.shimmer

/**
 * 阅读页正文层调度：加载骨架屏 + 各阅读模式视图（NORMAL/RSVP/SPEED/CLOZE/
 * FUZZY/DICTATION/SPLIT/BACK_TRANSLATION/POS_ANALYSIS）的 when 分发。
 *
 * 从 ReaderScreen.kt 抽出（SRP）：ReaderScreen 不再关心阅读模式视图的构造
 * 细节，只把 uiState / viewModel 回调透传给本调度器。
 */
@Composable
internal fun ReaderContentDispatcher(
    uiState: ReaderUiState,
    textColor: Color,
    accentColor: Color,
    viewModel: ReaderViewModel,
    onToggleChrome: () -> Unit,
) {
    if (uiState.isLoading) {
        ReaderLoadingSkeleton()
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
                    onCenterTap = onToggleChrome,
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

/**
 * 骨架屏：按正文排版预演段落形状（Spotify 式微光），
 * 替代居中转圈，感知加载更快。
 */
@Composable
internal fun ReaderLoadingSkeleton() {
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
}
