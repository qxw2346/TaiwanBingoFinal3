package com.bingoq.analyzer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.bingoq.analyzer.model.BingoDraw

@Database(entities = [BingoDraw::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class BingoDatabase : RoomDatabase() {
    abstract fun bingoDao(): BingoDao

    companion object {
        @Volatile
        private var INSTANCE: BingoDatabase? = null

        fun getInstance(context: Context): BingoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BingoDatabase::class.java,
                    "bingo_analyzer_db"
                )
                    // Deliberately do not use fallbackToDestructiveMigration().
                    // A missing migration must fail safely rather than silently
                    // deleting the user's draw history.
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
