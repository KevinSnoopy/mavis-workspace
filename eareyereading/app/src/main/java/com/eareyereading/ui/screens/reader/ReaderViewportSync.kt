package com.eareyereading.ui.screens.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 视口 ↔ ViewModel 的双向同步（NORMAL / SPLIT / BACK_TRANSLATION /
 * POS_ANALYSIS 四种 LazyColumn 渲染态共用）。
 *
 * ── 两个方向 ──
 * - 正向：`currentIndex` 被程序推进（朗读 / 进度滑杆 / 章节跳转）时把视口
 *   滚动到目标段；
 * - 反向：用户滚动时把可见段回报 VM，让底栏、进度滑杆、阅读统计跟上视口
 *   （播放中由播放循环主导，VM 侧会忽略）。
 *
 * ── 反向回报的"对齐闸门"（进度回滚的直接来源）──
 * 进书首帧的可见区间**一定从 item 0 开始**（视口还没滚到恢复位置）。旧实现
 * 无条件把首帧的可见段写回 VM，于是：
 *
 * 1. `currentParagraphIndex` 被改写成 0；
 * 2. `LaunchedEffect(currentIndex)` 观察到 0，把视口拉到书首；
 * 3. 防抖保存把 0 落库。
 *
 * 用户看到的就是"再次进入书总是回到开头"。修复方式是把"视口第一次真正抵达
 * [currentIndex]"当作闸门：闸门开启前只上报可见区间（翻译上屏据此判断哪一屏
 * 正在被看），不回报阅读位置；且闸门开启的那一帧也不回报——那正是恢复出来的
 * 位置本身，VM 里已经是对的。
 *
 * @param listState 目标列表状态
 * @param currentIndex VM 当前的阅读段落索引
 * @param paragraphCount 段落总数（越界保护）
 * @param paragraphOffset 段落索引与 item 索引的差：视图有表头 item 时传 1，
 *   无表头传 0
 * @param onVisibleParagraphChanged 阅读位置回报（闸门开启后才触发）
 * @param onVisibleRangeChanged 可见区间回报（始终触发，可空）
 */
@Composable
internal fun ReaderViewportSync(
    listState: LazyListState,
    currentIndex: Int,
    paragraphCount: Int,
    paragraphOffset: Int,
    onVisibleParagraphChanged: (Int) -> Unit,
    onVisibleRangeChanged: ((Int, Int) -> Unit)? = null,
) {
    val latestCurrentIndex by rememberUpdatedState(currentIndex)
    val latestOffset by rememberUpdatedState(paragraphOffset)
    var viewportReachedCurrent by remember { mutableStateOf(false) }
    var programmaticScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(currentIndex) {
        val target = currentIndex + paragraphOffset
        // 目标段已在可见窗口内就不发起程序化滚动：反向同步把用户滑动经过的
        // 段落写回 currentIndex 后，这里若再 animateScrollToItem，会在甩动
        // （fling）途中反复打断惯性、把视口拽回段首
        if (currentIndex in 0 until paragraphCount &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == target }
        ) {
            // 程序化滚动途中经过的段落不是"用户读到的位置"（长距离跳转如
            // 进度滑杆/章节跳转时，中途每滚过一段都会回报一次，把刚跳到的
            // 目标写成中途的旧位置）。整段滚动期间挂起回报
            programmaticScrolling = true
            try {
                listState.animateScrollToItem(target)
            } finally {
                // 协程被新一次 currentIndex 变更取消时也要复位，否则回报永久静默
                programmaticScrolling = false
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo
            if (info.isEmpty()) IntRange.EMPTY else info.first().index..info.last().index
        }
            .distinctUntilChanged()
            .collect { range ->
                if (range.isEmpty()) return@collect
                val first = (range.first - latestOffset).coerceAtLeast(0)
                val last = (range.last - latestOffset).coerceAtLeast(0)
                onVisibleRangeChanged?.invoke(first, last)
                if (!viewportReachedCurrent) {
                    if (latestCurrentIndex in first..last) {
                        // 视口已抵达恢复位置：开闸，但这一帧不回报
                        viewportReachedCurrent = true
                    }
                    return@collect
                }
                // 程序化滚动（视口对齐/跳转）途中的中间位置不回报
                if (programmaticScrolling) return@collect
                onVisibleParagraphChanged(first)
            }
    }
}
