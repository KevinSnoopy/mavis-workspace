@file:Suppress("TooGenericExceptionCaught", "ReturnCount", "SwallowedException")

package com.eareyereading.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM 翻译通道（OpenAI 兼容 chat/completions 协议）。
 *
 * 相比 ML Kit 端侧小模型 / Bing 消费级机翻的"逐词直译"，LLM 带
 * 文学化提示词 + 整段上下文一次成文，译文自然流畅（信达雅取向）。
 * 默认端点智谱 GLM-4-Flash（免费额度）；DeepSeek / 其它 OpenAI
 * 兼容服务只需改 Base URL + 模型名。
 *
 * 由 [TranslationHelper] 按"已配置则优先，失败回退机翻链"调度；
 * 单独一个类便于单测提示词构造与响应解析（HTTP 部分不可离线测）。
 */
@Singleton
class LlmTranslator @Inject constructor() {

    companion object {
        private const val TAG = "LlmTranslator"

        /** 单次请求超时：LLM 生成慢于机翻（整段成文），给足生成时间。 */
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 25_000

        /**
         * 单次送翻字符上限：按句子边界分片（段落级送翻保留上下文，
         * 只有超长段落才切；上限内 GLM/DeepSeek 均单请求可完成）。
         */
        internal const val CHUNK_MAX_CHARS = 3000

        /** 生成上限：3000 字符英文段的中文译文 ~2000 token，4096 留余量。 */
        private const val MAX_TOKENS = 4096

        /** 翻译温度：低温保证忠实与稳定，不复读原文。 */
        private const val TEMPERATURE = 0.1

        /** 句子边界（与 OnlineTranslator 同规则）：句末标点 + 空白。 */
        private val SENTENCE_BOUNDARY = Regex("(?<=[.!?;。！？；…])\\s+")

        /** 模型常见的自作主张前后缀（解析期防御性剥离）。 */
        private val PREAMBLE = Regex(
            "^(?:以下是[^\\n:：]{0,12}[:：]\\s*|翻译(?:如下|结果)?[:：]\\s*|译文[:：]\\s*)",
        )

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    /** 一次翻译所需的全部配置（TranslationHelper 从设置读出后传入）。 */
    data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    /** 入口：超长文本按句子边界分片，逐片送翻拼接；单片失败整体失败。 */
    suspend fun translate(text: String, sourceLang: String, targetLang: String, config: Config): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (sourceLang.equals(targetLang, ignoreCase = true)) return text
        return withContext(Dispatchers.IO) {
            val chunks = chunksOf(trimmed)
            val totalTimeout = REQUEST_TIMEOUT_MS * chunks.size
            withTimeoutOrNull(totalTimeout) {
                val parts = mutableListOf<String>()
                for (chunk in chunks) {
                    val piece = translateChunk(chunk, targetLang, config) ?: return@withTimeoutOrNull null
                    parts.add(piece)
                }
                val joiner = if (targetLang.lowercase().startsWith("zh")) "" else " "
                parts.joinToString(joiner).ifEmpty { null }
            }
        }
    }

    /** 按句子边界把长文本切成 ≤ [CHUNK_MAX_CHARS] 的片段；无句读的硬切。 */
    internal fun chunksOf(text: String): List<String> {
        if (text.length <= CHUNK_MAX_CHARS) return listOf(text)
        val chunks = mutableListOf<String>()
        val sb = StringBuilder()
        for (sentence in SENTENCE_BOUNDARY.split(text)) {
            val projected = sb.length + (if (sb.isEmpty()) 0 else 1) + sentence.length
            if (sb.isNotEmpty() && projected > CHUNK_MAX_CHARS) {
                chunks.add(sb.toString())
                sb.setLength(0)
            }
            if (sentence.length > CHUNK_MAX_CHARS) {
                var i = 0
                while (i < sentence.length) {
                    val end = minOf(i + CHUNK_MAX_CHARS, sentence.length)
                    chunks.add(sentence.substring(i, end))
                    i = end
                }
            } else {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(sentence)
            }
        }
        if (sb.isNotEmpty()) chunks.add(sb.toString())
        return chunks
    }

    /** 构造 system 提示词：文学化翻译规约（internal 供单测）。 */
    internal fun systemPrompt(targetLang: String): String {
        val targetName = when {
            targetLang.lowercase().startsWith("zh") -> "简体中文"
            else -> targetLang
        }
        return "你是一位资深的专业翻译，擅长把外文文学作品译成$targetName。" +
            "请把用户发送的原文段落翻译成$targetName，要求：" +
            "忠实原文含义，不增不减不漏译；" +
            "符合$targetName" + "表达习惯，杜绝翻译腔（如滥用被动句、代词逐字直译、定语从句套娃）；" +
            "人名地名用通行译名，术语准确；" +
            "保留原文的语气、文体与感情色彩；" +
            "只输出译文本身，不要任何解释、说明、原文或前后缀。"
    }

    /** 构造 chat/completions 请求体（internal 供单测）。 */
    internal fun buildRequestBody(text: String, targetLang: String, config: Config): String {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt(targetLang))
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", text)
            })
        }
        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("temperature", TEMPERATURE)
            put("max_tokens", MAX_TOKENS)
            put("stream", false)
            // 源语言不进提示词：语言自动识别更稳（混杂引文/专有名词时
            // 显式声明反而干扰）
        }
        return body.toString()
    }

    /** 解析 chat/completions 响应并清理模型前后缀（internal 供单测）。 */
    internal fun parseResponse(raw: String): String? {
        val json = JSONObject(raw)
        // OpenAI 兼容错误体：{"error": {"message": "..."}}
        json.optJSONObject("error")?.let { err ->
            android.util.Log.w(TAG, "LLM API error: ${err.optString("message")}")
            return null
        }
        val content = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?: return null
        val cleaned = PREAMBLE.replace(content.trim(), "").trim()
        return cleaned.ifEmpty { null }
    }

    private suspend fun translateChunk(
        text: String,
        targetLang: String,
        config: Config,
    ): String? {
        if (config.apiKey.isBlank() || config.baseUrl.isBlank() || config.model.isBlank()) return null
        val url = config.baseUrl.removeSuffix("/") + "/chat/completions"
        val raw = httpPostJson(url, buildRequestBody(text, targetLang, config), config.apiKey)
            ?: return null
        return try {
            parseResponse(raw)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "LLM response parse failed: ${e.message}")
            null
        }
    }

    // ── HTTP 基础设施（与 OnlineTranslator 同款 HttpURLConnection）──

    private fun httpPostJson(url: String, body: String, apiKey: String): String? =
        try {
            (URL(url).openConnection() as HttpURLConnection).let { conn ->
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = CONNECT_TIMEOUT_MS
                    conn.readTimeout = READ_TIMEOUT_MS
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    conn.setRequestProperty("Accept", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    readBody(conn)
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.d(TAG, "LLM request failed: ${e.message}")
            null
        }

    private fun readBody(conn: HttpURLConnection): String? {
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                // 错误体常带可读原因（401 无效 key / 429 限流），读 errorStream 辅助排查
                val err = conn.errorStream?.use { ins ->
                    val out = ByteArrayOutputStream()
                    ins.copyTo(out)
                    String(out.toByteArray(), Charsets.UTF_8)
                }
                android.util.Log.w(TAG, "HTTP $code from ${conn.url?.host}: ${err?.take(300)}")
                return null
            }
            val stream = conn.inputStream ?: return null
            val bytes = stream.use { ins ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                while (total < 4_000_000L) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.d(TAG, "LLM readBody failed: ${e.message}")
            null
        }
    }
}
