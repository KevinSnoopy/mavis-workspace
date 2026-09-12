package com.eareyereading.ui.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.eareyereading.util.BookImages

/**
 * 段落插图渲染：`[[IMG:n]]`（EPUB 落盘降采样 JPEG）/ `[[IMG:url]]`（文章/RSS 在线图）。
 *
 * 性能（用户确认"可以展示模糊一些"）：
 *  - Coil ImageRequest 固定 size(720)——按需解码 720px 宽的缩略位图，
 *    原图尺寸再大也不在阅读滚动路径上进出内存；
 *  - EPUB 图导入期已重编码为小 JPEG，文章图按 720 解码，memoryCacheKey 稳定
 *    派生，Coil 磁盘缓存默认开启——单图解码内存 ~≤2MB，翻页/滚动时缓存直接命中。
 *
 * 版式（issue：真实阅读源图片加载时文字错位）：
 *  - 加载中先占位（浅底 + minHeight），段落高度不再从 0 突变到图片高度，
 *    后文不会先"上移占位"再被图片顶下去；
 *  - 失败显示紧凑占位条（图片加载失败），保留段落节奏，后文不塌陷；
 *  - 图片高度封顶在 [ReaderLayout.ImageBlockHeight] 内（含本条目的 8dp 留白）。
 *    分页器是按这个固定高度给插图段记账的，图片原始比例再高也不能让它
 *    反超预算——否则又变成"这一页得滚动才能看完"。超出部分交给
 *    ContentScale.Fit 居中留白。
 */
@androidx.compose.runtime.Composable
internal fun ReaderImageBlock(
    ref: String,
    bookId: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 旧导入数据可能残留 JS 占位 src（resolve 后变成 …/undefined），这些
    // URL 拉回 HTML 错误页 → BitmapFactory null → "图片加载失败"。
    // 渲染期兜底跳过，不渲染图片也不渲染标记文本。
    if (!BookImages.isLoadableImageRef(ref)) return
    val model = remember(ref, bookId) {
        ref.toIntOrNull()
            ?.let { BookImages.localImageFile(context, bookId, it) }
            ?: ref
    }
    // null = 首帧还没回调（视为加载中）：占位先顶住段落高度
    var imageState by remember(ref, bookId) {
        mutableStateOf<AsyncImagePainter.State?>(null)
    }
    val failed = imageState is AsyncImagePainter.State.Error
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = ReaderLayout.ImageBlockVerticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        // 加载中/失败占位：Success 前 minHeight 先占住版式高度，后文不被
        // 突然弹出的图片顶下去（错位感）；失败保留紧凑提示条不塌陷
        if (imageState !is AsyncImagePainter.State.Success) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (failed) 48.dp else 160.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (failed) 0.06f else 0.10f,
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (failed) {
                    Text(
                        text = "图片加载失败",
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current.copy(alpha = 0.55f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(model)
                .size(720)
                // MIUI/HyperOS HWUI image decoder 原生 AImageDecoder_Create 返回
                // "unimplemented"，硬件位图（Bitmap.Config.HARDWARE）虽解码成功但
                // RenderThread 无法上传 GPU 纹理 → 空白/报错。allowHardware(false)
                // 强制软件位图（ARGB_8888），绕开该原生路径。
                .allowHardware(false)
                .memoryCacheKey("reader_img_${bookId}_${ref.takeLast(64)}")
                .crossfade(180)
                .build(),
            contentDescription = "插图",
            contentScale = ContentScale.Fit,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) {
                    android.util.Log.e(
                        "ReaderImage",
                        "image load failed: ref=$ref model=$model",
                        state.result.throwable,
                    )
                }
                imageState = state
            },
            modifier = Modifier
                .fillMaxWidth()
                // 高度封顶 = 分页记账值 - 本条目上下留白
                .heightIn(
                    max = ReaderLayout.ImageBlockHeight -
                        ReaderLayout.ImageBlockVerticalPadding * 2,
                )
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}
