# CK-05/A4/2-T0 – Deploy-felderítés és Minecraft acceptance előkészítése

## Státusz és döntés

**CONDITIONAL**

Felderítés dátuma: 2026-10-29. A vizsgálat kizárólag read-only repository-, kliens- és szerverellenőrzésekből állt. Nem történt build, deploy, restart, SAVE, REBUILD, adatbázis- vagy Minecraft-világmódosítás, mentéskészítés, commit vagy push.

Az új build artifact, a telepítési útvonal és a rollback-mechanizmus azonosítható, de a jelenleg futó Python szolgáltatás, a Fabric szervermod és a Fabric kliensmod nem a CK-05/A4/1 + FIX/1 + FIX/2 együttállást használja. A szerver- és kliens-JAR egymással sem azonos. A meglévő mentések használhatók korábbi állapot visszaállítására, de nem frissek a T1 előtti aktuális állapothoz.

Ezért a jelenlegi döntés:

> **T1: NO-GO mindaddig, amíg a dokumentum végén felsorolt előfeltételek nem teljesülnek.**

Az előfeltételek igazolása után a döntés külön projektgazdai jóváhagyással GO-ra válthat; ez a T0-jegyzőkönyv önmagában nem engedélyez telepítést.

## 1. Repository és build inventory

### Git-állapot

| Tulajdonság | Érték |
| --- | --- |
| Repository | `C:\Users\tamas\IdeaProjects\bloCKommanderFabric` |
| Branch | `main` |
| HEAD | `9de4524f81e8a1b3a1919a4b9f282c59772f0da5` |
| HEAD leírás | `feat(graph): add multi-cell topology discovery` |
| Staged fájl | nincs |
| Unstaged tracked fájl | 18 |
| Untracked fájl | 15 a T0 dokumentum létrehozása előtt |

A munkafa szándékosan nem tiszta. Az unstaged és untracked állományok együtt tartalmazzák az A4/1, FIX/1 és FIX/2 koherens implementációját. A T0 semmit nem stage-elt, nem stash-elt és nem resetelt.

Releváns Python-változások:

- `python-service/src/ck_webhook.py`;
- `python-service/src/hangar/graph_application.py`;
- `python-service/src/hangar/graph/__init__.py`;
- új `python-service/src/hangar/graph/placement.py`;
- HTTP- és placement-intent tesztek.

Releváns Fabric-változások:

- többcellás `geometry` blockstate és új modellek;
- discovery és placement response DTO-k;
- `WIRE_PLACE_INTENT` command és `/graph/wire/place` transport;
- authoritative teljes-snapshot Wire-projekció;
- `AUTO` és guggolásos `CROSS` interaction;
- transport-, fencing-, interaction-, asset- és projector-regressziós tesztek.

### Build artifactok

A jelenlegi artifactok a korábban sikeresen lefutott `cleanTest buildAll` eredményei; a T0 nem építette újra őket.

| Artifact | Méret | SHA-256 | Módosítás |
| --- | ---: | --- | --- |
| `build/libs/blockommander-fabric-0.1.0.jar` | 8 359 917 byte | `77297C1A1D5FFCABDE280032EBC904D8AECA87D5A447C235FA338926131D71DD` | 2026-10-28 20:38:22 +01:00 |
| `build/libs/blockommander-fabric-0.1.0-sources.jar` | 50 494 byte | `2C1059B59EC8FFF1CFE414CF1ADD6823E77334E7ADBCB0E2A7445BAF77420BE7` | 2026-10-28 20:40:08 +01:00 |
| `build/distributions/blockommander-runtime-0.1.0.zip` | 8 621 934 byte | `90565FD8D20D4B72817ACC8F58F892D310370B87EB6074AEF0ADA487A2A2CFE8` | 2026-10-28 20:40:12 +01:00 |

Utolsó dokumentált tesztkapu:

- 97 Kotlin/JVM teszt: PASS;
- 432 Python teszt: PASS;
- `cleanTest buildAll`: BUILD SUCCESSFUL.

### Telepített és új JAR-ok összehasonlítása

| Hely | Méret | SHA-256 | Következtetés |
| --- | ---: | --- | --- |
| új build | 8 359 917 | `77297C1...D71DD` | A4/1 + FIX/2 |
| Fabric szerver | 8 324 448 | `B697FF50...96387` | régebbi, nem egyezik a builddel |
| Fabric kliens | 8 308 395 | `8E6EF676...83301` | régebbi, nem egyezik a builddel vagy a szerverrel |

A szerver-JAR `fabric.mod.json` fájlja ugyan `0.1.0` verziót közöl, de a hash-összehasonlítás bizonyítja, hogy a névleges verzió nem elég az artifact azonosítására. A szerverben még az `axis=x|z` CK Wire blockstate található, a `GraphApiClient` csak `wire` és `bind` command útvonalat ismer, és nincs `WIRE_PLACE_INTENT` marker. Az új build `geometry` blockstate-et és `/wire/place` transportot tartalmaz.

## 2. Futó környezet

### Python webhook

| Tulajdonság | Bizonyított érték |
| --- | --- |
| Gép | `192.168.3.4`, SSH port `2227` |
| Folyamat | PID `3906690` |
| Indítás | `screen` session: `blockommander-fabric-python` |
| Processz | Python 3.12 + `waitress-serve --listen=127.0.0.1:5001 --call ck_webhook:create_app` |
| Forrás | `/home/mcuser/blockommander-fabric-python/src` |
| Start script | `/home/mcuser/blockommander-fabric-python/start-python.sh` |
| Konfiguráció | `/home/mcuser/blockommander-fabric-python/.env` |
| Port | `127.0.0.1:5001` |
| Health | `{"database":"configured","status":"ok","world_driver":"fabric"}` |
| Példányszám | egy Waitress processz |

A konfigurációt a start script `set -a; . ./.env; set +a` módon tölti be. A Graph authentikáció a Python `.env` és a Fabric `/home/mcuser/.blockommander-graph.env` konfigurációján alapul. A fájlok titkos értékei jogosan nem voltak olvashatók a felderítő felhasználóval; az aktuális secret-egyezést a T0 nem bizonyította újra. A változatlan PID-ekkel futó korábbi CK-04/T8 acceptance során a hitelesített Graph GET működött, de T1 előtt az egyezést ismét, titokérték kiírása nélkül ellenőrizni kell.

Az élő Python **nem** a FIX/1 verzió:

- az élő `src/hangar/graph/placement.py` hiányzik;
- az élő forrásban nincs `WIRE_PLACE_INTENT`, `/graph/wire/place`, `affected_cells` vagy `UNSUPPORTED_INTENT` marker;
- a releváns élő fájlok hash-e eltér a repositorytól.

| Python fájl | Repository SHA-256 | Élő SHA-256 |
| --- | --- | --- |
| `ck_webhook.py` | `38ABD309...D5FAE` | `915F096E...A89DE` |
| `hangar/graph_application.py` | `7E7F77E3...BD86` | `421002C1...4AB1` |
| `hangar/graph/placement.py` | `F5205E3C...0BB8E` | hiányzik |
| `hangar/graph/__init__.py` | `61CADD10...AFDE` | `B81D58DF...CFFF5` |

Read-only ellenőrzés: `/health`, processzlista, `screen -ls`, forráshash és hitelesített Graph GET. Kontrollált restart módja: kizárólag a `blockommander-fabric-python` screen leállítása, majd a fenti `start-python.sh` új példányának indítása; a deploy script ezt readiness ellenőrzéssel és automatikus source rollbackkel kezeli.

### Fabric szerver

| Tulajdonság | Bizonyított érték |
| --- | --- |
| Gyökér | `/home/mcuser/minecraft_fabric_26.3` |
| Folyamat | PID `3900974` |
| Indítás | `screen` session: `blockommander-fabric`; `./start.sh` |
| Java | OpenJDK `25.0.4.1` |
| Minecraft | `26.3` |
| Fabric Loader | `0.19.5` |
| Fabric API | `/mods/fabric-api-0.164.0+26.3.jar` |
| Port | `25566`, ténylegesen LISTENING |
| Világ | `/home/mcuser/minecraft_fabric_26.3/world`; `level-name=world` |
| Modok | `/home/mcuser/minecraft_fabric_26.3/mods` |
| bloCKommander JAR | `mods/blockommander-fabric-deployed.jar` |
| JAR SHA-256 | `B697FF50E1020305F2033E92321F71545D196FDC0DDB58016E354407E5796387` |

A szerver a `start.sh` segítségével tölti be a Graph secret konfigurációt, majd `/usr/lib/jvm/java-25-openjdk-amd64/bin/java -Xms1G -Xmx4G -jar fabric-server-launch.jar nogui` paranccsal indul. Szabályos leállítása a screen konzolon `save-all flush`, majd `stop`; ugyanarra a világra párhuzamos szerverpéldány nem indítható.

A repositoryban nincs külön üzemi `config` könyvtárra támaszkodó bloCKommander-konfiguráció. A világ, a modok, a szerverpropertyk, a start script és a titkos Graph-env külön fájlokban vannak.

### PostgreSQL

- container: `blockommander-db`;
- image: `postgres:latest`;
- állapot: running;
- publikált port: `5433`;
- a containeren belüli `pg_isready -U admin -d bloCKommander`: accepting connections;
- a webhook `/health` adatbázisa configured.

A `deploy-hp.ps1` a Python deploy előtt `docker exec blockommander-db pg_dump -U admin -d bloCKommander` paranccsal készít dumpot. A T0 ezt nem futtatta le és nem írt az adatbázisba.

### Fabric kliens

| Tulajdonság | Bizonyított érték |
| --- | --- |
| Launcher profil | `bloCKommander Fabric 26.3` |
| Game directory | `C:\Users\tamas\Minecraft\bloCKommander` |
| Version ID | `fabric-loader-0.19.5-26.3` |
| Betöltött Minecraft/Loader | 26.3 / 0.19.5 |
| Mods könyvtár | `C:\Users\tamas\Minecraft\bloCKommander\mods` |
| bloCKommander JAR | `blockommander-fabric-0.1.0.jar` |
| JAR SHA-256 | `8E6EF676F3F26AC26DEF103CBE148C95A4465DBFA202CFEEE6CC48C8D9383301` |
| Fabric API | `0.164.0+26.3` |

A profil nem ad meg explicit `javaDir` értéket. A kliens sikeresen betöltötte a Java 25-öt megkövetelő modot, de a launcher által ténylegesen használt Java patchverzió a rendelkezésre álló logból nem bizonyítható; állapota **UNKNOWN/kompatibilisnek valószínűsített**.

A következő telepítéshez a Minecraft klienst teljesen be kell zárni, majd az új JAR-t a kliens mods könyvtárába kell másolni. A `deploy-client.ps1` előbb időbélyeges `mods-backup` mentést készít, utána hash-egyezést ellenőriz.

## 3. Kompatibilitási mátrix

| Komponens | Repository/build | Futó/telepített | Egyezik? |
| --- | --- | --- | --- |
| Python Graph application | FIX/1 placement domain, 432 teszt PASS | régi forrás, `placement.py` hiányzik | **NEM** |
| Python HTTP API | `/graph/wire/place`, `WIRE_PLACE_INTENT` | endpoint/operation nincs az élő forrásban | **NEM** |
| Fabric szervermod | SHA `77297C1A...D71DD`, `geometry`, placement intent | SHA `B697FF50...96387`, régi `axis`, csak wire/bind | **NEM** |
| Fabric kliensmod | SHA `77297C1A...D71DD` | SHA `8E6EF676...83301` | **NEM** |
| Minecraft verzió | 26.3 | szerver és kliens 26.3 | **IGEN** |
| Fabric Loader | 0.19.5 | szerver és kliens 0.19.5 | **IGEN** |
| Java runtime | build target Java 25 | szerver 25.0.4.1; kliens patch UNKNOWN | **SZERVER IGEN / KLIENS FELTÉTELES** |

### Szerződés-ellenőrzés

| Szerződés | Repository/build | Élő együttállás |
| --- | --- | --- |
| `WIRE_PLACE_INTENT` | jelen | hiányzik Pythonból és Fabricből |
| `/graph/wire/place` | jelen | hiányzik |
| `schema_version=1` | jelen | a korábbi Graph contractban jelen |
| `AUTO`, `CROSS` | jelen | placement intentként hiányzik |
| `state.wire_cells` | authoritative renderforrás | korábbi CK-04 snapshotban jelen |
| `discovery.schema_version=1` | jelen | A3 eredetű forrásban várható; T1 előtt Graph GET-tel újra ellenőrzendő |
| `placement.affected_cells` | jelen | hiányzik |
| `wire_diff` | jelen | korábbi wire commandban jelen, placement-válasz nincs |
| epoch/revision fencing | tesztelt | korábbi Graph API-ban jelen |
| request-idempotencia | tesztelt | korábbi Graph API-ban jelen, placementre nincs élő implementáció |

Mutáló POST-próba nem történt. Az endpoint hiányát az élő forrás és a futó JAR statikus vizsgálata bizonyítja, nem próba-placement.

## 4. Mentési és rollback terv

### Meglévő rollback artifactok

Bizonyítottan rendelkezésre állnak:

- több korábbi szerver-JAR és hozzájuk tartozó `world.tar.gz` a `/home/mcuser/minecraft_fabric_26.3/deployment-backups` alatt;
- korábbi `python-service.tar.gz` és PostgreSQL dumpok;
- több kliens-JAR a `C:\Users\tamas\Minecraft\bloCKommander\mods-backup` alatt.

Fontos példák:

| Artifact | SHA-256 | Megjegyzés |
| --- | --- | --- |
| `20261027-191607-mod/.../blockommander-fabric-deployed.jar` | `EEB9234D...418A` | a jelenlegi szervertelepítés előtti JAR |
| `20261027-191607-mod/world.tar.gz` | `13D8E1F6...AD2` | a jelenlegi szervertelepítés előtti világ |
| `20261026-123524-webhook/python-service.tar.gz` | `CFBC5DD9...E385` | korábbi Python source backup |
| `20261026-123524-webhook/blockommander-predeploy.sql` | `1498A60D...54BE` | korábbi DB dump |
| `20261026-192321-mod/.../blockommander-fabric-deployed.jar` | `8E6EF676...83301` | megegyezik a jelenlegi kliens-JAR-ral |

Ezek visszaállíthatósági bizonyítékok, de nem helyettesítik a T1 indulásakor készítendő friss mentéseket. A legfrissebb meglévő világmentés 2026-10-27-i, a megjelölt Python/DB pár 2026-10-26-i; azóta a világ változott.

### A deploy szkriptek védelmei

- `deployWebhook`: DB dump, teljes Python source backup `.venv` nélkül, egyetlen screen processz, health-check, sikertelen deploynál automatikus source rollback.
- `deployServerMod`: szabályos Fabric stop, világ- és régi-JAR-mentés, egyetlen új JAR, port/log/health readiness, sikertelen deploynál régi JAR + világ automatikus visszaállítása.
- `deployClient`: a korábbi kliens-JAR időbélyeges mentése és másolás utáni SHA-256 ellenőrzés.

### Rollback hatóköre

1. **Csak bináris/source rollback** elegendő, ha a deploy utáni acceptance még nem végzett SAVE-et, nem hozott létre megőrzendő DB-objektumot, és a teszt-Stage mentetlen világprojekciója authoritative reconciliationnel eltávolítható.
2. **Adatbázis-visszaállítás** kell, ha a teszt SAVE-et, csarnok/Stage létrehozást vagy más tartós domainmódosítást végzett, és pontos predeploy állapotra kell visszaállni.
3. **Minecraft-világ-visszaállítás** kell, ha a világ nem reconciliálható biztonságosan, idegen blokkok vagy más játékosváltozások érintettek, illetve teljes predeploy állapot szükséges.
4. DB- és világ-visszaállítás esetén ugyanahhoz a karbantartási ponthoz tartozó párost kell használni; eltérő időpontú DB és világ összekeverése tilos.

A jelenlegi négy hangár megőrizhető, ha:

- T1 előtt minden Stage clean, vagy a megőrzendő dirty workspace-ről külön projektgazdai döntés születik;
- friss DB dump és leállított Fabric mellett friss világmentés készül;
- az acceptance külön teszt-Stage-ben történik;
- a négy meglévő hangárban csak baseline/reconnect megfigyelés történik;
- cleanup és rollback előtt a teszt során keletkezett tartós változásokat tételesen rögzítjük.

### Teljes kézi visszaállítási sorrend

Potenciálisan destruktív, csak külön engedéllyel:

1. Minecraft kliens bezárása és Fabric szabályos leállítása.
2. Python webhook leállítása.
3. Ha szükséges, PostgreSQL visszaállítása a friss predeploy dumpból.
4. A hozzá tartozó világ visszaállítása a friss `world.tar.gz` fájlból.
5. A korábbi Python source tar visszaállítása, a titkos `.env` megőrzésével.
6. A korábbi szerver-JAR és kliens-JAR visszaállítása.
7. PostgreSQL readiness, majd Python health.
8. Fabric indítás és readiness.
9. Kliensindítás, reconnect és a négy hangár read-only ellenőrzése.

## 5. Javasolt kontrollált telepítési sorrend

Ez végrehajtható terv, de T0-ban **nem hajtandó végre**.

1. **Read-only preflight:** HEAD/worktree, artifact-hash, PID, port, egyprocesszes invariáns, négy meglévő Stage és minden további memóriabeli workspace epoch/revision/saved_version/dirty állapotának rögzítése. Dirty, megőrzendő workspace esetén STOP.
2. **Karbantartási ablak:** minden játékos kilép, a kliens zárva marad; a teszt alatt senki nem módosít DB-t vagy világot.
3. **Potenciálisan destruktív:** Fabric `save-all flush`, majd szabályos `stop`. Ezzel a világ befagy a mentési ponthoz.
4. **Mentések:** friss PostgreSQL dump; aktuális Python source backup; aktuális szerver- és kliens-JAR backup; friss világmentés. Mindegyik méretét és SHA-256 hashét rögzíteni kell. A DB és világ időpontját egy közös release-recordban össze kell kötni.
5. **Python FIX/1 deploy:** a runtime ZIP Python tartalmának telepítése, csak a webhook restartja. Fabric maradjon leállítva.
6. **Python kapu:** egyetlen processz, `/health`, forráshash/marker, titokérték nélküli auth-egyezés, read-only Graph GET a meglévő Stage-ekre. Mutáló placement POST továbbra sem szükséges.
7. **Fabric szerver-JAR deploy:** régi JAR cseréje az új, `77297C1A...D71DD` artifactra, majd szabályos indítás és log/port/health ellenőrzés.
8. **Fabric kliens-JAR deploy:** zárt kliens mellett ugyanennek a hashnek a telepítése; backup- és célhash ellenőrzése.
9. **Verziókapu:** Python FIX/1 markerek, szerver- és kliens-JAR azonos hash, Minecraft/Fabric/Java kompatibilitás.
10. **Minecraft reconnect:** először kizárólag a négy meglévő hangár baseline projekciója és SAVE-táblája.
11. **CK Wire acceptance:** csak elkülönített teszt-Stage-ben, az alábbi sorrendben.

A repository `deployAll` sorrendje kliens → webhook → szerver. Kontrollált T1-ben a kliens végig zárva tartása mellett ez is kompatibilis, de a fenti sorrend jobb szerveroldali kapuzást ad: az új Fabric csak már ellenőrzött Python FIX/1 után indul el. A kliens csak azonos hash igazolása után csatlakozhat.

## 6. Minecraft acceptance forgatókönyv

### Általános szabályok

- A négy értékes hangárban csak baseline és reconnect megfigyelés megengedett.
- A placement, bind, SAVE és negatív próbák külön tesztcsarnokban/Stage-ben történjenek.
- Minden mutáló próba előtt rögzítendő: Stage ID, epoch, revision, saved_version, dirty, block/edge/route/wire darabszám.
- Bizonyíték: Minecraft képernyő/üzenet, hitelesített Graph GET és ahol perzisztencia érintett, read-only PostgreSQL SELECT.
- Egy sikertelen eset után előbb állapotfelvétel, csak utána cleanup; automatikus javítás vagy újrabind tiltott.

### A. Alapállapot

| Próba | Előfeltétel | Játékosi művelet | Várt Minecraft | Várt Python/Graph | Bizonyíték | Cleanup/rollback |
| --- | --- | --- | --- | --- | --- | --- |
| A1 kapcsolódás | azonos kliens/szerver JAR; Python health | csatlakozás | nincs mod/protokollhiba | nincs domainmódosítás | kliens- és szerverlog | nincs |
| A2 négy hangár | friss backup; baseline rögzítve | mind a négy bejárása | programblokkok, Wire és SAVE a mentett állapot szerint | clean Stage-ek változatlanok | Graph GET + képernyő | eltérésnél STOP, nincs renderjavító mutáció |
| A3 CK-04 E–W | meglévő mentett E–W vezeték | reconnect után megtekintés | helyes `east_west`, zöld ahol VALID route van | azonos edge/route/wire ID-k | Graph GET + képernyő | eltérésnél rollback-döntés |

### B. Placement

| Próba | Előfeltétel | Játékosi művelet | Várt Minecraft | Várt Python/Graph | Bizonyíték | Cleanup/rollback |
| --- | --- | --- | --- | --- | --- | --- |
| B1 önálló Wire | üres tesztcella | normál jobb kattintás | nullavégű cyan DRAFT | 1 `SINGLE`, üres directions; revision +1; nincs edge | Graph GET, üzenet | dokumentált break vagy teszt-Stage eldobása |
| B2 két cella | B1 megvan, szomszéd üres | mellé AUTO placement | kölcsönösen összekötött két cella | mindkét maszk reciprok; egy atomi revision | Graph GET `placement` + teljes state | csak állapotfelvétel után cleanup |
| B3 E–W egyenes | elkülönített sor | további AUTO cellák | folytonos E–W geometria | reciprok E/W maszkok, edge nélkül | Graph GET + képernyő | teszt-Stage cleanup |
| B4 N–S egyenes | elkülönített oszlop | AUTO cellák | folytonos N–S geometria | reciprok N/S maszkok | Graph GET + képernyő | teszt-Stage cleanup |
| B5 négy sarok | négy külön tesztterület | N→E, E→S, S→W, W→N építés | mind a négy sarokmodell helyes | minden `SINGLE` pontosan két megfelelő irány | Graph GET + 4 képernyő | területenként cleanup |
| B6 három szomszéd | cél körül három kapcsolható végpont | AUTO a középre | nincs új blokk; strukturált hiba | `AMBIGUOUS_PLACEMENT`, revision változatlan | előtte/utána Graph GET | környező draftok dokumentált eltávolítása |
| B7 négy szomszéd AUTO | négy végpont, üres közép | normál jobb kattintás | nincs implicit CROSS | ambiguity, revision változatlan | Graph GET + üzenet | mint B6 |
| B8 explicit CROSS | négy egyértelmű végpont | guggolás + jobb kattintás középre | CROSS, megemelt N–S híd | `NS=N+S`, `EW=E+W`; egy atomi revision | Graph GET placement/state + képernyő | állapotfelvétel után teszt cleanup |
| B9 CROSS izoláció | B8 megvan | mindkét tengely végpontjainak vizsgálata | vizuálisan keresztez, logikailag nem egyesül | két külön discovery komponens/csatorna | discovery DTO | nincs bind a vizsgálat előtt |

### C. Graph és bind

| Próba | Előfeltétel | Játékosi művelet | Várt Minecraft | Várt Python/Graph | Bizonyíték | Cleanup/rollback |
| --- | --- | --- | --- | --- | --- | --- |
| C1 nincs auto-edge | teljes fizikai út, még nincs bind | csak placement befejezése | cyan Wire | 0 új GraphEdge/Route | Graph GET | nincs |
| C2 Stage-wide bind | egyértelmű START→Wire→STOP | explicit Élbekötés (`/ckbind`) | VALID út zöld | 1 GraphEdge + 1 VALID EdgeRoute | Graph GET + üzenet | teszt edge-et csak jóváhagyott cleanup törölhet |
| C3 hiányos DRAFT | nyitott fizikai vég | bind | cyan marad; sárga discovery-diagnosztika | nincs hamis edge; `OPEN_WIRE` jellegű diagnosztika | Graph GET + játéküzenet | draft cleanup |
| C4 többértelmű | több bindjelölt | bind | elutasítás, nincs hamis zöld út | nincs új edge/route; strukturált diagnosztika | előtte/utána ID-lista | teszt layout cleanup |

### D. Perzisztencia és reconnect

| Próba | Előfeltétel | Játékosi művelet | Várt Minecraft | Várt Python/Graph | Bizonyíték | Cleanup/rollback |
| --- | --- | --- | --- | --- | --- | --- |
| D1 SAVE előtt | dirty teszt-Stage | SAVE tábla megtekintése | `NINCS MENTVE` | dirty=true, saved_version változatlan | Graph GET + tábla | nincs |
| D2 SAVE után | D1 állapot, külön engedély | SAVE | `MENTVE` | dirty=false, saved_version +1; DB snapshot egyezik | Graph GET + SELECT | rollbackhez DB+világ páros kellhet |
| D3 reconnect | rögzített mentett vagy tudatosan mentetlen állapot | kilépés/belépés | authoritative geometria és szín helyreáll | tartalom és ID-k változatlanok | log + Graph GET | eltérésnél STOP |
| D4 Python restart | minden megőrzendő Stage clean; külön GO | csak webhook restart, majd reconnect | mentett Stage helyreáll; SAVE helyes | új epoch, revision 0, mentett snapshot azonos | PID/epoch/Graph GET | mentetlen tesztállapot elvesztése előre elfogadandó |

### E. Negatív esetek

| Próba | Előfeltétel | Játékosi művelet | Várt Minecraft | Várt Python/Graph | Bizonyíték | Cleanup/rollback |
| --- | --- | --- | --- | --- | --- | --- |
| E1 idegen blokk | tesztcélon idegen blokk | Wire placement/projection kiváltása | idegen blokk megmarad; nincs részleges render | nincs vagy fail-closed módosítás | képernyő + Graph GET | idegen blokkot a játékos saját döntéssel bontja |
| E2 stale revision | kontrollált, külön engedélyezett konkurens változás | stale kliensművelet | nincs optimista Wire; reconciliation üzenet | command legfeljebb egyszer POST; GET helyreállít | transportlog + Graph GET | nincs automatikus retry |
| E3 timeout | biztonságosan injektálható tesztkörnyezet szükséges | placement egy mesterséges timeout alatt | nincs dupla blokk | egy POST, majd GET; request-idempotencia | HTTP/log korreláció | ha nincs izolált hiba-injektálás, SKIP |
| E4 Wire sérülés | edge/route ID-k rögzítve | egy Wire break | cella eltűnik, környezet nem ígér automatikus javítást | edge/route nem törlődik és nem lesz automatikusan BROKEN | Graph GET | ne bindolj automatikusan; külön cleanup-döntés |
| E5 nincs PUBLISH/BROKEN | bármely draft/hiba | megfigyelés | nincs automatikus publish-visszajelzés | nincs automatikus PUBLISH vagy BROKEN domainírás | Graph GET + DB SELECT | nincs |

Az E2/E3 próbát produkciós hálózati vagy szolgáltatás-kiesés előidézésével nem szabad végrehajtani. Ha nincs elkülönített, biztonságos hiba-injektálási lehetőség, az automatizált regressziós bizonyítékot kell elfogadni és az élő próbát SKIP jelöléssel lezárni.

## 7. Kockázatok

| Kockázat | Jelenlegi állapot | Kezelés |
| --- | --- | --- |
| Python/Fabric verzióeltérés | **bizonyított** | Python FIX/1 előbb, majd szerver- és kliens-FIX/2 |
| szerver/kliens JAR eltérés | **bizonyított** | ugyanazon build SHA telepítése mindkét oldalra |
| régi `axis` → új `geometry` | **bizonyított váltás** | reconnect/reconciliation és CK-04 E–W acceptance |
| mentetlen Stage | aktuális állapot UNKNOWN | T1 előtt minden workspace read-only leltára; dirty esetén STOP |
| friss rollback artifact | még nincs, T0-ban tiltott | T1 első mutáló lépése előtt friss, hash-elt mentéspár |
| authentikáció aktuális egyezése | értékek nem olvashatók; korábbi működés bizonyított | titokérték nélküli hash/SET egyezés T1-ben |
| idegen blokkütközés | automatizáltan védett, élőben nem bizonyított | külön teszt-Stage, fail-closed acceptance |
| reconnect/Python restart | CK-04-ben PASS, új geometriával még nem próbált | D3/D4 kontrollált sorrendben |
| break utáni maszkok | ismert korlát | nincs automatikus javítás ígérete; külön állapotfelvétel |
| CROSS render | csak automatizált asset/projection teszt | kötelező B8/B9 játékbeli acceptance |
| kliens Java patchverzió | UNKNOWN | T1 preflightban launcher runtime rögzítése |
| névleges modverzió minden JAR-ban 0.1.0 | hash alapján eltérő binárisok | kizárólag SHA-256 legyen release-azonosító |

## 8. GO/NO-GO kapu és T1 előfeltételei

### Kötelező T1 GO-feltételek

1. Külön projektgazdai T1 GO.
2. A kliens bezárva, játékosok kijelentkezve, karbantartási ablak biztosítva.
3. Minden memóriabeli Stage/workspace leltára elkészült; nincs megőrzendő dirty állapot. Bármely dirty Stage: **NO-GO**.
4. Az új JAR és runtime ZIP hash-e újra egyezik a jelen dokumentummal, vagy az eltérést új release-record indokolja.
5. Friss Python source-, PostgreSQL-, szerver-JAR-, kliens-JAR- és világmentés készül, méret- és SHA-256 ellenőrzéssel.
6. A DB dump és világmentés ugyanahhoz a befagyasztott karbantartási ponthoz tartozik.
7. A rollback célfájlok olvashatók, és a kézi visszaállítás pontos célútvonalai rögzítettek.
8. A Python deploy után pontosan egy processz, sikeres `/health`, FIX/1 source marker/hash, helyes `world_driver=fabric` és titokérték nélküli auth-egyezés.
9. Az új Fabric szerver- és kliens-JAR SHA-256 értéke egymással és a jóváhagyott builddel azonos.
10. Minecraft 26.3, Loader 0.19.5, Fabric API 0.164.0+26.3 és Java 25 kompatibilitás igazolt mindkét oldalon.
11. Elkülönített acceptance Stage és előre jóváhagyott cleanup/rollback döntési pont rendelkezésre áll.

### Azonnali NO-GO feltételek

- megőrzendő dirty workspace;
- sikertelen vagy ellenőrizhetetlen friss DB-/világmentés;
- hiányzó régi Python/JAR/client rollback artifact;
- eltérő új szerver- és klienshash;
- FIX/1 endpoint hiánya az új Python deploy után;
- több Python webhook processz;
- sikertelen health, Graph auth vagy read-only Graph GET;
- idegen blokk vagy Stage-azonosítási bizonytalanság az acceptance területén.

## 9. Végső összegzés

- **T0 státusz:** CONDITIONAL.
- **Bizonyított futó verziók:** Python 3.12/Waitress egy processzen, régi pre-FIX/1 forrás; Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3, OpenJDK 25.0.4.1; régi, `axis` blockstate-es szerver-JAR; külön régi kliens-JAR.
- **Szükséges telepítés:** Python FIX/1 source/runtime, új Fabric szerver-JAR és ugyanazon új JAR a kliensre.
- **Rollback:** technikailag rendelkezésre áll source/JAR/client/world/DB szinten, de T1 előtt friss, konzisztens és hash-elt mentéskészlet kötelező.
- **Legfontosabb kockázat:** a három élő komponens jelenleg nem kompatibilis az új placement-intent acceptance-tel; a névleges `0.1.0` verzió ezt elfedi.
- **T1 javaslat most:** NO-GO. A fenti kapuk teljesítése és külön projektgazdai döntés után GO-ra válthat.

**STOP:** T1 nem indult el.
