# CK-01/B mini ADR – CK Wire, Port, GraphEdge és Layout

- **Állapot:** elfogadott implementációs szerződés
- **Dátum:** 2026-10-22
- **Hatókör:** az első vezérlési gráf vertical slice
- **Nem része:** ModelNode, ModelRelation, Annotation, NavigationContext, több WorkspaceView, DATA-portok, JUNCTION és játékbeli ConnectionDestroyer

## 1. Kontextus és cél

A bloCKommanderben a PostgreSQL a mentett modell hiteles forrása, a Minecraft-világ pedig szerkesztő és megjelenítő felület. A CK Wire fizikailag lerakható vezeték, de a program jelentését nem a blokkok puszta szomszédsága, hanem a portok között fennálló stabil `GraphEdge` adja.

Ez az ADR véglegesíti az első implementációs szakasz szerződését úgy, hogy:

- a meglévő Stage- és blokkmodell újrahasznosuljon;
- a logikai kapcsolat és a fizikai útvonal külön maradjon;
- egy megszakadt vagy átrajzolt vezeték ne törölje vagy cserélje le automatikusan a logikai élt;
- a DRAFT állapot menthető legyen;
- a VALID minősítés konkrét munkapéldány-revízióhoz tartozzon;
- a későbbi REFORMAT kizárólag Layoutot változtathasson.

## 2. Meglévő architektúra

```text
Fabric Client
    ⇅ Fabric custom payload
Fabric Server
    ⇅ localhost HTTP JSON
Python HTTP Service
    ⇅ psycopg2
PostgreSQL

Fabric Server ── kizárólagos világíró ──> Minecraft-világ
```

A forráskód ezt az architektúrát továbbra is igazolja:

- `src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt` kezeli a játékosinterakciót és hívja a Python API-t.
- `src/main/kotlin/me/buciqua/blockommander/runtime/WorldRenderer.kt` módosítja a világot.
- `src/main/kotlin/me/buciqua/blockommander/runtime/HallRegistry.kt` a Fabric szerver futásidejű cache-e; nem hiteles perzisztencia.
- `python-service/src/hangar/stage.py` kezeli a memóriabeli Stage-munkapéldányt, a dirty állapotot és a munkarevíziót.
- `python-service/src/hangar/repository.py` kezeli a PostgreSQL snapshotot és az optimista `saved_version` ellenőrzést.
- `python-service/src/hangar/migrations/004_program_stages.sql` szerint a `stage_blocks.instance_id` stabil blokkazonosító.

### 2.1 Jelenlegi illesztési eltérés

A jelenlegi `StageService.save()` a sikeres mentés végén növeli a `revision` értéket (`stage.py:106-115`), noha a munkapéldány tartalma nem változik. A jelen ADR-ben a `working_revision` tartalmi revízió: csak node-, edge- vagy Layout-módosításkor nőhet. A SAVE a `saved_version` értéket növeli és `dirty=false` állapotot eredményez, de nem érvénytelenítheti a tartalmilag változatlan VALID minősítést.

Ez lokalizált implementációs illesztési feladat, nem a koncepcióval fennálló ellentmondás. A javítás nem része a CK-01/B dokumentációs műszaknak.

## 3. Elfogadott döntések

1. A meglévő `stage_blocks` rekord a `GraphNode`. Nem készül párhuzamos node-tábla.
2. A stabil node-azonosító az `instance_id`.
3. A port szemantikus azonosítója `(instance_id, port_key)`.
4. A port szemantikája független a blokk koordinátájától, világirányától és wire útvonalától.
5. Az első verzió kizárólag `CONTROL` portokat és `CONTROL` éleket támogat.
6. A `GraphEdge` logikai kapcsolat; az `edge_id` nem függ az útvonaltól.
7. A `Layout` fizikai reprezentáció. A CK Wire cellák és route-ok ide tartoznak.
8. Wire lerakás, törés vagy átrajzolás önmagában nem hoz létre és nem töröl `GraphEdge`-et.
9. Él csak explicit **ÉLBEKÖTÉS** művelettel hozható létre vagy köthető újra fizikai route-hoz.
10. Él csak explicit, megerősített ConnectionDestroyer művelettel törölhető.
11. A normál wire-bontás a route-ot töri meg; a GraphEdge megmarad.
12. A `CROSS_UNCONNECTED` NS és EW csatornái ugyanazon koordinátán léteznek, de nincs közöttük átjárás.
13. JUNCTION az első verzióban tiltott.
14. DRAFT Stage menthető. Export/task-commit csak az aktuális revízióra VALID és mentett állapotból történhet.
15. SAVE és ÉLBEKÖTÉS külön művelet. Az ÉLBEKÖTÉS nem ír PostgreSQL-be.

## 4. Objektumszerződések

Az alábbi mezőnevek nyelvfüggetlen szerződések. A CK-02 Python implementációban fagyasztott dataclassok és enumok javasoltak.

### 4.1 Alaptípusok

```text
GridPos
    x: int
    y: int
    z: int

CardinalDirection
    NORTH | EAST | SOUTH | WEST

LocalSide
    FRONT | RIGHT | BACK | LEFT

PortKind
    CONTROL
    # később: DATA

PortDirection
    IN | OUT

EdgeKind
    CONTROL
    # később: DATA
```

Minden wire-koordináta Stage-origóhoz viszonyított relatív koordináta a Python domainben és a későbbi DB-ben. A Fabric szerver abszolút világkoordinátát csak a Stage origójával képez.

### 4.2 PortDefinition

```text
PortDefinition
    port_key: str
    port_kind: PortKind
    direction: PortDirection
    local_side: LocalSide
    ordinal: int
    min_edges: int
    max_edges: int
    branch_label: str | None
```

Invariánsok:

- `port_key` blokktípuson belül egyedi és stabil.
- `port_key` nem tartalmaz világirányt vagy koordinátát.
- `0 <= min_edges <= max_edges`.
- Az első demonstrációban minden port `min_edges=1`, `max_edges=1`.
- `branch_label` az IF `true`/`false` portján rendre `1`/`0`; más portokon lehet `None`.
- `ordinal` kizárólag stabil rendezésre és fizikai megjelenítési sorrendre szolgál, nem portazonosító.

### 4.3 PortRef

```text
PortRef
    instance_id: UUID
    port_key: str
```

A `PortRef` szemantikus hivatkozás. Nem tartalmaz blokkpozíciót vagy orientációt.

### 4.4 GraphEdge

```text
GraphEdge
    edge_id: UUID
    stage_id: UUID
    source_instance_id: UUID
    source_port_key: str
    target_instance_id: UUID
    target_port_key: str
    edge_kind: EdgeKind
```

A szemantikus végpontkulcs:

```text
(stage_id,
 source_instance_id, source_port_key,
 target_instance_id, target_port_key,
 edge_kind)
```

Azonos végpontkulcs újrahuzalozásakor az `edge_id` kötelezően megmarad. A route, cellasorrend, hossz és alak nem része az edge azonosságának.

### 4.5 WireCell és CROSS csatornák

```text
WireChannel
    SINGLE | NS | EW

WireCell
    position: GridPos
    channels: tuple[WireChannelState, ...]

WireChannelState
    channel: WireChannel
    connections: frozenset[CardinalDirection]
```

Szabályok:

- Normál wire cella pontosan egy `SINGLE` csatornát tartalmaz.
- `CROSS_UNCONNECTED` pontosan két csatornát tartalmaz: `NS` kapcsolatai `{NORTH, SOUTH}`, `EW` kapcsolatai `{EAST, WEST}`.
- Egy cellán belül NS-ről EW-re nincs topológiai átjárás.
- Más többcsatornás cella az első verzióban érvénytelen.
- Három vagy több irányba kapcsoló `SINGLE` cella `ILLEGAL_JUNCTION`.

### 4.6 EdgeRoute

```text
RouteStatus
    INCOMPLETE | VALID | BROKEN | AMBIGUOUS | ILLEGAL

WireStep
    position: GridPos
    channel: WireChannel

EdgeRoute
    route_id: UUID
    stage_id: UUID
    edge_id: UUID | None
    source_port: PortRef | None
    target_port: PortRef | None
    steps: tuple[WireStep, ...]
    status: RouteStatus
```

`route_id` Layout-azonosító, nem programjelentés. Újrarajzoláskor változhat. Befejezetlen, GraphEdge-hez még nem tartozó route esetén `edge_id=None`.

Az első verzióban egy GraphEdge-hez legfeljebb egy aktív route tartozik. Ezt később lehet redundáns vagy alternatív útvonalakkal bővíteni, de a GraphEdge szerződése nem változik.

### 4.7 Diagnosztika

```text
Diagnostic
    code: DiagnosticCode
    severity: ERROR | WARNING | INFO
    message: str
    node_ids: tuple[UUID, ...]
    port_refs: tuple[PortRef, ...]
    edge_ids: tuple[UUID, ...]
    wire_positions: tuple[GridPos, ...]
    suggestion: str | None
```

Kötelező kódok:

```text
UNKNOWN_NODE
UNKNOWN_PORT
INVALID_PORT_DIRECTION
INCOMPATIBLE_PORT_KIND
PORT_CARDINALITY_VIOLATION
CROSS_STAGE_EDGE
OPEN_WIRE
AMBIGUOUS_CONNECTION
BROKEN_ROUTE
ILLEGAL_JUNCTION
INVALID_CROSS_CHANNEL
MISSING_IF_BRANCH
INVALID_IF_MERGE
UNREACHABLE_NODE
ILLEGAL_CYCLE
DUPLICATE_EDGE
```

Kiegészítő, szükséges concurrency kód:

```text
STALE_WORKING_REVISION
```

Az első vertical slice-ban minden felsorolt strukturális és route-hiba blokkoló `ERROR`. Később egyes lint jellegű diagnosztikák `WARNING` besorolást kaphatnak.

Determinista sorrend:

1. severity rang (`ERROR`, `WARNING`, `INFO`);
2. a `DiagnosticCode` deklarációs sorrendje;
3. node UUID-k szöveges sorrendje;
4. portkulcsok;
5. edge UUID-k;
6. wire-koordináták `(x, y, z)` szerint;
7. üzenet mint utolsó tie-breaker.

Az emberi üzenet változhat lokalizáció miatt; automatikus teszt elsősorban a kódot és a strukturált mezőket ellenőrizze.

## 5. Portszemantika és orientációtranszformáció

A blokk `FACING` értéke a blokk szemantikus eleje. A központi transzformáció:

```text
world_side(facing, local_side) -> CardinalDirection
port_world_position(node_world_position, facing, port_definition) -> GridPos
```

| FACING | FRONT | RIGHT | BACK | LEFT |
|---|---|---|---|---|
| NORTH | NORTH | EAST | SOUTH | WEST |
| EAST | EAST | SOUTH | WEST | NORTH |
| SOUTH | SOUTH | WEST | NORTH | EAST |
| WEST | WEST | NORTH | EAST | SOUTH |

A port csatlakozócellája a node világpozíciójának `world_side` irányú közvetlen szomszédja. Ha több port ugyanazon a `local_side` oldalon lenne, az `ordinal` ad stabil megjelenítési sorrendet; az első demonstráció blokkmérete miatt ilyen elrendezést lehetőleg kerülni kell.

A blokk elforgatása kizárólag a fizikai oldalt változtatja. Például az IF `true` portja minden orientációban `true` marad.

Az orientációtranszformáció pontosan egy Python domainfüggvényben legyen definiálva. A Fabric oldal vagy ugyanennek dokumentált tükörfüggvényét használja kontrakttesztekkel, vagy a Python által visszaadott fizikai porthelyet fogyasztja. Több, eltérő képlet nem fogadható el.

## 6. GraphEdge életciklus

```text
NINCS ÉL
  └─ explicit ÉLBEKÖTÉS, sikeres teljes ellenőrzés
       └─ GRAPH EDGE + VALID ROUTE
            ├─ normál wire-módosítás
            │    └─ UGYANAZ A GRAPH EDGE + BROKEN/STALE ROUTE
            │         └─ javítás + sikeres ÉLBEKÖTÉS
            │              └─ UGYANAZ AZ EDGE ID + VALID ROUTE
            ├─ útvonal-átrajzolás azonos végpontokkal
            │    └─ UGYANAZ AZ EDGE ID
            └─ ConnectionDestroyer + megerősítés
                 └─ ÉL ÉS ROUTE TÖRÖLVE A MUNKAPÉLDÁNYBÓL
```

### 6.1 Létrehozás

- Wire lerakás nem hoz létre élt.
- Az ÉLBEKÖTÉS teljes, konzisztens jelölt edge/route állapotot készít.
- Új végpontpárhoz új UUID készül.
- Már létező azonos végpontpár megtartja az edge UUID-jét.

### 6.2 Normál wire-bontás

- Csak a Layout változik.
- A GraphEdge változatlanul megmarad.
- Az érintett route `BROKEN`, és `BROKEN_ROUTE` diagnosztika készül.
- A `working_revision` nő, a Stage DRAFT és DIRTY lesz.
- A korábbi `validated_revision` történeti adatként megmaradhat, de nem egyezik az aktuális revízióval.
- Az állapotjelző `ELLENŐRIZENDŐ`; a proaktívan ismert `BROKEN_ROUTE` diagnosztika már megjeleníthető.

### 6.3 Újrahuzalozás

- Ha ugyanaz a forrás- és célport kapcsolódik újra, az edge ID megmarad.
- Ha más végpontpár készülne, az új élnek új ID kellene.
- Az explicit törlés kizárólagossága miatt az ÉLBEKÖTÉS nem törölheti csendben a régi, már nem útvonalazott élt. Az ilyen régi él `BROKEN_ROUTE`/kardinalitási hibát okozhat, amíg a játékos ConnectionDestroyerrel meg nem szünteti vagy az eredeti végpontokhoz vissza nem huzalozza.

### 6.4 Törlés

- GraphEdge kizárólag explicit ConnectionDestroyer domainműveletben törölhető.
- Node törlése előtt a hozzá tartozó élek explicit kezelését kell kérni; automatikus cascade a munkapéldány szemantikájában nem megengedett.
- A későbbi DB-FK lehet technikai `ON DELETE` védelem, de nem helyettesítheti a domainműveletet.

## 7. DRAFT/VALID és CLEAN/DIRTY állapotgép

### 7.1 Független dimenziók

```text
GraphStatus = DRAFT | VALID
SaveStatus  = CLEAN | DIRTY
```

Lehetséges kombinációk:

| GraphStatus | SaveStatus | Jelentés |
|---|---|---|
| DRAFT | DIRTY | módosított, nem validált vagy hibás munkapéldány |
| DRAFT | CLEAN | elmentett, de nem valid vagy még nem validált snapshot |
| VALID | DIRTY | aktuális revízió valid, de még nincs DB-be mentve |
| VALID | CLEAN | aktuális revízió valid és mentett; exportálható/committálható |

A DRAFT mentése megengedett. A SAVE nem validál automatikusan.

### 7.2 Verziófogalmak

```text
working_revision: int
    A munkapéldány tartalmi revíziója. Node-, edge- vagy Layout-módosításkor nő.

validated_revision: int | None
    A legutóbbi sikeresen validált tartalmi revízió.

saved_version: int
    PostgreSQL snapshotverzió, optimista konkurenciavezérléshez.

last_validation: ValidationReport | None
    Tartalmazza a vizsgált revíziót és a diagnosztikát.
```

Szabályok:

- `GraphStatus=VALID` pontosan akkor, ha `validated_revision == working_revision` és az ehhez a revízióhoz tartozó reportban nincs blokkoló diagnosztika.
- Minden tartalmi módosítás növeli a `working_revision` értéket, ezért az előző VALID azonnal elavul.
- SAVE nem növeli a `working_revision` értéket.
- SAVE sikerénél nő a `saved_version`, és `dirty=false` lesz.
- DRAFT és VALID egyaránt menthető.
- REBUILD/LOAD tartalmat cserél, ezért új munkarevíziót képez; automatikusan nem lesz VALID.
- `validated_revision` nem DB-lock token, a `saved_version` nem munkapéldány-revízió. A két számláló nem helyettesíti egymást.

### 7.3 Élállapot-jelző

```text
EdgeIndicatorStatus = CHECK_REQUIRED | OK | ERRORS
```

Számítása:

1. Ha `last_validation is None` vagy `last_validation.revision != working_revision`: `CHECK_REQUIRED` (`Élek: ellenőrzendő`).
2. Ha `validated_revision == working_revision`: `OK` (`Élek rendben`).
3. Ha `last_validation.revision == working_revision` és blokkoló hiba van: `ERRORS` (`Élek: hibásak`).

A normál wire-módosítás után az állapot `CHECK_REQUIRED`, még akkor is, ha lokálisan már felismerhető `BROKEN_ROUTE`. `ERRORS` csak az aktuális revízión lefutott teljes ellenőrzés eredménye.

## 8. Élbekötés munkafolyamat

### 8.1 Kérés

Tervezett, későbbi request:

```text
BindEdgesCommand
    stage_id
    player_uuid
    expected_working_revision
```

A Fabric szerver a Stage-et és játékost a gombpozícióból/aktív registryből azonosítja. A kliens által küldött Stage-ID önmagában nem jogosultsági bizonyíték.

### 8.2 Atomi algoritmus

1. Stage-workspace lock megszerzése.
2. Tulajdonos/szerkesztési jogosultság ellenőrzése.
3. `expected_working_revision == working_revision` ellenőrzése. Eltérés: `STALE_WORKING_REVISION`, nincs módosítás.
4. Immutable snapshot készítése node-okról, meglévő edge-ekről és wire Layoutból.
5. Wire-topológia feltérképezése csatornaérzékenyen.
6. Portvégpontok azonosítása a központi orientációtranszformációval.
7. Jelölt route-ok és végpontpárok képzése.
8. Ismert végpontpárnál meglévő edge ID újrahasznosítása; új párnál új edge ID jelölése.
9. A meglévő, de a jelölt route-okban nem szereplő edge-ek megtartása; nem törölhetők automatikusan.
10. Teljes jelölt állapot validálása: irány, kind, kardinalitás, Stage, IF/END_IF, elérhetőség, ciklus, route-hibák.
11. Blokkoló diagnosztika esetén a jelölt állapot eldobása. A korábbi edge/route halmaz változatlan. Csak a `last_validation` report frissül az aktuális revízióra.
12. Siker esetén a teljes jelölt edge/route hozzárendelés egyszerre kerül a munkapéldányba.
13. Ha a jelölt tartalom eltér, a `working_revision` pontosan egyszer nő. Ha csak újraellenőrzés történt tartalomváltozás nélkül, nem nő.
14. Sikeres report az alkalmazás utáni aktuális revízióhoz kötődik; `validated_revision` ezt kapja.
15. A művelet nem hív repository mentést és nem növeli a `saved_version` értéket.

### 8.3 Válasz

```text
BindEdgesResult
    applied: bool
    stage_id
    base_revision
    resulting_revision
    graph_status
    save_status
    indicator_status
    edges                  # teljes aktuális halmaz
    routes                 # teljes aktuális Layout-nézet
    diagnostics            # determinisztikusan rendezve
```

Nem lehet részleges siker. `applied=false` esetén a domain edge/route tartalom byte-szinten/érték szerint azonos marad a kérés előtti állapottal.

## 9. CROSS és JUNCTION

### 9.1 CROSS_UNCONNECTED

Egy CROSS cella két külön topológiai csúcsnak tekintendő ugyanazon koordinátán:

```text
(position, NS)  NORTH ↔ SOUTH
(position, EW)  EAST  ↔ WEST
```

A bejárás kulcsa `(position, channel)`, nem pusztán `position`. NS és EW között nem készül szomszédsági él.

Érvénytelen CROSS példák:

- hiányzó NS vagy EW csatorna;
- NS csatornához EAST/WEST kapcsolat;
- EW csatornához NORTH/SOUTH kapcsolat;
- bármely csatornák közötti átjárás;
- harmadik csatorna.

Ezek `INVALID_CROSS_CHANNEL` hibák.

### 9.2 JUNCTION

JUNCTION nem támogatott az első verzióban. Bármely normál `SINGLE` wire cella, amely három vagy négy irányt köt össze, `ILLEGAL_JUNCTION`. A CROSS vizuális négyirányúsága nem kivételként megengedett junction, hanem két külön kétirányú csatorna.

## 10. ConnectionDestroyer szerződés

A játékbeli eszköz későbbi feladat; a domainművelet szerződése most rögzített.

### 10.1 Kijelölés

```text
ConnectionSelection
    stage_id
    wire_position
    channel: WireChannel | None
    expected_working_revision
```

- Egyedi route/edge találat esetén megerősítési előnézet készül.
- CROSS esetén channel nélkül a kijelölés kétértelmű és elutasítandó.
- Több route-hoz tartozó cellánál a rendszer nem választ találomra.
- Unbound route kijelölhető, de ilyenkor csak Layout törölhető.

### 10.2 Megerősítés

A megerősítéshez a Stage-revízióhoz és a feloldott célhoz kötött egyszer használatos confirmation token javasolt. Ha a Stage közben változik, a token érvénytelen.

### 10.3 Atomi törlés

- Jogosultság és revízió újraellenőrzése.
- Bound route: a teljes route és a hozzá tartozó GraphEdge törlése a munkapéldányból.
- Unbound route: csak a fizikai route cellái törlődnek.
- Ha egy kijelölt route egy `CROSS_UNCONNECTED` cellának csak az egyik csatornáját használja, kizárólag ez a csatorna szűnik meg. A másik csatorna és route megmarad; a cella kívánt alakja CROSS-ról a megmaradó egyenes wire alakra változik.
- A `working_revision` pontosan egyszer nő; `dirty=true`; aktuális VALID megszűnik; indicator `CHECK_REQUIRED`.
- A Python válasza cellaállapot-diffet adjon vissza: koordinátánként törlés vagy a megmaradó csatornákat leíró kívánt `WireCell`. A Fabric nem törölhet vakon teljes CROSS blokkot.
- PostgreSQL-írás nincs; tartóssá csak későbbi SAVE után válik.

## 11. Perzisztenciahatárok

### 11.1 Munkapéldány

A Python `StageService`/későbbi graph domain tartalmazza:

- GraphNode-ok (`stage_blocks` munkapéldányai);
- GraphEdge-ek;
- WireCell-ek és EdgeRoute-ok;
- `working_revision`, `validated_revision`, `last_validation`, `dirty`.

Wire szerkesztés, ÉLBEKÖTÉS és ConnectionDestroyer kizárólag ezt módosítja.

### 11.2 PostgreSQL

SAVE az egyetlen perzisztenciahatár. A CK-07-ben készülő additív migráció után egyetlen optimista Stage-snapshot tranzakciónak kell mentenie:

- node-okat;
- GraphEdge-eket;
- Layout/wire adatokat;
- a snapshot validációs metaadatát, ha annak perzisztálása mellett döntünk.

A DRAFT snapshot menthető. A `saved_version` továbbra is `SELECT ... FOR UPDATE` mellett ellenőrzött.

A 001–005 migrációk változatlanok maradnak. A bővítés 006 vagy későbbi, additív migráció.

### 11.3 Minecraft-világ

A világ nem hiteles modell. A Fabric szerver kizárólag a Python által visszaadott munkapéldány- vagy DB-snapshot alapján renderel. DB/világ eltérés esetén az idempotens teljes Stage-rerender a helyreállítási út.

## 12. Kötelező invariánsok

### 12.1 Node és port

- Minden edge mindkét node-ja létezik.
- Mindkét node az edge `stage_id` Stage-éhez tartozik.
- Minden port létezik a node típusának katalógusában.
- Forrásport `OUT`, célport `IN`.
- Mindkét port `port_kind` értéke kompatibilis az `edge_kind` értékével.
- Portkardinalitás a teljes edge-halmazra teljesül.

### 12.2 Edge

- Edge végpontkulcs Stage-en belül egyedi; duplikáció `DUPLICATE_EDGE`.
- Edge ID stabil azonos végpontpár újrahuzalozásakor.
- Edge ID nem származtatható koordinátából vagy route hashből.
- Normál Layout-módosítás nem töröl edge-et.
- Sikertelen ÉLBEKÖTÉS nem módosít edge-et.

### 12.3 Layout

- Minden wire cella a Stage szerkeszthető tartományában van.
- Normál cella `SINGLE`; CROSS csak NS+EW.
- Ugyanaz a `(position, channel)` legfeljebb egy route-hoz tartozhat az első verzióban.
- Route endpointja nulla, egy vagy két port lehet; kettő alatt DRAFT/hibás.
- Route geometria nem része a GraphEdge azonosságának.

### 12.4 Strukturált demó

- START `out`, STOP `in` pontosan egy éllel rendelkezik.
- IF `in`, `true`, `false` mind pontosan egy éllel rendelkezik.
- IF `true` és `false` külön szemantikus port.
- END_IF `in_true` és `in_false` külön szemantikus port, nem felcserélhető.
- Mindkét IF ág a hozzá rendelt END_IF portra érkezik.
- A STOP elérhető a START-ból.
- Nem engedett strukturálatlan vagy tiltott ciklus az első demóban.

## 13. Elutasított alternatívák

### 13.1 Külön GraphNode-tábla

Elutasítva: megkettőzné a `stage_blocks` identitást és szinkronizációs problémát hozna létre.

### 13.2 BlockEntity minden wire-szegmenshez

Elutasítva az első verzióban: felesleges NBT/szinkronterhelés és a PostgreSQL mellett második perzisztenciaforrás lenne. A vizuális alak BlockState-tel kezelhető.

### 13.3 Automatikus edge wire lerakáskor

Elutasítva: a befejezetlen és átmenetileg hibás Layout közben nem szabad szemantikus kapcsolatot létrehozni.

### 13.4 Edge automatikus törlése wire-bontáskor

Elutasítva: megsértené az edge/layout szétválasztását, az edge ID stabilitását és az újrahuzalozhatóságot.

### 13.5 CROSS mint négypontos junction

Elutasítva: rejtett logikai összeköttetést és kétértelmű gráfot hozna létre.

### 13.6 SAVE-kori implicit ÉLBEKÖTÉS

Elutasítva: összemosná a validációt/perzisztenciát, és a DRAFT menthetőségét akadályozná.

### 13.7 Export közvetlenül wire-koordinátákból

Elutasítva: az exportnak a stabil GraphEdge modellből kell dolgoznia, hogy az átrajzolás ne változtassa meg a programot.

## 14. Kockázatok és nyitott implementációs részletek

### 14.1 Kockázatok

- **Stale validáció:** minden elemzés `expected_working_revision` mellett fusson; alkalmazás előtt lock alatt újraellenőrzés kell.
- **Sikertelen Élbekötés:** copy-on-write jelölt állapot, majd egyetlen swap; nincs inkrementális edge-módosítás.
- **DB/világ eltérés:** idempotens teljes rerender, strukturált operation ID és visszajelzés szükséges a későbbi integrációban.
- **Processzmemória:** több Python worker külön workspace-t látna. A jelenlegi telepítés egy Waitress processz, de ezt üzemeltetési invariánsként dokumentálni kell, amíg nincs megosztott workspace/lease.
- **Node törlés:** az explicit edge-törlési szabállyal össze kell hangolni; első implementációban kapcsolt node törlése legyen elutasítva.
- **Portgeometria:** a Fabric `FACING` jelentését vizuális modellteszttel kell rögzíteni.

### 14.2 Implementáció előtt még pontosítandó, de a CK-02-t nem blokkoló részletek

1. A portok vizuális jelölése textúrával, overlay-jel vagy külön kliensrenderrel történjen-e.
2. A VALID reportot a CK-07-ben perzisztáljuk-e, vagy LOAD után kötelező újravalidálás legyen. Biztonságos alapértelmezés: LOAD után DRAFT/check-required, amíg nincs validátorséma-verzió.
3. A későbbi DB-ben a route cellák normalizált sorok vagy tömör JSON legyenek-e. Az ADR csak a szemantikai határt rögzíti.
4. ConnectionDestroyer megerősítő UI-ja modal screen vagy chat-alapú legyen-e.
5. Az állapotjelző tábla frissítése teljes Stage-rerender vagy célzott control-update legyen-e.

Ezek egyikére sem kell választ adni a tiszta CK-02 Python domainmag megkezdéséhez.

## 15. Implementációs műszakok

Az alábbi sorrend a függőségeket követi, ezért változatlanul megtartandó.

### CK-02 – Python domainmag

- **Érintett fájlok:** új `python-service/src/hangar/graph/*`; új `python-service/tests/test_graph_*.py`.
- **Előfeltétel:** ez az ADR.
- **Feladat:** enumok, fagyasztott modellek, orientációtranszformáció, diagnosztikarendezés, revízióhoz kötött state és copy-on-write binding szerződés.
- **Automatikus teszt:** teljes kötelező tesztmátrix tiszta fixture-ökkel, DB/Minecraft nélkül.
- **Manuális ellenőrzés:** nincs runtime próba; import- és API-review.
- **Elfogadás:** determinisztikus, tiszta Python; nincs I/O; nincs produkciós integráció.
- **Visszaállítás:** csak az új graph modul és tesztfájlok eltávolítása; runtime nem érintett.

### CK-03 – Portkatalógus és strukturális validátor

- **Érintett fájlok:** graph catalog/rules modulok, `stage.py` későbbi adaptere, új tesztek; még nincs DB-migráció.
- **Előfeltétel:** CK-02 modellek stabilak.
- **Feladat:** START/IF/SET/PRINT/END_IF/STOP katalógus; generikus port- és kardinalitásellenőrzés; IF/END_IF szabályok; reachability/cycle.
- **Automatikus teszt:** négy orientáció; missing branch; invalid merge; unreachable; cycle; duplicate.
- **Manuális ellenőrzés:** katalógustábla összevetése az ADR-rel.
- **Elfogadás:** a demonstrációs edge-halmaz fizikai Layout nélkül validálható.
- **Visszaállítás:** új katalog/rule adapter kikapcsolása; meglévő Stage CRUD változatlan.

### CK-04 – CK Wire fizikai prototípus

- **Érintett fájlok:** új Kotlin `CkWireBlock`; `ModContent.kt`; resource blockstate/model/item/loot/lang; Kotlin tesztek vagy GameTest, ha támogatott.
- **Előfeltétel:** BlockState shape/CROSS szerződés végleges.
- **Feladat:** fekete, lapos, vastag, kézzel lerakható blokk; négyirányú helyi szomszédfrissítés; nincs ticker, redstone vagy BlockEntity.
- **Automatikus teszt:** shape-transitions és JSON/build.
- **Manuális ellenőrzés:** egyenes, sarok, törés, item/drop külön tesztvilágban.
- **Elfogadás:** dedikált szerver és kliens betölt; nincs világírás a Pythonból.
- **Visszaállítás:** új blokkregisztráció/resource-ok eltávolítása; világteszt backupból.

### CK-05 – Wire workspace és topológiafelderítés

- **Érintett fájlok:** `stage.py`; graph topology modul; `ck_webhook.py`; Fabric `Interactions.kt`, `HallRegistry.kt`, `WorldRenderer.kt`; tesztek.
- **Előfeltétel:** CK-03 és CK-04.
- **Feladat:** wire cell CRUD dirty workspace-ben; relatív koordináták; port endpointok; route-felderítés; CROSS channel.
- **Automatikus teszt:** open/broken/ambiguous/junction/cross/boundary/concurrency.
- **Manuális ellenőrzés:** wire szerkesztése SAVE nélkül; normál bontás edge-t nem töröl.
- **Elfogadás:** a Python teljes Layoutot ad vissza, Fabric kizárólag azt rendereli.
- **Visszaállítás:** wire API feature flaggel kikapcsolható; DB még érintetlen.

### CK-06 – Élbekötés

- **Érintett fájlok:** binding/validation service; `ck_webhook.py`; Fabric control modell, `Interactions.kt`, `WorldRenderer.kt`; állapotjelző DTO.
- **Előfeltétel:** CK-05 determinisztikus topológia.
- **Feladat:** ÉLBEKÖTÉS gomb; owner/revision check; atomikus bind; diagnosztika; CHECK_REQUIRED/OK/ERRORS tábla.
- **Automatikus teszt:** sikeres teljes apply; sikertelen no-partial; stale revision; edge-ID megőrzés.
- **Manuális ellenőrzés:** gomb és magyar feliratok tesztvilágban.
- **Elfogadás:** művelet nem ír DB-be; korábbi edge-ek hibánál érintetlenek.
- **Visszaállítás:** control/API kikapcsolása; workspace újratöltése mentett DB snapshotból.

### CK-07 – Perzisztencia

- **Érintett fájlok:** új `006_*.sql` (szükség esetén további új migráció); `repository.py`; `stage.py`; repository/integrációs tesztek.
- **Előfeltétel:** edge/layout DTO stabil, ellenőrzött DB-backup.
- **Feladat:** node+edge+layout egyetlen optimista snapshot; régi Stage-ek kompatibilis betöltése.
- **Automatikus teszt:** fresh migration, upgrade 005-ről, rollback injektált hibánál, version conflict, DRAFT save.
- **Manuális ellenőrzés:** schema diff és read-only round-trip ellenőrzés teszt-DB-n.
- **Elfogadás:** 001–005 változatlan; régi adat megmarad; nincs részleges snapshot.
- **Visszaállítás:** pre-migration DB backup; alkalmazás visszagörgetése olyan verzióra, amely figyelmen kívül hagyja az additív táblákat.

### CK-08 – SAVE / LOAD / REBUILD

- **Érintett fájlok:** `StageService`, repository, webhook DTO-k, `HallRegistry.kt`, `WorldRenderer.kt`.
- **Előfeltétel:** CK-07.
- **Feladat:** teljes snapshot visszatöltése; idempotens világ-rerender; dirty discard megerősítés.
- **Automatikus teszt:** canonical node/edge/layout összehasonlítás; üres wire; CROSS; DRAFT és VALID snapshot.
- **Manuális ellenőrzés:** SAVE → tesztterület ürítése → LOAD/REBUILD → vizuális ellenőrzés.
- **Elfogadás:** edge ID-k és portok azonosak; Layout az első slice-ban azonos; ismételt rerender idempotens.
- **Visszaállítás:** DB backup + világbackup; mentett snapshot változatlan marad sikertelen rendernél.

### CK-09 – ConnectionDestroyer

- **Érintett fájlok:** új item/recipe/model; kliens megerősítő UI/payload; Fabric interaction; Python destroy domain/API.
- **Előfeltétel:** CK-08 stabil round-trip.
- **Feladat:** kijelölés, ambiguity kezelés, confirmation token, edge+route vagy unbound route atomi törlés.
- **Automatikus teszt:** bound/unbound/cross ambiguous/no/stale/unauthorized.
- **Manuális ellenőrzés:** IGEN/NEM, teljes fizikai eltávolítás, SAVE előtti visszaépíthetőség.
- **Elfogadás:** találomra semmi nem törlődik; DB csak SAVE-kor változik.
- **Visszaállítás:** eszköz/API kikapcsolása; SAVE előtt REBUILD a mentett snapshotból.

### CK-10 – Pszeudokód-export

- **Érintett fájlok:** új tiszta exporter modul; read-only Python endpoint; tesztek; opcionális Fabric megjelenítés.
- **Előfeltétel:** CK-08, aktuális VALID+CLEAN snapshot.
- **Feladat:** START/IF/SET/PRINT/END_IF/STOP determinisztikus strukturálása GraphEdge-ből.
- **Automatikus teszt:** golden files; ágfelcserélés; invalid/draft/dirty elutasítás; Layout-átrajzolás invariancia.
- **Manuális ellenőrzés:** export összevetése a játékbeli gráffal.
- **Elfogadás:** exporter nem olvas wire-koordinátát a szemantika meghatározásához.
- **Visszaállítás:** read-only endpoint/exporter eltávolítása; mentett modell érintetlen.

## 16. Döntési összefoglaló

A minimális rendszer három szintje:

```text
stage_blocks / instance_id     = GraphNode identitás
PortDefinition + GraphEdge     = program szemantikája
WireCell + EdgeRoute           = fizikai Layout
```

E három szint között nincs automatikus törlési lánc. A Layout szabadon lehet DRAFT vagy hibás; a GraphEdge csak explicit ÉLBEKÖTÉS során jön létre, és kizárólag explicit ConnectionDestroyerrel törölhető. SAVE bármely DRAFT/VALID munkapéldányt perzisztálhat; exporthoz az aktuális revízióra VALID és CLEAN állapot szükséges.
