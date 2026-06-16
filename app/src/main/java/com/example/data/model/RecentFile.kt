package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_files")
data class RecentFile(
    @PrimaryKey val uri: String,
    val fileName: String,
    val lastOpened: Long,
    val pageSize: Int,
    val fileSize: Long
)
