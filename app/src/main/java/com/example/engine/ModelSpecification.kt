package com.example.engine

import kotlin.math.ln

/**
 * Constant Classification Audit:
 * 1. Algorithm Parameters: Weights initialization, linear combination coefficients (bias).
 * 2. Engineering Safeguards: Minimum sample requirements, positive price/timestamp filters.
 * 3. Research Configurations: flatThreshold (unvalidated heuristic research config, not universal market truth), horizon-aware tolerances.
 */

/**
 * Versioned, explicit research LabelPolicy with unvalidated heuristic provenance.
 * Represents UP, DOWN, and FLAT classification based on realized log-returns with explicit threshold.
 * NOTE: flatThreshold = 0.00005 is classified as a Research Configuration (heuristic unvalidated config),
 * NOT an empirical universal market truth. Requires ongoing empirical validation.
 */
data class LabelPolicy(
    val version: String = "log_return_v3_heuristic_unvalidated",
    val flatThreshold: Double = 0.00005,
    val researchProvenance: String = "Research Configuration (Unvalidated Heuristic): flatThreshold = 0.00005 (0.005%). Not an empirical universal market truth; filters micro-noise based on heuristic calibration and requires continuous empirical validation."
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
 * - Permitted resolution window: [T_target, T_target + dynamicTolerance] (Horizon-aware, avoiding silent universal 10s tolerance on short horizons like 5s)
 * - Selection rule when multiple observations exist: firstOrNull (earliest chronological observation within permitted window)
 * - Failure behavior when no valid observation exists: returns null (fails closed, no filling/lookahead)
 * 
 * Classification: Research Configuration & Engineering Safeguard.
 */
data class TargetResolutionPolicy(
    val version: String = "resolution_v2_horizon_aware",
    val maxToleranceMs: Long = 10000L,
    val documentation: String = "Horizon-aware target resolution policy: T_target = T + horizonMs. Window: [T_target, T_target + dynamicTolerance] where dynamicTolerance scales with horizon (5s -> 2000ms, 10s -> 3000ms, 30s -> 5000ms, 60s+ -> 10000ms). Selection: firstOrNull. Failure: null (fails closed)."
) {
    fun resolveTargetTick(ticks: List<Pair<Double, Long>>, targetTime: Long, horizonMs: Long): Pair<Double, Long>? {
        val dynamicTolerance = when {
            horizonMs <= 5000L -> 2000L
            horizonMs <= 10000L -> 3000L
            horizonMs <= 30000L -> 5000L
            else -> maxToleranceMs
        }
        val maxTargetTime = targetTime + dynamicTolerance
        return ticks.firstOrNull { it.second >= targetTime && it.second <= maxTargetTime }
    }

    // Backward-compatible overload
    fun resolveTargetTick(ticks: List<Pair<Double, Long>>, targetTime: Long): Pair<Double, Long>? {
        return resolveTargetTick(ticks, targetTime, 60000L)
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
        val futureTick = spec.targetResolutionPolicy.resolveTargetTick(ticks, targetTime, horizonMs) ?: return null
        if (currentTick.first <= 0.0 || futureTick.first <= 0.0) return null
        val logReturn = ln(futureTick.first / currentTick.first)
        return spec.labelPolicy.classify(logReturn)
    }
}

