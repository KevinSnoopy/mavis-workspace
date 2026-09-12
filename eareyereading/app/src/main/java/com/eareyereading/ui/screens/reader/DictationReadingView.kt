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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.*
import com.eareyereading.util.ClozeWord

/**
 * 听写模式视图：模糊原文只留轮廓，用户听音写字。
 * 从 PracticeReadingViews.kt 抽出的单模式视图（SRP）。
 */

@Composable
fun DictationReadingView(
    clozeWords: List<ClozeWord>,
    answer: String?,
    fontSize: Int,
    textColor: Color,
    paragraph: String,
    onCheckAnswer: (String) -> Boolean,
    onStartDictation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (clozeWords.isEmpty()) {
            // 还没开始听写
            Icon(
                Icons.Default.RecordVoiceOver,
                null,
                modifier = Modifier.size(64.dp),
                tint = LocalReaderAccent.current,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "听写练习",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "点击下方按钮开始，\n系统会随机隐藏段落中的部分单词",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(onClick = onStartDictation) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("开始听写")
            }
        } else {
            // 听写进行中
            Text(
                "请填写划线单词",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = LocalReaderAccent.current,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = buildAnnotatedString {
                    clozeWords.forEach { word ->
                        if (word.isWord) {
                            if (word.isHidden) {
                                // 填空占位：原实现直接把答案单词加下划线原样输出，
                                // 等于把答案写在题面上
                                withStyle(SpanStyle(
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.Bold,
                                    color = LocalReaderAccent.current,
                                )) { append("____") }
                            } else {
                                withStyle(SpanStyle(color = textColor)) { append(word.text) }
                            }
                        } else {
                            withStyle(SpanStyle(color = textColor.copy(alpha = 0.7f))) { append(word.text) }
                        }
                    }
                },
                style = readerParagraphStyle(fontSize, 2f),
            )
            Spacer(modifier = Modifier.height(24.dp))

            // 核对答案：输入与下一个隐藏词匹配才揭示（VM 侧判定并反馈）
            val hiddenWords = clozeWords.filter { it.isHidden }.map { it.text }
            if (hiddenWords.isNotEmpty()) {
                var inputWord by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = inputWord,
                    onValueChange = { inputWord = it },
                    label = { Text("填写答案") },
                    modifier = Modifier.fillMaxWidth(0.8f),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        if (inputWord.isNotBlank() && onCheckAnswer(inputWord)) {
                            inputWord = ""
                        }
                    }) {
                        Text("核对答案")
                    }
                    OutlinedButton(onClick = {
                        inputWord = ""
                        onStartDictation()
                    }) {
                        Text("重新出题")
                    }
                }
                if (answer != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Success.copy(alpha = 0.1f)),
                    ) {
                        Text(
                            "答案: $answer",
                            modifier = Modifier.padding(12.dp),
                            color = Success,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
