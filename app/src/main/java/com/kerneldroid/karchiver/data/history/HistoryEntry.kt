package com.kerneldroid.karchiver.data.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history_entries",
    indices = [Index("lastVisitedAt"), Index("isDirectory")]
)
data class HistoryEntry(
    @PrimaryKey val path: String,
    val name: String,
    val isDirectory: Boolean,
    val extension: String,
    val size: Long,
    val lastVisitedAt: Long,
    val visitCount: Int
)
