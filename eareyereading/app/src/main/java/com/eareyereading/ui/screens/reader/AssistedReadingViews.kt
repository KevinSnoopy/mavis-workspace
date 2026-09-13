package com.eareyereading.ui.screens.reader

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.theme.*
import com.eareyereading.util.WordAnalyzer

/**
 * 辅助阅读视图：RSVP 仿生 / 快速阅读（Speed）/ 分栏对照（Split）/ 成分分析（PosAnalysis）。
 */
// ── RSVP 仿生阅读视图 ──────────────────────────
@Composable
fun RsvpReadingView(
    paragraph: String,
    currentWordIndex: Int,
    fontSize: Int,
    textColor: Color,
    rsvpStrength: Int = 3,
) {
    val wordAnalyzer = remember { WordAnalyzer() }
    // 必须与 ReaderViewModel.getCurrentParagraphWords()（wordAnalyzer.extractWords，
    // 即 [a-zA-Z]+ 分词）使用完全相同的分词器：原实现按空白切分，
    // 遇到 "don't" 这类缩写时两边词数不一致，播放中显示空白且进度条超过 100%
    val words = remember(paragraph) { wordAnalyzer.extractWords(paragraph) }
    val currentWord = words.getOrNull(currentWordIndex) ?: ""

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (currentWord.isNotEmpty()) {
            val (bold, normal) = wordAnalyzer.processRsvpWord(currentWord, rsvpStrength)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = textColor)) {
                        append(bold)
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = textColor.copy(alpha = 0.7f))) {
                        append(normal)
                    }
                },
                fontSize = (fontSize * 3).sp,
                textAlign = TextAlign.Center,
            )
            // 强度指示：纯展示徽章。原实现是 onClick={} 的 AssistChip，
            // TalkBack 会把它读成"没反应的按钮"
            Surface(
                shape = RoundedCornerShape(50),
                color = LocalReaderAccent.current.copy(alpha = 0.1f),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    "强度 $rsvpStrength",
                    color = LocalReaderAccent.current,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        } else {
            Text("点击播放按钮开始", color = textColor.copy(alpha = 0.5f))
        }
        Spacer(modifier = Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = if (words.isNotEmpty()) {
                ((currentWordIndex + 1).toFloat() / words.size).coerceIn(0f, 1f)
            } else 0f,
            modifier = Modifier.width(200.dp),
        )
        if (words.isNotEmpty()) {
            Text(
                text = "${(currentWordIndex + 1).coerceAtMost(words.size)} / ${words.size}",
                color = textColor.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ── 快速阅读视图 ───────────────────────────────
@Composable
fun SpeedReadingView(
    paragraph: String,
    fontSize: Int,
    textColor: Color,
    isPlaying: Boolean,
    currentSentences: List<String> = emptyList(),
    currentSentenceIndex: Int = 0,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isPlaying && currentSentences.isNotEmpty()) {
            // VM 的速读链按句驱动（切句/回调/索引都有），视图按句渲染：
            // 已读句变淡、当前句高亮、未读句正常。原实现播放中只显示一个"●"
            Column(modifier = Modifier.fillMaxWidth()) {
                currentSentences.forEachIndexed { idx, sentence ->
                    val isCurrent = idx == currentSentenceIndex
                    val alpha = when {
                        idx < currentSentenceIndex -> 0.45f
                        isCurrent -> 1f
                        else -> 0.6f
                    }
                    Text(
                        text = sentence,
                        color = textColor.copy(alpha = alpha),
                        fontSize = fontSize.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .then(
                                if (isCurrent) {
                                    Modifier
                                        .background(LocalReaderAccent.current.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                } else Modifier
                            ),
                    )
                }
            }
        } else {
            // 未播放态：此前只截断正文显示，没有任何操作提示，而隔壁的
            // RSVP 有「点击播放按钮开始」——同类模式两种行为，用户不知道
            // 这个模式到底要按哪里。改为正文预览（压淡）+ 明确的第一步指引
            Text(
                text = paragraph.take(120),
                color = textColor.copy(alpha = 0.55f),
                fontSize = fontSize.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "点击上方 ▶ 开始逐句闪现",
                color = LocalReaderAccent.current,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

// ── 分栏对照阅读视图 ──────────────────────────────
