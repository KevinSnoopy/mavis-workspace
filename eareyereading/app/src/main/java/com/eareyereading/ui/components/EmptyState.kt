package com.eareyereading.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.EareyeReadingTheme
import com.eareyereading.ui.theme.Primary

/**
 * 通用空状态：圆形底图标 + 标题 + 副文案 +（可选）行动按钮。
 *
 * 全 App 唯一的空状态实现。此前三处各写一套 ——
 * `EmptyReviewView`（裸 80dp 图标、无底）、`EmptyReadingGuide`（28dp 图标、
 * 18dp 内边距底）、Library 走本组件 —— 同一个 App 里三种空状态长相。
 * 统一到这里后，新增页面直接复用，不会再长出第四套。
 *
 * 从 `ReadingHeatmap.kt` 迁出（SRP）：它和热力图没有任何关系，
 * 只是当初顺手写在了同一个文件里。
 *
 * @param tint 图标色。默认品牌主色；语义不同的场景可传（如复习完成用 Success，
 *             失败态用 Error），避免为了统一视觉而丢掉语义
 * @param actionLabel / actionIcon / onAction 行动入口。空状态最忌讳"只告诉
 *             用户这里是空的"，必须给一条明确的去路
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    tint: Color = Primary,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint.copy(alpha = 0.85f),
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 副文案常换行：Column 只保证自适应的居中，多行文本内部仍会左对齐
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedButton(onClick = onAction) {
                if (actionIcon != null) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(actionLabel)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStatePreview() {
    EareyeReadingTheme {
        EmptyState(
            icon = Icons.Outlined.MenuBook,
            title = "书架为空",
            subtitle = "导入 EPUB/TXT，或从经典名著一键下载",
            actionLabel = "去书库导入",
            onAction = {},
        )
    }
}
