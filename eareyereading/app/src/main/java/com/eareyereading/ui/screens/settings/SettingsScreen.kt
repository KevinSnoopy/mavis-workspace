package com.eareyereading.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.tts.KOKORO_VOICES
import com.eareyereading.ui.theme.*
import java.io.File
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页主屏：资料卡、TTS 模型管理、翻译/AI 配置、通知与数据管理分区。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
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
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
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
                SettingsSectionTitle("外观")
            }
            item {
                SettingsListCard {
                    SettingRowToggle(
                        icon = Icons.Default.Palette,
                        iconBg = SurfaceHover,
                        iconColor = Accent,
                        title = "动态取色",
                        subtitle = "跟随系统主题色",
                        checked = uiState.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── 阅读与词典 ──────────────────────────────
            // 字号/RSVP 速度/阅读主题/衬线字体等阅读设置已全部收敛到阅读页内
            //（顶栏"更多 → 设置"弹窗 + 底栏快捷设置），本页不再提供重复入口——
            // 两处入口并存时，设置页改的是"默认值"，阅读页改的是"当前值"，
            // 互相覆盖容易让用户困惑"为什么设置了不生效"
            item {
                SettingsSectionTitle("阅读与词典")
            }
            item {
                SettingsListCard {
                    SettingRow(
                        icon = Icons.Default.MenuBook,
                        iconBg = SurfaceHover,
                        iconColor = Primary,
                        title = "阅读偏好",
                        subtitle = "字号、阅读主题、衬线字体、翻译显示等",
                    ) {
                        Text(
                            text = "已移至阅读页内：进入任意书籍，点击正文唤出菜单 → 设置",
                            style = MaterialTheme.typography.bodySmall,
                            color = Primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    Divider(modifier = Modifier.padding(horizontal = 20.dp))

                    SettingRowClickable(
                        icon = Icons.Default.LibraryBooks,
                        iconBg = SurfaceHover,
                        iconColor = Primary,
                        title = "词典管理",
                        subtitle = "下载分级词典（四级/六级/考研/托福/GRE/雅思）",
                        onClick = onNavigateToDictionaryManager,
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── AI 翻译 ──────────────────────────────
            // LLM 通道：配置 API Key 后整段带上下文文学化翻译，
            // 未配置/请求失败时自动回退内置机翻链（ML Kit → 在线端点 → 词典）
            item {
                SettingsSectionTitle("AI 翻译")
            }
            item {
                var showProviderDialog by remember { mutableStateOf(false) }
                var showKeyDialog by remember { mutableStateOf(false) }
                var showModelDialog by remember { mutableStateOf(false) }
                var showUrlDialog by remember { mutableStateOf(false) }

                val providerName = when (uiState.llmBaseUrl.removeSuffix("/")) {
                    LLM_PRESET_GLM_BASE -> "智谱 GLM-4-Flash"
                    LLM_PRESET_DEEPSEEK_BASE -> "DeepSeek"
                    else -> "自定义"
                }

                SettingsListCard {
                    SettingRowToggle(
                        icon = Icons.Default.AutoAwesome,
                        iconBg = SurfaceHover,
                        iconColor = Accent,
                        title = "AI 智能翻译",
                        subtitle = if (uiState.llmApiKey.isNotBlank()) {
                            "整段上下文成文，译文自然流畅（需联网）"
                        } else {
                            "未配置 API Key，当前使用内置机翻"
                        },
                        checked = uiState.llmTranslateEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && uiState.llmApiKey.isBlank()) {
                                showKeyDialog = true
                                scope.launch {
                                    snackbarHostState.showSnackbar("先配置 API Key 再开启（GLM-4-Flash 免费）")
                                }
                            } else {
                                viewModel.setLlmTranslateEnabled(enabled)
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
                        subtitle = if (uiState.llmApiKey.isBlank()) "未配置"
                        else "已配置（···${uiState.llmApiKey.takeLast(4)}）",
                        onClick = { showKeyDialog = true },
                    )

                    Divider(modifier = Modifier.padding(horizontal = 20.dp))

                    SettingRowClickable(
                        icon = Icons.Default.TextFields,
                        iconBg = SurfaceHover,
                        iconColor = Primary,
                        title = "模型",
                        subtitle = uiState.llmModel,
                        onClick = { showModelDialog = true },
                    )

                    Divider(modifier = Modifier.padding(horizontal = 20.dp))

                    SettingRowClickable(
                        icon = Icons.Default.Link,
                        iconBg = SurfaceHover,
                        iconColor = OnSurfaceTertiary,
                        title = "接口地址",
                        subtitle = uiState.llmBaseUrl,
                        onClick = { showUrlDialog = true },
                    )

                    Divider(modifier = Modifier.padding(horizontal = 20.dp))

                    SettingRowClickable(
                        icon = Icons.Default.Science,
                        iconBg = SuccessBg,
                        iconColor = Accent,
                        title = if (uiState.llmTesting) "正在测试..." else "测试翻译",
                        subtitle = "送翻一句样例，验证 Key 与端点可用",
                        onClick = { viewModel.testLlmTranslation() },
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))

                if (showProviderDialog) {
                    AlertDialog(
                        onDismissRequest = { showProviderDialog = false },
                        title = { Text("选择服务商") },
                        text = {
                            Column {
                                LlmPresetOption(
                                    name = "智谱 GLM-4-Flash",
                                    note = "免费额度 · 国内直连",
                                    selected = providerName == "智谱 GLM-4-Flash",
                                ) {
                                    viewModel.applyLlmPreset(LLM_PRESET_GLM_BASE, LLM_PRESET_GLM_MODEL)
                                    showProviderDialog = false
                                }
                                LlmPresetOption(
                                    name = "DeepSeek",
                                    note = "低价高质量",
                                    selected = providerName == "DeepSeek",
                                ) {
                                    viewModel.applyLlmPreset(LLM_PRESET_DEEPSEEK_BASE, LLM_PRESET_DEEPSEEK_MODEL)
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
                        initialValue = uiState.llmApiKey,
                        label = "API Key",
                        helperText = "智谱开放平台 open.bigmodel.cn「API Keys」页创建；GLM-4-Flash 免费",
                        mask = true,
                        onConfirm = {
                            viewModel.setLlmApiKey(it)
                            showKeyDialog = false
                        },
                        onDismiss = { showKeyDialog = false },
                    )
                }
                if (showModelDialog) {
                    LlmTextFieldDialog(
                        title = "模型名称",
                        initialValue = uiState.llmModel,
                        label = "模型",
                        helperText = "如 glm-4-flash / deepseek-chat / glm-4-air",
                        onConfirm = {
                            viewModel.setLlmModel(it)
                            showModelDialog = false
                        },
                        onDismiss = { showModelDialog = false },
                    )
                }
                if (showUrlDialog) {
                    LlmTextFieldDialog(
                        title = "接口地址（Base URL）",
                        initialValue = uiState.llmBaseUrl,
                        label = "Base URL",
                        helperText = "OpenAI 兼容端点，实际请求 {地址}/chat/completions",
                        onConfirm = {
                            viewModel.setLlmBaseUrl(it)
                            showUrlDialog = false
                        },
                        onDismiss = { showUrlDialog = false },
                    )
                }
            }

            // ── 语音 ──────────────────────────────────
            // 内置 TTS（sherpa-onnx）下载/管理入口。
            // 国产手机系统 TTS 不可用时，这是唯一可用路径，必须在设置里暴露独立入口。
            item {
                SettingsSectionTitle("语音")
            }
            item {
                var showTtsModelDialog by remember { mutableStateOf(false) }
                var showTtsVoiceDialog by remember { mutableStateOf(false) }

                SettingsListCard {
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
                            onClick = { viewModel.downloadEmbeddedTts() },
                        )
                    } else {
                        SettingRowClickable(
                            icon = Icons.Default.Delete,
                            iconBg = SurfaceHover,
                            iconColor = OnSurfaceTertiary,
                            title = "删除语音模型",
                            subtitle = "释放 ${uiState.embeddedModelSizeText} 空间",
                            onClick = { viewModel.deleteEmbeddedTts() },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))

                // 模型选择弹窗：Piper 轻量英文 / Kokoro 中英多音色
                if (showTtsModelDialog) {
                    AlertDialog(
                        onDismissRequest = { showTtsModelDialog = false },
                        title = { Text("语音模型") },
                        text = {
                            Column {
                                uiState.embeddedModels.forEach { m ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.setEmbeddedModel(m.id)
                                                showTtsModelDialog = false
                                            }
                                            .padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = m.selected,
                                            onClick = {
                                                viewModel.setEmbeddedModel(m.id)
                                                showTtsModelDialog = false
                                            },
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
                            TextButton(onClick = { showTtsModelDialog = false }) { Text("关闭") }
                        },
                    )
                }

                // 音色选择弹窗（仅 Kokoro）：103 个音色按性别/口音分组，点击即试听。
                // LazyColumn：103 行的 Column 会超出弹窗高度且无法滚动
                if (showTtsVoiceDialog) {
                    AlertDialog(
                        onDismissRequest = { showTtsVoiceDialog = false },
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
                                                val selected = uiState.embeddedVoiceDisplay == v.displayName
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { viewModel.selectEmbeddedVoice(v.sid) }
                                                        .padding(vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    RadioButton(
                                                        selected = selected,
                                                        onClick = { viewModel.selectEmbeddedVoice(v.sid) },
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
                            TextButton(onClick = { showTtsVoiceDialog = false }) { Text("关闭") }
                        },
                    )
                }
            }

            // ── 通知偏好 ────────────────────────────────
            item {
                SettingsSectionTitle("通知偏好")
            }
            item {
                SettingsListCard {
                    SettingRowToggle(
                        icon = Icons.Default.Notifications,
                        iconBg = SuccessBg,
                        iconColor = Accent,
                        title = "复习提醒",
                        checked = uiState.notifications,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                val granted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                                        android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    // issue 5.3：区分"暂拒（可解释）"与"永久拒（don't ask again）"。
                                    // 永久拒后再 launch 也只会被系统路由到设置页，不如直接跳系统通知设置
                                    val activity = context as? android.app.Activity
                                    val rationale = activity != null &&
                                        androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                                            activity, android.Manifest.permission.POST_NOTIFICATIONS,
                                        )
                                    if (rationale) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("开启通知需要授予通知权限，用于每日复习提醒")
                                        }
                                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        openAppNotificationSettings(context)
                                        scope.launch {
                                            snackbarHostState.showSnackbar("已为你打开系统通知设置，请在设置中允许通知后重试")
                                        }
                                    }
                                    return@SettingRowToggle
                                }
                            }
                            viewModel.setNotifications(enabled)
                        },
                    )
                    Divider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowToggle(
                        icon = Icons.Default.Download,
                        iconBg = SurfaceHover,
                        iconColor = Primary,
                        title = "下载进度提醒",
                        checked = uiState.notificationDownloadProgress,
                        onCheckedChange = viewModel::setNotificationDownloadProgress,
                    )
                    Divider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowToggle(
                        icon = Icons.Default.CheckCircle,
                        iconBg = SuccessBg,
                        iconColor = Accent,
                        title = "下载完成提醒",
                        checked = uiState.notificationDownloadComplete,
                        onCheckedChange = viewModel::setNotificationDownloadComplete,
                    )
                    Divider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowClickable(
                        icon = Icons.Default.Settings,
                        iconBg = SurfaceHover,
                        iconColor = OnSurfaceTertiary,
                        title = "去系统通知设置",
                        subtitle = "管理应用的通知权限与分类",
                        onClick = { openAppNotificationSettings(context) },
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── 数据 ──────────────────────────────────
            item {
                SettingsSectionTitle("数据")
            }
            item {
                SettingsListCard {
                    SettingRowClickable(
                        icon = Icons.Default.Download,
                        iconBg = SurfaceHover,
                        iconColor = Primary,
                        title = "导出数据",
                        subtitle = if (uiState.isExporting) "导出中..." else "导出词汇和阅读数据",
                        onClick = { viewModel.exportData() },
                    )
                    Divider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowClickable(
                        icon = Icons.Default.Upload,
                        iconBg = SurfaceHover,
                        iconColor = Primary,
                        title = "导入数据",
                        subtitle = if (uiState.isImporting) "导入中..." else "从备份文件导入词汇",
                        onClick = { importFilePicker.launch(arrayOf("application/json", "text/plain")) },
                    )
                    Divider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowClickable(
                        icon = Icons.Default.Delete,
                        iconBg = SurfaceHover,
                        iconColor = OnSurfaceTertiary,
                        title = "清除缓存",
                        subtitle = if (uiState.isClearing) "清除中..." else {
                            String.format(java.util.Locale.getDefault(), "%.1f MB", uiState.cacheSizeMb)
                        },
                        onClick = { viewModel.clearCache() },
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── 危险区域 ────────────────────────────────
            item {
                SettingsSectionTitle("危险区域")
            }
            item {
                SettingsListCard {
                    SettingRowClickable(
                        icon = Icons.Default.Refresh,
                        iconBg = ErrorBg,
                        iconColor = Error,
                        title = "恢复默认设置",
                        subtitle = "清除所有设置（不影响数据）",
                        titleColor = Error,
                        onClick = { viewModel.resetToDefaults() },
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── 版本 ──────────────────────────────────
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "听阅 EareyeReading · v$versionName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

// ── 组件 ─────────────────────────────────────────────

/**
 * issue 5.3：跳转到系统通知设置页（POST_NOTIFICATIONS 被"don't ask again"永久拒后，
 * 应用内再弹权限框也只会被系统静默路由，唯一的恢复入口就是系统设置）。
 */
private fun openAppNotificationSettings(context: Context) {
    try {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.util.Log.w("SettingsScreen", "open notification settings failed", e)
    }
}
