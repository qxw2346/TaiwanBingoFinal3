package com.bingoq.analyzer.data.local
import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
enum class VerificationBadge {
    GREEN,
    YELLOW,
    RED
}
@Entity(tableName = "bingo_draws")
data class BingoDrawEntity(
    @PrimaryKey val period: Long,
    val drawDate: String,
    val drawTime: String?,
    val numbersCsv: String,
    val superNumber: Int?,
    val source: String,
    val verificationStatus: String,
    val isManuallyFixed: Boolean = false,
    val updatedAtEpochMs: Long
) {
    fun numberList(): List<Int> =
        numbersCsv.split(",").mapNotNull { it.trim().toIntOrNull() }
}
@Dao
interface BingoDrawDao {
    @Query("SELECT * FROM bingo_draws WHERE verificationStatus != 'RED' ORDER BY period DESC LIMIT :limit")
    suspend fun getLatestValidDraws(limit: Int): List<BingoDrawEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDraws(draws: List<BingoDrawEntity>)
    @Query("SELECT COUNT(*) FROM bingo_draws")
    suspend fun getTotalCount(): Int
}
@Database(entities = [BingoDrawEntity::class], version = 1, exportSchema = false)
abstract class BingoDatabase : RoomDatabase() {
    abstract fun drawDao(): BingoDrawDao
    companion object {
        @Volatile
        private var INSTANCE: BingoDatabase? = null
        fun getInstance(context: Context): BingoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BingoDatabase::class.java,
                    "taiwan_bingo_quant.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}