package com.eareyereading.data.repository

import android.content.Context
import com.eareyereading.util.BookImages
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** EPUB 插图落盘：zip 条目 → 降采样 JPEG。
 *
 * 从 BookRepositoryImpl 拆出（SRP）：插图解码/降采样/JPEG 重编码是独立的
 * 图像处理职责，与书籍导入主流程（解析/去重/元数据填充）无业务耦合。
 *
 * 性能策略（用户确认"展示可以模糊一些"）：
 *  - 边界采样计算 inSampleSize，统一缩到 ≤[IMAGE_TARGET_DIM]px 再解码，
 *    导入期一次重编码成 JPEG 75 —— 阅读时 Coil 解码的是小文件，
 *    滚动加载大插图也不再有整页级位图进出内存；
 *  - 单条目 5MB 上限（防压缩炸弹），解码/写盘失败静默跳过该图，
 *    不阻断导入主流程。
 */
@Singleton
internal class BookImageExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private companion object {
        /** 单张插图 zip 条目字节上限（防压缩炸弹，与封面上限同量级）。 */
        const val MAX_IMAGE_ENTRY_BYTES = 5L * 1024 * 1024

        /** 插图落盘目标边长（px）：统一降采样到该尺寸内再存 JPEG。
         *  阅读展示允许略糊（用户确认），换取解码内存 ~≤3MB/张 + 秒级导入。 */
        const val IMAGE_TARGET_DIM = 1000

        /** 插图 JPEG 落盘质量。 */
        const val IMAGE_JPEG_QUALITY = 75
    }

    /**
     * EPUB 插图落盘：zip 条目 → 降采样 JPEG（filesDir/book_images/<bookId>/img_n.jpg）。
     */
    fun extractBookImages(bookId: Long, filePath: String, imageEntryNames: List<String>) {
        if (imageEntryNames.isEmpty()) return
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) return
        java.util.zip.ZipFile(file).use { zip ->
            var saved = 0
            imageEntryNames.forEachIndexed { index, entryName ->
                try {
                    val entry = zip.getEntry(entryName) ?: return@forEachIndexed
                    if (entry.size > MAX_IMAGE_ENTRY_BYTES) return@forEachIndexed
                    val bytes = zip.getInputStream(entry).use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0L
                        while (total <= MAX_IMAGE_ENTRY_BYTES) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                    if (bytes.isEmpty()) return@forEachIndexed
                    if (decodeAndSaveImage(bytes, BookImages.localImageFile(context, bookId, index))) {
                        saved++
                    } else {
                        android.util.Log.w(
                            "BookRepository",
                            "extractBookImages: skipped index=$index entry=$entryName " +
                                "size=${bytes.size}B — img_$index.jpg NOT created (reader will show \"图片加载失败\")",
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.w("BookRepository", "save image $entryName failed", e)
                }
            }
            android.util.Log.i("BookRepository", "extracted $saved/${imageEntryNames.size} images for book $bookId")
        }
    }

    /** 解码 → inSampleSize 降采样 → JPEG 重编码落盘。 */
    private fun decodeAndSaveImage(bytes: ByteArray, outFile: File): Boolean {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            android.util.Log.w(
                "BookRepository",
                "decodeAndSaveImage: invalid bounds ${bounds.outWidth}x${bounds.outHeight} " +
                    "mime=${bounds.outMimeType} bytes=${bytes.size}B outFile=${outFile.name}",
            )
            return false
        }
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= IMAGE_TARGET_DIM || h / 2 >= IMAGE_TARGET_DIM) {
            sample *= 2
            w /= 2
            h /= 2
        }
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: run {
                android.util.Log.w(
                    "BookRepository",
                    "decodeAndSaveImage: decodeByteArray null " +
                        "mime=${bounds.outMimeType} ${bounds.outWidth}x${bounds.outHeight} " +
                        "bytes=${bytes.size}B outFile=${outFile.name}",
                )
                return false
            }
        return try {
            outFile.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, out)
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("BookRepository", "compress image failed: ${outFile.name}", e)
            false
        } finally {
            bitmap.recycle()
        }
    }
}
