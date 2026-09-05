package com.eareyereading.tts

/**
 * [EmbeddedTtsEngine.Progress] → UI 展示的统一映射。
 *
 * Reader 引导弹窗与 Settings 状态卡此前各自复制一份 when 映射（含
 * 解压 ETA 估算的整段重复），文案口径随时可能分叉——收敛为单一出口，
 * 两个调用点只保留各自的"进度条可见性/去重"派生逻辑。
 */
internal data class TtsProgressUi(
    /** 进度值 0..1（Initializing 语义值 0.99、Completed 1.0，与旧口径一致） */
    val fraction: Float,
    /** 阶段文案；Idle 为空串（Reader 侧以其判空收敛进度条） */
    val stageText: String,
    /** 是否处于下载完成后的初始化窗口（UI 显示"初始化中"而非"下载中"） */
    val isInitializing: Boolean,
)

internal fun EmbeddedTtsEngine.Progress.toProgressUi(): TtsProgressUi = when (this) {
    is EmbeddedTtsEngine.Progress.Downloading ->
        TtsProgressUi(fraction, "下载中 ${(fraction * 100).toInt()}%", isInitializing = false)
    is EmbeddedTtsEngine.Progress.Extracting ->
        TtsProgressUi(fraction, formatExtractingStage(), isInitializing = false)
    EmbeddedTtsEngine.Progress.Initializing ->
        TtsProgressUi(0.99f, "正在初始化模型…", isInitializing = true)
    EmbeddedTtsEngine.Progress.Completed ->
        TtsProgressUi(1f, "✅ 已启用", isInitializing = false)
    is EmbeddedTtsEngine.Progress.Failed ->
        TtsProgressUi(0f, "下载失败：$reason", isInitializing = false)
    EmbeddedTtsEngine.Progress.Idle ->
        TtsProgressUi(0f, "", isInitializing = false)
}

/** 是否处于下载/解压/初始化的进行中阶段（进度条应可见）。 */
internal val EmbeddedTtsEngine.Progress.isActiveStage: Boolean
    get() = this is EmbeddedTtsEngine.Progress.Downloading ||
        this is EmbeddedTtsEngine.Progress.Extracting ||
        this is EmbeddedTtsEngine.Progress.Initializing

/** 进度值是否应对 UI 隐藏（无任务/失败/完成后）：Reader 引导弹窗用。 */
internal val EmbeddedTtsEngine.Progress.hidesProgressValue: Boolean
    get() = this is EmbeddedTtsEngine.Progress.Idle ||
        this is EmbeddedTtsEngine.Progress.Failed ||
        this is EmbeddedTtsEngine.Progress.Completed

/**
 * 解压阶段文案：百分比 + ETA 估算 + 当前条目短名（1.3 起按字节推进，
 * 不再预扫文件数——旧文案的 "(2/3) tokens.txt" 已废弃）。
 */
private fun EmbeddedTtsEngine.Progress.Extracting.formatExtractingStage(): String {
    val shortEntry = currentEntryName?.substringAfterLast('/')
    val pct = (fraction * 100).toInt().coerceIn(0, 100)
    val eta = when {
        fraction <= 0.01f || fraction >= 0.99f || elapsedMs <= 0 -> ""
        else -> {
            val remainingMs = (elapsedMs / fraction * (1f - fraction)).toLong()
            if (remainingMs > 0) " · 剩余约${(remainingMs / 1000).coerceAtMost(999)}s" else ""
        }
    }
    return if (shortEntry != null) "解压中 $pct%$eta $shortEntry" else "解压中 $pct%$eta"
}
