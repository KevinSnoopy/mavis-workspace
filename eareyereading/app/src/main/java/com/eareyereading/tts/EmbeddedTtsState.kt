package com.eareyereading.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 引擎状态与下载进度的可变持有器。
 *
 * 把两个 MutableStateFlow 从 [EmbeddedTtsEngine] 抽出，让引擎主类只保留
 * 合成/播放逻辑，状态读写通过本类统一管理（SRP：状态管理单一职责）。
 * `internal` 可见性让同包的下载/初始化扩展函数能直接写流。
 *
 * 状态类型 [EmbeddedTtsEngine.EngineState] / [EmbeddedTtsEngine.Progress]
 * 仍作为嵌套 sealed class 保留在引擎类内，以兼容外部包已有的
 * `EmbeddedTtsEngine.EngineState.READY` 形式引用（OCP：对扩展开放，对修改封闭）。
 */
internal class EmbeddedTtsStateHolder {
    val _state = MutableStateFlow<EmbeddedTtsEngine.EngineState>(EmbeddedTtsEngine.EngineState.NOT_INITIALIZED)
    val state: StateFlow<EmbeddedTtsEngine.EngineState> = _state.asStateFlow()

    val _downloadProgress = MutableStateFlow<EmbeddedTtsEngine.Progress>(EmbeddedTtsEngine.Progress.Idle)
    val downloadProgress: StateFlow<EmbeddedTtsEngine.Progress> = _downloadProgress.asStateFlow()
}
