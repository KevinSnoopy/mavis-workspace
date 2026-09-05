package com.eareyereading.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 间距令牌（4dp 基准，对应原型 --space-N）
 *
 * 设计原则：所有 padding / margin / gap 取这里的值，
 * 杜绝散落的魔法数字。8dp 是视觉节奏的最小步进。
 *
 * 来源：design-system/SPEC.md §3.3
 */
object Spacing {
    val xxs = 4.dp    // 图标内 padding、密集行内间隔
    val xs = 8.dp      // 紧凑间距
    val sm = 12.dp     // 卡片内 padding、chip 间距
    val md = 16.dp     // 默认 padding
    val lg = 20.dp     // 顶栏/底栏 padding
    val xl = 24.dp     // 章节间隔
    val xxl = 32.dp    // 大块留白
    val xxxl = 48.dp   // 屏幕边距、空状态图与文字间距
    val huge = 64.dp   // Hero 区域间距
}

/**
 * 屏幕级水平 padding（避免每处手写 20dp）
 */
val screenHorizontalPadding = Spacing.lg
