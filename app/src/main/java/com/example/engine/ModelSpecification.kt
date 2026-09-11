package com.example.engine

data class LabelPolicy(
    val version: String = "log_return_v2_explicit_flat",
    val flatThreshold: Double = 0.00005,
    val rationale: String = "Explicit dead-zone flatThreshold = 0.00005 (0.005%) to filter noise in high-frequency returns"
)

data class TargetResolutionPolicy(
    val maxToleranceMs: Long = 10000L
) {
    fun resolveTargetTick(ticks: List<Pair<Double, Long>>, targetTime: Long, horizonMs: Long): Pair<Double, Long>? {
        val tolerance = maxOf(maxToleranceMs, horizonMs)
        val maxTargetTime = targetTime + tolerance
        return ticks.firstOrNull { it.second >= targetTime && it.second <= maxTargetTime }
    }
}

data class ModelSpecification(
    val horizon: String,
    val orderedFeatureNames: List<String>,
    val featureSetVersion: String,
    val labelPolicy: LabelPolicy,
    val targetResolutionPolicy: TargetResolutionPolicy,
    val parametersVersion: String = "v1"
) {
    val labelVersion: String get() = labelPolicy.version
}

/**
 * BASELINE CONTROL — PRICE-MOMENTUM ONLY.
 * Canonical horizons ONLY: 5s, 10s, 30s, 60s, 120s, 300s, 600s, 900s.
 */
object ModelSpecifications {
    val supportedHorizons = listOf("5s", "10s", "30s", "60s", "120s", "300s", "600s", "900s")
    private val defaultLabelPolicy = LabelPolicy()
    private val defaultTargetResolutionPolicy = TargetResolutionPolicy()

    fun getSpecification(horizon: String): ModelSpecification? {
        return when (horizon) {
            "5s" -> ModelSpecification("5s", listOf("log_return_5s"), "v1", defaultLabelPolicy, defaultTargetResolutionPolicy, "v1")
            "10s" -> ModelSpecification("10s", listOf("log_return_10s"), "v1", defaultLabelPolicy, defaultTargetResolutionPolicy, "v1")
            "30s" -> ModelSpecification("30s", listOf("log_return_30s"), "v1", defaultLabelPolicy, defaultTargetResolutionPolicy, "v1")
            "60s" -> ModelSpecification("60s", listOf("log_return_60s"), "v1", defaultLabelPolicy, defaultTargetResolutionPolicy, "v1")
            "120s" -> ModelSpecification("120s", listOf("log_return_120s"), "v1", defaultLabelPolicy, defaultTargetResolutionPolicy, "v1")
            "300s" -> ModelSpecification("300s", listOf("log_return_300s"), "v1", defaultLabelPolicy, defaultTargetResolutionPolicy, "v1")
            "600s" -> ModelSpecification("600s", listOf("log_return_600s"), "v1", defaultLabelPolicy, defaultTargetResolutionPolicy, "v1")
            "900s" -> ModelSpecification("900s", listOf("log_return_900s"), "v1", defaultLabelPolicy, defaultTargetResolutionPolicy, "v1")
            else -> null // Unknown horizons fail closed
        }
    }
}
