package com.eareyereading.ui.screens.reader

import com.eareyereading.ui.components.EareyeBottomSheet
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eareyereading.domain.model.ReadingMode
import com.eareyereading.domain.model.TocEntry
import com.eareyereading.ui.theme.*
import com.eareyereading.util.BookImages
import com.eareyereading.util.CollinsClassifier.WordLevel

/**
 * 阅读页弹窗与抽屉：TTS 引导、模式选择、单词详情、章节导航、选句翻译。
 */
// ── TTS 引擎引导弹窗 ───────────────────────────
@Composable
internal fun TtsInstallDialog(
    prompt: com.eareyereading.ui.screens.reader.TtsInstallPrompt,
    downloadProgress: Float? = null,
    downloadStage: String? = null,
    onAction: (com.eareyereading.ui.screens.reader.TtsInstallAction) -> Unit,
    onDismiss: () -> Unit,
) {
    // 2026-08-30: 系统 TTS 完全下线，对话框只剩"下载 / 启用内置模型"两类 CTA。
    // 此处原有 LocalContext 取值与 unusedCtx 保留位（旧 TtsEngineHelper 调用遗留），
    // 摘掉系统 TTS 后已无任何消费者，一并移除。
    val downloadButton: @Composable () -> Unit = {
        val progress = downloadProgress
        if (progress != null) {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.Text(
                    // 阶段缺失时的 fallback 不再提"请保持网络"：
                    // 该文案只在阶段切换瞬间短暂出现，解压/初始化阶段显示
                    // 网络提示会自相矛盾（issue 1.1）
                    text = downloadStage
                        ?: "正在准备内置 TTS 模型 ${(progress * 100).toInt()}%…",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = androidx.compose.ui.Modifier.padding(top = 4.dp),
                )
            }
        } else {
            androidx.compose.material3.Button(
                onClick = {
                    onAction(com.eareyereading.ui.screens.reader.TtsInstallAction.DownloadEmbeddedTts)
                },
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.Text(
                    text = "🚀 下载内置 TTS（${prompt.embeddedModelDisplayName}，${prompt.embeddedModelSizeText}）"
                )
            }
        }
    }

    val title = if (prompt.embeddedModelDownloaded) "启用内置 TTS" else "下载内置 TTS"

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.material3.Text(
                text = title,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        },
        text = {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.Text(
                    text = if (prompt.embeddedModelDownloaded) {
                        "内置 TTS 模型已下载。点下方按钮启用。"
                    } else {
                        "内置 TTS 完全离线、不依赖系统服务，能保证英文朗读稳定性。" +
                            "模型下载约 ${prompt.embeddedModelSizeText}。"
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Column {
                if (prompt.embeddedModelDownloaded) {
                    androidx.compose.material3.Button(
                        onClick = {
                            onAction(com.eareyereading.ui.screens.reader.TtsInstallAction.EnableEmbeddedTts)
                        },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Text("✅ 启用内置 TTS")
                    }
                } else {
                    downloadButton()
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("取消")
            }
        },
    )
}

// ── 模式选择（底部抽屉：M3 规范的拇指可达区弹层） ────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelectorDialog(
    currentMode: ReadingMode,
    onSelect: (ReadingMode) -> Unit,
    onDismiss: () -> Unit,
) {
    EareyeBottomSheet(
        onDismissRequest = onDismiss,
        shape = EareyeShapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            "选择阅读模式",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.heightIn(max = 480.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(ReadingMode.entries) { _, mode ->
                ListItem(
                    headlineContent = { Text(mode.displayName) },
                    supportingContent = { Text(getModeDescription(mode)) },
                    leadingContent = {
                        RadioButton(
                            selected = mode == currentMode,
                            onClick = { onSelect(mode) },
                        )
                    },
                    modifier = Modifier.clickable { onSelect(mode) },
                )
            }
        }
    }
}

private fun getModeDescription(mode: ReadingMode): String = when (mode) {
    ReadingMode.NORMAL -> "普通阅读，点击单词查释义"
    ReadingMode.RSVP -> "仿生阅读，快速捕捉关键词"
    ReadingMode.SPEED -> "逐句闪现，训练阅读速度"
    ReadingMode.CLOZE -> "挖空练习，隐藏单词填空"
    ReadingMode.FUZZY -> "模糊听读，训练听力复述"
    ReadingMode.DICTATION -> "听写练习，随机隐藏单词听写"
    ReadingMode.SPLIT -> "分栏对照，左原文右译文"
    ReadingMode.BACK_TRANSLATION -> "中译英练习，看译文回译英文"
    ReadingMode.POS_ANALYSIS -> "成分分析，词性着色标注"
}

// ── 单词详情（底部抽屉：点词查义高频操作，拇指可达） ────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordDetailDialog(
    word: String,
    definition: String?,
    wordLevel: WordLevel = WordLevel.UNKNOWN,
    onAddToVocabulary: () -> Unit,
    onSpeak: () -> Unit,
    onDismiss: () -> Unit,
) {
    EareyeBottomSheet(
        onDismissRequest = onDismiss,
        shape = EareyeShapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = onSpeak) {
                    Icon(Icons.Default.PlayCircleFilled, "播放发音", tint = LocalReaderAccent.current)
                }
                if (wordLevel != WordLevel.UNKNOWN) {
                    val badgeColor = when (wordLevel) {
                        WordLevel.CORE -> WordLevelCore
                        WordLevel.INTERMEDIATE -> WordLevelIntmd
                        WordLevel.UPPER_INTERMEDIATE -> WordLevelUpper
                        WordLevel.ADVANCED -> WordLevelAdv
                        WordLevel.RARE -> WordLevelRare
                        WordLevel.UNKNOWN -> Color.Gray
                    }
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                wordLevel.displayName,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = badgeColor.copy(alpha = 0.15f),
                            labelColor = badgeColor,
                        ),
                        border = null,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 超长释义限高 + 可滚：旧实现无约束，长释义把 BottomSheet 顶满屏
            Text(
                text = definition ?: "未找到释义",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            )
            if (wordLevel != WordLevel.UNKNOWN) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = wordLevel.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    onAddToVocabulary()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("加入生词本")
            }
        }
    }
}

// ── 章节目录导航（底部抽屉：长列表在抽屉里更接近拇指） ────────
/**
 * 双模式目录：有章节目录（EPUB/txt 导入时提取）显示章列表，
 * 否则回落到段落导航（URL/RSS 单篇文章等无目录来源）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterNavDialog(
    toc: List<TocEntry>,
    paragraphs: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (toc.isNotEmpty()) {
        ChapterListPane(toc, currentIndex, onSelect, onDismiss)
    } else {
        ParagraphListPane(paragraphs, currentIndex, onSelect, onDismiss)
    }
}

/** 章节目录模式：章名列表，当前章高亮，打开即定位。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterListPane(
    toc: List<TocEntry>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 当前段所属章：段落起点 ≤ 当前段的最后一个章条目
    val currentChapter = toc.indexOfLast { it.paragraphIndex <= currentIndex }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        listState.scrollToItem((currentChapter - 3).coerceAtLeast(0))
    }
    EareyeBottomSheet(
        onDismissRequest = onDismiss,
        shape = EareyeShapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            "目录 · 共 ${toc.size} 章",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = 480.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(toc) { idx, entry ->
                ListItem(
                    headlineContent = {
                        Text(
                            entry.title,
                            maxLines = 2,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    trailingContent = {
                        if (idx == currentChapter) {
                            Icon(Icons.Default.PlayArrow, "当前", tint = LocalReaderAccent.current)
                        }
                    },
                    modifier = Modifier.clickable {
                        onSelect(entry.paragraphIndex)
                        onDismiss()
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (idx == currentChapter)
                            LocalReaderAccent.current.copy(alpha = 0.1f) else Color.Transparent,
                    ),
                )
            }
        }
    }
}

/** 段落导航模式（无目录来源的回落）：原 ChapterNavDialog 段落列表逻辑。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParagraphListPane(
    paragraphs: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 打开即定位到当前段：长书原来停在第 0 段，用户得自己翻找。
    // 当前段落在视口中央（原 top 对齐在长列表里更难感知上下文）
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        listState.scrollToItem((currentIndex - 3).coerceAtLeast(0))
    }
    EareyeBottomSheet(
        onDismissRequest = onDismiss,
        shape = EareyeShapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            "段落导航",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = 480.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(paragraphs) { idx, para ->
                // 显示段落前60字预览（插图标记段显示占位）
                val isImage = BookImages.isImageMarker(para)
                val preview = if (isImage) {
                    "🖼 插图"
                } else {
                    para.take(60).replace("\n", " ") +
                        if (para.length > 60) "…" else ""
                }
                ListItem(
                    headlineContent = {
                        Text(
                            preview,
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    trailingContent = {
                        if (idx == currentIndex) {
                            Icon(Icons.Default.PlayArrow, "当前", tint = LocalReaderAccent.current)
                        }
                    },
                    modifier = Modifier.clickable {
                        onSelect(idx)
                        onDismiss()
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (idx == currentIndex)
                            LocalReaderAccent.current.copy(alpha = 0.1f) else Color.Transparent,
                    ),
                )
            }
        }
    }
}

// ── 选句翻译（底部抽屉） ─────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentenceTranslationDialog(
    sentence: String,
    translation: String?,
    isLoading: Boolean,
    onSpeak: () -> Unit,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    EareyeBottomSheet(
        onDismissRequest = onDismiss,
        shape = EareyeShapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())   // 超长句子+译文不再溢出被裁
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("句子翻译", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onSpeak) {
                    Icon(Icons.Default.PlayCircleFilled, "播放朗读", tint = LocalReaderAccent.current)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = sentence,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("翻译中...")
                }
            } else if (!translation.isNullOrBlank()) {
                Text(
                    text = translation,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalReaderAccent.current,
                )
            } else {
                // issue 8.9：null 语义是"词典/模型都没翻出来"，
                // 与"请求出错"区分开，避免误导用户以为网络故障。
                // 中性色 + 重试入口：旧实现红色文案且无重试，只能关抽屉重双击
                Text(
                    text = "暂无翻译结果",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("重试")
                }
            }
        }
    }
}

// ── 阅读模式说明（顶栏溢出菜单入口）────────────────
/**
 * 单个模式的完整说明。
 *
 * 模式选择器里那行 8～12 字的副标题说明不了任何事：用户切进"模糊听读"
 * 看到一屏糊字，或者切进"仿生阅读"不知道速度调到多少合适，就再也不会
 * 打开第二个模式了。这里给每个模式补齐四件事——
 * 是什么 / 第一步按哪里 / 参数怎么调 / 什么场景用。
 */
internal data class ModeHelp(
    val what: String,
    val steps: List<String>,
    val tip: String,
    val whenToUse: String,
)

internal fun modeHelpOf(mode: ReadingMode): ModeHelp = when (mode) {
    ReadingMode.NORMAL -> ModeHelp(
        what = "常规的连续阅读，正文按段落上下滚动，也可以在设置里改成左右翻页。",
        steps = listOf(
            "点任意单词 → 弹出释义，可一键加入生词本",
            "双击句子 → 查看整句翻译",
            "点正文空白处 → 唤起或收起工具栏",
        ),
        tip = "底栏可以快捷调字号、阅读主题与衬线字体，不用进设置。",
        whenToUse = "日常通读和精读都用它，也是熟悉这个 App 的起点。",
    )
    ReadingMode.RSVP -> ModeHelp(
        what = "单词逐个闪现，每个词的前半段加粗。加粗部分当作视线锚点，" +
            "让眼睛少做回跳——这是它叫「仿生阅读」的原因。",
        steps = listOf(
            "按顶栏的 ▶ 开始播放",
            "速度与加粗强度在「设置 → 仿生阅读」里调",
        ),
        tip = "建议从 300 字/分钟、强度 3 起步。读不下去就降速，别硬扛——" +
            "速度是练出来的，不是设出来的。",
        whenToUse = "泛读、复习读过的材料，或想在通勤时快速过一遍。",
    )
    ReadingMode.SPEED -> ModeHelp(
        what = "逐句闪现：已读的句子变淡，当前句高亮。练的是「不回头」的一次性阅读。",
        steps = listOf(
            "按顶栏的 ▶ 开始播放",
            "跟着高亮往下读，尽量不要回看上一句",
        ),
        tip = "它和仿生阅读共用同一套播放引擎，先摸清自己的舒适速度再来这里加压。",
        whenToUse = "想测出自己真实的阅读速度时。",
    )
    ReadingMode.CLOZE -> ModeHelp(
        what = "按段落随机藏起一部分单词，只留空格。专门检验那些「以为自己会」的词。",
        steps = listOf(
            "点任意 ____ → 单独揭示那一个空",
            "点底部「显示答案」→ 一次全揭",
            "已揭示的词照样可以点开查释义",
        ),
        tip = "先在脑子里补全再揭示，比直接点答案有用得多。",
        whenToUse = "读完一章后的自测，看看哪些词其实没记牢。",
    )
    ReadingMode.FUZZY -> ModeHelp(
        what = "正文被刻意模糊处理，只剩字形轮廓——看不清，只能靠听。",
        steps = listOf(
            "按顶栏的 ▶ 播放，用内置语音朗读本段",
            "跟着声音复述，不必试图辨认模糊的字",
            "想确认原文时切回普通阅读即可",
        ),
        tip = "这是听力练习，试图辨认模糊的文字是练不出效果的——" +
            "它模糊掉就是不想让你读。",
        whenToUse = "练听力与语音复述。",
    )
    ReadingMode.DICTATION -> ModeHelp(
        what = "随机藏起段落里的单词，你听着声音把词拼出来。",
        steps = listOf(
            "点「开始听写」随机出题",
            "把听到的词填进输入框，点「核对答案」",
            "填对会逐个揭示；点「重新出题」换一组",
        ),
        tip = "拿不准时先重听两遍再写，比反复瞎猜快。",
        whenToUse = "检验拼写与听辨，适合精听训练。",
    )
    ReadingMode.SPLIT -> ModeHelp(
        what = "左原文、右译文分栏对照。进入时会自动为全书准备译文。",
        steps = listOf(
            "上下滚动，两侧内容同步推进",
            "首次进入会看到翻译进度浮标，等它跑完即可",
        ),
        tip = "译文质量取决于翻译通道；在「设置 → AI 翻译」配置后可明显改善。",
        whenToUse = "精读长难句，逐句比对理解。",
    )
    ReadingMode.BACK_TRANSLATION -> ModeHelp(
        what = "只给你中文译文，让你自己回译成英文，再对照原文——输出型训练。",
        steps = listOf(
            "先看译文，在心里（或纸上）译回英文",
            "再展开原文对照，找出差在哪",
            "进入时会自动准备全书译文，进度浮标跑完即可开始",
        ),
        tip = "差别往往出在介词和时态上，那正是这个模式要练的地方。",
        whenToUse = "想真正记住表达方式，而不只是读懂意思。",
    )
    ReadingMode.POS_ANALYSIS -> ModeHelp(
        what = "按词性给正文着色（顶部常驻图例是解码表），用来一眼看清句子骨架。",
        steps = listOf(
            "先看顶部图例，确认每种颜色代表什么词性",
            "扫一遍句子，找出动词和主语",
            "点单词仍然可以查释义",
        ),
        tip = "词性是按常见词尾规则推测的，生僻词可能标错——" +
            "把它当阅读辅助，别当成语法工具。",
        whenToUse = "拆解长句结构、快速找主谓宾。",
    )
}

/**
 * 当前模式说明抽屉。
 *
 * @param onChangeMode 底部出口：看完说明直接去换一个模式，不用先关抽屉再找入口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeHelpSheet(
    mode: ReadingMode,
    onChangeMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val help = modeHelpOf(mode)
    val accent = LocalReaderAccent.current
    // 半展开会把底部「换一个模式」压到屏幕边缘：说明类抽屉开屏即全展开
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    EareyeBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = EareyeShapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    mode.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                help.what,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(20.dp))
            ModeHelpSectionTitle("怎么用")
            help.steps.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        step,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            ModeHelpSectionTitle("小技巧")
            Text(
                help.tip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(18.dp))
            ModeHelpSectionTitle("什么时候用")
            Text(
                help.whenToUse,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(
                onClick = {
                    onChangeMode()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("换一个模式")
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ModeHelpSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = LocalReaderAccent.current,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

// ── 高亮抽屉（长按选词 → 选色 / 移除）────────────────

/**
 * 可选的高亮底色：hex 写进数据库（`HighlightEntity.color`），
 * Color 用于色板展示 —— 两边必须来自同一份定义，否则存进去的和
 * 用户看到的会不一致。默认第一个（黄）与 addHighlight 的历史默认一致。
 */
private val HIGHLIGHT_COLOR_CHOICES: List<Pair<String, Color>> = listOf(
    "#FFE082" to Color(0xFFFFE082),
    "#A6F2D8" to Color(0xFFA6F2D8),
    "#F5C4B3" to Color(0xFFF5C4B3),
    "#B5D4F4" to Color(0xFFB5D4F4),
)

/**
 * 长按选词后的高亮抽屉。
 *
 * - 该词尚无高亮：展示色板，点色块直接落库
 * - 该词已有高亮：不再提供色板（对同一个词反复叠色只会越涂越花），
 *   改为提供「移除高亮」出口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightSheet(
    draft: HighlightDraft,
    onPickColor: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = LocalReaderAccent.current
    EareyeBottomSheet(
        onDismissRequest = onDismiss,
        shape = EareyeShapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FormatColorFill,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (draft.existingId == null) "高亮这个词" else "这条高亮",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "「${draft.text}」",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )

            if (draft.existingId == null) {
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    "选个颜色",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HIGHLIGHT_COLOR_CHOICES.forEach { (hex, color) ->
                        HighlightColorSwatch(color = color, onPick = { onPickColor(hex) })
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    "这个词已经有高亮了，可以移除后重新选色。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        onRemove()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("移除高亮")
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun HighlightColorSwatch(color: Color, onPick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {}
}
