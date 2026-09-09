package com.example.engine

import com.example.data.TrainedModelEntity

data class ValidationConfig(
    val minTrainingSamples: Int = 10,
    val minCalibrationSamples: Int = 5,
    val minOosSamples: Int = 5,
    val configVersion: String = "v1"
)

data class WalkForwardResult(
    val horizon: String,
    val status: String, // "COMPLETED", "UNAVAILABLE"
    val trainingDatasetIdentity: String?,
    val featureSetVersion: String?,
    val labelVersion: String?,
    val configVersion: String?,
    val trainStartTimestamp: Long?,
    val trainEndTimestamp: Long?,
    val calibrationStartTimestamp: Long?,
    val calibrationEndTimestamp: Long?,
    val oosStartTimestamp: Long?,
    val oosEndTimestamp: Long?,
    val sampleCount: Int?,
    val oosWinRate: Double?,
    val coverage: Double?,
    val abstentionRate: Double?,
    val truePositives: Int?,
    val falsePositives: Int?,
    val trueNegatives: Int?,
    val falseNegatives: Int?,
    val brierScore: Double?,
    val calibrationError: Double?,
    val message: String
)

class WalkForwardValidator(
    private val config: ValidationConfig = ValidationConfig()
) {
    private val trainer = ModelTrainer()
    private val predictionEngine = TrainedPredictionEngine()

    fun validate(
        ticks: List<Pair<Double, Long>>,
        horizon: String,
        trainingDatasetIdentity: String = "ds_v1"
    ): WalkForwardResult {
        val horizonMs = trainer.horizonToMs(horizon)
        val spec = ModelSpecifications.getSpecification(horizon)

        if (horizonMs == null || spec == null) {
            return WalkForwardResult(
                horizon = horizon,
                status = "UNAVAILABLE",
                trainingDatasetIdentity = trainingDatasetIdentity,
                featureSetVersion = spec?.featureSetVersion,
                labelVersion = spec?.labelVersion,
                configVersion = config.configVersion,
                trainStartTimestamp = null,
                trainEndTimestamp = null,
                calibrationStartTimestamp = null,
                calibrationEndTimestamp = null,
                oosStartTimestamp = null,
                oosEndTimestamp = null,
                sampleCount = null,
                oosWinRate = null,
                coverage = null,
                abstentionRate = null,
                truePositives = null,
                falsePositives = null,
                trueNegatives = null,
                falseNegatives = null,
                brierScore = null,
                calibrationError = null,
                message = "Invalid or unsupported horizon"
            )
        }

        // Sort ticks chronologically to guarantee no shuffling
        val sortedTicks = ticks.sortedBy { it.second }
        if (sortedTicks.isEmpty()) {
            return unavailableResult(horizon, trainingDatasetIdentity, spec, "Empty ticks")
        }

        // Chronological splits: Train (60%), Calibration (20%), OOS Test (20%)
        val totalSize = sortedTicks.size
        val trainEndIdx = (totalSize * 0.6).toInt()
        val calibEndIdx = (totalSize * 0.8).toInt()

        if (trainEndIdx < config.minTrainingSamples ||
            (calibEndIdx - trainEndIdx) < config.minCalibrationSamples ||
            (totalSize - calibEndIdx) < config.minOosSamples) {
            return unavailableResult(horizon, trainingDatasetIdentity, spec, "Insufficient samples for configured split requirements")
        }

        val trainTicks = sortedTicks.subList(0, trainEndIdx)
        val calibTicks = sortedTicks.subList(trainEndIdx, calibEndIdx)
        val oosTicks = sortedTicks.subList(calibEndIdx, totalSize)

        val trainStart = trainTicks.first().second
        val trainEnd = trainTicks.last().second
        val calibStart = calibTicks.first().second
        val calibEnd = calibTicks.last().second
        val oosStart = oosTicks.first().second
        val oosEnd = oosTicks.last().second

        // Train model strictly on training split
        val trainingResult = trainer.trainModel(trainTicks, horizon, trainingDatasetIdentity)
        if (!trainingResult.success || trainingResult.parameters == null) {
            return unavailableResult(horizon, trainingDatasetIdentity, spec, "Model training failed: ${trainingResult.message}")
        }

        val storedModel = TrainedModelEntity(
            horizon = horizon,
            trainingDatasetIdentity = trainingDatasetIdentity,
            featureSetVersion = spec.featureSetVersion,
            labelVersion = spec.labelVersion,
            trainingStartTime = trainStart,
            trainingEndTime = trainEnd,
            parametersJson = trainer.serializeParameters(trainingResult.parameters),
            parametersVersion = spec.parametersVersion,
            sampleCount = trainingResult.sampleCount,
            status = "TRAINED"
        )

        // Calibration evaluation: strictly receive ONLY observations with timestamp <= T
        var totalCalibEval = 0
        var calibSquaredErrorSum = 0.0
        for (i in 4 until calibTicks.size) {
            val t = calibTicks[i].second
            val ticksAtT = sortedTicks.filter { it.second <= t }
            val prediction = predictionEngine.evaluateHorizon(ticksAtT, t, storedModel)
            val futureTick = sortedTicks.firstOrNull { it.second >= t + horizonMs }
            if (prediction.status == "COMPLETED" && prediction.probability != null && futureTick != null) {
                val actual = if (futureTick.first > calibTicks[i].first) 1.0 else 0.0
                val err = prediction.probability - actual
                calibSquaredErrorSum += err * err
                totalCalibEval++
            }
        }
        val calibrationError = if (totalCalibEval > 0) kotlin.math.sqrt(calibSquaredErrorSum / totalCalibEval) else null

        // Final OOS Test Evaluation: strictly receive ONLY observations with timestamp <= T, and require realizable future label
        var attemptedOos = 0
        var validOos = 0
        var abstentions = 0
        var tp = 0
        var fp = 0
        var tn = 0
        var fn = 0
        var brierSquaredErrorSum = 0.0
        var correctPredictions = 0

        for (i in 4 until oosTicks.size) {
            val t = oosTicks[i].second
            attemptedOos++
            val ticksAtT = sortedTicks.filter { it.second <= t }
            val prediction = predictionEngine.evaluateHorizon(ticksAtT, t, storedModel)
            val futureTick = sortedTicks.firstOrNull { it.second >= t + horizonMs }

            // Count an OOS sample as valid only after BOTH a prediction exists AND a valid realized future outcome exists
            if (prediction.status != "COMPLETED" || prediction.probability == null || prediction.direction == null || futureTick == null) {
                abstentions++
                continue
            }

            validOos++
            val actualUp = futureTick.first > oosTicks[i].first
            val actualLabel = if (actualUp) 1.0 else 0.0
            val predictedUp = prediction.direction == "UP"

            val bErr = prediction.probability - actualLabel
            brierSquaredErrorSum += bErr * bErr

            val isCorrect = predictedUp == actualUp
            if (isCorrect) correctPredictions++

            if (predictedUp && actualUp) tp++
            else if (predictedUp && !actualUp) fp++
            else if (!predictedUp && !actualUp) tn++
            else if (!predictedUp && actualUp) fn++
        }

        if (validOos < config.minOosSamples) {
            return unavailableResult(horizon, trainingDatasetIdentity, spec, "Insufficient valid OOS samples ($validOos)")
        }

        val oosWinRate = if (validOos > 0) correctPredictions.toDouble() / validOos else null
        val coverage = if (attemptedOos > 0) validOos.toDouble() / attemptedOos else null
        val abstentionRate = if (attemptedOos > 0) abstentions.toDouble() / attemptedOos else null
        val brierScore = if (validOos > 0) brierSquaredErrorSum / validOos else null

        return WalkForwardResult(
            horizon = horizon,
            status = "COMPLETED",
            trainingDatasetIdentity = trainingDatasetIdentity,
            featureSetVersion = spec.featureSetVersion,
            labelVersion = spec.labelVersion,
            configVersion = config.configVersion,
            trainStartTimestamp = trainStart,
            trainEndTimestamp = trainEnd,
            calibrationStartTimestamp = calibStart,
            calibrationEndTimestamp = calibEnd,
            oosStartTimestamp = oosStart,
            oosEndTimestamp = oosEnd,
            sampleCount = validOos,
            oosWinRate = oosWinRate,
            coverage = coverage,
            abstentionRate = abstentionRate,
            truePositives = tp,
            falsePositives = fp,
            trueNegatives = tn,
            falseNegatives = fn,
            brierScore = brierScore,
            calibrationError = calibrationError,
            message = "Walk-forward OOS validation completed successfully"
        )
    }

    private fun unavailableResult(
        horizon: String,
        trainingDatasetIdentity: String,
        spec: ModelSpecification,
        message: String
    ): WalkForwardResult {
        return WalkForwardResult(
            horizon = horizon,
            status = "UNAVAILABLE",
            trainingDatasetIdentity = trainingDatasetIdentity,
            featureSetVersion = spec.featureSetVersion,
            labelVersion = spec.labelVersion,
            configVersion = config.configVersion,
            trainStartTimestamp = null,
            trainEndTimestamp = null,
            calibrationStartTimestamp = null,
            calibrationEndTimestamp = null,
            oosStartTimestamp = null,
            oosEndTimestamp = null,
            sampleCount = null,
            oosWinRate = null,
            coverage = null,
            abstentionRate = null,
            truePositives = null,
            falsePositives = null,
            trueNegatives = null,
            falseNegatives = null,
            brierScore = null,
            calibrationError = null,
            message = message
        )
    }
}
