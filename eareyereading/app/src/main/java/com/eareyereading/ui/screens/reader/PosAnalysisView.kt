package com.eareyereading.ui.screens.reader

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.*
import com.eareyereading.util.PosTag

/**
 * 词性分析视图：按词性给正文着色并附图例。
 * 从 AssistedReadingViews.kt 抽出的单模式视图（SRP）。
 */

@Composable
fun PosAnalysisView(
    paragraphs: List<String>,
    currentIndex: Int,
    fontSize: Int,
    textColor: Color,
    onWordClick: (String) -> Unit,
    onVisibleParagraphChanged: (Int) -> Unit = {},
) {
    // 非词性着色（非单词 token、图例文字）跟随阅读主题色：
    // 原实现硬编码 app 级浅色 onSurface，深色主题下深底深字不可读
    fun posColor(tag: PosTag): Color = when (tag) {
        PosTag.NOUN -> Info      // 青灰 - 名词
        PosTag.VERB -> Error     // 赤褐 - 动词
        PosTag.ADJECTIVE -> Warning // 暖金 - 形容词
        PosTag.ADVERB -> Primary  // 暖棕 - 副词
        else -> textColor.copy(alpha = 0.85f)
    }

    // LazyColumn 化：整书 eager Column 每次重组都重排版全文；
    // 词性标注串按 (段落, 透明度, 主题色) 缓存，可见窗口外不参与布局
    val listState = rememberLazyListState()
    // 反向同步（与 NORMAL/SPLIT 同款）：本视图无表头项，段落索引即 item 索引。
    // 缺失时用户在成分分析模式里滑多远，退出后进度/统计都停在旧位置。
    // 含对齐闸门（首帧可见区间不得覆盖已恢复进度），见 ReaderViewportSync
    ReaderViewportSync(
        listState = listState,
        currentIndex = currentIndex,
        paragraphCount = paragraphs.size,
        paragraphOffset = 0,
        onVisibleParagraphChanged = onVisibleParagraphChanged,
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(
            items = paragraphs,
            key = { index, _ -> index },
        ) { index, para ->
            val isCurrent = index == currentIndex
            val alpha = if (isCurrent) 1f else 0.5f

            // 词性着色文本（remember 缓存：原实现在组合里裸建，
            // 任何状态变化都重新切词+分类整本书）
            val annotatedText = remember(para, alpha, textColor) {
                buildAnnotatedString {
                    val allMatches = Regex("([a-zA-Z]+)|([^a-zA-Z]+)").findAll(para).toList()
                    allMatches.forEach { match ->
                        val token = match.value
                        if (Regex("^[a-zA-Z]+$").matches(token)) {
                            val word = token.lowercase()
                            val tag = wordPosMap[word] ?: classifyBySuffix(word)
                            val color = posColor(tag).copy(alpha = alpha)
                            withStyle(SpanStyle(color = color)) { append(token) }
                        } else {
                            withStyle(SpanStyle(color = textColor.copy(alpha = alpha * 0.5f))) {
                                append(token)
                            }
                        }
                    }
                }
            }
            TappableParagraphText(
                text = annotatedText,
                paragraph = para,
                onWordClick = onWordClick,
                onSentenceDoubleTap = {},
                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                style = readerParagraphStyle(fontSize),
            )

            if (index < paragraphs.lastIndex) {
                Divider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = textColor.copy(alpha = 0.15f),
                )
            }
        }

        // 底部图例
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PosLegendItem("青灰", Info, "名词")
                PosLegendItem("赤褐", Error, "动词")
                PosLegendItem("暖金", Warning, "形容词")
                PosLegendItem("暖棕", Primary, "副词")
            }
        }
    }
}

@Composable
private fun PosLegendItem(colorName: String, color: Color, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(12.dp),
            shape = CircleShape,
            color = color,
        ) {}
        Spacer(modifier = Modifier.width(4.dp))
        Text("$tag", style = MaterialTheme.typography.labelSmall)
    }
}

// 简单规则后缀分类（供 composable 使用）
private val wordPosMap = mapOf(
    "the" to PosTag.DETERMINER, "a" to PosTag.DETERMINER, "an" to PosTag.DETERMINER,
    "is" to PosTag.VERB, "am" to PosTag.VERB, "are" to PosTag.VERB,
    "was" to PosTag.VERB, "were" to PosTag.VERB, "be" to PosTag.VERB,
    "been" to PosTag.VERB, "being" to PosTag.VERB,
    "do" to PosTag.VERB, "does" to PosTag.VERB, "did" to PosTag.VERB,
    "have" to PosTag.VERB, "has" to PosTag.VERB, "had" to PosTag.VERB,
    "will" to PosTag.VERB, "would" to PosTag.VERB,
    "can" to PosTag.VERB, "could" to PosTag.VERB, "should" to PosTag.VERB,
    "and" to PosTag.CONJUNCTION, "but" to PosTag.CONJUNCTION, "or" to PosTag.CONJUNCTION,
    "in" to PosTag.PREPOSITION, "on" to PosTag.PREPOSITION, "at" to PosTag.PREPOSITION,
    "by" to PosTag.PREPOSITION, "for" to PosTag.PREPOSITION, "with" to PosTag.PREPOSITION,
    "to" to PosTag.PREPOSITION, "of" to PosTag.PREPOSITION, "from" to PosTag.PREPOSITION,
    "not" to PosTag.ADVERB, "no" to PosTag.ADVERB, "very" to PosTag.ADVERB,
    "also" to PosTag.ADVERB, "just" to PosTag.ADVERB, "only" to PosTag.ADVERB,
    "i" to PosTag.PRONOUN, "you" to PosTag.PRONOUN, "he" to PosTag.PRONOUN,
    "she" to PosTag.PRONOUN, "it" to PosTag.PRONOUN, "we" to PosTag.PRONOUN,
    "they" to PosTag.PRONOUN, "this" to PosTag.PRONOUN, "that" to PosTag.PRONOUN,
    "my" to PosTag.PRONOUN, "your" to PosTag.PRONOUN, "his" to PosTag.PRONOUN,
    "our" to PosTag.PRONOUN, "their" to PosTag.PRONOUN,
)

private fun classifyBySuffix(word: String): PosTag {
    val suffixes = listOf(
        "tion" to PosTag.NOUN, "sion" to PosTag.NOUN, "ment" to PosTag.NOUN,
        "ness" to PosTag.NOUN, "ity" to PosTag.NOUN, "ance" to PosTag.NOUN,
        "ence" to PosTag.NOUN, "er" to PosTag.NOUN, "or" to PosTag.NOUN, "ist" to PosTag.NOUN,
        "ing" to PosTag.VERB, "ed" to PosTag.VERB, "ify" to PosTag.VERB,
        "ful" to PosTag.ADJECTIVE, "less" to PosTag.ADJECTIVE,
        "ous" to PosTag.ADJECTIVE, "ive" to PosTag.ADJECTIVE,
        "able" to PosTag.ADJECTIVE, "ible" to PosTag.ADJECTIVE,
        "al" to PosTag.ADJECTIVE, "ical" to PosTag.ADJECTIVE,
        "ly" to PosTag.ADVERB,
    )
    for ((suffix, tag) in suffixes) {
        if (word.length > suffix.length + 2 && word.endsWith(suffix)) return tag
    }
    return PosTag.NOUN
}
