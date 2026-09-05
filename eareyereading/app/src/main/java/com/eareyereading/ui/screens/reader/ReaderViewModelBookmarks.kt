@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import com.eareyereading.data.local.entity.BookmarkEntity
import com.eareyereading.data.local.entity.HighlightEntity
import com.eareyereading.domain.model.*
import com.eareyereading.domain.repository.*
import com.eareyereading.ui.theme.*
import com.eareyereading.util.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * 书签与高亮域：书签切换（互斥串行化）、文本高亮增删、颜色解析。
 */
internal fun ReaderViewModel.parseHighlightColor(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: java.lang.IllegalArgumentException) {
        android.util.Log.w("ReaderViewModel", "Invalid color hex: ${hex}", e)
        Highlight
    }
}

// ── 书签 ─────────────────────────────────
fun ReaderViewModel.toggleBookmark(paragraphIndex: Int) {
    val bookId = currentBookId ?: return
    // 互斥锁串行化：原"取消上一个 job"不是真互斥（cancel 不阻塞、
    // Room 语句中途不响应取消），快速双击仍可能双读 null 各插一条。
    // 数据库侧另有 (bookId, paragraphIndex) 唯一索引 + IGNORE 兜底
    bookmarkToggleJob = viewModelScope.launch {
        // DAO 异常（约束冲突/磁盘满）不拦会崩 app：给用户提示而不是闪退
        try {
            bookmarkMutex.withLock {
                val existing = bookmarkDao.getBookmarkAt(bookId, paragraphIndex)
                if (existing != null) {
                    bookmarkDao.delete(existing)
                } else {
                    bookmarkDao.insert(BookmarkEntity(bookId = bookId, paragraphIndex = paragraphIndex))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "toggleBookmark failed", e)
            showToast("书签保存失败，请重试")
        }
    }
}

fun ReaderViewModel.isBookmarked(paragraphIndex: Int): Boolean {
    return paragraphIndex in _uiState.value.bookmarkedParagraphs
}

// ── 高亮 ─────────────────────────────────
fun ReaderViewModel.addHighlight(
    paragraphIndex: Int,
    startOffset: Int,
    endOffset: Int,
    text: String,
    colorHex: String = "#FFE082",
) {
    val bookId = currentBookId ?: return
    viewModelScope.launch {
        try {
            highlightDao.insert(
                HighlightEntity(
                    bookId = bookId,
                    paragraphIndex = paragraphIndex,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    text = text,
                    color = colorHex,
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "addHighlight failed", e)
            showToast("高亮保存失败，请重试")
        }
    }
}

fun ReaderViewModel.removeHighlight(highlightId: Long) {
    viewModelScope.launch {
        try {
            highlightDao.deleteById(highlightId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "removeHighlight failed", e)
            showToast("高亮删除失败，请重试")
        }
    }
}
