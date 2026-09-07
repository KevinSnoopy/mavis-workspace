package com.eareyereading.data.repository

import android.content.Context
import com.eareyereading.util.BookImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 书籍相关文件的安全清理。
 *
 * 从 BookRepositoryImpl 拆出（SRP）：删书、去重命中时都需要清理导入时拷贝的
 * 书籍文件 / 封面 / 插图目录，且都要求"只删应用 books 目录内的文件"。
 * 集中到此避免安全路径校验逻辑散落重复。 */
@Singleton
internal class BookFileCleanup @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {

    /**
     * issue 9.7：identifier 去重命中时，清理本次导入刚拷出的临时副本（仅限应用
     * books 目录内的文件，绝不碰用户目录）。失败仅告警，不阻塞返回旧 id。
     */
    suspend fun deleteOrphanCopy(filePath: String) {
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

    /**
     * 删书后清理导入时拷贝的书籍文件、封面、插图目录。
     *
     * - 书籍文件仅限应用 books 目录内（绝不碰用户目录）；
     * - canonical 解析失败时回退到 absolutePath 字符串前缀比较（issue 10.7）；
     * - 封面文件 covers/{bookId}、插图目录 book_images/{bookId}/ 随书删除。
     */
    suspend fun deleteBookFiles(filePath: String, bookId: Long) {
        if (filePath.isBlank()) return
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

            // 插图目录随书删除（book_images/{bookId}/，导入时提取的降采样插图）
            BookImages.deleteBookImages(context, bookId)
        }
    }
}
