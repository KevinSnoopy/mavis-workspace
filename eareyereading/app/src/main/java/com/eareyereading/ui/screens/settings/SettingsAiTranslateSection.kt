package com.eareyereading.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.Accent
import com.eareyereading.ui.theme.OnSurfaceTertiary
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.SuccessBg
import com.eareyereading.ui.theme.SurfaceHover

/**
 * 设置页「AI 翻译」分区：LLM 通道开关、服务商预设、API Key / 模型 / 接口地址
 * 配置与测试翻译入口。
 *
 * 从 [SettingsScreen] 按 SRP 抽出：本分区只负责 AI 翻译配置的展示与弹窗交互，
 * 状态由 [SettingsViewModel] 统一管理。
 *
 * LLM 通道：配置 API Key 后整段带上下文文学化翻译，
 * 未配置/请求失败时自动回退内置机翻链（ML Kit → 在线端点 → 词典）
 */
@Composable
internal fun SettingsAiTranslateSection(
    llmTranslateEnabled: Boolean,
    llmApiKey: String,
    llmBaseUrl: String,
    llmModel: String,
    llmTesting: Boolean,
    onSetLlmTranslateEnabled: (Boolean) -> Unit,
    onApplyLlmPreset: (baseUrl: String, model: String) -> Unit,
    onSetLlmApiKey: (String) -> Unit,
    onSetLlmModel: (String) -> Unit,
    onSetLlmBaseUrl: (String) -> Unit,
    onTestLlmTranslation: () -> Unit,
    onShowSnackbar: (String) -> Unit,
) {
    var showProviderDialog by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }

    val providerName = when (llmBaseUrl.removeSuffix("/")) {
        LLM_PRESET_GLM_BASE -> "智谱 GLM-4-Flash"
        LLM_PRESET_DEEPSEEK_BASE -> "DeepSeek"
        else -> "自定义"
    }

    SettingsSectionTitle("AI 翻译")
    SettingsListCard {
        SettingRowToggle(
            icon = Icons.Default.AutoAwesome,
            iconBg = SurfaceHover,
            iconColor = Accent,
            title = "AI 智能翻译",
            subtitle = if (llmApiKey.isNotBlank()) {
                "整段上下文成文，译文自然流畅（需联网）"
            } else {
                "未配置 API Key，当前使用内置机翻"
            },
            checked = llmTranslateEnabled,
            onCheckedChange = { enabled ->
                if (enabled && llmApiKey.isBlank()) {
                    showKeyDialog = true
                    onShowSnackbar("先配置 API Key 再开启（GLM-4-Flash 免费）")
                } else {
                    onSetLlmTranslateEnabled(enabled)
                }
            },
        )

        Divider(modifier = Modifier.padding(horizontal = 20.dp))

        SettingRowClickable(
            icon = Icons.Default.SmartToy,
            iconBg = SurfaceHover,
            iconColor = Primary,
            title = "服务商",
            subtitle = providerName,
            onClick = { showProviderDialog = true },
        )

        Divider(modifier = Modifier.padding(horizontal = 20.dp))

        SettingRowClickable(
            icon = Icons.Default.Key,
            iconBg = SurfaceHover,
            iconColor = Primary,
            title = "API Key",
            subtitle = if (llmApiKey.isBlank()) "未配置"
            else "已配置（···${llmApiKey.takeLast(4)}）",
            onClick = { showKeyDialog = true },
        )

        Divider(modifier = Modifier.padding(horizontal = 20.dp))

        SettingRowClickable(
            icon = Icons.Default.TextFields,
            iconBg = SurfaceHover,
            iconColor = Primary,
            title = "模型",
            subtitle = llmModel,
            onClick = { showModelDialog = true },
        )

        Divider(modifier = Modifier.padding(horizontal = 20.dp))

        SettingRowClickable(
            icon = Icons.Default.Link,
            iconBg = SurfaceHover,
            iconColor = OnSurfaceTertiary,
            title = "接口地址",
            subtitle = llmBaseUrl,
            onClick = { showUrlDialog = true },
        )

        Divider(modifier = Modifier.padding(horizontal = 20.dp))

        SettingRowClickable(
            icon = Icons.Default.Science,
            iconBg = SuccessBg,
            iconColor = Accent,
            title = if (llmTesting) "正在测试..." else "测试翻译",
            subtitle = "送翻一句样例，验证 Key 与端点可用",
            onClick = onTestLlmTranslation,
        )
    }
    Spacer(modifier = Modifier.height(20.dp))

    if (showProviderDialog) {
        AlertDialog(
            onDismissRequest = { showProviderDialog = false },
            title = { Text("选择服务商") },
            text = {
                androidx.compose.foundation.layout.Column {
                    LlmPresetOption(
                        name = "智谱 GLM-4-Flash",
                        note = "免费额度 · 国内直连",
                        selected = providerName == "智谱 GLM-4-Flash",
                    ) {
                        onApplyLlmPreset(LLM_PRESET_GLM_BASE, LLM_PRESET_GLM_MODEL)
                        showProviderDialog = false
                    }
                    LlmPresetOption(
                        name = "DeepSeek",
                        note = "低价高质量",
                        selected = providerName == "DeepSeek",
                    ) {
                        onApplyLlmPreset(LLM_PRESET_DEEPSEEK_BASE, LLM_PRESET_DEEPSEEK_MODEL)
                        showProviderDialog = false
                    }
                    LlmPresetOption(
                        name = "自定义",
                        note = "任意 OpenAI 兼容端点，手动填地址与模型",
                        selected = providerName == "自定义",
                    ) { showProviderDialog = false }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProviderDialog = false }) { Text("关闭") }
            },
        )
    }
    if (showKeyDialog) {
        LlmTextFieldDialog(
            title = "API Key",
            initialValue = llmApiKey,
            label = "API Key",
            helperText = "智谱开放平台 open.bigmodel.cn「API Keys」页创建；GLM-4-Flash 免费",
            mask = true,
            onConfirm = {
                onSetLlmApiKey(it)
                showKeyDialog = false
            },
            onDismiss = { showKeyDialog = false },
        )
    }
    if (showModelDialog) {
        LlmTextFieldDialog(
            title = "模型名称",
            initialValue = llmModel,
            label = "模型",
            helperText = "如 glm-4-flash / deepseek-chat / glm-4-air",
            onConfirm = {
                onSetLlmModel(it)
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false },
        )
    }
    if (showUrlDialog) {
        LlmTextFieldDialog(
            title = "接口地址（Base URL）",
            initialValue = llmBaseUrl,
            label = "Base URL",
            helperText = "OpenAI 兼容端点，实际请求 {地址}/chat/completions",
            onConfirm = {
                onSetLlmBaseUrl(it)
                showUrlDialog = false
            },
            onDismiss = { showUrlDialog = false },
        )
    }
}
