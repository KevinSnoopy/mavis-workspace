package com.eareyereading.data.local.dao

import androidx.room.*
import com.eareyereading.data.local.entity.ReadingStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ReadingStateEntity)

    @Query("SELECT * FROM reading_state WHERE bookId = :bookId LIMIT 1")
    suspend fun getForBook(bookId: Long): ReadingStateEntity?

    @Query("SELECT * FROM reading_state WHERE bookId = :bookId LIMIT 1")
    fun observeForBook(bookId: Long): Flow<ReadingStateEntity?>

    @Query("DELETE FROM reading_state WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    @Query("UPDATE reading_state SET currentParagraph = :paragraph, currentPosition = :position, lastUpdated = :now WHERE bookId = :bookId")
    suspend fun updateProgress(bookId: Long, paragraph: Int, position: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE reading_state SET readingMode = :mode WHERE bookId = :bookId")
    suspend fun updateMode(bookId: Long, mode: String)

    @Query("UPDATE reading_state SET rsvpSpeed = :speed WHERE bookId = :bookId")
    suspend fun updateRsvpSpeed(bookId: Long, speed: Int)
}
