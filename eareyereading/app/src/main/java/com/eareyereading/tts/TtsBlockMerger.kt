package com.eareyereading.tts

/**
 * 把句子列表合并成适合一次 generate 调用的文本块。
 *
 * 从 [EmbeddedTtsEngine.doSpeakQueueLocked] 抽出的纯逻辑（SRP）：
 * Kokoro 每次 generate 有 ~2s 固定开销，逐句串行 N 句 = N×2s。
 * 合并后大块一次 generate，native 端按句点切分逐句回调出声（流式），
 * 首句合成完就回调，不用等整块合成完。固定开销从 N 次降到 ceil(N/k) 次。
 *
 * 首块用更小的 [FIRST_BLOCK_MAX_CHARS] 上限：G2P 对整块一次性做，
 * 首块减小直接降低首声延迟（2026-09-06 实测 G2P 占首声 68%）。
 * 后续块维持 [MAX_CHUNK_CHARS]（400）合并——G2P 摊薄到可接受。
 */
internal object TtsBlockMerger {
    /**
     * 把句子合并成块。
     *
     * @param cleanedSentences 已清洗的非空句子列表
     * @return (块文本, 块内最后一个原句索引) 列表
     */
    fun merge(cleanedSentences: List<Pair<String, Int>>): List<Pair<String, Int>> {
        val blocks = mutableListOf<Pair<String, Int>>()
        val currentBuf = StringBuilder()
        var currentLastIdx = -1
        for ((cleaned, originalIdx) in cleanedSentences) {
            // 首块用 FIRST_BLOCK_MAX_CHARS，后续块用 MAX_CHUNK_CHARS
            val chunkLimit = if (blocks.isEmpty()) FIRST_BLOCK_MAX_CHARS else MAX_CHUNK_CHARS
            if (currentBuf.length + cleaned.length + 1 > chunkLimit && currentBuf.isNotEmpty()) {
                blocks.add(currentBuf.toString().trim() to currentLastIdx)
                currentBuf.clear()
                currentLastIdx = -1
            }
            if (currentBuf.isNotEmpty()) currentBuf.append(' ')
            currentBuf.append(cleaned)
            currentLastIdx = originalIdx
        }
        if (currentBuf.isNotEmpty()) {
            blocks.add(currentBuf.toString().trim() to currentLastIdx)
        }
        return blocks
    }

    /**
     * 单块合成文本最大字符数。
     *
     * 2026-09-05：从 80 提高到 400。Kokoro 不支持 maxNumSentences 并行
     *（native 日志：max_num_sentences != 1 is ignored for Kokoro），
     * 真正的加速是减少 generate 调用次数——每次 generate 有 ~2s 固定开销。
     * 把大块文本一次传给 native，native 端按句点切分逐句回调出声（流式），
     * 首句合成完就回调，不用等整块合成完。400 字符覆盖典型段落，
     * 在 sherpa-onnx 安全范围内（~500 以内稳定）。
     */
    internal const val MAX_CHUNK_CHARS = 400

    /**
     * 首块合并上限（字符数）。
     *
     * 2026-09-06 真机实测（Kokoro int8 / 4 线程）：G2P 对 179 字符整块要 ~7.5s，
     * 占首声延迟 68%。G2P 在 native ConvertTextToTokenIds 里对整块一次性做
     *（espeak-ng + jieba + 3 ruleFST），逐句回调只发生在 G2P 完成后的推理阶段。
     * 首块减小直接降低 G2P 耗时，首声从 ~11s 降到 ~6s。
     */
    internal const val FIRST_BLOCK_MAX_CHARS = 120
}
