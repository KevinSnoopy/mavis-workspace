package com.eareyereading.ui.screens.reader

import androidx.compose.animation.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.Secondary

/**
 * 阅读页顶部工具栏：返回 / 朗读 / 翻译 / 播放 / 书签 / 模式 / 溢出菜单。
 *
 * 从 ReaderScreen.kt 抽出（SRP）：顶栏动作编排独立成件，ReaderScreen
 * 只负责叠加层的显隐动画与定位。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTopBar(
    title: String,
    backgroundColor: Color,
    textColor: Color,
    isTtsPlaying: Boolean,
    isTranslating: Boolean,
    showTranslation: Boolean,
    isPlaying: Boolean,
    isAutoReading: Boolean,
    isBookmarked: Boolean,
    onBack: () -> Unit,
    onToggleTts: () -> Unit,
    onToggleTranslation: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleBookmark: () -> Unit,
    onShowModeSelector: () -> Unit,
    onShowModeHelp: () -> Unit,
    onToggleAutoRead: () -> Unit,
    onToggleChapterNav: () -> Unit,
    onToggleWordLevelColors: () -> Unit,
    showWordLevelColors: Boolean,
    onToggleKnownWordsHighlight: () -> Unit,
    showKnownWordsHighlight: Boolean,
    onToggleSettings: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        // 跟随阅读主题的背景必须同时指定内容色：
        // 深色主题下默认内容色是深色墨，标题/返回/操作图标会整个看不见
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = backgroundColor,
            titleContentColor = textColor,
            navigationIconContentColor = textColor,
            actionIconContentColor = textColor,
        ),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "返回")
            }
        },
        actions = {
            // TTS
            IconButton(onClick = onToggleTts) {
                Icon(
                    if (isTtsPlaying) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    "朗读"
                )
            }
            // 翻译
            IconButton(onClick = onToggleTranslation) {
                if (isTranslating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = LocalReaderAccent.current,
                    )
                } else {
                    Icon(
                        if (showTranslation) Icons.Default.Translate else Icons.Outlined.Translate,
                        "翻译",
                        tint = if (showTranslation) LocalReaderAccent.current else LocalContentColor.current,
                    )
                }
            }
            // 播放 / 暂停（NORMAL 模式下等价于从当前段开始自动朗读）
            // §4.6.2 关键重设计：播放是主操作——放大到 28dp + 强调色 +
            // 实心图标，与其余 24dp 中性图标拉开视觉权重（原图 6 个图标
            // 权重完全一致，用户无法判断哪个是主操作）
            IconButton(onClick = onTogglePlay) {
                Icon(
                    // isTtsPlaying 也要算播放中：挖空/听写等模式走单段朗读
                    if (isPlaying || isAutoReading || isTtsPlaying)
                        Icons.Default.Pause else Icons.Default.PlayArrow,
                    "播放",
                    tint = LocalReaderAccent.current,
                    modifier = Modifier.size(28.dp),
                )
            }
            // 书签
            IconButton(
                onClick = onToggleBookmark,
            ) {
                Icon(
                    if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    "书签",
                    tint = if (isBookmarked) Secondary else LocalContentColor.current,
                )
            }
            // 阅读模式
            IconButton(onClick = onShowModeSelector) {
                Icon(Icons.Default.MenuBook, "阅读模式")
            }
            // 更多（溢出菜单）
            var showOverflowMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(Icons.Default.MoreVert, "更多")
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false },
                ) {
                    // 放在最前：用户在这个模式里看不懂时，第一反应就是打开
                    // 溢出菜单找帮助，这个入口不该排在第五位
                    DropdownMenuItem(
                        text = { Text("这个模式怎么用") },
                        leadingIcon = { Icon(Icons.Default.HelpOutline, null) },
                        onClick = {
                            showOverflowMenu = false
                            onShowModeHelp()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (isAutoReading) "停止自动朗读" else "自动朗读")
                        },
                        leadingIcon = { Icon(Icons.Default.Headphones, null) },
                        onClick = {
                            showOverflowMenu = false
                            onToggleAutoRead()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("目录") },
                        leadingIcon = { Icon(Icons.Default.List, null) },
                        onClick = {
                            showOverflowMenu = false
                            onToggleChapterNav()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (showWordLevelColors) "关闭词频颜色" else "开启词频颜色"
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.ColorLens, null) },
                        onClick = {
                            showOverflowMenu = false
                            onToggleWordLevelColors()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (showKnownWordsHighlight) "关闭生词高亮" else "开启生词高亮"
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                        onClick = {
                            showOverflowMenu = false
                            onToggleKnownWordsHighlight()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("设置") },
                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                        onClick = {
                            showOverflowMenu = false
                            onToggleSettings()
                        },
                    )
                }
            }
        },
    )
}
