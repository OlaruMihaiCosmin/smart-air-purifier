package com.example.smartpurifier

import java.time.DayOfWeek
import java.time.LocalDate
import java.text.Normalizer
import kotlin.math.roundToInt

enum class TipEvenimentJurnal(val eticheta: String) {
    EROARE("Erori"), AVERTISMENT("Atenție"), SUCCES("Reușite"), SETARE("Setări"), INFORMATIE("Info")
}

fun tipEvenimentJurnal(mesaj: String): TipEvenimentJurnal {
    val text = Normalizer.normalize(mesaj, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase()
    fun contine(vararg termeni: String) = termeni.any { text.contains(it) }
    // Erorile au prioritate fata de numele operatiei: OTA, calibrare, offset etc.
    return when {
        contine("eroare", "esuat", "esec", "panic", "wdt", "blocat", "fail", "brownout", "cadere de tensiune") -> TipEvenimentJurnal.EROARE
        contine("pierdut", "deconect", "timeout", "restart", "repornit", "necunoscut", "avert") -> TipEvenimentJurnal.AVERTISMENT
        contine("restabilit", "refacut", "confirmat", "reusit", "finalizat", "salvat", "creat") -> TipEvenimentJurnal.SUCCES
        contine("offset", "night mode", "timer", "setar", "comanda", "inceput") -> TipEvenimentJurnal.SETARE
        else -> TipEvenimentJurnal.INFORMATIE
    }
}

data class FereastraArhiva(val inceput: LocalDate, val sfarsit: LocalDate)

fun fereastraArhiva(ultimaZi: LocalDate, pagina: Int): FereastraArhiva {
    val luni = ultimaZi.with(DayOfWeek.MONDAY).minusWeeks(pagina.coerceAtLeast(0).toLong() * 4)
    return FereastraArhiva(luni.minusWeeks(3), luni.plusDays(6))
}

// Un raspuns sosit dupa schimbarea paginii nu mai poate inlocui pagina curenta.
class GeneratieCerere {
    private var curenta = 0L
    fun incepe(): Long = ++curenta
    fun esteCurenta(id: Long): Boolean = id == curenta
    fun invalideaza() { ++curenta }
}

fun dateProaspete(timestamp: Long, acum: Long, limitaMs: Long = 90_000): Boolean {
    if (timestamp <= 0 || acum <= 0) return false
    return if (timestamp > acum) timestamp - acum <= 30_000 else acum - timestamp < limitaMs
}

data class PozitieTooltip(val x: Float, val y: Float)

fun pozitieTooltip(x: Float, y: Float, latime: Float, inaltime: Float): PozitieTooltip {
    val margineX = minOf(50f, latime / 2)
    val sus = minOf(35f, inaltime)
    val jos = maxOf(sus, inaltime - 15f)
    return PozitieTooltip(x.coerceIn(margineX, maxOf(margineX, latime - margineX)),
        (if (y - 45f < sus) y + 60f else y - 45f).coerceIn(sus, jos))
}

enum class TipMasurare { GENERIC, PM, CO2, INDICE, TEMPERATURA, UMIDITATE }

fun pragValid(text: String, maxim: Float): Boolean =
    text.toFloatOrNull()?.let { it.isFinite() && it > 0f && it <= maxim } == true

// Seriile live au acelasi ultim slot, chiar daca una are mai putine probe.
fun valoareAliniata(serie: List<Float>, slot: Int, total: Int): Float =
    serie.getOrNull(slot - (total - serie.size)) ?: -999f

fun exportIstoricLive(pm: List<Float>, co2: List<Float>, ultimulUnix: Long, acumUnix: Long): String = buildString {
    append("Minute_In_Urma,PM2.5,CO2_ppm\n")
    if (ultimulUnix <= 0) return@buildString
    val n = maxOf(pm.size, co2.size)
    val varsta = (acumUnix - ultimulUnix).coerceAtLeast(0) / 60
    for (i in maxOf(0, n - 288) until n) {
        fun celula(serie: List<Float>, tip: TipMasurare): String {
            val v = curataMasurare(valoareAliniata(serie, i, n), tip)
            return if (v == -999f) "" else v.toString()
        }
        append("${varsta + (n - 1 - i) * 5},${celula(pm, TipMasurare.PM)},${celula(co2, TipMasurare.CO2)}\n")
    }
}

fun curataMasurare(v: Float, tip: TipMasurare): Float {
    if (!v.isFinite() || v == -999f) return -999f
    val valid = when (tip) {
        TipMasurare.PM -> v in 0f..<6553.5f
        TipMasurare.CO2 -> v in 0f..40000f
        TipMasurare.INDICE -> v in 1f..500f
        // Excludem si santinela rotunjita la o zecimala din arhivele vechi.
        TipMasurare.TEMPERATURA -> v >= -163.84f && v < 163.8f
        TipMasurare.UMIDITATE -> v in 0f..100f
        TipMasurare.GENERIC -> true
    }
    return if (valid) v else -999f
}

fun tipMasurare(id: String): TipMasurare = when (id.removePrefix("AR_")) {
    "PM1", "PM25", "PM4", "PM10" -> TipMasurare.PM
    "CO2" -> TipMasurare.CO2
    "VOC", "NOx" -> TipMasurare.INDICE
    "Temp", "Temperatura" -> TipMasurare.TEMPERATURA
    "Umid", "Umiditate", "RH" -> TipMasurare.UMIDITATE
    else -> TipMasurare.GENERIC
}

// Un slot invalid ramane gol; eliminarea lui ar muta toate orele care urmeaza.
fun parseIstoric(data: String?, isIndex: Boolean = false, tip: TipMasurare = TipMasurare.GENERIC): List<Float> {
    if (data.isNullOrBlank()) return emptyList()
    return data.trim().split(",").map {
        val v = it.trim().toFloatOrNull()
        if (v == null) -999f else curataMasurare(v, if (isIndex) TipMasurare.INDICE else tip)
    }
}

data class ScaraGrafic(val minim: Float, val maxim: Float) {
    val interval: Float get() = if (maxim == minim) 1f else maxim - minim
    fun y(valoare: Float, inaltime: Float): Float {
        if (!valoare.isFinite() || inaltime <= 0f) return inaltime.coerceAtLeast(0f)
        return (inaltime - ((valoare - minim) / interval) * inaltime).coerceIn(0f, inaltime)
    }
}

// Calculul scalei este separat de Canvas pentru verificarea cazurilor limita.
fun scaraGrafic(valori: List<Float>, plafon: Float?, indice: Boolean): ScaraGrafic {
    val valide = valori.filter { it != -999f && it.isFinite() }
    val jos = valide.minOrNull() ?: 0f
    val sus = valide.maxOrNull() ?: 1f
    var maxim = sus + kotlin.math.abs(sus) * 0.1f
    var minim = jos - kotlin.math.abs(jos) * 0.1f
    if (plafon != null && maxim > plafon) maxim = plafon
    if (indice && maxim - minim < 2f) {
        minim = kotlin.math.floor(valide.minOrNull() ?: minim).coerceAtLeast(0f)
        maxim = minim + 2f
        if (plafon != null && maxim > plafon) { maxim = plafon; minim = (maxim - 2f).coerceAtLeast(0f) }
    }
    if (!maxim.isFinite()) maxim = plafon ?: (Float.MAX_VALUE / 2f)
    if (!minim.isFinite() || minim >= maxim) minim = maxim - kotlin.math.max(1f, kotlin.math.abs(maxim) * 0.1f)
    if (minim == maxim) maxim = minim + 1f
    return ScaraGrafic(minim, maxim)
}

fun fereastraIstoric(valori: List<Float>, puncte: Int, ultimulUnix: Long, acumUnix: Long): List<Float> {
    if (puncte <= 0) return emptyList()
    val lipsa = if (ultimulUnix > 0) ((acumUnix - ultimulUnix).coerceAtLeast(0) / 300).coerceAtMost(puncte.toLong()).toInt() else 0
    val complet = valori.takeLast(puncte) + List(lipsa) { -999f }
    return complet.takeLast(puncte)
}

// Aceeasi formula; filtrarea temporala se face inaintea ponderarii.
fun scorAer24h(
    pm: List<Float>, co2: List<Float>, voc: List<Float>, nox: List<Float>,
    ultimulUnix: Long, acumUnix: Long
): Int {
    if (ultimulUnix <= 0 || ultimulUnix > acumUnix + 30) return -1
    fun recente(serie: List<Float>) = serie.takeLast(288).filterIndexed { i, _ ->
        val moment = ultimulUnix - (minOf(288, serie.size) - 1 - i) * 300L
        moment > acumUnix - 86400 && moment <= acumUnix + 30
    }
    return scorAer(recente(pm), recente(co2), recente(voc), recente(nox))
}

fun scorAer(istoricPM25: List<Float>, istoricCO2: List<Float>, istoricVOC: List<Float>, istoricNOx: List<Float>): Int {
    fun calcSubScorPm(avg: Float): Float {
        // EPA PM2.5 breakpoints: 0-12 excelent, 12-35 moderat, 35-55 nesănătos sensibili, 55+ nesănătos
        return when {
            avg <= 0f -> 100f
            avg <= 12f -> 100f - (avg / 12f) * 10f               // 100→90
            avg <= 35f -> 90f - ((avg - 12f) / 23f) * 35f        // 90→55
            avg <= 55f -> 55f - ((avg - 35f) / 20f) * 35f        // 55→20
            else -> (20f - ((avg - 55f) / 10f) * 10f).coerceAtLeast(0f)
        }
    }

    fun calcSubScorCo2(avg: Float): Float {
        return when {
            avg <= 600f -> 100f
            avg <= 800f -> 100f - ((avg - 600f) / 200f) * 15f    // 100→85
            avg <= 1000f -> 85f - ((avg - 800f) / 200f) * 25f   // 85→60
            avg <= 1500f -> 60f - ((avg - 1000f) / 500f) * 40f  // 60→20
            else -> (20f - ((avg - 1500f) / 500f) * 20f).coerceAtLeast(0f)
        }
    }

    fun calcSubScorVoc(avg: Float): Float {
        return when {
            avg <= 100f -> 100f
            avg <= 150f -> 100f - ((avg - 100f) / 50f) * 15f     // 100→85
            avg <= 250f -> 85f - ((avg - 150f) / 100f) * 30f     // 85→55
            avg <= 400f -> 55f - ((avg - 250f) / 150f) * 35f     // 55→20
            else -> (20f - ((avg - 400f) / 100f) * 20f).coerceAtLeast(0f)
        }
    }

    fun calcSubScorNox(avg: Float): Float {
        return when {
            avg <= 20f -> 100f
            avg <= 50f -> 100f - ((avg - 20f) / 30f) * 20f       // 100→80
            avg <= 150f -> 80f - ((avg - 50f) / 100f) * 40f      // 80→40
            avg <= 300f -> 40f - ((avg - 150f) / 150f) * 30f     // 40→10
            else -> (10f - ((avg - 300f) / 100f) * 10f).coerceAtLeast(0f)
        }
    }

    // -1 = date insuficiente; altfel 0..100 (vezi descrierea algoritmului de mai sus).
    return run {
        val n = maxOf(istoricPM25.size, istoricCO2.size, istoricVOC.size, istoricNOx.size)
        if (n == 0) return@run -1

        val instScores = ArrayList<Float>(n)
        for (i in 0 until n) {
            var sumaW = 0f; var sumaSW = 0f; var worst = Float.MAX_VALUE
            fun adauga(s: Float, w: Float) { sumaSW += s * w; sumaW += w; if (s < worst) worst = s }

            valoareAliniata(istoricPM25, i, n).let { if (it != -999f && it >= 0f) adauga(calcSubScorPm(it),  0.40f) }
            valoareAliniata(istoricCO2, i, n).let  { if (it != -999f && it >= 0f) adauga(calcSubScorCo2(it), 0.30f) }
            valoareAliniata(istoricVOC, i, n).let  { if (it in 1f..500f)          adauga(calcSubScorVoc(it), 0.20f) }
            valoareAliniata(istoricNOx, i, n).let  { if (it in 1f..500f)          adauga(calcSubScorNox(it), 0.10f) }

            // La fiecare moment: 50% cel mai slab poluant + 50% media ponderată a celor disponibili
            if (sumaW > 0f) instScores.add(0.5f * worst + 0.5f * (sumaSW / sumaW))
        }

        if (instScores.isEmpty()) return@run -1

        val medieZi = instScores.average().toFloat()
        // Media celor mai proaste ~10% probe (cel puțin 1) — captează perioadele de poluare
        val sortate = instScores.sorted()
        val nrProaste = maxOf(1, kotlin.math.ceil(instScores.size * 0.10f).toInt())
        val medieProaste = sortate.take(nrProaste).average().toFloat()

        // 70% condiții tipice + 30% cele mai proaste perioade
        (0.70f * medieZi + 0.30f * medieProaste).coerceIn(0f, 100f).roundToInt()
    }

}
