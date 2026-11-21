package com.example.smartpurifier

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReliabilityAuditTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun calibrationDeadlineIsFixedAtConfirmationNotAtDelivery() {
        val request = expirareCalibrareCo2(1_800_000_000_000L)
        assertEquals(1_800_000_120L, request)
        assertTrue(request < 1_800_010_000L)
    }

    @Test fun remoteThresholdRefreshPreservesEditsAndDoesNotResendUntouchedFields() {
        var co2 = DraftPrag("1600").editeaza("1700")
        var voc = DraftPrag("250")
        co2 = co2.sincronizeaza("1800")
        voc = voc.sincronizeaza("300")
        assertEquals("1700", co2.text)
        assertEquals("300", voc.text)
        assertEquals(mapOf("alert_co2" to 1700.0), modificariPraguri(mapOf("alert_co2" to co2, "alert_voc" to voc)))
    }

    @Test fun pendingCsvSurvivesNewOwnerAndPreservesUtf8() {
        val dir = folder.newFolder()
        ExportCsvInAsteptare(dir).pregateste("Data,CO2\nastăzi,420\n")
        val out = ByteArrayOutputStream()
        ExportCsvInAsteptare(dir).salveaza { out }
        assertEquals("Data,CO2\nastăzi,420\n", out.toString("UTF-8"))
        assertFalse(ExportCsvInAsteptare(dir).exista())
    }

    @Test fun failedCsvWriteKeepsPayloadForRetry() {
        val store = ExportCsvInAsteptare(folder.newFolder())
        store.pregateste("header\n420\n")
        try {
            store.salveaza { object : OutputStream() {
                override fun write(b: Int) { throw IOException("destinatie indisponibila") }
            } }
            fail("Trebuia raportata eroarea de scriere")
        } catch (_: IOException) { }
        assertTrue(store.exista())
        val out = ByteArrayOutputStream()
        store.salveaza { out }
        assertEquals("header\n420\n", out.toString("UTF-8"))
    }

    @Test fun missingCsvDoesNotOpenOrTruncateDestination() {
        var opened = false
        try {
            ExportCsvInAsteptare(folder.newFolder()).salveaza { opened = true; ByteArrayOutputStream() }
            fail("Payload absent")
        } catch (_: IOException) { }
        assertFalse(opened)
    }

    @Test fun destroyedActivityStillStopsLiveAndReturnCancelsOldStop() {
        val callbacks = mutableListOf<() -> Unit>()
        var live = true
        val controller = OprireLiveAmanata { callbacks.add(it) }
        val oldActivity = Any()
        controller.porneste(oldActivity)
        controller.opreste(oldActivity, { false }) { live = false }
        callbacks.single().invoke()
        assertFalse(live)
        live = true
        controller.porneste(oldActivity)
        controller.opreste(oldActivity, { false }) { live = false }
        val newActivity = Any()
        controller.porneste(newActivity)
        controller.opreste(oldActivity, { false }) { live = false }
        callbacks.last().invoke()
        assertTrue(live)
    }

    @Test fun permanentModeAtDeadlinePreventsDelayedStop() {
        var callback: (() -> Unit)? = null
        var permanent = false
        var stopped = false
        val controller = OprireLiveAmanata { callback = it }
        val owner = Any()
        controller.porneste(owner)
        controller.opreste(owner, { permanent }) { stopped = true }
        permanent = true
        callback!!.invoke()
        assertFalse(stopped)
    }

    @Test fun scoreRejectsYesterdayAndUnknownTimeAndFiltersPartiallyExpiredSeries() {
        assertEquals(-1, scorAer24h(listOf(0f), emptyList(), emptyList(), emptyList(), 100_000, 186_400))
        assertEquals(-1, scorAer24h(listOf(0f), emptyList(), emptyList(), emptyList(), 0, 186_400))
        assertEquals(100, scorAer24h(listOf(100f, 0f), emptyList(), emptyList(), emptyList(), 100_000, 186_399))
        assertEquals(90, scorAer24h(listOf(12f), emptyList(), emptyList(), emptyList(), 100_000, 100_000))
    }
}
