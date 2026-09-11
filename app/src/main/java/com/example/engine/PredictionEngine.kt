package com.example.engine

import com.example.data.TrainedModelEntity

data class HorizonPrediction(
    val horizon: String,
    val status: String, // "NO_PREDICTION", "COMPLETED"
    val direction: String?,
    val confidence: Double?,
    val targetPrice: Double?,
    val uncertainty: Double?, // P0: uncertainty = null until defensible uncertainty exists
    val isSelectiveHighWinRate: Boolean
)

/**
 * Authoritative production prediction path wrapper/facade.
 * BASELINE CONTROL — PRICE-MOMENTUM ONLY.
 */
class PredictionEngine {
    private val trainedPredictionEngine = TrainedPredictionEngine()

    fun evaluateHorizons(ticks: List<Pair<Double, Long>>, modelsMap: Map<String, TrainedModelEntity?> = emptyMap()): List<HorizonPrediction> {
        val inferenceTimestamp = ticks.lastOrNull()?.second ?: System.currentTimeMillis()
        val trainedPredictions = trainedPredictionEngine.evaluateAllHorizons(ticks, inferenceTimestamp, modelsMap)

        return trainedPredictions.map { tp ->
            HorizonPrediction(
                horizon = tp.horizon,
                status = tp.status,
                direction = tp.direction,
                confidence = tp.probability,
                targetPrice = null,
                uncertainty = null, // P0 requirement: no fake uncertainty derived from probability
                isSelectiveHighWinRate = false
            )
        }
    }
}
