"""Verifica expresiile de warmup din sketch inclusiv dupa o rotatie completa millis."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "Esp_SmartPurifier.ino").read_text(encoding="utf-8")
age = re.search(r"t_umid, t_temp, t_voc, t_nox, t_co2, (.*)\);", source).group(1)
display = re.search(r"bool inWarmup = (.*);", source).group(1)
alert = re.search(r"if \(!fcmActiv \|\| (.*)\) return;", source).group(1)
helper = re.search(r"^uint32_t varstaSenzor\([^\n]+\) \{.*?^\}", source, re.M | re.S)
header = r'''
#include "SmartAirReliability.h"
#include <iostream>
uint32_t tSenzorStart=0;
bool warmupSenzorTerminat=false;
int checks=0,failures=0;
void check(bool ok,const char* name){++checks;if(!ok){++failures;std::cout<<"FAIL: "<<name<<"\n";}}
'''
tests = r'''
int main(){
  tSenzorStart=100;
  check(!reading(20099).existaDate(),"Boot: toate canalele asteapta primele 20 secunde");
  auto partial=reading(20100);
  check(partial.pm && partial.co2 && !partial.temperatura,"Boot: PM si CO2 pornesc la 20 secunde");
  check(screenWarm(60099) && alertsWarm(60099),"Ecranul si alertele asteapta 60 secunde");
  check(reading(60100).temperatura && !screenWarm(60100) && !alertsWarm(60100),"Warmup incheiat dupa 60 secunde");
  reading(0xfffffff0u);
  auto rollover=reading(10100);
  check(rollover.pm && rollover.temperatura && rollover.voc,"Rotatia completa millis nu reia warmup");
  check(!screenWarm(10100) && !alertsWarm(10100),"Ecranul si alertele raman active dupa rotatia completa");
  tSenzorStart=11000;warmupSenzorTerminat=false;
  check(!reading(11000).existaDate() && screenWarm(11000),"Reset real: warmup reincepe");
  check(reading(71000).temperatura,"Reset real: date valide dupa noul warmup");
  tSenzorStart=0xfffffff0u;warmupSenzorTerminat=false;
  check(!reading(9984u).existaDate(),"Warmup inceput langa rollover pastreaza cele 20 secunde");
  check(reading(59984u).temperatura,"Warmup inceput langa rollover se incheie dupa 60 secunde");
  std::cout<<checks<<" verificari, "<<failures<<" esecuri\n";return failures?1:0;
}
'''
compiler = shutil.which("g++")
if not compiler:
    raise RuntimeError("Este necesar g++ pentru testele native")
with tempfile.TemporaryDirectory(prefix="smartair-warmup-") as directory:
    folder = Path(directory)
    cpp, binary = folder / "warmup_test.cpp", folder / "warmup_test.exe"
    wrappers = f"""
    ValiditateSenzor reading(uint32_t t){{return valideazaCitire(1,2,3,4,45,21,100,1,500,{age});}}
    bool screenWarm(uint32_t t){{return {display};}}
    bool alertsWarm(uint32_t t){{return {alert};}}
    """
    cpp.write_text(header + (helper.group() if helper else "") + wrappers + tests, encoding="utf-8")
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-I", str(root), str(cpp), "-o", str(binary)], check=True)
    raise SystemExit(subprocess.run([str(binary)]).returncode)
