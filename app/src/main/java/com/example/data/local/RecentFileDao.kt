package com.example.data.local

import androidx.room.*
import com.example.data.model.RecentFile
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentFileDao {
    @Query("SELECT * FROM recent_files ORDER BY lastOpened DESC")
    fun getAllRecentFiles(): Flow<List<RecentFile>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentFile(recentFile: RecentFile)

    @Query("DELETE FROM recent_files WHERE uri = :uri")
    suspend fun deleteRecentFileByUri(uri: String)

    @Query("DELETE FROM recent_files")
    suspend fun clearAllRecentFiles()

    @Query("SELECT * FROM recent_files WHERE uri = :uri LIMIT 1")
    suspend fun getRecentFileByUri(uri: String): RecentFile?
}
