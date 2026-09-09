package com.example.engine

import kotlin.math.sqrt

data class FeatureVector(
    val return1s: Double?,
    val return5s: Double?,
    val volatility: Double?,
    val volumeDelta: Double?,
    val isAuthentic: Boolean
)

class FeatureExtractor {
    fun extract(ticks: List<Pair<Double, Long>>): FeatureVector {
        if (ticks.size < 5) {
            return FeatureVector(null, null, null, null, false)
        }
        val current = ticks.last().first
        val p1 = ticks[ticks.size - 2].first
        val p5 = ticks[maxOf(0, ticks.size - 5)].first

        val return1s = if (p1 > 0.0) (current - p1) / p1 else null
        val return5s = if (p5 > 0.0) (current - p5) / p5 else null

        val recentReturns = mutableListOf<Double>()
        for (i in 1 until minOf(ticks.size, 15)) {
            val prev = ticks[ticks.size - i - 1].first
            val curr = ticks[ticks.size - i].first
            if (prev > 0.0 && curr > 0.0) {
                recentReturns.add((curr - prev) / prev)
            }
        }
        if (recentReturns.size < 2) {
            return FeatureVector(return1s, return5s, null, null, false)
        }
        val meanReturn = recentReturns.average()
        val variance = recentReturns.map { (it - meanReturn) * (it - meanReturn) }.average()
        val volatility = sqrt(variance)

        return FeatureVector(
            return1s = return1s,
            return5s = return5s,
            volatility = if (volatility.isNaN() || volatility.isInfinite()) null else volatility,
            volumeDelta = null,
            isAuthentic = true
        )
    }
}
