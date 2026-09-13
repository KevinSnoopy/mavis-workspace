@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eareyereading.data.local.dao.BookmarkDao
import com.eareyereading.data.local.dao.HighlightDao
import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.domain.model.*
import com.eareyereading.domain.repository.*
import com.eareyereading.ui.theme.*
import com.eareyereading.util.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    internal val bookRepository: BookRepository,
    private val vocabularyRepository: VocabularyRepository,
    internal val readingRepository: ReadingRepository,
    private val settingsRepository: SettingsRepository,
    internal val wordAnalyzer: WordAnalyzer,
    internal val ttsHelper: TtsHelper,
    internal val translationHelper: TranslationHelper,
    private val epubParser: EpubParser,
    private val collinsClassifier: CollinsClassifier,
    internal val bookmarkDao: BookmarkDao,
    internal val highlightDao: HighlightDao,
    internal val readingStatsDao: ReadingStatsDao,
) : ViewModel() {

    internal val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    // 一次性 UI 提示（错误 / 警告），UI 层收集后弹 Toast
    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    // TTS 引擎引导事件：弹窗引导用户去设置/安装 TTS 引擎
    internal val _ttsInstallPrompt = MutableSharedFlow<TtsInstallPrompt>(extraBufferCapacity = 2)
    val ttsInstallPrompt: SharedFlow<TtsInstallPrompt> = _ttsInstallPrompt.asSharedFlow()

    internal fun showToast(msg: String) {
        _toastMessage.tryEmit(msg)
    }

    /**
     * 暴露注入的 CollinsClassifier 单例给渲染层：此前 ReaderScreen 两个视图
     * 各自 remember { CollinsClassifier() } 手动 new，词表双份内存且在组合期
     * 构建卡首帧；统一走单例后全 App 一份词表、首次进入阅读页前已就绪。
     */
    val wordClassifier: CollinsClassifier get() = collinsClassifier

    // 一次性提示防抖标志（域逻辑见 ReaderViewModelTts.kt）
    internal var embeddedVoiceMismatchHintShown = false
    internal var ttsWarmUpHintShown = false

    internal var rsvpJob: Job? = null
    internal var speedJob: Job? = null
    internal var autoReadJob: Job? = null
    internal var currentBookId: Long? = null
    internal var readingStartTime: Long = 0L

    /**
     * 视口可见段落区间（滚动视图 = 当前可见项区间；翻页视图 = 当前页段落区间）。
     *
     * 翻译上屏策略据此判断"哪些段落要先攒着"：视口内的段落不能逐段上屏，
     * 否则每冒出一段译文就把同屏下方内容往下顶一次。
     *
     * 故意放在普通字段而非 uiState：滚动时高频变化，进状态流会驱动整屏
     * 重组；它只被翻译协程读取，没有 UI 消费方。
     */
    internal var visibleParaRange: IntRange = IntRange.EMPTY

    // 本次阅读会话的统计（用于 saveProgress/cleanup 时写入 DB）
    internal var sessionCharsRead: Long = 0L
    internal var lastRecordedParagraphIndex: Int = -1
    // 增量落库的时间基准：距上次落库满 1 分钟才增量写一次，
    // 避免进程被杀丢失整段会话，也避免每次保存都记 1 分钟
    internal var lastFlushTime = 0L
    // 书籍是否成功加载过：未加载成功时退出不得写任何进度/状态（防孤儿行）
    internal var bookLoaded = false
    // saveProgress 防抖/收尾用：拖动进度条不再每像素写一次 DB
    internal var saveJob: kotlinx.coroutines.Job? = null

    // 书签切换用互斥锁串行化：真正的互斥而不是 cancel 上一个
    // （cancel 不阻塞、Room 语句中途不响应取消，竞态窗口仍在）
    internal val bookmarkMutex = kotlinx.coroutines.sync.Mutex()
    internal var bookmarkToggleJob: kotlinx.coroutines.Job? = null
    // 内置 TTS 模型下载防重入
    internal var downloadJob: kotlinx.coroutines.Job? = null
    internal var sentenceTranslateJob: kotlinx.coroutines.Job? = null
    // 全书翻译任务追踪：退出时可取消，防止 ML Kit 在后台空转完整本书
    internal var translationJob: kotlinx.coroutines.Job? = null
    // 单段朗读的初始化尝试（防初始化窗口内连点产生重复朗读）
    internal var ttsInitJob: kotlinx.coroutines.Job? = null

    // 朗读按钮防抖：连点（<500ms）直接忽略，避免并发 initialize 竞争
    //（2026-09-06 实测：连点触发多次 initialize，竞相构造 OfflineTts 实例，
    // 抢先者加载完被后到者 reuse，白加载一次 + 浪费 3s）
    internal var lastTogglePlayMs: Long = 0L

    // TTS 引导弹窗防抖：本会话内已经弹过则不再弹（避免用户每次点朗读都看到同一个弹窗）
    internal var ttsPromptShownThisSession = false

    // 双击选句翻译
    internal val _selectedSentence = MutableStateFlow<String?>(null)
    val selectedSentence: StateFlow<String?> = _selectedSentence.asStateFlow()

    internal val _sentenceTranslation = MutableStateFlow<String?>(null)
    val sentenceTranslation: StateFlow<String?> = _sentenceTranslation.asStateFlow()

    // ── 职责委托 ─────────────────────────────
    internal val settings = ReaderViewModelSettings(
        vm = this,
        settingsRepository = settingsRepository,
    )

    internal val vocabulary = ReaderViewModelVocabulary(
        vm = this,
        vocabularyRepository = vocabularyRepository,
        translationHelper = translationHelper,
        ttsHelper = ttsHelper,
        collinsClassifier = collinsClassifier,
    )

    internal val practice = ReaderViewModelPractice(
        vm = this,
        wordAnalyzer = wordAnalyzer,
        readingRepository = readingRepository,
    )

    internal val navigation = ReaderViewModelNavigation(
        vm = this,
        readingRepository = readingRepository,
        practice = practice,
    )

    internal val bookLoader = ReaderViewModelBookLoader(
        vm = this,
        context = context,
        bookRepository = bookRepository,
        readingRepository = readingRepository,
        vocabularyRepository = vocabularyRepository,
        settingsRepository = settingsRepository,
        epubParser = epubParser,
        translationHelper = translationHelper,
        ttsHelper = ttsHelper,
        bookmarkDao = bookmarkDao,
        highlightDao = highlightDao,
        practice = practice,
    )

    init {
        viewModelScope.launch {
            try {
                combine(
                    settingsRepository.getRsvpSpeed(),
                    settingsRepository.getRsvpStrength(),
                    settingsRepository.getFontSize(),
                    settingsRepository.getTheme(),
                    settingsRepository.getTranslationAlpha(),
                    settingsRepository.getCollinsHighlight(),
                    settingsRepository.getSerifFont(),
                    settingsRepository.getReadingPageMode(),
                    settingsRepository.getKnownWordsHighlight(),
                ) { values ->
                    // P1 修复: 用 as? 安全转换 + 默认值,避免 DataStore 旧版本数据 schema
                    // 不匹配时 ClassCastException 直接死掉 init block(整个 Reader 屏开不起来)。
                    // 当前 SettingsRepository 返回类型稳定,但 as 是脆性耦合,加防御。
                    @Suppress("UNCHECKED_CAST")
                    val speed = values[0] as? Int ?: 300
                    @Suppress("UNCHECKED_CAST")
                    val strength = values[1] as? Int ?: 3
                    @Suppress("UNCHECKED_CAST")
                    val fontSize = values[2] as? Int ?: 18
                    @Suppress("UNCHECKED_CAST")
                    val theme = values[3] as? ReadingTheme ?: ReadingTheme.LIGHT
                    @Suppress("UNCHECKED_CAST")
                    val alpha = values[4] as? Float ?: 0.85f
                    @Suppress("UNCHECKED_CAST")
                    val collinsHighlight = values[5] as? Boolean ?: false
                    @Suppress("UNCHECKED_CAST")
                    val serifFont = values[6] as? Boolean ?: false
                    @Suppress("UNCHECKED_CAST")
                    val pageMode = values[7] as? Boolean ?: false
                    @Suppress("UNCHECKED_CAST")
                    val knownWordsHighlight = values[8] as? Boolean ?: true
                    ReadingSettings(
                        speed, fontSize, theme, alpha, strength,
                        collinsHighlight, serifFont, pageMode, knownWordsHighlight,
                    )
                }.collect { s ->
                    _uiState.update {
                        it.copy(
                            // 已打开书籍时，书籍自带的值优先（loadBook 写入）：
                            // 全局设置的（重）发射不得覆盖它。此前只保护了
                            // rsvpSpeed，fontSize / theme 同样被随书持久化，
                            // 却会被任意一次设置变更（如改译文透明度）打回全局值
                            // ——表现为"书内调过的字号/主题莫名重置"。
                            // 判据用 bookLoaded 而非 currentBookId：loadBook 一进来
                            // 就置 currentBookId，用它会把"首次打开的书本应继承全局
                            // 字号"也一起挡掉
                            rsvpSpeed = if (currentBookId != null) it.rsvpSpeed else s.speed,
                            rsvpStrength = s.strength,
                            fontSize = if (bookLoaded) it.fontSize else s.fontSize,
                            theme = if (bookLoaded) it.theme else s.theme,
                            translationAlpha = s.alpha,
                            showWordLevelColors = s.collinsHighlight,
                            showKnownWordsHighlight = s.knownWordsHighlight,
                            serifFont = s.serifFont,
                            pageMode = s.pageMode,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "settings combine failed", e)
            }
        }

        // 引擎外部停止信号（音频焦点被电话/闹钟抢走等）：引擎 stop() 只能
        // 取消正在出声的那一句，循环播放由本 VM 的 Job 驱动——必须在这里
        // 收闸（清 isAutoReading/isPlaying/isTtsPlaying + 取消驱动 Job），
        // 否则焦点丢失后自动朗读/速读会推进到下一段继续压着通话读；
        // 单段朗读的 onComplete 也会被取消路径吞掉导致 isTtsPlaying 卡 true
        viewModelScope.launch {
            try {
                kotlinx.coroutines.flow.merge(
                    ttsHelper.getEmbeddedEngine().externalStop,
                    ttsHelper.getTencentEngine().externalStop,
                ).collect {
                    android.util.Log.i("ReaderViewModel", "external stop received, halting all playback")
                    stopAllPlayback()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "externalStop collect failed", e)
            }
        }

        // TTS 语速倍率：此前设置页可写、数据层可存，但没有任何消费者（死线）。
        // 这里接到 ttsHelper.setSpeed，系统/内置朗读都会生效
        viewModelScope.launch {
            try {
                settingsRepository.getTtsSpeed().collect { speed ->
                    ttsHelper.setSpeed(speed)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "tts speed collect failed", e)
            }
        }
    }

    // ── 公共 API 委托 ─────────────────────────────

    fun loadBook(bookId: Long) = bookLoader.loadBook(bookId)

    fun setReadingMode(mode: ReadingMode) = navigation.setReadingMode(mode)

    fun generateCloze() = practice.generateCloze()

    fun generateFuzzy() = practice.generateFuzzy()

    fun nextParagraph() = navigation.nextParagraph()

    fun prevParagraph() = navigation.prevParagraph()

    fun goToParagraph(index: Int) = navigation.goToParagraph(index)

    fun onVisibleParagraphChanged(index: Int) = navigation.onVisibleParagraphChanged(index)

    /**
     * 视口可见段落区间上报（滚动视图上报可见项区间，翻页视图上报当前页
     * 覆盖的段落区间）。翻译上屏据此把"正在看的这一屏"整体延后上屏，
     * 详见 [commitReaderTranslations]。
     */
    fun onVisibleRangeChanged(first: Int, last: Int) {
        visibleParaRange = if (first <= last) first..last else IntRange.EMPTY
    }

    fun setFontSize(size: Int) = settings.setFontSize(size)

    fun adjustFontSize(delta: Int) = settings.adjustFontSize(delta)

    fun cycleReadingTheme() = settings.cycleReadingTheme()

    fun toggleSerifFont() = settings.toggleSerifFont()

    fun togglePageMode() = settings.togglePageMode()

    fun setRsvpSpeed(speed: Int) = settings.setRsvpSpeed(speed)

    fun setRsvpStrength(strength: Int) = settings.setRsvpStrength(strength)

    fun selectWord(word: String) = vocabulary.selectWord(word)

    fun addToVocabulary(word: String, context: String?) = vocabulary.addToVocabulary(word, context)

    fun hideWord() = practice.hideWord()

    fun checkDictationAnswer(input: String): Boolean = practice.checkDictationAnswer(input)

    fun setTranslationAlpha(alpha: Float) = settings.setTranslationAlpha(alpha)

    fun dismissModeSelector() = settings.dismissModeSelector()

    fun showModeSelector() = settings.showModeSelector()

    fun toggleSettings() = settings.toggleSettings()

    fun dismissWordDialog() = vocabulary.dismissWordDialog()

    fun toggleWordLevelColors() = settings.toggleWordLevelColors()

    fun toggleKnownWordsHighlight() = settings.toggleKnownWordsHighlight()

    fun toggleChapterNav() = settings.toggleChapterNav()

    fun toggleModeHelp() = settings.toggleModeHelp()

    fun startDictation(paragraphIndex: Int) = practice.startDictation(paragraphIndex)

    /**
     * 取消所有运行中的作业并停止 TTS，完成最后一次保存。
     *
     * 保存分两条路径（issue 3.10）：
     * - 默认（onDispose 触发，scope 仍存活）：异步保存，不阻塞主线程
     * - [synchronous]（onCleared 触发）：lifecycle-viewmodel 在 onCleared
     *   返回之后才取消 viewModelScope，此时必须 runBlocking 同步写完，
     *   否则异步保存在第一个挂起点就被取消 —— 退出进度静默丢失。
     * flushSessionStats 以 sessionCharsRead==0 天然单飞，
     * onDispose + onCleared 双路径不会重复写。
     */
    fun cleanup(synchronous: Boolean = false) {
        rsvpJob?.cancel()
        speedJob?.cancel()
        autoReadJob?.cancel()
        ttsInitJob?.cancel()
        downloadJob?.cancel()
        saveJob?.cancel()
        vocabulary.cancel()
        sentenceTranslateJob?.cancel()
        translationJob?.cancel()
        bookmarkToggleJob?.cancel()
        bookLoader.cancel()
        ttsHelper.stop()
        // issue 8.2：close() 此前全项目无人调用，ML Kit Translator
        // native handle 永不释放，模型被系统回收后翻译静默失效
        translationHelper.close()
        // 防抖窗口内未落盘的设置写入：取消计时、同步冲刷，
        // 用户拖完滑杆立刻退页也不会丢设置
        val pendingSettings = settings.flushPendingWrites()
        val finalSave: suspend () -> Unit = {
            pendingSettings.forEach { write ->
                try {
                    write()
                } catch (e: Exception) {
                    android.util.Log.e("ReaderViewModel", "flush settings write failed", e)
                }
            }
            doSaveProgress()
            currentBookId?.let { flushSessionStats(it) }
        }
        if (synchronous) {
            runBlocking(Dispatchers.IO) { finalSave() }
        } else {
            // onDispose 路径：scope 仍存活，异步写不卡主线程；
            // 若随后 VM 销毁触发 onCleared，其同步保存兜底（且取消本异步任务也无碍）
            viewModelScope.launch(Dispatchers.IO) { finalSave() }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // onCleared 返回后 viewModelScope 立即被取消：这里必须同步写完，
        // 否则收尾保存落在已取消的 scope 上全部丢失
        cleanup(synchronous = true)
    }
}
