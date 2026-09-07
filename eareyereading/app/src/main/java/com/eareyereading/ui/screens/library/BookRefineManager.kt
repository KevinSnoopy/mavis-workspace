@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.eareyereading.ui.screens.library

import com.eareyereading.domain.repository.BookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 导入后「完善信息」流程的单一职责控制器。
 *
 * 持有待完善书 id，跟随书库流变化（如去重命中、行被删）自动更新
 * pendingRefineBook，避免 UI 拿到失效 Book。完善/跳过后清除挂起状态。
 */
internal class BookRefineManager(
    private val stateController: LibraryStateController,
    private val bookRepository: BookRepository,
) {

    /** v2：导入后待完善信息的书 id；null = 无待完善流程 */
    private val pendingRefineBookId = MutableStateFlow<Long?>(null)

    /** 启动待完善书跟随流：pendingRefineBookId 在书库中的对应行 */
    fun startCollection(scope: CoroutineScope) {
        scope.launch {
            pendingRefineBookId
                .combine(bookRepository.getAllBooks()) { id, books ->
                    id?.let { target -> books.find { it.id == target } }
                }
                .collect { book ->
                    stateController.update { it.copy(pendingRefineBook = book) }
                }
        }
    }

    /** 导入成功后挂起完善流程 */
    fun setPendingRefineBookId(bookId: Long) {
        pendingRefineBookId.value = bookId
    }

    /** 完善流程：写分类 + 封面背景后清除挂起状态 */
    fun finishBookRefine(category: String, coverStyle: Int, scope: CoroutineScope) {
        val book = stateController.current.pendingRefineBook ?: return
        scope.launch {
            try {
                if (category.isNotBlank()) bookRepository.updateCategory(book.id, category)
                bookRepository.updateCoverStyle(book.id, coverStyle)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "finishBookRefine failed", e)
                stateController.setResultMessage("保存书籍信息失败")
            } finally {
                pendingRefineBookId.value = null
            }
        }
    }

    /** 跳过完善流程：保留导入时的默认分类与封面 */
    fun skipBookRefine() {
        pendingRefineBookId.value = null
    }
}
