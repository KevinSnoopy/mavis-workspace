package com.eareyereading.ui.components.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.EareyeShapes
import com.eareyereading.ui.theme.OnSurface

/**
 * 封面背景选择器弹窗（SPEC §4.10）。
 *
 * 15 个预设封面背景分 3 段（纯色渐变 / 几何图案 / 装饰风格），
 * 网格与分段切换由 [CoverPickerContent] 提供——与新增书籍流程内的
 * 「选封面」步骤共用同一份内容实现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverPickerSheet(
    initialCoverId: Int = 0,
    previewTitle: String = "书名",
    previewAuthor: String = "作者",
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedId by remember { mutableIntStateOf(initialCoverId) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = EareyeShapes.bottomSheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = "选择封面背景",
                style = MaterialTheme.typography.titleLarge,
                color = OnSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            CoverPickerContent(
                selectedId = selectedId,
                onSelect = { selectedId = it },
                previewTitle = previewTitle,
                previewAuthor = previewAuthor,
                modifier = Modifier.fillMaxWidth(),
            )

            // 底部按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = EareyeShapes.md,
                ) {
                    Text("取消")
                }
                Button(
                    onClick = { onPick(selectedId) },
                    modifier = Modifier.weight(2f),
                    shape = EareyeShapes.md,
                ) {
                    Text("应用此封面", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
