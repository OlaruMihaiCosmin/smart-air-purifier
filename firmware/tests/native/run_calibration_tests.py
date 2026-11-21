"""Executa receptorul real de calibrare cu ceas, retea si senzor simulate local."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "Esp_SmartPurifier.ino").read_text(encoding="utf-8")
functions = []
for name in ("calibrareCo2InTermen", "respingeCalibrareCo2Veche", "comandaCalibrareCo2"):
    match = re.search(r"^(?:bool|void) " + name + r"\([^\n]*\) \{.*?^\}", source, re.M | re.S)
    if match:
        functions.append(match.group())
has_legacy_rejection = "void respingeCalibrareCo2Veche()" in source

header = r'''
#include <string>
#include <vector>
#include <cmath>
#include <cstdint>
#include <ctime>
#include <iostream>
using std::isfinite;
class String {
  std::string s;
public:
  String(const char* p=""):s(p){}
  String(std::string p):s(p){}
  String(int p):s(std::to_string(p)){}
  friend bool operator==(const String& a,const char* b){return a.s==b;}
  friend bool operator!=(const String& a,const char* b){return a.s!=b;}
  friend String operator+(const String& a,const String& b){return String(a.s+b.s);}
};
bool firebaseGata=true, clockValid=true, connected=true, readOk=true, writeOk=true;
bool legacy=true, forceFullRedraw=false;
uint64_t elapsed=0;
time_t epoch=1800000000;
int writeDelay=0, readDelay=0, beforeFrcDelay=0, writes=0, frc=0, starts=0, stops=0;
double deadline=1800000120;
const char* valueType="int";
time_t testTime(void*) { return epoch + elapsed/1000; }
#define time testTime
bool ceasValid(){return clockValid;}
unsigned long millis(){return elapsed;}
void yield(){elapsed+=100;}
void esp_task_wdt_reset(){}
constexpr int WL_CONNECTED=1, ST77XX_BLACK=0, ST77XX_ORANGE=1;
struct {int status(){return connected ? WL_CONNECTED : 0;}} WiFi;
struct Data {
  double doubleData(){return deadline;}
  String dataType(){return valueType;}
} fbdoCitire, fbdoScriere;
struct RTDB {
  bool getDouble(Data*,const char*){elapsed+=readDelay; return readOk;}
  bool setInt(Data*,const char*,int value){elapsed+=writeDelay; ++writes; if(writeOk) deadline=value; return writeOk;}
};
struct { struct RTDB RTDB; } Firebase;
bool consumaComanda(const char*){ if(!legacy || !writeOk) return false; legacy=false; return true; }
struct {
  void fillScreen(int){} void setCursor(int,int){} void setTextColor(int){} void print(const char*){}
} tft;
void updateState(const char*){}
std::vector<String> logs;
void logSystem(String s){logs.push_back(s);}
struct {
  void stopMeasurement(){++stops; elapsed+=beforeFrcDelay;}
  int16_t performForcedCo2Recalibration(int ppm,uint16_t& correction){if(ppm==420) ++frc; correction=0x8000; return 0;}
  void startContinuousMeasurement(){++starts;}
} sen66;
void reset(){
  firebaseGata=clockValid=connected=readOk=writeOk=legacy=true;
  forceFullRedraw=false; elapsed=0; epoch=1800000000;
  writeDelay=readDelay=beforeFrcDelay=writes=frc=starts=stops=0;
  deadline=1800000120; valueType="int"; logs.clear();
}
int failures=0, checks=0;
void check(bool ok,const char* message){++checks; if(!ok){++failures; std::cout<<"FAIL: "<<message<<"\n";}}
'''
tests = r'''
int main(){
  reset(); comandaCalibrareCo2();
  check(frc==1 && writes==1 && deadline==0 && starts==1,"Cerere proaspata consumata inainte de FRC, masurarea reluata");
  comandaCalibrareCo2();
  check(frc==1,"Eveniment duplicat reciteste cheia consumata");
  reset(); deadline=1799999900; comandaCalibrareCo2();
  check(frc==0 && stops==0,"Cererea livrata dupa reconectare tarzie nu calibreaza");
  reset(); deadline=1800000000; comandaCalibrareCo2();
  check(frc==0,"Termenul exact este expirat");
  reset(); deadline=1800000121; comandaCalibrareCo2();
  check(frc==0,"Termenul mai mare de 120 secunde este respins");
  reset(); deadline=1800000100.5; comandaCalibrareCo2();
  check(frc==0,"Timestamp fractional invalid");
  reset(); clockValid=false; comandaCalibrareCo2();
  check(frc==0,"Ceasul nesincronizat nu autorizeaza calibrarea");
  reset(); writeOk=false; comandaCalibrareCo2();
  check(frc==0 && stops==0,"Esec la consumarea comenzii nu executa FRC");
  reset(); readOk=false; comandaCalibrareCo2();
  check(frc==0,"Esec de recitire nu executa FRC");
  reset(); connected=false; comandaCalibrareCo2();
  check(frc==0,"Deconectarea nu executa FRC dintr-un eveniment memorat");
  reset(); deadline=1800000002; writeDelay=2500; comandaCalibrareCo2();
  check(frc==0 && stops==0,"Termen reverificat dupa consumarea lenta");
  reset(); deadline=1800000001; beforeFrcDelay=500; comandaCalibrareCo2();
  check(frc==0 && starts==1,"Termen reverificat chiar inainte de FRC si masurarea reluata");
  reset(); valueType="boolean"; comandaCalibrareCo2();
  check(frc==0,"Tipul boolean nu este termen numeric");
  reset(); valueType="string"; comandaCalibrareCo2();
  check(frc==0,"Tipul text nu este termen numeric");
  reset(); epoch=2200000000; deadline=2200000120.0; comandaCalibrareCo2();
  check(frc==1,"Termenul Unix peste 2038 nu este trunchiat la int32");
  LEGACY_TEST
  std::cout<<checks<<" verificari, "<<failures<<" esecuri\n";
  return failures ? 1 : 0;
}
'''.replace("LEGACY_TEST", 'reset(); respingeCalibrareCo2Veche(); check(!legacy && frc==0,"Cheia booleana veche este consumata fara FRC");' if has_legacy_rejection else 'check(false,"Lipseste respingerea cheii booleene vechi");')
compiler = shutil.which("g++")
if not compiler:
    raise RuntimeError("Este necesar g++")
with tempfile.TemporaryDirectory(prefix="smartair-calibration-") as directory:
    folder = Path(directory)
    cpp, binary = folder / "calibration_test.cpp", folder / "calibration_test.exe"
    cpp.write_text(header + "\n".join(functions) + tests, encoding="utf-8")
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", str(cpp), "-o", str(binary)], check=True)
    raise SystemExit(subprocess.run([str(binary)]).returncode)
