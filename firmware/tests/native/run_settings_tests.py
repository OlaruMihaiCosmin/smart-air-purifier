"""Verifica functiile reale de aplicare a setarilor, fara placa sau Firebase online."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "Esp_SmartPurifier.ino").read_text(encoding="utf-8")
functions = []
for name in ("aplicaSetariJson", "aplicaSetareSingura"):
    match = re.search(r"^void " + name + r"\([^\n]+\) \{.*?^\}", source, re.M | re.S)
    if not match:
        raise RuntimeError(f"Functia {name} nu a fost gasita")
    functions.append(match.group())

header = r'''
#include <string>
#include <map>
#include <cmath>
#include <cstdlib>
#include <algorithm>
#include <iostream>
class String {
  std::string s;
public:
  String(const char* p=""): s(p) {}
  const char* c_str() const { return s.c_str(); }
  float toFloat() const { return std::strtof(s.c_str(), nullptr); }
  int toInt() const { return std::strtol(s.c_str(), nullptr, 10); }
  friend bool operator==(const String& a,const char* b) { return a.s==b; }
};
struct FirebaseJsonData {
  bool success=false, boolValue=false;
  int intValue=0, typeNum=0;
  float floatValue=0;
};
struct FirebaseJson {
  enum { INT=4, FLOAT=5, DOUBLE=6, BOOL=7, JSON_NULL=8, STRING=3,
         JSON_INT=INT, JSON_FLOAT=FLOAT, JSON_DOUBLE=DOUBLE };
  std::map<std::string,FirebaseJsonData> fields;
  void set(const char* key,float v,int type=FLOAT) {
    fields[key]={true,v!=0,int(v),type,v};
  }
  bool get(FirebaseJsonData& result,const char* key) {
    auto it=fields.find(key);
    // FirebaseJson 3.0.10 pastreaza rezultatul precedent cand cheia lipseste.
    // Vezi FirebaseJsonBase::mGet() si FirebaseJson::get() din biblioteca instalata.
    if(it==fields.end()) return false;
    result=it->second;
    return true;
  }
};
template<class T> T constrain(T v,T lo,T hi) { return std::clamp(v,lo,hi); }
bool nightModeApp=false,nightModePrimit=false,modLiveRapid=false,notifPraf=true,notifGaze=true,nmAuto=false;
int luminozitateEcran=100,luminozitateLed=150,vitezaDinAplicatie=30;
int alertCo2=1600,notifFreqMin=30,nmStartMin=1380,nmEndMin=420;
float offsetTemperatura=-0.8f,alertPm=20,alertVoc=250,alertNox=30;
int offsetWrites=0,resetVoc=0,calibrare=0,calibrareVecheRespinsa=0,curatare=0,restart=0;
void aplicaOffsetTemp(float v) {
  if(std::isfinite(v) && v>=-3 && v<=3) { offsetTemperatura=v; ++offsetWrites; }
}
void comandaResetVoc() { ++resetVoc; }
void comandaCalibrareCo2() { ++calibrare; }
void respingeCalibrareCo2Veche() { ++calibrareVecheRespinsa; }
void comandaCuratare() { ++curatare; }
void comandaRestart() { ++restart; }
// Timerul are suita proprie cu parserul JSON real; aici conservam verificarile vechi.
void aplicaSetariTimer(FirebaseJson& json) {
  FirebaseJsonData data;
  if(json.get(data,"speed"))vitezaDinAplicatie=constrain(data.intValue,0,100);
}
bool aplicaTimerIndividual(const String& path,const String& value) {
  if(path=="/speed" && !(value=="null")){vitezaDinAplicatie=constrain(value.toInt(),0,100);return true;}
  return false;
}
int esecuri=0,verificari=0;
bool necesitaSetariVentilator=false;
void aplicaSetariJson(FirebaseJson&,bool permiteVentilator=true);
void verifica(bool ok,const char* mesaj) {
  ++verificari;
  if(!ok) { ++esecuri; std::cout<<"FAIL: "<<mesaj<<"\n"; }
}
'''
tests = r'''
int main() {
  FirebaseJson live; live.set("live_mode",0,FirebaseJson::BOOL);
  aplicaSetariJson(live);
  verifica(offsetTemperatura==-0.8f && offsetWrites==0,"live_mode=false nu reseteaza offsetul absent");
  verifica(luminozitateEcran==100 && luminozitateLed==150 && vitezaDinAplicatie==30,
           "Patch-ul live nu modifica luminozitatea sau viteza");
  verifica(alertPm==20 && alertCo2==1600 && alertVoc==250 && alertNox==30 && notifPraf && notifGaze,
           "Patch-ul live nu modifica alertele");
  FirebaseJson night; night.set("night_mode",1,FirebaseJson::BOOL);
  aplicaSetariJson(night);
  verifica(nightModePrimit && !nightModeApp && !modLiveRapid && offsetTemperatura==-0.8f && !nmAuto && nmStartMin==1380 && nmEndMin==420,
           "Patch-ul night_mode modifica numai campul prezent");
  verifica(resetVoc+calibrare+curatare+restart==0,"Un boolean true nu creeaza comenzi absente");
  FirebaseJson command; command.set("reset_voc",1,FirebaseJson::BOOL);
  resetVoc=calibrare=curatare=restart=0;
  aplicaSetariJson(command);
  verifica(resetVoc==1 && calibrare+curatare+restart==0,"Se executa numai comanda prezenta");
  FirebaseJson empty;
  offsetTemperatura=-0.8f; offsetWrites=0;
  aplicaSetariJson(empty);
  verifica(offsetWrites==0,"Obiectul gol nu modifica offsetul");
  for(int type: {FirebaseJson::JSON_NULL,FirebaseJson::BOOL,FirebaseJson::STRING}) {
    FirebaseJson invalid; invalid.set("temp_offset",0,type);
    aplicaSetariJson(invalid);
  }
  verifica(offsetWrites==0,"Offsetul null, boolean sau text nu este transformat in zero");
  offsetWrites=0;
  FirebaseJson zero; zero.set("temp_offset",0,FirebaseJson::INT);
  aplicaSetariJson(zero);
  verifica(offsetTemperatura==0 && offsetWrites==1,"Zero numeric cerut explicit ramane permis");
  FirebaseJson negative; negative.set("temp_offset",-0.8f);
  aplicaSetariJson(negative);
  verifica(offsetTemperatura==-0.8f,"Snapshot-ul pastreaza offsetul negativ");
  offsetWrites=0;
  for(const char* payload: {"", "null", "false", "\"-0.8\"", "{}", "0abc", "nan", "4"})
    aplicaSetareSingura("/temp_offset",payload);
  verifica(offsetWrites==0 && offsetTemperatura==-0.8f,"Evenimentele individuale invalide nu modifica offsetul");
  aplicaSetareSingura("/temp_offset","0");
  verifica(offsetTemperatura==0,"Evenimentul individual permite zero numeric");
  aplicaSetareSingura("/temp_offset","-0.8");
  verifica(offsetTemperatura==-0.8f,"Evenimentul individual permite offsetul negativ");
  FirebaseJson legacy; legacy.set("calibrare_co2",1,FirebaseJson::BOOL);
  aplicaSetariJson(legacy);
  aplicaSetareSingura("/calibrare_co2","true");
  verifica(calibrare==0 && calibrareVecheRespinsa==2,"Comanda veche merge numai pe ramura de respingere");
  FirebaseJson fresh; fresh.set("calibrare_co2_expira",1800000120.0f,FirebaseJson::INT);
  aplicaSetariJson(fresh);
  aplicaSetareSingura("/calibrare_co2_expira","1800000120");
  verifica(calibrare==2,"Snapshot si stream trimit cererea cu termen la receptor");
  FirebaseJson consumed; consumed.set("calibrare_co2_expira",0,FirebaseJson::INT);
  aplicaSetariJson(consumed);
  aplicaSetareSingura("/calibrare_co2_expira","0");
  verifica(calibrare==2,"Termenul consumat nu declanseaza o noua recitire");
  std::cout<<verificari<<" verificari, "<<esecuri<<" esecuri\n";
  return esecuri ? 1 : 0;
}
'''
compiler = shutil.which("g++")
if not compiler:
    raise RuntimeError("Este necesar g++ pentru testele native")
with tempfile.TemporaryDirectory(prefix="smartair-settings-") as directory:
    folder = Path(directory)
    cpp, binary = folder / "settings_test.cpp", folder / "settings_test.exe"
    cpp.write_text(header + "\n".join(functions) + tests, encoding="utf-8")
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", str(cpp), "-o", str(binary)], check=True)
    raise SystemExit(subprocess.run([str(binary)]).returncode)
