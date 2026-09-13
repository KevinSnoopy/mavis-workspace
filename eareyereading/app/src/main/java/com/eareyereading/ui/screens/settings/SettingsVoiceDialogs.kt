package com.eareyereading.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eareyereading.tts.KOKORO_VOICES
import com.eareyereading.ui.theme.Primary

/**
 * 语音分区弹窗组件：引擎选择、腾讯云凭证/音色、离线模型/音色选择弹窗。
 *
 * 从 [SettingsVoiceSection] 按 SRP 抽出：弹窗是独立的 UI 组件，
 * 与主分区的布局/状态编排解耦。
 */

/** 引擎类型选择弹窗（离线 / 在线腾讯云）。 */
@Composable
internal fun TtsEngineTypeDialog(
    currentType: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("语音引擎") },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect("embedded") }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = currentType == "embedded",
                        onClick = { onSelect("embedded") },
                    )
                    Column {
                        Text("离线 sherpa-onnx", style = MaterialTheme.typography.bodyMedium, fontWeight = if (currentType == "embedded") FontWeight.Bold else FontWeight.Normal)
                        Text("推荐 · 无需账号、无需联网，英文男声，首声 <1s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect("tencent") }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = currentType == "tencent",
                        onClick = { onSelect("tencent") },
                    )
                    Column {
                        Text("在线腾讯云 TTS", style = MaterialTheme.typography.bodyMedium, fontWeight = if (currentType == "tencent") FontWeight.Bold else FontWeight.Normal)
                        // 把「要自己申请密钥」这件事在选项层面就说清楚：
                        // 此前只写"需配置凭证"，用户点进来才发现要走腾讯云实名+访问管理
                        Text(
                            "进阶 · 101 音色，但需自备腾讯云账号与密钥",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 腾讯云凭证配置弹窗。
 *
 * @param onOpenGuide 非空时展示「怎么拿到这两个值」入口 —— 腾讯云的密钥页在
 *                    「访问管理」而不是语音合成控制台，用户十有八九找不到。
 */
@Composable
internal fun TencentCredentialDialog(
    initialSecretId: String,
    initialSecretKey: String,
    onDismiss: () -> Unit,
    onConfirm: (secretId: String, secretKey: String) -> Unit,
    onOpenGuide: (() -> Unit)? = null,
) {
    var secretId by remember { mutableStateOf(initialSecretId) }
    var secretKey by remember { mutableStateOf(initialSecretKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("腾讯云凭证") },
        text = {
            Column {
                Text(
                    text = "在腾讯云控制台 → 访问管理 → API 密钥管理获取。" +
                        "SecretKey 只在创建时显示一次，请当场保存。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onOpenGuide != null) {
                    TextButton(
                        onClick = onOpenGuide,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(
                            Icons.Default.HelpOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("不知道去哪拿？看获取步骤", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = secretId,
                    // 从控制台整段复制常带上换行与空格，就地清洗
                    onValueChange = { secretId = it.trim() },
                    label = { Text("SecretId") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = secretKey,
                    onValueChange = { secretKey = it.trim() },
                    label = { Text("SecretKey") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(secretId, secretKey) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 腾讯云音色选择弹窗（101 音色列表）。 */
@Composable
internal fun TencentVoiceDialog(
    selectedVoiceId: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择腾讯云音色") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                item {
                    Text(
                        text = "腾讯云 TTS 101 种音色，点击切换",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                com.eareyereading.tts.TENCENT_VOICES.forEach { v ->
                    item(key = "tencent_${v.id}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(v.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedVoiceId == v.id,
                                onClick = { onSelect(v.id) },
                            )
                            Text(
                                text = v.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selectedVoiceId == v.id) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 离线语音模型选择弹窗。 */
@Composable
internal fun EmbeddedModelDialog(
    models: List<EmbeddedModelUi>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("语音模型") },
        text = {
            Column {
                models.forEach { m ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(m.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = m.selected,
                            onClick = { onSelect(m.id) },
                        )
                        Column {
                            Text(
                                text = m.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (m.selected) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(
                                text = if (m.downloaded) "已下载" else "未下载",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** Kokoro 音色选择弹窗（按类别分组，Kokoro 已下线，保留兼容）。 */
@Composable
internal fun EmbeddedVoiceDialog(
    currentVoiceDisplay: String,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择音色") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                item {
                    Text(
                        text = "所有音色均支持中英混读，点击即切换并试听",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 按类别分组展示：美式女声 / 英式女声 / 中文女声 / 中文男声
                KOKORO_VOICES
                    .groupBy { it.category }
                    .forEach { (category, voices) ->
                        item(key = "header_$category") {
                            Text(
                                text = category,
                                style = MaterialTheme.typography.labelMedium,
                                color = Primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                            )
                        }
                        voices.forEach { v ->
                            item(key = v.name) {
                                val selected = currentVoiceDisplay == v.displayName
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(v.sid) }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = selected,
                                        onClick = { onSelect(v.sid) },
                                    )
                                    Text(
                                        text = v.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
