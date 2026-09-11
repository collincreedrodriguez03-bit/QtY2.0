package com.example.engine

import kotlin.math.ln

/**
 * Versioned, explicit research LabelPolicy with provenance and documented decision rationale.
 * Represents UP, DOWN, and FLAT classification based on realized log-returns with explicit dead-zone threshold.
 */
data class LabelPolicy(
    val version: String = "log_return_v3_research_explicit_flat",
    val flatThreshold: Double = 0.00005,
    val researchProvenance: String = "Explicit dead-zone flatThreshold = 0.00005 (0.005%) with documented research rationale: filters micro-noise in high-frequency BTC ticks while supporting UP, DOWN, and FLAT classification."
) {
    fun classify(logReturn: Double): String {
        return when {
            logReturn > flatThreshold -> "UP"
            logReturn < -flatThreshold -> "DOWN"
            else -> "FLAT"
        }
    }
}

/**
 * Exact TargetResolutionPolicy semantics for resolving T+h:
 * - Exact target timestamp: T_target = T + horizonMs
 * - Permitted resolution window: [T_target, T_target + maxToleranceMs] (WITHOUT implicit max(horizon, tolerance) widening)
 * - Selection rule when multiple observations exist: firstOrNull (earliest chronological observation within permitted window)
 * - Failure behavior when no valid observation exists: returns null (fails closed, no filling/lookahead)
 */
data class TargetResolutionPolicy(
    val maxToleranceMs: Long = 10000L,
    val documentation: String = "Exact T_target = T + horizonMs. Window: [T_target, T_target + maxToleranceMs]. Selection: firstOrNull. Failure: null (fails closed)."
) {
    fun resolveTargetTick(ticks: List<Pair<Double, Long>>, targetTime: Long): Pair<Double, Long>? {
        val maxTargetTime = targetTime + maxToleranceMs
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

    /**
     * Shared Outcome Resolver ensuring the exact SAME LabelPolicy and TargetResolutionPolicy
     * are used across training, calibration, OOS validation, and live outcome resolution.
     */
    fun resolveOutcome(
        ticks: List<Pair<Double, Long>>,
        predictionTimestamp: Long,
        horizon: String,
        horizonMs: Long
    ): String? {
        val spec = getSpecification(horizon) ?: return null
        val targetTime = predictionTimestamp + horizonMs
        val currentTick = ticks.firstOrNull { it.second == predictionTimestamp } ?: ticks.filter { it.second <= predictionTimestamp }.maxByOrNull { it.second } ?: return null
        val futureTick = spec.targetResolutionPolicy.resolveTargetTick(ticks, targetTime) ?: return null
        if (currentTick.first <= 0.0 || futureTick.first <= 0.0) return null
        val logReturn = ln(futureTick.first / currentTick.first)
        return spec.labelPolicy.classify(logReturn)
    }
}
