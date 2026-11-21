"""Verifica payload-ul real de arhivare cu FirebaseJson instalat si emulatorul RTDB local.

Nu citeste secrete si nu accepta adrese de productie. Metodele JSON sunt extrase
nemodificate din biblioteca instalata; adaptorul inlocuieste numai string/vector
Arduino si overload-urile publice pentru siruri. Fuziunea CSV are teste separate.
"""
from pathlib import Path
import argparse
import copy
import json
import re
import shutil
import subprocess
import tempfile
import urllib.request
import diagnostic_test_support as diagnostic


def method(source, name):
    found = re.search(r"^[^\n]*FirebaseJson(?:Base)?::" + name + r"\([^\n]*\)\n\{.*?^\}", source, re.M | re.S)
    if not found:
        raise RuntimeError("Metoda bibliotecii lipseste: " + name)
    return found.group().replace("FirebaseJson::", "FirebaseJsonBase::").replace("FirebaseJson &", "FirebaseJsonBase &")


def generate_payload(library, sketch, directory):
    source = sketch.read_text(encoding="utf-8")
    constants = "\n".join(re.findall(r'^constexpr char (?:VERSIUNE|BUILD)_FIRMWARE\[\].*$', source, re.M))
    library_source = (library / "FirebaseJson.cpp").read_text(encoding="utf-8")
    names = ("prepareRoot", "searchElements", "getElement", "mAdd", "makeList",
             "pushLish", "clearList", "isArray", "isObject", "addArray",
             "appendArray", "replaceItem", "replace", "mSet", "nAdd")
    methods = [method(library_source, name) for name in names]
    declarations = "\n".join(m.split("\n{", 1)[0].replace("FirebaseJsonBase::", "") + ";" for m in methods)
    calls = re.findall(r'^\s*(patch\.(?:set|add)\("(?:Arhiva_Zilnica/|last_archived_date)"[^\n]+;)', source, re.M)
    if len(calls) != 2:
        raise RuntimeError("Constructia patch-ului s-a schimbat; testul necesita revizuire")
    target = re.search(r'updateNodeSilent\(&fbdoScriere, "([^"]+)", &patch\)', source)
    if not target:
        raise RuntimeError("Destinatia patch-ului nu a fost gasita")
    packets = {}
    targets = {"archive": target.group(1)}
    for name in ("jsonLive", "jsonIstoric"):
        found = re.search(r"FirebaseJson " + name + r";(?P<body>.*?)Firebase\.RTDB\.updateNodeAsync\(&fbdoScriere, \"(?P<path>[^\"]+)\", &" + name + r"\)", source, re.S)
        if not found:
            raise RuntimeError("Pachetul nu a fost gasit: " + name)
        # Excludem selectarea transportului; executam integral construirea JSON.
        body = re.sub(r"\s*if\s*\(\s*!\s*$", "", found["body"])
        if name == "jsonLive":
            body = body.split("    // O data pe minut asteptam confirmarea HTTP.")[0]
            confirmed = re.search(r'updateNodeSilent\(&fbdoScriere, "([^"]+)", &jsonLive\)', source)
            if not confirmed or confirmed[1] != found["path"]:
                raise RuntimeError("Destinatiile live async/confirmat trebuie verificate impreuna")
        packets[name] = "FirebaseJson " + name + ";" + body
        targets[name] = found["path"]
    header = r'''
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <iostream>
#include <cmath>
#include "SmartAirCloudHealth.h"
#include "MB_JSON/MB_JSON.h"
class MB_String : public std::string {
public:
  using std::string::string;
  MB_String(const std::string& s):std::string(s){}
  void trim() {
    auto a=find_first_not_of(" \t\r\n");
    if(a==npos){clear();return;}
    erase(find_last_not_of(" \t\r\n")+1); erase(0,a);
  }
};
#define MB_VECTOR std::vector
class FirebaseJsonBase {
public:
  enum key_status { key_status_not_existed, key_status_existed, key_status_mistype, key_status_out_of_range };
  struct search_result_t { MB_JSON* parent=nullptr; key_status status=key_status_not_existed; int foundIndex=-1,stopIndex=0; };
  enum {Root_Type_JSON,Root_Type_JSONArray};
  int root_type=Root_Type_JSON;
  MB_JSON* root=nullptr;
  ~FirebaseJsonBase(){ MB_JSON_Delete(root); }
  bool isArrayKey(const char* key){return strlen(key)>0 && key[0]=='[' && key[strlen(key)-1]==']';}
  int getArrIndex(const char* key){return atoi(key+1);}
  void set(const std::string& key,const std::string& value){mSet(key.c_str(),MB_JSON_CreateString(value.c_str()));}
  void set(const std::string& key,double value){mSet(key.c_str(),MB_JSON_CreateNumber(value));}
  template<class T, typename std::enable_if<std::is_same<T,bool>::value,int>::type=0>
  void set(const std::string& key,T value){mSet(key.c_str(),MB_JSON_CreateBool(value));}
  void set(const std::string& key){mSet(key.c_str(),MB_JSON_CreateNull());}
  void add(const std::string& key,const std::string& value){nAdd(key.c_str(),MB_JSON_CreateString(value.c_str()));}
'''
    # Se executa instructiunile din sketch; fuziunea CSV este deja verificata separat.
    main = r'''
using FirebaseJson=FirebaseJsonBase;
String combinaArhivaZi(const String&,const String& noua){return noua;}
struct { int getFreeHeap(){return 123456;} int getMinFreeHeap(){return 65432;} int getFreePsram(){return 7000000;} } ESP;
constexpr int WL_CONNECTED=3;
struct {bool online=true; int RSSI(){return -50;} int status(){return online?3:0;} } WiFi;
uint64_t nowMs=5000;
long long esp_timer_get_time(){return nowMs*1000;}
float tempMcu=42;
float temperatureRead(){return tempMcu;}
StareCloud stareCloud;
bool firebaseGata=true,offsetFortatLaBoot=true;
struct {bool pm=true,co2=true,voc=true,nox=true,temperatura=true,umiditate=true;} validitateSenzor;
int rpmReal=600,targetFanSpeed=40,currentFanSpeed=25;
float offsetTemperatura=-0.8f;
void logSystem(const char*){}
''' + diagnostic.GLOBALS + diagnostic.diagnostic_functions(source) + diagnostic.SCENARIOS + r'''
void print(FirebaseJson& p){
  char* out=MB_JSON_PrintUnformatted(p.root);
  if(!out) std::exit(3);
  std::cout<<out; MB_JSON_free(out);
}
int main(int argc,char** argv){
  if(argc==4){
    String mode=argv[3];
    configureDiagnosticScenario(mode);
    struct {bool pm,co2,voc,nox,temperatura,umiditate;} validitateSenzor;
    bool valid=mode!="live-invalid";
    bool cereConfirmare=mode!="live-async";
    validitateSenzor={valid,valid,valid,valid,valid,valid};
    int rpmReal=600,targetFanSpeed=40,co2=700;
    bool offsetFortatLaBoot=valid;
    float offsetTemperatura=-0.8f,pm1=1,pm25=2,pm4=3,pm10=4,voc=50,nox=2,temp=22,umiditate=45;
    String istPM25="2,3",istCO2="500,600",istVOC="10,11",istNOx="1,2",istTemp="20,21",istUmid="40,41";
    String istPM1="1,2",istPM4="3,4",istPM10="4,5";
    long lastIstoricSaveTime=1700000100;
    if(mode=="history"){
''' + packets["jsonIstoric"] + "\nprint(jsonIstoric); return 0;\n}\n" + packets["jsonLive"] + r'''
    print(jsonLive); return 0;
  }
  if(argc!=3) return 2;
  String dataInAsteptare=argv[1],ziDupaArhiva=argv[2],veche;
  const char* numeArhiva[]={"PM25","CO2","VOC","NOx","Temp","Umid"};
  String seriiInAsteptare[]={"1,2","400,450","10,20","1,2","21,22","40,41"};
  FirebaseJson patch;
  for(int i=0;i<6;++i){
''' + calls[0] + "\n}\n" + calls[1] + r'''
  print(patch);
}
'''
    cpp = directory / "archive_payload.cpp"
    binary = directory / "archive_payload.exe"
    cpp.write_text(header + declarations + "\n};\n" + "\n".join(methods) + constants + diagnostic.STRING + main, encoding="utf-8")
    compiler = shutil.which("g++")
    if not compiler:
        raise RuntimeError("Este necesar g++")
    subprocess.run([compiler, "-std=c++17", "-I", str(library), "-I", str(sketch.parent), str(cpp),
                    str(library / "MB_JSON/MB_JSON.c"), "-o", str(binary)], check=True)
    return binary, targets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--firebase-json", type=Path, required=True)
    parser.add_argument("--port", type=int, default=19000)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("Port local invalid")
    # Adresa si namespace-ul sunt fixe pentru a exclude accidental productia.
    base = f"http://127.0.0.1:{args.port}"
    def request(verb, path, data=None):
        body = None if data is None else json.dumps(data).encode()
        req = urllib.request.Request(base + path + ".json?ns=demo-smartair-archive-test",
                                     data=body, method=verb,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as response:
            return json.load(response)
    sketch = Path(__file__).resolve().parents[2] / "Esp_SmartPurifier.ino"
    with tempfile.TemporaryDirectory(prefix="smartair-archive-") as temp:
        binary, targets = generate_payload(args.firebase_json, sketch, Path(temp))
        target = targets["archive"]
        def payload(day, following):
            return json.loads(subprocess.check_output([str(binary), day, following], text=True))
        fixture = {"SenzoriAer": {
            "Arhiva_Zilnica": {"2026_03_14": {"PM25": "11,12", "CO2": "600,601"},
                               "2026_06_21": {"VOC": "21,22"},
                               "2026_09_20": {"PM25": "1,-999", "metadata": "pastrat"}},
            "last_archived_date": "2026_09_20", "setari": {"temp_offset": -0.8},
            "Istoric_PM25": "3,4", "ErrorLog": "pastrat",
            "Diagnostic": {"camp_vechi": "inlocuit", "offset_aplicat_c": 99},
            "Timer": {"camp_vechi": "inlocuit", "activ": True}}, "alt_nod": "pastrat"}
        request("PUT", "/", fixture)
        first = payload("2026_09_20", "2026_09_21")
        request("PATCH", target, first)
        result = request("GET", "/")
        archive = result["SenzoriAer"]["Arhiva_Zilnica"]
        assert archive.get("2026_03_14") == fixture["SenzoriAer"]["Arhiva_Zilnica"]["2026_03_14"], "Publicarea unei zile a sters/modificat ziua din martie"
        assert archive.get("2026_06_21") == {"VOC": "21,22"}, "Ziua din iunie nu este pastrata"
        assert archive["2026_09_20"]["metadata"] == "pastrat", "Campurile straine ale aceleiasi zile au fost sterse"
        assert archive["2026_09_20"]["CO2"] == "400,450"
        assert result["SenzoriAer"]["last_archived_date"] == "2026_09_21"
        untouched = copy.deepcopy(result)
        untouched["SenzoriAer"]["Arhiva_Zilnica"] = fixture["SenzoriAer"]["Arhiva_Zilnica"]
        untouched["SenzoriAer"]["last_archived_date"] = "2026_09_20"
        assert untouched == fixture, "Scrierea a modificat alte date sau setari"
        request("PATCH", target, first)
        assert request("GET", "/") == result, "Retrimiterea patch-ului nu este idempotenta"
        request("PATCH", target, payload("2026_09_21", "2026_09_22"))
        later = request("GET", "/")
        assert len(later["SenzoriAer"]["Arhiva_Zilnica"]) == 4
        for day, values in archive.items():
            assert later["SenzoriAer"]["Arhiva_Zilnica"][day] == values, "Ziua urmatoare modifica arhiva existenta"
        assert later["SenzoriAer"]["last_archived_date"] == "2026_09_22"
        assert set(first) == {"Arhiva_Zilnica/2026_09_20/" + c for c in ("PM25", "CO2", "VOC", "NOx", "Temp", "Umid")} | {"last_archived_date"}
        for mode in ("live-valid", "live-invalid", "live-async", "history"):
            before = request("GET", "/")
            packet = json.loads(subprocess.check_output([str(binary), "", "", mode], text=True))
            packet_target = targets["jsonIstoric" if mode == "history" else "jsonLive"]
            request("PATCH", packet_target, packet)
            after = request("GET", "/")
            for protected in ("Arhiva_Zilnica", "last_archived_date", "setari", "ErrorLog"):
                assert before["SenzoriAer"][protected] == after["SenzoriAer"][protected], mode + " modifica " + protected
            assert after["alt_nod"] == "pastrat"
            if mode == "history":
                assert after["SenzoriAer"]["Istoric_PM25"] == "2,3"
                assert after["SenzoriAer"]["last_history_time"] == 1700000100
                assert after["SenzoriAer"]["Firmware"] == before["SenzoriAer"]["Firmware"]
                assert after["SenzoriAer"]["Diagnostic"] == before["SenzoriAer"]["Diagnostic"]
                assert after["SenzoriAer"]["Timer"] == before["SenzoriAer"]["Timer"]
            else:
                assert isinstance(after["SenzoriAer"]["last_update"], (int, float))
                timer_report=after["SenzoriAer"].get("Timer",{})
                assert timer_report.get("schema") == 1 and timer_report.get("activ") is False
                assert isinstance(timer_report.get("raportat_la"), (int,float)), "Timer cere timestamp server propriu"
                assert "camp_vechi" not in timer_report, "Timer trebuie inlocuit coerent inclusiv la async"
                firmware = after["SenzoriAer"].get("Firmware", {})
                assert firmware.get("versiune"), "Pachetul live nu identifica firmware-ul instalat"
                assert firmware.get("build"), "Lipseste identificarea compilarii ESP32"
                assert isinstance(firmware.get("raportat_la"), (int, float)), "Versiunea necesita timestamp propriu"
                diagnostic_report = after["SenzoriAer"].get("Diagnostic", {})
                assert diagnostic_report.get("schema") == 1, "Pachetul confirmat nu publica schema diagnostic"
                assert diagnostic_report.get("firmware_versiune") == firmware["versiune"]
                assert isinstance(diagnostic_report.get("raportat_la"), (int, float)), "Diagnosticul necesita timestamp server propriu"
                assert "camp_vechi" not in diagnostic_report, "Raportul trebuie inlocuit coerent, fara campuri vechi"
                if mode != "live-async":
                    assert ("offset_aplicat_c" in diagnostic_report) == (mode != "live-invalid"), "Null nu pastreaza offset vechi"
                    assert len(json.dumps(diagnostic_report).encode()) < 6144, "Diagnostic depaseste 6KiB"
                if mode == "live-async":
                    assert "Firmware" not in packet, "Metadatele nu trebuie retrimise la fiecare pachet rapid"
                    assert firmware == before["SenzoriAer"]["Firmware"], "Pachetul rapid nu modifica raportul firmware"
                    assert "Diagnostic" not in packet, "Pachetul rapid nu include diagnosticul"
                    assert diagnostic_report == before["SenzoriAer"]["Diagnostic"], "Pachetul rapid nu modifica diagnosticul"
                assert after["SenzoriAer"]["RPM"] == 600
                for channel in ("PM1", "PM25", "PM4", "PM10", "CO2", "VOC", "NOx", "Temperatura", "Umiditate"):
                    assert (channel in after["SenzoriAer"]) == (mode != "live-invalid")
        # Contractele noilor scrieri pe frunze; payload-urile pure Android au teste JUnit.
        # Arborele real al emulatorului trebuie sa pastreze tot ce nu este vizat.
        before = request("GET", "/")
        expected = copy.deepcopy(before)
        request("PATCH", "/SenzoriAer/setari", {"alert_co2": 1700.0})
        expected["SenzoriAer"]["setari"]["alert_co2"] = 1700.0
        assert request("GET", "/") == expected, "Pragul modificat a afectat noduri independente"
        for deadline in (1800000120, 0):
            request("PUT", "/SenzoriAer/setari/calibrare_co2_expira", deadline)
            expected["SenzoriAer"]["setari"]["calibrare_co2_expira"] = deadline
            assert request("GET", "/") == expected, "Comanda CO2 a afectat alte date"
        request("PUT", "/SenzoriAer/ErrorLog", "1800000000#Test sintetic")
        expected["SenzoriAer"]["ErrorLog"] = "1800000000#Test sintetic"
        assert request("GET", "/") == expected, "Jurnalul a afectat alte date"
        # Extragem calea scrierii confirmate Night Mode din sursa activa.
        night_path = re.search(r'Firebase\.RTDB\.setBool\(&fbdoScriere, "([^"]+)", nightModeApp\)',
                               sketch.read_text(encoding="utf-8")).group(1)
        assert night_path == "/SenzoriAer/setari/night_mode"
        for enabled in (True, False, False, True):
            request("PUT", night_path, enabled)
            expected["SenzoriAer"]["setari"]["night_mode"] = enabled
            assert request("GET", "/") == expected, "Night Mode a afectat noduri independente"
        # Pornire/editare folosesc acelasi obiect complet. Oprirea/manualul pun false atomic.
        timer_command={"id":"00000000000000000000000000000001","boot_id":"0123456789abcdef",
                       "creat_la_ms":1800000000000,"sfarsit_ms":1800000300000,"viteza_pct":80}
        for speed,timer in ((0,timer_command),(0,dict(timer_command,viteza_pct=45)),(0,False),(60,False)):
            request("PATCH","/SenzoriAer/setari",{"speed":speed,"timer":timer})
            expected["SenzoriAer"]["setari"].update(speed=speed,timer=timer)
            assert request("GET","/") == expected, "Comanda timer a afectat noduri independente"
        for mode in ("diag-timer-active","diag-timer-expired","diag-timer-cancelled"):
            before=request("GET","/")
            packet=json.loads(subprocess.check_output([str(binary),"","",mode],text=True))
            request("PATCH",targets["jsonLive"],packet)
            after=request("GET","/")
            for protected in ("Arhiva_Zilnica","last_archived_date","setari","ErrorLog"):
                assert after["SenzoriAer"][protected] == before["SenzoriAer"][protected], mode+" modifica "+protected
            assert after["alt_nod"] == before["alt_nod"]
            live_timer=after["SenzoriAer"]["Timer"]
            assert live_timer["activ"] == (mode == "diag-timer-active")
            assert live_timer["ramas_sec"] == (300 if mode == "diag-timer-active" else 0)
            assert after["SenzoriAer"]["Diagnostic"]["timer"] == {k:v for k,v in live_timer.items() if k != "raportat_la"}
        print("PASS: arhiva/retry/zi noua, live valid/invalid/Diagnostic/Timer, istoric; contracte praguri/CO2/jurnal/Night Mode/timer start/edit/cancel/manual; noduri independente pastrate")


if __name__ == "__main__":
    main()
