package com.eareyereading.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角令牌（对应原型 --radius-*）
 *
 * 设计判断：Android Material 3 默认 16dp 圆角偏大且偏方，
 * 我们的墨绿+暖白纸感美学需要更柔和的圆弧。统一收 8dp
 * 为基础，关键容器（弹窗/卡片）上提到 16dp 以上。
 *
 * 来源：design-system/SPEC.md §3.4
 */
object EareyeShapes {
    /** 4dp：chip 内部小元素 */
    val xs = RoundedCornerShape(4.dp)

    /** 8dp：按钮、tag */
    val sm = RoundedCornerShape(8.dp)

    /** 12dp：输入框、表单元素 */
    val md = RoundedCornerShape(12.dp)

    /** 16dp：卡片（默认） */
    val lg = RoundedCornerShape(16.dp)

    /** 20dp：弹窗 sheet 顶部 */
    val xl = RoundedCornerShape(20.dp)

    /** 28dp：bottom sheet 顶部圆角 */
    val xxl = RoundedCornerShape(28.dp)

    /** 胶囊：full 圆角（50% 半径） */
    val full = RoundedCornerShape(50)
}

/**
 * Material 3 Shapes 标准映射
 * 把我们的圆角体系接入 M3 组件
 */
val Shapes = Shapes(
    extraSmall = EareyeShapes.xs,
    small = EareyeShapes.sm,
    medium = EareyeShapes.md,
    large = EareyeShapes.lg,
    extraLarge = EareyeShapes.xxl,
)

