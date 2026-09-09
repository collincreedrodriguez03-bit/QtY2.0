package com.example.engine

data class FeatureVector(
    val return1s: Double,
    val return5s: Double,
    val volatility: Double,
    val volumeDelta: Double,
    val isAuthentic: Boolean
)

class FeatureExtractor {
    fun extract(ticks: List<Pair<Double, Long>>): FeatureVector {
        if (ticks.size < 5) {
            return FeatureVector(0.0, 0.0, 0.0, 0.0, false)
        }
        val current = ticks.last().first
        val p1 = ticks[ticks.size - 2].first
        val p5 = ticks[maxOf(0, ticks.size - 5)].first

        val return1s = (current - p1) / p1
        val return5s = (current - p5) / p5

        val recentReturns = mutableListOf<Double>()
        for (i in 1 until minOf(ticks.size, 15)) {
            val prev = ticks[ticks.size - i - 1].first
            val curr = ticks[ticks.size - i].first
            recentReturns.add((curr - prev) / prev)
        }
        val meanReturn = if (recentReturns.isNotEmpty()) recentReturns.average() else 0.0
        val variance = if (recentReturns.size > 1) recentReturns.map { (it - meanReturn) * (it - meanReturn) }.average() else 0.0
        val volatility = kotlin.math.sqrt(variance)

        val volumeDelta = 0.0 // Requires authentic level-2 or trade volume stream

        return FeatureVector(
            return1s = return1s,
            return5s = return5s,
            volatility = volatility,
            volumeDelta = volumeDelta,
            isAuthentic = true
        )
    }
}
