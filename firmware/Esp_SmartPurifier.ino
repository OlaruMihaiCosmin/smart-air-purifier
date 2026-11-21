#include <Arduino.h>
#include <WiFi.h>
#include <WiFiMulti.h>
#include <Firebase_ESP_Client.h>
#include <Adafruit_NeoPixel.h>
#include <Wire.h>
#include <SensirionI2cSen66.h>
#include <Preferences.h>
#include "time.h"
#include "esp_timer.h"
#include <esp_system.h>

// ==== LIBRARIILE OFICIALE ADAFRUIT PENTRU ECRAN ====
#include <Adafruit_GFX.h>
#include <Adafruit_ST7789.h>
#include <SPI.h>

// Fonturi HD pt Interfata
#include <Fonts/FreeSans9pt7b.h>
#include <Fonts/FreeSansBold12pt7b.h>
#include <Fonts/FreeSansBold18pt7b.h>
#include <Fonts/FreeSansBold24pt7b.h>

// Librarii auxiliare Firebase
#include "addons/TokenHelper.h"
#include "addons/RTDBHelper.h"

// Librarii OTA si Watchdog
#include <ESPmDNS.h>
#include <WiFiUdp.h>
#include <ArduinoOTA.h>
#include <esp_task_wdt.h>
#include <math.h>
#include "SmartAirReliability.h"
#include "SmartAirCloudHealth.h"
#include "SmartAirTimer.h"
#include "esp_sntp.h"

// Incrementam versiunea la fiecare livrare modificata; build-ul identifica si recompilarea.
constexpr char VERSIUNE_FIRMWARE[] = "2026.10.01.1";
constexpr char BUILD_FIRMWARE[] = __DATE__ " " __TIME__;

// ================= DEBUG CONTROL =================
#define DEBUG_SERIAL
// =================================================

// ================= DATE PRIVATE (WiFi, Firebase, OTA, FCM) =================
// Toate cheile, parolele si URL-urile stau in fisierul local "secrete.h", care
// NU se comite in git (vezi .gitignore) — codul principal poate fi publicat
// fara risc. Pentru un clone proaspat: copiaza "secrete.h.example" ca "secrete.h".
//
// Despre FCM: placa trimite alertele push DIRECT prin FCM HTTP v1, autentificata
// cu un cont de serviciu (Project settings -> Service accounts -> Generate new
// private key -> campurile se copiaza in secrete.h). CAT TIMP campurile FCM_*
// sunt goale, placa ramane pe email/parola si NU trimite notificari — totul
// functioneaza exact ca inainte (flash sigur si fara chei).
#include "secrete.h"
#define FCM_TOPIC "alerte_aer"

const char* TZ_INFO   = "EET-2EEST,M3.5.0/3,M10.5.0/4";
const char* ntpServer = "pool.ntp.org";
PlanificatorNtp planificatorNtp;
bool sincronizareNtpConfirmata = false;

void confirmaNtp(struct timeval*) {
  __atomic_store_n(&sincronizareNtpConfirmata, true, __ATOMIC_RELAXED);
}

bool ceasValid() {
  return __atomic_load_n(&sincronizareNtpConfirmata, __ATOMIC_RELAXED) && time(nullptr) >= 1704067200;
}

void intretineCeas(uint32_t acum) {
  if (planificatorNtp.solicita(WiFi.status() == WL_CONNECTED, ceasValid(), acum)) {
    // Pornirea SNTP este asincrona; nu blocam purificarea cat timp routerul isi revine.
    configTzTime(TZ_INFO, ntpServer, "time.cloudflare.com", "time.nist.gov");
  }
}

// Pini Hardware S3
#define I2C_SDA 8
#define I2C_SCL 9
#define PIN_LED_RGB 38
#define PIN_FAN_PWM 1
#define PIN_FAN_RPM 2
#define PIN_TFT_BL  16

// Pinii Magistralei SPI liberi pt OPI PSRAM
#define TFT_SCLK 6
#define TFT_MOSI 5
#define TFT_CS   4
#define TFT_DC   7
#define TFT_RST  15

#define PWM_FRECVENTA 25000
#define PWM_REZOLUTIE 8

// Culori Custom (Format RGB565)
#define C_BG       0x0821
#define C_PANEL    0x18E3
#define C_PANEL_B  0x3186
#define C_TEXT_DIM 0x8410
#define C_SKYBLUE  0x34BF

Adafruit_ST7789    tft    = Adafruit_ST7789(&SPI, TFT_CS, TFT_DC, TFT_RST);
Adafruit_NeoPixel  rgbLed(1, PIN_LED_RGB, NEO_GRB + NEO_KHZ800);
SensirionI2cSen66  sen66;
WiFiMulti          wifiMulti;

FirebaseData   fbdoScriere, fbdoCitire;
FirebaseData   fbdoStream;   // stream-ul de setari (serverul impinge schimbarile, nu mai facem polling)
FirebaseData   fbdoFcm;      // folosit doar la trimiterea notificarilor push FCM
FirebaseAuth   auth;
FirebaseConfig config;
bool firebaseGata = false;
StareCloud stareCloud;
bool firebaseAvertizat = false;
bool fcmActiv     = false;   // true doar daca cheile de service account sunt completate

Preferences storace;

bool isAutoMode = false;
unsigned long timpUltimaSchimbareAuto = 0;
int  vitezaAutoCurenta = 0;
bool eraAutoInainte    = false;

bool istoricRecuperat  = false;
bool bootLogSent       = false;
time_t ultimulSlotSalvat = 0;
String lastArchivedDateStr = "";
String arhivaDiagnosticInAsteptare = "";  // Oglinda pasiva; logica arhivarii detine starea.
long   lastIstoricSaveTime = 0;
unsigned long tAntSenzor = 0, tAntEcran = 0, tAntLive = 0,
              tAntFading = 0, tAntRpm = 0, tAntWifi = 0, tAntVocSave = 0,
              tAntPollSetari = 0;

unsigned long tUltimaCitireReusita = 0;
unsigned long tSenzorStart         = 0;
bool warmupSenzorTerminat = false;
bool diagnosticAreCitireSenzor = false;
uint64_t diagnosticUltimaCitireMs = 0;

uint32_t varstaSenzor(uint32_t t) {
  const uint32_t varsta = t - tSenzorStart;
  if (varsta >= 60000UL) warmupSenzorTerminat = true;
  // Dupa stabilizare, rotatia millis nu trebuie sa reporneasca incalzirea.
  return warmupSenzorTerminat ? 60000UL : varsta;
}

ValiditateSenzor validitateSenzor{};
bool areDate     = false;
bool isNightMode = false;
bool nightModeApp = false;
bool nightModePrimit = false;  // Valoarea din Firebase; programarea decide separat starea efectiva.

int  vitezaDinAplicatie = 0;
int  vitezaBaza = 0;  // speed din cloud; timerul suprapune temporar cererea efectiva.
StareTimer stareTimer;
int  luminozitateEcran  = 100;
int  luminozitateLed    = 150;
int  targetFanSpeed     = 0;
int  currentFanSpeed    = 0;
int  rpmReal            = 0;
bool modLiveRapid       = false;

float offsetTemperatura = -1.0;

// Praguri alerte push (sincronizate din aplicatie prin /SenzoriAer/setari)
float alertPm      = 20.0;
int   alertCo2     = 1600;
float alertVoc     = 250.0;
float alertNox     = 30.0;
int   notifFreqMin = 30;
bool  notifPraf = true, notifGaze = true;
unsigned long tUltimaAlertaPraf = 0, tUltimaAlertaGaze = 0;

// Night Mode programat: fereastra orara vine din aplicatie, dar o aplica PLACA,
// dupa ceasul propriu NTP — functioneaza si cu telefonul inchis/descarcat.
bool nmAuto = false;
int  nmStartMin = 23 * 60, nmEndMin = 7 * 60;
uint32_t tAntNmPublicare = 0;

// Istoric local inainte de prima sincronizare cu Firebase (boot fara WiFi):
// punctele se salveaza la cadenta de 5 min pe ceasul intern (millis), iar la prima
// conectare se lipesc dupa istoricul din cloud, cu -999 pe golul dintre ele.
unsigned long tAntSalvareLocala = 0;
int  puncteLocale = 0;
unsigned long millisPrimaSalvareLocala = 0, millisUltimaSalvareLocala = 0;

RTC_NOINIT_ATTR char rtc_last_state[64];
String motivRestart = "";
String etapaAnterioaraReset = "";

// =========================================================================
// VARIABILE PENTRU ANIMATIILE FLICKER-FREE (UI Dashboard)
// =========================================================================
bool forceFullRedraw = true;

int   old_co2  = -1,   old_voc = -1, old_nox = -1;
float old_pm25 = -1.0, old_temp = -99.0, old_umid = -1.0;
int   old_fan  = -1,   old_rpm = -1;
String old_time = "", old_date = "";
String old_ssid = "";

// Bounding boxes pentru valorile dinamice (pentru stergere precisa)
int16_t bx_co2,  by_co2;  uint16_t bw_co2,  bh_co2;
int16_t bx_pm,   by_pm;   uint16_t bw_pm,   bh_pm;
int16_t bx_voc,  by_voc;  uint16_t bw_voc,  bh_voc;
int16_t bx_nox,  by_nox;  uint16_t bw_nox,  bh_nox;
int16_t bx_temp, by_temp; uint16_t bw_temp, bh_temp;
int16_t bx_umid, by_umid; uint16_t bw_umid, bh_umid;
int16_t bx_fan,  by_fan;  uint16_t bw_fan,  bh_fan;
int16_t bx_rpm,  by_rpm;  uint16_t bw_rpm,  bh_rpm;
int16_t bx_time, by_time; uint16_t bw_time, bh_time;
int16_t bx_date, by_date; uint16_t bw_date, bh_date;
int16_t bx_ssid, by_ssid; uint16_t bw_ssid, bh_ssid;

int cur_co2=-1, cur_pm=-1, cur_voc=-1, cur_nox=-1, cur_temp=-1, cur_umid=-1;

// =========================================================================
// updateState
// =========================================================================
void updateState(const char* state) {
  strncpy(rtc_last_state, state, sizeof(rtc_last_state) - 1);
  rtc_last_state[sizeof(rtc_last_state) - 1] = '\0';
  #ifdef DEBUG_SERIAL
  if (Serial) { Serial.print(">> Executing: "); Serial.println(rtc_last_state); }
  #endif
}

String getResetReasonString() {
  esp_reset_reason_t reason = esp_reset_reason();
  switch (reason) {
    case ESP_RST_POWERON:  return "Pornit la Priza";
    case ESP_RST_SW:       return "Restart din Aplicatie / OTA";
    case ESP_RST_PANIC:    return String("Eroare Fatala (Panic) in: ")   + String(rtc_last_state);
    case ESP_RST_INT_WDT:  return String("Blocat! (Interrupt WDT) in: ") + String(rtc_last_state);
    case ESP_RST_TASK_WDT: return String("Blocat! (Task WDT) in: ")      + String(rtc_last_state);
    case ESP_RST_WDT:      return String("Blocat! (Other WDT) in: ")     + String(rtc_last_state);
    case ESP_RST_BROWNOUT: return "Cadere de Tensiune (Brownout)";
    case 11:               return "Restart prin Cablu USB (Upload/Serial Monitor)";
    default:               return "Motiv Necunoscut (" + String(reason) + ")";
  }
}

void clearI2CBus(int sdaPin, int sclPin) {
  pinMode(sdaPin, INPUT_PULLUP); pinMode(sclPin, INPUT_PULLUP); delay(10);
  if (digitalRead(sdaPin) == LOW) {
    #ifdef DEBUG_SERIAL
    if (Serial) Serial.println("I2C Bus Blocat! Incerc deblocarea...");
    #endif
    pinMode(sclPin, OUTPUT);
    for (int i = 0; i < 9; i++) {
      digitalWrite(sclPin, LOW);  delayMicroseconds(20);
      digitalWrite(sclPin, HIGH); delayMicroseconds(20);
    }
  }
  pinMode(sdaPin, INPUT); pinMode(sclPin, INPUT);
}

String getPaddedHistory(String istoric, time_t tNow, long lastSave) {
  if (lastSave <= 0) return istoric;
  // Probele offline pot avea secunde diferite; comparam sloturile, nu durata trunchiata.
  int intervalePierdute = (tNow / 300) - (lastSave / 300) - 1;
  if (intervalePierdute <= 0) return istoric;
  if (intervalePierdute > 288) intervalePierdute = 288;
  String rezultat = istoric;
  for (int i = 0; i < intervalePierdute; i++) {
    if (rezultat != "") rezultat += ",";
    rezultat += "-999";
  }
  int numarVirgule = 0;
  for (int i = 0; i < (int)rezultat.length(); i++) { if (rezultat[i] == ',') numarVirgule++; }
  while (numarVirgule >= 288) {
    int idx = rezultat.indexOf(',');
    if (idx != -1) rezultat = rezultat.substring(idx + 1); else break;
    numarVirgule--;
  }
  return rezultat;
}

String extrageArhivaIeri(String dateCurente, int puncteAzi) {
  // Istoricul scurt este o coada de zi, nu inceputul zilei. Completam in stanga.
  int probe = dateCurente.length() ? 1 : 0;
  for (unsigned int i=0; i<dateCurente.length(); ++i) if (dateCurente[i] == ',') ++probe;
  while (probe > 288) { dateCurente.remove(0, dateCurente.indexOf(',') + 1); --probe; }
  String prefix;
  for (int i=probe; i<288; ++i) { if (prefix.length()) prefix += ","; prefix += "-999"; }
  if (prefix.length() && dateCurente.length()) prefix += ",";
  prefix += dateCurente;
  dateCurente = prefix;
  if (puncteAzi < 0) puncteAzi = 0;
  if (puncteAzi >= 288) puncteAzi = 288;
  int puncteIeriRamase = 288 - puncteAzi;
  if (puncteIeriRamase <= 0) {
    String gol = "-999";
    for (int i = 1; i < 288; i++) gol += ",-999";
    return gol;
  }
  int currentIndex = 0;
  for (int i = 0; i < puncteIeriRamase; i++) {
    int nextComma = dateCurente.indexOf(',', currentIndex);
    if (nextComma == -1) { currentIndex = dateCurente.length() + 1; break; }
    currentIndex = nextComma + 1;
  }
  String valoriIeri = dateCurente;
  if (currentIndex > 0 && currentIndex <= (int)dateCurente.length()) {
    valoriIeri = dateCurente.substring(0, currentIndex - 1);
  }
  String arhivaReconstruita = "";
  for (int i = 0; i < puncteAzi; i++) arhivaReconstruita += "-999,";
  arhivaReconstruita += valoriIeri;
  return arhivaReconstruita;
}

// =========================================================================
// FILTRUL RPM (Software Timer + debounce)
// =========================================================================
esp_timer_handle_t tacho_timer;
volatile unsigned int validPulseCount = 0;
volatile uint8_t tachoState  = HIGH;
volatile uint8_t lowSamples  = 0;
volatile uint8_t highSamples = 0;

void IRAM_ATTR tachoPoll(void* arg) {
  uint8_t pin = digitalRead(PIN_FAN_RPM);
  // assignment explicit (nu ++/+=) pe variabile volatile: in C++20 operatorii compusi pe
  // volatile sunt deprecati (-Wvolatile); citire+scriere simpla evita avertismentul.
  if (pin == LOW) {
    if (lowSamples  < 255) lowSamples = lowSamples + 1;
    highSamples = 0;
  } else {
    if (highSamples < 255) highSamples = highSamples + 1;
    lowSamples = 0;
  }
  // Incrementare ATOMICA: citirea+resetul din loop() se fac de pe celalalt core,
  // un "++" simplu (load/add/store) ar putea anula resetul daca se intercaleaza.
  if (lowSamples  >= 4 && tachoState == HIGH) { tachoState = LOW;  __atomic_fetch_add(&validPulseCount, 1, __ATOMIC_RELAXED); }
  else if (highSamples >= 4 && tachoState == LOW)  { tachoState = HIGH; }
}

float pm1=-1, pm25=-1, pm4=-1, pm10=-1, umiditate=-999, temp=-999, voc=65535, nox=65535;
uint16_t co2 = 0;

float sumPM1=0, sumPM25=0, sumPM4=0, sumPM10=0, sumTemp=0, sumUmid=0, sumVOC=0, sumNOx=0;
unsigned long sumCO2 = 0;
// Prima fereastra pastreaza regula intentionata: o singura proba produce gol.
// Contorul real evita impartirea primului lot la un numar de probe prea mic.
bool primaFereastraPm = true;
int countPM=0, countCO2=0, countTemp=0, countUmid=0, countVOC=0, countNOx=0;

// Pastram separat indisponibilitatea de o valoare fizica egala cu zero.
void invalideazaMasurari() {
  validitateSenzor = {};
  areDate = false;
  pm1=pm25=pm4=pm10=-1;
  co2=65535; voc=nox=65535;
  temp=umiditate=-999;
}

String istPM25="", istCO2="", istVOC="", istNOx="", istTemp="", istUmid="";
String istPM1="", istPM4="", istPM10="";
String jurnalErori = "";
bool jurnalRecuperat = false, jurnalModificat = false, jurnalIncercat = false;
uint32_t tUltimaIncercareJurnal = 0;
bool eroriCuratateLaOra=false, wifiAvertizat=false, senzorAvertizat=false;
// Stare comunicare senzor (cu debounce): senzorEsuatComm devine true dupa cateva erori
// consecutive (~15s fara raspuns) -> LED mov + ecran de eroare. senzorEroareAfisata retine
// daca ecranul de eroare e deja desenat (ca sa nu-l redesenam in fiecare cadru -> fara palpaire).
int  senzorEroriConsecutive = 0;
bool senzorEsuatComm     = false;
bool senzorEroareAfisata = false;

void limiteazaJurnal() {
  int nrErori = 0;
  for (int i = 0; i < (int)jurnalErori.length(); i++) { if (jurnalErori[i] == '|') nrErori++; }
  while (nrErori >= 30) {
    int idx = jurnalErori.indexOf('|');
    if (idx != -1) jurnalErori.remove(0, idx + 1);
    nrErori--;
  }
}

void captureazaEtapaAnterioaraReset() {
  etapaAnterioaraReset = "";
  const auto motiv = esp_reset_reason();
  if (motiv == ESP_RST_POWERON || motiv == ESP_RST_BROWNOUT) return;
  // RTC_NOINIT nu garanteaza continut valid; citim numai un identificator terminat.
  size_t n = 0;
  while (n < sizeof(rtc_last_state) && rtc_last_state[n] != '\0') {
    const char c = rtc_last_state[n];
    if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
          (c >= '0' && c <= '9') || c == '_')) break;
    ++n;
  }
  if (n > 0 && n < sizeof(rtc_last_state) && rtc_last_state[n] == '\0')
    etapaAnterioaraReset = String(rtc_last_state);
  else strcpy(rtc_last_state, "Etapa_Necunoscuta");
}

void logSystem(String mesaj) {
  #ifdef DEBUG_SERIAL
  if (Serial) Serial.println(mesaj);
  #endif
  time_t now; time(&now);
  String tsStr = (now < 1000000000) ? "999999999" : String(now);
  if (jurnalErori != "") jurnalErori += "|";
  jurnalErori += tsStr + "#" + mesaj;
  limiteazaJurnal();
  // Evenimentele de boot/offline raman in RAM pana la import si confirmarea scrierii.
  jurnalModificat = true;
}

void recupereazaJurnal(const String& cloud) {
  if (jurnalRecuperat) return;
  if (cloud != "") jurnalErori = cloud + (jurnalErori != "" ? "|" + jurnalErori : "");
  limiteazaJurnal();
  jurnalRecuperat = true;
  // Bootul nu depinde de finalizarea celor noua serii de istoric.
  if (!bootLogSent) {
    logSystem("Sistem Repornit: " + motivRestart);
    bootLogSent = true;
  }
}

void trimiteJurnalDacaNecesar() {
  if (!jurnalRecuperat || !jurnalModificat || !firebaseGata ||
      WiFi.status() != WL_CONNECTED || !Firebase.ready()) return;
  const uint32_t acum = millis();
  if (jurnalIncercat && uint32_t(acum - tUltimaIncercareJurnal) < 10000UL) return;
  jurnalIncercat = true;
  tUltimaIncercareJurnal = acum;
  updateState("Trimitere_Jurnal_FB");
  if (Firebase.RTDB.setString(&fbdoScriere, "/SenzoriAer/ErrorLog", jurnalErori))
    jurnalModificat = false;
  updateState("Running_Loop");
}

void adaugaInIstoric(String &istoricActual, String valoareNoua, int puncteLipsa) {
  for (int i = 0; i < puncteLipsa; i++) {
    if (istoricActual.length() > 0) istoricActual += ",";
    istoricActual += "-999";
  }
  if (istoricActual.length() > 0) istoricActual += ",";
  istoricActual += valoareNoua;

  int numarVirgule = 0;
  for (int i = 0; i < (int)istoricActual.length(); i++) { if (istoricActual[i] == ',') numarVirgule++; }
  while (numarVirgule >= 288) {
    int idx = istoricActual.indexOf(',');
    if (idx != -1) istoricActual.remove(0, idx + 1); else break;
    numarVirgule--;
  }
}

// Construieste un punct de istoric din mediile acumulate si il adauga in toate
// cele 9 stringuri (cu "puncteLipsa" goluri -999 inainte). Reseteaza acumulatorii.
// Folosit si de salvarea normala la 5 minute, si de salvarea locala pre-sincronizare.
void salveazaPunctIstoric(int puncteLipsa) {
  String strPM25="-999", strCO2="-999", strVOC="-999", strNOx="-999";
  String strTemp="-999", strUmid="-999", strPM1="-999", strPM4="-999", strPM10="-999";

  const int divizor = divizorPm(countPM, primaFereastraPm);
  if (divizor > 0) { strPM1=String(sumPM1/divizor,1); strPM25=String(sumPM25/divizor,1); strPM4=String(sumPM4/divizor,1); strPM10=String(sumPM10/divizor,1); }
  if (countCO2  > 0) { strCO2=String((int)round((float)sumCO2/countCO2)); }
  if (countTemp > 0) strTemp=String(sumTemp/countTemp,1);
  if (countUmid > 0) strUmid=String(sumUmid/countUmid,1);
  if (countVOC  > 0) { strVOC=String((int)round(sumVOC/countVOC)); }
  if (countNOx  > 0) { strNOx=String((int)round(sumNOx/countNOx)); }

  adaugaInIstoric(istPM25, strPM25, puncteLipsa); adaugaInIstoric(istCO2,  strCO2,  puncteLipsa);
  adaugaInIstoric(istVOC,  strVOC,  puncteLipsa); adaugaInIstoric(istNOx,  strNOx,  puncteLipsa);
  adaugaInIstoric(istTemp, strTemp, puncteLipsa); adaugaInIstoric(istUmid, strUmid, puncteLipsa);
  adaugaInIstoric(istPM1,  strPM1,  puncteLipsa); adaugaInIstoric(istPM4,  strPM4,  puncteLipsa);
  adaugaInIstoric(istPM10, strPM10, puncteLipsa);

  sumPM1=0; sumPM25=0; sumPM4=0; sumPM10=0; sumTemp=0; sumUmid=0; sumVOC=0; sumNOx=0; sumCO2=0;
  countPM=0; countCO2=0; countTemp=0; countUmid=0; countVOC=0; countNOx=0;
  primaFereastraPm = false;
}

// Lipeste istoricul local (acumulat cat timp placa a fost offline de la boot) dupa
// cel din cloud, cu "gapSlots" goluri -999 intre ele; pastreaza cele mai noi 288 puncte.
String combinaArhivaZi(String existenta, String recuperata) {
  existenta = extrageArhivaIeri(existenta, 0);
  recuperata = extrageArhivaIeri(recuperata, 0);
  String rezultat;
  int vechi = 0, nou = 0;
  for (int i=0; i<288; ++i) {
    int sfVechi = existenta.indexOf(',', vechi);
    int sfNou = recuperata.indexOf(',', nou);
    if (sfVechi < 0) sfVechi = existenta.length();
    if (sfNou < 0) sfNou = recuperata.length();
    String a = existenta.substring(vechi, sfVechi);
    String b = recuperata.substring(nou, sfNou);
    if (i) rezultat += ",";
    rezultat += (a == "" || a == "-999" || a == "nan" || a == "NaN") ? b : a;
    vechi = sfVechi + 1; nou = sfNou + 1;
  }
  return rezultat;
}

String mergeIstoricCloudLocal(String cloud, const String &local, int gapSlots) {
  if (local.length() == 0) return cloud;
  String rezultat = cloud;
  for (int i = 0; i < gapSlots; i++) {
    if (rezultat.length() > 0) rezultat += ",";
    rezultat += "-999";
  }
  if (rezultat.length() > 0) rezultat += ",";
  rezultat += local;

  int numarVirgule = 0;
  for (int i = 0; i < (int)rezultat.length(); i++) { if (rezultat[i] == ',') numarVirgule++; }
  while (numarVirgule >= 288) {
    int idx = rezultat.indexOf(',');
    if (idx != -1) rezultat.remove(0, idx + 1); else break;
    numarVirgule--;
  }
  return rezultat;
}

// =========================================================================
// STREAM SETARI (push de la Firebase, inlocuieste polling-ul sincron la 2s)
// Callback-ul ruleaza pe task-ul intern al bibliotecii: aici DOAR copiem
// evenimentul intr-o coada FreeRTOS; procesarea (care poate bloca — ex.
// curatarea senzorului dureaza ~11s) se face in loop(), pe task-ul principal.
// =========================================================================
struct EvenimentSetari { char path[48]; char data[1024]; };
QueueHandle_t coadaSetari = NULL;
bool necesitaReconciliere = false;
bool necesitaSetariVentilator = false;  // Folosit numai din loop; stream-ul copiaza intentia in coada.

void streamCallback(FirebaseStream data) {
  if (coadaSetari == NULL) return;
  EvenimentSetari ev;
  if (data.dataPath().length() >= sizeof(ev.path) || data.payload().length() >= sizeof(ev.data)) {
    __atomic_store_n(&necesitaReconciliere, true, __ATOMIC_RELAXED);
    return;
  }
  strncpy(ev.path, data.dataPath().c_str(), sizeof(ev.path) - 1);
  ev.path[sizeof(ev.path) - 1] = '\0';
  strncpy(ev.data, data.payload().c_str(), sizeof(ev.data) - 1);
  ev.data[sizeof(ev.data) - 1] = '\0';
  // La depasirea cozii cerem un snapshot complet din bucla principala.
  if (xQueueSend(coadaSetari, &ev, 0) != pdTRUE)
    __atomic_store_n(&necesitaReconciliere, true, __ATOMIC_RELAXED);
}

void streamTimeoutCallback(bool timeout) {
  // Task-ul intern al setStreamCallback reconecteaza SINGUR stream-ul. NU apelam beginStream
  // din loop pe fbdoStream — ar rula concurent cu acest task pe acelasi obiect (buffere SSL
  // comune) si ar corupe memoria (Panic). Aici doar notam; plasa de siguranta (poll la 60s,
  // pe fbdoCitire) garanteaza oricum aplicarea setarilor chiar daca stream-ul ramane cazut.
  #ifdef DEBUG_SERIAL
  if (timeout && Serial) Serial.println(">> Stream setari timeout (reconectare automata)");
  #endif
}

// =========================================================================
// ALERTE PUSH FCM (trimise direct de pe placa, pe topicul abonat de telefon)
// Returneaza: 1 = trimisa, 0 = incercata dar esuata (consumam cooldown-ul ca sa
// nu blocam bucla cu reincercari TLS la fiecare secunda), -1 = nu s-a putut
// incerca (offline / Firebase nepregatit) — caz in care NU consumam cooldown-ul,
// ca alerta sa plece imediat ce revine conexiunea.
// =========================================================================
int trimiteAlertaFCM(String titlu, String mesaj) {
  if (!fcmActiv || !firebaseGata || WiFi.status() != WL_CONNECTED || !Firebase.ready()) return -1;
  updateState("Trimitere_Alerta_FCM");

  FCM_HTTPv1_JSON_Message msg;
  msg.topic              = FCM_TOPIC;
  msg.notification.title = titlu;
  msg.notification.body  = mesaj;
  msg.android.priority   = "HIGH";
  msg.android.notification.channel_id = "ALERTE_AER";

  // payload de date: telefonul isi actualizeaza widgetul din el cand primeste alerta
  FirebaseJson dataJson;
  dataJson.set("pm25", String(pm25, 1));
  dataJson.set("co2",  String((int)co2));
  msg.data = dataJson.raw();

  bool ok = Firebase.FCM.send(&fbdoFcm, &msg);
  // Alertele de poluare (PM2.5/CO2/VOC/NOx) NU se mai scriu in Jurnalul de Erori — umpleau
  // jurnalul cu intrari de rutina. Le pastram doar pe serial, pentru debug.
  #ifdef DEBUG_SERIAL
  if (Serial) Serial.println(ok ? ("Alerta push trimisa: " + mesaj)
                                : ("Alerta push esuata: " + fbdoFcm.errorReason()));
  #endif
  updateState("Running_Loop");
  return ok ? 1 : 0;
}

void verificaAlertePoluare(unsigned long t) {
  if (!fcmActiv || (varstaSenzor(t) < 60000UL)) return;   // nimic in warmup
  unsigned long cooldown = (unsigned long)notifFreqMin * 60000UL;

  if (notifPraf && validitateSenzor.pm && pm25 >= alertPm &&
      (tUltimaAlertaPraf == 0 || t - tUltimaAlertaPraf >= cooldown)) {
    if (trimiteAlertaFCM("Alerta Praf", "Nivel ridicat de PM2.5: " + String(pm25, 1) + " ug/m3!") >= 0)
      tUltimaAlertaPraf = t;
  }

  if (notifGaze && (tUltimaAlertaGaze == 0 || t - tUltimaAlertaGaze >= cooldown)) {
    String motiv = "";
    if      (validitateSenzor.co2 && co2 >= alertCo2 && co2 != 0 && co2 != 65535) motiv = "CO2 a ajuns la " + String(co2) + " ppm!";
    else if (validitateSenzor.voc && voc >= alertVoc && voc <= 500)               motiv = "VOC a ajuns la nivelul " + String((int)voc) + "!";
    else if (validitateSenzor.nox && nox >= alertNox && nox <= 500)               motiv = "NOx a ajuns la nivelul " + String((int)nox) + "!";
    if (motiv != "" && trimiteAlertaFCM("Alerta Aerisire", motiv) >= 0) tUltimaAlertaGaze = t;
  }
}

// =========================================================================
// APLICAREA SETARILOR + COMENZILE VENITE DIN APLICATIE
// =========================================================================
bool offsetFortatLaBoot = false;
float offsetSolicitatTemp = 0;
bool offsetIncercat = false, offsetAvertizat = false;
uint32_t tUltimaIncercareOffset = 0;

void aplicaOffsetTemp(float newOffset) {
  if (!isfinite(newOffset) || newOffset < -3.0f || newOffset > 3.0f) return;
  offsetSolicitatTemp = newOffset;
  if (offsetFortatLaBoot && fabs(newOffset - offsetTemperatura) <= 0.05) return;
  const uint32_t acum = millis();
  if (offsetIncercat && uint32_t(acum - tUltimaIncercareOffset) < 10000UL) return;
  offsetIncercat = true;
  tUltimaIncercareOffset = acum;
  const int16_t eroare = sen66.setTemperatureOffsetParameters((int16_t)(newOffset * 200), 0, 0, 0);
  unsigned long tWait = millis();
  while (millis() - tWait < 20) { yield(); }
  if (eroare != 0) {
    if (!offsetAvertizat) {
      logSystem("Aplicare offset termic esuata: Cod " + String(eroare));
      offsetAvertizat = true;
    }
    return;
  }
  const bool modificat = fabs(newOffset - offsetTemperatura) > 0.05;
  // RAM/NVS descriu numai valoarea confirmata de comanda I2C; cererea ramane pentru retry.
  offsetTemperatura = newOffset;
  if (modificat) storace.putFloat("tempOffset", offsetTemperatura);
  if (modificat || offsetAvertizat)
    logSystem("Offset Termic modificat: " + String(offsetTemperatura) + " C");
  offsetAvertizat = false;
  offsetFortatLaBoot = true;
}

String mesajDiagnostic(const String& mesaj, bool& limitat) {
  // Lista explicita impiedica exportul SSID-ului, erorilor SDK si textelor cloud arbitrare.
  const char* mesajeSigure[] = {
    "Curatare SEN66 Inceputa...", "Curatare SEN66 Finalizata.",
    "Comanda: Stergere calibrare VOC si Restart",
    "Calibrare CO2 respinsa: actualizati aplicatia si confirmati din nou.",
    "Calibrare CO2 respinsa: termen expirat sau ceas nesincronizat.",
    "Calibrare CO2 respinsa: termen expirat inainte de FRC.",
    "Comunicare SEN66 restabilita",
    "WDT: Senzorul SEN66 a blocat citirile de 15s. Reinitializare...",
    "Calibrare VOC extrasa si salvata.", "Eroare la extragerea VOC State.",
    "Night Mode pornit (programat)", "Night Mode oprit (programat)",
    "Firebase restabilit: publicare confirmata",
    "Timer pornit", "Timer expirat: revenire AUTO", "Timer anulat: revenire AUTO"
  };
  for (const char* sigur : mesajeSigure) if (mesaj == sigur) return mesaj;
  const char* prefixe[] = {"Sistem Repornit: ", "WiFi restabilit pe: ",
    "Eroare buffer Live FB: ", "Aplicare offset termic esuata: Cod ",
    "Offset Termic modificat: ", "FRC CO2 reusit. Corectie aplicata: ",
    "FRC CO2 esuat (cod ", "Firebase fara confirmare 120s; ",
    "Eroare citire SEN66: Cod ", "Arhiva confirmata: "};
  const char* descrieri[] = {"Sistem Repornit (motiv in raport)", "WiFi restabilit (SSID omis)",
    "Eroare buffer Live FB (detalii omise)", "Aplicare offset termic esuata (detalii omise)",
    "Offset Termic modificat (detalii omise)", "FRC CO2 reusit (detalii omise)",
    "FRC CO2 esuat (detalii omise)", "Firebase fara confirmare 120s; refacere conexiune",
    "Eroare citire SEN66 (detalii omise)", "Arhiva confirmata (data in reperul arhivei)"};
  limitat = true;
  for (size_t i = 0; i < sizeof(prefixe) / sizeof(prefixe[0]); ++i)
    if (mesaj.startsWith(prefixe[i])) return descrieri[i];
  return "Eveniment omis (text neclasificat)";
}

String jurnalDiagnostic(bool& limitat) {
  limitat = false;
  int evenimente = jurnalErori.length() ? 1 : 0;
  for (unsigned int i = 0; i < jurnalErori.length(); ++i) if (jurnalErori[i] == '|') ++evenimente;
  int start = 0;
  while (evenimente > 10) { start = jurnalErori.indexOf('|', start) + 1; --evenimente; limitat = true; }
  String rezultat;
  while (start < (int)jurnalErori.length()) {
    int end = jurnalErori.indexOf('|', start);
    if (end < 0) end = jurnalErori.length();
    const int capatLimitat = end - start > 160 ? start + 160 : end;
    if (capatLimitat != end) limitat = true;
    String eveniment = jurnalErori.substring(start, capatLimitat);
    const int hash = eveniment.indexOf('#');
    String stamp = hash > 0 && hash <= 11 ? eveniment.substring(0, hash) : "";
    for (unsigned int i = 0; i < stamp.length(); ++i)
      if (stamp[i] < '0' || stamp[i] > '9') { stamp = ""; break; }
    if (stamp == "") { stamp = "999999999"; limitat = true; }
    String mesaj = hash >= 0 ? eveniment.substring(hash + 1) : eveniment;
    eveniment = stamp + "#" + mesajDiagnostic(mesaj, limitat);
    if (eveniment.length() > 160) { eveniment = eveniment.substring(0, 160); limitat = true; }
    if (rezultat.length()) rezultat += "|";
    rezultat += eveniment;
    start = end + 1;
  }
  return rezultat;
}

void adaugaStareTimer(FirebaseJson& json, const String& prefix, bool timestamp) {
  const CerereTimer& cerere = stareTimer.cerere();
  json.set(prefix + "/schema", 1);
  json.set(prefix + "/boot_id", stareTimer.bootId());
  json.set(prefix + "/id", cerere.id);
  json.set(prefix + "/activ", stareTimer.activ());
  json.set(prefix + "/creat_la_ms", cerere.creatLaMs);
  json.set(prefix + "/sfarsit_ms", cerere.sfarsitMs);
  json.set(prefix + "/viteza_pct", cerere.vitezaPct);
  json.set(prefix + "/ramas_sec", stareTimer.ramasSec(esp_timer_get_time() / 1000ULL));
  if (timestamp) json.set(prefix + "/raportat_la/.sv", "timestamp");
}

void adaugaDiagnostic(FirebaseJson& json) {
  const uint64_t acumMs = esp_timer_get_time() / 1000ULL;
  uint64_t varstaAckMs = 0;
  const bool wifi = WiFi.status() == WL_CONNECTED;
  const float temperaturaMcu = temperatureRead();
  json.set("Diagnostic/schema", 1);
  json.set("Diagnostic/raportat_la/.sv", "timestamp");
  json.set("Diagnostic/firmware_versiune", VERSIUNE_FIRMWARE);
  json.set("Diagnostic/firmware_build", BUILD_FIRMWARE);
  json.set("Diagnostic/uptime_sec", acumMs / 1000ULL);
  json.set("Diagnostic/reset_motiv", motivRestart);
  json.set("Diagnostic/etapa_curenta", rtc_last_state);
  if (etapaAnterioaraReset.length()) json.set("Diagnostic/etapa_anterioara_reset", etapaAnterioaraReset);
  else json.set("Diagnostic/etapa_anterioara_reset");
  json.set("Diagnostic/wifi_conectat", wifi);
  if (wifi) json.set("Diagnostic/wifi_rssi_dbm", WiFi.RSSI()); else json.set("Diagnostic/wifi_rssi_dbm");
  json.set("Diagnostic/ntp_sincronizat", ceasValid());
  json.set("Diagnostic/firebase_initializat", firebaseGata);
  // Varsta ACK-ului anterior; publicarea acestui raport inca nu a fost confirmata.
  if (stareCloud.varstaConfirmare(acumMs, varstaAckMs)) json.set("Diagnostic/cloud_ack_varsta_sec", varstaAckMs / 1000ULL);
  else json.set("Diagnostic/cloud_ack_varsta_sec");
  json.set("Diagnostic/heap_liber_bytes", ESP.getFreeHeap());
  json.set("Diagnostic/heap_minim_bytes", ESP.getMinFreeHeap());
  json.set("Diagnostic/psram_liber_bytes", ESP.getFreePsram());
  if (isfinite(temperaturaMcu)) json.set("Diagnostic/temperatura_mcu_c", temperaturaMcu);
  else json.set("Diagnostic/temperatura_mcu_c");
  json.set("Diagnostic/senzor_comunicatie_ok", diagnosticAreCitireSenzor && senzorEroriConsecutive == 0 && !senzorEsuatComm);
  json.set("Diagnostic/senzor_warmup_incheiat", warmupSenzorTerminat);
  if (diagnosticAreCitireSenzor) json.set("Diagnostic/senzor_ultima_citire_varsta_ms", acumMs - diagnosticUltimaCitireMs);
  else json.set("Diagnostic/senzor_ultima_citire_varsta_ms");
  json.set("Diagnostic/canal_pm_valid", validitateSenzor.pm);
  json.set("Diagnostic/canal_co2_valid", validitateSenzor.co2);
  json.set("Diagnostic/canal_voc_valid", validitateSenzor.voc);
  json.set("Diagnostic/canal_nox_valid", validitateSenzor.nox);
  json.set("Diagnostic/canal_temperatura_valid", validitateSenzor.temperatura);
  json.set("Diagnostic/canal_umiditate_valid", validitateSenzor.umiditate);
  json.set("Diagnostic/fan_auto", isAutoMode);
  json.set("Diagnostic/fan_cerut_pct", vitezaDinAplicatie);
  json.set("Diagnostic/fan_tinta_pct", targetFanSpeed);
  json.set("Diagnostic/fan_aplicat_pct", currentFanSpeed);
  json.set("Diagnostic/rpm", rpmReal);
  adaugaStareTimer(json, "Diagnostic/timer", false);
  json.set("Diagnostic/offset_solicitat_c", offsetSolicitatTemp);
  if (offsetFortatLaBoot) json.set("Diagnostic/offset_aplicat_c", offsetTemperatura);
  else json.set("Diagnostic/offset_aplicat_c");
  json.set("Diagnostic/night_auto", nmAuto);
  json.set("Diagnostic/night_activ", nightModeApp);
  json.set("Diagnostic/nm_start", nmStartMin);
  json.set("Diagnostic/nm_end", nmEndMin);
  json.set("Diagnostic/istoric_recuperat", istoricRecuperat);
  json.set("Diagnostic/istoric_ultimul_punct", lastIstoricSaveTime);
  json.set("Diagnostic/arhiva_reper_data", lastArchivedDateStr);
  json.set("Diagnostic/arhiva_in_asteptare", arhivaDiagnosticInAsteptare);
  json.set("Diagnostic/jurnal_recuperat", jurnalRecuperat);
  json.set("Diagnostic/jurnal_nepublicat", jurnalModificat);
  bool jurnalLimitat = false;
  json.set("Diagnostic/jurnal", jurnalDiagnostic(jurnalLimitat));
  json.set("Diagnostic/jurnal_limitat", jurnalLimitat);
}

// Recitim intentia curenta: un eveniment vechi din coada nu executa comanda de doua ori.
bool consumaComanda(const char* nume) {
  const String cale = String("/SenzoriAer/setari/") + nume;
  if (!firebaseGata || WiFi.status() != WL_CONNECTED) return false;
  if (!Firebase.RTDB.getBool(&fbdoCitire, cale) || !fbdoCitire.boolData()) return false;
  return Firebase.RTDB.setBool(&fbdoScriere, cale, false);
}

void comandaRestart() {
  if (!consumaComanda("restart")) return;
  delay(100); ESP.restart();
}

void comandaCuratare() {
  if (!consumaComanda("curatare")) return;
  tft.fillScreen(ST77XX_BLACK); tft.setCursor(30, 160); tft.setTextColor(ST77XX_ORANGE); tft.print("CURATARE SENZOR");
  logSystem("Curatare SEN66 Inceputa...");
  sen66.stopMeasurement();
  unsigned long tCuratareStop = millis();
  while (millis() - tCuratareStop < 1500) { esp_task_wdt_reset(); yield(); }
  sen66.startFanCleaning();
  unsigned long tCuratare = millis();
  while (millis() - tCuratare < 10000) { ArduinoOTA.handle(); esp_task_wdt_reset(); yield(); delay(10); }
  sen66.startContinuousMeasurement();
  unsigned long tWait = millis();
  while (millis() - tWait < 50) { esp_task_wdt_reset(); yield(); }
  logSystem("Curatare SEN66 Finalizata.");
  forceFullRedraw = true;
}

// Resetare calibrare VOC: sterge starea invatata a algoritmului din NVS si restarteaza.
// La boot, setup() face deviceReset() pe senzor si nu mai gaseste stare de restaurat,
// deci algoritmul VOC porneste de la zero — re-invatare 12-24h, conform Sensirion.
// (Starea VOC e volatila in senzor; doar copia din NVS o facea persistenta.)
void comandaResetVoc() {
  if (!consumaComanda("reset_voc")) return;
  tft.fillScreen(ST77XX_BLACK); tft.setCursor(30, 160); tft.setTextColor(ST77XX_RED); tft.print("RESET VOC");
  updateState("Reset_VOC");
  logSystem("Comanda: Stergere calibrare VOC si Restart");
  storace.remove("vocState");

  // RAM dispare la restart: incercam o publicare confirmata, chiar dupa un ACK recent.
  // Garda de import ramane activa, astfel nu suprascriem un jurnal inca necitit.
  jurnalIncercat = false;
  trimiteJurnalDacaNecesar();
  ESP.restart();
}

bool calibrareCo2InTermen(double expira) {
  if (!ceasValid() || !isfinite(expira) || expira != floor(expira)) return false;
  const double acum = static_cast<double>(time(nullptr));
  return expira > acum && expira - acum <= 120.0;
}

// Aplicatiile vechi nu trimit termen; consumam intentia fara calibrare intarziata.
void respingeCalibrareCo2Veche() {
  if (consumaComanda("calibrare_co2"))
    logSystem("Calibrare CO2 respinsa: actualizati aplicatia si confirmati din nou.");
}

// Calibrare fortata CO2 (FRC) la aer proaspat, conform datasheet SEN6x (cmd 0x6707):
// disponibila DOAR in idle -> oprim masurarea si asteptam 600 ms inainte de comanda;
// procedura dureaza ~500 ms; corectia returnata = valoare - 0x8000 (0xFFFF = esec);
// rezultatul e PERSISTENT peste reset/power-cycle, deci NU e nevoie de restart —
// masurarea continua e reluata imediat. Aplicatia instruieste utilizatorul sa tina
// aparatul la aer proaspat (~420 ppm) minim 3 minute inainte, altfel senzorul s-ar
// calibra pe aerul viciat din camera.
void comandaCalibrareCo2() {
  const char* cale = "/SenzoriAer/setari/calibrare_co2_expira";
  if (!firebaseGata || WiFi.status() != WL_CONNECTED) return;
  // Recitim termenul actual; un eveniment vechi nu poate reinnoi cererea consumata.
  if (!Firebase.RTDB.getDouble(&fbdoCitire, cale)) return;
  const String tip = fbdoCitire.dataType();
  if (tip != "int" && tip != "float" && tip != "double") return;
  const double expira = fbdoCitire.doubleData();
  if (expira <= 0) return;
  const bool inTermen = calibrareCo2InTermen(expira);
  if (!Firebase.RTDB.setInt(&fbdoScriere, cale, 0)) return;
  if (!inTermen || !calibrareCo2InTermen(expira)) {
    logSystem("Calibrare CO2 respinsa: termen expirat sau ceas nesincronizat.");
    return;
  }
  tft.fillScreen(ST77XX_BLACK); tft.setCursor(30, 160); tft.setTextColor(ST77XX_ORANGE); tft.print("CALIBRARE CO2");
  updateState("Calibrare_CO2");

  sen66.stopMeasurement();
  unsigned long tW = millis();
  while (millis() - tW < 600) { esp_task_wdt_reset(); yield(); }

  // Inclusiv oprirea senzorului poate trece de termen; nu executam atunci FRC.
  if (!calibrareCo2InTermen(expira)) {
    sen66.startContinuousMeasurement();
    logSystem("Calibrare CO2 respinsa: termen expirat inainte de FRC.");
    forceFullRedraw = true;
    return;
  }
  uint16_t corectie = 0xFFFF;
  int16_t errFrc = sen66.performForcedCo2Recalibration(420, corectie);
  tW = millis();
  while (millis() - tW < 600) { esp_task_wdt_reset(); yield(); }

  if (errFrc == 0 && corectie != 0xFFFF)
    logSystem("FRC CO2 reusit. Corectie aplicata: " + String((int)corectie - 0x8000) + " ppm");
  else
    logSystem("FRC CO2 esuat (cod " + String(errFrc) + "). Aparatul a stat destul la aer proaspat?");

  sen66.startContinuousMeasurement();
  tW = millis();
  while (millis() - tW < 50) { esp_task_wdt_reset(); yield(); }
  forceFullRedraw = true;
}

// Snapshot complet de setari (la conectarea stream-ului sau patch cu mai multe campuri).
// Folosim rezultatul fiecarui get(): biblioteca poate pastra jsonData la o cheie lipsa.
// Astfel, patch-urile partiale nu copiaza valoarea precedenta peste alte setari/comenzi.
int64_t timerAcumUnixMs() {
  struct timeval acum;
  gettimeofday(&acum, nullptr);
  return static_cast<int64_t>(acum.tv_sec) * 1000 + acum.tv_usec / 1000;
}

void intretineTimer() {
  if (stareTimer.expira(esp_timer_get_time() / 1000ULL)) {
    vitezaBaza = 0;
    logSystem("Timer expirat: revenire AUTO");
  }
  vitezaDinAplicatie = stareTimer.activ() ? stareTimer.viteza() : vitezaBaza;
}

bool citesteNumarTimer(FirebaseJson& json, const String& cale, int64_t& valoare) {
  FirebaseJsonData data;
  if (!json.get(data, cale) || (data.typeNum != FirebaseJson::JSON_INT &&
      data.typeNum != FirebaseJson::JSON_FLOAT && data.typeNum != FirebaseJson::JSON_DOUBLE)) return false;
  const double numar = data.doubleValue;
  if (!isfinite(numar) || numar != floor(numar) || numar < 0 || numar > 9007199254740991.0) return false;
  valoare = static_cast<int64_t>(numar);
  return true;
}

void aplicaObiectTimer(FirebaseJson& json, const String& prefix) {
  intretineTimer();
  vitezaBaza = 0;  // Un timer invalid/vechi nu reactiveaza o treapta manuala anterioara.
  CerereTimer cerere;
  FirebaseJsonData data;
  int64_t viteza = 0;
  bool valid = json.get(data, prefix + "id") && data.typeNum == FirebaseJson::JSON_STRING &&
               StareTimer::hexValid(data.stringValue.c_str(), 32);
  if (valid) memcpy(cerere.id, data.stringValue.c_str(), sizeof(cerere.id));
  valid = valid && json.get(data, prefix + "boot_id") && data.typeNum == FirebaseJson::JSON_STRING &&
          StareTimer::hexValid(data.stringValue.c_str(), 16);
  if (valid) memcpy(cerere.bootId, data.stringValue.c_str(), sizeof(cerere.bootId));
  valid = valid && citesteNumarTimer(json, prefix + "creat_la_ms", cerere.creatLaMs) &&
          citesteNumarTimer(json, prefix + "sfarsit_ms", cerere.sfarsitMs) &&
          citesteNumarTimer(json, prefix + "viteza_pct", viteza) && viteza >= 1 && viteza <= 100;
  cerere.vitezaPct = static_cast<int>(viteza);
  const StareTimer::Rezultat rezultat = valid
      ? stareTimer.aplica(cerere, ceasValid(), timerAcumUnixMs(), esp_timer_get_time() / 1000ULL)
      : StareTimer::RESPINS;
  if (rezultat == StareTimer::PORNIT) logSystem("Timer pornit");
  if (rezultat == StareTimer::RESPINS && stareTimer.anuleaza()) logSystem("Timer anulat: revenire AUTO");
  intretineTimer();
}

void aplicaSetariTimer(FirebaseJson& json) {
  FirebaseJsonData data;
  const bool areViteza = json.get(data, "speed");
  const int viteza = areViteza ? constrain(data.intValue, 0, 100) : vitezaBaza;
  const bool areTimer = json.get(data, "timer");
  if (areTimer && data.typeNum == FirebaseJson::JSON_OBJECT) {
    aplicaObiectTimer(json, "timer/");
    return;
  }
  if (areTimer) {
    // false este anularea explicita. null/tip gresit aleg AUTO in siguranta.
    const bool anulare = data.typeNum == FirebaseJson::JSON_BOOL && !data.boolValue;
    if (stareTimer.anuleaza()) logSystem("Timer anulat: revenire AUTO");
    vitezaBaza = anulare && areViteza ? viteza : 0;
  } else if (areViteza) {
    // Clientii vechi raman compatibili; o schimbare speed singura este manual/AUTO.
    if (stareTimer.anuleaza()) logSystem("Timer anulat: revenire AUTO");
    vitezaBaza = viteza;
  } else {
    // PATCH poate contine chei multipath literale, nu un obiect timer complet.
    const size_t total = json.iteratorBegin();
    for (size_t i = 0; i < total; ++i) {
      int tip; String cheie, valoare;
      json.iteratorGet(i, tip, cheie, valoare);
      if (cheie.startsWith("timer/")) necesitaSetariVentilator = true;
    }
    json.iteratorEnd();
  }
  intretineTimer();
}

bool aplicaTimerIndividual(const String& cale, const String& val) {
  (void)val;
  if (cale == "/timer" || cale.startsWith("/timer/") || cale == "/speed") {
    // Evenimentele pot ramane in coada dupa un poll mai nou. Aplicam doar copia
    // completa recitita, niciodata o anulare/treapta veche din stream.
    necesitaSetariVentilator = true;
    return true;
  }
  return false;
}

void aplicaSetariJson(FirebaseJson &json, bool permiteVentilator) {
  FirebaseJsonData jsonData;

  if (json.get(jsonData, "night_mode")) nightModePrimit = jsonData.boolValue;

  if (json.get(jsonData, "live_mode")) modLiveRapid = jsonData.boolValue;

  if (json.get(jsonData, "luminozitate")) luminozitateEcran = constrain(jsonData.intValue, 0, 100);

  if (json.get(jsonData, "luminozitate_led")) luminozitateLed = constrain(jsonData.intValue, 0, 255);

  if (permiteVentilator) aplicaSetariTimer(json);
  else necesitaSetariVentilator = true;

  if (json.get(jsonData, "temp_offset") &&
      (jsonData.typeNum == FirebaseJson::JSON_INT || jsonData.typeNum == FirebaseJson::JSON_FLOAT ||
       jsonData.typeNum == FirebaseJson::JSON_DOUBLE)) aplicaOffsetTemp(jsonData.floatValue);

  // praguri + comportament alerte push (FCM)
  if (json.get(jsonData, "alert_pm"))       alertPm  = jsonData.floatValue;
  if (json.get(jsonData, "alert_co2"))      alertCo2 = jsonData.intValue;
  if (json.get(jsonData, "alert_voc"))      alertVoc = jsonData.floatValue;
  if (json.get(jsonData, "alert_nox"))      alertNox = jsonData.floatValue;
  if (json.get(jsonData, "notif_freq_min") && jsonData.intValue >= 1) notifFreqMin = jsonData.intValue;
  if (json.get(jsonData, "notif_praf"))     notifPraf = jsonData.boolValue;
  if (json.get(jsonData, "notif_gaze"))     notifGaze = jsonData.boolValue;

  // fereastra Night Mode programata (aplicata de placa, nu de telefon)
  if (json.get(jsonData, "nm_auto"))  nmAuto = jsonData.boolValue;
  if (json.get(jsonData, "nm_start")) nmStartMin = jsonData.intValue;
  if (json.get(jsonData, "nm_end"))   nmEndMin = jsonData.intValue;

  // comenzile raman la final (pot bloca bucla sau pot restarta placa)
  // Copiem toate intentiile inainte de recitire: json poate apartine lui fbdoCitire.
  const bool cereResetVoc = json.get(jsonData, "reset_voc") && jsonData.boolValue;
  const bool cereCalibrareVeche = json.get(jsonData, "calibrare_co2") && jsonData.boolValue;
  const bool cereCalibrare = json.get(jsonData, "calibrare_co2_expira") && jsonData.floatValue > 0;
  const bool cereCuratare = json.get(jsonData, "curatare") && jsonData.boolValue;
  const bool cereRestart = json.get(jsonData, "restart") && jsonData.boolValue;
  if (cereResetVoc) comandaResetVoc();
  if (cereCalibrareVeche) respingeCalibrareCo2Veche();
  if (cereCalibrare) comandaCalibrareCo2();
  if (cereCuratare) comandaCuratare();
  if (cereRestart) comandaRestart();
}

// Un singur camp s-a schimbat (ex. aplicatia a miscat sliderul de viteza) — stream-ul
// trimite doar calea ("/speed") si valoarea bruta, nu tot JSON-ul.
void aplicaSetareSingura(const String &cale, const String &val) {
  if (aplicaTimerIndividual(cale, val)) return;
  if (val == "null") return;
  if      (cale == "/night_mode")       nightModePrimit = (val == "true");
  else if (cale == "/live_mode")        modLiveRapid = (val == "true");
  else if (cale == "/luminozitate")     luminozitateEcran  = constrain(val.toInt(), 0, 100);
  else if (cale == "/luminozitate_led") luminozitateLed    = constrain(val.toInt(), 0, 255);
  else if (cale == "/temp_offset") {
    // toFloat() transforma si textul invalid in zero; acceptam numai numere complete.
    char* sfarsit = nullptr;
    const float offset = strtof(val.c_str(), &sfarsit);
    if (sfarsit != val.c_str() && *sfarsit == '\0') aplicaOffsetTemp(offset);
  }
  else if (cale == "/alert_pm")         alertPm  = val.toFloat();
  else if (cale == "/alert_co2")        alertCo2 = val.toInt();
  else if (cale == "/alert_voc")        alertVoc = val.toFloat();
  else if (cale == "/alert_nox")        alertNox = val.toFloat();
  else if (cale == "/notif_freq_min")   { int v = val.toInt(); if (v >= 1) notifFreqMin = v; }
  else if (cale == "/notif_praf")       notifPraf = (val == "true");
  else if (cale == "/notif_gaze")       notifGaze = (val == "true");
  else if (cale == "/nm_auto")          nmAuto = (val == "true");
  else if (cale == "/nm_start")         nmStartMin = val.toInt();
  else if (cale == "/nm_end")           nmEndMin = val.toInt();
  else if (cale == "/reset_voc")        { if (val == "true") comandaResetVoc(); }
  else if (cale == "/calibrare_co2")    { if (val == "true") respingeCalibrareCo2Veche(); }
  else if (cale == "/calibrare_co2_expira") { if (val.toFloat() > 0) comandaCalibrareCo2(); }
  else if (cale == "/curatare")         { if (val == "true") comandaCuratare(); }
  else if (cale == "/restart")          { if (val == "true") comandaRestart(); }
}

// =========================================================================
// HELPERI CULORI
// =========================================================================
uint16_t getPmColor(float val) {
  if (val < 0.0)  return ST77XX_WHITE;
  if (val < 5.0)  return ST77XX_GREEN;
  if (val < 12.0) return ST77XX_YELLOW;
  if (val < 35.0) return ST77XX_ORANGE;
  return ST77XX_RED;
}
uint16_t getCo2Color(uint16_t val) {
  if (val == 0 || val == 65535) return ST77XX_WHITE;
  if (val < 800)  return ST77XX_GREEN;
  if (val < 1200) return ST77XX_YELLOW;
  if (val < 1600) return ST77XX_ORANGE;
  return ST77XX_RED;
}
uint16_t getVocColor(float val) {
  if (val > 500)  return ST77XX_WHITE;
  if (val < 150)  return ST77XX_GREEN;
  if (val < 250)  return ST77XX_YELLOW;
  if (val < 400)  return ST77XX_ORANGE;
  return ST77XX_RED;
}
uint16_t getNoxColor(float val) {
  if (val > 500)  return ST77XX_WHITE;
  if (val < 150)  return ST77XX_GREEN;
  if (val < 250)  return ST77XX_YELLOW;
  if (val < 400)  return ST77XX_ORANGE;
  return ST77XX_RED;
}
uint16_t getTempColor(float val) {
  if (val == 0)   return ST77XX_WHITE;
  if (val < 18.0) return ST77XX_BLUE;
  if (val < 22.0) return ST77XX_GREEN;
  if (val < 26.0) return ST77XX_YELLOW;
  return ST77XX_RED;
}
uint16_t getHumidColor(float val) {
  if (val == 0)   return ST77XX_WHITE;
  if (val < 25.0) return tft.color565(255, 128, 0);
  if (val < 50.0) return tft.color565(0, 255, 0);
  if (val < 75.0) return tft.color565(0, 178, 255);
  return tft.color565(0, 0, 255);
}

// =========================================================================
// FUNCTII FLICKER-FREE — VERSIUNE ROBUSTA
//
// Principiu:
//   1. Stergem bounding box-ul VECHI cu fillRect (rapid, zona mica)
//   2. Desenam textul NOU cu setTextColor(fg, bg) — bg-ul se deseneaza
//      SIMULTAN cu caracterele, elimina orice flash pe zona noua.
//
// Rezultat: zona veche dispare instant, zona noua apare atomic.
// Flickerul vizibil este minim (doar durata unui fillRect pe zona veche).
// =========================================================================

void drawUpdatingText(String text, int x, int y, const GFXfont *font,
                      uint16_t color,
                      int16_t &bx, int16_t &by, uint16_t &bw, uint16_t &bh)
{
  tft.setFont(font);
  int16_t x1, y1; uint16_t w, h;
  tft.getTextBounds(text, x, y, &x1, &y1, &w, &h);

  // Sterge strict bounding box-ul vechiului text
  if (bw > 0 && bh > 0)
    tft.fillRect(bx, by, bw, bh, C_PANEL);

  // Actualizeaza bounding box cu valorile noi
  bx = x1; by = y1; bw = w; bh = h;

  // Desenare atomica: fg + bg simultan → zero flicker pe zona noua
  tft.setTextColor(color, C_PANEL);
  tft.setCursor(x, y);
  tft.print(text);
}

void drawUpdatingTextCentered(String text, int centerX, int y, const GFXfont *font,
                               uint16_t color,
                               int16_t &bx, int16_t &by, uint16_t &bw, uint16_t &bh)
{
  tft.setFont(font);
  int16_t x1, y1; uint16_t w, h;

  // Calculam pozitia centrata
  tft.getTextBounds(text, 0, 0, &x1, &y1, &w, &h);
  int startX = centerX - (w / 2);
  tft.getTextBounds(text, startX, y, &x1, &y1, &w, &h);

  // Sterge strict bounding box-ul vechiului text
  if (bw > 0 && bh > 0)
    tft.fillRect(bx, by, bw, bh, C_PANEL);

  // Actualizeaza bounding box cu valorile noi
  bx = x1; by = y1; bw = w; bh = h;

  // Desenare atomica: fg + bg simultan → zero flicker pe zona noua
  tft.setTextColor(color, C_PANEL);
  tft.setCursor(startX, y);
  tft.print(text);
}

// =========================================================================
// BARE COLORATE (desenate o singura data la forceFullRedraw)
// =========================================================================
void drawSegmentedBar(int x, int y, int w, int h) {
  int q = w / 4;
  tft.fillRect(x,       y, q,       h, ST77XX_GREEN);
  tft.fillRect(x+q,     y, q,       h, ST77XX_YELLOW);
  tft.fillRect(x+2*q,   y, q,       h, ST77XX_ORANGE);
  tft.fillRect(x+3*q,   y, w-3*q,   h, ST77XX_RED);
}
void drawTempBar(int x, int y, int w, int h) {
  int q = w / 4;
  tft.fillRect(x,       y, q,       h, ST77XX_BLUE);
  tft.fillRect(x+q,     y, q,       h, ST77XX_GREEN);
  tft.fillRect(x+2*q,   y, q,       h, ST77XX_YELLOW);
  tft.fillRect(x+3*q,   y, w-3*q,   h, ST77XX_RED);
}
void drawHumidBar(int x, int y, int w, int h) {
  int q = w / 4;
  tft.fillRect(x,       y, q,       h, tft.color565(255,128,0));
  tft.fillRect(x+q,     y, q,       h, tft.color565(0,255,0));
  tft.fillRect(x+2*q,   y, q,       h, tft.color565(0,178,255));
  tft.fillRect(x+3*q,   y, w-3*q,   h, tft.color565(0,0,255));
}

void updateCursor(int x, int y, int w, float val, float minV, float maxV, int &oldC) {
  if (oldC >= 0) tft.fillRect(oldC, y-4, 3, 3, C_PANEL);
  float pct = (val - minV) / (maxV - minV);
  if (pct < 0) pct = 0; if (pct > 1) pct = 1;
  int cx = x + (int)(pct * (w - 3));
  tft.fillRect(cx, y-4, 3, 3, ST77XX_WHITE);
  oldC = cx;
}

const char* getDayNameRo(int wday) {
  switch (wday) {
    case 0: return "Duminica"; case 1: return "Luni";    case 2: return "Marti";
    case 3: return "Miercuri"; case 4: return "Joi";     case 5: return "Vineri";
    case 6: return "Sambata";  default: return "";
  }
}
const char* getMonthNameRo(int mon) {
  switch (mon) {
    case 0: return "Ian"; case 1: return "Feb"; case 2: return "Mar";
    case 3: return "Apr"; case 4: return "Mai"; case 5: return "Iun";
    case 6: return "Iul"; case 7: return "Aug"; case 8: return "Sep";
    case 9: return "Oct"; case 10: return "Nov"; case 11: return "Dec";
    default: return "";
  }
}

// =========================================================================
// ECRAN EROARE COMUNICARE SENZOR (SEN66)
// =========================================================================
void drawCenteredStatic(const char* text, int centerX, int y, const GFXfont* font, uint16_t color) {
  tft.setFont(font);
  int16_t x1, y1; uint16_t w, h;
  tft.getTextBounds(text, 0, 0, &x1, &y1, &w, &h);
  tft.setTextColor(color);
  tft.setCursor(centerX - (w / 2), y);
  tft.print(text);
}

void deseneazaEcranEroareSenzor() {
  uint16_t mov = tft.color565(160, 0, 240);
  tft.fillScreen(C_BG);

  // Bula mov cu semn de exclamare centrat PERFECT in cerc (pe ambele axe, dupa bounding box-ul real al glifului)
  const int cx = 160, cy = 64, r = 32;
  tft.fillCircle(cx, cy, r, mov);
  tft.fillCircle(cx, cy, r - 5, C_BG);
  tft.setFont(&FreeSansBold24pt7b);
  int16_t ebx, eby; uint16_t ebw, ebh;
  tft.getTextBounds("!", 0, 0, &ebx, &eby, &ebw, &ebh);
  tft.setTextColor(mov);
  tft.setCursor(cx - ebx - (int)ebw / 2, cy - eby - (int)ebh / 2);
  tft.print("!");

  // Mesaje
  drawCenteredStatic("EROARE SENZOR", 160, 138, &FreeSansBold18pt7b, ST77XX_WHITE);
  drawCenteredStatic("Comunicare esuata cu SEN66", 160, 170, &FreeSans9pt7b, C_TEXT_DIM);
  drawCenteredStatic("Reincercare automata...", 160, 196, &FreeSans9pt7b, C_SKYBLUE);
}

// =========================================================================
// DASHBOARD PRINCIPAL
// =========================================================================
void deseneazaDashboard() {
  if (isNightMode) return;

  if (forceFullRedraw) {
    tft.fillScreen(C_BG);

    // Panel CO2
    tft.fillRoundRect(4, 4, 153, 76, 6, C_PANEL);
    tft.drawRoundRect(4, 4, 153, 76, 6, C_PANEL_B);
    tft.setFont(NULL); tft.setCursor(12, 10); tft.setTextColor(C_TEXT_DIM, C_PANEL); tft.print("CO2");
    drawSegmentedBar(12, 68, 137, 5);

    // Panel PM2.5
    tft.fillRoundRect(163, 4, 153, 76, 6, C_PANEL);
    tft.drawRoundRect(163, 4, 153, 76, 6, C_PANEL_B);
    tft.setFont(NULL); tft.setCursor(171, 10); tft.setTextColor(C_TEXT_DIM, C_PANEL); tft.print("PM2.5");
    drawSegmentedBar(171, 68, 137, 5);

    // Panel VOC + NOx
    tft.fillRoundRect(4, 86, 153, 76, 6, C_PANEL);
    tft.drawRoundRect(4, 86, 153, 76, 6, C_PANEL_B);
    tft.setFont(NULL); tft.setCursor(12, 92); tft.setTextColor(C_TEXT_DIM, C_PANEL); tft.print("VOC");
    tft.setFont(NULL); tft.setCursor(84, 92); tft.setTextColor(C_TEXT_DIM, C_PANEL); tft.print("NOx");
    drawSegmentedBar(12, 150, 65, 5);
    drawSegmentedBar(84, 150, 65, 5);

    // Panel TEMP + UMID
    tft.fillRoundRect(163, 86, 153, 76, 6, C_PANEL);
    tft.drawRoundRect(163, 86, 153, 76, 6, C_PANEL_B);
    tft.setFont(NULL); tft.setCursor(171, 92); tft.setTextColor(C_TEXT_DIM, C_PANEL); tft.print("TEMP");
    tft.setFont(NULL); tft.setCursor(243, 92); tft.setTextColor(C_TEXT_DIM, C_PANEL); tft.print("UMID");
    drawTempBar(171, 150, 65, 5);
    drawHumidBar(243, 150, 65, 5);

    // Panel SISTEM
    // Linia de titlu: "SISTEM" static la stanga, SSID dinamic dupa el, dot WiFi la dreapta
    tft.fillRoundRect(4, 168, 153, 68, 6, C_PANEL);
    tft.drawRoundRect(4, 168, 153, 68, 6, C_PANEL_B);
    tft.setFont(NULL);
    tft.setTextColor(C_TEXT_DIM, C_PANEL);
    tft.setCursor(12, 174);
    tft.print("SISTEM");  // "SISTEM" = 6 chars x 6px = 36px, se termina la x=48

    // Panel CEAS
    tft.fillRoundRect(163, 168, 153, 68, 6, C_PANEL);
    tft.drawRoundRect(163, 168, 153, 68, 6, C_PANEL_B);

    forceFullRedraw = false;

    // Reset toate variabilele de tracking pentru redesenare completa
    old_co2=-1; old_pm25=-99.0; old_voc=-1; old_nox=-1;
    old_temp=-99.0; old_umid=-1.0; old_fan=-1; old_rpm=-1;
    old_time=""; old_date=""; old_ssid="";
    bw_co2=0; bw_pm=0; bw_voc=0; bw_nox=0; bw_temp=0; bw_umid=0;
    bw_fan=0; bw_rpm=0; bw_time=0; bw_date=0; bw_ssid=0;
    cur_co2=-1; cur_pm=-1; cur_voc=-1; cur_nox=-1; cur_temp=-1; cur_umid=-1;
  }

  // ---- CO2 ----
  if ((int)co2 != old_co2) {
    String vStr = !validitateSenzor.co2 ? "Init" : String(co2);
    drawUpdatingTextCentered(vStr, 80, 52, &FreeSansBold24pt7b, !validitateSenzor.co2 ? ST77XX_WHITE : getCo2Color(co2),
                             bx_co2, by_co2, bw_co2, bh_co2);
    updateCursor(12, 68, 137, co2, 400, 2000, cur_co2);
    old_co2 = co2;
  }

  // ---- PM2.5 ----
  float disp_pm25 = round(pm25 * 10.0) / 10.0;
  if (disp_pm25 != old_pm25) {
    String vStr = !validitateSenzor.pm ? "Init" : String(disp_pm25, 1);
    drawUpdatingTextCentered(vStr, 239, 52, &FreeSansBold24pt7b, !validitateSenzor.pm ? ST77XX_WHITE : getPmColor(disp_pm25),
                             bx_pm, by_pm, bw_pm, bh_pm);
    updateCursor(171, 68, 137, disp_pm25, 0, 50, cur_pm);
    old_pm25 = disp_pm25;
  }

  // ---- VOC ----
  int disp_voc = (int)voc;
  if (disp_voc != old_voc) {
    String vStr = !validitateSenzor.voc ? "Init" : String(disp_voc);
    drawUpdatingTextCentered(vStr, 44, 138, &FreeSansBold18pt7b, !validitateSenzor.voc ? ST77XX_WHITE : getVocColor(disp_voc),
                             bx_voc, by_voc, bw_voc, bh_voc);
    updateCursor(12, 150, 65, disp_voc, 1, 500, cur_voc);
    old_voc = disp_voc;
  }

  // ---- NOx ----
  int disp_nox = (int)nox;
  if (disp_nox != old_nox) {
    String vStr = !validitateSenzor.nox ? "Init" : String(disp_nox);
    drawUpdatingTextCentered(vStr, 116, 138, &FreeSansBold18pt7b, !validitateSenzor.nox ? ST77XX_WHITE : getNoxColor(disp_nox),
                             bx_nox, by_nox, bw_nox, bh_nox);
    updateCursor(84, 150, 65, disp_nox, 1, 500, cur_nox);
    old_nox = disp_nox;
  }

  // ---- TEMP ----
  float disp_temp = round(temp * 10.0) / 10.0;
  if (disp_temp != old_temp) {
    String vStr = !validitateSenzor.temperatura ? "Init" : String(disp_temp, 1);
    drawUpdatingTextCentered(vStr, 203, 138, &FreeSansBold18pt7b, !validitateSenzor.temperatura ? ST77XX_WHITE : getTempColor(disp_temp),
                             bx_temp, by_temp, bw_temp, bh_temp);
    updateCursor(171, 150, 65, disp_temp, 16, 30, cur_temp);
    old_temp = disp_temp;
  }

  // ---- UMIDITATE ----
  float disp_umid = round(umiditate * 10.0) / 10.0;
  if (disp_umid != old_umid) {
    String vStr = !validitateSenzor.umiditate ? "Init" : String(disp_umid, 1);
    drawUpdatingTextCentered(vStr, 275, 138, &FreeSansBold18pt7b, !validitateSenzor.umiditate ? ST77XX_WHITE : getHumidColor(disp_umid),
                             bx_umid, by_umid, bw_umid, bh_umid);
    updateCursor(243, 150, 65, disp_umid, 10, 90, cur_umid);
    old_umid = disp_umid;
  }

  // ---- VENTILATOR + RPM ----
  if (currentFanSpeed != old_fan || rpmReal != old_rpm) {
    String fanStr = String(currentFanSpeed) + "% " + (isAutoMode ? "(AUTO)" : "(MAN)");
    drawUpdatingText(fanStr, 12, 205, &FreeSans9pt7b, ST77XX_WHITE,
                     bx_fan, by_fan, bw_fan, bh_fan);
    String rpmStr = String(rpmReal) + " RPM";
    drawUpdatingText(rpmStr, 12, 226, &FreeSans9pt7b, C_SKYBLUE,
                     bx_rpm, by_rpm, bw_rpm, bh_rpm);
    old_fan = currentFanSpeed; old_rpm = rpmReal;
  }

 // ---- LINIA SISTEM: "SISTEM <SSID> ●" pe acelasi rand ----
  bool wifiConn = (WiFi.status() == WL_CONNECTED);
  String ssidCurent = wifiConn ? WiFi.SSID() : "Offline";
  
  // Tăiem puțin textul doar dacă e extrem de lung (peste 14 caractere) ca să nu urce pe bulină
  if ((int)ssidCurent.length() > 14) ssidCurent = ssidCurent.substring(0, 13) + "~";

  if (ssidCurent != old_ssid) {
    tft.setFont(NULL); // Folosim fontul standard (fiecare literă are cam 6px lățime)
    
    int16_t x1, y1; uint16_t w, h;
    // 1. Măsurăm exact câți pixeli lățime (w) are numele rețelei curente
    tft.getTextBounds(ssidCurent.c_str(), 0, 0, &x1, &y1, &w, &h);
    
    // 2. Spațiul liber este de la x=48 (unde se termină "SISTEM") până la x=140 (unde începe bulina).
    // Asta ne oferă o "fereastră" lată de ~92 de pixeli. Centrăm textul matematic:
    int startX = 48 + (92 - w) / 2;

    // 3. Ștergem fosta amprentă a textului folosind lățimea veche salvată
    if (bw_ssid > 0) {
        tft.fillRect(bx_ssid, 174, bw_ssid, 8, C_PANEL);
    }

    // 4. Desenăm noul SSID perfect centrat
    tft.setTextColor(wifiConn ? C_TEXT_DIM : ST77XX_RED, C_PANEL);
    tft.setCursor(startX, 174);
    tft.print(ssidCurent);

    // 5. Salvăm coordonatele noului text pentru a-l putea șterge corect data viitoare
    bx_ssid = startX;
    bw_ssid = w;
    old_ssid = ssidCurent;
  }

  // Verde = publicare confirmata; portocaliu = WiFi prezent, cloud neconfirmat.
  // Impulsul albastru de 1s marcheaza confirmarea periodica, fara sa ascunda ceasul.
  const uint64_t acumIndicator = esp_timer_get_time() / 1000ULL;
  const bool cloudConfirmat = stareCloud.confirmatRecent(acumIndicator);
  tft.fillCircle(146, 177, 3, !wifiConn ? ST77XX_RED : stareCloud.impulsConfirmare(acumIndicator)
      ? ST77XX_BLUE : cloudConfirmat ? ST77XX_GREEN : ST77XX_ORANGE);

  // ---- ORA + DATA ----
  struct tm timeinfo;
  // getLocalTime(..., 0) poate rata bucla interna la schimbarea milisecundei.
  // Ora validata prin SNTP se citeste direct, fara asteptare si fara falsul Sync.
  const time_t oraAfisaj = time(nullptr);
  if (ceasValid() && localtime_r(&oraAfisaj, &timeinfo) != nullptr) {
    char timeStringBuff[10];
    strftime(timeStringBuff, sizeof(timeStringBuff), "%H:%M", &timeinfo);
    String tStr = String(timeStringBuff);

    String dStr       = String(timeinfo.tm_mday) + " " + String(getMonthNameRo(timeinfo.tm_mon));
    String dayStr     = String(getDayNameRo(timeinfo.tm_wday));
    String fulldateStr = dayStr + ", " + dStr;

    if (tStr != old_time) {
      drawUpdatingTextCentered(tStr, 238, 208, &FreeSansBold24pt7b, ST77XX_WHITE,
                               bx_time, by_time, bw_time, bh_time);
      old_time = tStr;
    }
    if (fulldateStr != old_date) {
      drawUpdatingTextCentered(fulldateStr, 239, 230, &FreeSans9pt7b, C_TEXT_DIM,
                               bx_date, by_date, bw_date, bh_date);
      old_date = fulldateStr;
    }
  } else {
    if (old_time != "Sync..") {
      drawUpdatingTextCentered("Sync..", 239, 208, &FreeSansBold24pt7b, ST77XX_WHITE,
                               bx_time, by_time, bw_time, bh_time);
      old_time = "Sync..";
    }
  }
}

// =========================================================================
// SETUP
// =========================================================================
void setup() {
  char timerBootId[17];
  snprintf(timerBootId, sizeof(timerBootId), "%08lx%08lx", (unsigned long)esp_random(), (unsigned long)esp_random());
  stareTimer.incepeBoot(timerBootId);
  Serial.begin(115200);
  setenv("TZ", TZ_INFO, 1);
  tzset();
  sntp_set_time_sync_notification_cb(confirmaNtp);

  captureazaEtapaAnterioaraReset();
  if (esp_reset_reason() == ESP_RST_POWERON || esp_reset_reason() == ESP_RST_BROWNOUT) {
    strcpy(rtc_last_state, "Sistem_Pornit_Priza");
  }
  rtc_last_state[sizeof(rtc_last_state) - 1] = '\0';
  motivRestart = getResetReasonString();

  // ---- Pasul 1: Rezervare memorie ----
  #ifdef DEBUG_SERIAL
  Serial.println("[1/8] Rezervare memorie stringuri...");
  #endif
  istPM25.reserve(1500); istCO2.reserve(1500); istVOC.reserve(1500);
  istNOx.reserve(1500);  istTemp.reserve(1500); istUmid.reserve(1500);
  istPM1.reserve(1500);  istPM4.reserve(1500);  istPM10.reserve(1500);
  jurnalErori.reserve(2000);

  // ---- Pasul 2: PWM + Backlight ----
  #ifdef DEBUG_SERIAL
  Serial.println("[2/8] Setup PWM si Ventilatoare...");
  #endif
  ledcAttach(PIN_FAN_PWM, PWM_FRECVENTA, PWM_REZOLUTIE);
  ledcWrite(PIN_FAN_PWM, 0);
  ledcAttach(PIN_TFT_BL, 5000, 8);
  ledcWrite(PIN_TFT_BL, 255);

  // ---- Pasul 3: Ecran TFT ----
  #ifdef DEBUG_SERIAL
  Serial.println("[3/8] Pornire Ecran TFT Landscape (Hardware SPI)...");
  #endif
  SPI.begin(TFT_SCLK, -1, TFT_MOSI, -1);
  tft.init(240, 320);
  tft.setRotation(3);

  // ---- Boot Screen cu progress bar ----
  tft.fillScreen(C_BG);

  tft.setFont(&FreeSansBold18pt7b);
  tft.setTextColor(ST77XX_WHITE);
  tft.setCursor(55, 55);
  tft.print("Smart Air");

  tft.setFont(&FreeSans9pt7b);
  tft.setTextColor(C_SKYBLUE);
  tft.setCursor(68, 78);
  tft.print("Purificator DIY");

  tft.drawFastHLine(20, 90, 280, C_PANEL_B);

  auto bootMsg = [&](const char* msg, uint8_t step) {
    tft.fillRect(0, 100, 320, 22, C_BG);
    tft.setFont(NULL);
    tft.setTextColor(C_TEXT_DIM);
    tft.setCursor(20, 108);
    tft.print(msg);
    int barW = map(step, 1, 8, 0, 280);
    tft.fillRect(20,        220, barW,       10, C_SKYBLUE);
    tft.fillRect(20 + barW, 220, 280 - barW, 10, C_PANEL);
    tft.drawRoundRect(18, 218, 284, 14, 4, C_PANEL_B);
  };

  bootMsg("[3/8] Ecran TFT pornit", 3);

  // ---- Pasul 4: I2C ----
  bootMsg("[4/8] Magistrala I2C...", 4);
  #ifdef DEBUG_SERIAL
  Serial.println("[4/8] Curatare si Pornire Magistrala I2C...");
  #endif
  clearI2CBus(I2C_SDA, I2C_SCL);
  Wire.begin(I2C_SDA, I2C_SCL);
  Wire.setTimeout(500);
  //Wire.setClock(50000);   // I2C la 50kHz (sub maximul SEN66 de 100kHz): mai tolerant pe magistrale marginale

  // ---- Pasul 5: LED RGB + Flash Memory ----
  bootMsg("[5/8] LED RGB si Memorie Flash...", 5);
  #ifdef DEBUG_SERIAL
  Serial.println("[5/8] LED RGB si Flash Memory...");
  #endif
  rgbLed.begin();
  rgbLed.setBrightness(150);
  rgbLed.setPixelColor(0, rgbLed.Color(0, 0, 255));
  rgbLed.show();
  storace.begin("smart_air", false);
  offsetTemperatura = storace.getFloat("tempOffset", 0.0);
  if (!isfinite(offsetTemperatura) || offsetTemperatura < -3.0f || offsetTemperatura > 3.0f)
    offsetTemperatura = 0;
  offsetSolicitatTemp = offsetTemperatura;

  // ---- Pasul 6: Senzor SEN66 ----
  bootMsg("[6/8] Senzor SEN66...", 6);
  #ifdef DEBUG_SERIAL
  Serial.println("[6/8] Initializez Senzorul SEN66...");
  #endif
  sen66.begin(Wire, 0x6B);
  sen66.deviceReset();
  delay(1200);

  uint8_t vocState[8];
  if (storace.getBytesLength("vocState") == 8) {
    storace.getBytes("vocState", vocState, 8);
    uint16_t errVoc = sen66.setVocAlgorithmState(vocState, 8);
    delay(20);
    #ifdef DEBUG_SERIAL
    if (errVoc == 0 && Serial) Serial.println(">> VOC State Incarcat din Flash cu succes!");
    #endif
  }

  sen66.startContinuousMeasurement();
  delay(50);
  tSenzorStart          = millis();
  warmupSenzorTerminat = false;
  tUltimaCitireReusita  = millis();

  aplicaOffsetTemp(offsetSolicitatTemp);

  // ---- Pasul 7: WiFi ----
  bootMsg("[7/8] Conectare WiFi...", 7);
  #ifdef DEBUG_SERIAL
  Serial.println("[7/8] Adaugare retele WiFi cunoscute si conectare...");
  #endif
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.setAutoReconnect(true);

  // =============================================
  // LISTA RETELE WIFI CUNOSCUTE (din secrete.h)
  wifiMulti.addAP(WIFI1_SSID, WIFI1_PASS);
  if (strlen(WIFI2_SSID) > 0) wifiMulti.addAP(WIFI2_SSID, WIFI2_PASS);
  if (strlen(WIFI3_SSID) > 0) wifiMulti.addAP(WIFI3_SSID, WIFI3_PASS);
  // =============================================

  // O singura incercare la boot; restul sunt programate in loop, fara asteptarea routerului.
  wifiMulti.run(1500);
  #ifdef DEBUG_SERIAL
  if (WiFi.status() == WL_CONNECTED) {
    Serial.print(">> Conectat la: "); Serial.println(WiFi.SSID());
    Serial.print(">> IP: ");          Serial.println(WiFi.localIP());
  }
  #endif

  // ---- Pasul 8: WDT + OTA + Tahometru ----
  bootMsg("[8/8] Sistem gata!", 8);
  tft.fillRect(20, 220, 280, 10, ST77XX_GREEN);
  tft.drawRoundRect(18, 218, 284, 14, 4, C_PANEL_B);

  #ifdef DEBUG_SERIAL
  Serial.println("[8/8] Pornire Tahometru Software si Intrare in Loop...");
  #endif

  esp_task_wdt_config_t wdt_config = {
    .timeout_ms     = 90000,
    .idle_core_mask = (1 << portNUM_PROCESSORS) - 1,
    .trigger_panic  = true
  };
  if (esp_task_wdt_init(&wdt_config) == ESP_ERR_INVALID_STATE)
    esp_task_wdt_reconfigure(&wdt_config);
  esp_task_wdt_add(NULL);

  ArduinoOTA.setHostname("SmartPurifier");
  ArduinoOTA.setPassword(OTA_PASSWORD);
  ArduinoOTA.onStart([]() { esp_task_wdt_delete(NULL); });
  ArduinoOTA.onError([](ota_error_t) { esp_task_wdt_add(NULL); });
  ArduinoOTA.begin();

  pinMode(PIN_FAN_RPM, INPUT_PULLUP);
  // initializam toate campurile (nu doar callback/name) ca sa fie curat si sub -Wextra
  const esp_timer_create_args_t tacho_timer_args = {
    .callback = &tachoPoll, .arg = nullptr, .dispatch_method = ESP_TIMER_TASK,
    .name = "tacho_poll", .skip_unhandled_events = false
  };
  esp_timer_create(&tacho_timer_args, &tacho_timer);
  esp_timer_start_periodic(tacho_timer, 250);

  forceFullRedraw = true;
  delay(200);
}

// =========================================================================
// LOOP
// =========================================================================
void intretineFirebase() {
  const uint64_t acum = esp_timer_get_time() / 1000ULL;
  if (!stareCloud.recuperareNecesara(firebaseGata, WiFi.status() == WL_CONNECTED, acum)) return;
  updateState("Recuperare_Firebase");
  // Recuperarea nu depinde de ready(): exact autentificarea poate ramane indisponibila.
  // Inchidem numai socket-urile detinute de loop. Task-ul de stream isi pastreaza obiectul.
  if (!firebaseAvertizat) {
    firebaseAvertizat = true;
    logSystem("Firebase fara confirmare 120s; refacere conexiune (HTTP " + String(fbdoScriere.httpCode()) + ")");
  }
  fbdoScriere.stopWiFiClient();
  fbdoCitire.stopWiFiClient();
  // Fluxul public ReAuthenticate reface si JWT-ul service account; refreshToken()
  // ar cere un refresh_token OAuth care nu exista in configuratia cu cheie privata.
  Firebase.reset(&config);
  Firebase.begin(&config, &auth);
  updateState("Running_Loop");
}

void loop() {
  ArduinoOTA.handle();
  esp_task_wdt_reset();

  unsigned long t = millis();
  time_t tNow; time(&tNow);
  struct tm timeinfo; localtime_r(&tNow, &timeinfo);

  intretineCeas(t);

  // ---- Gestionare WiFi + reconectare multi-AP ----
  if (WiFi.status() != WL_CONNECTED) {
    if (t - tAntWifi >= 10000) {
      tAntWifi = t;
      if (!wifiAvertizat) { wifiAvertizat = true; updateState("Pierdut_WiFi"); }
      wifiMulti.run(1500);
    }
  } else {
    if (wifiAvertizat) {
      logSystem("WiFi restabilit pe: " + WiFi.SSID());
      wifiAvertizat = false;
      fbdoScriere.clear();
      fbdoCitire.clear();
      // NU atingem fbdoStream aici (nici .clear(), nici beginStream): e folosit concurent de
      // task-ul intern de stream al bibliotecii -> orice apel din loop pe el ar corupe memoria.
      // Task-ul se reconecteaza singur dupa revenirea WiFi; plasa de 60s acopera golul.
      Firebase.reconnectWiFi(true);
      updateState("Running_Loop");
    }

    static bool firebaseInitializat = false;
    if (!firebaseInitializat && ceasValid()) {
      updateState("Init_Firebase");
      // Firebase porneste numai dupa confirmarea NTP, astfel biblioteca nu initiaza
      // propriul configTime(UTC) si nu suprascrie fusul EET/EEST.

      config.api_key        = API_KEY;
      config.database_url   = DATABASE_URL;
      config.timeout.serverResponse  = 3 * 1000;
      config.timeout.socketConnection = 3 * 1000;
      config.tcp_data_sending_retry  = 1;

      // Cu cheile de service account completate trecem pe OAuth2 (obligatoriu pentru
      // trimiterea FCM); fara chei ramanem pe email/parola, exact ca pana acum.
      fcmActiv = (strlen(FCM_PROJECT_ID) > 0) && (strlen(FCM_CLIENT_EMAIL) > 0) && (strlen(FCM_PRIVATE_KEY) > 0);
      if (fcmActiv) {
        config.service_account.data.project_id   = FCM_PROJECT_ID;
        config.service_account.data.client_email = FCM_CLIENT_EMAIL;
        config.service_account.data.private_key  = FCM_PRIVATE_KEY;
      } else {
        auth.user.email    = USER_EMAIL;
        auth.user.password = USER_PASSWORD;
      }

      config.token_status_callback = tokenStatusCallback;
      fbdoScriere.setBSSLBufferSize(2048, 1024);
      fbdoCitire.setBSSLBufferSize(2048, 1024);
      // O zi contine sase serii de cate 288 valori; limita implicita de 2 KB o trunchiaza.
      fbdoCitire.setResponseSize(32768);
      fbdoStream.setBSSLBufferSize(2048, 1024);
      fbdoFcm.setBSSLBufferSize(4096, 1024);

      Firebase.begin(&config, &auth);
      Firebase.reconnectWiFi(true);

      // Stream pe nodul de setari: serverul ne IMPINGE schimbarile instant — bucla
      // principala nu mai face niciun apel de retea pentru citirea setarilor.
      // Coada de 16: aplicatia trimite ~8 scrieri una dupa alta la fiecare pornire
      // (sincronizarea pragurilor), iar bucla poate fi ocupata cateva secunde.
      coadaSetari = xQueueCreate(16, sizeof(EvenimentSetari));
      // beginStream o SINGURA DATA aici; mai departe task-ul setStreamCallback detine
      // obiectul si se reconecteaza singur. Daca acest begin esueaza, task-ul reincearca
      // oricum, iar reconcilierea la 60s (pe fbdoCitire) aplica setarile intre timp.
      Firebase.RTDB.beginStream(&fbdoStream, "/SenzoriAer/setari");
      Firebase.RTDB.setStreamCallback(&fbdoStream, streamCallback, streamTimeoutCallback);

      firebaseInitializat = true;
      firebaseGata        = true;
      updateState("Running_Loop");
    }
  }

  intretineFirebase();

  // Operatiile de retea pot dura; renovarile folosesc timpul real de dupa ele.
  t = millis();
  time(&tNow); localtime_r(&tNow, &timeinfo);

  // ---- Tahometru (1 secunda) ----
  if (t - tAntRpm >= 1000) {
    static unsigned int pulsuriTrecute[3] = {0, 0, 0};
    static byte indexPuls = 0;

    // Schimb atomic (citire + reset intr-o singura instructiune): callback-ul esp_timer
    // ruleaza ca task pe core 0, deci noInterrupts() pe core-ul loop-ului nu il oprea —
    // un puls numarat intre citire si reset s-ar fi pierdut.
    unsigned int pulsuriAcum = __atomic_exchange_n(&validPulseCount, 0, __ATOMIC_RELAXED);

    if (currentFanSpeed < 2) {
      rpmReal = 0;
      pulsuriTrecute[0] = pulsuriTrecute[1] = pulsuriTrecute[2] = 0;
    } else {
      pulsuriTrecute[indexPuls] = pulsuriAcum;
      if (++indexPuls > 2) indexPuls = 0;
      rpmReal = (pulsuriTrecute[0] + pulsuriTrecute[1] + pulsuriTrecute[2]) * 10;
    }
    tAntRpm = t;
  }

  // ---- Citire Senzor SEN66 (1 secunda) ----
  if (t - tAntSenzor >= 1000) {
    tAntSenzor = t;
    updateState("Citire_SEN66");
    float t_pm1=0, t_pm25=0, t_pm4=0, t_pm10=0, t_umid=0, t_temp=0, t_voc=0, t_nox=0;
    uint16_t t_co2 = 0;

    uint16_t error = sen66.readMeasuredValues(t_pm1, t_pm25, t_pm4, t_pm10,
                                              t_umid, t_temp, t_voc, t_nox, t_co2);
    if (error) {
      invalideazaMasurari();
      if (!senzorAvertizat) { logSystem("Eroare citire SEN66: Cod " + String(error)); senzorAvertizat = true; }
      if (senzorEroriConsecutive < 1000) senzorEroriConsecutive++;
      if (senzorEroriConsecutive >= 15) senzorEsuatComm = true;   // failure persistenta (~15s fara raspuns)
    } else {
      if (senzorAvertizat) { logSystem("Comunicare SEN66 restabilita"); senzorAvertizat = false; }
      senzorEroriConsecutive = 0;
      senzorEsuatComm = false;
      tUltimaCitireReusita = t;
      diagnosticAreCitireSenzor = true;
      diagnosticUltimaCitireMs = esp_timer_get_time() / 1000ULL;

      validitateSenzor = valideazaCitire(t_pm1, t_pm25, t_pm4, t_pm10,
          t_umid, t_temp, t_voc, t_nox, t_co2, varstaSenzor(t));
      // Fiecare canal are validitate proprie; un CO2 indisponibil nu ascunde praful.
      if (validitateSenzor.pm) {
        pm1=t_pm1; pm25=t_pm25; pm4=t_pm4; pm10=t_pm10;
        sumPM1+=pm1; sumPM25+=pm25; sumPM4+=pm4; sumPM10+=pm10; countPM++;
      } else { pm1=pm25=pm4=pm10=-1; }
      co2 = validitateSenzor.co2 ? t_co2 : 65535;
      if (validitateSenzor.co2) { sumCO2+=co2; countCO2++; }
      temp = validitateSenzor.temperatura ? t_temp : -999;
      umiditate = validitateSenzor.umiditate ? t_umid : -999;
      if (validitateSenzor.temperatura) { sumTemp+=temp; countTemp++; }
      if (validitateSenzor.umiditate) { sumUmid+=umiditate; countUmid++; }
      voc = validitateSenzor.voc ? t_voc : 65535;
      nox = validitateSenzor.nox ? t_nox : 65535;
      if (validitateSenzor.voc) { sumVOC+=voc; countVOC++; }
      if (validitateSenzor.nox) { sumNOx+=nox; countNOx++; }
      areDate = validitateSenzor.existaDate();
      verificaAlertePoluare(t);
    }
    updateState("Running_Loop");
  }

  // ---- Watchdog Senzor: reinitializare dupa 15s fara citire ----
  if (t - tUltimaCitireReusita > 15000) {
    tUltimaCitireReusita = t;
    invalideazaMasurari();
    logSystem("WDT: Senzorul SEN66 a blocat citirile de 15s. Reinitializare...");
    updateState("Reset_Senzor_Hardware");

    sen66.stopMeasurement();
    unsigned long tStop = millis();
    while (millis() - tStop < 1500) { esp_task_wdt_reset(); yield(); }

    clearI2CBus(I2C_SDA, I2C_SCL);
    Wire.begin(I2C_SDA, I2C_SCL);
    Wire.setTimeout(500);
    //Wire.setClock(50000);
    sen66.begin(Wire, 0x6B);
    sen66.deviceReset();

    unsigned long tReset = millis();
    while (millis() - tReset < 1200) { esp_task_wdt_reset(); yield(); }

    uint8_t vocState[8];
    if (storace.getBytesLength("vocState") == 8) {
      storace.getBytes("vocState", vocState, 8);
      sen66.setVocAlgorithmState(vocState, 8);
      tReset = millis(); while (millis() - tReset < 20) { yield(); }
    }

    sen66.startContinuousMeasurement();
    tReset = millis(); while (millis() - tReset < 50) { yield(); }
    tSenzorStart = millis();
    warmupSenzorTerminat = false;

    offsetFortatLaBoot = false;
    offsetIncercat = false;
    aplicaOffsetTemp(offsetSolicitatTemp);

    updateState("Running_Loop");
  }

  // Reinitializarea senzorului poate dura; nu scadem un t vechi din noul tSenzorStart.
  t = millis(); time(&tNow); localtime_r(&tNow, &timeinfo);

  // Reincercarea este limitata in aplicaOffsetTemp la cel mult o comanda la 10 secunde.
  aplicaOffsetTemp(offsetSolicitatTemp);

  // ---- Salvare calibrare VOC la fiecare 6 ore ----
  if (areDate && (t - tAntVocSave >= 21600000)) {
    tAntVocSave = t;
    updateState("Salvare_Calibrare_VOC");
    uint8_t currentVocState[8];
    if (sen66.getVocAlgorithmState(currentVocState, 8) == 0) {
      storace.putBytes("vocState", currentVocState, 8);
      logSystem("Calibrare VOC extrasa si salvata.");
    } else {
      logSystem("Eroare la extragerea VOC State.");
    }
    updateState("Running_Loop");
  }

  intretineTimer();
  // ---- Logica Auto / Manual ventilator ----
  bool comandaEsteAuto = (vitezaDinAplicatie == 0);
  if (WiFi.status() != WL_CONNECTED && !comandaEsteAuto && vitezaDinAplicatie > 5) comandaEsteAuto = true;
  isAutoMode = comandaEsteAuto;

  if (isAutoMode) {
    if (validitateSenzor.pm) {
      int vitezaDorita = 100;
      if      (pm25 <= 0.5)  vitezaDorita = 25;
      else if (pm25 <= 2.0)  vitezaDorita = 35;
      else if (pm25 <= 6.0)  vitezaDorita = 40;
      else if (pm25 <= 12.0) vitezaDorita = 50;
      else if (pm25 <= 30.0) vitezaDorita = 70;

      if (!eraAutoInainte) { vitezaAutoCurenta = vitezaDorita; timpUltimaSchimbareAuto = t; }
      else {
        if (vitezaDorita > vitezaAutoCurenta) { vitezaAutoCurenta = vitezaDorita; timpUltimaSchimbareAuto = t; }
        else if (vitezaDorita < vitezaAutoCurenta && t - timpUltimaSchimbareAuto >= 600000) { vitezaAutoCurenta = vitezaDorita; timpUltimaSchimbareAuto = t; }
      }
      eraAutoInainte = true; targetFanSpeed = vitezaAutoCurenta;
    }
  } else { eraAutoInainte = false; targetFanSpeed = vitezaDinAplicatie; }

  // ---- Fading ventilator (200ms) ----
  if (t - tAntFading >= 200) {
    tAntFading = t;
    if (currentFanSpeed != targetFanSpeed) {
      if (currentFanSpeed < targetFanSpeed) currentFanSpeed++; else currentFanSpeed--;
      ledcWrite(PIN_FAN_PWM, map(currentFanSpeed, 0, 100, 0, 255));
    }
  }

  // ---- Night Mode programat (fereastra orara aplicata cu ceasul propriu NTP) ----
  // Un snapshot vechi nu trebuie sa anuleze programul local la fiecare reconciliere.
  // Fara ora valida pastram starea efectiva; in manual respectam valoarea primita.
  const bool programNmValid = nmAuto && ceasValid() && timeinfo.tm_year > 120;
  bool nightModeDorit = nmAuto ? nightModeApp : nightModePrimit;
  if (programNmValid) {
    const int minutZi = timeinfo.tm_hour * 60 + timeinfo.tm_min;
    nightModeDorit = (nmStartMin <= nmEndMin)
                      ? (minutZi >= nmStartMin && minutZi < nmEndMin)
                      : (minutZi >= nmStartMin || minutZi < nmEndMin);
  }
  if (nightModeDorit != nightModeApp) {
    nightModeApp = nightModeDorit;
    if (programNmValid)
      logSystem(nightModeApp ? "Night Mode pornit (programat)" : "Night Mode oprit (programat)");
  }
  // Confirmam numai frunza night_mode. Esecul pastreaza diferenta pentru retry,
  // fara alte tranzitii/loguri; polling-ul nu reseteaza limita de 30 secunde.
  if (programNmValid && nightModePrimit != nightModeApp &&
      uint32_t(t - tAntNmPublicare) >= 30000 && firebaseGata &&
      WiFi.status() == WL_CONNECTED && Firebase.ready()) {
    tAntNmPublicare = t;
    if (Firebase.RTDB.setBool(&fbdoScriere, "/SenzoriAer/setari/night_mode", nightModeApp))
      nightModePrimit = nightModeApp;
  }

  // ---- Bucla UI (500ms) ----
  if (t - tAntEcran >= 500) {
    tAntEcran = t;
    updateState("Control_Ecran");
    esp_task_wdt_reset(); yield();

    if (nightModeApp) {
      if (!isNightMode) {
        isNightMode = true;
        ledcWrite(PIN_TFT_BL, 0);
        rgbLed.clear(); rgbLed.show();
      }
    } else {
      if (isNightMode) { isNightMode = false; forceFullRedraw = true; }

      ledcWrite(PIN_TFT_BL, map(luminozitateEcran, 0, 100, 0, 255));

      // Prioritate stari LED/ecran: (1) eroare comunicare senzor  (2) warmup  (3) calitate aer
      bool inWarmup = (varstaSenzor(t) < 60000UL);

      if (senzorEsuatComm) {
        // (1) Senzorul SEN66 nu raspunde -> LED MOV + ecran dedicat de eroare
        rgbLed.setPixelColor(0, rgbLed.Color(160, 0, 240));
        rgbLed.setBrightness(luminozitateLed);
        rgbLed.show();
        if (!senzorEroareAfisata) { senzorEroareAfisata = true; deseneazaEcranEroareSenzor(); }
      } else {
        // Tocmai am iesit din starea de eroare -> fortam redesenarea completa a dashboard-ului
        if (senzorEroareAfisata) { senzorEroareAfisata = false; forceFullRedraw = true; }

        if (inWarmup) {
          // (2) Senzorul comunica, dar inca se incalzeste (primele 60s) -> LED ALBASTRU
          rgbLed.setPixelColor(0, rgbLed.Color(0, 80, 255));
        }
        // (3) Calitate aer normala
        else if (!areDate)
          rgbLed.setPixelColor(0, rgbLed.Color(160, 0, 240));
        else if (pm25 >= 35 || (validitateSenzor.co2 && co2 >= 1600) || (voc >= 400 && voc <= 500) || (nox >= 300 && nox <= 500))
          rgbLed.setPixelColor(0, rgbLed.Color(255, 0, 0));
        else if (pm25 >= 12 || (validitateSenzor.co2 && co2 >= 1200) || (voc >= 250 && voc <= 500) || (nox >= 150 && nox <= 500))
          rgbLed.setPixelColor(0, rgbLed.Color(255, 144, 0));
        else if (pm25 >= 5  || (validitateSenzor.co2 && co2 >= 800) || (voc >= 150 && voc <= 500) || (nox >= 20 && nox <= 500))
          rgbLed.setPixelColor(0, rgbLed.Color(255, 255, 0));
        else
          rgbLed.setPixelColor(0, rgbLed.Color(0, 255, 0));

        rgbLed.setBrightness(luminozitateLed);
        rgbLed.show();
        deseneazaDashboard();
      }
    }
    updateState("Running_Loop");
  }

  // NB: NU repornim stream-ul manual din loop (beginStream pe fbdoStream ar rula concurent cu
  // task-ul intern al setStreamCallback pe acelasi obiect -> Panic). Task-ul bibliotecii se
  // reconecteaza singur; daca totusi ramane cazut, plasa de reconciliere la 60s (mai jos, pe
  // fbdoCitire) aplica oricum setarile — deci niciun blocaj de durata pentru utilizator.

  // ---- Setari: procesarea evenimentelor primite pe stream ----
  // Serverul trimite la (re)conectare un snapshot complet (path "/"), apoi cate un
  // eveniment per camp schimbat. Procesam aici, pe task-ul principal, ca atunci cand
  // o comanda blocheaza (ex. curatarea ~11s) sa nu omoram task-ul de stream.
  if (coadaSetari != NULL) {
    EvenimentSetari ev;
    for (int procesate=0; procesate<4 && xQueueReceive(coadaSetari, &ev, 0) == pdTRUE; ++procesate) {
      updateState("Aplicare_Setari_FB");
      String cale(ev.path);
      if (cale == "/" || cale.length() == 0) {
        FirebaseJson json;
        json.setJsonData(ev.data);
        aplicaSetariJson(json, false);
      } else {
        aplicaSetareSingura(cale, String(ev.data));
      }
      updateState("Running_Loop");
    }
  }

  // ---- Setari: reconciliere periodica (PLASA DE SIGURANTA) ----
  // Stream-ul de mai sus poate muri silentios dupa multe ore (refresh token OAuth2 la 1h,
  // sesiune SSL picata) FARA sa declanseze callback-ul de timeout -> placa n-ar mai
  // reactiona la nicio setare desi trimite date normal. Recitim tot nodul la 60s, ca
  // setarile (si comenzile) sa fie aplicate oricum in cel mult un minut. In functionare
  // normala stream-ul livreaza instant, deci asta intervine doar cand stream-ul a cazut.
  if (firebaseGata && WiFi.status() == WL_CONNECTED && Firebase.ready() &&
      (necesitaSetariVentilator || (t - tAntPollSetari >= 60000) ||
       (__atomic_load_n(&necesitaReconciliere, __ATOMIC_RELAXED) && t - tAntPollSetari >= 10000))) {
    tAntPollSetari = t;
    necesitaSetariVentilator = false;
    __atomic_store_n(&necesitaReconciliere, false, __ATOMIC_RELAXED);
    updateState("Reconciliere_Setari_FB");
    if (Firebase.RTDB.getJSON(&fbdoCitire, "/SenzoriAer/setari"))
      aplicaSetariJson(fbdoCitire.jsonObject(), true);
    else __atomic_store_n(&necesitaReconciliere, true, __ATOMIC_RELAXED);
    updateState("Running_Loop");
  }

  // ---- Trimitere date live catre Firebase ----
  unsigned long intervalTrimitere = 10000;
  if (currentFanSpeed != targetFanSpeed || modLiveRapid) intervalTrimitere = 1000;

  if (firebaseGata && WiFi.status() == WL_CONNECTED && Firebase.ready() && (t - tAntLive >= intervalTrimitere)) {
    tAntLive = t;
    updateState("Trimitere_Live_FB");

    const bool cereConfirmare = stareCloud.necesitaConfirmare(esp_timer_get_time() / 1000ULL);
    FirebaseJson jsonLive;
    intretineTimer();
    adaugaStareTimer(jsonLive, "Timer", true);
    // Metadate coerente intr-un singur nod, numai la publicarea confirmata (~60 s).
    // Timestamp propriu: un firmware vechi reinstalat nu improspateaza acest raport.
    if (cereConfirmare) {
      jsonLive.set("Firmware/versiune", VERSIUNE_FIRMWARE);
      jsonLive.set("Firmware/build", BUILD_FIRMWARE);
      jsonLive.set("Firmware/raportat_la/.sv", "timestamp");
      adaugaDiagnostic(jsonLive);
    }
    jsonLive.set("last_update/.sv",      "timestamp");
    jsonLive.set("RPM",                  rpmReal);
    jsonLive.set("ProcentVentilator",    targetFanSpeed);
    jsonLive.set("Telemetrie_RAM_Liber", ESP.getFreeHeap());
    jsonLive.set("Telemetrie_WiFi_RSSI", WiFi.RSSI());
    // esp_timer_get_time() e pe 64 biti (microsecunde) si NU se reseteaza la ~49.7 zile
    // asa cum face millis() (care e pe 32 biti). Astfel uptime-ul ramane corect mereu.
    jsonLive.set("Telemetrie_Uptime_Sec",(int)(esp_timer_get_time() / 1000000LL));
    jsonLive.set("Telemetrie_Temp_Core", temperatureRead());
    if (offsetFortatLaBoot) jsonLive.set("Setari_Offset_Temp", offsetTemperatura);
    else jsonLive.set("Setari_Offset_Temp");

    // Un camp invalid devine null in RTDB; heartbeat-ul nu transforma o valoare veche in una noua.
    if (validitateSenzor.co2) jsonLive.set("CO2", co2); else jsonLive.set("CO2");
    if (validitateSenzor.pm) {
      jsonLive.set("PM1", pm1); jsonLive.set("PM25", pm25); jsonLive.set("PM4", pm4); jsonLive.set("PM10", pm10);
    } else { jsonLive.set("PM1"); jsonLive.set("PM25"); jsonLive.set("PM4"); jsonLive.set("PM10"); }
    if (validitateSenzor.voc) jsonLive.set("VOC", (int)voc); else jsonLive.set("VOC");
    if (validitateSenzor.nox) jsonLive.set("NOx", (int)nox); else jsonLive.set("NOx");
    if (validitateSenzor.temperatura) jsonLive.set("Temperatura", temp); else jsonLive.set("Temperatura");
    if (validitateSenzor.umiditate) jsonLive.set("Umiditate", umiditate); else jsonLive.set("Umiditate");

    // O data pe minut asteptam confirmarea HTTP. Restul actualizarilor pastreaza
    // ritmul async; succesul lor inseamna doar expediere, nu acceptare de server.
    const bool trimis = cereConfirmare
        ? Firebase.RTDB.updateNodeSilent(&fbdoScriere, "/SenzoriAer", &jsonLive)
        : Firebase.RTDB.updateNodeAsync(&fbdoScriere, "/SenzoriAer", &jsonLive);
    if (cereConfirmare && trimis) {
      stareCloud.confirma(esp_timer_get_time() / 1000ULL);
      if (firebaseAvertizat) {
        firebaseAvertizat = false;
        logSystem("Firebase restabilit: publicare confirmata");
      }
    }
    if (!trimis) {
      String motivEroare = fbdoScriere.errorReason();
      #ifdef DEBUG_SERIAL
      Serial.println("⚠️ Pachet Live pierdut! Motiv: " + motivEroare);
      #endif
      if (motivEroare.indexOf("buffer") != -1 || motivEroare.indexOf("memory") != -1) {
        logSystem("Eroare buffer Live FB: " + motivEroare);
      }
    }
    updateState("Running_Loop");
  }


  // ---- Istoric local cand (inca) nu exista conexiune cu Firebase ----
  // La boot fara WiFi nu avem nici ora exacta (NTP vine dupa WiFi), asa ca salvam
  // punctele la fix 5 minute pe ceasul intern (millis). La prima sincronizare,
  // merge-ul de mai jos le aseaza in spatele istoricului din cloud, cu goluri -999
  // calculate pe timp de perete (atunci ora e deja valida).
  if (!istoricRecuperat && t - tAntSalvareLocala >= 300000UL) {
    tAntSalvareLocala = t;
    updateState("Salvare_Istoric_Local");
    if (puncteLocale == 0) millisPrimaSalvareLocala = t;
    millisUltimaSalvareLocala = t;
    if (puncteLocale < 288) puncteLocale++;
    salveazaPunctIstoric(0);
    updateState("Running_Loop");
  }

  trimiteJurnalDacaNecesar();

  // ---- Sincronizare initiala: o citire pe iteratie, cu reluare dupa esec ----
  if (!istoricRecuperat) {
    static String cloud[9];
    static long timpCloud = 0;
    static String dataCloud;
    static uint8_t etapa = 0;
    static uint32_t ultimaIncercare = 0;
    static bool asteapta = false;
    const char* chei[] = {"Istoric_PM25", "Istoric_CO2", "Istoric_VOC", "Istoric_NOx", "Istoric_Temp", "Istoric_Umid", "Istoric_PM1", "Istoric_PM4", "Istoric_PM10"};
    if (ceasValid() && firebaseGata && WiFi.status() == WL_CONNECTED && Firebase.ready() &&
        (!asteapta || uint32_t(millis() - ultimaIncercare) >= 10000)) {
      updateState("Recuperare_Istoric_FB");
      asteapta = false;
      if (etapa == 0 && jurnalRecuperat) etapa = 1;
      String cheie = etapa == 0 ? "ErrorLog" : etapa == 1 ? "last_history_time" : etapa == 2 ? "last_archived_date" : chei[etapa-3];
      bool ok = Firebase.RTDB.get(&fbdoCitire, "/SenzoriAer/" + cheie);
      const bool caleLipsa = fbdoCitire.httpCode() == FIREBASE_ERROR_PATH_NOT_EXIST;
      ok = ok || caleLipsa;
      if (ok) {
        const String tip = fbdoCitire.dataType();
        const bool lipsa = caleLipsa || tip == "null";
        if (etapa == 1) {
          ok = lipsa || tip == "int";
          if (ok) timpCloud = lipsa ? 0 : fbdoCitire.intData();
        } else {
          ok = lipsa || tip == "string";
          if (ok) {
            const String valoare = lipsa ? "" : fbdoCitire.stringData();
            if (etapa == 0) recupereazaJurnal(valoare);
            else if (etapa == 2) dataCloud = valoare;
            else cloud[etapa-3] = valoare;
          }
        }
      }
      if (!ok) {
        // Nicio serie locala nu a fost inlocuita; reluam un set complet dupa backoff.
        etapa = 0; ultimaIncercare = millis(); asteapta = true;
      } else if (++etapa == 12) {
        time(&tNow); t = millis(); localtime_r(&tNow, &timeinfo);
        int goluri = 0;
        if (puncteLocale > 0 && timpCloud > 0) {
          // Folosim ultima proba si numarul RETINUT, inclusiv dupa >24h offline.
          const time_t ultimaLocala = tNow - uint32_t(t - millisUltimaSalvareLocala) / 1000;
          const time_t primaLocala = ultimaLocala - (puncteLocale - 1) * 300;
          goluri = constrain((int)(primaLocala / 300 - timpCloud / 300) - 1, 0, 288);
        }
        String* serii[] = {&istPM25, &istCO2, &istVOC, &istNOx, &istTemp, &istUmid, &istPM1, &istPM4, &istPM10};
        for (int i=0; i<9; ++i) {
          *serii[i] = mergeIstoricCloudLocal(cloud[i], *serii[i], goluri);
          cloud[i] = "";
        }
        lastIstoricSaveTime = puncteLocale > 0 ? tNow - uint32_t(t - millisUltimaSalvareLocala) / 1000 : timpCloud;
        lastArchivedDateStr = dataCloud;
        puncteLocale = 0;
        istoricRecuperat = true; forceFullRedraw = true;
      }
      updateState("Running_Loop");
    }
    return;
  }

  t = millis(); time(&tNow); localtime_r(&tNow, &timeinfo);
  // ---- Logica istorice + arhiva zilnica ----
  if (ceasValid() && timeinfo.tm_year > 120 && istoricRecuperat) {
    int curentMin  = timeinfo.tm_min;
    int curentHour = timeinfo.tm_hour;

    // Curatare jurnal erori din 6 in 6 ore
    if (curentHour % 6 == 0 && curentMin == 0 && firebaseGata && WiFi.status() == WL_CONNECTED && Firebase.ready()) {
      if (!eroriCuratateLaOra) {
        eroriCuratateLaOra = true;
        String jurnalCurat = ""; int startIdx = 0;
        updateState("Curatare_Erori_FB");
        while (startIdx < (int)jurnalErori.length()) {
          int endIdx = jurnalErori.indexOf('|', startIdx); if (endIdx == -1) endIdx = jurnalErori.length();
          String eroareTemp = jurnalErori.substring(startIdx, endIdx);
          int hashIdx = eroareTemp.indexOf('#');
          if (hashIdx != -1) { long tsEroare = eroareTemp.substring(0, hashIdx).toInt(); if (tNow - tsEroare <= 86400) { if (jurnalCurat != "") jurnalCurat += "|"; jurnalCurat += eroareTemp; } }
          startIdx = endIdx + 1;
        }
        if (jurnalCurat != jurnalErori) { jurnalErori = jurnalCurat; jurnalModificat = true; }
        updateState("Running_Loop");
      }
    } else { eroriCuratateLaOra = false; }

    char timeBuffToday[50];
    strftime(timeBuffToday, sizeof(timeBuffToday), "%Y_%m_%d", &timeinfo);
    String todayStr = String(timeBuffToday);

    // Pregatim ziua la schimbarea datei, chiar offline. O pastram pana la confirmarea serverului.
    static String dataInAsteptare, ziDupaArhiva;
    static String seriiInAsteptare[6];
    static uint32_t ultimaIncercareArhiva = 0;
    static bool arhivaAIncercat = false;
    const char* numeArhiva[] = {"PM25", "CO2", "VOC", "NOx", "Temp", "Umid"};
    // O corectie a ceasului inapoi nu trebuie sa eticheteze probe viitoare ca trecut.
    if (dataInAsteptare == "" && lastArchivedDateStr != "" && todayStr > lastArchivedDateStr &&
        lastIstoricSaveTime <= tNow) {
      struct tm ieri = timeinfo;
      ieri.tm_mday--; ieri.tm_hour=12; ieri.tm_min=0; ieri.tm_sec=0; ieri.tm_isdst=-1;
      mktime(&ieri);
      char dataIeri[12]; strftime(dataIeri, sizeof(dataIeri), "%Y_%m_%d", &ieri);
      // Fereastra RAM nu poate reconstrui zile deja iesite din cele 24h retinute.
      // Nu etichetam datele de ieri cu o data veche de saptamani.
      dataInAsteptare = String(dataIeri);
      arhivaDiagnosticInAsteptare = dataInAsteptare;
      ziDupaArhiva = todayStr;
      int puncteAzi = 0;
      if (lastIstoricSaveTime > 0) {
        long goluri = constrain((long)(tNow/300-lastIstoricSaveTime/300)-1, 0L, 288L);
        time_t slotFinal = lastIstoricSaveTime + goluri*300;
        struct tm slotTm; localtime_r(&slotFinal, &slotTm);
        char dataSlot[12]; strftime(dataSlot, sizeof(dataSlot), "%Y_%m_%d", &slotTm);
        if (todayStr == String(dataSlot)) puncteAzi = (slotTm.tm_hour*60+slotTm.tm_min)/5+1;
      }
      String* serii[] = {&istPM25, &istCO2, &istVOC, &istNOx, &istTemp, &istUmid};
      for (int i=0; i<6; ++i)
        seriiInAsteptare[i] = extrageArhivaIeri(getPaddedHistory(*serii[i], tNow, lastIstoricSaveTime), puncteAzi);
      arhivaAIncercat = false;
    }
    if (firebaseGata && WiFi.status() == WL_CONNECTED && Firebase.ready() &&
        (!arhivaAIncercat || uint32_t(millis()-ultimaIncercareArhiva) >= 30000)) {
      if (dataInAsteptare != "") {
        arhivaAIncercat = true; ultimaIncercareArhiva = millis();
        updateState("Confirmare_Arhiva_FB");
        const String cale = "/SenzoriAer/Arhiva_Zilnica/" + dataInAsteptare;
        bool citit = Firebase.RTDB.get(&fbdoCitire, cale);
        const bool lipsa = fbdoCitire.httpCode() == FIREBASE_ERROR_PATH_NOT_EXIST || fbdoCitire.dataType() == "null";
        if (citit || fbdoCitire.httpCode() == FIREBASE_ERROR_PATH_NOT_EXIST) {
          if (lipsa || fbdoCitire.dataType() == "json") {
            FirebaseJson patch;
            for (int i=0; i<6; ++i) {
              String veche;
              if (!lipsa) {
                FirebaseJsonData v;
                fbdoCitire.jsonObject().get(v, numeArhiva[i]);
                if (v.success) veche = v.stringValue;
              }
              // add pastreaza cheia cu slash-uri pentru PATCH multipath; set ar crea
              // un obiect Arhiva_Zilnica imbricat si ar sterge celelalte zile din server.
              patch.add("Arhiva_Zilnica/" + dataInAsteptare + "/" + numeArhiva[i], combinaArhivaZi(veche, seriiInAsteptare[i]));
            }
            // O singura scriere atomica, sincron confirmata: ziua si marcajul avanseaza impreuna.
            patch.set("last_archived_date", ziDupaArhiva);
            if (Firebase.RTDB.updateNodeSilent(&fbdoScriere, "/SenzoriAer", &patch)) {
              lastArchivedDateStr = ziDupaArhiva;
              logSystem("Arhiva confirmata: " + dataInAsteptare);
              dataInAsteptare = "";
              arhivaDiagnosticInAsteptare = dataInAsteptare;
              for (int i=0; i<6; ++i) seriiInAsteptare[i] = "";
            }
          }
        }
        updateState("Running_Loop");
      } else if (lastArchivedDateStr == "") {
        arhivaAIncercat = true; ultimaIncercareArhiva = millis();
        if (Firebase.RTDB.setString(&fbdoScriere, "/SenzoriAer/last_archived_date", todayStr))
          lastArchivedDateStr = todayStr;
      }
    }

    // Salvare istoric la fiecare 5 minute
    const time_t slotCurent = tNow / 300;
    if (curentMin % 5 == 0 && slotCurent > ultimulSlotSalvat &&
        (lastIstoricSaveTime <= 0 || slotCurent > lastIstoricSaveTime / 300)) {
      ultimulSlotSalvat = slotCurent;
      int intervalePierdute = 0;
      updateState("Salvare_Istoric_5Min");

      if (lastIstoricSaveTime > 0) {
        intervalePierdute = slotCurent - (lastIstoricSaveTime / 300) - 1;
        if (intervalePierdute < 0) intervalePierdute = 0;
        if (intervalePierdute > 288) intervalePierdute = 288;
      }
      lastIstoricSaveTime = slotCurent * 300;

      salveazaPunctIstoric(intervalePierdute);

      if (firebaseGata && WiFi.status() == WL_CONNECTED && Firebase.ready()) {
        FirebaseJson jsonIstoric;
        jsonIstoric.set("Istoric_PM25",  istPM25);  jsonIstoric.set("Istoric_CO2",  istCO2);
        jsonIstoric.set("Istoric_VOC",   istVOC);   jsonIstoric.set("Istoric_NOx",  istNOx);
        jsonIstoric.set("Istoric_Temp",  istTemp);  jsonIstoric.set("Istoric_Umid", istUmid);
        jsonIstoric.set("Istoric_PM1",   istPM1);   jsonIstoric.set("Istoric_PM4",  istPM4);
        jsonIstoric.set("Istoric_PM10",  istPM10);
        jsonIstoric.set("last_history_time", lastIstoricSaveTime);
        Firebase.RTDB.updateNodeAsync(&fbdoScriere, "/SenzoriAer", &jsonIstoric);
      }
      updateState("Running_Loop");
    }
  }
}
