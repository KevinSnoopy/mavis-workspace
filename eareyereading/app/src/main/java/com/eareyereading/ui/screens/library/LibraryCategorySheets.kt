package com.eareyereading.ui.screens.library

import androidx.compose.ui.graphics.toArgb
import com.eareyereading.ui.components.category.AddBookFlowSheet
import com.eareyereading.ui.components.category.Category
import com.eareyereading.ui.components.category.CategoryEditSheet
import com.eareyereading.ui.components.category.CategoryManageSheet
import androidx.compose.runtime.Composable
import com.eareyereading.domain.model.Book

/**
 * 分类管理 sheet（TopAppBar 菜单按钮触发）。
 *
 * @param categories 合成的全部分类列表
 * @param onEdit 编辑分类回调
 * @param onAdd 新建分类回调
 * @param onDelete 删除分类元数据回调
 * @param onReorder 拖动排序回调
 * @param onDismiss 关闭回调
 */
@Composable
internal fun LibraryCategoryManageSheetWrapper(
    categories: List<Category>,
    onEdit: (Category) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Category) -> Unit,
    onReorder: (List<Category>) -> Unit,
    onDismiss: () -> Unit,
) {
    CategoryManageSheet(
        categories = categories,
        onEdit = onEdit,
        onAdd = onAdd,
        onDelete = onDelete,
        onReorder = onReorder,
        onDismiss = onDismiss,
    )
}

/**
 * 新建 / 编辑分类 sheet（「+ 新建分类」或管理列表编辑触发）。
 *
 * @param initial 初始分类（null = 新建模式）
 * @param onSave 保存回调（分类名、图标名、颜色 ARGB long）
 * @param onDismiss 关闭回调
 */
@Composable
internal fun LibraryCategoryEditSheetWrapper(
    initial: Category?,
    onSave: (name: String, icon: String, color: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    CategoryEditSheet(
        initial = initial,
        onSave = { cat ->
            onSave(cat.name, cat.icon.name, cat.color.toArgb().toLong())
        },
        onDismiss = onDismiss,
    )
}

/**
 * 导入后完善信息 sheet（选分类 + 选封面），EPUB 文件导入成功后触发。
 *
 * @param categories 全部分类列表
 * @param pendingBook 待完善信息的书
 * @param onComplete 完善完成回调（分类名、封面 ID）
 * @param onDismiss 跳过回调
 * @param onCreateCategory 流程内新建分类回调（分类名、图标名、颜色 ARGB long）
 */
@Composable
internal fun LibraryBookRefineSheetWrapper(
    categories: List<Category>,
    pendingBook: Book,
    onComplete: (categoryName: String?, coverId: Int) -> Unit,
    onDismiss: () -> Unit,
    onCreateCategory: (name: String, icon: String, color: Long) -> Unit = { _, _, _ -> },
) {
    AddBookFlowSheet(
        categories = categories,
        initialTitle = pendingBook.title,
        initialAuthor = pendingBook.author,
        onComplete = { _, _, _, categoryName, coverId ->
            onComplete(categoryName, coverId)
        },
        onDismiss = onDismiss,
        onCreateCategory = { cat ->
            // 与 LibraryCategoryEditSheetWrapper 保持同一套持久化参数形态
            onCreateCategory(cat.name, cat.icon.name, cat.color.toArgb().toLong())
        },
    )
}
