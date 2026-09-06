package com.eareyereading.tts

/**
 * 句子 → 合成块的贪心装箱器（单一职责：纯文本组织，零 Android 依赖）。
 *
 * 把相邻句子合并成大块（≤[MAX_CHUNK_CHARS]）一次 generate：Kokoro 每次
 * generate 有 ~2s 固定开销，逐句串行 N 句 = N×2s。合并后大块一次
 * generate，native 端按句点切分逐句回调出声（流式），首句合成完就回调，
 * 不用等整块合成完。固定开销从 N 次降到 ceil(N/k) 次。
 *
 * 首块用更小的 [FIRST_BLOCK_MAX_CHARS] 上限：G2P 对整块一次性做
 * （2026-09-06 实测 G2P 占首声 68%），首块减小直接降低首声延迟；
 * 后续块维持 MAX_CHUNK_CHARS——G2P 摊薄到可接受，且减少 generate
 * 调用次数。
 */
internal object TtsBlockChunker {

    /**
     * 单块合成文本最大字符数。
     *
     * 2026-09-05：从 80 提高到 400。Kokoro 不支持 maxNumSentences 并行
     *（native 日志：max_num_sentences != 1 is ignored for Kokoro），
     * 真正的加速是减少 generate 调用次数。400 字符覆盖典型段落，
     * 在 sherpa-onnx 安全范围内（~500 以内稳定）。
     */
    private const val MAX_CHUNK_CHARS = 400

    /** 首块合并上限（字符数），直接决定首声延迟（见类注释）。 */
    private const val FIRST_BLOCK_MAX_CHARS = 120

    /**
     * 合成块：合并后的文本 + 块内最后一个原始句子的索引。
     *
     * 句完成水位用 block 边界：onSentenceDone 回调 block 内最后一个句子的
     * 索引（block 可能含多个原句）。精确的句级高亮需要 native 回调报告
     * 句边界，当前按 block 粒度。
     */
    data class Block(val text: String, val lastSentenceIndex: Int)

    /**
     * @param rawSentences 原始句子列表
     * @param preprocess 模型相关的清洗（Piper 重清洗 / Kokoro 轻清洗），
     *        由调用方按当前模型注入
     */
    fun merge(
        rawSentences: List<String>,
        preprocess: (String) -> String,
    ): List<Block> {
        val blocks = mutableListOf<Block>()
        val currentBuf = StringBuilder()
        var currentLastIdx = -1
        for ((sIdx, raw) in rawSentences.withIndex()) {
            if (raw.isBlank()) continue
            val cleaned = preprocess(raw)
            if (cleaned.isBlank()) continue
            // 首块用 FIRST_BLOCK_MAX_CHARS，后续块用 MAX_CHUNK_CHARS
            val chunkLimit = if (blocks.isEmpty()) FIRST_BLOCK_MAX_CHARS else MAX_CHUNK_CHARS
            if (currentBuf.length + cleaned.length + 1 > chunkLimit && currentBuf.isNotEmpty()) {
                blocks.add(Block(currentBuf.toString().trim(), currentLastIdx))
                currentBuf.clear()
                currentLastIdx = -1
            }
            if (currentBuf.isNotEmpty()) currentBuf.append(' ')
            currentBuf.append(cleaned)
            currentLastIdx = sIdx
        }
        if (currentBuf.isNotEmpty()) {
            blocks.add(Block(currentBuf.toString().trim(), currentLastIdx))
        }
        return blocks
    }
}
