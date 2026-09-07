package com.eareyereading.ui.screens.settings

import android.content.Context
import androidx.room.withTransaction
import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.data.local.dao.VocabularyDao
import com.eareyereading.data.local.database.AppDatabase
import com.eareyereading.data.local.entity.ReviewRecordEntity
import com.eareyereading.data.local.entity.ReadingStatsEntity
import com.eareyereading.data.local.entity.VocabularyEntity
import com.eareyereading.domain.repository.VocabularyRepository
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 数据导入/导出与缓存管理的业务逻辑。
 *
 * 从 [SettingsViewModel] 抽出的单一职责类（SRP）：ViewModel 只负责调度与
 * uiState 更新，JSON 序列化、事务化导入、复习记录重链接等纯数据操作集中在本类。
 * 无 Android UI 依赖，可独立测试。
 *
 * @param context 应用上下文（取外部存储目录）
 * @param vocabularyRepository 词汇仓库
 * @param readingStatsDao 阅读统计 DAO
 * @param vocabularyDao 词汇 DAO
 * @param database Room 数据库（事务化导入）
 */
internal class SettingsDataPorter(
    private val context: Context,
    private val vocabularyRepository: VocabularyRepository,
    private val readingStatsDao: ReadingStatsDao,
    private val vocabularyDao: VocabularyDao,
    private val database: AppDatabase,
) {
    /** 导出结果：成功时 file 为产物，失败时 file=null 且 message 含原因。 */
    data class ExportResult(val file: File?, val message: String)

    /** 导入结果统计。 */
    data class ImportResult(
        val imported: Int,
        val skipped: Int,
        val statsImported: Int,
        val statsSkipped: Int,
        val reviewImported: Int,
    )

    /**
     * 导出词汇、阅读统计与复习记录到应用外部私有目录的 JSON 文件。
     * 用 org.json 序列化以正确转义所有特殊字符（旧实现手写 JSON 会产出非法 JSON）。
     */
    suspend fun export(): ExportResult {
        return try {
            // 写到应用外部私有目录（文件管理器 Android/data/... 可见，用户可取走），
            // 而不是 cacheDir —— 旧实现放 cacheDir 会被"清除缓存"删掉唯一副本。
            // 外部存储不可用时回退内部 filesDir（至少不会被清缓存误删）
            val exportDir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(exportDir, "eareye_backup_${System.currentTimeMillis()}.json")
            val vocabList = vocabularyRepository.getAllVocabulary().first()
            val statsList = readingStatsDao.getAllStats()
            val root = org.json.JSONObject()
            root.put("version", 1)
            root.put("exportedAt", System.currentTimeMillis())
            val vocabArr = org.json.JSONArray()
            for (v in vocabList) {
                val o = org.json.JSONObject()
                o.put("word", v.word)
                o.put("definition", v.definition ?: "")
                o.put("level", v.level)
                o.put("isLearned", v.isLearned)
                o.put("note", v.note ?: "")
                o.put("example", v.example ?: "")
                vocabArr.put(o)
            }
            root.put("vocabulary", vocabArr)
            val statsArr = org.json.JSONArray()
            for (s in statsList) {
                val o = org.json.JSONObject()
                o.put("bookId", s.bookId)
                o.put("date", s.date)
                o.put("readingMinutes", s.readingMinutes)
                o.put("charsRead", s.charsRead)
                statsArr.put(o)
            }
            root.put("stats", statsArr)
            // issue 11.8：导出复习记录（SM-2 进度），否则换机恢复后
            // 所有词的记忆曲线清零、全部重新 from-scratch。
            // 按 word 导出（vocabularyId 跨机不可复用），导入端按词重链接。
            val reviewRecords = database.reviewRecordDao().getAllReviews().first()
            val reviewArr = org.json.JSONArray()
            for (r in reviewRecords) {
                val o = org.json.JSONObject()
                o.put("word", r.word)
                o.put("easeFactor", r.easeFactor.toDouble())
                o.put("interval", r.interval)
                o.put("repetitions", r.repetitions)
                o.put("nextReviewDate", r.nextReviewDate)
                o.put("lastReviewDate", r.lastReviewedAt)
                o.put("lastQuality", r.lastQuality)
                reviewArr.put(o)
            }
            root.put("reviewRecords", reviewArr)
            // 序列化结果写盘放 IO 线程，大备份不冻 UI
            withContext(Dispatchers.IO) { file.writeText(root.toString()) }
            ExportResult(file, "已导出: ${file.name}")
        } catch (e: Exception) {
            ExportResult(null, "导出失败: ${e.message}")
        }
    }

    /**
     * 从 JSON 文件事务化导入词汇、阅读统计与复习记录。
     *
     * 已存在的词跳过保留本地状态（REPLACE 冲突策略会用备份字段覆盖本地行的
     * 复习进度/书籍关联）。复习记录按词重链接到本备份新插入词的 id。
     */
    suspend fun importFromFile(file: File): ImportResult {
        // 大备份的读盘与 JSON 解析都放 IO 线程：解析留在 Main 会冻 UI。
        // 用 org.json 结构化解析。旧实现按字段正则扫描再按索引配对：
        // 释义里一旦出现 "word":"..." 之类的文本就会产生额外匹配，
        // 索引整体错位，后续每个词都配到错误的释义（批量数据损坏）
        val root = withContext(Dispatchers.IO) {
            org.json.JSONObject(file.readText())
        }
        val vocabArr = root.optJSONArray("vocabulary") ?: org.json.JSONArray()
        val statsArr = root.optJSONArray("stats")
        // issue 11.8：复习记录按导入的新词重链接（旧词跳过保本地进度）
        val reviewArr = root.optJSONArray("reviewRecords")
        val reviewByWord = LinkedHashMap<String, org.json.JSONObject>()
        if (reviewArr != null) {
            for (i in 0 until reviewArr.length()) {
                val o = reviewArr.optJSONObject(i) ?: continue
                val w = o.optString("word").trim()
                if (w.isNotEmpty()) reviewByWord[w.lowercase(Locale.ROOT)] = o
            }
        }
        var imported = 0
        var skipped = 0
        var statsImported = 0
        var statsSkipped = 0
        var reviewImported = 0
        // 事务化导入：中途失败整体回滚，不再留下半成品
        database.withTransaction {
            // 判存集合一次性预加载：循环内逐词 getWord 是 LOWER 全表扫描，
            // n 词备份 × 全表 = O(n²)，大备份会拉长事务持锁时间
            val existingWords = vocabularyDao.getAllWordsLowercase().toHashSet()
            // issue 11.8：记录本备份新插入词的 id，供下方重链接复习记录
            val newIdByWord = HashMap<String, Long>()
            for (i in 0 until vocabArr.length()) {
                val obj = vocabArr.optJSONObject(i) ?: continue
                val word = obj.optString("word").trim()
                if (word.isEmpty()) continue
                if (word.lowercase(Locale.ROOT) in existingWords) {
                    skipped++
                    continue
                }
                val newId = vocabularyDao.insert(VocabularyEntity(
                    word = word,
                    definition = obj.optString("definition", ""),
                    level = obj.optInt("level", 0),
                    isLearned = obj.optBoolean("isLearned", false),
                    note = obj.optString("note", "").ifBlank { null },
                    example = obj.optString("example", "").ifBlank { null },
                    bookId = 0,
                    bookTitle = "Imported",
                    context = null,
                    translation = null,
                    reviewCount = 0,
                    lastReviewTime = 0L,
                    dateAdded = System.currentTimeMillis(),
                ))
                newIdByWord[word.lowercase(Locale.ROOT)] = newId
                imported++
            }
            // issue 11.8：恢复复习记录（SM-2 进度）。只恢复"本备份新增词汇"的
            // 记录——已存在词跳过并保留本地进度，与词汇的"保留本地"语义一致。
            // insertReview 用 IGNORE（vocabularyId 唯一），竞态重复插入变幂等。
            for ((wordLower, reviewObj) in reviewByWord) {
                val vid = newIdByWord[wordLower] ?: continue
                database.reviewRecordDao().insertReview(
                    ReviewRecordEntity(
                        vocabularyId = vid,
                        word = reviewObj.optString("word"),
                        easeFactor = reviewObj.optDouble("easeFactor", 2.5).toFloat(),
                        interval = reviewObj.optInt("interval", 1),
                        repetitions = reviewObj.optInt("repetitions", 0),
                        nextReviewDate = reviewObj.optLong("nextReviewDate", System.currentTimeMillis()),
                        lastReviewedAt = reviewObj.optLong("lastReviewDate", System.currentTimeMillis()),
                        lastQuality = reviewObj.optInt("lastQuality", 0),
                    ),
                )
                reviewImported++
            }
            // 恢复一并导出的阅读统计：旧导入只读 vocabulary，
            // "导出词汇和阅读数据"的承诺恢复时静默丢一半。
            // 与词汇同款的"保留本地"语义：(bookId,date) 已有本地记录则跳过——
            // insertStat 是 REPLACE 冲突策略，直接插会静默覆盖本地当天真实数据
            if (statsArr != null) {
                for (i in 0 until statsArr.length()) {
                    val obj = statsArr.optJSONObject(i) ?: continue
                    val date = obj.optString("date")
                    val bookId = obj.optLong("bookId", 0)
                    if (date.isEmpty() || bookId == 0L) continue
                    if (readingStatsDao.getStatForBookAndDate(bookId, date) != null) {
                        statsSkipped++
                        continue
                    }
                    readingStatsDao.insertStat(
                        ReadingStatsEntity(
                            bookId = bookId,
                            date = date,
                            readingMinutes = obj.optInt("readingMinutes", 0),
                            charsRead = obj.optInt("charsRead", 0),
                            paragraphsRead = obj.optInt("paragraphsRead", 0),
                        )
                    )
                    statsImported++
                }
            }
        }
        return ImportResult(imported, skipped, statsImported, statsSkipped, reviewImported)
    }

    /** 清理缓存目录（临时文件等）；导出备份不在 cacheDir，不受影响。 */
    suspend fun clearCache() {
        withContext(Dispatchers.IO) {
            context.cacheDir.walkTopDown().forEach { it.delete() }
        }
    }

    /** 异步统计缓存目录大小（MB）。 */
    suspend fun cacheSizeMb(): Double = withContext(Dispatchers.IO) {
        try {
            context.cacheDir.walkTopDown().sumOf { it.length() } / (1024.0 * 1024.0)
        } catch (_: Exception) {
            0.0
        }
    }
}
