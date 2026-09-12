package com.eareyereading.ui.screens.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.eareyereading.data.repository.CategoryPrefs
import com.eareyereading.domain.model.Book

/**
 * 书卡动作共享件：列表卡（BookCard）与网格卡（BookGridCard）共用的
 * ⋮ 菜单与两个确认/编辑弹窗，消除两份菜单项 + 弹窗的复制粘贴漂移
 * （菜单语义今后若有调整，改这一处即可）。
 */

/** 书卡 ⋮ 菜单：移至分类 / 归档 / 删除。 */
@Composable
internal fun BookCardMenu(
    expanded: Boolean,
    book: Book,
    onDismiss: () -> Unit,
    onCategorizeClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("移至分类（当前：${book.category.ifBlank { "未分类" }}）") },
            onClick = onCategorizeClick,
            leadingIcon = { Icon(Icons.Default.Category, null) },
        )
        DropdownMenuItem(
            text = { Text("移至归档") },
            onClick = onArchiveClick,
            leadingIcon = { Icon(Icons.Default.Archive, null) },
        )
        DropdownMenuItem(
            text = { Text("删除") },
            onClick = onDeleteClick,
            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
        )
    }
}

/**
 * 书卡共享弹窗：分类编辑 + 删除二次确认（删除级联清书签/高亮/进度/统计，
 * 必须防误触）。列表卡与网格卡共用。
 */
@Composable
internal fun BookActionDialogs(
    book: Book,
    showCategory: Boolean,
    showDelete: Boolean,
    categories: List<String>,
    bookCounts: Map<String, Int>,
    categoryMetaByName: Map<String, CategoryPrefs.Meta>,
    onDismissCategory: () -> Unit,
    onConfirmCategory: (String) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    if (showCategory) {
        CategoryEditDialog(
            current = book.category.ifBlank { "未分类" },
            categories = categories,
            bookCountByName = bookCounts,
            categoryMetaByName = categoryMetaByName,
            onDismiss = onDismissCategory,
            onConfirm = onConfirmCategory,
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("删除《${book.title}》？") },
            text = { Text("将同时删除该书的书签、高亮、阅读进度和统计，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) { Text("取消") }
            },
        )
    }
}
