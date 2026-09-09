package com.example.engine

data class HorizonPrediction(
    val horizon: String,
    val direction: String, // "UP" or "DOWN"
    val confidence: Double, // 0.0 to 1.0 (target >0.90 for selective trades)
    val targetPrice: Double,
    val uncertainty: Double,
    val isSelectiveHighWinRate: Boolean
)

class PredictionEngine {
    private val featureExtractor = FeatureExtractor()

    fun evaluateHorizons(prices: List<Double>, volumes: List<Double>): List<HorizonPrediction> {
        if (prices.isEmpty()) return emptyList()
        val currentPrice = prices.last()
        val features = featureExtractor.extract(prices, volumes)

        val horizons = listOf("5s", "30s", "1m", "5m", "15m")
        val weights = mapOf(
            "5s" to 1.0,
            "30s" to 1.5,
            "1m" to 2.0,
            "5m" to 3.5,
            "15m" to 5.0
        )

        return horizons.map { horizon ->
            val w = weights[horizon] ?: 1.0
            val score = (features.return1s * 2.0 + features.return5s * 1.5 + features.emaSlope * 3.0 + (features.orderBookImbalance - 0.5) * 2.0) * w
            val direction = if (score >= 0.0) "UP" else "DOWN"
            
            // Confidence calculation based on feature strength and low volatility
            val rawConfidence = 0.5 + minOf(0.45, kotlin.math.abs(score) / (1.0 + features.volatility * 100))
            val confidence = maxOf(0.51, minOf(0.96, rawConfidence))
            
            val expectedMove = currentPrice * features.return1s * w * 0.5
            val targetPrice = currentPrice + expectedMove
            val uncertainty = features.volatility * currentPrice * sqrtOfHorizon(horizon)
            val isSelectiveHighWinRate = confidence >= 0.88

            HorizonPrediction(
                horizon = horizon,
                direction = direction,
                confidence = confidence,
                targetPrice = targetPrice,
                uncertainty = uncertainty,
                isSelectiveHighWinRate = isSelectiveHighWinRate
            )
        }
    }

    private fun sqrtOfHorizon(horizon: String): Double {
        return when (horizon) {
            "5s" -> 1.0
            "30s" -> 2.45
            "1m" -> 3.46
            "5m" -> 7.74
            "15m" -> 13.41
            else -> 1.0
        }
    }
}
