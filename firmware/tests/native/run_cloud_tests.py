"""Executa trimiterea live si recuperarea din sketch, cu transport local controlat.

Transportul async poate accepta bytes fara confirmarea serverului, ca SDK-ul
instalat. Nu foloseste WiFi, credentiaIe sau Firebase de productie.
"""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import diagnostic_test_support as diagnostic

root = Path(__file__).resolve().parents[2]
source = (root / "Esp_SmartPurifier.ino").read_text(encoding="utf-8")
constants = "\n".join(re.findall(r'^constexpr char (?:VERSIUNE|BUILD)_FIRMWARE\[\].*$', source, re.M))
start = source.index("  unsigned long intervalTrimitere = 10000;")
end = source.index("  // ---- Istoric local", start)
live = source[start:end]
dot = source.split("  // Verde = publicare confirmata;", 1)[1].split("  // ---- ORA + DATA ----", 1)[0]
dot = dot.split("\n", 1)[1]
maintenance = re.search(r"^void intretineFirebase\(\) \{.*?^\}", source, re.M | re.S)
header = r'''
#include <cstdint>
#include <cmath>
#include <string>
#include <iostream>
#include <vector>
#include <set>
#if __has_include("SmartAirCloudHealth.h")
#include "SmartAirCloudHealth.h"
StareCloud stareCloud;
#endif
''' + diagnostic.STRING + r'''
uint64_t nowMs=0;
unsigned long millis(){return (uint32_t)nowMs;}
int64_t esp_timer_get_time(){return nowMs*1000;}
constexpr int WL_CONNECTED=3;
struct Wifi {bool online=true; int resets=0;int status(){return online?3:0;}
 int RSSI(){return -60;} void disconnect(){++resets;online=false;} } WiFi;
struct Esp {int getFreeHeap(){return 100000;} int getMinFreeHeap(){return 50000;} int getFreePsram(){return 7000000;}} ESP;
float temperatureRead(){return 35;}
constexpr int ST77XX_RED=1,ST77XX_GREEN=2,ST77XX_ORANGE=3,ST77XX_BLUE=4;
int dotColor=0;
struct {void fillCircle(int,int,int,int color){dotColor=color;}} tft;
struct FirebaseJson {std::set<std::string> keys; template<class... T> void set(const std::string& key,T...){keys.insert(key);} };
bool lastHasMetadata=false;
bool lastHasDiagnostic=false;
bool lastHasTimer=false;
struct Data {int stops=0; int code=200; void stopWiFiClient(){++stops;}
 String errorReason(){return "transport";} int httpCode(){return code;} } fbdoScriere,fbdoCitire;
bool ack=true, authReady=true;
int asynchronous=0, synchronous=0, refreshes=0,unsupportedRefreshes=0;
struct Client {
 struct DB {
  bool updateNodeAsync(Data*,const char* path,FirebaseJson* json) {
   if(std::string(path)!="/SenzoriAer")std::abort();
   lastHasMetadata=json->keys.count("Firmware/versiune");
   lastHasDiagnostic=json->keys.count("Diagnostic/schema");
   lastHasTimer=json->keys.count("Timer/schema") && json->keys.count("Timer/raportat_la/.sv");
   ++asynchronous;return true; // Conexiune TCP vie, nu confirmare HTTP.
  }
  bool updateNodeSilent(Data* data,const char* path,FirebaseJson* json) {
   if(std::string(path)!="/SenzoriAer")std::abort();
   lastHasMetadata=json->keys.count("Firmware/versiune") && json->keys.count("Firmware/build") && json->keys.count("Firmware/raportat_la/.sv");
   lastHasDiagnostic=json->keys.count("Diagnostic/schema") && json->keys.count("Diagnostic/raportat_la/.sv") && json->keys.count("Diagnostic/jurnal");
   lastHasTimer=json->keys.count("Timer/schema") && json->keys.count("Timer/raportat_la/.sv");
   ++synchronous; data->code=ack?204:401;return ack;
  }
 } RTDB;
 bool ready(){return authReady;}
 bool resetDone=false;
 // Service account nu are refresh_token; SDK-ul cere reset + begin pentru reautentificare.
 void refreshToken(int*){++unsupportedRefreshes;}
 void reset(int*){resetDone=true;}
 void begin(int*,int*){if(resetDone){++refreshes;resetDone=false;}}
} Firebase;
int config=0,auth=0;
bool firebaseGata=true,offsetFortatLaBoot=true,firebaseAvertizat=false;
unsigned long tAntLive=0;
int currentFanSpeed=50,targetFanSpeed=50,rpmReal=1000,co2=500;
bool modLiveRapid=false;
float offsetTemperatura=-0.8f,pm1=1,pm25=2,pm4=3,pm10=4,voc=100,nox=1,temp=21,umiditate=45;
struct {bool co2=true,pm=true,voc=true,nox=true,temperatura=true,umiditate=true;} validitateSenzor;
std::vector<std::string> events;
void logSystem(String text){events.push_back(text);}
void updateState(const char*){}
''' + diagnostic.GLOBALS
tests = r'''
int fails=0,checks=0;
void check(bool ok,const char* message){++checks;if(!ok){++fails;std::cerr<<"FAIL: "<<message<<"\n";}}
int main(){
 nowMs=10000;ack=false;tickLive();
 check(synchronous==1,"Prima trimitere cere confirmare HTTP, nu doar socket TCP");
 check(lastHasMetadata,"Prima trimitere include versiune/build/timestamp firmware");
 check(lastHasDiagnostic,"Prima trimitere include raportul de diagnostic in acelasi PATCH");
 check(lastHasTimer,"Publicarea confirmata contine Timer si timestamp propriu");
#if __has_include("SmartAirCloudHealth.h")
 check(!stareCloud.confirmatRecent(nowMs),"Async acceptat nu inseamna date confirmate in cloud");
 nowMs=20000;tickLive();
 check(asynchronous==1,"Intre confirmari ramane ritmul async existent");
 check(!lastHasMetadata,"Telemetria rapida nu retrimite metadatele statice");
 check(!lastHasDiagnostic,"Telemetria rapida nu retrimite raportul de diagnostic");
 check(lastHasTimer,"Telemetria rapida actualizeaza Timer inclusiv timestamp");
 nowMs=70000;ack=true;tickLive();
 check(stareCloud.confirmatRecent(nowMs),"Confirmarea reala permite indicator verde");
 tickDot();check(dotColor==ST77XX_BLUE,"Confirmarea produce un impuls albastru");
 nowMs+=1000;tickDot();check(dotColor==ST77XX_GREEN,"Dupa impuls revine verde");
 nowMs=70000;
 check(!stareCloud.confirmatRecent(nowMs+90000),"Confirmarea veche nu tine indicatorul verde");
 ack=false;nowMs=130000;tickLive();
 intretineFirebase();
 check(refreshes==0,"Esec scurt nu forteaza reautentificare");
 nowMs=190000;authReady=false;intretineFirebase();
 check(refreshes==1 && fbdoScriere.stops==1 && fbdoCitire.stops==1,
       "Recuperarea se executa chiar cand Firebase.ready ramane false");
 check(unsupportedRefreshes==0,"Service account foloseste reautentificare, nu refresh_token absent");
 nowMs=190001;intretineFirebase();
 check(refreshes==1,"Fara bucla de reconectari la fiecare iteratie");
 WiFi.online=false;nowMs=400000;intretineFirebase();
 check(refreshes==1,"Fara reautentificare inutila cand WiFi lipseste");
 tickDot();check(dotColor==ST77XX_RED,"WiFi absent este rosu");
 WiFi.online=true;intretineFirebase();
 tickDot();check(dotColor==ST77XX_ORANGE,"WiFi fara confirmare cloud este portocaliu");
 check(refreshes==2,"Revenirea WiFi permite recuperarea cloud blocat");
 check(events.size()==1,"O pana lunga nu umple jurnalul cu aceeasi eroare");
 authReady=true;ack=true;nowMs=410000;tickLive();
 check(stareCloud.confirmatRecent(nowMs),"Server revenit reactiveaza starea confirmata");
 intretineFirebase();
 check(refreshes==2,"Recuperarea se opreste dupa confirmare");
 check(events.size()==2,"Revenirea confirmata adauga un eveniment distinct");
 // Uptime pe 64 biti: o confirmare de acum 49,7 zile nu redevine recenta.
 nowMs=410000ULL+0x100000000ULL;ack=false;intretineFirebase();
 check(!stareCloud.confirmatRecent(nowMs),"Uptime lung nu reinvie confirmarea veche");
 check(refreshes==3,"Recuperare dupa rollover millis");
 StareCloud startup;
 check(!startup.confirmatRecent(0),"Boot fara ACK nu este verde");
 check(!startup.recuperareNecesara(true,true,1000),"Gratie initializare cloud");
 check(!startup.recuperareNecesara(true,true,120999),"Gratie completa de doua minute");
 check(startup.recuperareNecesara(true,true,121000),"Boot cu auth blocat cere recuperare");
 check(!startup.recuperareNecesara(false,true,999999),"Fara Firebase initializat nu recuperam");
#endif
 std::cout<<checks<<" verificari cloud, "<<fails<<" esecuri\n";return fails?1:0;
}
'''
with tempfile.TemporaryDirectory(prefix="smartair-cloud-") as tmp:
    cpp=Path(tmp)/"cloud.cpp"
    cpp.write_text(header + constants + diagnostic.diagnostic_functions(source) + (maintenance.group() if maintenance else "void intretineFirebase() {}")
                   + "\nvoid tickLive(){unsigned long t=millis();\n" + live + "\n}\n"
                   + "\nvoid tickDot(){bool wifiConn=WiFi.status()==WL_CONNECTED;\n" + dot + "\n}\n" + tests,
                   encoding="utf-8")
    exe=Path(tmp)/"cloud.exe"
    subprocess.run([shutil.which("g++") or "g++", "-std=c++17", "-Wall", "-Wextra", "-I", str(root),str(cpp),"-o",str(exe)],check=True)
    subprocess.run([str(exe)],check=True)
