package com.eareyereading.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 词典下载器（issue 13：从 DictionaryManager 按 SRP 拆出）。
 *
 * 职责：网络下载（text / file）、下载并发准入、进度节流推送、
 * 下载产物最小内容校验。下载状态通过 [statuses] 共享给门面，
 * 由门面在 manifest 刷新/删除/切换时重建列表时保留 in-flight 条目。
 *
 * @param dictDir 词典文件目录（由门面惰性建好后传入）
 * @param statuses 词典状态流（由门面持有，下载器更新 downloading/progress 字段）
 * @param manifestProvider 解析 manifest 的回调（由门面提供，下载器不直接碰 manifest 文件）
 * @param fileResolver 把 manifest 里的 fileName 落到 dictDir 内的安全文件（由门面提供）
 */
internal class DictionaryDownloader(
    private val dictDir: File,
    private val statuses: MutableStateFlow<List<DictionaryStatus>>,
    private val manifestProvider: () -> DictionaryManifest?,
    private val fileResolver: (String) -> File?,
) {
    companion object {
        // issue 10.1：响应体字节上限，防恶意/畸形成员把内存打爆或磁盘写满。
        // 文本类（manifest）上限 1MB；词典文件上限 500MB（分级词表实际只有几十 MB）。
        internal const val MAX_TEXT_BYTES = 1L * 1024 * 1024
        internal const val MAX_FILE_BYTES = 500L * 1024 * 1024

        // 下载进度推送节流：定量进度按步进（1%）节流，不定量（-1f）按时间节流。
        // 旧实现每读 256KB 就全列表拷贝 + StateFlow 发射，100MB 词典 ≈ 400 次
        internal const val DOWNLOAD_PROGRESS_STEP = 0.01f
        internal const val DOWNLOAD_PROGRESS_INTERVAL_MS = 250L
    }

    // 下载中的词典 id 集合：防双击/刷新后按钮复活引发同一 .tmp 文件
    // 两个下载协程交错写入（产物损坏且 rename 会把坏文件转正）
    private val downloadingIds = mutableSetOf<String>()

    /**
     * 下载指定词典。成功返回 true。
     * progress 回调在 IO 线程触发；响应无 Content-Length（chunked）时
     * 回调 -1f，UI 侧按不定量进度渲染。
     */
    suspend fun download(dictId: String, onProgress: (Float) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            // 同一词典只允许一个下载在途：双击、或刷新翻回按钮再点，
            // 都会对同一 .tmp 并发写入，产物损坏后被 rename 转正
            val admitted = synchronized(downloadingIds) {
                if (dictId in downloadingIds) false
                else { downloadingIds.add(dictId); true }
            }
            if (!admitted) return@withContext false

            try {
                // 准入后所有出口统一走末尾 finally 清理：原先多个提前 return
                // 各自手动删除，若窗口内抛未预期异常，dictId 会永久残留、
                // 该词典之后再也不能下载
                val manifest = manifestProvider()
                val info = manifest?.dictionaries?.find { it.id == dictId }
                    ?: return@withContext false
                val dest = fileResolver(info.fileName)
                if (dest == null) {
                    android.util.Log.w("DictionaryManager", "词典 ${info.name} 的文件名非法: ${info.fileName}")
                    return@withContext false
                }
                if (info.downloadUrl.startsWith("REPLACE_WITH")) {
                    android.util.Log.w("DictionaryManager", "词典 ${info.name} 的下载地址未配置")
                    return@withContext false
                }

                // 标记下载中
                updateStatusDownloading(dictId, true, 0f)
                // 进度节流：updateStatusDownloading 是全列表拷贝 + StateFlow 发射，
                // 每 256KB 触发一次会驱动 UI 每秒数百次重组
                var lastPushedProgress = 0f
                var lastPushAtMs = 0L
                try {
                    downloadFile(info.downloadUrl, dest) { p ->
                        val now = System.currentTimeMillis()
                        val shouldPush = when {
                            p < 0f -> now - lastPushAtMs >= DOWNLOAD_PROGRESS_INTERVAL_MS
                            p - lastPushedProgress >= DOWNLOAD_PROGRESS_STEP -> true
                            else -> false
                        }
                        if (shouldPush) {
                            lastPushedProgress = if (p > lastPushedProgress) p else lastPushedProgress
                            lastPushAtMs = now
                            updateStatusDownloading(dictId, true, p)
                        }
                        onProgress(p)
                    }
                    // 内容最小校验：HTTP 200 的 CDN 错误页/自举门户页也会被写入，
                    // 不校验就 rename 转正，之后查词静默全 miss
                    if (!looksLikeDictionary(dest)) {
                        dest.delete()
                        throw IOException("Downloaded content is not a valid dictionary")
                    }
                    updateStatusDownloading(dictId, false, 0f)
                    true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    updateStatusDownloading(dictId, false, 0f)
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("DictionaryManager", "下载词典 ${info.name} 失败: ${e.message}")
                    updateStatusDownloading(dictId, false, 0f)
                    false
                }
            } finally {
                synchronized(downloadingIds) { downloadingIds.remove(dictId) }
            }
        }

    /** 下载 manifest 文本（带字节上限保护）。 */
    internal fun downloadText(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode} for $urlStr")
            }
            // issue 10.1：text 也设上限——manifest 被 CDN 换成畸形大文件时
            // 不再把整个读进内存。超限抛 IOException，refreshManifest catch 后降级。
            val limited = LimitInputStream(conn.inputStream, MAX_TEXT_BYTES)
            return limited.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 词典文件格式为每行 `word|definition`：至少有一行合法条目才算有效。 */
    private fun looksLikeDictionary(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            file.bufferedReader().useLines { lines ->
                lines.take(200).any { line ->
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#")) return@any false
                    val sep = t.indexOf('|')
                    sep > 0 && sep < t.length - 1
                }
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun updateStatusDownloading(dictId: String, downloading: Boolean, progress: Float) {
        // 原子 CAS 更新：并发下载/并发刷新状态时不丢更新
        statuses.update { list ->
            list.map { s ->
                if (s.info.id == dictId) s.copy(downloading = downloading, progress = progress)
                else s
            }
        }
    }

    /**
     * 下载文件：先写 .tmp 再原子改名，避免中途失败留下半截文件被当成完整词典加载。
     */
    private fun downloadFile(urlStr: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode} for $urlStr")
            }
            val total = conn.contentLengthLong
            var done = 0L
            // issue 10.1：服务器声明的长度即已超限则直接终止，不再起流
            if (total > MAX_FILE_BYTES) {
                throw IOException("File too large ($total bytes, limit $MAX_FILE_BYTES)")
            }
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(262144)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        done += n
                        if (done > MAX_FILE_BYTES) {
                            throw IOException("File too large ($done bytes, limit $MAX_FILE_BYTES)")
                        }
                        output.write(buf, 0, n)
                        if (total > 0) {
                            // 限幅：响应体大于声明长度时不得显示 >100%
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        } else {
                            // chunked/无 Content-Length：-1f 哨兵让 UI 走不定量进度，
                            // 否则定量进度条永远 0% 看起来像卡死
                            onProgress(-1f)
                        }
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 限制读取的输入流装饰器：读到 maxBytes 仍不足即抛 IOException，
     * 让下载/解析统一走各自的失败路径，不把超大响应体读进内存。
     */
    private class LimitInputStream(delegate: InputStream, private val maxBytes: Long)
        : FilterInputStream(delegate) {
        private var read = 0L
        override fun read(): Int {
            val b = super.read()
            if (b < 0) return b
            if (++read > maxBytes) throw IOException("Stream exceeded $maxBytes bytes")
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n < 0) return n
            read += n
            if (read > maxBytes) throw IOException("Stream exceeded $maxBytes bytes")
            return n
        }
    }
}
