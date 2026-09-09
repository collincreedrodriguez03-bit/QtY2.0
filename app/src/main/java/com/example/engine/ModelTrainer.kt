package com.example.engine

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlin.math.exp

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
     * Converts horizon string to milliseconds. Returns null if unknown (never silently defaults to 5s).
     */
    fun horizonToMs(horizon: String): Long? {
        return when (horizon) {
            "5s" -> 5000L
            "15s" -> 15000L
            "30s" -> 30000L
            "1m" -> 60000L
            "5m" -> 300000L
            "15m" -> 900000L
            else -> null
        }
    }

    /**
     * Constructs timestamp-correct realized future outcome labels without lookahead using ModelSpecification.
     */
    fun buildTrainingDataset(
        ticks: List<Pair<Double, Long>>,
        horizon: String
    ): List<Pair<List<Double>, Double>> {
        val horizonMs = horizonToMs(horizon) ?: return emptyList()
        val spec = ModelSpecifications.getSpecification(horizon) ?: return emptyList()
        val dataset = mutableListOf<Pair<List<Double>, Double>>()
        val pipeline = FeatureResearchPipeline()

        for (i in 4 until ticks.size) {
            val currentTick = ticks[i]
            val t = currentTick.second
            val pT = currentTick.first

            val targetTime = t + horizonMs
            val futureTick = ticks.firstOrNull { it.second >= targetTime } ?: continue
            val historyTicks = ticks.filter { it.second <= t }

            val featureValues = mutableListOf<Double>()
            var allValid = true

            for (featName in spec.orderedFeatureNames) {
                val lookback = if (featName.contains("1m")) 60000L else if (featName.contains("5m")) 300000L else 10000L
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
                val label = if (pFuture > pT) 1.0 else 0.0
                dataset.add(Pair(featureValues, label))
            }
        }
        return dataset
    }

    /**
     * Trains a logistic regression model via gradient descent using exact ModelSpecification provenance.
     */
    fun trainModel(
        ticks: List<Pair<Double, Long>>,
        horizon: String,
        trainingDatasetIdentity: String
    ): TrainingResult {
        val trainingStartTime = System.currentTimeMillis()
        val horizonMs = horizonToMs(horizon)
        val spec = ModelSpecifications.getSpecification(horizon)

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
                trainingEndTime = System.currentTimeMillis(),
                parametersVersion = "v1",
                message = "Unknown or unsupported horizon: $horizon (fails closed)"
            )
        }

        val trainingData = buildTrainingDataset(ticks, horizon)
        val trainingEndTime = System.currentTimeMillis()

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
            var db = 0.0
            for ((features, label) in trainingData) {
                var z = bias
                for (j in 0 until numFeatures) {
                    z += weights[j] * features[j]
                }
                val pred = 1.0 / (1.0 + exp(-maxOf(-30.0, minOf(30.0, z))))
                val err = pred - label
                for (j in 0 until numFeatures) {
                    dWeights[j] += err * features[j]
                }
                db += err
            }
            for (j in 0 until numFeatures) {
                weights[j] -= lr * (dWeights[j] / n)
            }
            bias -= lr * (db / n)
        }

        val parameters = ModelParameters(weights = weights, bias = bias)
        return TrainingResult(
            horizon = horizon,
            success = true,
            sampleCount = trainingData.size,
            parameters = parameters,
            trainingDatasetIdentity = trainingDatasetIdentity,
            featureSetVersion = spec.featureSetVersion,
            labelVersion = spec.labelVersion,
            trainingStartTime = trainingStartTime,
            trainingEndTime = trainingEndTime,
            parametersVersion = spec.parametersVersion,
            message = "Trained successfully"
        )
    }
}
