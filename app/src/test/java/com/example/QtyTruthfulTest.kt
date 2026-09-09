package com.example

import com.example.engine.FeatureExtractor
import com.example.engine.PredictionEngine
import com.example.engine.WalkForwardValidator
import org.junit.Assert.*
import org.junit.Test

class QtyTruthfulTest {

    @Test
    fun `test insufficient data produces no prediction`() {
        val engine = PredictionEngine()
        val predictions = engine.evaluateHorizons(emptyList())
        
        assertTrue(predictions.isNotEmpty())
        predictions.forEach { pred ->
            assertEquals("NO_PREDICTION", pred.status)
            assertNull(pred.direction)
            assertNull(pred.confidence)
            assertNull(pred.targetPrice)
            assertNull(pred.uncertainty)
            assertFalse(pred.isSelectiveHighWinRate)
        }
    }

    @Test
    fun `test validator cannot fabricate metrics`() {
        val validator = WalkForwardValidator()
        val result = validator.validate(emptyList(), "5s")

        assertEquals("UNAVAILABLE", result.status)
        assertNull(result.oosWinRate)
        assertNull(result.totalValidated)
        assertNull(result.calibrationError)
        assertNull(result.featureImportanceMap)
    }

    @Test
    fun `test feature extractor handles insufficient data correctly without fallback synthesis`() {
        val extractor = FeatureExtractor()
        val vector = extractor.extract(listOf(Pair(100.0, 1000L), Pair(101.0, 2000L))) // Less than 5 ticks
        
        assertFalse(vector.isAuthentic)
        assertEquals(0.0, vector.return1s, 0.0001)
        assertEquals(0.0, vector.return5s, 0.0001)
        assertEquals(0.0, vector.volatility, 0.0001)
        assertEquals(0.0, vector.volumeDelta, 0.0001)
    }
}
