package com.example.engine

import com.squareup.moshi.Moshi
import kotlin.math.exp

data class TrainedHorizonPrediction(
    val horizon: String,
    val status: String, // "NO_PREDICTION", "COMPLETED"
    val direction: String?, // "UP" or "DOWN"
    val probability: Double?, // mathematically produced by trained model
    val uncertainty: Double?,
    val isTrained: Boolean
)

class TrainedPredictionEngine {
    private val moshi = Moshi.Builder().build()
    private val paramsAdapter = moshi.adapter(ModelParameters::class.java)
    private val pipeline = FeatureResearchPipeline()

    private val supportedHorizons = listOf("5s", "15s", "30s", "1m", "5m", "15m")

    /**
     * Evaluates a specific horizon independently using ONLY its own trained parameters.
     * Never derives one horizon from another.
     */
    fun evaluateHorizon(
        ticks: List<Pair<Double, Long>>,
        inferenceTimestamp: Long,
        horizon: String,
        parametersJson: String?
    ): TrainedHorizonPrediction {
        if (!supportedHorizons.contains(horizon)) {
            return TrainedHorizonPrediction(horizon, "NO_PREDICTION", null, null, null, false)
        }

        // Abstention if no trained parameters exist
        if (parametersJson.isNullOrBlank()) {
            return TrainedHorizonPrediction(horizon, "NO_PREDICTION", null, null, null, false)
        }

        val params = try {
            paramsAdapter.fromJson(parametersJson)
        } catch (e: Exception) {
            null
        }

        if (params == null || params.weights.isEmpty()) {
            return TrainedHorizonPrediction(horizon, "NO_PREDICTION", null, null, null, false)
        }

        // Enforce no-lookahead: feature extraction only uses ticks <= inferenceTimestamp
        val spec = FeatureCandidateSpec("log_return_5s", 10000L)
        val featResult = pipeline.extractFeature(ticks, inferenceTimestamp, spec)

        if (featResult.validityStatus != "VALID" || featResult.value == null) {
            return TrainedHorizonPrediction(horizon, "NO_PREDICTION", null, null, null, false)
        }

        // Mathematical inference from trained parameters only
        val weight = params.weights[0]
        val bias = params.bias
        val x = featResult.value
        val z = weight * x + bias
        val prob = 1.0 / (1.0 + exp(-maxOf(-30.0, minOf(30.0, z))))

        val direction = if (prob >= 0.5) "UP" else "DOWN"
        // Uncertainty derived from distance from 0.5 boundary
        val uncertainty = 1.0 - (2.0 * kotlin.math.abs(prob - 0.5))

        return TrainedHorizonPrediction(
            horizon = horizon,
            status = "COMPLETED",
            direction = direction,
            probability = prob,
            uncertainty = uncertainty,
            isTrained = true
        )
    }

    /**
     * Evaluates all independent horizons.
     */
    fun evaluateAllHorizons(
        ticks: List<Pair<Double, Long>>,
        inferenceTimestamp: Long,
        modelParamsMap: Map<String, String?>
    ): List<TrainedHorizonPrediction> {
        return supportedHorizons.map { horizon ->
            evaluateHorizon(ticks, inferenceTimestamp, horizon, modelParamsMap[horizon])
        }
    }
}
