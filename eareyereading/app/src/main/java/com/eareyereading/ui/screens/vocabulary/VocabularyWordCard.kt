package com.eareyereading.ui.screens.vocabulary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eareyereading.domain.model.Vocabulary
import com.eareyereading.ui.theme.Accent
import com.eareyereading.ui.theme.Primary

/**
 * 单词卡片：展示单词、音标、释义、上下文、笔记、例句，
 * 展开后提供标记已掌握、编辑笔记、加入复习、删除操作。
 *
 * @param vocabulary 单词数据
 * @param onMarkLearned 标记已掌握
 * @param onSpeak 播放发音
 * @param onDelete 删除单词
 * @param onAddToReview 加入复习
 * @param onEditNote 编辑笔记（笔记、例句）
 */
@Composable
internal fun WordCard(
    vocabulary: Vocabulary,
    onMarkLearned: () -> Unit,
    onSpeak: () -> Unit,
    onDelete: () -> Unit,
    onAddToReview: () -> Unit,
    onEditNote: (String?, String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    // 删词是永久操作（连同复习记录一起删）：二次确认防误触
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 带上数据键：单词的笔记/例句被外部路径更新后，对话框不再显示陈旧初值
    var noteText by remember(vocabulary.id, vocabulary.note) { mutableStateOf(vocabulary.note ?: "") }
    var exampleText by remember(vocabulary.id, vocabulary.example) { mutableStateOf(vocabulary.example ?: "") }

    if (showNoteDialog) {
        WordNoteEditDialog(
            noteText = noteText,
            exampleText = exampleText,
            onNoteChange = { noteText = it },
            onExampleChange = { exampleText = it },
            onSave = {
                onEditNote(noteText.ifBlank { null }, exampleText.ifBlank { null })
                showNoteDialog = false
            },
            onDismiss = { showNoteDialog = false },
        )
    }

    val levelColor = levelColors.getOrElse(vocabulary.level - 1) { Color.Gray }
    val cardBgColor = if (vocabulary.isLearned) Accent.copy(alpha = 0.06f) else levelColor.copy(alpha = 0.06f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            WordCardHeader(
                vocabulary = vocabulary,
                levelColor = levelColor,
                onSpeak = onSpeak,
            )

            if (!vocabulary.definition.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = vocabulary.definition,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!vocabulary.context.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "\"${vocabulary.context}\"",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            // 用户笔记
            if (!vocabulary.note.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                WordNoteSection(note = vocabulary.note)
            }

            // 例句
            if (!vocabulary.example.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "📝 \"${vocabulary.example}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = Primary,
                    fontStyle = FontStyle.Italic,
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(14.dp))
                WordCardActions(
                    vocabulary = vocabulary,
                    onMarkLearned = onMarkLearned,
                    onShowNoteDialog = { showNoteDialog = true },
                    onAddToReview = onAddToReview,
                    onShowDeleteConfirm = { showDeleteConfirm = true },
                )
            }
        }
    }

    if (showDeleteConfirm) {
        WordDeleteConfirmDialog(
            word = vocabulary.word,
            onConfirm = { showDeleteConfirm = false; onDelete() },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

// ── 卡片头部 ──────────────────────────────────────────

@Composable
private fun WordCardHeader(
    vocabulary: Vocabulary,
    levelColor: Color,
    onSpeak: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            // 难度徽章
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(10.dp),
                color = levelColor.copy(alpha = 0.15f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "L${vocabulary.level}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = levelColor,
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = vocabulary.word,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = onSpeak, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Default.PlayCircleFilled,
                            "播放发音",
                            tint = Primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                if (!vocabulary.phonetic.isNullOrBlank()) {
                    Text(
                        text = vocabulary.phonetic,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
        }
        if (vocabulary.isLearned) {
            AssistChip(
                onClick = {},
                label = { Text("✓ 已掌握", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = {
                    Icon(
                        Icons.Default.CheckCircle,
                        null,
                        modifier = Modifier.size(14.dp),
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Accent.copy(alpha = 0.15f),
                    labelColor = Accent,
                ),
            )
        }
    }
}

// ── 笔记区 ────────────────────────────────────────────

@Composable
private fun WordNoteSection(note: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Primary.copy(alpha = 0.08f),
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            Icon(
                Icons.Default.Lightbulb,
                null,
                modifier = Modifier.size(15.dp),
                tint = Primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ── 展开操作区 ────────────────────────────────────────

@Composable
private fun WordCardActions(
    vocabulary: Vocabulary,
    onMarkLearned: () -> Unit,
    onShowNoteDialog: () -> Unit,
    onAddToReview: () -> Unit,
    onShowDeleteConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        if (!vocabulary.isLearned) {
            Button(
                onClick = onMarkLearned,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("认识", fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        OutlinedButton(
            onClick = onShowNoteDialog,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("笔记")
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(
            onClick = onAddToReview,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Default.School, null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("复习")
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = onShowDeleteConfirm) {
            Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
        }
    }
}

// ── 笔记编辑弹窗 ──────────────────────────────────────

@Composable
private fun WordNoteEditDialog(
    noteText: String,
    exampleText: String,
    onNoteChange: (String) -> Unit,
    onExampleChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑笔记") },
        text = {
            Column {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = onNoteChange,
                    label = { Text("笔记") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = exampleText,
                    onValueChange = onExampleChange,
                    label = { Text("例句") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ── 删除确认弹窗 ──────────────────────────────────────

@Composable
private fun WordDeleteConfirmDialog(
    word: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除「$word」？") },
        text = { Text("删除后无法恢复，该词的复习进度也会一并删除。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
