package com.example.smartpurifier

import org.junit.Assert.*
import org.junit.Test

class ExportRegressionTest {
    @Test fun shorterLiveSeriesAlignsToNewestSample() {
        assertEquals(-999f, valoareAliniata(listOf(10f), 0, 3))
        assertEquals(10f, valoareAliniata(listOf(10f), 2, 3))
    }
    @Test fun exportRetainsIndependentChannelsGapsAndStaleAge() {
        assertEquals("Minute_In_Urma,PM2.5,CO2_ppm\n20,1.0,\n15,,500.0\n10,3.0,600.0\n",
            exportIstoricLive(listOf(1f, -999f, 3f), listOf(500f, 600f), 1000, 1600))
    }
    @Test fun unknownTimeDoesNotInventDates() {
        assertEquals("Minute_In_Urma,PM2.5,CO2_ppm\n", exportIstoricLive(listOf(1f), emptyList(), 0, 1600))
    }
}
