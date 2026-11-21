#pragma once
#include <stdint.h>
#include <string.h>

struct CerereTimer {
  char id[33] = {};
  char bootId[17] = {};
  int64_t creatLaMs = 0;
  int64_t sfarsitMs = 0;
  int vitezaPct = 0;
};

// Autoritate numai in RAM; timpul ramas nu depinde de corectiile ulterioare NTP.
class StareTimer {
 public:
  enum Rezultat { RESPINS, IGNORAT, PORNIT, MODIFICAT };

  void incepeBoot(const char* boot) {
    *this = StareTimer();
    if (hexValid(boot, 16)) memcpy(bootId_, boot, sizeof(bootId_));
  }

  static bool hexValid(const char* valoare, unsigned lungime) {
    if (!valoare || strlen(valoare) != lungime) return false;
    for (unsigned i = 0; i < lungime; ++i)
      if (!((valoare[i] >= '0' && valoare[i] <= '9') ||
            (valoare[i] >= 'a' && valoare[i] <= 'f'))) return false;
    return true;
  }

  Rezultat aplica(const CerereTimer& cerere, bool ceasValid, int64_t unixMs, uint64_t acumMs) {
    if (!hexValid(cerere.id, 32) || !hexValid(cerere.bootId, 16) ||
        strcmp(cerere.bootId, bootId_) != 0 || cerere.vitezaPct < 1 || cerere.vitezaPct > 100)
      return RESPINS;
    if (strcmp(cerere.id, cerere_.id) == 0) {
      // Ecoul/reconcilierea nu reinnoieste termenul, nici dupa anulare/expirare.
      if (!activ_ || cerere.creatLaMs != cerere_.creatLaMs || cerere.sfarsitMs != cerere_.sfarsitMs)
        return IGNORAT;
      if (cerere.vitezaPct == cerere_.vitezaPct) return IGNORAT;
      cerere_.vitezaPct = cerere.vitezaPct;
      return MODIFICAT;
    }
    if (cerere.creatLaMs < 946684800000LL || cerere.sfarsitMs > 253402300799999LL ||
        cerere.sfarsitMs <= cerere.creatLaMs) return RESPINS;
    const int64_t durata = cerere.sfarsitMs - cerere.creatLaMs;
    if (durata < 300000 || durata > 14400000 || durata % 300000 != 0) return RESPINS;
    // Reperul ramane dupa anulare/expirare. Egalitatea foloseste ID-ul drept departajare.
    if (cerere.creatLaMs < cerere_.creatLaMs ||
        (cerere.creatLaMs == cerere_.creatLaMs && strcmp(cerere.id, cerere_.id) <= 0))
      return IGNORAT;
    if (!ceasValid || cerere.creatLaMs - unixMs > 5000 || unixMs - cerere.creatLaMs > 30000 ||
        cerere.sfarsitMs <= unixMs) return RESPINS;
    cerere_ = cerere;
    const int64_t ramas = cerere.sfarsitMs - unixMs;
    // Toleranta intre ceasuri nu poate extinde durata aleasa sau limita de patru ore.
    termenMs_ = acumMs + static_cast<uint64_t>(ramas < durata ? ramas : durata);
    activ_ = true;
    return PORNIT;
  }

  bool expira(uint64_t acumMs) {
    if (!activ_ || acumMs < termenMs_) return false;
    activ_ = false;
    return true;
  }

  bool anuleaza() { bool anterior = activ_; activ_ = false; return anterior; }
  bool activ() const { return activ_; }
  int viteza() const { return cerere_.vitezaPct; }
  const char* bootId() const { return bootId_; }
  const CerereTimer& cerere() const { return cerere_; }
  uint32_t ramasSec(uint64_t acumMs) const {
    return activ_ && acumMs < termenMs_ ? static_cast<uint32_t>((termenMs_ - acumMs + 999) / 1000) : 0;
  }

 private:
  char bootId_[17] = {};
  CerereTimer cerere_;
  uint64_t termenMs_ = 0;
  bool activ_ = false;
};
