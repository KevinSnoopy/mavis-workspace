package com.eareyereading.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.eareyereading.domain.model.Book
import com.eareyereading.ui.components.BookCover
import com.eareyereading.ui.theme.Primary

// ── 最近阅读书籍卡片 ────────────────────────────────
@Composable
internal fun RecentBookCard(
    book: Book,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            // 改版C：横向卡 144dp 宽（§4.5.3 horizontal-card 规格）；
            // 首卡 16dp 对齐 + 末卡右露 16dp 由 LazyRow contentPadding 保证
            .width(144.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),

    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // 封面：v2 预设封面 > EPUB 内嵌封面 > 生成式插图封面（自带书名/作者）
            BookCover(
                title = book.title,
                coverPath = book.coverPath,
                author = book.author,
                coverStyle = book.coverStyle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(128.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = book.readProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Primary,
                trackColor = Primary.copy(alpha = 0.15f),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${(book.readProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
