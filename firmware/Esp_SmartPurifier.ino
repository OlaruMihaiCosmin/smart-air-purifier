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

// ================= DEBUG CONTROL =================
// Comentează linia de mai jos pentru a dezactiva complet output-ul Serial
// și a recupera ~2-5% din viteza buclei principale
#define DEBUG_SERIAL
// =================================================

// ================= DATELE FIREBASE =================
#define API_KEY "YOUR_FIREBASE_API_KEY"
#define DATABASE_URL "https://YOUR_PROJECT-default-rtdb.firebasedatabase.app/"
// ===================================================

#define USER_EMAIL "YOUR_DEVICE_EMAIL"
#define USER_PASSWORD "YOUR_PASSWORD"

const char* TZ_INFO   = "EET-2EEST,M3.5.0/3,M10.5.0/4";
const char* ntpServer = "pool.ntp.org";

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
FirebaseAuth   auth;
FirebaseConfig config;
bool firebaseGata = false;

Preferences storace;

bool isAutoMode = false;
unsigned long timpUltimaSchimbareAuto = 0;
int  vitezaAutoCurenta = 0;
bool eraAutoInainte    = false;

bool istoricRecuperat  = false;
bool bootLogSent       = false;
int  lastSavedMin      = -1;
String lastArchivedDateStr = "";
long   lastIstoricSaveTime = 0;
unsigned long tAntSenzor = 0, tAntFB = 0, tAntEcran = 0, tAntLive = 0,
              tAntFading = 0, tAntRpm = 0, tAntWifi = 0, tAntVocSave = 0;

unsigned long tUltimaCitireReusita = 0;
unsigned long tSenzorStart         = 0;

bool areDate     = false;
bool isNightMode = false;
bool nightModeApp = false;

int  vitezaDinAplicatie = 0;
int  luminozitateEcran  = 100;
int  luminozitateLed    = 150;
int  targetFanSpeed     = 0;
int  currentFanSpeed    = 0;
int  rpmReal            = 0;
bool modLiveRapid       = false;

float offsetTemperatura = -1.0;

RTC_NOINIT_ATTR char rtc_last_state[64];
String motivRestart = "";

// =========================================================================
// VARIABILE PENTRU ANIMATIILE FLICKER-FREE (UI Dashboard)
// =========================================================================
bool forceFullRedraw = true;

int   old_co2  = -1,   old_voc = -1, old_nox = -1;
float old_pm25 = -1.0, old_temp = -99.0, old_umid = -1.0;
int   old_fan  = -1,   old_rpm = -1;
String old_time = "", old_date = "";
// SSID afisat in panelul SISTEM
String old_ssid = "";

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
// Bounding box pentru SSID
int16_t bx_ssid = 0, by_ssid = 0; uint16_t bw_ssid = 0, bh_ssid = 0;

int cur_co2=-1, cur_pm=-1, cur_voc=-1, cur_nox=-1, cur_temp=-1, cur_umid=-1;

// =========================================================================
// updateState — scriere in RTC si, optional, in Serial
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
  int secundePierdute  = tNow - lastSave;
  int intervalePierdute = (secundePierdute / 300) - 1;
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
// FILTRUL RPM (Software Timer + filtrare debounce)
// =========================================================================
esp_timer_handle_t tacho_timer;
volatile unsigned int validPulseCount = 0;
volatile uint8_t tachoState  = HIGH;
volatile uint8_t lowSamples  = 0;
volatile uint8_t highSamples = 0;

void IRAM_ATTR tachoPoll(void* arg) {
  uint8_t pin = digitalRead(PIN_FAN_RPM);
  if (pin == LOW) {
    if (lowSamples  < 255) lowSamples++;
    highSamples = 0;
  } else {
    if (highSamples < 255) highSamples++;
    lowSamples = 0;
  }
  if (lowSamples  >= 4 && tachoState == HIGH) { tachoState = LOW;  validPulseCount++; }
  else if (highSamples >= 4 && tachoState == LOW)  { tachoState = HIGH; }
}

float pm1=-1, pm25=-1, pm4=-1, pm10=-1, umiditate=0, temp=0, voc=65535, nox=65535;
uint16_t co2 = 0;

float sumPM1=0, sumPM25=0, sumPM4=0, sumPM10=0, sumTemp=0, sumUmid=0, sumVOC=0, sumNOx=0;
unsigned long sumCO2 = 0;
int countPM=-1, countCO2=0, countClima=0, countVOC=0, countNOx=0;

String istPM25="", istCO2="", istVOC="", istNOx="", istTemp="", istUmid="";
String istPM1="", istPM4="", istPM10="";
String jurnalErori = "";
bool eroriCuratateLaOra=false, wifiAvertizat=false, senzorAvertizat=false;

void logSystem(String mesaj) {
  #ifdef DEBUG_SERIAL
  if (Serial) Serial.println(mesaj);
  #endif
  if (!firebaseGata) return;
  time_t now; time(&now);
  String tsStr  = (now < 1000000000) ? "999999999" : String(now);
  String intrare = tsStr + "#" + mesaj;

  if (jurnalErori != "") jurnalErori += "|";
  jurnalErori += intrare;

  int nrErori = 0;
  for (int i = 0; i < (int)jurnalErori.length(); i++) { if (jurnalErori[i] == '|') nrErori++; }
  while (nrErori >= 30) {
    int idx = jurnalErori.indexOf('|');
    if (idx != -1) jurnalErori.remove(0, idx + 1);
    nrErori--;
  }
  Firebase.RTDB.setStringAsync(&fbdoScriere, "/SenzoriAer/ErrorLog", jurnalErori);
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
// FUNCTII FLICKER-FREE — DESENARE TEXT
//
// Principiu: setTextColor(fg, bg) face ca GFX să deseneze fiecare pixel
// de fundal simultan cu caracterul → nu mai există "frame gol" între
// stergere și redesenare. Eliminăm fillRect-ul global și înlocuim cu
// ștergerea selectivă DOAR a fragmentelor care depășesc noul bounding box.
// =========================================================================

void drawUpdatingText(String text, int x, int y, const GFXfont *font,
                      uint16_t color,
                      int16_t &bx, int16_t &by, uint16_t &bw, uint16_t &bh)
{
  tft.setFont(font);
  int16_t x1, y1; uint16_t w, h;
  tft.getTextBounds(text, x, y, &x1, &y1, &w, &h);

  // Sterge NUMAI zonele care raman descoperite față de textul anterior
  if (bw > 0) {
    // Stânga (dacă noul text începe mai la dreapta)
    if (x1 > bx)
      tft.fillRect(bx, by, x1 - bx, bh, C_PANEL);
    // Dreapta (dacă noul text se termină mai la stânga)
    if ((int)(x1 + w) < (int)(bx + bw))
      tft.fillRect(x1 + w, by, (bx + bw) - (x1 + w), bh, C_PANEL);
    // Sus (dacă noul bounding box începe mai jos)
    if (y1 > by)
      tft.fillRect(bx, by, bw, y1 - by, C_PANEL);
    // Jos (dacă noul bounding box se termină mai sus)
    if ((int)(y1 + h) < (int)(by + bh))
      tft.fillRect(bx, y1 + h, bw, (by + bh) - (y1 + h), C_PANEL);
  }

  bx = x1; by = y1; bw = w; bh = h;

  // Desenare atomică: foreground + background simultan → zero flicker
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

  // Calcul pozitie centrată
  tft.getTextBounds(text, 0, 0, &x1, &y1, &w, &h);
  int startX = centerX - (w / 2);
  tft.getTextBounds(text, startX, y, &x1, &y1, &w, &h);

  // Sterge NUMAI zonele care raman descoperite
  if (bw > 0) {
    // Stânga
    if (x1 > bx)
      tft.fillRect(bx, by, x1 - bx, bh, C_PANEL);
    // Dreapta
    if ((int)(x1 + w) < (int)(bx + bw))
      tft.fillRect(x1 + w, by, (bx + bw) - (x1 + w), bh, C_PANEL);
    // Sus
    if (y1 > by)
      tft.fillRect(bx, by, bw, y1 - by, C_PANEL);
    // Jos
    if ((int)(y1 + h) < (int)(by + bh))
      tft.fillRect(bx, y1 + h, bw, (by + bh) - (y1 + h), C_PANEL);
  }

  bx = x1; by = y1; bw = w; bh = h;

  // Desenare atomică
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

void updateCursor(int x, int y, int w, int h, float val, float minV, float maxV, int &oldC) {
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
// DASHBOARD PRINCIPAL
// =========================================================================
void deseneazaDashboard() {
  if (isNightMode) return;

  if (forceFullRedraw) {
    tft.fillScreen(C_BG);

    // Panel CO2
    tft.fillRoundRect(4, 4, 153, 76, 6, C_PANEL);
    tft.drawRoundRect(4, 4, 153, 76, 6, C_PANEL_B);
    tft.setFont(NULL); tft.setCursor(12, 10); tft.setTextColor(C_TEXT_DIM); tft.print("CO2");
    drawSegmentedBar(12, 68, 137, 5);

    // Panel PM2.5
    tft.fillRoundRect(163, 4, 153, 76, 6, C_PANEL);
    tft.drawRoundRect(163, 4, 153, 76, 6, C_PANEL_B);
    tft.setFont(NULL); tft.setCursor(171, 10); tft.setTextColor(C_TEXT_DIM); tft.print("PM2.5");
    drawSegmentedBar(171, 68, 137, 5);

    // Panel VOC + NOx
    tft.fillRoundRect(4, 86, 153, 76, 6, C_PANEL);
    tft.drawRoundRect(4, 86, 153, 76, 6, C_PANEL_B);
    tft.setFont(NULL); tft.setCursor(12, 92); tft.setTextColor(C_TEXT_DIM); tft.print("VOC");
    tft.setFont(NULL); tft.setCursor(84, 92); tft.setTextColor(C_TEXT_DIM); tft.print("NOx");
    drawSegmentedBar(12, 150, 65, 5);
    drawSegmentedBar(84, 150, 65, 5);

    // Panel TEMP + UMID
    tft.fillRoundRect(163, 86, 153, 76, 6, C_PANEL);
    tft.drawRoundRect(163, 86, 153, 76, 6, C_PANEL_B);
    tft.setFont(NULL); tft.setCursor(171, 92); tft.setTextColor(C_TEXT_DIM); tft.print("TEMP");
    tft.setFont(NULL); tft.setCursor(243, 92); tft.setTextColor(C_TEXT_DIM); tft.print("UMID");
    drawTempBar(171, 150, 65, 5);
    drawHumidBar(243, 150, 65, 5);

    // Panel SISTEM
    tft.fillRoundRect(4, 168, 153, 68, 6, C_PANEL);
    tft.drawRoundRect(4, 168, 153, 68, 6, C_PANEL_B);
    tft.setFont(NULL); tft.setCursor(12, 174); tft.setTextColor(C_TEXT_DIM); tft.print("SISTEM");

    // Panel CEAS
    tft.fillRoundRect(163, 168, 153, 68, 6, C_PANEL);
    tft.drawRoundRect(163, 168, 153, 68, 6, C_PANEL_B);

    forceFullRedraw = false;

    // Reset tracking variables
    old_co2=-1; old_pm25=-99.0; old_voc=-1; old_nox=-1;
    old_temp=-99.0; old_umid=-1.0; old_fan=-1; old_rpm=-1;
    old_time=""; old_date=""; old_ssid="";
    bw_co2=0; bw_pm=0; bw_voc=0; bw_nox=0; bw_temp=0; bw_umid=0;
    bw_fan=0; bw_rpm=0; bw_time=0; bw_date=0; bw_ssid=0;
    cur_co2=-1; cur_pm=-1; cur_voc=-1; cur_nox=-1; cur_temp=-1; cur_umid=-1;
  }

  // ---- CO2 ----
  if (co2 != (uint16_t)old_co2) {
    String vStr = (co2 == 0 || co2 == 65535) ? "Init" : String(co2);
    drawUpdatingTextCentered(vStr, 80, 52, &FreeSansBold24pt7b, getCo2Color(co2),
                             bx_co2, by_co2, bw_co2, bh_co2);
    updateCursor(12, 68, 137, 6, co2, 400, 2000, cur_co2);
    old_co2 = co2;
  }

  // ---- PM2.5 ----
  float disp_pm25 = round(pm25 * 10.0) / 10.0;
  if (disp_pm25 != old_pm25) {
    String vStr = (disp_pm25 < 0.0 && !areDate) ? "Init" : String(disp_pm25, 1);
    drawUpdatingTextCentered(vStr, 239, 52, &FreeSansBold24pt7b, getPmColor(disp_pm25),
                             bx_pm, by_pm, bw_pm, bh_pm);
    updateCursor(171, 68, 137, 6, disp_pm25, 0, 50, cur_pm);
    old_pm25 = disp_pm25;
  }

  // ---- VOC ----
  int disp_voc = (int)voc;
  if (disp_voc != old_voc) {
    String vStr = (disp_voc > 500) ? "Init" : String(disp_voc);
    drawUpdatingTextCentered(vStr, 44, 138, &FreeSansBold18pt7b, getVocColor(disp_voc),
                             bx_voc, by_voc, bw_voc, bh_voc);
    updateCursor(12, 150, 65, 6, disp_voc, 1, 500, cur_voc);
    old_voc = disp_voc;
  }

  // ---- NOx ----
  int disp_nox = (int)nox;
  if (disp_nox != old_nox) {
    String vStr = (disp_nox > 500) ? "Init" : String(disp_nox);
    drawUpdatingTextCentered(vStr, 116, 138, &FreeSansBold18pt7b, getNoxColor(disp_nox),
                             bx_nox, by_nox, bw_nox, bh_nox);
    updateCursor(84, 150, 65, 6, disp_nox, 1, 500, cur_nox);
    old_nox = disp_nox;
  }

  // ---- TEMP ----
  float disp_temp = round(temp * 10.0) / 10.0;
  if (disp_temp != old_temp) {
    String vStr = (disp_temp == 0) ? "Init" : String(disp_temp, 1);
    drawUpdatingTextCentered(vStr, 203, 138, &FreeSansBold18pt7b, getTempColor(disp_temp),
                             bx_temp, by_temp, bw_temp, bh_temp);
    updateCursor(171, 150, 65, 6, disp_temp, 16, 30, cur_temp);
    old_temp = disp_temp;
  }

  // ---- UMIDITATE ----
  float disp_umid = round(umiditate * 10.0) / 10.0;
  if (disp_umid != old_umid) {
    String vStr = (disp_umid == 0) ? "Init" : String(disp_umid, 1);
    drawUpdatingTextCentered(vStr, 275, 138, &FreeSansBold18pt7b, getHumidColor(disp_umid),
                             bx_umid, by_umid, bw_umid, bh_umid);
    updateCursor(243, 150, 65, 6, disp_umid, 10, 90, cur_umid);
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

  // ---- INDICATOR WiFi + SSID in panelul SISTEM ----
  // (y=190 e perfect intre eticheta "SISTEM" la y=174 si fan% la y=205)
  bool wifiConn = (WiFi.status() == WL_CONNECTED);
  tft.fillCircle(146, 178, 3, wifiConn ? ST77XX_GREEN : ST77XX_RED);

  String ssidCurent = wifiConn ? WiFi.SSID() : "Offline";
  if ((int)ssidCurent.length() > 13) ssidCurent = ssidCurent.substring(0, 12) + "~";

  if (ssidCurent != old_ssid) {
    // Sterge textul anterior (SSID foloseste font NULL deci setTextColor cu bg merge perfect)
    if (bw_ssid > 0) tft.fillRect(bx_ssid, by_ssid, bw_ssid, bh_ssid, C_PANEL);
    tft.setFont(NULL);
    int16_t x1, y1; uint16_t w, h;
    tft.getTextBounds(ssidCurent.c_str(), 12, 190, &x1, &y1, &w, &h);
    bx_ssid = x1; by_ssid = y1; bw_ssid = w; bh_ssid = h;
    // Culoare: gri-dim daca conectat, rosu daca offline
    tft.setTextColor(wifiConn ? C_TEXT_DIM : ST77XX_RED, C_PANEL);
    tft.setCursor(12, 190);
    tft.print(ssidCurent);
    old_ssid = ssidCurent;
  }

  // ---- ORA + DATA ----
  struct tm timeinfo;
  if (getLocalTime(&timeinfo)) {
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
  Serial.begin(115200);

  if (esp_reset_reason() == ESP_RST_POWERON || esp_reset_reason() == ESP_RST_BROWNOUT) {
    strcpy(rtc_last_state, "Sistem_Pornit_Priza");
  }
  motivRestart = getResetReasonString();

  // ---- Pasul 1: Rezervare memorie (inainte de ecran) ----
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

  // Titlu fix
  tft.setFont(&FreeSansBold18pt7b);
  tft.setTextColor(ST77XX_WHITE);
  tft.setCursor(55, 55);
  tft.print("Smart Air");

  // Subtitlu
  tft.setFont(&FreeSans9pt7b);
  tft.setTextColor(C_SKYBLUE);
  tft.setCursor(68, 78);
  tft.print("Purificator DIY");

  // Linie separator
  tft.drawFastHLine(20, 90, 280, C_PANEL_B);

  // Helper pentru mesajele de boot (Lambda C++11 — nativ pe ESP32 Arduino)
  // Afiseaza mesajul curent si actualizeaza bara de progres (pasii 3-8)
  auto bootMsg = [&](const char* msg, uint8_t step) {
    // Sterge zona de mesaj
    tft.fillRect(0, 100, 320, 22, C_BG);
    tft.setFont(NULL);
    tft.setTextColor(C_TEXT_DIM);
    tft.setCursor(20, 108);
    tft.print(msg);
    // Bara de progres (pasii 1-8 mapati pe 280px)
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
  Wire.setTimeout(500); // 500ms — suficient pentru SEN66, reduce blocajele vs 1000ms

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
  tUltimaCitireReusita  = millis();

  sen66.setTemperatureOffsetParameters((int16_t)(offsetTemperatura * 200), 0, 0, 0);
  delay(20);

  // ---- Pasul 7: WiFi ----
  bootMsg("[7/8] Conectare WiFi...", 7);
  #ifdef DEBUG_SERIAL
  Serial.println("[7/8] Adaugare retele WiFi cunoscute si conectare...");
  #endif
  WiFi.setSleep(false);

  // =============================================
  // LISTA RETELE WIFI CUNOSCUTE
  // Adaugă/modifică după necesitate:
  wifiMulti.addAP("YOUR_WIFI_SSID",  "YOUR_WIFI_PASS");  // Retea acasa
  wifiMulti.addAP("YOUR_WIFI_SSID_2", "YOUR_WIFI_PASS_2");   // Retea locatie 2
  wifiMulti.addAP("YOUR_WIFI_SSID_3",  "YOUR_WIFI_PASS_3");    // Retea locatie 3
  // =============================================

  int conectareTries = 0;
  while (wifiMulti.run() != WL_CONNECTED && conectareTries < 20) {
    delay(500);
    conectareTries++;
  }
  #ifdef DEBUG_SERIAL
  if (WiFi.status() == WL_CONNECTED) {
    Serial.print(">> Conectat la: "); Serial.println(WiFi.SSID());
    Serial.print(">> IP: ");          Serial.println(WiFi.localIP());
  }
  #endif

  // ---- Pasul 8: WDT + OTA + Tahometru ----
  bootMsg("[8/8] Sistem gata!", 8);
  // Bara complet verde la final
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
  ArduinoOTA.setPassword("YOUR_OTA_PASSWORD");
  ArduinoOTA.onStart([]() { esp_task_wdt_delete(NULL); });
  ArduinoOTA.begin();

  pinMode(PIN_FAN_RPM, INPUT_PULLUP);
  const esp_timer_create_args_t tacho_timer_args = { .callback = &tachoPoll, .name = "tacho_poll" };
  esp_timer_create(&tacho_timer_args, &tacho_timer);
  esp_timer_start_periodic(tacho_timer, 250);

  forceFullRedraw = true;
  delay(200);
}

// =========================================================================
// LOOP
// =========================================================================
void loop() {
  ArduinoOTA.handle();
  esp_task_wdt_reset();

  unsigned long t = millis();
  time_t tNow; time(&tNow);
  struct tm timeinfo; localtime_r(&tNow, &timeinfo);

  // ---- Gestionare WiFi + reconectare multi-AP ----
  if (WiFi.status() != WL_CONNECTED) {
    if (t - tAntWifi >= 30000) {
      tAntWifi = t;
      if (!wifiAvertizat) { wifiAvertizat = true; updateState("Pierdut_WiFi"); }
      // wifiMulti.run() scanează automat toate AP-urile cunoscute
      // și se conectează la cel cu semnal mai bun disponibil
      wifiMulti.run();
    }
  } else {
    if (wifiAvertizat) {
      logSystem("WiFi restabilit pe: " + WiFi.SSID());
      wifiAvertizat = false;
      fbdoScriere.clear();
      fbdoCitire.clear();
      Firebase.reconnectWiFi(true);
      updateState("Running_Loop");
    }

    static bool firebaseInitializat = false;
    if (!firebaseInitializat) {
      updateState("Init_Firebase");
      configTzTime(TZ_INFO, ntpServer);
      time_t testT; int retries = 0;
      while (time(&testT) < 1000000000 && retries < 10) {
        delay(500); retries++; esp_task_wdt_reset(); yield();
      }

      config.api_key        = API_KEY;
      config.database_url   = DATABASE_URL;
      config.timeout.serverResponse  = 3 * 1000;
      config.timeout.socketConnection = 3 * 1000;
      config.tcp_data_sending_retry  = 1;

      auth.user.email    = USER_EMAIL;
      auth.user.password = USER_PASSWORD;

      config.token_status_callback = tokenStatusCallback;
      fbdoScriere.setBSSLBufferSize(2048, 1024);
      fbdoCitire.setBSSLBufferSize(2048, 1024);

      Firebase.begin(&config, &auth);
      Firebase.reconnectWiFi(true);
      firebaseInitializat = true;
      firebaseGata        = true;
      updateState("Running_Loop");
    }
  }

  // ---- Tahometru (1 secunda) ----
  if (t - tAntRpm >= 1000) {
    static unsigned int pulsuriTrecute[3] = {0, 0, 0};
    static byte indexPuls = 0;

    noInterrupts();
    unsigned int pulsuriAcum = validPulseCount;
    validPulseCount = 0;
    interrupts();

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
      if (!senzorAvertizat) { logSystem("Eroare citire SEN66: Cod " + String(error)); senzorAvertizat = true; }
    } else {
      if (senzorAvertizat) { logSystem("Comunicare SEN66 restabilita"); senzorAvertizat = false; }
      tUltimaCitireReusita = t;

      if (t_co2 != 65535) {
        unsigned long uptimeSenzor = t - tSenzorStart;
        bool prafValid  = (uptimeSenzor >= 20000);
        bool co2Valid   = (uptimeSenzor >= 20000);
        bool climaValid = (uptimeSenzor >= 60000);
        bool gazeValid  = (uptimeSenzor >= 60000);

        if (prafValid)  { pm1=t_pm1; pm25=t_pm25; pm4=t_pm4; pm10=t_pm10; sumPM1+=pm1; sumPM25+=pm25; sumPM4+=pm4; sumPM10+=pm10; countPM++; }
        if (co2Valid)   { co2=t_co2; sumCO2+=co2; countCO2++; }
        if (climaValid) { temp=t_temp; umiditate=t_umid; sumTemp+=temp; sumUmid+=umiditate; countClima++; }
        if (gazeValid)  {
          if (t_voc <= 500) { voc=t_voc; sumVOC+=voc; countVOC++; }
          if (t_nox <= 500) { nox=t_nox; sumNOx+=nox; countNOx++; }
        }
        areDate = (prafValid || co2Valid || climaValid || gazeValid);
      } else {
        areDate = false;
      }
    }
    updateState("Running_Loop");
  }

  // ---- Watchdog Senzor: reinitializare dupa 15s fara citire ----
  if (t - tUltimaCitireReusita > 15000) {
    tUltimaCitireReusita = t;
    areDate = false;
    logSystem("WDT: Senzorul SEN66 a blocat citirile de 15s. Reinitializare...");
    updateState("Reset_Senzor_Hardware");

    sen66.stopMeasurement();
    unsigned long tStop = millis();
    while (millis() - tStop < 1500) { esp_task_wdt_reset(); yield(); }

    clearI2CBus(I2C_SDA, I2C_SCL);
    Wire.begin(I2C_SDA, I2C_SCL);
    Wire.setTimeout(500);
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

    sen66.setTemperatureOffsetParameters((int16_t)(offsetTemperatura * 200), 0, 0, 0);
    tReset = millis(); while (millis() - tReset < 20) { yield(); }

    updateState("Running_Loop");
  }

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

  // ---- Logica Auto / Manual ventilator ----
  bool comandaEsteAuto = (vitezaDinAplicatie == 0);
  if (WiFi.status() != WL_CONNECTED && !comandaEsteAuto && vitezaDinAplicatie > 5) comandaEsteAuto = true;
  isAutoMode = comandaEsteAuto;

  if (isAutoMode) {
    if (areDate) {
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

      if      (pm25 >= 35 || co2 >= 1600 || (voc >= 400 && voc <= 500) || (nox >= 300 && nox <= 500))
        rgbLed.setPixelColor(0, rgbLed.Color(255, 0, 0));
      else if (pm25 >= 12 || co2 >= 1200 || (voc >= 250 && voc <= 500) || (nox >= 150 && nox <= 500))
        rgbLed.setPixelColor(0, rgbLed.Color(255, 144, 0));
      else if (pm25 >= 5  || (co2 >= 800 && co2 != 0) || (voc >= 150 && voc <= 500) || (nox >= 20 && nox <= 500))
        rgbLed.setPixelColor(0, rgbLed.Color(255, 255, 0));
      else
        rgbLed.setPixelColor(0, rgbLed.Color(0, 255, 0));

      rgbLed.setBrightness(luminozitateLed);
      rgbLed.show();
      deseneazaDashboard();
    }
    updateState("Running_Loop");
  }

  // ---- Sincronizare initiala istoric din Firebase ----
  if (!istoricRecuperat) {
    if (WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata) {
      tft.fillRect(4, 168, 153, 68, C_PANEL);
      tft.setFont(&FreeSans9pt7b); tft.setTextColor(ST77XX_ORANGE);
      tft.setCursor(12, 195); tft.print("Sync DB...");

      updateState("Sync_Jurnal");
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/ErrorLog"))       jurnalErori = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
      if (!bootLogSent) { logSystem("Sistem Repornit: " + motivRestart); bootLogSent = true; esp_task_wdt_reset(); yield(); }
      updateState("Sync_Time");
      if (Firebase.RTDB.getInt(&fbdoCitire, "/SenzoriAer/last_history_time")) lastIstoricSaveTime = fbdoCitire.intData(); esp_task_wdt_reset(); yield();
      updateState("Sync_Date");
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/last_archived_date")) lastArchivedDateStr = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
      updateState("Sync_IstoricPM25");
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_PM25"))    istPM25 = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
      updateState("Sync_IstoricCO2");
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_CO2"))     istCO2  = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
      updateState("Sync_IstoricVOC");
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_VOC"))     istVOC  = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
      updateState("Sync_IstoricNOx");
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_NOx"))     istNOx  = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
      updateState("Sync_IstoricTemp");
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_Temp"))    istTemp = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
      updateState("Sync_IstoricUmid");
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_Umid"))    istUmid = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
      updateState("Sync_IstoricPM1");
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_PM1"))     istPM1  = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
      updateState("Sync_IstoricPM4");
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_PM4"))     istPM4  = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
      updateState("Sync_IstoricPM10");
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_PM10"))    istPM10 = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();

      istoricRecuperat = true; forceFullRedraw = true;
      updateState("Running_Loop");
    }
    return;
  }

  // ---- Citire setari din Firebase (2 secunde) ----
  if (WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata && (t - tAntFB >= 2000)) {
    tAntFB = t;
    updateState("Ascultare_Setari_FB");
    esp_task_wdt_reset(); yield();

    if (Firebase.RTDB.getJSON(&fbdoCitire, "/SenzoriAer/setari")) {
      FirebaseJson     &json = fbdoCitire.jsonObject();
      FirebaseJsonData  jsonData;

      json.get(jsonData, "night_mode");
      if (jsonData.success) nightModeApp = jsonData.boolValue;

      json.get(jsonData, "live_mode");
      if (jsonData.success) modLiveRapid = jsonData.boolValue;

      json.get(jsonData, "luminozitate");
      if (jsonData.success) {
        luminozitateEcran = jsonData.intValue;
        if (luminozitateEcran < 0)   luminozitateEcran = 0;
        if (luminozitateEcran > 100) luminozitateEcran = 100;
      }

      json.get(jsonData, "luminozitate_led");
      if (jsonData.success) {
        luminozitateLed = jsonData.intValue;
        if (luminozitateLed < 0)   luminozitateLed = 0;
        if (luminozitateLed > 255) luminozitateLed = 255;
      }

      json.get(jsonData, "speed");
      if (jsonData.success) vitezaDinAplicatie = jsonData.intValue;

      json.get(jsonData, "temp_offset");
      if (jsonData.success) {
        float newOffset = jsonData.floatValue;
        static bool offsetFortatLaBoot = false;
        if (!offsetFortatLaBoot || fabs(newOffset - offsetTemperatura) > 0.05) {
          offsetTemperatura = newOffset;
          storace.putFloat("tempOffset", offsetTemperatura);
          sen66.setTemperatureOffsetParameters((int16_t)(offsetTemperatura * 200), 0, 0, 0);
          unsigned long tWait = millis();
          while (millis() - tWait < 20) { yield(); }
          if (offsetFortatLaBoot) logSystem("Offset Termic modificat: " + String(offsetTemperatura) + " C");
          else { 
          #ifdef DEBUG_SERIAL 
          if (Serial) Serial.println("Offset sincronizat la boot."); 
          #endif 
          }
          offsetFortatLaBoot = true;
        }
      }

      json.get(jsonData, "reset_calibrare");
      if (jsonData.success && jsonData.boolValue == true) {
        Firebase.RTDB.setBool(&fbdoScriere, "/SenzoriAer/setari/reset_calibrare", false);
        tft.fillScreen(ST77XX_BLACK); tft.setCursor(30, 160); tft.setTextColor(ST77XX_RED); tft.print("RESET CALIBRARI");
        logSystem("Comanda: Stergere Flash VOC si Restart");
        storace.remove("vocState"); delay(100); ESP.restart();
      }

      json.get(jsonData, "curatare");
      if (jsonData.success && jsonData.boolValue == true) {
        Firebase.RTDB.setBool(&fbdoScriere, "/SenzoriAer/setari/curatare", false);
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

      json.get(jsonData, "restart");
      if (jsonData.success && jsonData.boolValue == true) {
        Firebase.RTDB.setBool(&fbdoScriere, "/SenzoriAer/setari/restart", false);
        delay(100); ESP.restart();
      }
    }
    updateState("Running_Loop");
  }

  // ---- Trimitere date live catre Firebase ----
  unsigned long intervalTrimitere = 10000;
  if (currentFanSpeed != targetFanSpeed || modLiveRapid) intervalTrimitere = 1000;

  if (WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata && (t - tAntLive >= intervalTrimitere)) {
    tAntLive = t;
    updateState("Trimitere_Live_FB");

    FirebaseJson jsonLive;
    jsonLive.set("last_update/.sv",      "timestamp");
    jsonLive.set("RPM",                  rpmReal);
    jsonLive.set("ProcentVentilator",    targetFanSpeed);
    jsonLive.set("Telemetrie_RAM_Liber", ESP.getFreeHeap());
    jsonLive.set("Telemetrie_WiFi_RSSI", WiFi.RSSI());
    jsonLive.set("Telemetrie_Uptime_Sec",(int)(millis() / 1000));
    jsonLive.set("Telemetrie_Temp_Core", temperatureRead());
    jsonLive.set("Setari_Offset_Temp",   offsetTemperatura);

    if (areDate) {
      jsonLive.set("CO2", co2); jsonLive.set("VOC", (int)voc); jsonLive.set("NOx", (int)nox);
      jsonLive.set("PM1", pm1); jsonLive.set("PM25", pm25); jsonLive.set("PM4", pm4); jsonLive.set("PM10", pm10);
      jsonLive.set("Temperatura", temp); jsonLive.set("Umiditate", umiditate);
    }

    if (!Firebase.RTDB.updateNodeAsync(&fbdoScriere, "/SenzoriAer", &jsonLive)) {
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

  // ---- Logica istorice + arhiva zilnica ----
  if (timeinfo.tm_year > 120 && istoricRecuperat) {
    int curentMin  = timeinfo.tm_min;
    int curentHour = timeinfo.tm_hour;

    // Curatare jurnal erori din 6 in 6 ore
    if (curentHour % 6 == 0 && curentMin == 0 && WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata) {
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
        if (jurnalCurat != jurnalErori) { jurnalErori = jurnalCurat; Firebase.RTDB.setStringAsync(&fbdoScriere, "/SenzoriAer/ErrorLog", jurnalErori); }
        updateState("Running_Loop");
      }
    } else { eroriCuratateLaOra = false; }

    char timeBuffToday[50];
    strftime(timeBuffToday, sizeof(timeBuffToday), "%Y_%m_%d", &timeinfo);
    String todayStr = String(timeBuffToday);

    // Arhivare zilnica la schimbarea datei
    if (lastArchivedDateStr != "" && todayStr != lastArchivedDateStr &&
        WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata) {
      updateState("Arhivare_Zilnica_FB");
      String nodBaza = "/SenzoriAer/Arhiva_Zilnica/" + lastArchivedDateStr;
      int puncteAzi  = (curentHour * 60 + curentMin) / 5;

      FirebaseJson jsonArhiva;
      jsonArhiva.set("PM25", extrageArhivaIeri(getPaddedHistory(istPM25, tNow, lastIstoricSaveTime), puncteAzi));
      jsonArhiva.set("CO2",  extrageArhivaIeri(getPaddedHistory(istCO2,  tNow, lastIstoricSaveTime), puncteAzi));
      jsonArhiva.set("VOC",  extrageArhivaIeri(getPaddedHistory(istVOC,  tNow, lastIstoricSaveTime), puncteAzi));
      jsonArhiva.set("NOx",  extrageArhivaIeri(getPaddedHistory(istNOx,  tNow, lastIstoricSaveTime), puncteAzi));
      jsonArhiva.set("Temp", extrageArhivaIeri(getPaddedHistory(istTemp, tNow, lastIstoricSaveTime), puncteAzi));
      jsonArhiva.set("Umid", extrageArhivaIeri(getPaddedHistory(istUmid, tNow, lastIstoricSaveTime), puncteAzi));
      Firebase.RTDB.setJSONAsync(&fbdoScriere, nodBaza, &jsonArhiva);

      lastArchivedDateStr = todayStr;
      Firebase.RTDB.setStringAsync(&fbdoScriere, "/SenzoriAer/last_archived_date", todayStr);

      if (curentHour == 0 && curentMin < 5) logSystem("Arhiva pentru " + nodBaza.substring(27) + " creata!");
      else                                   logSystem("Arhiva pentru " + nodBaza.substring(27) + " refacuta!");
      updateState("Running_Loop");
    } else if (lastArchivedDateStr == "" && WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata) {
      lastArchivedDateStr = todayStr;
      Firebase.RTDB.setStringAsync(&fbdoScriere, "/SenzoriAer/last_archived_date", todayStr);
    }

    // Salvare istoric la fiecare 5 minute
    if (curentMin % 5 == 0 && curentMin != lastSavedMin) {
      lastSavedMin = curentMin;
      int intervalePierdute = 0;
      updateState("Salvare_Istoric_5Min");

      if (lastIstoricSaveTime > 0) {
        intervalePierdute = ((tNow - lastIstoricSaveTime) / 300) - 1;
        if (intervalePierdute < 0) intervalePierdute = 0;
        if (intervalePierdute > 288) intervalePierdute = 288;
      }
      lastIstoricSaveTime = tNow;

      String strPM25="-999", strCO2="-999", strVOC="-999", strNOx="-999";
      String strTemp="-999", strUmid="-999", strPM1="-999", strPM4="-999", strPM10="-999";

      if (countPM   > 0) { strPM1=String(sumPM1/countPM,1); strPM25=String(sumPM25/countPM,1); strPM4=String(sumPM4/countPM,1); strPM10=String(sumPM10/countPM,1); }
      if (countCO2  > 0) { strCO2=String((int)round((float)sumCO2/countCO2)); }
      if (countClima> 0) { strTemp=String(sumTemp/countClima,1); strUmid=String(sumUmid/countClima,1); }
      if (countVOC  > 0) { strVOC=String((int)round(sumVOC/countVOC)); }
      if (countNOx  > 0) { strNOx=String((int)round(sumNOx/countNOx)); }

      adaugaInIstoric(istPM25, strPM25, intervalePierdute); adaugaInIstoric(istCO2,  strCO2,  intervalePierdute);
      adaugaInIstoric(istVOC,  strVOC,  intervalePierdute); adaugaInIstoric(istNOx,  strNOx,  intervalePierdute);
      adaugaInIstoric(istTemp, strTemp, intervalePierdute); adaugaInIstoric(istUmid, strUmid, intervalePierdute);
      adaugaInIstoric(istPM1,  strPM1,  intervalePierdute); adaugaInIstoric(istPM4,  strPM4,  intervalePierdute);
      adaugaInIstoric(istPM10, strPM10, intervalePierdute);

      sumPM1=0; sumPM25=0; sumPM4=0; sumPM10=0; sumTemp=0; sumUmid=0; sumVOC=0; sumNOx=0; sumCO2=0;
      countPM=0; countCO2=0; countClima=0; countVOC=0; countNOx=0;

      if (WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata) {
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