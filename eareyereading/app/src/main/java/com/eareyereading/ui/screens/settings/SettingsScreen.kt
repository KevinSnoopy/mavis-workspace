package com.eareyereading.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 阅读设置
            item {
                Text("阅读", style = SectionTitle, modifier = Modifier.padding(vertical = 12.dp))
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
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
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
                        iconBg = Color(0xFFF5F0FF),
                        iconColor = Color(0xFF5856D6),
                        title = "RSVP 默认速度",
                        subtitle = "${uiState.rsvpSpeed} 字/分钟",
                    )
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Slider(
                            value = uiState.rsvpSpeed.toFloat(),
                            onValueChange = { viewModel.setRsvpSpeed(it.toInt()) },
                            valueRange = 100f..800f,
                            steps = 13,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF5856D6),
                                activeTrackColor = Color(0xFF5856D6),
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
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }

            // 关于
            item {
                Text("关于", style = SectionTitle, modifier = Modifier.padding(vertical = 12.dp))
            }

            item {
                SettingsListCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.MenuBook,
                            null,
                            tint = Primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "听阅 EareyeReading",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "版本 1.9.0",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Star,
                            null,
                            tint = Color(0xFFFF6B35),
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "功能特色",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "全文翻译 · 词频统计 · 仿生阅读 · 快速阅读\n挖空练习 · 模糊听读 · 真人朗读 · 生词本",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(40.dp)) }
        }
    }
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
    trailing: (@Composable () -> Unit)? = null,
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
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            trailing()
        }
    }
}
