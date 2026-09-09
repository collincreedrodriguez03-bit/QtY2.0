package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QtyDao {
    @Query("SELECT * FROM price_ticks ORDER BY timestamp DESC LIMIT 100")
    fun getRecentTicks(): Flow<List<PriceTickEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTick(tick: PriceTickEntity)

    @Query("SELECT * FROM predictions ORDER BY timestamp DESC LIMIT 50")
    fun getRecentPredictions(): Flow<List<PredictionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrediction(prediction: PredictionEntity)

    @Query("UPDATE predictions SET actualOutcome = :outcome, isCorrect = :correct WHERE id = :id")
    suspend fun updatePredictionOutcome(id: Long, outcome: String, correct: Boolean)

    @Query("SELECT * FROM walk_forward_audits ORDER BY timestamp DESC LIMIT 20")
    fun getRecentAudits(): Flow<List<WalkForwardAuditEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudit(audit: WalkForwardAuditEntity)

    @Query("SELECT AVG(CASE WHEN isCorrect = 1 THEN 1.0 ELSE 0.0 END) FROM predictions WHERE horizon = :horizon AND actualOutcome IS NOT NULL")
    suspend fun getWinRateForHorizon(horizon: String): Double?
}
