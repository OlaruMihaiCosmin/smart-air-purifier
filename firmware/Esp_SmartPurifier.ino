#include <Arduino.h>
#include <WiFi.h>
#include <Firebase_ESP_Client.h>
#include <Adafruit_NeoPixel.h>
#include <Wire.h>
#include <U8g2lib.h>
#include <SensirionI2cSen66.h>
#include <Preferences.h>
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
#include <math.h>

// ================= DATELE FIREBASE =================
#define WIFI_SSID "YOUR_WIFI_SSID"
#define WIFI_PASSWORD "YOUR_WIFI_PASS"
#define API_KEY "YOUR_FIREBASE_API_KEY"
#define DATABASE_URL "https://YOUR_PROJECT-default-rtdb.firebasedatabase.app/" 
// ===================================================

int OFFSET_X = 2; 
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

U8G2_SH1106_128X64_NONAME_F_HW_I2C u8g2(U8G2_R2, /* reset=*/ U8X8_PIN_NONE);
Adafruit_NeoPixel rgbLed(1, PIN_LED_RGB, NEO_GRB + NEO_KHZ800);
SensirionI2cSen66 sen66;

FirebaseData fbdoScriere, fbdoCitire;
FirebaseAuth auth;
FirebaseConfig config;
bool firebaseGata = false;

Preferences stocare;

bool istoricRecuperat = false;
bool bootLogSent = false; 
int lastSavedMin = -1;
String lastArchivedDateStr = ""; 
long lastIstoricSaveTime = 0; 
unsigned long tAntSenzor = 0, tAntFB = 0, tAntEcran = 0, tAntLive = 0, tAntFading = 0, tAntRpm = 0, tAntWifi = 0, tAntVocSave = 0;

// WATCHDOG SENZOR
unsigned long tUltimaCitireReusita = 0; 

int paginaCurenta = 1;
bool areDate = false;

int vitezaDinAplicatie = 0; 
int targetFanSpeed = 0;   
int currentFanSpeed = 0;  
int rpmReal = 0; 

float offsetTemperatura = -1.0;

// =========================================================================
// MAGIA DE DEBUG: MEMORIA RTC NO-INIT
// =========================================================================
RTC_NOINIT_ATTR char rtc_last_state[64]; 
String motivRestart = ""; 

void updateState(const char* state) {
  strncpy(rtc_last_state, state, sizeof(rtc_last_state) - 1);
  rtc_last_state[sizeof(rtc_last_state) - 1] = '\0';
  if (Serial) { 
      Serial.print(">> Executing: "); Serial.println(rtc_last_state);
  }
}

String getResetReasonString() {
  esp_reset_reason_t reason = esp_reset_reason();
  switch (reason) {
    case ESP_RST_POWERON:   return "Pornit la Priza";
    case ESP_RST_SW:        return "Restart din Aplicatie / OTA";
    case ESP_RST_PANIC:     return String("Eroare Fatala (Panic) in: ") + String(rtc_last_state);
    case ESP_RST_INT_WDT:   return String("Blocat! (Interrupt WDT) in: ") + String(rtc_last_state);
    case ESP_RST_TASK_WDT:  return String("Blocat! (Task WDT) in: ") + String(rtc_last_state);
    case ESP_RST_WDT:       return String("Blocat! (Other WDT) in: ") + String(rtc_last_state);
    case ESP_RST_BROWNOUT:  return "Cadere de Tensiune (Brownout)";
    case 11:                return "Restart prin Cablu USB (Upload/Serial Monitor)";
    default:                return "Motiv Necunoscut (" + String(reason) + ")";
  }
}

void clearI2CBus(int sdaPin, int sclPin) {
  pinMode(sdaPin, INPUT_PULLUP);
  pinMode(sclPin, INPUT_PULLUP);
  delay(10);
  if (digitalRead(sdaPin) == LOW) {
    if (Serial) Serial.println("I2C Bus Blocat! Incerc deblocarea...");
    pinMode(sclPin, OUTPUT);
    for (int i = 0; i < 9; i++) {
      digitalWrite(sclPin, LOW); delayMicroseconds(20);
      digitalWrite(sclPin, HIGH); delayMicroseconds(20);
    }
  }
  pinMode(sdaPin, INPUT); pinMode(sclPin, INPUT);
}

String getPaddedHistory(String istoric, time_t tNow, long lastSave) {
    if (lastSave <= 0) return istoric;
    int secundePierdute = tNow - lastSave;
    int intervalePierdute = (secundePierdute / 300) - 1;
    if (intervalePierdute <= 0) return istoric;
    if (intervalePierdute > 288) intervalePierdute = 288;
    String rezultat = istoric;
    for(int i = 0; i < intervalePierdute; i++) {
        if (rezultat != "") rezultat += ",";
        rezultat += "-999";
    }
    int numarVirgule = 0;
    for (int i = 0; i < rezultat.length(); i++) { if (rezultat[i] == ',') numarVirgule++; }
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
        for(int i=1; i<288; i++) gol += ",-999";
        return gol;
    }
    
    int currentIndex = 0;
    for (int i = 0; i < puncteIeriRamase; i++) {
        int nextComma = dateCurente.indexOf(',', currentIndex);
        if (nextComma == -1) { currentIndex = dateCurente.length() + 1; break; }
        currentIndex = nextComma + 1;
    }
    
    String valoriIeri = dateCurente;
    if (currentIndex > 0 && currentIndex <= dateCurente.length()) { 
        valoriIeri = dateCurente.substring(0, currentIndex - 1); 
    }
    
    String arhivaReconstruita = "";
    for (int i = 0; i < puncteAzi; i++) { arhivaReconstruita += "-999,"; }
    arhivaReconstruita += valoriIeri;
    
    return arhivaReconstruita;
}

// =========================================================================
// FILTRUL ORIGINAL RPM
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
  if (lowSamples >= 4 && tachoState == HIGH) { tachoState = LOW; validPulseCount++; } 
  else if (highSamples >= 4 && tachoState == LOW) { tachoState = HIGH; }
}

bool isAutoMode = false;
unsigned long timpUltimaSchimbareAuto = 0;
int vitezaAutoCurenta = 0;
bool eraAutoInainte = false;

float pm1=0, pm25=0, pm4=0, pm10=0, umiditate=0, temp=0, voc=0, nox=0;
uint16_t co2=0;
float sumPM1 = 0, sumPM25 = 0, sumPM4 = 0, sumPM10 = 0, sumTemp = 0, sumUmid = 0, sumVOC = 0, sumNOx = 0;
unsigned long sumCO2 = 0;

int countAveraging = 0, countVOC = 0, countNOx = 0;

String istPM25="", istCO2="", istVOC="", istNOx="", istTemp="", istUmid="", istPM1="", istPM4="", istPM10="";
String jurnalErori = "";
bool eroriCuratateLaOra = false, wifiAvertizat = false, senzorAvertizat = false;

void logSystem(String mesaj) {
  if (Serial) Serial.println(mesaj);
  if (!firebaseGata) return; 
  time_t now; time(&now);
  
  String tsStr = (now < 1000000000) ? "999999999" : String(now); 
  String intrare = tsStr + "#" + mesaj;
  
  if (jurnalErori != "") jurnalErori += "|";
  jurnalErori += intrare;
  
  int nrErori = 0;
  for(int i=0; i<jurnalErori.length(); i++) { if(jurnalErori[i] == '|') nrErori++; }
  while(nrErori >= 30) {
      int idx = jurnalErori.indexOf('|');
      if(idx != -1) jurnalErori.remove(0, idx + 1);
      nrErori--;
  }
  Firebase.RTDB.setStringAsync(&fbdoScriere, "/SenzoriAer/ErrorLog", jurnalErori);
}

void adaugaInIstoric(String &istoricActual, String valoareNoua, int puncteLipsa) {
  for(int i = 0; i < puncteLipsa; i++) { 
      if (istoricActual.length() > 0) istoricActual += ","; 
      istoricActual += "-999"; 
  }
  if (istoricActual.length() > 0) istoricActual += ",";
  istoricActual += valoareNoua;
  
  int numarVirgule = 0;
  for (int i = 0; i < istoricActual.length(); i++) { if (istoricActual[i] == ',') numarVirgule++; }
  while (numarVirgule >= 288) { 
    int idx = istoricActual.indexOf(',');
    if (idx != -1) istoricActual.remove(0, idx + 1); 
    else break;
    numarVirgule--;
  }
}

void deseneazaPagina(int pag) {
  u8g2.clearBuffer(); u8g2.setFont(u8g2_font_ncenB08_tr); 
  if (pag == 1) { 
      u8g2.drawStr(OFFSET_X, OFFSET_Y + 12, "== SYSTEM STATUS =="); u8g2.setCursor(OFFSET_X, OFFSET_Y + 30); 
      u8g2.print("Putere: "); u8g2.print(currentFanSpeed); u8g2.print("%"); 
      if (isAutoMode) { u8g2.print(" (Auto)"); } else { u8g2.print(" (Man)"); }
      u8g2.setCursor(OFFSET_X, OFFSET_Y + 45); u8g2.print("RPM Real: "); u8g2.print(rpmReal);
  } else if (pag == 2) { 
      u8g2.drawStr(OFFSET_X, OFFSET_Y + 12, "== PRAF (ug/m3) =="); u8g2.setCursor(OFFSET_X, OFFSET_Y + 30); u8g2.print("PM1: "); u8g2.print(pm1, 1);
      u8g2.setCursor(OFFSET_X + 64, OFFSET_Y + 30); u8g2.print("PM2.5: "); u8g2.print(pm25, 1);
      u8g2.setCursor(OFFSET_X, OFFSET_Y + 50); u8g2.print("PM4: "); u8g2.print(pm4, 1);
      u8g2.setCursor(OFFSET_X + 64, OFFSET_Y + 50); u8g2.print("PM10: "); u8g2.print(pm10, 1);
  } else if (pag == 3) { 
      u8g2.drawStr(OFFSET_X, OFFSET_Y + 12, "== GAZE (ppm/idx) =="); u8g2.setCursor(OFFSET_X, OFFSET_Y + 30); u8g2.print("CO2 : "); u8g2.print(co2);
      u8g2.setCursor(OFFSET_X, OFFSET_Y + 45); u8g2.print("VOC : "); u8g2.print((int)voc);
      u8g2.setCursor(OFFSET_X, OFFSET_Y + 60); if (nox > 1000) u8g2.print("NOx : Init..."); else { u8g2.print("NOx : "); u8g2.print((int)nox); }
  } else if (pag == 4) { 
      u8g2.drawStr(OFFSET_X + 16, OFFSET_Y + 12, "== AMBIENTA =="); u8g2.setCursor(OFFSET_X, OFFSET_Y + 30); u8g2.print("Temp: "); u8g2.print(temp, 1); u8g2.print(" C");
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
  
  Serial.setTxTimeoutMs(0); 
  
  if (esp_reset_reason() == ESP_RST_POWERON || esp_reset_reason() == ESP_RST_BROWNOUT) { 
      strcpy(rtc_last_state, "Sistem_Pornit_Priza"); 
  }
  motivRestart = getResetReasonString();

  istPM25.reserve(1500); istCO2.reserve(1500); istVOC.reserve(1500);
  istNOx.reserve(1500); istTemp.reserve(1500); istUmid.reserve(1500);
  istPM1.reserve(1500); istPM4.reserve(1500); istPM10.reserve(1500);
  jurnalErori.reserve(2000);
  
  ledcAttach(PIN_FAN_PWM, PWM_FRECVENTA, PWM_REZOLUTIE); ledcWrite(PIN_FAN_PWM, 0); 
  
  pinMode(PIN_FAN_RPM, INPUT); 
  const esp_timer_create_args_t tacho_timer_args = { .callback = &tachoPoll, .name = "tacho_poll" };
  esp_timer_create(&tacho_timer_args, &tacho_timer); 
  esp_timer_start_periodic(tacho_timer, 250); 
  
  clearI2CBus(I2C_SDA, I2C_SCL);
  Wire.begin(I2C_SDA, I2C_SCL); 
  Wire.setTimeout(1000); 

  rgbLed.begin(); rgbLed.setBrightness(150); rgbLed.setPixelColor(0, rgbLed.Color(0, 0, 255)); rgbLed.show();
  u8g2.begin(); u8g2.clearBuffer(); u8g2.setFont(u8g2_font_ncenB08_tr); u8g2.drawStr(OFFSET_X, OFFSET_Y + 15, "Pornire Sistem..."); u8g2.sendBuffer();

  stocare.begin("smart_air", false);
  offsetTemperatura = stocare.getFloat("tempOffset", 0.0);

  // START SECVENTA DATASHEET INITIALIZARE SENZOR
  sen66.begin(Wire, 0x6B); 
  sen66.deviceReset(); 
  delay(1200); 

  uint8_t vocState[8];
  if (stocare.getBytesLength("vocState") == 8) {
      stocare.getBytes("vocState", vocState, 8);
      uint16_t errVoc = sen66.setVocAlgorithmState(vocState, 8);
      delay(20);
      if (errVoc == 0 && Serial) Serial.println(">> VOC State Incarcat din Flash cu succes!");
  }

  sen66.startContinuousMeasurement();
  delay(50); 
  
  sen66.setTemperatureOffsetParameters((int16_t)(offsetTemperatura * 200), 0, 0, 0);
  delay(20); 

  tUltimaCitireReusita = millis(); 

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD); WiFi.setSleep(false); 
  
  int conectareTries = 0;
  while (WiFi.status() != WL_CONNECTED && conectareTries < 15) { delay(500); conectareTries++; }

  esp_task_wdt_config_t wdt_config = { .timeout_ms = 90000, .idle_core_mask = (1 << portNUM_PROCESSORS) - 1, .trigger_panic = true };
  if (esp_task_wdt_init(&wdt_config) == ESP_ERR_INVALID_STATE) esp_task_wdt_reconfigure(&wdt_config);
  esp_task_wdt_add(NULL);

  ArduinoOTA.setHostname("SmartPurifier"); ArduinoOTA.setPassword("YOUR_OTA_PASSWORD");
  ArduinoOTA.onStart([]() { esp_task_wdt_delete(NULL); }); ArduinoOTA.begin();

  delay(2000); 
}

void loop() {
  ArduinoOTA.handle();        
  esp_task_wdt_reset(); 

  unsigned long t = millis(); 
  time_t tNow; time(&tNow);
  struct tm timeinfo; localtime_r(&tNow, &timeinfo);

  if (WiFi.status() != WL_CONNECTED) { 
      if (t - tAntWifi >= 30000) { 
          tAntWifi = t;
          if (!wifiAvertizat) { wifiAvertizat = true; updateState("Pierdut_WiFi"); }
          WiFi.disconnect(); WiFi.reconnect();  
      }
  } 
  else { 
      if (wifiAvertizat) { 
          logSystem("Conexiune WiFi restabilita"); 
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
          while (time(&testT) < 1000000000 && retries < 10) { delay(500); retries++; esp_task_wdt_reset(); yield(); }

          config.api_key = API_KEY; config.database_url = DATABASE_URL; 
          config.timeout.serverResponse = 3 * 1000; config.timeout.socketConnection = 3 * 1000; config.tcp_data_sending_retry = 1;        

          if (Firebase.signUp(&config, &auth, "", "")) { 
              config.token_status_callback = tokenStatusCallback; 
              fbdoScriere.setBSSLBufferSize(2048, 1024); fbdoCitire.setBSSLBufferSize(2048, 1024);
              Firebase.begin(&config, &auth); 
              Firebase.reconnectWiFi(true); 
              firebaseInitializat = true; firebaseGata = true;
          }
          updateState("Running_Loop");
      }
  }

  if (t - tAntRpm >= 1000) {
      static unsigned int pulsuriTrecute[3] = {0, 0, 0}; static byte indexPuls = 0;
      
      noInterrupts();
      unsigned int pulsuriAcum = validPulseCount; 
      validPulseCount = 0; 
      interrupts();
      
      if (currentFanSpeed < 2) { rpmReal = 0; pulsuriTrecute[0] = 0; pulsuriTrecute[1] = 0; pulsuriTrecute[2] = 0; } 
      else { 
        pulsuriTrecute[indexPuls] = pulsuriAcum; 
        indexPuls++; 
        if (indexPuls > 2) indexPuls = 0; 
        rpmReal = (pulsuriTrecute[0] + pulsuriTrecute[1] + pulsuriTrecute[2]) * 10; 
      }
      tAntRpm = t;
  }

  // ======================================================================================
  // CITIRE SENZOR - 1000ms conform Datasheet (New readings available every second)
  // ======================================================================================
  if (t - tAntSenzor >= 1000) {
      tAntSenzor = t; 
      updateState("Citire_SEN66");
      float t_pm1=0, t_pm25=0, t_pm4=0, t_pm10=0, t_umid=0, t_temp=0, t_voc=0, t_nox=0; uint16_t t_co2=0;
      
      uint16_t error = sen66.readMeasuredValues(t_pm1, t_pm25, t_pm4, t_pm10, t_umid, t_temp, t_voc, t_nox, t_co2);
      
      if (error) { 
          if (!senzorAvertizat) { logSystem("Eroare citire SEN66: Cod " + String(error)); senzorAvertizat = true; } 
      } 
      else {
          if (senzorAvertizat) { logSystem("Comunicare SEN66 restabilita"); senzorAvertizat = false; }
          
          tUltimaCitireReusita = t; 
          
          if (t_co2 != 65535) { 
              temp = t_temp; 
              umiditate = t_umid;
              pm1=t_pm1; pm25=t_pm25; pm4=t_pm4; pm10=t_pm10; voc=t_voc; nox=t_nox; co2=t_co2; 
              areDate = true; 
              
              if (millis() > 30000) {
                  sumPM1 += pm1; sumPM25 += pm25; sumPM4 += pm4; sumPM10 += pm10; sumTemp += temp; sumUmid += umiditate; sumCO2 += co2; 
                  countAveraging++; 
                  if (voc <= 1000) { sumVOC += voc; countVOC++; }
                  if (nox <= 1000) { sumNOx += nox; countNOx++; }
              }
          } else {
              areDate = false; 
          }
      }
      updateState("Running_Loop");
  }

  // ======================================================================================
  // WATCHDOG SENZOR
  // ======================================================================================
  if (t - tUltimaCitireReusita > 15000) {
      tUltimaCitireReusita = t; 
      areDate = false; 
      
      logSystem("WDT: Senzorul SEN66 a blocat citirile de 15s. Reinitializare...");
      updateState("Reset_Senzor_Hardware");
      
      sen66.stopMeasurement();
      unsigned long tStop = millis();
      while(millis() - tStop < 1500) { esp_task_wdt_reset(); yield(); } 
      
      clearI2CBus(I2C_SDA, I2C_SCL);
      Wire.begin(I2C_SDA, I2C_SCL);
      Wire.setTimeout(1000);
      sen66.begin(Wire, 0x6B);
      sen66.deviceReset();
      
      unsigned long tReset = millis();
      while(millis() - tReset < 1200) { esp_task_wdt_reset(); yield(); } 
      
      uint8_t vocState[8];
      if (stocare.getBytesLength("vocState") == 8) {
          stocare.getBytes("vocState", vocState, 8);
          sen66.setVocAlgorithmState(vocState, 8);
          tReset = millis(); while(millis() - tReset < 20) { yield(); } 
      }
      
      sen66.startContinuousMeasurement();
      tReset = millis(); while(millis() - tReset < 50) { yield(); } 
      
      sen66.setTemperatureOffsetParameters((int16_t)(offsetTemperatura * 200), 0, 0, 0);
      tReset = millis(); while(millis() - tReset < 20) { yield(); } 
      
      updateState("Running_Loop");
  }

  // ======================================================================================
  // SALVARE VOC - Executata "din zbor", FARA stopMeasurement() (Datasheet Pag. 46)
  // ======================================================================================
  if (areDate && (t - tAntVocSave >= 14400000)) { 
      tAntVocSave = t; updateState("Salvare_Calibrare_VOC");
      
      uint8_t currentVocState[8];
      if (sen66.getVocAlgorithmState(currentVocState, 8) == 0) {
          stocare.putBytes("vocState", currentVocState, 8);
          logSystem("Calibrare VOC extrasa si salvata.");
      } else {
          logSystem("Eroare la extragerea VOC State.");
      }
      
      updateState("Running_Loop");
  }

  bool comandaEsteAuto = (vitezaDinAplicatie == 0);
  if (WiFi.status() != WL_CONNECTED) { if (!comandaEsteAuto && vitezaDinAplicatie > 5) { comandaEsteAuto = true; } }
  isAutoMode = comandaEsteAuto; 

  if (isAutoMode) {
      if (areDate) { 
          int vitezaDorita = 100;
          if (pm1 <= 0.3 && pm25 <= 0.3 && pm4 <= 0.3 && pm10 <= 0.3) vitezaDorita = 25; 
          else if (pm25 < 12.0) vitezaDorita = 35; 
          else if (pm25 < 35.0) vitezaDorita = 60; 
          
          if (!eraAutoInainte) { vitezaAutoCurenta = vitezaDorita; timpUltimaSchimbareAuto = t; } 
          else {
              if (vitezaDorita > vitezaAutoCurenta) { vitezaAutoCurenta = vitezaDorita; timpUltimaSchimbareAuto = t; } 
              else if (vitezaDorita < vitezaAutoCurenta) { if (t - timpUltimaSchimbareAuto >= 300000) { vitezaAutoCurenta = vitezaDorita; timpUltimaSchimbareAuto = t; } }
          }
          eraAutoInainte = true; targetFanSpeed = vitezaAutoCurenta;
      }
  } else { eraAutoInainte = false; targetFanSpeed = vitezaDinAplicatie; }

  if (t - tAntFading >= 200) { 
      tAntFading = t;
      if (currentFanSpeed != targetFanSpeed) { 
          if (currentFanSpeed < targetFanSpeed) currentFanSpeed++; else currentFanSpeed--;                                  
          ledcWrite(PIN_FAN_PWM, map(currentFanSpeed, 0, 100, 0, 255)); 
      }
  }

  if (t - tAntEcran >= 4000) {
      tAntEcran = t; updateState("Control_Ecran");
      bool nightModeApp = false;
      
      esp_task_wdt_reset(); yield();
      if (WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata) {
          if (Firebase.RTDB.getBool(&fbdoCitire, "/SenzoriAer/setari/night_mode")) nightModeApp = fbdoCitire.boolData();
      }

      if (nightModeApp) { u8g2.clearBuffer(); u8g2.sendBuffer(); rgbLed.clear(); rgbLed.show(); } 
      else {
          if (pm25 >= 55 || co2 >= 1500 || voc >= 400 || nox >= 300) rgbLed.setPixelColor(0, rgbLed.Color(255, 0, 0)); 
          else if (pm25 >= 35 || co2 >= 1000 || voc >= 250 || nox >= 150) rgbLed.setPixelColor(0, rgbLed.Color(255, 100, 0)); 
          else if (pm25 >= 12 || co2 >= 800 || voc >= 150 || nox >= 20) rgbLed.setPixelColor(0, rgbLed.Color(255, 255, 0)); 
          else rgbLed.setPixelColor(0, rgbLed.Color(0, 255, 0)); 
          rgbLed.show(); paginaCurenta++; if(paginaCurenta > 4) paginaCurenta = 1; deseneazaPagina(paginaCurenta); 
      }
      updateState("Running_Loop");
  }

  if (!istoricRecuperat) {
      if (WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata) {
          u8g2.clearBuffer(); u8g2.drawStr(OFFSET_X, OFFSET_Y + 15, "Sincronizare DB..."); u8g2.sendBuffer();
          
          updateState("Sync_Jurnal"); 
          if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/ErrorLog")) jurnalErori = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
          if (!bootLogSent) { logSystem("Sistem Repornit: " + motivRestart); bootLogSent = true; esp_task_wdt_reset(); yield(); }
          updateState("Sync_Time"); if (Firebase.RTDB.getInt(&fbdoCitire, "/SenzoriAer/last_history_time")) lastIstoricSaveTime = fbdoCitire.intData(); esp_task_wdt_reset(); yield();
          updateState("Sync_Date"); if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/last_archived_date")) lastArchivedDateStr = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
          updateState("Sync_IstoricPM25"); if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_PM25")) istPM25 = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
          updateState("Sync_IstoricCO2"); if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_CO2")) istCO2 = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
          updateState("Sync_IstoricVOC"); if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_VOC")) istVOC = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
          updateState("Sync_IstoricNOx"); if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_NOx")) istNOx = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
          updateState("Sync_IstoricTemp"); if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_Temp")) istTemp = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
          updateState("Sync_IstoricUmid"); if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_Umid")) istUmid = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
          updateState("Sync_IstoricPM1"); if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_PM1")) istPM1 = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
          updateState("Sync_IstoricPM4"); if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_PM4")) istPM4 = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
          updateState("Sync_IstoricPM10"); if (Firebase.RTDB.getString(&fbdoCitire, "/SenzoriAer/Istoric_PM10")) istPM10 = fbdoCitire.stringData(); esp_task_wdt_reset(); yield();
          
          istoricRecuperat = true; updateState("Running_Loop");
      }
      return; 
  }

  if (WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata && (t - tAntFB >= 2000)) {
      tAntFB = t; updateState("Ascultare_Comenzi_FB");
      static int pasCitire = 0; 
      
      if (pasCitire == 0) {
          esp_task_wdt_reset(); yield(); 
          if (Firebase.RTDB.getInt(&fbdoCitire, "/SenzoriAer/speed")) { vitezaDinAplicatie = fbdoCitire.intData(); }
      }
      else if (pasCitire == 1) {
          esp_task_wdt_reset(); yield();
          if (Firebase.RTDB.getFloat(&fbdoCitire, "/SenzoriAer/setari/temp_offset")) {
              float newOffset = fbdoCitire.floatData();
              static bool offsetFortatLaBoot = false;

              if (!offsetFortatLaBoot || fabs(newOffset - offsetTemperatura) > 0.05) { 
                  offsetTemperatura = newOffset;
                  stocare.putFloat("tempOffset", offsetTemperatura);
                  
                  sen66.setTemperatureOffsetParameters((int16_t)(offsetTemperatura * 200), 0, 0, 0); 
                  
                  unsigned long tWait = millis();
                  while(millis() - tWait < 20) { yield(); } 
                  
                  if (offsetFortatLaBoot) {
                      logSystem("Offset Termic modificat cu succes: " + String(offsetTemperatura) + " C");
                  } else {
                      if (Serial) Serial.println("Offset sincronizat cu Firebase la boot: " + String(offsetTemperatura) + " C");
                  }
                  
                  offsetFortatLaBoot = true;
              }
          }
      }
      else if (pasCitire == 2) {
          esp_task_wdt_reset(); yield();
          if (Firebase.RTDB.getJSON(&fbdoCitire, "/SenzoriAer/comenzi")) {
              FirebaseJson &json = fbdoCitire.jsonObject();
              FirebaseJsonData jsonData;
              
              json.get(jsonData, "reset_calibrare");
              if (jsonData.success && jsonData.boolValue == true) {
                  Firebase.RTDB.setBool(&fbdoScriere, "/SenzoriAer/comenzi/reset_calibrare", false);
                  u8g2.clearBuffer(); u8g2.setFont(u8g2_font_ncenB08_tr); u8g2.drawStr(OFFSET_X + 10, OFFSET_Y + 25, "RESET CALIBRARI"); u8g2.sendBuffer();
                  logSystem("Comanda: Stergere Flash VOC si Restart");
                  stocare.remove("vocState"); delay(1000); ESP.restart(); 
              }

              json.get(jsonData, "curatare");
              if (jsonData.success && jsonData.boolValue == true) {
                  Firebase.RTDB.setBool(&fbdoScriere, "/SenzoriAer/comenzi/curatare", false);
                  u8g2.clearBuffer(); u8g2.setFont(u8g2_font_ncenB08_tr); u8g2.drawStr(OFFSET_X + 10, OFFSET_Y + 25, "CURATARE SENZOR"); u8g2.sendBuffer();
                  
                  logSystem("Curatare SEN66 Inceputa..."); 
                  
                  sen66.stopMeasurement(); 
                  unsigned long tCuratareStop = millis();
                  while(millis() - tCuratareStop < 1500) { esp_task_wdt_reset(); yield(); }
                  
                  sen66.startFanCleaning(); 
                  unsigned long tCuratare = millis();
                  while(millis() - tCuratare < 10000) { ArduinoOTA.handle(); esp_task_wdt_reset(); yield(); delay(10); }
                  
                  sen66.startContinuousMeasurement();
                  unsigned long tWait = millis();
                  while(millis() - tWait < 50) { esp_task_wdt_reset(); yield(); }
                  
                  logSystem("Curatare SEN66 Finalizata.");
              }

              json.get(jsonData, "restart");
              if (jsonData.success && jsonData.boolValue == true) {
                  Firebase.RTDB.setBool(&fbdoScriere, "/SenzoriAer/comenzi/restart", false); ESP.restart(); 
              }
          }
      }
      
      pasCitire++; if (pasCitire > 2) pasCitire = 0;
      esp_task_wdt_reset(); yield(); updateState("Running_Loop");
  }

  unsigned long intervalTrimitere = (currentFanSpeed != targetFanSpeed) ? 1500 : 10000;
  
  if (WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata && (t - tAntLive >= intervalTrimitere)) {
      tAntLive = t; updateState("Trimitere_Live_FB");
      
      FirebaseJson jsonLive; 
      jsonLive.set("last_update/.sv", "timestamp"); 
      jsonLive.set("RPM", rpmReal); 
      jsonLive.set("ProcentVentilator", targetFanSpeed);
      jsonLive.set("Telemetrie_RAM_Liber", ESP.getFreeHeap()); 
      jsonLive.set("Telemetrie_WiFi_RSSI", WiFi.RSSI());       
      jsonLive.set("Telemetrie_Uptime_Sec", (int)(millis() / 1000)); 
      jsonLive.set("Telemetrie_Temp_Core", temperatureRead()); 
      jsonLive.set("Setari_Offset_Temp", offsetTemperatura); 

      if (areDate) {
          jsonLive.set("CO2", co2); jsonLive.set("VOC", (int)voc); jsonLive.set("NOx", (int)nox); 
          jsonLive.set("PM1", pm1); jsonLive.set("PM25", pm25); jsonLive.set("PM4", pm4); jsonLive.set("PM10", pm10); 
          jsonLive.set("Temperatura", temp); jsonLive.set("Umiditate", umiditate);
      }

      Firebase.RTDB.updateNodeAsync(&fbdoScriere, "/SenzoriAer", &jsonLive);
      updateState("Running_Loop");
  }

  if (timeinfo.tm_year > 120 && istoricRecuperat) { 
      int curentMin = timeinfo.tm_min; int curentHour = timeinfo.tm_hour;
      
      if (curentHour % 6 == 0 && curentMin == 0 && WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata) {
          if (!eroriCuratateLaOra) {
              eroriCuratateLaOra = true; String jurnalCurat = ""; int startIdx = 0; updateState("Curatare_Erori_FB");
              while(startIdx < jurnalErori.length()) {
                  int endIdx = jurnalErori.indexOf('|', startIdx); if(endIdx == -1) endIdx = jurnalErori.length();
                  String eroareTemp = jurnalErori.substring(startIdx, endIdx); int hashIdx = eroareTemp.indexOf('#');
                  if(hashIdx != -1) { long tsEroare = eroareTemp.substring(0, hashIdx).toInt(); if(tNow - tsEroare <= 86400) { if(jurnalCurat != "") jurnalCurat += "|"; jurnalCurat += eroareTemp; } }
                  startIdx = endIdx + 1;
              }
              if(jurnalCurat != jurnalErori) { jurnalErori = jurnalCurat; Firebase.RTDB.setStringAsync(&fbdoScriere, "/SenzoriAer/ErrorLog", jurnalErori); }
              updateState("Running_Loop");
          }
      } else { eroriCuratateLaOra = false; }

      char timeBuffToday[50]; strftime(timeBuffToday, sizeof(timeBuffToday), "%d_%m_%Y", &timeinfo); String todayStr = String(timeBuffToday);

      if (lastArchivedDateStr != "" && todayStr != lastArchivedDateStr && WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata) {
          updateState("Arhivare_Zilnica_FB");
          String nodBaza = "/SenzoriAer/Arhiva_Zilnica/" + lastArchivedDateStr; 
          
          int puncteAzi = ((curentHour * 60 + curentMin) / 5) + 1;
          
          FirebaseJson jsonArhiva; 
          jsonArhiva.set("PM25", extrageArhivaIeri(getPaddedHistory(istPM25, tNow, lastIstoricSaveTime), puncteAzi)); 
          jsonArhiva.set("CO2", extrageArhivaIeri(getPaddedHistory(istCO2, tNow, lastIstoricSaveTime), puncteAzi)); 
          jsonArhiva.set("VOC", extrageArhivaIeri(getPaddedHistory(istVOC, tNow, lastIstoricSaveTime), puncteAzi)); 
          jsonArhiva.set("NOx", extrageArhivaIeri(getPaddedHistory(istNOx, tNow, lastIstoricSaveTime), puncteAzi)); 
          jsonArhiva.set("Temp", extrageArhivaIeri(getPaddedHistory(istTemp, tNow, lastIstoricSaveTime), puncteAzi)); 
          jsonArhiva.set("Umid", extrageArhivaIeri(getPaddedHistory(istUmid, tNow, lastIstoricSaveTime), puncteAzi));
          
          Firebase.RTDB.setJSONAsync(&fbdoScriere, nodBaza, &jsonArhiva);
          
          lastArchivedDateStr = todayStr; 
          Firebase.RTDB.setStringAsync(&fbdoScriere, "/SenzoriAer/last_archived_date", todayStr);
          
          if (curentHour == 0 && curentMin < 10) { logSystem("Arhiva pt " + nodBaza.substring(28) + " creata!"); } else { logSystem("Arhiva pt " + nodBaza.substring(28) + " refacuta!"); }
          updateState("Running_Loop");
      } 
      else if (lastArchivedDateStr == "" && WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata) { lastArchivedDateStr = todayStr; Firebase.RTDB.setStringAsync(&fbdoScriere, "/SenzoriAer/last_archived_date", todayStr); }

      if (curentMin % 5 == 0 && curentMin != lastSavedMin) {
          lastSavedMin = curentMin; int intervalePierdute = 0; updateState("Salvare_Istoric_5Min");
          
          if (lastIstoricSaveTime > 0) { intervalePierdute = ((tNow - lastIstoricSaveTime) / 300) - 1; if (intervalePierdute < 0) intervalePierdute = 0; if (intervalePierdute > 288) intervalePierdute = 288; }
          lastIstoricSaveTime = tNow; 

          String strPM25 = "-999", strCO2 = "-999", strVOC = "-999", strNOx = "-999", strTemp = "-999", strUmid = "-999", strPM1 = "-999", strPM4 = "-999", strPM10 = "-999";

          if (countAveraging > 0) {
              float avgPM1 = sumPM1 / countAveraging; float avgPM25 = sumPM25 / countAveraging; float avgPM4 = sumPM4 / countAveraging; float avgPM10 = sumPM10 / countAveraging;
              float avgTemp = sumTemp / countAveraging; float avgUmid = sumUmid / countAveraging; int avgCO2 = sumCO2 / countAveraging;
              float avgVOC = (countVOC > 0) ? (sumVOC / countVOC) : 0; float avgNOx = (countNOx > 0) ? (sumNOx / countNOx) : 0;

              strNOx = (avgNOx == 0 && nox > 1000) ? "-999" : String((int)avgNOx); 
              strVOC = (avgVOC == 0 && voc > 1000) ? "-999" : String((int)avgVOC);
              strPM25 = String(avgPM25, 1); strCO2 = String(avgCO2); strTemp = String(avgTemp, 1); strUmid = String(avgUmid, 1);
              strPM1 = String(avgPM1, 1); strPM4 = String(avgPM4, 1); strPM10 = String(avgPM10, 1);
          }

          adaugaInIstoric(istPM25, strPM25, intervalePierdute); adaugaInIstoric(istCO2, strCO2, intervalePierdute);
          adaugaInIstoric(istVOC, strVOC, intervalePierdute); adaugaInIstoric(istNOx, strNOx, intervalePierdute);
          adaugaInIstoric(istTemp, strTemp, intervalePierdute); adaugaInIstoric(istUmid, strUmid, intervalePierdute);
          adaugaInIstoric(istPM1, strPM1, intervalePierdute); adaugaInIstoric(istPM4, strPM4, intervalePierdute); adaugaInIstoric(istPM10, strPM10, intervalePierdute);

          sumPM1 = 0; sumPM25 = 0; sumPM4 = 0; sumPM10 = 0; sumTemp = 0; sumUmid = 0; sumVOC = 0; sumNOx = 0; sumCO2 = 0; countAveraging = 0; countVOC = 0; countNOx = 0;

          if (WiFi.status() == WL_CONNECTED && Firebase.ready() && firebaseGata) { 
              FirebaseJson jsonIstoric; 
              jsonIstoric.set("Istoric_PM25", istPM25); jsonIstoric.set("Istoric_CO2", istCO2); jsonIstoric.set("Istoric_VOC", istVOC); jsonIstoric.set("Istoric_NOx", istNOx);
              jsonIstoric.set("Istoric_Temp", istTemp); jsonIstoric.set("Istoric_Umid", istUmid); jsonIstoric.set("Istoric_PM1", istPM1); jsonIstoric.set("Istoric_PM4", istPM4); jsonIstoric.set("Istoric_PM10", istPM10);
              jsonIstoric.set("last_history_time", lastIstoricSaveTime);
              Firebase.RTDB.updateNodeAsync(&fbdoScriere, "/SenzoriAer", &jsonIstoric);
          }
          updateState("Running_Loop");
      }
  }
}