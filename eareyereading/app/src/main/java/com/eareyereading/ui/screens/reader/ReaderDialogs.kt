package com.eareyereading.ui.screens.reader

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eareyereading.domain.model.ReadingMode
import com.eareyereading.ui.theme.*
import com.eareyereading.util.BookImages
import com.eareyereading.util.CollinsClassifier.WordLevel

/**
 * 阅读页弹窗与抽屉：TTS 引导、模式选择、单词详情、章节导航、选句翻译。
 */
// ── TTS 引擎引导弹窗 ───────────────────────────
@Composable
internal fun TtsInstallDialog(
    prompt: com.eareyereading.ui.screens.reader.TtsInstallPrompt,
    downloadProgress: Float? = null,
    downloadStage: String? = null,
    onAction: (com.eareyereading.ui.screens.reader.TtsInstallAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    @Suppress("UNUSED_VARIABLE") val unusedCtx = context  // 旧版本用于 TtsEngineHelper 调用，移除后保留位

    // 2026-08-30: 系统 TTS 完全下线，对话框只剩"下载内置模型"一种 CTA。
    val downloadButton: @Composable () -> Unit = {
        val progress = downloadProgress
        if (progress != null) {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.Text(
                    // 阶段缺失时的 fallback 不再提"请保持网络"：
                    // 该文案只在阶段切换瞬间短暂出现，解压/初始化阶段显示
                    // 网络提示会自相矛盾（issue 1.1）
                    text = downloadStage
                        ?: "正在准备内置 TTS 模型 ${(progress * 100).toInt()}%…",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = androidx.compose.ui.Modifier.padding(top = 4.dp),
                )
            }
        } else {
            androidx.compose.material3.Button(
                onClick = {
                    onAction(com.eareyereading.ui.screens.reader.TtsInstallAction.DownloadEmbeddedTts)
                },
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.Text(
                    text = "🚀 下载内置 TTS（${prompt.embeddedModelDisplayName}，${prompt.embeddedModelSizeText}）"
                )
            }
        }
    }

    val title = if (prompt.embeddedModelDownloaded) "启用内置 TTS" else "下载内置 TTS"

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.material3.Text(
                text = title,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        },
        text = {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.Text(
                    text = if (prompt.embeddedModelDownloaded) {
                        "内置 TTS 模型已下载。点下方按钮启用。"
                    } else {
                        "内置 TTS 完全离线、不依赖系统服务，能保证英文朗读稳定性。" +
                            "模型下载约 ${prompt.embeddedModelSizeText}。"
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Column {
                if (prompt.embeddedModelDownloaded) {
                    androidx.compose.material3.Button(
                        onClick = {
                            onAction(com.eareyereading.ui.screens.reader.TtsInstallAction.RetryWithEngine("__EMBEDDED__"))
                        },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Text("✅ 启用内置 TTS")
                    }
                } else {
                    downloadButton()
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("取消")
            }
        },
    )
}

// ── 模式选择（底部抽屉：M3 规范的拇指可达区弹层） ────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelectorDialog(
    currentMode: ReadingMode,
    onSelect: (ReadingMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            "选择阅读模式",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.heightIn(max = 480.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(ReadingMode.entries) { _, mode ->
                ListItem(
                    headlineContent = { Text(mode.displayName) },
                    supportingContent = { Text(getModeDescription(mode)) },
                    leadingContent = {
                        RadioButton(
                            selected = mode == currentMode,
                            onClick = { onSelect(mode) },
                        )
                    },
                    modifier = Modifier.clickable { onSelect(mode) },
                )
            }
        }
    }
}

private fun getModeDescription(mode: ReadingMode): String = when (mode) {
    ReadingMode.NORMAL -> "普通阅读，点击单词查释义"
    ReadingMode.RSVP -> "仿生阅读，快速捕捉关键词"
    ReadingMode.SPEED -> "逐句闪现，训练阅读速度"
    ReadingMode.CLOZE -> "挖空练习，隐藏单词填空"
    ReadingMode.FUZZY -> "模糊听读，训练听力复述"
    ReadingMode.DICTATION -> "听写练习，随机隐藏单词听写"
    ReadingMode.SPLIT -> "分栏对照，左原文右译文"
    ReadingMode.BACK_TRANSLATION -> "中译英练习，看译文回译英文"
    ReadingMode.POS_ANALYSIS -> "成分分析，词性着色标注"
}

// ── 单词详情（底部抽屉：点词查义高频操作，拇指可达） ────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordDetailDialog(
    word: String,
    definition: String?,
    wordLevel: WordLevel = WordLevel.UNKNOWN,
    onAddToVocabulary: () -> Unit,
    onSpeak: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = onSpeak) {
                    Icon(Icons.Default.PlayCircleFilled, "播放发音", tint = LocalReaderAccent.current)
                }
                if (wordLevel != WordLevel.UNKNOWN) {
                    val badgeColor = when (wordLevel) {
                        WordLevel.CORE -> WordLevelCore
                        WordLevel.INTERMEDIATE -> WordLevelIntmd
                        WordLevel.UPPER_INTERMEDIATE -> WordLevelUpper
                        WordLevel.ADVANCED -> WordLevelAdv
                        WordLevel.RARE -> WordLevelRare
                        WordLevel.UNKNOWN -> Color.Gray
                    }
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                wordLevel.displayName,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = badgeColor.copy(alpha = 0.15f),
                            labelColor = badgeColor,
                        ),
                        border = null,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 超长释义限高 + 可滚：旧实现无约束，长释义把 BottomSheet 顶满屏
            Text(
                text = definition ?: "未找到释义",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            )
            if (wordLevel != WordLevel.UNKNOWN) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = wordLevel.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    onAddToVocabulary()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("加入生词本")
            }
        }
    }
}

// ── 章节目录导航（底部抽屉：长列表在抽屉里更接近拇指） ────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterNavDialog(
    paragraphs: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 打开即定位到当前段：长书原来停在第 0 段，用户得自己翻找。
    // 当前段落在视口中央（原 top 对齐在长列表里更难感知上下文）
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        listState.scrollToItem((currentIndex - 3).coerceAtLeast(0))
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            "段落导航",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = 480.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(paragraphs) { idx, para ->
                // 显示段落前60字预览（插图标记段显示占位）
                val isImage = BookImages.isImageMarker(para)
                val preview = if (isImage) {
                    "🖼 插图"
                } else {
                    para.take(60).replace("\n", " ") +
                        if (para.length > 60) "…" else ""
                }
                ListItem(
                    headlineContent = {
                        Text(
                            preview,
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    trailingContent = {
                        if (idx == currentIndex) {
                            Icon(Icons.Default.PlayArrow, "当前", tint = LocalReaderAccent.current)
                        }
                    },
                    modifier = Modifier.clickable {
                        onSelect(idx)
                        onDismiss()
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (idx == currentIndex)
                            LocalReaderAccent.current.copy(alpha = 0.1f) else Color.Transparent,
                    ),
                )
            }
        }
    }
}

// ── 选句翻译（底部抽屉） ─────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentenceTranslationDialog(
    sentence: String,
    translation: String?,
    isLoading: Boolean,
    onSpeak: () -> Unit,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())   // 超长句子+译文不再溢出被裁
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("句子翻译", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onSpeak) {
                    Icon(Icons.Default.PlayCircleFilled, "播放朗读", tint = LocalReaderAccent.current)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = sentence,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("翻译中...")
                }
            } else if (!translation.isNullOrBlank()) {
                Text(
                    text = translation,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalReaderAccent.current,
                )
            } else {
                // issue 8.9：null 语义是"词典/模型都没翻出来"，
                // 与"请求出错"区分开，避免误导用户以为网络故障。
                // 中性色 + 重试入口：旧实现红色文案且无重试，只能关抽屉重双击
                Text(
                    text = "暂无翻译结果",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("重试")
                }
            }
        }
    }
}
