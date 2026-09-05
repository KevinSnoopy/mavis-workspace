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

// 对齐 M3 命名（Material 3 ColorScheme.onSurfaceVariant），便于 v2 组件直接引用
val OnSurfaceVariant = OnSurfaceSecondary

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

// ── v2 新增：分类自定义预设色（SPEC §4.9.3）──────────────
// 10 个语义色，对齐品牌色阶，覆盖冷暖两极。
// 白字对全部 10 色对比度均 ≥ 4.5:1（最深 #4F5442 也达 5.2:1）。
val CategoryColorPrimary = Primary                 // #0E6B5E 墨绿
val CategoryColorWarmBrown = Color(0xFFB8854A)     // 暖棕（呼应原 AppIcon）
val CategoryColorIndigo = Color(0xFF1A5276)        // 靛蓝
val CategoryColorPurple = Color(0xFF5B3E8B)        // 紫
val CategoryColorOrange = Color(0xFFB85A1A)        // 赭橙
val CategoryColorBrick = Color(0xFF9B3B3B)         // 砖红
val CategoryColorDeepGreen = Color(0xFF1A6E50)     // 深绿
val CategoryColorBlueSlate = Color(0xFF4A5494)     // 蓝灰
val CategoryColorMagenta = Color(0xFF8B3E8B)       // 品红
val CategoryColorOlive = Color(0xFF4F5442)         // 橄榄

/** 用户可选分类色集合（顺序即默认展示顺序） */
val CategoryPalette = listOf(
    CategoryColorPrimary,
    CategoryColorWarmBrown,
    CategoryColorIndigo,
    CategoryColorPurple,
    CategoryColorOrange,
    CategoryColorBrick,
    CategoryColorDeepGreen,
    CategoryColorBlueSlate,
    CategoryColorMagenta,
    CategoryColorOlive,
)

// ── v2 新增：封面背景库（SPEC §4.10）────────────────────
// 15 个预设封面背景，每条用 Brush.linearGradient 或纯色填充。
// 调用方用 rememberBrushFor(coverId) 取对应 Brush 实例。
// 渐变向量：左上 → 右下（135°，与原型 CSS 一致）

// 1-10 纯色渐变（cv-1..cv-10）
val CoverGradient1 = listOf(Color(0xFF88D3C9), Color(0xFF0E6B5E))
val CoverGradient2 = listOf(Color(0xFFE8C795), Color(0xFFB8854A))
val CoverGradient3 = listOf(Color(0xFF82B4D6), Color(0xFF1A5276))
val CoverGradient4 = listOf(Color(0xFFB091CC), Color(0xFF5B3E8B))
val CoverGradient5 = listOf(Color(0xFFE89B9B), Color(0xFF9B3B3B))
val CoverGradient6 = listOf(Color(0xFF6FC3B7), Color(0xFF1A6E62))
val CoverGradient7 = listOf(Color(0xFFB5C0E0), Color(0xFF4A5494))
val CoverGradient8 = listOf(Color(0xFFF0B27A), Color(0xFFB85A1A))
val CoverGradient9 = listOf(Color(0xFF48C0A0), Color(0xFF1A6E50))
val CoverGradient10 = listOf(Color(0xFFD6A0D6), Color(0xFF8B3E8B))

// 11-13 几何图案（在渐变基础上叠加图案，调用方自行用 drawBehind 绘制纹理）
// 这里给出基础渐变；图案层由 CoverPattern 实现端绘制
val CoverGradient11 = listOf(Color(0xFF88D3C9), Color(0xFF0E6B5E)) // 竖线条纹
val CoverGradient12 = listOf(Color(0xFF1A5276), Color(0xFF1A5276))  // 点阵（纯底）
val CoverGradient13 = listOf(Color(0xFFB85A1A), Color(0xFF6B2E0A))  // 对角线

// 14-15 装饰风格
val CoverGradient14 = listOf(Color(0xFF48C0A0), Color(0xFF1A6E50)) // 横线条纹
val CoverGradient15 = listOf(Color(0xFF5B3E8B), Color(0xFF2A1A4A))  // 高光辐射

/** 封面背景渐变集（按 ID 取用，0-based） */
val CoverGradients = listOf(
    CoverGradient1, CoverGradient2, CoverGradient3, CoverGradient4, CoverGradient5,
    CoverGradient6, CoverGradient7, CoverGradient8, CoverGradient9, CoverGradient10,
    CoverGradient11, CoverGradient12, CoverGradient13,
    CoverGradient14, CoverGradient15,
)

/**
 * 几何图案类型（用于 CoverGradient 11-15 的纹理叠加）
 * - 0..9：纯渐变，无图案
 * - 11: VERTICAL_LINES  竖线
 * - 12: DOTS            点阵
 * - 13: DIAGONAL_LINE   对角线
 * - 14: HORIZONTAL_LINES 横线
 * - 15: RADIAL_GLOW     高光辐射
 */
enum class CoverPattern { NONE, VERTICAL_LINES, DOTS, DIAGONAL_LINE, HORIZONTAL_LINES, RADIAL_GLOW }

val CoverPatterns = listOf(
    CoverPattern.NONE, CoverPattern.NONE, CoverPattern.NONE, CoverPattern.NONE, CoverPattern.NONE,
    CoverPattern.NONE, CoverPattern.NONE, CoverPattern.NONE, CoverPattern.NONE, CoverPattern.NONE,
    CoverPattern.VERTICAL_LINES,    // 11
    CoverPattern.DOTS,             // 12
    CoverPattern.DIAGONAL_LINE,    // 13
    CoverPattern.HORIZONTAL_LINES, // 14
    CoverPattern.RADIAL_GLOW,      // 15
)

