@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.*
import com.eareyereading.domain.repository.*
import com.eareyereading.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书籍加载域：换书编排（停播/旧任务取消/会话统计快照落库）、正文解析、
 * 阅读状态与翻译缓存恢复、书签/高亮/生词本订阅。
 */
fun ReaderViewModel.loadBook(bookId: Long) {
    // 同一 VM 重新加载（换书/重进）：先停掉所有播放，
    // 旧循环持有的是旧段落快照，继续跑会越界/错读
    stopAllPlayback()

    flushPreviousBookStatsOnSwitch()

    currentBookId = bookId
    readingStartTime = System.currentTimeMillis()
    lastFlushTime = System.currentTimeMillis()
    sessionCharsRead = 0L
    lastRecordedParagraphIndex = -1

    // 取消旧的 Flow collectors，防止泄漏
    vocabJob?.cancel()
    bookmarksJob?.cancel()
    highlightsJob?.cancel()
    bookJob?.cancel()
    // 全书翻译 Job 也必须取消：它捕获的是旧书段落，翻译结果是按
    // 段落下标键控的 Map——不取消的话，慢翻译（首次要下载 ML Kit 模型）
    // 落地后会把旧书译文写进新书的同名下标，新书段落顶着别人的译文
    translationJob?.cancel()
    // 点词/句子翻译的异步结果同样属于旧书：A 书点词后立刻换 B 书，
    // 慢查询落地会把 A 书的词卡写进 B 书 UI（issue 3.2）
    selectWordJob?.cancel()
    sentenceTranslateJob?.cancel()

    _uiState.update { it.copy(isLoading = true, readingStartTime = readingStartTime) }

    observeVocabulary()

    bookJob = viewModelScope.launch {
        try {
            // 用 first() 而非 collect() — 单次拉取，避免 updateProgress 后 Flow 重发射时
            // 错误地将 currentParagraphIndex 重置为保存的旧位置（覆盖用户当前阅读进度）
            val book = bookRepository.getBookById(bookId).first()
            if (book == null) {
                // 书籍不存在（深链失效/已删除）：明确提示，由页面自动返回；
                // 同时保持 bookLoaded = false，退出时不写孤儿进度行
                android.util.Log.w("ReaderViewModel", "loadBook: book $bookId not found")
                _uiState.update { it.copy(isLoading = false) }
                showToast("书籍不存在或已被删除")
                return@launch
            }
            val paragraphs = if (book.content.isNotBlank()) {
                // split 是 O(全书) 的字符串切分 + 一次性分配全部段子串，
                // 10M 字符的书在主线程执行可感知卡顿——与 EPUB 重解析
                // 同样下沉后台调度器
                withContext(Dispatchers.Default) {
                    book.content.split("\n\n").filter { it.isNotBlank() }
                }
            } else {
                // parseBook 是阻塞式 zip IO + 正则解析，viewModelScope 跑在
                // Main 上——大书打开时直接 ANR（R9 修过 addBook 同款调用点，
                // 阅读加载路径这条漏网）
                // issue 9.9：统一读取代理，本地文件失效时回退用持久化的 content:// URI 读取
                withContext(Dispatchers.IO) {
                    epubParser.parseBook(book.filePath, book.sourceUri, context.contentResolver).paragraphs
                }
            }
            val state = readingRepository.getState(bookId)
            // 与 saveState 持久化的 totalCharacters 口径一致（都按段落分隔符拼接）
            val totalChars = paragraphs.joinToString("\n\n").length.toLong()
            // 内容可能比重导入/重切分，持久化的位置必须按新内容收敛，
            // 否则 Slider/进度/朗读索引全部越界
            val maxIdx = (paragraphs.size - 1).coerceAtLeast(0)
            // issue 8.5：优先从 Room 读本书语言对的翻译缓存。回译/分栏模式
            // 重开书直接展示已缓存的译文，不再重跑整本翻译；翻译结果首次落地后
            // 由 translateAllParagraphs 写入缓存表
            val bookLang = book.language.takeIf { it.isNotBlank() } ?: "en"
            // 缓存键分层（LLM/机翻分开缓存）：见 TranslationHelper.effectiveCacheLangPair
            val cachedTranslations = readingRepository.getTranslations(
                bookId,
                translationHelper.effectiveCacheLangPair("$bookLang>zh"),
            )

            _uiState.update {
                it.copy(
                    // content 剥离：paragraphs 已是全文的段落形态，再在 uiState
                    // 持有 content 即整书双份常驻内存（10M 字符书 ≈ 40MB+）。
                    // 后续需要重解析时（content 为空分支）由本地 book 变量兜底
                    book = book.copy(content = ""),
                    paragraphs = paragraphs,
                    currentParagraphIndex = (state?.currentParagraph ?: 0).coerceIn(0, maxIdx),
                    currentWordIndex = (state?.currentPosition ?: 0).coerceAtLeast(0),
                    readingMode = state?.readingMode ?: ReadingMode.NORMAL,
                    rsvpSpeed = state?.rsvpSpeed ?: it.rsvpSpeed,
                    // 每本书持久化的字号/主题随书恢复（此前只写不读，往返不对称）
                    fontSize = state?.fontSize ?: it.fontSize,
                    theme = state?.theme ?: it.theme,
                    totalReadChars = totalChars,
                    // 换书必须清掉上一本书的派生状态，否则旧书内容在新书里诈尸：
                    // 译文 Map 按下标键控会直接张冠李戴；词卡/答案弹窗引用旧书内容
                    // issue 8.5：不再硬清 paragraphTranslations，改为读新书的 Room 缓存
                    paragraphTranslations = cachedTranslations,
                    showTranslation = false,
                    isTranslating = false,
                    selectedVocab = null,
                    showWordDialog = false,
                    wordDefinition = null,
                    hiddenWordAnswer = null,
                    // 书签/高亮 collect 到新书首帧前是旧书数据：
                    // 短暂残留即"幽灵书签"（issue 3.1）
                    bookmarkedParagraphs = emptySet(),
                    highlights = emptyMap(),
                    isLoading = false,
                )
            }
            // 句子翻译弹窗同样属于上一本书的内容，一并清掉
            _selectedSentence.value = null
            _sentenceTranslation.value = null
            bookLoaded = true
            // 字符统计的高水位从"恢复后的位置"起算，而不是 -1：
            // 否则退出时 doSaveProgress 会把 0..恢复位置 的整段前缀当成本次新读，
            // 累计写库后每次重开同一本书今日字数都会虚增一截
            lastRecordedParagraphIndex = (state?.currentParagraph ?: 0).coerceIn(0, maxIdx)

            // 恢复的阅读模式若依赖派生数据（挖空/模糊/全书译文），必须立即
            // 生成/拉取，否则重开书是空白页或"正在获取译文..."假加载态
            // （此前只有 setReadingMode 会生成）
            when (_uiState.value.readingMode) {
                ReadingMode.CLOZE -> generateCloze()
                ReadingMode.FUZZY -> generateFuzzy()
                // 全文翻译改为"总是补缺"：loadBook 已把 Room 缓存灌进
                // paragraphTranslations，若只在 isEmpty 时才触发，部分缓存
                // （上次中途取消/失败）的书永远缺着尾巴不补
                ReadingMode.BACK_TRANSLATION, ReadingMode.SPLIT ->
                    translateAllParagraphs()
                else -> Unit
            }

            // TTS 是单例、跨书复用：无论是否已初始化都要同步语言，
            // 否则读完英文书再开中文书会用旧 locale 一直读下去
            ttsHelper.setLanguage(book.language)

            // 预翻译预热：进书即后台拉起 ML Kit 翻译模型下载/就绪，
            // 首次开启全文翻译不再阻塞等待模型（最多 30s）
            viewModelScope.launch {
                try {
                    translationHelper.warmUp(bookLang)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 预热失败静默：正式翻译路径仍有重试窗口兜底
                }
            }

            // 初始化 TTS
            if (!_uiState.value.ttsInitialized) {
                val ok = try {
                    ttsHelper.initialize(book.language)
                } catch (e: TimeoutCancellationException) {
                    android.util.Log.w("ReaderViewModel", "TTS init timed out", e)
                    false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("ReaderViewModel", "TTS init failed", e)
                    false
                }
                _uiState.update { it.copy(ttsInitialized = ok) }
                // 加载书籍时静默失败，不弹引导（等用户点击朗读时再弹）
                if (!ok) {
                    android.util.Log.i(
                        "ReaderViewModel",
                        "TTS init failed silently on load: ${ttsHelper.lastFailureReason}",
                    )
                }
            }

            observeBookmarks(bookId)
            observeHighlights(bookId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 损坏/缺失的 EPUB、DB 异常等不再经由未捕获处理器崩 App
            android.util.Log.e("ReaderViewModel", "loadBook failed", e)
            _uiState.update { it.copy(isLoading = false) }
            showToast("书籍加载失败")
        }
    }
}

/**
 * 换书前把上一本书的会话统计落库。
 * 必须快照传参：viewModelScope 是 Main 调度器，launch 体要等本函数
 * 让出线程后才执行，而调用方随后同步把 sessionCharsRead 归零/前移基准——
 * 旧实现让 flush 协程读字段，永远读到 0 直接早返回，上一本书的
 * 阅读时长/字数在每次换书时静默丢失
 */
private fun ReaderViewModel.flushPreviousBookStatsOnSwitch() {
    currentBookId?.let { prevId ->
        val pendingChars = sessionCharsRead
        if (pendingChars > 0) {
            val flushBase = lastFlushTime
            val flushHighWater = (lastRecordedParagraphIndex + 1).coerceAtLeast(1)
            viewModelScope.launch {
                flushSessionStats(
                    prevId,
                    chars = pendingChars,
                    baseTime = flushBase,
                    paragraphsHighWater = flushHighWater,
                    clearSession = false,   // 字段已被调用方同步重置，不能再清
                )
            }
        }
    }
}

/** 生词本订阅：驱动"已知词/全部生词"高亮。 */
private fun ReaderViewModel.observeVocabulary() {
    vocabJob = viewModelScope.launch {
        try {
            vocabularyRepository.getAllVocabulary().collect { vocabList ->
                val known = vocabList.filter { it.isLearned }.map { it.word.lowercase(java.util.Locale.ROOT) }.toSet()
                val allWords = vocabList.map { it.word.lowercase(java.util.Locale.ROOT) }.toSet()
                _uiState.update { it.copy(knownWords = known, learnedWords = allWords) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "vocab collect failed", e)
        }
    }
}

/** 书签订阅：新书首帧前清空集合，杜绝"幽灵书签"（issue 3.1）。 */
private fun ReaderViewModel.observeBookmarks(bookId: Long) {
    bookmarksJob?.cancel()
    bookmarksJob = viewModelScope.launch {
        try {
            bookmarkDao.getBookmarksForBook(bookId).collect { bookmarks ->
                _uiState.update {
                    it.copy(bookmarkedParagraphs = bookmarks.map { b -> b.paragraphIndex }.toSet())
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "bookmarks collect failed", e)
        }
    }
}

/** 高亮订阅：实体按段落分组映射为渲染用的 HighlightData。 */
private fun ReaderViewModel.observeHighlights(bookId: Long) {
    highlightsJob?.cancel()
    highlightsJob = viewModelScope.launch {
        try {
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "highlights collect failed", e)
        }
    }
}
