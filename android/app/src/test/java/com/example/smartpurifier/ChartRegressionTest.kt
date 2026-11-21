package com.example.smartpurifier

import org.junit.Assert.*
import org.junit.Test

class ChartRegressionTest {
    @Test fun humidityFaultCannotEscapePlot() {
        val scara = scaraGrafic(listOf(45f, 327.67f), 100f, false)
        assertEquals(0f, scara.y(327.67f, 120f), 0.001f)
    }
    @Test fun allValuesAboveCapStillProduceIncreasingScale() {
        val scara = scaraGrafic(listOf(327.67f), 100f, false)
        assertTrue(scara.maxim > scara.minim)
    }
    @Test fun negativeTemperatureFitsInsidePlot() {
        val scara = scaraGrafic(listOf(-10f, -5f), null, false)
        assertTrue(scara.minim <= -10f && scara.maxim >= -5f)
        assertTrue(scara.y(-10f, 120f) in 0f..120f)
    }
    @Test fun nonFiniteInputDoesNotPoisonScale() {
        val scara = scaraGrafic(listOf(Float.NaN, Float.POSITIVE_INFINITY, 20f), null, false)
        assertTrue(scara.minim.isFinite() && scara.maxim.isFinite())
    }
    @Test fun outageEqualToWindowShowsOnlyGaps() {
        assertEquals(List(48) { -999f }, fereastraIstoric(listOf(10f, 20f), 48, 1000L, 15400L))
    }
    @Test fun outageLongerThanWindowDoesNotRelabelOldData() {
        assertEquals(List(48) { -999f }, fereastraIstoric(listOf(10f, 20f), 48, 1000L, 100000L))
    }
    @Test fun ordinaryOutagePreservesValidTail() {
        assertEquals(listOf(10f, 20f, -999f, -999f), fereastraIstoric(listOf(10f, 20f), 48, 1000L, 1600L))
    }
}
