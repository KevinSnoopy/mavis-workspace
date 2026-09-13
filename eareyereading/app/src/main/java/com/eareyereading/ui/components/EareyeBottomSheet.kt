package com.eareyereading.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.eareyereading.ui.theme.EareyeShapes

/**
 * 全 App 统一的底部抽屉壳：形状、容器色与**底部 safeArea 适配**都在这里收敛。
 *
 * ── 为什么必须有这个壳 ──
 * M3 1.1.2 的 [ModalBottomSheet] 默认给弹窗窗口传入
 * `BottomSheetDefaults.windowInsets`（系统栏上下两侧），
 * 结果：遮罩和抽屉都被导航栏高度顶起，抽屉底边悬在屏幕底之上，
 * 露出一条**无遮罩**的页面底色 —— 用户看到的"底边镂空"。
 * 实测在本机留出约 48px（≈18dp），长按高亮、模式说明、取 Key 引导等
 * 所有抽屉都有这个问题。
 *
 * 修法：`windowInsets` 归零，遮罩与抽屉都铺满整屏；手势条的避让职责
 * 移交给内容层 —— 本壳统一加 [navigationBarsPadding]，交互元素不会
 * 落进手势区，底色则由抽屉自己补齐，不再透出页面底色。
 *
 * @param shape 默认 [EareyeShapes.bottomSheet]（仅顶部圆角）——底部圆角
 *   会在贴屏时露出遮罩缺口，历史上单独修过一次，别再改回去
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EareyeBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = EareyeShapes.bottomSheet,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        windowInsets = WindowInsets(0),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            content = content,
        )
    }
}
