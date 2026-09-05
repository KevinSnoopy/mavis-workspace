package com.eareyereading.ui.screens.reader

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.theme.*

/**
 * 阅读器设置抽屉及其行组件（滑杆行/开关行/分组标题）。
 */
// ── 阅读器设置对话框 ────────────────────────────

// 设置弹窗文案表：提到顶层，避免每次重组都重新分配
private val RSVP_STRENGTH_LABELS = listOf("30%", "40%", "50%", "60%", "70%")

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsDialog(
    fontSize: Int,
    rsvpSpeed: Int,
    rsvpStrength: Int,
    translationAlpha: Float,
    showWordLevelColors: Boolean,
    showKnownWordsHighlight: Boolean,
    pageMode: Boolean = false,
    onFontSizeChange: (Int) -> Unit,
    onSpeedChange: (Int) -> Unit,
    onStrengthChange: (Int) -> Unit,
    onTranslationAlphaChange: (Float) -> Unit,
    onWordLevelColorsToggle: () -> Unit,
    onKnownWordsHighlightToggle: () -> Unit,
    onTogglePageMode: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "阅读设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            // ── 排版 ──────────────────────────────
            SettingsGroupLabel("排版")
            // 这些值来自 DataStore 持久化，历史版本可能写入越界值；
            // Slider 要求 value 在 valueRange 内，列表索引也要收敛，否则抽屉一开就崩
            SettingSliderRow(
                label = "字体大小",
                valueText = "${fontSize}sp",
                value = fontSize.toFloat().coerceIn(12f, 32f),
                onValueChange = { onFontSizeChange(it.toInt()) },
                valueRange = 12f..32f,
                steps = 19,
                preview = SliderPreview.FONT_SIZE,
            )
            SwitchSettingRow(
                title = "左右翻页",
                subtitle = if (pageMode) "仿书页横向翻页阅读" else "当前：上下滚动阅读",
                checked = pageMode,
                onToggle = onTogglePageMode,
            )

            // ── 仿生阅读 ──────────────────────────
            SettingsGroupLabel("仿生阅读")
            // steps=13：50 字/分钟一档，避免逐像素连续值带来的无意义精度抖动
            SettingSliderRow(
                label = "RSVP 速度",
                valueText = "$rsvpSpeed 字/分钟",
                value = rsvpSpeed.toFloat().coerceIn(100f, 800f),
                onValueChange = { onSpeedChange(it.toInt()) },
                valueRange = 100f..800f,
                steps = 13,
                preview = SliderPreview.RSVP_SPEED,
            )
            SettingSliderRow(
                label = "加粗强度",
                valueText = RSVP_STRENGTH_LABELS[rsvpStrength.coerceIn(1, 5) - 1],
                value = rsvpStrength.toFloat().coerceIn(1f, 5f),
                onValueChange = { onStrengthChange(it.toInt()) },
                valueRange = 1f..5f,
                steps = 3,
                preview = SliderPreview.BOLD,
            )

            // ── 翻译 ──────────────────────────────
            SettingsGroupLabel("翻译")
            SettingSliderRow(
                label = "译文透明度",
                valueText = "${(translationAlpha * 100).toInt()}%",
                value = translationAlpha,
                onValueChange = onTranslationAlphaChange,
                valueRange = 0.3f..1f,
                // 5% 一档（30%→100% 共 14 档），thumb 吸附刻度点
                steps = 13,
                preview = SliderPreview.ALPHA,
            )

            // ── 词色 ──────────────────────────────
            SettingsGroupLabel("词色")
            SwitchSettingRow(
                title = "Collins 词频色彩",
                checked = showWordLevelColors,
                onToggle = onWordLevelColorsToggle,
            )
            SwitchSettingRow(
                title = "生词高亮",
                checked = showKnownWordsHighlight,
                onToggle = onKnownWordsHighlightToggle,
            )
            // Collins 词级颜色图例：纯展示徽章（原 AssistChip 空点击有水波纹，
            // 且 weight 等分在窄屏会挤压截断），FlowRow 自动换行。
            // §4.6.1：等级 chip 用难度色 50% 透明底 + 药丸圆角 + 对应 on 色
            if (showWordLevelColors) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        L1 to OnSurface,
                        L2 to OnSurface,
                        L3 to OnSurface,
                        L4 to OnPrimaryContainer,
                        L5 to OnPrimaryContainer,
                    ).forEachIndexed { index, (bg, fg) ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = bg.copy(alpha = 0.5f),
                        ) {
                            Text(
                                listOf("核心", "进阶", "提高", "高阶", "学术")[index],
                                style = MaterialTheme.typography.labelMedium,
                                color = fg,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/** 设置弹窗分组小标题：视觉分节，降低四滑杆三开关平铺的密度 */
@Composable
private fun SettingsGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = LocalReaderAccent.current,
        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
    )
}

/**
 * 滑块预览条类型（§4.6.3「三个滑块视觉完全一致」修复）：
 * 每个滑块上方有实时预览，用预览区分滑块用途——
 * Readwise Reader 的实时预览 + Kindle Aa 菜单结合。
 */
private enum class SliderPreview {
    /** 无预览 */
    NONE,
    /** 字号滑块："Aa" 样本（当前字号） */
    FONT_SIZE,
    /** RSVP 滑块：箭头密度（当前速度） */
    RSVP_SPEED,
    /** 加粗滑块：加粗 "Aa" */
    BOLD,
    /** 译文透明度滑块：带透明度的方块 */
    ALPHA,
}

/**
 * 「标签左 + 当前值右 + 预览条 + 滑块 + 刻度点」滑杆行（改版B）。
 *
 * - 拖动时视线不必上移找数值；
 * - 上方预览条实时反映该滑块的效果（区分四个用途相同的滑块）；
 * - 下方等距刻度点 + 当前值位置放大高亮（12×12dp primary，
 *   300ms spring 弹性），thumb 松手自动吸附最近刻度（Slider steps）。
 */
@Composable
private fun SettingSliderRow(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    preview: SliderPreview = SliderPreview.NONE,
) {
    val accent = LocalReaderAccent.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
        // 预览条：居中 28dp 高，内容随当前值实时变化
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (preview) {
                SliderPreview.FONT_SIZE -> Text(
                    "Aa",
                    fontSize = value.coerceIn(12f, 26f).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SliderPreview.RSVP_SPEED -> Text(
                    // 箭头密度随速度增加：100-200 一档 / 300-600 两档 / 700+ 三档
                    buildString {
                        repeat(((value - 100f) / 300f).toInt().coerceIn(0, 2) + 1) {
                            if (it > 0) append(" ")
                            append("→")
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SliderPreview.BOLD -> Text(
                    "Aa",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SliderPreview.ALPHA -> Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent.copy(alpha = value.coerceIn(0f, 1f))),
                )
                SliderPreview.NONE -> Unit
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = MaterialTheme.colorScheme.outline,
            ),
        )
        // 刻度点行：steps>0 时显示 steps+2 个等距 dot（§5.2 带刻度滑块）。
        // 当前值 dot 放大为 12dp primary，弹性缩放（spring ~300ms）
        if (steps > 0) {
            val tickCount = steps + 2
            val fraction = (
                (value - valueRange.start) /
                    (valueRange.endInclusive - valueRange.start)
                ).coerceIn(0f, 1f)
            val currentIndex = Math.round(fraction * (tickCount - 1))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 与 M3 Slider thumb 行程对齐：两端各留半个 thumb 宽
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(tickCount) { index ->
                    val isCurrent = index == currentIndex
                    val dotSize by animateDpAsState(
                        targetValue = if (isCurrent) 12.dp else 4.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "tickSize",
                    )
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .clip(CircleShape)
                            .background(
                                if (isCurrent) accent else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/**
 * 开关行：整行可点（toggleable + Role.Switch）。旧实现只有右侧 Switch
 * 可点，点文字无反应——移动端高发误操作点。
 */
@Composable
private fun SwitchSettingRow(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = { onToggle() },
            )
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
