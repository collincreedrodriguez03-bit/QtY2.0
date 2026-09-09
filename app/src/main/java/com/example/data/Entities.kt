package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "price_ticks")
data class PriceTickEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val sourceTimestamp: Long?, // Nullable: NEVER substituted with local time if exchange timestamp is missing
    val localReceiptTimestamp: Long,
    val symbol: String,
    val price: Double?, // Nullable: never 0.0 when missing
    val volume: Double?, // Nullable: never 0.0 when missing
    val eventId: String?, // Provider event identity for duplicate & ordering detection
    val rawPayloadHash: String,
    val datasetVersionIdentity: String,
    val dataQualityStatus: String // "VALID", "INVALID", "DUPLICATE", "OUT_OF_ORDER", "UNAVAILABLE"
)

@Entity(tableName = "ingestion_events")
data class IngestionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val symbol: String,
    val status: String, // "UNAVAILABLE", "API_ERROR", "FAIL_CLOSED"
    val message: String?
)

@Entity(tableName = "predictions")
data class PredictionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val horizon: String,
    val status: String, // "NO_PREDICTION", "INSUFFICIENT_DATA", "COMPLETED"
    val predictedDirection: String?,
    val confidence: Double?,
    val targetPrice: Double?,
    val actualOutcome: String?,
    val isCorrect: Boolean?,
    val uncertainty: Double?,
    val dataQualityStatus: String
)

@Entity(tableName = "walk_forward_audits")
data class WalkForwardAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val horizon: String,
    val status: String, // "UNAVAILABLE", "COMPLETED"
    val outOfSampleWinRate: Double?,
    val totalValidated: Int?,
    val calibrationError: Double?,
    val featureRankJson: String?,
    val dataQualityStatus: String
)
