package com.example.data.repository

import com.example.data.local.BookmarkDao
import com.example.data.model.Bookmark
import kotlinx.coroutines.flow.Flow

class BookmarkRepository(private val bookmarkDao: BookmarkDao) {
    fun getBookmarksForFile(fileUri: String): Flow<List<Bookmark>> {
        return bookmarkDao.getBookmarksForFile(fileUri)
    }

    suspend fun insertBookmark(bookmark: Bookmark) {
        bookmarkDao.insertBookmark(bookmark)
    }

    suspend fun deleteBookmark(bookmark: Bookmark) {
        bookmarkDao.deleteBookmark(bookmark)
    }

    suspend fun deleteBookmarkById(id: Int) {
        bookmarkDao.deleteBookmarkById(id)
    }

    suspend fun isBookmarked(fileUri: String, pageNumber: Int): Boolean {
        return bookmarkDao.isBookmarked(fileUri, pageNumber)
    }
}
