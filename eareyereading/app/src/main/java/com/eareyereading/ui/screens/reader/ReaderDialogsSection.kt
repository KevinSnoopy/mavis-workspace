package com.eareyereading.ui.screens.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * 阅读页弹窗编排：模式选择 / 设置 / 章节目录 / 单词详情 / 选句翻译 /
 * TTS 引擎引导。按 uiState 标志位逐个条件渲染。
 *
 * 从 ReaderScreen.kt 抽出（SRP）：ReaderScreen 不再内联弹窗构造，
 * 只把 uiState / viewModel / ttsPrompt 透传给本编排器。
 *
 * @param ttsPrompt TTS 引导弹窗状态：null 表示不显示。由 ReaderScreen
 *   收集 viewModel.ttsInstallPrompt 后传入；下载内置模型期间保持非 null
 *   以便页内展示进度，其余动作后由调用方置 null。
 * @param onTtsPromptDismiss 用户关闭 TTS 引导弹窗时回调（调用方需置
 *   ttsPrompt = null 并通知 VM）。
 * @param onTtsPromptAction 用户点击弹窗内动作（下载/启用/取消等）时回调；
 *   返回 true 表示该动作后应关闭弹窗，false 表示保持打开（下载内置模型
 *   时页内展示进度，不关弹窗）。
 */
@Composable
internal fun ReaderDialogsSection(
    uiState: ReaderUiState,
    viewModel: ReaderViewModel,
    ttsPrompt: TtsInstallPrompt?,
    onTtsPromptAction: (TtsInstallAction) -> Unit,
    onTtsPromptDismiss: () -> Unit,
) {
    // 模式选择弹窗
    if (uiState.showModeSelector) {
        ModeSelectorDialog(
            currentMode = uiState.readingMode,
            onSelect = viewModel::setReadingMode,
            onDismiss = viewModel::dismissModeSelector,
        )
    }

    // 当前模式说明：看完可直接跳去换模式，不用先关抽屉再找入口
    if (uiState.showModeHelp) {
        ModeHelpSheet(
            mode = uiState.readingMode,
            onChangeMode = viewModel::showModeSelector,
            onDismiss = viewModel::toggleModeHelp,
        )
    }

    // 高亮抽屉：长按选词 → 选色落库，或移除已有高亮
    uiState.highlightDraft?.let { draft ->
        HighlightSheet(
            draft = draft,
            onPickColor = viewModel::confirmHighlight,
            onRemove = viewModel::removeDraftedHighlight,
            onDismiss = viewModel::dismissHighlightSheet,
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
            toc = uiState.toc,
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
            onAction = onTtsPromptAction,
            onDismiss = onTtsPromptDismiss,
        )
    }
}
