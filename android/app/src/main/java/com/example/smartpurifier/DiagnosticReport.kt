package com.example.smartpurifier

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val INDISPONIBIL = "indisponibil"
private const val PRIMUL_TIMESTAMP_MS = 946_684_800_000L
private const val ULTIMUL_TIMESTAMP_MS = 253_402_300_799_999L
private const val DIAGNOSTIC_RECENT_MS = 120_000L
private const val TOLERANTA_CEAS_MS = 30_000L

// Acceptăm numai câmpurile contractului; nici cheile necunoscute, nici obiectele arbitrare nu se exportă.
fun genereazaRaportDiagnostic(
    diagnostic: Any?,
    firmware: VersiuneFirmware?,
    versiuneAndroid: String,
    buildAndroid: String,
    generatLaMs: Long,
    conexiuneFirebase: Boolean,
    ultimulLiveMs: Long,
    zona: ZoneId = ZoneId.systemDefault()
): String {
    val intrare = diagnostic as? Map<*, *>
    val schema = intregDiagnostic(intrare?.get("schema"), 0, 1_000_000)
    val date = if (schema == 1L) intrare else null
    val raportatLa = timestampDiagnostic(date?.get("raportat_la"))
    val acum = timestampDiagnostic(generatLaMs)
    fun moment(ms: Long?): String = ms?.let {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(zona)
            .format(Instant.ofEpochMilli(it)) + " " + if (zona.rules.isFixedOffset && zona.rules.getOffset(Instant.ofEpochMilli(it)).totalSeconds == 0) "UTC" else zona.id
    } ?: INDISPONIBIL
    fun camp(cheie: String) = date?.get(cheie)
    fun intreg(cheie: String, maxim: Long = 4_294_967_295L) = intregDiagnostic(camp(cheie), 0, maxim)
    fun numar(cheie: String, minim: Double, maxim: Double) = numarDiagnostic(camp(cheie), minim, maxim)
    fun boolean(cheie: String, da: String = "da", nu: String = "nu") = when (camp(cheie)) {
        true -> da
        false -> nu
        else -> INDISPONIBIL
    }
    fun masura(cheie: String, minim: Double, maxim: Double, unitate: String): String =
        numar(cheie, minim, maxim)?.let { "${formatNumarDiagnostic(it)} $unitate" } ?: INDISPONIBIL
    fun memorie(cheie: String) = intreg(cheie, 17_179_869_184)?.let { "$it bytes (${it / 1024} KiB)" } ?: INDISPONIBIL
    fun vechime(timestamp: Long?): String = when {
        timestamp == null || acum == null -> "vechime necunoscută; starea actuală a plăcii nu este confirmată"
        timestamp - acum > TOLERANTA_CEAS_MS -> "dată în viitor; verifică ora telefonului; starea actuală a plăcii nu este confirmată"
        acum - timestamp > DIAGNOSTIC_RECENT_MS -> "VECHI (${durataDiagnostic((acum - timestamp) / 1000)}); starea actuală a plăcii nu este confirmată"
        timestamp > acum -> "recent, cu abatere ceas sub 30 s; reprezintă starea la raportare"
        else -> "recent (${(acum - timestamp) / 1000} s); reprezintă starea la raportare"
    }
    val versiune = versiuneDiagnostic(camp("firmware_versiune"))
    val build = buildDiagnostic(camp("firmware_build"))
    // Fallback-ul are propriul timestamp și nu repară prospețimea unui diagnostic absent/vechi.
    val fallback = firmware?.takeIf { timestampDiagnostic(it.raportatLaMs) != null }
    val folosesteFallback = versiune == null || build == null
    val versiuneAfisata = if (folosesteFallback) versiuneDiagnostic(fallback?.versiune) else versiune
    val buildAfisat = if (folosesteFallback) buildDiagnostic(fallback?.build) else build
    val nmStart = intreg("nm_start", 1439)
    val nmEnd = intreg("nm_end", 1439)
    val jurnal = textDiagnostic(camp("jurnal"), 6_000, permiteGol = true)
    // Timerul din DIAG apartine aceluiasi instantaneu, nu celui din telemetria live.
    val timer = (camp("timer") as? Map<*, *>)?.let {
        citesteStareTimer(it + mapOf("raportat_la" to raportatLa))
    }
    return buildString {
        fun rand(eticheta: String, valoare: Any?) { appendLine("$eticheta: ${valoare ?: INDISPONIBIL}") }
        appendLine("SmartAir — raport diagnostic")
        rand("Generat pe telefon", moment(acum))
        rand("Android", versiuneAndroid.take(120).replace(Regex("[\\p{Cntrl}]"), " "))
        rand("Build Android", buildAndroid.take(120).replace(Regex("[\\p{Cntrl}]"), " "))
        rand("Telefon ↔ Firebase", if (conexiuneFirebase) "conectat (conexiunea telefonului)" else "offline — ultimele valori cunoscute")
        if (!conexiuneFirebase) appendLine("Telefonul nu poate confirma date noi; starea actuală a plăcii nu este confirmată.")
        rand("Ultima telemetrie live", moment(timestampDiagnostic(ultimulLiveMs)))
        rand("Schema diagnostic", schema)
        if (date == null) {
            appendLine("Raport parțial. Diagnostic placă: indisponibil — schema lipsește, este invalidă sau nu este acceptată.")
        }
        rand("Raport diagnostic placă", moment(raportatLa))
        rand("Diagnostic", vechime(raportatLa))
        appendLine("Valorile de mai jos descriu instantaneul datat; conexiunea telefonului și telemetria live nu îi schimbă vechimea.")
        appendLine()
        rand("ESP32", versiuneAfisata)
        rand("Build ESP32", buildAfisat)
        if (folosesteFallback && fallback != null) {
            rand("Raport separat Firmware", moment(timestampDiagnostic(fallback.raportatLaMs)))
            appendLine("Versiune din ultimul raport Firmware cunoscut; poate fi vechi.")
        }
        rand("Uptime", intreg("uptime_sec", 31_557_600_000)?.let(::durataDiagnostic))
        rand("Motiv restart", motivDiagnostic(camp("reset_motiv")))
        rand("Etapă la raportare", etapaDiagnostic(camp("etapa_curenta")))
        rand("Etapă păstrată înainte de reset", etapaDiagnostic(camp("etapa_anterioara_reset")))
        appendLine("Backtrace: indisponibil; etapa păstrată nu dovedește cauza resetului.")
        appendLine()
        rand("WiFi placă la raportare", boolean("wifi_conectat", "conectat", "deconectat"))
        rand("RSSI", masura("wifi_rssi_dbm", -127.0, 0.0, "dBm"))
        rand("Ceas NTP sincronizat", boolean("ntp_sincronizat"))
        rand("Firebase inițializat pe placă", boolean("firebase_initializat"))
        rand("Vârsta confirmării cloud precedente", intreg("cloud_ack_varsta_sec")?.let { "$it s la raportare" })
        appendLine("Inițializarea Firebase nu dovedește transferul curent; confirmarea precedentă este măsurată înaintea acestui raport.")
        rand("Heap liber", memorie("heap_liber_bytes"))
        rand("Heap minim", memorie("heap_minim_bytes"))
        rand("PSRAM liberă", memorie("psram_liber_bytes"))
        rand("Temperatură MCU", masura("temperatura_mcu_c", -50.0, 150.0, "°C (cip, nu aer ambiant)"))
        appendLine()
        rand("Comunicare SEN66", boolean("senzor_comunicatie_ok", "OK", "eroare"))
        rand("Warmup încheiat", boolean("senzor_warmup_incheiat"))
        rand("Vârsta ultimei citiri reușite", intreg("senzor_ultima_citire_varsta_ms", 31_557_600_000_000)?.let { "$it ms la raportare" })
        appendLine("Canale la raportare — " + listOf("pm" to "PM", "co2" to "CO₂", "voc" to "VOC", "nox" to "NOx", "temperatura" to "Temperatură", "umiditate" to "Umiditate")
            .joinToString("; ") { (cheie, nume) -> "$nume: ${boolean("canal_${cheie}_valid", "valid", "invalid")}" })
        appendLine()
        rand("Mod ventilator", boolean("fan_auto", "AUTO", "manual"))
        rand("Treaptă cerută", numar("fan_cerut_pct", 0.0, 100.0)?.let { if (it == 0.0) "AUTO (0 %)" else "${formatNumarDiagnostic(it)} %" })
        rand("Țintă ventilator", masura("fan_tinta_pct", 0.0, 100.0, "%"))
        rand("Ieșire aplicată (rampă)", masura("fan_aplicat_pct", 0.0, 100.0, "%"))
        rand("RPM", intreg("rpm", 100_000))
        rand("Offset solicitat", masura("offset_solicitat_c", -50.0, 50.0, "°C"))
        rand("Offset aplicat", masura("offset_aplicat_c", -50.0, 50.0, "°C (confirmat I²C)"))
        rand("Programare noapte", boolean("night_auto", "activă", "oprită"))
        rand("Night Mode efectiv", boolean("night_activ", "activ", "oprit"))
        rand("Program noapte", if (nmStart != null && nmEnd != null) "${oraDiagnostic(nmStart)}–${oraDiagnostic(nmEnd)}" else null)
        rand("Timer", timer?.let { if (it.activ) "activ" else "inactiv" } ?: "indisponibil în acest raport")
        if (timer != null && timer.id.isNotEmpty()) {
            rand("Treaptă temporizată", "${timer.vitezaPct} %")
            rand("Rămas la raportare", formatDurataTimer(timer.ramasSec))
            rand("Final planificat", moment(timer.sfarsitMs))
            appendLine("Timerul revine în AUTO; pierderea WiFi păstrează comportamentul AUTO existent. Restartul abandonează timerul.")
        }
        appendLine()
        rand("Istoric recuperat", boolean("istoric_recuperat"))
        rand("Ultimul punct istoric", moment(intregDiagnostic(camp("istoric_ultimul_punct"), PRIMUL_TIMESTAMP_MS / 1000, ULTIMUL_TIMESTAMP_MS / 1000)?.times(1000)))
        rand("Reper arhivare (ziua următoare)", dataDiagnostic(camp("arhiva_reper_data")))
        rand("Arhivă în așteptare", if (camp("arhiva_in_asteptare") == "") "niciuna" else dataDiagnostic(camp("arhiva_in_asteptare")))
        rand("Jurnal recuperat", boolean("jurnal_recuperat"))
        rand("Jurnal nepublicat", boolean("jurnal_nepublicat"))
        rand("Jurnal limitat/filtrat", boolean("jurnal_limitat"))
        when {
            jurnal == null -> rand("Jurnal", INDISPONIBIL)
            jurnal.isEmpty() -> rand("Jurnal", "fără evenimente în instantaneu")
            else -> {
                appendLine("Ultimele evenimente (cel mult 10; ore locale):")
                jurnal.split('|').takeLast(10).forEach { eveniment ->
                    val separator = eveniment.indexOf('#')
                    val secunde = if (separator in 1..12) eveniment.substring(0, separator).toLongOrNull() else null
                    // Firmware-ul folosește 999999999 când ceasul nu este sincronizat.
                    val ms = secunde?.takeIf { it > 999_999_999 && it <= ULTIMUL_TIMESTAMP_MS / 1000 }?.times(1000)
                    val mesaj = if (separator >= 0) eveniment.substring(separator + 1) else ""
                    appendLine("- ${ms?.let { moment(it) } ?: "oră necunoscută"} — ${evenimentDiagnostic(mesaj)}")
                }
            }
        }
        appendLine()
        append("Limite: numai date instrumentate; fără tensiune măsurată, backtrace sau istoric de reseturi persistent. Câmpurile lipsă/invalide sunt indisponibile; identificatorii de rețea și textele nerecunoscute sunt omise.")
    }
}

private fun numarDiagnostic(value: Any?, minim: Double, maxim: Double): Double? {
    val n = when (value) {
        is Byte, is Short, is Int, is Long, is Float, is Double -> (value as Number).toDouble()
        else -> return null
    }
    return n.takeIf { it.isFinite() && it >= minim && it <= maxim }
}

private fun intregDiagnostic(value: Any?, minim: Long, maxim: Long): Long? =
    numarDiagnostic(value, minim.toDouble(), maxim.toDouble())?.takeIf { it % 1.0 == 0.0 }?.toLong()

private fun timestampDiagnostic(value: Any?): Long? = intregDiagnostic(value, PRIMUL_TIMESTAMP_MS, ULTIMUL_TIMESTAMP_MS)

private fun textDiagnostic(value: Any?, max: Int, permiteGol: Boolean = false): String? =
    (value as? String)?.takeIf { it.length <= max && (permiteGol || it.isNotBlank()) && it.none(Char::isISOControl) }

private fun formatNumarDiagnostic(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

private fun durataDiagnostic(secunde: Long): String =
    "${secunde / 86400} z ${(secunde % 86400) / 3600} h ${(secunde % 3600) / 60} min ${secunde % 60} s"

private fun oraDiagnostic(minute: Long): String = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

private fun versiuneDiagnostic(value: Any?): String? = textDiagnostic(value, 40)?.takeIf {
    Regex("[0-9]{1,4}(?:[.][0-9]{1,4}){1,3}").matches(it)
}

private fun buildDiagnostic(value: Any?): String? = textDiagnostic(value, 40)?.takeIf {
    Regex("(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) {1,2}[0-9]{1,2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2}").matches(it)
}

private fun dataDiagnostic(value: Any?): String? = textDiagnostic(value, 10)?.takeIf {
    Regex("[0-9]{4}_[0-9]{2}_[0-9]{2}").matches(it)
}?.replace('_', '-')?.let { runCatching { LocalDate.parse(it).toString() }.getOrNull() }

private val etapeDiagnostic = setOf(
    "Boot", "Setup", "Running_Loop", "Trimitere_Jurnal_FB", "Trimitere_Alerta_FCM", "Reset_VOC",
    "Calibrare_CO2", "Recuperare_Firebase", "Pierdut_WiFi", "Init_Firebase", "Citire_SEN66",
    "Reset_Senzor_Hardware", "Salvare_Calibrare_VOC", "Control_Ecran", "Aplicare_Setari_FB",
    "Reconciliere_Setari_FB", "Trimitere_Live_FB", "Salvare_Istoric_Local", "Recuperare_Istoric_FB",
    "Curatare_Erori_FB", "Confirmare_Arhiva_FB", "Salvare_Istoric_5Min"
)

private fun etapaDiagnostic(value: Any?): String? = textDiagnostic(value, 48)?.takeIf { it in etapeDiagnostic }

private fun motivDiagnostic(value: Any?): String? {
    val text = textDiagnostic(value, 160) ?: return null
    if (text in setOf("Pornit la Priza", "Restart din Aplicatie / OTA", "Cadere de Tensiune (Brownout)",
        "Restart prin Cablu USB (Upload/Serial Monitor)")) return text
    if (Regex("Motiv Necunoscut \\([0-9]{1,3}\\)").matches(text)) return text
    for (prefix in listOf("Eroare Fatala (Panic) in: ", "Blocat! (Interrupt WDT) in: ",
        "Blocat! (Task WDT) in: ", "Blocat! (Other WDT) in: ")) {
        if (text.startsWith(prefix)) return prefix + (etapaDiagnostic(text.removePrefix(prefix)) ?: "etapă indisponibilă")
    }
    return null
}

// Șabloanele închise păstrează evenimentele utile fără parole, SSID sau erori brute ale SDK-ului.
private fun evenimentDiagnostic(value: String): String {
    val text = textDiagnostic(value, 200) ?: return "Eveniment nerecunoscut (text omis)"
    if (text.startsWith("WiFi restabilit")) return "WiFi restabilit (rețea omisă)"
    if (text.startsWith("Eroare buffer Live FB")) return "Eroare buffer Live FB (detalii omise)"
    if (text.startsWith("Sistem Repornit: ")) return "Sistem Repornit: " + (motivDiagnostic(text.removePrefix("Sistem Repornit: ")) ?: "motiv indisponibil")
    if (text in setOf(
            "Curatare SEN66 Inceputa...", "Curatare SEN66 Finalizata.",
            "Comanda: Stergere calibrare VOC si Restart", "Comunicare SEN66 restabilita",
            "Calibrare CO2 respinsa: actualizati aplicatia si confirmati din nou.",
            "Calibrare CO2 respinsa: termen expirat sau ceas nesincronizat.",
            "Calibrare CO2 respinsa: termen expirat inainte de FRC.",
            "WDT: Senzorul SEN66 a blocat citirile de 15s. Reinitializare...",
            "Calibrare VOC extrasa si salvata.", "Eroare la extragerea VOC State.",
            "Night Mode pornit (programat)", "Night Mode oprit (programat)",
            "Firebase restabilit: publicare confirmata",
            "Timer pornit", "Timer expirat: revenire AUTO", "Timer anulat: revenire AUTO",
            // Mesajele deja filtrate de firmware pastreaza categoria evenimentului.
            "Sistem Repornit (motiv in raport)", "Aplicare offset termic esuata (detalii omise)",
            "Offset Termic modificat (detalii omise)", "FRC CO2 reusit (detalii omise)",
            "FRC CO2 esuat (detalii omise)", "Firebase fara confirmare 120s; refacere conexiune",
            "Eroare citire SEN66 (detalii omise)", "Arhiva confirmata (data in reperul arhivei)",
            "Eveniment omis (text neclasificat)"
        )) return text
    if (text.startsWith("Arhiva confirmata: ") && dataDiagnostic(text.removePrefix("Arhiva confirmata: ")) != null) return text
    val sabloane = listOf(
        "Aplicare offset termic esuata: Cod [0-9]{1,6}",
        "Offset Termic modificat: -?[0-9]{1,2}(?:[.][0-9]{1,3})? C",
        "FRC CO2 reusit[.] Corectie aplicata: -?[0-9]{1,6} ppm",
        "FRC CO2 esuat \\(cod [0-9]{1,6}\\)[.] Aparatul a stat destul la aer proaspat[?]",
        "Firebase fara confirmare 120s; refacere conexiune \\(HTTP -?[0-9]{1,4}\\)",
        "Eroare citire SEN66: Cod [0-9]{1,6}"
    )
    return if (sabloane.any { Regex(it).matches(text) }) text else "Eveniment nerecunoscut (text omis)"
}

// Numele separat nu intră în conflict cu CSV. Fișierul supraviețuiește selectorului și recreării Activity.
class ExportDiagnosticInAsteptare(directory: File) {
    private val fisier = File(directory, "diagnostic-in-asteptare.txt")

    fun exista(): Boolean = fisier.isFile

    fun pregateste(raport: String) {
        val temporar = File.createTempFile("diagnostic-", ".tmp", fisier.parentFile)
        try {
            temporar.writeText(raport, Charsets.UTF_8)
            // Inlocuirea explicita permite un raport nou dupa un export anterior esuat.
            Files.move(temporar.toPath(), fisier.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporar.delete()
        }
    }

    fun salveaza(deschide: () -> OutputStream?) {
        // Deschidem sursa înaintea destinației: lipsa ei nu trebuie să trunchieze documentul ales.
        if (!exista()) throw IOException("Raportul diagnostic pregătit nu mai există")
        fisier.inputStream().use { input ->
            val output = deschide() ?: throw IOException("Documentul nu poate fi deschis")
            output.use { input.copyTo(it) }
        }
        // Orice eroare de deschidere/scriere/închidere păstrează sursa pentru reîncercare.
        anuleaza()
    }

    fun anuleaza() { fisier.delete() }
}
