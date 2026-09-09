package com.example.engine

import kotlin.math.sqrt

data class ResearchEvaluationResult(
    val featureName: String,
    val evaluationTimestamp: Long,
    val horizon: String,
    val sampleSize: Int,
    val incrementalInformationMetric: Double?, // Information Coefficient / correlation
    val evaluationStatus: String // "COMPLETED", "INSUFFICIENT_DATA"
)

class FeatureResearchEvaluator {

    /**
     * Measures incremental predictive information (Information Coefficient / Correlation)
     * between feature values and future realized returns without leakage.
     */
    fun evaluateIncrementalInformation(
        featureName: String,
        evaluationTimestamp: Long,
        horizon: String,
        pairs: List<Pair<Double, Double>> // Pair(FeatureValue, FutureReturn)
    ): ResearchEvaluationResult {
        val validPairs = pairs.filter { !it.first.isNaN() && !it.second.isNaN() && !it.first.isInfinite() && !it.second.isInfinite() }
        val sampleSize = validPairs.size

        if (sampleSize < 5) {
            return ResearchEvaluationResult(
                featureName = featureName,
                evaluationTimestamp = evaluationTimestamp,
                horizon = horizon,
                sampleSize = sampleSize,
                incrementalInformationMetric = null,
                evaluationStatus = "INSUFFICIENT_DATA"
            )
        }

        val features = validPairs.map { it.first }
        val returns = validPairs.map { it.second }

        val meanF = features.average()
        val meanR = returns.average()

        var num = 0.0
        var den1 = 0.0
        var den2 = 0.0

        for (i in 0 until sampleSize) {
            val df = features[i] - meanF
            val dr = returns[i] - meanR
            num += df * dr
            den1 += df * df
            den2 += dr * dr
        }

        // Zero variance check: if den1 == 0.0 or den2 == 0.0, correlation is undefined.
        // Return null and INSUFFICIENT_DATA — never substitute 0.0 and mark COMPLETED.
        if (den1 <= 0.0 || den2 <= 0.0) {
            return ResearchEvaluationResult(
                featureName = featureName,
                evaluationTimestamp = evaluationTimestamp,
                horizon = horizon,
                sampleSize = sampleSize,
                incrementalInformationMetric = null,
                evaluationStatus = "INSUFFICIENT_DATA"
            )
        }

        val correlation = num / sqrt(den1 * den2)

        if (correlation.isNaN() || correlation.isInfinite()) {
            return ResearchEvaluationResult(
                featureName = featureName,
                evaluationTimestamp = evaluationTimestamp,
                horizon = horizon,
                sampleSize = sampleSize,
                incrementalInformationMetric = null,
                evaluationStatus = "INSUFFICIENT_DATA"
            )
        }

        return ResearchEvaluationResult(
            featureName = featureName,
            evaluationTimestamp = evaluationTimestamp,
            horizon = horizon,
            sampleSize = sampleSize,
            incrementalInformationMetric = correlation,
            evaluationStatus = "COMPLETED"
        )
    }
}
