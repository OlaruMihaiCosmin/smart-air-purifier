package com.example.smartpurifier

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticReportTest {
    @get:Rule val folder = TemporaryFolder()
    private val acum = 1_800_000_000_000L
    private val baza = mapOf<String, Any?>(
        "schema" to 1L, "raportat_la" to acum - 30_000,
        "firmware_versiune" to "2026.09.29.1", "firmware_build" to "Sep 29 2026 10:20:30"
    )

    private fun raport(date: Any? = baza, online: Boolean = true, firmware: VersiuneFirmware? = null) =
        genereazaRaportDiagnostic(date, firmware, "2026.09.29.1 (cod 3, debug)",
            "2026-09-29 07:20:30 UTC", acum, online, acum, ZoneOffset.UTC)

    @Test fun missingOrUnsupportedSchemaDoesNotInterpretUnknownFields() {
        for (date in listOf(null, "cloud", emptyMap<String, Any>()) +
            listOf(0, 2, "1", Double.NaN).map { baza + ("schema" to it) }) {
            val text = raport(date)
            assertTrue(text.contains("Android: 2026.09.29.1 (cod 3, debug)"))
            assertTrue(text.contains("Raport parțial"))
            assertTrue(text.contains("Diagnostic placă: indisponibil"))
            assertFalse(text.contains("Uptime: 0"))
        }
    }

    @Test fun supportedSchemaShowsMeasuredFieldsAndKeepsTargetSeparateFromRamp() {
        val text = raport(baza + mapOf(
            "uptime_sec" to 90_061L, "reset_motiv" to "Pornit la Priza",
            "etapa_curenta" to "Trimitere_Live_FB", "wifi_conectat" to true,
            "wifi_rssi_dbm" to -61L, "fan_auto" to true, "fan_cerut_pct" to 0,
            "fan_tinta_pct" to 70.0, "fan_aplicat_pct" to 48.0, "rpm" to 1210,
            "offset_solicitat_c" to 2.5, "offset_aplicat_c" to null,
            "nm_start" to 1320, "nm_end" to 480, "arhiva_in_asteptare" to "",
            "canal_pm_valid" to true, "canal_co2_valid" to false
        ))
        assertTrue(text.contains("Schema diagnostic: 1"))
        assertTrue(text.contains("Uptime: 1 z 1 h 1 min 1 s"))
        assertTrue(text.contains("Țintă ventilator: 70 %"))
        assertTrue(text.contains("Ieșire aplicată (rampă): 48 %"))
        assertTrue(text.contains("Treaptă cerută: AUTO (0 %)"))
        assertTrue(text.contains("Offset aplicat: indisponibil"))
        assertTrue(text.contains("Program noapte: 22:00–08:00"))
        assertTrue(text.contains("Arhivă în așteptare: niciuna"))
        assertTrue(text.contains("Timer: indisponibil în acest raport"))
        assertTrue(text.contains("PM: valid; CO₂: invalid"))
    }

    @Test fun freshLiveCannotFreshenAnOldDiagnosticSnapshot() {
        val text = raport(baza + ("raportat_la" to acum - 121_000))
        assertTrue(text.contains("Diagnostic: VECHI"))
        assertTrue(text.contains("Ultima telemetrie live: 2027-01-15 08:00:00 UTC"))
        assertTrue(text.contains("Raport diagnostic placă: 2027-01-15 07:57:59 UTC"))
        assertFalse(text.contains("Diagnostic: recent"))
    }

    @Test fun offlineReportKeepsDatedValuesWithoutClaimingBoardOnline() {
        val text = raport(baza + ("wifi_conectat" to true), online = false)
        assertTrue(text.contains("Telefon ↔ Firebase: offline"))
        assertTrue(text.contains("ultimele valori cunoscute"))
        assertTrue(text.contains("WiFi placă la raportare: conectat"))
        assertTrue(text.contains("starea actuală a plăcii nu este confirmată"))
    }

    @Test fun invalidOrMissingTimestampNeverBecomesEpochOrCurrent() {
        for (bad in listOf(null, 0, -1, 1_800_000_000, Long.MAX_VALUE, Double.NaN,
            Double.POSITIVE_INFINITY, acum + 0.5, acum.toString(), mapOf(".sv" to "timestamp"))) {
            val text = raport(baza + ("raportat_la" to bad))
            assertTrue(text.contains("Diagnostic: vechime necunoscută"))
            assertTrue(text.contains("Raport diagnostic placă: indisponibil"))
            assertFalse(text.contains("1970-"))
            assertFalse(text.contains("Diagnostic: recent"))
        }
        assertTrue(raport(baza + ("raportat_la" to acum + 31_000)).contains("dată în viitor"))
    }

    @Test fun freshnessBoundaryAndSmallClockSkewAreExplicit() {
        assertTrue(raport(baza + ("raportat_la" to acum - 120_000)).contains("Diagnostic: recent"))
        assertTrue(raport(baza + ("raportat_la" to acum + 20_000)).contains("abatere ceas"))
    }

    @Test fun firmwareFallbackIsDatedIndependentlyFromDiagnostic() {
        val text = raport(null, firmware = VersiuneFirmware("2026.09.28.1", "Sep 28 2026 23:10:00", acum - 86_400_000))
        assertTrue(text.contains("ESP32: 2026.09.28.1"))
        assertTrue(text.contains("Raport separat Firmware: 2027-01-14 08:00:00 UTC"))
        assertTrue(text.contains("Raport parțial"))
    }

    @Test fun malformedAndNonfiniteMeasurementsStayUnavailable() {
        val text = raport(baza + mapOf(
            "uptime_sec" to "12", "wifi_conectat" to 1, "wifi_rssi_dbm" to 5,
            "heap_liber_bytes" to Double.POSITIVE_INFINITY, "heap_minim_bytes" to -1,
            "rpm" to 3.5, "temperatura_mcu_c" to Double.NaN,
            "fan_tinta_pct" to 101, "fan_aplicat_pct" to -1,
            "nm_start" to 1440, "nm_end" to true
        ))
        for (label in listOf("Uptime", "WiFi placă la raportare", "RSSI", "Heap liber", "Heap minim",
            "RPM", "Temperatură MCU", "Țintă ventilator", "Ieșire aplicată (rampă)", "Program noapte")) {
            assertTrue(label, text.contains("$label: indisponibil"))
        }
        assertFalse(text.contains("NaN"))
        assertFalse(text.contains("Infinity"))
    }

    @Test fun unknownAndInjectedFieldsNeverExposeSecretsOrArbitraryObjects() {
        val secret = "test-only-secret-DO-NOT-EXPORT"
        val poisoned = object { override fun toString(): String = secret }
        val text = raport(baza + mapOf(
            "password" to secret, "token" to secret, "wifi_ssid" to secret,
            "settings" to mapOf("private_key" to secret), "rpm" to poisoned,
            "reset_motiv" to secret, "etapa_curenta" to secret, "firmware_versiune" to secret,
            "firmware_build" to secret, "arhiva_reper_data" to secret,
            "jurnal" to "1800000000#$secret|1800000000#WiFi restabilit pe: $secret|1800000000#Eroare buffer Live FB: $secret"
        ))
        assertFalse(text.contains(secret))
        assertFalse(text.contains("private_key"))
        assertTrue(text.contains("WiFi restabilit (rețea omisă)"))
        assertTrue(text.contains("Eveniment nerecunoscut (text omis)"))
    }

    @Test fun oversizedStringsAndJournalAreBoundedWithoutRenderingArbitraryText() {
        val huge = "x".repeat(100_000)
        val text = raport(baza + mapOf("etapa_curenta" to huge, "jurnal" to huge, "firmware_build" to huge))
        assertTrue(text.length < 8_000)
        assertFalse(text.contains("x".repeat(100)))
        assertTrue(text.contains("Jurnal: indisponibil"))
    }

    @Test fun lastTenJournalEntriesAreReadableAndUnknownClockIsLabelled() {
        val events = (1..12).map { "${1_799_999_980L + it}#Arhiva confirmata: 2026_09_${it.toString().padStart(2, '0')}" }
        val text = raport(baza + mapOf("jurnal" to (events + "999999999#Comunicare SEN66 restabilita").joinToString("|"), "jurnal_limitat" to true))
        assertFalse(text.contains("Arhiva confirmata: 2026_09_01"))
        assertFalse(text.contains("Arhiva confirmata: 2026_09_03"))
        assertTrue(text.contains("2027-01-15 07:59:44 UTC — Arhiva confirmata: 2026_09_04"))
        assertTrue(text.contains("oră necunoscută — Comunicare SEN66 restabilita"))
        assertTrue(text.contains("Jurnal limitat/filtrat: da"))
        assertFalse(text.contains("2001-09"))
    }

    @Test fun nullPendingArchiveIsNotMistakenForNoPendingArchive() {
        assertTrue(raport(baza).contains("Arhivă în așteptare: indisponibil"))
        assertTrue(raport(baza + ("arhiva_in_asteptare" to "2026_09_28")).contains("Arhivă în așteptare: 2026-09-28"))
        assertTrue(raport(baza + ("arhiva_in_asteptare" to "2026_02_31")).contains("Arhivă în așteptare: indisponibil"))
    }

    @Test fun pendingDiagnosticSurvivesOwnerRecreationAndKeepsUtf8ApartFromCsv() {
        val dir = folder.newFolder()
        ExportCsvInAsteptare(dir).pregateste("istoric,420\n")
        ExportDiagnosticInAsteptare(dir).pregateste("Raport diagnostic\nStare: în așteptare; 25 °C\n")
        val out = ByteArrayOutputStream()
        ExportDiagnosticInAsteptare(dir).salveaza { out }
        assertEquals("Raport diagnostic\nStare: în așteptare; 25 °C\n", out.toString("UTF-8"))
        assertFalse(ExportDiagnosticInAsteptare(dir).exista())
        assertTrue(ExportCsvInAsteptare(dir).exista())
    }

    @Test fun cancellationDoesNotRemoveCsvAndMissingSourceNeverOpensDestination() {
        val dir = folder.newFolder()
        ExportCsvInAsteptare(dir).pregateste("historical")
        val store = ExportDiagnosticInAsteptare(dir)
        store.pregateste("diagnostic")
        store.anuleaza()
        assertFalse(store.exista())
        assertTrue(ExportCsvInAsteptare(dir).exista())
        var opened = false
        try { store.salveaza { opened = true; ByteArrayOutputStream() }; fail("Sursă absentă") }
        catch (_: IOException) { }
        assertFalse(opened)
    }

    @Test fun failedOpenWriteOrCloseKeepsReportForRetry() {
        for (failure in 0..2) {
            val store = ExportDiagnosticInAsteptare(folder.newFolder())
            store.pregateste("Raport păstrat")
            try {
                store.salveaza {
                    if (failure == 0) null else object : OutputStream() {
                        override fun write(b: Int) { if (failure == 1) throw IOException("scriere") }
                        override fun close() { if (failure == 2) throw IOException("închidere") }
                    }
                }
                fail("Eroarea trebuie propagată")
            } catch (_: IOException) { }
            assertTrue(store.exista())
            val out = ByteArrayOutputStream()
            store.salveaza { out }
            assertEquals("Raport păstrat", out.toString("UTF-8"))
        }
    }

    @Test fun newReportCanReplaceAPendingReportAfterFailure() {
        val store = ExportDiagnosticInAsteptare(folder.newFolder())
        store.pregateste("raport vechi")
        try { store.salveaza { null }; fail("Deschiderea trebuie sa esueze") }
        catch (_: IOException) { }
        store.pregateste("raport nou")
        val out = ByteArrayOutputStream()
        store.salveaza { out }
        assertEquals("raport nou", out.toString("UTF-8"))
    }

    @Test fun canonicalFirmwareJournalKeepsUsefulEventCategories() {
        val events = listOf("Sistem Repornit (motiv in raport)", "Aplicare offset termic esuata (detalii omise)",
            "Offset Termic modificat (detalii omise)", "FRC CO2 reusit (detalii omise)",
            "FRC CO2 esuat (detalii omise)", "Firebase fara confirmare 120s; refacere conexiune",
            "Eroare citire SEN66 (detalii omise)", "Arhiva confirmata (data in reperul arhivei)",
            "Eveniment omis (text neclasificat)")
        val text = raport(baza + ("jurnal" to events.joinToString("|") { "1800000000#$it" }))
        events.forEach { assertTrue(it, text.contains(it)) }
    }

    @Test fun timerDiagnosticUsesItsSnapshotAndDoesNotClaimCurrentRemainingTime() {
        val timer = mapOf("schema" to 1L, "boot_id" to "0123456789abcdef", "id" to "a".repeat(32),
            "activ" to true, "creat_la_ms" to acum - 60_000L, "sfarsit_ms" to acum + 240_000L,
            "viteza_pct" to 90L, "ramas_sec" to 270L)
        val text = raport(baza + ("timer" to timer))
        assertTrue(text.contains("Timer: activ"))
        assertTrue(text.contains("Treaptă temporizată: 90 %"))
        assertTrue(text.contains("Rămas la raportare: 04:30"))
        assertFalse(text.contains("Timer: neimplementat"))
        val stopped = raport(baza + ("timer" to timer + mapOf("activ" to false, "ramas_sec" to 0L)))
        assertTrue(stopped.contains("Timer: inactiv"))
        val malformed = raport(baza + ("timer" to timer + ("sfarsit_ms" to "invalid")))
        assertTrue(malformed.contains("Timer: indisponibil în acest raport"))
    }

    @Test fun timerEventsRemainReadableInTheFilteredJournal() {
        val events = listOf("Timer pornit", "Timer expirat: revenire AUTO", "Timer anulat: revenire AUTO")
        val text = raport(baza + ("jurnal" to events.joinToString("|") { "1800000000#$it" }))
        events.forEach { assertTrue(it, text.contains(it)) }
    }
}
