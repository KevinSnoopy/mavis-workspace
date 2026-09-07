package com.eareyereading.util

import com.eareyereading.data.local.dao.DictionaryEntryDao
import com.eareyereading.data.local.entity.DictionaryEntryEntity
import java.io.File

/**
 * 词典导入器与按需查询器（issue 13：从 DictionaryManager 按 SRP 拆出）。
 *
 * 职责：
 * - 大词典首次查询时把 txt 文件按行批量写入 Room（幂等）；
 * - 大词典单条查询 + 最近命中 LRU 缓存；
 * - 小词典 txt 文件整份载内存解析（供门面 getActiveDict 委托）；
 * - 删除词典时清理已入库条目与内存标志。
 *
 * @param dictionaryEntryDao Room DAO（大词典按需查询/批量写入）
 */
internal class DictionaryImporter(
    private val dictionaryEntryDao: DictionaryEntryDao,
) {
    companion object {
        // issue 12.5：小于该字节数的词典仍整份载内存（保最快）；
        // 大于等于该值视为大词典，写 Room 表按需单条查询，避免 OOM。
        internal const val LARGE_DICT_THRESHOLD_BYTES = 10L * 1024 * 1024

        // 大词典按需查询的批量写入批次大小（毫秒级小节流，避免单次事务过大）
        internal const val BIG_DICT_IMPORT_BATCH = 2000

        // 大词典最近命中的小 LRU：阅读/RSVP 热路径同一词会反复查询，
        // 缓存最近命中可大幅减少对 Room 的单条查询次数。
        internal const val BIG_DICT_LRU_MAX = 256
    }

    // issue 12.5：大词典最近命中缓存（accessOrder=true 即访问序 LRU）。
    // key 用 "dictId\u0000word"，跨词典复用互不污染；仅在方法内 synchronized 访问。
    private val bigDictCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > BIG_DICT_LRU_MAX
    }

    // 大词典"已导入 Room"内存标志：替代每次查词的 countByDictId 聚合查询
    private val importedBigDicts = mutableSetOf<String>()

    /**
     * 把 txt 词典文件整份载内存为 word→definition 的 Map。
     * 文件格式为每行 `word|definition`，`#` 开头或空行跳过。
     * 文件不存在或读取失败（如被并发删除）返回 null。
     */
    internal fun loadToMemory(file: File): Map<String, String>? {
        if (!file.exists()) return null
        // issue 12.6：delete() 删除当前选中词典时，此处与删除协程存在竞态——
        // exists() 通过后文件可能在读盘途中被删 -> FileNotFoundException 裸抛。
        // 捕获后返回 null（查词按未命中处理），由调用方清掉已失效的内存态。
        return try {
            linkedMapOf<String, String>().also { m ->
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                        val sep = trimmed.indexOf('|')
                        if (sep <= 0) continue
                        m[trimmed.substring(0, sep).trim()] = trimmed.substring(sep + 1).trim()
                    }
                }
            }
        } catch (e: java.io.FileNotFoundException) {
            android.util.Log.w("DictionaryManager", "active dict file disappeared: ${e.message}")
            null
        }
    }

    /**
     * 首次查询某大词典时把文件按行写入 Room（幂等：已入库则直接复用，
     * 不重复扫描）。(dictId, word) 唯一 + REPLACE 覆盖，重复导入无副作用。
     */
    internal suspend fun ensureBigDictImported(dictId: String, file: File) {
        // 内存标志短路：旧实现每次查词都对几十万行的表做一次 count 聚合
        synchronized(importedBigDicts) {
            if (dictId in importedBigDicts) return
        }
        if (dictionaryEntryDao.countByDictId(dictId) > 0L) {
            synchronized(importedBigDicts) { importedBigDicts.add(dictId) }
            return
        }
        val buffer = ArrayList<DictionaryEntryEntity>(BIG_DICT_IMPORT_BATCH)
        try {
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val sep = trimmed.indexOf('|')
                    if (sep <= 0) continue
                    buffer.add(
                        DictionaryEntryEntity(
                            dictId = dictId,
                            word = trimmed.substring(0, sep).trim(),
                            definition = trimmed.substring(sep + 1).trim(),
                        ),
                    )
                    if (buffer.size >= BIG_DICT_IMPORT_BATCH) {
                        dictionaryEntryDao.insertAll(buffer)
                        buffer.clear()
                    }
                }
            }
            if (buffer.isNotEmpty()) dictionaryEntryDao.insertAll(buffer)
            android.util.Log.i(
                "DictionaryManager",
                "已导入大词典到 Room: $dictId (${dictionaryEntryDao.countByDictId(dictId)} 条)",
            )
        } catch (e: java.io.IOException) {
            android.util.Log.w("DictionaryManager", "导入大词典到 Room 失败 $dictId: ${e.message}")
            return
        }
        synchronized(importedBigDicts) { importedBigDicts.add(dictId) }
    }

    /** 大词典单条查询，命中最近的查询结果用 LRU 缓存减少反复敲 DB。 */
    internal suspend fun cachedBigLookup(dictId: String, word: String): String? {
        val key = "$dictId\u0000$word"
        synchronized(bigDictCache) {
            bigDictCache[key]?.let { return it }
        }
        val def = dictionaryEntryDao.getDefinition(dictId, word)
        if (def != null) {
            synchronized(bigDictCache) {
                bigDictCache[key] = def
            }
        }
        return def
    }

    /**
     * 删除词典时同步清理：清掉已入库的大词典条目（避免孤儿行常驻 DB），
     * 并失效"已导入"内存标志（否则删除后查词永远命中旧 Room 数据）。
     */
    internal suspend fun onDictDeleted(dictId: String) {
        dictionaryEntryDao.deleteByDictId(dictId)
        synchronized(importedBigDicts) { importedBigDicts.remove(dictId) }
    }
}
