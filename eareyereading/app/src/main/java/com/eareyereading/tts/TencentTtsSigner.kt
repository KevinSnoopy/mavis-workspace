package com.eareyereading.tts

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 腾讯云 TC3-HMAC-SHA256 签名与 HTTP 请求发送。
 *
 * 从 [TencentTtsEngine] 按 SRP 抽出：签名计算与 HTTP 传输是独立职责，
 * 与合成/播放逻辑解耦。
 */
internal object TencentTtsSigner {

    private const val ENDPOINT = "tts.tencentcloudapi.com"
    private const val API_URL = "https://$ENDPOINT"
    private const val SERVICE = "tts"
    private const val VERSION = "2019-08-23"
    private const val ACTION = "TextToVoice"

    /**
     * 发送 TC3-HMAC-SHA256 签名的 HTTP 请求。
     * 返回 (responseCode, responseBody)。
     */
    fun sendSignedRequest(
        requestBody: String,
        secretId: String,
        secretKey: String,
    ): Pair<Int, String> {
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
}
