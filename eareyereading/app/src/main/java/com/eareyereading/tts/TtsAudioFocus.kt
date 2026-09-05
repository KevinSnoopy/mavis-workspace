package com.eareyereading.tts

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/** 日志 tag 与引擎一致，便于 logcat 统一过滤。 */
private const val TAG = "EmbeddedTtsEngine"

/**
 * TTS 音频焦点控制器：申请/归还 TRANSIENT_MAY_DUCK 焦点。
 *
 * 用 TRANSIENT_MAY_DUCK：朗读期间让其他音频让路，结束后自动恢复。
 * 焦点丢失（电话/闹钟/其他媒体抢焦点）时回调 [onFocusLost]——引擎侧
 * 先发射外部停止信号再 stop()：同一 Main 调度器上 FIFO，UI 层 collect
 * 先把播放标志清零，循环播放驱动才不会在 stop() 取消当前句后又推进
 * 到下一段继续压着通话读。
 */
internal class TtsAudioFocusController(
    private val audioManager: AudioManager?,
    audioAttributes: AudioAttributes,
    private val onFocusLost: () -> Unit,
) {
    @Volatile
    private var audioFocusHeld = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "audio focus lost ($change), stopping playback")
                audioFocusHeld = false
                onFocusLost()
            }
            else -> { /* GAIN/DUCK 无需响应：我们 duck 别人，别人 duck 我们不影响朗读 */ }
        }
    }

    /**
     * 焦点请求（API 26+ 新 API）：旧版 requestAudioFocus(listener, STREAM_MUSIC,
     * gain) 走 legacy stream 焦点路径，与无 streamType 的 AudioTrack 属性不一致，
     * MIUI 焦点状态机在此错配下行为不可预期（deprecated 警告即源于此）。
     */
    private val focusRequest: AudioFocusRequest = AudioFocusRequest
        .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    fun requestIfNeeded() {
        if (audioFocusHeld) return
        val am = audioManager ?: return
        try {
            val result = am.requestAudioFocus(focusRequest)
            // 只在真正拿到焦点时置位：系统拒给焦点（通话中）时若照样置 true，
            // 一来 abandon 会归还我们没持有的焦点，二来"未持焦点"语义丢失
            audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!audioFocusHeld) {
                Log.w(TAG, "audio focus request denied (result=$result), playing without focus")
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestAudioFocus failed", e)
        }
    }

    fun abandonIfHeld() {
        if (!audioFocusHeld) return
        audioFocusHeld = false
        val am = audioManager ?: return
        try {
            am.abandonAudioFocusRequest(focusRequest)
        } catch (e: Exception) {
            Log.w(TAG, "abandonAudioFocus failed", e)
        }
    }
}
