package com.example.engine

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlin.math.exp
import kotlin.math.ln

@JsonClass(generateAdapter = true)
data class ModelParameters(
    val weights: List<Double>,
    val bias: Double
)

data class TrainingResult(
    val horizon: String,
    val success: Boolean,
    val sampleCount: Int,
    val parameters: ModelParameters?,
    val trainingDatasetIdentity: String,
    val featureSetVersion: String,
    val labelVersion: String,
    val trainingStartTime: Long,
    val trainingEndTime: Long,
    val parametersVersion: String,
    val message: String
)

class ModelTrainer {
    private val moshi = Moshi.Builder().build()
    private val paramsAdapter = moshi.adapter(ModelParameters::class.java)

    fun serializeParameters(params: ModelParameters): String {
        return paramsAdapter.toJson(params)
    }

    /**
     * Converts canonical horizon string to milliseconds. Returns null if unknown (never silently defaults).
     * Canonical horizons: 5s, 10s, 30s, 60s, 120s, 300s, 600s, 900s.
     */
    fun horizonToMs(horizon: String): Long? {
        return when (horizon) {
            "5s" -> 5000L
            "10s" -> 10000L
            "30s" -> 30000L
            "60s" -> 60000L
            "120s" -> 120000L
            "300s" -> 300000L
            "600s" -> 600000L
            "900s" -> 900000L
            else -> null
        }
    }

    /**
     * Constructs timestamp-correct realized log-return labels supporting UP, DOWN, FLAT/NO_DIRECTION
     * using explicit LabelPolicy and centralized TargetResolutionPolicy.
     */
    fun buildTrainingDataset(
        ticks: List<Pair<Double, Long>>,
        horizon: String
    ): List<Pair<List<Double>, Double>> {
        val horizonMs = horizonToMs(horizon) ?: return emptyList()
        val spec = ModelSpecifications.getSpecification(horizon) ?: return emptyList()
        val dataset = mutableListOf<Pair<List<Double>, Double>>()
        val pipeline = FeatureResearchPipeline()
        val flatThreshold = spec.labelPolicy.flatThreshold

        // Ensure chronological order and filter out invalid/missing source timestamps
        val sortedTicks = ticks.filter { it.second > 0L && it.first > 0.0 }.sortedBy { it.second }

        for (i in sortedTicks.indices) {
            val currentTick = sortedTicks[i]
            val t = currentTick.second
            val pT = currentTick.first

            val targetTime = t + horizonMs
            // Centralized TargetResolutionPolicy
            val futureTick = spec.targetResolutionPolicy.resolveTargetTick(sortedTicks, targetTime, horizonMs) ?: continue
            val historyTicks = sortedTicks.filter { it.second <= t }

            val featureValues = mutableListOf<Double>()
            var allValid = true

            for (featName in spec.orderedFeatureNames) {
                val lookback = if (featName.contains("1m") || featName.contains("60s")) 60000L else if (featName.contains("5m") || featName.contains("300s")) 300000L else 10000L
                val candidateSpec = FeatureCandidateSpec(featName, lookback)
                val featResult = pipeline.extractFeature(historyTicks, t, candidateSpec)
                if (featResult.validityStatus == "VALID" && featResult.value != null) {
                    featureValues.add(featResult.value)
                } else {
                    allValid = false
                    break
                }
            }

            if (allValid && featureValues.size == spec.orderedFeatureNames.size) {
                val pFuture = futureTick.first
                if (pT > 0.0 && pFuture > 0.0) {
                    val logReturn = ln(pFuture / pT)
                    when {
                        logReturn > flatThreshold -> dataset.add(Pair(featureValues, 1.0)) // UP
                        logReturn < -flatThreshold -> dataset.add(Pair(featureValues, 0.0)) // DOWN
                        else -> {
                            // FLAT / NO_DIRECTION: excluded from binary training dataset per explicit LabelPolicy
                        }
                    }
                }
            }
        }
        return dataset
    }

    /**
     * Trains a logistic regression model via gradient descent using exact ModelSpecification provenance.
     * Uses sourceTimestamp (min/max of training ticks) for training period provenance, never System.currentTimeMillis().
     */
    fun trainModel(
        ticks: List<Pair<Double, Long>>,
        horizon: String,
        trainingDatasetIdentity: String
    ): TrainingResult {
        val horizonMs = horizonToMs(horizon)
        val spec = ModelSpecifications.getSpecification(horizon)

        val sortedTicks = ticks.filter { it.second > 0L && it.first > 0.0 }.sortedBy { it.second }
        val trainingStartTime = sortedTicks.firstOrNull()?.second ?: 0L
        val trainingEndTime = sortedTicks.lastOrNull()?.second ?: 0L

        if (horizonMs == null || spec == null) {
            return TrainingResult(
                horizon = horizon,
                success = false,
                sampleCount = 0,
                parameters = null,
                trainingDatasetIdentity = trainingDatasetIdentity,
                featureSetVersion = "unknown",
                labelVersion = "unknown",
                trainingStartTime = trainingStartTime,
                trainingEndTime = trainingEndTime,
                parametersVersion = "v1",
                message = "Unknown or unsupported horizon: $horizon (fails closed)"
            )
        }

        val trainingData = buildTrainingDataset(sortedTicks, horizon)

        if (trainingData.size < 5) {
            return TrainingResult(
                horizon = horizon,
                success = false,
                sampleCount = trainingData.size,
                parameters = null,
                trainingDatasetIdentity = trainingDatasetIdentity,
                featureSetVersion = spec.featureSetVersion,
                labelVersion = spec.labelVersion,
                trainingStartTime = trainingStartTime,
                trainingEndTime = trainingEndTime,
                parametersVersion = spec.parametersVersion,
                message = "Insufficient samples for training (min 5 required)"
            )
        }

        val numFeatures = spec.orderedFeatureNames.size
        val weights = MutableList(numFeatures) { 0.0 }
        var bias = 0.0
        val lr = 0.05
        val epochs = 100
        val n = trainingData.size.toDouble()

        for (epoch in 0 until epochs) {
            val dWeights = MutableList(numFeatures) { 0.0 }
            var dBias = 0.0

            for ((features, label) in trainingData) {
                var z = bias
                for (j in features.indices) {
                    z += weights[j] * features[j]
                }
                val pred = 1.0 / (1.0 + exp(-maxOf(-30.0, minOf(30.0, z))))
                val error = pred - label

                for (j in features.indices) {
                    dWeights[j] += error * features[j]
                }
                dBias += error
            }

            for (j in weights.indices) {
                weights[j] -= lr * (dWeights[j] / n)
            }
            bias -= lr * (dBias / n)
        }

        return TrainingResult(
            horizon = horizon,
            success = true,
            sampleCount = trainingData.size,
            parameters = ModelParameters(weights, bias),
            trainingDatasetIdentity = trainingDatasetIdentity,
            featureSetVersion = spec.featureSetVersion,
            labelVersion = spec.labelVersion,
            trainingStartTime = trainingStartTime,
            trainingEndTime = trainingEndTime,
            parametersVersion = spec.parametersVersion,
            message = "Model trained successfully as BASELINE CONTROL — PRICE-MOMENTUM ONLY"
        )
    }
}
