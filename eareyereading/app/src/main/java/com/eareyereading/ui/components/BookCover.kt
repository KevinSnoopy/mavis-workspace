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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.eareyereading.ui.theme.CoverPattern
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
 * v2 预设封面：用户从封面背景库选择的渐变 + 几何纹理 + 书名/作者排版。
 * [compact] 小尺寸（搜索结果等）隐藏作者行，并跳过纹理绘制。
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
    val pattern = remember(coverStyle) { coverPatternOf(coverStyle) }
    Box(modifier = Modifier
        .fillMaxSize()
        .background(brush)) {
        // 几何纹理层（封面库索引 10-14 才有）：小尺寸下纹理糊成一团，
        // 且每张缩略图多一次绘制调用，故 compact 时跳过
        if (!compact && pattern != CoverPattern.NONE) {
            Canvas(modifier = Modifier.matchParentSize()) { drawCoverPattern(pattern) }
        }
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

// 生成式封面的手绘母题（DrawScope 绘制函数）见 BookCoverMotifs.kt
