"""Temporizator: timp monoton si integrare cu parserii/controlul real, numai local."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

folder=Path(__file__).resolve().parent
root=folder.parents[1]
source=(root/"Esp_SmartPurifier.ino").read_text(encoding="utf-8")
library=Path("C:/Users/olaru/OneDrive/Documents/Arduino/libraries/Firebase_Arduino_Client_Library_for_ESP8266_and_ESP32/src/json")
names=("timerAcumUnixMs","intretineTimer","citesteNumarTimer","aplicaObiectTimer","aplicaSetariTimer","aplicaTimerIndividual","aplicaSetariJson","aplicaSetareSingura")
functions=[]
for name in names:
    found=re.search(r"^(?:void|bool|int64_t) "+name+r"\([^\n]*\) \{.*?^\}",source,re.M|re.S)
    if found: functions.append(found.group())
header=r'''
#include <string>
#include <vector>
#include <deque>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <cstdlib>
#include <algorithm>
#include <iostream>
#include "MB_JSON/MB_JSON.h"
using std::isfinite;
class String: public std::string {
public:
 using std::string::string;
 String(std::string s):std::string(s){}
 float toFloat()const{return std::strtof(c_str(),nullptr);}
 int toInt()const{return std::strtol(c_str(),nullptr,10);}
 bool startsWith(const char* p)const{return rfind(p,0)==0;}
};
struct FirebaseJsonData {
 bool success=false,boolValue=false;int intValue=0,typeNum=0;
 float floatValue=0;double doubleValue=0;String stringValue;
};
struct FirebaseJson {
 enum {JSON_OBJECT=1,JSON_STRING=3,JSON_INT=4,JSON_FLOAT=5,JSON_DOUBLE=6,JSON_BOOL=7,JSON_NULL=8};
 MB_JSON* root=nullptr;
 std::vector<MB_JSON*> iteration;
 ~FirebaseJson(){MB_JSON_Delete(root);}
 void setJsonData(const String& s){MB_JSON_Delete(root);root=MB_JSON_Parse(s.c_str());}
 size_t iteratorBegin(){iteration.clear();for(auto n=root?root->child:nullptr;n;n=n->next)iteration.push_back(n);return iteration.size();}
 int iteratorGet(size_t i,int& type,String& key,String& value){auto n=iteration.at(i);type=1;key=n->string;char* v=MB_JSON_PrintUnformatted(n);value=v;MB_JSON_free(v);return 0;}
 void iteratorEnd(){iteration.clear();}
 bool get(FirebaseJsonData& data,const String& path){
  MB_JSON* n=root;size_t from=0;
  while(n){auto slash=path.find('/',from);auto key=path.substr(from,slash==String::npos?slash:slash-from);
   n=MB_JSON_GetObjectItemCaseSensitive(n,key.c_str());if(slash==String::npos)break;from=slash+1;}
  if(!n)return false; // Rezultatul vechi ramane la cheie absenta, ca SDK-ul.
  data={};data.success=true;
  if(MB_JSON_IsNumber(n)){data.doubleValue=n->valuedouble;data.floatValue=n->valuedouble;
   data.intValue=(n->valuedouble>=INT32_MIN&&n->valuedouble<=INT32_MAX)?int(n->valuedouble):0;
   data.typeNum=std::floor(n->valuedouble)==n->valuedouble?JSON_INT:JSON_DOUBLE;}
  else if(MB_JSON_IsString(n)){data.typeNum=JSON_STRING;data.stringValue=n->valuestring;}
  else if(MB_JSON_IsBool(n)){data.typeNum=JSON_BOOL;data.boolValue=MB_JSON_IsTrue(n);}
  else if(MB_JSON_IsObject(n))data.typeNum=JSON_OBJECT;else data.typeNum=JSON_NULL;
  return true;
 }
};
template<class T>T constrain(T v,T a,T b){return std::clamp(v,a,b);}
uint64_t nowMs=1000;
int64_t wallMs=1800000000000LL;
int gettimeofday(timeval* t,void*){t->tv_sec=wallMs/1000;t->tv_usec=(wallMs%1000)*1000;return 0;}
int64_t esp_timer_get_time(){return nowMs*1000;}
bool clockValid=true,necesitaReconciliere=false,necesitaSetariVentilator=false;
constexpr int WL_CONNECTED=3;
struct {bool online=true;int status(){return online?3:0;}} WiFi;
bool firebaseGata=true,readOk=true;
uint32_t tAntPollSetari=0;
int reads=0;
String serverSettings="{}";
struct {FirebaseJson json;FirebaseJson& jsonObject(){return json;}} fbdoCitire;
struct {
 bool ready(){return true;}
 struct {
  bool getJSON(decltype(fbdoCitire)* target,const char*){++reads;if(!readOk)return false;target->json.setJsonData(serverSettings);return true;}
 } RTDB;
} Firebase;
struct EvenimentSetari{char path[48];char data[1024];};
std::deque<EvenimentSetari> events;
void* coadaSetari=(void*)1;
constexpr int pdTRUE=1;
int xQueueReceive(void*,EvenimentSetari* e,int){if(events.empty())return 0;*e=events.front();events.pop_front();return pdTRUE;}
void updateState(const char*){}
bool isAutoMode=false,eraAutoInainte=false;
int vitezaAutoCurenta=0,targetFanSpeed=0;
uint32_t timpUltimaSchimbareAuto=0;
float pm25=2;
struct {bool pm=true;} validitateSenzor;
bool ceasValid(){return clockValid;}
std::vector<std::string> logs;
void logSystem(const char* p){logs.emplace_back(p);}
bool nightModeApp=false,nightModePrimit=false,modLiveRapid=false,notifPraf=true,notifGaze=true,nmAuto=false;
int luminozitateEcran=100,luminozitateLed=150,vitezaDinAplicatie=30,vitezaBaza=30;
int alertCo2=1600,notifFreqMin=30,nmStartMin=1380,nmEndMin=420;
float offsetTemperatura=-0.8f,alertPm=20,alertVoc=250,alertNox=30;
void aplicaOffsetTemp(float v){offsetTemperatura=v;}
void comandaResetVoc(){} void comandaCalibrareCo2(){} void respingeCalibrareCo2Veche(){}
void comandaCuratare(){} void comandaRestart(){}
#if __has_include("SmartAirTimer.h")
#include "SmartAirTimer.h"
StareTimer stareTimer;
#else
void intretineTimer(){}
#endif
int esecuri=0,verificari=0;
void verifica(bool ok,const char* p){++verificari;if(!ok){++esecuri;std::cerr<<"FAIL: "<<p<<"\n";}}
void aplicaSetariJson(FirebaseJson&,bool permiteVentilator=true);
void intretineTimer();
void aplica(const String& s){FirebaseJson j;j.setJsonData(s);aplicaSetariJson(j);intretineTimer();}
'''
control = source.split("  // ---- Logica Auto / Manual ventilator ----",1)[1].split("  // ---- Fading ventilator",1)[0]
control = "void fan(){intretineTimer();uint32_t t=nowMs;\n"+control+"\n}\n"
queue = source.split("  if (coadaSetari != NULL) {",1)[1].split("  // ---- Trimitere date live",1)[0]
queue = "void proceseaza(){unsigned long t=nowMs;\n  if (coadaSetari != NULL) {"+queue+"\n}\n"
tests=(folder/"timer_cases.cpp.inc").read_text(encoding="utf-8")
with tempfile.TemporaryDirectory(prefix="smartair-timer-") as tmp:
    cpp,binary=Path(tmp)/"timer.cpp",Path(tmp)/"timer.exe"
    cpp.write_text(header+"\n".join(functions)+control+queue+tests,encoding="utf-8")
    subprocess.run([shutil.which("g++"),"-std=c++17","-Wall","-Wextra","-I",str(root),"-I",str(library),str(cpp),str(library/"MB_JSON/MB_JSON.c"),"-o",str(binary)],check=True)
    raise SystemExit(subprocess.run([str(binary)]).returncode)
