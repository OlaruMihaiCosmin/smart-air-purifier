package com.example.smartpurifier

import org.junit.Assert.*
import org.junit.Test

class FanTimerTest {
    private val acum = 1_800_000_000_000L
    private val boot = "0123456789abcdef"
    private val id = "0123456789abcdef0123456789abcdef"
    private val idNou = "fedcba9876543210fedcba9876543210"

    private fun raport(activ: Boolean = true): Map<String, Any?> = mapOf(
        "schema" to 1L, "boot_id" to boot, "id" to id,
        "activ" to activ, "creat_la_ms" to acum - 300_000,
        "sfarsit_ms" to acum + 600_000, "viteza_pct" to 90L,
        "ramas_sec" to if (activ) 600L else 0L, "raportat_la" to acum
    )

    private fun stare() = requireNotNull(citesteStareTimer(raport()))

    private fun initial() = requireNotNull(citesteStareTimer(raport(false) + mapOf(
        "id" to "", "creat_la_ms" to 0, "sfarsit_ms" to 0,
        "viteza_pct" to 0, "ramas_sec" to 0
    )))

    private fun respinge(block: () -> Unit) {
        try { block(); fail("Parametrul invalid trebuie respins") }
        catch (error: IllegalArgumentException) { assertFalse(error.message.isNullOrBlank()) }
    }

    @Test fun readsCompleteTelemetryAndStrictlyTypedIdentifiers() {
        val rezultat = stare()
        assertEquals(boot, rezultat.bootId)
        assertEquals(id, rezultat.id)
        assertTrue(rezultat.activ)
        assertEquals(acum - 300_000, rezultat.creatLaMs)
        assertEquals(acum + 600_000, rezultat.sfarsitMs)
        assertEquals(90, rezultat.vitezaPct)
        assertEquals(600L, rezultat.ramasSec)
        assertEquals(acum, rezultat.raportatLaMs)
    }

    @Test fun acceptsInitialAndCompletedInactiveReports() {
        assertEquals("", initial().id)
        assertFalse(initial().activ)
        val incheiat = requireNotNull(citesteStareTimer(raport(false)))
        assertEquals(id, incheiat.id)
        assertEquals(acum + 600_000, incheiat.sfarsitMs)
        assertFalse(incheiat.activ)
        assertNotNull(citesteStareTimer(raport(false) + ("viteza_pct" to 0)))
    }

    @Test fun rejectsMissingLegacyPartialOrUnsupportedTelemetry() {
        for (value in listOf(null, false, "Timer", emptyMap<String, Any>())) {
            assertNull(citesteStareTimer(value))
        }
        for (cheie in raport().keys) assertNull(cheie, citesteStareTimer(raport() - cheie))
        for (schema in listOf(0, 2, "1", true)) {
            assertNull(citesteStareTimer(raport() + ("schema" to schema)))
        }
    }

    @Test fun rejectsMalformedBootAndCommandIdentifiers() {
        for (bad in listOf("", "0123456789abcde", "0123456789abcdef0", "0123456789abcdeF", "g".repeat(16), 123)) {
            assertNull(citesteStareTimer(raport() + ("boot_id" to bad)))
        }
        for (bad in listOf("", "a".repeat(31), "a".repeat(33), "A".repeat(32), "z".repeat(32), 123)) {
            assertNull(citesteStareTimer(raport() + ("id" to bad)))
        }
    }

    @Test fun rejectsCoercionFractionalNonfiniteAndOverflowNumbers() {
        for (cheie in listOf("schema", "creat_la_ms", "sfarsit_ms", "viteza_pct", "ramas_sec", "raportat_la")) {
            for (bad in listOf("1", true, 1.5, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Long.MAX_VALUE, Long.MIN_VALUE, mapOf(".sv" to "timestamp"))) {
                assertNull("$cheie: $bad", citesteStareTimer(raport() + (cheie to bad)))
            }
        }
        for (bad in listOf(1, "true", null)) assertNull(citesteStareTimer(raport() + ("activ" to bad)))
    }

    @Test fun acceptsWholeFirebaseDoubleValuesWithoutTruncatingFractions() {
        val complet = raport().mapValues { (_, value) -> if (value is Number) value.toDouble() else value }
        assertEquals(stare(), citesteStareTimer(complet))
        for (cheie in listOf("creat_la_ms", "sfarsit_ms", "raportat_la")) {
            assertNull(citesteStareTimer(raport() + (cheie to (acum + 0.5))))
        }
    }

    @Test fun rejectsWrongEpochUnitsAndImpossibleReportTimes() {
        for (cheie in listOf("creat_la_ms", "sfarsit_ms", "raportat_la")) {
            for (bad in listOf(0L, -1L, acum / 1000, 253_402_300_800_000L)) {
                assertNull(citesteStareTimer(raport() + (cheie to bad)))
            }
        }
    }

    @Test fun rejectsInconsistentActiveInactiveAndRemainingStates() {
        assertNull(citesteStareTimer(raport() + ("viteza_pct" to 0)))
        assertNull(citesteStareTimer(raport() + ("viteza_pct" to 101)))
        assertNull(citesteStareTimer(raport() + ("ramas_sec" to -1)))
        assertNull(citesteStareTimer(raport() + ("ramas_sec" to 901)))
        assertNull(citesteStareTimer(raport(false) + ("ramas_sec" to 1)))
        assertNull(citesteStareTimer(raport(false) + ("id" to "")))
        assertNotNull(citesteStareTimer(raport() + ("ramas_sec" to 0)))
        assertNotNull(citesteStareTimer(raport() + ("viteza_pct" to 1)))
        assertNotNull(citesteStareTimer(raport() + ("viteza_pct" to 100)))
    }

    @Test fun acceptsOnlyFiveMinuteStepsBetweenFiveMinutesAndFourHours() {
        for (durata in listOf(300_000L, 900_000L, 14_400_000L)) {
            assertNotNull(citesteStareTimer(raport() + mapOf(
                "creat_la_ms" to acum, "sfarsit_ms" to acum + durata, "ramas_sec" to 0L)))
        }
        for (durata in listOf(-1L, 0L, 299_999L, 300_001L, 360_000L, 14_700_000L)) {
            assertNull(citesteStareTimer(raport() + mapOf(
                "creat_la_ms" to acum, "sfarsit_ms" to acum + durata, "ramas_sec" to 0L)))
        }
    }

    @Test fun ignoresUnknownTelemetryWithoutForwardingItToCommands() {
        val extern = object { override fun toString(): String = error("Valoarea necunoscută nu trebuie citită") }
        val rezultat = requireNotNull(citesteStareTimer(raport() + mapOf("token" to extern, "stare" to extern)))
        assertEquals(stare(), rezultat)
        val comanda = comandaVitezaVentilator(70, rezultat)
        assertEquals(setOf("speed", "timer"), comanda.keys)
        assertEquals(setOf("id", "boot_id", "creat_la_ms", "sfarsit_ms", "viteza_pct"), (comanda["timer"] as Map<*, *>).keys)
    }

    @Test fun startEligibilityRequiresOwnFreshTelemetryAndPhoneConnectionAndServerTime() {
        assertTrue(timerDisponibil(stare(), true, acum))
        assertTrue(timerDisponibil(initial(), true, acum))
        assertFalse(timerDisponibil(null, true, acum))
        assertFalse(timerDisponibil(stare(), false, acum))
        assertFalse(timerDisponibil(stare(), true, null))
        assertFalse(timerDisponibil(stare(), true, 0))
        assertFalse(timerDisponibil(stare(), true, Long.MAX_VALUE))
    }

    @Test fun eligibilityHonorsThirtySecondAgeAndFiveSecondFutureBoundaries() {
        assertTrue(timerDisponibil(stare(), true, acum + 30_000))
        assertFalse(timerDisponibil(stare(), true, acum + 30_001))
        assertTrue(timerDisponibil(stare(), true, acum - 5_000))
        assertFalse(timerDisponibil(stare(), true, acum - 5_001))
    }

    @Test fun countdownUsesReportedRemainingInsteadOfWallClockDeadline() {
        val raportDecalat = requireNotNull(citesteStareTimer(raport() + ("ramas_sec" to 270)))
        assertEquals(270L, secundeRamaseTimer(raportDecalat, acum))
        assertEquals(269L, secundeRamaseTimer(raportDecalat, acum + 1_000))
        assertEquals(240L, secundeRamaseTimer(raportDecalat, acum + 30_000))
    }

    @Test fun countdownEstimatesOfflineAndNeverGoesBelowZeroOrAboveReport() {
        assertFalse(timerDisponibil(stare(), false, acum + 10_000))
        assertEquals(590L, secundeRamaseTimer(stare(), acum + 10_000))
        assertEquals(600L, secundeRamaseTimer(stare(), acum + 999))
        assertEquals(600L, secundeRamaseTimer(stare(), acum - 5_000))
        assertEquals(600L, secundeRamaseTimer(stare(), Long.MIN_VALUE))
        assertEquals(0L, secundeRamaseTimer(stare(), acum + 600_000))
        assertEquals(0L, secundeRamaseTimer(stare(), Long.MAX_VALUE))
        assertEquals(0L, secundeRamaseTimer(initial(), acum))
    }

    @Test fun formattingUsesPaddedMinutesOrHoursAndClampsNegativeEstimate() {
        assertEquals("00:00", formatDurataTimer(0))
        assertEquals("00:00", formatDurataTimer(-1))
        assertEquals("00:09", formatDurataTimer(9))
        assertEquals("04:30", formatDurataTimer(270))
        assertEquals("59:59", formatDurataTimer(3599))
        assertEquals("1:00:00", formatDurataTimer(3600))
        assertEquals("4:00:00", formatDurataTimer(14_400))
    }

    @Test fun newCommandFixesCreationAndDeadlineAtTheTapAndUsesCurrentBoot() {
        val comanda = comandaPornireTimer(initial(), acum, 15, 90, idNou)
        assertEquals(mapOf("speed" to 0, "timer" to mapOf(
            "id" to idNou, "boot_id" to boot, "creat_la_ms" to acum,
            "sfarsit_ms" to acum + 900_000, "viteza_pct" to 90)), comanda)
        assertEquals(comanda, comandaPornireTimer(initial(), acum, 15, 90, idNou))
    }

    @Test fun startCommandSupportsMinimumMaximumAndActiveReplacement() {
        assertEquals(acum + 300_000, (comandaPornireTimer(stare(), acum, 5, 1, idNou)["timer"] as Map<*, *>)["sfarsit_ms"])
        assertEquals(acum + 14_400_000, (comandaPornireTimer(initial(), acum, 240, 100, idNou)["timer"] as Map<*, *>)["sfarsit_ms"])
    }

    @Test fun invalidDurationsSpeedsIdsAndClockOverflowCannotBuildCommands() {
        for (d in listOf(Int.MIN_VALUE, 0, 4, 6, 241, 245, Int.MAX_VALUE)) {
            respinge { comandaPornireTimer(initial(), acum, d, 90, idNou) }
        }
        for (v in listOf(Int.MIN_VALUE, -1, 0, 101, Int.MAX_VALUE)) {
            respinge { comandaPornireTimer(initial(), acum, 15, v, idNou) }
        }
        for (identitate in listOf("", idNou.uppercase(), "a".repeat(33), id)) {
            respinge { comandaPornireTimer(stare(), acum, 15, 90, identitate) }
        }
        for (moment in listOf(Long.MIN_VALUE, 0L, acum + 30_001, acum - 5_001, Long.MAX_VALUE)) {
            respinge { comandaPornireTimer(initial(), moment, 15, 90, idNou) }
        }
        val limita = initial().copy(raportatLaMs = 253_402_300_799_999L)
        respinge { comandaPornireTimer(limita, limita.raportatLaMs, 240, 90, idNou) }
    }

    @Test fun speedChangeKeepsIdentityBootCreationAndDeadlineAfterAcceptanceWindow() {
        val vechi = stare().copy(raportatLaMs = acum + 120_000, ramasSec = 480)
        val payload = comandaVitezaVentilator(70, vechi)
        assertEquals(mapOf("speed" to 0, "timer" to mapOf(
            "id" to id, "boot_id" to boot, "creat_la_ms" to acum - 300_000,
            "sfarsit_ms" to acum + 600_000, "viteza_pct" to 70)), payload)
        assertEquals(acum + 600_000, (comandaVitezaVentilator(4, vechi)["timer"] as Map<*, *>)["sfarsit_ms"])
    }

    @Test fun autoAlwaysCancelsAndInactiveManualCommandClearsTimerInSamePatch() {
        for (stare in listOf(null, initial(), stare())) {
            assertEquals(mapOf("speed" to 0, "timer" to false), comandaVitezaVentilator(0, stare))
        }
        for (stare in listOf(null, initial(), citesteStareTimer(raport(false)))) {
            assertEquals(mapOf("speed" to 50, "timer" to false), comandaVitezaVentilator(50, stare))
            assertEquals(mapOf("speed" to 1, "timer" to false), comandaVitezaVentilator(1, stare))
        }
        for (viteza in listOf(-1, 101)) respinge { comandaVitezaVentilator(viteza, stare()) }
    }

    @Test fun cancellationOnlyTouchesSettingsLeavesAndPreservesIndependentData() {
        val initial = mapOf<String, Any>(
            "Arhiva_Zilnica" to mapOf("2026_09_28" to mapOf("PM" to "1|2|3")),
            "Diagnostic" to mapOf("schema" to 1),
            "setari" to mapOf("speed" to 90, "timer" to mapOf("id" to id), "pm_alerta" to 20)
        )
        val setari = (initial["setari"] as Map<*, *>).toMutableMap()
        setari.putAll(comandaVitezaVentilator(0, stare()))
        val rezultat = initial + ("setari" to setari)
        assertEquals(initial["Arhiva_Zilnica"], rezultat["Arhiva_Zilnica"])
        assertEquals(initial["Diagnostic"], rezultat["Diagnostic"])
        assertEquals(20, setari["pm_alerta"])
        assertEquals(false, setari["timer"])
        assertEquals(0, setari["speed"])
        assertEquals(setOf("speed", "timer", "pm_alerta"), setari.keys)
    }
}
