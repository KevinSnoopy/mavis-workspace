@file:Suppress("TooGenericExceptionCaught", "ReturnCount", "SwallowedException")

package com.eareyereading.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 在线 HTTP 翻译兜底（无 GMS 设备的全文翻译主路径）。
 *
 * ML Kit Translate 的语言模型必须经 Google Play Services 下载：
 * 国产无 GMS ROM 上 downloadModelIfNeeded 必失败，全文翻译只能
 * 静默回退本地词典（仅单词）。这里补一条纯 HTTP 翻译链，端点间
 * 相互独立、免 API key、依次尝试，直到一个成功：
 *
 *  1. Google gtx（translate.googleapis.com 非官方端点，质量最好，
 *     需可直连 Google 的网络）
 *  2. Bing 消费版（cn.bing.com / www.bing.com ttranslatev3，
 *     国内可直连，需携带页面下发的 IG/IID/token）
 *  3. MyMemory（api.mymemory.translated.net，全球可达，
 *     匿名 500 字符/次 + 每日配额）
 *
 * 端点"粘性"：某端点一旦成功就记住，后续请求优先走它（失败自动轮换），
 * 避免每次都从头探测。所有请求走 HttpURLConnection + 超时保护，
 * 单端点 15s 上限，调用方（TranslationHelper）还有外层总超时。
 */
@Singleton
class OnlineTranslator @Inject constructor() {

    companion object {
        private const val TAG = "OnlineTranslator"

        /** 单端点整体超时（含建连 + 读取 + 解析）。 */
        private const val ENDPOINT_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000

        /** MyMemory 单次查询字符上限（官方限制 500，留余量）。 */
        private const val MYMEMORY_MAX_CHARS = 490

        /** Google gtx 单次查询字符上限（URL/表单长度保护）。 */
        private const val GTX_MAX_CHARS = 3500

        /** Bing 单次查询字符上限。 */
        private const val BING_MAX_CHARS = 3500

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** Bing 配置（IG/IID/token）的刷新间隔。 */
        private const val BING_CONFIG_TTL_MS = 5 * 60_000L

        private val BING_HOSTS = listOf("https://cn.bing.com", "https://www.bing.com")

        private const val ENDPOINT_COUNT = 3
    }

    /** 端点序号（粘性偏好的起始索引）。 */
    @Volatile
    private var preferredEndpoint = 0

    // ── Bing 会话配置（多请求共享，TTL 过期或 401 时单飞刷新） ──
    private val bingMutex = Mutex()

    private data class BingConfig(
        val host: String,
        val ig: String,
        val iid: String,
        val token: String,
        val key: String,
        val fetchedAt: Long,
    )

    @Volatile
    private var bingConfig: BingConfig? = null

    /** 入口：按粘性顺序尝试各端点，任一成功即返回译文。 */
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (sourceLang.equals(targetLang, ignoreCase = true)) return text
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(ENDPOINT_TIMEOUT_MS * ENDPOINT_COUNT) {
                val start = preferredEndpoint
                for (i in 0 until ENDPOINT_COUNT) {
                    val idx = (start + i) % ENDPOINT_COUNT
                    val result = tryEndpoint(idx, trimmed, sourceLang, targetLang)
                    if (result != null) {
                        preferredEndpoint = idx
                        return@withTimeoutOrNull result
                    }
                }
                null
            }
        }
    }

    private suspend fun tryEndpoint(index: Int, text: String, sourceLang: String, targetLang: String): String? =
        try {
            when (index) {
                0 -> translateViaGtx(text, sourceLang, targetLang)
                1 -> translateViaBing(text, sourceLang, targetLang)
                else -> translateViaMyMemory(text, sourceLang, targetLang)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.d(TAG, "endpoint $index failed: ${e.message}")
            null
        }

    // ─────────────────────────────────────────────────────────────
    // 端点 1：Google gtx（非官方 translate_a/single 接口）
    // 响应形如 [[["你好","hello",null,null,10],…],…]，拼第一层每个元素的 [0]。
    // ─────────────────────────────────────────────────────────────
    private suspend fun translateViaGtx(text: String, sourceLang: String, targetLang: String): String? {
        if (text.length > GTX_MAX_CHARS) return null
        val sl = gtxLang(sourceLang) ?: return null
        val tl = gtxLang(targetLang) ?: return null
        val url = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=$sl&tl=$tl&dt=t"
        val body = "q=" + URLEncoder.encode(text, "UTF-8")
        val raw = httpPostForm(url, body, expectJson = true) ?: return null
        val array = org.json.JSONArray(raw)
        val sb = StringBuilder()
        for (i in 0 until array.length()) {
            val seg = array.optJSONArray(i) ?: continue
            val piece = seg.optJSONArray(0)?.optString(0) ?: continue
            if (piece.isNotEmpty() && piece != "null") sb.append(piece)
        }
        val result = sb.toString().trim()
        return result.ifEmpty { null }
    }

    private fun gtxLang(code: String): String? = when (code.trim().lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "zh-CN"
        "zh-tw", "zh-hant" -> "zh-TW"
        else -> code.trim().lowercase().ifEmpty { null }
    }

    // ─────────────────────────────────────────────────────────────
    // 端点 2：Bing 消费版翻译（网页端同款 ttranslatev3 接口）
    // 需要先 GET /translator 抓取 IG/IID/AbusePrevention token。
    // ─────────────────────────────────────────────────────────────
    private suspend fun translateViaBing(text: String, sourceLang: String, targetLang: String): String? {
        if (text.length > BING_MAX_CHARS) return null
        val from = bingLang(sourceLang) ?: return null
        val to = bingLang(targetLang) ?: return null

        var config = obtainBingConfig(forceRefresh = false)

        repeat(2) { attempt ->
            // config 会被循环尾部重赋值（changing closure），编译器无法 smart cast，
            // 这里拷贝成本地 val 再用
            val cfg = config ?: return null
            val url = "${cfg.host}/ttranslatev3?isVertical=1&IG=${cfg.ig}&IID=${cfg.iid}"
            val form = buildString {
                append("fromLang=").append(urlEnc(from))
                append("&text=").append(urlEnc(text))
                append("&to=").append(urlEnc(to))
                append("&token=").append(urlEnc(cfg.token))
                append("&key=").append(urlEnc(cfg.key))
            }
            val raw = httpPostForm(url, form, expectJson = true)
            if (raw != null && !raw.contains("ShowCaptcha") && !raw.contains("\"statusCode\"")) {
                val array = org.json.JSONArray(raw)
                val translated = array.optJSONObject(0)
                    ?.optJSONArray("translations")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                if (!translated.isNullOrEmpty()) return translated
            }
            // 401/异常：token 失效，强制刷新后重试一次
            if (attempt == 0) {
                config = obtainBingConfig(forceRefresh = true) ?: return null
            }
        }
        return null
    }

    /** 单飞获取 Bing 页面配置；TTL 内复用，401 后强制刷新。 */
    private suspend fun obtainBingConfig(forceRefresh: Boolean): BingConfig? = bingMutex.withLock {
        val cached = bingConfig
        if (!forceRefresh && cached != null &&
            System.currentTimeMillis() - cached.fetchedAt < BING_CONFIG_TTL_MS
        ) {
            return@withLock cached
        }
        for (host in BING_HOSTS) {
            val html = try {
                httpGet("$host/translator")
            } catch (e: Exception) {
                null
            } ?: continue
            val ig = Regex("IG:\"([0-9A-Fa-f]{20,40})\"").find(html)?.groupValues?.get(1)
            val iid = Regex("data-iid=\"(translator\\.[0-9]+)\"").find(html)?.groupValues?.get(1)
            val helper = Regex("params_AbusePreventionHelper\\s*=\\s*\\[(\\d+),\"([^\"]+)\"")
                .find(html)
            val key = helper?.groupValues?.get(1)
            val token = helper?.groupValues?.get(2)
            if (!ig.isNullOrBlank() && !iid.isNullOrBlank() && !key.isNullOrBlank() && !token.isNullOrBlank()) {
                val cfg = BingConfig(host, ig, iid, token, key, System.currentTimeMillis())
                bingConfig = cfg
                return@withLock cfg
            }
        }
        null
    }

    private fun bingLang(code: String): String? = when (code.trim().lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "zh-Hans"
        "zh-tw", "zh-hant" -> "zh-Hant"
        "fil", "tl" -> "fil"
        "nb", "no" -> "nb"
        else -> code.trim().lowercase().ifEmpty { null }
    }

    // ─────────────────────────────────────────────────────────────
    // 端点 3：MyMemory（GET，匿名有配额；超长文本直接跳过）
    // ─────────────────────────────────────────────────────────────
    private suspend fun translateViaMyMemory(text: String, sourceLang: String, targetLang: String): String? {
        if (text.length > MYMEMORY_MAX_CHARS) return null
        val from = myMemoryLang(sourceLang) ?: return null
        val to = myMemoryLang(targetLang) ?: return null
        val url = "https://api.mymemory.translated.net/get?q=" +
            URLEncoder.encode(text, "UTF-8") + "&langpair=" +
            URLEncoder.encode("$from|$to", "UTF-8")
        val raw = withTimeoutOrNull(ENDPOINT_TIMEOUT_MS) { httpGet(url) } ?: return null
        val json = JSONObject(raw)
        if (json.optInt("responseStatus", 200) !in 200..299) return null
        val translated = json.optJSONObject("responseData")?.optString("translatedText") ?: return null
        // 配额耗尽时 MyMemory 会把 WARNING 塞进 translatedText
        if (translated.contains("MYMEMORY WARNING") || translated.contains("QUERY LENGTH LIMIT")) return null
        val result = translated.trim()
        return result.ifEmpty { null }
    }

    private fun myMemoryLang(code: String): String? = when (code.trim().lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "zh-CN"
        "zh-tw", "zh-hant" -> "zh-TW"
        else -> code.trim().lowercase().ifEmpty { null }
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP 基础设施
    // ─────────────────────────────────────────────────────────────
    private fun httpGet(url: String): String? = openConnection(url)?.let { conn ->
        try {
            conn.requestMethod = "GET"
            readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPostForm(url: String, form: String, expectJson: Boolean): String? =
        openConnection(url)?.let { conn ->
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                if (expectJson) conn.setRequestProperty("Accept", "*/*")
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                readBody(conn)
            } finally {
                conn.disconnect()
            }
        }

    private fun openConnection(url: String): HttpURLConnection? = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8")
            instanceFollowRedirects = true
        }
    } catch (e: Exception) {
        android.util.Log.d(TAG, "openConnection failed for $url: ${e.message}")
        null
    }

    private fun readBody(conn: HttpURLConnection): String? {
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                android.util.Log.d(TAG, "HTTP $code from ${conn.url?.host}")
                return null
            }
            val stream = conn.inputStream ?: return null
            val bytes = stream.use { ins ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                while (total < 2_000_000L) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.d(TAG, "readBody failed: ${e.message}")
            null
        }
    }

    private fun urlEnc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
