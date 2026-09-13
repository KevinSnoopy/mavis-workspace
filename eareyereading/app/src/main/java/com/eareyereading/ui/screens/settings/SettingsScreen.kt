package com.eareyereading.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.ui.components.AppTopBar
import kotlinx.coroutines.launch

/**
 * 设置页主屏：资料卡、TTS 模型管理、翻译/AI 配置、通知与数据管理分区。
 *
 * 重构后本文件只负责顶层编排（Scaffold + LazyColumn + 分区委托），
 * 各设置分区按 SRP 拆到同包 internal 文件：
 * - [SettingsAppearanceSection]：外观
 * - [SettingsReadingSection]：阅读与词典
 * - [SettingsAiTranslateSection]：AI 翻译
 * - [SettingsVoiceSection]：语音
 * - [SettingsNotificationSection]：通知偏好
 * - [SettingsDataSection] / [SettingsDangerZoneSection] / [SettingsVersionFooter]：数据/危险区域/版本
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToDictionaryManager: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 版本号从包信息动态取：buildConfig 未开启，写死字符串会随发布漂移。
    // 此前页脚固定 "v1.9.0"，用户看到的版本与实际安装包不一致
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "dev"
        } catch (_: Exception) {
            "dev"
        }
    }

    // Android 13+ 通知权限申请
    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.setNotifications(true)
        } else {
            scope.launch { snackbarHostState.showSnackbar("通知权限被拒绝，无法发送提醒") }
        }
    }

    // 文件选择器：用于导入数据
    val importFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        uri?.let {
            // openInputStream 可能在选完文件后被拒（文档删除/权限失效），
            // 不能裸奔在回调里；拷贝放 IO 线程，大备份文件不冻 UI
            scope.launch {
                val tempFile = java.io.File(context.cacheDir, "import_temp.json")
                try {
                    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val inputStream = context.contentResolver.openInputStream(it)
                            ?: return@withContext false
                        inputStream.use { input ->
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        true
                    }
                    if (ok) {
                        viewModel.importFromFile(tempFile)
                    } else {
                        viewModel.showSnackbarMessage("无法读取所选文件")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e

                } catch (e: Exception) {
                    viewModel.showSnackbarMessage("导入失败: ${e.message}")
                } finally {
                    tempFile.delete()
                }
            }
        }
    }

    // Snackbar 直接挂起等待展示结束再清状态：原先 launch+立即 dismiss
    // 会让两条消息并发抢同一个 SnackbarHostState，后到的消息被吞
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSnackbar()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "设置")
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // ── Profile Card ──────────────────────────────
            item {
                Spacer(modifier = Modifier.height(8.dp))
                ProfileCard(
                    streakDays = uiState.streakDays,
                    totalWords = uiState.totalWords,
                )
                Spacer(modifier = Modifier.height(28.dp))
            }

            // ── 外观 ──────────────────────────────────
            item {
                SettingsAppearanceSection(
                    dynamicColor = uiState.dynamicColor,
                    onDynamicColorChange = viewModel::setDynamicColor,
                )
            }

            // ── 阅读与词典 ──────────────────────────────
            item {
                SettingsReadingSection(
                    onNavigateToDictionaryManager = onNavigateToDictionaryManager,
                )
            }

            // ── AI 翻译 ──────────────────────────────
            item {
                SettingsAiTranslateSection(
                    llmTranslateEnabled = uiState.llmTranslateEnabled,
                    llmApiKey = uiState.llmApiKey,
                    llmBaseUrl = uiState.llmBaseUrl,
                    llmModel = uiState.llmModel,
                    llmTesting = uiState.llmTesting,
                    onSetLlmTranslateEnabled = viewModel::setLlmTranslateEnabled,
                    onApplyLlmPreset = viewModel::applyLlmPreset,
                    onSetLlmApiKey = viewModel::setLlmApiKey,
                    onSetLlmModel = viewModel::setLlmModel,
                    onSetLlmBaseUrl = viewModel::setLlmBaseUrl,
                    onTestLlmTranslation = viewModel::testLlmTranslation,
                    onShowSnackbar = { msg ->
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    },
                )
            }

            // ── 语音 ──────────────────────────────────
            item {
                SettingsVoiceSection(
                    uiState = uiState,
                    onSetTtsEngineType = viewModel::setTtsEngineType,
                    onSetTencentCredentials = viewModel::setTencentCredentials,
                    onSetTencentVoiceId = viewModel::setTencentVoiceId,
                    onSetEmbeddedModel = viewModel::setEmbeddedModel,
                    onSelectEmbeddedVoice = viewModel::selectEmbeddedVoice,
                    onDownloadEmbeddedTts = viewModel::downloadEmbeddedTts,
                    onDeleteEmbeddedTts = viewModel::deleteEmbeddedTts,
                )
            }

            // ── 通知偏好 ────────────────────────────────
            item {
                SettingsNotificationSection(
                    notifications = uiState.notifications,
                    notificationDownloadProgress = uiState.notificationDownloadProgress,
                    notificationDownloadComplete = uiState.notificationDownloadComplete,
                    context = context,
                    snackbarHostState = snackbarHostState,
                    notificationPermissionLauncher = notificationPermissionLauncher,
                    onSetNotifications = viewModel::setNotifications,
                    onSetNotificationDownloadProgress = viewModel::setNotificationDownloadProgress,
                    onSetNotificationDownloadComplete = viewModel::setNotificationDownloadComplete,
                )
            }

            // ── 数据 ──────────────────────────────────
            item {
                SettingsDataSection(
                    isExporting = uiState.isExporting,
                    isImporting = uiState.isImporting,
                    isClearing = uiState.isClearing,
                    cacheSizeMb = uiState.cacheSizeMb,
                    onExport = viewModel::exportData,
                    onImport = { importFilePicker.launch(arrayOf("application/json", "text/plain")) },
                    onClearCache = viewModel::clearCache,
                )
            }

            // ── 危险区域 ────────────────────────────────
            item {
                SettingsDangerZoneSection(
                    onResetToDefaults = viewModel::resetToDefaults,
                )
            }

            // ── 版本 ──────────────────────────────────
            item {
                SettingsVersionFooter(versionName)
            }
        }
    }
}
