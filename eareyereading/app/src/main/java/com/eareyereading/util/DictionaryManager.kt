package com.eareyereading.util

import android.content.Context
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
) {
    companion object {
        // manifest.json 的下载地址（托管在 jsDelivr CDN，从 GitHub 仓库拉取）
        const val MANIFEST_URL = "https://cdn.jsdelivr.net/gh/KevinSnoopy/mavis-workspace@eareyereading/eareyereading/scripts/out/dictionaries/manifest.json"

        private const val DICT_DIR_NAME = "dictionaries"
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val ACTIVE_DICT_PREFS = "dict_prefs"
        private const val ACTIVE_DICT_KEY = "active_dict_id"
    }

    private val gson = Gson()
    private val dictDir = File(context.filesDir, DICT_DIR_NAME).apply { mkdirs() }

    private val _statuses = MutableStateFlow<List<DictionaryStatus>>(emptyList())
    val statuses: StateFlow<List<DictionaryStatus>> = _statuses.asStateFlow()

    private val _activeDictId = MutableStateFlow<String?>(null)
    val activeDictId: StateFlow<String?> = _activeDictId.asStateFlow()

    private val _manifestError = MutableStateFlow<String?>(null)
    val manifestError: StateFlow<String?> = _manifestError.asStateFlow()

    // 当前已加载到内存的词典（按 activeDictId 对应的文件加载）
    @Volatile
    private var loadedDict: Map<String, String>? = null
    @Volatile
    private var loadedDictId: String? = null

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
     * 删除已下载的词典文件。如果删除的是当前选中词典，回退到内置词典。
     */
    suspend fun delete(dictId: String): Boolean = withContext(Dispatchers.IO) {
        val manifest = parseManifest()
        val info = manifest?.dictionaries?.find { it.id == dictId }
        // manifest 的 fileName 不可信，同样走安全解析；拿不到时用 id 兜底（再过一次校验）
        val file = safeDictFile(info?.fileName ?: "") ?: safeDictFile("$dictId.txt")
        val ok = file?.delete() == true
        if (ok && _activeDictId.value == dictId) {
            setActiveDict(null)
        }
        updateStatuses()
        ok
    }

    /**
     * 设置当前选中的词典。传 null 表示使用内置词典。
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
     * 如果没有选中任何下载的词典，返回 null（调用方回退到内置词典）。
     */
    suspend fun getActiveDict(): Map<String, String>? = withContext(Dispatchers.IO) {
        val activeId = _activeDictId.value ?: return@withContext null
        if (activeId == loadedDictId && loadedDict != null) return@withContext loadedDict

        val manifest = parseManifest() ?: return@withContext null
        val info = manifest.dictionaries.find { it.id == activeId } ?: return@withContext null
        val file = safeDictFile(info.fileName) ?: return@withContext null
        if (!file.exists()) return@withContext null

        val map = linkedMapOf<String, String>()
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val sep = trimmed.indexOf('|')
                if (sep <= 0) continue
                map[trimmed.substring(0, sep).trim()] = trimmed.substring(sep + 1).trim()
            }
        }
        loadedDict = map
        loadedDictId = activeId
        android.util.Log.i("DictionaryManager", "加载词典 ${info.name}: ${map.size} 条")
        map
    }

    /**
     * 查询当前选中词典。未命中返回 null（调用方可继续查内置词典）。
     */
    suspend fun lookup(word: String): String? {
        val dict = getActiveDict() ?: return null
        // Locale.ROOT：避免土耳其语等 locale 的 lowercase 变体（I→ı）破坏查词。
        // 保留撇号/连字符先试原形（词典键可能保留它们，如 "don't"），
        // 未命中再退回剥离非字母的旧归一化，两种键格式都不漏
        val lower = word.trim().lowercase(java.util.Locale.ROOT)
        if (lower.length < 2) return null
        dict[lower]?.let { return it }
        val clean = lower.replace(Regex("[^a-z]"), "")
        if (clean.length < 2 || clean == lower) return null
        return dict[clean]
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
            return conn.inputStream.bufferedReader().use { it.readText() }
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
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(262144)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
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
}
