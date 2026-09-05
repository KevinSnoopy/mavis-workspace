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
import com.eareyereading.tts.EmbeddedTtsEngine
import com.eareyereading.ui.theme.*
import com.eareyereading.util.*
import com.eareyereading.util.CollinsClassifier.WordLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext internal val context: Context,
    internal val bookRepository: BookRepository,
    internal val vocabularyRepository: VocabularyRepository,
    internal val readingRepository: ReadingRepository,
    private val settingsRepository: SettingsRepository,
    internal val wordAnalyzer: WordAnalyzer,
    internal val ttsHelper: TtsHelper,
    internal val translationHelper: TranslationHelper,
    internal val epubParser: EpubParser,
    internal val collinsClassifier: CollinsClassifier,
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

    // 双击选句翻译（域逻辑见 ReaderViewModelTranslation.kt）
    internal val _selectedSentence = MutableStateFlow<String?>(null)
    val selectedSentence: StateFlow<String?> = _selectedSentence.asStateFlow()

    internal val _sentenceTranslation = MutableStateFlow<String?>(null)
    val sentenceTranslation: StateFlow<String?> = _sentenceTranslation.asStateFlow()

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
    internal var vocabJob: Job? = null
    internal var bookmarksJob: Job? = null
    internal var highlightsJob: Job? = null
    internal var bookJob: Job? = null
    internal var currentBookId: Long? = null
    internal var readingStartTime: Long = 0L

    companion object {
        // 翻译透明度下限
        private const val TRANSLATION_ALPHA_MIN = 0.3f
        private const val TRANSLATION_ALPHA_MAX = 1f

        private const val SETTINGS_PERSIST_DEBOUNCE_MS = 300L

    }

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

    // 设置滑杆逐像素写 DataStore 的防抖：UI 状态立即更新保证滑杆跟手，
    // 持久化合并到拖停后一次（与 saveProgress 同型）。按设置项分 key，
    // 一个滑杆的拖动不会取消另一项的待写；退出时由 cleanup() 兜底冲刷
    private val settingsPersistJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val settingsPendingWrites = mutableMapOf<String, suspend () -> Unit>()

    private fun persistSettingDebounced(key: String, write: suspend () -> Unit) {
        settingsPersistJobs[key]?.cancel()
        settingsPendingWrites[key] = write
        settingsPersistJobs[key] = viewModelScope.launch {
            delay(SETTINGS_PERSIST_DEBOUNCE_MS)
            write()
            // 按身份移除：只清自己这条，不误删并发排队的同名写入
            if (settingsPendingWrites[key] === write) {
                settingsPendingWrites.remove(key)
            }
        }
    }
    // 书签切换用互斥锁串行化：真正的互斥而不是 cancel 上一个
    // （cancel 不阻塞、Room 语句中途不响应取消，竞态窗口仍在）
    internal val bookmarkMutex = kotlinx.coroutines.sync.Mutex()
    internal var bookmarkToggleJob: kotlinx.coroutines.Job? = null
    // 内置 TTS 模型下载防重入
    internal var downloadJob: kotlinx.coroutines.Job? = null
    // 点词查询串行化：后一次点词取消前一次，慢查询不再覆盖新弹窗
    internal var selectWordJob: kotlinx.coroutines.Job? = null
    internal var sentenceTranslateJob: kotlinx.coroutines.Job? = null
    // 全书翻译任务追踪：退出时可取消，防止 ML Kit 在后台空转完整本书
    internal var translationJob: kotlinx.coroutines.Job? = null
    // 单段朗读的初始化尝试（防初始化窗口内连点产生重复朗读）
    internal var ttsInitJob: kotlinx.coroutines.Job? = null

    // TTS 引导弹窗防抖：本会话内已经弹过则不再弹（避免用户每次点朗读都看到同一个弹窗）
    internal var ttsPromptShownThisSession = false

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
                    ReadingSettings(speed, fontSize, theme, alpha, strength, collinsHighlight, serifFont, pageMode)
                }.collect { s ->
                    _uiState.update {
                        it.copy(
                            // 已打开书籍时，书籍自带的 rsvpSpeed 优先（loadBook 写入），
                            // 全局设置的（重）发射不再覆盖它，消除双写竞态
                            rsvpSpeed = if (currentBookId != null) it.rsvpSpeed else s.speed,
                            rsvpStrength = s.strength,
                            fontSize = s.fontSize,
                            theme = s.theme,
                            translationAlpha = s.alpha,
                            showWordLevelColors = s.collinsHighlight,
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
                ttsHelper.getEmbeddedEngine().externalStop.collect {
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

    fun nextParagraph() {
        val paragraphs = _uiState.value.paragraphs
        if (paragraphs.isEmpty()) return
        // 手动跳转必须停掉进行中的播放：否则朗读循环下一步会把
        // currentParagraphIndex 又写回它自己的进度，视口被拽回
        stopAllPlayback()
        val nextIdx = (_uiState.value.currentParagraphIndex + 1).coerceAtMost(paragraphs.size - 1)
        _uiState.update { it.copy(currentParagraphIndex = nextIdx, currentWordIndex = 0) }
        recordParagraphVisit(nextIdx)  // issue 3.6：原子累计，不等防抖保存
        saveProgress()
        if (_uiState.value.readingMode == ReadingMode.CLOZE) generateCloze()
        if (_uiState.value.readingMode == ReadingMode.FUZZY) generateFuzzy()
    }

    fun prevParagraph() {
        val paragraphs = _uiState.value.paragraphs
        if (paragraphs.isEmpty()) return
        stopAllPlayback()
        val prevIdx = (_uiState.value.currentParagraphIndex - 1).coerceAtLeast(0)
        _uiState.update { it.copy(currentParagraphIndex = prevIdx, currentWordIndex = 0) }
        saveProgress()
        if (_uiState.value.readingMode == ReadingMode.CLOZE) generateCloze()
        if (_uiState.value.readingMode == ReadingMode.FUZZY) generateFuzzy()
    }

    fun goToParagraph(index: Int) {
        val paragraphs = _uiState.value.paragraphs
        if (paragraphs.isEmpty()) return
        stopAllPlayback()
        val idx = index.coerceIn(0, paragraphs.size - 1)
        _uiState.update { it.copy(currentParagraphIndex = idx, currentWordIndex = 0) }
        recordParagraphVisit(idx)  // issue 3.6
        saveProgress()
        if (_uiState.value.readingMode == ReadingMode.CLOZE) generateCloze()
        if (_uiState.value.readingMode == ReadingMode.FUZZY) generateFuzzy()
    }

    /**
     * 视口滚动同步（NORMAL 模式 LazyColumn 上报可见段落）。
     * 滑动阅读时让底栏/进度/统计跟上视口；播放进行中由播放循环主导索引，忽略上报
     */
    fun onVisibleParagraphChanged(index: Int) {
        val s = _uiState.value
        if (s.isAutoReading || s.isPlaying || s.isTtsPlaying) return
        if (index < 0 || index >= s.paragraphs.size) return
        if (index == s.currentParagraphIndex) return
        _uiState.update { it.copy(currentParagraphIndex = index, currentWordIndex = 0) }
        recordParagraphVisit(index)  // issue 3.6：视口滚动前进按段累计
        saveProgress()
        if (s.readingMode == ReadingMode.CLOZE) generateCloze()
        if (s.readingMode == ReadingMode.FUZZY) generateFuzzy()
    }

    fun setFontSize(size: Int) {
        // 持久化收敛后的值：原实现 UI 显示收敛值、存储原始值，
        // 下次启动设置流回填时越界值会重新进入 UI。
        // 写库走防抖：滑杆拖动逐像素回调不再逐像素写 DataStore
        val coerced = size.coerceIn(12, 32)
        _uiState.update { it.copy(fontSize = coerced) }
        persistSettingDebounced("fontSize") { settingsRepository.setFontSize(coerced) }
    }

    /** 底部栏快捷字号调节（A- / A+ 按钮）：±1sp 步进，复用 setFontSize 的收敛与防抖。 */
    fun adjustFontSize(delta: Int) {
        setFontSize(_uiState.value.fontSize + delta)
    }

    /**
     * 阅读主题循环切换（明亮 → 护眼 → 暗黑 → 明亮），供底部栏快捷胶囊使用。
     * 主题本身是全局设置：写 DataStore 后设置流会回填 uiState.theme。
     */
    fun cycleReadingTheme() {
        val next = when (_uiState.value.theme) {
            ReadingTheme.LIGHT -> ReadingTheme.SEPIA
            ReadingTheme.SEPIA -> ReadingTheme.DARK
            ReadingTheme.DARK -> ReadingTheme.LIGHT
        }
        _uiState.update { it.copy(theme = next) }
        viewModelScope.launch {
            try {
                settingsRepository.setTheme(next)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "setTheme failed", e)
            }
        }
    }

    /** 衬线字体切换（阅读器正文字体，全局设置持久化）。 */
    fun toggleSerifFont() {
        val next = !_uiState.value.serifFont
        _uiState.update { it.copy(serifFont = next) }
        viewModelScope.launch {
            try {
                settingsRepository.setSerifFont(next)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "setSerifFont failed", e)
            }
        }
    }

    /** 阅读方式切换：上下滚动 ⇄ 左右翻页（仿书页，全局设置持久化）。 */
    fun togglePageMode() {
        val next = !_uiState.value.pageMode
        _uiState.update { it.copy(pageMode = next) }
        viewModelScope.launch {
            try {
                settingsRepository.setReadingPageMode(next)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "setReadingPageMode failed", e)
            }
        }
    }

    fun setRsvpSpeed(speed: Int) {
        val coerced = speed.coerceIn(100, 800)
        _uiState.update { it.copy(rsvpSpeed = coerced) }
        val bookId = currentBookId
        persistSettingDebounced("rsvpSpeed") {
            settingsRepository.setRsvpSpeed(coerced)
            bookId?.let { readingRepository.updateRsvpSpeed(it, coerced) }
        }
    }

    fun setRsvpStrength(strength: Int) {
        val coerced = strength.coerceIn(1, 5)
        _uiState.update { it.copy(rsvpStrength = coerced) }
        persistSettingDebounced("rsvpStrength") { settingsRepository.setRsvpStrength(coerced) }
    }

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
        selectWordJob?.cancel()
        sentenceTranslateJob?.cancel()
        translationJob?.cancel()
        bookmarkToggleJob?.cancel()
        vocabJob?.cancel()
        bookmarksJob?.cancel()
        highlightsJob?.cancel()
        bookJob?.cancel()
        ttsHelper.stop()
        // issue 8.2：close() 此前全项目无人调用，ML Kit Translator
        // native handle 永不释放，模型被系统回收后翻译静默失效
        translationHelper.close()
        // 防抖窗口内未落盘的设置写入：取消计时、同步冲刷，
        // 用户拖完滑杆立刻退页也不会丢设置
        settingsPersistJobs.values.forEach { it.cancel() }
        val pendingSettings = settingsPendingWrites.values.toList()
        settingsPendingWrites.clear()
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

    fun setTranslationAlpha(alpha: Float) {
        val coerced = alpha.coerceIn(TRANSLATION_ALPHA_MIN, TRANSLATION_ALPHA_MAX)
        _uiState.update { it.copy(translationAlpha = coerced) }
        persistSettingDebounced("translationAlpha") { settingsRepository.setTranslationAlpha(coerced) }
    }

    fun dismissModeSelector() {
        _uiState.update { it.copy(showModeSelector = false) }
    }

    fun showModeSelector() {
        _uiState.update { it.copy(showModeSelector = true) }
    }

    fun toggleSettings() {
        _uiState.update { it.copy(showSettings = !it.showSettings) }
    }

    fun dismissWordDialog() {
        _uiState.update { it.copy(showWordDialog = false, selectedVocab = null) }
    }

    fun toggleWordLevelColors() {
        // 持久化到 DataStore（复用 COLLINS_HIGHLIGHT），再次进入阅读详情页时由 init 的
        // settings combine 恢复，不再每次默认退回关闭
        val newValue = !_uiState.value.showWordLevelColors
        _uiState.update { it.copy(showWordLevelColors = newValue) }
        viewModelScope.launch { settingsRepository.setCollinsHighlight(newValue) }
    }

    fun toggleKnownWordsHighlight() {
        _uiState.update { it.copy(showKnownWordsHighlight = !it.showKnownWordsHighlight) }
    }

    fun toggleChapterNav() {
        _uiState.update { it.copy(showChapterNav = !it.showChapterNav) }
    }

    override fun onCleared() {
        super.onCleared()
        // onCleared 返回后 viewModelScope 立即被取消：这里必须同步写完，
        // 否则收尾保存落在已取消的 scope 上全部丢失
        cleanup(synchronous = true)
    }
}
