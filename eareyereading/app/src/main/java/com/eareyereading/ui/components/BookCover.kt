package com.eareyereading.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/** 生成封面用的调色板：按书名哈希稳定取色，同一本书永远是同一张"伪封面"。 */
private val coverPalettes = listOf(
    0xFF8B7355 to 0xFF5F4A35, // 暖棕
    0xFFB08054 to 0xFF7A5A3A, // 赤陶
    0xFF6B8E9E to 0xFF47626F, // 青灰
    0xFF7A9E7E to 0xFF52745A, // 灰绿
    0xFF9E8C5A to 0xFF6B5C38, // 橄榄
    0xFFA87D7D to 0xFF755454, // 豆沙
)

/** 封面插图母题数：山与日 / 海与月 / 丘与鸟 / 松林 / 帆船 / 星夜。 */
private const val MOTIF_COUNT = 6

/**
 * 书籍封面（v2）：三层优先级 ——
 * 1. [coverStyle] >= 0：用户选择的预设封面背景（CoverGradients 渐变 + 书名/作者排版）
 * 2. [coverPath] 非空：EPUB 内嵌封面（Coil 加载）
 * 3. 兜底："书名哈希 → 确定性插图封面"：上半部 Canvas 手绘母题
 *    （山/海/鸟/林/帆/星六选一），下半部书名 + 作者
 *
 * 性能：封面存在性检查交给 Coil（IO 线程解码，失败静默露出下层生成式封面）。
 * 旧实现在组合阶段对每个滚入屏幕的封面执行 File.exists()/length() 磁盘
 * stat——书库快速滚动时每张封面都做主线程 IO，直接掉帧。
 */
@Composable
fun BookCover(
    title: String,
    coverPath: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 10.dp,
    author: String? = null,
    coverStyle: Int = -1,
) {
    // floorMod：abs(hashCode()) 在 Int.MIN_VALUE 时仍为负，旧实现的
    // abs % size 会直接负索引越界崩溃
    val (top, bottom) = remember(title) {
        coverPalettes[Math.floorMod(title.hashCode(), coverPalettes.size)]
    }
    val motif = remember(title) { Math.floorMod(title.hashCode() * 31, MOTIF_COUNT) }
    val gradientBrush = remember(top, bottom) {
        Brush.linearGradient(listOf(Color(top), Color(bottom)))
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(gradientBrush),
    ) {
        if (coverStyle >= 0) {
            // v2：用户选择的预设封面背景（优先级最高，可覆盖内嵌封面）
            PresetCover(
                title = title,
                author = author,
                coverStyle = coverStyle,
                compact = maxHeight < 60.dp,
            )
        } else if (coverPath != null) {
            // allowHardware(false)：MIUI/HyperOS 硬件位图渲染路径原生
            // AImageDecoder_Create 返回 "unimplemented"，导致 HWUI 上传
            // GPU 纹理失败。软件位图绕开该路径。
            val context = LocalContext.current
            AsyncImage(
                model = remember(coverPath) {
                    ImageRequest.Builder(context)
                        .data(File(coverPath))
                        .allowHardware(false)
                        .build()
                },
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            GeneratedCover(
                title = title,
                author = author,
                motif = motif,
                compact = maxHeight < 60.dp,
                coverHeight = maxHeight,
            )
        }
    }
}

/**
 * v2 预设封面：用户从封面背景库选择的渐变 + 书名/作者排版。
 * [compact] 小尺寸（搜索结果等）隐藏作者行。
 */
@Composable
private fun PresetCover(
    title: String,
    author: String?,
    coverStyle: Int,
    compact: Boolean,
) {
    val gradients = com.eareyereading.ui.theme.CoverGradients
    val brush = remember(coverStyle) {
        Brush.linearGradient(gradients[coverStyle.coerceIn(0, gradients.lastIndex)])
    }
    Box(modifier = Modifier
        .fillMaxSize()
        .background(brush)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (compact) 4.dp else 10.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = if (compact) 9.sp else 15.sp,
                lineHeight = if (compact) 11.sp else 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = if (compact) 1 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(modifier = Modifier.weight(1f))
            if (!compact && !author.isNullOrBlank()) {
                Text(
                    text = author,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 生成式插图封面：上 55% 母题插图 + 下 45% 书名/作者排版 + 左侧书脊高光。
 * [compact] 为搜索结果等小尺寸（高度 < 60dp）——隐藏作者行、书名单行。
 */
@Composable
private fun GeneratedCover(
    title: String,
    author: String?,
    motif: Int,
    compact: Boolean,
    coverHeight: androidx.compose.ui.unit.Dp,
) {
    val density = LocalDensity.current
    // 文字随封面高度缩放：小封面不至于挤爆、大封面不至于太小
    val titleSize = with(density) { (coverHeight * (if (compact) 0.14f else 0.11f)).toSp() }
    val authorSize = with(density) { (coverHeight * 0.082f).toSp() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 上半部：确定性母题插图 ──
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f),
            ) {
                drawMotif(motif)
            }
            // ── 下半部：书名 + 作者 ──
            Column(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = titleSize,
                    lineHeight = titleSize * 1.12f,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    textAlign = TextAlign.Center,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!compact && !author.isNullOrBlank() && author != "Unknown") {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = author,
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = authorSize,
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Serif,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        // 书脊高光：左侧一条竖向浅色窄带，给生成式封面一点"实体书"的立体感
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                color = Color.White.copy(alpha = 0.16f),
                size = Size(size.width * 0.06f, size.height),
            )
        }
    }
}

/**
 * 母题绘制：全部用半透明白绘制在深色渐变上，保证任意调色板下可读。
 * 六种母题（按 [motif] 确定性选择）对应不同书名哈希，书架视觉可区分。
 */
private fun DrawScope.drawMotif(motif: Int) {
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
private fun DrawScope.drawMountains(baseY: Float, peaks: List<Offset>, span: Float, alpha: Float) {
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
private fun DrawScope.drawMoon(cx: Float, cy: Float, r: Float) {
    drawCircle(Color.White.copy(alpha = 0.85f), r, Offset(cx, cy))
    drawCircle(
        Color(0xFF3E2F22).copy(alpha = 0.92f),
        r * 0.86f,
        Offset(cx + r * 0.38f, cy - r * 0.18f),
    )
}

/** 海浪线：两段贝塞尔波横贯封面。 */
private fun DrawScope.drawWaves(y: Float, amplitude: Float, alpha: Float) {
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
private fun DrawScope.drawHills(tops: List<Offset>) {
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
private fun DrawScope.drawBird(center: Offset, sizeFactor: Float) {
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
private fun DrawScope.drawPine(cx: Float, baseY: Float, width: Float, alpha: Float) {
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
private fun DrawScope.drawSailboat(hullY: Float, hullW: Float, sailH: Float) {
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
private fun DrawScope.drawStar(center: Offset, r: Float, alpha: Float) {
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
