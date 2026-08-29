package com.eareyereading.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.data.local.dao.VocabularyDao
import com.eareyereading.domain.model.ReadingTheme
import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.domain.repository.VocabularyRepository
import com.eareyereading.ui.theme.*
import com.eareyereading.util.NotificationHelper
import com.eareyereading.tts.EmbeddedTtsEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class SettingsUiState(
    val fontSize: Int = 18,
    val rsvpSpeed: Int = 300,
    val theme: ReadingTheme = ReadingTheme.LIGHT,
    val streakDays: Int = 0,
    val totalWords: Int = 0,
    val darkMode: Boolean = false,
    val notifications: Boolean = true,
    val collinsHighlight: Boolean = true,
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val isClearing: Boolean = false,
    val snackbarMessage: String? = null,
    // 内置 TTS（sherpa-onnx）状态
    val embeddedModelName: String = "",
    val embeddedModelSizeText: String = "",
    val embeddedModelDownloaded: Boolean = false,
    val embeddedDownloading: Boolean = false,
    val embeddedDownloadProgress: Float = 0f,  // 0..1
    val embeddedReady: Boolean = false,         // 引擎已加载就绪
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val readingStatsDao: ReadingStatsDao,
    private val vocabularyDao: VocabularyDao,
    private val embeddedTts: EmbeddedTtsEngine,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val notificationHelper = NotificationHelper(context)

    init {
        // 加载阅读设置
        viewModelScope.launch {
            combine(
                settingsRepository.getFontSize(),
                settingsRepository.getRsvpSpeed(),
                settingsRepository.getTheme(),
                settingsRepository.getDarkMode(),
                settingsRepository.getNotifications(),
            ) { fontSize, speed, theme, darkMode, notifications ->
                _uiState.update {
                    it.copy(
                        fontSize = fontSize,
                        rsvpSpeed = speed,
                        theme = theme,
                        darkMode = darkMode,
                        notifications = notifications,
                    )
                }
            }.collect()
        }

        viewModelScope.launch {
            settingsRepository.getCollinsHighlight().collect { collinsHighlight ->
                _uiState.update { it.copy(collinsHighlight = collinsHighlight) }
            }
        }

        // 加载统计数据
        viewModelScope.launch {
            try {
                val allStats = readingStatsDao.getAllStats()
                val streak = calculateStreak(allStats)
                _uiState.update { it.copy(streakDays = streak) }
            } catch (_: Exception) { /* DB empty */ }
        }

        viewModelScope.launch {
            vocabularyRepository.getTotalCount().collect { count ->
                _uiState.update { it.copy(totalWords = count) }
            }
        }

        // 内置 TTS 状态：模型信息 + 下载进度 + 引擎状态
        viewModelScope.launch {
            embeddedTts.state.collect { state ->
                _uiState.update {
                    it.copy(
                        embeddedReady = state is EmbeddedTtsEngine.EngineState.READY,
                    )
                }
            }
        }
        viewModelScope.launch {
            embeddedTts.downloadProgress.collect { progress ->
                _uiState.update {
                    it.copy(
                        embeddedDownloading = progress != null,
                        embeddedDownloadProgress = progress ?: 0f,
                    )
                }
            }
        }
        refreshEmbeddedStatus()
    }

    private fun refreshEmbeddedStatus() {
        viewModelScope.launch {
            val model = embeddedTts.getCurrentModelInfo()
            val downloaded = embeddedTts.isModelDownloaded(model)
            _uiState.update {
                it.copy(
                    embeddedModelName = model.displayName,
                    embeddedModelSizeText = formatBytes(model.sizeBytes),
                    embeddedModelDownloaded = downloaded,
                )
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        return "%.0f MB".format(kb / 1024.0)
    }

    /** 下载内置 TTS 模型（带进度），下载完成后自动初始化。 */
    fun downloadEmbeddedTts() {
        if (_uiState.value.embeddedDownloading) return
        viewModelScope.launch {
            val model = embeddedTts.getCurrentModelInfo()
            _uiState.update { it.copy(embeddedDownloading = true, embeddedDownloadProgress = 0f) }
            val ok = embeddedTts.downloadModel(model) { progress ->
                _uiState.update { it.copy(embeddedDownloadProgress = progress) }
            }
            if (ok) {
                val initOk = embeddedTts.initialize(model)
                embeddedTts.cancelDownloadNotification()
                _uiState.update {
                    it.copy(
                        embeddedDownloading = false,
                        embeddedDownloadProgress = 0f,
                        embeddedModelDownloaded = true,
                        embeddedReady = initOk,
                        snackbarMessage = if (initOk) "内置语音已下载并启用" else "下载完成但初始化失败",
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        embeddedDownloading = false,
                        embeddedDownloadProgress = 0f,
                        snackbarMessage = "下载失败，请检查网络后重试（已下载部分下次会续传）",
                    )
                }
            }
            refreshEmbeddedStatus()
        }
    }

    /** 删除已下载的内置 TTS 模型（释放空间）。 */
    fun deleteEmbeddedTts() {
        viewModelScope.launch {
            embeddedTts.deleteModel()
            embeddedTts.release()
            refreshEmbeddedStatus()
            _uiState.update {
                it.copy(embeddedReady = false, snackbarMessage = "已删除内置语音模型")
            }
        }
    }

    private fun calculateStreak(stats: List<com.eareyereading.data.local.entity.ReadingStatsEntity>): Int {
        if (stats.isEmpty()) return 0
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = dateFormat.format(Date())
        val today = dateFormat.parse(todayStr) ?: return 0
        val dates = stats.mapNotNull { stat ->
            try { dateFormat.parse(stat.date) } catch (_: Exception) { null }
        }.distinct().sorted().reversed()
        var streak = 0
        var expected = today
        for (date in dates) {
            val dayDiff = ((expected.time - date.time) / 86_400_000).toInt()
            if (dayDiff <= 1) { streak++; expected = date } else break
        }
        return streak
    }

    fun setFontSize(size: Int) {
        viewModelScope.launch { settingsRepository.setFontSize(size) }
    }

    fun setRsvpSpeed(speed: Int) {
        viewModelScope.launch { settingsRepository.setRsvpSpeed(speed) }
    }

    fun setTheme(theme: ReadingTheme) {
        viewModelScope.launch { settingsRepository.setTheme(theme) }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDarkMode(enabled) }
    }

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNotifications(enabled)
            if (enabled) {
                notificationHelper.scheduleReviewReminder()
            } else {
                notificationHelper.cancelReminder()
            }
        }
    }

    fun setCollinsHighlight(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCollinsHighlight(enabled) }
    }

    fun exportData() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isExporting = true) }
                val file = File(context.cacheDir, "eareye_backup_${System.currentTimeMillis()}.json")
                val vocabList = vocabularyRepository.getAllVocabulary().first()
                val statsList = readingStatsDao.getAllStats()
                val json = buildString {
                    append("{")
                    append("\"version\":1,")
                    append("\"exportedAt\":${System.currentTimeMillis()},")
                    append("\"vocabulary\":[")
                    vocabList.forEachIndexed { i, v ->
                        append("{")
                        append("\"word\":\"${v.word.replace("\"","\\\"")}\",")
                        append("\"definition\":\"${(v.definition ?: "").replace("\"","\\\"")}\",")
                        append("\"level\":${v.level},")
                        append("\"isLearned\":${v.isLearned},")
                        append("\"note\":\"${(v.note ?: "").replace("\"","\\\"")}\",")
                        append("\"example\":\"${(v.example ?: "").replace("\"","\\\"")}\"")
                        append("}")
                        if (i < vocabList.size - 1) append(",")
                    }
                    append("],")
                    append("\"stats\":[")
                    statsList.forEachIndexed { i, s ->
                        append("{")
                        append("\"bookId\":${s.bookId},")
                        append("\"date\":\"${s.date}\",")
                        append("\"readingMinutes\":${s.readingMinutes},")
                        append("\"charsRead\":${s.charsRead}")
                        append("}")
                        if (i < statsList.size - 1) append(",")
                    }
                    append("]}")
                }
                file.writeText(json)
                _uiState.update { it.copy(isExporting = false, snackbarMessage = "已导出: ${file.name}") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isExporting = false, snackbarMessage = "导出失败: ${e.message}") }
            }
        }
    }

    suspend fun importFromFile(file: File) {
        try {
            _uiState.update { it.copy(isImporting = true) }
            val json = file.readText()

            // 可靠解析：按字段分别提取再按索引配对
            val words = Regex(""""word"\s*:\s*"([^"]+)"""").findAll(json).map { it.groupValues[1] }.toList()
            val defs = Regex(""""definition"\s*:\s*"([^"]*)"""").findAll(json).map { it.groupValues[1] }.toList()
            val levels = Regex(""""level"\s*:\s*(\d+)"""").findAll(json).map { it.groupValues[1].toIntOrNull() ?: 0 }.toList()
            val learned = Regex(""""isLearned"\s*:\s*(true|false)"""").findAll(json).map { it.groupValues[1] == "true" }.toList()
            val notes = Regex(""""note"\s*:\s*"([^"]*)"""").findAll(json).map { it.groupValues[1] }.toList()

            var imported = 0
            words.forEachIndexed { i, word ->
                vocabularyDao.insert(com.eareyereading.data.local.entity.VocabularyEntity(
                    word = word,
                    definition = defs.getOrElse(i) { "" },
                    level = levels.getOrElse(i) { 0 },
                    isLearned = learned.getOrElse(i) { false },
                    note = notes.getOrElse(i) { "" }.ifBlank { null },
                    bookId = 0,
                    bookTitle = "Imported",
                    context = null,
                    translation = null,
                    reviewCount = 0,
                    lastReviewTime = 0L,
                    dateAdded = System.currentTimeMillis(),
                ))
                imported++
            }
            _uiState.update { it.copy(isImporting = false, snackbarMessage = "已导入 $imported 条词汇") }
        } catch (e: Exception) {
            _uiState.update { it.copy(isImporting = false, snackbarMessage = "导入失败: ${e.message}") }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearing = true) }
            try {
                // 清理缓存目录（临时文件、导出文件等）
                context.cacheDir.walkTopDown().forEach { it.delete() }
                _uiState.update { it.copy(isClearing = false, snackbarMessage = "缓存已清除") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isClearing = false, snackbarMessage = "清除失败: ${e.message}") }
            }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            try {
                settingsRepository.clearAll()
                notificationHelper.cancelReminder()
                _uiState.update { it.copy(snackbarMessage = "已恢复默认设置") }
            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = "重置失败: ${e.message}") }
            }
        }
    }

    fun dismissSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}

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
            val inputStream = context.contentResolver.openInputStream(it)
            val tempFile = java.io.File(context.cacheDir, "import_temp.json")
            inputStream?.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
            scope.launch {
                viewModel.importFromFile(tempFile)
                tempFile.delete()
            }
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            scope.launch { snackbarHostState.showSnackbar(it) }
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
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
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

            // ── 阅读 ──────────────────────────────────
            item {
                SettingsSectionTitle("阅读")
            }
            item {
                SettingsListCard {
                    SettingRow(
                        icon = Icons.Default.FormatSize,
                        iconBg = PrimaryLight,
                        iconColor = Primary,
                        title = "默认字体大小",
                        subtitle = "${uiState.fontSize}sp",
                    )
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Slider(
                            value = uiState.fontSize.toFloat(),
                            onValueChange = { viewModel.setFontSize(it.toInt()) },
                            valueRange = 12f..32f,
                            steps = 19,
                            colors = SliderDefaults.colors(
                                thumbColor = Primary,
                                activeTrackColor = Primary,
                            ),
                        )
                    }

                    Divider(modifier = Modifier.padding(horizontal = 20.dp))

                    SettingRow(
                        icon = Icons.Default.VolumeUp,
                        iconBg = PrimaryLight,
                        iconColor = Info,
                        title = "RSVP 默认速度",
                        subtitle = "${uiState.rsvpSpeed} 字/分钟",
                    )
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Slider(
                            value = uiState.rsvpSpeed.toFloat(),
                            onValueChange = { viewModel.setRsvpSpeed(it.toInt()) },
                            valueRange = 100f..800f,
                            steps = 13,
                            colors = SliderDefaults.colors(
                                thumbColor = Info,
                                activeTrackColor = Info,
                            ),
                        )
                    }

                    Divider(modifier = Modifier.padding(horizontal = 20.dp))

                    SettingRow(
                        icon = Icons.Default.Visibility,
                        iconBg = SuccessBg,
                        iconColor = Accent,
                        title = "阅读主题",
                        subtitle = uiState.theme.displayName,
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.padding(horizontal = 0.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ReadingTheme.entries.forEach { theme ->
                                FilterChip(
                                    selected = theme == uiState.theme,
                                    onClick = { viewModel.setTheme(theme) },
                                    label = { Text(theme.displayName) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Primary.copy(alpha = 0.12f),
                                        selectedLabelColor = Primary,
                                    ),
                                )
                            }
                        }
                    }

                    Divider(modifier = Modifier.padding(horizontal = 20.dp))

                    SettingRowClickable(
                        icon = Icons.Default.LibraryBooks,
                        iconBg = PrimaryLight,
                        iconColor = Primary,
                        title = "词典管理",
                        subtitle = "下载分级词典（四级/六级/考研/托福/GRE/雅思）",
                        onClick = onNavigateToDictionaryManager,
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── 语音 ──────────────────────────────────
            // 内置 TTS（sherpa-onnx）下载/管理入口。
            // 国产手机系统 TTS 不可用时，这是唯一可用路径，必须在设置里暴露独立入口。
            item {
                SettingsSectionTitle("语音")
            }
            item {
                SettingsListCard {
                    SettingRow(
                        icon = Icons.Default.RecordVoiceOver,
                        iconBg = PrimaryLight,
                        iconColor = Primary,
                        title = "内置语音引擎",
                        subtitle = uiState.embeddedModelName,
                    )
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        when {
                            uiState.embeddedDownloading -> {
                                LinearProgressIndicator(
                                    progress = uiState.embeddedDownloadProgress,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "下载中 ${(uiState.embeddedDownloadProgress * 100).toInt()}%",
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

                    if (uiState.embeddedDownloading) {
                        SettingRow(
                            icon = Icons.Default.Downloading,
                            iconBg = SurfaceSecondary,
                            iconColor = OnSurfaceTertiary,
                            title = "正在下载...",
                            subtitle = "请保持网络连接",
                        )
                    } else if (!uiState.embeddedModelDownloaded) {
                        SettingRowClickable(
                            icon = Icons.Default.Download,
                            iconBg = PrimaryLight,
                            iconColor = Primary,
                            title = "下载内置语音模型",
                            subtitle = "完全离线，不依赖系统 TTS",
                            onClick = { viewModel.downloadEmbeddedTts() },
                        )
                    } else {
                        SettingRowClickable(
                            icon = Icons.Default.Delete,
                            iconBg = SurfaceSecondary,
                            iconColor = OnSurfaceTertiary,
                            title = "删除语音模型",
                            subtitle = "释放 ${uiState.embeddedModelSizeText} 空间",
                            onClick = { viewModel.deleteEmbeddedTts() },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── 学习 ──────────────────────────────────
            item {
                SettingsSectionTitle("学习")
            }
            item {
                SettingsListCard {
                    SettingRowToggle(
                        icon = Icons.Default.Notifications,
                        iconBg = SuccessBg,
                        iconColor = Accent,
                        title = "复习间隔提醒",
                        checked = uiState.notifications,
                        onCheckedChange = { enabled ->
                            if (enabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    return@SettingRowToggle
                                }
                            }
                            viewModel.setNotifications(enabled)
                        },
                    )
                    Divider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowToggle(
                        icon = Icons.Default.LocalFireDepartment,
                        iconBg = WarningBg,
                        iconColor = Warning,
                        title = "连胜提醒",
                        checked = uiState.notifications,
                        onCheckedChange = viewModel::setNotifications,
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── 外观 ──────────────────────────────────
            item {
                SettingsSectionTitle("外观")
            }
            item {
                SettingsListCard {
                    SettingRowToggle(
                        icon = Icons.Default.DarkMode,
                        iconBg = SurfaceSecondary,
                        iconColor = OnSurfaceTertiary,
                        title = "深色模式",
                        checked = uiState.darkMode,
                        onCheckedChange = viewModel::setDarkMode,
                    )
                    Divider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowToggle(
                        icon = Icons.Default.Highlight,
                        iconBg = WarningBg,
                        iconColor = Warning,
                        title = "高亮 Collins 等级",
                        checked = uiState.collinsHighlight,
                        onCheckedChange = viewModel::setCollinsHighlight,
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
                        iconBg = PrimaryLight,
                        iconColor = Primary,
                        title = "导出数据",
                        subtitle = if (uiState.isExporting) "导出中..." else "导出词汇和阅读数据",
                        onClick = { viewModel.exportData() },
                    )
                    Divider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowClickable(
                        icon = Icons.Default.Upload,
                        iconBg = PrimaryLight,
                        iconColor = Primary,
                        title = "导入数据",
                        subtitle = if (uiState.isImporting) "导入中..." else "从备份文件导入词汇",
                        onClick = { importFilePicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    )
                    Divider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowClickable(
                        icon = Icons.Default.Delete,
                        iconBg = SurfaceSecondary,
                        iconColor = OnSurfaceTertiary,
                        title = "清除缓存",
                        subtitle = if (uiState.isClearing) "清除中..." else {
                            val size = context.cacheDir.walkTopDown().sumOf { it.length() } / (1024.0 * 1024.0)
                            String.format("%.1f MB", size)
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
                        "听阅 EareyeReading · v1.9.0",
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

@Composable
private fun ProfileCard(
    streakDays: Int,
    totalWords: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar
            Surface(
                modifier = Modifier.size(60.dp),
                shape = RoundedCornerShape(30.dp),
                color = Primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "K",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "学习者",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "共学习 $totalWords 词",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = WarningBg,
                    ) {
                        Text(
                            "🔥 $streakDays 天连胜",
                            style = MaterialTheme.typography.labelSmall,
                            color = Warning,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            "📚 $totalWords 词",
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        style = SectionTitle,
        modifier = Modifier.padding(vertical = 10.dp),
    )
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun SettingsListCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    iconBg: Color,
    iconColor: Color,
    title: String,
    subtitle: String,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: @Composable ColumnScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(9.dp),
            color = iconBg,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = titleColor,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column { trailing() }
        }
    }
}

@Composable
private fun SettingRowToggle(
    icon: ImageVector,
    iconBg: Color,
    iconColor: Color,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(9.dp),
            color = iconBg,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = SurfaceHover,
            ),
        )
    }
}

@Composable
private fun SettingRowClickable(
    icon: ImageVector,
    iconBg: Color,
    iconColor: Color,
    title: String,
    subtitle: String,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(9.dp),
            color = iconBg,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = titleColor,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
