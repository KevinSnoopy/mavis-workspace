package com.eareyereading.ui.screens.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.domain.model.Vocabulary
import com.eareyereading.ui.theme.*

private val levelColors = listOf(L1, L2, L3, L4, L5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyScreen(
    onBack: () -> Unit,
    viewModel: VocabularyViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "词汇本",
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
        ) {
            // 搜索：M3 OutlinedTextField 默认样式（可见描边），不再做无边胶囊
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = {
                    Text("搜索单词...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 学习概览
            Column {
                Text(
                    "学习概览",
                    style = SectionTitle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatMiniCard(
                        value = "${uiState.learnedCount}",
                        label = "已掌握",
                        color = Accent,
                        modifier = Modifier.weight(1f),
                    )
                    StatMiniCard(
                        value = "${uiState.totalCount - uiState.learnedCount}",
                        label = "学习中",
                        color = Warning,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 难度分布
            Column {
                Text(
                    "难度分布",
                    style = SectionTitle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (i in 1..5) {
                        val count = uiState.filteredWords.count { it.level == i }
                        LevelChip(level = i, count = count)
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))

            // Tab：标准 M3 TabRow（与书库页统一；原透明胶囊无指示器是 web 风格）
            TabRow(
                selectedTabIndex = uiState.selectedTab,
            ) {
                Tab(
                    selected = uiState.selectedTab == 0,
                    onClick = { viewModel.setTab(0) },
                    text = { Text("全部") },
                    selectedContentColor = Primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Tab(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.setTab(1) },
                    text = { Text("学习中") },
                    selectedContentColor = Primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Tab(
                    selected = uiState.selectedTab == 2,
                    onClick = { viewModel.setTab(2) },
                    text = { Text("已掌握") },
                    selectedContentColor = Primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 单词列表 header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("单词列表", style = SectionTitle)
                Text(
                    "${uiState.filteredWords.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            val displayed = when (uiState.selectedTab) {
                1 -> uiState.filteredWords.filter { !it.isLearned }
                2 -> uiState.filteredWords.filter { it.isLearned }
                else -> uiState.filteredWords
            }

            if (displayed.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📝", style = MaterialTheme.typography.displayLarge)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            when (uiState.selectedTab) {
                                1 -> "还没有正在学习的单词"
                                2 -> "还没有已掌握的单词"
                                else -> "生词本为空"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(displayed, key = { it.id }) { word ->
                        WordCard(
                            vocabulary = word,
                            onMarkLearned = { viewModel.markAsLearned(word) },
                            onSpeak = { viewModel.speakWord(word) },
                            onDelete = { viewModel.deleteWord(word) },
                            onAddToReview = { viewModel.addToReview(word) },
                            onEditNote = { note, example -> viewModel.updateNote(word, note, example) },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun StatMiniCard(
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.1f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LevelChip(level: Int, count: Int) {
    val color = levelColors.getOrElse(level - 1) { Color.Gray }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(12.dp),
            color = color.copy(alpha = 0.1f),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, color.copy(alpha = 0.3f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "L$level",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "${count}词",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun WordCard(
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
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("编辑笔记") },
            text = {
                Column {
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("笔记") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = exampleText,
                        onValueChange = { exampleText = it },
                        label = { Text("例句") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onEditNote(noteText.ifBlank { null }, exampleText.ifBlank { null })
                    showNoteDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) { Text("取消") }
            },
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
                            text = vocabulary.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
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
                        onClick = { showNoteDialog = true },
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
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除「${vocabulary.word}」？") },
            text = { Text("删除后无法恢复，该词的复习进度也会一并删除。") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}
