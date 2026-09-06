package com.eareyereading.tts

import java.net.URL

/**
 * 内置 TTS 模型目录：Piper 英文模型清单与文件镜像配置。
 * 纯数据，由引擎与设置页读取。
 *
 * 2026-09-06：Kokoro 已下线（G2P 9s 固有性能无法优化，多音色改走
 * 在线 Edge TTS）。离线只保留 Piper 英文男声。
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
     * Kokoro 系模型标识（已下线，保留字段让 ModelInfo 构造不破坏，
     * AVAILABLE_MODELS 里不再有 isKokoro=true 的模型）。
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
 * 仅 Piper lessac-medium：韵律自然、英文发音地道、体积小（66MB）；
 * G2P 走 espeak-ng（归档自带 espeak-ng-data/），首声 <1s。
 *
 * 多音色/中文朗读走在线腾讯云 TTS（见 TencentTtsEngine）。
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
)

// 内置默认 = Piper 英文声
val DEFAULT_MODEL_ID = "vits-piper-en_US-lessac-medium"

/**
 * Kokoro 音色信息（Kokoro 已下线，保留类与空列表让引用点编译过）。
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

/** Kokoro 音色目录（Kokoro 已下线，空列表） */
val KOKORO_VOICES: List<VoiceInfo> = emptyList()
