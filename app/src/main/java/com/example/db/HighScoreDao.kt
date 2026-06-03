package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HighScoreDao {
    @Query("SELECT * FROM high_scores ORDER BY score DESC, timestamp DESC LIMIT 10")
    fun getTopScores(): Flow<List<HighScore>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScore(highScore: HighScore)

    @Query("SELECT MAX(score) FROM high_scores WHERE difficulty = :difficulty")
    suspend fun getMaxScore(difficulty: String): Int?

    @Query("DELETE FROM high_scores")
    suspend fun clearScores()
}
