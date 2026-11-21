package com.example.smartpurifier

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementRegressionTest {
    @Test fun sentinelsAndImpossibleHumidityAreNotMeasurements() {
        listOf(327.67f, 100.01f, -1f).forEach {
            assertEquals(-999f, curataMasurare(it, TipMasurare.UMIDITATE))
        }
        listOf(163.835f, 163.84f, 163.8f).forEach {
            assertEquals(-999f, curataMasurare(it, TipMasurare.TEMPERATURA))
        }
        assertEquals(-999f, curataMasurare(6553.5f, TipMasurare.PM))
        assertEquals(-999f, curataMasurare(65535f, TipMasurare.CO2))
        assertEquals(-999f, curataMasurare(0f, TipMasurare.INDICE))
    }
    @Test fun validBoundaryValuesRemainVisible() {
        assertEquals(0f, curataMasurare(0f, TipMasurare.PM))
        assertEquals(100f, curataMasurare(100f, TipMasurare.UMIDITATE))
        assertEquals(-10f, curataMasurare(-10f, TipMasurare.TEMPERATURA))
        assertEquals(500f, curataMasurare(500f, TipMasurare.INDICE))
        assertEquals(40000f, curataMasurare(40000f, TipMasurare.CO2))
    }
}
