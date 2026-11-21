"""Regresii pentru Night Mode: parserul si blocul real din loop, transport simulat."""
import ast
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

folder = Path(__file__).resolve().parent
source = (folder.parents[1] / "Esp_SmartPurifier.ino").read_text(encoding="utf-8")
# Refolosim numai adaptorul JSON din testele parserului, fara executarea lor.
tree = ast.parse((folder / "run_settings_tests.py").read_text(encoding="utf-8"))
header = next(ast.literal_eval(n.value) for n in tree.body
              if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "header" for t in n.targets))
functions = []
for name in ("aplicaSetariJson", "aplicaSetareSingura"):
    functions.append(re.search(r"^void " + name + r"\([^\n]+\) \{.*?^\}", source, re.M | re.S).group())
block = source.split("  // ---- Night Mode programat", 1)[1].split("  // ---- Bucla UI", 1)[0]
block = block[block.index("\n") + 1:]
adapter = r'''
#include <cstdint>
#include <ctime>
#include <vector>
bool firebaseGata=true, clockValid=true, connected=true, ready=true;
uint32_t tAntNmPublicare=0;
const int WL_CONNECTED=3;
int fbdoScriere=0, writes=0;
bool acceptWrite=false, serverNight=true;
std::vector<std::string> events;
bool ceasValid() { return clockValid; }
void logSystem(const char* s) { events.emplace_back(s); }
struct { int status() { return connected ? WL_CONNECTED : 0; } } WiFi;
struct {
  bool ready() { return ::ready; }
  struct {
    bool setBool(int*,const char* path,bool value) {
      verifica(std::string(path)=="/SenzoriAer/setari/night_mode", "Scriere limitata la frunza night_mode");
      ++writes;
      if(acceptWrite) serverNight=value;
      return acceptWrite;
    }
    // Vechiul apel accepta doar expedierea; serverul poate ramane neschimbat.
    bool setBoolAsync(int*,const char*,bool value) {
      ++writes;
      if(acceptWrite) serverNight=value;
      return true;
    }
  } RTDB;
} Firebase;
void ruleaza(uint32_t t,int h,int m) {
  tm timeinfo{}; timeinfo.tm_year=126; timeinfo.tm_hour=h; timeinfo.tm_min=m;
'''
tests = r'''
}
void snapshot(bool value) {
  FirebaseJson json;
  json.set("night_mode",value,FirebaseJson::BOOL);
  json.set("nm_auto",nmAuto,FirebaseJson::BOOL);
  json.set("nm_start",nmStartMin,FirebaseJson::INT);
  json.set("nm_end",nmEndMin,FirebaseJson::INT);
  aplicaSetariJson(json);
}
int main() {
  nmAuto=true; nightModeApp=true;
  snapshot(true);
  ruleaza(60000,11,27);
  verifica(!nightModeApp && events.size()==1,"Prima iesire reala din interval produce un singur eveniment");
  for(int minute=28;minute<=42;++minute) {
    snapshot(true);
    ruleaza((minute-26)*60000,11,minute);
  }
  verifica(!nightModeApp && events.size()==1,"Snapshot vechi la fiecare minut nu repeta oprirea");
  verifica(serverNight,"Scrierile esuate nu au fost confirmate de serverul simulat");
  int before=writes;
  snapshot(true);
  ruleaza(960001,11,42);
  verifica(writes==before,"Nu se repeta scrierea in fiecare iteratie dupa esec");
  acceptWrite=true;
  ruleaza(990000,11,43);
  verifica(!serverNight && writes==before+1,"Starea efectiva este retrimisa si confirmata fara o noua tranzitie");
  snapshot(false); ruleaza(1020000,11,44);
  verifica(events.size()==1 && writes==before+1,"Ecoul confirmarii nu produce eveniment sau scriere suplimentara");
  aplicaSetareSingura("/night_mode","true"); ruleaza(1050000,11,45);
  verifica(!nightModeApp && events.size()==1,"Evenimentul individual vechi nu invinge programarea");

  events.clear(); snapshot(false);
  ruleaza(1080000,22,59); verifica(!nightModeApp,"Inainte de inceput ramane oprit");
  ruleaza(1110000,23,0); verifica(nightModeApp && events.size()==1,"Inceput inclusiv, o singura pornire");
  snapshot(false); ruleaza(1140000,23,1);
  verifica(nightModeApp && events.size()==1,"Snapshot oprit vechi nu intrerupe noaptea");
  ruleaza(1170000,0,0); verifica(nightModeApp,"Intervalul traverseaza miezul noptii");
  ruleaza(1200000,6,59); verifica(nightModeApp,"Ultimul minut din interval ramane pornit");
  ruleaza(1230000,7,0); verifica(!nightModeApp && events.size()==2,"Sfarsit exclusiv, o singura oprire");

  aplicaSetareSingura("/nm_start","600"); aplicaSetareSingura("/nm_end","660");
  ruleaza(1260000,10,0); verifica(nightModeApp,"Interval in aceeasi zi: inceput");
  ruleaza(1290000,11,0); verifica(!nightModeApp,"Interval in aceeasi zi: sfarsit");
  aplicaSetareSingura("/nm_end","600");
  ruleaza(1320000,10,0); verifica(!nightModeApp,"Start egal cu stop ramane interval gol");

  nmStartMin=1380; nmEndMin=420;
  connected=false; before=writes;
  ruleaza(1350000,23,0);
  verifica(nightModeApp && writes==before,"Ora locala respecta programul fara WiFi");
  connected=true; ready=false;
  ruleaza(1380000,23,1); verifica(writes==before,"Token indisponibil: fara scriere");
  ready=true; ruleaza(1410000,23,2);
  verifica(serverNight,"Revenirea cloud publica starea fara alte tranzitii");
  clockValid=false; before=writes;
  snapshot(false); ruleaza(1440000,11,0);
  verifica(nightModeApp && writes==before,"Ceas invalid: pastreaza ultima stare efectiva, nu inventeaza interval");
  clockValid=true; ruleaza(1470000,11,0); verifica(!nightModeApp,"Sincronizarea orei reia programarea");

  aplicaSetareSingura("/nm_auto","false");
  aplicaSetareSingura("/night_mode","true"); ruleaza(1500000,11,0);
  verifica(nightModeApp,"Programare oprita: butonul manual porneste");
  snapshot(false); ruleaza(1530000,11,0);
  verifica(!nightModeApp,"Programare oprita: snapshot manual opreste");
  clockValid=false;
  aplicaSetareSingura("/night_mode","true"); ruleaza(1560000,11,0);
  verifica(nightModeApp,"Manual functioneaza si fara NTP");
  clockValid=true; aplicaSetareSingura("/nm_auto","true"); ruleaza(1590000,11,0);
  verifica(!nightModeApp,"Reactivarea programarii recalculeaza starea");

  // Rollover ESP32: uint32_t reproduce aritmetica millis, inclusiv pe un host de 64 biti.
  acceptWrite=false; snapshot(true); ruleaza(0xfffffff0u,11,0); before=writes;
  ruleaza(100,11,0); verifica(writes==before,"Rollover nu declanseaza retry prematur");
  ruleaza(30000,11,0); verifica(writes==before+1,"Retry dupa rollover la termen");
  std::cout<<verificari<<" verificari, "<<esecuri<<" esecuri\n";
  return esecuri ? 1 : 0;
}
'''
compiler = shutil.which("g++")
if not compiler:
    raise SystemExit("Este necesar g++ in PATH")
with tempfile.TemporaryDirectory(prefix="smartair-night-mode-") as tmp:
    cpp, binary = Path(tmp)/"night_mode.cpp", Path(tmp)/"night_mode.exe"
    cpp.write_text(header + adapter.split("void ruleaza", 1)[0] + "\n".join(functions)
                   + "\nvoid ruleaza" + adapter.split("void ruleaza", 1)[1] + block + tests, encoding="utf-8")
    subprocess.run([compiler,"-std=c++17","-Wall","-Wextra",str(cpp),"-o",str(binary)],check=True)
    raise SystemExit(subprocess.run([str(binary)]).returncode)
