package com.kerneldroid.karchiver.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history_entries ORDER BY lastVisitedAt DESC")
    fun observeAll(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history_entries WHERE path = :path LIMIT 1")
    suspend fun find(path: String): HistoryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: HistoryEntry)

    @Query("DELETE FROM history_entries WHERE path = :path")
    suspend fun remove(path: String)

    @Query("DELETE FROM history_entries")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM history_entries")
    suspend fun count(): Int

    @Query(
        "DELETE FROM history_entries WHERE path NOT IN " +
            "(SELECT path FROM history_entries ORDER BY lastVisitedAt DESC LIMIT :keep)"
    )
    suspend fun prune(keep: Int)
}
