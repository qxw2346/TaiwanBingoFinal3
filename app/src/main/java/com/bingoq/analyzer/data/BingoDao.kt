package com.bingoq.analyzer.data
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bingoq.analyzer.model.BingoDraw
@Dao
interface BingoDao {
    @Query("SELECT * FROM bingo_draws ORDER BY drawNo DESC")
    suspend fun getAllDraws(): List<BingoDraw>
    @Query("SELECT * FROM bingo_draws ORDER BY drawNo DESC LIMIT :limit")
    suspend fun getRecentDraws(limit: Int = 1000): List<BingoDraw>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDraws(draws: List<BingoDraw>)
    @Query("SELECT COUNT(*) FROM bingo_draws")
    suspend fun getDrawCount(): Int
    @Query("DELETE FROM bingo_draws WHERE drawNo NOT IN (SELECT drawNo FROM bingo_draws ORDER BY drawNo DESC LIMIT :keepLimit)")
    suspend fun pruneOldDraws(keepLimit: Int = 3000)
    @Query("DELETE FROM bingo_draws")
    suspend fun clearAll()
}