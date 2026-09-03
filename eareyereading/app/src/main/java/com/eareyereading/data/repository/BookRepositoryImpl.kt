package com.eareyereading.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.eareyereading.data.local.dao.BookDao
import com.eareyereading.data.local.dao.BookListItem
import com.eareyereading.data.local.dao.BookmarkDao
import com.eareyereading.data.local.dao.HighlightDao
import com.eareyereading.data.local.dao.ReadingStateDao
import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.data.local.dao.ReviewRecordDao
import com.eareyereading.data.local.dao.VocabularyDao
import com.eareyereading.data.local.dao.WordFrequencyDao
import com.eareyereading.data.local.database.AppDatabase
import com.eareyereading.data.local.entity.BookEntity
import com.eareyereading.data.local.entity.WordFrequencyEntity
import com.eareyereading.domain.model.Book
import com.eareyereading.domain.repository.BookRepository
import com.eareyereading.util.EpubParser
import com.eareyereading.util.WordAnalyzer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepositoryImpl @Inject constructor(
    private val bookDao: BookDao,
    private val epubParser: EpubParser,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val readingStateDao: ReadingStateDao,
    private val readingStatsDao: ReadingStatsDao,
    private val wordFrequencyDao: WordFrequencyDao,
    private val vocabularyDao: VocabularyDao,
    private val reviewRecordDao: ReviewRecordDao,
    private val wordAnalyzer: WordAnalyzer,
    private val database: AppDatabase,
    @ApplicationContext private val context: Context,
) : BookRepository {

    private companion object {
        /** 纯文本导入读取上限，防超大文件撑爆内存（与 EPUB 全局上限同量级）。 */
        const val MAX_PLAIN_TEXT_CHARS = 10_000_000

        /** 入库时保留的词频条目上限：与 getTopFrequencies 的默认 limit 对齐，
         *  大书唯一词数可达数万，全量插入只有存储成本没有查询收益。 */
        const val TOP_FREQUENCY_WORDS = 100
    }

    override fun getAllBooks(): Flow<List<Book>> =
        bookDao.getAllBooks()
            .map { entities -> entities.map { it.toDomain() } }
            // 实体→领域对象的全量重建放 Default 调度器：
            // Room Flow 在查询执行器发射，但下游 map 跑在收集者上下文（主线程），
            // 大书库时每次失效重发射都在主线程做整列表拷贝
            .flowOn(Dispatchers.Default)

    override fun getArchivedBooks(): Flow<List<Book>> =
        bookDao.getArchivedBooks()
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    override fun getBookById(id: Long): Flow<Book?> =
        bookDao.getBookByIdFlow(id).map { it?.toDomain() }

    override suspend fun addBook(book: Book): Long = withContext(Dispatchers.IO) {
        // issue 9.7：同文件路径重复导入去重。SAF 导入会带时间戳前缀（避免重名覆盖），
        // 这里至少能挡住同一路径（如重试同一 URL 拷贝、重复选择同一文件）的重复入库。
        if (book.filePath.isNotBlank()) {
            bookDao.findByFilePath(book.filePath)?.let { existing ->
                android.util.Log.i("BookRepository", "Book already imported (filePath=${book.filePath}), reusing id=${existing.id}")
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
                deleteOrphanCopy(book.filePath)
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
        val entity = book.toEntity().copy(
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
        }

        // 词频统计此前只有删没有写：word_frequencies 永远是空表，
        // getTopFrequencies 永远不出数据（issue 12.2）
        val frequencies = wordAnalyzer.calculateWordFrequencies(paragraphs)
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

    override suspend fun updateBook(book: Book) {
        bookDao.update(book.toEntity())
    }

    override suspend fun updateProgress(bookId: Long, progress: Float, position: Int) {
        bookDao.updateProgress(bookId, progress, position)
    }

    override suspend fun setArchived(bookId: Long, archived: Boolean) {
        bookDao.setArchived(bookId, archived)
    }

    override suspend fun deleteBook(bookId: Long) {
        // 删除前记下文件路径：事务成功后清理导入时拷贝的书籍文件，
        // 防孤儿文件无限累积（仅限应用 books 目录内的文件）
        val filePath = bookDao.getFilePath(bookId).orEmpty()
        // 在单个事务中级联删除，保证原子性：要么全部成功，要么全部回滚
        database.withTransaction {
            // 复习记录先删：它靠 vocabulary 行的 bookId 子查询定位，
            // 顺序反了会匹配不到，留下指向已删词汇的孤儿记录
            reviewRecordDao.deleteByBookId(bookId)
            vocabularyDao.deleteForBook(bookId)
            bookmarkDao.deleteAllForBook(bookId)
            highlightDao.deleteAllForBook(bookId)
            readingStateDao.deleteForBook(bookId)
            readingStatsDao.deleteForBook(bookId)
            wordFrequencyDao.deleteForBook(bookId)
            bookDao.deleteById(bookId)
        }
        if (filePath.isNotBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(filePath)
                    // issue 11.19：先确保 books 目录存在，否则 canonicalPath 解析
                    // 会因目录缺失抛 IOException，下面的清理被整个吞掉且无回退。
                    val booksDir = File(context.filesDir, "books").apply { mkdirs() }
                    // 只清理本应用导入目录内的文件，绝不碰用户目录。
                    // canonical 解析失败时回退到 absolutePath 字符串前缀比较，
                    // 保证"清不清理成功"不会被一次解析异常悄悄吞掉（issue 10.7）。
                    val safe = try {
                        file.canonicalPath.startsWith(booksDir.canonicalPath + File.separator)
                    } catch (_: java.io.IOException) {
                        file.absolutePath.startsWith(booksDir.absolutePath + File.separator)
                    }
                    if (safe) {
                        file.delete()
                    } else {
                        android.util.Log.w("BookRepository", "Refuse to delete outside books dir: $filePath")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("BookRepository", "Failed to delete book file", e)
                }

                // 封面文件随书删除（covers/{bookId}，导入时提取的 EPUB 内嵌封面）
                try {
                    File(context.filesDir, "covers/$bookId").delete()
                } catch (e: Exception) {
                    android.util.Log.w("BookRepository", "Failed to delete cover file", e)
                }
            }
        }
    }

    override fun searchBooks(query: String): Flow<List<Book>> =
        // issue 10.6：把查询里的 LIKE 通配符转义为字面量（与 DAO 的 ESCAPE '\' 配套），
        // 避免用户输入 %/_ 时语义被破坏；同时截断超长搜索词防全表跑来兜底
        bookDao.searchBooks(escapeForLike(query).take(64))
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    private fun escapeForLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** 列表投影 → 领域对象（content 不进列表：需要正文走 getBookById）。 */
    private fun BookListItem.toDomain() = Book(
        id = id, title = title, author = author, coverPath = coverPath,
        filePath = filePath, sourceUri = sourceUri, identifier = identifier,
        isTruncated = isTruncated, originalCharCount = originalCharCount,
        totalWords = totalWords, readProgress = readProgress,
        lastReadPosition = lastReadPosition, lastReadTime = lastReadTime,
        dateAdded = dateAdded, language = language, isArchived = isArchived,
        addedAt = addedAt,
    )

    private fun BookEntity.toDomain() = Book(
        id = id, title = title, author = author, coverPath = coverPath,
        filePath = filePath, sourceUri = sourceUri, identifier = identifier,
        isTruncated = isTruncated, originalCharCount = originalCharCount,
        totalWords = totalWords, readProgress = readProgress,
        lastReadPosition = lastReadPosition, lastReadTime = lastReadTime,
        dateAdded = dateAdded, language = language, isArchived = isArchived,
        content = content, addedAt = addedAt,
    )

    private fun Book.toEntity() = BookEntity(
        id = id, title = title, author = author, coverPath = coverPath,
        filePath = filePath, sourceUri = sourceUri, identifier = identifier,
        isTruncated = isTruncated, originalCharCount = originalCharCount,
        totalWords = totalWords, readProgress = readProgress,
        lastReadPosition = lastReadPosition, lastReadTime = lastReadTime,
        dateAdded = dateAdded, language = language, isArchived = isArchived,
        content = content, addedAt = addedAt,
    )

    /**
     * issue 9.7：identifier 去重命中时，清理本次导入刚拷出的临时副本（仅限应用
     * books 目录内的文件，绝不碰用户目录）。失败仅告警，不阻塞返回旧 id。
     */
    private suspend fun deleteOrphanCopy(filePath: String) {
        if (filePath.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                val booksDir = File(context.filesDir, "books").apply { mkdirs() }
                val safe = try {
                    file.canonicalPath.startsWith(booksDir.canonicalPath + File.separator)
                } catch (_: java.io.IOException) {
                    file.absolutePath.startsWith(booksDir.absolutePath + File.separator)
                }
                if (safe && file.exists()) {
                    file.delete()
                } else {
                    android.util.Log.w("BookRepository", "Skip deleting non-books-dir copy on dedup: $filePath")
                }
            } catch (e: Exception) {
                android.util.Log.w("BookRepository", "Failed to delete orphan copy on dedup", e)
            }
        }
    }
}
