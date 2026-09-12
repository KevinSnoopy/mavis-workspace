@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.eareyereading.ui.screens.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.eareyereading.domain.model.Book
import com.eareyereading.domain.model.ClassicBook
import com.eareyereading.domain.repository.BookRepository
import com.eareyereading.util.ArticleParser
import com.eareyereading.util.EpubParseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 书籍导入的单一职责控制器，涵盖三种导入路径：
 *
 * - SAF/外部 content:// URI 文件导入（[importBook]）
 * - URL 文章抓取导入（[importFromUrl] 及 URL 对话框状态）
 * - 英文经典名著一键下载（[downloadClassic]）
 *
 * 导入成功后挂起「完善信息」流程（通过 [onImportSuccess] 回调通知门面）。
 * 加载态与结果消息统一走 [LibraryStateController]。
 */
internal class BookImporter(
    private val stateController: LibraryStateController,
    private val bookRepository: BookRepository,
    private val articleParser: ArticleParser,
    private val context: Context,
    private val onImportSuccess: (Long) -> Unit,
    private val onDueTimestampRefresh: () -> Unit,
) {

    /** 导入文件体积上限：SAF 可递任意大文件（如数 GB 视频），
     * 不设限会写满内部存储 */
    private val maxImportBytes = 200L * 1024 * 1024

    /**
     * 经典书并发下载上限：下载完每本都要走 addBook 的整本解析 +
     * 词频统计（CPU 密集），不限并发时批量点下载会同时开 N 条
     * HttpURLConnection + N 个解析任务，GC/IO 风暴拖慢全进程。
     * 2 路（下载一队、解析一队）实测吞吐与流畅度的平衡点
     */
    private val classicDownloadLimiter = Semaphore(2)

    // ── URL 导入文章对话框 ─────────────────────────────

    fun showUrlDialog() {
        stateController.update { it.copy(showUrlDialog = true, urlInput = "") }
    }

    fun hideUrlDialog() {
        stateController.update { it.copy(showUrlDialog = false, urlInput = "") }
    }

    fun onUrlInputChange(url: String) {
        stateController.update { it.copy(urlInput = url) }
    }

    fun importFromUrl(scope: CoroutineScope) {
        val url = stateController.current.urlInput.trim()
        if (url.isBlank()) return

        // 自动补全 https://
        val fullUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else url

        scope.launch {
            stateController.beginImportOp("正在抓取文章...")
            stateController.update { it.copy(showUrlDialog = false) }
            try {
                val result = articleParser.parseFromUrl(fullUrl)
                if (result != null && result.paragraphs.isNotEmpty()) {
                    // 保存为本地"书"
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    val timestamp = dateFormat.format(Date())

                    val book = Book(
                        title = result.title.ifBlank { extractDomain(fullUrl) },
                        author = extractDomain(fullUrl),
                        filePath = "",
                        content = result.paragraphs.joinToString("\n\n"),
                        category = "文章",
                        addedAt = timestamp,
                    )
                    bookRepository.addBook(book)
                    onDueTimestampRefresh()
                    stateController.setResultMessage("文章已加入书库")
                } else {
                    stateController.setResultMessage("抓取失败，请检查链接")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                stateController.setResultMessage("抓取失败: 网络错误")
            } catch (e: java.lang.RuntimeException) {
                stateController.setResultMessage("抓取失败: ${e.message}")
            } finally {
                stateController.endImportOp()
            }
        }
    }

    /** 从 URL 提取域名（去 www. 前缀），失败回退通用名 */
    private fun extractDomain(url: String): String {
        return try {
            val u = URL(url)
            u.host.removePrefix("www.")
        } catch (e: java.net.MalformedURLException) {
            "Web Article"
        }
    }

    // ── 文件导入 ─────────────────────────────────

    fun importBook(uri: Uri, scope: CoroutineScope) {
        scope.launch {
            stateController.beginImportOp("正在导入...")
            var destFile: File? = null
            try {
                // issue 9.9：尝试持久化原始 content:// 读权限，使本地拷贝失效后仍能
                // 凭 contentResolver 重新打开；Provider 拒绝只告警不阻断拷贝
                tryTakePersistableReadPermission(uri)
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("无法读取文件内容")
                // SAF content:// URI 的 lastPathSegment 常常只是文档 id（"12"）或通用名（"book.epub"），
                // 直接复用会覆盖上次导入的同名文件（静默数据丢失）。加时间戳前缀并去掉路径分隔符。
                val rawName = uri.lastPathSegment?.substringAfterLast('/') ?: "book.epub"
                val fileName = "${System.currentTimeMillis()}_${rawName.replace('/', '_')}"
                val dest = File(context.filesDir, "books/$fileName")
                destFile = dest
                dest.parentFile?.mkdirs()
                // 整文件拷贝放 IO 线程，避免主线程拷贝大文件 ANR；
                // 带上限并计数，超限即中止
                withContext(Dispatchers.IO) {
                    inputStream.use { input ->
                        dest.outputStream().use { output ->
                            // 256KB 缓冲：SAF 拷贝几十 MB 的 EPUB 时，
                            // 8KB 缓冲 = 数万次 read/write 系统调用
                            val buffer = ByteArray(262144)
                            var copied = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                copied += n
                                if (copied > maxImportBytes) {
                                    throw java.io.IOException("File exceeds import size limit")
                                }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                }

                val book = Book(
                    title = rawName.removeSuffix(".epub").removeSuffix(".txt"),
                    author = "Unknown",
                    filePath = dest.absolutePath,
                    // issue 9.9：持久化原始 content:// URI，本地拷贝失效时回退读取
                    sourceUri = uri.toString(),
                )
                val newId = bookRepository.addBook(book)
                destFile = null // 成功入库后文件由 deleteBook 生命周期接管
                onDueTimestampRefresh()
                stateController.setResultMessage("导入成功")
                // v2：导入成功后挂起「完善信息」流程（选分类 + 选封面），
                // LibraryScreen 观察 pendingRefineBook 弹 AddBookFlowSheet。
                // addBook 返回去重复用的旧 id：完善流程对老书同样有效。
                onImportSuccess(newId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: EpubParseException) {
                android.util.Log.e("LibraryViewModel", "Error importing book file", e)
                // EPUB 解析失败此前一律"文件读取错误"（issue 9.3）：
                // 按异常类型区分损坏/加密/空文件/缺 OPF/无可读章节
                stateController.setResultMessage("导入失败: ${e.message}")
            } catch (e: java.io.IOException) {
                android.util.Log.e("LibraryViewModel", "Error importing book file", e)
                stateController.setResultMessage("导入失败: 文件读取错误")
            } catch (e: java.lang.SecurityException) {
                android.util.Log.e("LibraryViewModel", "Security error importing book", e)
                stateController.setResultMessage("导入失败: 权限错误")
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "Unexpected error importing book", e)
                stateController.setResultMessage("导入失败: ${e.javaClass.simpleName}")
            } finally {
                // 失败/取消时删除已拷贝的孤儿文件，防存储泄漏
                destFile?.delete()
                stateController.endImportOp()
            }
        }
    }

    /**
     * 一键下载英文经典名著（Project Gutenberg 纯文本）并加入书库。
     * 确定性路径名（books/classics/{id}.txt）让重复下载走 addBook 的 filePath 去重。
     */
    fun downloadClassic(classic: ClassicBook, scope: CoroutineScope) {
        if (classic.id in stateController.current.downloadingClassicIds) return
        if (classic.id in stateController.current.ownedClassicIds) return
        scope.launch {
            var destFile: File? = null
            try {
                stateController.update { it.copy(downloadingClassicIds = it.downloadingClassicIds + classic.id) }
                stateController.beginImportOp("正在下载《${classic.title}》...")
                classicDownloadLimiter.withPermit {
                    // mkdirs/exists/下载全部放 IO 调度器（旧实现 mkdirs/exists 跑在
                    // Main）；失败以异常抛出由外层 catch 统一处理
                    val dest = withContext(Dispatchers.IO) {
                        val dir = File(context.filesDir, "books/classics").apply { mkdirs() }
                        val d = File(dir, "${classic.id}.txt")
                        if (!d.exists()) {
                            val conn = (URL(classic.url).openConnection() as java.net.HttpURLConnection).apply {
                                connectTimeout = 20_000
                                readTimeout = 120_000
                                setRequestProperty("User-Agent", "Mozilla/5.0")
                                setRequestProperty("Accept", "text/plain,*/*")
                                instanceFollowRedirects = true
                            }
                            try {
                                if (conn.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                                    throw java.io.IOException("HTTP ${conn.responseCode}")
                                }
                                // 30MB 上限：整本长篇绰绰有余，同时防异常来源撑爆磁盘
                                val max = 30L * 1024 * 1024
                                conn.inputStream.use { input ->
                                    d.outputStream().use { output ->
                                        val buffer = ByteArray(262144)
                                        var done = 0L
                                        while (true) {
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            done += n
                                            if (done > max) throw java.io.IOException("File too large")
                                            output.write(buffer, 0, n)
                                        }
                                    }
                                }
                            } finally {
                                conn.disconnect()
                            }
                        }
                        d
                    }
                    destFile = dest
                    bookRepository.addBook(
                        Book(
                            title = classic.title,
                            author = classic.author,
                            filePath = dest.absolutePath,
                            language = "en",
                            category = "经典名著",
                        ),
                    )
                }
                destFile = null // 成功入库后文件交 deleteBook 生命周期接管
                onDueTimestampRefresh()
                stateController.setResultMessage("已加入书库：${classic.title}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                android.util.Log.e("LibraryViewModel", "download classic ${classic.id} failed", e)
                stateController.setResultMessage("下载失败：${e.message ?: "网络错误"}")
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "download classic ${classic.id} failed", e)
                stateController.setResultMessage("下载失败：${e.javaClass.simpleName}")
            } finally {
                destFile?.delete()
                stateController.update { it.copy(downloadingClassicIds = it.downloadingClassicIds - classic.id) }
                stateController.endImportOp()
            }
        }
    }

    /** issue 9.9：为 SAF/ACTION_VIEW 转发的 content:// URI 申请可持久化读权限。
     * 仅在带 FLAG_GRANT_PERSISTABLE_URI_PERMISSION 且 Provider 支持时才生效；
     * 权限不足会抛 SecurityException，此处只告警不阻断导入（本地拷贝仍可读）。 */
    private fun tryTakePersistableReadPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            android.util.Log.w("LibraryViewModel", "takePersistableUriPermission denied for $uri", e)
        }
    }
}
