package com.eareyereading.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 腾讯云 TTS 在线引擎。
 *
 * - 100 万字/月免费（个人开发者注册即享）
 * - 国内可达性顶级（腾讯云基础设施）
 * - 101 种音色，中英多音色
 * - HTTP API + TC3-HMAC-SHA256 签名，返回 base64 PCM 16kHz 16-bit mono
 * - 非流式：单次请求 ≤150 字，整段合成完返回（短文本延迟 ~300-500ms）
 *
 * 2026-09-06 引入：Edge TTS 国内不稳定（ping timeout），腾讯云作为国内稳定方案。
 * 需用户在设置页配置 SecretId / SecretKey（腾讯云控制台获取）。
 */
@Singleton
class TencentTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "TencentTtsEngine"

        // 腾讯云 TTS API 端点
        private const val ENDPOINT = "tts.tencentcloudapi.com"
        private const val API_URL = "https://$ENDPOINT"

        // 签名参数
        private const val SERVICE = "tts"
        private const val VERSION = "2019-08-23"
        private const val ACTION = "TextToVoice"

        // 腾讯云 TTS 输出 PCM 16kHz 16-bit mono
        private const val TENCENT_SAMPLE_RATE = 16000

        // 单次请求文本上限（腾讯云基础版限制）
        private const val MAX_TEXT_CHARS = 150
    }

    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private val playbackAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val trackSlot = AudioTrackSlot()

    private val _externalStop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val externalStop: SharedFlow<Unit> = _externalStop.asSharedFlow()

    private val audioFocus = TtsAudioFocusController(
        audioManager = audioManager,
        audioAttributes = playbackAudioAttributes,
        onFocusLost = {
            _externalStop.tryEmit(Unit)
            stop()
        },
    )

    fun abandonAudioFocus() {
        audioFocus.abandonIfHeld()
    }

    private val isPlaying = AtomicBoolean(false)

    @Volatile
    private var currentVoiceId: Int = 101001 // 默认智瑜（女声）

    @Volatile
    private var currentSpeed: Float = 1.0f

    @Volatile
    private var secretId: String = ""

    @Volatile
    private var secretKey: String = ""

    private var scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentSynthJob: Job? = null

    /** 设置腾讯云凭证（设置页配置后调用） */
    fun setCredentials(id: String, key: String) {
        secretId = id
        secretKey = key
        Log.i(TAG, "setCredentials: id=${id.take(8)}..., key=${key.take(4)}...")
    }

    fun hasCredentials(): Boolean = secretId.isNotEmpty() && secretKey.isNotEmpty()

    /** 设置音色 id（腾讯云音色 id，如 101001=智瑜女声） */
    fun setVoiceId(voiceId: Int) {
        currentVoiceId = voiceId
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed.coerceIn(0.5f, 2.0f)
    }

    /**
     * 初始化：腾讯云无需加载模型，有凭证即就绪。
     */
    suspend fun initialize(language: String? = null): Boolean {
        if (!hasCredentials()) {
            Log.w(TAG, "initialize: no credentials configured")
            return false
        }
        // 按语言选默认音色
        if (language != null) {
            val defaultVoice = defaultTencentVoiceForLanguage(language)
            if (currentVoiceId == 101001 && language.startsWith("en")) {
                currentVoiceId = defaultVoice
            }
        }
        Log.i(TAG, "initialize: ready (voice=$currentVoiceId)")
        return true
    }

    suspend fun speak(text: String, speed: Float = currentSpeed): Boolean {
        if (text.isBlank()) return true
        if (!hasCredentials()) {
            Log.w(TAG, "speak: no credentials")
            return false
        }
        return synthesizeAndPlay(listOf(text), speed)
    }

    suspend fun speakSentencesStreaming(
        sentences: List<String>,
        speed: Float = currentSpeed,
        onSentenceDone: (Int) -> Unit,
    ): Boolean {
        if (sentences.isEmpty()) return true
        if (!hasCredentials()) return false
        return synthesizeAndPlay(sentences, speed, onSentenceDone)
    }

    /**
     * 核心合成+播放循环：逐句发 HTTP 请求，PCM 喂 StreamingTrackPlayer。
     * 腾讯云非流式——每句等完整 PCM 返回再播，但句 i 播放时句 i+1 已在合成。
     */
    private suspend fun synthesizeAndPlay(
        sentences: List<String>,
        speed: Float,
        onSentenceDone: (Int) -> Unit = {},
    ): Boolean {
        currentSynthJob?.cancel()
        val job = kotlin.coroutines.coroutineContext[Job]
        currentSynthJob = job
        isPlaying.set(true)

        val player = StreamingTrackPlayer(
            sampleRate = TENCENT_SAMPLE_RATE,
            audioManager = audioManager,
            audioAttributes = playbackAudioAttributes,
            trackSlot = trackSlot,
            requestAudioFocus = audioFocus::requestIfNeeded,
        )

        try {
            return withContext(Dispatchers.IO) {
                for ((index, sentence) in sentences.withIndex()) {
                    if (sentence.isBlank()) {
                        onSentenceDone(index)
                        continue
                    }
                    if (!scope.isActive || job?.isActive == false) return@withContext false

                    // 腾讯云单次 ≤150 字，超长分段
                    val chunks = splitText(sentence, MAX_TEXT_CHARS)
                    for (chunk in chunks) {
                        if (!scope.isActive || job?.isActive == false) return@withContext false
                        val pcm = synthesizeOneChunk(chunk, speed)
                        if (pcm == null) return@withContext false
                        val samples = pcmBytesToFloats(pcm)
                        if (samples.isNotEmpty()) {
                            player.offer(samples)
                        }
                    }
                    player.awaitWatermark(player.framesOffered)
                    onSentenceDone(index)
                }
                player.awaitWatermark(player.framesOffered)
                true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "synthesizeAndPlay failed", e)
            return false
        } finally {
            player.releaseIfCurrent()
            isPlaying.set(false)
        }
    }

    /** 按最大字符数分段（尽量在空格/标点处断） */
    private fun splitText(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val result = mutableListOf<String>()
        var remaining = text
        while (remaining.length > maxChars) {
            // 在 maxChars 范围内找最后一个空格/标点
            var cut = maxChars
            for (i in maxChars downTo 1) {
                if (remaining[i - 1] == ' ' || remaining[i - 1] == ',' || remaining[i - 1] == '，' ||
                    remaining[i - 1] == '.' || remaining[i - 1] == '。'
                ) {
                    cut = i
                    break
                }
            }
            result.add(remaining.substring(0, cut))
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotEmpty()) result.add(remaining)
        return result
    }

    /**
     * 合成一段文本（≤150 字）：发 HTTP 请求，返回 PCM 字节。
     */
    private suspend fun synthesizeOneChunk(text: String, speed: Float): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val sessionId = UUID.randomUUID().toString().replace("-", "").take(20)
                // 腾讯云 Speed: [-2, 6] float，0=1.0 倍；线性映射 speed∈[0.5,2.0] → [-2, 6]
                val speedParam = ((speed - 1.0f) * 6.0f).coerceIn(-2.0f, 6.0f)
                // PrimaryLanguage: 1=中文 2=英文（按文本自动判断）
                val primaryLanguage = if (text.any { it.code in 0x4E00..0x9FFF }) 1 else 2

                val requestBody = JSONObject().apply {
                    put("Text", text)
                    put("SessionId", sessionId)
                    put("ModelType", 1) // 1=基础版
                    put("VoiceType", currentVoiceId)
                    put("PrimaryLanguage", primaryLanguage)
                    put("SampleRate", TENCENT_SAMPLE_RATE)
                    put("Codec", "pcm")
                    put("Speed", speedParam)
                    put("Volume", 0) // 0=正常
                }.toString()

                val (responseCode, responseBody) = sendSignedRequest(requestBody)
                if (responseCode != 200) {
                    Log.e(TAG, "TTS API failed: $responseCode, body=${responseBody.take(200)}")
                    return@withContext null
                }

                val json = JSONObject(responseBody)
                val response = json.optJSONObject("Response")
                if (response == null) {
                    Log.e(TAG, "No Response field: $responseBody")
                    return@withContext null
                }
                if (response.has("Error")) {
                    Log.e(TAG, "TTS API error: ${response.getJSONObject("Error")}")
                    return@withContext null
                }
                val audioBase64 = response.getString("Audio")
                Base64.decode(audioBase64, Base64.DEFAULT)
            } catch (e: Exception) {
                Log.e(TAG, "synthesizeOneChunk failed", e)
                null
            }
        }

    /**
     * 发送 TC3-HMAC-SHA256 签名的 HTTP 请求。
     * 返回 (responseCode, responseBody)。
     */
    private fun sendSignedRequest(requestBody: String): Pair<Int, String> {
        val timestamp = System.currentTimeMillis() / 1000
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val date = sdf.format(Date(timestamp * 1000))

        // 1. CanonicalRequest
        val hashedPayload = sha256Hex(requestBody)
        val canonicalRequest = buildString {
            append("POST\n")
            append("/\n") // URI
            append("\n") // QueryString (empty)
            append("content-type:application/json; charset=utf-8\n")
            append("host:$ENDPOINT\n")
            append("x-tc-action:${ACTION.lowercase()}\n")
            append("\n") // end of headers
            append("content-type;host;x-tc-action\n") // SignedHeaders
            append(hashedPayload)
        }

        // 2. StringToSign
        val credentialScope = "$date/$SERVICE/tc3_request"
        val stringToSign = buildString {
            append("TC3-HMAC-SHA256\n")
            append("$timestamp\n")
            append(credentialScope)
            append("\n")
            append(sha256Hex(canonicalRequest))
        }

        // 3. Signature
        val secretDate = hmacSha256(("TC3$secretKey").toByteArray(), date)
        val secretService = hmacSha256(secretDate, SERVICE)
        val secretSigning = hmacSha256(secretService, "tc3_request")
        val signature = hmacSha256(secretSigning, stringToSign).toHex()

        // 4. Authorization
        val authorization = "TC3-HMAC-SHA256 " +
            "Credential=$secretId/$credentialScope, " +
            "SignedHeaders=content-type;host;x-tc-action, " +
            "Signature=$signature"

        // 5. 发请求
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Host", ENDPOINT)
            setRequestProperty("Authorization", authorization)
            setRequestProperty("X-TC-Action", ACTION)
            setRequestProperty("X-TC-Version", VERSION)
            setRequestProperty("X-TC-Region", "ap-guangzhou")
            setRequestProperty("X-TC-Timestamp", timestamp.toString())
            doOutput = true
        }

        try {
            conn.outputStream.use { it.write(requestBody.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            }
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    // ── 签名工具 ──

    private fun sha256Hex(data: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(StandardCharsets.UTF_8))
            .toHex()

    private fun hmacSha256(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }.doFinal(data.toByteArray(StandardCharsets.UTF_8))

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    /** PCM 16-bit little-endian bytes → FloatArray（归一化） */
    private fun pcmBytesToFloats(bytes: ByteArray): FloatArray {
        val sampleCount = bytes.size / 2
        val floats = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            floats[i] = sample / 32768.0f
        }
        return floats
    }

    fun stop() {
        currentSynthJob?.cancel()
        currentSynthJob = null
        isPlaying.set(false)
        synchronized(trackSlot.lock) {
            try {
                trackSlot.track?.let {
                    if (it.state == android.media.AudioTrack.STATE_INITIALIZED) {
                        it.pause()
                        it.flush()
                    }
                    it.release()
                }
            } catch (_: Exception) {}
            trackSlot.track = null
        }
    }

    fun isPlaying(): Boolean = isPlaying.get()

    suspend fun release() {
        stop()
        audioFocus.abandonIfHeld()
        scope.cancel()
        scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** 按书语言选默认腾讯云音色 id */
fun defaultTencentVoiceForLanguage(language: String?): Int =
    when {
        language == null -> 101001 // 智瑜·女声
        language.startsWith("zh") -> 101001 // 智瑜·中文女声
        else -> 101001 // 智瑜也支持英文
    }

/**
 * 腾讯云 TTS 常用音色。
 * id 来自腾讯云 TTS 文档 VoiceType 参数。
 */
data class TencentVoice(
    val id: Int,
    val displayName: String,
    val language: String,
)

val TENCENT_VOICES: List<TencentVoice> = listOf(
    TencentVoice(101001, "智瑜 · 中文女声（精品）", "zh"),
    TencentVoice(101002, "智瑜 · 中文女声（精品2）", "zh"),
    TencentVoice(101003, "智玲 · 中文女声", "zh"),
    TencentVoice(101004, "智美 · 中文女声", "zh"),
    TencentVoice(101005, "智云 · 中文男声", "zh"),
    TencentVoice(101006, "智莉 · 中文女声", "zh"),
    TencentVoice(101007, "智娜 · 中文女声", "zh"),
    TencentVoice(101008, "智琪 · 中文男声", "zh"),
    TencentVoice(101009, "智杰 · 中文男声", "zh"),
    TencentVoice(101011, "智芸 · 中文女声", "zh"),
    TencentVoice(101012, "智坤 · 中文男声", "zh"),
    TencentVoice(101013, "智丹 · 中文女声", "zh"),
    TencentVoice(101014, "智辉 · 中文男声", "zh"),
    TencentVoice(101015, "智宁 · 中文女声", "zh"),
    TencentVoice(101016, "智燕 · 中文女声", "zh"),
    TencentVoice(101017, "智华 · 中文男声", "zh"),
    TencentVoice(101018, "智燕 · 中文女声2", "zh"),
    TencentVoice(101019, "智辉 · 中文男声2", "zh"),
    TencentVoice(101020, "智蓉 · 中文女声", "zh"),
    TencentVoice(101021, "智靖 · 中文男声", "zh"),
    TencentVoice(101022, "智萱 · 中文女声", "zh"),
    TencentVoice(101023, "智溪 · 中文女声", "zh"),
    TencentVoice(101024, "智谦 · 中文男声", "zh"),
    TencentVoice(101025, "智白 · 中文女声", "zh"),
    TencentVoice(101026, "智宁 · 中文男声", "zh"),
    TencentVoice(101027, "智彤 · 中文女声", "zh"),
    TencentVoice(101028, "智蓝 · 中文女声", "zh"),
    TencentVoice(101029, "智元 · 中文男声", "zh"),
    TencentVoice(101030, "智剑 · 中文男声", "zh"),
    TencentVoice(101031, "智莱 · 中文女声", "zh"),
    TencentVoice(101032, "智香 · 中文女声", "zh"),
    TencentVoice(101033, "智丹 · 中文女声2", "zh"),
    TencentVoice(101034, "智琴 · 中文女声", "zh"),
    TencentVoice(101035, "智婷 · 中文女声", "zh"),
    TencentVoice(101036, "智娥 · 中文女声", "zh"),
    TencentVoice(101037, "智媛 · 中文女声", "zh"),
    TencentVoice(101038, "智华 · 中文女声", "zh"),
    TencentVoice(101039, "智晶 · 中文女声", "zh"),
    TencentVoice(101040, "智悦 · 中文女声", "zh"),
    TencentVoice(101041, "智贝 · 中文女声", "zh"),
    TencentVoice(101042, "智倩 · 中文女声", "zh"),
    TencentVoice(101043, "智歆 · 中文女声", "zh"),
    TencentVoice(101044, "智鹏 · 中文男声", "zh"),
    TencentVoice(101045, "智熙 · 中文男声", "zh"),
    TencentVoice(101046, "智玛 · 中文女声", "zh"),
    TencentVoice(101047, "智波 · 中文男声", "zh"),
    TencentVoice(101048, "智云 · 中文女声", "zh"),
    TencentVoice(101049, "智刚 · 中文男声", "zh"),
    TencentVoice(101050, "智茹 · 中文女声", "zh"),
    TencentVoice(101051, "智铃 · 中文女声", "zh"),
    TencentVoice(101052, "智浩 · 中文男声", "zh"),
    TencentVoice(101053, "智心 · 中文女声", "zh"),
    TencentVoice(101054, "智笙 · 中文男声", "zh"),
    TencentVoice(101055, "智宁 · 中文女声3", "zh"),
    TencentVoice(101056, "智皓 · 中文男声", "zh"),
    TencentVoice(101057, "智柔 · 中文女声", "zh"),
    TencentVoice(101058, "智米 · 中文女声", "zh"),
    TencentVoice(101059, "智果 · 中文女声", "zh"),
    TencentVoice(101060, "智虹 · 中文女声", "zh"),
    TencentVoice(101061, "智娥 · 中文女声2", "zh"),
    TencentVoice(101062, "智言 · 中文女声", "zh"),
    TencentVoice(101063, "智娜 · 中文女声2", "zh"),
    TencentVoice(101064, "智梅 · 中文女声", "zh"),
    TencentVoice(101065, "智桦 · 中文男声", "zh"),
    TencentVoice(101066, "智春 · 中文女声", "zh"),
    TencentVoice(101067, "智健 · 中文男声", "zh"),
    TencentVoice(101068, "智明 · 中文男声", "zh"),
    TencentVoice(101069, "智严 · 中文男声", "zh"),
    TencentVoice(101070, "智静 · 中文女声", "zh"),
    TencentVoice(101071, "智强 · 中文男声", "zh"),
    TencentVoice(101072, "智纯 · 中文女声", "zh"),
    TencentVoice(101073, "智杨 · 中文男声", "zh"),
    TencentVoice(101074, "智韩 · 中文男声", "zh"),
    TencentVoice(101075, "智翠 · 中文女声", "zh"),
    TencentVoice(101076, "智冬 · 中文女声", "zh"),
    TencentVoice(101077, "智凤 · 中文女声", "zh"),
    TencentVoice(101078, "智红 · 中文女声", "zh"),
    TencentVoice(101079, "智龙 · 中文男声", "zh"),
    TencentVoice(101080, "智琴 · 中文女声2", "zh"),
    TencentVoice(101081, "智秀 · 中文女声", "zh"),
    TencentVoice(101082, "智旭 · 中文男声", "zh"),
    TencentVoice(101083, "智阳 · 中文男声", "zh"),
    TencentVoice(101084, "智凡 · 中文男声", "zh"),
    TencentVoice(101085, "智海 · 中文男声", "zh"),
    TencentVoice(101086, "智亮 · 中文男声", "zh"),
    TencentVoice(101087, "智波 · 中文男声2", "zh"),
    TencentVoice(101088, "智霖 · 中文男声", "zh"),
    TencentVoice(101089, "智杰 · 中文男声2", "zh"),
    TencentVoice(101090, "智宇 · 中文男声", "zh"),
    TencentVoice(101091, "智威 · 中文男声", "zh"),
    TencentVoice(101092, "智忠 · 中文男声", "zh"),
    TencentVoice(101093, "智卿 · 中文男声", "zh"),
    TencentVoice(101094, "智丽 · 中文女声", "zh"),
    TencentVoice(101095, "智国 · 中文男声", "zh"),
    TencentVoice(101096, "智星 · 中文男声", "zh"),
    TencentVoice(101097, "智夏 · 中文女声", "zh"),
    TencentVoice(101098, "智雨 · 中文女声", "zh"),
    TencentVoice(101099, "智风 · 中文男声", "zh"),
    TencentVoice(101100, "智雪 · 中文女声", "zh"),
    TencentVoice(101101, "智梦 · 中文女声", "zh"),
)
