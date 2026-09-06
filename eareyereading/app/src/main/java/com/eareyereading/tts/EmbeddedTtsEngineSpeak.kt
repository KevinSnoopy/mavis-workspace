package com.eareyereading.tts

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EmbeddedTtsEngine 的朗读队列扩展（沿用 TtsModelDownloader.kt 的
 * extension-file 惯例）：doSpeakQueueLocked 是引擎最重的一块——
 * 流式播放编排、预合成缓存消费、句完成水位监视、熔断器。
 * 从引擎类抽出后，引擎主文件只剩生命周期与并发语义。
 */
private const val TAG = "EmbeddedTtsEngine"

/** 熔断阈值：连续合成失败 3 块判定模型损坏，中止整链并报失败 */
private const val CIRCUIT_BREAK_FAILURES = 3

/**
 * 句链合成+流式播放主体（speakViaQueue 持 speakMutex 后调用）。
 *
 * 流程：文本预处理分块 → 逐块 generate（native 按句回调出声）→
 * 预合成缓存命中则直接播 → 等最后水位排空（音频真正播完）本链才算结束。
 *
 * @param rawSentences 原始句子列表（引擎内部按当前模型做预处理再切分）
 * @param speed 语速倍率，1.0 = 正常
 * @param onSentenceDone 第 i 句音频播完时回调（IO 线程触发，单生产者保序；
 *        调用方自行切回主线程）
 */
internal suspend fun EmbeddedTtsEngine.doSpeakQueueLocked(
    rawSentences: List<String>,
    speed: Float,
    onSentenceDone: ((Int) -> Unit)?,
): Boolean {
    val currentTts = tts ?: run {
        Log.w(TAG, "speak() called but tts not initialized")
        return false
    }
    if (rawSentences.isEmpty()) return true

    // 首声埋点：补全本链句子数（click 瞬间尚未切句，进链时才有）
    firstAudioTrace.noteSentenceCount(rawSentences.size)

    val speakJob = kotlin.coroutines.coroutineContext[Job]
    isPlaying.set(true)
    // 流式播放器：整条链共用一条 AudioTrack，边合成边写边播
    val player = StreamingTrackPlayer(
        sampleRate = currentTts.sampleRate(),
        audioManager = audioManager,
        audioAttributes = playbackAudioAttributes,
        trackSlot = trackSlot,
        requestAudioFocus = audioFocus::requestIfNeeded,
        onFirstTrackPlayed = firstAudioTrace::recordTrackPlayed,
        onFirstHeadMoved = firstAudioTrace::recordHeadMoved,
    )
    var circuitBroken = false
    try {
        coroutineScope {
            // 文本预处理（模型相关）：
            // - Piper：把 OOV 字符替换成可发音等价物（数字→英文单词、CJK→占位符），
            //   否则裸文本进 generate() 会触发 native 段错误 (SIGSEGV)。
            // - Kokoro：双语模型自带中英 G2P（espeak-ng + 中文词典 + ruleFst 数字
            //   归一化），只做空白归一化——Piper 专用的替换反而会破坏中文文本
            val isKokoro = currentModelIsKokoro
            // Kokoro 音色：用户在设置页选择的 sid（Piper 单说话人恒为 0）。
            // 每条链入队时读取一次偏好：朗读中途切音色，下一条链生效
            val sid = if (isKokoro) getSelectedSid() else 0
            Log.i(TAG, "Embedded TTS speak queue: sentences=${rawSentences.size}, sid=$sid, streaming")
            // 句完成水位队列 + 单监视协程：句 i 的全部帧被硬件消费完（水位到达）
            // 才回调 onSentenceDone(i)，既不超前（音频没播完就推进）也不滞后；
            // 单消费者保证回调顺序与句子顺序一致
            val pendingWatermarks = ConcurrentLinkedQueue<Pair<Long, Int>>()
            val generationDone = AtomicBoolean(false)
            if (onSentenceDone != null) {
                launch {
                    while (true) {
                        delay(20)
                        if (player.isTrackTakenOver()) {
                            // 轨道已被 stop() 接管释放：未播完的句不再回调
                            //（父协程随即被取消，监视协程一并退出）
                            pendingWatermarks.clear()
                            return@launch
                        }
                        val head = player.currentHead()
                        if (head >= 0L) {
                            while (true) {
                                val next = pendingWatermarks.peek() ?: break
                                if (head < next.first) break
                                pendingWatermarks.poll()
                                onSentenceDone(next.second)
                            }
                        }
                        if (generationDone.get() && pendingWatermarks.isEmpty()) return@launch
                    }
                }
            }
            // 熔断器：模型损坏时每句都会抛异常，旧实现逐句"跳过"后照常返回成功，
            // 上层会"静音朗读"完整本书并推进进度。连续失败 3 句直接中止并报失败
            var consecutiveFailures = 0
            try {
                val blocks = TtsBlockChunker.merge(rawSentences) { raw ->
                    if (isKokoro) preprocessForTtsLight(raw) else preprocessForTts(raw)
                }
                for ((idx, block) in blocks.withIndex()) {
                    val blockText = block.text
                    if (blockText.isBlank()) continue
                    // 每块之前检查协程是否已被取消（stop() 调用）
                    yield()
                    if (speakJob?.isActive == false) {
                        throw CancellationException("stop() requested")
                    }
                    val framesBeforeBlock = player.framesOffered
                    // 预合成缓存命中（单词弹窗 selectWord 时后台预合成）：
                    // 跳过 generate 直接播缓存 PCM——Kokoro 每次 generate 有
                    // ~2s 固定开销（与文本长度无关），单词现场合成必然卡
                    val cachedPcm = pcmCache.get(blockText, sid, speed)
                    if (cachedPcm != null) {
                        if (speakJob?.isActive != false) {
                            // 首声埋点：缓存命中路径同样记录 firstOffer
                            firstAudioTrace.recordFirstOffer(blockText.length)
                            player.offer(cachedPcm)
                            Log.i(
                                TAG,
                                "Embedded TTS block from cache: len=${blockText.length}, " +
                                    "samples=${cachedPcm.size}",
                            )
                        }
                    } else {
                        // G2P+回调粒度埋点：拆解首块 10.8s 的归因（G2P vs 推理 vs 回调粒度）
                        val blockGenStartMs = System.currentTimeMillis()
                        var firstCallbackMs = 0L
                        var lastCallbackMs = 0L
                        var callbackCount = 0
                        var firstCallbackSamples = 0
                        val audio = try {
                            currentTts.generateWithCallback(blockText, sid = sid, speed = speed) { samples ->
                                // 返回 1 继续合成；协程已取消时返回 0 让 native 立即中止
                                if (speakJob?.isActive == false) 0 else {
                                    callbackCount++
                                    if (callbackCount == 1) {
                                        firstCallbackMs = System.currentTimeMillis()
                                        firstCallbackSamples = samples.size
                                    }
                                    lastCallbackMs = System.currentTimeMillis()
                                    // 首声埋点：首次 offer 时记录 firstOffer 时间戳
                                    firstAudioTrace.recordFirstOffer(blockText.length)
                                    player.offer(samples)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // 单块 generate 崩溃（如 native G2P bug）：跳过该块，继续
                            Log.e(TAG, "block generate failed, skipping: '${blockText.take(60)}'", e)
                            null
                        }
                        // G2P+回调粒度汇总日志（仅首块打，避免噪声）
                        if (firstAudioTrace.hasFirstOffer() && callbackCount > 0) {
                            val g2pMs = firstCallbackMs - blockGenStartMs
                            val synthMs = lastCallbackMs - firstCallbackMs
                            Log.i(
                                TAG,
                                "TTS block-decomp: g2p+firstCallback=${g2pMs}ms, " +
                                    "synth=${synthMs}ms, callbackCount=$callbackCount, " +
                                    "firstCallbackSamples=$firstCallbackSamples, " +
                                    "blockChars=${blockText.length}",
                            )
                        }
                        // 兜底与日志均以 framesOffered（含预缓冲 pending 里的帧）为基准：
                        // 预缓冲期间 framesWritten 恒为 0，若用它判断"一帧未写"会把
                        // 首块音频在兜底路径重复 offer 一遍（声音重叠）
                        // 链已被 stop() 取消时必须禁用兜底：回调被取消检查挡住
                        //（返回 0 中止合成）并不代表"JNI 回调静默失效"，此时整段
                        // 补写会让一条已停止的链在数秒后突然出声——真机表现为
                        // "点了停止，几秒后突然又开始读"，且与用户随后启动的新链
                        // 叠音（2026-09-05 顶栏两播报按钮"冲突"的机理）
                        if (audio != null && player.framesOffered == framesBeforeBlock &&
                            audio.samples.isNotEmpty() && speakJob?.isActive != false
                        ) {
                            // 兜底：JNI 回调静默失效（一帧未写）时整段补写，保证有声
                            player.offer(audio.samples)
                        }
                    }
                    if (player.framesOffered > framesBeforeBlock) {
                        // 真实合成成功 = 本模型的首次推理开销已被消化，
                        // 与 warmUp() 的置位语义一致（幂等，@Volatile 写）
                        warmedUpModelId = currentModelName
                        Log.i(
                            TAG,
                            "Embedded TTS block queued: idx=$idx, len=${blockText.length}, " +
                                "samples=${player.framesOffered - framesBeforeBlock}, " +
                                "totalFrames=${player.framesOffered}",
                        )
                    }
                    // 句完成水位：用 block 边界作为水位。onSentenceDone 回调
                    // block 内最后一个句子的索引（block 可能含多个原句）。
                    // 精确的句级高亮需要 native 回调报告句边界，当前按 block 粒度。
                    if (onSentenceDone != null && player.framesOffered > framesBeforeBlock) {
                        consecutiveFailures = 0
                        if (block.lastSentenceIndex >= 0) {
                            pendingWatermarks.add(player.framesOffered to block.lastSentenceIndex)
                        }
                    } else if (player.framesOffered == framesBeforeBlock) {
                        consecutiveFailures++
                        if (consecutiveFailures >= CIRCUIT_BREAK_FAILURES) {
                            Log.e(
                                TAG,
                                "$CIRCUIT_BREAK_FAILURES consecutive block failures — aborting speak (model likely broken)",
                            )
                            _state.value = EngineState.FAILED("语音合成连续失败，模型可能已损坏")
                            circuitBroken = true
                            return@coroutineScope
                        }
                    }
                }
                // 整条链音频总量可能不足预缓冲阈值（如单句短文本）：此时全部帧还在
                // pending 队列、轨道从未开播——冲刷出去并开播，否则最后一段静音丢失。
                // 必须在 awaitWatermark 之前：flush 后 pending 帧才计入 framesWritten，
                // 排水水位才是完整帧数。
                // 先做协作式取消检查（与循环内每句前的检查同级）：stop() 之后
                // 不能再建新轨道开播残留音频
                if (speakJob?.isActive == false) {
                    throw CancellationException("stop() requested")
                }
                player.flushPendingAndPlay()
                // 全部生成完毕：等最后水位排空（音频真正播完）本链才算结束。
                // onSentenceDone 为 null 时监视协程不存在，这里是唯一的排水口
                player.awaitWatermark(player.framesWritten)
            } finally {
                generationDone.set(true)
            }
        }
    } catch (e: CancellationException) {
        // 播放等待的 delay() 会在协程取消时抛出 CancellationException；
        // 不能吞掉，否则 withContext 不会正确传播取消信号。
        isPlaying.set(false)
        player.releaseIfCurrent()
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "speak failed", e)
        isPlaying.set(false)
        player.releaseIfCurrent()
        return false
    }
    isPlaying.set(false)
    player.releaseIfCurrent()
    return !circuitBroken
}
