@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.update

/** 阅读页日志 tag，与全 App 的 logcat 过滤保持一致。 */
internal const val TAG_READER_VM = "ReaderViewModel"

/**
 * TTS 引擎初始化的唯一入口。
 *
 * ── 重构说明（DRY / 单一真实来源）──
 * 「初始化 + 超时/取消/异常三类兜底 + ttsInitialized 上屏」这段逻辑原本在
 * [ReaderViewModelAutoRead]、[ReaderViewModelPlayback]（RSVP、速读、单段朗读
 * 三处）与 [ReaderViewModelBookLoader] 共 5 处逐行重复。任一处改动异常分类
 * 都可能只落到部分播放形态，造成行为漂移——[stopAllPlayback] 注释里记录的
 * "被打断读成读完了"正是这类漂移的后果。现收敛到此处，调用方只关心成败。
 *
 * @param language 目标语言；传 null 表示沿用当前书语言（无书时缺省 "en"）。
 * @return 初始化是否成功；结果同时写入 [ReaderUiState.ttsInitialized]。
 * @throws CancellationException 协程取消原样上抛，绝不吞取消信号。
 *         注意 [TimeoutCancellationException] 是它的子类，必须**优先**捕获，
 *         否则初始化超时会被误判为调用方取消而静默失败。
 */
internal suspend fun ReaderViewModel.initTtsEngine(language: String? = null): Boolean {
    val lang = language ?: _uiState.value.book?.language ?: "en"
    val ok = try {
        ttsHelper.initialize(lang)
    } catch (e: TimeoutCancellationException) {
        android.util.Log.w(TAG_READER_VM, "TTS init timed out", e)
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e(TAG_READER_VM, "TTS init failed", e)
        false
    }
    _uiState.update { it.copy(ttsInitialized = ok) }
    return ok
}
