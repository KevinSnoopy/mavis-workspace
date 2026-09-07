package com.eareyereading.ui.screens.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * URL 导入文章弹窗：输入英文文章网址，自动抓取正文。
 *
 * @param urlInput 当前 URL 输入值
 * @param onUrlInputChange URL 输入变更回调
 * @param onImport 确认抓取回调
 * @param onDismiss 取消/关闭回调
 */
@Composable
internal fun LibraryUrlImportDialog(
    urlInput: String,
    onUrlInputChange: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🌐 导入网址文章") },
        text = {
            Column {
                Text(
                    "输入英文文章网址，自动抓取正文",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = onUrlInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://bbc.com/...") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = { Icon(Icons.Default.Link, null) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onImport,
                enabled = urlInput.isNotBlank(),
            ) { Text("抓取") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
