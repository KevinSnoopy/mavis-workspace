package com.eareyereading.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 阴影 / 高度令牌（对应原型 --elev-*）
 *
 * 设计判断：Material 3 默认 elevation 用的是彩色阴影（primary 12% 透明）
 * 在纸感美学里反而显脏。改用中性灰阴影（rgba(0,0,0,.04-.08)）
 * 保留物理感不抢颜色焦点。
 *
 * 来源：design-system/SPEC.md §3.5
 */
object Elevation {
    /** 0：无阴影（基线） */
    val none = 0.dp

    /** 1dp：状态提示（chip 选中、tab 下划线） */
    val xs = 1.dp

    /** 2dp：卡片悬浮 */
    val sm = 2.dp

    /** 3dp：FAB、弹窗 */
    val md = 3.dp

    /** 4dp：导航栏 */
    val lg = 4.dp

    /** 6dp：modal sheet、snackbar */
    val xl = 6.dp

    /** 8dp：弹出菜单、pop over */
    val xxl = 8.dp
}
