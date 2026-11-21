"""Executa traseul real de istoric din loop(), cu timp si transport RTDB simulate.

Nu simuleaza senzorul, reteaua/TLS sau semantica interna FirebaseJson. Conservarea
arborelui RTDB se verifica separat in run_archive_patch_tests.py, pe emulator.
Fiecare scenariu ruleaza intr-un proces nou, inclusiv variabilele static din sketch.
"""
from pathlib import Path
import argparse
import re
import shutil
import subprocess
import tempfile

import run_history_tests as unit


def extract_function(source, name):
    found = re.search(r"^(?:void|String) " + name + r"\([^\n]+\) \{.*?^\}", source, re.M | re.S)
    if not found:
        raise RuntimeError("Functia lipseste: " + name)
    return found.group()


ADAPTER = r'''
#include <map>
#include <vector>
#include <type_traits>
#include "SmartAirReliability.h"
bool operator>(const String& a, const String& b) { return a.str()>b.str(); }
template<class T> T constrain(T v, T lo, T hi) { return std::clamp(v, lo, hi); }
uint32_t ceasMillis=0;
time_t ceasEpoch=0;
bool oraValida=true;
uint32_t millis() { return ceasMillis; }
time_t fakeTime(time_t* rezultat) { if(rezultat) *rezultat=ceasEpoch; return ceasEpoch; }
tm* localtime_r(const time_t* timp, tm* rezultat) { *rezultat=*std::localtime(timp); return rezultat; }
#define time fakeTime
bool ceasValid() { return oraValida; }
constexpr int WL_CONNECTED=3, FIREBASE_ERROR_PATH_NOT_EXIST=-103;
struct { bool conectat=true; int status() { return conectat ? WL_CONNECTED : 0; } } WiFi;
struct FirebaseJsonData { bool success=false; String stringValue; };
struct FirebaseJson {
  std::map<std::string,String> fields;
  void set(const String& key, const String& value) { fields[key.str()]=value; }
  template<class T, typename=std::enable_if_t<std::is_arithmetic_v<T>>>
  void set(const String& key, T value) { fields[key.str()]=std::to_string(value); }
  void add(const String& key, const String& value) { fields[key.str()]=value; }
  bool get(FirebaseJsonData& result, const String& key) {
    auto it=fields.find(key.str());
    if(it==fields.end()) return false;
    result={true,it->second}; return true;
  }
};
struct FirebaseData {
  int code=200; String type="null", value;
  FirebaseJson object;
  int httpCode() { return code; }
  String dataType() { return type; }
  long intData() { return value.toInt(); }
  String stringData() { return value; }
  FirebaseJson& jsonObject() { return object; }
} fbdoCitire, fbdoScriere;
struct Node { String type="string", value; };
struct Transport {
  std::map<std::string,Node> nodes;
  std::vector<FirebaseJson> archiveAttempts;
  std::vector<FirebaseJson> historyAttempts;
  std::string failPath;
  int readFailures=0, writeFailures=0, reads=0, markerWrites=0;
  bool get(FirebaseData* out, const String& path) {
    ++reads; *out=FirebaseData{};
    if(path.str()==failPath && readFailures>0) { --readFailures; out->code=503; return false; }
    auto it=nodes.find(path.str());
    if(it!=nodes.end()) { out->type=it->second.type; out->value=it->second.value; return true; }
    const std::string prefix=path.str()+"/";
    for(const auto& entry:nodes)
      if(entry.first.find(prefix)==0) out->object.fields[entry.first.substr(prefix.size())]=entry.second.value;
    if(!out->object.fields.empty()) { out->type="json"; return true; }
    out->code=FIREBASE_ERROR_PATH_NOT_EXIST; return false;
  }
  bool updateNodeSilent(FirebaseData*, const String& path, FirebaseJson* packet) {
    archiveAttempts.push_back(*packet);
    if(writeFailures>0) { --writeFailures; return false; }
    for(const auto& entry:packet->fields) nodes[path.str()+"/"+entry.first]={"string",entry.second};
    return true;
  }
  bool updateNodeAsync(FirebaseData*, const String&, FirebaseJson* packet) {
    historyAttempts.push_back(*packet); return true;
  }
  bool setString(FirebaseData*, const String& path, const String& value) {
    ++markerWrites; nodes[path.str()]={"string",value}; return true;
  }
  bool setStringAsync(FirebaseData*, const String& path, const String& value) {
    nodes[path.str()]={"string",value}; return true;
  }
};
struct { Transport RTDB; bool gata=true; bool ready() { return gata; } } Firebase;
bool firebaseGata=true, istoricRecuperat=false, forceFullRedraw=false, bootLogSent=false;
bool eroriCuratateLaOra=false;
String motivRestart="test", jurnalErori;
String istPM25,istCO2,istVOC,istNOx,istTemp,istUmid,istPM1,istPM4,istPM10;
String lastArchivedDateStr;
String arhivaDiagnosticInAsteptare;
long lastIstoricSaveTime=0;
time_t ultimulSlotSalvat=0;
int puncteLocale=0;
uint32_t tAntSalvareLocala=0,millisPrimaSalvareLocala=0,millisUltimaSalvareLocala=0;
float sumPM1=0,sumPM25=0,sumPM4=0,sumPM10=0,sumTemp=0,sumUmid=0,sumVOC=0,sumNOx=0;
unsigned long sumCO2=0;
int countPM=0,countCO2=0,countTemp=0,countUmid=0,countVOC=0,countNOx=0;
bool primaFereastraPm=true;
void updateState(const char*) {}
void logSystem(const String&) {}
// Jurnalul este izolat aici; functiile reale au propria suita de teste.
bool jurnalRecuperat=false, jurnalModificat=false;
void trimiteJurnalDacaNecesar() {}
void importaJurnalCloud(const String& valoare) { jurnalErori=valoare; jurnalRecuperat=true; }
void recupereazaJurnal(const String& valoare) { importaJurnalCloud(valoare); }
String* seriiTest[]={&istPM25,&istCO2,&istVOC,&istNOx,&istTemp,&istUmid,&istPM1,&istPM4,&istPM10};
const char* cheiTest[]={"Istoric_PM25","Istoric_CO2","Istoric_VOC","Istoric_NOx","Istoric_Temp","Istoric_Umid","Istoric_PM1","Istoric_PM4","Istoric_PM10"};
const char* canaleTest[]={"PM25","CO2","VOC","NOx","Temp","Umid"};
time_t epoch(int zi,int ora=0,int minut=0,int secunda=0) {
  tm data{}; data.tm_year=126; data.tm_mon=8; data.tm_mday=zi;
  data.tm_hour=ora; data.tm_min=minut; data.tm_sec=secunda; data.tm_isdst=-1;
  return std::mktime(&data);
}
void ceas(time_t ep,uint32_t ms) { ceasEpoch=ep; ceasMillis=ms; }
void punct(float valoare=50) {
  countPM=2; sumPM1=sumPM25=sumPM4=sumPM10=valoare*2;
  countCO2=countTemp=countUmid=countVOC=countNOx=1;
  sumCO2=(unsigned long)valoare; sumTemp=sumUmid=sumVOC=sumNOx=valoare;
}
void serii(const String& s) { for(auto serie:seriiTest) *serie=s; }
String token(const String& s,int indice) {
  int start=0;
  for(int i=0;i<indice;++i) { int end=s.indexOf(',',start); if(end<0)return ""; start=end+1; }
  int end=s.indexOf(',',start); return s.substring(start,end<0?s.length():end);
}
void cloud(time_t ultim, const String& s="10,20", const String& zi="2026_09_26") {
  auto& db=Firebase.RTDB.nodes;
  db["/SenzoriAer/last_history_time"]={"int",std::to_string(ultim)};
  db["/SenzoriAer/last_archived_date"]={"string",zi};
  db["/SenzoriAer/ErrorLog"]={"string","istoric independent"};
  for(auto key:cheiTest) db[std::string("/SenzoriAer/")+key]={"string",s};
}
String arhiva(int incercare,const char* zi,const char* canal="PM25") {
  return Firebase.RTDB.archiveAttempts.at(incercare).fields[std::string("Arhiva_Zilnica/")+zi+"/"+canal];
}
'''

TESTS = r'''
void sincronizeaza() { for(int i=0;i<12;++i) istoricLoop(); }
int main(int argc,char** argv) {
  if(argc!=2)return 2;
  _putenv_s("TZ","UTC0"); _tzset();
  const std::string scenariu=argv[1];
  if(scenariu=="merge-gap") {
    ceas(epoch(26,0,20,30),1230000); tAntSalvareLocala=ceasMillis;
    cloud(epoch(26,0,5,30)); serii("30,40"); puncteLocale=2; millisUltimaSalvareLocala=1200000;
    sincronizeaza();
    verifica(istoricRecuperat,"Recuperarea se incheie numai dupa toate cele 12 citiri");
    for(auto s:seriiTest) verifica(*s=="10,20,-999,30,40","Fuziunea reala conserva slotul lipsa intre cloud si RAM");
    verifica(lastIstoricSaveTime==epoch(26,0,20),"Ultima proba locala primeste timestampul reconstituit");
    verifica(puncteLocale==0,"Contorul local se consuma dupa fuziune");
  } else if(scenariu=="boot-short") {
    WiFi.conectat=false; oraValida=false;
    ceas(epoch(26,0,5,30),330000); punct(30); istoricLoop();
    ceas(epoch(26,0,10,30),630000); punct(40); istoricLoop();
    verifica(puncteLocale==2 && istPM25=="30.0,40.0","Boot offline salveaza mediile reale in RAM");
    verifica(Firebase.RTDB.reads==0,"Boot fara ora/retea nu citeste cloud");
    cloud(epoch(26,0,0)); WiFi.conectat=true; oraValida=true;
    ceas(epoch(26,0,11),660000); sincronizeaza();
    verifica(istPM25=="10,20,30.0,40.0","Boot sub 24h combina cloud cu toate probele locale");
    verifica(lastIstoricSaveTime==epoch(26,0,10,30),"Timestampul offline nealiniat ramane exact");
    ceas(epoch(26,0,20),1200000); punct(50); istoricLoop();
    verifica(istPM25=="10,20,30.0,40.0,-999,50.0","Dupa sincronizare salvarea pastreaza golul si media noua");
  } else if(scenariu=="boot-long") {
    WiFi.conectat=false; oraValida=false;
    for(int i=1;i<=300;++i) { ceas(epoch(25)+i*300+30,i*300000U+30000); punct(float(i)); istoricLoop(); }
    verifica(puncteLocale==288 && numar(istPM25)==288,"Boot de 25h limiteaza RAM si contorul la 288");
    verifica(token(istPM25,0)=="13.0" && token(istPM25,287)=="300.0","Fereastra lunga retine ultimele 288 medii");
    cloud(epoch(24)); WiFi.conectat=true; oraValida=true; sincronizeaza();
    verifica(numar(istPM25)==288 && token(istPM25,0)=="13.0","Merge peste 24h nu reintroduce cloud-ul expirat");
    verifica(lastIstoricSaveTime==epoch(25)+300*300+30,"Ultima proba dupa 25h foloseste ultima salvare retinuta");
  } else if(scenariu=="read-retry") {
    ceas(epoch(26,1,1),100000); cloud(epoch(26,0,45)); serii("30,40");
    puncteLocale=2; millisUltimaSalvareLocala=40000;
    Firebase.RTDB.failPath="/SenzoriAer/Istoric_NOx"; Firebase.RTDB.readFailures=1;
    sincronizeaza();
    verifica(!istoricRecuperat && istPM25=="30,40","Citirea partiala nu inlocuieste seria locala");
    int citiri=Firebase.RTDB.reads; ceasMillis+=9999; istoricLoop();
    verifica(Firebase.RTDB.reads==citiri,"Recuperarea respecta backoff-ul de 10s");
    ceasMillis++; ceasEpoch+=10; sincronizeaza();
    verifica(istoricRecuperat,"Recuperarea reia toate campurile dupa esec");
    for(auto s:seriiTest) verifica(*s=="10,20,-999,30,40","Reluarea nu dubleaza segmentul local sau norul");
  } else if(scenariu=="regular-gap") {
    istoricRecuperat=true; lastArchivedDateStr="2026_09_26";
    lastIstoricSaveTime=epoch(26,0,5,30); serii("10,20");
    ceas(epoch(26,0,15),900000); punct(50); istoricLoop();
    verifica(istPM25=="10,20,-999,50.0","Salvarea regulata identifica slotul lipsa dupa proba decalata");
    verifica(istCO2=="10,20,-999,50","Canalele intregi raman aliniate cu PM");
    verifica(lastIstoricSaveTime==epoch(26,0,15),"Timestampul regulat este aliniat la slot");
    verifica(Firebase.RTDB.historyAttempts.size()==1,"Salvarea publica un singur pachet pentru cele noua canale");
    verifica(Firebase.RTDB.historyAttempts[0].fields.size()==10,"Pachetul contine noua serii si timestamp");
    istoricLoop(); verifica(Firebase.RTDB.historyAttempts.size()==1,"Acelasi slot nu este salvat de doua ori");
    ceas(epoch(26,0,10),1000000); punct(60); istoricLoop();
    verifica(Firebase.RTDB.historyAttempts.size()==1 && countPM==2,"Corectia inapoi in aceeasi zi nu consuma media");
    ceas(epoch(26,0,20),1300000); istoricLoop();
    verifica(istPM25=="10,20,-999,50.0,60.0","Salvarea se reia cand ceasul depaseste ultimul slot");
  } else if(scenariu=="archive-gap") {
    istoricRecuperat=true; lastArchivedDateStr="2026_09_25";
    lastIstoricSaveTime=epoch(26,0,0,30); serii("11,22,33");
    ceas(epoch(26,0,10),600000); punct(50); istoricLoop();
    auto zi=arhiva(0,"2026_09_25");
    verifica(numar(zi)==288,"Arhiva calculata prin loop are 288 sloturi");
    verifica(token(zi,286)=="11" && token(zi,287)=="22","Formula de pregatire elimina ambele sloturi de azi");
    verifica(lastArchivedDateStr=="2026_09_26","Marcajul avanseaza dupa scrierea confirmata");
    verifica(istPM25=="11,22,33,-999,50.0","Arhivarea nu modifica seria rulanta inainte de salvare");
  } else if(scenariu=="midnight-retry") {
    istoricRecuperat=true; lastArchivedDateStr="2026_09_26";
    lastIstoricSaveTime=epoch(26,23,55,30); serii("11,22");
    for(auto canal:canaleTest) Firebase.RTDB.nodes[std::string("/SenzoriAer/Arhiva_Zilnica/2026_09_26/")+canal]={"string",extrageArhivaIeri("99,-999",0)};
    Firebase.RTDB.writeFailures=1; ceas(epoch(27),100000); punct(50); istoricLoop();
    verifica(Firebase.RTDB.archiveAttempts.size()==1 && lastArchivedDateStr=="2026_09_26","Esec la miezul noptii nu avanseaza marcajul");
    verifica(arhivaDiagnosticInAsteptare=="2026_09_26","Diagnosticul pastreaza ziua neconfirmata dupa esec");
    auto prima=arhiva(0,"2026_09_26");
    verifica(token(prima,286)=="99" && token(prima,287)=="22","Arhiva combina numai golurile zilei existente");
    verifica(istPM25=="11,22,50.0","Proba de la miezul noptii se salveaza dupa pregatirea zilei precedente");
    ceas(epoch(27,0,0,29),129999); istoricLoop();
    verifica(Firebase.RTDB.archiveAttempts.size()==1,"Arhiva respecta pauza de 30s dupa esec");
    ceas(epoch(27,0,0,30),130000); istoricLoop();
    verifica(Firebase.RTDB.archiveAttempts.size()==2 && arhiva(1,"2026_09_26")==prima,"Retrimiterea pastreaza ziua si probele pregatite");
    verifica(arhivaDiagnosticInAsteptare=="","Diagnosticul goleste ziua in asteptare numai dupa ACK");
    verifica(lastArchivedDateStr=="2026_09_27","Succesul ulterior confirma marcajul");
    ceas(epoch(27,0,0,59),159000); istoricLoop();
    verifica(Firebase.RTDB.archiveAttempts.size()==2,"Ziua confirmata nu este retrimisa");
  } else if(scenariu=="offline-midnight") {
    istoricRecuperat=true; lastArchivedDateStr="2026_09_26";
    lastIstoricSaveTime=epoch(26,23,55); serii("11,22"); WiFi.conectat=false;
    ceas(epoch(27),100000); punct(50); istoricLoop();
    verifica(Firebase.RTDB.archiveAttempts.empty() && lastArchivedDateStr=="2026_09_26","Miezul noptii offline pregateste fara publicare");
    verifica(arhivaDiagnosticInAsteptare=="2026_09_26","Diagnosticul vede ziua pregatita offline");
    ceas(epoch(27,2),7300000); punct(60); istoricLoop();
    WiFi.conectat=true; ceas(epoch(27,2,0,1),7301000); istoricLoop();
    auto zi=arhiva(0,"2026_09_26");
    verifica(token(zi,286)=="11" && token(zi,287)=="22","Revenirea dupa 2h pastreaza coada reala a zilei precedente");
    verifica(lastArchivedDateStr=="2026_09_27","Revenirea confirma data corecta");
  } else if(scenariu=="offline-two-days") {
    istoricRecuperat=true; lastArchivedDateStr="2026_09_24";
    lastIstoricSaveTime=epoch(24,23,55); serii("11,22"); WiFi.conectat=false;
    for(int i=0;i<=600;++i) { ceas(epoch(25)+i*300,100000+i*300000U); punct(float(i+1)); istoricLoop(); }
    verifica(numar(istPM25)==288 && Firebase.RTDB.archiveAttempts.empty(),"Pana de peste 48h ramane limitata la RAM");
    WiFi.conectat=true; ceasMillis++; ceasEpoch++; istoricLoop();
    verifica(token(arhiva(0,"2026_09_24"),287)=="22","Prima zi in asteptare nu este reetichetata dupa 48h");
    ceasMillis+=30000; ceasEpoch+=30; istoricLoop();
    verifica(Firebase.RTDB.archiveAttempts.size()==2,"Dupa confirmare se pregateste ieri din fereastra inca disponibila");
    verifica(numar(arhiva(1,"2026_09_26"))==288,"Ziua recuperabila ramane calendaristica");
    verifica(lastArchivedDateStr=="2026_09_27","Marcajul ajunge la ziua curenta dupa revenirea lunga");
    verifica(Firebase.RTDB.nodes.count("/SenzoriAer/Arhiva_Zilnica/2026_09_25/PM25")==0,"Limita explicita: o zi iesita din RAM nu este inventata");
  } else if(scenariu=="backward-date") {
    istoricRecuperat=true; lastArchivedDateStr="2026_09_27";
    lastIstoricSaveTime=epoch(27,12); serii("11,22");
    ceas(epoch(26,12),100000); punct(50); istoricLoop();
    verifica(Firebase.RTDB.archiveAttempts.empty(),"Corectia datei inapoi nu publica probe viitoare sub o zi veche");
    verifica(lastArchivedDateStr=="2026_09_27","Corectia datei inapoi nu regreseaza marcajul confirmat");
    verifica(Firebase.RTDB.historyAttempts.empty() && countPM==2,"Data inapoi nu publica si nu consuma media curenta");
    ceas(epoch(27,12,5),100001); istoricLoop();
    verifica(istPM25=="11,22,50.0" && Firebase.RTDB.historyAttempts.size()==1,"Salvarea reia media cand data depaseste ultimul slot");
    verifica(Firebase.RTDB.archiveAttempts.empty(),"Revenirea la data confirmata nu arhiveaza o zi din nou");
  } else if(scenariu=="future-samples") {
    istoricRecuperat=true; lastArchivedDateStr="2026_09_25";
    lastIstoricSaveTime=epoch(27,12); serii("11,22");
    ceas(epoch(26,12),100000); istoricLoop();
    verifica(Firebase.RTDB.archiveAttempts.empty(),"Un marcaj restant nu permite arhivarea probelor din viitor");
    verifica(lastArchivedDateStr=="2026_09_25","Marcajul ramane nemodificat pana cand timpul ajunge la probe");
    ceas(epoch(27,12,5),100001); istoricLoop();
    verifica(Firebase.RTDB.archiveAttempts.size()==1 && lastArchivedDateStr=="2026_09_27","Arhivarea restanta se reia dupa corectarea ceasului");
    verifica(arhiva(0,"2026_09_26")==extrageArhivaIeri("",0),"Probele de azi nu devin probele zilei precedente la reluare");
  } else if(scenariu=="pending-backward-date") {
    istoricRecuperat=true; lastArchivedDateStr="2026_09_26";
    lastIstoricSaveTime=epoch(26,23,55); serii("11,22"); WiFi.conectat=false;
    ceas(epoch(27),100000); punct(50); istoricLoop();
    verifica(Firebase.RTDB.archiveAttempts.empty(),"Ziua corecta se pregateste offline inaintea corectiei ceasului");
    WiFi.conectat=true; ceas(epoch(26,12),100001); punct(60); istoricLoop();
    verifica(Firebase.RTDB.archiveAttempts.size()==1,"Arhiva pregatita anterior poate fi confirmata dupa corectia ceasului");
    auto zi=arhiva(0,"2026_09_26");
    verifica(token(zi,286)=="11" && token(zi,287)=="22","Arhiva in asteptare pastreaza data si probele originale");
    verifica(lastArchivedDateStr=="2026_09_27","Confirmarea arhivei in asteptare nu foloseste data regresata");
    verifica(Firebase.RTDB.nodes.count("/SenzoriAer/Arhiva_Zilnica/2026_09_25/PM25")==0,"Corectia ceasului nu reeticheteaza arhiva deja pregatita");
    verifica(Firebase.RTDB.historyAttempts.empty() && countPM==2,"Proba noua asteapta depasirea ultimului slot corect");
    ceas(epoch(27,0,5),100002); istoricLoop();
    verifica(Firebase.RTDB.archiveAttempts.size()==1 && istPM25=="11,22,50.0,60.0","Revenirea ceasului nu dubleaza arhiva si reia istoricul");
  } else return 2;
  std::cout<<scenariu<<": "<<verificari<<" verificari, "<<esecuri<<" esecuri\n";
  return esecuri ? 1 : 0;
}
'''

SCENARIOS = ("merge-gap", "boot-short", "boot-long", "read-retry", "regular-gap",
             "archive-gap", "midnight-retry", "offline-midnight", "offline-two-days", "backward-date", "future-samples",
             "pending-backward-date")

# Revenirea individuala la formulele vechi trebuie detectata de scenarii reale.
MUTATIONS = (
    ("padding", "(tNow / 300) - (lastSave / 300) - 1", "(tNow - lastSave) / 300 - 1", "archive-gap"),
    ("merge", "(int)(primaLocala / 300 - timpCloud / 300) - 1", "(int)((primaLocala - timpCloud) / 300) - 1", "merge-gap"),
    ("archive", "(long)(tNow/300-lastIstoricSaveTime/300)-1", "(long)((tNow-lastIstoricSaveTime)/300)-1", "archive-gap"),
    ("regular", "slotCurent - (lastIstoricSaveTime / 300) - 1", "(slotCurent * 300 - lastIstoricSaveTime) / 300 - 1", "regular-gap"),
)


def program(source):
    names = ("getPaddedHistory", "extrageArhivaIeri", "adaugaInIstoric",
             "salveazaPunctIstoric", "combinaArhivaZi", "mergeIstoricCloudLocal")
    functions = "\n".join(extract_function(source, name) for name in names)
    marker = "  // ---- Istoric local cand (inca) nu exista conexiune cu Firebase ----"
    if source.count(marker) != 1:
        raise RuntimeError("Blocul de istoric s-a schimbat; revizuiti extragerea")
    tail = source.split(marker, 1)[1]
    return (unit.header + ADAPTER + functions +
            "\nvoid istoricLoop(){ uint32_t t=millis(); time_t tNow=0; tm timeinfo{};\n" +
            tail + TESTS)


def compile_test(source, folder, name):
    cpp = folder / (name + ".cpp")
    binary = folder / (name + ".exe")
    cpp.write_text(program(source), encoding="utf-8")
    subprocess.run([shutil.which("g++") or "g++", "-std=c++17", "-Wall", "-Wextra",
                    "-Wno-unused-parameter", "-I", str(unit.root), str(cpp), "-o", str(binary)], check=True)
    return binary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=SCENARIOS)
    parser.add_argument("--mutations", action="store_true", help="Verifica sensibilitatea la fiecare formula veche FW-02")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="smartair-history-integration-") as directory:
        binary = compile_test(unit.source, Path(directory), "history_integration")
        rezultate = [subprocess.run([str(binary), s]).returncode for s in ([args.scenario] if args.scenario else SCENARIOS)]
        if args.mutations:
            for name, actual, vechi, scenariu in MUTATIONS:
                if unit.source.count(actual) != 1:
                    raise RuntimeError("Formula s-a schimbat; revizuiti mutatia: " + name)
                mutant = compile_test(unit.source.replace(actual, vechi, 1), Path(directory), name)
                result = subprocess.run([str(mutant), scenariu], capture_output=True, text=True)
                detectat = result.returncode == 1 and "FAIL:" in result.stdout
                print("Mutatie " + name + (": detectata" if detectat else ": NEDETECTATA"), flush=True)
                rezultate.append(0 if detectat else 1)
        return int(any(rezultate))


if __name__ == "__main__":
    raise SystemExit(main())
