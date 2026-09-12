package com.eareyereading.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * 生成式封面的手绘母题（DrawScope 绘制工具集）。
 *
 * 从 `BookCover.kt` 抽出的单一职责模块（SRP）：封面组件负责"用哪张封面、
 * 怎么排版"，本文件只负责"母题怎么画"。全部为纯绘制函数——无状态、
 * 不依赖组件内其他成员，输入相同的尺寸/参数得到相同的像素结果。
 *
 * 母题共 [MOTIF_COUNT][com.eareyereading.ui.components.BookCover] 种：
 * 山与日 / 海与月 / 丘与鸟 / 松林 / 帆船 / 星夜。
 */

internal fun DrawScope.drawMotif(motif: Int) {
    val w = size.width
    val h = size.height
    when (motif) {
        0 -> { // 山与日：日轮 + 双峰雪山
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = w * 0.14f,
                center = Offset(w * 0.68f, h * 0.30f),
            )
            drawMountains(
                baseY = h * 0.92f,
                peaks = listOf(
                    Offset(w * 0.22f, h * 0.28f),
                    Offset(w * 0.52f, h * 0.52f),
                ),
                span = w * 0.62f,
                alpha = 0.30f,
            )
            drawMountains(
                baseY = h * 0.98f,
                peaks = listOf(Offset(w * 0.80f, h * 0.50f)),
                span = w * 0.55f,
                alpha = 0.18f,
            )
        }
        1 -> { // 海与月：弯月 + 三道海浪
            drawMoon(w * 0.70f, h * 0.26f, w * 0.16f)
            drawWaves(h * 0.66f, w * 0.18f, alpha = 0.55f)
            drawWaves(h * 0.80f, w * 0.14f, alpha = 0.35f)
            drawWaves(h * 0.92f, w * 0.10f, alpha = 0.22f)
        }
        2 -> { // 丘与鸟：起伏丘陵 + 三只飞鸟
            drawHills(listOf(Offset(w * 0.24f, h * 0.62f), Offset(w * 0.76f, h * 0.70f)))
            drawBird(Offset(w * 0.30f, h * 0.26f), w * 0.07f)
            drawBird(Offset(w * 0.48f, h * 0.18f), w * 0.05f)
            drawBird(Offset(w * 0.62f, h * 0.30f), w * 0.06f)
        }
        3 -> { // 松林：三棵层叠松树
            drawPine(w * 0.26f, h * 0.95f, w * 0.30f, alpha = 0.20f)
            drawPine(w * 0.56f, h * 0.98f, w * 0.40f, alpha = 0.30f)
            drawPine(w * 0.82f, h * 0.92f, w * 0.24f, alpha = 0.16f)
        }
        4 -> { // 帆船：双帆小船 + 水线
            drawSailboat(
                hullY = h * 0.82f,
                hullW = w * 0.52f,
                sailH = h * 0.52f,
            )
            drawWaves(h * 0.92f, w * 0.12f, alpha = 0.35f)
        }
        else -> { // 星夜：大星 + 疏星 + 地平线
            drawStar(Offset(w * 0.62f, h * 0.30f), w * 0.15f, alpha = 0.9f)
            drawStar(Offset(w * 0.22f, h * 0.22f), w * 0.06f, alpha = 0.55f)
            drawStar(Offset(w * 0.40f, h * 0.44f), w * 0.04f, alpha = 0.40f)
            drawStar(Offset(w * 0.82f, h * 0.52f), w * 0.05f, alpha = 0.45f)
            drawLine(
                color = Color.White.copy(alpha = 0.25f),
                start = Offset(w * 0.10f, h * 0.86f),
                end = Offset(w * 0.90f, h * 0.86f),
                strokeWidth = w * 0.02f,
            )
        }
    }
}

/** 山体：peak 顶点向下展开的三角形，底部横跨 [span]。 */
internal fun DrawScope.drawMountains(baseY: Float, peaks: List<Offset>, span: Float, alpha: Float) {
    peaks.forEach { peak ->
        val half = span / 2f
        val path = Path().apply {
            moveTo(peak.x - half, baseY)
            lineTo(peak.x, peak.y)
            lineTo(peak.x + half, baseY)
            close()
        }
        drawPath(path, Color.White.copy(alpha = alpha))
        // 雪顶：峰顶往下 28% 处一道浅色横线
        val snowY = peak.y + (baseY - peak.y) * 0.28f
        drawLine(
            color = Color.White.copy(alpha = (alpha + 0.35f).coerceAtMost(1f)),
            start = Offset(peak.x - half * 0.30f, snowY),
            end = Offset(peak.x + half * 0.30f, snowY),
            strokeWidth = size.width * 0.02f,
        )
    }
}

/** 弯月：外圆实心 + 深色圆偏移遮出月牙（渐变底色够深，视觉上成立）。 */
internal fun DrawScope.drawMoon(cx: Float, cy: Float, r: Float) {
    drawCircle(Color.White.copy(alpha = 0.85f), r, Offset(cx, cy))
    drawCircle(
        Color(0xFF3E2F22).copy(alpha = 0.92f),
        r * 0.86f,
        Offset(cx + r * 0.38f, cy - r * 0.18f),
    )
}

/** 海浪线：两段贝塞尔波横贯封面。 */
internal fun DrawScope.drawWaves(y: Float, amplitude: Float, alpha: Float) {
    val w = size.width
    val path = Path().apply {
        moveTo(-w * 0.05f, y)
        cubicTo(w * 0.15f, y - amplitude, w * 0.25f, y + amplitude, w * 0.45f, y)
        cubicTo(w * 0.65f, y - amplitude, w * 0.75f, y + amplitude, w * 1.05f, y)
    }
    drawPath(
        path,
        Color.White.copy(alpha = alpha),
        style = Stroke(width = w * 0.025f),
    )
}

/** 起伏丘陵：两段大弧填底。 */
internal fun DrawScope.drawHills(tops: List<Offset>) {
    val w = size.width
    val h = size.height
    tops.forEachIndexed { i, top ->
        val path = Path().apply {
            moveTo(0f, h)
            quadraticBezierTo(top.x, top.y - h * 0.10f, w, h * (0.75f + i * 0.12f))
            lineTo(w, h)
            close()
        }
        drawPath(path, Color.White.copy(alpha = 0.18f + i * 0.08f))
    }
}

/** 飞鸟："v" 两笔。 */
internal fun DrawScope.drawBird(center: Offset, sizeFactor: Float) {
    val strokeW = sizeFactor * 0.18f
    val color = Color.White.copy(alpha = 0.8f)
    drawLine(color, Offset(center.x - sizeFactor, center.y), center, strokeWidth = strokeW)
    drawLine(
        color,
        center,
        Offset(center.x + sizeFactor, center.y - sizeFactor * 0.2f),
        strokeWidth = strokeW,
    )
}

/** 松树：三层三角 + 短干。 */
internal fun DrawScope.drawPine(cx: Float, baseY: Float, width: Float, alpha: Float) {
    val color = Color.White.copy(alpha = alpha)
    val h = width * 1.3f
    repeat(3) { layer ->
        val topY = baseY - h * (0.30f + layer * 0.32f)
        val layerW = width * (0.40f + layer * 0.30f)
        val path = Path().apply {
            moveTo(cx, topY)
            lineTo(cx - layerW / 2f, topY + h * 0.38f)
            lineTo(cx + layerW / 2f, topY + h * 0.38f)
            close()
        }
        drawPath(path, color)
    }
    drawRect(
        color = color,
        topLeft = Offset(cx - width * 0.04f, baseY - h * 0.14f),
        size = Size(width * 0.08f, h * 0.16f),
    )
}

/** 帆船：船身梯形 + 桅杆 + 主帆/前帆三角。 */
internal fun DrawScope.drawSailboat(hullY: Float, hullW: Float, sailH: Float) {
    val w = size.width
    val cx = w * 0.5f
    val hullDepth = hullW * 0.22f
    val hull = Path().apply {
        moveTo(cx - hullW / 2f, hullY)
        lineTo(cx + hullW / 2f, hullY)
        lineTo(cx + hullW * 0.30f, hullY + hullDepth)
        lineTo(cx - hullW * 0.30f, hullY + hullDepth)
        close()
    }
    drawPath(hull, Color.White.copy(alpha = 0.75f))
    drawLine(
        Color.White.copy(alpha = 0.85f),
        Offset(cx, hullY),
        Offset(cx, hullY - sailH),
        strokeWidth = w * 0.02f,
    )
    // 主帆（左）
    val mainSail = Path().apply {
        moveTo(cx - w * 0.015f, hullY - sailH)
        lineTo(cx - w * 0.015f, hullY - sailH * 0.08f)
        lineTo(cx - hullW * 0.48f, hullY - sailH * 0.08f)
        close()
    }
    drawPath(mainSail, Color.White.copy(alpha = 0.55f))
    // 前帆（右）
    val jib = Path().apply {
        moveTo(cx + w * 0.015f, hullY - sailH * 0.92f)
        lineTo(cx + w * 0.015f, hullY - sailH * 0.08f)
        lineTo(cx + hullW * 0.40f, hullY - sailH * 0.08f)
        close()
    }
    drawPath(jib, Color.White.copy(alpha = 0.38f))
}

/** 四角星：四段贝塞尔拼出的凸菱形星。 */
internal fun DrawScope.drawStar(center: Offset, r: Float, alpha: Float) {
    val path = Path().apply {
        moveTo(center.x, center.y - r)
        quadraticBezierTo(center.x + r * 0.18f, center.y - r * 0.18f, center.x + r, center.y)
        quadraticBezierTo(center.x + r * 0.18f, center.y + r * 0.18f, center.x, center.y + r)
        quadraticBezierTo(center.x - r * 0.18f, center.y + r * 0.18f, center.x - r, center.y)
        quadraticBezierTo(center.x - r * 0.18f, center.y - r * 0.18f, center.x, center.y - r)
        close()
    }
    drawPath(path, Color.White.copy(alpha = alpha))
}
