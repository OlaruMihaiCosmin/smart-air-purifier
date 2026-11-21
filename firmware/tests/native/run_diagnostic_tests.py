"""Executa helperul Diagnostic real cu serializarea FirebaseJson instalata, fara retea."""
from pathlib import Path
import json
import subprocess
import tempfile

from run_archive_patch_tests import generate_payload


def main():
    root = Path(__file__).resolve().parents[2]
    library = Path.home() / "OneDrive/Documents/Arduino/libraries/Firebase_Arduino_Client_Library_for_ESP8266_and_ESP32/src/json"
    if not library.exists():
        library = Path("C:/Users/olaru/OneDrive/Documents/Arduino/libraries/Firebase_Arduino_Client_Library_for_ESP8266_and_ESP32/src/json")
    with tempfile.TemporaryDirectory(prefix="smartair-diagnostic-") as tmp:
        binary, _ = generate_payload(library, root / "Esp_SmartPurifier.ino", Path(tmp))
        checks = 0
        def check(condition, message):
            nonlocal checks
            checks += 1
            assert condition, message
        def packet(mode):
            return json.loads(subprocess.check_output([str(binary), "x", "x", mode], text=True))
        p = packet("live")
        check("Diagnostic" in p, "Publicarea confirmata nu contine raportul Diagnostic")
        d = p["Diagnostic"]
        fields = set("schema raportat_la firmware_versiune firmware_build uptime_sec reset_motiv etapa_curenta etapa_anterioara_reset wifi_conectat wifi_rssi_dbm ntp_sincronizat firebase_initializat cloud_ack_varsta_sec heap_liber_bytes heap_minim_bytes psram_liber_bytes temperatura_mcu_c senzor_comunicatie_ok senzor_warmup_incheiat senzor_ultima_citire_varsta_ms canal_pm_valid canal_co2_valid canal_voc_valid canal_nox_valid canal_temperatura_valid canal_umiditate_valid fan_auto fan_cerut_pct fan_tinta_pct fan_aplicat_pct rpm offset_solicitat_c offset_aplicat_c night_auto night_activ nm_start nm_end istoric_recuperat istoric_ultimul_punct arhiva_reper_data arhiva_in_asteptare jurnal_recuperat jurnal_nepublicat jurnal jurnal_limitat".split())
        fields.add("timer")
        check(set(d) == fields, "Schema explicita evita campuri private sau disparute")
        check(d["schema"] == 1 and d["raportat_la"] == {".sv": "timestamp"}, "Schema si timestamp propriu server")
        check(d["firmware_versiune"] == p["Firmware"]["versiune"] and d["firmware_build"] == p["Firmware"]["build"], "Identitate compatibila cu DIAG-01")
        check(d["uptime_sec"] == 5, "Uptime exprimat in secunde")
        check(d["cloud_ack_varsta_sec"] is None, "Fara confirmare anterioara nu inventam ACK")
        check(d["senzor_ultima_citire_varsta_ms"] == 1000, "Citire I2C masurata in milisecunde")
        check(d["senzor_comunicatie_ok"] is True and d["senzor_warmup_incheiat"] is True, "Comunicatie si warmup distincte")
        check((d["fan_cerut_pct"], d["fan_tinta_pct"], d["fan_aplicat_pct"], d["fan_auto"]) == (0,40,25,True), "AUTO/cerere/tinta/rampa sunt distincte")
        check(abs(d["offset_solicitat_c"]+1.2)<0.001 and abs(d["offset_aplicat_c"]+0.8)<0.001, "Offset solicitat diferit de confirmat")
        check(d["heap_liber_bytes"] == 123456 and d["heap_minim_bytes"] == 65432 and d["psram_liber_bytes"] == 7000000, "Memoria este raportata in bytes")
        check(d["arhiva_in_asteptare"] == "" and d["arhiva_reper_data"] == "2026_09_29", "Data in asteptare nu este dedusa din reper")
        check(d["istoric_ultimul_punct"] == 1700000100, "Istoric in secunde Unix")
        check(d["nm_start"] == 1380 and d["nm_end"] == 420 and d["night_activ"] is True, "Night Mode in minute din zi")
        check(d["jurnal_limitat"] is False and d["jurnal"].endswith("#Comunicare SEN66 restabilita"), "Eveniment sigur pastrat")
        check("Diagnostic" not in packet("live-async"), "Pachetul rapid nu retrimite raportul")
        check("Diagnostic" not in packet("history"), "Istoricul nu improspateaza raportul")
        timer_fields=set("schema boot_id id activ creat_la_ms sfarsit_ms viteza_pct ramas_sec".split())
        check(set(d["timer"]) == timer_fields, "Diagnostic/timer are numai campurile cunoscute si foloseste data raportului")
        check(set(p["Timer"]) == timer_fields | {"raportat_la"}, "Timer live are timestamp server propriu")
        check(p["Timer"]["raportat_la"] == {".sv":"timestamp"}, "Timestamp Timer foloseste obiectul server")
        check(d["timer"]["boot_id"] == "0123456789abcdef" and d["timer"]["id"] == "" and
              d["timer"]["activ"] is False and all(d["timer"][k] == 0 for k in ("creat_la_ms","sfarsit_ms","viteza_pct","ramas_sec")), "Timer initial complet si inactiv")
        check("Timer" in packet("live-async") and "Timer" not in packet("history"), "Timer se publica la fiecare live, nu in istoric")
        active=packet("diag-timer-active")
        check(active["Timer"]["activ"] is True and active["Timer"]["ramas_sec"] == 300 and active["Timer"]["viteza_pct"] == 80, "Timer activ serializat coerent")
        check(active["Diagnostic"]["timer"] == {k:v for k,v in active["Timer"].items() if k != "raportat_la"}, "Diagnostic si live folosesc acelasi snapshot Timer")
        check(active["Diagnostic"]["fan_cerut_pct"] == 80 and active["Diagnostic"]["fan_auto"] is False, "Diagnosticul raporteaza treapta efectiva ceruta de timer")
        for mode in ("diag-timer-expired","diag-timer-cancelled"):
            finished=packet(mode)
            check(finished["Timer"]["activ"] is False and finished["Timer"]["ramas_sec"] == 0 and finished["Timer"]["id"] == active["Timer"]["id"], "Timer inactiv pastreaza ACK dupa " + mode)
            check(finished["Diagnostic"]["fan_cerut_pct"] == 0 and finished["Diagnostic"]["fan_auto"] is True, "Timer finalizat revine AUTO in diagnostic")
        boot = packet("diag-boot")["Diagnostic"]
        check(boot["senzor_ultima_citire_varsta_ms"] is None and boot["senzor_comunicatie_ok"] is False, "Boot fara citire reusita")
        check(boot["offset_aplicat_c"] is None and boot["etapa_anterioara_reset"] is None, "Boot fara confirmare offset/RTC")
        check(boot["senzor_warmup_incheiat"] is False and not any(boot[f] for f in fields if f.startswith("canal_")), "Warmup fara canale valide")
        check(boot["istoric_recuperat"] is False and boot["jurnal_recuperat"] is False and boot["jurnal_nepublicat"] is True, "Import si publicare independente")
        check(boot["istoric_ultimul_punct"] == 0 and boot["ntp_sincronizat"] is False, "Lipsa istoric si NTP nu devin valori confirmate")
        offline = packet("diag-offline")["Diagnostic"]
        check(offline["wifi_conectat"] is False and offline["wifi_rssi_dbm"] is None and offline["firebase_initializat"] is False, "WiFi deconectat nu raporteaza RSSI vechi")
        check(offline["cloud_ack_varsta_sec"] == 130, "Varsta ACK anterioara in secunde trunchiate")
        check(offline["senzor_comunicatie_ok"] is False and offline["senzor_ultima_citire_varsta_ms"] == 111999, "Eroarea I2C nu reseteaza varsta ultimei citiri reale")
        check(offline["canal_co2_valid"] is False and offline["canal_pm_valid"] is True, "Canale independente")
        check(offline["offset_aplicat_c"] is None and offline["temperatura_mcu_c"] is None, "Offset neconfirmat si NaN se omit in RTDB")
        check(offline["arhiva_in_asteptare"] == "2026_09_28", "Ziua in asteptare provine din starea reala")
        rollover = packet("diag-rollover")["Diagnostic"]
        check(rollover["uptime_sec"] == 4295090 and rollover["cloud_ack_varsta_sec"] == 4295067, "Uptime si ACK depasesc rollover millis")
        check(rollover["senzor_ultima_citire_varsta_ms"] == 4295089752, "Varsta I2C ramane pe 64 biti")
        check(packet("diag-long-uptime")["Diagnostic"]["uptime_sec"] == 3000000000, "Uptime nu este trunchiat la int32")
        check(packet("diag-rtc")["Diagnostic"]["etapa_anterioara_reset"] == "Citire_SEN66", "Etapa RTC este capturata inainte de suprascriere")
        for mode in ("diag-rtc-invalid", "diag-rtc-power", "diag-rtc-unterminated"):
            check(packet(mode)["Diagnostic"]["etapa_anterioara_reset"] is None, "RTC invalid sau power-on ramane necunoscut: " + mode)
        private = packet("diag-journal")["Diagnostic"]
        check("PRIVATE" not in private["jurnal"] and "password" not in private["jurnal"], "SSID/token/text arbitrar/stamp invalid nu se exporta")
        check(private["jurnal_limitat"] is True and "Night Mode pornit (programat)" in private["jurnal"], "Redactarea nu ascunde evenimentele sigure")
        maximal = packet("diag-max")["Diagnostic"]
        events = maximal["jurnal"].split("|")
        check(len(events) == 10 and events[0].startswith("1800000020#") and events[-1].startswith("1800000029#"), "Ultimele zece evenimente in ordine")
        check(max(len(e.encode()) for e in events) <= 160 and maximal["jurnal_limitat"] is True, "Limita per eveniment si indicator")
        long_event = packet("diag-journal-long")["Diagnostic"]
        check(len(long_event["jurnal"].encode()) <= 160 and long_event["jurnal_limitat"] is True, "Eveniment cloud foarte lung ramane limitat")
        payload_size = len(json.dumps(maximal, separators=(",", ":"), ensure_ascii=False).encode())
        check(payload_size <= 6144, "Bugetul nodului Diagnostic este 6KiB")
    print(f"{checks} verificari diagnostic, 0 esecuri; fixture maxima {payload_size} bytes")


if __name__ == "__main__":
    main()
