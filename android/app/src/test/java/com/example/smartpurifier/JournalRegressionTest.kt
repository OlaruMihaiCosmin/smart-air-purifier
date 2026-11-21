package com.example.smartpurifier

import org.junit.Assert.assertEquals
import org.junit.Test

class JournalRegressionTest {
    @Test fun failuresTakePriorityOverOperationNames() {
        listOf("FRC CO2 esuat (cod 1)", "Eroare OTA", "Eroare calibrare VOC",
            "Curățare eșuată", "Offset: eroare", "Sistem Repornit: Eroare Fatala (Panic)",
            "WDT: Senzorul SEN66 a blocat citirile", "Sistem Repornit: Cadere de Tensiune (Brownout)")
            .forEach { assertEquals(it, TipEvenimentJurnal.EROARE, tipEvenimentJurnal(it)) }
    }

    @Test fun successfulOperationsAndRecoveriesAreGreenCategory() {
        listOf("Calibrare VOC extrasa si salvata.", "FRC CO2 reusit. Corectie aplicata: 20 ppm",
            "Curatare SEN66 Finalizata.", "WiFi restabilit pe: Acasa", "Comunicare SEN66 restabilita",
            "Arhiva confirmata: 2026-09-20", "Arhiva refacuta")
            .forEach { assertEquals(it, TipEvenimentJurnal.SUCCES, tipEvenimentJurnal(it)) }
    }

    @Test fun restartsAndConnectionLossAreWarnings() {
        listOf("Sistem Repornit: Restart din Aplicatie / OTA", "Restart prin Cablu USB",
            "Sistem Repornit: Pornit la Priza", "WiFi pierdut", "Deconectat", "Stream timeout")
            .forEach { assertEquals(it, TipEvenimentJurnal.AVERTISMENT, tipEvenimentJurnal(it)) }
    }

    @Test fun settingsAndUnknownEventsHaveExplicitCategories() {
        listOf("Offset Termic modificat: -0.80 C", "Night Mode pornit (programat)",
            "Night Mode oprit (programat)", "Curatare SEN66 Inceputa...", "Timer pornit",
            "Timer expirat: revenire AUTO", "Timer anulat: revenire AUTO")
            .forEach { assertEquals(it, TipEvenimentJurnal.SETARE, tipEvenimentJurnal(it)) }
        assertEquals(TipEvenimentJurnal.INFORMATIE, tipEvenimentJurnal("Eveniment nou"))
    }
}
