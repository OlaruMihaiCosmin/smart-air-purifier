"""Adaptoare hardware sintetice pentru executia helperelor reale de diagnostic."""
import re


def diagnostic_functions(source):
    result = []
    for name in ("intretineTimer", "adaugaStareTimer", "captureazaEtapaAnterioaraReset", "mesajDiagnostic", "jurnalDiagnostic", "adaugaDiagnostic"):
        found = re.search(r"^(?:void|String) " + name + r"\([^\n]*\) \{.*?^\}", source, re.M | re.S)
        if not found:
            raise RuntimeError("Functia diagnostic lipseste: " + name)
        result.append(found.group())
    return "\n".join(result)


STRING = r'''
class String: public std::string {
public:
 using std::string::string;
 String(std::string s):std::string(s){}
 String(int x):std::string(std::to_string(x)){}
 int indexOf(const char* x)const{auto p=find(x);return p==npos?-1:int(p);}
 int indexOf(char x,size_t from=0)const{auto p=find(x,from);return p==npos?-1:int(p);}
 bool startsWith(const char* x)const{return rfind(x,0)==0;}
 String substring(size_t from,size_t to=std::string::npos)const{
  if(from>=size())return "";
  return substr(from,to==std::string::npos?to:to-from);
 }
};
'''

GLOBALS = r'''
#include <cstring>
#include <limits>
#include "SmartAirTimer.h"
StareTimer stareTimer;
int vitezaBaza=0;
using std::isfinite;
bool ceasSincronizat=true;
bool ceasValid(){return ceasSincronizat;}
constexpr int ESP_RST_POWERON=1,ESP_RST_BROWNOUT=9;
int motivResetTest=4;
int esp_reset_reason(){return motivResetTest;}
char rtc_last_state[64]="Trimitere_Live_FB";
String motivRestart="Eroare Fatala (Panic) in: Citire_SEN66",etapaAnterioaraReset="Citire_SEN66";
bool diagnosticAreCitireSenzor=true,warmupSenzorTerminat=true,senzorEsuatComm=false;
uint64_t diagnosticUltimaCitireMs=4000;
int senzorEroriConsecutive=0;
bool isAutoMode=true,nmAuto=true,nightModeApp=true,istoricRecuperat=true;
int vitezaDinAplicatie=0,nmStartMin=1380,nmEndMin=420;
float offsetSolicitatTemp=-1.2f;
String lastArchivedDateStr="2026_09_29",arhivaDiagnosticInAsteptare="";
long lastIstoricSaveTime=1700000100;
bool jurnalRecuperat=true,jurnalModificat=false;
String jurnalErori="1800000000#Comunicare SEN66 restabilita";
'''

SCENARIOS = r'''
void configureDiagnosticScenario(const String& mode){
 stareTimer.incepeBoot("0123456789abcdef");
 if(mode=="diag-timer-active" || mode=="diag-timer-expired" || mode=="diag-timer-cancelled"){
  CerereTimer cerere;
  std::strcpy(cerere.id,"00000000000000000000000000000001");
  std::strcpy(cerere.bootId,"0123456789abcdef");
  cerere.creatLaMs=1800000000000LL;cerere.sfarsitMs=1800000300000LL;cerere.vitezaPct=80;
  stareTimer.aplica(cerere,true,1800000000000LL,nowMs);
  isAutoMode=false;
  if(mode=="diag-timer-expired"){nowMs+=300000;isAutoMode=true;}
  if(mode=="diag-timer-cancelled"){stareTimer.anuleaza();isAutoMode=true;}
 }
 if(mode=="live-invalid" || mode=="diag-boot"){
  diagnosticAreCitireSenzor=false;warmupSenzorTerminat=false;
  validitateSenzor={false,false,false,false,false,false};
  offsetFortatLaBoot=false;etapaAnterioaraReset="";
  istoricRecuperat=false;lastIstoricSaveTime=0;lastArchivedDateStr="";
  jurnalRecuperat=false;jurnalModificat=true;ceasSincronizat=false;
 }
 if(mode=="diag-offline"){
  nowMs=191999;WiFi.online=false;firebaseGata=false;
  stareCloud.confirma(61000);diagnosticUltimaCitireMs=80000;
  senzorEroriConsecutive=1;validitateSenzor.co2=false;
  arhivaDiagnosticInAsteptare="2026_09_28";
  jurnalModificat=true;offsetFortatLaBoot=false;
  tempMcu=std::numeric_limits<float>::quiet_NaN();
 }
 if(mode=="diag-rollover"){
  nowMs=0x100000000ULL+123456;stareCloud.confirma(23000);
  diagnosticUltimaCitireMs=1000;
 }
 if(mode=="diag-long-uptime"){
  nowMs=3000000000000ULL;stareCloud.confirma(1000);
 }
 if(mode=="diag-rtc" || mode=="diag-rtc-invalid" || mode=="diag-rtc-power" || mode=="diag-rtc-unterminated"){
  std::strcpy(rtc_last_state,"Citire_SEN66");
  if(mode=="diag-rtc-invalid")std::strcpy(rtc_last_state,"secret\nunknown");
  if(mode=="diag-rtc-power")motivResetTest=ESP_RST_POWERON;
  if(mode=="diag-rtc-unterminated")std::memset(rtc_last_state,'A',sizeof(rtc_last_state));
  captureazaEtapaAnterioaraReset();
  std::strcpy(rtc_last_state,"Trimitere_Live_FB");
 }
 if(mode=="diag-journal"){
  jurnalErori="1800000000#WiFi restabilit pe: PRIVATE_SSID"
   "|1800000001#Eroare buffer Live FB: PRIVATE_TOKEN"
   "|1800000002#password=PRIVATE_PASSWORD"
   "|PRIVATE_STAMP#Comunicare SEN66 restabilita"
   "|1800000004#Sistem Repornit: PRIVATE_RESET"
   "|1800000005#Night Mode pornit (programat)";
 }
 if(mode=="diag-max"){
  jurnalErori="";
  for(int i=0;i<30;++i){
   if(i)jurnalErori+="|";
   jurnalErori+=std::to_string(1800000000+i)+"#WDT: Senzorul SEN66 a blocat citirile de 15s. Reinitializare...";
  }
  std::memset(rtc_last_state,'A',63);rtc_last_state[63]='\0';
  etapaAnterioaraReset=String(rtc_last_state);motivRestart=std::string(120,'X');
 }
 if(mode=="diag-journal-long"){
  jurnalErori="1800000000#"+std::string(20000,'X');
 }
}
'''
