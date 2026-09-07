package com.eareyereading.util

import android.content.Context
import com.eareyereading.BuildConfig
import com.eareyereading.data.local.dao.DictionaryEntryDao
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
 * 词典管理器（门面）。
 *
 * 负责词典的发现（从 manifest）、下载、删除、切换、查询。
 * 词典文件存储在 context.filesDir/dictionaries/{id}.txt
 *
 * issue 13：按 SRP 拆分——下载逻辑委托 [DictionaryDownloader]，
 * 导入/按需查询逻辑委托 [DictionaryImporter]，本类只保留
 * manifest 元数据管理、状态流编排与公共 API。
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

        // 查词归一化用（剥离非字母），查词热路径每次调用编译一次太浪费
        private val NON_ALPHA_REGEX = Regex("[^a-z]")
    }

    private val gson = Gson()

    // 惰性建目录：单例构造在 Hilt 注入点（App 启动主线程），
    // mkdirs 是磁盘操作，延迟到首个后台使用者触发
    private val dictDir: File by lazy {
        File(context.filesDir, DICT_DIR_NAME).apply { mkdirs() }
    }

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

    // manifest 内存缓存（按文件 lastModified+length 失效）：
    // 查词热路径每次 lookup 都要过 getActiveDict/resolveActiveFile，
    // 旧实现每次点词 = 2 次磁盘读 + 2 次 Gson 反序列化
    @Volatile
    private var cachedManifest: DictionaryManifest? = null
    @Volatile
    private var cachedManifestStamp: Long = 0L

    // 后台状态刷新用：setActiveDict 等公共入口不得在 Main 线程做
    // manifest 解析/文件存在性检查
    private val bgScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )

    // issue 13：按职责拆出的协作者
    private val downloader = DictionaryDownloader(
        dictDir = dictDir,
        statuses = _statuses,
        manifestProvider = ::parseManifest,
        fileResolver = ::safeDictFile,
    )
    private val importer = DictionaryImporter(dictionaryEntryDao)

    init {
        // 恢复上次选中的词典 + 加载缓存 manifest（离线可用）。
        // SharedPreferences 首次加载是磁盘 IO：移出主线程构造路径，
        // 且仅在仍为空时回填，避免覆盖用户刚设置的更新值
        bgScope.launch {
            val savedActive = context.getSharedPreferences(ACTIVE_DICT_PREFS, Context.MODE_PRIVATE)
                .getString(ACTIVE_DICT_KEY, null)
            if (_activeDictId.value == null) {
                _activeDictId.value = savedActive
            }
            loadCachedManifest()
        }
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
            val json = downloader.downloadText(MANIFEST_URL)
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
        // 内存缓存命中（文件未变）直接复用；并发下的重复解析是无害竞态
        val stamp = manifestFile.lastModified() * 31 + manifestFile.length()
        cachedManifest?.let { if (cachedManifestStamp == stamp) return it }
        return try {
            val manifest = gson.fromJson(manifestFile.readText(), DictionaryManifest::class.java)
            cachedManifest = manifest
            cachedManifestStamp = stamp
            manifest
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
        downloader.download(dictId, onProgress)

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
            // 同步失效"已导入"内存标志，否则删除后查词永远命中旧 Room 数据
            importer.onDictDeleted(dictId)
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
        if (file.length() >= DictionaryImporter.LARGE_DICT_THRESHOLD_BYTES) return@withContext null

        val map = importer.loadToMemory(file)
        if (map == null) {
            // 文件读盘中被并发删除等：清掉已失效的内存态避免状态错位
            loadedDict = null
            loadedDictId = null
            return@withContext null
        }
        loadedDict = map
        loadedDictId = activeId
        android.util.Log.i("DictionaryManager", "加载词典 ${info.name}: ${map.size} 条")
        map
    }

    /**
     * 查询当前选中词典。未命中返回 null（无内置词典兜底）。
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
        val clean = lower.replace(NON_ALPHA_REGEX, "")
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
        if (file.length() < DictionaryImporter.LARGE_DICT_THRESHOLD_BYTES) return@withContext null
        importer.ensureBigDictImported(activeId, file)
        for (c in candidates) {
            importer.cachedBigLookup(activeId, c)?.let { return@withContext it }
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
}
