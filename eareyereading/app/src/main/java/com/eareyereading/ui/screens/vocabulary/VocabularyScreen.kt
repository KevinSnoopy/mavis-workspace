package com.eareyereading.ui.screens.vocabulary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.domain.model.Vocabulary
import com.eareyereading.ui.theme.KnownWord
import com.eareyereading.ui.theme.NewWord
import com.eareyereading.ui.theme.Primary

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
                    Column {
                        Text("生词本", fontWeight = FontWeight.Bold)
                        Text(
                            "${uiState.learnedCount}/${uiState.totalCount} 已掌握",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 搜索
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索单词...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            // Tab
            TabRow(selectedTabIndex = uiState.selectedTab) {
                Tab(
                    selected = uiState.selectedTab == 0,
                    onClick = { viewModel.setTab(0) },
                    text = { Text("全部 (${uiState.totalCount})") },
                )
                Tab(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.setTab(1) },
                    text = { Text("新词 (${uiState.totalCount - uiState.learnedCount})") },
                )
                Tab(
                    selected = uiState.selectedTab == 2,
                    onClick = { viewModel.setTab(2) },
                    text = { Text("已学 (${uiState.learnedCount})") },
                )
            }

            val displayed = when (uiState.selectedTab) {
                1 -> uiState.filteredWords.filter { !it.isLearned }
                2 -> uiState.filteredWords.filter { it.isLearned }
                else -> uiState.filteredWords
            }

            if (displayed.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📝", style = MaterialTheme.typography.displayMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            when (uiState.selectedTab) {
                                1 -> "暂无新词，继续阅读积累吧"
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
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(displayed, key = { it.id }) { word ->
                        WordCard(
                            vocabulary = word,
                            onMarkLearned = { viewModel.markAsLearned(word) },
                            onDelete = { viewModel.deleteWord(word) },
                            onAddToReview = { viewModel.addToReview(word) },
                            onEditNote = { note, example -> viewModel.updateNote(word, note, example) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WordCard(
    vocabulary: Vocabulary,
    onMarkLearned: () -> Unit,
    onDelete: () -> Unit,
    onAddToReview: () -> Unit,
    onEditNote: (String?, String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf(vocabulary.note ?: "") }
    var exampleText by remember { mutableStateOf(vocabulary.example ?: "") }

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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (vocabulary.isLearned)
                KnownWord.copy(alpha = 0.08f)
            else
                NewWord.copy(alpha = 0.08f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = vocabulary.word,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!vocabulary.phonetic.isNullOrBlank()) {
                        Text(
                            text = vocabulary.phonetic,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                }
                if (vocabulary.isLearned) {
                    AssistChip(
                        onClick = {},
                        label = { Text("已掌握") },
                        leadingIcon = { Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = KnownWord.copy(alpha = 0.2f),
                        ),
                    )
                }
            }

            if (!vocabulary.definition.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
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
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Primary.copy(alpha = 0.08f),
                ) {
                    Row(modifier = Modifier.padding(8.dp)) {
                        Icon(
                            Icons.Default.Note, null,
                            modifier = Modifier.size(16.dp),
                            tint = Primary,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
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
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "例句: ${vocabulary.example}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Primary,
                    fontStyle = FontStyle.Italic,
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (!vocabulary.isLearned) {
                        FilledTonalButton(onClick = onMarkLearned) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("标记已学")
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = { showNoteDialog = true }) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("笔记")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = onAddToReview) {
                        Icon(Icons.Default.School, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("复习")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
