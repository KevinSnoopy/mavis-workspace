package com.eareyereading.tts

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 日志标签：绑定到主类的常量，避免字面量重复（DRY）。
 * 扩展函数无法直接解析接收者的 companion 成员，故在此显式引用。
 */
private const val TAG = EmbeddedTtsEngine.TAG

/**
 * 引擎初始化与预热（模型加载 → 首声延迟优化）。
 *
 * 从 [EmbeddedTtsEngine] 抽出的单一职责模块（SRP）：引擎主类只保留对外门面，
 * 「加载 OfflineTts 实例」「首次推理预热」「短文本预合成缓存」三条与 native
 * 生命周期强相关的路径集中在本文件。
 *
 * 全部以 [EmbeddedTtsEngine] 的 internal 扩展函数实现，调用方
 * （`initialize` / `warmUp` / `prewarmSynthesis`）的签名与行为保持不变。
 *
 * 锁语义（与拆分前完全一致）：native 指针的替换/释放必须与 `generate()` 互斥，
 * 因此所有写 [EmbeddedTtsEngine] native 实例的操作都在 `speakMutex` 内完成。
 */

/**
 * 初始化 OfflineTts 实例（调用前确保模型已下载）。
 *
 * @param modelInfo 要加载的模型
 * @param language 书籍语言（"en"/"zh"/null）。Kokoro 按此决定 G2P 配置：
 *   英文时省略中文 lexicon/ruleFST/jieba，G2P 从 ~8s 降到 <1s
 *  （2026-09-06 实测：中文资源是 G2P 68% 耗时的根因）。
 *   null = 全配（向后兼容，中英混读场景）。
 */
internal suspend fun EmbeddedTtsEngine.initializeEngine(
    modelInfo: ModelInfo,
    language: String?,
): Boolean =
    withContext(Dispatchers.IO) {
        // 快路径也进锁：与 release()/deleteModel 竞态时，可能在 tts 被置空的
        // 同时返回 true，之后每次 speak 静默失败
        val sameModelLoaded = speakMutex.withLock {
            nativeEngine.isModelLoaded(modelInfo.id)
        }
        if (sameModelLoaded) {
            // 已加载同模型：把状态流也摆正（此前可能停留在
            // FAILED/DOWNLOAD_FAILED，与布尔返回值互相矛盾）
            _state.value = EmbeddedTtsEngine.EngineState.READY(modelInfo.id)
            // 若正处于下载→初始化流程中（progress=Initializing），推进到 Completed
            // 让 UI 收到 100% "已启用"；否则不碰 progress（避免干扰独立 initialize 调用）
            if (_downloadProgress.value is EmbeddedTtsEngine.Progress.Initializing) {
                _downloadProgress.value = EmbeddedTtsEngine.Progress.Completed
            }
            return@withContext true
        }
        _state.value = EmbeddedTtsEngine.EngineState.INITIALIZING
        try {
            if (!isModelDownloaded(modelInfo)) {
                _state.value = EmbeddedTtsEngine.EngineState.MODEL_NOT_FOUND
                // 下载→初始化流程中模型文件缺失（解压后校验失败等）：
                // 推进到 Failed 让 UI 退出"初始化中"，否则 UI 卡在 99%
                if (_downloadProgress.value is EmbeddedTtsEngine.Progress.Initializing) {
                    _downloadProgress.value = EmbeddedTtsEngine.Progress.Failed("模型文件缺失")
                }
                return@withContext false
            }
            val newTts = configBuilder.build(modelInfo)
            // 关键：替换/释放旧 native 实例必须与 generate() 互斥。
            // 只加 synchronized(this) 时，另一个协程可能正持有 speakMutex
            // 在 generate() 里使用旧实例 → release() 直接 JNI use-after-free
            //（正是注释里说的 SIGSEGV 类别）。构造在锁外完成，仅替换进锁。
            var assigned = false
            try {
                speakMutex.withLock {
                    synchronized(this@initializeEngine) {
                        // 锁内双检：快路径检查后两个协程可能同时在锁外构造
                        // OfflineTts（各 ~66MB native 内存）。后进锁者若发现
                        // 同模型已被抢先加载，直接复用——否则会把刚加载好的
                        // 实例 release 掉再换自己的（双份峰值 + 白加载一次）
                        if (nativeEngine.isModelLoaded(modelInfo.id)) {
                            Log.i(TAG, "initialize: model=${modelInfo.id} already loaded by concurrent call, reuse")
                        } else {
                            // 替换前 shutdown 旧的
                            nativeEngine.release()
                            nativeEngine.assign(
                                newTts,
                                modelInfo.id,
                                modelInfo.isKokoro,
                                newTts.sampleRate(),
                            )
                            assigned = true
                        }
                        // 状态写入也进锁：出锁再写会与 release()（同锁内置
                        // tts=null + NOT_INITIALIZED）交错出 READY∧tts=null 的
                        // 说谎状态——之后所有 speak 静默失败而 UI 显示就绪
                        _state.value = EmbeddedTtsEngine.EngineState.READY(modelInfo.id)
                    }
                }
            } finally {
                // 构造成功但从未赋值（等锁时被取消/异常/被并发抢先）：显式释放，
                // 上百 MB 的 native 模型不该只等 GC finalizer
                if (!assigned) {
                    try { newTts.release() } catch (_: Exception) {}
                }
            }
            Log.i(TAG, "Initialized sherpa-onnx OfflineTts: model=${modelInfo.id}, sampleRate=${nativeEngine.sampleRate}")
            // 下载→初始化流程：推进到 Completed 让 UI 收到 100% "已启用"
            if (_downloadProgress.value is EmbeddedTtsEngine.Progress.Initializing) {
                _downloadProgress.value = EmbeddedTtsEngine.Progress.Completed
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "initialize failed", e)
            // 新实例构造失败但旧引擎还在时，状态回到"旧模型就绪"，
            // 而不是 FAILED（引擎实际仍可用，UI 显示"未就绪"会说谎）
            val fallback = if (nativeEngine.isLoaded()) nativeEngine.currentModelName else null
            _state.value = if (fallback != null) {
                EmbeddedTtsEngine.EngineState.READY(fallback)
            } else {
                EmbeddedTtsEngine.EngineState.FAILED(e.message ?: "初始化失败")
            }
            // 下载→初始化流程中失败：推进到 Failed 让 UI 退出"初始化中"
            if (_downloadProgress.value is EmbeddedTtsEngine.Progress.Initializing) {
                _downloadProgress.value = EmbeddedTtsEngine.Progress.Failed(e.message ?: "初始化失败")
            }
            false
        }
    }

/**
 * 后台预热：跑一次与真实首块等长的合成并丢弃音频，提前消化
 * ONNX Runtime **首次** generate 的一次性开销（图优化、线程池爬升、
 * arena 内存池扩张与物理页缺页）。
 *
 * **锁语义**：tryLock 拿不到（正在朗读）直接放弃。拿到锁后开始合成，
 * 但合成期间用户点朗读时，runSpeakQueue 会设 [EmbeddedTtsEngine.warmUpCancelled] = true，
 * generate 回调返回 0 中止合成、释放锁，用户请求立即开始——
 * 不让预热阻塞用户 10 秒（2026-09-05 真机实测：warmUp 10s 未完成时
 * 用户点朗读，speak 挂锁等 10s 才出声）。
 */
internal suspend fun EmbeddedTtsEngine.warmUpEngine() = withContext(Dispatchers.IO) {
    val modelId = nativeEngine.currentModelName
    if (modelId.isEmpty() || modelId == nativeEngine.warmedUpModelId) return@withContext
    if (!speakMutex.tryLock()) return@withContext
    try {
        // 双检：等锁/调度期间引擎可能已被 release() 或换了模型
        val engine = synchronized(this@warmUpEngine) { nativeEngine.tts } ?: return@withContext
        if (nativeEngine.currentModelName != modelId) return@withContext
        val sid = if (nativeEngine.currentModelIsKokoro) getSelectedSid() else 0
        val startMs = System.currentTimeMillis()
        warmUpCancelled = false
        try {
            // 与真实朗读同一代码路径（generateWithCallback + sid），
            // 确保 ONNX 会话/内存池/线程池全部被预热。
            // 回调检查 warmUpCancelled：用户点朗读时返回 0 中止合成
            engine.generateWithCallback(EmbeddedTtsEngine.WARMUP_TEXT, sid = sid, speed = 1.0f) {
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
                nativeEngine.warmedUpModelId = modelId
                Log.i(
                    TAG,
                    "warmUp: model=$modelId done in ${System.currentTimeMillis() - startMs}ms",
                )
            }
        } catch (e: CancellationException) {
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
 *（见 [synthesizeBlockIntoPlayer]），避开 Kokoro 每次 generate ~2s 的固定开销。
 *
 * 典型用法：单词释义弹窗打开时调用——用户看释义的几秒内合成完成，
 * 点喇叭时缓存命中立即出声；若用户在预合成完成前点喇叭，speak 挂在
 * mutex 上等预合成结束，缓存随即命中，总延迟仍严格小于现场合成。
 *
 * 锁语义与 [warmUpEngine] 一致：tryLock 拿不到（正文朗读进行中）直接放弃——
 * 预合成是体验优化，绝不能反过来阻塞用户的正文朗读。
 * 引擎未加载/文本超长/已在缓存：零成本 no-op。
 */
internal suspend fun EmbeddedTtsEngine.prewarmSynthesisEngine(
    text: String,
    speed: Float,
) = withContext(Dispatchers.IO) {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.length > EmbeddedTtsEngine.MAX_PREWARM_CHARS) return@withContext
    if (synchronized(this@prewarmSynthesisEngine) { nativeEngine.tts } == null) return@withContext
    val isKokoro = nativeEngine.currentModelIsKokoro
    val sid = if (isKokoro) getSelectedSid() else 0
    // 与朗读路径同一套清洗：缓存键与合成输入都必须和 executeSpeakQueueLocked 对齐
    val cleaned = if (isKokoro) preprocessForTtsLight(trimmed) else preprocessForTts(trimmed)
    if (cleaned.isBlank()) return@withContext
    val key = pcmCache.key(cleaned, sid, speed)
    if (pcmCache.get(key) != null) return@withContext
    if (!speakMutex.tryLock()) return@withContext
    try {
        // 双检：等锁期间可能已被另一条路径合成并缓存 / 引擎被 release
        val engine = synchronized(this@prewarmSynthesisEngine) { nativeEngine.tts } ?: return@withContext
        if (pcmCache.get(key) != null) return@withContext
        val audio = engine.generateWithCallback(cleaned, sid = sid, speed = speed) { _ -> 1 }
        if (audio.samples.isNotEmpty()) {
            pcmCache.put(key, audio.samples)
            // 顺带完成引擎级预热置位（本次 generate 已消化首次推理开销）
            nativeEngine.markWarmedUp()
            Log.i(
                TAG,
                "prewarmSynthesis cached: '${cleaned.take(30)}', samples=${audio.samples.size}",
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 预合成失败静默：点喇叭时走正常合成路径兜底
        Log.w(TAG, "prewarmSynthesis failed for '${trimmed.take(30)}'", e)
    } finally {
        speakMutex.unlock()
    }
}
