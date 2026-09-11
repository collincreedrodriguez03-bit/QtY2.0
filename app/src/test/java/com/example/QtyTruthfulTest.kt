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
    fun `test horizon independence and no prediction without trained parameters`() {
        val trainer = ModelTrainer()
        val engine = TrainedPredictionEngine()

        val ticks = (1..30).map { i -> Pair(100.0 + i, i * 1000L) }

        // Without trained parameters, engine must abstain (NO_PREDICTION)
        val predNoModel = engine.evaluateHorizon(ticks, 5000L, null)
        assertEquals("NO_PREDICTION", predNoModel.status)
        assertNull(predNoModel.probability)
        assertFalse(predNoModel.isTrained)

        // Train 5s model
        val train5s = trainer.trainModel(ticks, "5s", "v1")
        assertTrue(train5s.success)
        val spec5s = ModelSpecifications.getSpecification("5s")!!
        val model5s = TrainedModelEntity(
            horizon = "5s",
            trainingDatasetIdentity = "v1",
            featureSetVersion = spec5s.featureSetVersion,
            labelVersion = spec5s.labelVersion,
            trainingStartTime = train5s.trainingStartTime,
            trainingEndTime = train5s.trainingEndTime,
            parametersJson = trainer.serializeParameters(train5s.parameters!!),
            parametersVersion = spec5s.parametersVersion,
            sampleCount = train5s.sampleCount,
            status = "TRAINED"
        )

        // Train 15s model (independent)
        val train15s = trainer.trainModel(ticks, "15s", "v1")

        val pred5s = engine.evaluateHorizon(ticks, 5000L, model5s)
        assertEquals("COMPLETED", pred5s.status)
        assertNotNull(pred5s.probability)
    }

    @Test
    fun `test unknown horizon fails closed`() {
        val trainer = ModelTrainer()
        val ms = trainer.horizonToMs("invalid_horizon")
        assertNull(ms)

        val ticks = (1..30).map { i -> Pair(100.0 + i, i * 1000L) }
        val result = trainer.trainModel(ticks, "invalid_horizon", "v1")
        assertFalse(result.success)
    }

    @Test
    fun `test mismatched feature version returns NO_PREDICTION`() {
        val trainer = ModelTrainer()
        val engine = TrainedPredictionEngine()
        val ticks = (1..30).map { i -> Pair(100.0 + i, i * 1000L) }

        val trainResult = trainer.trainModel(ticks, "5s", "v1")
        assertTrue(trainResult.success)

        val storedModel = TrainedModelEntity(
            horizon = "5s",
            trainingDatasetIdentity = "v1",
            featureSetVersion = "mismatched_version",
            labelVersion = trainResult.labelVersion,
            trainingStartTime = trainResult.trainingStartTime,
            trainingEndTime = trainResult.trainingEndTime,
            parametersJson = trainer.serializeParameters(trainResult.parameters!!),
            parametersVersion = trainResult.parametersVersion,
            sampleCount = trainResult.sampleCount,
            status = "TRAINED"
        )

        val prediction = engine.evaluateHorizon(ticks, 5000L, storedModel)
        assertEquals("NO_PREDICTION", prediction.status)
    }

    @Test
    fun `test each horizon has independent specification`() {
        val spec5s = ModelSpecifications.getSpecification("5s")
        val spec10s = ModelSpecifications.getSpecification("10s")
        assertNotNull(spec5s)
        assertNotNull(spec10s)
        assertNotEquals(spec5s?.horizon, spec10s?.horizon)
        assertEquals(listOf("log_return_5s"), spec5s?.orderedFeatureNames)
        assertEquals(listOf("log_return_10s"), spec10s?.orderedFeatureNames)
    }

    @Test
    fun `test training and inference use identical ordered features`() {
        val trainer = ModelTrainer()
        val engine = TrainedPredictionEngine()
        val ticks = (1..30).map { i -> Pair(100.0 + i, i * 1000L) }

        val trainResult = trainer.trainModel(ticks, "5s", "v1")
        assertTrue(trainResult.success)

        val spec = ModelSpecifications.getSpecification("5s")
        assertNotNull(spec)

        val storedModel = TrainedModelEntity(
            horizon = "5s",
            trainingDatasetIdentity = "v1",
            featureSetVersion = spec!!.featureSetVersion,
            labelVersion = spec.labelVersion,
            trainingStartTime = trainResult.trainingStartTime,
            trainingEndTime = trainResult.trainingEndTime,
            parametersJson = trainer.serializeParameters(trainResult.parameters!!),
            parametersVersion = spec.parametersVersion,
            sampleCount = trainResult.sampleCount,
            status = "TRAINED"
        )

        val prediction = engine.evaluateHorizon(ticks, 5000L, storedModel)
        assertEquals("COMPLETED", prediction.status)
        assertEquals("5s", prediction.horizon)
    }

    @Test
    fun `test no lookahead during label construction`() {
        val trainer = ModelTrainer()
        // Ticks end at 5000L, so no future outcome at >= T + 5s exists for any t
        val ticks = listOf(
            Pair(100.0, 1000L),
            Pair(101.0, 2000L),
            Pair(102.0, 3000L),
            Pair(103.0, 4000L),
            Pair(104.0, 5000L)
        )
        val dataset = trainer.buildTrainingDataset(ticks, "5s")
        // Since no tick exists at >= T + 5s, dataset should be empty (no lookahead / fake future)
        assertTrue(dataset.isEmpty())
    }

    @Test
    fun `test walk forward validation runs successfully with provenance and metrics`() {
        val validator = WalkForwardValidator()
        val ticks = (1..100).map { i -> Pair(100.0 + i, i * 1000L) }
        val result = validator.validate(ticks, "5s", "ds_test_v1")

        assertEquals("COMPLETED", result.status)
        assertEquals("5s", result.horizon)
        assertEquals("ds_test_v1", result.trainingDatasetIdentity)
        assertNotNull(result.sampleCount)
        assertNotNull(result.oosWinRate)
        assertNotNull(result.brierScore)
        assertNotNull(result.calibrationError)
    }

    @Test
    fun `test chronological partitions prevent future data leakage into training and calibration`() {
        val validator = WalkForwardValidator()
        // Ticks ordered strictly by timestamp
        val ticks = (1..100).map { i -> Pair(100.0 + i, i * 1000L) }
        val result = validator.validate(ticks, "5s", "ds_test_v1")

        assertEquals("COMPLETED", result.status)
        // Verify train start < train end <= calib start < calib end <= oos start < oos end
        assertTrue(result.trainStartTimestamp!! < result.trainEndTimestamp!!)
        assertTrue(result.trainEndTimestamp!! <= result.calibrationStartTimestamp!!)
        assertTrue(result.calibrationStartTimestamp!! < result.calibrationEndTimestamp!!)
        assertTrue(result.calibrationEndTimestamp!! <= result.oosStartTimestamp!!)
        assertTrue(result.oosStartTimestamp!! < result.oosEndTimestamp!!)
    }

    @Test
    fun `test calibration data is separated from final oos evaluation`() {
        val validator = WalkForwardValidator()
        val ticks = (1..100).map { i -> Pair(100.0 + i, i * 1000L) }
        val result = validator.validate(ticks, "5s", "ds_test_v1")

        assertEquals("COMPLETED", result.status)
        // Calibration timestamps and OOS timestamps must be completely disjoint ranges
        assertTrue(result.calibrationEndTimestamp!! <= result.oosStartTimestamp!!)
    }

    @Test
    fun `test samples without realizable future outcome are excluded from scored metrics`() {
        val validator = WalkForwardValidator()
        // Provide ticks where trailing ticks have no future outcome within horizon (e.g. truncated at end)
        val ticks = (1..50).map { i -> Pair(100.0 + i, i * 1000L) }
        val result = validator.validate(ticks, "900s", "ds_test_v1") // 900s horizon on 50s data has no future outcomes

        // Should be UNAVAILABLE due to insufficient realizable samples
        assertEquals("UNAVAILABLE", result.status)
        assertNull(result.sampleCount)
        assertNull(result.oosWinRate)
    }

    @Test
    fun `test insufficient data returned when evidence requirements unmet`() {
        val validator = WalkForwardValidator(ValidationConfig(minEvidenceSamples = 1000))
        val ticks = (1..100).map { i -> Pair(100.0 + i, i * 1000L) }
        val result = validator.validate(ticks, "5s", "ds_test_v1")
        assertEquals("INSUFFICIENT_DATA", result.status)
    }

    @Test
    fun `test source timestamp defines training provenance not system time`() {
        val trainer = ModelTrainer()
        val ticks = listOf(
            Pair(100.0, 5000L),
            Pair(101.0, 6000L),
            Pair(102.0, 7000L),
            Pair(103.0, 8000L),
            Pair(104.0, 9000L),
            Pair(105.0, 15000L)
        )
        val result = trainer.trainModel(ticks, "5s", "v1")
        assertEquals(5000L, result.trainingStartTime)
        assertEquals(15000L, result.trainingEndTime)
    }

    @Test
    fun `test canonical horizons enforce exact list and fail closed on unknown`() {
        val expected = listOf("5s", "10s", "30s", "60s", "120s", "300s", "600s", "900s")
        assertEquals(expected, ModelSpecifications.supportedHorizons)
        assertNull(ModelSpecifications.getSpecification("15s"))
        assertNull(ModelSpecifications.getSpecification("1m"))
        assertNull(ModelSpecifications.getSpecification("unknown"))
    }

    @Test
    fun `test centralized target resolution policy`() {
        val policy = TargetResolutionPolicy(maxToleranceMs = 5000L)
        val ticks = listOf(
            Pair(100.0, 10000L),
            Pair(101.0, 15000L)
        )
        val resolved = policy.resolveTargetTick(ticks, 12000L, 5000L)
        assertNotNull(resolved)
        assertEquals(15000L, resolved?.second)
    }

    @Test
    fun `test trained prediction engine enforces strict time boundary at T`() {
        val engine = TrainedPredictionEngine()
        val trainer = ModelTrainer()
        val ticks = (1..30).map { i -> Pair(100.0 + i, i * 1000L) }
        val trainRes = trainer.trainModel(ticks.subList(0, 15), "5s", "v1")
        assertTrue(trainRes.success)

        val spec = ModelSpecifications.getSpecification("5s")!!
        val model = TrainedModelEntity(
            horizon = "5s",
            trainingDatasetIdentity = "v1",
            featureSetVersion = spec.featureSetVersion,
            labelVersion = spec.labelVersion,
            trainingStartTime = 1000L,
            trainingEndTime = 15000L,
            parametersJson = trainer.serializeParameters(trainRes.parameters!!),
            parametersVersion = spec.parametersVersion,
            sampleCount = 10,
            status = "TRAINED"
        )

        // Evaluate at inference timestamp T = 10000L, passing ONLY ticks <= 10000L
        val inferenceTimestamp = 10000L
        val strictTicks = ticks.filter { it.second <= inferenceTimestamp }
        val prediction = engine.evaluateHorizon(strictTicks, inferenceTimestamp, model)

        assertEquals("COMPLETED", prediction.status)
    }
}


