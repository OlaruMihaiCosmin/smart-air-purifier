#include <Arduino.h>
#include <WiFi.h>
#include <Firebase_ESP_Client.h>
#include <Adafruit_NeoPixel.h>
#include <Wire.h>
#include <U8g2lib.h>
#include <SensirionI2cSen66.h>
#include "time.h"
#include "esp_timer.h" 
#include <esp_system.h> 

// Librarii auxiliare Firebase
#include "addons/TokenHelper.h"
#include "addons/RTDBHelper.h"

// Librarii pentru Update prin WiFi si Watchdog
#include <ESPmDNS.h>
#include <WiFiUdp.h>
#include <ArduinoOTA.h>
#include <esp_task_wdt.h>

// ================= DATELE FIREBASE =================
#define WIFI_SSID "YOUR_WIFI_SSID"
#define WIFI_PASSWORD "YOUR_WIFI_PASS"
#define API_KEY "YOUR_FIREBASE_API_KEY"
#define DATABASE_URL "https://YOUR_PROJECT-default-rtdb.firebasedatabase.app/" 
// ===================================================

int OFFSET_X = 0; 
int OFFSET_Y = 0;  

const char* TZ_INFO    = "EET-2EEST,M3.5.0/3,M10.5.0/4";
const char* ntpServer  = "pool.ntp.org";

// Pini Hardware
#define I2C_SDA 8
#define I2C_SCL 9
#define PIN_LED_RGB 38
#define PIN_FAN_PWM 1  
#define PIN_FAN_RPM 2  

#define PWM_FRECVENTA 25000 
#define PWM_REZOLUTIE 8 

U8G2_SH1106_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, /* reset=*/ U8X8_PIN_NONE);
Adafruit_NeoPixel rgbLed(1, PIN_LED_RGB, NEO_GRB + NEO_KHZ800);
SensirionI2cSen66 sen66;

FirebaseData fbdoScriere, fbdoCitire;
FirebaseAuth auth;
FirebaseConfig config;
bool firebaseGata = false;

bool istoricRecuperat = false;
bool bootLogSent = false; 
int lastSavedMin = -1;
String lastArchivedDateStr = ""; 
long lastIstoricSaveTime = 0; 
unsigned long tAntSenzor = 0, tAntFB = 0, tAntEcran = 0, tAntLive = 0, tAntFading = 0, tAntRpm = 0;

int paginaCurenta = 1;
bool areDate = false;

int targetFanSpeed = 0;   
int currentFanSpeed = 0;  
int rpmReal = 0; 

// =========================================================================
// FUNCTIE: AFLAREA MOTIVULUI DE RESTART 
// =========================================================================
String getResetReasonString() {
  esp_reset_reason_t reason = esp_reset_reason();
  switch (reason) {
    case ESP_RST_POWERON:   return "Pornit la Priza";
    case ESP_RST_SW:        return "Restart din Aplicatie / OTA";
    case ESP_RST_PANIC:     return "Eroare Fatala (Software Panic)";
    case ESP_RST_INT_WDT:   return "Blocat! (Interrupt Watchdog)";
    case ESP_RST_TASK_WDT:  return "Blocat! (Task Watchdog)";
    case ESP_RST_WDT:       return "Blocat! (Other Watchdog)";
    case ESP_RST_BROWNOUT:  return "Cadere scurta de Tensiune (Brownout)";
    default:                return "Motiv Necunoscut (" + String(reason) + ")";
  }
}

// =========================================================================
// FUNCTIE NOUA: CHIRURGIA DATELOR PENTRU ARHIVARE ASINCRONA
// Aceasta functie "taie" din sir doar datele de ieri si le completeaza pe cele lipsa
// =========================================================================
String extrageArhivaIeri(String dateCurente, int minuteAzi) {
    int puncteAzi = minuteAzi / 5;
    if (puncteAzi >= 288) puncteAzi = 288; // Limita maxima
    
    int puncteIeriRamase = 288 - puncteAzi;
    
    // Daca a trecut mai mult de 24h, ieri nu mai exista nicio data in RAM
    if (puncteIeriRamase <= 0) {
        String gol = "-999";
        for(int i=1; i<288; i++) gol += ",-999";
        return gol;
    }
    
    // Gasim virgula unde se termina datele de ieri
    int currentIndex = 0;
    for (int i = 0; i < puncteIeriRamase; i++) {
        int nextComma = dateCurente.indexOf(',', currentIndex);
        if (nextComma == -1) {
            currentIndex = dateCurente.length() + 1;
            break;
        }
        currentIndex = nextComma + 1;
    }
    
    String valoriIeri = dateCurente;
    if (currentIndex > 0 && currentIndex <= dateCurente.length()) {
        valoriIeri = dateCurente.substring(0, currentIndex - 1);
    }
    
    // Completam dimineata pierduta din ziua de ieri cu -999, ca sa aliniem graficul
    String arhivaReconstruita = "";
    for (int i = 0; i < puncteAzi; i++) {
        arhivaReconstruita += "-999,";
    }
    arhivaReconstruita += valoriIeri;
    
    return arhivaReconstruita;
}

// =========================================================================
// VARIABILE PENTRU FILTRUL DIGITAL RPM
// =========================================================================
esp_timer_handle_t tacho_timer;
volatile unsigned int validPulseCount = 0;
volatile uint8_t tachoState = HIGH;
volatile uint8_t lowSamples = 0;
volatile uint8_t highSamples = 0;

void IRAM_ATTR tachoPoll(void* arg) {
  uint8_t pin = digitalRead(PIN_FAN_RPM);
  if (pin == LOW) {
    if (lowSamples < 255) lowSamples++;
    highSamples = 0;
  } else {
    if (highSamples < 255) highSamples++;
    lowSamples = 0;
  }
  if (lowSamples >= 4 && tachoState == HIGH) {
    tachoState = LOW;
    validPulseCount++;
  } else if (highSamples >= 4 && tachoState == LOW) {
    tachoState = HIGH;
  }
}
// =========================================================================

bool isAutoMode = false;
unsigned long timpUltimaSchimbareAuto = 0;
int vitezaAutoCurenta = 0;
bool eraAutoInainte = false;

// Variabile de aer
float pm1=0, pm25=0, pm4=0, pm10=0, umiditate=0, temp=0, voc=0, nox=0;
uint16_t co2=0;

// Variabile pentru media pe 5 minute
float sumPM1 = 0, sumPM25 = 0, sumPM4 = 0, sumPM10 = 0;
float sumTemp = 0, sumUmid = 0, sumVOC = 0, sumNOx = 0;
unsigned long sumCO2 = 0;
int countAveraging = 0;

String istPM25="", istCO2="", istVOC="", istNOx="", istTemp="", istUmid="", istPM1="", istPM4="", istPM10="";
String jurnalErori = "";
bool eroriCuratateLaOra = false;
bool wifiAvertizat = false;
bool senzorAvertizat = false;

void logSystem(String mesaj) {
  Serial.println(mesaj);
  if (!firebaseGata) return; 
  time_t now; time(&now);
  if (now < 1000000000) return; 

  String intrare = String(now) + "#" + mesaj;
  if (jurnalErori != "") jurnalErori += "|";
  jurnalErori += intrare;

  int nrErori = 0;
  for(int i=0; i<jurnalErori.length(); i++) { if(jurnalErori[i] == '|') nrErori++; }
  while(nrErori >= 30) {
      int idx = jurnalErori.indexOf('|');
      if(idx != -1) jurnalErori = jurnalErori.substring(idx + 1);
      nrErori--;
  }
  Firebase.RTDB.setStringAsync(&fbdoScriere, "/SenzoriAer/ErrorLog", jurnalErori);
}

String adaugaInIstoric(String istoricActual, String valoareNoua, int puncteLipsa) {
  String rezultat = istoricActual;
  for(int i = 0; i < puncteLipsa; i++) {
    if (rezultat != "") rezultat += ",";
    rezultat += "-999";
  }
  if (rezultat != "") rezultat += ",";
  rezultat += valoareNoua;

  int numarVirgule = 0;
  for (int i = 0; i < rezultat.length(); i++) { if (rezultat[i] == ',') numarVirgule++; }
  while (numarVirgule >= 288) { 
    int idx = rezultat.indexOf(',');
    if (idx != -1) rezultat = rezultat.substring(idx + 1);
    else break;
    numarVirgule--;
  }
  return rezultat;
}

void deseneazaPagina(int pag) {
  u8g2.clearBuffer();
  u8g2.setFont(u8g2_font_ncenB08_tr); 
  
  if (pag == 1) { 
      u8g2.drawStr(OFFSET_X, OFFSET_Y + 12, "== SYSTEM STATUS =="); 
      u8g2.setCursor(OFFSET_X, OFFSET_Y + 30); 
      u8g2.print("Putere: "); u8g2.print(currentFanSpeed); u8g2.print("%"); 
      if (isAutoMode) { u8g2.print(" (Auto)"); } else { u8g2.print(" (Man)"); }
      u8g2.setCursor(OFFSET_X, OFFSET_Y + 45); 
      u8g2.print("RPM Real: "); u8g2.print(rpmReal);
  } else if (pag == 2) { 
      u8g2.drawStr(OFFSET_X, OFFSET_Y + 12, "== PRAF (ug/m3) =="); 
      u8g2.setCursor(OFFSET_X, OFFSET_Y + 30); u8g2.print("PM1: "); u8g2.print(pm1, 1);
      u8g2.setCursor(OFFSET_X + 64, OFFSET_Y + 30); u8g2.print("PM2.5: "); u8g2.print(pm25, 1);
      u8g2.setCursor(OFFSET_X, OFFSET_Y + 50); u8g2.print("PM4: "); u8g2.print(pm4, 1);
      u8g2.setCursor(OFFSET_X + 64, OFFSET_Y + 50); u8g2.print("PM10: "); u8g2.print(pm10, 1);
  } else if (pag == 3) { 
      u8g2.drawStr(OFFSET_X, OFFSET_Y + 12, "== GAZE (ppm/idx) =="); 
      u8g2.setCursor(OFFSET_X, OFFSET_Y + 30); u8g2.print("CO2 : "); u8g2.print(co2);
      u8g2.setCursor(OFFSET_X, OFFSET_Y + 45); u8g2.print("VOC : "); u8g2.print((int)voc);
      u8g2.setCursor(OFFSET_X, OFFSET_Y + 60); 
      if (nox > 1000) u8g2.print("NOx : Init..."); else { u8g2.print("NOx : "); u8g2.print((int)nox); }
  } else if (pag == 4) { 
      u8g2.drawStr(OFFSET_X + 16, OFFSET_Y + 12, "== AMBIENTA =="); 
      u8g2.setCursor(OFFSET_X, OFFSET_Y + 30); u8g2.print("Temp: "); u8g2.print(temp, 1); u8g2.print(" C");
      u8g2.setCursor(OFFSET_X, OFFSET_Y + 45); u8g2.print("Umid: "); u8g2.print(umiditate, 1); u8g2.print(" %");
      struct tm timeinfo;
      if (getLocalTime(&timeinfo)) { char timeStringBuff[10]; strftime(timeStringBuff, sizeof(timeStringBuff), "%H:%M", &timeinfo); u8g2.setCursor(OFFSET_X, OFFSET_Y + 60); u8g2.print("Ora: "); u8g2.print(timeStringBuff); } else { u8g2.setCursor(OFFSET_X, OFFSET_Y + 60); u8g2.print("Ora: Sync.."); }
  }
  
  u8g2.setFont(u8g2_font_ncenB08_tr);
  if(pag == 1) u8g2.drawStr(OFFSET_X + 45, OFFSET_Y + 64, "O . . ."); else if(pag == 2) u8g2.drawStr(OFFSET_X + 45, OFFSET_Y + 64, ". O . ."); else if(pag == 3) u8g2.drawStr(OFFSET_X + 45, OFFSET_Y + 64, ". . O ."); else if(pag == 4) u8g2.drawStr(OFFSET_X + 45, OFFSET_Y + 64, ". . . O");
  u8g2.sendBuffer(); 
}

void setup() {
  Serial.begin(115200);
  
  ledcAttach(PIN_FAN_PWM, PWM_FRECVENTA, PWM_REZOLUTIE); 
  ledcWrite(PIN_FAN_PWM, 0); 
  
  pinMode(PIN_FAN_RPM, INPUT); 
  
  const esp_timer_create_args_t tacho_timer_args = {
      .callback = &tachoPoll,
      .name = "tacho_poll"
  };
  esp_timer_create(&tacho_timer_args, &tacho_timer);
  esp_timer_start_periodic(tacho_timer, 250); 
  
  Wire.begin(I2C_SDA, I2C_SCL); Wire.setClock(100000); 
  rgbLed.begin(); rgbLed.setBrightness(150); rgbLed.setPixelColor(0, rgbLed.Color(0, 0, 255)); rgbLed.show();

  u8g2.begin(); u8g2.clearBuffer(); u8g2.setFont(u8g2_font_ncenB08_tr);
  u8g2.drawStr(OFFSET_X, OFFSET_Y + 15, "Pornire WiFi..."); u8g2.sendBuffer();

  sen66.begin(Wire, 0x6B); sen66.deviceReset(); delay(1000); sen66.startContinuousMeasurement();

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  WiFi.setSleep(false); 
  while (WiFi.status() != WL_CONNECTED) delay(500);
  configTzTime(TZ_INFO, ntpServer); 

  u8g2.clearBuffer(); u8g2.drawStr(OFFSET_X, OFFSET_Y + 15, "Conectare Cloud..."); u8g2.sendBuffer();

  config.api_key = API_KEY; 
  config.database_url = DATABASE_URL; 
  config.timeout.serverResponse = 10 * 1000;
  config.tcp_data_sending_retry = 5;
  if (Firebase.signUp(&config, &auth, "", "")) {
    Serial.println("Conectat Anonim la Firebase.");
    firebaseGata = true;
  }
  config.token_status_callback = tokenStatusCallback; 
  fbdoScriere.setBSSLBufferSize(4096, 2048); 
  fbdoCitire.setBSSLBufferSize(4096, 1024);
  Firebase.begin(&config, &auth); 
  Firebase.reconnectWiFi(true);

  esp_task_wdt_config_t wdt_config = {
      .timeout_ms = 30000, 
      .idle_core_mask = (1 << portNUM_PROCESSORS) - 1, 
      .trigger_panic = true
  };
  if (esp_task_wdt_init(&wdt_config) == ESP_ERR_INVALID_STATE) esp_task_wdt_reconfigure(&wdt_config);
  esp_task_wdt_add(NULL);

  ArduinoOTA.setHostname("SmartPurifier-ESP32");
  ArduinoOTA.setPassword("YOUR_OTA_PASSWORD");
  ArduinoOTA.onStart([]() { esp_task_wdt_delete(NULL); });
  ArduinoOTA.begin();

  delay(3000); 
}

void loop() {
  ArduinoOTA.handle();        
  esp_task_wdt_reset(); 

  unsigned long t = millis(); 
  time_t tNow; time(&tNow);
  struct tm timeinfo; localtime_r(&tNow, &timeinfo);

  if (WiFi.status() != WL_CONNECTED) {
      if (!wifiAvertizat) { wifiAvertizat = true; WiFi.reconnect(); }
  } else {
      if (wifiAvertizat) { logSystem("Conexiune WiFi restabilita"); wifiAvertizat = false; }
  }

  // --- SINCRONIZAREA INIȚIALĂ ---
  if (Firebase.ready() && firebaseGata && !istoricRecuperat) {
      u8g2.clearBuffer(); u8g2.drawStr(OFFSET_X, OFFSET_Y + 15, "Sincronizare DB..."); u8g2.sendBuffer();
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/ErrorLog")) jurnalErori = fbdoCitire.stringData();
      if (Firebase.RTDB.getInt(&fbdoCitire, "/SenzoriAer/last_history_time")) lastIstoricSaveTime = fbdoCitire.intData();
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/last_archived_date")) lastArchivedDateStr = fbdoCitire.stringData(); 
      
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_PM25")) istPM25 = fbdoCitire.stringData();
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_CO2")) istCO2 = fbdoCitire.stringData();
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_VOC")) istVOC = fbdoCitire.stringData();
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_NOx")) istNOx = fbdoCitire.stringData();
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_Temp")) istTemp = fbdoCitire.stringData();
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_Umid")) istUmid = fbdoCitire.stringData();
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_PM1")) istPM1 = fbdoCitire.stringData();
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_PM4")) istPM4 = fbdoCitire.stringData();
      if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_PM10")) istPM10 = fbdoCitire.stringData();
      istoricRecuperat = true;
      return; 
  }

  if (!istoricRecuperat) return; 

  if (!bootLogSent && tNow > 1000000000) {
      String motiv = getResetReasonString();
      logSystem("Sistem Repornit: " + motiv);
      bootLogSent = true;
  }

  // --- Calcul Turație RPM ---
  if (t - tAntRpm >= 1000) {
      static unsigned int pulsuriTrecute[3] = {0, 0, 0};
      static byte indexPuls = 0;

      unsigned int pulsuriAcum = validPulseCount;
      validPulseCount = 0; 

      if (currentFanSpeed < 2) {
         rpmReal = 0;
         pulsuriTrecute[0] = 0; pulsuriTrecute[1] = 0; pulsuriTrecute[2] = 0; 
      } else {
         pulsuriTrecute[indexPuls] = pulsuriAcum;
         indexPuls++;
         if (indexPuls > 2) indexPuls = 0;

         unsigned int total3Sec = pulsuriTrecute[0] + pulsuriTrecute[1] + pulsuriTrecute[2];
         rpmReal = total3Sec * 10;
      }
      
      tAntRpm = t;
  }

  // --- Citire SEN66 ---
  if (t - tAntSenzor >= 1000) {
      tAntSenzor = t;
      float t_pm1=0, t_pm25=0, t_pm4=0, t_pm10=0, t_umid=0, t_temp=0, t_voc=0, t_nox=0; uint16_t t_co2=0;
      uint16_t error = sen66.readMeasuredValues(t_pm1, t_pm25, t_pm4, t_pm10, t_umid, t_temp, t_voc, t_nox, t_co2);
      if (error) {
          if (!senzorAvertizat) { logSystem("Eroare citire SEN66: Cod " + String(error)); senzorAvertizat = true; }
      } else {
          if (senzorAvertizat) { logSystem("Comunicare SEN66 restabilita"); senzorAvertizat = false; }
          if (t_co2 != 65535) { 
              pm1=t_pm1; pm25=t_pm25; pm4=t_pm4; pm10=t_pm10; umiditate=t_umid; temp=t_temp; voc=t_voc; nox=t_nox; co2=t_co2; areDate = true;
              sumPM1 += pm1; sumPM25 += pm25; sumPM4 += pm4; sumPM10 += pm10; sumTemp += temp; sumUmid += umiditate; 
              if (voc <= 1000) sumVOC += voc; else sumVOC += 0;
              if (nox <= 1000) sumNOx += nox; else sumNOx += 0;
              sumCO2 += co2; countAveraging++;
          }
      }
  }

  // --- Ascultare Comenzi Telefon ---
  if (Firebase.ready() && (t - tAntFB >= 2000)) {
      tAntFB = t;
      if (Firebase.RTDB.getInt(&fbdoCitire, "/SenzoriAer/speed")) {
          int appSpeed = fbdoCitire.intData();
          if (appSpeed == 0) { 
              isAutoMode = true;
              if (areDate) { 
                  int vitezaDorita = 100;
                  if (pm1 <= 0.2 && pm25 <= 0.2 && pm4 <= 0.2 && pm10 <= 0.2) vitezaDorita = 25; 
                  else if (pm25 < 12.0) vitezaDorita = 35; 
                  else if (pm25 < 35.0) vitezaDorita = 60; 

                  if (!eraAutoInainte) { vitezaAutoCurenta = vitezaDorita; timpUltimaSchimbareAuto = t; } 
                  else {
                      if (vitezaDorita > vitezaAutoCurenta) { vitezaAutoCurenta = vitezaDorita; timpUltimaSchimbareAuto = t; } 
                      else if (vitezaDorita < vitezaAutoCurenta) { if (t - timpUltimaSchimbareAuto >= 300000) { vitezaAutoCurenta = vitezaDorita; timpUltimaSchimbareAuto = t; } }
                  }
                  eraAutoInainte = true; targetFanSpeed = vitezaAutoCurenta;
              }
          } else { isAutoMode = false; eraAutoInainte = false; targetFanSpeed = appSpeed; }
      }
      
      if (Firebase.RTDB.getBool(&fbdoCitire, "/SenzoriAer/comenzi/curatare")) {
          if (fbdoCitire.boolData()) {
              Firebase.RTDB.setBool(&fbdoScriere, "/SenzoriAer/comenzi/curatare", false);
              u8g2.clearBuffer(); u8g2.setFont(u8g2_font_ncenB08_tr); 
              u8g2.drawStr(OFFSET_X + 10, OFFSET_Y + 25, "CURATARE SENZOR"); u8g2.sendBuffer();
              sen66.stopMeasurement(); delay(100); sen66.startFanCleaning();
              unsigned long tCuratare = millis();
              while(millis() - tCuratare < 10000) { ArduinoOTA.handle(); esp_task_wdt_reset(); delay(10); }
              sen66.startContinuousMeasurement();
          }
      }
      if (Firebase.RTDB.getBool(&fbdoCitire, "/SenzoriAer/comenzi/restart")) {
          if (fbdoCitire.boolData()) { Firebase.RTDB.setBool(&fbdoScriere, "/SenzoriAer/comenzi/restart", false); ESP.restart(); }
      }
  }

  // --- Fading Lin ---
  if (t - tAntFading >= 200) { 
      tAntFading = t;
      if (currentFanSpeed != targetFanSpeed) { 
          if (currentFanSpeed < targetFanSpeed) currentFanSpeed++; else currentFanSpeed--;                                  
          ledcWrite(PIN_FAN_PWM, map(currentFanSpeed, 0, 100, 0, 255)); 
      }
  }

  // --- Control Ecran ---
  if (t - tAntEcran >= 4000) {
      tAntEcran = t;
      bool nightModeApp = false;
      if (Firebase.ready()) if (Firebase.RTDB.getBool(&fbdoCitire, "/SenzoriAer/setari/night_mode")) nightModeApp = fbdoCitire.boolData();

      if (nightModeApp) { u8g2.clearBuffer(); u8g2.sendBuffer(); rgbLed.clear(); rgbLed.show(); } 
      else {
          if (pm25 >= 55 || co2 >= 1500 || voc >= 400 || nox >= 300) rgbLed.setPixelColor(0, rgbLed.Color(255, 0, 0)); 
          else if (pm25 >= 35 || co2 >= 1000 || voc >= 250 || nox >= 150) rgbLed.setPixelColor(0, rgbLed.Color(255, 100, 0)); 
          else if (pm25 >= 12 || co2 >= 800 || voc >= 150 || nox >= 20) rgbLed.setPixelColor(0, rgbLed.Color(255, 255, 0)); 
          else rgbLed.setPixelColor(0, rgbLed.Color(0, 255, 0)); 
          rgbLed.show();
          paginaCurenta++; if(paginaCurenta > 4) paginaCurenta = 1; deseneazaPagina(paginaCurenta); 
      }
  }

  // --- Live Update ---
  unsigned long intervalTrimitere = (currentFanSpeed != targetFanSpeed) ? 1500 : 10000;
  if (areDate && Firebase.ready() && firebaseGata && (t - tAntLive >= intervalTrimitere)) {
      tAntLive = t; 
      FirebaseJson jsonLive; 
      jsonLive.set("last_update/.sv", "timestamp"); 
      jsonLive.set("RPM", rpmReal); 
      jsonLive.set("ProcentVentilator", targetFanSpeed);
      jsonLive.set("CO2", co2); jsonLive.set("VOC", (int)voc); jsonLive.set("NOx", (int)nox);
      jsonLive.set("PM1", pm1); jsonLive.set("PM25", pm25); jsonLive.set("PM4", pm4); jsonLive.set("PM10", pm10);
      jsonLive.set("Temperatura", temp); jsonLive.set("Umiditate", umiditate);
      Firebase.RTDB.updateNodeAsync(&fbdoScriere, "/SenzoriAer", &jsonLive);
  }

  // --- SALVARI SI ARHIVARE CHIRURGICALA ---
  if (timeinfo.tm_year > 120 && Firebase.ready()) { 
      int curentMin = timeinfo.tm_min; int curentHour = timeinfo.tm_hour;

      if (curentHour % 6 == 0 && curentMin == 0) {
          if (!eroriCuratateLaOra) {
              eroriCuratateLaOra = true; String jurnalCurat = ""; int startIdx = 0;
              while(startIdx < jurnalErori.length()) {
                  int endIdx = jurnalErori.indexOf('|', startIdx); if(endIdx == -1) endIdx = jurnalErori.length();
                  String eroareTemp = jurnalErori.substring(startIdx, endIdx); int hashIdx = eroareTemp.indexOf('#');
                  if(hashIdx != -1) { long tsEroare = eroareTemp.substring(0, hashIdx).toInt(); if(tNow - tsEroare <= 86400) { if(jurnalCurat != "") jurnalCurat += "|"; jurnalCurat += eroareTemp; } }
                  startIdx = endIdx + 1;
              }
              if(jurnalCurat != jurnalErori) { jurnalErori = jurnalCurat; Firebase.RTDB.setStringAsync(&fbdoScriere, "/SenzoriAer/ErrorLog", jurnalErori); }
          }
      } else { eroriCuratateLaOra = false; }

      // ========================================================
      // LOGICA DE ARHIVARE ASINCRONA "CHIRURGICALA"
      // ========================================================
      char timeBuffToday[50]; strftime(timeBuffToday, sizeof(timeBuffToday), "%d_%m_%Y", &timeinfo);
      String todayStr = String(timeBuffToday);

      if (lastArchivedDateStr != "" && todayStr != lastArchivedDateStr) {
          // Aflam numele folderului de ieri
          time_t timpAcum = mktime(&timeinfo); timpAcum -= 86400; struct tm * timpIeri = localtime(&timpAcum);
          char timeBuffIeri[50]; strftime(timeBuffIeri, sizeof(timeBuffIeri), "%d_%m_%Y", timpIeri); 
          String nodBaza = "/SenzoriAer/Arhiva_Zilnica/" + String(timeBuffIeri);
          
          int minuteTrecuteAzi = curentHour * 60 + curentMin;
          
          // Extragem din RAM DOAR datele de ieri si le "reparam"
          FirebaseJson jsonArhiva; 
          jsonArhiva.set("PM25", extrageArhivaIeri(istPM25, minuteTrecuteAzi)); 
          jsonArhiva.set("CO2", extrageArhivaIeri(istCO2, minuteTrecuteAzi)); 
          jsonArhiva.set("VOC", extrageArhivaIeri(istVOC, minuteTrecuteAzi)); 
          jsonArhiva.set("NOx", extrageArhivaIeri(istNOx, minuteTrecuteAzi)); 
          jsonArhiva.set("Temp", extrageArhivaIeri(istTemp, minuteTrecuteAzi)); 
          jsonArhiva.set("Umid", extrageArhivaIeri(istUmid, minuteTrecuteAzi));
          
          Firebase.RTDB.setJSONAsync(&fbdoScriere, nodBaza, &jsonArhiva);
          
          lastArchivedDateStr = todayStr;
          Firebase.RTDB.setStringAsync(&fbdoScriere, "/SenzoriAer/last_archived_date", todayStr);
          logSystem("Arhiva pt " + String(timeBuffIeri) + " refacuta si salvata!");
      } 
      else if (lastArchivedDateStr == "") {
          lastArchivedDateStr = todayStr;
          Firebase.RTDB.setStringAsync(&fbdoScriere, "/SenzoriAer/last_archived_date", todayStr);
      }

      // ========================================================
      // SALVAREA DE RUTINA (5 MINUTE)
      // ========================================================
      if (curentMin % 5 == 0 && curentMin != lastSavedMin) {
          lastSavedMin = curentMin; int intervalePierdute = 0;
          if (lastIstoricSaveTime > 0) { intervalePierdute = ((tNow - lastIstoricSaveTime) / 300) - 1; if (intervalePierdute < 0) intervalePierdute = 0; if (intervalePierdute > 288) intervalePierdute = 288; }
          lastIstoricSaveTime = tNow;

          if (countAveraging == 0) { 
              sumPM1 = pm1; sumPM25 = pm25; sumPM4 = pm4; sumPM10 = pm10; sumTemp = temp; sumUmid = umiditate; sumCO2 = co2;
              sumVOC = (voc <= 1000) ? voc : 0; sumNOx = (nox <= 1000) ? nox : 0; countAveraging = 1; 
          }

          float avgPM1 = sumPM1 / countAveraging; float avgPM25 = sumPM25 / countAveraging; float avgPM4 = sumPM4 / countAveraging; float avgPM10 = sumPM10 / countAveraging;
          float avgTemp = sumTemp / countAveraging; float avgUmid = sumUmid / countAveraging; float avgVOC = sumVOC / countAveraging; float avgNOx = sumNOx / countAveraging; int avgCO2 = sumCO2 / countAveraging;

          String strNOx = (avgNOx == 0 && nox > 1000) ? "-999" : String((int)avgNOx); String strVOC = (avgVOC == 0 && voc > 1000) ? "-999" : String((int)avgVOC);

          istPM25 = adaugaInIstoric(istPM25, String(avgPM25, 1), intervalePierdute); istCO2 = adaugaInIstoric(istCO2, String(avgCO2), intervalePierdute);
          istVOC = adaugaInIstoric(istVOC, strVOC, intervalePierdute); istNOx = adaugaInIstoric(istNOx, strNOx, intervalePierdute);
          istTemp = adaugaInIstoric(istTemp, String(avgTemp, 1), intervalePierdute); istUmid = adaugaInIstoric(istUmid, String(avgUmid, 1), intervalePierdute);
          istPM1 = adaugaInIstoric(istPM1, String(avgPM1, 1), intervalePierdute); istPM4 = adaugaInIstoric(istPM4, String(avgPM4, 1), intervalePierdute); istPM10 = adaugaInIstoric(istPM10, String(avgPM10, 1), intervalePierdute);

          sumPM1 = 0; sumPM25 = 0; sumPM4 = 0; sumPM10 = 0; sumTemp = 0; sumUmid = 0; sumVOC = 0; sumNOx = 0; sumCO2 = 0; countAveraging = 0;

          FirebaseJson jsonIstoric; jsonIstoric.set("Istoric_PM25", istPM25); jsonIstoric.set("Istoric_CO2", istCO2); jsonIstoric.set("Istoric_VOC", istVOC); jsonIstoric.set("Istoric_NOx", istNOx);
          jsonIstoric.set("Istoric_Temp", istTemp); jsonIstoric.set("Istoric_Umid", istUmid); jsonIstoric.set("Istoric_PM1", istPM1); jsonIstoric.set("Istoric_PM4", istPM4); jsonIstoric.set("Istoric_PM10", istPM10);
          jsonIstoric.set("last_history_time", lastIstoricSaveTime);
          Firebase.RTDB.updateNodeAsync(&fbdoScriere, "/SenzoriAer", &jsonIstoric);
      }
  }
}