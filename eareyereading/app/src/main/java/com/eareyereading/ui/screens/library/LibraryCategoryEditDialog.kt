package com.eareyereading.ui.screens.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eareyereading.data.repository.CategoryPrefs
import com.eareyereading.ui.components.category.Category
import com.eareyereading.ui.components.category.CategoryIcon
import com.eareyereading.ui.components.category.CategorySelectGrid
import com.eareyereading.ui.components.category.derivedColorFor
import com.eareyereading.ui.components.category.derivedIconFor

/**
 * 分类编辑弹窗（v2 重写）：用「图标 + 颜色」分类卡网格替代原 FlowRow + 文字 chip，
 * 保留自定义输入（新输入的分类名即建即用，元数据按名称派生稳定默认）。
 * 确认回调最终分类名（空输入由仓库层归一化为"未分类"）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryEditDialog(
    current: String,
    categories: List<String>,
    bookCountByName: Map<String, Int> = emptyMap(),
    categoryMetaByName: Map<String, CategoryPrefs.Meta> = emptyMap(),
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 可选分类 = 预设 + 现有分类去重；合成完整 Category（图标/颜色取 meta，缺省派生）
    val selectableCategories = remember(categories, categoryMetaByName) {
        (listOf("未分类", "经典名著", "小说", "非虚构", "文章", "教材", "科技") + categories)
            .distinct()
            .map { name ->
                val meta = categoryMetaByName[name]
                Category(
                    name = name,
                    bookCount = bookCountByName[name] ?: 0,
                    icon = meta?.let { m ->
                        runCatching { CategoryIcon.valueOf(m.icon) }.getOrDefault(derivedIconFor(name))
                    } ?: derivedIconFor(name),
                    color = meta?.let { m -> Color(m.color) } ?: derivedColorFor(name),
                )
            }
    }
    var custom by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移至分类") },
        text = {
            Column {
                Text(
                    "点选分类卡，或输入自定义分类名",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                CategorySelectGrid(
                    categories = selectableCategories,
                    selectedName = custom,
                    onSelect = { cat -> custom = cat.name.take(12) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.take(12) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("分类名（12 字内）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = { Icon(Icons.Default.Category, null) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(custom) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
