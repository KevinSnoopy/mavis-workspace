package com.eareyereading.tts

import android.util.Log

/**
 * 首声四段埋点（TTS_LATENCY_DIAGNOSIS_2026-09-06 第五节）：
 * click→firstOffer→trackPlayed→headMoved 四段耗时一条日志量化首声延迟，
 * 不再靠 awaitWatermark 的 head=0 反推。
 *
 * 会话级单例够用：speakMutex 保证同一时刻只有一条朗读链，新链 [begin]
 * 会重置全部字段。
 *
 * 封装动机（单一职责 + 封装）：原先 8 个 @Volatile 计时字段裸露在
 * EmbeddedTtsEngine 字段区，读写散落在 doSpeakQueueLocked 与
 * StreamingTrackPlayer 回调里，"只在 click!=0 且本段未记录时写"的守卫
 * 约定被复制了 5 处——收敛为一个类后，引擎只管在对应时机调用
 * record* 钩子，计时与日志语义集中在此，杜绝第 6 处复制走样。
 */
internal class TtsFirstAudioTrace {

    /** 用户点击朗读的时刻（ms）；由 [begin] 在 UI 线程写入 */
    @Volatile
    private var clickMs: Long = 0L

    /** 首帧 PCM 入队时刻（ms）；generateWithCallback 首次回调时写 */
    @Volatile
    private var firstOfferMs: Long = 0L

    /** AudioTrack.play() 时刻（ms）；StreamingTrackPlayer.startTrack 首次成功时写 */
    @Volatile
    private var trackPlayedMs: Long = 0L

    /** 硬件首次消费帧时刻（ms）；awaitWatermark 首次 head>0 时写 */
    @Volatile
    private var headMovedMs: Long = 0L

    /** 本链首块字符数与句子数，首声日志里一并报出 */
    @Volatile
    private var firstBlockChars: Int = 0

    @Volatile
    private var sentenceCount: Int = 0

    /** 本链是否已开始（click 已打点）；未开始的链不记录后续段 */
    private val started: Boolean
        get() = clickMs != 0L

    /** 首帧是否已入队（G2P 归因日志只对已 offer 的链打） */
    fun hasFirstOffer(): Boolean = firstOfferMs != 0L

    /**
     * 在 UI 点击朗读的瞬间调用，标记本条朗读链首声计时的起点。
     * 必须在 speakViaQueue 拿锁之前调用——首声延迟含等锁时间。
     * 幂等重置：连点会重置起点，与 speakViaQueue 取消旧 job 的语义一致。
     */
    fun begin() {
        clickMs = System.currentTimeMillis()
        firstOfferMs = 0L
        trackPlayedMs = 0L
        headMovedMs = 0L
        firstBlockChars = 0
        sentenceCount = 0
    }

    /** 补全本链句子数（click 瞬间尚未切句，进链时才有）。 */
    fun noteSentenceCount(count: Int) {
        if (sentenceCount == 0 && started) {
            sentenceCount = count
        }
    }

    /** 首帧 PCM 入队（现场合成或缓存命中路径共用）。 */
    fun recordFirstOffer(blockChars: Int) {
        if (firstOfferMs == 0L && started) {
            firstOfferMs = System.currentTimeMillis()
            firstBlockChars = blockChars
        }
    }

    /** AudioTrack 首次成功 start。 */
    fun recordTrackPlayed() {
        if (trackPlayedMs == 0L && started) {
            trackPlayedMs = System.currentTimeMillis()
        }
    }

    /**
     * 硬件首次消费帧：打完整首声日志（四段耗时一条）。
     * click→firstOffer=合成+G2P，→trackPlayed=预缓冲攒够+建轨，
     * →headMoved=硬件开始消费；blockChars/sentences 标首块规模。
     */
    fun recordHeadMoved() {
        if (headMovedMs == 0L && started) {
            headMovedMs = System.currentTimeMillis()
            Log.i(
                TAG,
                "TTS first-audio: click→firstOffer=${firstOfferMs - clickMs}ms, " +
                    "→trackPlayed=${trackPlayedMs - clickMs}ms, " +
                    "→headMoved=${headMovedMs - clickMs}ms, " +
                    "blockChars=$firstBlockChars, sentences=$sentenceCount",
            )
        }
    }

    private companion object {
        private const val TAG = "EmbeddedTtsEngine"
    }
}
