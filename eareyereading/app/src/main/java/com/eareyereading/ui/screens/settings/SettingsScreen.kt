package com.eareyereading.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import com.eareyereading.domain.model.ReadingTheme
import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.getFontSize(),
                settingsRepository.getRsvpSpeed(),
                settingsRepository.getTheme(),
            ) { fontSize, speed, theme ->
                SettingsUiState(fontSize = fontSize, rsvpSpeed = speed, theme = theme)
            }.collect { state ->
                _uiState.value = state
            }
        }
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
        _uiState.value = _uiState.value.copy(darkMode = enabled)
    }

    fun setNotifications(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(notifications = enabled)
    }

    fun setCollinsHighlight(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(collinsHighlight = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
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

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))

                    SettingRow(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
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

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))

                    SettingRow(
                        icon = Icons.Default.Visibility,
                        iconBg = SuccessBg,
                        iconColor = Accent,
                        title = "阅读主题",
                        subtitle = uiState.theme.displayName,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                        onCheckedChange = viewModel::setNotifications,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRowToggle(
                        icon = Icons.Default.LocalFireDepartment,
                        iconBg = WarningBg,
                        iconColor = Warning,
                        title = "连胜提醒",
                        checked = true,
                        onCheckedChange = {},
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
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
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
                    SettingRow(
                        icon = Icons.Default.Download,
                        iconBg = PrimaryLight,
                        iconColor = Primary,
                        title = "导出数据",
                        subtitle = "",
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRow(
                        icon = Icons.Default.Upload,
                        iconBg = PrimaryLight,
                        iconColor = Primary,
                        title = "导入数据",
                        subtitle = "",
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                    SettingRow(
                        icon = Icons.Default.Delete,
                        iconBg = SurfaceSecondary,
                        iconColor = OnSurfaceTertiary,
                        title = "清除缓存",
                        subtitle = "23.4 MB",
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
                    SettingRow(
                        icon = Icons.Default.Refresh,
                        iconBg = ErrorBg,
                        iconColor = Error,
                        title = "恢复默认设置",
                        subtitle = "",
                        titleColor = Error,
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
                    "Kevin",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "已学习 128 天",
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
                            "📚 12847 词",
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
