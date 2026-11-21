"""Executa jurnalul real din sketch, cu transport Firebase local controlat."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "Esp_SmartPurifier.ino").read_text(encoding="utf-8")
functions = []
for name in ("limiteazaJurnal", "logSystem", "recupereazaJurnal", "trimiteJurnalDacaNecesar", "comandaResetVoc"):
    match = re.search(r"^void " + name + r"\([^\n]*\) \{.*?^\}", source, re.M | re.S)
    if match:
        functions.append(match.group())
    else:
        raise RuntimeError("Functia " + name + " nu a fost gasita")
import_line = re.search(r"^\s*if \(etapa == 0\) [^\n]+", source, re.M).group().strip()

header = r'''
#include <algorithm>
#include <ctime>
#include <cstdint>
#include <iostream>
#include <string>
class String {
  std::string s;
public:
  String(const char* p=""):s(p){}
  String(std::string p):s(p){}
  String(time_t p):s(std::to_string(p)){}
  size_t length() const {return s.size();}
  int indexOf(char c) const {auto i=s.find(c);return i==s.npos ? -1 : int(i);}
  void remove(size_t i,size_t n){s.erase(i,n);}
  char operator[](size_t i)const{return s[i];}
  String& operator+=(const String& b){s+=b.s;return *this;}
  friend String operator+(const String& a,const String& b){return a.s+b.s;}
  friend bool operator==(const String& a,const String& b){return a.s==b.s;}
  friend bool operator!=(const String& a,const String& b){return !(a==b);}
  const std::string& str()const{return s;}
};
bool firebaseGata=false, jurnalRecuperat=false, jurnalModificat=false, jurnalIncercat=false;
bool bootLogSent=false, transportOk=true, wifiOnline=true;
uint32_t tUltimaIncercareJurnal=0, clockMs=0;
time_t clockEpoch=1700000000;
uint32_t millis(){return clockMs;}
String jurnalErori, server, motivRestart="Test boot";
int fbdoScriere=0, etapa=0, scrieri=0, incercari=0;
const int WL_CONNECTED=3;
struct Wifi {int status(){return wifiOnline?WL_CONNECTED:0;}} WiFi;
void updateState(const char*){}
struct Transport {
  bool setString(int*,const char* path,const String& payload){
    if(std::string(path)!="/SenzoriAer/ErrorLog")std::abort();
    ++incercari;if(!transportOk)return false;
    server=payload;++scrieri;return true;
  }
  bool setStringAsync(int* p,const char* path,const String& payload){return setString(p,path,payload);}
};
struct FirebaseClient {Transport RTDB;bool ready(){return true;}} Firebase;
bool restartCalled=false,logAtRestart=false,commandAllowed=true;
bool consumaComanda(const char*){return commandAllowed;}
struct {void fillScreen(int){} void setCursor(int,int){} void setTextColor(int){} void print(const char*){}} tft;
constexpr int ST77XX_BLACK=0,ST77XX_RED=1;
struct {void remove(const char*){}} storace;
struct {void restart(){restartCalled=true;logAtRestart=server.str().find("Stergere calibrare VOC")!=std::string::npos;}} ESP;
void esp_task_wdt_reset(){} void yield(){++clockMs;}
time_t fakeTime(time_t* p){*p=clockEpoch;return *p;}
#define time fakeTime
int esecuri=0,verificari=0;
void verifica(bool ok,const char* mesaj){++verificari;if(!ok){++esecuri;std::cout<<"FAIL: "<<mesaj<<"\n";}}
bool contine(const String& s,const char* token){return s.str().find(token)!=std::string::npos;}
int numar(const String& s){return s.length() ? int(std::count(s.str().begin(),s.str().end(),'|'))+1 : 0;}
void reset(){firebaseGata=false;jurnalRecuperat=false;jurnalErori="";server="";scrieri=0;etapa=0;
  jurnalModificat=false;jurnalIncercat=false;bootLogSent=false;transportOk=true;wifiOnline=true;
  clockMs=0;tUltimaIncercareJurnal=0;incercari=0;clockEpoch=1700000000;}
'''
tests = r'''
int main(){
  reset();clockEpoch=0;logSystem("Eroare inainte de NTP");
  verifica(contine(jurnalErori,"999999999#Eroare inainte de NTP"),"Boot fara ora pastreaza evenimentul in RAM cu data necunoscuta");
  firebaseGata=true;clockEpoch=1700000000;importa("1699999990#Anterior");trimiteJurnalDacaNecesar();
  verifica(contine(server,"Eroare inainte de NTP") && contine(server,"Anterior"),"Sincronizarea orei si importul pastreaza evenimentul produs fara NTP");
  reset();server="1699999990#Anterior";
  logSystem("Fara retea");
  verifica(scrieri==0,"Boot fara Firebase nu publica jurnalul");
  verifica(contine(jurnalErori,"Fara retea"),"Evenimentul de boot ramane in RAM inainte de Firebase");
  firebaseGata=true;logSystem("Pornire curatare");
  trimiteJurnalDacaNecesar();
  verifica(server=="1699999990#Anterior" && scrieri==0,"Comanda initiala nu suprascrie jurnalul nerecuperat");
  String citit=server;importa(citit);
  trimiteJurnalDacaNecesar();
  verifica(contine(server,"Sistem Repornit:") && bootLogSent,"Bootul este publicat dupa importul jurnalului fara a astepta istoricele");
  verifica(contine(jurnalErori,"Anterior") && contine(jurnalErori,"Fara retea") && contine(jurnalErori,"Pornire curatare"),"Importul combina istoricul cloud cu evenimentele locale");
  logSystem("Dupa import");
  clockMs+=10000;trimiteJurnalDacaNecesar();
  verifica(numar(server)==5 && contine(server,"Anterior") && contine(server,"Dupa import"),"Prima publicare completa pastreaza toate evenimentele");
  importa(citit);
  verifica(numar(jurnalErori)==5 && contine(jurnalErori,"Dupa import"),"Reincercarea importului nu dubleaza si nu pierde evenimente noi");
  reset();firebaseGata=true;logSystem("Primul");importa("");logSystem("Al doilea");
  trimiteJurnalDacaNecesar();
  verifica(numar(server)==3 && contine(server,"Primul"),"Nodul cloud absent nu elimina evenimentele in asteptare");
  reset();firebaseGata=true;
  String cloud;
  for(int i=0;i<28;++i){if(i)cloud+="|";cloud+=String("1699999990#V"+std::to_string(i));}
  for(int i=0;i<5;++i)logSystem(String("N"+std::to_string(i)));
  importa(cloud);logSystem("Ultimul");
  trimiteJurnalDacaNecesar();
  verifica(numar(server)==30,"Importul si publicarea respecta limita de 30 intrari");
  verifica(!contine(server,"#V4|") && contine(server,"#V5|") && contine(server,"#N0|") && contine(server,"Ultimul"),"Limita elimina cele mai vechi intrari si pastreaza ultimele locale");
  reset();firebaseGata=true;importa("1699999990#Anterior");transportOk=false;
  trimiteJurnalDacaNecesar();
  verifica(jurnalModificat && scrieri==0,"Eroarea transportului pastreaza jurnalul pentru retry");
  const int incercariEsuate=incercari;
  for(int i=0;i<100;++i){clockMs+=10;trimiteJurnalDacaNecesar();}
  verifica(incercari==incercariEsuate,"Retry jurnal limitat: nu se trimite in fiecare bucla");
  transportOk=true;clockMs=10000;logSystem("Eveniment nou");trimiteJurnalDacaNecesar();
  verifica(scrieri==1 && !jurnalModificat && contine(server,"Anterior") && contine(server,"Sistem Repornit:") && contine(server,"Eveniment nou"),"Revenirea transportului publica toate evenimentele in asteptare");
  clockMs+=10000;trimiteJurnalDacaNecesar();
  verifica(scrieri==1,"Un jurnal confirmat nu este retrimis fara schimbari");
  reset();firebaseGata=true;importa("");trimiteJurnalDacaNecesar();
  comandaResetVoc();
  verifica(restartCalled && logAtRestart,"Comanda VOC publica evenimentul inainte de restart, inclusiv dupa un ACK recent");
  std::cout<<verificari<<" verificari, "<<esecuri<<" esecuri\n";
  return esecuri ? 1:0;
}
'''
compiler = shutil.which("g++")
if not compiler:
    raise RuntimeError("Este necesar g++ pentru testele native")
with tempfile.TemporaryDirectory(prefix="smartair-journal-") as directory:
    folder = Path(directory)
    cpp, binary = folder / "journal_test.cpp", folder / "journal_test.exe"
    cpp.write_text(header + "\n".join(functions) + "\nvoid importa(const String& valoare){" + import_line + "}\n" + tests, encoding="utf-8")
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", str(cpp), "-o", str(binary)], check=True)
    raise SystemExit(subprocess.run([str(binary)]).returncode)
