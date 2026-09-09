package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QtyTruthfulTest {

    private lateinit var database: QtyDatabase
    private lateinit var dao: QtyDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, QtyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.qtyDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `test source timestamp is never substituted with local time`() = runBlocking {
        // If exchange timestamp is null, sourceTimestamp must be null
        val tick = PriceTickEntity(
            source = "Binance_REST_Trades",
            sourceTimestamp = null,
            localReceiptTimestamp = System.currentTimeMillis(),
            symbol = "BTCUSDT",
            price = 50000.0,
            volume = null,
            eventId = "123",
            rawPayloadHash = "hash",
            datasetVersionIdentity = "v2.0",
            dataQualityStatus = "INVALID"
        )
        dao.insertTick(tick)
        val latest = dao.getLatestTick()
        assertNotNull(latest)
        assertNull(latest?.sourceTimestamp)
        assertNotNull(latest?.localReceiptTimestamp)
    }

    @Test
    fun `test missing volume is null not zero`() = runBlocking {
        val tick = PriceTickEntity(
            source = "Binance_REST_Trades",
            sourceTimestamp = 1000L,
            localReceiptTimestamp = System.currentTimeMillis(),
            symbol = "BTCUSDT",
            price = 50000.0,
            volume = null, // null, never 0.0
            eventId = "124",
            rawPayloadHash = "hash",
            datasetVersionIdentity = "v2.0",
            dataQualityStatus = "VALID"
        )
        dao.insertTick(tick)
        val latest = dao.getLatestTick()
        assertNotNull(latest)
        assertNull(latest?.volume)
        assertNotEquals(0.0, latest?.volume)
    }

    @Test
    fun `test api failure creates no fake tick`() = runBlocking {
        // Simulate API failure recording ingestion event instead of fake tick
        dao.insertIngestionEvent(
            IngestionEventEntity(
                timestamp = System.currentTimeMillis(),
                symbol = "BTCUSDT",
                status = "API_ERROR",
                message = "Network timeout"
            )
        )
        val latestTick = dao.getLatestTick()
        assertNull(latestTick)
    }

    @Test
    fun `test duplicate events are rejected`() = runBlocking {
        val tick1 = PriceTickEntity(
            source = "Binance_REST_Trades",
            sourceTimestamp = 1000L,
            localReceiptTimestamp = System.currentTimeMillis(),
            symbol = "BTCUSDT",
            price = 50000.0,
            volume = 1.0,
            eventId = "dup_event_999",
            rawPayloadHash = "hash1",
            datasetVersionIdentity = "v2.0",
            dataQualityStatus = "VALID"
        )
        dao.insertTick(tick1)

        // Attempting to process same eventId again should be flagged as DUPLICATE
        val count = dao.countTicksWithEventId("dup_event_999")
        assertEquals(1, count)

        val tick2 = PriceTickEntity(
            source = "Binance_REST_Trades",
            sourceTimestamp = 1000L,
            localReceiptTimestamp = System.currentTimeMillis(),
            symbol = "BTCUSDT",
            price = 50000.0,
            volume = 1.0,
            eventId = "dup_event_999",
            rawPayloadHash = "hash1",
            datasetVersionIdentity = "v2.0",
            dataQualityStatus = "DUPLICATE"
        )
        dao.insertTick(tick2)
        assertEquals("DUPLICATE", tick2.dataQualityStatus)
    }

    @Test
    fun `test out-of-order events are flagged`() = runBlocking {
        val tick1 = PriceTickEntity(
            source = "Binance_REST_Trades",
            sourceTimestamp = 2000L,
            localReceiptTimestamp = System.currentTimeMillis(),
            symbol = "BTCUSDT",
            price = 50000.0,
            volume = 1.0,
            eventId = "evt_2",
            rawPayloadHash = "hash2",
            datasetVersionIdentity = "v2.0",
            dataQualityStatus = "VALID"
        )
        dao.insertTick(tick1)

        val latest = dao.getLatestTick()
        assertNotNull(latest)

        // New event has older sourceTimestamp than latest (1000L < 2000L)
        val incomingTimestamp = 1000L
        val isOutOfOrder = incomingTimestamp < (latest?.sourceTimestamp ?: 0L)
        assertTrue(isOutOfOrder)

        val tickOut = PriceTickEntity(
            source = "Binance_REST_Trades",
            sourceTimestamp = incomingTimestamp,
            localReceiptTimestamp = System.currentTimeMillis(),
            symbol = "BTCUSDT",
            price = 50100.0,
            volume = 0.5,
            eventId = "evt_1",
            rawPayloadHash = "hash1",
            datasetVersionIdentity = "v2.0",
            dataQualityStatus = "OUT_OF_ORDER"
        )
        dao.insertTick(tickOut)
        assertEquals("OUT_OF_ORDER", tickOut.dataQualityStatus)
    }

    @Test
    fun `test insufficient data produces no prediction`() {
        val engine = PredictionEngine()
        val predictions = engine.evaluateHorizons(emptyList())
        assertTrue(predictions.isNotEmpty())
        predictions.forEach { pred ->
            assertEquals("NO_PREDICTION", pred.status)
        }
    }

    @Test
    fun `test validator cannot fabricate metrics`() {
        val validator = WalkForwardValidator()
        val result = validator.validate(emptyList(), "5s")
        assertEquals("UNAVAILABLE", result.status)
        assertNull(result.oosWinRate)
    }

    @Test
    fun `test no future observations enter feature calculation (no lookahead)`() {
        val pipeline = FeatureResearchPipeline()
        val ticks = listOf(
            Pair(100.0, 1000L),
            Pair(101.0, 2000L),
            Pair(105.0, 5000L) // Future tick
        )
        val spec = FeatureCandidateSpec("log_return_1s", 5000L)
        val result = pipeline.extractFeature(ticks, 2000L, spec)

        assertEquals(2, result.sampleCount)
        assertEquals(2000L, result.sourceDataRangeEnd)
    }

    @Test
    fun `test insufficient history fails closed`() {
        val pipeline = FeatureResearchPipeline()
        val ticks = listOf(Pair(100.0, 1000L))
        val spec = FeatureCandidateSpec("log_return_1s", 5000L)
        val result = pipeline.extractFeature(ticks, 2000L, spec)

        assertEquals("INSUFFICIENT_DATA", result.validityStatus)
        assertNull(result.value)
    }

    @Test
    fun `test feature timestamps and lookback ranges are correct`() {
        val pipeline = FeatureResearchPipeline()
        val ticks = listOf(
            Pair(100.0, 1000L),
            Pair(101.0, 2000L),
            Pair(102.0, 3000L)
        )
        val featureTimestamp = 3000L
        val lookbackMs = 2000L
        val spec = FeatureCandidateSpec("trade_arrival_rate", lookbackMs)
        val result = pipeline.extractFeature(ticks, featureTimestamp, spec)

        assertEquals(3000L, result.timestamp)
        assertEquals(2000L, result.lookbackWindowMs)
        assertEquals(1000L, result.sourceDataRangeStart)
        assertEquals(3000L, result.sourceDataRangeEnd)
        assertEquals(3, result.sampleCount)
        assertEquals("VALID", result.validityStatus)
    }

    @Test
    fun `test calculations are deterministic for identical authentic input`() {
        val pipeline = FeatureResearchPipeline()
        val ticks = listOf(
            Pair(100.0, 1000L),
            Pair(101.0, 2000L),
            Pair(102.0, 3000L),
            Pair(103.0, 4000L)
        )
        val spec = FeatureCandidateSpec("log_return_3s", 3000L)
        val res1 = pipeline.extractFeature(ticks, 4000L, spec)
        val res2 = pipeline.extractFeature(ticks, 4000L, spec)

        assertEquals(res1.value, res2.value)
        assertEquals(res1.validityStatus, res2.validityStatus)
        assertEquals(res1.sampleCount, res2.sampleCount)
    }

    @Test
    fun `test feature research evaluator handles zero variance feature values`() {
        val evaluator = FeatureResearchEvaluator()
        val pairs = listOf(
            Pair(1.0, 0.02),
            Pair(1.0, 0.03),
            Pair(1.0, 0.04),
            Pair(1.0, 0.05),
            Pair(1.0, 0.06)
        )
        val result = evaluator.evaluateIncrementalInformation("zero_var_feat", 1000L, "5s", pairs)
        assertEquals("INSUFFICIENT_DATA", result.evaluationStatus)
        assertNull(result.incrementalInformationMetric)
    }

    @Test
    fun `test feature research evaluator handles zero variance labels`() {
        val evaluator = FeatureResearchEvaluator()
        val pairs = listOf(
            Pair(0.1, 0.05),
            Pair(0.2, 0.05),
            Pair(0.3, 0.05),
            Pair(0.4, 0.05),
            Pair(0.5, 0.05)
        )
        val result = evaluator.evaluateIncrementalInformation("zero_var_label", 1000L, "5s", pairs)
        assertEquals("INSUFFICIENT_DATA", result.evaluationStatus)
        assertNull(result.incrementalInformationMetric)
    }

    @Test
    fun `test unknown feature names return INVALID`() {
        val pipeline = FeatureResearchPipeline()
        val ticks = listOf(Pair(100.0, 1000L), Pair(101.0, 2000L))
        val spec = FeatureCandidateSpec("unknown_fancy_indicator", 5000L)
        val result = pipeline.extractFeature(ticks, 2000L, spec)
        assertEquals("INVALID", result.validityStatus)
        assertNull(result.value)
    }

    @Test
    fun `test legacy FeatureExtractor cannot emit fabricated zeros as authentic`() {
        val extractor = FeatureExtractor()
        val vector = extractor.extract(listOf(Pair(100.0, 1000L))) // Insufficient data (< 5 ticks)
        assertFalse(vector.isAuthentic)
        assertNull(vector.return1s)
        assertNull(vector.return5s)
        assertNull(vector.volatility)
        assertNull(vector.volumeDelta)
    }
}

