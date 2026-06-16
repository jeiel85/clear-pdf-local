package com.jeiel85.clearpdflocal.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jeiel85.clearpdflocal.data.model.Bookmark
import com.jeiel85.clearpdflocal.data.model.RecentFile

@Database(entities = [RecentFile::class, Bookmark::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recentFileDao(): RecentFileDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "clearpdf_local_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
