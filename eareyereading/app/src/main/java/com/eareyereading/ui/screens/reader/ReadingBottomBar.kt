package com.eareyereading.ui.screens.reader

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.domain.model.ReadingTheme
import com.eareyereading.ui.theme.*

/**
 * 阅读页底部栏：进度滑杆、播放控制、模式/设置入口。
 */
// ── 底部导航栏 ──────────────────────────────────
@Composable
fun ReadingBottomBar(
    uiState: ReaderUiState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Int) -> Unit,
    onFontDelta: (Int) -> Unit = {},
    onCycleTheme: () -> Unit = {},
    onToggleSerif: () -> Unit = {},
) {
    Surface(
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
        // Scaffold contentWindowInsets=0：底栏必须自己避让手势导航条，
        // 否则上一段/下一段按钮整行被系统手势区压住
        modifier = Modifier.navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            // 快捷设置行（微信读书式）：字号 ±、主题循环、衬线切换。
            // 沉浸阅读最高频的三个调整一步直达，不再进设置弹窗
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    ) {
                        // 44dp 触摸目标（原 36dp 低于最小可点标准，相邻易误触）
                        IconButton(onClick = { onFontDelta(-1) }, modifier = Modifier.size(44.dp)) {
                            Icon(
                                Icons.Default.TextDecrease,
                                contentDescription = "减小字号",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            "${uiState.fontSize}sp",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { onFontDelta(1) }, modifier = Modifier.size(44.dp)) {
                            Icon(
                                Icons.Default.TextIncrease,
                                contentDescription = "增大字号",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                IconButton(onClick = onCycleTheme) {
                    Icon(
                        when (uiState.theme) {
                            ReadingTheme.LIGHT -> Icons.Default.LightMode
                            ReadingTheme.SEPIA -> Icons.Default.Contrast
                            ReadingTheme.DARK -> Icons.Default.DarkMode
                        },
                        contentDescription = "切换阅读主题（当前：${uiState.theme.displayName}）",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggleSerif) {
                    Icon(
                        if (uiState.serifFont) Icons.Default.TextFields else Icons.Default.FormatSize,
                        contentDescription = if (uiState.serifFont) "衬线字体（开）" else "衬线字体（关）",
                        tint = if (uiState.serifFont) LocalReaderAccent.current else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 进度条：拖动期间只更新本地值，松手才提交。
            // 原实现逐像素调 goToParagraph：每像素都触发 stopAllPlayback +
            // saveProgress，挖空/模糊模式下还会逐像素重生成整段词序列
            var seekValue by remember { mutableStateOf(uiState.currentParagraphIndex.toFloat()) }
            // 拖动中标志：播放中程序化推进 currentIndex 时不再回写滑杆——
            // 旧实现用户拖到一半会被 LaunchedEffect 拉回当前段，拖动永远完不成
            var isSeeking by remember { mutableStateOf(false) }
            // 索引被程序化推进（播放/上下段/跳转）时同步滑杆位置
            LaunchedEffect(uiState.currentParagraphIndex) {
                if (!isSeeking) seekValue = uiState.currentParagraphIndex.toFloat()
            }
            Slider(
                value = seekValue.coerceIn(0f, (uiState.paragraphs.size - 1).coerceAtLeast(1).toFloat()),
                onValueChange = {
                    seekValue = it
                    isSeeking = true
                },
                onValueChangeFinished = {
                    isSeeking = false
                    onSeek(seekValue.toInt())
                },
                valueRange = 0f..(uiState.paragraphs.size - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = LocalReaderAccent.current,
                    activeTrackColor = LocalReaderAccent.current,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrev, enabled = uiState.currentParagraphIndex > 0) {
                    Icon(Icons.Default.NavigateBefore, "上一段")
                }
                // 拖动中实时显示目标位置：旧实现显示 currentParagraphIndex，
                // 长书拖动全程纹丝不动，松手前不知道会跳到哪
                Text(
                    text = if (isSeeking) {
                        "松开跳到第 ${(seekValue.toInt() + 1).coerceAtMost(uiState.paragraphs.size)} 段"
                    } else {
                        "${uiState.readingMode.displayName} · " +
                            "${(uiState.currentParagraphIndex + 1)}/${uiState.paragraphs.size}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSeeking) LocalReaderAccent.current else MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = onNext, enabled = uiState.currentParagraphIndex < uiState.paragraphs.size - 1) {
                    Icon(Icons.Default.NavigateNext, "下一段")
                }
            }
        }
    }
}
