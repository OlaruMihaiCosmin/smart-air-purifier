package com.example.smartpurifier

import org.junit.Assert.*
import org.junit.Test

class FreshnessRegressionTest {
    @Test fun invalidAlertThresholdCannotBeSubmitted() {
        listOf("NaN", "Infinity", "-1", "0", "", "40001").forEach { assertFalse(pragValid(it, 40000f)) }
        assertTrue(pragValid("1600", 40000f))
        assertTrue(pragValid("35.5", 6553.4f))
    }
    @Test fun oldButAdvancedTimestampIsStillStale() {
        assertFalse(dateProaspete(1_000_000, 1_600_000))
        assertFalse(dateProaspete(0, 1_600_000))
        assertFalse(dateProaspete(1_000_000, 1_090_000))
        assertTrue(dateProaspete(1_000_000, 1_089_999))
    }
    @Test fun unreasonableFutureTimeCannotRefreshWidget() {
        assertFalse(dateProaspete(Long.MAX_VALUE, 1_600_000))
        assertTrue(dateProaspete(1_610_000, 1_600_000))
    }
    @Test fun tooltipFitsAtAllFourEdgesAndNearTop() {
        listOf(0f, 250f, 500f).forEach { x ->
            listOf(0f, 40f, 60f, 120f).forEach { y ->
                val p = pozitieTooltip(x, y, 500f, 120f)
                assertTrue(p.x - 50f >= 0f && p.x + 50f <= 500f)
                assertTrue(p.y - 35f >= 0f && p.y + 15f <= 120f)
            }
        }
    }
}
