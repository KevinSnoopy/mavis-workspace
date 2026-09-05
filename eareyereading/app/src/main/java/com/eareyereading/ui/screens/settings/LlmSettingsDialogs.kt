package com.eareyereading.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.*
import java.util.*
import kotlinx.coroutines.flow.*

/**
 * AI 翻译服务商预设与配置组件：GLM / DeepSeek 预设选项与自定义端点输入弹窗。
 */
// ── AI 翻译服务商预设（OpenAI 兼容端点）──
internal const val LLM_PRESET_GLM_BASE = "https://open.bigmodel.cn/api/paas/v4"
internal const val LLM_PRESET_GLM_MODEL = "glm-4-flash"
internal const val LLM_PRESET_DEEPSEEK_BASE = "https://api.deepseek.com/v1"
internal const val LLM_PRESET_DEEPSEEK_MODEL = "deepseek-chat"

/** 服务商预设选项行（单选样式）。 */
@Composable
internal fun LlmPresetOption(
    name: String,
    note: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** AI 翻译配置的单字段输入弹窗（Key/模型/接口地址共用）。 */
@Composable
internal fun LlmTextFieldDialog(
    title: String,
    initialValue: String,
    label: String,
    helperText: String = "",
    mask: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(label) },
                    visualTransformation = if (mask) {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    } else {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    },
                )
                if (helperText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        helperText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
