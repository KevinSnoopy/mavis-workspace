@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.eareyereading.ui.screens.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * 书库 UI 状态与消息管理的单一职责控制器。
 *
 * 持有 [LibraryUiState] 的唯一可写源，统一管理结果消息（带自增 eventId 去重）
 * 与并发导入操作的加载态计数。所有拆分出的 internal 协作类通过此控制器
 * 读写 UI 状态，避免多源并发更新产生竞态。
 */
internal class LibraryStateController {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    /** issue 11.16：结果消息自增序列号，保证相同文案也能触发 Snackbar 重新展示 */
    private var messageEventId = 0L

    /** 并发导入操作计数：任一操作先结束不得清掉其它操作的加载态 */
    private val activeImportOps = AtomicInteger(0)

    /** 当前 UI 状态快照（供门面与协作类读取） */
    val current: LibraryUiState get() = _uiState.value

    /** 原子更新 UI 状态 */
    fun update(transform: (LibraryUiState) -> LibraryUiState) {
        _uiState.update(transform)
    }

    /** 每条结果消息统一入口：message 写入 loadingMessage，并递增 eventId 供收集端去重展示 */
    fun setResultMessage(message: String) {
        messageEventId += 1
        _uiState.update { it.copy(loadingMessage = message, messageEventId = messageEventId) }
    }

    /** 导入操作开始：计数 +1 并显示加载态 */
    fun beginImportOp(message: String) {
        activeImportOps.incrementAndGet()
        _uiState.update { it.copy(isLoading = true, loadingMessage = message) }
    }

    /** 导入操作结束：计数 -1，归零时清除加载态 */
    fun endImportOp() {
        if (activeImportOps.decrementAndGet() <= 0) {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /** 清除加载消息（Snackbar 消除入口） */
    fun dismissLoadingMessage() {
        _uiState.update { it.copy(loadingMessage = "") }
    }
}
