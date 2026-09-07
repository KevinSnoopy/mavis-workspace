package com.eareyereading.tts

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * 流式播放诊断与 MIUI/HyperOS mixer 自愈逻辑。
 *
 * 从 [StreamingTrackPlayer] 按 SRP 抽出：播放诊断日志与 mixer 不消费的自愈重试
 * 是独立的运维职责，与核心播放流程（offer/write/build）解耦。
 */
internal object StreamingTrackDiagnostics {

    private const val TAG = "EmbeddedTtsEngine"

    /**
     * 播放诊断（2026-09-05 "AudioTrack start 成功但扬声器无声"定位用）：
     * 一次开播打一条，三个字段各自排除一类根因——
     *   peak=0        → PCM 数据本身是静音（NaN/全零转换结果），合成/缓存层问题；
     *   musicVol=0    → 媒体音量为 0（音量键在无媒体播放时调的是铃声音量）；
     *   以上正常但 awaitWatermark 的 head 不动 → 硬件不消费（焦点/路由/系统策略）。
     */
    fun logPlaybackDiagnostics(
        pending: ArrayDeque<ShortArray>,
        pendingFrames: Long,
        audioManager: AudioManager?,
        sampleRate: Int,
        trackSampleRate: Int,
    ) {
        var peak = 0
        for (chunk in pending) {
            for (s in chunk) {
                val v = kotlin.math.abs(s.toInt())
                if (v > peak) peak = v
            }
        }
        val vol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        val volMax = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
        // mode（0=NORMAL/1=RINGTONE/2=IN_CALL/3=IN_COMMUNICATION）：后台挂着
        // 微信语音/电话时媒体流会被系统静音或路由听筒——head=0 无声的
        // 高频环境根因；outputs 看实际路由（是否真到扬声器）
        val mode = audioManager?.mode ?: -1
        val speakerOn = audioManager?.isSpeakerphoneOn
        val musicActive = audioManager?.isMusicActive
        val outputDevices = try {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val outputs = outputDevices.joinToString { "${it.type}:${it.productName}" }
        // A2DP/蓝牙设备路由检测：type 7=A2DP, 8=SCO, 26=HEARING_AID, 27=BLE_SPEAKER
        // 蓝牙手表（如华为 Watch 3 Pro）连着但无扬声器/休眠时，AudioTrack 写入
        // 成功、PLAYING，但 mixer 恒不消费（head=0）——2026-09-05 18:02 日志定案
        val hasBtOutput = outputDevices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_HEARING_AID ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        if (hasBtOutput) {
            Log.w(
                TAG,
                "TTS routed to Bluetooth device (likely not consuming): " +
                    "outputs=[$outputs]. If head stays 0, will try forcing speaker.",
            )
        }
        Log.i(
            TAG,
            "TTS playback diag: pcmPeak=$peak, musicVol=$vol/$volMax, mode=$mode, " +
                "speakerOn=$speakerOn, musicActive=$musicActive, outputs=[$outputs], " +
                "pendingFrames=$pendingFrames, srcRate=$sampleRate, trackRate=$trackSampleRate",
        )
    }

    /**
     * MIUI/HyperOS mixer 不消费的自愈重试。
     *
     * 检测到 head 恒 0 持续超过阈值时，按重试次数执行不同的 workaround：
     * - 第 1 次：强制切扬声器（绕过蓝牙 A2DP 路由）
     * - 第 2 次：MODE_IN_COMMUNICATION + setSpeakerphoneOn 组合
     */
    fun applyMixerWorkaround(
        replayAttempts: Int,
        audioManager: AudioManager?,
    ) {
        if (replayAttempts == 1) {
            // 首次重试：尝试强制切扬声器（绕过蓝牙 A2DP 路由）
            // 2026-09-05 18:02 日志定案：蓝牙手表 A2DP 连接但无扬声器/休眠时，
            // mixer 恒不消费。setSpeakerphoneOn(true) 在 MODE_NORMAL 下可能
            // 无效，但部分 MIUI 版本会响应并切到扬声器。
            try {
                audioManager?.let { am ->
                    if (!am.isSpeakerphoneOn) {
                        am.isSpeakerphoneOn = true
                        Log.i(TAG, "forced speakerphone on (A2DP workaround)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "setSpeakerphoneOn failed", e)
            }
        } else if (replayAttempts == 2) {
            // 第二次重试：MODE_IN_COMMUNICATION + setSpeakerphoneOn 组合。
            // MODE_NORMAL 下 setSpeakerphoneOn 无效（19:01 日志已证伪），
            // MODE_IN_COMMUNICATION 改变音频路由策略，强制走通信通道+
            // 扬声器，绕过 MIUI 媒体流的低功耗策略。播放结束后在
            // releaseIfCurrent 恢复 MODE_NORMAL。
            try {
                audioManager?.let { am ->
                    if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                        am.mode = AudioManager.MODE_IN_COMMUNICATION
                        Log.i(TAG, "set mode IN_COMMUNICATION (mixer workaround)")
                    }
                    if (!am.isSpeakerphoneOn) {
                        am.isSpeakerphoneOn = true
                        Log.i(TAG, "forced speakerphone on (mode=IN_COMMUNICATION)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "mode/speaker workaround failed", e)
            }
        }
    }
}
