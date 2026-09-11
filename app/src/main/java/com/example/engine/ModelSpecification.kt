package com.example.engine

data class ModelSpecification(
    val horizon: String,
    val orderedFeatureNames: List<String>,
    val featureSetVersion: String,
    val labelVersion: String,
    val parametersVersion: String = "v1"
)

/**
 * BASELINE CONTROL — PRICE-MOMENTUM ONLY.
 * Canonical horizons ONLY: 5s, 10s, 30s, 60s, 120s, 300s, 600s, 900s.
 */
object ModelSpecifications {
    val supportedHorizons = listOf("5s", "10s", "30s", "60s", "120s", "300s", "600s", "900s")

    fun getSpecification(horizon: String): ModelSpecification? {
        return when (horizon) {
            "5s" -> ModelSpecification("5s", listOf("log_return_5s"), "v1", "log_return_v2", "v1")
            "10s" -> ModelSpecification("10s", listOf("log_return_10s"), "v1", "log_return_v2", "v1")
            "30s" -> ModelSpecification("30s", listOf("log_return_30s"), "v1", "log_return_v2", "v1")
            "60s" -> ModelSpecification("60s", listOf("log_return_60s"), "v1", "log_return_v2", "v1")
            "120s" -> ModelSpecification("120s", listOf("log_return_120s"), "v1", "log_return_v2", "v1")
            "300s" -> ModelSpecification("300s", listOf("log_return_300s"), "v1", "log_return_v2", "v1")
            "600s" -> ModelSpecification("600s", listOf("log_return_600s"), "v1", "log_return_v2", "v1")
            "900s" -> ModelSpecification("900s", listOf("log_return_900s"), "v1", "log_return_v2", "v1")
            else -> null // Unknown horizons fail closed
        }
    }
}
