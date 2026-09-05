package com.eareyereading.ui.theme

import androidx.compose.ui.graphics.Color

// ── 温润墨绿 + 暖白（单一色系，Material 3 角色制）────────
// 设计判断：原「大地色 + 墨绿强调」双调性割裂，统一收敛到
// 「温润墨绿 + 暖白」。primary 只承担 CTA / 当前态 / 进度填充。

// Primary
val Primary = Color(0xFF0E6B5E)        // 温润墨绿 · 主操作色
val PrimaryDark = Color(0xFF00504A)    // 深墨绿 · 深色主题容器
val PrimaryLight = Color(0xFFA6F2D8)   // primary-container · 选中底/强调底
val OnPrimaryContainer = Color(0xFF002019) // 主色容器上的前景

// Secondary / Tertiary
val Secondary = Color(0xFF4A635F)      // 次要色 · 次级按钮/辅助图标
val Accent = Color(0xFFB85B00)         // tertiary 暖橙 · 高亮标记（平衡墨绿主调）

// Background / Surface
val Background = Color(0xFFFBF7F1)     // 暖白页面背景（非纯白）
val Surface = Color(0xFFFFFFFF)        // 卡片白
val SurfaceSecondary = Color(0xFFF2EBE0) // surface-container · 容器背景
val SurfaceHover = Color(0xFFE8DFCF)   // surface-container-high · 凸起容器

// Text
val OnSurface = Color(0xFF1B1C18)      // 主前景 · 正文/标题
val OnSurfaceSecondary = Color(0xFF6B7268) // on-surface-variant · 副标/说明
val OnSurfaceTertiary = Color(0xFF767D74)  // 辅助文字（区块小标题）
val OnSurfaceQuaternary = Color(0xFF9CA39A) // 图标默认色

// Border
val Border = Color(0xFFE6DECD)         // outline-variant · 细分割线
val BorderStrong = Color(0xFFD4C9B4)   // outline · 卡片描边/滑杆轨道

// ── 难度等级色（同一主色 hue 的饱和度阶梯，§3.1.2）──────
val L1 = Color(0xFFE8F1ED)   // 核心词 · 最浅
val L2 = Color(0xFFA6D2C0)   // 进阶词
val L3 = Color(0xFF5BA889)   // 提高词
val L4 = Color(0xFF2E7D63)   // 高阶词
val L5 = Color(0xFF0E6B5E)   // 学术词 · 最深（与主色同色）
val KnownWord = Color(0xFF5BA889) // 已认识 · L3 绿

// ── 语义色 ──────────────────────────────────────
val Success = Color(0xFF2E7D32)    // 成功 · 打卡/复习完成
val SuccessBg = Color(0xFFE7F1E8)

val Warning = Color(0xFFE65100)    // 警告 · 待复习
val WarningBg = Color(0xFFFDEDE3)

val Error = Color(0xFFBA1A1A)      // 错误 · 删除/失败
val ErrorBg = Color(0xFFFBE9E9)

val Info = Color(0xFF1E88E5)       // 信息 · 蓝
val InfoBg = Color(0xFFE8F1FB)

// ── 学习状态色（Anki New/Learning/Review 三色映射，§3.1.3）──
val StateNew = Color(0xFF1E88E5)       // 新词 · 蓝
val StateLearning = Color(0xFFFB8C00)  // 学习中 · 橙
val StateMastered = Color(0xFF2E7D32)  // 已掌握 · 绿

// ── 热力图色阶（主色 hue 5 阶明度，§3.1.4）────────────
val HeatmapEmpty = Color(0xFFF2EBE0)
val HeatmapLevel1 = Color(0xFFB8DCD0)
val HeatmapLevel2 = Color(0xFF5BA889)
val HeatmapLevel3 = Color(0xFF2E7D63)
val HeatmapLevel4 = Color(0xFF0E6B5E)

// ── 阅读主题色 ────────────────────────────────────
val SepiaBg = Color(0xFFF5E6C8)
val SepiaText = Color(0xFF5D4037)
val DarkBg = Color(0xFF14201C)     // 墨绿调深底（原蓝紫深底与品牌割裂）
val DarkText = Color(0xFFE8EBE9)

// ── 词性/功能色 ──────────────────────────────────
val Highlight = Color(0xFFFFE082)     // 高亮 · 黄色
val RsvpBold = OnSurface              // RSVP 加粗部分

// ── 词频分级颜色（正文文字用，同一色系可读阶梯）──────────
val WordLevelCore = Color(0xFF2E7D63)
val WordLevelIntmd = Color(0xFF0E6B5E)
val WordLevelUpper = Color(0xFF4A635F)
val WordLevelAdv = Color(0xFFB85B00)
val WordLevelRare = Color(0xFFE65100)
val WordLevelUnknown = Color(0xFF6B7268)

// ── 固定常量 ──────────────────────────────────────
val OnPrimary = Color(0xFFFFFFFF)  // 白色文字，在 Primary 背景上使用
val OnBackground = OnSurface        // 背景上的文字，同 OnSurface

// ── 兼容性别名（保持其他文件引用不报错）──────────────

@Deprecated("Use PrimaryDark", ReplaceWith("PrimaryDark"))
val PrimaryVariant = PrimaryDark

@Deprecated("Use Error", ReplaceWith("Error"))
val SecondaryVariant = Error

@Deprecated("Use SurfaceSecondary", ReplaceWith("SurfaceSecondary"))
val SurfaceVariant = SurfaceSecondary
