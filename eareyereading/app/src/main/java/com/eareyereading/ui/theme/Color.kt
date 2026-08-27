package com.eareyereading.ui.theme

import androidx.compose.ui.graphics.Color

// ── 精致文具风配色 ────────────────────────────────

// Primary
val Primary = Color(0xFF8B7355)       // 暖棕 · 皮具/文具主色
val PrimaryDark = Color(0xFF6E5A43)   // 深暖棕
val PrimaryLight = Color(0xFFF0EBE3)  // 暖棕浅底

// Accent
val Accent = Color(0xFFC4956A)        // 赤陶橙 · 暖调点缀

// Background
val Background = Color(0xFFFAF8F5)    // 象牙白 · 品质纸质感

// Surface
val Surface = Color(0xFFFFFFFF)        // 卡片白
val SurfaceSecondary = Color(0xFFF5F2ED) // 暖灰白 · 柔和背景
val SurfaceHover = Color(0xFFEDE8E0)   // 悬停/选中态

// Text
val OnSurface = Color(0xFF3D3530)      // 暖深棕 · 正文，不是纯黑
val OnSurfaceSecondary = Color(0xFF7A7067) // 暖灰棕 · 次要文字
val OnSurfaceTertiary = Color(0xFFA69E94) // 暖浅灰 · 辅助文字
val OnSurfaceQuaternary = Color(0xFFC8C1B8) // 更淡的暖灰 · 图标默认色

// Border
val Border = Color(0xFFE8E4DC)         // 暖灰线 · 细边框
val BorderStrong = Color(0xFFD4CFC5)   // 深一点儿的暖线

// ── Collins 五级色（改为暖调）──────────
val L1 = Color(0xFFC9735B)   // 赤褐 · L1
val L2 = Color(0xFFD4A853)   // 暖金 · L2
val L3 = Color(0xFF9E8C5A)   // 橄榄 · L3
val L4 = Color(0xFF7A9E7E)   // 灰绿 · L4
val L5 = Color(0xFF6B8E9E)   // 青灰 · L5
val KnownWord = Color(0xFF7A9E7E) // 已认识 · 灰绿

// ── 语义色（暖调）──────────
val Success = Color(0xFF7A9E7E)    // 灰绿 · 柔和自然
val SuccessBg = Color(0xFFEEF3EE)

val Warning = Color(0xFFD4A853)    // 暖金 · 温和
val WarningBg = Color(0xFFFBF5EA)

val Error = Color(0xFFC9735B)     // 赤褐 · 不刺眼
val ErrorBg = Color(0xFFFBF0ED)

val Info = Color(0xFF6B8E9E)     // 青灰
val InfoBg = Color(0xFFEDF3F5)

// ── 阅读主题色 ────────────────────────────────────
val SepiaBg = Color(0xFFF5E6C8)
val SepiaText = Color(0xFF5D4037)
val DarkBg = Color(0xFF1A1A2E)
val DarkText = Color(0xFFE8E8F0)

// ── 词性/功能色 ──────────────────────────────────
val Highlight = Color(0xFFFFE082)     // 高亮 · 黄色
val RsvpBold = Color(0xFF3D3530)     // RSVP 加粗部分

// ── 词频分级颜色 ──────────────────────────────
val WordLevelCore = Color(0xFF7A9E7E)
val WordLevelIntmd = Color(0xFF7A9E7E)
val WordLevelUpper = Color(0xFF6B8E9E)
val WordLevelAdv = Color(0xFFD4A853)
val WordLevelRare = Color(0xFFC9735B)
val WordLevelUnknown = Color(0xFFA69E94)

// ── 固定常量 ──────────────────────────────────────
val OnPrimary = Color(0xFFFFFFFF)  // 白色文字，在 Primary 背景上使用
val OnBackground = OnSurface        // 背景上的文字，同 OnSurface

// ── 兼容性别名（保持其他文件引用不报错）──────────────

@Deprecated("Use Primary", ReplaceWith("Primary"))
val PrimaryVariant = PrimaryDark

@Deprecated("Use Accent or Success", ReplaceWith("Accent"))
val Secondary = Accent

@Deprecated("Use Error", ReplaceWith("Error"))
val SecondaryVariant = Error

@Deprecated("Use Background", ReplaceWith("Background"))
val SurfaceVariant = SurfaceSecondary
