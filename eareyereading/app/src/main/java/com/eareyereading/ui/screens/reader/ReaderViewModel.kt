package com.eareyereading.ui.screens.reader

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eareyereading.data.local.dao.BookmarkDao
import com.eareyereading.data.local.dao.HighlightDao
import com.eareyereading.data.local.entity.BookmarkEntity
import com.eareyereading.data.local.entity.HighlightEntity
import com.eareyereading.domain.model.*
import com.eareyereading.domain.repository.*
import com.eareyereading.ui.theme.*
import com.eareyereading.util.*
import com.eareyereading.util.CollinsClassifier.WordLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReaderUiState(
    val book: Book? = null,
    val paragraphs: List<String> = emptyList(),
    val currentParagraphIndex: Int = 0,
    val currentWordIndex: Int = 0,
    val readingMode: ReadingMode = ReadingMode.NORMAL,
    val rsvpSpeed: Int = 300,
    val rsvpStrength: Int = 3,    // 1-5，影响加粗字母占比
    val rsvpInterval: Int = 1,    // 1-3，影响加粗词间隔
    val fontSize: Int = 18,
    val theme: ReadingTheme = ReadingTheme.LIGHT,
    val isPlaying: Boolean = false,
    val isTtsPlaying: Boolean = false,
    val ttsInitialized: Boolean = false,
    // 自动朗读（句子级同步）
    val isAutoReading: Boolean = false,
    val autoReadingParaIndex: Int = 0,
    val currentSentences: List<String> = emptyList(),
    val currentSentenceIndex: Int = 0,
    // Collins 词频
    val wordFrequencies: List<WordFrequency> = emptyList(),
    // 生词本词汇（用于阅读时高亮）
    val knownWords: Set<String> = emptySet(),
    val learnedWords: Set<String> = emptySet(),
    // 挖空
    val clozeWords: List<ClozeWord> = emptyList(),
    val hiddenWordAnswer: String? = null,
    // 模糊
    val fuzzyWords: List<FuzzyWord> = emptyList(),
    // 生词提示
    val selectedWord: String? = null,
    val wordDefinition: String? = null,
    val selectedWordLevel: WordLevel = WordLevel.UNKNOWN,
    val showWordDialog: Boolean = false,
    // 全文翻译
    val showTranslation: Boolean = false,
    val paragraphTranslations: Map<Int, String> = emptyMap(),
    val isTranslating: Boolean = false,
    val translationAlpha: Float = 0.85f,
    // Collins 词频色彩
    val showWordLevelColors: Boolean = false,
    // 生词本高亮
    val showKnownWordsHighlight: Boolean = true,
    // 导航
    val showModeSelector: Boolean = false,
    val showSettings: Boolean = false,
    val showChapterNav: Boolean = false,
    // 阅读统计
    val readingStartTime: Long = 0L,
    val totalReadChars: Long = 0L,
    // 书签
    val bookmarkedParagraphs: Set<Int> = emptySet(),
    // 高亮
    val highlights: Map<Int, List<HighlightData>> = emptyMap(),
    // 加载
    val isLoading: Boolean = true,
)

// 高亮数据（用于渲染）
data class HighlightData(
    val id: Long,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val color: Color,
)

private data class ReadingSettings(
    val speed: Int,
    val fontSize: Int,
    val theme: ReadingTheme,
    val alpha: Float,
    val strength: Int = 3,
    val interval: Int = 1,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val readingRepository: ReadingRepository,
    private val settingsRepository: SettingsRepository,
    private val wordAnalyzer: WordAnalyzer,
    private val ttsHelper: TtsHelper,
    private val translationHelper: TranslationHelper,
    private val epubParser: EpubParser,
    private val collinsClassifier: CollinsClassifier,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var rsvpJob: Job? = null
    private var speedJob: Job? = null
    private var autoReadJob: Job? = null
    private var currentBookId: Long? = null
    private var readingStartTime: Long = 0L

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.getRsvpSpeed(),
                settingsRepository.getRsvpStrength(),
                settingsRepository.getRsvpInterval(),
                settingsRepository.getFontSize(),
                settingsRepository.getTheme(),
                settingsRepository.getTranslationAlpha(),
            ) { speed, strength, interval, fontSize, theme, alpha ->
                ReadingSettings(speed, fontSize, theme, alpha, strength, interval)
            }.collect { s ->
                _uiState.update {
                    it.copy(
                        rsvpSpeed = s.speed,
                        rsvpStrength = s.strength,
                        rsvpInterval = s.interval,
                        fontSize = s.fontSize,
                        theme = s.theme,
                        translationAlpha = s.alpha,
                    )
                }
            }
        }
    }

    fun loadBook(bookId: Long) {
        currentBookId = bookId
        readingStartTime = System.currentTimeMillis()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, readingStartTime = readingStartTime) }

            // 加载生词本（用于阅读高亮）
            vocabularyRepository.getAllVocabulary().collect { vocabList ->
                val known = vocabList.filter { it.isLearned }.map { it.word.lowercase() }.toSet()
                val allWords = vocabList.map { it.word.lowercase() }.toSet()
                _uiState.update { it.copy(knownWords = known, learnedWords = allWords) }
            }
        }

        viewModelScope.launch {
            bookRepository.getBookById(bookId).collect { book ->
                val paragraphs = if (book.content.isNotBlank()) {
                    book.content.split("\n\n").filter { it.isNotBlank() }
                } else {
                    epubParser.parseBook(book.filePath)
                }
                val state = readingRepository.getState(bookId)
                val freq = calculateWordFrequencies(paragraphs)
                val totalChars = paragraphs.joinToString(" ").length

                _uiState.update {
                    it.copy(
                        book = book,
                        paragraphs = paragraphs,
                        currentParagraphIndex = state?.currentParagraph ?: 0,
                        currentWordIndex = state?.currentPosition ?: 0,
                        readingMode = state?.readingMode ?: ReadingMode.NORMAL,
                        rsvpSpeed = state?.rsvpSpeed ?: it.rsvpSpeed,
                        wordFrequencies = freq,
                        totalReadChars = totalChars,
                        isLoading = false,
                    )
                }

                // 初始化 TTS
                if (!_uiState.value.ttsInitialized) {
                    val ok = ttsHelper.initialize(book.language)
                    _uiState.update { it.copy(ttsInitialized = ok) }
                }

                // 加载书签
                launch {
                    bookmarkDao.getBookmarksForBook(bookId).collect { bookmarks ->
                        _uiState.update {
                            it.copy(bookmarkedParagraphs = bookmarks.map { b -> b.paragraphIndex }.toSet())
                        }
                    }
                }

                // 加载高亮
                launch {
                    highlightDao.getHighlightsForBook(bookId).collect { highlights ->
                        val grouped = highlights.groupBy { it.paragraphIndex }.mapValues { (_, list) ->
                            list.map { h ->
                                HighlightData(
                                    id = h.id,
                                    startOffset = h.startOffset,
                                    endOffset = h.endOffset,
                                    text = h.text,
                                    color = parseHighlightColor(h.color),
                                )
                            }
                        }
                        _uiState.update { it.copy(highlights = grouped) }
                    }
                }
            }
        }
    }

    private fun parseHighlightColor(hex: String): Color {
        return try {
            Color(android.graphics.Color.parseColor(hex))
        } catch (_: Exception) {
            Highlight
        }
    }

    private suspend fun calculateWordFrequencies(paragraphs: List<String>): List<WordFrequency> {
        val text = paragraphs.joinToString(" ")
        val freqMap = wordAnalyzer.calculateWordFrequencies(text)
        val total = freqMap.values.sum().toFloat()
        return freqMap.entries
            .sortedByDescending { it.value }
            .take(100)
            .map { WordFrequency(word = it.key, count = it.value, frequency = it.value / total) }
    }

    // ── 自动全文朗读 ─────────────────────────────
    fun toggleAutoRead() {
        if (_uiState.value.isAutoReading) {
            stopAutoRead()
        } else {
            startAutoRead()
        }
    }

    private fun startAutoRead() {
        val paragraphs = _uiState.value.paragraphs
        if (paragraphs.isEmpty()) return

        // 初始化 TTS
        if (!_uiState.value.ttsInitialized) {
            viewModelScope.launch {
                val ok = ttsHelper.initialize(_uiState.value.book?.language ?: "en")
                _uiState.update { it.copy(ttsInitialized = ok) }
                if (ok) doStartAutoRead(paragraphs)
            }
        } else {
            doStartAutoRead(paragraphs)
        }
    }

    private fun doStartAutoRead(paragraphs: List<String>) {
        _uiState.update { it.copy(isAutoReading = true, autoReadingParaIndex = 0, currentSentenceIndex = 0) }

        autoReadJob = viewModelScope.launch {
            val startParaIdx = _uiState.value.currentParagraphIndex

            for (paraIdx in startParaIdx until paragraphs.size) {
                if (!_uiState.value.isAutoReading) break

                val para = paragraphs[paraIdx]
                if (para.isBlank()) {
                    _uiState.update { it.copy(autoReadingParaIndex = paraIdx, currentParagraphIndex = paraIdx) }
                    continue
                }

                _uiState.update { it.copy(autoReadingParaIndex = paraIdx, currentParagraphIndex = paraIdx) }

                // 按句子分割（简单处理：按 . ! ? 分割）
                val sentences = para.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
                _uiState.update { it.copy(currentSentences = sentences) }

                suspendCancellableCoroutine { cont ->
                    var cancelled = false

                    ttsHelper.speakSentences(
                        sentences = sentences,
                        onSentenceDone = { sentenceIdx ->
                            _uiState.update { it.copy(currentSentenceIndex = sentenceIdx) }
                        },
                        onAllDone = {
                            if (!cancelled) cont.resume(true)
                        },
                    )

                    // 超时保护（每段最长 60 秒）
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(60_000)
                        if (!cancelled) {
                            cancelled = true
                            cont.resume(false)
                        }
                    }
                }

                // 段落间停顿
                kotlinx.coroutines.delay(600)
            }

            _uiState.update { it.copy(isAutoReading = false, currentSentences = emptyList()) }
        }
    }

    fun stopAutoRead() {
        autoReadJob?.cancel()
        ttsHelper.stop()
        _uiState.update { it.copy(isAutoReading = false, currentSentences = emptyList(), currentSentenceIndex = 0) }
    }

    fun setReadingMode(mode: ReadingMode) {
        rsvpJob?.cancel()
        speedJob?.cancel()

        if (mode == ReadingMode.CLOZE) {
            generateCloze()
        } else if (mode == ReadingMode.FUZZY) {
            generateFuzzy()
        }

        viewModelScope.launch {
            _uiState.update { it.copy(readingMode = mode, showModeSelector = false) }
            currentBookId?.let { readingRepository.updateMode(it, mode) }
        }
    }

    fun generateCloze() {
        val paragraphs = _uiState.value.paragraphs
        val currentIdx = _uiState.value.currentParagraphIndex
        if (currentIdx < paragraphs.size) {
            val text = paragraphs[currentIdx]
            val clozeWords = wordAnalyzer.generateClozeText(text, ratio = 0.15f)
            _uiState.update { it.copy(clozeWords = clozeWords, hiddenWordAnswer = null) }
        }
    }

    fun generateFuzzy() {
        val paragraphs = _uiState.value.paragraphs
        val currentIdx = _uiState.value.currentParagraphIndex
        if (currentIdx < paragraphs.size) {
            val text = paragraphs[currentIdx]
            val fuzzyWords = wordAnalyzer.generateFuzzyText(text, visibleRatio = 0.3f)
            _uiState.update { it.copy(fuzzyWords = fuzzyWords) }
        }
    }

    fun togglePlay() {
        when (_uiState.value.readingMode) {
            ReadingMode.RSVP -> toggleRsvp()
            ReadingMode.SPEED -> toggleSpeed()
            ReadingMode.NORMAL -> toggleTts()
            else -> toggleTts()
        }
    }

    fun toggleRsvp() {
        if (_uiState.value.isPlaying) {
            rsvpJob?.cancel()
            _uiState.update { it.copy(isPlaying = false) }
        } else {
            _uiState.update { it.copy(isPlaying = true) }
            rsvpJob = viewModelScope.launch {
                val words = getCurrentParagraphWords()
                val interval = (60_000L / _uiState.value.rsvpSpeed)
                for (i in _uiState.value.currentWordIndex until words.size) {
                    if (!_uiState.value.isPlaying) break
                    _uiState.update { it.copy(currentWordIndex = i) }
                    val word = words.getOrNull(i) ?: break
                    ttsHelper.speak(word)
                    delay(interval)
                }
                _uiState.update { it.copy(isPlaying = false) }
            }
        }
    }

    fun toggleSpeed() {
        if (_uiState.value.isPlaying) {
            speedJob?.cancel()
            ttsHelper.stop()
            _uiState.update { it.copy(isPlaying = false) }
        } else {
            _uiState.update { it.copy(isPlaying = true) }
            speedJob = viewModelScope.launch {
                val paragraphs = _uiState.value.paragraphs
                for (i in _uiState.value.currentParagraphIndex until paragraphs.size) {
                    if (!_uiState.value.isPlaying) break
                    _uiState.update { it.copy(currentParagraphIndex = i) }
                    ttsHelper.speak(paragraphs[i])
                    // 每句停留时间
                    delay((paragraphs[i].length * 60L / 130).coerceAtLeast(1500L))
                }
                _uiState.update { it.copy(isPlaying = false) }
            }
        }
    }

    fun toggleTts() {
        // 停止自动朗读（如果正在运行）
        if (_uiState.value.isAutoReading) {
            stopAutoRead()
            return
        }

        if (_uiState.value.isTtsPlaying) {
            ttsHelper.pause()
            _uiState.update { it.copy(isTtsPlaying = false) }
        } else {
            _uiState.update { it.copy(isTtsPlaying = true) }
            val para = _uiState.value.paragraphs.getOrNull(_uiState.value.currentParagraphIndex) ?: return
            ttsHelper.speak(para) {
                viewModelScope.launch {
                    _uiState.update { it.copy(isTtsPlaying = false) }
                }
            }
        }
    }

    fun nextParagraph() {
        val paragraphs = _uiState.value.paragraphs
        val nextIdx = (_uiState.value.currentParagraphIndex + 1).coerceAtMost(paragraphs.size - 1)
        _uiState.update { it.copy(currentParagraphIndex = nextIdx, currentWordIndex = 0) }
        saveProgress()
        if (_uiState.value.readingMode == ReadingMode.CLOZE) generateCloze()
        if (_uiState.value.readingMode == ReadingMode.FUZZY) generateFuzzy()
    }

    fun prevParagraph() {
        val prevIdx = (_uiState.value.currentParagraphIndex - 1).coerceAtLeast(0)
        _uiState.update { it.copy(currentParagraphIndex = prevIdx, currentWordIndex = 0) }
        saveProgress()
        if (_uiState.value.readingMode == ReadingMode.CLOZE) generateCloze()
        if (_uiState.value.readingMode == ReadingMode.FUZZY) generateFuzzy()
    }

    fun goToParagraph(index: Int) {
        val paragraphs = _uiState.value.paragraphs
        val idx = index.coerceIn(0, paragraphs.size - 1)
        _uiState.update { it.copy(currentParagraphIndex = idx, currentWordIndex = 0) }
        saveProgress()
        if (_uiState.value.readingMode == ReadingMode.CLOZE) generateCloze()
        if (_uiState.value.readingMode == ReadingMode.FUZZY) generateFuzzy()
    }

    fun setFontSize(size: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(fontSize = size.coerceIn(12, 32)) }
            settingsRepository.setFontSize(size)
        }
    }

    fun setRsvpSpeed(speed: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(rsvpSpeed = speed.coerceIn(100, 800)) }
            settingsRepository.setRsvpSpeed(speed)
            currentBookId?.let { readingRepository.updateRsvpSpeed(it, speed) }
        }
    }

    fun setRsvpStrength(strength: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(rsvpStrength = strength.coerceIn(1, 5)) }
            settingsRepository.setRsvpStrength(strength)
        }
    }

    fun setRsvpInterval(interval: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(rsvpInterval = interval.coerceIn(1, 3)) }
            settingsRepository.setRsvpInterval(interval)
        }
    }

    fun selectWord(word: String) {
        val clean = word.trim().replace(Regex("[^a-zA-Z]"), "")
        if (clean.isBlank()) return

        val level = collinsClassifier.classify(clean)
        viewModelScope.launch {
            // 检查是否已收录
            val existing = vocabularyRepository.getWord(clean)
            // 如果没有释义，用 ML Kit 翻译
            val definition = existing?.definition
                ?: translationHelper.translateWord(clean)
                ?: "未找到释义"
            _uiState.update {
                it.copy(
                    selectedWord = clean,
                    wordDefinition = definition,
                    selectedWordLevel = level,
                    showWordDialog = true,
                )
            }
        }
    }

    fun addToVocabulary(word: String, context: String?) {
        viewModelScope.launch {
            val existing = vocabularyRepository.getWord(word)
            if (existing == null) {
                val vocab = Vocabulary(
                    word = word,
                    bookId = currentBookId,
                    bookTitle = _uiState.value.book?.title,
                    context = context,
                    dateAdded = System.currentTimeMillis(),
                )
                vocabularyRepository.addWord(vocab)
                _uiState.update { it.copy(showWordDialog = false, selectedWord = null) }
            }
        }
    }

    fun hideWord() {
        val hidden = _uiState.value.clozeWords.find { it.isHidden }
        _uiState.update { it.copy(hiddenWordAnswer = hidden?.text) }
    }

    fun toggleTranslation() {
        val show = !_uiState.value.showTranslation
        _uiState.update { it.copy(showTranslation = show) }

        // 如果是打开翻译，且还没翻译过，则触发翻译
        if (show && _uiState.value.paragraphTranslations.isEmpty() && !_uiState.value.isTranslating) {
            translateAllParagraphs()
        }
    }

    // 揭示所有模糊文本（回译模式）
    fun revealAllFuzzy() {
        // 切换到普通模式显示原文
        setReadingMode(ReadingMode.NORMAL)
    }

    private fun translateAllParagraphs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTranslating = true) }
            try {
                val paragraphs = _uiState.value.paragraphs
                val translations = translationHelper.translateParagraphs(paragraphs)
                _uiState.update { it.copy(
                    paragraphTranslations = translations,
                    isTranslating = false,
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isTranslating = false) }
            }
        }
    }

    fun saveProgress() {
        viewModelScope.launch {
            val bookId = currentBookId ?: return@launch
            val state = _uiState.value
            val totalChars = state.paragraphs.joinToString("\n\n").length
            val progress = if (totalChars > 0) {
                state.currentParagraphIndex.toFloat() / state.paragraphs.size.coerceAtLeast(1)
            } else 0f
            bookRepository.updateProgress(bookId, progress, state.currentParagraphIndex)

            readingRepository.saveState(
                ReadingState(
                    bookId = bookId,
                    currentPosition = state.currentWordIndex,
                    currentParagraph = state.currentParagraphIndex,
                    totalCharacters = totalChars,
                    totalParagraphs = state.paragraphs.size,
                    readingMode = state.readingMode,
                    rsvpSpeed = state.rsvpSpeed,
                    fontSize = state.fontSize,
                    theme = state.theme,
                )
            )
        }
    }

    private fun getCurrentParagraphWords(): List<String> {
        val para = _uiState.value.paragraphs.getOrNull(_uiState.value.currentParagraphIndex) ?: return emptyList()
        return wordAnalyzer.extractWords(para)
    }

    fun cleanup() {
        rsvpJob?.cancel()
        speedJob?.cancel()
        ttsHelper.stop()
        saveProgress()
    }

    fun setTranslationAlpha(alpha: Float) {
        viewModelScope.launch {
            _uiState.update { it.copy(translationAlpha = alpha.coerceIn(0.3f, 1f)) }
            settingsRepository.setTranslationAlpha(alpha.coerceIn(0.3f, 1f))
        }
    }

    fun toggleWordLevelColors() {
        _uiState.update { it.copy(showWordLevelColors = !it.showWordLevelColors) }
    }

    fun toggleKnownWordsHighlight() {
        _uiState.update { it.copy(showKnownWordsHighlight = !it.showKnownWordsHighlight) }
    }

    fun toggleChapterNav() {
        _uiState.update { it.copy(showChapterNav = !it.showChapterNav) }
    }

    // 双击选句翻译
    private val _selectedSentence = MutableStateFlow<String?>(null)
    val selectedSentence: StateFlow<String?> = _selectedSentence.asStateFlow()

    private val _sentenceTranslation = MutableStateFlow<String?>(null)
    val sentenceTranslation: StateFlow<String?> = _sentenceTranslation.asStateFlow()

    fun translateSentence(sentence: String) {
        viewModelScope.launch {
            _selectedSentence.value = sentence
            _sentenceTranslation.value = null
            val result = translationHelper.translateSentence(sentence)
            _sentenceTranslation.value = result
        }
    }

    fun dismissSentenceTranslation() {
        _selectedSentence.value = null
        _sentenceTranslation.value = null
    }

    // ── 书签 ─────────────────────────────────
    fun toggleBookmark(paragraphIndex: Int) {
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            val existing = bookmarkDao.getBookmarkAt(bookId, paragraphIndex)
            if (existing != null) {
                bookmarkDao.delete(existing)
            } else {
                bookmarkDao.insert(BookmarkEntity(bookId = bookId, paragraphIndex = paragraphIndex))
            }
        }
    }

    fun isBookmarked(paragraphIndex: Int): Boolean {
        return paragraphIndex in _uiState.value.bookmarkedParagraphs
    }

    // ── 高亮 ─────────────────────────────────
    fun addHighlight(
        paragraphIndex: Int,
        startOffset: Int,
        endOffset: Int,
        text: String,
        colorHex: String = "#FFE082",
    ) {
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            highlightDao.insert(
                HighlightEntity(
                    bookId = bookId,
                    paragraphIndex = paragraphIndex,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    text = text,
                    color = colorHex,
                )
            )
        }
    }

    fun removeHighlight(highlightId: Long) {
        viewModelScope.launch {
            highlightDao.deleteById(highlightId)
        }
    }

    // ── 听写练习 ─────────────────────────────
    fun startDictation(paragraphIndex: Int) {
        val para = _uiState.value.paragraphs.getOrNull(paragraphIndex) ?: return
        val words = Regex("([a-zA-Z]+)").findAll(para).map { it.value }.toList()
        if (words.isEmpty()) return
        val hiddenIndices = words.indices.shuffled().take(maxOf(1, words.size / 3))
        val cloze = words.mapIndexed { i, word ->
            ClozeWord(text = word, isHidden = i in hiddenIndices)
        }
        _uiState.update {
            it.copy(
                readingMode = ReadingMode.DICTATION,
                clozeWords = cloze,
                hiddenWordAnswer = null,
                currentParagraphIndex = paragraphIndex,
            )
        }
    }

    fun getReadingDurationMinutes(): Long {
        val start = _uiState.value.readingStartTime
        return if (start > 0) (System.currentTimeMillis() - start) / 60_000 else 0
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}
