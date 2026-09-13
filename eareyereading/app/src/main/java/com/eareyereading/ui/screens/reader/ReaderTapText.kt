package com.eareyereading.ui.screens.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle

// ── 段落点击辅助：把点击位置解析为单词 / 句子 ──────────────
private val WordRegex = Regex("[a-zA-Z]+")
// issue 8.6：同时认 ASCII 与 CJK 句末标点——旧实现只认 [.!?]，
// 双击中文/日文句子只会截到第一个英文句号，整句后半段丢失
private val SentenceEndRegex = Regex("[.!?。！？；]")

/**
 * 根据文本布局把点击/长按位置反查成段落中命中位置的单词。
 * 若点击位置落在标点 / 空白，返回 null。
 */
private fun findWordAtOffset(paragraph: String, offset: Offset, layout: TextLayoutResult): String? {
    val charIndex = layout.getOffsetForPosition(offset)
    return WordRegex.findAll(paragraph).find { it.range.contains(charIndex) }?.value
}

/**
 * 返回包含 [charOffset] 的单词区间（含头不含尾）；落在标点/空白返回 null。
 *
 * 与 [findWordAtOffset] 用同一个分词正则 —— 之前 RSVP 视图栽过跟头：
 * 两处分词规则不一致会导致词数对不上（见 RsvpReadingView 注释）。
 */
internal fun wordRangeAt(paragraph: String, charOffset: Int): IntRange? =
    WordRegex.findAll(paragraph).firstOrNull { it.range.contains(charOffset) }?.range

/**
 * 根据 TextLayoutResult 把点击位置反查成包含该位置的句子。
 * 若无句子边界，返回整段。
 */
private fun findSentenceAtOffset(paragraph: String, offset: Offset, layout: TextLayoutResult): String {
    val charIndex = layout.getOffsetForPosition(offset)
    return findSentenceAtGlobalOffset(paragraph, charIndex)
}

/**
 * 按字符 offset 在段落里找包含该位置的完整句子（跨页切片双击翻译用：
 * 句子可能被分页切开，这里始终在完整段落文本上定位整句，避免拿到残句）。
 */
internal fun findSentenceAtGlobalOffset(paragraph: String, charOffset: Int): String {
    val matches = SentenceEndRegex.findAll(paragraph).toList()
    val start = matches.lastOrNull { it.range.first < charOffset }?.range?.last?.plus(1) ?: 0
    val end = matches.firstOrNull { charOffset < it.range.first }?.range?.first?.plus(1)
        ?: paragraph.length
    return paragraph.substring(start, end).trim()
}

/**
 * 支持「点击单词查释义 / 双击句子翻译 / 长按单词高亮」的段落 Text。
 * 使用 TextLayoutResult 反查命中位置，避免把整段当成一个单词。
 *
 * @param onLongPress 长按回调，参数是命中位置在**完整段落**里的字符偏移；
 *   词区间的解析交给调用方（它才知道自己在渲染整段还是切片）。
 *   null 表示该场景不支持长按。
 */
@androidx.compose.runtime.Composable
internal fun TappableParagraphText(
    text: AnnotatedString,
    paragraph: String,
    onWordClick: (String) -> Unit,
    onSentenceDoubleTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle(),
    // 跨页切片用：双击时把切片内局部 offset 换算到完整段落坐标系找整句
    // （句子可能被分页切开，直接在切片文本上找只会得到残句）
    sentenceLookup: ((localCharOffset: Int) -> String)? = null,
    onLongPress: ((charOffset: Int) -> Unit)? = null,
) {
    // issue 3.7：remember(paragraph) 而非 remember{}——LazyColumn 会对滚出又滚回的
    // 可见 item 复用同一组合实例，不带 key 时会在换段后残留上一段的 TextLayoutResult，
    // 点击仍用旧布局反查坐标 → 段滚出再回来点击失效。
    val textLayoutResult = remember(paragraph) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        modifier = modifier
            .pointerInput(paragraph, onLongPress) {
                detectTapGestures(
                    onTap = { offset ->
                        textLayoutResult.value?.let { layout ->
                            findWordAtOffset(paragraph, offset, layout)?.let { onWordClick(it) }
                        }
                    },
                    onDoubleTap = { offset ->
                        textLayoutResult.value?.let { layout ->
                            val sentence = if (sentenceLookup != null) {
                                sentenceLookup(layout.getOffsetForPosition(offset))
                            } else {
                                findSentenceAtOffset(paragraph, offset, layout)
                            }
                            if (sentence.isNotBlank()) onSentenceDoubleTap(sentence)
                        }
                    },
                    // 长按高亮：Text 未启用 SelectionContainer，不存在与文本选择的冲突；
                    // 子层消费长按后，父层（正文空白 → 切换 chrome）的 tap 探测会因
                    // 事件被消费而取消，不会连带切换工具栏
                    onLongPress = onLongPress?.let { callback ->
                        { offset ->
                            textLayoutResult.value?.let { layout ->
                                callback(layout.getOffsetForPosition(offset))
                            }
                        }
                    },
                )
            },
        style = style,
        onTextLayout = { textLayoutResult.value = it },
    )
}
