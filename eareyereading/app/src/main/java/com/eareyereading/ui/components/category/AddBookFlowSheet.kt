package com.eareyereading.ui.components.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.theme.EareyeShapes
import com.eareyereading.ui.theme.OnSurface
import com.eareyereading.ui.theme.OnSurfaceVariant
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.Surface

/**
 * 添加书籍 3 步流程（SPEC §4.11）
 *
 * Step 1 基础信息（书名/作者/ISBN）→ Step 2 选分类 → Step 3 选封面
 *
 * [initialTitle]/[initialAuthor]：导入流程接入时预填 EPUB 元数据，
 * 用户可在步骤 1 校对后直接「下一步」。
 *
 * 设计判断：
 * - 单 Sheet 内 3 步内容互斥显示，避免 3 个独立 Sheet 状态同步难题
 * - 步骤条状态机：pending / active / done
 * - 「下一步」按下进入下一步，「上一步」回退一步
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBookFlowSheet(
    categories: List<Category> = emptyList(),
    initialTitle: String = "",
    initialAuthor: String = "",
    onComplete: (title: String, author: String, isbn: String, categoryName: String?, coverId: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var step by remember { mutableIntStateOf(0) }  // 0..2
    var title by remember { mutableStateOf(initialTitle) }
    var author by remember { mutableStateOf(initialAuthor) }
    var isbn by remember { mutableStateOf("") }
    var selectedCategoryIdx by remember { mutableIntStateOf(-1) }
    var selectedCoverId by remember { mutableIntStateOf(0) }

    val stepLabels = listOf("基础信息", "分类", "封面")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = EareyeShapes.xxl,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = "添加书籍",
                style = MaterialTheme.typography.titleLarge,
                color = OnSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // 步骤条
            Stepper(
                currentStep = step,
                labels = stepLabels,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
            )

            // 当前步骤内容
            when (step) {
                0 -> StepBasicInfo(
                    title = title,
                    author = author,
                    isbn = isbn,
                    onTitleChange = { title = it },
                    onAuthorChange = { author = it },
                    onIsbnChange = { isbn = it },
                )

                1 -> StepSelectCategory(
                    categories = categories,
                    selectedIdx = selectedCategoryIdx,
                    onSelect = { selectedCategoryIdx = it },
                )

                2 -> StepSelectCover(
                    selectedCoverId = selectedCoverId,
                    title = title,
                    author = author,
                    onSelect = { selectedCoverId = it },
                )
            }

            // 底部按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = { step-- },
                        modifier = Modifier.weight(1f),
                        shape = EareyeShapes.md,
                    ) {
                        Text("上一步")
                    }
                }
                Button(
                    onClick = {
                        when (step) {
                            2 -> {
                                // 完成：分类索引映射为分类名传给调用方
                                val categoryName = selectedCategoryIdx
                                    .takeIf { it >= 0 }
                                    ?.let { categories.getOrNull(it)?.name }
                                onComplete(title, author, isbn, categoryName, selectedCoverId)
                            }

                            else -> step++
                        }
                    },
                    modifier = Modifier.weight(2f),
                    enabled = when (step) {
                        0 -> title.isNotBlank()
                        1 -> selectedCategoryIdx >= 0
                        else -> true
                    },
                    shape = EareyeShapes.md,
                ) {
                    Text(
                        if (step == 2) "完成" else "下一步",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stepper(
    currentStep: Int,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { idx, label ->
            val state = when {
                idx < currentStep -> StepState.DONE
                idx == currentStep -> StepState.ACTIVE
                else -> StepState.PENDING
            }
            StepDot(stepNumber = idx + 1, label = label, state = state)
            if (idx < labels.size - 1) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.5.dp)
                        .background(
                            if (idx < currentStep) Primary else OnSurfaceVariant.copy(alpha = 0.3f)
                        ),
                )
            }
        }
    }
}

private enum class StepState { PENDING, ACTIVE, DONE }

@Composable
private fun StepDot(stepNumber: Int, label: String, state: StepState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    when (state) {
                        StepState.ACTIVE, StepState.DONE -> Primary
                        StepState.PENDING -> Surface
                    }
                )
                .border(
                    width = 1.5.dp,
                    color = if (state == StepState.PENDING) OnSurfaceVariant.copy(alpha = 0.5f) else Primary,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                StepState.DONE -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = Color.White,
                )

                else -> Text(
                    text = stepNumber.toString(),
                    color = if (state == StepState.ACTIVE) Color.White else OnSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = label,
            color = if (state == StepState.PENDING) OnSurfaceVariant else OnSurface,
            fontSize = 12.sp,
            fontWeight = if (state == StepState.ACTIVE) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── 步骤 1：基础信息 ──
@Composable
private fun StepBasicInfo(
    title: String,
    author: String,
    isbn: String,
    onTitleChange: (String) -> Unit,
    onAuthorChange: (String) -> Unit,
    onIsbnChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LabeledTextField(
            label = "书名",
            value = title,
            onValueChange = onTitleChange,
            placeholder = "如：Atomic Habits",
        )
        LabeledTextField(
            label = "作者",
            value = author,
            onValueChange = onAuthorChange,
            placeholder = "如：James Clear",
        )
        LabeledTextField(
            label = "ISBN（可选，用于自动填充）",
            value = isbn,
            onValueChange = onIsbnChange,
            placeholder = "978-0-7352-1129-2",
        )
    }
}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Column {
        Text(
            text = label,
            color = OnSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = OnSurfaceVariant.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = EareyeShapes.md,
        )
    }
}

// ── 步骤 2：选择分类（复用公共 CategorySelectGrid）──
@Composable
private fun StepSelectCategory(
    categories: List<Category>,
    selectedIdx: Int,
    onSelect: (Int) -> Unit,
) {
    CategorySelectGrid(
        categories = categories,
        selectedName = categories.getOrNull(selectedIdx)?.name,
        onSelect = { cat -> onSelect(categories.indexOf(cat)) },
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
        showAddTile = true,
        onAddClick = { /* v2 占位：可打开 CategoryEditSheet 新建后回填 */ },
    )
}

// ── 步骤 3：选择封面 ──
@Composable
private fun StepSelectCover(
    selectedCoverId: Int,
    title: String,
    author: String,
    onSelect: (Int) -> Unit,
) {
    // 步骤 3 复用 CoverPickerSheet 的视觉，但内嵌在 AddBookFlow 内
    Text(
        text = "从下方选择一个封面背景：",
        color = OnSurfaceVariant,
        fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    // 简化：仅显示前 6 个封面背景
    val ids = (0..5).toList()
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(ids) { id ->
            val gradients = com.eareyereading.ui.theme.CoverGradients
            Box(
                modifier = Modifier
                    .aspectRatio(0.75f)
                    .clip(EareyeShapes.md)
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(gradients[id]))
                    .border(
                        width = if (selectedCoverId == id) 2.5.dp else 0.dp,
                        color = if (selectedCoverId == id) Primary else Color.Transparent,
                        shape = EareyeShapes.md,
                    )
                    .clickable { onSelect(id) },
            ) {
                if (selectedCoverId == id) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Color.White,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                ) {
                    Text(
                        text = title.ifBlank { "书名" },
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                    )
                    Box(modifier = Modifier.weight(1f))
                    Text(
                        text = author.ifBlank { "作者" },
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 8.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
