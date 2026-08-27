package com.eareyereading.ui.theme

import androidx.compose.ui.graphics.Color

// ── Apple Design System ────────────────────────────

// 主色调
val Primary = Color(0xFF007AFF)        // SF Blue
val PrimaryDark = Color(0xFF0056CC)
val PrimaryLight = Color(0xFFEBF5FF)
val Accent = Color(0xFF34C759)          // Green

// 背景色
val Background = Color(0xFFF2F2F7)      // Apple Light Gray
val Surface = Color(0xFFFFFFFF)
val SurfaceSecondary = Color(0xFFF2F2F7)
val SurfaceHover = Color(0xFFE8E8ED)

// 文字颜色
val OnPrimary = Color.White
val OnBackground = Color(0xFF000000)
val OnSurface = Color(0xFF000000)
val OnSurfaceVariant = Color(0xFF3C3C43)
val OnSurfaceTertiary = Color(0xFF8E8E93)
val OnSurfaceQuaternary = Color(0xFFC7C7CC)

// ── 功能色 ─────────────────────────────────────────

val Success = Color(0xFF34C759)
val SuccessBg = Color(0xFFE8F9ED)
val Warning = Color(0xFFFF9F0A)
val WarningBg = Color(0xFFFFF5E5)
val Error = Color(0xFFFF3B30)
val ErrorBg = Color(0xFFFFEBEA)
val Info = Color(0xFF007AFF)
val InfoBg = Color(0xFFEBF5FF)

// ── 阅读主题色 ────────────────────────────────────

val SepiaBg = Color(0xFFF5E6C8)
val SepiaText = Color(0xFF5D4037)
val DarkBg = Color(0xFF1A1A2E)
val DarkText = Color(0xFFE8E8F0)

// ── 词性/功能色 ──────────────────────────────────

val KnownWord = Color(0xFF34C759)       // 已认识的词 - 绿色
val NewWord = Color(0xFFFF9500)         // 生词 - 橙色
val Highlight = Color(0xFFFFE082)        // 高亮 - 黄色
val RsvpBold = Color(0xFF1A1A2E)        // RSVP 加粗部分

// ── Collins 词频分级颜色 ──────────────────────────

val WordLevelCore    = Color(0xFF34C759)   // 核心词汇 - 绿色
val WordLevelIntmd   = Color(0xFF34C759)   // 进阶词汇 - 绿色
val WordLevelUpper   = Color(0xFF007AFF)   // 提高词汇 - 蓝色
val WordLevelAdv     = Color(0xFFFF9500)   // 高阶词汇 - 橙色
val WordLevelRare    = Color(0xFFFF3B30)   // 学术词汇 - 红色
val WordLevelUnknown = Color(0xFF8E8E93)   // 未分级 - 灰色

// ── 兼容性别名（保持其他文件引用不报错）──────────────

@Deprecated("Use Primary", ReplaceWith("Primary"))
val PrimaryVariant = PrimaryDark

@Deprecated("Use Accent or Success", ReplaceWith("Accent"))
val Secondary = Accent

@Deprecated("Use Error", ReplaceWith("Error"))
val SecondaryVariant = Error

@Deprecated("Use Background", ReplaceWith("Background"))
val SurfaceVariant = SurfaceSecondary
