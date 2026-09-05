package com.eareyereading.tts

import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import java.net.URL

/**
 * 内置 TTS 模型目录：模型清单（Piper / Kokoro）、文件镜像配置与 Kokoro 音色表。
 * 纯数据，由引擎与设置页读取。
 */
/**
 * 模型配置：模型名 → CDN URL
 *
 * 使用 k2-fsa 官方 HuggingFace 上的预编译模型。
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val language: String,
    val sizeBytes: Long,
    val files: List<ModelFile>,
    /**
     * GitHub release 整包 tarball URL（推荐，国内可达性优于 HuggingFace）。
     * 下载后解压到 models 目录。若为 null 则回退到逐文件下载。
     */
    val tarballUrl: String? = null,
    val tarballMirrorUrls: List<String> = emptyList(),
    /**
     * Piper 系模型：G2P 走 espeak-ng（需模型目录下的 espeak-ng-data/），
     * 不用 lexicon。初始化时 dataDir 指向 espeak-ng-data 目录。
     */
    val usesEspeakNg: Boolean = false,
    /**
     * Kokoro 系模型（kokoro-multi-lang-v1_1）：初始化走
     * OfflineTtsKokoroModelConfig（voices.bin + 双 lexicon + ruleFsts），
     * generate() 时传 sid 选择 103 种音色之一。
     */
    val isKokoro: Boolean = false,
) {
    fun tarballAllUrls(): List<String> =
        (listOfNotNull(tarballUrl) + tarballMirrorUrls)
            // 镜像列表里历史上混入过裸 model.onnx URL：它会被写成 .tar.bz2
            // 导致 bzip2 解压必然失败，还白白下载上百 MB。这里只接受 tarball。
            .filter { it.endsWith(".tar.bz2") }
}

data class ModelFile(
    val relativePath: String,  // 在 app models 目录下的相对路径
    val url: String,
    /**
     * 备选镜像 URL 列表（按优先级排序）。主 URL 失败后依次尝试。
     * 用于解决 HuggingFace 在国内不可达的问题。
     */
    val mirrorUrls: List<String> = emptyList(),
) {
    /** 按优先级返回所有可用 URL（主 URL 在前）。 */
    fun allUrls(): List<String> = listOf(url) + mirrorUrls
}

/**
 * 内置可用模型列表。
 *
 * 默认 = Piper lessac-medium（见 DEFAULT_MODEL_ID）：韵律自然、英文发音
 * 地道、体积小；G2P 走 espeak-ng（归档自带 espeak-ng-data/）。
 *
 * Kokoro int8 中英双语（2026-09-04 新增）：103 种音色 + 原生中英混读。
 * 归档含 jieba dict/、三个 ruleFst（phone/date/number-zh.fst，中文数字
 * 日期归一化）与双 lexicon；官方 Android 演示同款配置。
 */
val AVAILABLE_MODELS = listOf(
    ModelInfo(
        id = "vits-piper-en_US-lessac-medium",
        displayName = "Piper 英文男声·自然语调（约 66MB）",
        language = "en",
        sizeBytes = 66_000_000L,
        // 主源用 ghfast.top 镜像（国内可达性更稳），保留 GitHub release 作 fallback。
        // 注意：ghfast.top 嵌套 GitHub URL 时必须把内层 scheme/路径做 URL 转义，
        // 否则 Java URL.openConnection 会发送未转义的 `://` 给边缘节点，
        // 部分 CDN 会判定为非法资源 → 404 或卡死握手。转义后的
        // 形态在浏览器和 ghfast.top 后端都稳。
        tarballUrl = "https://ghfast.top/https%3A%2F%2Fgithub.com%2Fk2-fsa%2Fsherpa-onnx%2Freleases%2Fdownload%2Ftts-models%2Fvits-piper-en_US-lessac-medium.tar.bz2",
        tarballMirrorUrls = listOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2",
        ),
        usesEspeakNg = true,
        // URL 留空 = 仅归档下载：espeak-ng-data 含数百个小文件，
        // 逐文件路径不可行；归档失败时由下载逻辑直接报失败
        files = listOf(
            ModelFile("vits-piper-en_US-lessac-medium/en_US-lessac-medium.onnx", url = ""),
            ModelFile("vits-piper-en_US-lessac-medium/tokens.txt", url = ""),
            ModelFile("vits-piper-en_US-lessac-medium/espeak-ng-data", url = ""),
        ),
    ),
    ModelInfo(
        id = "kokoro-int8-multi-lang-v1_1",
        displayName = "Kokoro 中英双语·多音色（约 205MB，103 种音色）",
        language = "zh,en",
        // 解压后总大小（model.int8.onnx 114MB + voices.bin 54MB + 词典/分词数据）。
        // 下载进度分母优先用响应 Content-Length（压缩包 ~147MB），此处数值
        // 仅作磁盘预检（×3）与解压估算的基准
        sizeBytes = 205_000_000L,
        tarballUrl = "https://ghfast.top/https%3A%2F%2Fgithub.com%2Fk2-fsa%2Fsherpa-onnx%2Freleases%2Fdownload%2Ftts-models%2Fkokoro-int8-multi-lang-v1_1.tar.bz2",
        tarballMirrorUrls = listOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2",
        ),
        usesEspeakNg = true,
        isKokoro = true,
        // URL 留空 = 仅归档下载：espeak-ng-data / dict 各含数百个小文件
        files = listOf(
            ModelFile("kokoro-int8-multi-lang-v1_1/model.int8.onnx", url = ""),
            ModelFile("kokoro-int8-multi-lang-v1_1/voices.bin", url = ""),
            ModelFile("kokoro-int8-multi-lang-v1_1/tokens.txt", url = ""),
            ModelFile("kokoro-int8-multi-lang-v1_1/espeak-ng-data", url = ""),
            ModelFile("kokoro-int8-multi-lang-v1_1/dict", url = ""),
            ModelFile("kokoro-int8-multi-lang-v1_1/lexicon-us-en.txt", url = ""),
            ModelFile("kokoro-int8-multi-lang-v1_1/lexicon-zh.txt", url = ""),
            ModelFile("kokoro-int8-multi-lang-v1_1/lexicon-gb-en.txt", url = ""),
            ModelFile("kokoro-int8-multi-lang-v1_1/phone-zh.fst", url = ""),
            ModelFile("kokoro-int8-multi-lang-v1_1/date-zh.fst", url = ""),
            ModelFile("kokoro-int8-multi-lang-v1_1/number-zh.fst", url = ""),
        ),
    ),
)

// 内置默认 = Piper 英文声（英文阅读主线；Kokoro 为用户可选升级）
val DEFAULT_MODEL_ID = "vits-piper-en_US-lessac-medium"

/**
 * Kokoro（kokoro-multi-lang-v1_1）的 103 个音色。
 * sid→名称映射来自官方文档；前缀含义：af=美式女声 bf=英式女声
 * zf=中文女声 zm=中文男声。所有音色均可中英混读，只是口音倾向不同。
 */
data class VoiceInfo(val sid: Int, val name: String) {
    val category: String
        get() = when {
            name.startsWith("af") -> "美式女声"
            name.startsWith("bf") -> "英式女声"
            name.startsWith("zf") -> "中文女声"
            name.startsWith("zm") -> "中文男声"
            else -> "其他"
        }
    val displayName: String get() = "$name · $category"
}

private val KOKORO_VOICE_NAMES = listOf(
    "af_maple", "af_sol", "bf_vale",
    "zf_001", "zf_002", "zf_003", "zf_004", "zf_005", "zf_006", "zf_007",
    "zf_008", "zf_017", "zf_018", "zf_019", "zf_021", "zf_022", "zf_023",
    "zf_024", "zf_026", "zf_027", "zf_028", "zf_032", "zf_036", "zf_038",
    "zf_039", "zf_040", "zf_042", "zf_043", "zf_044", "zf_046", "zf_047",
    "zf_048", "zf_049", "zf_051", "zf_059", "zf_060", "zf_067", "zf_070",
    "zf_071", "zf_072", "zf_073", "zf_074", "zf_075", "zf_076", "zf_077",
    "zf_078", "zf_079", "zf_083", "zf_084", "zf_085", "zf_086", "zf_087",
    "zf_088", "zf_090", "zf_092", "zf_093", "zf_094", "zf_099",
    "zm_009", "zm_010", "zm_011", "zm_012", "zm_013", "zm_014", "zm_015",
    "zm_016", "zm_020", "zm_025", "zm_029", "zm_030", "zm_031", "zm_033",
    "zm_034", "zm_035", "zm_037", "zm_041", "zm_045", "zm_050", "zm_052",
    "zm_053", "zm_054", "zm_055", "zm_056", "zm_057", "zm_058", "zm_061",
    "zm_062", "zm_063", "zm_064", "zm_065", "zm_066", "zm_068", "zm_069",
    "zm_080", "zm_081", "zm_082", "zm_089", "zm_091", "zm_095", "zm_096",
    "zm_097", "zm_098", "zm_100",
)

/** Kokoro 音色目录（sid 顺序与官方 voices.bin 对齐） */
val KOKORO_VOICES: List<VoiceInfo> =
    KOKORO_VOICE_NAMES.mapIndexed { sid, name -> VoiceInfo(sid, name) }
