package com.eareyereading.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.ReadingTheme
import com.eareyereading.domain.repository.SettingsRepository
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
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("阅读设置", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 字体大小
            item {
                SettingCard(icon = Icons.Default.FormatSize, title = "字体大小", subtitle = "${uiState.fontSize}sp") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Slider(
                            value = uiState.fontSize.toFloat(),
                            onValueChange = { viewModel.setFontSize(it.toInt()) },
                            valueRange = 12f..32f,
                            steps = 19,
                        )
                    }
                }
            }

            // RSVP 速度
            item {
                SettingCard(icon = Icons.AutoMirrored.Filled.VolumeUp, title = "RSVP 速度", subtitle = "${uiState.rsvpSpeed} 字/分钟") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Slider(
                            value = uiState.rsvpSpeed.toFloat(),
                            onValueChange = { viewModel.setRsvpSpeed(it.toInt()) },
                            valueRange = 100f..800f,
                            steps = 13,
                        )
                    }
                }
            }

            // 阅读主题
            item {
                SettingCard(icon = Icons.Default.Palette, title = "阅读主题", subtitle = uiState.theme.displayName) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ReadingTheme.entries.forEach { theme ->
                            FilterChip(
                                selected = theme == uiState.theme,
                                onClick = { viewModel.setTheme(theme) },
                                label = { Text(theme.displayName) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }

            // 关于
            item {
                Text("关于", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                ListItem(
                    headlineContent = { Text("听阅 EareyeReading") },
                    supportingContent = { Text("版本 1.0.0") },
                    leadingContent = {
                        Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                    },
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("功能特色") },
                    supportingContent = {
                        Text("全文翻译 · 词频统计 · 仿生阅读 · 快速阅读\n挖空练习 · 模糊听读 · 真人朗读 · 生词本")
                    },
                    leadingContent = {
                        Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.secondary)
                    },
                )
            }
        }
    }
}

@Composable
fun SettingCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}
