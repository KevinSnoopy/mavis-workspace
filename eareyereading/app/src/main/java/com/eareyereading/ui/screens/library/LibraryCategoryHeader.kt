package com.eareyereading.ui.screens.library

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.SectionTitle

/** 分类组头：书架分组标题（分类名 + 数量 + 分隔线）。 */
@Composable
internal fun CategoryHeader(category: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Category,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = category,
            style = SectionTitle,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$count 本",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Divider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
    }
}
