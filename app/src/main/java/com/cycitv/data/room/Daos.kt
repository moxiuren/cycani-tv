package com.cycitv.data.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_video ORDER BY updateTime DESC")
    fun all(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history_video WHERE sectionId = :id LIMIT 1")
    suspend fun bySection(id: Long): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(h: HistoryEntity)

    @Query("DELETE FROM history_video WHERE sectionId = :id")
    suspend fun remove(id: Long)
}

@Dao
interface SearchDao {
    @Query("SELECT * FROM search_history ORDER BY updateTime DESC LIMIT 20")
    fun all(): Flow<List<SearchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SearchEntity)

    @Query("DELETE FROM search_history WHERE id = :id")
    suspend fun remove(id: Long)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}
