package com.example.engine

import kotlin.math.ln
import kotlin.math.sqrt

data class FeatureCandidateSpec(
    val featureName: String,
    val lookbackWindowMs: Long
)

data class FeatureCalculationResult(
    val featureName: String,
    val timestamp: Long,
    val lookbackWindowMs: Long,
    val sourceDataRangeStart: Long?,
    val sourceDataRangeEnd: Long?,
    val sampleCount: Int,
    val validityStatus: String, // "VALID", "INVALID", "INSUFFICIENT_DATA"
    val value: Double?
)

class FeatureResearchPipeline {

    private val supportedFeaturePrefixes = listOf(
        "log_return",
        "rolling_volatility",
        "trade_arrival_rate",
        "mean_volume",
        "sum_volume"
    )

    /**
     * Calculates candidate features at a specific feature timestamp T using ONLY observations at or before T (No-Lookahead enforcement).
     */
    fun extractFeature(
        ticks: List<Pair<Double, Long>>,
        featureTimestamp: Long,
        spec: FeatureCandidateSpec
    ): FeatureCalculationResult {
        // Enforce unknown feature name check
        val isSupported = supportedFeaturePrefixes.any { spec.featureName.startsWith(it) }
        if (!isSupported) {
            return FeatureCalculationResult(
                featureName = spec.featureName,
                timestamp = featureTimestamp,
                lookbackWindowMs = spec.lookbackWindowMs,
                sourceDataRangeStart = null,
                sourceDataRangeEnd = null,
                sampleCount = 0,
                validityStatus = "INVALID",
                value = null
            )
        }

        // Enforce no-lookahead: filter ticks strictly <= featureTimestamp
        val eligibleTicks = ticks.filter { it.second <= featureTimestamp }

        val lookbackStart = featureTimestamp - spec.lookbackWindowMs
        val windowTicks = eligibleTicks.filter { it.second >= lookbackStart }

        val sampleCount = windowTicks.size
        val dataRangeStart = windowTicks.firstOrNull()?.second
        val dataRangeEnd = windowTicks.lastOrNull()?.second

        val minRequired = if (spec.featureName.contains("return") || spec.featureName.contains("volatility")) 2 else 1

        if (sampleCount < minRequired || windowTicks.isEmpty()) {
            return FeatureCalculationResult(
                featureName = spec.featureName,
                timestamp = featureTimestamp,
                lookbackWindowMs = spec.lookbackWindowMs,
                sourceDataRangeStart = dataRangeStart,
                sourceDataRangeEnd = dataRangeEnd,
                sampleCount = sampleCount,
                validityStatus = "INSUFFICIENT_DATA",
                value = null
            )
        }

        val value: Double? = when {
            spec.featureName.startsWith("log_return") -> {
                val startPrice = windowTicks.first().first
                val endPrice = windowTicks.last().first
                if (startPrice <= 0.0 || endPrice <= 0.0) null
                else ln(endPrice / startPrice)
            }
            spec.featureName.startsWith("rolling_volatility") -> {
                if (windowTicks.size < 2) null
                else {
                    val returns = mutableListOf<Double>()
                    for (i in 1 until windowTicks.size) {
                        val pPrev = windowTicks[i - 1].first
                        val pCurr = windowTicks[i].first
                        if (pPrev > 0.0 && pCurr > 0.0) {
                            returns.add(ln(pCurr / pPrev))
                        }
                    }
                    if (returns.size < 2) null
                    else {
                        val mean = returns.average()
                        val variance = returns.map { (it - mean) * (it - mean) }.average()
                        sqrt(variance)
                    }
                }
            }
            spec.featureName.startsWith("trade_arrival_rate") -> {
                val durationSec = spec.lookbackWindowMs / 1000.0
                if (durationSec <= 0.0) 0.0
                else sampleCount / durationSec
            }
            else -> null
        }

        val validityStatus = if (value != null && !value.isNaN() && !value.isInfinite()) "VALID" else "INVALID"
        val finalValue = if (validityStatus == "VALID") value else null

        return FeatureCalculationResult(
            featureName = spec.featureName,
            timestamp = featureTimestamp,
            lookbackWindowMs = spec.lookbackWindowMs,
            sourceDataRangeStart = dataRangeStart,
            sourceDataRangeEnd = dataRangeEnd,
            sampleCount = sampleCount,
            validityStatus = if (finalValue != null) "VALID" else "INVALID",
            value = finalValue
        )
    }

    /**
     * Overload for volume-based features using Triple<Double (Price), Double? (Volume), Long (Timestamp)>
     * Stricter missing-data policy: if any volume in the window is null, return INVALID / INSUFFICIENT_DATA with null value (no silent partial averaging).
     */
    fun extractVolumeFeature(
        ticksWithVolume: List<Triple<Double, Double?, Long>>,
        featureTimestamp: Long,
        spec: FeatureCandidateSpec
    ): FeatureCalculationResult {
        val isSupported = supportedFeaturePrefixes.any { spec.featureName.startsWith(it) }
        if (!isSupported) {
            return FeatureCalculationResult(
                featureName = spec.featureName,
                timestamp = featureTimestamp,
                lookbackWindowMs = spec.lookbackWindowMs,
                sourceDataRangeStart = null,
                sourceDataRangeEnd = null,
                sampleCount = 0,
                validityStatus = "INVALID",
                value = null
            )
        }

        val eligibleTicks = ticksWithVolume.filter { it.third <= featureTimestamp }
        val lookbackStart = featureTimestamp - spec.lookbackWindowMs
        val windowTicks = eligibleTicks.filter { it.third >= lookbackStart }

        val sampleCount = windowTicks.size
        val dataRangeStart = windowTicks.firstOrNull()?.third
        val dataRangeEnd = windowTicks.lastOrNull()?.third

        if (sampleCount < 1 || windowTicks.isEmpty()) {
            return FeatureCalculationResult(
                featureName = spec.featureName,
                timestamp = featureTimestamp,
                lookbackWindowMs = spec.lookbackWindowMs,
                sourceDataRangeStart = dataRangeStart,
                sourceDataRangeEnd = dataRangeEnd,
                sampleCount = sampleCount,
                validityStatus = "INSUFFICIENT_DATA",
                value = null
            )
        }

        // Strict missing data policy: if any volume in the window is null, do not silently average partial data. Return INVALID.
        val hasMissingVolume = windowTicks.any { it.second == null }
        if (hasMissingVolume) {
            return FeatureCalculationResult(
                featureName = spec.featureName,
                timestamp = featureTimestamp,
                lookbackWindowMs = spec.lookbackWindowMs,
                sourceDataRangeStart = dataRangeStart,
                sourceDataRangeEnd = dataRangeEnd,
                sampleCount = sampleCount,
                validityStatus = "INVALID",
                value = null
            )
        }

        val value: Double? = when {
            spec.featureName.startsWith("mean_volume") -> {
                val volumes = windowTicks.mapNotNull { it.second }
                if (volumes.isEmpty()) null
                else volumes.average()
            }
            spec.featureName.startsWith("sum_volume") -> {
                val volumes = windowTicks.mapNotNull { it.second }
                if (volumes.isEmpty()) null
                else volumes.sum()
            }
            else -> null
        }

        val validityStatus = if (value != null && !value.isNaN() && !value.isInfinite()) "VALID" else "INVALID"
        val finalValue = if (validityStatus == "VALID") value else null

        return FeatureCalculationResult(
            featureName = spec.featureName,
            timestamp = featureTimestamp,
            lookbackWindowMs = spec.lookbackWindowMs,
            sourceDataRangeStart = dataRangeStart,
            sourceDataRangeEnd = dataRangeEnd,
            sampleCount = sampleCount,
            validityStatus = if (finalValue != null) "VALID" else "INVALID",
            value = finalValue
        )
    }
}
