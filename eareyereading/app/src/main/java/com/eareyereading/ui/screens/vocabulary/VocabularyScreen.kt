package com.eareyereading.ui.screens.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.ui.components.AppTopBar
import com.eareyereading.ui.components.EmptyState
import com.eareyereading.ui.theme.L1
import com.eareyereading.ui.theme.L2
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.SectionTitle

/**
 * 词汇本主屏：搜索、学习概览、难度分布、Tab 筛选与单词列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyScreen(
    onBack: () -> Unit,
    viewModel: VocabularyViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            AppTopBar(title = "词汇本", onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
        ) {
            // 搜索：M3 SearchBar
            VocabularySearchBar(
                searchQuery = uiState.searchQuery,
                onQueryChange = viewModel::onSearch,
                filteredWords = uiState.filteredWords,
                onWordClick = { },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 学习概览
            VocabularyLearningOverview(
                learnedCount = uiState.learnedCount,
                totalCount = uiState.totalCount,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 难度分布
            VocabularyLevelDistribution(filteredWords = uiState.filteredWords)

            Spacer(modifier = Modifier.height(20.dp))

            // Tab：标准 M3 TabRow
            VocabularyTabRow(
                selectedTab = uiState.selectedTab,
                onTabSelected = viewModel::setTab,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 单词列表 header
            VocabularyListHeader(count = uiState.filteredWords.size)

            Spacer(modifier = Modifier.height(10.dp))

            VocabularyWordList(
                uiState = uiState,
                viewModel = viewModel,
            )
        }
    }
}

// ── 学习概览 ──────────────────────────────────────────

@Composable
private fun VocabularyLearningOverview(
    learnedCount: Int,
    totalCount: Int,
) {
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
            // §4.2.2：右卡（学习中）是用户核心关注，背景升一档（L2），
            // 左卡维持 L1——用色阶区分权重而非纯装饰底色
            StatMiniCard(
                value = "$learnedCount",
                label = "已掌握",
                bg = L1,
                modifier = Modifier.weight(1f),
            )
            StatMiniCard(
                value = "${totalCount - learnedCount}",
                label = "学习中",
                bg = L2,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── 难度分布 ──────────────────────────────────────────

@Composable
private fun VocabularyLevelDistribution(filteredWords: List<com.eareyereading.domain.model.Vocabulary>) {
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
                val count = filteredWords.count { it.level == i }
                LevelChip(level = i, count = count)
            }
        }
    }
}

// ── TabRow ───────────────────────────────────────────

@Composable
private fun VocabularyTabRow(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedTab,
    ) {
        Tab(
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            text = { Text("全部") },
            selectedContentColor = Primary,
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Tab(
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            text = { Text("学习中") },
            selectedContentColor = Primary,
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Tab(
            selected = selectedTab == 2,
            onClick = { onTabSelected(2) },
            text = { Text("已掌握") },
            selectedContentColor = Primary,
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 列表 header ──────────────────────────────────────

@Composable
private fun VocabularyListHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("单词列表", style = SectionTitle)
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 单词列表 ──────────────────────────────────────────

@Composable
private fun VocabularyWordList(
    uiState: VocabularyUiState,
    viewModel: VocabularyViewModel,
) {
    val displayed = when (uiState.selectedTab) {
        1 -> uiState.filteredWords.filter { !it.isLearned }
        2 -> uiState.filteredWords.filter { it.isLearned }
        else -> uiState.filteredWords
    }

    if (displayed.isEmpty()) {
        // §4.2.2 空状态：必须有引导文（原图只有 4 个字，
        // 用户不知道如何把单词加入），图标用描线风格不用 emoji
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                icon = Icons.Default.Translate,
                title = when (uiState.selectedTab) {
                    1 -> "还没有正在学习的单词"
                    2 -> "还没有已掌握的单词"
                    else -> "生词本为空"
                },
                subtitle = when (uiState.selectedTab) {
                    1 -> "在全部列表里把单词加入复习，即进入学习中"
                    2 -> "学习中的单词复习达标后会自动移到这里"
                    else -> "阅读时点选单词即可加入生词本"
                },
                modifier = Modifier.padding(horizontal = 32.dp),
            )
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
