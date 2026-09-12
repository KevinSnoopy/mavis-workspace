package com.eareyereading.ui.screens.reader

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.theme.*
import com.eareyereading.util.ClozeWord

/**
 * 练习类阅读视图：挖空（Cloze）/ 模糊（Fuzzy）/ 回译（BackTranslation）/ 听写（Dictation）。
 */
// ── 挖空练习视图 ────────────────────────────────
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ClozeReadingView(
    clozeWords: List<ClozeWord>,
    answer: String?,
    fontSize: Int,
    textColor: Color,
    showTranslation: Boolean,
    translationAlpha: Float = 0.85f,
    currentTranslation: String?,
    onReveal: () -> Unit,
    onWordClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // FlowRow 行内排布：原实现把每个词放进纵向 Column，
        // 一段话被渲染成一列单词，完全不可读。
        // 揭示是渐进的：VM 每按一次"显示答案"清除一个隐藏词标记
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            clozeWords.forEach { clozeWord ->
                if (clozeWord.isWord) {
                    if (clozeWord.isHidden) {
                        Text(
                            text = "____",
                            color = textColor.copy(alpha = 0.5f),
                            fontSize = fontSize.sp,
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                // 挖空词本身可点击揭示，扩大可点区并补上此前缺的
                                // 点击入口（issue 3.5）
                                .clickable { onReveal() },
                        )
                    } else {
                        Text(
                            text = clozeWord.text,
                            color = textColor,
                            fontSize = fontSize.sp,
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .clickable { onWordClick(clozeWord.text) },
                        )
                    }
                } else {
                    // 分隔符 token 原样输出，保证词间距/标点自然
                    Text(
                        text = clozeWord.text,
                        color = textColor.copy(alpha = 0.7f),
                        fontSize = fontSize.sp,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        val remainingHidden = clozeWords.count { it.isWord && it.isHidden }
        FilledTonalButton(
            onClick = onReveal,
            enabled = remainingHidden > 0,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Default.Visibility, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (remainingHidden > 0) "显示答案（剩 $remainingHidden 空）" else "已全部揭示")
        }

        if (showTranslation && !currentTranslation.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = LocalReaderAccent.current.copy(alpha = 0.1f)),
            ) {
                Text(
                    text = currentTranslation,
                    modifier = Modifier.padding(12.dp),
                    color = LocalReaderAccent.current.copy(alpha = translationAlpha),
                    fontSize = (fontSize - 2).sp,
                )
            }
        }
    }
}

// ── 模糊阅读视图 ────────────────────────────────
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FuzzyReadingView(
    fuzzyWords: List<com.eareyereading.util.FuzzyWord>,
    fontSize: Int,
    textColor: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // 同挖空视图：FlowRow 行内排布，不再一词一行
        FlowRow(modifier = Modifier.fillMaxWidth()) {
            fuzzyWords.forEach { fuzzyWord ->
                Text(
                    text = fuzzyWord.text,
                    color = if (fuzzyWord.isBlurred) textColor.copy(alpha = 0.15f) else textColor,
                    fontSize = fontSize.sp,
                    modifier = if (fuzzyWord.isBlurred) Modifier.blur(8.dp) else Modifier,
                )
            }
        }
    }
}

// ── 中译英回译视图 ───────────────────────────────
