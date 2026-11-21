package com.example.smartpurifier

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRegressionTest {
    @Test fun malformedSamplesKeepTheirFiveMinuteSlots() {
        assertEquals(listOf(1f, -999f, 3f, -999f, 5f), parseIstoric("1,defect,3,,5"))
    }

    @Test fun nonFiniteSamplesBecomeGaps() {
        assertEquals(listOf(-999f, -999f, -999f, 21f), parseIstoric("NaN,Infinity,-Infinity,21"))
    }

    @Test fun unavailableGasIndicesBecomeGapsWithoutMovingNeighbors() {
        assertEquals(listOf(1f, -999f, -999f, 500f), parseIstoric("1,0,3276.7,500", true))
    }
}
