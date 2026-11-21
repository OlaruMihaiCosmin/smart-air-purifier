package com.example.smartpurifier

import java.io.File
import java.io.IOException
import java.io.OutputStream

// Termenul se fixeaza la confirmare; persistenta Firebase nu il reinnoieste la livrare.
fun expirareCalibrareCo2(acumMs: Long): Long = acumMs / 1000 + 120

data class VersiuneFirmware(val versiune: String, val build: String, val raportatLaMs: Long)

// Nodul se citeste integral: nu combinam versiunea unui build cu data altuia.
// Absenta sau un raport partial nu inseamna ca placa ruleaza versiunea aplicatiei.
fun citesteVersiuneFirmware(valoare: Any?): VersiuneFirmware? {
    val date = valoare as? Map<*, *> ?: return null
    val versiune = (date["versiune"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val build = (date["build"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val timp = (date["raportat_la"] as? Number)?.toDouble() ?: return null
    // RTDB stocheaza timestamp-ul serverului in milisecunde, ca last_update.
    if (!timp.isFinite() || timp < 946684800000.0 || timp >= Long.MAX_VALUE.toDouble() || timp % 1.0 != 0.0) return null
    return VersiuneFirmware(versiune, build, timp.toLong())
}

data class DraftPrag(val text: String, val modificat: Boolean = false) {
    fun editeaza(valoare: String) = copy(text = valoare, modificat = true)
    fun sincronizeaza(valoare: String) = if (modificat) this else copy(text = valoare)
}

fun modificariPraguri(drafturi: Map<String, DraftPrag>): Map<String, Double> =
    drafturi.filterValues { it.modificat }.mapValues { it.value.text.toDouble() }

// Fisierul privat ramane disponibil dupa recrearea activitatii sau a procesului.
class ExportCsvInAsteptare(directory: File) {
    private val fisier = File(directory, "export-in-asteptare.csv")

    fun exista(): Boolean = fisier.isFile

    fun pregateste(csv: String) {
        val temporar = File(fisier.parentFile, "export-in-asteptare.tmp")
        temporar.writeText(csv, Charsets.UTF_8)
        if (!temporar.renameTo(fisier)) {
            temporar.delete()
            throw IOException("Exportul nu a putut fi pregatit")
        }
    }

    fun salveaza(deschide: () -> OutputStream?) {
        // Verificam sursa inainte de a deschide/trunchia documentul destinatie.
        if (!exista()) throw IOException("Exportul pregatit nu mai exista")
        fisier.inputStream().use { input ->
            val output = deschide() ?: throw IOException("Documentul nu poate fi deschis")
            output.use { input.copyTo(it) }
        }
        anuleaza()
    }

    fun anuleaza() { fisier.delete() }
}

// Instanta traieste la nivel de proces; numai programatorul decide temporizarea.
class OprireLiveAmanata(private val programeaza: (() -> Unit) -> Unit) {
    private val active = mutableSetOf<Any>()
    private var generatie = 0L

    fun porneste(proprietar: Any) {
        active.add(proprietar)
        ++generatie
    }

    fun opreste(proprietar: Any, permanent: () -> Boolean, oprire: () -> Unit) {
        if (!active.remove(proprietar) || active.isNotEmpty()) return
        val cerere = ++generatie
        if (permanent()) return
        programeaza {
            if (cerere == generatie && active.isEmpty() && !permanent()) oprire()
        }
    }
}
