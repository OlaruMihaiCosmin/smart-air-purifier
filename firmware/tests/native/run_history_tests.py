"""Executa functiile reale din sketch cu un adaptor String, fara acces la placa/cloud."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "Esp_SmartPurifier.ino").read_text(encoding="utf-8")
functions = []
for name in ("getPaddedHistory", "extrageArhivaIeri", "mergeIstoricCloudLocal", "combinaArhivaZi"):
    match = re.search(r"^String " + name + r"\([^\n]+\) \{.*?^\}", source, re.M | re.S)
    if not match:
        raise RuntimeError(f"Functia {name} nu a fost gasita")
    functions.append(match.group())
tacho = re.search(r"^void IRAM_ATTR tachoPoll\(void\* arg\) \{.*?^\}", source, re.M | re.S).group()
rpm = source.split("// ---- Tahometru (1 secunda) ----", 1)[1].split("// ---- Citire Senzor SEN66", 1)[0]
header = r'''
#include <string>
#include <algorithm>
#include <iostream>
#include <cstdint>
#include <ctime>
#include <sstream>
#include <iomanip>
#include <cstdlib>
class String {
  std::string s;
public:
  String(const char* p=""): s(p) {}
  String(std::string p): s(p) {}
  String(int n): s(std::to_string(n)) {}
  String(long n): s(std::to_string(n)) {}
  String(double n, int zecimale) {
    std::ostringstream out; out << std::fixed << std::setprecision(zecimale) << n; s=out.str();
  }
  size_t length() const { return s.size(); }
  long toInt() const { return std::strtol(s.c_str(), nullptr, 10); }
  int indexOf(char c, size_t from=0) const { auto i=s.find(c,from); return i==s.npos ? -1 : int(i); }
  String substring(size_t from, size_t to=std::string::npos) const {
    if(from>=s.size()) return "";
    return s.substr(from,to==s.npos ? to : to-from);
  }
  void remove(size_t from,size_t count) { s.erase(from,count); }
  char operator[](size_t i) const { return s[i]; }
  String& operator+=(const String& rhs) { s+=rhs.s; return *this; }
  friend String operator+(String a,const String& b) { a+=b; return a; }
  friend bool operator==(const String&a,const String&b) { return a.s==b.s; }
  friend bool operator!=(const String&a,const String&b) { return !(a==b); }
  const std::string& str() const { return s; }
};
#define IRAM_ATTR
#define HIGH 1
#define LOW 0
#define PIN_FAN_RPM 2
using byte = uint8_t;
volatile unsigned int validPulseCount=0;
volatile uint8_t tachoState=HIGH,lowSamples=0,highSamples=0;
int nivelPin=HIGH;
int digitalRead(int) { return nivelPin; }
uint32_t t=0,tAntRpm=0;
int currentFanSpeed=30,rpmReal=0;
int esecuri=0, verificari=0;
void verifica(bool ok,const char* mesaj) {
  ++verificari;
  if(!ok) { ++esecuri; std::cout<<"FAIL: "<<mesaj<<"\n"; }
}
int numar(const String& s) { return s.length() ? int(std::count(s.str().begin(),s.str().end(),','))+1 : 0; }
String serie(int n) { String s; for(int i=0;i<n;i++){if(i)s+=",";s+=String(std::to_string(i));} return s; }
'''
tests = r'''
int main() {
  auto scurt=extrageArhivaIeri("10,20",0);
  verifica(numar(scurt)==288,"Ziua partiala pastreaza 288 sloturi calendaristice");
  verifica(scurt.substring(scurt.length()-5)=="10,20","Probele de seara raman la sfarsitul zilei");
  verifica(numar(extrageArhivaIeri("",0))==288,"Ziua fara probe are 288 goluri");
  auto complet=serie(288);
  verifica(extrageArhivaIeri(complet,0)==complet,"Ziua completa nu este modificata");
  verifica(numar(extrageArhivaIeri(complet,12))==288,"Arhivarea intarziata pastreaza 288 sloturi");
  verifica(numar(getPaddedHistory(complet,100000,1000))==288,"Golul foarte lung ramane limitat");
  verifica(getPaddedHistory("10,20",900,330)=="10,20,-999",
           "O proba offline decalata cu 30s nu ascunde slotul lipsa de la minutul 10");
  verifica(getPaddedHistory("10,20",600,330)=="10,20",
           "Decalajul de 30s nu creeaza gol intre doua sloturi consecutive");
  verifica(getPaddedHistory("10,20",300,600)=="10,20",
           "Corectia inapoi a ceasului nu adauga goluri");
  verifica(mergeIstoricCloudLocal("1,2","3,4",2)=="1,2,-999,-999,3,4","Merge offline pastreaza golul dintre segmente");
  verifica(mergeIstoricCloudLocal(complet,complet,288)==complet,"Merge lung retine ultima fereastra");
  auto veche=extrageArhivaIeri("10,-999",0);
  auto noua=extrageArhivaIeri("-999,20",0);
  auto imbinata=combinaArhivaZi(veche,noua);
  verifica(imbinata.substring(imbinata.length()-5)=="10,20","Recuperarea completeaza goluri fara a sterge probe bune");
  verifica(combinaArhivaZi(complet,extrageArhivaIeri("",0))==complet,"O zi valida din cloud nu este inlocuita cu goluri");
  auto impuls=[](int nivel,int n) { nivelPin=nivel; for(int i=0;i<n;i++) tachoPoll(nullptr); };
  impuls(LOW,3);impuls(HIGH,4);
  verifica(validPulseCount==0,"Filtrul RPM respinge impulsul mai scurt de patru esantioane");
  impuls(LOW,4);impuls(LOW,100);impuls(HIGH,4);
  verifica(validPulseCount==1,"Nivelul LOW stabil numara un singur front");
  for(int i=0;i<100;i++){impuls(LOW,4);impuls(HIGH,4);}
  verifica(validPulseCount==101,"Filtrul RPM conserva trenul de impulsuri valide");
  for(int i=1;i<=3;i++){validPulseCount=10;t=i*1000;agregaRpm();}
  verifica(rpmReal==300,"Fereastra RPM pastreaza conversia existenta");
  currentFanSpeed=0;t+=1000;agregaRpm();
  verifica(rpmReal==0,"Oprirea goleste fereastra RPM");
  std::cout<<verificari<<" verificari, "<<esecuri<<" esecuri\n";
  return esecuri ? 1 : 0;
}
'''
def main():
    compiler = shutil.which("g++")
    if not compiler:
        raise RuntimeError("Este necesar g++ pentru testele native")
    with tempfile.TemporaryDirectory(prefix="smartair-native-") as directory:
        folder = Path(directory)
        cpp = folder / "history_test.cpp"
        binary = folder / "history_test.exe"
        cpp.write_text(header + "\n".join(functions) + tacho + "\nvoid agregaRpm(){" + rpm + "}\n" + tests, encoding="utf-8")
        subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Wno-unused-parameter", str(cpp), "-o", str(binary)], check=True)
        return subprocess.run([str(binary)]).returncode


if __name__ == "__main__":
    raise SystemExit(main())
