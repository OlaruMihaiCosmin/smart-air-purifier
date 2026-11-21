package com.example.smartpurifier

private const val TIMER_PRIMUL_TIMESTAMP_MS = 946_684_800_000L
private const val TIMER_ULTIMUL_TIMESTAMP_MS = 253_402_300_799_999L
private const val TIMER_RAPORT_RECENT_MS = 30_000L
private const val TIMER_TOLERANTA_VIITOR_MS = 5_000L
private const val TIMER_MINIM_MS = 300_000L
private const val TIMER_MAXIM_MS = 14_400_000L
private val TIMER_BOOT_ID = Regex("[0-9a-f]{16}")
private val TIMER_COMANDA_ID = Regex("[0-9a-f]{32}")

// Obiectul confirmat de placă; câmpurile necunoscute din Firebase nu sunt păstrate.
data class StareTimerVentilator(
    val bootId: String,
    val id: String,
    val activ: Boolean,
    val creatLaMs: Long,
    val sfarsitMs: Long,
    val vitezaPct: Int,
    val ramasSec: Long,
    val raportatLaMs: Long
) {
    init {
        require(TIMER_BOOT_ID.matches(bootId)) { "Identitatea pornirii plăcii este invalidă" }
        require(timestampTimerValid(raportatLaMs)) { "Data raportului TIMER este invalidă" }
        require(vitezaPct in 0..100 && ramasSec in 0..14_400) { "Treapta sau timpul rămas este invalid" }
        if (id.isEmpty()) {
            require(!activ && creatLaMs == 0L && sfarsitMs == 0L && vitezaPct == 0 && ramasSec == 0L) {
                "Starea inițială TIMER este incompletă"
            }
        } else {
            require(TIMER_COMANDA_ID.matches(id)) { "Identitatea timerului este invalidă" }
            require(timestampTimerValid(creatLaMs) && timestampTimerValid(sfarsitMs)) {
                "Datele timerului sunt invalide"
            }
            val durataMs = sfarsitMs - creatLaMs
            require(durataMs in TIMER_MINIM_MS..TIMER_MAXIM_MS && durataMs % TIMER_MINIM_MS == 0L) {
                "Durata trebuie să fie între 5 și 240 minute, în pași de 5 minute"
            }
            require(ramasSec <= durataMs / 1000 && (activ || ramasSec == 0L) && (!activ || vitezaPct >= 1)) {
                "Starea activă, treapta și timpul rămas nu sunt coerente"
            }
        }
    }
}

fun citesteStareTimer(value: Any?): StareTimerVentilator? {
    val date = value as? Map<*, *> ?: return null
    if (intregTimer(date["schema"], 1, 1) == null) return null
    val bootId = date["boot_id"] as? String ?: return null
    val id = date["id"] as? String ?: return null
    val activ = date["activ"] as? Boolean ?: return null
    val creatLaMs = intregTimer(date["creat_la_ms"], 0, TIMER_ULTIMUL_TIMESTAMP_MS) ?: return null
    val sfarsitMs = intregTimer(date["sfarsit_ms"], 0, TIMER_ULTIMUL_TIMESTAMP_MS) ?: return null
    val vitezaPct = intregTimer(date["viteza_pct"], 0, 100)?.toInt() ?: return null
    val ramasSec = intregTimer(date["ramas_sec"], 0, 14_400) ?: return null
    val raportatLaMs = intregTimer(date["raportat_la"], TIMER_PRIMUL_TIMESTAMP_MS, TIMER_ULTIMUL_TIMESTAMP_MS) ?: return null
    return try {
        StareTimerVentilator(bootId, id, activ, creatLaMs, sfarsitMs, vitezaPct, ramasSec, raportatLaMs)
    } catch (_: IllegalArgumentException) {
        null
    }
}

// Ora trebuie corectată în apelant cu .info/serverTimeOffset, disponibil în această conexiune.
fun timerDisponibil(stare: StareTimerVentilator?, conectat: Boolean, acumServerMs: Long?): Boolean {
    if (!conectat || stare == null || acumServerMs == null || !timestampTimerValid(acumServerMs)) return false
    val varstaMs = acumServerMs - stare.raportatLaMs
    return varstaMs in -TIMER_TOLERANTA_VIITOR_MS..TIMER_RAPORT_RECENT_MS
}

// Estimarea continuă și offline; numai un raport nou confirmă starea reală a plăcii.
// Folosim contorul raportat, nu termenul civil, care poate diferi după resincronizarea NTP.
fun secundeRamaseTimer(stare: StareTimerVentilator, acumServerMs: Long): Long {
    if (!stare.activ) return 0
    val trecuteSec = if (acumServerMs > stare.raportatLaMs) (acumServerMs - stare.raportatLaMs) / 1000 else 0
    return (stare.ramasSec - trecuteSec).coerceAtLeast(0)
}

// Se construiește o singură dată la apăsare; reîncercarea transportului reutilizează acest obiect.
// Apelantul verifică și conexiunea telefonului înainte de a trimite comanda.
fun comandaPornireTimer(
    stare: StareTimerVentilator,
    acumServerMs: Long,
    durataMinute: Int,
    vitezaPct: Int,
    id: String
): Map<String, Any> {
    require(timerDisponibil(stare, true, acumServerMs)) { "Raportul TIMER nu este recent sau ora serverului este indisponibilă" }
    require(durataMinute in 5..240 && durataMinute % 5 == 0) { "Durata trebuie să fie între 5 și 240 minute, în pași de 5 minute" }
    require(vitezaPct in 1..100) { "Treapta TIMER trebuie să fie între 1 și 100%" }
    require(TIMER_COMANDA_ID.matches(id) && id != stare.id) { "Un timer nou necesită o identitate nouă validă" }
    val durataMs = durataMinute * 60_000L
    require(acumServerMs <= TIMER_ULTIMUL_TIMESTAMP_MS - durataMs) { "Termenul timerului depășește intervalul acceptat" }
    return mapOf("speed" to 0, "timer" to obiectComandaTimer(id, stare.bootId, acumServerMs, acumServerMs + durataMs, vitezaPct))
}

// Patch exclusiv sub /SenzoriAer/setari. AUTO anulează; o treaptă nouă păstrează termenul activ.
fun comandaVitezaVentilator(vitezaPct: Int, stare: StareTimerVentilator?): Map<String, Any> {
    require(vitezaPct in 0..100) { "Treapta ventilatorului trebuie să fie între 0 și 100%" }
    return if (vitezaPct > 0 && stare?.activ == true) {
        mapOf("speed" to 0, "timer" to obiectComandaTimer(stare.id, stare.bootId, stare.creatLaMs, stare.sfarsitMs, vitezaPct))
    } else {
        mapOf("speed" to vitezaPct, "timer" to false)
    }
}

fun formatDurataTimer(secunde: Long): String {
    val total = secunde.coerceAtLeast(0)
    val sec = (total % 60).toString().padStart(2, '0')
    val min = (total / 60 % 60).toString().padStart(2, '0')
    return if (total >= 3600) "${total / 3600}:$min:$sec" else "$min:$sec"
}

private fun obiectComandaTimer(id: String, bootId: String, creatLaMs: Long, sfarsitMs: Long, vitezaPct: Int): Map<String, Any> =
    mapOf("id" to id, "boot_id" to bootId, "creat_la_ms" to creatLaMs, "sfarsit_ms" to sfarsitMs, "viteza_pct" to vitezaPct)

private fun timestampTimerValid(value: Long): Boolean = value in TIMER_PRIMUL_TIMESTAMP_MS..TIMER_ULTIMUL_TIMESTAMP_MS

private fun intregTimer(value: Any?, minim: Long, maxim: Long): Long? {
    val numar = when (value) {
        is Byte, is Short, is Int, is Long, is Float, is Double -> (value as Number).toDouble()
        else -> return null
    }
    return numar.takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= minim.toDouble() && it <= maxim.toDouble() }?.toLong()
}
