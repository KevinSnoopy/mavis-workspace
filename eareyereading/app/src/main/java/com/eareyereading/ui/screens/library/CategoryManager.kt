@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.eareyereading.ui.screens.library

import com.eareyereading.data.repository.CategoryPrefs
import com.eareyereading.domain.repository.BookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 书架分类管理的单一职责控制器，涵盖：
 *
 * - 分类筛选切换（[setCategory]）
 * - 修改单本书分类（[updateBookCategory]）
 * - v2 分类元数据持久化：保存/删除/拖动排序
 *
 * 结果消息与 UI 状态走 [LibraryStateController]。
 */
internal class CategoryManager(
    private val stateController: LibraryStateController,
    private val bookRepository: BookRepository,
    private val categoryPrefs: CategoryPrefs,
) {

    /** 分类筛选：null = 全部（分组展示所有书）。 */
    fun setCategory(category: String?) {
        stateController.update { it.copy(selectedCategory = category) }
    }

    /** 修改书籍分类（书卡菜单入口；空串/空白在仓库层归一化为"未分类"）。
     *  分类列表由 books Flow 异步刷新；被清空的选中分类由 UI 层
     *  （effectiveCategory = selectedCategory?.takeIf { it in categories }）兜底回"全部"。 */
    fun updateBookCategory(bookId: Long, category: String, scope: CoroutineScope) {
        scope.launch {
            try {
                bookRepository.updateCategory(bookId, category)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "updateCategory failed", e)
                stateController.setResultMessage("分类修改失败")
            }
        }
    }

    // ── v2：分类自定义（元数据持久化到 DataStore）──────────

    /** 保存分类元数据（新建或编辑）：分类名是主键，重名即编辑。
     *  新建的分类若无书挂上，会出现在 customCategories（meta 存在即分类存在）。 */
    fun saveCategoryMeta(name: String, icon: String, color: Long, scope: CoroutineScope) {
        scope.launch {
            try {
                categoryPrefs.setMeta(name, CategoryPrefs.Meta(icon = icon, color = color))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "saveCategoryMeta failed", e)
                stateController.setResultMessage("分类保存失败")
            }
        }
    }

    /** 删除分类元数据：书籍的 category 字符串保留（回退派生默认显示），不影响藏书 */
    fun deleteCategoryMeta(name: String, scope: CoroutineScope) {
        scope.launch {
            try {
                categoryPrefs.removeMeta(name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "deleteCategoryMeta failed", e)
                stateController.setResultMessage("分类删除失败")
            }
        }
    }

    /** 拖动排序：按新顺序批量写 order（原子生效）。无 meta 的分类 UI 侧回退派生顺序。 */
    fun reorderCategories(names: List<String>, scope: CoroutineScope) {
        scope.launch {
            try {
                categoryPrefs.setOrders(names.withIndex().associate { (i, n) -> n to i })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "reorderCategories failed", e)
                stateController.setResultMessage("排序保存失败")
            }
        }
    }
}
