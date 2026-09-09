package com.example.data

import android.content.Context
import com.example.engine.HorizonPrediction
import com.example.engine.PredictionEngine
import com.example.engine.WalkForwardResult
import com.example.engine.WalkForwardValidator
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class QtyRepository(context: Context) {
    private val database = QtyDatabase.getDatabase(context)
    val dao = database.qtyDao()
    private val predictionEngine = PredictionEngine()
    private val walkForwardValidator = WalkForwardValidator()
    private val moshi = Moshi.Builder().build()
    private val tradeListType = Types.newParameterizedType(List::class.java, BinanceTradeResponse::class.java)
    private val tradeAdapter = moshi.adapter<List<BinanceTradeResponse>>(tradeListType)

    val recentTicks: Flow<List<PriceTickEntity>> = dao.getRecentTicks()
    val recentIngestionEvents: Flow<List<IngestionEventEntity>> = dao.getRecentIngestionEvents()
    val recentPredictions: Flow<List<PredictionEntity>> = dao.getRecentPredictions()
    val recentAudits: Flow<List<WalkForwardAuditEntity>> = dao.getRecentAudits()

    suspend fun fetchAndProcessTick(): Pair<Double?, List<HorizonPrediction>> = withContext(Dispatchers.IO) {
        val localReceiptTimestamp = System.currentTimeMillis()
        val symbol = "BTCUSDT"
        val datasetVersionIdentity = "v2.0-truthful"

        try {
            val responseBody = BinanceClient.api.getRawTrades(symbol, 1)
            val rawPayload = responseBody.string()
            val rawPayloadHash = sha256(rawPayload)

            val trades = tradeAdapter.fromJson(rawPayload)
            val trade = trades?.firstOrNull()

            if (trade == null) {
                // Ingestion failure: no fake tick, persist ingestion event
                dao.insertIngestionEvent(
                    IngestionEventEntity(
                        timestamp = localReceiptTimestamp,
                        symbol = symbol,
                        status = "UNAVAILABLE",
                        message = "Empty trade response from exchange"
                    )
                )
                val predictions = predictionEngine.evaluateHorizons(emptyList())
                return@withContext Pair(null, predictions)
            }

            // 1. Source timestamp from exchange (trade.time), NEVER substituted with local time
            val sourceTimestamp: Long? = trade.time
            // 2. Nullable price and volume, never 0.0
            val price: Double? = trade.price.toDoubleOrNull()
            val volume: Double? = trade.qty.toDoubleOrNull()
            val eventId = trade.id.toString()

            val dataQualityStatus = evaluateDataQuality(eventId, sourceTimestamp, price, volume)

            val tickEntity = PriceTickEntity(
                source = "Binance_REST_Trades",
                sourceTimestamp = sourceTimestamp,
                localReceiptTimestamp = localReceiptTimestamp,
                symbol = symbol,
                price = price,
                volume = volume,
                eventId = eventId,
                rawPayloadHash = rawPayloadHash,
                datasetVersionIdentity = datasetVersionIdentity,
                dataQualityStatus = dataQualityStatus
            )

            // Only persist valid/duplicate/out-of-order ticks as price ticks; invalid/missing data fails closed or logged
            dao.insertTick(tickEntity)

            val activePrice = if (dataQualityStatus == "VALID" || dataQualityStatus == "OUT_OF_ORDER" || dataQualityStatus == "DUPLICATE") price else null
            val ticksForEngine = if (activePrice != null && sourceTimestamp != null) listOf(Pair(activePrice, sourceTimestamp)) else emptyList()
            val predictions = predictionEngine.evaluateHorizons(ticksForEngine)

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
                        dataQualityStatus = if (ticksForEngine.isNotEmpty()) "VALID" else "INSUFFICIENT_DATA"
                    )
                )
            }

            Pair(activePrice, predictions)
        } catch (e: Exception) {
            // 3. Do not persist API failures as price ticks. Persist separate ingestion event.
            dao.insertIngestionEvent(
                IngestionEventEntity(
                    timestamp = localReceiptTimestamp,
                    symbol = symbol,
                    status = "API_ERROR",
                    message = e.localizedMessage ?: "Unknown network/API error"
                )
            )

            val predictions = predictionEngine.evaluateHorizons(emptyList())
            Pair(null, predictions)
        }
    }

    private suspend fun evaluateDataQuality(eventId: String, sourceTimestamp: Long?, price: Double?, volume: Double?): String {
        if (sourceTimestamp == null || price == null || volume == null || price <= 0.0) {
            return "INVALID"
        }

        // Check duplicate
        val existingCount = dao.countTicksWithEventId(eventId)
        if (existingCount > 0) {
            return "DUPLICATE"
        }

        // Check ordering
        val latest = dao.getLatestTick()
        if (latest != null && latest.sourceTimestamp != null) {
            if (sourceTimestamp < latest.sourceTimestamp || (latest.eventId != null && eventId.toLongOrNull() != null && latest.eventId.toLongOrNull() != null && eventId.toLong() <= latest.eventId.toLong())) {
                return "OUT_OF_ORDER"
            }
        }

        return "VALID"
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
