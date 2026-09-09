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
    val message: String
)

class ModelTrainer {
    private val moshi = Moshi.Builder().build()
    private val paramsAdapter = moshi.adapter(ModelParameters::class.java)

    fun serializeParameters(params: ModelParameters): String {
        return paramsAdapter.toJson(params)
    }

    /**
     * Converts horizon string to milliseconds.
     */
    fun horizonToMs(horizon: String): Long {
        return when (horizon) {
            "5s" -> 5000L
            "15s" -> 15000L
            "30s" -> 30000L
            "1m" -> 60000L
            "5m" -> 300000L
            "15m" -> 900000L
            else -> 5000L
        }
    }

    /**
     * Constructs timestamp-correct realized future outcome labels without lookahead.
     * Label = 1.0 if price at (T + horizon) > price at T, else 0.0.
     */
    fun buildTrainingDataset(
        ticks: List<Pair<Double, Long>>,
        horizon: String
    ): List<Pair<List<Double>, Double>> {
        val horizonMs = horizonToMs(horizon)
        val dataset = mutableListOf<Pair<List<Double>, Double>>()
        val pipeline = FeatureResearchPipeline()

        // We need at least 5 ticks for features and valid future tick at T + horizon
        for (i in 4 until ticks.size) {
            val currentTick = ticks[i]
            val t = currentTick.second
            val pT = currentTick.first

            // Future tick requirement: must be >= T + horizon
            val targetTime = t + horizonMs
            val futureTick = ticks.firstOrNull { it.second >= targetTime } ?: continue

            // Strict no-lookahead enforcement: future tick timestamp must be strictly >= targetTime
            // and we only use history <= t for feature extraction
            val historyTicks = ticks.filter { it.second <= t }
            val spec = FeatureCandidateSpec("log_return_5s", 10000L)
            val featResult = pipeline.extractFeature(historyTicks, t, spec)

            if (featResult.validityStatus == "VALID" && featResult.value != null) {
                val pFuture = futureTick.first
                val label = if (pFuture > pT) 1.0 else 0.0
                val features = listOf(featResult.value)
                dataset.add(Pair(features, label))
            }
        }
        return dataset
    }

    /**
     * Trains a simple logistic regression model via gradient descent on historical data.
     */
    fun trainModel(
        ticks: List<Pair<Double, Long>>,
        horizon: String,
        trainingDatasetIdentity: String
    ): TrainingResult {
        val trainingData = buildTrainingDataset(ticks, horizon)
        if (trainingData.size < 5) {
            return TrainingResult(
                horizon = horizon,
                success = false,
                sampleCount = trainingData.size,
                parameters = null,
                message = "Insufficient samples for training (min 5 required)"
            )
        }

        // Gradient descent for logistic regression
        var weight = 0.0
        var bias = 0.0
        val lr = 0.05
        val epochs = 100
        val n = trainingData.size.toDouble()

        for (epoch in 0 until epochs) {
            var dw = 0.0
            var db = 0.0
            for ((features, label) in trainingData) {
                val x = features[0]
                val z = weight * x + bias
                val pred = 1.0 / (1.0 + exp(-maxOf(-30.0, minOf(30.0, z))))
                val err = pred - label
                dw += err * x
                db += err
            }
            weight -= lr * (dw / n)
            bias -= lr * (db / n)
        }

        val parameters = ModelParameters(weights = listOf(weight), bias = bias)
        return TrainingResult(
            horizon = horizon,
            success = true,
            sampleCount = trainingData.size,
            parameters = parameters,
            message = "Trained successfully"
        )
    }
}
