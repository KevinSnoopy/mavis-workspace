package com.eareyereading.tts

import android.content.Context
import java.io.File

/**
 * TTS 模型文件在磁盘上的管理：存在性检查、占用空间、删除。
 *
 * 从 [EmbeddedTtsEngine] 抽出的单一职责类（SRP）：引擎主类只负责合成，
 * 文件系统操作集中在本类。所有路径基于 `context.filesDir/sherpa_tts_models/`。
 *
 * @param context 应用上下文
 */
internal class EmbeddedTtsModelFiles(context: Context) {
    private val modelsDir = File(context.filesDir, MODELS_DIR_NAME)

    /**
     * 检查模型是否已下载（且每个文件有 .complete 标记，确保完整）。
     */
    fun isModelDownloaded(modelInfo: ModelInfo): Boolean {
        return modelInfo.files.all { file ->
            val f = File(modelsDir, file.relativePath)
            val contentOk = if (f.isDirectory) f.exists() else f.exists() && f.length() > 0
            contentOk && File(modelsDir, file.relativePath + COMPLETE_SUFFIX).exists()
        }
    }

    /**
     * 获取已下载的模型占用空间（字节）。
     */
    fun getDownloadedSize(): Long {
        if (!modelsDir.exists()) return 0L
        return modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 删除已下载的模型（释放空间）。
     */
    fun deleteModel(modelInfo: ModelInfo) {
        modelInfo.files.forEach { file ->
            // deleteRecursively：Piper 的 espeak-ng-data 是目录，
            // File.delete() 对非空目录静默失败会留下 ~5MB 残留
            File(modelsDir, file.relativePath).let { if (it.exists()) it.deleteRecursively() }
            File(modelsDir, file.relativePath + COMPLETE_SUFFIX).let { if (it.exists()) it.delete() }
        }
    }

    /** 模型目录（供下载/初始化扩展函数使用） */
    fun modelDir(modelId: String): File = File(modelsDir, modelId)

    /** 模型根目录（供下载扩展函数使用） */
    fun rootDir(): File = modelsDir
}
