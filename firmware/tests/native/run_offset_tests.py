"""Executa aplicarea reala a offsetului cu erori I2C injectate, fara hardware."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "Esp_SmartPurifier.ino").read_text(encoding="utf-8")
function = re.search(r"^void aplicaOffsetTemp\([^\n]+\) \{.*?^\}", source, re.M | re.S).group()
globals_ = source.split("bool offsetFortatLaBoot = false;", 1)[1].split("void aplicaOffsetTemp", 1)[0]
retry = source.split("// Reincercarea este limitata", 1)[1].split("// ---- Salvare calibrare VOC", 1)[0].split("\n", 1)[1]
sensor_reset = re.search(r"    offsetFortatLaBoot = false;\s+offsetIncercat = false;\s+aplicaOffsetTemp\([^\n]+\);", source).group()
telemetry = re.search(r"    if \(offsetFortatLaBoot\) jsonLive.set\(\"Setari_Offset_Temp\"[^\n]+\n    else [^\n]+", source).group()
header = r'''
#include <cmath>
#include <cstdint>
#include <iostream>
#include <string>
#include <vector>
using std::isfinite;
class String {
  std::string s;
public:
  String(const char* p=""):s(p){}
  String(std::string p):s(p){}
  String(float p):s(std::to_string(p)){}
  String(int p):s(std::to_string(p)){}
  friend String operator+(const String& a,const String& b){return a.s+b.s;}
  const std::string& str()const{return s;}
};
uint32_t clockMs=0;
uint32_t millis(){return clockMs++;}
void yield(){}
bool offsetFortatLaBoot=false;
float offsetTemperatura=0;
struct Storage {
  float saved=0; int writes=0;
  void putFloat(const char*,float v){saved=v;++writes;}
} storace;
struct Sensor {
  bool fail=true; int calls=0; int16_t applied=0;
  int16_t setTemperatureOffsetParameters(int16_t value,int,int,int){
    ++calls;if(fail)return 123;applied=value;return 0;
  }
} sen66;
std::vector<std::string> logs;
void logSystem(String text){logs.push_back(text.str());}
int checks=0, failures=0;
void check(bool ok,const char* name){++checks;if(!ok){++failures;std::cout<<"FAIL: "<<name<<"\n";}}
bool successLogged(){for(const auto& s:logs)if(s.find("modificat")!=s.npos)return true;return false;}
struct JsonLive {
  bool available=false; float reported=0;
  void set(const char*,float value){available=true;reported=value;}
  void set(const char*){available=false;}
};
'''
tests = r'''
int main(){
  aplicaOffsetTemp(-0.8f);
  check(!offsetFortatLaBoot && offsetTemperatura==0 && storace.saved==0 && storace.writes==0,
        "Eroarea I2C nu confirma si nu persista un offset neaplicat");
  check(!successLogged(),"Eroarea I2C nu produce jurnal de succes");
  check(!telemetrie().available,"Telemetria nu publica valoarea neconfirmata la boot");
  const int dupaEsuare=sen66.calls;
  for(int i=0;i<50;++i)aplicaOffsetTemp(-0.8f);
  check(sen66.calls==dupaEsuare,"Reincercarile apropiate nu incarca magistrala");
  clockMs+=10001;sen66.fail=false;aplicaOffsetTemp(-0.8f);
  check(sen66.calls==dupaEsuare+1 && sen66.applied==-160 && offsetFortatLaBoot &&
        offsetTemperatura==-0.8f && storace.saved==-0.8f && storace.writes==1,
        "Aceeasi cerere este reexecutata si confirmata dupa revenirea I2C");
  check(telemetrie().available && telemetrie().reported==-0.8f,"Telemetria publica numai valoarea aplicata cu succes");
  aplicaOffsetTemp(-0.8f);
  check(sen66.calls==dupaEsuare+1 && storace.writes==1,"Offsetul confirmat nu produce scrieri repetate");
  clockMs+=10001;sen66.fail=true;aplicaOffsetTemp(-1.2f);
  check(offsetTemperatura==-0.8f && storace.saved==-0.8f && sen66.applied==-160,
        "O schimbare esuata pastreaza ultima valoare confirmata");
  check(telemetrie().available && telemetrie().reported==-0.8f,"Schimbarea esuata continua sa raporteze ultima confirmare");
  clockMs+=10001;sen66.fail=false;aplicaOffsetTemp(-1.2f);
  check(sen66.applied==-240 && offsetTemperatura==-1.2f && storace.saved==-1.2f,
        "Schimbarea noua se confirma numai dupa succes");
  const int inainteInvalid=sen66.calls;
  for(float v:{NAN,INFINITY,-3.1f,3.1f})aplicaOffsetTemp(v);
  check(sen66.calls==inainteInvalid,"Valorile invalide nu ajung pe I2C");
  sen66.applied=0;sen66.fail=true;clockMs+=10001;
  dupaResetSenzor();
  check(!offsetFortatLaBoot && !telemetrie().available,"Resetul senzorului nu confirma reaplicarea esuata");
  clockMs+=10001;sen66.fail=false;reincearcaDinLoop();
  check(offsetFortatLaBoot && sen66.applied==-240 && storace.writes==2,
        "Reaplicarea dupa reset confirma senzorul fara rescriere NVS inutila");
  const int inainteBackoff=sen66.calls;
  aplicaOffsetTemp(-1.8f);
  check(sen66.calls==inainteBackoff && offsetSolicitatTemp==-1.8f,"Cererea noua in backoff ramane memorata fara trafic I2C");
  clockMs+=10001;reincearcaDinLoop();
  check(sen66.applied==-360 && storace.saved==-1.8f && telemetrie().reported==-1.8f,
        "Retry din loop aplica cererea noua fara un nou eveniment Firebase");
  clockMs+=10001;sen66.fail=true;aplicaOffsetTemp(-2.0f);
  const int scrieriInainteReset=storace.writes;
  sen66.applied=0;dupaResetSenzor();
  check(offsetSolicitatTemp==-2.0f && !offsetFortatLaBoot,"Resetul senzorului pastreaza cererea mai noua inca neaplicata");
  clockMs+=10001;sen66.fail=false;reincearcaDinLoop();
  check(sen66.applied==-400 && storace.saved==-2.0f && storace.writes==scrieriInainteReset+1,
        "Dupa reset retry confirma si persista cererea pastrata");
  clockMs=0xfffffff0u;sen66.fail=true;aplicaOffsetTemp(-2.2f);
  const int incercariRollover=sen66.calls;
  clockMs=9000;reincearcaDinLoop();
  check(sen66.calls==incercariRollover,"Rollover millis nu scurteaza pauza retry offset");
  clockMs=10000;sen66.fail=false;reincearcaDinLoop();
  check(sen66.applied==-440 && offsetFortatLaBoot,"Retry offset functioneaza peste rollover millis");
  std::cout<<checks<<" verificari, "<<failures<<" esecuri\n";
  return failures?1:0;
}
'''
compiler = shutil.which("g++")
if not compiler:
    raise RuntimeError("Este necesar g++ pentru testele native")
with tempfile.TemporaryDirectory(prefix="smartair-offset-") as directory:
    folder = Path(directory)
    cpp, binary = folder / "offset_test.cpp", folder / "offset_test.exe"
    wrappers = "\nvoid reincearcaDinLoop(){" + retry + "}\nvoid dupaResetSenzor(){" + sensor_reset + "}\nJsonLive telemetrie(){JsonLive jsonLive;\n" + telemetry + "\nreturn jsonLive;}\n"
    cpp.write_text(header + globals_ + function + wrappers + tests, encoding="utf-8")
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", str(cpp), "-o", str(binary)], check=True)
    raise SystemExit(subprocess.run([str(binary)]).returncode)
