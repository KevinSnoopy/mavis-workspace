package com.eareyereading.util

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 翻译门面：按优先级编排「AI 翻译（LLM）> ML Kit 机翻 > 在线 HTTP > 本地词典」
 * 的回退链，并对外提供段落级、书级与单词级的翻译入口。
 *
 * 优先级：AI 翻译（LLM，配置后）> ML Kit（GMS 可用时）> 在线 HTTP > 本地词典
 *
 * ── 重构说明（13 条软件设计原则）──
 * 本类原本 586 行，混合了"ML Kit Translator 生命周期管理"、"LLM 配置与熔断"、
 * "语言代码映射表"、"翻译策略编排"四类职责，违反 SRP。现已下沉为独立模块：
 *   - [MlKitTranslatorPool]：ML Kit Translator 的懒加载、就绪等待、
 *     按语言对单飞下载与资源释放
 *   - [LlmTranslationGate]：AI 通道的配置读取、启用开关与失败熔断
 *   - [mlKitLanguageTag]：语言代码 → ML Kit 常量的纯数据映射
 *   - [TranslationMemoryCache]：翻译结果内存 LRU
 *   - [LlmCircuitBreaker]：熔断状态机
 *
 * 本类现在只做**策略编排**：决定用哪条通道、失败后回退到哪条、结果如何缓存。
 * 所有对外 API 的签名与业务行为完全不变。
 */
@Singleton
class TranslationHelper @Inject constructor(
    private val dictionaryManager: DictionaryManager,
    private val onlineTranslator: OnlineTranslator,
    private val mlkitPool: MlKitTranslatorPool,
    private val llmGate: LlmTranslationGate,
) {
    // ── 翻译结果内存 LRU 缓存（委托给 TranslationMemoryCache，SRP）──
    private val memoryCache = TranslationMemoryCache()

    private fun cacheKey(text: String, sourceLang: String, targetLang: String): String =
        memoryCache.key(text, sourceLang, targetLang)

    // ── AI 翻译（LLM 通道）─────────────────────

    /**
     * 设置页"测试翻译"：无视开关，直接以当前 Key/端点/模型送翻一句样例，
     * 用于配置期校验（非 null 即 Key 可用）。不走任何缓存与回退链。
     */
    suspend fun testLlmTranslation(sample: String = DEFAULT_TEST_SAMPLE): String? =
        llmGate.testTranslate(sample)

    /**
     * 译文 Room 缓存键分层：LLM 译文与机翻译文分开缓存。
     * 旧书在开启 AI 翻译后重新打开时读 "#llm" 键（空），触发整本重翻，
     * 不会一直展示启用前缓存的机械译文；关闭 AI 翻译则回到原键的机翻缓存。
     */
    suspend fun effectiveCacheLangPair(langPair: String): String =
        if (llmGate.isEnabled()) "$langPair#llm" else langPair

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
        memoryCache.get(key)?.let { return it }
        val result = translateUncached(text, sourceLang, targetLang)
        if (!result.isNullOrBlank()) memoryCache.put(key, result)
        return result
    }

    private suspend fun translateUncached(
        text: String,
        sourceLang: String,
        targetLang: String,
    ): String? {
        val isDefaultPair = sourceLang.equals("en", ignoreCase = true) &&
            targetLang.equals("zh", ignoreCase = true)
        // 默认方向（EN→ZH）：单词级输入先查本地词典——词典释义比任何机器
        // 翻译都更适合查词场景，且零成本零延迟
        if (isDefaultPair && text.length <= WORD_LOOKUP_MAX_CHARS && !text.contains(' ')) {
            lookupLocalDict(text)?.let { return it }
        }
        // AI 翻译（LLM）优先：已配置时整句/整段带上下文成文，译文质量
        // 显著优于下方机翻链；失败（网络/配额/Key 无效）回退机翻，不静默丢
        llmGate.tryTranslate(text, sourceLang, targetLang)?.let { return it }
        return if (isDefaultPair) {
            mlkitPool.ensureDefaultInitialized()  // 首次触发懒加载
            translateViaMlKit(text)
        } else {
            translateViaPair(text, sourceLang, targetLang)
        }
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
        memoryCache.get(key)?.let { return it }
        val result = llmGate.tryTranslate(paragraph, sourceLang, targetLang)
            ?: translateParagraphSentenceBySentence(paragraph, sourceLang, targetLang)
        if (!result.isNullOrBlank()) memoryCache.put(key, result)
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
        // AI 翻译已配置启用时，不预热 ML Kit 模型——
        // LLM 是主通道，ML Kit 仅作离线兜底，按需懒加载即可，
        // 不必进书就下载 ~30MB 模型浪费流量/存储
        if (llmGate.isEnabled()) return
        if (sourceLang.equals("en", ignoreCase = true) && targetLang.equals("zh", ignoreCase = true)) {
            // 默认方向：只触发懒加载（内部异步下载，不等待完成）
            mlkitPool.ensureDefaultInitialized()
        } else if (mlKitLanguageTag(sourceLang) != null && mlKitLanguageTag(targetLang) != null) {
            // 其他语言对：单飞启动对应模型下载
            mlkitPool.startPairDownload(sourceLang, targetLang)
        }
    }

    suspend fun translateEnToZh(text: String): String? = translate(text, "en", "zh")

    /**
     * 默认方向（EN→ZH）的机翻链：ML Kit → 在线 HTTP → 本地词典。
     * ML Kit 未就绪与其翻译失败走完全相同的兜底路径，故合并为一条链路（DRY）。
     */
    private suspend fun translateViaMlKit(text: String): String? {
        mlkitPool.translateDefault(text)?.let { return it }
        onlineTranslator.translate(text, "en", "zh")?.let { return it }
        return lookupLocalDict(text)
    }

    /**
     * 非默认语言对的机翻链：ML Kit → 在线 HTTP（与默认方向同语义）。
     */
    private suspend fun translateViaPair(
        text: String,
        sourceLang: String,
        targetLang: String,
    ): String? {
        val mlkitResult = mlkitPool.translatePair(text, sourceLang, targetLang)
        if (!mlkitResult.isNullOrEmpty()) return mlkitResult
        return onlineTranslator.translate(text, sourceLang, targetLang)
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
        mlkitPool.close()
    }

    private companion object {
        // 单段翻译字符上限（避免超出 ML Kit 请求限制）
        const val TRANSLATION_CHAR_LIMIT = 4000

        // 整书翻译并发上限：旧实现 200 段一次性 async 同时压 ML Kit
        //（各自还可能等模型就绪），限流后吞吐更高也更稳
        const val PARAGRAPH_CONCURRENCY = 6

        // 单词级输入先查本地词典的长度阈值（超过则视为句子，直接走机翻）
        const val WORD_LOOKUP_MAX_CHARS = 20

        // 设置页"测试翻译"的默认样例句
        const val DEFAULT_TEST_SAMPLE =
            "The old man sat by the harbor, watching the boats drift home as the sun melted into the sea."

        // 句子边界（ASCII）：句末标点 + 空白 + 大写字母/引号/左括号
        //（与 ReaderViewModel.splitSentencesCompat 同规则）
        val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+(?=[A-Z\"\\(])")

        // 句子边界（CJK）：全角句点 。！？；（允许尾随闭引号/括号）
        val SENTENCE_BOUNDARY_CJK = Regex("(?<=[。！？；][”’」』]?)")

        // 本地词典查词的归一化正则（查词热路径预编译）
        val NON_ALPHA_REGEX = Regex("[^a-z]")
    }
}
