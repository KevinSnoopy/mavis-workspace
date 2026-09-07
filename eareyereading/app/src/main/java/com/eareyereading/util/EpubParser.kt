@file:Suppress("SwallowedException", "ReturnCount")

package com.eareyereading.util

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/** EPUB 解析失败的具体原因（issue 9.3）：
 * 此前所有 IOException 一律吞掉返回空列表，导入 UI 只能笼统提示"文件读取错误"，
 * 无法区分损坏/加密/空文件/缺 OPF/无可读章节。
 * 继承 IOException，调用方现有 catch 语句无需改动。 */
sealed class EpubParseException(message: String) : java.io.IOException(message) {
    /** zip 结构损坏或根本不是 EPUB。 */
    class Corrupted : EpubParseException("文件损坏或非 EPUB 格式")

    /** 加密 EPUB（java.util.zip 在读条目时抛 "encrypted ZIP entry not supported"）。 */
    class Encrypted : EpubParseException("文件已加密（需解密）")

    /** 0 字节 / 不存在的文件。 */
    class Empty : EpubParseException("文件为空或不存在")

    /** zip 有效但找不到 OPF 描述文件。 */
    class NoOpf : EpubParseException("EPUB 缺少 OPF 描述文件")

    /** OPF/spine 有效但提取不到任何正文段落。 */
    class NoContent : EpubParseException("EPUB 不含可读章节内容")

    /** 章节全是图片、没有任何可读文本段落（图册类 EPUB，issue 9.8）。 */
    class ImageOnly : EpubParseException("此书为图册，不支持导入")
}

/**
 * EPUB 解析结果（issue 9.1 / 9.2 / 9.8）：
 * 此前 [EpubParser.parseBook] 只返回段落列表，OPF 里的元数据
 * （标题/作者/语言）从不提取，导入书籍卡片标题 = 文件名、作者 = Unknown、
 * 语言 = en（永远），TTS 用英文声读中文/日文书。
 */
data class ParsedBook(
    /** OPF `<dc:title>`；缺失时为空串。 */
    val title: String = "",
    /** OPF `<dc:creator>`；缺失时为空串。 */
    val author: String = "",
    /** OPF `<dc:language>`（小写，截断到 8 字符）；缺失时为空串。 */
    val language: String = "",
    /** OPF `<dc:identifier>`（书籍唯一标识，跨导入去重用，issue 9.7）；缺失时为空串。 */
    val identifier: String = "",
    /** 按 spine 顺序提取的正文段落。插图以 `[[IMG:n]]` 标记段出现。 */
    val paragraphs: List<String> = emptyList(),
    /**
     * 插图 zip 条目名，与段落标记 `[[IMG:n]]` 的 n 一一对应
     * （导入时由 BookRepositoryImpl 落盘降采样 JPEG）。
     */
    val imageEntryNames: List<String> = emptyList(),
    /** 是否因 [EpubContentExtractor.MAX_TOTAL_CHARS] 上限被截断（issue 9.2）。 */
    val wasTruncated: Boolean = false,
    /** 截断前扫描到的原文累计字符数（仅截断时>0，issue 9.2）。 */
    val originalCharCount: Int = 0,
    /** 解析过程中见到的 `<img>` 数量（图册检测用，issue 9.8）。 */
    val images: Int = 0,
)

/**
 * EPUB 文件解析器
 * 解析 EPUB 文件并提取段落文本
 *
 * 重构后职责拆分（SRP）：
 *  - [EpubZipReader]：ZIP 条目低层读取 / 编码探测 / 条目解析 / OPF 定位
 *  - [OpfParser]：OPF 元数据 / spine / manifest 解析
 *  - [XhtmlParagraphExtractor]：XHTML → 段落提取
 *  - [EpubContentExtractor]：spine 遍历 / 图片标记解析 / 截断
 *  - [EpubCoverExtractor]：封面图片提取
 * 本类仅保留外部 API 入口与文件级防护/流兜底编排。
 */
@Singleton
class EpubParser @Inject constructor() {

    companion object {
        /** EPUB 文件总字节上限（issue 10.4）：防超大连载/恶意文件占满存储或撑爆内存。 */
        private const val MAX_EPUB_BYTES = 200L * 1024 * 1024
    }

    /**
     * 解析 EPUB 文件。
     * 解析失败抛 [EpubParseException]（IOException 子类），
     * 调用方可按异常类型给出可区分的失败提示。
     * 成功时返回 [ParsedBook]（含 OPF 元数据与段落，issue 9.1/9.2/9.8）。
     */
    fun parseBook(filePath: String): ParsedBook {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) throw EpubParseException.Empty()
        // 字节级防护（issue 10.4）：文件本身超限直接拒绝，避免进一步解压 OOM/占盘
        if (file.length() > MAX_EPUB_BYTES) {
            android.util.Log.w("EpubParser", "EPUB exceeds ${MAX_EPUB_BYTES / (1024 * 1024)}MB: ${file.length()}")
            throw EpubParseException.Corrupted()
        }
        return try {
            parseEpub(file)
        } catch (e: EpubParseException) {
            throw e
        } catch (e: java.io.IOException) {
            // ZipException 是 IOException 子类：加密条目在读流时才暴露，
            // 靠 message 特征分流出"已加密"，其余按损坏归类
            if (e.message?.contains("encrypt", ignoreCase = true) == true) {
                throw EpubParseException.Encrypted()
            }
            android.util.Log.e("EpubParser", "Error reading EPUB file: ${filePath}", e)
            throw EpubParseException.Corrupted()
        }
    }

    /**
     * 统一读取代理（issue 9.9）：文件优先、content:// URI 兜底。
     * 存量书在换设备/清数据后本地拷贝可能失效（filePath 指向的文件不存在），
     * 此时回退用 sourceUri 经 ContentResolver 重新打开流读取，避免"书源丢失打不开"。
     * 文件存在时等价于 [parseBook] 的既有行为，不破坏存量书解析。
     * 解析失败抛 [EpubParseException]（IOException 子类），调用方可按类型区分。
     */
    fun parseBook(
        filePath: String,
        sourceUri: String?,
        resolver: ContentResolver,
    ): ParsedBook {
        val file = File(filePath)
        if (file.exists() && file.length() > 0L) {
            return parseBook(filePath)
        }
        if (!sourceUri.isNullOrBlank()) {
            val input = try {
                resolver.openInputStream(Uri.parse(sourceUri))
            } catch (e: java.io.IOException) {
                android.util.Log.w("EpubParser", "openInputStream failed for $sourceUri", e)
                throw EpubParseException.Empty()
            } catch (e: SecurityException) {
                android.util.Log.w("EpubParser", "No read permission for $sourceUri", e)
                throw EpubParseException.Empty()
            }
            if (input == null) {
                android.util.Log.w("EpubParser", "openInputStream returned null for $sourceUri")
                throw EpubParseException.Empty()
            }
            return parseFromInputStream(input, sourceUri)
        }
        throw EpubParseException.Empty()
    }

    /**
     * 提取 EPUB 内嵌封面图片字节（导入时调用，落盘为书籍封面文件）。
     * 查找顺序（与主流阅读器一致）：
     * 1. OPF manifest 中 properties="cover-image" 的条目（EPUB 3 标准）；
     * 2. `<meta name="cover" content="id">` 指向的 manifest 条目（EPUB 2 常见）；
     * 3. 兜底：manifest 中 id/href 含 "cover" 的图片条目。
     * 找不到、读取失败或超过 5MB 上限时返回 null，调用方回退生成式封面。
     */
    fun extractCoverImage(filePath: String): ByteArray? = EpubCoverExtractor.extractCoverImage(filePath)

    /**
     * 从任意 InputStream 解析 EPUB（issue 9.9 URI 兜底用）。
     * ZipFile 需要随机访问，content:// 流不能直接喂给 ZipFile：
     * 先按字节上限拷到临时文件，再复用既有 [parseEpub] 逻辑，解析完即删。
     */
    private fun parseFromInputStream(input: java.io.InputStream, label: String): ParsedBook {
        val tempFile = try {
            File.createTempFile("epub_resolve_", ".epub")
        } catch (e: java.io.IOException) {
            throw EpubParseException.Corrupted()
        }
        try {
            var total = 0L
            tempFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    // 字节级防护（issue 10.4）：流来源同样受全局体积上限约束
                    if (total > MAX_EPUB_BYTES) {
                        android.util.Log.w(
                            "EpubParser",
                            "EPUB exceeds ${MAX_EPUB_BYTES / (1024 * 1024)}MB from $label",
                        )
                        throw EpubParseException.Corrupted()
                    }
                    output.write(buffer, 0, n)
                }
            }
            input.close()
            if (total == 0L) throw EpubParseException.Empty()
            return parseEpub(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 解析 EPUB 文件
     */
    private fun parseEpub(file: File): ParsedBook {
        // OPF 元数据在 zip 块内解析，ParsedBook 在块外组装，须提升作用域
        var metadata = OpfParser.OpfMetadata("", "", "", "")
        lateinit var content: EpubContentExtractor.ExtractedContent
        ZipFile(file).use { zip ->
            // 一次性构建条目索引：防恶意 EPUB 在 OPF 里塞海量 itemref，让
            // resolveEntry 兜底分支退化成 O(spine × entries) 算法炸弹（issue 10.3）
            val entryNames = zip.entries().toList()
            if (entryNames.size > EpubZipReader.MAX_ZIP_ENTRIES) {
                android.util.Log.w("EpubParser", "EPUB has ${entryNames.size} entries, exceeding ${EpubZipReader.MAX_ZIP_ENTRIES}")
                throw EpubParseException.Corrupted()
            }

            // issue 9.5：优先读 META-INF/container.xml 里声明的 OPF 路径，
            // 只有读不到/解析不到时才回退到"第一个 .opf"启发式（并打 WARN）
            val opfEntry = EpubZipReader.findOpfEntry(entryNames, zip) ?: throw EpubParseException.NoOpf()

            // 解析 OPF 获取 spine 顺序 + 元数据
            val opfContent = EpubZipReader.readEntryTextCapped(zip.getInputStream(opfEntry), EpubZipReader.MAX_DOC_CHARS)
            metadata = OpfParser.extractMetadata(opfContent)
            // spine href 是相对 OPF 所在目录的路径
            val opfDir = opfEntry.name.substringBeforeLast('/', "")

            content = EpubContentExtractor.extractContent(zip, entryNames, opfContent, opfDir)
        }

        // issue 9.8：没有可读段落但全是图片 → 图册，给明确错误而非笼统 NoContent
        if (content.paragraphs.isEmpty() && content.images > 0) throw EpubParseException.ImageOnly()
        if (content.paragraphs.isEmpty()) throw EpubParseException.NoContent()
        return ParsedBook(
            title = metadata.title,
            author = metadata.author,
            language = metadata.language,
            identifier = metadata.identifier,
            paragraphs = content.paragraphs,
            imageEntryNames = content.imageEntryNames,
            wasTruncated = content.wasTruncated,
            originalCharCount = content.originalCharCount,
            images = content.images,
        )
    }
}
