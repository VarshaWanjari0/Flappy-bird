package com.example.db

import kotlinx.coroutines.flow.Flow

class HighScoreRepository(private val highScoreDao: HighScoreDao) {
    val topScores: Flow<List<HighScore>> = highScoreDao.getTopScores()

    suspend fun insertScore(playerName: String, score: Int, difficulty: String) {
        val finalName = playerName.trim().ifEmpty { "Anonymous Bird" }
        highScoreDao.insertScore(HighScore(playerName = finalName, score = score, difficulty = difficulty))
    }

    suspend fun getMaxScore(difficulty: String): Int {
        return highScoreDao.getMaxScore(difficulty) ?: 0
    }

    suspend fun clearScores() {
        highScoreDao.clearScores()
    }
}
