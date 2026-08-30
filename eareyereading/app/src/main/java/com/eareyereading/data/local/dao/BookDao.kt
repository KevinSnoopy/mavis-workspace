package com.eareyereading.data.local.dao

import androidx.room.*
import com.eareyereading.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE isArchived = 0 ORDER BY lastReadTime DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE isArchived = 1 ORDER BY lastReadTime DESC")
    fun getArchivedBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun getBookByIdFlow(id: Long): Flow<BookEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET readProgress = :progress, lastReadPosition = :position, lastReadTime = :time WHERE id = :bookId")
    suspend fun updateProgress(bookId: Long, progress: Float, position: Int, time: Long = System.currentTimeMillis())

    @Query("UPDATE books SET isArchived = :archived WHERE id = :bookId")
    suspend fun setArchived(bookId: Long, archived: Boolean)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("SELECT * FROM books WHERE (title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%') AND isArchived = 0 ORDER BY lastReadTime DESC")
    fun searchBooks(query: String): Flow<List<BookEntity>>

    @Query("SELECT COUNT(*) FROM books")
    suspend fun getBookCount(): Int
}
