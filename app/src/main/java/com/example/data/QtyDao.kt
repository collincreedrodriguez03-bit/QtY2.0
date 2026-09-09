package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QtyDao {
    @Query("SELECT * FROM price_ticks ORDER BY localReceiptTimestamp DESC LIMIT 100")
    fun getRecentTicks(): Flow<List<PriceTickEntity>>

    @Query("SELECT * FROM price_ticks ORDER BY localReceiptTimestamp DESC LIMIT 1")
    suspend fun getLatestTick(): PriceTickEntity?

    @Query("SELECT COUNT(*) FROM price_ticks WHERE eventId = :eventId")
    suspend fun countTicksWithEventId(eventId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTick(tick: PriceTickEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIngestionEvent(event: IngestionEventEntity)

    @Query("SELECT * FROM ingestion_events ORDER BY timestamp DESC LIMIT 50")
    fun getRecentIngestionEvents(): Flow<List<IngestionEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeatureRecord(record: FeatureRecordEntity)

    @Query("SELECT * FROM feature_records ORDER BY timestamp DESC LIMIT 100")
    fun getRecentFeatureRecords(): Flow<List<FeatureRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeatureResearchEvaluation(evaluation: FeatureResearchEvaluationEntity)

    @Query("SELECT * FROM feature_research_evaluations ORDER BY evaluationTimestamp DESC LIMIT 50")
    fun getRecentFeatureResearchEvaluations(): Flow<List<FeatureResearchEvaluationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrainedModel(model: TrainedModelEntity)

    @Query("SELECT * FROM trained_models WHERE horizon = :horizon ORDER BY trainingEndTime DESC LIMIT 1")
    suspend fun getLatestTrainedModel(horizon: String): TrainedModelEntity?

    @Query("SELECT * FROM trained_models ORDER BY trainingEndTime DESC LIMIT 50")
    fun getRecentTrainedModels(): Flow<List<TrainedModelEntity>>

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
