package com.eareyereading.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.CoverPattern
import com.eareyereading.ui.theme.CoverPatterns

/**
 * 封面几何纹理层（SPEC §4.10 封面背景库索引 10-14）。
 *
 * ── 背景 ──
 * 封面库 15 个背景分三类：0-9 纯色渐变、10-12 几何图案、13-14 装饰风格。
 * 后 5 个的明确定义是「在渐变基础上叠加图案」，但此前调用方只取了
 * [com.eareyereading.ui.theme.CoverGradients] 的渐变底、从未绘制图案层，
 * 于是 10-14 与纯渐变封面视觉上完全无法区分（其中 12 号的底色是与自身
 * 同色的纯色，渲染出来就是一个没有任何细节的色块）。
 *
 * 本文件补齐图案层；[CoverPatterns] 从「只被赋值」变为真正被消费。
 *
 * ── 绘制约定 ──
 * 全部纹理用半透明白绘制（与生成式封面母题一致）：封面底色恒定是深色渐变，
 * 白描边在任意调色板下都能读出纹理而不压过书名文字。
 * 纹样尺寸全部按画布边长取相对值，因此同一图案在书架缩略图、
 * 选择器磁贴、详情页大封面上比例一致。
 */

/** 纹理墨色：半透明白，压不住白字又能在深底上看清。 */
private val PatternInk = Color.White.copy(alpha = 0.13f)

/** 装饰性高光的中心亮斑强度，比线性纹理略强才有"光晕"感。 */
private val GlowInk = Color.White.copy(alpha = 0.22f)

/** 线宽：1.5dp 在缩略图上不至于糊成一片，在大封面上也不显粗。 */
private val StrokeWidth = 1.5.dp

/** 点阵半径。 */
private val DotRadius = 1.4.dp

/**
 * 按 [pattern] 在当前画布上绘制纹理层。
 * 调用方负责把本函数放在背景渐变之上、文字之下。
 */
internal fun DrawScope.drawCoverPattern(pattern: CoverPattern) {
    val stroke = StrokeWidth.toPx()
    when (pattern) {
        CoverPattern.NONE -> Unit

        // 竖线：8 等分，线落在每格中线，左右留白对称
        CoverPattern.VERTICAL_LINES -> {
            val step = size.width / 8f
            var x = step / 2f
            while (x < size.width) {
                drawLine(PatternInk, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                x += step
            }
        }

        // 横线：行距按宽度取（封面多为竖长比例，按宽度才不会过密）
        CoverPattern.HORIZONTAL_LINES -> {
            val step = size.width / 8f
            var y = step / 2f
            while (y < size.height) {
                drawLine(PatternInk, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
                y += step
            }
        }

        // 点阵：错位排列（奇偶行半格偏移），比正方点阵更像织物
        CoverPattern.DOTS -> {
            val step = size.width / 6f
            val radius = DotRadius.toPx()
            var row = 0
            var y = step / 2f
            while (y < size.height) {
                val offsetX = if (row % 2 == 0) 0f else step / 2f
                var x = step / 2f + offsetX
                while (x < size.width) {
                    drawCircle(PatternInk, radius = radius, center = Offset(x, y))
                    x += step
                }
                y += step
                row++
            }
        }

        // 对角线：45° 斜纹，按对角线长度外扩一圈保证四角铺满
        CoverPattern.DIAGONAL_LINE -> {
            val step = size.width / 6f
            val span = size.width + size.height
            var d = -size.height
            while (d < span) {
                drawLine(
                    PatternInk,
                    Offset(d, 0f),
                    Offset(d + size.height, size.height),
                    strokeWidth = stroke,
                )
                d += step
            }
        }

        // 高光辐射：左上偏心的径向白光，模拟光源打在封面上
        CoverPattern.RADIAL_GLOW -> {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(GlowInk, Color.Transparent),
                    center = Offset(size.width * 0.30f, size.height * 0.22f),
                    radius = size.maxDimension * 0.8f,
                ),
            )
        }
    }
}

/**
 * 取 [coverId] 对应的纹理类型；越界或非图案封面返回 [CoverPattern.NONE]。
 *
 * 收敛取用入口，避免调用方各自写 `getOrNull(id) ?: NONE` 时漏掉越界兜底
 * （封面 ID 来自持久化数据，历史数据可能残留越界值）。
 */
internal fun coverPatternOf(coverId: Int): CoverPattern =
    CoverPatterns.getOrNull(coverId) ?: CoverPattern.NONE
