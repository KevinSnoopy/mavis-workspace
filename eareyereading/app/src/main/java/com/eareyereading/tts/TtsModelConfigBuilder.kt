package com.eareyereading.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * sherpa-onnx [OfflineTts] 实例的配置构造。
 *
 * 从 [EmbeddedTtsEngine.buildOfflineTts] 抽出的单一职责类（SRP）：
 * 引擎主类不再关心模型文件查找、VITS/Kokoro 配置分支、线程数选择等
 * 配置构造细节，只通过本类获取构造好的 [OfflineTts] 实例。
 *
 * 纯构造逻辑，无状态：每次 [build] 调用独立读取 [EmbeddedTtsModelFiles]
 * 的路径信息，构造完即丢弃。
 *
 * @param modelFiles 模型文件路径提供者
 */
internal class TtsModelConfigBuilder(
    private val modelFiles: EmbeddedTtsModelFiles,
) {
    /**
     * 构造 sherpa-onnx [OfflineTts] 实例。
     * Kokoro 与 VITS 走不同的 [OfflineTtsModelConfig] 分支。
     *
     * @param modelInfo 要加载的模型信息
     * @param numThreads VITS 模型推理线程数
     * @param numThreadsKokoro Kokoro 模型推理线程数
     */
    fun build(
        modelInfo: ModelInfo,
        numThreads: Int = NUM_THREADS,
        numThreadsKokoro: Int = NUM_THREADS_KOKORO,
    ): OfflineTts {
        val dir = modelFiles.rootDir()
        val modelDir = modelFiles.modelDir(modelInfo.id)

        // 通过文件名在已下载文件中查找路径，避免依赖 files 数组下标顺序
        fun findFile(name: String): String? =
            modelInfo.files.firstOrNull { it.relativePath.endsWith("/$name") }
                ?.let { File(dir, it.relativePath).absolutePath }

        // 主模型文件名各家不同（model.onnx / en_US-lessac-medium.onnx），
        // 按 .onnx 后缀找而不是写死文件名
        val modelPath = modelInfo.files
            .firstOrNull { it.relativePath.endsWith(".onnx") }
            ?.let { File(dir, it.relativePath).absolutePath }
            ?: throw IllegalStateException("缺少 .onnx 模型文件")
        val tokensPath = findFile("tokens.txt")
            ?: throw IllegalStateException("缺少 tokens.txt")
        // Piper 不用 lexicon（G2P 走 espeak-ng）；其余模型可选
        val lexiconPath = if (modelInfo.usesEspeakNg) null else findFile("lexicon.txt")
        // MeloTTS 的 jieba 词典目录名为 dict
        val dictDirPath = modelInfo.files
            .firstOrNull { it.relativePath.endsWith("/dict") }
            ?.let { File(dir, it.relativePath).absolutePath }
        // Piper 的 dataDir 必须指向 espeak-ng-data 目录（G2P 数据），
        // 其余模型保持模型目录本身（MeloTTS 另用 dict 子目录）
        val dataDirPath = if (modelInfo.usesEspeakNg) {
            File(modelDir, "espeak-ng-data").absolutePath
        } else {
            modelDir.absolutePath
        }

        // 配置构造：Kokoro 与 VITS 走不同的 ModelConfig 分支。
        val modelConfig: OfflineTtsModelConfig
        var ruleFsts = ""
        if (modelInfo.isKokoro) {
            val voicesPath = findFile("voices.bin")
                ?: throw IllegalStateException("缺少 voices.bin")
            // 2026-09-06 实测：省略中文 lexicon/ruleFST 只降 1.2s G2P（10.6→9.4s），
            // 但引入 3s 重新初始化开销 + 并发竞态，净负。始终全配。
            // G2P 9s 是 Kokoro 英文 lexicon + espeak-ng 的固有性能，Kotlin 侧无法优化。
            val lexicons = listOfNotNull(
                findFile("lexicon-us-en.txt"),
                findFile("lexicon-zh.txt"),
            ).joinToString(",")
            ruleFsts = listOfNotNull(
                findFile("phone-zh.fst"),
                findFile("date-zh.fst"),
                findFile("number-zh.fst"),
            ).joinToString(",")
            val kokoroConfig = OfflineTtsKokoroModelConfig(
                model = modelPath,
                voices = voicesPath,
                tokens = tokensPath,
                dataDir = dataDirPath,
                lexicon = lexicons,
            )
            // 官方 getOfflineTtsConfig 对带 voices 的模型（Kokoro/Kitten）
            // 推荐 4 线程：82M 参数模型合成开销远大于 Piper
            modelConfig = OfflineTtsModelConfig(
                kokoro = kokoroConfig,
                numThreads = numThreadsKokoro,
            )
        } else {
            // VITS 模型配置（使用构造参数，避免依赖 var 字段默认值）
            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelPath,
                tokens = tokensPath,
                lexicon = lexiconPath ?: "",
                dataDir = dataDirPath,
                dictDir = dictDirPath ?: "",
            )
            modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = numThreads,
            )
        }
        val config = OfflineTtsConfig(model = modelConfig, ruleFsts = ruleFsts)
        return OfflineTts(config = config)
    }

    companion object {
        /** VITS 模型推理线程数 */
        internal const val NUM_THREADS = 2

        /** Kokoro（82M 参数）合成开销大，官方演示对带 voices 的模型用 4 线程 */
        internal const val NUM_THREADS_KOKORO = 4
    }
}
