const char* BOOT="0123456789abcdef";
const char* ID1="00000000000000000000000000000001";
const char* ID2="00000000000000000000000000000002";
const char* ID3="00000000000000000000000000000003";
constexpr int64_t EPOCH=1800000000000LL;
void resetTimer(){
 stareTimer.incepeBoot(BOOT);nowMs=1000;wallMs=EPOCH;clockValid=true;
 logs.clear();vitezaBaza=vitezaDinAplicatie=75;necesitaReconciliere=false;
 necesitaSetariVentilator=false;events.clear();serverSettings="{}";readOk=true;reads=0;tAntPollSetari=0;
 WiFi.online=true;eraAutoInainte=false;validitateSenzor.pm=true;
}
String obiect(const char* id=ID1,int speed=80,int64_t created=EPOCH,int64_t duration=300000,const char* boot=BOOT){
 return "{\"id\":\""+std::string(id)+"\",\"boot_id\":\""+boot+"\",\"creat_la_ms\":"+
 std::to_string(created)+",\"sfarsit_ms\":"+std::to_string(created+duration)+",\"viteza_pct\":"+std::to_string(speed)+"}";
}
String cerere(const char* id=ID1,int speed=80,int64_t created=EPOCH,int64_t duration=300000,const char* boot=BOOT){
 return "{\"speed\":0,\"timer\":"+obiect(id,speed,created,duration,boot)+"}";
}
size_t logCount(const char* text){return std::count(logs.begin(),logs.end(),text);}
void eveniment(const char* path,const String& value){EvenimentSetari e{};std::strncpy(e.path,path,sizeof(e.path)-1);std::strncpy(e.data,value.c_str(),sizeof(e.data)-1);events.push_back(e);}
void stream(const char* path,const String& value,const String& actual){serverSettings=actual;eveniment(path,value);proceseaza();}
String schimba(String text,const String& old,const String& value){text.replace(text.find(old),old.size(),value);return text;}
int main(){
 resetTimer();
 verifica(!stareTimer.activ()&&stareTimer.cerere().id[0]=='\0'&&stareTimer.ramasSec(nowMs)==0&&stareTimer.viteza()==0,"Boot: fara timer sau stare restaurata");
 aplica(cerere());
 verifica(vitezaDinAplicatie==80&&vitezaBaza==0&&stareTimer.activ(),"Snapshot coerent aplica treapta temporara peste baza AUTO");
 verifica(stareTimer.ramasSec(nowMs)==300&&logCount("Timer pornit")==1,"Start 5 minute si un singur eveniment");
 nowMs+=60000;wallMs+=60000;aplica(cerere());
 verifica(stareTimer.ramasSec(nowMs)==240&&logCount("Timer pornit")==1,"Snapshot repetat dupa 30s nu reporneste timerul");
 aplica(cerere(ID1,35));
 verifica(vitezaDinAplicatie==35&&stareTimer.ramasSec(nowMs)==240,"Editarea vitezei dupa30s pastreaza termenul");
 clockValid=false;wallMs-=3600000;aplica(cerere(ID1,60));
 verifica(vitezaDinAplicatie==60&&stareTimer.ramasSec(nowMs)==240,"Timer activ: editarea ramane posibila dupa pierderea NTP/saltul orei");
 aplica("{\"night_mode\":true,\"temp_offset\":-0.7}");
 verifica(stareTimer.activ()&&vitezaDinAplicatie==60&&offsetTemperatura==-0.7f,"Patch fara timer nu anuleaza si nu modifica campuri absente");
 nowMs=300999;intretineTimer();verifica(stareTimer.ramasSec(nowMs)==1&&stareTimer.activ(),"Ultima milisecunda este rotunjita in sus");
 nowMs=301000;fan();
 verifica(!stareTimer.activ()&&vitezaDinAplicatie==0&&isAutoMode&&targetFanSpeed==35,"Expirarea alimenteaza AUTO existent in aceeasi iteratie");
 intretineTimer();aplica(cerere());
 verifica(logCount("Timer expirat: revenire AUTO")==1&&!stareTimer.activ(),"Expirarea se logheaza o singura data; replay nu reporneste");
 verifica(std::string(stareTimer.cerere().id)==ID1&&stareTimer.cerere().sfarsitMs==EPOCH+300000,"ACK inactiv pastreaza ID si termenele acceptate");

 resetTimer();aplica(cerere());
 aplica(cerere(ID1,70,EPOCH,600000));
 verifica(stareTimer.activ()&&stareTimer.viteza()==80&&stareTimer.ramasSec(nowMs)==300,"Acelasi ID nu accepta un termen prelungit");
 aplica(cerere(ID1,70,EPOCH,300001));
 verifica(stareTimer.activ()&&stareTimer.viteza()==80,"Imutabile alterate nu anuleaza timerul deja acceptat");
 wallMs+=1000;nowMs+=1000;aplica(cerere(ID2,90,wallMs));
 verifica(std::string(stareTimer.cerere().id)==ID2&&vitezaDinAplicatie==90,"Comanda noua mai recenta inlocuieste timerul");
 aplica(cerere());
 verifica(std::string(stareTimer.cerere().id)==ID2&&vitezaDinAplicatie==90,"Snapshot vechi cu alt ID nu inlocuieste timerul nou");
 aplica("{\"speed\":0,\"timer\":false}");
 verifica(!stareTimer.activ()&&vitezaDinAplicatie==0&&logCount("Timer anulat: revenire AUTO")==1,"AUTO atomic anuleaza timerul");
 aplica("{\"speed\":0,\"timer\":false}");aplica(cerere(ID2,90,wallMs));aplica(cerere());
 verifica(!stareTimer.activ()&&logCount("Timer anulat: revenire AUTO")==1,"False repetat si snapshoturi vechi nu reiau timerul anulat");
 wallMs+=1000;nowMs+=1000;aplica(cerere(ID3,45,wallMs));
 verifica(stareTimer.activ()&&vitezaDinAplicatie==45,"O cerere noua dupa anulare ramane permisa");
 aplica("{\"speed\":65,\"timer\":false}");nowMs+=600000;fan();
 verifica(!stareTimer.activ()&&targetFanSpeed==65&&!isAutoMode,"Expirarea timerului anulat nu suprascrie noul manual");

 resetTimer();stream("/timer",obiect(),cerere());fan();
 verifica(stareTimer.activ()&&targetFanSpeed==80,"Obiectul /timer din stream porneste aceeasi autoritate");
 WiFi.online=false;nowMs+=10000;fan();
 verifica(isAutoMode&&targetFanSpeed==35&&stareTimer.activ()&&stareTimer.ramasSec(nowMs)==290,"Pierderea WiFi pastreaza fallback AUTO si countdown");
 WiFi.online=true;fan();verifica(!isAutoMode&&targetFanSpeed==80,"Revenirea WiFi inainte de termen reia treapta timerului");
 WiFi.online=false;nowMs=301000;fan();WiFi.online=true;fan();
 verifica(!stareTimer.activ()&&isAutoMode,"WiFi revenit dupa expirare nu reia manualul");
 resetTimer();aplica(cerere(ID1,5));WiFi.online=false;fan();
 verifica(!isAutoMode&&targetFanSpeed==5,"Pragul WiFi original >5 este pastrat exact");
 WiFi.online=true;stream("/speed","30","{\"speed\":30,\"timer\":false}");WiFi.online=false;fan();
 verifica(!stareTimer.activ()&&isAutoMode,"Client vechi /speed anuleaza timerul si pastreaza fallback");
 WiFi.online=true;fan();verifica(targetFanSpeed==30&&!isAutoMode,"Manualul vechi ramane functional la reconectare");

 resetTimer();aplica(cerere());aplicaSetareSingura("/timer/viteza_pct","45");
 verifica(necesitaSetariVentilator&&stareTimer.viteza()==80,"Fragment de stream cere reconciliere fara aplicare partiala");
 necesitaSetariVentilator=false;aplica("{\"timer/viteza_pct\":45}");
 verifica(necesitaSetariVentilator&&stareTimer.viteza()==80,"Patch multipath literal cere copie coerenta");
 stream("/timer/id","null","{\"speed\":0,\"timer\":null}");
 verifica(!stareTimer.activ()&&vitezaDinAplicatie==0,"Stergerea unui camp necesar anuleaza in siguranta");
 for(const char* raw:{"false","null","true","{}","[]","garbage","{\"id\":","\"text\""}){
  resetTimer();aplica(cerere());
  const String actual=(std::string(raw)=="garbage"||std::string(raw)=="{\"id\":") ? "{\"timer\":\"invalid\"}" : "{\"timer\":"+std::string(raw)+"}";
  stream("/timer",raw,actual);
  verifica(!stareTimer.activ()&&vitezaDinAplicatie==0,"Timer individual fals/null/malformat revine AUTO");
 }
 for(const char* raw:{"null","true","[]","\"text\"","123"}){
  resetTimer();aplica("{\"speed\":75,\"timer\":"+std::string(raw)+"}");
  verifica(!stareTimer.activ()&&vitezaDinAplicatie==0,"Tipul invalid din snapshot nu reinvie manualul");
 }
 for(const char* field:{"id","boot_id","creat_la_ms","sfarsit_ms","viteza_pct"}){
  resetTimer();String raw=cerere();auto start=raw.find("\""+std::string(field)+"\":");auto end=raw.find_first_of(",}",start);
  raw.erase(start,end-start+(raw[end]==','?1:0));if(raw.find(",}")!=String::npos)raw.replace(raw.find(",}"),2,"}");
  aplica(raw);verifica(!stareTimer.activ()&&vitezaDinAplicatie==0,"Campul absent nu reutilizeaza FirebaseJsonData precedent");
 }
 for(const char* raw:{"true","null","\"1800000000000\"","1800000000000.5","1e100","-1"}){
  resetTimer();aplica(schimba(cerere(),"1800000000000",raw));
  verifica(!stareTimer.activ()&&vitezaDinAplicatie==0,"Timestamp numeric invalid nu este convertit sau trunchiat");
 }
 for(int speed:{0,-1,101}){resetTimer();aplica(cerere(ID1,speed));verifica(!stareTimer.activ(),"Treapta in afara1..100 respinsa");}
 for(int64_t duration:{0LL,299999LL,300001LL,14400001LL}){resetTimer();aplica(cerere(ID1,80,EPOCH,duration));verifica(!stareTimer.activ(),"Durata invalida este respinsa");}
 for(int64_t duration:{300000LL,14400000LL}){
  resetTimer();aplica(cerere(ID1,100,EPOCH,duration));verifica(stareTimer.activ()&&stareTimer.ramasSec(nowMs)==duration/1000,"Limitele5min/4h sunt acceptate");
  resetTimer();aplica(cerere(ID1,100,EPOCH+5000,duration));
  verifica(stareTimer.activ()&&stareTimer.ramasSec(nowMs)==duration/1000,"Toleranta5s in viitor nu depaseste durata solicitata");
 }
 resetTimer();clockValid=false;aplica(cerere());verifica(!stareTimer.activ(),"Pornirea cere sincronizare NTP reala");
 for(int64_t delta:{-30001LL,5001LL}){resetTimer();aplica(cerere(ID1,80,EPOCH+delta));verifica(!stareTimer.activ(),"Comanda expirata sau prea in viitor respinsa");}
 resetTimer();aplica(cerere(ID1,80,EPOCH-30000));verifica(stareTimer.activ()&&stareTimer.ramasSec(nowMs)==270,"Limita30s scade transportul din durata");
 resetTimer();aplica(cerere(ID1,80,EPOCH,300000,"fedcba9876543210"));verifica(!stareTimer.activ()&&vitezaDinAplicatie==0,"ID de boot anterior respins in AUTO");
 resetTimer();aplica(cerere());stareTimer.incepeBoot("fedcba9876543210");vitezaBaza=0;aplica(cerere());
 verifica(!stareTimer.activ()&&vitezaDinAplicatie==0&&stareTimer.cerere().id[0]=='\0',"Restart abandoneaza RAM si snapshotul nu restaureaza timerul");
 resetTimer();aplica(schimba(cerere(),ID1,"ABCDEF00000000000000000000000001"));verifica(!stareTimer.activ(),"ID strict hex minuscul");
 resetTimer();nowMs=0x100000000ULL-1000;aplica(cerere());nowMs+=299999;fan();
 verifica(stareTimer.activ()&&stareTimer.ramasSec(nowMs)==1,"Deadline monoton64 traverseaza rollover millis");
 nowMs+=1;fan();verifica(!stareTimer.activ()&&isAutoMode,"Expirare exacta dupa rollover64");
 resetTimer();nowMs=3000000000000ULL;aplica(cerere());nowMs+=300000;intretineTimer();
 verifica(!stareTimer.activ(),"Uptime mare nu trunchiaza deadline la32biti");
 for(int64_t epoch:{946684799999LL,253402300800000LL}){
  resetTimer();wallMs=epoch;aplica(cerere(ID1,80,epoch));verifica(!stareTimer.activ(),"Timestamp in afara contractului2000..9999 respins");
 }
 // Executam blocul de coada/poll real: dupa primele4evenimente poll vede B,
 // dar mai raman anulari/trepte vechi. Ele trebuie sa reciteasca tot B.
 for(const char* path:{"/timer","/speed","/"}){
  resetTimer();aplica(cerere());wallMs+=1000;nowMs=60000;
  serverSettings=cerere(ID2,90,wallMs);
  for(int i=0;i<4;++i)eveniment("/live_mode","false");
  eveniment(path,std::string(path)=="/"?"{\"speed\":0,\"timer\":false}":"false");
  proceseaza();verifica(stareTimer.activ()&&stareTimer.viteza()==90&&events.size()==1,"Poll accepta B inaintea ultimului eveniment vechi");
  nowMs+=1;proceseaza();verifica(stareTimer.activ()&&stareTimer.viteza()==90&&logCount("Timer anulat: revenire AUTO")==0,"Evenimentul vechi ramas nu anuleaza B acceptat prin poll");
 }
 resetTimer();serverSettings=cerere();
 eveniment("/timer",obiect());eveniment("/speed","0");eveniment("/timer/viteza_pct","80");proceseaza();
 verifica(reads==1&&stareTimer.activ(),"Intentiile din acelasi lot au o singura recitire coerenta");
 readOk=false;eveniment("/timer","false");proceseaza();
 verifica(stareTimer.activ()&&necesitaReconciliere,"Citirea esuata nu aplica o intentie stream partiala");
 int before=reads;nowMs+=9999;proceseaza();verifica(reads==before,"Retry de reconciliere respecta pauza10s");
 readOk=true;serverSettings="{\"speed\":0,\"timer\":false}";nowMs+=1;proceseaza();
 verifica(reads==before+1&&!stareTimer.activ()&&vitezaDinAplicatie==0,"Retry aplica anularea curenta confirmata de citire");
 std::cout<<verificari<<" verificari timer, "<<esecuri<<" esecuri\n";return esecuri?1:0;
}
