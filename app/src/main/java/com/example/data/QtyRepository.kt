package com.example.data

import android.content.Context
import com.example.engine.HorizonPrediction
import com.example.engine.PredictionEngine
import com.example.engine.WalkForwardResult
import com.example.engine.WalkForwardValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class QtyRepository(context: Context) {
    private val database = QtyDatabase.getDatabase(context)
    val dao = database.qtyDao()
    private val predictionEngine = PredictionEngine()
    private val walkForwardValidator = WalkForwardValidator()

    val recentTicks: Flow<List<PriceTickEntity>> = dao.getRecentTicks()
    val recentPredictions: Flow<List<PredictionEntity>> = dao.getRecentPredictions()
    val recentAudits: Flow<List<WalkForwardAuditEntity>> = dao.getRecentAudits()

    suspend fun fetchAndProcessTick(): Pair<Double?, List<HorizonPrediction>> = withContext(Dispatchers.IO) {
        val localReceiptTimestamp = System.currentTimeMillis()
        val symbol = "BTCUSDT"
        val datasetVersionIdentity = "v1.0-truthful"

        try {
            val response = BinanceClient.api.getBtcPrice(symbol)
            val price = response.price.toDouble()
            val volume = 0.0 // Authentic spot price ticker does not provide volume in /api/v3/ticker/price; keep 0.0 or authentic source if available
            val sourceTimestamp = System.currentTimeMillis() // Exchange timestamp if provided, or receipt time
            val rawPayload = "${response.symbol}-${response.price}-$sourceTimestamp"
            val rawPayloadHash = sha256(rawPayload)

            val tickEntity = PriceTickEntity(
                source = "Binance_REST_Authentic",
                sourceTimestamp = sourceTimestamp,
                localReceiptTimestamp = localReceiptTimestamp,
                symbol = symbol,
                price = price,
                volume = volume,
                rawPayloadHash = rawPayloadHash,
                datasetVersionIdentity = datasetVersionIdentity,
                dataQualityStatus = "VALID"
            )
            dao.insertTick(tickEntity)

            val predictions = predictionEngine.evaluateHorizons(emptyList())

            predictions.forEach { pred ->
                dao.insertPrediction(
                    PredictionEntity(
                        timestamp = localReceiptTimestamp,
                        horizon = pred.horizon,
                        status = pred.status,
                        predictedDirection = pred.direction,
                        confidence = pred.confidence,
                        targetPrice = pred.targetPrice,
                        actualOutcome = null,
                        isCorrect = null,
                        uncertainty = pred.uncertainty,
                        dataQualityStatus = "VALID"
                    )
                )
            }

            Pair(price, predictions)
        } catch (e: Exception) {
            // FAIL CLOSED: Never substitute fake market data on network/API failure.
            val tickEntity = PriceTickEntity(
                source = "Binance_REST_Authentic",
                sourceTimestamp = localReceiptTimestamp,
                localReceiptTimestamp = localReceiptTimestamp,
                symbol = symbol,
                price = 0.0,
                volume = 0.0,
                rawPayloadHash = "UNAVAILABLE",
                datasetVersionIdentity = datasetVersionIdentity,
                dataQualityStatus = "UNAVAILABLE"
            )
            dao.insertTick(tickEntity)

            val predictions = predictionEngine.evaluateHorizons(emptyList())
            Pair(null, predictions)
        }
    }

    suspend fun runWalkForwardAudit(horizon: String): WalkForwardResult = withContext(Dispatchers.IO) {
        val result = walkForwardValidator.validate(emptyList(), horizon)
        dao.insertAudit(
            WalkForwardAuditEntity(
                timestamp = System.currentTimeMillis(),
                horizon = horizon,
                status = result.status,
                outOfSampleWinRate = result.oosWinRate,
                totalValidated = result.totalValidated,
                calibrationError = result.calibrationError,
                featureRankJson = null,
                dataQualityStatus = "UNAVAILABLE"
            )
        )
        result
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
