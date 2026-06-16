package com.jeiel85.clearpdflocal.data.local

import androidx.room.*
import com.jeiel85.clearpdflocal.data.model.Bookmark
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE fileUri = :fileUri ORDER BY pageNumber ASC")
    fun getBookmarksForFile(fileUri: String): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark)

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE fileUri = :fileUri AND pageNumber = :pageNumber LIMIT 1)")
    suspend fun isBookmarked(fileUri: String, pageNumber: Int): Boolean
}
