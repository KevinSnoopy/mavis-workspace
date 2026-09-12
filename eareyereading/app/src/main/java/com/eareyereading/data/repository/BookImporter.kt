package com.eareyereading.data.repository

import android.content.Context
import com.eareyereading.data.local.dao.BookDao
import com.eareyereading.data.local.dao.WordFrequencyDao
import com.eareyereading.data.local.entity.BookEntity
import com.eareyereading.data.local.entity.WordFrequencyEntity
import com.eareyereading.domain.model.Book
import com.eareyereading.domain.model.TocEntry
import com.eareyereading.util.BookImages
import com.eareyereading.util.EpubParser
import com.eareyereading.util.ParsedBook
import com.eareyereading.util.TocCodec
import com.eareyereading.util.TocHeadingScanner
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
 * 从 BookRepositoryImpl 拆出（SRP）：addBook 是整个仓库最重的方法，混合了
 * 文件解析、去重策略、词数统计、封面/插图落盘、词频写入等多个关注点。
 *
 * ── 重构说明（13 条软件设计原则）──
 * [addBook] 原本是 166 行的单函数（上述五个关注点串在一个大括号里），
 * 违反 SRP/KISS。现按导入阶段提取为独立私有方法，主函数只保留编排骨架
 * （文件路径去重 → 解析 → 标识符去重 → 统计落库 → 封面插图 → 词频），
 * **执行顺序与副作用与拆分前完全一致**。 */
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
        // issue 9.7：同文件路径重复导入去重
        reuseByFilePath(book)?.let { return@withContext it }

        // issue 9.1：EPUB 解析携带 OPF 元数据（标题/作者/语言）
        val parsed = parseContent(book)
        if (parsed.paragraphs.isEmpty()) {
            throw java.io.IOException("Failed to parse book file: ${book.filePath}")
        }

        // issue 9.7：按 OPF dc:identifier 去重
        val identifier = parsed.metadata?.identifier?.trim().orEmpty()
        if (identifier.isNotEmpty()) {
            reuseByIdentifier(identifier, book.filePath)?.let { return@withContext it }
        }

        val totalWords = countWords(parsed.paragraphs)
        val contentToSave = if (book.content.isNotBlank()) book.content
            else parsed.paragraphs.joinToString("\n\n")
        val toc = extractToc(parsed)
        val bookId = bookDao.insert(
            buildEntity(book, parsed.metadata, identifier, totalWords, contentToSave, toc),
        )

        // EPUB 内嵌封面与插图：失败静默，绝不阻断导入主流程
        if (book.filePath.isNotBlank() && parsed.metadata != null) {
            extractCoverAndImages(bookId, book, parsed.metadata)
        }

        writeWordFrequencies(bookId, parsed.paragraphs)
        bookId
    }

    // ── 去重 ───────────────────────────────────────────────

    /**
     * issue 9.7：同文件路径重复导入去重。SAF 导入会带时间戳前缀（避免重名覆盖），
     * 这里至少能挡住同一路径（如重试同一 URL 拷贝、重复选择同一文件）的重复入库。
     *
     * @return 命中时返回既有书籍 id
     */
    private suspend fun reuseByFilePath(book: Book): Long? {
        if (book.filePath.isBlank()) return null
        val existing = bookDao.findByFilePath(book.filePath) ?: return null
        android.util.Log.i(
            "BookRepository",
            "Book already imported (filePath=${book.filePath}), reusing id=${existing.id}",
        )
        // 命中的旧书记于归档态时自动取消归档：书库列表只显示未归档书，
        // 归档后经典书列表重新出现"下载"按钮，用户点击期望书回到书库；
        // 旧实现复用 id 但保持归档态，提示"已加入书库"列表里却永远看不到
        unarchiveIfNeeded(existing.id, existing.isArchived)
        return existing.id
    }

    /**
     * issue 9.7：按 OPF dc:identifier 去重。SAF 每次导入的本地拷贝路径都带时间戳前缀，
     * filePath 去重挡不住；同一本 EPUB 的 dc:identifier 稳定，命中即复用旧 id。
     * 同时清理本次导入刚拷出的临时副本，避免磁盘上重复文件堆积。
     */
    private suspend fun reuseByIdentifier(identifier: String, filePath: String): Long? {
        val existing = bookDao.findByIdentifier(identifier) ?: return null
        android.util.Log.i(
            "BookRepository",
            "Book already imported (identifier=$identifier), reusing id=${existing.id}",
        )
        // 与 filePath 去重同款：重新导入已归档的书 = 用户要它回书库
        unarchiveIfNeeded(existing.id, existing.isArchived)
        fileCleanup.deleteOrphanCopy(filePath)
        return existing.id
    }

    /** 重新导入已归档的书 = 用户要它回书库（书库列表只显示未归档书）。 */
    private suspend fun unarchiveIfNeeded(bookId: Long, isArchived: Boolean) {
        if (!isArchived) return
        bookDao.setArchived(bookId, false)
        android.util.Log.i("BookRepository", "Unarchived on re-add (id=$bookId)")
    }

    // ── 内容解析 ───────────────────────────────────────────

    /**
     * 解析导入源：正文段落 + EPUB OPF 元数据。
     *
     * 解析是重 IO + 正则工作，调用方已处于 IO 调度器。
     * EPUB 解析失败会抛 EpubParseException（IOException 子类，带可读原因），
     * 直接透传给调用方；纯文本路径解析失败仍返回空列表，由调用方感知，
     * 否则会静默创建一本 0 词的空书。
     */
    private suspend fun parseContent(book: Book): ParsedContent {
        if (book.content.isNotBlank()) {
            return ParsedContent(
                paragraphs = book.content.split("\n\n").filter { it.isNotBlank() },
                metadata = null,
            )
        }
        if (book.filePath.isBlank()) {
            return ParsedContent(paragraphs = emptyList(), metadata = null)
        }
        if (book.filePath.lowercase(Locale.ROOT).endsWith(".txt")) {
            return ParsedContent(paragraphs = parsePlainText(File(book.filePath)), metadata = null)
        }
        // 大 EPUB 的 zip 读取阻塞式进行，主线程调用会 ANR
        val parsed = epubParser.parseBook(book.filePath, book.sourceUri, context.contentResolver)
        // issue 9.2：MAX_TOTAL_CHARS 截断不再是静默行为，至少打日志告警
        if (parsed.wasTruncated) {
            android.util.Log.w(
                "BookRepository",
                "EPUB was truncated at ${parsed.paragraphs.size} paragraphs (${book.filePath})",
            )
        }
        return ParsedContent(paragraphs = parsed.paragraphs, metadata = parsed)
    }

    // ── 统计与落库 ─────────────────────────────────────────

    /**
     * 单趟统计：一次遍历段落同时完成分词与 CJK 计数。
     * 旧实现 join 全文 → split（数十万 String）→ count 再扫一遍全文，
     * 大书导入瞬时内存峰值约为正文的 3~4 倍。
     *
     * 2.0：词数只做计数不物化 token 字符串——一部长篇 ~10 万词，
     * 旧写法每本导入白造 10 万个 String（几 MB 分配），批量下载多本书时
     * 触发 GC 风暴拖慢全进程（含 UI 线程）。
     */
    private fun countWords(paragraphs: List<String>): Int {
        var tokenCount = 0
        var cjkChars = 0
        for (paragraph in paragraphs) {
            // 插图标记段不是正文文本：不计词频/字数
            if (BookImages.isImageMarker(paragraph)) continue
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
        return if (cjkChars > tokenCount) cjkChars else tokenCount
    }

    /**
     * 章节目录提取：EPUB 用解析器产出的章边界（spine 分组 + toc.ncx/nav 标题），
     * 其余（.txt / URL 文章正文）用章标题段扫描——正文含 URL/RSS 文章时
     * 连续 3 段命中 "chapter <数字>" 的概率趋近于零，误伤被阈值挡住。
     */
    private fun extractToc(parsed: ParsedContent): List<TocEntry> {
        parsed.metadata?.let { return it.chapters }
        return TocHeadingScanner.scan(parsed.paragraphs)
    }

    /**
     * 元数据填充优先级：显式传入 > OPF 解析 > 文件名/Unknown/en 兜底。
     * language 仅在 OPF 声明且非默认值时才覆盖——避免把所有导入书都改回 en。
     */
    private fun buildEntity(
        book: Book,
        metadata: ParsedBook?,
        identifier: String,
        totalWords: Int,
        contentToSave: String,
        toc: List<TocEntry>,
    ): BookEntity = BookMapper.toEntity(book).copy(
        title = book.title.ifBlank { metadata?.title.orEmpty() }
            .ifBlank { File(book.filePath).nameWithoutExtension },
        author = book.author.ifBlank { metadata?.author ?: "Unknown" }
            .ifBlank { "Unknown" },
        language = if (!book.language.equals("en", ignoreCase = true)) book.language
            else metadata?.language?.ifBlank { "en" } ?: "en",
        // issue 9.7：持久化 OPF dc:identifier（唯一索引 + 去重）
        identifier = identifier.ifBlank { null },
        // issue 9.2：截断标记与原文规模入库，书库卡片据此提示"正文被截断"
        isTruncated = metadata?.wasTruncated ?: false,
        originalCharCount = metadata?.originalCharCount ?: 0,
        totalWords = totalWords,
        content = contentToSave,
        addedAt = book.addedAt,
        // 章节目录：TocEntry 列表序列化落库；空目录存 NULL
        tocJson = TocCodec.encode(toc),
    )

    // ── 封面与插图 ─────────────────────────────────────────

    /**
     * EPUB 内嵌封面提取：落盘 covers/{bookId}，coverPath 入库供书库/首页渲染。
     * 失败静默回退生成式封面（BookCover 的渐变占位），绝不阻断导入主流程。
     *
     * EPUB 插图落盘：正文里的 [[IMG:n]] 标记 → 降采样 JPEG 文件，
     * 阅读页按文件渲染（见 BookImages）；失败静默，不影响导入。
     */
    private suspend fun extractCoverAndImages(bookId: Long, book: Book, metadata: ParsedBook) {
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
        try {
            imageExtractor.extractBookImages(bookId, book.filePath, metadata.imageEntryNames)
        } catch (e: Exception) {
            android.util.Log.w("BookRepository", "extract images failed for $bookId", e)
        }
    }

    // ── 词频 ───────────────────────────────────────────────

    /**
     * 词频统计此前只有删没有写：word_frequencies 永远是空表，
     * getTopFrequencies 永远不出数据（issue 12.2）。
     * 插图标记段不参与词频（否则 "IMG"/"jpg" 等标记碎片进 Top 榜）。
     */
    private suspend fun writeWordFrequencies(bookId: Long, paragraphs: List<String>) {
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

    /** [parseContent] 的产物：正文段落 + 可选的 EPUB OPF 元数据。 */
    private class ParsedContent(
        val paragraphs: List<String>,
        val metadata: ParsedBook?,
    )
}
