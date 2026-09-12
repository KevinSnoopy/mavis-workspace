package com.eareyereading.ui.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.Secondary

/**
 * 段落渲染共享零件。
 *
 * ── 重构说明（DRY / 单一真实来源）──
 * 整段渲染 [ReaderParagraphBlock] 与跨页切片渲染 [ReaderSliceParagraphBlock]
 * 必须保证段落视觉逐像素一致，但"朗读段落底 / 书签标记行 / 译文块"这三段
 * 此前在两个文件里各写一份、逐行相同。任何一处样式调整漏改另一处，
 * 就会让滚动阅读与翻页阅读出现肉眼可见的差异。现收敛到此文件。
 */

/**
 * 段落内容容器修饰符：朗读中的当前段落铺强调色底，其余段落不加修饰。
 * 直接作用于内容容器——原实现额外包了一个 Text("") 的 Surface 承载背景，
 * 该 Surface 零高度，背景永远不可见，已废弃。
 *
 * 刻意不加内边距：本容器一旦内缩，当前段落的可用宽度就比 [paginateBook]
 * 测量的宽度窄，换行结果随之变多（并且高亮段落的文字会相对邻段左右跳动）。
 * 分页是"先算后画"，渲染侧任何几何变化都会变成页内溢出。
 */
@Composable
internal fun readerParagraphContainerModifier(isCurrent: Boolean, isAutoReading: Boolean): Modifier =
    Modifier
        .fillMaxWidth()
        .then(
            if (isCurrent && isAutoReading) {
                Modifier.background(
                    LocalReaderAccent.current.copy(alpha = 0.06f),
                    RoundedCornerShape(8.dp),
                )
            } else {
                Modifier
            },
        )

/**
 * 书签段落标记行：书签图标 + 延伸分隔线。
 * 高度由 [ReaderLayout.BookmarkRowHeight] 定死（分页侧按同一常量记账）。
 */
@Composable
internal fun ReaderBookmarkMark() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ReaderLayout.BookmarkRowHeight),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Bookmark,
            "已书签",
            modifier = Modifier.size(16.dp),
            tint = Secondary,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Divider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = Secondary.copy(alpha = 0.3f),
        )
    }
}

/**
 * 段落译文块：上间距 + 强调色译文 + 下间距。
 * 间距只在实际有译文时产生——原实现把下间距放在判空之外，
 * 未翻译段落会多出一截空白，段落节奏不齐。
 *
 * 三段间距之和恒等于 [ReaderLayout.TransBlockHeight]，分页侧按同一常量
 * 给段尾切片记账；改这里就必须改那里，否则一页内容会反超一屏。
 */
@Composable
internal fun ReaderTranslationBlock(
    translation: String,
    fontSize: Int,
    alpha: Float,
    translationAlpha: Float,
) {
    Spacer(modifier = Modifier.height(ReaderLayout.TransTopSpacing))
    Text(
        text = translation,
        modifier = Modifier
            .padding(vertical = ReaderLayout.TransInnerPadding)
            .alpha(alpha),
        style = readerParagraphStyle(
            fontSize - ReaderLayout.TRANS_FONT_DELTA,
            ReaderLayout.TRANS_LINE_MULTIPLIER,
        ).copy(
            color = LocalReaderAccent.current.copy(alpha = translationAlpha),
        ),
    )
    Spacer(modifier = Modifier.height(ReaderLayout.TransBottomSpacing))
}
