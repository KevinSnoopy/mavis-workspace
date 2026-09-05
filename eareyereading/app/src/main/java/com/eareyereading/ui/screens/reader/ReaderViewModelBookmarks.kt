@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import com.eareyereading.data.local.entity.BookmarkEntity
import com.eareyereading.domain.model.*
import com.eareyereading.domain.repository.*
import com.eareyereading.ui.theme.*
import com.eareyereading.util.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * 书签域：书签切换（互斥串行化）、高亮颜色解析。
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
