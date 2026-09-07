package com.eareyereading.tts

/**
 * 腾讯云 TTS 音频处理工具：PCM 字节转换与文本分段。
 *
 * 从 [TencentTtsEngine] 按 SRP 抽出：纯数据转换，无引擎状态依赖。
 */
internal object TencentTtsAudioUtils {

    /** PCM 16-bit little-endian bytes → FloatArray（归一化） */
    fun pcmBytesToFloats(bytes: ByteArray): FloatArray {
        val sampleCount = bytes.size / 2
        val floats = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            floats[i] = sample / 32768.0f
        }
        return floats
    }

    /** 按最大字符数分段（尽量在空格/标点处断） */
    fun splitText(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val result = mutableListOf<String>()
        var remaining = text
        while (remaining.length > maxChars) {
            // 在 maxChars 范围内找最后一个空格/标点
            var cut = maxChars
            for (i in maxChars downTo 1) {
                if (remaining[i - 1] == ' ' || remaining[i - 1] == ',' || remaining[i - 1] == '，' ||
                    remaining[i - 1] == '.' || remaining[i - 1] == '。'
                ) {
                    cut = i
                    break
                }
            }
            result.add(remaining.substring(0, cut))
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotEmpty()) result.add(remaining)
        return result
    }
}
