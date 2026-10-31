# CK-06/A – ProgramBlock és Stage-navigáció: read-only felderítés

- Dátum: 2026-10-30
- Mód: **READ-ONLY felderítés**
- Eredmény: **CONDITIONAL GO**
- Nem történt build, deploy, restart, adatbázis-írás vagy alkalmazáskód-módosítás.

## 1. Vezetői összefoglaló

A kívánt ProgramBlock → gyermek Stage → visszalépés munkafolyamat a meglévő
StageSnapshot-, workspace-, SAVE-, Graph- és Fabric-projekciós elemekre építve
megvalósítható. A meglévő rendszer fontos alapjai újrahasznosíthatók:

- stabil Stage- és blokk-UUID;
- Stage-enként elkülönített, dirty in-memory Python workspace;
- epoch/revision fencing és Stage-enkénti lock;
- teljes GraphEdge/EdgeRoute/WireCell SAVE és PostgreSQL-visszatöltés;
- authoritative Stage/Graph reconciliation;
- programblokk- és CK Wire-világprojekció;
- meglévő jobb kattintásos Fabric esemény és kliensoldali képernyő/payload lánc.

A funkció azonban nem pusztán egy új Minecraft-blokk hozzáadása. Jelenleg a
PostgreSQL **pontosan egy Stage-et enged hangáronként**, a Fabric `Hall` modellje
is pontosan egy Stage-et tart, és nincs aktív Stage, szülő–gyermek kapcsolat,
navigációs stack vagy közvetlen authoritative Stage GET. Ezekhez additív
migráció, Python hierarchy/application API és egy fail-safe Fabric
Stage-váltási koordinátor szükséges.

A legfontosabb tervezési korlát: a gyermek Stage létrehozása nem válhat a dirty
szülő Stage csendes részmentésévé. Az első biztonságos változatban a gyermek
workspace már a még nem mentett ProgramBlockból is létrejöhet és navigálható,
de tartós gyermek-SAVE csak azután engedhető, hogy a szülő ProgramBlockot egy
explicit szülő-SAVE perzisztálta. Stage-váltás önmagában nem ment.

## 2. Jelenlegi képességek

### 2.1 Stage-azonosság, Hall és tulajdonos

- A `stages.stage_id` UUID elsődleges kulcs
  ([`004_program_stages.sql:21`](../python-service/src/hangar/migrations/004_program_stages.sql#L21)).
- A Stage a Hallon keresztül kap tulajdonost és világot; a repository betöltés
  közben a `stages` és `halls` táblákat összekapcsolja
  ([`repository.py:379`](../python-service/src/hangar/repository.py#L379)).
- Minden Stage-módosító Python művelet összeveti a payload játékos-UUID-ját a
  Hall tulajdonosával ([`stage.py:238`](../python-service/src/hangar/stage.py#L238)).
- A Stage koordinátái Hall-relatív originből és méretből állnak; a Python
  szerveroldalon ellenőrzi, hogy egy blokk a Stage térfogatán belül van-e
  ([`stage.py:243`](../python-service/src/hangar/stage.py#L243)).

**Korlát:** a `stages.hall_id` jelenleg `UNIQUE`, tehát adatbázis-szinten is
egy Stage tartozhat egy Hallhoz
([`004_program_stages.sql:22`](../python-service/src/hangar/migrations/004_program_stages.sql#L22)).
A `ensure_stage()` ugyanezt az egyetlen Hall-Stage-et keresi vagy hozza létre
([`repository.py:353`](../python-service/src/hangar/repository.py#L353)). Nincs
`parent_stage_id`, gyermek Stage, Stage-típus vagy ProgramBlock–Stage link.

### 2.2 Több egyidejű Python munkapéldány

A `StageService` már most Stage ID szerint indexelt workspace-szótárat tart
([`stage.py:143`](../python-service/src/hangar/stage.py#L143)). A `_workspace()`
csak a még nem betöltött Stage-et olvassa fel PostgreSQL-ből, utána ugyanazt az
objektumot adja vissza ([`stage.py:157`](../python-service/src/hangar/stage.py#L157)).
Ezért egy Python folyamaton belül több Stage dirty munkapéldánya egyszerre
megőrizhető. A Stage-váltáshoz nem szükséges az egyik workspace tartalmát a
másikba másolni vagy automatikusan menteni.

Minden workspace saját:

- immutable `StageSnapshot` referenciát;
- `dirty` és `saved_version` állapotot;
- process-lokális `workspace_epoch` UUID-t;
- `RLock`-ot tart
  ([`stage.py:40`](../python-service/src/hangar/stage.py#L40)).

Nincs jelenlegi Stage-váltási művelet, ezért váltáskor ma sem mentés, sem
eldobás nem történik. A képesség domain-alapja megvan, a navigációs alkalmazási
réteg hiányzik.

### 2.3 SAVE, REBUILD és restart

- `StageService.save()` Stage-lock alatt, owner- és opcionális epoch/revision
  ellenőrzéssel ment ([`stage.py:321`](../python-service/src/hangar/stage.py#L321)).
- A teljes snapshot mentése PostgreSQL-ben CAS-szal és egy tranzakcióban cseréli
  a blokkokat, éleket, route-okat és wire cellákat
  ([`repository.py:469`](../python-service/src/hangar/repository.py#L469)).
- A SAVE nem lépteti a working revisiont; siker után növeli a saved versiont és
  CLEAN-re állítja a workspace-t.
- A `REBUILD` dirty workspace-nél explicit `confirm_discard` nélkül leáll, majd
  siker esetén új epochot és CLEAN, DB-ből betöltött snapshotot ad
  ([`stage.py:346`](../python-service/src/hangar/stage.py#L346)).
- Python-restart után az összes process-lokális workspace megszűnik. Következő
  Stage-eléréskor a mentett PostgreSQL snapshot töltődik be, új epoch és
  revision 0 mellett. A mentetlen tartalom elveszik; a mentett tartalom teljes
  gráffal visszaáll.

Ez a szemantika gyermek Stage-re is újrahasznosítható. Stage-váltás közben a
workspace marad; Python-restart viszont továbbra is csak az explicit SAVE-ig
vállal tartósságot.

### 2.4 Jelenlegi HTTP API

Működő belépési pontok:

- blokk place/update/delete;
- Stage SAVE és REBUILD;
- Graph GET;
- Wire placement/layout és bind
  ([`ck_webhook.py:128`](../python-service/src/ck_webhook.py#L128)).

A Hall-listázás az `enrich()` hívásban mindig a Hall egyetlen biztosított
Stage-ét csatolja a válaszhoz. Nincs:

- közvetlen `GET /api/v1/stages/{stage_id}` Stage-view;
- gyermek Stage létrehozási/ensure API;
- szülő vagy breadcrumb DTO;
- Stage-select/leave API.

Az aktív világprojekció kiválasztása nem Python-domain mutáció, ezért külön
„activate” adatbázis-művelet nem szükséges. A Pythonnak a hierarchiát és a
hiteles Stage-view-t kell szolgáltatnia; a világban éppen megjelenített Stage a
Fabric runtime állapota lehet.

### 2.5 Fabric registry és világprojekció

A Fabric `Hall` jelenleg egy nullable `stage` mezőt tart
([`HallRegistry.kt:21`](../src/main/kotlin/me/buciqua/blockommander/runtime/HallRegistry.kt#L21)).
Nincs aktív Stage ID, Stage-cache vagy játékos/hangár navigációs stack.

Az interaction routing térbeli:

- `stageAt()` a Hall egy Stage-ének térfogatából választ;
- `stageBlockAt()` ugyanebből keresi a blokkot
  ([`HallRegistry.kt:170`](../src/main/kotlin/me/buciqua/blockommander/runtime/HallRegistry.kt#L170)).

A `WorldRenderer.renderStageBlocks()` a Stage teljes térfogatában eltávolítja a
felismert CK programblokkokat, majd kirendereli a hiteles Stage blokkjait
([`WorldRenderer.kt:58`](../src/main/kotlin/me/buciqua/blockommander/runtime/WorldRenderer.kt#L58)).
A Hall fala, ajtaja és vezérlői külön renderelhetők, ezért Stage-váltáskor a
fizikai csarnokszerkezet megtartható.

A CK Wire külön authoritative projectoron megy keresztül. A projector:

- ellenőrzi a Stage ID/epoch/revision egyezést;
- minden világírás előtt teljes preflightot végez;
- a cél snapshotban nem szereplő, Stage-térfogaton belüli CK Wire-okat eltávolítja
  ([`WireWorldProjection.kt:51`](../src/main/kotlin/me/buciqua/blockommander/runtime/wire/WireWorldProjection.kt#L51)).

Ez alkalmas az előző Stage wire-jainak eltávolítására is, ha a cél Stage
snapshotja már hitelesen és sikeresen beérkezett. Sikertelen cél-Graph GET után
nem szabad meghívni destruktív váltásra.

### 2.6 Reconnect és fencing

A T8 óta JOIN után:

1. lefut a world UUID-alapú Hall restore;
2. authoritative current-world Hall/Stage GET történik;
3. Stage-reload;
4. programblokk-projekció;
5. Graph/Wire reconciliation;
6. SAVE-tábla frissítés
   ([`Interactions.kt:163`](../src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt#L163)).

A `GraphProjectionStore` Stage ID-nként epochot, revisiont, lifecycle- és
request-generationt tart. Késői lifecycle-válasz `IgnoredStaleRequest`, hibás
azonosság vagy revision konfliktus lesz
([`GraphProjectionStore.kt:18`](../src/main/kotlin/me/buciqua/blockommander/runtime/graph/GraphProjectionStore.kt#L18)).
A `StageReconnectReconciler` a SAVE-felirat előtt ismét ellenőrzi, hogy nem
változott-e meg a Stage lifecycle
([`StageReconnectReconciler.kt:45`](../src/main/kotlin/me/buciqua/blockommander/runtime/StageReconnectReconciler.kt#L45)).

Ezek a mechanizmusok újrahasznosíthatók, de a Stage-váltásnak külön
switch-generation fence kell. Egy helyes, de már nem aktív Stage-hez tartozó
késői válasz ugyanis Stage-szinten nem stale, a közös fizikai világprojekció
szempontjából mégis az.

### 2.7 Jobb kattintás, UI és blokkregisztráció

- A `UseBlockCallback` már megkülönbözteti a kontrollt, CK Wire-t, hangárkardot,
  Stage-blokkot és lerakandó CK blokkot
  ([`Interactions.kt:62`](../src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt#L62)).
- Stage-blokk jobb kattintása már szerverről kliensre küldött `OpenFormPayload`
  képernyőt nyit ([`Interactions.kt:90`](../src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt#L90)).
- A kliens `CkFormScreen` jelenleg két szövegmezőt, Mentés és Mégse gombot tud
  ([`CkFormScreen.kt:12`](../src/client/kotlin/me/buciqua/blockommander/client/screen/CkFormScreen.kt#L12)).
- A `ModContent.programBlock()` ugyanazzal a helperrel regisztrál blokkot és
  BlockItemet; a creative tab tartalma egy helyen bővíthető
  ([`ModContent.kt:18`](../src/main/kotlin/me/buciqua/blockommander/ModContent.kt#L18)).

ProgramBlock jelenleg sem Fabric-regisztrációként, sem PostgreSQL
`program_block_types` rekordként nem létezik. A runtime típuslista bővítésekor a
verziózott Python portkatalógust is bővíteni kell. A port nélküli első változat
`SPECIFIED` státuszú, üres porttuple-lel szerepelhet; ellenkező esetben a runtime
katalógus-ellenőrzés a teljes graph workspace-t inkompatibilissé tenné.

## 3. Hiányzó láncszemek

| Terület | Hiány | Érintett hely |
|---|---|---|
| PostgreSQL | Több Stage/Hall, Stage-kind, parent–child link és link lifecycle | `004_program_stages.sql`, új számozott migráció |
| Repository | Root-Stage ensure helyett root/child lekérdezés; hierarchia mentése és betöltése | `HallRepository.ensure_stage()`, `load_*`, `save_full_stage_snapshot()` |
| Python workspace | Workspace-only gyermek létrehozás, idempotens child ID, parent/breadcrumb view | `StageService` |
| Python API | Authoritative Stage GET, child ensure, navigation DTO | `ck_webhook.py` |
| ProgramBlock domain | Új runtime block type; port nélküli katalógus-bejegyzés és verziólépés | `005_ck_block_types.sql`, `port_catalog.py` |
| Fabric content | ProgramBlock blokk/item/model/lang/creative tab | `ModContent.kt`, resources |
| Fabric registry | Stage-cache + Hall-szintű active Stage; nem aktív Stage-ek graph slotjainak megőrzése | `HallRegistry.kt` |
| Navigation | Belépés/visszalépés, switch token és owner-check | `Interactions.kt`, új coordinator |
| Projection | Target Stage+Graph prepare/preflight, majd közös commit; késői válasz tiltása | `WorldRenderer`, Wire projector, új coordinator |
| SAVE | A fizikai SAVE gomb az aktív Stage-et mentse, ne mindig a Hall root Stage-ét | `Interactions.handleControl()`, `/halls/save-button` |
| UI | ProgramBlock szerkesztő „Belépés” akcióval; visszalépési affordance | kliens screen/payload |
| Reconnect | Aktív Stage helyreállítási vagy root-fallback szabály | JOIN reconciliation |

## 4. Javasolt minimális architektúra

### 4.1 PostgreSQL: hierarchia, nem aktív projekció

Javasolt új, additív migráció:

1. a `stages.hall_id` teljes UNIQUE constraint helyett részleges egyediség csak
   a root Stage-re;
2. `stage_kind` (`ROOT`, `PROGRAM`);
3. nullable `parent_stage_id`, önhivatkozó FK-val és `ON DELETE RESTRICT`
   viselkedéssel;
4. nullable `owner_block_instance_id`, egyedi
   `(parent_stage_id, owner_block_instance_id)` indexszel;
5. link lifecycle (`ACTIVE`, `DETACHED`) vagy ezzel egyenértékű recoverable
   állapot;
6. minden gyermek Stage ugyanahhoz a Hallhoz, ownerhez, világhoz és ugyanahhoz
   a fizikai Stage-origin/mérethez tartozik.

Nem javasolt FK-val közvetlenül a `stage_blocks` sorra CASCADE-del kötni a
gyermeket. A repository SAVE jelenleg a Stage graph-tábláit és blokkjait
törli, majd újra beszúrja ([`repository.py:498`](../python-service/src/hangar/repository.py#L498));
egy ilyen cascade minden szülő-SAVE során törölhetné a gyermek Stage-et.

A Stage aktív világprojekciója ne kerüljön PostgreSQL-be az első változatban.
Ez Fabric runtime/UI állapot, nem programdomain-tartalom.

### 4.2 ProgramBlock–gyermek kapcsolat

Javasolt invariánsok:

- minden ProgramBlock stabil `instance_id`-jához legfeljebb egy gyermek Stage;
- a gyermek Stage ID determinisztikusan vagy idempotens request ID-val képződik;
- az unsaved ProgramBlock gyermek workspace-e process-lokálisan létrejöhet;
- navigáció nem végez SAVE-et;
- a szülő első explicit SAVE-je ugyanabban a PostgreSQL-tranzakcióban menti a
  ProgramBlockot és biztosítja a gyermek Stage fejlécét/linkjét;
- gyermek-SAVE addig fail-closed, amíg a szülő linkje nincs perzisztálva;
- ProgramBlock törlése nem törli automatikusan a gyermeket: az explicit parent
  SAVE után a gyermek `DETACHED`, visszaállítható állapotba kerül;
- végleges child delete külön, megerősített későbbi művelet legyen.

Ehhez a `StageBlockSnapshot` read modelnek ismernie kell a nullable
`child_stage_id` értéket, vagy a Stage-view-nak külön block→child linkmapet kell
adnia. A kapcsolat hiteles gazdája Python/PostgreSQL; Fabric csak DTO-t cache-el.

### 4.3 Python felelőssége

- Stage-hierarchia és owner jogosultság;
- workspace-only gyermek ensure;
- parent/child/depth DTO;
- Stage-kind szerinti blokkengedélyezés;
- minden Stage saját lockja, epochja, revisionje, dirty állapota;
- parent-link perzisztálás és child SAVE előfeltétel;
- meglévő teljes snapshot SAVE/LOAD újrahasznosítása;
- nincs automatikus SAVE, GraphEdge-törlés, BROKEN-re írás vagy DB-javítás.

Minimális új API-k:

- `GET /api/v1/stages/{stage_id}?player_uuid=...` – authoritative Stage-view és
  navigation metadata;
- `POST /api/v1/stages/{parent_stage_id}/blocks/{instance_id}/child-stage` –
  owner-ellenőrzött, idempotens workspace ensure, DB-írás nélkül, amíg a parent
  nincs explicit mentve;
- a meglévő `POST /stages/{stage_id}/save` használható az aktív Stage mentésére;
- opcionális read-only breadcrumb/list endpoint csak akkor, ha a Stage-view nem
  hordoz elegendő parent metadata-t.

Külön Python „select” vagy „leave” mutáció nem szükséges. A kiválasztás a
Fabric világprojekciójának állapota.

### 4.4 Fabric felelőssége

Hallonként egyetlen globálisan látható aktív Stage szükséges. Per-player aktív
Stage ugyanabban a fizikai térben nem lehetséges: két játékos nem láthat két
eltérő blokkvilágot ugyanazon szerverkoordinátákon. Az első verzió ezért legyen
owner-only, Hall-szintű navigációval.

A `HallRegistry` javasolt szerkezete:

- Hall metaadat és controls;
- `stagesById` cache több Stage-hez;
- `activeStageId` Hallonként;
- `navigationGeneration` Hallonként;
- opcionális owner navigation stack; a hiteles visszalépési cél azonban a
  Python DTO `parent_stage_id` mezője legyen;
- GraphProjectionStore slotonként továbbra is Stage ID alapján működjön;
- Stage-váltáskor a nem aktív Stage slotját ne törölje.

### 4.5 Biztonságos Stage-váltási sorrend

Az existing reconnect sorrend önmagában nem elég, mert target Graph-hiba után
nem szabad félig átváltott világot hagyni. Javasolt kétfázisú coordinator:

**Prepare – nincs világírás**

1. forrás Hall/active Stage/navigation generation rögzítése;
2. authoritative target Stage GET;
3. Hall-, owner-, parent-child-, world-, Stage ID-, epoch- és revision-check;
4. authenticated target Graph GET;
5. Stage és Graph azonos lifecycle-jának ellenőrzése;
6. programblokk- és Wire-projekció preflightja.

**Commit – Minecraft server thread**

7. navigation generation és forrás aktív Stage újbóli ellenőrzése;
8. régi projekció eltávolítása és target programblokkok kirajzolása;
9. target CK Wire snapshot alkalmazása;
10. `activeStageId` publikálása;
11. SAVE-tábla frissítése kizárólag az új aktív lifecycle szinkronizált dirty
    állapotából;
12. hiba esetén diagnosztika és a forrás Stage változatlanul aktív marad.

Mivel Minecraft világírás nem tranzakciós, a legfontosabb védelem a teljes
prepare/preflight és a commit előtti generation fence. Részleges write failure
esetén explicit reconciliation szükséges, nem Python-domain mutáció.

### 4.6 SAVE-tábla

A jelenlegi SAVE gomb a Hall koordinátájából a Hall egyetlen Stage-ét biztosítja
és menti ([`ck_webhook.py:190`](../python-service/src/ck_webhook.py#L190)).
Navigáció után ez hibás lenne.

Javaslat:

- Fabric a Hall aktív Stage ID-ját, epochját és revisionjét küldi;
- Python owner-, Hall–Stage- és fence-check után a meglévő `StageService.save()`
  útvonalat használja;
- a felirat mindig az aktív Stage állapotát mutatja;
- visszalépéskor azonnal a szülő dirty állapotára vált;
- gyermek dirty állapota a Python workspace-ben megmarad, és újrabelépéskor
  ismét `NINCS MENTVE` lesz.

### 4.7 Stage–Block minimális szabály

A szerveroldali ellenőrzési pont már létezik: `StageService.place()` a blokk
típuskatalógust és a cél Stage-et ugyanazon lock alatt ismeri
([`stage.py:255`](../python-service/src/hangar/stage.py#L255)). Ide illeszthető
egy tiszta `allowed(stage_kind, type_key)` policy.

Első minimális szabály:

- `PROGRAM` gyermek Stage: `START`, `STOP`, `IF`, a már engedélyezett
  végrehajtási blokkok és `PROGRAM`;
- `CLASS` és `INTERFACE`: tiltott;
- ismeretlen típus továbbra is tiltott;
- Fabric csak UX-előellenőrzést végezhet, a Python-döntés hiteles.

A teljes Stage–Block mátrix nem szükséges ehhez a mérföldkőhöz. A nested
ProgramBlock támogatásához a `PROGRAM` típust engedni kell gyermek Stage-ben is.
Érdemes maximális mélységet meghatározni a véletlen korlátlan nesting ellen.

## 5. Kockázatok és védelmek

| Kockázat | Következmény | Kötelező védelem |
|---|---|---|
| Váltás implicit SAVE-et végez | Dirty szemantika megsérül | Navigation API és coordinator soha ne hívjon SAVE-et |
| Child SAVE a dirty parent előtt | Árva vagy részlegesen perzisztált link | Parent explicit első SAVE előfeltétel vagy külön jóváhagyott kompozit tranzakció |
| Késői target válasz | Inaktív Stage felülírja a világot | Hall-szintű navigation generation + Stage lifecycle fence |
| Target Graph GET hiba | Régi blokkok törlődnek, régi wire marad | Prepare/preflight után kezdődhet csak világírás |
| Két Stage wire-jai keverednek | Hamis fizikai topológia | Target snapshot teljes apply-ja ugyanabban a switch commitban |
| Per-player aktív Stage | Játékosok ugyanazon koordinátán eltérő világot várnak | Hall-globális aktív Stage, owner-only első verzió |
| Python restart dirty child mellett | Mentetlen child tartalom elveszik | Egyértelmű UX; csak SAVE garantál restart-tartósságot |
| Fabric restart aktív child mellett | Active projection választása elveszik | Döntött root fallback vagy perzisztált UI preference; domain nem módosul |
| ProgramBlock törlése | Gyermekadat elveszhet | DETACHED lifecycle, nincs automatikus CASCADE |
| Hall törlése | Teljes Stage-fa törlődik | Már most explicit owner destroy; UI-ban jelezni kell a teljes fa törlését |
| Workspace-cache korlátlanul nő | Memóriahasználat | Első verzióban mérés; később csak clean idle eviction, dirty soha automatikusan |
| Új PROGRAM típus nincs portkatalógusban | Graph workspace read-only inkompatibilis | Új katalógusverzió, PROGRAM = SPECIFIED/0 port |
| Parent/child kereszt-lock | Deadlock | Meghatározott lock-sorrend vagy hierarchy-level coordinator lock |

## 6. Implementációs szeletek

### A1 – Hierarchia-adatmodell és migráció

- új számozott, additív migráció;
- root/PROGRAM Stage-kind;
- több Stage/Hall, egyetlen root partial unique index;
- parent/owner-block/link lifecycle;
- repository round-trip és migrációs tesztek;
- meglévő Stage-ek backfillje ROOT-ként.

**Kapu:** nincs Fabric- vagy világmódosítás.

### A2 – Python ProgramBlock és workspace-only child ensure

- PROGRAM blokk-típus;
- portkatalógus-verziólépés, zero-port PROGRAM;
- deterministic/idempotens child ID;
- child workspace, parent/depth metadata;
- Stage-kind allow policy;
- owner, nesting és idempotencia unit tesztek.

### A3 – Hierarchia SAVE/LOAD lifecycle

- parent SAVE + child header/link egy tranzakcióban;
- child SAVE parent-link előfeltétel;
- nested reload;
- ProgramBlock delete → DETACHED, nem delete;
- CAS/rollback/restart tesztek valódi PostgreSQL-en.

### A4 – Read-only Stage/navigation HTTP API

- authoritative Stage GET;
- child ensure endpoint;
- navigation DTO;
- strukturált stale/owner/not-persisted-parent hibák;
- nincs activate/leave DB-mutáció.

### A5 – Fabric ProgramBlock és minimális UI

- blokk/item/resource/creative tab;
- meglévő right-click dispatch specializálása;
- ProgramBlock screen: metaadat + `Belépés`;
- első visszalépési affordance: külön `Vissza` gomb/parancs a projektgazdai
  döntés szerint;
- nincs port és végrehajtási szemantika.

### A6 – Fabric Stage-cache és navigációs coordinator

- több Stage cache-elése Hallonként;
- Hall-globális active Stage;
- navigation generation fencing;
- target Stage+Graph prepare/preflight;
- programblokk → wire → SAVE commit;
- late response és partial failure Kotlin regressziók.

### A7 – SAVE, reconnect és restart integráció

- SAVE gomb aktív Stage-re irányítása;
- dirty child → parent → child állapotváltás;
- client reconnect, Python restart és Fabric restart policy;
- authoritative epochváltás minden cache-elt/aktív Stage-re;
- nincs domainmódosítás reconciliation miatt.

### A8 – Minecraft acceptance

- egy root és egy child Stage;
- dirty oda-vissza navigáció;
- parent és child explicit SAVE;
- reconnect;
- csak Python restart;
- teljes Fabric restart;
- nested ProgramBlock legalább két szinten;
- child detach viselkedés külön destructive művelet nélkül.

## 7. Projektgazdai döntést igénylő kérdések

1. **Aktív Stage scope:** elfogadott-e, hogy egy Hallban egyetlen, minden játékos
   számára közös aktív Stage van? Ez a közös Minecraft-világ miatt ajánlott.
2. **Első child SAVE:** elfogadott-e a „szülőt előbb explicit mentsd” szabály,
   vagy szükséges egy külön, kifejezetten jóváhagyott parent+child kompozit SAVE?
3. **ProgramBlock törlés:** DETACHED, visszaállítható child legyen-e az alapértelmezés?
   Automatikus törlés nem javasolt.
4. **Visszalépési UX:** `/ckback`, állandó Hall-control vagy kliens UI gomb legyen
   az első acceptance-ben?
5. **Fabric restart utáni aktív Stage:** mindig root legyen, vagy perzisztáljuk az
   utolsó UI-választást? A root fallback egyszerűbb és fail-safe.
6. **Nesting limit:** legyen-e például 16 vagy 32 szintű hard limit?
7. **Elnevezés:** a domain type key `PROGRAM`, a játékbeli név `ProgramBlock`
   legyen-e?
8. **Hall destroy:** elegendő-e a meglévő owner gomb, ha ez a teljes Stage-fát
   véglegesen törli, vagy szükséges külön megerősítés?

## 8. Első Minecraft-acceptance forgatókönyv

Előfeltétel: friss migráció, Python/Fabric/kliens verzióegyezés, új vagy ismert
clean Hall.

1. A root Stage CLEAN, a SAVE tábla `MENTVE`.
2. A tulajdonos lerak egy ProgramBlockot. A root Stage dirty, `NINCS MENTVE`.
3. Jobb kattintás → ProgramBlock képernyő → `Belépés`.
4. A világprojekció child Stage-re vált; a Hall szerkezete változatlan.
5. A childban START, STOP és CK Wire épül; `/ckbind` után a Wire zöld.
6. Visszalépés a root Stage-re **SAVE nélkül**. A ProgramBlock megmarad, a root
   továbbra is dirty.
7. Újrabelépés: ugyanaz a child epoch/workspace/revision és ugyanaz a mentetlen
   tartalom jelenik meg.
8. Vissza a roothoz, explicit root SAVE; a ProgramBlock–child link tartóssá válik.
9. Újra child, explicit child SAVE; a child CLEAN és `MENTVE`.
10. Minecraft reconnect: a választott root-fallback/active policy szerint, majd
    childba belépve a mentett tartalom és zöld Wire helyreáll.
11. Python webhook restart Fabric restart nélkül: child új epochot kap, belépés
    authoritative reload után ugyanazt a mentett snapshotot mutatja.
12. Fabric restart után root jelenik meg; childba belépve a mentett tartalom
    ismét helyes.
13. ProgramBlock törlésének próbája nem törli automatikusan a gyermek Stage
    tartalmát; a döntött DETACHED/confirmation UX jelenik meg.

Elfogadási invariánsok:

- navigáció nem növeli a `saved_version` értéket;
- minden Stage külön dirty/epoch/revision állapotú;
- inaktív Stage késői válasza nem ír a világba;
- a SAVE-tábla mindig az aktív Stage-et mutatja;
- az előző Stage programblokkjai és wire-jai nem maradnak az új projekcióban;
- nincs automatikus GraphEdge-törlés, BROKEN-re írás vagy DB-javítás.

## 9. Végső javaslat

**CONDITIONAL GO.**

A meglévő StageSnapshot, több-workspace Python szolgáltatás, teljes
PostgreSQL-perzisztencia, GraphProjectionStore és T8 reconciliation megfelelő
alap. A megvalósítás kis, külön tesztelhető szeletekre bontható.

GO az A1 tervezésére és implementációjára akkor adható, ha a projektgazda
előbb rögzíti legalább az aktív Stage scope-ját, az első child SAVE
előfeltételét, a ProgramBlock-törlés recoverable viselkedését és a visszalépési
UX-et. NO-GO lenne olyan implementációra, amely a Hall egyetlen Stage mezőjét
egyszerűen felülírja, váltáskor automatikusan ment, vagy ProgramBlock-törléskor
csendben cascade-del törli a gyermek Stage-et.
