package com.example.smartpurifier

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ArchiveRegressionTest {
    @Test fun lastSundayKeepsTheOldestOfFourWeeksVisible() {
        val f = fereastraArhiva(LocalDate.of(2026, 9, 20), 0)
        assertEquals(LocalDate.of(2026, 8, 24), f.inceput)
        assertEquals(LocalDate.of(2026, 9, 20), f.sfarsit)
    }
    @Test fun pagesMeetWithoutLostOrRepeatedDays() {
        val ultima = LocalDate.of(2026, 9, 21)
        val recent = fereastraArhiva(ultima, 0)
        val vechi = fereastraArhiva(ultima, 1)
        assertEquals(recent.inceput.minusDays(1), vechi.sfarsit)
        assertEquals(27L, java.time.temporal.ChronoUnit.DAYS.between(vechi.inceput, vechi.sfarsit))
    }
    @Test fun lateCallbackFromDisposedPageCannotReplaceCurrentPage() {
        val cereri = GeneratieCerere()
        val prima = cereri.incepe()
        val aDoua = cereri.incepe()
        assertFalse(cereri.esteCurenta(prima))
        assertTrue(cereri.esteCurenta(aDoua))
        cereri.invalideaza()
        assertFalse(cereri.esteCurenta(aDoua))
    }
}
