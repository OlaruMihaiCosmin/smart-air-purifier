#pragma once
#include <cmath>
#include <cstdint>

// Prima fereastra cere doua probe; numitorul ramane numarul real de probe valide.
inline int divizorPm(int citiri, bool primaFereastra) {
  return citiri >= (primaFereastra ? 2 : 1) ? citiri : 0;
}

class PlanificatorNtp {
  bool pornit = false;
  bool wifiAnterior = false;
  uint32_t ultima = 0;
public:
  bool solicita(bool wifi, bool oraValida, uint32_t acum) {
    const bool revenire = wifi && !wifiAnterior;
    wifiAnterior = wifi;
    const bool cere = wifi && (!pornit || revenire || (!oraValida && uint32_t(acum - ultima) >= 30000));
    if (cere) { pornit = true; ultima = acum; }
    return cere;
  }
};

// Politicile pure pot fi verificate pe calculator fara a simula bibliotecile hardware.
struct ValiditateSenzor {
  bool pm, co2, temperatura, umiditate, voc, nox;
  bool existaDate() const { return pm || co2 || temperatura || umiditate || voc || nox; }
};

inline ValiditateSenzor valideazaCitire(float pm1, float pm25, float pm4, float pm10,
                                      float umiditate, float temperatura, float voc, float nox,
                                      uint16_t co2, uint32_t varstaSenzor) {
  const auto pmValid = [](float v) { return std::isfinite(v) && v >= 0 && v < 6553.5f; };
  const bool prafGata = varstaSenzor >= 20000;
  const bool climaGata = varstaSenzor >= 60000;
  return {prafGata && pmValid(pm1) && pmValid(pm25) && pmValid(pm4) && pmValid(pm10),
          prafGata && co2 <= 40000,
          climaGata && std::isfinite(temperatura) && temperatura >= -163.84f && temperatura < 163.8f,
          climaGata && std::isfinite(umiditate) && umiditate >= 0 && umiditate <= 100,
          climaGata && std::isfinite(voc) && voc >= 1 && voc <= 500,
          climaGata && std::isfinite(nox) && nox >= 1 && nox <= 500};
}
