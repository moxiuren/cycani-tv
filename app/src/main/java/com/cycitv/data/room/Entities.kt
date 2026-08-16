package com.cycitv.data.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "history_video")
data class HistoryEntity(
    @PrimaryKey val sectionId: Long,
    val animeId: Long,
    val title: String = "",
    val coverUrl: String = "",
    val sectionTitle: String = "",
    val progressMs: Long = 0,
    val durationMs: Long = 0,
    val updateTime: Long = System.currentTimeMillis(),
)

@Entity(tableName = "search_history", indices = [Index(value = ["keyword"], unique = true)])
data class SearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val updateTime: Long = System.currentTimeMillis(),
)
