package com.eareyereading.tts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * EmbeddedTtsEngine 的预热/预合成扩展（沿用 TtsModelDownloader.kt 的
 * extension-file 惯例：引擎类保留字段与并发原语，按职责分文件）。
 *
 * 统一的锁语义（组合而非继承地复用引擎的 speakMutex）：
 * tryLock 拿不到（正在朗读）直接放弃——预热/预合成是体验优化，
 * 绝不能反过来阻塞用户的正文朗读。
 */
private const val TAG = "EmbeddedTtsEngine"

/**
 * 推理预热文本（见 [EmbeddedTtsEngine.warmUp]）：长度必须接近真实首块负载
 * （~90 字符 ≈ 4-6 秒音频）。用 "Ok." 这类短句预热时，ONNX Runtime
 * 的 arena 内存池只长到小句规模，真实首句推理仍要触发大额 arena
 * 扩张与物理页缺页，冷启动成本大部分重现（2026-09-05 真机实测：
 * 短句预热后首句出声仍 ~8s）。长句预热把内存池/页表一次性长到
 * 峰值形状，真实首句直接复用。合成出的音频直接丢弃，
 * 不建 AudioTrack、不申请音频焦点。
 */
private const val WARMUP_TEXT =
    "The morning sun rises slowly over the quiet hills, and the birds begin to sing in the trees."

/**
 * 预合成仅面向短文本（单词/短语）：超长文本的每次 generate 固定开销
 * 占比小，缓存价值低且浪费内存。
 */
private const val MAX_PREWARM_CHARS = 40

/**
 * 后台预热：跑一次与真实首块等长的合成并丢弃音频，提前消化
 * ONNX Runtime **首次** generate 的一次性开销（图优化、线程池爬升、
 * arena 内存池扩张与物理页缺页）。
 *
 * **为什么需要**（2026-09-05 真机日志实测，Kokoro int8 / 4 线程）：
 * 首次 generateWithCallback 从入队到攒满 0.8s 预缓冲花了 **10.4 秒**，
 * 而稳态第二块仅 4.2 秒合成 6.5 秒音频（RTF≈0.65）——即首块里约
 * 7-8 秒是纯冷启动开销，全部落在"用户点击朗读后的首声延迟"上。
 * 把这笔开销挪到进书/初始化后的空闲时间，用户点击时引擎已热，
 * 首声延迟从 10s 级降到稳态首块合成时间（约 1-3 秒）。
 *
 * **锁语义**：tryLock 拿不到（正在朗读）直接放弃。拿到锁后开始合成，
 * 但合成期间用户点朗读时，speakViaQueue 会设 warmUpCancelled = true，
 * generate 回调返回 0 中止合成、释放锁，用户请求立即开始——
 * 不让预热阻塞用户 10 秒（2026-09-05 真机实测：warmUp 10s 未完成时
 * 用户点朗读，speak 挂锁等 10s 才出声）。
 */
internal suspend fun EmbeddedTtsEngine.warmUp() = withContext(Dispatchers.IO) {
    val modelId = currentModelName
    if (modelId.isEmpty() || modelId == warmedUpModelId) return@withContext
    if (!speakMutex.tryLock()) return@withContext
    try {
        // 双检：等锁/调度期间引擎可能已被 release() 或换了模型
        val engine = synchronized(this@EmbeddedTtsEngine) { tts } ?: return@withContext
        if (currentModelName != modelId) return@withContext
        val sid = if (currentModelIsKokoro) getSelectedSid() else 0
        val startMs = System.currentTimeMillis()
        warmUpCancelled = false
        try {
            // 与真实朗读同一代码路径（generateWithCallback + sid），
            // 确保 ONNX 会话/内存池/线程池全部被预热。
            // 回调检查 warmUpCancelled：用户点朗读时返回 0 中止合成
            engine.generateWithCallback(WARMUP_TEXT, sid = sid, speed = 1.0f) {
                if (warmUpCancelled) 0 else 1
            }
            // P0-2 修复：被中止时不要置位 warmedUpModelId。
            // 中止意味着预热没真正完成（ONNX arena 没长到峰值、首次推理开销
            // 没被完全消化），置位后 isWarmedUp() 返回 true、hintTtsWarmUpIfNeeded
            // 不再提示、warmUp 早退——本进程永不再预热，但引擎实际仍是冷的。
            // 用户在 app 启动预热完成前点朗读（预热要 8-10s，这是常态）就会命中：
            // 8-15s 无声且无提示。中止后不置位，下次 warmUp 会重新预热。
            if (warmUpCancelled) {
                Log.i(TAG, "warmUp: model=$modelId cancelled, will retry on next idle")
            } else {
                warmedUpModelId = modelId
                Log.i(
                    TAG,
                    "warmUp: model=$modelId done in ${System.currentTimeMillis() - startMs}ms",
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 预热失败静默：真实朗读路径有自己的重试/熔断处理，
            // 下次 initialize 后还会再尝试
            Log.w(TAG, "warmUp generate failed (harmless, first real speak will retry)", e)
        }
    } finally {
        speakMutex.unlock()
    }
}

/**
 * 预合成一段短文本（≤[MAX_PREWARM_CHARS]，单词/短语）并把 PCM 存入
 * [EmbeddedTtsEngine.pcmCache]；朗读路径命中缓存时跳过 generate 直接播
 * （见 doSpeakQueueLocked），避开 Kokoro 每次 generate ~2s 的固定开销。
 *
 * 典型用法：单词释义弹窗打开时调用——用户看释义的几秒内合成完成，
 * 点喇叭时缓存命中立即出声；若用户在预合成完成前点喇叭，speak 挂在
 * mutex 上等预合成结束，缓存随即命中，总延迟仍严格小于现场合成。
 *
 * 引擎未加载/文本超长/已在缓存：零成本 no-op。
 */
internal suspend fun EmbeddedTtsEngine.prewarmSynthesis(text: String, speed: Float = 1.0f) =
    withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_PREWARM_CHARS) return@withContext
        if (synchronized(this@EmbeddedTtsEngine) { tts } == null) return@withContext
        val isKokoro = currentModelIsKokoro
        val sid = if (isKokoro) getSelectedSid() else 0
        // 与朗读路径同一套清洗：缓存键与合成输入都必须和 doSpeakQueueLocked 对齐
        val cleaned = if (isKokoro) preprocessForTtsLight(trimmed) else preprocessForTts(trimmed)
        if (cleaned.isBlank()) return@withContext
        if (pcmCache.get(cleaned, sid, speed) != null) return@withContext
        if (!speakMutex.tryLock()) return@withContext
        try {
            // 双检：等锁期间可能已被另一条路径合成并缓存 / 引擎被 release
            val engine = synchronized(this@EmbeddedTtsEngine) { tts } ?: return@withContext
            if (pcmCache.get(cleaned, sid, speed) != null) return@withContext
            val audio = engine.generateWithCallback(cleaned, sid = sid, speed = speed) { _ -> 1 }
            if (audio.samples.isNotEmpty()) {
                pcmCache.put(cleaned, sid, speed, audio.samples)
                // 顺带完成引擎级预热置位（本次 generate 已消化首次推理开销）
                warmedUpModelId = currentModelName
                Log.i(
                    TAG,
                    "prewarmSynthesis cached: '${cleaned.take(30)}', samples=${audio.samples.size}",
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 预合成失败静默：点喇叭时走正常合成路径兜底
            Log.w(TAG, "prewarmSynthesis failed for '${trimmed.take(30)}'", e)
        } finally {
            speakMutex.unlock()
        }
    }
