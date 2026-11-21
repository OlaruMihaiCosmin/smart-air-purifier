package com.example.smartpurifier

import org.junit.Assert.*
import org.junit.Test

class FirmwareVersionTest {
    private val raport = mapOf("versiune" to "2026.09.28.1", "build" to "Sep 28 2026 23:10:00", "raportat_la" to 1_800_000_000_000L)

    @Test fun legacyOrPartialReportDoesNotInventInstalledFirmware() {
        for (value in listOf(null, "1.0", emptyMap<String, Any>()) + raport.keys.map { raport - it }) {
            assertNull(citesteVersiuneFirmware(value))
        }
    }

    @Test fun reportKeepsItsOwnTimestampEvenWhenTelemetryBecomesFresh() {
        val date = citesteVersiuneFirmware(raport + ("last_update" to 1_900_000_000_000L))!!
        assertEquals(1_800_000_000_000L, date.raportatLaMs)
        assertEquals("2026.09.28.1", date.versiune)
        assertEquals("Sep 28 2026 23:10:00", date.build)
    }

    @Test fun malformedOrUnresolvedServerTimestampIsUnavailable() {
        for (bad in listOf(0L, -1L, 1_800_000_000L, 1_800_000_000_000.5, Double.NaN, Double.POSITIVE_INFINITY,
            Long.MAX_VALUE, "1800000000000", mapOf(".sv" to "timestamp"))) {
            assertNull(citesteVersiuneFirmware(raport + ("raportat_la" to bad)))
        }
        assertNull(citesteVersiuneFirmware(raport + ("versiune" to " ")))
        assertNull(citesteVersiuneFirmware(raport + ("build" to 123)))
    }

    @Test fun numericFirebaseTimestampAcceptsLongAndDouble() {
        assertEquals(citesteVersiuneFirmware(raport), citesteVersiuneFirmware(raport + ("raportat_la" to 1_800_000_000_000.0)))
    }
}
