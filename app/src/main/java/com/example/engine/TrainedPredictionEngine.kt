package com.example.engine

import com.example.data.TrainedModelEntity
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

    /**
     * Evaluates a specific horizon independently using ONLY its own trained parameters and matching provenance metadata.
     * Returns NO_PREDICTION if stored model metadata does not match active ModelSpecification.
     */
    fun evaluateHorizon(
        ticks: List<Pair<Double, Long>>,
        inferenceTimestamp: Long,
        storedModel: TrainedModelEntity?
    ): TrainedHorizonPrediction {
        if (storedModel == null) {
            return TrainedHorizonPrediction("unknown", "NO_PREDICTION", null, null, null, false)
        }

        val horizon = storedModel.horizon
        val spec = ModelSpecifications.getSpecification(horizon)
        if (spec == null) {
            return TrainedHorizonPrediction(horizon, "NO_PREDICTION", null, null, null, false)
        }

        // Verify provenance and metadata against active ModelSpecification
        if (storedModel.featureSetVersion != spec.featureSetVersion ||
            storedModel.labelVersion != spec.labelVersion ||
            storedModel.parametersVersion != spec.parametersVersion) {
            return TrainedHorizonPrediction(horizon, "NO_PREDICTION", null, null, null, false)
        }

        if (storedModel.parametersJson.isNullOrBlank()) {
            return TrainedHorizonPrediction(horizon, "NO_PREDICTION", null, null, null, false)
        }

        val params = try {
            paramsAdapter.fromJson(storedModel.parametersJson)
        } catch (e: Exception) {
            null
        }

        if (params == null || params.weights.size != spec.orderedFeatureNames.size) {
            return TrainedHorizonPrediction(horizon, "NO_PREDICTION", null, null, null, false)
        }

        // Enforce no-lookahead: feature extraction only uses ticks <= inferenceTimestamp
        val historyTicks = ticks.filter { it.second <= inferenceTimestamp }
        val featureValues = mutableListOf<Double>()

        for (featName in spec.orderedFeatureNames) {
            val lookback = if (featName.contains("1m")) 60000L else if (featName.contains("5m")) 300000L else 10000L
            val candidateSpec = FeatureCandidateSpec(featName, lookback)
            val featResult = pipeline.extractFeature(historyTicks, inferenceTimestamp, candidateSpec)
            if (featResult.validityStatus == "VALID" && featResult.value != null) {
                featureValues.add(featResult.value)
            } else {
                return TrainedHorizonPrediction(horizon, "NO_PREDICTION", null, null, null, false)
            }
        }

        // Mathematical inference from trained parameters only
        var z = params.bias
        for (i in featureValues.indices) {
            z += params.weights[i] * featureValues[i]
        }
        val prob = 1.0 / (1.0 + exp(-maxOf(-30.0, minOf(30.0, z))))

        val direction = if (prob >= 0.5) "UP" else "DOWN"
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
        modelsMap: Map<String, TrainedModelEntity?>
    ): List<TrainedHorizonPrediction> {
        return ModelSpecifications.supportedHorizons.map { horizon ->
            evaluateHorizon(ticks, inferenceTimestamp, modelsMap[horizon])
        }
    }
}
