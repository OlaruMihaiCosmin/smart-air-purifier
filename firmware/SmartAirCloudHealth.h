#pragma once
#include <cstdint>

// Numai un raspuns HTTP confirmat dovedeste publicarea. Ceasul monoton pe
// 64 biti evita confundarea unei confirmari vechi cu una noua dupa 49,7 zile.
class StareCloud {
  bool pornit = false, areConfirmare = false, probaTrimisa = false;
  uint64_t inceput = 0, ultimaConfirmare = 0, ultimaProba = 0, ultimaRecuperare = 0;
  void porneste(uint64_t acum) {
    if (!pornit) { pornit = true; inceput = ultimaRecuperare = acum; }
  }
public:
  bool necesitaConfirmare(uint64_t acum) {
    porneste(acum);
    if (probaTrimisa && acum - ultimaProba < 60000) return false;
    probaTrimisa = true; ultimaProba = acum;
    return true;
  }
  void confirma(uint64_t acum) { areConfirmare = true; ultimaConfirmare = acum; }
  bool varstaConfirmare(uint64_t acum, uint64_t& varstaMs) const {
    if (!areConfirmare) return false;
    varstaMs = acum - ultimaConfirmare;
    return true;
  }
  bool confirmatRecent(uint64_t acum) const {
    return areConfirmare && acum - ultimaConfirmare < 90000;
  }
  bool impulsConfirmare(uint64_t acum) const {
    return areConfirmare && acum - ultimaConfirmare < 1000;
  }
  bool recuperareNecesara(bool initializat, bool wifi, uint64_t acum) {
    if (!initializat) return false;
    porneste(acum);
    if (!wifi || acum - (areConfirmare ? ultimaConfirmare : inceput) < 120000 ||
        acum - ultimaRecuperare < 120000) return false;
    ultimaRecuperare = acum;
    probaTrimisa = false;
    return true;
  }
};
