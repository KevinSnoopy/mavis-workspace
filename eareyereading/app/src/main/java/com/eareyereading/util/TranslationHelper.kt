@file:Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")

package com.eareyereading.util

import android.content.Context
import android.os.SystemClock
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 翻译助手
 * 优先级：系统翻译(Android 14+) > ML Kit > 本地词典
 * 懒加载，首次翻译时初始化
 */
@Singleton
class TranslationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dictionaryManager: DictionaryManager,
) {
    @Volatile
    private var mlkitTranslator: com.google.mlkit.nl.translate.Translator? = null
    @Volatile
    private var mlkitReady = false

    /** CAS 保证并发首次翻译时只初始化一次，避免重复创建 Translator 泄漏。 */
    private val initAttempted = AtomicBoolean(false)
    @Volatile
    private var mlkitReadyDeferred: CompletableDeferred<Boolean>? = null

    /**
     * 初始化失败的时间戳（elapsedRealtime 毫秒）。
     * issue 8.2：initAttempted 一旦置位即使失败也永不重置（close() 无人调用），
     * ML Kit 模型被系统回收后翻译永久静默失败——失败后开 60s 重试窗口。
     */
    @Volatile
    private var initFailedAt = 0L

    // 内置词典：从 assets/dictionary.txt 加载（word|translation 格式），作为兜底
    private val builtinDict: Map<String, String> by lazy { loadBuiltinDict() }

    private fun loadBuiltinDict(): Map<String, String> {
        return try {
            context.assets.open("dictionary.txt")
                .bufferedReader()
                .useLines { lines ->
                    val map = linkedMapOf<String, String>()
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                        val sep = trimmed.indexOf('|')
                        if (sep <= 0) continue
                        map[trimmed.substring(0, sep).trim()] =
                            trimmed.substring(sep + 1).trim()
                    }
                    android.util.Log.i(
                        "TranslationHelper",
                        "Loaded ${map.size} entries from builtin dictionary.txt"
                    )
                    map
                }
        } catch (e: Exception) {
            android.util.Log.e("TranslationHelper", "Failed to load builtin dictionary.txt", e)
            emptyMap()
        }
    }

    // ── 懒加载初始化（线程安全）─────────────────────
    private suspend fun ensureInitialized() {
        // 失败重试窗口：初始化失败满 60s 后放行重试（issue 8.2）
        if (initAttempted.get()) {
            if (mlkitReady || initFailedAt == 0L) return
            if (SystemClock.elapsedRealtime() - initFailedAt < INIT_RETRY_WINDOW_MS) return
            // 复位失败标记，走下方 CAS 重新初始化
            if (!initAttempted.compareAndSet(true, false)) return
            initFailedAt = 0L
        }
        // compareAndSet：并发首次翻译只允许一个线程进入初始化
        if (!initAttempted.compareAndSet(false, true)) return

        // 使用 ML Kit（后台预加载模型）
        initMlKitAsync()
    }

    // ── ML Kit（Google，依赖 GMS）────────────────
    // 后台异步初始化，不阻塞首次翻译
    private fun initMlKitAsync() {
        val deferred = CompletableDeferred<Boolean>()
        mlkitReadyDeferred = deferred
        try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build()
            mlkitTranslator = Translation.getClient(options)
            mlkitTranslator?.downloadModelIfNeeded(
                DownloadConditions.Builder().build()
            )?.addOnSuccessListener {
                mlkitReady = true
                initFailedAt = 0L
                deferred.complete(true)
                android.util.Log.d("TranslationHelper", "ML Kit model downloaded, ready")
            }?.addOnFailureListener { e ->
                android.util.Log.w("TranslationHelper", "ML Kit download failed: ${e.message}")
                mlkitReady = false
                initFailedAt = SystemClock.elapsedRealtime()
                deferred.complete(false)
            }
        } catch (e: com.google.mlkit.common.MlKitException) {
            android.util.Log.w("TranslationHelper", "ML Kit init failed: ${e.message}")
            mlkitReady = false
            initFailedAt = SystemClock.elapsedRealtime()
            deferred.complete(false)
        } catch (e: java.lang.RuntimeException) {
            android.util.Log.w("TranslationHelper", "Runtime error initializing ML Kit: ${e.message}")
            mlkitReady = false
            initFailedAt = SystemClock.elapsedRealtime()
            deferred.complete(false)
        }
    }

    /**
     * 等待 ML Kit 模型就绪（最多等 timeoutMs 毫秒）。
     * 首次点击翻译时，模型可能还在下载中；这里阻塞等待，避免每次都走本地词典
     * 兜底导致 UI 显示 "[翻译失败]"。
     */
    private suspend fun waitForMlKit(timeoutMs: Long = 30_000): Boolean {
        if (mlkitReady) return true
        val deferred = mlkitReadyDeferred ?: return false
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w("TranslationHelper", "ML Kit init timed out after ${timeoutMs}ms", e)
            false
        }
    }

    private suspend fun translateViaMlKit(text: String): String? {
        // 等待 ML Kit 模型就绪；等待失败才回退到本地词典
        if (!waitForMlKit()) {
            android.util.Log.d("TranslationHelper", "ML Kit not ready, using dict fallback")
            return lookupLocalDict(text)
        }
        // ML Kit 翻译；失败/超时/并发 close 时回退到本地词典。
        // 外层 20s 超时：GMS Task 挂死时不再无限挂起调用方。
        val mlkitResult = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { cont ->
                val translator = mlkitTranslator
                if (translator == null) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                try {
                    translator.translate(text)
                        .addOnSuccessListener { translated -> cont.resume(translated) }
                        .addOnFailureListener {
                            android.util.Log.w("TranslationHelper", "ML Kit translate failed: ${it.message}")
                            cont.resume(null)
                        }
                } catch (e: java.lang.RuntimeException) {
                    // close() 与 translate() 并发时 ML Kit 可能抛 IllegalStateException 等
                    android.util.Log.w("TranslationHelper", "ML Kit translate threw: ${e.message}")
                    cont.resume(null)
                }
            }
        }
        return mlkitResult ?: lookupLocalDict(text)
    }

    // ── 主入口 ───────────────────────────────────
    suspend fun translateEnToZh(text: String): String? {
        if (text.isBlank()) return null
        // issue 8.7：单词级输入（≤20 字符且无空格）先查本地词典，命中即返回，
        // 不消耗 ML Kit 配额/不联网；只有本地未命中才走 ML Kit 翻译
        if (text.length <= 20 && !text.contains(' ')) {
            lookupLocalDict(text)?.let { return it }
        }
        ensureInitialized()  // 首次触发懒加载
        return translateViaMlKit(text)
    }

    suspend fun translateParagraphs(paragraphs: List<String>): Map<Int, String> {
        // issue 8.4：全文翻译 200 段顺序 await 无并发、无分段、无进度反馈。
        // 改为并发翻译（每段独立 async），ML Kit translate 是异步 Task，CPU 不占满，
        // 但网络/模型推理可并行，明显缩短整本书翻译时长。
        val blank = paragraphs.indices.filter { paragraphs[it].isBlank() }.toSet()
        return coroutineScope {
            paragraphs.indices.map { index ->
                async(Dispatchers.IO) {
                    if (index in blank) {
                        // 空段保留占位（下述 filter 会按"翻译结果为空"剔除，二者无冲突）
                        index to ""
                    } else {
                        index to (translateEnToZh(paragraphs[index].take(TRANSLATION_CHAR_LIMIT)) ?: "")
                    }
                }
            }.awaitAll().filter { (_, value) ->
                // issue 8.3：失败段不写入（而不是写 ""）——全 "" 的非空 Map
                // 会把调用方的 isEmpty() 失败判定顶掉，"重试"按钮永远不出现
                value.isNotEmpty()
            }.toMap()
        }
    }

    suspend fun translateWord(word: String): String? = translateEnToZh(word)
    suspend fun translateContext(sentence: String): String? = translateEnToZh(sentence)
    suspend fun translateSentence(sentence: String): String? = translateEnToZh(sentence)

    // ── 本地词典（用户下载的分级词典 + 内置兜底）───────────
    private suspend fun lookupLocalDict(text: String): String? {
        // issue 8.9：词典只收单词；句子/多词输入查词典只会返回
        // 首词或子串的无意义结果（"the book is" 命中 "the"），直接放弃
        if (text.contains(' ')) return null
        // Locale.ROOT：避免土耳其语等 locale 下 lowercase 的 I→ı 变体破坏查词
        val clean = text.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")
        if (clean.length < 2) return null
        // 优先查用户选中的下载词典
        dictionaryManager.lookup(clean)?.let { return it }
        // 回退到内置词典
        return builtinDict[clean]
    }

    /**
     * 释放 ML Kit Translator native 资源并复位初始化状态。
     * issue 8.2：close() 此前全项目无人调用，模型被系统回收后
     * 翻译永久静默失败。现在 ReaderViewModel.cleanup() / App.onTerminate /
     * MainActivity.onDestroy 都会调用；关闭时同步放行挂起的等待者。
     */
    fun close() {
        try {
            mlkitTranslator?.close()
        } catch (e: java.lang.RuntimeException) {
            android.util.Log.w("TranslationHelper", "close translator threw: ${e.message}")
        }
        mlkitTranslator = null
        mlkitReady = false
        initFailedAt = 0L
        // close 与 translate 并发时挂起的等待者必须被放行，否则 30s 超时前一直空转
        mlkitReadyDeferred?.complete(false)
        mlkitReadyDeferred = null
        initAttempted.set(false)  // 允许重新初始化
    }

    private companion object {
        // 单段翻译字符上限（避免超出 ML Kit 请求限制）
        const val TRANSLATION_CHAR_LIMIT = 4000

        // 初始化失败后的重试窗口（issue 8.2）
        const val INIT_RETRY_WINDOW_MS = 60_000L
    }
}
