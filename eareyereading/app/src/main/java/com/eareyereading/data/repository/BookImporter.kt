package com.eareyereading.data.repository

import android.content.Context
import com.eareyereading.data.local.dao.BookDao
import com.eareyereading.data.local.dao.WordFrequencyDao
import com.eareyereading.data.local.entity.WordFrequencyEntity
import com.eareyereading.domain.model.Book
import com.eareyereading.util.BookImages
import com.eareyereading.util.EpubParser
import com.eareyereading.util.WordAnalyzer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 书籍导入：EPUB/TXT 解析 → 去重 → 元数据填充 → 封面/插图提取 → 词频统计。
 *
 * 从 BookRepositoryImpl 拆出（SRP）：addBook 是整个仓库最重的方法（~170 行），
 * 混合了文件解析、去重策略、词数统计、封面/插图落盘、词频写入等多个关注点。
 * 拆为独立 internal class 后，BookRepositoryImpl 仅做委托，导入逻辑的变更
 * 不再影响查询/删除等其他职责。 */
@Singleton
internal class BookImporter @Inject constructor(
    private val bookDao: BookDao,
    private val epubParser: EpubParser,
    private val wordFrequencyDao: WordFrequencyDao,
    private val wordAnalyzer: WordAnalyzer,
    private val imageExtractor: BookImageExtractor,
    private val fileCleanup: BookFileCleanup,
    @ApplicationContext private val context: Context,
) {

    private companion object {
        /** 纯文本导入读取上限，防超大文件撑爆内存（与 EPUB 全局上限同量级）。 */
        const val MAX_PLAIN_TEXT_CHARS = 10_000_000

        /** 入库时保留的词频条目上限：与 getTopFrequencies 的默认 limit 对齐，
         *  大书唯一词数可达数万，全量插入只有存储成本没有查询收益。 */
        const val TOP_FREQUENCY_WORDS = 100
    }

    /**
     * 导入一本书：解析文件 → 去重 → 填充元数据 → 提取封面/插图 → 写词频。
     *
     * @return 新导入或复用的书籍 id
     * @throws java.io.IOException 文件解析失败时抛出
     */
    suspend fun addBook(book: Book): Long = withContext(Dispatchers.IO) {
        // issue 9.7：同文件路径重复导入去重。SAF 导入会带时间戳前缀（避免重名覆盖），
        // 这里至少能挡住同一路径（如重试同一 URL 拷贝、重复选择同一文件）的重复入库。
        if (book.filePath.isNotBlank()) {
            bookDao.findByFilePath(book.filePath)?.let { existing ->
                android.util.Log.i("BookRepository", "Book already imported (filePath=${book.filePath}), reusing id=${existing.id}")
                // 命中的旧书记于归档态时自动取消归档：书库列表只显示未归档书，
                // 归档后经典书列表重新出现"下载"按钮，用户点击期望书回到书库；
                // 旧实现复用 id 但保持归档态，提示"已加入书库"列表里却永远看不到
                if (existing.isArchived) {
                    bookDao.setArchived(existing.id, false)
                    android.util.Log.i("BookRepository", "Unarchived on re-add (id=${existing.id})")
                }
                return@withContext existing.id
            }
        }

        // issue 9.1：EPUB 解析携带 OPF 元数据（标题/作者/语言），
        // 导入时填充，替代"标题=文件名、作者=Unknown、语言=en(永远)"
        var parsedMetadata: com.eareyereading.util.ParsedBook? = null
        val paragraphs = if (book.content.isNotBlank()) {
            book.content.split("\n\n").filter { it.isNotBlank() }
        } else if (book.filePath.isNotBlank()) {
            // EPUB 解析失败会抛 EpubParseException（IOException 子类，带可读原因），
            // 直接透传给调用方；纯文本路径解析失败仍返回空列表，
            // 这里必须感知，否则会静默创建一本 0 词的空书。
            // 解析是重 IO + 正则工作：整体放 IO 调度器，主线程只拿结果，
            // 否则大 EPUB 的 zip 读取会阻塞 UI 线程（ANR）。
            if (book.filePath.lowercase(Locale.ROOT).endsWith(".txt")) {
                parsePlainText(File(book.filePath))
            } else {
                val parsed = epubParser.parseBook(book.filePath, book.sourceUri, context.contentResolver)
                parsedMetadata = parsed
                // issue 9.2：MAX_TOTAL_CHARS 截断不再是静默行为，至少打日志告警
                if (parsed.wasTruncated) {
                    android.util.Log.w(
                        "BookRepository",
                        "EPUB was truncated at ${parsed.paragraphs.size} paragraphs (${book.filePath})",
                    )
                }
                parsed.paragraphs
            }
        } else {
            emptyList()
        }
        if (paragraphs.isEmpty()) {
            throw java.io.IOException("Failed to parse book file: ${book.filePath}")
        }

        // issue 9.7：按 OPF dc:identifier 去重。SAF 每次导入的本地拷贝路径都带时间戳前缀，
        // filePath 去重挡不住；同一本 EPUB 的 dc:identifier 稳定，命中即复用旧 id。
        // 同时清理本次导入刚拷出的临时副本，避免磁盘上重复文件堆积。
        val identifier = parsedMetadata?.identifier?.trim().orEmpty()
        if (identifier.isNotEmpty()) {
            bookDao.findByIdentifier(identifier)?.let { existing ->
                android.util.Log.i(
                    "BookRepository",
                    "Book already imported (identifier=$identifier), reusing id=${existing.id}",
                )
                // 与 filePath 去重同款：重新导入已归档的书 = 用户要它回书库
                if (existing.isArchived) {
                    bookDao.setArchived(existing.id, false)
                    android.util.Log.i("BookRepository", "Unarchived on re-add (id=${existing.id})")
                }
                fileCleanup.deleteOrphanCopy(book.filePath)
                return@withContext existing.id
            }
        }

        // 单趟统计：一次遍历段落同时完成分词与 CJK 计数。
        // 旧实现 join 全文 → split（数十万 String）→ count 再扫一遍全文，
        // 大书导入瞬时内存峰值约为正文的 3~4 倍。
        // 2.0：词数只做计数不物化 token 字符串——一部长篇 ~10 万词，
        // 旧写法每本导入白造 10 万个 String（几 MB 分配），批量下载多本书时
        // 触发 GC 风暴拖慢全进程（含 UI 线程）
        var tokenCount = 0
        var cjkChars = 0
        for (paragraph in paragraphs) {
            // 插图标记段不是正文文本：不计词频/字数
            if (com.eareyereading.util.BookImages.isImageMarker(paragraph)) continue
            var inWord = false
            for (i in paragraph.indices) {
                val c = paragraph[i]
                if (c in '\u4E00'..'\u9FFF') cjkChars++
                val isWhitespace = c == ' ' || c == '\t' || c == '\n' || c == '\r'
                if (isWhitespace) {
                    inWord = false
                } else if (!inWord) {
                    inWord = true
                    tokenCount++
                }
            }
        }
        // 中文等无空白语言按空白切分只得 1 个"词"：此时按 CJK 字符数计词，
        // 避免"少数派"类中文文章整书报 1 词
        val totalWords = if (cjkChars > tokenCount) cjkChars else tokenCount

        val contentToSave = if (book.content.isNotBlank()) book.content
            else paragraphs.joinToString("\n\n")

        // 元数据填充优先级：显式传入 > OPF 解析 > 文件名/Unknown/en 兜底。
        // language 仅在 OPF 声明且非默认值时才覆盖——避免把所有导入书都改回 en
        val entity = BookMapper.toEntity(book).copy(
            title = book.title.ifBlank { parsedMetadata?.title.orEmpty() }
                .ifBlank { File(book.filePath).nameWithoutExtension },
            author = book.author.ifBlank { parsedMetadata?.author ?: "Unknown" }
                .ifBlank { "Unknown" },
            language = if (!book.language.equals("en", ignoreCase = true)) book.language
                else parsedMetadata?.language?.ifBlank { "en" } ?: "en",
            // issue 9.7：持久化 OPF dc:identifier（唯一索引 + 去重）
            identifier = identifier.ifBlank { null },
            // issue 9.2：截断标记与原文规模入库，书库卡片据此提示"正文被截断"
            isTruncated = parsedMetadata?.wasTruncated ?: false,
            originalCharCount = parsedMetadata?.originalCharCount ?: 0,
            totalWords = totalWords,
            content = contentToSave,
            addedAt = book.addedAt,
        )
        val bookId = bookDao.insert(entity)

        // EPUB 内嵌封面提取：落盘 covers/{bookId}，coverPath 入库供书库/首页渲染。
        // 失败静默回退生成式封面（BookCover 的渐变占位），绝不阻断导入主流程
        if (book.filePath.isNotBlank() && parsedMetadata != null) {
            try {
                val coverBytes = epubParser.extractCoverImage(book.filePath)
                if (coverBytes != null && coverBytes.isNotEmpty()) {
                    val coverDir = File(context.filesDir, "covers").apply { mkdirs() }
                    val coverFile = File(coverDir, "$bookId")
                    coverFile.writeBytes(coverBytes)
                    // 定向 UPDATE：旧实现 SELECT * 回读整行（含刚写入的整书正文）
                    // 只为 copy 出一个改了 coverPath 的实体再全字段 UPDATE
                    bookDao.updateCoverPath(bookId, coverFile.absolutePath)
                }
            } catch (e: Exception) {
                android.util.Log.w("BookRepository", "extract cover failed for $bookId", e)
            }
            // EPUB 插图落盘：正文里的 [[IMG:n]] 标记 → 降采样 JPEG 文件，
            // 阅读页按文件渲染（见 BookImages）；失败静默，不影响导入
            try {
                imageExtractor.extractBookImages(bookId, book.filePath, parsedMetadata.imageEntryNames)
            } catch (e: Exception) {
                android.util.Log.w("BookRepository", "extract images failed for $bookId", e)
            }
        }

        // 词频统计此前只有删没有写：word_frequencies 永远是空表，
        // getTopFrequencies 永远不出数据（issue 12.2）
        // 插图标记段不参与词频（否则 "IMG"/"jpg" 等标记碎片进 Top 榜）
        val textParagraphs = paragraphs.filter { !BookImages.isImageMarker(it) }
        val frequencies = wordAnalyzer.calculateWordFrequencies(textParagraphs)
            .entries
            .sortedByDescending { it.value }
            .take(TOP_FREQUENCY_WORDS)
            .map { (word, count) ->
                WordFrequencyEntity(
                    bookId = bookId,
                    word = word,
                    count = count,
                    frequency = count.toFloat(),
                )
            }
        if (frequencies.isNotEmpty()) {
            wordFrequencyDao.insertAll(frequencies)
        }
        bookId
    }

    /**
     * 纯文本文件按空行分段导入（与 EPUB 段落结构对齐）。
     * 读取有字符上限，防超大文本撑爆内存；解析失败返回空列表，
     * 由 addBook 抛 IOException 让调用方感知。
     */
    private fun parsePlainText(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.inputStream().reader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < MAX_PLAIN_TEXT_CHARS) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, minOf(n, MAX_PLAIN_TEXT_CHARS - sb.length))
                }
                sb.toString()
            }
            text.split(Regex("\\n\\s*\\n"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
        } catch (e: java.io.IOException) {
            android.util.Log.e("BookRepository", "Error reading plain text file", e)
            emptyList()
        }
    }
}
