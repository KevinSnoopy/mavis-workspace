package com.eareyereading.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.eareyereading.ui.theme.Accent
import com.eareyereading.ui.theme.Info
import com.eareyereading.ui.theme.OnSurfaceTertiary
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.Success
import com.eareyereading.ui.theme.SuccessBg
import com.eareyereading.ui.theme.SurfaceHover

/**
 * 设置页「AI 翻译」分区。
 *
 * ── 设计基调：零配置先行 ──
 * 内置翻译链（ML Kit 端侧 → 在线端点 → 本地词典）本来就**不需要任何账号**，
 * 开箱即用。但此前的分区把「服务商 / API Key / 模型 / 接口地址」四个技术字段
 * 平铺在开关下面，用户的直觉是"这功能要申请 Key 才能用"，于是直接放弃 ——
 * 一个可选增强项被摆成了必填门槛。
 *
 * 现在按三层重排：
 *   1. 顶部一句话说清「不配也能用」，把心理门槛先拆掉；
 *   2. 只有「AI 增强翻译」开关 + 「如何获取免费 API Key」两行常驻；
 *   3. 模型 / 接口地址仅在服务商选「自定义」时才出现 —— 用预设的用户
 *      根本不需要知道有这两个字段。
 *
 * Key 的获取路径由 [KeyGuideSheet] 承担，不再只留一行 helper 文字。
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
    var showGuideSheet by remember { mutableStateOf(false) }

    val providerName = when (llmBaseUrl.removeSuffix("/")) {
        LLM_PRESET_GLM_BASE -> "智谱 GLM-4-Flash"
        LLM_PRESET_DEEPSEEK_BASE -> "DeepSeek"
        else -> "自定义"
    }
    // 只有「自定义」端点才需要手填模型与地址；走预设时这两个值由预设带出，
    // 展示出来只会增加认知负担（也容易被误改坏）
    val isCustomProvider = providerName == "自定义"

    SettingsSectionTitle("AI 翻译")
    SettingsListCard {
        LlmDefaultPathNote()

        Divider(modifier = Modifier.padding(horizontal = 20.dp))

        SettingRowToggle(
            icon = Icons.Default.AutoAwesome,
            iconBg = SurfaceHover,
            iconColor = Accent,
            title = "AI 增强翻译",
            subtitle = if (llmApiKey.isNotBlank()) {
                "整段上下文成文，译文自然流畅（需联网）"
            } else {
                "可选增强 · 需自备 API Key。不开也照样能全文翻译"
            },
            checked = llmTranslateEnabled,
            onCheckedChange = { enabled ->
                if (enabled && llmApiKey.isBlank()) {
                    // 打开开关但还没有 Key：直接把他送去取 Key 的路径，
                    // 而不是弹一个空白输入框让人自己想办法
                    showGuideSheet = true
                    onShowSnackbar("先拿到并粘贴 API Key，再打开这个开关")
                } else {
                    onSetLlmTranslateEnabled(enabled)
                }
            },
        )

        Divider(modifier = Modifier.padding(horizontal = 20.dp))

        SettingRowClickable(
            icon = Icons.Default.HelpOutline,
            iconBg = SuccessBg,
            iconColor = Info,
            title = "如何获取免费的 API Key",
            subtitle = "智谱 glm-4-flash 免费 · 分 4 步，约 2 分钟",
            onClick = { showGuideSheet = true },
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
            subtitle = if (llmApiKey.isBlank()) "未配置 · 点这里粘贴" else "已配置（···${llmApiKey.takeLast(4)}）",
            onClick = { showKeyDialog = true },
        )

        if (isCustomProvider) {
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
        }

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

    if (showGuideSheet) {
        KeyGuideSheet(
            guide = GlmKeyGuide,
            onDismiss = { showGuideSheet = false },
            onDone = { showKeyDialog = true },
        )
    }
    if (showProviderDialog) {
        AlertDialog(
            onDismissRequest = { showProviderDialog = false },
            title = { Text("选择服务商") },
            text = {
                Column {
                    LlmPresetOption(
                        name = "智谱 GLM-4-Flash",
                        note = "免费额度 · 国内直连 · 推荐",
                        selected = providerName == "智谱 GLM-4-Flash",
                    ) {
                        onApplyLlmPreset(LLM_PRESET_GLM_BASE, LLM_PRESET_GLM_MODEL)
                        showProviderDialog = false
                    }
                    LlmPresetOption(
                        name = "DeepSeek",
                        note = "按量付费，质量更高",
                        selected = providerName == "DeepSeek",
                    ) {
                        onApplyLlmPreset(LLM_PRESET_DEEPSEEK_BASE, LLM_PRESET_DEEPSEEK_MODEL)
                        showProviderDialog = false
                    }
                    LlmPresetOption(
                        name = "自定义",
                        note = "任意 OpenAI 兼容端点，需手动填地址与模型",
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
            helperText = "直接粘贴即可，前后空格会自动去掉。GLM-4-Flash 目前免费。",
            mask = true,
            // 先关弹窗再开引导：ModalBottomSheet 与 AlertDialog 同时存在时，
            // 抽屉会被压在对话框后面，用户只看到四周漏出一点内容
            onOpenGuide = {
                showKeyDialog = false
                showGuideSheet = true
            },
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

/**
 * 分区顶部的「默认可用」说明。
 *
 * 不加这一段，用户在开关下面看到「服务商 / API Key / 模型 / 接口地址」四行，
 * 会直接判定这是个需要申请密钥才能用的功能。
 */
@Composable
private fun LlmDefaultPathNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.VerifiedUser,
            contentDescription = null,
            tint = Success,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                "不配置也能全文翻译",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Success,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "内置离线翻译已默认可用，全程不需要账号，也不消耗流量。" +
                    "下面的 AI 翻译是可选增强：把机翻的逐句拼接升级成整段成文。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
