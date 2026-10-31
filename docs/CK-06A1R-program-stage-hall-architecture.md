# CK-06/A1-R – Program–Stage–Hangár architektúra korrekció

- Dátum: 2026-10-30
- Mód: **READ-ONLY tervezés**
- Implementációs felhatalmazás: **nincs**
- Értékelés: **CONDITIONAL GO**

## 1. Projektgazdai döntések és új invariánsok

### 1.1 Kötelező domainhatárok

**Program**

- hangártól független, stabil `program_id` azonosítójú aggregátum;
- egy tulajdonosa és pontosan egy root Stage-e van;
- az aktív Stage-fát, valamint a biztonságosan megőrzött, már nem aktív
  részfákat fogja össze;
- a SAVE és később a PUBLISH hatóköre;
- ugyanaz a Program később más hangárba vagy több külön hangárba is mountolható.

**Stage**

- stabil `stage_id`, egy Programhoz tartozik;
- saját snapshot, Graph, workspace epoch, working revision, dirty és saved
  version állapota van;
- a Stage-hierarchia ProgramBlock-kapcsolatokból épül fel;
- a hierarchia ciklusmentes, de nincs mesterséges mélységlimit;
- a Stage domainpozíciói relatívak, nem Minecraft-világkoordináták.

**Hangár**

- Minecraft-megjelenítési és szerkesztési infrastruktúra;
- egy Programot mountol, és egyszerre pontosan egy Stage-et vetít;
- a Programot és Stage-et nem birtokolja;
- DESTROY kizárólag a Hallt, controljait és világépítményét törölheti;
- Fabric restart után az aktív Stage első változatban visszaeshet a mountolt
  Program root Stage-ére.

### 1.2 SAVE-invariánsok

1. Navigáció soha nem ment.
2. Minden Stage külön dirty/epoch/revision/saved-version állapotú.
3. SAVE célja a mountolt, aktív Stage-hez tartozó teljes Program.
4. A Program összes dirty és új Stage-e, valamint hierarchy-változása egyetlen
   PostgreSQL-tranzakcióban mentődik.
5. Egyetlen Stage- vagy linkhiba teljes rollbacket okoz.
6. DB-hiba után egyetlen Python workspace állapota sem változhat.
7. A DB-commit és a process-lokális workspace commit két külön fázis.
8. Nem előfeltétel a szülő külön mentése: egy Program-SAVE atomikusan menti a
   még új parentet, childot és linket.
9. A SAVE owner-ellenőrzött.
10. PUBLISH csak teljesen mentett és a későbbi publish-szabályok szerint valid
    Programból indulhat.

### 1.3 Mi változik a CK-06/A felderítéshez képest?

| CK-06/A korábbi javaslat | A1-R korrekció |
|---|---|
| A Stage-fa továbbra is Hallhoz kötött | A Program és minden Stage teljesen Hall-független |
| A Hallon belül több Stage | Egy Programon belül több Stage; a Hall csak mount/projection |
| Child SAVE előtt külön parent SAVE | Egyetlen Program-SAVE ment minden dirty/új Stage-et és linket |
| Stage-szintű SAVE marad az alap | A Stage SAVE belső building block; publikus művelet Program-SAVE |
| `DETACHED` UX felmerült | Első verzióban nincs külön UI; adatvesztésmentes belső retained linkállapot elegendő |
| Stage tárolja a Hallhoz viszonyított origint | Domain Stage csak relatív tartalmat/méretet tárol; az origin a Hall-projekció része |
| Parent-child mezők közvetlenül a Stage-en | Külön ProgramBlock–Stage link az aktív/retained kapcsolat kezelésére |
| Child Stage parent mentéséig nem lehet tartós | Ugyanabban a Program-tranzakcióban új parent és child együtt menthető |
| Navigációs döntések még nyitottak | Közös Hall-active Stage, owner-only, `/ckback`, korlátlan mélység, root fallback rögzített |

## 2. Jelenlegi adatmodell és problémás kapcsolatok

### 2.1 A Stage ma a Hall tulajdona

A `stages` tábla jelenleg:

- `stage_id` UUID elsődleges kulcsot használ;
- `hall_id` mezője `NOT NULL UNIQUE`;
- a Hallra `ON DELETE CASCADE` FK-val mutat
  ([`004_program_stages.sql:20-22`](../python-service/src/hangar/migrations/004_program_stages.sql#L20)).

Ennek három következménye van:

1. Hallonként csak egy Stage lehet.
2. Stage nem nyitható meg másik Hallban.
3. Hall törlése automatikusan törli a Stage-et, majd a Stage FK-kon keresztül a
   blokk-, GraphEdge-, EdgeRoute- és WireCell-tartalmat is.

A repository ugyanezt a modellt kódolja: `ensure_stage()` Hall ID alapján keresi
az egyetlen Stage-et, és a Stage fizikai méretét/originjét a Hall geometriájából
számolja ([`repository.py:353`](../python-service/src/hangar/repository.py#L353)).

### 2.2 A Stage domain DTO ma világ- és Hall-függő

A `StageService` a Stage abszolút originjét a `hall_min_* + origin_rel_*`
összegből számítja ([`stage.py:243`](../python-service/src/hangar/stage.py#L243)).
A Stage view `hall_id`, `world_uuid` és abszolút `origin` mezőt ad
([`stage.py:484`](../python-service/src/hangar/stage.py#L484)).

A Graph context szintén Hall ID-t, world UUID-t és fizikai origint hordoz
([`stage.py:127`](../python-service/src/hangar/stage.py#L127)), és a Kotlin
`GraphStateSnapshot` is `hallId` + `origin` mezőket vár
([`GraphDtos.kt:55`](../src/main/kotlin/me/buciqua/blockommander/runtime/graph/GraphDtos.kt#L55)).

Ez a legmélyebb szétválasztandó kapcsolat. A Graph node-, route- és WireCell
koordináták már relatívak, tehát a mentett graph tartalom jó alap. A fizikai
origin viszont projection context, nem Stage-domain adat.

### 2.3 Egy Stage-re korlátozott Hall DTO és Fabric registry

A webhook Hall-válaszában az `enrich()` mindig a Hall egyetlen biztosított
Stage-ét adja vissza. A Fabric `Hall` adatmodell egyetlen nullable `stage` mezőt
tart ([`HallRegistry.kt:21`](../src/main/kotlin/me/buciqua/blockommander/runtime/HallRegistry.kt#L21)).
A `stageAt()` és `stageBlockAt()` közvetlenül ezt az egy Stage-et használja
([`HallRegistry.kt:170`](../src/main/kotlin/me/buciqua/blockommander/runtime/HallRegistry.kt#L170)).

Nincs:

- `program_id`;
- Hall–Program mount;
- root Stage azonosító;
- több Stage cache;
- Hallonkénti `active_stage_id` vagy switch generation;
- Program-szintű dirty/save státusz.

### 2.4 A jelenlegi SAVE valóban csak egy Stage-re atomi

`StageService.save()` egyetlen workspace `RLock`-ját fogja, majd egyetlen Stage
repository API-ját hívja ([`stage.py:321`](../python-service/src/hangar/stage.py#L321)).

`save_full_stage_snapshot()`:

1. egy Stage sort `FOR UPDATE` zárol;
2. ellenőrzi annak `saved_version` értékét;
3. törli és újraírja ugyanazon Stage teljes blokk/edge/route/wire tartalmát;
4. egy Stage saved versionjét növeli
   ([`repository.py:469`](../python-service/src/hangar/repository.py#L469)).

Ez jó egy-Stage-es tranzakciós primitive, de nem Program-SAVE. Nincs
Program-sor, Program-CAS, több Stage közös tranzakciója vagy hierarchy-írás.

### 2.5 Több in-memory workspace alapja már létezik

A Python `StageService._workspaces` Stage ID szerint több workspace-t tart, és
egy Stage-et csak első eléréskor tölt be
([`stage.py:143-164`](../python-service/src/hangar/stage.py#L143)). Minden
workspace saját immutable snapshotot, dirty flaget, epochot és `RLock`-ot tart
([`stage.py:40`](../python-service/src/hangar/stage.py#L40)).

Ez továbbra is használható. Nem kell új Stage-domain motor, de szükséges egy
Python `ProgramService`/aggregate coordinator, amely a meglévő StageWorkspace-ek
hierarchiáját és közös SAVE-jét szervezi.

### 2.6 A jelenlegi Hall DESTROY adatvesztő lenne az új domainben

`HallService.destroy()` a világ bontása után meghívja a
`HallRepository.delete(hall_id)` műveletet
([`service.py:20-27`](../python-service/src/hangar/service.py#L20)). A repository
fizikailag törli a Hall sort ([`repository.py:337`](../python-service/src/hangar/repository.py#L337)).
A jelenlegi `stages.hall_id ON DELETE CASCADE` ezért a teljes programadatot
eltávolítaná.

**Ez migrációs és lifecycle blokkoló.** Hall DESTROY új szemantikája nem
engedhető productionbe addig, amíg a Stage–Hall FK és cascade meg nem szűnt,
és regressziós teszt nem bizonyítja a Program teljes változatlanságát.

## 3. Javasolt minimális új domain- és PostgreSQL-modell

### 3.1 `programs`

Javasolt mezők:

| Mező | Jelentés |
|---|---|
| `program_id uuid PK` | Hangártól független stabil programazonosság |
| `owner_uuid uuid NOT NULL FK developers` | Program tulajdonosa |
| `program_name varchar(...)` | Felhasználói név |
| `root_stage_id uuid` | Pontosan egy root Stage, DEFERRABLE FK-val |
| `saved_version bigint NOT NULL` | Program-aggregátum CAS/generation |
| `lifecycle` | Első körben `ACTIVE`; későbbi explicit delete/publish alap |
| timestamps | Audit és üzemeltetés |

A `programs.root_stage_id` és `stages.program_id` körkörös létrehozási
függősége egy tranzakción belül nullable→set sorrenddel vagy DEFERRABLE FK-val
kezelhető. A commit végén a root kötelező és ugyanahhoz a Programhoz tartozik.

### 3.2 `stages`

Javasolt változás:

- új, kötelező `program_id` FK, **ON DELETE RESTRICT**;
- `stage_kind` (`ROOT`, `PROGRAM_BODY`; később CONDITION stb.);
- megmarad a stabil `stage_id`, logical `size_*`, snapshot-version,
  port-catalog-version és `saved_version`;
- megszűnik a Hall ownership és az `ON DELETE CASCADE` kapcsolat;
- `origin_rel_*` nem Stage-domain tulajdonság: kivezetendő vagy kizárólag
  projection template-ként kezelendő, de a Stage DTO nem adhat belőle abszolút
  világkoordinátát.

A Stage blokkjai és graph tartalma továbbra is relatív koordinátán maradnak.
Másik Hallba mountoláskor a Fabric a Hall projection origint adja hozzá.

### 3.3 `program_stage_links`

A Stage→Parent Stage és ProgramBlock→Child Stage kapcsolatot egy külön tábla
fejezze ki:

| Mező | Jelentés |
|---|---|
| `program_id` | Aggregate és keresési kulcs |
| `parent_stage_id` | A ProgramBlockot tartalmazó Stage |
| `program_block_instance_id` | Stabil blokk-instance UUID |
| `child_stage_id` | A gyermek Stage |
| `link_state` | `ACTIVE` vagy belső `RETAINED` |
| timestamps | későbbi audit/undo alap |

Javasolt invariánsok:

- `(program_id, parent_stage_id, program_block_instance_id)` aktív állapotban
  egyedi;
- egy child Stage-nek legfeljebb egy aktív parent linkje lehet;
- parent és child ugyanahhoz a Programhoz tartozik;
- parent != child;
- a root Stage nem lehet aktív child;
- az aktív linkek gráfja ciklusmentes.

A `program_block_instance_id` mezőre első körben ne legyen `ON DELETE CASCADE`
FK a `stage_blocks` táblába. A jelenlegi snapshot writer minden SAVE-nél törli
és újra beszúrja a blokk sorokat; cascade FK emiatt helyes SAVE közben is
törölhetné a child linket. A Program repository a mentési tervben ellenőrizze,
hogy minden aktív linkhez létezik megfelelő `PROGRAM` blokk a parent snapshotban.

A ciklusmentességet a Program-lock alatt tiszta domain-validátor ellenőrizze,
majd a repository a DB-tranzakcióban ismét ellenőrizze a teljes aktív linksetet.
A Program sor `FOR UPDATE` lockja sorosítja az ugyanazon Programot módosító
többprocesszes SAVE-eket.

### 3.4 `hall_program_mounts`

Javasolt tartós kapcsolat:

| Mező | Jelentés |
|---|---|
| `hall_id PK FK halls ON DELETE CASCADE` | A projection infrastruktúra |
| `program_id FK programs ON DELETE RESTRICT` | A mountolt Program |
| timestamps / mount generation | programváltás és fencing alap |

Az `ON DELETE CASCADE` itt kizárólag a mount sort törli, nem a Programot. A
`program_id` ne legyen unique: ugyanaz a Program később több Hallban is
megjeleníthető.

Az `active_stage_id` első verzióban ne legyen kanonikus Program-adat. A Fabric
Hall-runtime tartja; restartkor `program.root_stage_id` a fallback. Ha később
aktív nézetfolytatás kell, külön projection preference-ként adható hozzá, nem a
Stage ownership részeként.

### 3.5 Domain- és projection DTO-k szétválasztása

**StageDomainView**:

- program/stage/kind/parent metadata;
- size és relatív blocks;
- epoch/revision/dirty/saved version;
- nincs Hall ID, world UUID vagy abszolút origin.

**HallProjectionContext**:

- hall/world/dimension;
- physical origin és rendelkezésre álló méret;
- mounted program ID, root Stage ID, Fabric active Stage ID;
- navigation/switch generation.

**GraphStateSnapshot** továbbra is relatív graph tartalom. A fizikai wire origin
a HallProjectionContextból származik. Ez megakadályozza, hogy ugyanaz a Stage
másik Hallba nyitva régi világkoordinátákat hordozzon.

Mountkor szerveroldali fit-check kell: a Hall használható Stage-terének el kell
férnie a Stage logical size-ához. Az első verzió ne alkalmazzon implicit cropot
vagy átméretezést.

### 3.6 PROGRAM blokk és portkatalógus

- új type key: `PROGRAM`;
- Minecraft név: `ProgramBlock`;
- `SPECIFIED`, nulla port, nem végrehajtható;
- gyermek Stage kind: `PROGRAM_BODY`;
- gyermekben újabb PROGRAM megengedett;
- CLASS és INTERFACE hitelesen tiltott Pythonban.

Az új runtime típus miatt új portkatalógus-verzió szükséges. A jelenlegi strict
loader verzióeltérésnél read-only módot ad, ezért a meglévő snapshotok verzióját
nem szabad vakon átírni. A v2→új verzió upgrade csak akkor történjen, ha a
migráció bizonyítja, hogy a meglévő START/STOP/IF szemantika változatlan, és az
új verzió kizárólag a zero-port PROGRAM típust adja hozzá. Régebbi vagy
ismeretlen katalógus maradjon explicit inkompatibilis.

## 4. Migrációs stratégia a jelenlegi sémából

### 4.1 Alapelv

A régi Stage-ek, instance-, edge-, route- és wire-ID-k, saved versionök és
snapshot-tartalmak változatlanok maradnak. A migráció új Program- és mount
azonosságot ad köréjük; nem építi újra és nem értelmezi át a graphot.

### 4.2 Javasolt tranzakciós backfill

Egy új, számozott migrációban:

1. `programs`, `program_stage_links`, `hall_program_mounts` létrehozása.
2. `stages.program_id` és `stage_kind` kezdetben nullable hozzáadása.
3. Minden meglévő Hall–Stage párhoz új Program létrehozása:
   - owner = Hall owner;
   - program name = meglévő Hall/Stage név alapján;
   - a meglévő Stage ID lesz a root Stage ID.
4. Stage `program_id` és `stage_kind=ROOT` backfill.
5. Hall→Program mount létrehozása.
6. Program root Stage FK kitöltése és integritásellenőrzés.
7. `stages.program_id` NOT NULL-ra állítása.
8. A régi `stages.hall_id` UNIQUE és FK eltávolítása; különösen az
   `ON DELETE CASCADE` megszüntetése.
9. A legacy `hall_id` mező nullable/deprecated lehet egy kompatibilitási
   periódusra, majd későbbi migrációban törölhető. Nem maradhat hiteles
   ownership-forrás.
10. Csak bizonyítottan v2-kompatibilis snapshotok explicit katalógus-upgrade-je.

A migráció végén ellenőrzendő:

- programonként pontosan egy root;
- minden Stage pontosan egy Programhoz tartozik;
- minden korábbi Hall mountol egy Programot;
- blokk/edge/route/step/wire darabszám és tartalomhash változatlan;
- Hall törlésének query planje nem ér Stage/Graph táblát.

### 4.3 Alkalmazási átállás

A séma és a runtime kód együtt váltson. Nem biztonságos olyan köztes deploy,
amelyben:

- az új séma mellett a régi `ensure_stage(hall)` új Hall-tulajdonú Stage-et
  próbál létrehozni; vagy
- az új Hall DESTROY mellett még él a Stage→Hall cascade.

Szükséges migrációs/integrációs kapu:

- másolat/elkülönített PostgreSQL;
- pre/post fingerprint;
- legacy Hall megnyitás;
- Program-SAVE;
- Hall DESTROY után Program fingerprint-változatlanság;
- rollback hibainjektálással.

## 5. Programszintű, atomi SAVE algoritmus

### 5.1 Python aggregate coordinator

Javasolt `ProgramWorkspace`:

- `program_id`, owner, root Stage ID;
- aktív és retained hierarchy linkek;
- betöltött és új StageWorkspace-ek indexe;
- `program_saved_version`;
- egy Program-szintű `RLock`.

A StageSnapshot és StageWorkspace marad a Stage tartalmának egyetlen hiteles
in-memory modellje. A ProgramService csak koordinál; nem duplikál graph vagy
Stage-domain motort.

### 5.2 Kötelező lock-sorrend

Minden Programon belüli mutáció ugyanazt a sorrendet használja:

1. `ProgramWorkspace.lock`;
2. érintett `StageWorkspace.lock` objektumok Stage UUID szerint növekvő,
   determinisztikus sorrendben;
3. repository/DB művelet.

Egyszerű és biztonságos első változatban a Program-lock a teljes Stage-mutáció
alatt tartható. Ez Programon belül sorosítja a szerkesztést, de külön Programok
egymással párhuzamosak maradnak. Később read/write lock optimalizálható, a
lock-sorrend változtatása nélkül.

Semmilyen kódút nem szerezhet előbb Stage-lockot, majd Program-lockot. A
jelenlegi StageService publikus mutációit ezért a ProgramService-en keresztül
kell vezetni vagy belső, már-lockolt primitive-ekre bontani.

### 5.3 SAVE prepare fázis – csak memória

Program-lock alatt:

1. owner és mount/program kapcsolat ellenőrzése;
2. teljes aktív hierarchy és minden új/retained link befagyasztása;
3. minden dirty vagy új Stage ID összegyűjtése;
4. Stage-lockok rendezett megszerzése;
5. snapshot, epoch, revision, stage saved version és új/persisted állapot
   immutable save planbe másolása;
6. teljes hierarchy ciklus-, programazonosság- és PROGRAM block-link validáció;
7. minden Stage snapshot storage-validációja;
8. ha nincs változás, idempotens no-op válasz.

Ebben a fázisban workspace mező nem változik, dirty flag nem törlődik.

### 5.4 Egyetlen repository tranzakció

Új `save_program_snapshot(plan)` repository API:

1. `programs` sor `SELECT ... FOR UPDATE`;
2. program-level `saved_version` CAS;
3. az összes már perzisztált érintett Stage saved-version ellenőrzése;
4. új Stage fejlécek beszúrása;
5. dirty Stage-ek teljes blokk/edge/route/wire snapshotjának írása a jelenlegi
   egy-Stage-es writer közös cursoros primitive-jével;
6. aktív és retained linkek szinkronizálása;
7. root és ciklusmentes hierarchy ismételt ellenőrzése;
8. minden mentett Stage saved versionjének pontosan +1 léptetése;
9. Program saved versionjének pontosan +1 léptetése;
10. commit és immutable `ProgramSaveReceipt` visszaadása.

Az azonos Program több processből érkező SAVE-jét a Program-sor lock + CAS
sorosítja. A Stage-CAS megmarad második integritási kapunak. Más Programok
Stage-sorai diszjunktak, ezért a rendezett Stage-lock mellett nincs
keresztprogramos deadlock.

Bármely constraint, CAS, serialization vagy storage hiba az egész tranzakciót
rollbackeli. Nincs részleges új child, link vagy Stage-snapshot.

### 5.5 Commit utáni memóriabesorolás

Csak a sikeres DB-commitot igazoló receipt után, még mindig a Program- és
Stage-lockok alatt:

- az új Stage-ek `persisted=true` állapotot kapnak;
- receipt szerinti Stage saved versionök frissülnek;
- mentett Stage-ek dirty flagje false lesz;
- Program saved version frissül;
- nem mentett, a plan után keletkezett változás nem lehetséges a tartott lockok
  miatt.

DB-hiba előtt vagy közben a Python workspaces érintetlenek maradnak.

Ritka DB-commit-utáni processhiba esetén a PostgreSQL a kanonikus nyertes. Ha a
memóriabeli receipt-alkalmazás exceptiont dob, az érintett ProgramWorkspace-et
`RELOAD_REQUIRED` állapotba kell tenni, nem szabad vakon újra-SAVE-elni. Process
restart után a teljes mentett Program tisztán visszatöltődik.

### 5.6 SAVE-tábla és PUBLISH előkészítés

A Hall SAVE gombja a Hall mountból feloldja a `program_id`-t, majd a
ProgramService SAVE-jét hívja. Nem a Hall és nem csak az active Stage mentődik.

A SAVE-tábla javasolt állapota:

- `MENTVE`, ha nincs dirty/új/hierarchy-változás a Programban;
- `NINCS MENTVE`, ha a Program bármely Stage-e vagy linkje dirty;
- opcionálisan később dirty Stage darabszám.

PUBLISH későbbi kapuja:

- Program aggregate clean;
- nincs új vagy mentetlen hierarchy link;
- minden aktív Stage a publish-szabályok szerinti aktuális validációval bír;
- retained/unreachable részfák publish-politikája külön döntés.

## 6. ProgramBlock–Child Stage kapcsolat és törlési szemantika

### 6.1 Létrehozás

- ProgramBlock placement stabil `instance_id`-t hoz létre a parent Stage
  snapshotban.
- Python ugyanazon ProgramWorkspace-ben idempotensen létrehoz egy új child
  StageWorkspace-et és ACTIVE linket.
- Az új child saját epoch/revision/dirty állapotú; üres új Stage is dirty/new.
- Navigálható anélkül, hogy DB-írás történne.
- A következő Program-SAVE ugyanabban a tranzakcióban menti a parent blokkot,
  child Stage-et és linket. Nincs külön parent SAVE előfeltétel.

### 6.2 Normál törlés

A ProgramBlock törlése továbbra is hiteles Python programszerkesztési művelet:

1. eltávolítja a blokkot a parent Stage snapshotból;
2. a jelenlegi Stage-delete szemantikához hasonlóan eltávolítja a blokkhoz
   tartozó parent-Stage GraphEdge/route kapcsolatokat;
3. az ACTIVE child linket in-memory `RETAINED` állapotúvá teszi;
4. a child Stage és teljes leszármazotti tartalma változatlan marad;
5. Program-SAVE ezt atomikusan perzisztálja.

Nincs most külön retained/orphan UI. Ez belső adatbiztonsági állapot, amely
megakadályozza a csendes kaszkádos adatvesztést és később lehetővé teszi a
CUT/PASTE vagy UNDO/REDO reaktiválását.

### 6.3 Fizikai blokkeltűnés

Stage-switch és authoritative világprojekció kizárólag Minecraft-blokkot
távolít el; nem küld Python delete parancsot. Játékos break esetén a meglévő
Fabric callback explicit Python blokk-delete API-t hív
([`Interactions.kt:102`](../src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt#L102)).
Így a fizikai projekció eltűnése és a programszerkesztési törlés továbbra is
elkülöníthető.

### 6.4 Még nyitott végleges lifecycle

Nem része az első implementációnak:

- retained subtree listázás/visszaállítás;
- tartós undo history;
- végleges garbage collection;
- Program végleges törlése;
- CUT/PASTE UI.

Addig retained Stage vagy link automatikusan nem törölhető. PUBLISH előtt el kell
dönteni, hogy retained részfák kizártak, figyelmeztetést adnak vagy explicit
cleanupot igényelnek.

## 7. Hangár projekciós kapcsolat és DESTROY elemzés

### 7.1 Mount

Hall létrehozás után egy külön mount-művelet választ Programot. Az első UX
automatikusan létrehozhat és mountolhat üres Programot, de ez kényelmi workflow,
nem ownership.

Mount feltételek:

- első verzióban a játékos a Hall és a Program tulajdonosa;
- Stage logical méret elfér a Hall projection terében;
- a mountolt Program root Stage-e létezik;
- Programváltás nem ment és nem módosít Stage snapshotot;
- Hall active Stage runtime rootra áll.

### 7.2 DESTROY

Új sorrend:

1. owner authorization;
2. Hall lifecycle `DELETING`;
3. Fabric világstruktúra lebontása;
4. `hall_program_mounts` sor törlése/cascade-je;
5. Hall controls és Hall sor törlése;
6. Program, Stage, block, edge, route és wire táblák érintetlenek.

Kötelező teszt: teljes Program fingerprint Hall DESTROY előtt és után azonos.

A jelenlegi útvonal változtatás nélkül **NO-GO**, mert a Hall törlése a Stage FK
miatt programadatot cascade-del törölne.

### 7.3 Ugyanaz a Program több Hallban

A séma ezt ne tiltsa. Az első implementation acceptance lehet egy Hallra
korlátozott, de az adatmodell ne használjon `UNIQUE(program_id)` mountot.

Két aktív mount ugyanazt a Python workspace-t szerkesztheti. A Stage
epoch/revision fencing a domaint védi, de a másik Hall világprojekciója stale
lehet egy módosítás után. Későbbi több-Hall reconciliation/notification külön
feladat; az első verzió dokumentálhatja, hogy egyidejű szerkesztés nem támogatott.

## 8. Fabric navigációs és fencing-következmények

### 8.1 Runtime modell

Hallonként:

- `mountedProgramId`;
- `rootStageId`;
- `activeStageId`;
- `switchGeneration`;
- projection origin/size;
- több Stage cache, Stage ID szerint.

A GraphProjectionStore már Stage ID szerint külön slotot tart, ezért a nem
aktív Stage graph lifecycle-ja megőrizhető. Stage-váltáskor a régi slotot nem
szabad törölni. Kotlinban továbbra sincs második Stage-domain: csak Python DTO
cache és világprojekció.

### 8.2 Belépés és `/ckback`

- ProgramBlock jobb kattintás → kliens UI → owner-checkelt child ensure/get;
- target linknek ACTIVE állapotúnak és a mountolt Programhoz tartozónak kell lennie;
- `/ckback` a hiteles link parent Stage-ére vált;
- rooton `/ckback` no-op/érthető üzenet;
- nincs mélységlimit, de minden belépésnél ciklus- és Program-azonosság ellenőrzés;
- navigáció nem érinti a dirty vagy saved version mezőket.

### 8.3 Kétfázisú switch

**Prepare, világírás nélkül**

1. Hall ID, mounted Program ID, source active Stage és switch generation token;
2. target authoritative Stage GET;
3. Program/Stage/link/owner/world projection identity check;
4. target Graph GET;
5. Stage és Graph epoch/revision egyezés;
6. programblokk- és CK Wire projection preflight.

**Commit a Minecraft server threaden**

7. switch token ismételt ellenőrzése;
8. korábbi programblokk- és wire-vetítés cseréje;
9. target `activeStageId` publikálása;
10. Program-szintű SAVE státusz renderelése.

A Stage lifecycle generation önmagában nem elég: egy késői válasz a saját
Stage-éhez helyes lehet, de közben másik Stage vált aktívvá. A Hall-szintű
`switchGeneration` tiltja az ilyen világírást.

### 8.4 Dirty workspace-ek és restart

- Inaktív dirty Stage Python workspace-e nem kerül evictálásra.
- Fabric cache eldobása nem dobhatja el a Python workspace-et.
- kliens reconnectkor a futó Fabric megtarthatja a Hall active Stage-ét, de
  authoritative Stage/Graph reload szükséges;
- Fabric restartkor root fallback elfogadott;
- Python restartkor mentetlen workspaces elvesznek; a Fabric target GET alapján
  a mentett Programhoz reconciliál, és ezt nem jelenti hamisan mentett
  process-lokális tartalomként.

### 8.5 SAVE gomb

A jelenlegi `/halls/save-button` Hallból egyetlen Stage-et biztosít és ment
([`ck_webhook.py:190`](../python-service/src/ck_webhook.py#L190)). Ezt
Program-SAVE útvonalra kell cserélni:

- Hall control hitelesen feloldja a mountolt Programot;
- owner ellenőrzés;
- `ProgramService.save_program()`;
- response: program saved version, mentett Stage receipt-ek, aggregate dirty;
- Fabric a SAVE táblát aggregate program státuszból frissíti.

## 9. Módosított A1–A8 implementációs sorrend és tesztkapuk

### A1 – Domain contract és számozott séma-migráció

- Program/Stage/link/mount DDL;
- legacy backfill;
- Stage–Hall cascade megszüntetése;
- pre/post fingerprint és rollback statikus/unit tesztek.

**Kapu:** régi graph tartalom bitazonos; Hall DESTROY már nem érhet Programot.

### A2 – Repository Program aggregate

- Program/root/stage/link/mount load API;
- közös cursoros Stage snapshot writer;
- `save_program_snapshot()` egy tranzakcióban;
- Program + Stage CAS;
- PostgreSQL hibainjektálás, rollback, konkurens save teszt.

**Kapu:** több dirty + új Stage vagy mind commitol, vagy egyik sem.

### A3 – Python ProgramWorkspace és hierarchy domain

- Program-lock és kötelező lock-sorrend;
- child ensure, korlátlan acyclic nesting;
- ACTIVE/RETAINED link;
- Program-SAVE prepare/receipt commit;
- Stage-kind allow policy.

**Kapu:** DB-hiba után minden workspace byte-for-byte/logikailag változatlan.

### A4 – Python HTTP/DTO

- Program create/list/get/status/save;
- Stage domain GET;
- child ensure;
- Hall mount/program switch;
- structured owner/stale/cycle/fit hibák;
- Graph DTO fizikai originjének projection contextbe helyezése.

**Kapu:** API-n nincs Hall-tulajdonú Stage vagy implicit SAVE.

### A5 – PROGRAM block és katalógus

- számozott block-type adatbővítés;
- portkatalógus új verziója, zero-port PROGRAM;
- Fabric blokk/item/model/lang/creative tab;
- PROGRAM_BODY allow policy; CLASS/INTERFACE tiltás.

**Kapu:** meglévő START/STOP/IF graph regresszió PASS; kompatibilis régi
snapshotok explicit upgrade-je bizonyított.

### A6 – Fabric mount/cache/navigation

- Hall projection context és Stage cache;
- ProgramBlock UI `Belépés`;
- owner-only switch;
- `/ckback`;
- switch generation és késői válasz tesztek.

**Kapu:** navigáció nem hív SAVE-et és nem módosít Python revisiont.

### A7 – Világprojekció, aggregate SAVE és restart

- kétfázisú Stage switch;
- programblokk + wire közös projection commit;
- Program-SAVE gomb és aggregate dirty tábla;
- reconnect, Python restart, Fabric root fallback;
- Hall DESTROY end-to-end adatmegőrzés.

**Kapu:** partial GET/projection hiba nem okoz destruktív stale rendert.

### A8 – Minecraft acceptance

1. ProgramBlock → child → nested child navigáció SAVE nélkül.
2. Több Stage külön dirty állapota.
3. Egy SAVE minden dirty és új Stage-et egy programverzióban ment.
4. Reconnect, Python restart, Fabric restart/root fallback.
5. ProgramBlock delete retained child adatvesztés nélkül.
6. Hall DESTROY, majd ugyanazon Program megnyitása másik Hallban.
7. SAVE-hibainjektálásnál minden Stage dirty marad és DB rollback történik.

## 10. Megmaradó valódi projektgazdai döntési pontok

1. **Új Hall első UX-e:** automatikusan hozzon létre és mountoljon üres
   Programot, vagy kötelező legyen Program választása/létrehozása?
2. **Retained subtree és PUBLISH:** publish figyelmen kívül hagyja, blokkolja vagy
   explicit cleanupot kér a retained részfákra?
3. **Végleges Program delete:** milyen megerősítés, retention és audit szükséges?
4. **Program név/azonosítás:** név egyedi-e tulajdonoson belül, kell-e külön
   rövid programkód?
5. **Egyidejű több-Hall szerkesztés:** első verzióban tiltott vagy csak nem
   támogatott legyen? Milyen UX jelezze a másik mount stale projekcióját?
6. **Stage logical size:** minden child örökli a Program root méretét, vagy a
   Stage kind határozza meg? Az első acceptance-hez az öröklés a legegyszerűbb.
7. **Program-level saved version kezdőértéke a migrált adatoknál:** 0 vagy 1;
   szemantikailag mindkettő lehetséges, de API-szerződésként rögzíteni kell.
8. **DB-szintű ciklusvédelem:** elegendő-e Program-lock + repository recursive
   validation, vagy kötelező DEFERRABLE constraint trigger is?
9. **Commit utáni memóriahiba UX:** automatikus Program reload vagy explicit
   „újracsatlakozás szükséges” állapot legyen?

Ezek nem kérdőjelezik meg a domain-szétválasztást vagy az atomi Program-SAVE-et,
de A1/A2 konkrét szerződését befolyásolják.

## 11. GO / CONDITIONAL GO / NO-GO értékelés

### Értékelés: **CONDITIONAL GO**

Az architektúra megvalósítható a meglévő immutable StageSnapshot,
StageWorkspace, graph repository és Fabric projection mechanizmusokra építve.
Nem szükséges második Kotlin Stage-domain vagy a graph újraírása.

**GO adható az A1 implementációjára**, ha a projektgazda elfogadja:

- a `programs` + `stages.program_id` + `program_stage_links` +
  `hall_program_mounts` modellt;
- az ACTIVE→RETAINED, adatvesztésmentes ProgramBlock-delete minimumot;
- a Stage-domain és Hall-projection koordináták szétválasztását;
- a Program-szintű RLock → rendezett Stage-lock sorrendet;
- a program row CAS-ra épülő egytranzakciós SAVE-et;
- a migrációs backfill és Hall-cascade eltávolítás kötelező kapuit.

**NO-GO** bármely olyan implementációra, amely:

- Stage-et továbbra is Hall-tulajdonként kezeli;
- Hall DESTROY során Stage/Graph sort cascade-del töröl;
- egy Program-SAVE-et Stage-enként külön tranzakciókra bont;
- parentet automatikusan ment navigációkor;
- ProgramBlock delete során child Stage-et automatikusan töröl;
- abszolút Minecraft origint perzisztens Stage-domain mezőként használ;
- vagy aktív Stage-váltást Python/Kotlin graph-domain duplikációval old meg.

Az A1-R tehát nem implementációs GO, hanem szakmailag alkalmas, felülvizsgálható
terv a következő döntési kapuhoz.
