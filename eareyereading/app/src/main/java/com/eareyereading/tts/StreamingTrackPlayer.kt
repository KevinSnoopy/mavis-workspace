package com.eareyereading.tts

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * 流式播放器：一条朗读链专用一条 MODE_STREAM AudioTrack，边合成边写边播；
 * 预缓冲攒够才建轨开播，含 MIUI mixer 不消费的自愈重试。轨道槽与音频焦点
 * 由引擎注入（stop() 需能随时接管释放）。
 */
/** 日志 tag 与引擎一致，便于 logcat 统一过滤。 */
private const val TAG = "EmbeddedTtsEngine"

/**
 * 流式朗读的 AudioTrack 环形缓冲（秒）：大于典型单句音频时长，
 * 句间合成抖动不产生断音；合成快于播放时阻塞写自然形成背压。
 */
private const val STREAM_BUFFER_SECONDS = 4

/**
 * 开播前预缓冲（秒）：首次 offer 攒够约 0.8 秒 PCM 才建轨并 play()。
 *
 * 为什么需要：旧实现第一段采样一到就 play()，AudioTrack 缓冲垫≈0，
 * 合成稍有抖动（如系统 binder 停顿/线程调度）就耗尽缓冲触发 underrun，
 * AudioTrack 被系统禁用后 restartIfDisabled 重启还伴随百毫秒级 binder
 * 停顿，听感为卡顿/长停顿。0.8 秒的权衡：远小于当前 6 秒级的首块合成
 * 时间（首声延迟几乎不变），又足够吸收一次秒级合成抖动。
 */
private const val PREBUFFER_SECONDS = 0.8f

/**
 * AudioTrack 硬件采样率：固定 48000（设备 primary output 原生率）。
 *
 * 为什么不用模型原生率（Kokoro 24000 / Piper 22050）：2026-09-05 真机
 * 诊断——MIUI（afSampleRate=48000）上 24kHz 的流写入成功、start
 * 成功、自报 PLAYING，但 mixer 恒不消费（playbackHeadPosition=0，
 * 扬声器无声；pcmPeak/musicVol 均正常）。疑为 AudioPolicy 把非
 * 原生率流路由到不支持重采样的 direct/low-power 输出线程
 * （audio_lowpower_app_list.xml 即该策略配置文件）。统一上采样到
 * 48k 建轨，强制走 primary mixer 原生路径。
 */
private const val TRACK_SAMPLE_RATE = 48000

/** 引擎与播放器共享的"当前轨道"槽：stop() 经此接管并释放正在播的流。 */
internal class AudioTrackSlot {
    val lock = Any()
    var track: AudioTrack? = null
}

/**
 * 流式播放器：一条朗读链专用一条 MODE_STREAM AudioTrack。
 *
 * sherpa-onnx 的 generateWithCallback 每合成完一小段（内部按句）就回调一次，
 * 采样转 16-bit PCM 后直接写入流式 AudioTrack 立即出声——后续小段还在
 * 合成时本段已在播放，把"按下朗听到听见声音"的等待从整段合成时间缩短到
 * 首小段合成时间。
 *
 * 预缓冲：首次 [offer] 不立即开播，先把采样攒进 [pending] 队列，累计达到
 * [PREBUFFER_SECONDS]（约 0.8 秒）才建轨、把 pending 一次性写入硬件并 play()
 * ——旧实现零缓冲垫开播，合成稍有抖动就 underrun（AudioTrack 被系统禁用 +
 * restartIfDisabled 重启伴随百毫秒级 binder 停顿，听感为卡顿）。0.8 秒远小于
 * 首块合成时间（数秒级），首声延迟几乎不受影响。
 *
 * 线程模型：[offer] 由 JNI 回调在合成线程（与 generateWithCallback 同线程）
 * 调用，阻塞写提供天然背压（缓冲写满时等硬件消费，合成永不跑飞内存）；
 * [awaitWatermark]/[currentHead] 由水位监视协程轮询。与 stop() 的互斥靠
 * trackSlot.track 字段 + trackSlot.lock：stop() 释放并置空字段后，写失败/
 * 水位检查发现轨道已死并快速中止。pending 队列同样仅合成线程读写，
 * 无需加锁；stop()/取消路径下随 player 整体丢弃，不泄漏。
 */
// isSpeakerphoneOn 为 deprecated 但有意保留：setCommunicationDevice 等
// 新 API 在 MIUI/HyperOS 等深度定制 ROM 上不生效，扬声器强制路由的
// workaround 依赖旧 API（2026-09-05 真机日志定案，见 awaitWatermark 注释）。
@Suppress("DEPRECATION")
internal class StreamingTrackPlayer(
    private val sampleRate: Int,
    private val audioManager: AudioManager?,
    private val audioAttributes: AudioAttributes,
    private val trackSlot: AudioTrackSlot,
    private val requestAudioFocus: () -> Unit,
) {

    /** 已写入硬件的帧数（水位基准）；仅合成线程写，监视协程读快照 */
    var framesWritten: Long = 0L
        private set

    /**
     * 已接受的帧总数（含仍在预缓冲 [pending] 队列、尚未写入硬件的帧）。
     * 仅合成线程写。chunk 兜底判断与句完成水位用它而非 [framesWritten]：
     * 预缓冲期间 framesWritten 恒为 0，用它会把首块误判为"一帧未写"
     * （兜底路径重复 offer → 声音重叠）、把首句水位提前满足（音频没播
     * 就回调句完成）
     */
    var framesOffered: Long = 0L
        private set

    private var track: AudioTrack? = null

    /** 轨道已损坏（构建/播放/写入失败）：后续 offer 拒绝 */
    @Volatile
    private var broken = false

    /**
     * 预缓冲 pending 队列：开播前攒够 [prebufferFrames] 的 16-bit PCM。
     * 仅合成线程（offer/drain）读写，无需加锁；stop()/取消路径下随
     * player 整体丢弃，不存在泄漏
     */
    private val pending = ArrayDeque<ShortArray>()

    /** pending 队列里尚未写入硬件的帧数 */
    private var pendingFrames = 0L

    /** 开播前的预缓冲目标帧数（约 [PREBUFFER_SECONDS] 秒音频，按轨道采样率计） */
    private val prebufferFrames = (TRACK_SAMPLE_RATE * PREBUFFER_SECONDS).toLong()

    /**
     * 源采样率（模型输出，[sampleRate]）→ 轨道采样率（[TRACK_SAMPLE_RATE]）
     * 的线性插值上采样。见 TRACK_SAMPLE_RATE 注释：24kHz 流在 MIUI 上
     * mixer 不消费，必须按设备原生率 48k 建轨。速率相等时原样返回。
     */
    private fun upsampleToTrackRate(src: FloatArray): FloatArray {
        if (sampleRate == TRACK_SAMPLE_RATE) return src
        val ratio = TRACK_SAMPLE_RATE.toDouble() / sampleRate
        val dstLen = (src.size * ratio).toInt()
        if (dstLen <= 0) return FloatArray(0)
        val dst = FloatArray(dstLen)
        for (i in 0 until dstLen) {
            val pos = i / ratio
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = if (idx < src.size) src[idx] else 0f
            val b = if (idx + 1 < src.size) src[idx + 1] else a
            dst[i] = a + (b - a) * frac
        }
        return dst
    }

    /**
     * 写入一段采样（[-1,1] Float → 16-bit PCM）。在 JNI 回调内调用，
     * 绝不能抛异常（会穿过 JNI 边界变成 pending exception 破坏后续调用）。
     * @return 1 继续合成；0 立即中止合成（轨道坏/写入失败）
     */
    fun offer(samples: FloatArray): Int {
        if (broken || samples.isEmpty()) return 1
        val resampled = upsampleToTrackRate(samples)
        val pcm16 = ShortArray(resampled.size) { i ->
            (resampled[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        framesOffered += pcm16.size
        val t = track
        if (t != null) {
            // 已开播：直接写硬件（pending 必已清空，单写者不变式）
            return if (writePcm(t, pcm16)) 1 else 0
        }
        // 未开播：先入 pending 攒预缓冲
        pending.addLast(pcm16)
        pendingFrames += pcm16.size
        if (pendingFrames < prebufferFrames) return 1
        // 攒够预缓冲：建轨 → play() → pending 一次性写入硬件。
        // 顺序说明（2026-09-05 19:34 日志定案）：PERFORMANCE_MODE_LATENCY
        // 下 AudioTrack 走 fast track 路径（AUDIO_OUTPUT_FLAG_FAST），
        // fast track FIFO 很小，play() 前写数据会阻塞/只写少量到 FIFO，
        // play() 后只播 FIFO 里的数据就 underrun（head=16704/36998，
        // 只播 45%）。先 play() 让硬件开始消费，再写数据，write() 的
        // 阻塞由硬件消费驱动，数据能持续流入。
        logPlaybackDiagnostics()
        val newTrack = buildTrack() ?: return 0
        if (startTrack(newTrack) == 0) return 0
        return if (drainPending(newTrack)) 1 else 0
    }

    /**
     * 整条链生成完毕时调用（[doSpeakQueueLocked] 的 for 循环结束后、
     * awaitWatermark 之前）：整链音频总量不足预缓冲阈值时全部帧还在
     * pending 里、轨道从未开播——冲刷出去并开播，否则最后一段静音丢失。
     * 已开播（pending 必空）或整链无音频时是空操作。
     */
    fun flushPendingAndPlay() {
        if (broken) {
            pending.clear()
            pendingFrames = 0L
            return
        }
        if (pendingFrames == 0L) return
        logPlaybackDiagnostics()
        val newTrack = buildTrack() ?: return
        if (!drainPending(newTrack)) return
        startTrack(newTrack)
    }

    /**
     * 播放诊断（2026-09-05 "AudioTrack start 成功但扬声器无声"定位用）：
     * 一次开播打一条，三个字段各自排除一类根因——
     *   peak=0        → PCM 数据本身是静音（NaN/全零转换结果），合成/缓存层问题；
     *   musicVol=0    → 媒体音量为 0（音量键在无媒体播放时调的是铃声音量）；
     *   以上正常但 awaitWatermark 的 head 不动 → 硬件不消费（焦点/路由/系统策略）。
     */
    private fun logPlaybackDiagnostics() {
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
                "pendingFrames=$pendingFrames, srcRate=$sampleRate, trackRate=$TRACK_SAMPLE_RATE",
        )
    }

    /** 当前硬件已播帧数；轨道未建/已被外部接管（stop()）返回 -1。 */
    fun currentHead(): Long {
        val t = track ?: return -1L
        return synchronized(trackSlot.lock) {
            if (trackSlot.track !== t) -1L else t.playbackHeadPosition.toLong()
        }
    }

    /**
     * 轨道是否已被外部接管（stop() 释放了 trackSlot.track 字段）。
     * 轨道尚未建立时返回 false——生成还在进行，稍后会有音频写入，
     * 水位监视必须继续等待而不是退出。
     */
    fun isTrackTakenOver(): Boolean {
        val t = track ?: return false
        return synchronized(trackSlot.lock) { trackSlot.track !== t }
    }

    /**
     * 等待水位（已播帧数 ≥ [frames]）。轨道被外部接管（stop() 释放）时
     * 返回 false；自然排空返回 true。
     */
    suspend fun awaitWatermark(frames: Long): Boolean {
        val t = track ?: return frames <= 0L
        var lastLogMs = 0L
        // MIUI/HyperOS workaround：play() 后 mixer 可能不消费（head 恒 0）。
        // 检测到该现象持续 >1.5s 时重新 play() 一次——实测部分 MIUI 版本
        // 二次 play 能激活 mixer 消费（首次 play 被低功耗策略拦截）。
        // 重试上限 2 次，避免无限循环；每次重试间隔 1.5s。
        var stillSinceMs = System.currentTimeMillis()
        var replayAttempts = 0
        val maxReplayAttempts = 2
        val replayThresholdMs = 1500L
        while (true) {
            kotlinx.coroutines.delay(20)
            val head = synchronized(trackSlot.lock) {
                if (trackSlot.track !== t) return false
                t.playbackHeadPosition.toLong()
            }
            // 播放诊断（临时）：head 不增长 = 硬件不消费（焦点/路由/音量问题），
            // 与 pcmPeak/musicVol 组合可三分定位"start 成功但无声"
            val now = System.currentTimeMillis()
            if (now - lastLogMs > 500) {
                lastLogMs = now
                Log.d(TAG, "awaitWatermark: head=$head/$frames, playState=${t.playState}")
            }
            if (head >= frames) return true
            // head 增长说明 mixer 已开始消费，重置计时
            if (head > 0) {
                stillSinceMs = now
            } else if (now - stillSinceMs > replayThresholdMs && replayAttempts < maxReplayAttempts) {
                replayAttempts++
                Log.w(TAG, "awaitWatermark: head stuck at 0 for ${now - stillSinceMs}ms, replay attempt $replayAttempts/$maxReplayAttempts")
                // 首次重试：尝试强制切扬声器（绕过蓝牙 A2DP 路由）
                // 2026-09-05 18:02 日志定案：蓝牙手表 A2DP 连接但无扬声器/休眠时，
                // mixer 恒不消费。setSpeakerphoneOn(true) 在 MODE_NORMAL 下可能
                // 无效，但部分 MIUI 版本会响应并切到扬声器。
                if (replayAttempts == 1) {
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
                synchronized(trackSlot.lock) {
                    if (trackSlot.track === t && t.state == AudioTrack.STATE_INITIALIZED) {
                        try {
                            // 不 flush：保留已写入的音频数据。
                            // pause→play 触发 AudioFlinger 重新挂载这条流到 mixer，
                            // 部分 MIUI 版本首次 play 被低功耗策略拦截，二次能激活。
                            t.pause()
                            t.play()
                            stillSinceMs = System.currentTimeMillis()
                        } catch (e: Exception) {
                            Log.w(TAG, "replay failed", e)
                        }
                    }
                }
            }
        }
    }

    /** 释放轨道（仅当仍是当前 trackSlot.track，避免与 stop() 双重释放）。 */
    fun releaseIfCurrent() {
        val t = track ?: return
        synchronized(trackSlot.lock) {
            if (trackSlot.track === t) {
                trackSlot.track = null
                // 欠载诊断（API 24+）：硬件侧欠载计数在 release 前读取。
                // >0 说明播放期缓冲仍被击穿，真机可据此加大 PREBUFFER_SECONDS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val underruns = t.underrunCount
                    if (underruns > 0) {
                        Log.w(TAG, "AudioTrack underrun count: $underruns over $framesWritten frames")
                    }
                }
                try {
                    if (t.state == AudioTrack.STATE_INITIALIZED) {
                        t.pause()
                        t.flush()
                    }
                    t.release()
                } catch (_: Exception) {}
                // 恢复音频模式：自愈逻辑可能设了 MODE_IN_COMMUNICATION，
                // 不恢复会影响后续系统音频（通话/铃声路由异常）
                try {
                    audioManager?.let { am ->
                        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                            am.mode = AudioManager.MODE_NORMAL
                            Log.i(TAG, "restored audio mode to NORMAL")
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        track = null
    }

    /**
     * 阻塞写一段 PCM 到硬件轨道。失败（轨道被 stop() 释放等）置 broken
     * 并返回 false，调用方中止合成、交由上层按句跳过。
     */
    private fun writePcm(t: AudioTrack, pcm16: ShortArray): Boolean {
        var offset = 0
        while (offset < pcm16.size) {
            val written = t.write(pcm16, offset, pcm16.size - offset)
            if (written < 0) {
                Log.w(TAG, "stream write failed: $written")
                broken = true
                return false
            }
            offset += written
            framesWritten += written
        }
        return true
    }

    /** 把 pending 队列一次性写入硬件；写失败返回 false（broken 已置位）。 */
    private fun drainPending(newTrack: AudioTrack): Boolean {
        while (pending.isNotEmpty()) {
            val chunk = pending.removeFirst()
            pendingFrames -= chunk.size
            if (!writePcm(newTrack, chunk)) return false
        }
        return true
    }

    /**
     * play() 建好的轨道。从旧的 buildAndStartTrack 拆出：play 必须发生在
     * pending 数据写入硬件之后（play 时缓冲内已有 ≥预缓冲量的音频）。
     * @return 1 成功；0 失败（broken 已置位，合成中止）
     */
    private fun startTrack(newTrack: AudioTrack): Int {
        return try {
            newTrack.play()
            1
        } catch (e: Exception) {
            Log.w(TAG, "stream track play failed", e)
            releaseIfCurrent()
            broken = true
            0
        }
    }

    /** 构建并注册轨道（不 play）：stop() 需能通过 trackSlot.track 字段接管。 */
    private fun buildTrack(): AudioTrack? {
        if (broken) return null
        val newTrack = try {
            val minBuffer = AudioTrack.getMinBufferSize(
                TRACK_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val targetBytes = TRACK_SAMPLE_RATE * 2 * STREAM_BUFFER_SECONDS
            val bufferSize = if (minBuffer > 0) maxOf(minBuffer, targetBytes) else targetBytes
            val builder = AudioTrack.Builder()
                // 与焦点请求共用同一 AudioAttributes（见 audioAttributes
                // 注释：SPEECH 内容类型在 MIUI 上会被语音通道策略静默）
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(TRACK_SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
            // PERFORMANCE_MODE_LATENCY（API 26+）：强制走低延迟路径，
            // 绕过 MIUI 低功耗策略（audio_lowpower_app_list.xml 加载失败时
            // STREAM 模式被路由到不消费的输出线程）。2026-09-05 19:01 日志：
            // 蓝牙已断开、路由到扬声器、pcmPeak/musicVol/mode 均正常，
            // 但 head 恒 0——mixer 不消费 STREAM 流。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // PERFORMANCE_MODE_LATENCY = 1（API 26+）：强制走低延迟路径
                builder.setPerformanceMode(1)
            }
            builder.build()
        } catch (e: Exception) {
            Log.w(TAG, "stream track build failed", e)
            broken = true
            return null
        }
        // 注册进 trackSlot.track：stop() 才能立刻停掉正在播的流
        synchronized(trackSlot.lock) { trackSlot.track = newTrack }
        track = newTrack
        // 播放前申请音频焦点：让音乐/播客让路，朗读结束/停止后归还
        requestAudioFocus()
        return newTrack
    }
}
