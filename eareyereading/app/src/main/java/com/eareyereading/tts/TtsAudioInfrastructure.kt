package com.eareyereading.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * TTS 音频基础设施：[AudioManager]、播放/焦点共用的 [AudioAttributes]、
 * 轨道槽 [AudioTrackSlot]、外部停止信号流。
 *
 * 从 [EmbeddedTtsEngine] 抽出的单一职责类（SRP）：引擎主类不再直接持有
 * 这些音频基础设施对象，通过本类统一获取。播放轨道与焦点请求共用同一
 * [AudioAttributes]——两处必须严格一致（见 [playbackAudioAttributes] 注释）。
 *
 * @param context 应用上下文
 */
internal class TtsAudioInfrastructure(context: Context) {
    /** 系统音频服务（可能为 null：某些无音频设备的车机/模拟器） */
    val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    /**
     * 播放轨道与焦点请求共用的音频属性（两处必须严格一致）。
     *
     * CONTENT_TYPE_MUSIC 而非 SPEECH（2026-09-05 真机诊断定案）：MIUI/HyperOS
     * 对 SPEECH 内容类型走语音通道特殊策略（与小爱同学/语音识别通道互斥），
     * 实测 USAGE_MEDIA+CONTENT_TYPE_SPEECH 组合下 AudioTrack 写入成功、
     * start 成功、状态 PLAYING，但 mixer 恒不消费（playbackHeadPosition=0），
     * 扬声器完全无声；pcmPeak/musicVol 诊断排除数据与音量因素后锁定于此。
     */
    val playbackAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** 当前播放轨道槽：流式播放器建轨时注册，stop() 经此接管释放 */
    val trackSlot = AudioTrackSlot()

    /**
     * 外部停止信号：音频焦点丢失等系统事件触发。
     * 引擎的 stop() 只能取消"正在出声的那一句"，循环播放是由上层
     *（ReaderViewModel 的 autoRead/speed/rsvp Job）驱动的——它们以
     * uiState 播放标志为闸，焦点丢失后不收闸就会播下一段。
     * UI 层 collect 此流后应调用 stopAllPlayback() 收闸。
     */
    private val _externalStop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val externalStop: SharedFlow<Unit> = _externalStop.asSharedFlow()

    /** 发射外部停止信号（音频焦点丢失时调用） */
    fun emitExternalStop() {
        _externalStop.tryEmit(Unit)
    }
}
