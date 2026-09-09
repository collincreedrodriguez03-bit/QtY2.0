package com.example.engine

import kotlin.math.sqrt

data class FeatureVector(
    val return1s: Double,
    val return5s: Double,
    val volatility: Double,
    val orderBookImbalance: Double,
    val volumeDelta: Double,
    val emaSlope: Double
)

class FeatureExtractor {
    fun extract(prices: List<Double>, volumes: List<Double>): FeatureVector {
        if (prices.size < 10) {
            return FeatureVector(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val current = prices.last()
        val p1 = prices[prices.size - 2]
        val p5 = prices[maxOf(0, prices.size - 5)]
        
        val return1s = (current - p1) / p1
        val return5s = (current - p5) / p5

        // Volatility (standard deviation of recent returns)
        val recentReturns = mutableListOf<Double>()
        for (i in 1 until minOf(prices.size, 20)) {
            val prev = prices[prices.size - i - 1]
            val curr = prices[prices.size - i]
            recentReturns.add((curr - prev) / prev)
        }
        val meanReturn = if (recentReturns.isNotEmpty()) recentReturns.average() else 0.0
        val variance = if (recentReturns.size > 1) recentReturns.map { (it - meanReturn) * (it - meanReturn) }.average() else 0.0
        val volatility = sqrt(variance)

        // Order Book Imbalance & volume delta proxy
        val vCurr = if (volumes.isNotEmpty()) volumes.last() else 1.0
        val vPrev = if (volumes.size > 1) volumes[volumes.size - 2] else 1.0
        val volumeDelta = (vCurr - vPrev) / (vPrev + 1e-8)
        val orderBookImbalance = if (return1s >= 0) 0.55 + minOf(return1s * 10, 0.4) else 0.45 + maxOf(return1s * 10, -0.4)

        // EMA slope proxy
        val emaSlope = (current - prices[prices.size - minOf(10, prices.size)]) / prices[prices.size - minOf(10, prices.size)]

        return FeatureVector(
            return1s = return1s,
            return5s = return5s,
            volatility = volatility,
            orderBookImbalance = orderBookImbalance,
            volumeDelta = volumeDelta,
            emaSlope = emaSlope
        )
    }
}
