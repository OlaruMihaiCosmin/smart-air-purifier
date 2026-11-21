"""Executa sincronizarea reala din loop impreuna cu jurnalul real, fara retea.

Reutilizeaza exclusiv adaptorul local al suitei de istoric. Transportul injecteaza
esecuri la citiri/scrieri; fluxul importului si publicarii provine din sketch.
"""
from pathlib import Path
import re
import subprocess
import tempfile

import run_history_integration_tests as integration


source = integration.unit.source
functions = []
for name in ("limiteazaJurnal", "logSystem", "recupereazaJurnal", "trimiteJurnalDacaNecesar"):
    match = re.search(r"^void " + name + r"\([^\n]*\) \{.*?^\}", source, re.M | re.S)
    if not match:
        raise RuntimeError("Functia lipseste: " + name)
    functions.append(match.group())


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError("Adaptorul comun s-a schimbat; revizuiti substitutia: " + old)
    return text.replace(old, new, 1)


adapter = integration.ADAPTER
integration.unit.header = replace_once(integration.unit.header,
    "  String(long n): s(std::to_string(n)) {}",
    "  String(long n): s(std::to_string(n)) {}\n  String(long long n): s(std::to_string(n)) {}")
adapter = replace_once(adapter, "  int readFailures=0, writeFailures=0, reads=0, markerWrites=0;", """
  int readFailures=0, writeFailures=0, reads=0, markerWrites=0, journalFailures=0;
  std::map<std::string,int> readsByPath;
  std::vector<String> journalAttempts;
""")
adapter = replace_once(adapter, "    ++reads; *out=FirebaseData{};",
                       "    ++readsByPath[path.str()]; ++reads; *out=FirebaseData{};")
adapter = replace_once(adapter, "    ++markerWrites; nodes[path.str()]={\"string\",value}; return true;", """
    if(path=="/SenzoriAer/ErrorLog") {
      journalAttempts.push_back(value);
      if(journalFailures>0) { --journalFailures; return false; }
    }
    ++markerWrites; nodes[path.str()]={"string",value}; return true;
""")
start = adapter.index("void logSystem(const String&) {}")
end = adapter.index("String* seriiTest", start)
adapter = adapter[:start] + """
bool jurnalRecuperat=false, jurnalModificat=false, jurnalIncercat=false;
uint32_t tUltimaIncercareJurnal=0;
""" + "\n".join(functions) + "\n" + adapter[end:]
integration.ADAPTER = adapter
integration.TESTS = r'''
bool contine(const String& text,const char* token) { return text.str().find(token)!=std::string::npos; }
int numarEvenimente(const String& text) { return text.length()?int(std::count(text.str().begin(),text.str().end(),'|'))+1:0; }
String jurnalCloud() { return Firebase.RTDB.nodes["/SenzoriAer/ErrorLog"].value; }
int main(int argc,char** argv) {
  if(argc!=2)return 2;
  _putenv_s("TZ","UTC0"); _tzset();
  const std::string scenariu=argv[1];
  ceas(epoch(26,1,1),100000); cloud(epoch(26,0,45));
  Firebase.RTDB.nodes["/SenzoriAer/ErrorLog"]={"string","1790377190#Anterior"};
  Firebase.RTDB.nodes["/SenzoriAer/setari/temp_offset"]={"string","-0.8"};
  Firebase.RTDB.nodes["/SenzoriAer/Arhiva_Zilnica/2026_03_14/PM25"]={"string","1,2,3"};
  auto& transport=Firebase.RTDB;
  if(scenariu=="later-read-failure") {
    logSystem("Local inainte de import");
    transport.failPath="/SenzoriAer/Istoric_NOx"; transport.readFailures=1;
    istoricLoop();
    verifica(jurnalRecuperat && !istoricRecuperat,"Jurnalul se importa independent de finalizarea istoricului");
    verifica(transport.journalAttempts.empty(),"Nu exista scriere inainte de citirea jurnalului cloud");
    for(int i=0;i<11;++i)istoricLoop();
    verifica(!istoricRecuperat && contine(jurnalCloud(),"Sistem Repornit:"),"Esec ulterior in istoric nu impiedica publicarea bootului");
    logSystem("Dupa esecul istoricului");
    ceasMillis+=10000; ceasEpoch+=10;
    for(int i=0;i<12;++i)istoricLoop();
    verifica(istoricRecuperat && transport.readsByPath["/SenzoriAer/ErrorLog"]==1,"Reluarea istoricului nu reciteste jurnalul deja importat");
    verifica(numarEvenimente(jurnalCloud())==4 && contine(jurnalCloud(),"Anterior") && contine(jurnalCloud(),"Dupa esecul istoricului"),"Reluarea pastreaza toate evenimentele fara duplicare");
  } else if(scenariu=="failed-publication") {
    transport.journalFailures=1;
    logSystem("Local initial");
    for(int i=0;i<12;++i)istoricLoop();
    verifica(istoricRecuperat && jurnalModificat,"Importul complet nu confirma o scriere de jurnal esuata");
    verifica(jurnalCloud()=="1790377190#Anterior","Scrierea esuata lasa jurnalul cloud intact");
    logSystem("Local dupa esec");
    ceasMillis+=9999;istoricLoop();
    verifica(transport.journalAttempts.size()==1,"Publicarea jurnalului respecta backoff si dupa finalizarea importului");
    ++ceasMillis;istoricLoop();
    verifica(transport.journalAttempts.size()==2 && !jurnalModificat,"Prima confirmare ulterioara inchide starea pending");
    verifica(numarEvenimente(jurnalCloud())==4 && contine(jurnalCloud(),"Local dupa esec"),"Retry publica si evenimentele aparute dupa esec");
  } else if(scenariu=="no-clock-boot") {
    oraValida=false; ceasEpoch=0; WiFi.conectat=false;
    logSystem("Eroare fara ora sau WiFi");
    istoricLoop();
    verifica(transport.reads==0 && transport.journalAttempts.empty(),"Boot fara ora si retea nu atinge transportul");
    oraValida=true; ceasEpoch=epoch(26,1,1); WiFi.conectat=true;
    for(int i=0;i<12;++i)istoricLoop();
    verifica(contine(jurnalCloud(),"999999999#Eroare fara ora sau WiFi") && contine(jurnalCloud(),"Anterior"),"Conectarea ulterioara conserva evenimentul fara timestamp cunoscut");
  } else return 2;
  verifica(transport.nodes["/SenzoriAer/setari/temp_offset"].value=="-0.8" &&
           transport.nodes["/SenzoriAer/Arhiva_Zilnica/2026_03_14/PM25"].value=="1,2,3",
           "Publicarea jurnalului foloseste numai copilul ErrorLog");
  std::cout<<scenariu<<": "<<verificari<<" verificari, "<<esecuri<<" esecuri\n";
  return esecuri?1:0;
}
'''


with tempfile.TemporaryDirectory(prefix="smartair-journal-integration-") as directory:
    binary = integration.compile_test(source, Path(directory), "journal_integration")
    results = [subprocess.run([str(binary), scenario]).returncode for scenario in
               ("later-read-failure", "failed-publication", "no-clock-boot")]
    raise SystemExit(int(any(results)))
