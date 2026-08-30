@file:Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")

package com.eareyereading.util

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
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
                deferred.complete(true)
                android.util.Log.d("TranslationHelper", "ML Kit model downloaded, ready")
            }?.addOnFailureListener { e ->
                android.util.Log.w("TranslationHelper", "ML Kit download failed: ${e.message}")
                mlkitReady = false
                deferred.complete(false)
            }
        } catch (e: com.google.mlkit.common.MlKitException) {
            android.util.Log.w("TranslationHelper", "ML Kit init failed: ${e.message}")
            mlkitReady = false
            deferred.complete(false)
        } catch (e: java.lang.RuntimeException) {
            android.util.Log.w("TranslationHelper", "Runtime error initializing ML Kit: ${e.message}")
            mlkitReady = false
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
            android.util.Log.w("TranslationHelper", "ML Kit init timed out after ${timeoutMs}ms")
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
        ensureInitialized()  // 首次触发懒加载
        return translateViaMlKit(text)
    }

    suspend fun translateParagraphs(paragraphs: List<String>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        paragraphs.forEachIndexed { index, para ->
            result[index] = if (para.isBlank()) ""
                else translateEnToZh(para.take(TRANSLATION_CHAR_LIMIT)) ?: ""
        }
        return result
    }

    suspend fun translateWord(word: String): String? = translateEnToZh(word)
    suspend fun translateContext(sentence: String): String? = translateEnToZh(sentence)
    suspend fun translateSentence(sentence: String): String? = translateEnToZh(sentence)

    // ── 本地词典（用户下载的分级词典 + 内置兜底）───────────
    private suspend fun lookupLocalDict(text: String): String? {
        // Locale.ROOT：避免土耳其语等 locale 下 lowercase 的 I→ı 变体破坏查词
        val clean = text.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")
        if (clean.length < 2) return null
        // 优先查用户选中的下载词典
        dictionaryManager.lookup(clean)?.let { return it }
        // 回退到内置词典
        return builtinDict[clean]
    }

    fun close() {
        mlkitTranslator?.close()
        mlkitTranslator = null
        mlkitReady = false
        initAttempted.set(false)  // 允许重新初始化
    }

    private companion object {
        // 单段翻译字符上限（避免超出 ML Kit 请求限制）
        const val TRANSLATION_CHAR_LIMIT = 4000
    }
}
