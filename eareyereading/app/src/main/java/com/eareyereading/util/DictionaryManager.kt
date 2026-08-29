package com.eareyereading.util

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    init {
        // 恢复上次选中的词典
        val savedActive = context.getSharedPreferences(ACTIVE_DICT_PREFS, Context.MODE_PRIVATE)
            .getString(ACTIVE_DICT_KEY, null)
        _activeDictId.value = savedActive

        // 如果有缓存的 manifest，先加载它（离线可用）
        loadCachedManifest()
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
            manifestFile.writeText(json)
            _manifestError.value = null
            updateStatuses()
            true
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
            null
        }
    }

    private fun updateStatuses() {
        val manifest = parseManifest() ?: return
        val active = _activeDictId.value
        _statuses.value = manifest.dictionaries.map { info ->
            val file = File(dictDir, info.fileName)
            DictionaryStatus(
                info = info,
                downloaded = file.exists(),
                downloading = false,
                progress = 0f,
                active = info.id == active,
            )
        }
    }

    /**
     * 下载指定词典。成功返回 true。
     * progress 回调在 IO 线程触发。
     */
    suspend fun download(dictId: String, onProgress: (Float) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            val manifest = parseManifest() ?: return@withContext false
            val info = manifest.dictionaries.find { it.id == dictId }
                ?: return@withContext false
            if (info.downloadUrl.startsWith("REPLACE_WITH")) {
                android.util.Log.w("DictionaryManager", "词典 ${info.name} 的下载地址未配置")
                return@withContext false
            }

            // 标记下载中
            updateStatusDownloading(dictId, true, 0f)
            try {
                downloadFile(info.downloadUrl, File(dictDir, info.fileName)) { p ->
                    updateStatusDownloading(dictId, true, p)
                    onProgress(p)
                }
                updateStatusDownloading(dictId, false, 0f)
                updateStatuses()
                true
            } catch (e: Exception) {
                android.util.Log.w("DictionaryManager", "下载词典 ${info.name} 失败: ${e.message}")
                updateStatusDownloading(dictId, false, 0f)
                false
            }
        }

    /**
     * 删除已下载的词典文件。如果删除的是当前选中词典，回退到内置词典。
     */
    suspend fun delete(dictId: String): Boolean = withContext(Dispatchers.IO) {
        val manifest = parseManifest()
        val info = manifest?.dictionaries?.find { it.id == dictId }
        val fileName = info?.fileName ?: "$dictId.txt"
        val file = File(dictDir, fileName)
        val ok = file.delete()
        if (ok && _activeDictId.value == dictId) {
            setActiveDict(null)
        }
        updateStatuses()
        ok
    }

    /**
     * 设置当前选中的词典。传 null 表示使用内置词典。
     */
    fun setActiveDict(dictId: String?) {
        _activeDictId.value = dictId
        context.getSharedPreferences(ACTIVE_DICT_PREFS, Context.MODE_PRIVATE)
            .edit().putString(ACTIVE_DICT_KEY, dictId).apply()
        // 失效已加载的内存词典，下次查询时重新加载
        loadedDict = null
        loadedDictId = null
        updateStatuses()
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
        val file = File(dictDir, info.fileName)
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
        val clean = word.trim().lowercase().replace(Regex("[^a-z]"), "")
        if (clean.length < 2) return null
        return dict[clean]
    }

    private fun updateStatusDownloading(dictId: String, downloading: Boolean, progress: Float) {
        _statuses.value = _statuses.value.map { s ->
            if (s.info.id == dictId) s.copy(downloading = downloading, progress = progress)
            else s
        }
    }

    // ── 网络工具 ──────────────────────────────────────

    private fun downloadText(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun downloadFile(urlStr: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        val total = conn.contentLengthLong
        var done = 0L
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(262144)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
    }
}
