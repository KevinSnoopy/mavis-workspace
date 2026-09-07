package com.eareyereading.tts

import android.util.Log

/**
 * 首声四段埋点：click→firstOffer→trackPlayed→headMoved 四段耗时一条日志量化首声延迟。
 *
 * 从 [EmbeddedTtsEngine] 抽出的单一职责类（SRP）：把埋点字段与日志格式
 * 从合成主路径分离，合成代码不再被埋点字段污染。会话级单例够用：
 * speakMutex 保证同一时刻只有一条朗读链，新链 [begin] 会重置全部字段。
 *
 * 不再靠 awaitWatermark 的 head=0 反推延迟，直接用时间戳量化。
 */
internal class FirstAudioTrace {
    /** 用户点击朗读的时刻（ms）；由 [begin] 在 UI 线程写入 */
    @Volatile
    private var clickMs: Long = 0L
    /** 首帧 PCM 入队时刻（ms）；合成回调首次 offer 时写 */
    @Volatile
    private var offerMs: Long = 0L
    /** AudioTrack.play() 时刻（ms）；StreamingTrackPlayer 首次成功时写 */
    @Volatile
    private var playedMs: Long = 0L
    /** 硬件首次消费帧时刻（ms）；awaitWatermark 首次 head>0 时写 */
    @Volatile
    private var headMovedMs: Long = 0L
    /** 本链首块字符数与句子数，首声日志里一并报出 */
    @Volatile
    private var blockChars: Int = 0
    @Volatile
    private var sentenceCount: Int = 0

    /**
     * 在 UI 点击朗读的瞬间调用，标记本条朗读链首声计时的起点。
     * 必须在 speakViaQueue 拿锁之前调用——首声延迟含等锁时间。
     * 幂等重置：连点会重置起点，与 speakViaQueue 取消旧 job 的语义一致。
     */
    fun begin() {
        clickMs = System.currentTimeMillis()
        offerMs = 0L
        playedMs = 0L
        headMovedMs = 0L
        blockChars = 0
        sentenceCount = 0
    }

    /** 是否已开始本链计时（用于判断是否需要补全 sentenceCount 等） */
    fun isStarted(): Boolean = clickMs != 0L

    /** 是否尚未记录首帧 offer（用于合成回调首次 offer 时写一次） */
    fun shouldRecordOffer(): Boolean = offerMs == 0L && clickMs != 0L

    /** 记录首帧 PCM 入队时刻与首块字符数 */
    fun recordOffer(blockLength: Int) {
        if (shouldRecordOffer()) {
            offerMs = System.currentTimeMillis()
            blockChars = blockLength
        }
    }

    /** 补全本链句子数（click 瞬间尚未切句，进链时才有） */
    fun fillSentenceCountIfEmpty(count: Int) {
        if (sentenceCount == 0 && clickMs != 0L) {
            sentenceCount = count
        }
    }

    /** 记录 AudioTrack.play() 时刻（StreamingTrackPlayer 首次成功时） */
    fun recordPlayed() {
        if (playedMs == 0L && clickMs != 0L) {
            playedMs = System.currentTimeMillis()
        }
    }

    /** 记录硬件首次消费帧时刻并输出完整首声日志 */
    fun recordHeadMovedAndLog() {
        if (headMovedMs == 0L && clickMs != 0L) {
            headMovedMs = System.currentTimeMillis()
            // 完整首声日志：四段耗时一条，量化首声延迟
            // click→firstOffer=合成+G2P，→trackPlayed=预缓冲攒够+建轨，
            // →headMoved=硬件开始消费；blockChars/sentences 标首块规模
            Log.i(
                TAG,
                "TTS first-audio: click→firstOffer=${offerMs - clickMs}ms, " +
                    "→trackPlayed=${playedMs - clickMs}ms, " +
                    "→headMoved=${headMovedMs - clickMs}ms, " +
                    "blockChars=$blockChars, sentences=$sentenceCount",
            )
        }
    }

    companion object {
        private const val TAG = "EmbeddedTtsEngine"
    }
}
