package com.jeiel85.clearpdflocal.data.repository

import com.jeiel85.clearpdflocal.data.local.RecentFileDao
import com.jeiel85.clearpdflocal.data.model.RecentFile
import kotlinx.coroutines.flow.Flow

class RecentFileRepository(private val recentFileDao: RecentFileDao) {
    val allRecentFiles: Flow<List<RecentFile>> = recentFileDao.getAllRecentFiles()

    suspend fun insertRecentFile(recentFile: RecentFile) {
        recentFileDao.insertRecentFile(recentFile)
    }

    suspend fun deleteRecentFileByUri(uri: String) {
        recentFileDao.deleteRecentFileByUri(uri)
    }

    suspend fun getRecentFileByUri(uri: String): RecentFile? {
        return recentFileDao.getRecentFileByUri(uri)
    }

    suspend fun clearAllRecentFiles() {
        recentFileDao.clearAllRecentFiles()
    }
}
