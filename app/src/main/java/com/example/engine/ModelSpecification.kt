package com.example.engine

data class ModelSpecification(
    val horizon: String,
    val orderedFeatureNames: List<String>,
    val featureSetVersion: String,
    val labelVersion: String,
    val parametersVersion: String = "v1"
)

object ModelSpecifications {
    fun getSpecification(horizon: String): ModelSpecification? {
        return when (horizon) {
            "5s" -> ModelSpecification("5s", listOf("log_return_5s"), "v1", "price_up_v1", "v1")
            "15s" -> ModelSpecification("15s", listOf("log_return_15s"), "v1", "price_up_v1", "v1")
            "30s" -> ModelSpecification("30s", listOf("log_return_30s"), "v1", "price_up_v1", "v1")
            "1m" -> ModelSpecification("1m", listOf("log_return_1m"), "v1", "price_up_v1", "v1")
            "5m" -> ModelSpecification("5m", listOf("log_return_5m"), "v1", "price_up_v1", "v1")
            "15m" -> ModelSpecification("15m", listOf("log_return_15m"), "v1", "price_up_v1", "v1")
            else -> null
        }
    }

    val supportedHorizons = listOf("5s", "15s", "30s", "1m", "5m", "15m")
}
