package com.jeiel85.clearpdflocal.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileUri: String,
    val pageNumber: Int,
    val note: String,
    val createdTime: Long = System.currentTimeMillis()
)
