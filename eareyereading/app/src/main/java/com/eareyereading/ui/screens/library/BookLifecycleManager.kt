@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.eareyereading.ui.screens.library

import com.eareyereading.domain.repository.BookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 书籍生命周期操作的单一职责控制器：删除、归档、取消归档。
 *
 * 级联清理与异常兜底在此集中处理，结果消息走 [LibraryStateController]。
 */
internal class BookLifecycleManager(
    private val stateController: LibraryStateController,
    private val bookRepository: BookRepository,
) {

    fun deleteBook(bookId: Long, scope: CoroutineScope) {
        scope.launch {
            // issue 11.14：删除是级联清理（书/进度/生词/复习记录/文件），
            // 任一环抛异常都会把未捕获异常直接甩进 viewModelScope 崩 app
            try {
                bookRepository.deleteBook(bookId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "deleteBook failed", e)
                stateController.setResultMessage("删除失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun archiveBook(bookId: Long, scope: CoroutineScope) {
        scope.launch {
            bookRepository.setArchived(bookId, true)
        }
    }

    /** Snackbar"撤销"入口：滑动归档可一键还原（归档目前无浏览入口，必须可撤销）。 */
    fun unarchiveBook(bookId: Long, scope: CoroutineScope) {
        scope.launch {
            try {
                bookRepository.setArchived(bookId, false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "unarchiveBook failed", e)
            }
        }
    }
}
