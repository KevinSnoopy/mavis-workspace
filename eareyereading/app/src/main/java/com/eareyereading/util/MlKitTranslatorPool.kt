package com.eareyereading.util

import android.os.SystemClock
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ML Kit 翻译器池：模型懒加载、就绪等待、按语言对单飞下载、资源释放。
 *
 * 从 [TranslationHelper] 抽出的单一职责类（SRP）：把"ML Kit Translator 的生命周期"
 * 与"翻译策略编排（LLM → 机翻 → 在线 → 词典的回退链）"分离。本类只负责
 * 「拿一个可用的 ML Kit 翻译器并翻译」，不关心上层选哪条通道、失败后如何回退。
 *
 * 两类翻译器：
 * - **默认方向**（EN→ZH）：单实例 + 事件式就绪等待（[ensureDefaultInitialized]
 *   触发后台下载，[waitForDefault] 阻塞等待）。
 * - **其他语言对**：按 "src>tgt" 懒创建、单飞下载（[startPairDownload]），
 *   之后按需翻译，不再写死 EN→ZH（issue 8.1）。
 *
 * 线程安全：就绪状态用 `@Volatile`；初始化入口用 CAS 保证并发首次调用只初始化一次；
 * 语言对容器用 [ConcurrentHashMap] 的 computeIfAbsent 保证单飞。
 */
@Singleton
class MlKitTranslatorPool @Inject constructor() {

    @Volatile
    private var defaultTranslator: Translator? = null

    @Volatile
    private var defaultReady = false

    /** CAS 保证并发首次翻译时只初始化一次，避免重复创建 Translator 泄漏。 */
    private val initAttempted = AtomicBoolean(false)

    @Volatile
    private var defaultReadyDeferred: CompletableDeferred<Boolean>? = null

    /**
     * 初始化失败的时间戳（elapsedRealtime 毫秒）。
     * issue 8.2：initAttempted 一旦置位即使失败也永不重置（close() 无人调用），
     * ML Kit 模型被系统回收后翻译永久静默失败——失败后开 60s 重试窗口。
     */
    @Volatile
    private var initFailedAt = 0L

    // ── issue 8.1：非默认语言对（非 EN→ZH）的按需 Translator ──────────
    // 默认方向仍走上面的单 Translator + 事件式就绪等待；其余语言对按
    // "src>tgt" 懒创建、单飞下载，之后按需翻译，不再写死 EN→ZH。
    private val pairTranslators = ConcurrentHashMap<String, Translator>()
    private val pairDeferreds = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * 确保默认方向（EN→ZH）的模型开始加载（幂等，非阻塞）。
     * 首次调用触发后台下载；失败后 60s 内不再重试。
     */
    fun ensureDefaultInitialized() {
        // 失败重试窗口：初始化失败满 60s 后放行重试（issue 8.2）
        if (initAttempted.get()) {
            if (defaultReady || initFailedAt == 0L) return
            if (SystemClock.elapsedRealtime() - initFailedAt < INIT_RETRY_WINDOW_MS) return
            // 复位失败标记，走下方 CAS 重新初始化
            if (!initAttempted.compareAndSet(true, false)) return
            initFailedAt = 0L
        }
        // compareAndSet：并发首次翻译只允许一个线程进入初始化
        if (!initAttempted.compareAndSet(false, true)) return
        initDefaultAsync()
    }

    /**
     * 默认方向翻译（纯 ML Kit 通道，不含任何上层回退）。
     *
     * @return 译文；模型未就绪 / 翻译失败 / 超时时返回 null，由调用方决定回退策略。
     */
    suspend fun translateDefault(text: String): String? {
        if (!waitForDefault()) {
            Log.d(TAG, "ML Kit not ready, handing off to caller fallback")
            return null
        }
        // ML Kit 翻译；失败/超时/并发 close 时返回 null 交给调用方兜底。
        // 外层 20s 超时：GMS Task 挂死时不再无限挂起调用方。
        return withTimeoutOrNull(MLKIT_TRANSLATE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val translator = defaultTranslator
                if (translator == null) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                try {
                    translator.translate(text)
                        .addOnSuccessListener { translated -> cont.resume(translated) }
                        .addOnFailureListener {
                            Log.w(TAG, "ML Kit translate failed: ${it.message}")
                            cont.resume(null)
                        }
                } catch (e: RuntimeException) {
                    // close() 与 translate() 并发时 ML Kit 可能抛 IllegalStateException 等
                    Log.w(TAG, "ML Kit translate threw: ${e.message}")
                    cont.resume(null)
                }
            }
        }
    }

    /**
     * 非默认语言对的按需翻译（纯 ML Kit 通道）。
     *
     * @return 译文；模型不可用 / 下载未就绪 / 翻译失败 / 超时时返回 null。
     */
    suspend fun translatePair(text: String, sourceLang: String, targetLang: String): String? {
        // 并发首次访问只需下载一次；下载失败也以 CompletableDeferred(false) 落地，
        // 后续不再反复重试（模型缺失是持久态）→ 转在线兜底
        val ready = startPairDownload(sourceLang, targetLang)
        if (ready == null) return null
        if (withTimeoutOrNull(PAIR_MODEL_READY_TIMEOUT_MS) { ready.await() } != true) {
            Log.d(TAG, "pair $sourceLang>$targetLang model not ready")
            return null
        }
        val translator = pairTranslators["$sourceLang>$targetLang"] ?: return null
        return withTimeoutOrNull(MLKIT_TRANSLATE_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                try {
                    translator.translate(text)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "pair translate threw: ${e.message}", e)
                    cont.resume(null)
                }
            }
        }
    }

    /**
     * 单飞创建并启动某语言对的 Translator 模型下载（幂等）。
     * 预热与翻译共用：并发首次访问只下载一次。
     */
    fun startPairDownload(sourceLang: String, targetLang: String): CompletableDeferred<Boolean>? {
        val src = mlKitLanguageTag(sourceLang) ?: return null
        val tgt = mlKitLanguageTag(targetLang) ?: return null
        val key = "$sourceLang>$targetLang"
        return pairDeferreds.computeIfAbsent(key) { k ->
            CompletableDeferred<Boolean>().also { d ->
                try {
                    val translator = Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(src)
                            .setTargetLanguage(tgt)
                            .build(),
                    )
                    pairTranslators[k] = translator
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                        .addOnSuccessListener { d.complete(true) }
                        .addOnFailureListener {
                            Log.w(TAG, "download $key failed: ${it.message}")
                            d.complete(false)
                        }
                } catch (e: Exception) {
                    Log.w(TAG, "init pair $key failed: ${e.message}")
                    d.complete(false)
                }
            }
        }
    }

    /**
     * 释放 ML Kit Translator native 资源并复位初始化状态。
     * issue 8.2：close() 此前全项目无人调用，模型被系统回收后
     * 翻译永久静默失败。现在 ReaderViewModel.cleanup() / App.onTerminate /
     * MainActivity.onDestroy 都会调用；关闭时同步放行挂起的等待者。
     */
    fun close() {
        try {
            defaultTranslator?.close()
        } catch (e: RuntimeException) {
            Log.w(TAG, "close translator threw: ${e.message}")
        }
        defaultTranslator = null
        defaultReady = false
        initFailedAt = 0L
        // close 与 translate 并发时挂起的等待者必须被放行，否则 30s 超时前一直空转
        defaultReadyDeferred?.complete(false)
        defaultReadyDeferred = null
        initAttempted.set(false)  // 允许重新初始化
        // issue 8.1：一并释放按需语言对 Translator 及其中标记，下一入口可重建
        pairTranslators.forEach { (_, t) ->
            try {
                t.close()
            } catch (e: RuntimeException) {
                Log.w(TAG, "close pair translator threw: ${e.message}")
            }
        }
        pairTranslators.clear()
        pairDeferreds.forEach { (_, d) -> d.complete(false) }
        pairDeferreds.clear()
    }

    // ── 内部：默认方向的初始化与就绪等待 ──────────────────────────

    /**
     * 使用 ML Kit（后台预加载模型），不阻塞首次翻译。
     */
    private fun initDefaultAsync() {
        val deferred = CompletableDeferred<Boolean>()
        defaultReadyDeferred = deferred
        try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build()
            defaultTranslator = Translation.getClient(options)
            defaultTranslator?.downloadModelIfNeeded(
                DownloadConditions.Builder().build()
            )?.addOnSuccessListener {
                defaultReady = true
                initFailedAt = 0L
                deferred.complete(true)
                Log.d(TAG, "ML Kit model downloaded, ready")
            }?.addOnFailureListener { e ->
                Log.w(TAG, "ML Kit download failed: ${e.message}")
                defaultReady = false
                initFailedAt = SystemClock.elapsedRealtime()
                deferred.complete(false)
            }
        } catch (e: com.google.mlkit.common.MlKitException) {
            Log.w(TAG, "ML Kit init failed: ${e.message}")
            defaultReady = false
            initFailedAt = SystemClock.elapsedRealtime()
            deferred.complete(false)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Runtime error initializing ML Kit: ${e.message}")
            defaultReady = false
            initFailedAt = SystemClock.elapsedRealtime()
            deferred.complete(false)
        }
    }

    /**
     * 等待 ML Kit 模型就绪（最多等 [timeoutMs] 毫秒）。
     * 首次点击翻译时，模型可能还在下载中；这里阻塞等待，避免每次都走本地词典
     * 兜底导致 UI 显示 "[翻译失败]"。
     */
    private suspend fun waitForDefault(timeoutMs: Long = 30_000): Boolean {
        if (defaultReady) return true
        val deferred = defaultReadyDeferred ?: return false
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "ML Kit init timed out after ${timeoutMs}ms", e)
            false
        }
    }

    private companion object {
        const val TAG = "MlKitTranslatorPool"

        /** 初始化失败后的重试窗口（issue 8.2） */
        const val INIT_RETRY_WINDOW_MS = 60_000L

        /** ML Kit 单次翻译的外层超时：GMS Task 挂死时不再无限挂起调用方 */
        const val MLKIT_TRANSLATE_TIMEOUT_MS = 20_000L

        /** 非默认语言对等待模型下载完成的上限 */
        const val PAIR_MODEL_READY_TIMEOUT_MS = 30_000L
    }
}
