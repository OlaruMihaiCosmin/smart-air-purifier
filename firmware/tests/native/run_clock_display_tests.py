"""Executa blocul ceasului real; reproduce timeout-ul zero din core-ul ESP32.

Core 3.3.12 esp32-hal-time.c, getLocalTime: daca millis() avanseaza intre
start si conditia while, ms=0 returneaza false fara sa consulte time().
"""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "Esp_SmartPurifier.ino").read_text(encoding="utf-8")
block = source.split("  // ---- ORA + DATA ----", 1)[1].split("\n}", 1)[0]
cpp = r'''
#include <ctime>
#include <cstdint>
#include <string>
#include <iostream>
class String:public std::string {
public: using std::string::string;
 String(std::string s):std::string(s){} String(int x):std::string(std::to_string(x)){}
};
bool valid=true;
time_t epoch=1790584200;
bool ceasValid(){return valid;}
time_t fakeTime(time_t* p){if(p)*p=epoch;return epoch;}
#define time fakeTime
tm* localtime_r(const time_t* t,tm* out){*out=*std::gmtime(t);return out;}
// Boundary-ul de timeout reproduce intrarea ratata in bucla ms=0.
bool getLocalTime(tm*,uint32_t){return false;}
String old_time="",old_date="";
int FreeSansBold24pt7b=0,FreeSans9pt7b=0,ST77XX_WHITE=1,C_TEXT_DIM=2;
int bx_time=0,by_time=0,bw_time=0,bh_time=0,bx_date=0,by_date=0,bw_date=0,bh_date=0;
const char* getMonthNameRo(int){return "Sep";}
const char* getDayNameRo(int){return "Luni";}
template<class... T>void drawUpdatingTextCentered(T...){}
void afiseaza(){
''' + block + r'''
}
int main(){
 int failed=0;
 afiseaza();
 if(old_time.find(':')==std::string::npos){std::cerr<<"FAIL: Ora valida devine Sync la schimbarea milisecundei\n";++failed;}
 const auto before=old_time;
 epoch+=60;afiseaza();
 if(old_time==before){std::cerr<<"FAIL: Ceasul nu avanseaza dupa minut\n";++failed;}
 valid=false;old_time="";afiseaza();
 if(old_time.find(':')!=std::string::npos){std::cerr<<"FAIL: Boot nesincronizat afiseaza ora inventata\n";++failed;}
 std::cout<<"3 verificari afisare ceas, "<<failed<<" esecuri\n";return failed?1:0;
}
'''
with tempfile.TemporaryDirectory(prefix="smartair-clock-") as tmp:
    path=Path(tmp)/"clock.cpp"
    path.write_text(cpp,encoding="utf-8")
    exe=Path(tmp)/"clock.exe"
    subprocess.run([shutil.which("g++") or "g++","-std=c++17","-Wall","-Wextra",str(path),"-o",str(exe)],check=True)
    subprocess.run([str(exe)],check=True)
