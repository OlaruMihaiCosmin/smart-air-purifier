#include "SmartAirReliability.h"
#include <cstdio>
#include <limits>

int esecuri = 0;
void verifica(bool ok, const char* nume) {
  if (!ok) { std::printf("FAIL: %s\n", nume); ++esecuri; }
}
int main() {
  verifica(divizorPm(0, true) == 0, "Prima fereastra fara citiri ramane gol");
  verifica(divizorPm(1, true) == 0, "Prima fereastra cu o citire pastreaza golul intentionat");
  verifica(divizorPm(2, true) == 2, "Prima medie din doua citiri foloseste doua la impartire");
  verifica(divizorPm(250, true) == 250, "Prima medie completa nu este amplificata");
  verifica(divizorPm(1, false) == 1, "Ferestrele ulterioare accepta o singura citire");
  PlanificatorNtp ceas;
  verifica(!ceas.solicita(false, false, 30000), "Boot fara router nu incearca NTP");
  verifica(ceas.solicita(true, false, 240000), "Router revenit dupa patru minute solicita NTP");
  verifica(!ceas.solicita(true, false, 240001), "Nu repetam NTP la fiecare bucla");
  verifica(ceas.solicita(true, false, 270000), "NTP indisponibil initial este reincercat");
  verifica(!ceas.solicita(true, true, 300000), "Ceasul sincronizat nu este resetat periodic");
  ceas.solicita(false, true, 310000);
  verifica(ceas.solicita(true, true, 340000), "Revenirea WiFi reia sincronizarea fara reboot");
  PlanificatorNtp rollover;
  rollover.solicita(true, false, 0xfffff000u);
  verifica(rollover.solicita(true, false, 25904u), "Temporizarea NTP rezista la rollover millis");
  auto defect = valideazaCitire(1, 2, 3, 4, 327.67f, 163.835f, 100, 1, 500, 60000);
  verifica(!defect.temperatura && !defect.umiditate, "Santinelele SEN66 nu sunt clima valida");
  auto partial = valideazaCitire(1, 2, 3, 4, 45, 21, 100, 1, 65535, 60000);
  verifica(partial.pm && partial.temperatura && !partial.co2, "CO2 invalid nu elimina senzorii valizi");
  auto nan = valideazaCitire(1, std::numeric_limits<float>::quiet_NaN(), 3, 4, 45, 21, 100, 1, 500, 60000);
  verifica(!nan.pm, "NaN nu intra in controlul AUTO");
  auto warm = valideazaCitire(1, 2, 3, 4, 45, 21, 100, 1, 500, 19999);
  verifica(!warm.existaDate(), "Warmup initial pastreaza toate canalele indisponibile");
  auto praf = valideazaCitire(0, 0, 0, 0, 45, 21, 100, 1, 500, 20000);
  verifica(praf.pm && praf.co2 && !praf.temperatura, "Praful zero este valid dupa 20 secunde");
  auto normal = valideazaCitire(1, 2, 3, 4, 100, -10, 500, 1, 40000, 60000);
  verifica(normal.pm && normal.co2 && normal.temperatura && normal.umiditate && normal.voc && normal.nox, "Limitele valide raman acceptate");
  std::printf("Esec testari: %d\n", esecuri);
  return esecuri ? 1 : 0;
}
