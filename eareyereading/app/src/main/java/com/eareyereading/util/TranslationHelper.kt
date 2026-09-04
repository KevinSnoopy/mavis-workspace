@file:Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")

package com.eareyereading.util

import android.os.SystemClock
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 翻译助手
 * 优先级：AI 翻译（LLM，配置后）> 系统翻译(Android 14+) > ML Kit > 在线 HTTP > 本地词典
 * 懒加载，首次翻译时初始化
 */
@Singleton
class TranslationHelper @Inject constructor(
    private val dictionaryManager: DictionaryManager,
    private val onlineTranslator: OnlineTranslator,
    private val llmTranslator: LlmTranslator,
    private val settingsRepository: com.eareyereading.domain.repository.SettingsRepository,
) {
    @Volatile
    private var mlkitTranslator: com.google.mlkit.nl.translate.Translator? = null
    @Volatile
    private var mlkitReady = false

    /** CAS 保证并发首次翻译时只初始化一次，避免重复创建 Translator 泄漏。 */
    private val initAttempted = AtomicBoolean(false)
    @Volatile
    private var mlkitReadyDeferred: CompletableDeferred<Boolean>? = null

    // ── issue 8.1：非默认语言对（非 EN→ZH）的按需 Translator ──────────
    // 默认方向仍走上面的单 Translator + 事件式就绪等待；其余语言对按
    // "src>tgt" 懒创建、单飞下载，之后按需翻译，不再写死 EN→ZH。
    private val pairTranslators = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()
    private val pairDeferreds = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * 初始化失败的时间戳（elapsedRealtime 毫秒）。
     * issue 8.2：initAttempted 一旦置位即使失败也永不重置（close() 无人调用），
     * ML Kit 模型被系统回收后翻译永久静默失败——失败后开 60s 重试窗口。
     */
    @Volatile
    private var initFailedAt = 0L

    // ── 翻译结果内存 LRU 缓存 ─────────────────────────
    // 同一段落/句子/单词的重复翻译（翻译开关重开、分栏/回译模式重进、
    // 同句再次双击等）直接命中内存，不再消耗 ML Kit 推理。
    // accessOrder LinkedHashMap + 条数上限驱逐，synchronizedMap 保证并发安全；
    // 失败结果不落缓存（下次仍会重试）。
    private val memoryCache: MutableMap<String, String> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                    size > MEMORY_CACHE_MAX_ENTRIES
            },
        )

    private fun cacheKey(text: String, sourceLang: String, targetLang: String): String =
        "$sourceLang>$targetLang|$text"

    // ── AI 翻译（LLM 通道）─────────────────────

    /**
     * 读取 LLM 翻译配置；[checkEnabled]=false 时只看 Key（设置页
     * "测试翻译"在开关打开前就要能校验 Key 是否可用）。
     * DataStore 首次加载后常驻内存，first() 每次调用开销可忽略。
     */
    private suspend fun readLlmConfig(checkEnabled: Boolean): LlmTranslator.Config? {
        return try {
            if (checkEnabled && !settingsRepository.getLlmTranslateEnabled().first()) return null
            val apiKey = settingsRepository.getLlmApiKey().first()
            if (apiKey.isBlank()) return null
            LlmTranslator.Config(
                baseUrl = settingsRepository.getLlmBaseUrl().first(),
                apiKey = apiKey,
                model = settingsRepository.getLlmModel().first(),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.d("TranslationHelper", "read llm config failed: ${e.message}")
            null
        }
    }

    private suspend fun llmConfigIfEnabled(): LlmTranslator.Config? = readLlmConfig(checkEnabled = true)

    // ── LLM 熔断 ─────────────────────────────────
    // 连续失败达阈值后进入冷却期，期间不再尝试 LLM（直接走机翻链）：
    // 离线/端点故障时避免每段翻译都先等满 10s 连接超时才回退
    @Volatile
    private var llmConsecutiveFailures = 0

    @Volatile
    private var llmCooldownUntil = 0L

    private fun llmCircuitOpen(): Boolean = SystemClock.elapsedRealtime() < llmCooldownUntil

    /** 带熔断的 LLM 翻译尝试：成功/失败都维护熔断计数，失败返回 null 由调用方回退机翻。 */
    private suspend fun tryLlmTranslate(text: String, sourceLang: String, targetLang: String): String? {
        if (llmCircuitOpen()) return null
        val config = readLlmConfig(checkEnabled = true) ?: return null
        val result = llmTranslator.translate(text, sourceLang, targetLang, config)
        if (result == null) {
            llmConsecutiveFailures++
            if (llmConsecutiveFailures >= LLM_FAILURE_THRESHOLD) {
                llmCooldownUntil = SystemClock.elapsedRealtime() + LLM_COOLDOWN_MS
                llmConsecutiveFailures = 0
                android.util.Log.w(
                    "TranslationHelper",
                    "LLM failed $LLM_FAILURE_THRESHOLD times in a row, cooldown ${LLM_COOLDOWN_MS}ms",
                )
            }
        } else {
            llmConsecutiveFailures = 0
        }
        return result
    }

    /**
     * 设置页"测试翻译"：无视开关，直接以当前 Key/端点/模型送翻一句样例，
     * 用于配置期校验（非 null 即 Key 可用）。不走任何缓存与回退链。
     */
    suspend fun testLlmTranslation(
        sample: String = "The old man sat by the harbor, watching the boats drift home as the sun melted into the sea.",
    ): String? {
        val config = readLlmConfig(checkEnabled = false) ?: return null
        return llmTranslator.translate(sample, "en", "zh", config)
    }

    /**
     * 译文 Room 缓存键分层：LLM 译文与机翻译文分开缓存。
     * 旧书在开启 AI 翻译后重新打开时读 "#llm" 键（空），触发整本重翻，
     * 不会一直展示启用前缓存的机械译文；关闭 AI 翻译则回到原键的机翻缓存。
     */
    suspend fun effectiveCacheLangPair(langPair: String): String =
        if (llmConfigIfEnabled() != null) "$langPair#llm" else langPair

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
        // 等待 ML Kit 模型就绪；等待失败先走在线翻译（无 GMS ROM 上
        // downloadModelIfNeeded 必失败，全文翻译此前只有本地词典单词兜底，
        // 段落级翻译全军覆没——issue：全文翻译不可用的根因）
        if (!waitForMlKit()) {
            android.util.Log.d("TranslationHelper", "ML Kit not ready, trying online fallback")
            onlineTranslator.translate(text, "en", "zh")?.let { return it }
            return lookupLocalDict(text)
        }
        // ML Kit 翻译；失败/超时/并发 close 时先走在线翻译，再回退本地词典。
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
        if (mlkitResult != null) return mlkitResult
        // ML Kit 失败（模型被回收/推理异常）：在线兜底
        onlineTranslator.translate(text, "en", "zh")?.let { return it }
        return lookupLocalDict(text)
    }

    // ── 主入口 ───────────────────────────────────

    /**
     * 语言对感知的统一翻译入口（issue 8.1）。
     * 外层套内存 LRU 缓存：同一文本重复翻译（开关重开/模式重进/同句再点）
     * 直接命中，不再消耗 ML Kit 推理；失败结果不缓存，下次仍会重试。
     *
     * @param sourceLang 语言代码（如 "en" / "fr" / "ja"，ML Kit 支持范围内）
     * @param targetLang 目标语言代码，默认 "zh"
     */
    suspend fun translate(
        text: String,
        sourceLang: String = "en",
        targetLang: String = "zh",
    ): String? {
        if (text.isBlank()) return null
        if (sourceLang.equals(targetLang, ignoreCase = true)) return text
        val key = cacheKey(text, sourceLang, targetLang)
        memoryCache[key]?.let { return it }
        val result = translateUncached(text, sourceLang, targetLang)
        if (!result.isNullOrBlank()) memoryCache[key] = result
        return result
    }

    private suspend fun translateUncached(
        text: String,
        sourceLang: String,
        targetLang: String,
    ): String? {
        // 默认方向（EN→ZH）：单词级输入先查本地词典——词典释义比任何机器
        // 翻译都更适合查词场景，且零成本零延迟
        if (sourceLang.equals("en", ignoreCase = true) && targetLang.equals("zh", ignoreCase = true) &&
            text.length <= 20 && !text.contains(' ')
        ) {
            lookupLocalDict(text)?.let { return it }
        }
        // AI 翻译（LLM）优先：已配置时整句/整段带上下文成文，译文质量
        // 显著优于下方机翻链；失败（网络/配额/Key 无效）回退机翻，不静默丢
        tryLlmTranslate(text, sourceLang, targetLang)?.let { return it }
        if (sourceLang.equals("en", ignoreCase = true) && targetLang.equals("zh", ignoreCase = true)) {
            ensureInitialized()  // 首次触发懒加载
            return translateViaMlKit(text)
        }
        return translateViaPair(text, sourceLang, targetLang)
    }

    /**
     * 段落级翻译（全文翻译/分栏/回译视图的入口）：
     * - LLM 可用：整段一次送翻——跨句上下文完整，代词衔接/语气连贯，
     *   这是"文学化"与"逐句机翻拼接"的本质差距；
     * - LLM 不可用：按句末标点切句逐句机翻再拼接（规避 ML Kit 长输入
     *   截断与 4000 字符上限截尾），与 ReaderViewModel 旧行为一致。
     * 结果走内存 LRU（"¶|" 前缀与句级缓存隔离）。
     */
    suspend fun translateParagraph(
        paragraph: String,
        sourceLang: String = "en",
        targetLang: String = "zh",
    ): String? {
        if (paragraph.isBlank()) return null
        if (sourceLang.equals(targetLang, ignoreCase = true)) return paragraph
        val key = "¶|" + cacheKey(paragraph, sourceLang, targetLang)
        memoryCache[key]?.let { return it }
        val result = tryLlmTranslate(paragraph, sourceLang, targetLang)
            ?: translateParagraphSentenceBySentence(paragraph, sourceLang, targetLang)
        if (!result.isNullOrBlank()) memoryCache[key] = result
        return result
    }

    /** 机翻兜底：逐句翻译拼接成段译文；任一句失败整段按失败处理（不缓存残缺）。 */
    private suspend fun translateParagraphSentenceBySentence(
        paragraph: String,
        sourceLang: String,
        targetLang: String,
    ): String? {
        val sentences = paragraph.split(SENTENCE_BOUNDARY_CJK)
            .flatMap { it.split(SENTENCE_BOUNDARY) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (sentences.isEmpty()) return null
        val parts = ArrayList<String>(sentences.size)
        for (sentence in sentences) {
            val translated = translate(sentence.take(TRANSLATION_CHAR_LIMIT), sourceLang, targetLang)
            if (translated.isNullOrBlank()) return null
            parts.add(translated.trim())
        }
        return parts.joinToString("")
    }

    /**
     * 预热翻译模型：进阅读页即后台拉起模型下载/就绪，首次开启全文翻译
     * 不再阻塞等待模型 30s。非阻塞（下载异步进行）、失败静默
     * （正式翻译路径仍有 60s 重试窗口兜底）。
     */
    suspend fun warmUp(sourceLang: String = "en", targetLang: String = "zh") {
        if (sourceLang.equals("en", ignoreCase = true) && targetLang.equals("zh", ignoreCase = true)) {
            // 默认方向：只触发懒加载（内部异步下载，不等待完成）
            ensureInitialized()
        } else if (languageTag(sourceLang) != null && languageTag(targetLang) != null) {
            // 其他语言对：单飞启动对应模型下载
            startPairTranslator(sourceLang, targetLang)
        }
    }

    suspend fun translateEnToZh(text: String): String? = translate(text, "en", "zh")

    /**
     * 非默认语言对的按需翻译：单飞创建 + 下载对应语言模型，之后翻译。
     * ML Kit 不可用/失败时走在线翻译兜底（与默认方向同语义）。
     */
    private suspend fun translateViaPair(text: String, sourceLang: String, targetLang: String): String? {
        // 并发首次访问只需下载一次；下载失败也以 CompletableDeferred(false) 落地，
        // 后续不再反复重试（模型缺失是持久态）→ 转在线兜底
        val ready = startPairTranslator(sourceLang, targetLang)
        val mlkitResult = if (ready == null) {
            null
        } else if (withTimeoutOrNull(30_000) { ready.await() } != true) {
            android.util.Log.d("TranslationHelper", "pair $sourceLang>$targetLang model not ready")
            null
        } else {
            val key = "$sourceLang>$targetLang"
            val translator = pairTranslators[key]
            if (translator == null) {
                null
            } else {
                withTimeoutOrNull(20_000) {
                    suspendCancellableCoroutine<String?> { cont ->
                        try {
                            translator.translate(text)
                                .addOnSuccessListener { cont.resume(it) }
                                .addOnFailureListener { cont.resume(null) }
                        } catch (e: java.lang.RuntimeException) {
                            android.util.Log.w("TranslationHelper", "pair translate threw: ${e.message}", e)
                            cont.resume(null)
                        }
                    }
                }
            }
        }
        if (!mlkitResult.isNullOrEmpty()) return mlkitResult
        return onlineTranslator.translate(text, sourceLang, targetLang)
    }

    /**
     * 单飞创建并启动某语言对的 Translator 模型下载（幂等）。
     * warmUp 预热与 translateViaPair 共用：并发首次访问只下载一次。
     */
    private fun startPairTranslator(
        sourceLang: String,
        targetLang: String,
    ): CompletableDeferred<Boolean>? {
        val src = languageTag(sourceLang) ?: return null
        val tgt = languageTag(targetLang) ?: return null
        val key = "$sourceLang>$targetLang"
        return pairDeferreds.computeIfAbsent(key) { k ->
            CompletableDeferred<Boolean>().also { d ->
                try {
                    val translator = com.google.mlkit.nl.translate.Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(src)
                            .setTargetLanguage(tgt)
                            .build(),
                    )
                    pairTranslators[k] = translator
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                        .addOnSuccessListener { d.complete(true) }
                        .addOnFailureListener {
                            android.util.Log.w("TranslationHelper", "download $key failed: ${it.message}")
                            d.complete(false)
                        }
                } catch (e: Exception) {
                    android.util.Log.w("TranslationHelper", "init pair $key failed: ${e.message}")
                    d.complete(false)
                }
            }
        }
    }

    suspend fun translateParagraphs(
        paragraphs: List<String>,
        sourceLang: String = "en",
    ): Map<Int, String> {
        // issue 8.4：全文翻译 200 段顺序 await 无并发、无分段、无进度反馈。
        // 改为并发翻译（每段独立 async），ML Kit translate 是异步 Task，CPU 不占满，
        // 但网络/模型推理可并行，明显缩短整本书翻译时长。
        // Semaphore 限流：不限流时几百段同时压 ML Kit（各自还可能等模型就绪）
        val semaphore = Semaphore(PARAGRAPH_CONCURRENCY)
        val blank = paragraphs.indices.filter { paragraphs[it].isBlank() }.toSet()
        return coroutineScope {
            paragraphs.indices.map { index ->
                async(Dispatchers.IO) {
                    if (index in blank) {
                        // 空段保留占位（下述 filter 会按"翻译结果为空"剔除，二者无冲突）
                        index to ""
                    } else {
                        semaphore.withPermit {
                            index to (translate(paragraphs[index].take(TRANSLATION_CHAR_LIMIT), sourceLang) ?: "")
                        }
                    }
                }
            }.awaitAll().filter { (_, value) ->
                // issue 8.3：失败段不写入（而不是写 ""）——全 "" 的非空 Map
                // 会把调用方的 isEmpty() 失败判定顶掉，"重试"按钮永远不出现
                value.isNotEmpty()
            }.toMap()
        }
    }

    suspend fun translateWord(word: String, sourceLang: String = "en"): String? =
        translate(word, sourceLang, "zh")
    suspend fun translateContext(sentence: String, sourceLang: String = "en"): String? =
        translate(sentence, sourceLang, "zh")
    suspend fun translateSentence(sentence: String, sourceLang: String = "en"): String? =
        translate(sentence, sourceLang, "zh")

    /**
     * issue 8.1：把语言代码（如 "zh" / "fr"）映射成 ML Kit 的 TranslateLanguage 常量。
     * ML Kit 没有提供按代码查常量的静态方法，这里显式维护一份常用映射；
     * 未支持的语言返回 null（调用方按"无法翻译"判定）。
     */
    private fun languageTag(code: String): String? = when (code.trim().lowercase(Locale.ROOT)) {
        "af" -> TranslateLanguage.AFRIKAANS
        "ar" -> TranslateLanguage.ARABIC
        "be" -> TranslateLanguage.BELARUSIAN
        "bg" -> TranslateLanguage.BULGARIAN
        "bn" -> TranslateLanguage.BENGALI
        "ca" -> TranslateLanguage.CATALAN
        "cs" -> TranslateLanguage.CZECH
        "cy" -> TranslateLanguage.WELSH
        "da" -> TranslateLanguage.DANISH
        "de" -> TranslateLanguage.GERMAN
        "el" -> TranslateLanguage.GREEK
        "en" -> TranslateLanguage.ENGLISH
        "eo" -> TranslateLanguage.ESPERANTO
        "es" -> TranslateLanguage.SPANISH
        "et" -> TranslateLanguage.ESTONIAN
        "fa" -> TranslateLanguage.PERSIAN
        "fi" -> TranslateLanguage.FINNISH
        "fr" -> TranslateLanguage.FRENCH
        "ga" -> TranslateLanguage.IRISH
        "gl" -> TranslateLanguage.GALICIAN
        "gu" -> TranslateLanguage.GUJARATI
        "he" -> TranslateLanguage.HEBREW
        "hi" -> TranslateLanguage.HINDI
        "hr" -> TranslateLanguage.CROATIAN
        "ht" -> TranslateLanguage.HAITIAN_CREOLE
        "hu" -> TranslateLanguage.HUNGARIAN
        "id" -> TranslateLanguage.INDONESIAN
        "is" -> TranslateLanguage.ICELANDIC
        "it" -> TranslateLanguage.ITALIAN
        "ja" -> TranslateLanguage.JAPANESE
        "ko" -> TranslateLanguage.KOREAN
        "lt" -> TranslateLanguage.LITHUANIAN
        "lv" -> TranslateLanguage.LATVIAN
        "mk" -> TranslateLanguage.MACEDONIAN
        "mr" -> TranslateLanguage.MARATHI
        "ms" -> TranslateLanguage.MALAY
        "mt" -> TranslateLanguage.MALTESE
        "nl" -> TranslateLanguage.DUTCH
        "no" -> TranslateLanguage.NORWEGIAN
        "pl" -> TranslateLanguage.POLISH
        "pt" -> TranslateLanguage.PORTUGUESE
        "ro" -> TranslateLanguage.ROMANIAN
        "ru" -> TranslateLanguage.RUSSIAN
        "sk" -> TranslateLanguage.SLOVAK
        "sl" -> TranslateLanguage.SLOVENIAN
        "sq" -> TranslateLanguage.ALBANIAN
        "sv" -> TranslateLanguage.SWEDISH
        "sw" -> TranslateLanguage.SWAHILI
        "ta" -> TranslateLanguage.TAMIL
        "te" -> TranslateLanguage.TELUGU
        "th" -> TranslateLanguage.THAI
        "tl" -> TranslateLanguage.TAGALOG
        "tr" -> TranslateLanguage.TURKISH
        "uk" -> TranslateLanguage.UKRAINIAN
        "ur" -> TranslateLanguage.URDU
        "vi" -> TranslateLanguage.VIETNAMESE
        "zh" -> TranslateLanguage.CHINESE
        else -> null
    }

    // ── 本地词典（用户下载的分级词典）────────────────
    private suspend fun lookupLocalDict(text: String): String? {
        // issue 8.9：词典只收单词；句子/多词输入查词典只会返回
        // 首词或子串的无意义结果（"the book is" 命中 "the"），直接放弃
        if (text.contains(' ')) return null
        // Locale.ROOT：避免土耳其语等 locale 下 lowercase 的 I→ı 变体破坏查词
        val clean = text.trim().lowercase(Locale.ROOT).replace(NON_ALPHA_REGEX, "")
        if (clean.length < 2) return null
        // 查用户选中的下载词典，未选中/未命中返回 null（不再有内置兜底）
        return dictionaryManager.lookup(clean)
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
        // issue 8.1：一并释放按需语言对 Translator 及其中标记，下一入口可重建
        pairTranslators.forEach { (_, t) ->
            try {
                t.close()
            } catch (e: java.lang.RuntimeException) {
                android.util.Log.w("TranslationHelper", "close pair translator threw: ${e.message}")
            }
        }
        pairTranslators.clear()
        pairDeferreds.forEach { (_, d) -> d.complete(false) }
        pairDeferreds.clear()
    }

    private companion object {
        // 单段翻译字符上限（避免超出 ML Kit 请求限制）
        const val TRANSLATION_CHAR_LIMIT = 4000

        // 初始化失败后的重试窗口（issue 8.2）
        const val INIT_RETRY_WINDOW_MS = 60_000L

        // LLM 熔断：连续失败阈值与冷却时长（离线快速回退机翻）
        const val LLM_FAILURE_THRESHOLD = 3
        const val LLM_COOLDOWN_MS = 60_000L

        // 内存 LRU 缓存上限：段落级译文体量较大，512 条足够覆盖
        // 整本中小型书籍 + 常用句子/单词，超出按访问顺序驱逐
        const val MEMORY_CACHE_MAX_ENTRIES = 512

        // 整书翻译并发上限：旧实现 200 段一次性 async 同时压 ML Kit
        //（各自还可能等模型就绪），限流后吞吐更高也更稳
        const val PARAGRAPH_CONCURRENCY = 6

        // 句子边界（ASCII）：句末标点 + 空白 + 大写字母/引号/左括号
        //（与 ReaderViewModel.splitSentencesCompat 同规则）
        val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+(?=[A-Z\"\\(])")

        // 句子边界（CJK）：全角句点 。！？；（允许尾随闭引号/括号）
        val SENTENCE_BOUNDARY_CJK = Regex("(?<=[。！？；][”’」』]?)")

        // 本地词典查词的归一化正则（查词热路径预编译）
        val NON_ALPHA_REGEX = Regex("[^a-z]")
    }
}
