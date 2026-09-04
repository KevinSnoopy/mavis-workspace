package com.eareyereading.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/** 生成封面用的暖调渐变库：按书名哈希稳定取色，同一本书永远是同一张"伪封面"。 */
private val coverPalettes = listOf(
    0xFF8B7355 to 0xFF5F4A35, // 暖棕
    0xFFB08054 to 0xFF7A5A3A, // 赤陶
    0xFF6B8E9E to 0xFF47626F, // 青灰
    0xFF7A9E7E to 0xFF52745A, // 灰绿
    0xFF9E8C5A to 0xFF6B5C38, // 橄榄
    0xFFA87D7D to 0xFF755454, // 豆沙
)

/**
 * 书籍封面：EPUB 内嵌封面（[coverPath] 指向导入时提取的图片文件）优先，
 * 缺失时回退为"书名哈希渐变 + 首字母 + 书脊高光"的生成式封面（Readwise 风格），
 * 替代原先千篇一律的纯色块。
 *
 * 性能：封面存在性检查交给 Coil（IO 线程解码，失败静默露出下层渐变）。
 * 旧实现在组合阶段对每个滚入屏幕的封面执行 File.exists()/length() 磁盘
 * stat——书库快速滚动时每张封面都做主线程 IO，直接掉帧。
 */
@Composable
fun BookCover(
    title: String,
    coverPath: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 10.dp,
) {
    // floorMod：abs(hashCode()) 在 Int.MIN_VALUE 时仍为负，旧实现的
    // abs % size 会直接负索引越界崩溃
    val (top, bottom) = remember(title) {
        coverPalettes[Math.floorMod(title.hashCode(), coverPalettes.size)]
    }
    val gradientBrush = remember(top, bottom) {
        Brush.linearGradient(listOf(Color(top), Color(bottom)))
    }
    val initials = remember(title) { title.take(2).uppercase() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(gradientBrush),
        contentAlignment = Alignment.Center,
    ) {
        if (coverPath != null) {
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
            Text(
                text = initials,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            // 书脊高光：左侧一条竖向浅色窄带，给生成式封面一点"实体书"的立体感
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxSize()
                    .width(3.dp)
                    .background(Color.White.copy(alpha = 0.18f)),
            )
        }
    }
}
