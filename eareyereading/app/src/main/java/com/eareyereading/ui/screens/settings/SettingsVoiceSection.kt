package com.eareyereading.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.OnSurfaceTertiary
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.SurfaceHover

/**
 * 设置页「语音」分区：TTS 引擎选择（离线 sherpa-onnx / 在线腾讯云）、
 * 腾讯云凭证与音色配置、离线模型下载/删除/切换与音色选择。
 *
 * 从 [SettingsScreen] 按 SRP 抽出：本分区只负责语音配置的展示与弹窗交互，
 * 状态由 [SettingsViewModel] 统一管理。弹窗组件见 [SettingsVoiceDialogs]。
 *
 * 内置 TTS（sherpa-onnx）下载/管理入口。
 * 国产手机系统 TTS 不可用时，这是唯一可用路径，必须在设置里暴露独立入口。
 */
@Composable
internal fun SettingsVoiceSection(
    uiState: SettingsUiState,
    onSetTtsEngineType: (String) -> Unit,
    onSetTencentCredentials: (id: String, key: String) -> Unit,
    onSetTencentVoiceId: (Int) -> Unit,
    onSetEmbeddedModel: (String) -> Unit,
    onSelectEmbeddedVoice: (Int) -> Unit,
    onDownloadEmbeddedTts: () -> Unit,
    onDeleteEmbeddedTts: () -> Unit,
) {
    var showTtsEngineDialog by remember { mutableStateOf(false) }
    var showTtsModelDialog by remember { mutableStateOf(false) }
    var showTtsVoiceDialog by remember { mutableStateOf(false) }
    var showTencentVoiceDialog by remember { mutableStateOf(false) }
    var showTencentCredDialog by remember { mutableStateOf(false) }

    SettingsSectionTitle("语音")
    SettingsListCard {
        // 引擎类型选择：离线 / 在线腾讯云 TTS
        SettingRowClickable(
            icon = Icons.Default.GraphicEq,
            iconBg = SurfaceHover,
            iconColor = Primary,
            title = "语音引擎",
            subtitle = if (uiState.ttsEngineType == "tencent") "在线腾讯云 TTS" else "离线 sherpa-onnx",
            onClick = { showTtsEngineDialog = true },
        )

        if (uiState.ttsEngineType == "tencent") {
            // 腾讯云模式：凭证配置 + 音色选择
            Divider(modifier = Modifier.padding(horizontal = 20.dp))
            SettingRowClickable(
                icon = Icons.Default.Key,
                iconBg = SurfaceHover,
                iconColor = Primary,
                title = "腾讯云凭证",
                subtitle = if (uiState.tencentSecretId.isNotEmpty()) "已配置（${uiState.tencentSecretId.take(8)}...）" else "未配置，点击设置",
                onClick = { showTencentCredDialog = true },
            )
            Divider(modifier = Modifier.padding(horizontal = 20.dp))
            SettingRowClickable(
                icon = Icons.Default.RecordVoiceOver,
                iconBg = SurfaceHover,
                iconColor = Primary,
                title = "腾讯云音色",
                subtitle = uiState.tencentVoiceDisplay,
                onClick = { showTencentVoiceDialog = true },
            )
        } else {
            SettingRowClickable(
                icon = Icons.Default.RecordVoiceOver,
                iconBg = SurfaceHover,
                iconColor = Primary,
                title = "语音模型",
                subtitle = uiState.embeddedModelName,
                onClick = { showTtsModelDialog = true },
            )

            if (uiState.embeddedSelectedModelIsKokoro) {
                Divider(modifier = Modifier.padding(horizontal = 20.dp))
                SettingRowClickable(
                    icon = Icons.Default.GraphicEq,
                    iconBg = SurfaceHover,
                    iconColor = Primary,
                    title = "音色",
                    subtitle = uiState.embeddedVoiceDisplay.ifEmpty { "默认音色" } +
                        if (uiState.embeddedReady) " · 点击切换并试听" else "",
                    onClick = { showTtsVoiceDialog = true },
                )
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                when {
                    uiState.embeddedDownloading || uiState.embeddedInitializing -> {
                        LinearProgressIndicator(
                            progress = uiState.embeddedDownloadProgress,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = uiState.embeddedDownloadStage.ifEmpty {
                                if (uiState.embeddedInitializing) "初始化中..."
                                else "下载中 ${(uiState.embeddedDownloadProgress * 100).toInt()}%"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    uiState.embeddedModelDownloaded && uiState.embeddedReady -> {
                        Text(
                            text = "✅ 已下载并启用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    uiState.embeddedModelDownloaded -> {
                        Text(
                            text = "已下载（未启用）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        Text(
                            text = "未下载（约 ${uiState.embeddedModelSizeText}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Divider(modifier = Modifier.padding(horizontal = 20.dp))

            if (uiState.embeddedDownloading || uiState.embeddedInitializing) {
                // 阶段细分（issue 1.1）：解压/初始化阶段不再显示
                // "正在下载…请保持网络连接"的自相矛盾文案
                val stage = uiState.embeddedDownloadStage
                val isExtracting = stage.contains("解压")
                SettingRow(
                    icon = Icons.Default.Downloading,
                    iconBg = SurfaceHover,
                    iconColor = OnSurfaceTertiary,
                    title = when {
                        uiState.embeddedInitializing -> "正在初始化..."
                        isExtracting -> "正在解压..."
                        else -> "正在下载..."
                    },
                    subtitle = when {
                        // 解压/初始化是纯本地操作，网络提示反而误导
                        isExtracting || uiState.embeddedInitializing ->
                            stage.ifEmpty { "无需联网，请稍候" }
                        else -> "请保持网络连接"
                    },
                )
            } else if (!uiState.embeddedModelDownloaded) {
                SettingRowClickable(
                    icon = Icons.Default.Download,
                    iconBg = SurfaceHover,
                    iconColor = Primary,
                    title = "下载内置语音模型",
                    subtitle = "完全离线，不依赖系统 TTS",
                    onClick = onDownloadEmbeddedTts,
                )
            } else {
                SettingRowClickable(
                    icon = Icons.Default.Delete,
                    iconBg = SurfaceHover,
                    iconColor = OnSurfaceTertiary,
                    title = "删除语音模型",
                    subtitle = "释放 ${uiState.embeddedModelSizeText} 空间",
                    onClick = onDeleteEmbeddedTts,
                )
            }
        } // end if engineType == "tencent" else (离线分区)
    }
    Spacer(modifier = Modifier.height(20.dp))

    // 引擎类型选择弹窗
    if (showTtsEngineDialog) {
        TtsEngineTypeDialog(
            currentType = uiState.ttsEngineType,
            onDismiss = { showTtsEngineDialog = false },
            onSelect = { type ->
                onSetTtsEngineType(type)
                showTtsEngineDialog = false
            },
        )
    }

    // 腾讯云凭证配置弹窗
    if (showTencentCredDialog) {
        TencentCredentialDialog(
            initialSecretId = uiState.tencentSecretId,
            initialSecretKey = uiState.tencentSecretKey,
            onDismiss = { showTencentCredDialog = false },
            onConfirm = { id, key ->
                onSetTencentCredentials(id, key)
                showTencentCredDialog = false
            },
        )
    }

    // 腾讯云音色选择弹窗
    if (showTencentVoiceDialog) {
        TencentVoiceDialog(
            selectedVoiceId = uiState.tencentVoiceId,
            onDismiss = { showTencentVoiceDialog = false },
            onSelect = { voiceId ->
                onSetTencentVoiceId(voiceId)
                showTencentVoiceDialog = false
            },
        )
    }

    // 模型选择弹窗：Piper 英文男声
    if (showTtsModelDialog) {
        EmbeddedModelDialog(
            models = uiState.embeddedModels,
            onDismiss = { showTtsModelDialog = false },
            onSelect = { modelId ->
                onSetEmbeddedModel(modelId)
                showTtsModelDialog = false
            },
        )
    }

    // 音色选择弹窗（Kokoro 已下线，此弹窗不再显示，保留代码兼容）
    // LazyColumn：103 行的 Column 会超出弹窗高度且无法滚动
    if (showTtsVoiceDialog) {
        EmbeddedVoiceDialog(
            currentVoiceDisplay = uiState.embeddedVoiceDisplay,
            onDismiss = { showTtsVoiceDialog = false },
            onSelect = { sid -> onSelectEmbeddedVoice(sid) },
        )
    }
}
