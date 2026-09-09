package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "price_ticks")
data class PriceTickEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val price: Double,
    val volume: Double,
    val source: String,
    val provenanceHash: String
)

@Entity(tableName = "predictions")
data class PredictionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val horizon: String, // "5s", "30s", "1m", "5m", "15m"
    val predictedDirection: String, // "UP" or "DOWN"
    val confidence: Double,
    val targetPrice: Double,
    val actualOutcome: String?, // "UP", "DOWN", or null if pending
    val isCorrect: Boolean?,
    val uncertainty: Double
)

@Entity(tableName = "walk_forward_audits")
data class WalkForwardAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val horizon: String,
    val outOfSampleWinRate: Double,
    val totalValidated: Int,
    val calibrationError: Double,
    val featureRankJson: String
)
