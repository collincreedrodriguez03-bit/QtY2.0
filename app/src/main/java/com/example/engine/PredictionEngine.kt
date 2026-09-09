package com.example.engine

data class HorizonPrediction(
    val horizon: String,
    val status: String, // "NO_PREDICTION" until trained model exists
    val direction: String?,
    val confidence: Double?,
    val targetPrice: Double?,
    val uncertainty: Double?,
    val isSelectiveHighWinRate: Boolean
)

class PredictionEngine {
    private val featureExtractor = FeatureExtractor()

    fun evaluateHorizons(ticks: List<Pair<Double, Long>>): List<HorizonPrediction> {
        val horizons = listOf("5s", "30s", "1m", "5m", "15m")
        // Fail closed: Until trained and validated model exists, return NO_PREDICTION rather than invented probabilities.
        return horizons.map { horizon ->
            HorizonPrediction(
                horizon = horizon,
                status = "NO_PREDICTION",
                direction = null,
                confidence = null,
                targetPrice = null,
                uncertainty = null,
                isSelectiveHighWinRate = false
            )
        }
    }
}
