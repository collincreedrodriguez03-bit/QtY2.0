package com.example.engine

data class WalkForwardResult(
    val horizon: String,
    val status: String, // "UNAVAILABLE" until rigorous OOS validation is executed with authentic data
    val oosWinRate: Double?,
    val totalValidated: Int?,
    val calibrationError: Double?,
    val featureImportanceMap: Map<String, Double>?
)

class WalkForwardValidator {
    fun validate(historicalTicks: List<Pair<Double, Long>>, horizon: String): WalkForwardResult {
        // Fail closed: No fabricated metrics or fallback clamps.
        return WalkForwardResult(
            horizon = horizon,
            status = "UNAVAILABLE",
            oosWinRate = null,
            totalValidated = null,
            calibrationError = null,
            featureImportanceMap = null
        )
    }
}
