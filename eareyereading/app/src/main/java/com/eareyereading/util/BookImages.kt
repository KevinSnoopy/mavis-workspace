package com.eareyereading.util

import android.content.Context
import java.io.File

/**
 * 书籍/文章插图的段落标记协议。
 *
 * 解析期（EpubParser / ArticleParser）把 `<img>` 替换为独立段落标记：
 *   - EPUB：`[[IMG:<序号>]]`，序号对应导入时落盘的
 *     `filesDir/book_images/<bookId>/img_<序号>.jpg`（已降采样重编码）
 *   - 网络文章：`[[IMG:<绝对 URL>]]`，渲染时按 URL 在线加载
 *
 * 所有消费方（TTS/翻译/词频/挖空/朗读）通过 [stripImageMarkers] 剔除标记，
 * 渲染层通过 [markerRef] 识别并把该段落画成图片。
 */
object BookImages {

    /** 整段就是一个图片标记（渲染层按插图处理）。 */
    private val MARKER_PARAGRAPH = Regex("^\\[\\[IMG:([^\\]]+)\\]\\]$")

    /** 文本中任意位置的图片标记（剥离用，捕获组保住标记本身）。 */
    private val MARKER_SPLIT = Regex("(\\[\\[IMG:[^\\]]*\\]\\])")

    /** 行内标记剥离（无捕获组，直接删除）。 */
    private val MARKER_REMOVE = Regex("\\[\\[IMG:[^\\]]*\\]\\]")

    /** 标记剥离后的连续空格折叠（"a [[IMG:0]] b" → "a b"）。 */
    private val MULTI_SPACE = Regex(" {2,}")

    // ── HTML <img> → 标记 的共享转换（ArticleParser / RssParser 共用）──

    /** 整段 <img> 标签（src 属性顺序无关）。 */
    private val IMG_TAG_FULL = Regex(
        "<img\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
        RegexOption.IGNORE_CASE,
    )

    /** 明显的装饰/追踪类小图：命中即丢弃（1x1 像素、分享徽章等）。 */
    private val IMG_NOISE_SRC = Regex(
        "(1x1|pixel|spacer|blank\\.gif|tracking|analytics|beacon|badge|icon|logo|avatar|emoji|smiley|sprite)",
        RegexOption.IGNORE_CASE,
    )

    /** Coil 未含 svg 模块，svg 源直接跳过。 */
    private val IMG_SKIP_EXT = Regex("\\.svg(\\?|#|$)", RegexOption.IGNORE_CASE)

    /**
     * 把 HTML 里的 `<img>` 替换为独立段落标记 `[[IMG:绝对URL]]`。
     * - 噪音图（追踪/徽章/svg/data URI）与无法解析成绝对 http(s) 的 src 直接丢弃；
     * - 相对 src 以 [baseUrl] 解析补全；
     * - 标记两侧垫空行，后续按空行分段的管线（cleanText /
     *   stripHtmlKeepParagraphs / addArticleToLibrary 的 split）自然把标记
     *   切成独立段落，渲染层按插图整块呈现，文字不再错位。
     */
    fun replaceImgTagsWithMarkers(html: String, baseUrl: String?): String =
        html.replace(IMG_TAG_FULL) { m ->
            val src = m.groupValues[1].trim()
            val absolute = when {
                src.isBlank() || src.startsWith("data:", ignoreCase = true) -> null
                IMG_SKIP_EXT.containsMatchIn(src) -> null
                IMG_NOISE_SRC.containsMatchIn(src) -> null
                src.startsWith("http://") || src.startsWith("https://") -> src
                baseUrl != null -> try {
                    java.net.URI(baseUrl).resolve(src).toString()
                } catch (_: Exception) {
                    null
                }
                else -> null
            }
            if (absolute != null &&
                (absolute.startsWith("http://") || absolute.startsWith("https://"))
            ) {
                "\n\n[[IMG:$absolute]]\n\n"
            } else {
                ""
            }
        }

    /** 该段落是否是图片标记段（渲染为插图而非文本）。 */
    fun isImageMarker(paragraph: String): Boolean = MARKER_PARAGRAPH.matches(paragraph.trim())

    /** 取标记引用（EPUB 序号或文章 URL）；非标记段返回 null。 */
    fun markerRef(paragraph: String): String? =
        MARKER_PARAGRAPH.find(paragraph.trim())?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** 剥离文本里的所有图片标记（TTS 朗读/词频统计/挖空生成等输入净化）。 */
    fun stripImageMarkers(text: String): String =
        MARKER_REMOVE.replace(text, "")
            .replace(MULTI_SPACE, " ")
            .trim()

    /**
     * 把"文本与行内标记混排"的段落拆开：标记独立成段，文本片段保持原序。
     * 解析期的正则/空白压缩会把标记挤进相邻文本，这里统一规整为
     * "纯文本段 + 纯标记段"序列。
     *
     * 注意不能用 Regex.split：它丢弃匹配本身，标记会被吞掉；
     * 这里手工扫描，标记与夹在其间的文本片段都保留。
     */
    fun expandInlineMarkers(paragraphs: List<String>): List<String> =
        paragraphs.flatMap { para ->
            if (!para.contains("[[IMG:")) {
                listOf(para)
            } else {
                buildList {
                    var last = 0
                    for (match in MARKER_SPLIT.findAll(para)) {
                        val before = para.substring(last, match.range.first).trim()
                        if (before.isNotEmpty()) add(before)
                        add(match.value)
                        last = match.range.last + 1
                    }
                    val after = para.substring(last).trim()
                    if (after.isNotEmpty()) add(after)
                }
            }
        }

    /** EPUB 落盘插图目录：filesDir/book_images/<bookId>/。 */
    fun bookImageDir(context: Context, bookId: Long): File =
        File(File(context.filesDir, "book_images"), bookId.toString())

    /** EPUB 落盘插图文件：img_<序号>.jpg。 */
    fun localImageFile(context: Context, bookId: Long, index: Int): File =
        File(bookImageDir(context, bookId), "img_$index.jpg")

    /** 删除某本书的全部落盘插图（删除书籍时调用，静默容错）。 */
    fun deleteBookImages(context: Context, bookId: Long) {
        try {
            bookImageDir(context, bookId).deleteRecursively()
        } catch (_: Exception) {
            // 插图清理失败不阻塞删书主流程
        }
    }
}
