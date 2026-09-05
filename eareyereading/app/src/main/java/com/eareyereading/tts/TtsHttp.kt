package com.eareyereading.tts

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/**
 * HTTP 下载原语：多代理候选、手动重定向、断点续传（206/200/416 分流）与流式落盘。
 * 均为无状态函数，供下载链（TtsModelDownloader.kt）复用。
 */
/** 日志 tag 与引擎一致，便于 logcat 统一过滤。 */
private const val TAG = "EmbeddedTtsEngine"

/**
 * 带断点续传的文件下载。
 * 若 target 已存在部分内容，通过 Range: bytes=offset- 请求续传。
 * 服务器不支持 Range 时回退为全量覆盖下载。
 */
/**
 * 构造一个走指定 Proxy 的 HttpURLConnection（仅构造不连接；调用方负责
 * 设置超时并 connect()）。失败时 catch 后由调用方决定切下一条 proxy。
 */
private fun openConnection(url: String, proxy: java.net.Proxy?): HttpURLConnection {
    val u = URL(url)
    return (if (proxy != null) u.openConnection(proxy) else u.openConnection()) as HttpURLConnection
}

internal suspend fun downloadFileWithResume(
    url: String,
    target: File,
    onChunkDownloaded: (Int, Long) -> Unit,
    onTotalSizeKnown: ((Long) -> Unit)? = null,
): Boolean {
    val existingLen = if (target.exists()) target.length() else 0L
    // 构造候选 proxy 列表：先系统代理，后常见端口兜底，最后 None（直连）
    val candidateProxies = buildProxyCandidates(url)
    var lastError: Throwable? = null
    for ((idx, p) in candidateProxies.withIndex()) {
        var conn: HttpURLConnection? = null
        // 是否已把连接交接给 streamResponse：交接后由 fullStream/appendStream
        // 关闭流，finally 里不再 disconnect
        var handedOff = false
        try {
            conn = openConnection(url, p)
            conn.connectTimeout = 8_000
            conn.readTimeout = 120_000
            conn.doInput = true
            conn.instanceFollowRedirects = false   // 关键：禁用自动跟随，自己手动跟，
                                                   // 否则 followRedirect 会丢失 Proxy
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("User-Agent", "eareyereading/1.0 Android")
            if (existingLen > 0) conn.setRequestProperty("Range", "bytes=$existingLen-")
            conn.connect()
            val code = conn.responseCode
            Log.i(TAG, "download: candidate #$idx (proxy=${describeProxy(p)}) responded HTTP $code for $url")
            when {
                code in 200..299 || code == 206 -> {
                    // 拿到响应 Content-Length 写到外面闭包的引用，供上层计算分母
                    val contentLen = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                    handedOff = true
                    return streamResponse(
                        conn, code, contentLen, existingLen, target,
                        onChunkDownloaded, onTotalSizeKnown,
                    )
                }
                code in 300..399 -> {
                    // 手动跟随重定向：复用同一个 Proxy；只跟一次以避免循环
                    val loc = conn.getHeaderField("Location")
                    Log.w(TAG, "download: HTTP $code -> Location: $loc")
                    conn.disconnect()
                    if (loc == null) return false
                    val nextUrl = if (loc.startsWith("http")) loc else {
                        // 相对路径重定向：拼成绝对 URL
                        val base = URL(url)
                        URL(base, loc).toString()
                    }
                    // 重定向 1 次，递归走一次 candidate 循环（不递归函数，避免栈深）
                    val redirectedConn = openConnection(nextUrl, p)
                    redirectedConn.connectTimeout = 8_000
                    redirectedConn.readTimeout = 120_000
                    redirectedConn.doInput = true
                    redirectedConn.instanceFollowRedirects = false
                    redirectedConn.setRequestProperty("Connection", "close")
                    redirectedConn.setRequestProperty("User-Agent", "eareyereading/1.0 Android")
                    if (existingLen > 0) redirectedConn.setRequestProperty("Range", "bytes=$existingLen-")
                    redirectedConn.connect()
                    val redirectedCode = redirectedConn.responseCode
                    Log.i(TAG, "download: redirected (via same proxy) -> HTTP $redirectedCode")
                    conn = redirectedConn
                    if (redirectedCode in 200..299 || redirectedCode == 206) {
                        val contentLen = redirectedConn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                        handedOff = true
                        return streamResponse(
                            redirectedConn, redirectedCode, contentLen, existingLen, target,
                            onChunkDownloaded, onTotalSizeKnown,
                        )
                    }
                    if (redirectedCode == 416) {
                        // 与下方 416 分支同理：残片已失效，删掉让下个候选从头下
                        Log.w(TAG, "download: HTTP 416 after redirect, deleting stale partial ${target.name}")
                        target.delete()
                        return false
                    }
                    lastError = RuntimeException("HTTP $redirectedCode after redirect")
                }
                code == 416 -> {
                    // Range 不可满足：本地残片比服务端资源还长或已失效。
                    // 删掉残片并短路返回——不删的话每个候选都会带着同一个
                    // 越界 Range 再 416 一次，死循环浪费所有镜像
                    Log.w(TAG, "download: HTTP 416, deleting stale partial ${target.name} (${existingLen}B)")
                    target.delete()
                    return false
                }
                else -> {
                    Log.e(TAG, "downloadFile: HTTP $code for $url via ${describeProxy(p)}")
                    // 4xx 不重试（除非 408 timeout）；5xx 下一条候选
                    if (code in 400..499 && code != 408) return false
                    lastError = RuntimeException("HTTP $code")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download: candidate #$idx (${describeProxy(p)}) failed: ${e.javaClass.simpleName}: ${e.message}")
            lastError = e
        } finally {
            // 没交接出去的连接统一断开；已交接给 streamResponse 的由
            // fullStream/appendStream 关流。
            // 旧实现用 conn.inputStream != null 探测——getInputStream() 有副作用
            // （部分实现在此时就开始读 socket buffer），可能把 200/206 响应的
            // 头几个字节提前消费掉，导致下载体残缺、解压必败且重试也失败
            if (!handedOff) try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
    Log.e(TAG, "download: all ${candidateProxies.size} candidates failed for $url", lastError)
    return false
}

/**
 * 构造候选 proxy 列表：
 *   [0] = ProxySelector 系统代理（如有）
 *   [1..] = 127.0.0.1 常见端口 [7897, 7892, 7890, 1080, 8888]
 *   [last] = null（直连，urlObj.openConnection() 不带 Proxy）
 *
 * 实测踩坑：MIUI 全局代理经常指向 7892 而不是 7897；批量探测可绕开
 * "代理存在但端口不对"的问题。下载主循环会以 connect 成功判定 probe 成功。
 */
private fun buildProxyCandidates(url: String): List<java.net.Proxy?> {
    val out = mutableListOf<java.net.Proxy?>()
    val uri = try { URL(url).toURI() } catch (_: Exception) { null }
    if (uri != null) {
        try {
            java.net.ProxySelector.getDefault()?.select(uri)?.forEach { out.add(it) }
        } catch (_: Exception) { /* 吞 */ }
    }
    intArrayOf(7897, 7892, 7890, 1080, 8888).forEach { port ->
        out.add(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", port)))
    }
    out.add(null)  // 直连
    return out
}

private fun describeProxy(p: java.net.Proxy?): String =
    if (p == null) "DIRECT" else "HTTP@${p.address()}"

/**
 * 按响应码把响应体写入 target：
 *  - 206 + 本地有残片 → Content-Range 校验后 append 续传；
 *  - 200（服务器忽略 Range）→ 全量覆盖（fullStream 以 truncate 模式打开）。
 *
 * 此前 206 也走 fullStream（append=false）：本地残片被截断后只写入尾部
 * 字节，产出"只有后半截"的损坏文件，length>0 又骗过完成校验 → 必解压
 * 失败。Content-Length 统一折算成"完整文件总长"汇报，让上层进度分母稳定。
 */
private suspend fun streamResponse(
    conn: HttpURLConnection,
    code: Int,
    contentLen: Long,
    existingLen: Long,
    target: File,
    onChunkDownloaded: (Int, Long) -> Unit,
    onTotalSizeKnown: ((Long) -> Unit)?,
): Boolean {
    return if (code == 206 && existingLen > 0) {
        // 续传：Content-Length 只是剩余字节数，总长要加上本地已有部分
        onTotalSizeKnown?.invoke(if (contentLen > 0) existingLen + contentLen else -1L)
        appendStream(conn, target, existingLen, onChunkDownloaded)
    } else {
        if (existingLen > 0) {
            Log.i(TAG, "server ignored Range (HTTP $code), full re-download of ${target.name}")
        }
        onTotalSizeKnown?.invoke(if (contentLen > 0) contentLen else -1L)
        fullStream(conn, target, onChunkDownloaded)
    }
}

/**
 * 续传流（append）：服务器返回 206 时从 existingLen 处继续写入。
 * Content-Range 校验失败说明本地残片与服务端资源对不上（过期/损坏），
 * 直接删掉残片返回 false，让后续候选走全量下载而不是叠加错数据。
 */
private suspend fun appendStream(
    conn: HttpURLConnection,
    target: File,
    existingLen: Long,
    onChunkDownloaded: (Int, Long) -> Unit,
): Boolean {
    val contentRange = conn.getHeaderField("Content-Range")
    val start = contentRange
        ?.substringAfter("bytes ", "")
        ?.substringBefore("-")
        ?.toLongOrNull()
    if (start == null || start != existingLen) {
        Log.w(TAG, "Content-Range mismatch (start=$start, existing=$existingLen), deleting partial and aborting resume")
        // 残片与远端对不上（重发包/远端更新）：继续保留只会让后续候选
        // 带着同样的 existingLen 反复 mismatch，删掉让下轮全量重来。
        // 响应体未被消费，外层 finally 的 inputStream 探测在 206 上会成功
        // （opened=true 不断开）——这里显式 disconnect，别留给 GC
        target.delete()
        try { conn.disconnect() } catch (_: Exception) {}
        return false
    }
    return try {
        conn.inputStream.use { input ->
            java.io.FileOutputStream(target, /* append = */ true).use { output ->
                // 256KB 缓冲：66MB 模型 tarball 旧 8KB 缓冲要 8000+ 次
                // read/write 系统调用 + 8000+ 次进度回调闭包，纯 CPU 浪费
                val buffer = ByteArray(262144)
                var sinceCheck = 0
                var totalRead = existingLen
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    totalRead += read
                    onChunkDownloaded(read, totalRead)
                    sinceCheck += read
                    if (sinceCheck >= 262144) {
                        sinceCheck = 0
                        kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                            ?.ensureActive()
                    }
                }
            }
        }
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "appendStream failed", e)
        false
    }
}

private suspend fun fullStream(
    conn: HttpURLConnection,
    target: File,
    onChunkDownloaded: (Int, Long) -> Unit,
): Boolean {
    return try {
        Log.i(TAG, "fullStream: opening input stream from $conn")
        conn.inputStream.use { input ->
            java.io.FileOutputStream(target, /* append = */ false).use { output ->
                // 256KB 缓冲：与 appendStream 同款，理由见彼处注释
                val buffer = ByteArray(262144)
                var totalRead = 0L
                var sinceCheck = 0L
                var lastTraceMs = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) {
                        Log.i(TAG, "fullStream: EOF, totalBytes=$totalRead")
                        break
                    }
                    output.write(buffer, 0, read)
                    totalRead += read
                    // 把本 batch 的字节数 + 累计字节吐给上层，让上层算 progress 分母
                    onChunkDownloaded(read, totalRead)
                    // 每 500ms 打一次 trace：能看到 byte stream 真在流
                    val now = System.currentTimeMillis()
                    if (now - lastTraceMs > 500) {
                        lastTraceMs = now
                        Log.d(TAG, "fullStream: progress ${totalRead / 1024}KB")
                    }
                    // 同 appendStream：周期性响应协程取消。
                    // 旧实现用 totalRead % 262144 < read 概率探测，末尾
                    // chunk 越小命中概率越低（<1KB 时 ~0.4%），最后 256KB
                    // 基本不响应取消——改 sinceCheck 累加器（与 appendStream 一致）
                    sinceCheck += read
                    if (sinceCheck >= 262144L) {
                        sinceCheck = 0
                        kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                            ?.ensureActive()
                    }
                }
            }
        }
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "fullStream failed", e)
        false
    }
}
