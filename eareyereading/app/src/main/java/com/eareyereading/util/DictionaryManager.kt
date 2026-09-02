package com.eareyereading.util

import android.content.Context
import com.eareyereading.BuildConfig
import com.eareyereading.data.local.dao.DictionaryEntryDao
import com.eareyereading.data.local.entity.DictionaryEntryEntity
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 词典元数据（来自 manifest.json）
 */
data class DictionaryInfo(
    val id: String,
    val name: String,
    val description: String,
    val entryCount: Int,
    val sizeBytes: Long,
    val fileName: String,
    val downloadUrl: String,
)

/**
 * manifest.json 结构
 */
data class DictionaryManifest(
    val version: Int,
    val dictionaries: List<DictionaryInfo>,
)

/**
 * 单个词典的运行时状态
 */
data class DictionaryStatus(
    val info: DictionaryInfo,
    val downloaded: Boolean,
    val downloading: Boolean,
    val progress: Float,  // 0..1
    val active: Boolean,  // 是否为当前选中
)

/**
 * 词典管理器
 *
 * 负责词典的发现（从 manifest）、下载、删除、切换、查询。
 * 词典文件存储在 context.filesDir/dictionaries/{id}.txt
 */
@Singleton
class DictionaryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dictionaryEntryDao: DictionaryEntryDao,
) {
    companion object {
        // manifest.json 的下载地址（托管在 jsDelivr CDN，从 GitHub 仓库拉取）。
        // issue 12.3：不再硬编码 const，改由 BuildConfig 注入（app/build.gradle.kts
        // 的 buildConfigField DICTIONARY_MANIFEST_URL），便于替换源/锁版本。
        val MANIFEST_URL: String = BuildConfig.DICTIONARY_MANIFEST_URL

        private const val DICT_DIR_NAME = "dictionaries"
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val ACTIVE_DICT_PREFS = "dict_prefs"
        private const val ACTIVE_DICT_KEY = "active_dict_id"

        // issue 10.1：响应体字节上限，防恶意/畸形成员把内存打爆或磁盘写满。
        // 文本类（manifest）上限 1MB；词典文件上限 500MB（分级词表实际只有几十 MB）。
        private const val MAX_TEXT_BYTES = 1L * 1024 * 1024
        private const val MAX_FILE_BYTES = 500L * 1024 * 1024

        // issue 12.5：小于该字节数的词典仍整份载内存（保最快）；
        // 大于等于该值视为大词典，写 Room 表按需单条查询，避免 OOM。
        private const val LARGE_DICT_THRESHOLD_BYTES = 10L * 1024 * 1024

        // 大词典按需查询的批量写入批次大小（毫秒级小节流，避免单次事务过大）
        private const val BIG_DICT_IMPORT_BATCH = 2000

        // 大词典最近命中的小 LRU：阅读/RSVP 热路径同一词会反复查询，
        // 缓存最近命中可大幅减少对 Room 的单条查询次数。
        private const val BIG_DICT_LRU_MAX = 256
    }

    private val gson = Gson()
    private val dictDir = File(context.filesDir, DICT_DIR_NAME).apply { mkdirs() }

    private val _statuses = MutableStateFlow<List<DictionaryStatus>>(emptyList())
    val statuses: StateFlow<List<DictionaryStatus>> = _statuses.asStateFlow()

    private val _activeDictId = MutableStateFlow<String?>(null)
    val activeDictId: StateFlow<String?> = _activeDictId.asStateFlow()

    private val _manifestError = MutableStateFlow<String?>(null)
    val manifestError: StateFlow<String?> = _manifestError.asStateFlow()

    // 当前已加载到内存的词典（按 activeDictId 对应的文件加载，仅小词典）
    @Volatile
    private var loadedDict: Map<String, String>? = null
    @Volatile
    private var loadedDictId: String? = null

    // issue 12.5：大词典最近命中缓存（accessOrder=true 即访问序 LRU）。
    // key 用 "dictId\u0000word"，跨词典复用互不污染；仅在方法内 synchronized 访问。
    private val bigDictCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > BIG_DICT_LRU_MAX
    }

    // 下载中的词典 id 集合：防双击/刷新后按钮复活引发同一 .tmp 文件
    // 两个下载协程交错写入（产物损坏且 rename 会把坏文件转正）
    private val downloadingIds = mutableSetOf<String>()

    // 后台状态刷新用：setActiveDict 等公共入口不得在 Main 线程做
    // manifest 解析/文件存在性检查
    private val bgScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )

    init {
        // 恢复上次选中的词典
        val savedActive = context.getSharedPreferences(ACTIVE_DICT_PREFS, Context.MODE_PRIVATE)
            .getString(ACTIVE_DICT_KEY, null)
        _activeDictId.value = savedActive

        // 如果有缓存的 manifest，先加载它（离线可用）。
        // 磁盘解析放后台：单例在 Hilt 注入时于 Main 线程构造
        bgScope.launch { loadCachedManifest() }
    }

    /**
     * 从网络刷新词典列表（manifest.json）。
     * 失败时保留缓存数据，设置 manifestError。
     */
    suspend fun refreshManifest(): Boolean = withContext(Dispatchers.IO) {
        if (MANIFEST_URL.startsWith("REPLACE_WITH")) {
            _manifestError.value = "词典源未配置，请在 DictionaryManager.MANIFEST_URL 设置 manifest 地址"
            return@withContext false
        }
        try {
            val json = downloadText(MANIFEST_URL)
            val manifestFile = File(dictDir, MANIFEST_FILE_NAME)
            // 原子写：先 .tmp 再改名，进程中途被杀不会留下半截缓存
            // 让下次启动解析失败降级成空列表
            val tmp = File(dictDir, "$MANIFEST_FILE_NAME.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(manifestFile)) {
                tmp.copyTo(manifestFile, overwrite = true)
                tmp.delete()
            }
            _manifestError.value = null
            updateStatuses()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("DictionaryManager", "刷新 manifest 失败: ${e.message}")
            _manifestError.value = "无法获取词典列表：${e.message}"
            false
        }
    }

    private fun loadCachedManifest() {
        val manifestFile = File(dictDir, MANIFEST_FILE_NAME)
        if (!manifestFile.exists()) return
        try {
            updateStatuses()
        } catch (e: Exception) {
            android.util.Log.w("DictionaryManager", "加载缓存 manifest 失败: ${e.message}")
        }
    }

    private fun parseManifest(): DictionaryManifest? {
        val manifestFile = File(dictDir, MANIFEST_FILE_NAME)
        if (!manifestFile.exists()) return null
        return try {
            gson.fromJson(manifestFile.readText(), DictionaryManifest::class.java)
        } catch (e: Exception) {
            // manifest 损坏/半截写入时降级为无列表，但要留痕便于排查
            android.util.Log.w("DictionaryManager", "parse manifest failed", e)
            null
        }
    }

    private fun updateStatuses() {
        val manifest = parseManifest() ?: return
        val active = _activeDictId.value
        // 重建状态时保留正在下载条目的 downloading/progress：
        // 否则刷新/删除/切换触发重建会把下载中卡片翻回"下载"按钮，
        // 用户再点一次就触发并发下载（同一 .tmp 双写）
        val inFlight = _statuses.value
            .filter { it.downloading }
            .associateBy { it.info.id }
        _statuses.value = manifest.dictionaries.map { info ->
            val file = safeDictFile(info.fileName)
            val flying = inFlight[info.id]
            DictionaryStatus(
                info = info,
                downloaded = file?.exists() == true,
                downloading = flying != null,
                progress = flying?.progress ?: 0f,
                active = info.id == active,
            )
        }
    }

    /**
     * 把 manifest 里的 fileName 落到 dictDir 内的安全文件。
     * manifest 来自远端 CDN，fileName 不可信：去掉路径段并校验
     * canonical 路径仍在 dictDir 内，防路径穿越写入/删除沙箱内任意文件。
     */
    private fun safeDictFile(fileName: String): File? {
        val name = fileName.substringAfterLast('/')
        if (name.isEmpty() || name == "." || name == "..") return null
        val file = File(dictDir, name)
        return try {
            if (file.canonicalPath.startsWith(dictDir.canonicalPath + File.separator)) file else null
        } catch (_: java.io.IOException) {
            null
        }
    }

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
                val manifest = parseManifest()
                val info = manifest?.dictionaries?.find { it.id == dictId }
                    ?: return@withContext false
                val dest = safeDictFile(info.fileName)
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
                try {
                    downloadFile(info.downloadUrl, dest) { p ->
                        updateStatusDownloading(dictId, true, p)
                        onProgress(p)
                    }
                    // 内容最小校验：HTTP 200 的 CDN 错误页/自举门户页也会被写入，
                    // 不校验就 rename 转正，之后查词静默全 miss
                    if (!looksLikeDictionary(dest)) {
                        dest.delete()
                        throw java.io.IOException("Downloaded content is not a valid dictionary")
                    }
                    updateStatusDownloading(dictId, false, 0f)
                    updateStatuses()
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
        } catch (_: java.io.IOException) {
            false
        }
    }

    /**
     * 删除已下载的词典文件。如果删除的是当前选中词典，清空选中状态。
     */
    suspend fun delete(dictId: String): Boolean = withContext(Dispatchers.IO) {
        val manifest = parseManifest()
        val info = manifest?.dictionaries?.find { it.id == dictId }
        // manifest 的 fileName 不可信，同样走安全解析；拿不到时用 id 兜底（再过一次校验）
        val file = safeDictFile(info?.fileName ?: "") ?: safeDictFile("$dictId.txt")
        val ok = file?.delete() == true
        if (ok) {
            // issue 12.5：删除词典时同步清掉已入库的大词典条目，避免孤儿行常驻 DB
            dictionaryEntryDao.deleteByDictId(dictId)
        }
        if (ok && _activeDictId.value == dictId) {
            setActiveDict(null)
        }
        updateStatuses()
        ok
    }

    /**
     * 设置当前选中的词典。传 null 表示不选中任何下载词典（查词返回未命中）。
     * 可从 Compose 点击回调直接调用：偏好写入是内存+异步落盘，
     * 磁盘侧的状态重建丢给后台调度器，不在 Main 线程解析 manifest。
     */
    fun setActiveDict(dictId: String?) {
        _activeDictId.value = dictId
        context.getSharedPreferences(ACTIVE_DICT_PREFS, Context.MODE_PRIVATE)
            .edit().putString(ACTIVE_DICT_KEY, dictId).apply()
        // 失效已加载的内存词典，下次查询时重新加载
        loadedDict = null
        loadedDictId = null
        bgScope.launch { updateStatuses() }
    }

    /**
     * 获取当前选中词典的已加载内存 Map。
     * 如果没有选中任何下载的词典，返回 null（调用方查词按未命中处理）。
     * issue 12.5：对"大词典"（文件 >= 10MB）同样返回 null，不整份载内存，
     * 由 [lookup] 走 Room 表按需单条查询。
     */
    suspend fun getActiveDict(): Map<String, String>? = withContext(Dispatchers.IO) {
        val activeId = _activeDictId.value ?: return@withContext null
        if (activeId == loadedDictId && loadedDict != null) return@withContext loadedDict

        val manifest = parseManifest() ?: return@withContext null
        val info = manifest.dictionaries.find { it.id == activeId } ?: return@withContext null
        val file = safeDictFile(info.fileName) ?: return@withContext null
        if (!file.exists()) return@withContext null
        // issue 12.5：大词典不整份载内存（OOM 隐患），由 lookup 走 Room 按需查询
        if (file.length() >= LARGE_DICT_THRESHOLD_BYTES) return@withContext null

        // issue 12.6：delete() 删除当前选中词典时，此处与删除协程存在竞态——
        // exists() 通过后文件可能在读盘途中被删 -> FileNotFoundException 裸抛。
        // 捕获后返回 null（回退内置词典），并把已失效的内存态清掉避免状态错位。
        val map = try {
            linkedMapOf<String, String>().also { m ->
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                        val sep = trimmed.indexOf('|')
                        if (sep <= 0) continue
                        m[trimmed.substring(0, sep).trim()] = trimmed.substring(sep + 1).trim()
                    }
                }
            }
        } catch (e: java.io.FileNotFoundException) {
            loadedDict = null
            loadedDictId = null
            android.util.Log.w("DictionaryManager", "active dict file disappeared for ${info.name}: ${e.message}")
            return@withContext null
        }
        loadedDict = map
        loadedDictId = activeId
        android.util.Log.i("DictionaryManager", "加载词典 ${info.name}: ${map.size} 条")
        map
    }

    /**
     * 查询当前选中词典。未命中返回 null（调用方可继续查内置词典）。
     *
     * issue 12.5：小词典（文件 <10MB）仍整份载内存查 Map（最快）；
     * 大词典（>=10MB）首次查询时把文件写入 Room 的 `dictionary_entries` 表，
     * 之后每次查词走 (dictId, word) 单条查询 + 最近命中 LRU，不整份载入内存。
     */
    suspend fun lookup(word: String): String? = withContext(Dispatchers.IO) {
        val activeId = _activeDictId.value
        if (activeId == null) return@withContext null

        // Locale.ROOT：避免土耳其语等 locale 的 lowercase 变体（I→ı）破坏查词。
        // 保留撇号/连字符先试原形（词典键可能保留它们，如 "don't"），
        // 未命中再退回剥离非字母的旧归一化，两种键格式都不漏
        val lower = word.trim().lowercase(java.util.Locale.ROOT)
        if (lower.length < 2) return@withContext null
        val clean = lower.replace(Regex("[^a-z]"), "")
        val candidates = if (clean.length >= 2 && clean != lower) listOf(lower, clean) else listOf(lower)

        // 小词典：优先整份载内存查（getActiveDict 对大词典返回 null）
        val dict = getActiveDict()
        if (dict != null) {
            for (c in candidates) dict[c]?.let { return@withContext it }
            return@withContext null
        }

        // 大词典路径：只有当文件确实存在且为大（>=10MB）时才走 Room，
        // 避免 getActiveDict 因 manifest/文件缺失等其它原因返回 null 时误查 DB
        val file = resolveActiveFile(activeId) ?: return@withContext null
        if (file.length() < LARGE_DICT_THRESHOLD_BYTES) return@withContext null
        ensureBigDictImported(activeId, file)
        for (c in candidates) {
            cachedBigLookup(activeId, c)?.let { return@withContext it }
        }
        return@withContext null
    }

    /** 解析当前选中词典对应的有效文件；不存在或不可用返回 null。 */
    private fun resolveActiveFile(activeId: String): File? {
        val manifest = parseManifest() ?: return null
        val info = manifest.dictionaries.find { it.id == activeId } ?: return null
        val file = safeDictFile(info.fileName) ?: return null
        return if (file.exists()) file else null
    }

    /**
     * 首次查询某大词典时把文件按行写入 Room（幂等：已入库则直接复用，
     * 不重复扫描）。(dictId, word) 唯一 + REPLACE 覆盖，重复导入无副作用。
     */
    private suspend fun ensureBigDictImported(dictId: String, file: File) {
        if (dictionaryEntryDao.countByDictId(dictId) > 0L) return
        val buffer = ArrayList<DictionaryEntryEntity>(BIG_DICT_IMPORT_BATCH)
        try {
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val sep = trimmed.indexOf('|')
                    if (sep <= 0) continue
                    buffer.add(
                        DictionaryEntryEntity(
                            dictId = dictId,
                            word = trimmed.substring(0, sep).trim(),
                            definition = trimmed.substring(sep + 1).trim(),
                        ),
                    )
                    if (buffer.size >= BIG_DICT_IMPORT_BATCH) {
                        dictionaryEntryDao.insertAll(buffer)
                        buffer.clear()
                    }
                }
            }
            if (buffer.isNotEmpty()) dictionaryEntryDao.insertAll(buffer)
            android.util.Log.i(
                "DictionaryManager",
                "已导入大词典到 Room: $dictId (${dictionaryEntryDao.countByDictId(dictId)} 条)",
            )
        } catch (e: java.io.IOException) {
            android.util.Log.w("DictionaryManager", "导入大词典到 Room 失败 $dictId: ${e.message}")
        }
    }

    /** 大词典单条查询，命中最近的查询结果用 LRU 缓存减少反复敲 DB。 */
    private suspend fun cachedBigLookup(dictId: String, word: String): String? {
        val key = "$dictId\u0000$word"
        synchronized(bigDictCache) {
            bigDictCache[key]?.let { return it }
        }
        val def = dictionaryEntryDao.getDefinition(dictId, word)
        if (def != null) {
            synchronized(bigDictCache) {
                bigDictCache[key] = def
            }
        }
        return def
    }

    private fun updateStatusDownloading(dictId: String, downloading: Boolean, progress: Float) {
        // 原子 CAS 更新：并发下载/并发刷新状态时不丢更新
        _statuses.update { list ->
            list.map { s ->
                if (s.info.id == dictId) s.copy(downloading = downloading, progress = progress)
                else s
            }
        }
    }

    // ── 网络工具 ──────────────────────────────────────

    private fun downloadText(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("HTTP ${conn.responseCode} for $urlStr")
            }
            // issue 10.1：text 也设上限——manifest 被 CDN 换成畸形大文件时
            // 不再把整个读进内存。超限抛 IOException，refreshManifest catch 后降级。
            val limited = LimitInputStream(conn.inputStream, MAX_TEXT_BYTES)
            return limited.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
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
                throw java.io.IOException("HTTP ${conn.responseCode} for $urlStr")
            }
            val total = conn.contentLengthLong
            var done = 0L
            // issue 10.1：服务器声明的长度即已超限则直接终止，不再起流
            if (total > MAX_FILE_BYTES) {
                throw java.io.IOException("File too large ($total bytes, limit $MAX_FILE_BYTES)")
            }
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(262144)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        done += n
                        if (done > MAX_FILE_BYTES) {
                            throw java.io.IOException("File too large ($done bytes, limit $MAX_FILE_BYTES)")
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
            if (++read > maxBytes) throw java.io.IOException("Stream exceeded $maxBytes bytes")
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n < 0) return n
            read += n
            if (read > maxBytes) throw java.io.IOException("Stream exceeded $maxBytes bytes")
            return n
        }
    }
}
