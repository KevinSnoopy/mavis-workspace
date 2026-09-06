package com.eareyereading.tts

import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * sherpa-onnx [OfflineTtsConfig] 构造器（单一职责：模型文件清单 → native 配置）。
 *
 * 从 EmbeddedTtsEngine.initialize 抽出：配置构造是纯函数（ModelInfo + 模型根目录
 * → OfflineTtsConfig），与引擎的锁/状态机/协程无关。构造规则集中在此，
 * 新增模型族时只改本文件（开闭原则），引擎 initialize 只保留装配与并发语义。
 *
 * 配置规则（sherpa-onnx v1.13.7）：
 *  - Kokoro 官方 Android 演示配置：model/voices/tokens/dataDir(espeak-ng-data) +
 *    lexicon = "lexicon-us-en.txt,lexicon-zh.txt"（逗号分隔）+
 *    ruleFsts = "phone-zh.fst,date-zh.fst,number-zh.fst"（中文电话号/日期/数字
 *    归一化）。jieba dict/ 由 native 端按模型目录自动加载，无需显式配置。
 *  - VITS（Piper）：lexicon 可空（G2P 走 espeak-ng），dictDir 为 MeloTTS 的
 *    jieba 词典目录。
 */
internal object TtsEngineConfigFactory {

    /** Kokoro（82M 参数）合成开销大，官方演示对带 voices 的模型用 4 线程 */
    private const val NUM_THREADS_KOKORO = 4

    private const val NUM_THREADS = 2

    /**
     * 按模型清单构造 native 配置。文件缺失抛 [IllegalStateException]，
     * 由调用方决定状态机走向。
     *
     * @param modelInfo 模型清单（files 数组即下载校验清单）
     * @param modelsRoot 模型根目录（filesDir/models）
     */
    fun create(modelInfo: ModelInfo, modelsRoot: File): OfflineTtsConfig {
        val modelDir = File(modelsRoot, modelInfo.id)

        // 通过文件名在已下载文件中查找路径，避免依赖 files 数组下标顺序
        fun findFile(name: String): String? =
            modelInfo.files.firstOrNull { it.relativePath.endsWith("/$name") }
                ?.let { File(modelsRoot, it.relativePath).absolutePath }

        // 主模型文件名各家不同（model.onnx / en_US-lessac-medium.onnx），
        // 按 .onnx 后缀找而不是写死文件名
        val modelPath = modelInfo.files
            .firstOrNull { it.relativePath.endsWith(".onnx") }
            ?.let { File(modelsRoot, it.relativePath).absolutePath }
            ?: throw IllegalStateException("缺少 .onnx 模型文件")
        val tokensPath = findFile("tokens.txt")
            ?: throw IllegalStateException("缺少 tokens.txt")
        // Piper 不用 lexicon（G2P 走 espeak-ng）；其余模型可选
        val lexiconPath = if (modelInfo.usesEspeakNg) null else findFile("lexicon.txt")
        // MeloTTS 的 jieba 词典目录名为 dict
        val dictDirPath = modelInfo.files
            .firstOrNull { it.relativePath.endsWith("/dict") }
            ?.let { File(modelsRoot, it.relativePath).absolutePath }
        // Piper 的 dataDir 必须指向 espeak-ng-data 目录（G2P 数据），
        // 其余模型保持模型目录本身（MeloTTS 另用 dict 子目录）
        val dataDirPath = if (modelInfo.usesEspeakNg) {
            File(modelDir, "espeak-ng-data").absolutePath
        } else {
            modelDir.absolutePath
        }

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
            modelConfig = OfflineTtsModelConfig(
                kokoro = kokoroConfig,
                numThreads = NUM_THREADS_KOKORO,
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
                numThreads = NUM_THREADS,
            )
        }
        return OfflineTtsConfig(model = modelConfig, ruleFsts = ruleFsts)
    }
}
