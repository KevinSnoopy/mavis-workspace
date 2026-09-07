package com.eareyereading.ui.screens.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 阅读沉浸态（chrome 显隐）控制器。
 *
 * issue 3.8：点正文空白切换显隐；滚动短暂显示后自动收起；
 * 弹窗/选词/朗读等需要操作时强制常亮。
 *
 * 从 ReaderScreen.kt 抽出（SRP）：把 chromeVisible 状态、自动隐藏计时、
 * NestedScrollConnection 与 forceChrome 联动逻辑收敛到一个稳定持有者，
 * ReaderScreen 只消费 [visible] / [toggle] / [revealTemporarily] /
 * [applyForceChrome] / [scrollReveal]。
 *
 * 进书默认显示：旧值 false 时用户打开书只看到纯正文，顶栏的翻译/阅读
 * 模式入口与底栏快捷设置全部不可见，且"点空白唤出"无任何提示——
 * 用户反馈"仿书页左右翻译等设置丢了"即此。改为进书先展示，
 * 开始滚动阅读后自动收起进沉浸态，可发现性与沉浸感兼得。
 */
@Stable
internal class ReaderChromeController(
    initialVisible: Boolean,
    private val autoHideScope: kotlinx.coroutines.CoroutineScope,
    private val isForceChrome: () -> Boolean,
) {
    var visible: Boolean by mutableStateOf(initialVisible)
        private set

    private var autoHideJob: Job? = null

    /** 切换 chrome 显隐（点空白处轻击）。 */
    fun toggle() {
        visible = !visible
    }

    /**
     * 滚动短暂显示 chrome：每次滚动都重置自动隐藏计时器
     * （scroll-driven reveal）。
     */
    fun revealTemporarily() {
        visible = true
        autoHideJob?.cancel()
        // 自动朗读/弹窗等强状态期间的滚动也被强制显示，但收起交给 force 分支统一控制
        autoHideJob = autoHideScope.launch {
            delay(2500)
            if (!isForceChrome()) visible = false
        }
    }

    /**
     * issue 3.8（保全诉求 #5）：弹窗 / 选词 / TTS 播放 / 自动朗读 / 速读等
     * 需要操作时强制常亮 chrome，且取消自动隐藏；强状态解除后显隐恢复由
     * 滚动/点击驱动。
     */
    fun applyForceChrome() {
        if (isForceChrome()) {
            autoHideJob?.cancel()
            autoHideJob = null
            visible = true
        }
    }

    /**
     * 与本控制器配套的 NestedScrollConnection：滚动增量触发
     * [revealTemporarily]（仅在非 forceChrome 时）。
     */
    val scrollReveal: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!isForceChrome() && available.y != 0f) revealTemporarily()
            return Offset.Zero
        }
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (!isForceChrome() && (consumed.y != 0f || available.y != 0f)) revealTemporarily()
            return Offset.Zero
        }
    }
}

/**
 * 创建并 remember 一个 [ReaderChromeController]。
 *
 * forceChrome 在重组间变化时通过 rememberUpdatedState 始终读到最新值，
 * ReaderChromeController 只 remember 一次不会丢失引用。
 */
@Composable
internal fun rememberReaderChromeController(forceChrome: Boolean): ReaderChromeController {
    val autoHideScope = rememberCoroutineScope()
    // NestedScrollConnection 是 remember 一次创建，只能拿到"创建当下"的引用，
    // 用 rememberUpdatedState 让它始终读到最新的 forceChrome。
    val currentForceChrome by rememberUpdatedState(forceChrome)
    return remember(autoHideScope) {
        ReaderChromeController(
            initialVisible = true,
            autoHideScope = autoHideScope,
            isForceChrome = { currentForceChrome },
        )
    }
}
