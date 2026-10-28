# CK-05/A3 – Discovery API és Stage-wide Élbekötés

## 1. Mérföldkő-eredmény

- **A3/1 – Discovery DTO és explicit többjelöltes bind: PASS**
- **A3/2 – Többcellás WIRE_LAYOUT: PASS**

Az A2 `discover_physical_topology()` eredménye bekerült a Python Graph alkalmazási válaszokba. A Stage-szintű `BIND_EDGES` most az összes fizikailag teljes kapcsolatjelöltet egyetlen, meglévő `bind_edges()` domainhívással kezeli. A `WIRE_LAYOUT` egymást követő, továbbra is egycellás parancsokkal többcellás egyenest, sarkot, CROSS-t és félkész DRAFT vezetéket tud felépíteni.

Nem történt Fabric-, PostgreSQL-, PUBLISH-, Junction-, Restore- vagy színmodell-módosítás.

## 2. Módosított fájlok

Az A3 közvetlen változtatásai:

- `python-service/src/hangar/graph_application.py`
- `python-service/tests/test_graph_application.py`
- `python-service/tests/test_graph_http_api.py`
- `python-service/tests/test_graph_application_discovery.py`
- `docs/CK-05A3-discovery-api-stage-bind.md`

Az A3 az ugyanebben a munkafában lévő, még nem commitolt A2 fájlokra épül:

- `python-service/src/hangar/graph/discovery.py`
- `python-service/src/hangar/graph/__init__.py`
- `python-service/tests/test_graph_discovery.py`
- `docs/CK-05A2-python-discovery.md`

A meglévő idegen/korábbi unstaged dokumentumok nem lettek átírva.

## 3. API-szerződés és verziózás

### 3.1. Additív discovery DTO

A meglévő

```text
GET /api/v1/stages/{stage_id}/graph?player_uuid={player_uuid}
```

válasz, valamint a sikeres `WIRE_LAYOUT` és `BIND_EDGES` válasz új, felső szintű `discovery` mezőt tartalmaz:

```json
{
  "api_version": "ck-graph-v1",
  "status": "ok",
  "discovery": {
    "schema_version": 1,
    "components": [],
    "candidates": [],
    "diagnostics": []
  },
  "state": {}
}
```

A `components` elemei tartalmazzák:

- `component_id`;
- `status`;
- `vertices`: pozíció és csatorna;
- `ports`;
- `open_ends`: pozíció, csatorna és nyitott irány.

A `candidates` elemei tartalmazzák:

- `candidate_id` és `component_id`;
- `source_port` és `target_port`;
- forrástól célig rendezett `steps` listát.

A discovery diagnosztika tartalmazza a kódot, súlyosságot, üzenetet, komponensazonosítót, portokat és wire pozíciókat.

### 3.2. Kompatibilitási döntés

- A Graph API verzió maradt `ck-graph-v1`.
- A Graph command schema maradt `schema_version=1`.
- A discovery saját additív `schema_version=1` mezőt kapott.
- A meglévő Fabric `GraphResponseParser` a szükséges mezőket név szerint olvassa és az ismeretlen felső szintű mezőket nem utasítja el, ezért az új `discovery` mező visszafelé kompatibilis.
- A `binding` objektum új, additív `candidate_ids` mezőt kapott; a meglévő Fabric parser ezt szintén figyelmen kívül hagyja.

A domain discovery objektumai nem függnek HTTP-től; a szerializáció kizárólag a `graph_application.py` alkalmazási adapterben történik.

### 3.3. Read-only garancia

A Graph GET az authorized immutable `StageGraphContext.state` alapján futtat discoveryt. Nem hív `replace_content()`-et vagy Stage-műveletet, ezért nem módosítja:

- a working revisiont;
- a dirty állapotot;
- GraphEdge-et vagy EdgeRoute-ot;
- a mentett verziót;
- a validációs állapotot.

Ezt külön application unit teszt ellenőrzi.

## 4. Stage-wide többjelöltes Élbekötés

A `BIND_EDGES` alkalmazási sorrendje:

1. A meglévő `_execute()` ellenőrzi a játékost, Stage-et, workspace epochot, working revisiont és request-idempotenciát.
2. A jelenlegi immutable workspace-re lefut a discovery.
3. Kizárólag a discovery `COMPLETE` elemei alakulnak meglévő `RouteCandidate` értékké.
4. Az összes jelölt egyetlen `BindEdgesCommand` részeként, egyetlen `bind_edges()` domainhívásba kerül.
5. A `StageService.apply_graph_operation()` csak elfogadott teljes eredményt cserél be.
6. A válasz felsorolja a jelölt-, létrehozott edge- és újrahasznált edge-azonosítókat.

Nem készült második bind-algoritmus. Az edge-azonosságot, route-ID újrahasználást, portkardinalitást és domainvalidációt továbbra is a meglévő `binding.py` kezeli.

### 4.1. Több komponens és részleges fizikai hibák

Az elfogadott és tesztelt politika a meglévő bind-domain hatókörét követi:

- az `INCOMPLETE`, `INCOMPATIBLE`, `AMBIGUOUS`, `ILLEGAL` és `CYCLIC` komponens nem lesz bind-jelölt;
- egy unrelated hibás fizikai komponens nem akadályozza a független `COMPLETE` jelöltek bekötését;
- a hibás komponens discovery diagnosztikája megmarad a válaszban;
- a bindnek átadott összes `COMPLETE` jelölt egyetlen atomi egység;
- ha a bind preflight vagy domainvalidáció bármely átadott jelöltet elutasítja, egyik jelölt módosítása sem kerül a workspace-be.

Ez nem hallgatólagos részleges bind: a discovery előzetesen kizárja a nem beköthető komponenseket, a tényleges jelölthalmaz pedig együtt, atomikusan kerül a domainbe.

### 4.2. Üres jelölthalmaz

Ha nincs beköthető kapcsolat, az alkalmazás `422 NO_BINDABLE_CONNECTIONS` választ ad, és mellékeli a discovery DTO-t. A korábbi CK-04 pontosan egy START/egy STOP/legfeljebb egy wire cellás esetében a már elfogadott konkrét hibák (`MISSING_WIRE`, illetve a legacy orientációs/topológiai hiba) megmaradnak.

### 4.3. Sérült régi kapcsolat

Ha egy már létező GraphEdge fizikai útja megszakad:

- a discovery nem készít belőle új jelöltet;
- az explicit bind nem talál beköthető kapcsolatot;
- a régi GraphEdge és EdgeRoute változatlan marad;
- nincs automatikus `BROKEN`, edge-törlés vagy route-törlés.

## 5. WIRE_LAYOUT új szabályai

### 5.1. Megmaradó command-szerződés

- A parancs és payload formája változatlan.
- Egy parancs továbbra is pontosan egy pozíciót módosít.
- A request-idempotencia, epoch- és revision-fencing változatlan.
- Az azonos pozíciót tartalmazó batch továbbra is parserhiba; a többváltozásos parancs alkalmazási szinten továbbra sem támogatott.

Ez megőrzi a jelenlegi Fabric egycellás E–W parancsainak kompatibilitását, miközben egymás utáni parancsokkal többcellás hálózat építhető.

### 5.2. Elfogadott cellák

A placement már nem követeli meg, hogy:

- pontosan egy START és egy STOP legyen;
- a két port ugyanarra a cellára mutasson;
- a cella csak E–W `SINGLE` legyen.

Elfogadható minden meglévő domain-invariáns szerint konstruálható WireCell:

- 0–2 kapcsolatú `SINGLE`;
- egyenes vagy sarok;
- pontos `NS` + `EW` CROSS.

A parser és a `WireCell`/`WireChannelState` konstruktor továbbra is elutasítja a háromirányú SINGLE-t, hibás CROSS-t, ismeretlen csatornát vagy duplikált csatornát.

### 5.3. Stage- és foglaltsági szabályok

- Hozzáadás és eltávolítás csak Stage-határon belül történhet.
- WireCell nem helyezhető GraphNode pozíciójára.
- Eltávolítás egy programblokk pozícióján ártalmatlan no-op lehet; nem tekintjük wire-elhelyezési ütközésnek.
- Egy pozíció upsert vagy delete művelete immutable `replace_content()` átmenettel történik.

### 5.4. Nyitott és ellentmondásos DRAFT

A WIRE_LAYOUT szándékosan nem követel teljes, kölcsönösen összekötött útvonalat. Félbehagyott vagy egymással nem kompatibilis szomszédos csatornák mentetlen DRAFT részei lehetnek. A művelet:

- módosítja a working revisiont és dirty állapotot, ha a layout ténylegesen változik;
- authoritative state és discovery diagnosztikát ad vissza;
- nem futtat automatikus bindot;
- nem hoz létre vagy töröl GraphEdge-et;
- nem ír automatikus `BROKEN` route státuszt;
- nem módosít perzisztált vagy publikált állapotot.

## 6. Hibakezelés és atomikusság

| Hibatípus | Válaszhely | Módosítás történik? |
| --- | --- | --- |
| Fizikai nyitott/inkompatibilis/ambiguous/illegal/cyclic komponens | `discovery.diagnostics` | A layout igen, edge automatikusan nem |
| Bind domainhiba | felső szintű `diagnostics`, `GRAPH_OPERATION_REJECTED` | nem |
| Nincs bind-jelölt | `NO_BINDABLE_CONNECTIONS` + `discovery` | nem |
| Stale epoch/revision | alkalmazási `409` | nem |
| Hibás command/transport/auth | alkalmazási/transport error | nem |
| Stage-határon kívüli cella vagy node-ütközés | strukturált `422` | nem |

A discovery diagnosztika és a bind diagnosztika külön mezőben marad, így a kliens nem keveri össze a fizikai DRAFT állapotot a szemantikai bind elutasításával.

## 7. Teszteredmények

Futtatási környezet: `python-service/.venv`, `PYTHONPATH=src;tests`. Nem kellett PostgreSQL, HTTP-szerverprocessz vagy Minecraft.

### A2 + A3 célzott kapu

```text
python -m unittest -q \
  test_graph_discovery \
  test_graph_application_discovery \
  test_graph_application \
  test_graph_http_api

Ran 57 tests
OK
```

### Teljes Graph-regresszió

```text
python -m unittest discover -s tests -p "test_graph_*.py" -q
Ran 327 tests
OK
```

### Teljes Python unit suite

```text
python -m unittest discover -s tests -q
Ran 413 tests
OK
```

`git diff --check` nem jelzett whitespace-hibát. Gradle nem futott, mert az A3 kizárólag Python alkalmazási/API változás, Fabric forrás vagy asset nem változott, és nem történt deploy.

### Lefedett A3/1 esetek

- egy és több teljes kapcsolat discovery DTO-ja;
- félbehagyott kapcsolat strukturált diagnosztikája;
- CROSS-csatornák elkülönítése;
- read-only GET változatlansága;
- két jelölt egyetlen atomi bindban;
- ismételt bind edge- és route-ID újrahasználattal;
- sérült fizikai út mellett régi edge/route megőrzése;
- stale epoch/revision és idempotencia;
- szimulált többjelöltes bind-elutasítás teljes rollbackkel;
- CK-04 egycellás E–W bind regresszió.

### Lefedett A3/2 esetek

- többcellás egyenes egymást követő parancsokkal;
- sarok;
- CROSS két független csatornával;
- félbehagyott vezeték;
- cella hozzáadása és eltávolítása;
- kölcsönösen nem illeszkedő szomszédok diagnosztikája;
- Stage-határ és programblokk-ütközés;
- régi E–W SINGLE kompatibilitás;
- automatikus edge-létrehozás/-törlés tilalma;
- epoch/revision/request-id idempotencia.

## 8. Kompatibilitási kockázatok

1. A Python API additív mezőit a jelenlegi Fabric parser figyelmen kívül hagyja, de az A4-ben a Fabric DTO explicit bővítésekor a `discovery.schema_version` ellenőrzése szükséges.
2. A Fabric jelenlegi világprojektora továbbra is csak E–W SINGLE cellát jelenít meg. A3 szerveroldalon már elfogad olyan geometriát, amelyet a jelenlegi Fabric nem tud renderelni; production használat előtt A4 szükséges.
3. A layout egy cella/parancs miatt gyors egymásutánban stale revision újrapróbálást igényelhet. Többcellás batch csak külön, verziózott döntéssel kerüljön be.
4. Az unrelated hibás komponens melletti valid jelöltek bindolhatók. A kliensnek a sikeres bind mellett is meg kell jelenítenie a megmaradó discovery diagnosztikákat.
5. A discovery származtatott és nem perzisztált. Restart után a mentett WireCell-ekből újraszámolódik.
6. Nincs Junction, PUBLISH vagy új színmodell; a discovery státuszt nem szabad publikációs állapotként értelmezni.

## 9. CK-05/A4 Fabric-integrációs követelmények

Az A4 külön GO után:

1. bővítse a Fabric read DTO-t a verziózott `discovery` mezővel;
2. továbbra is a Python authoritative válaszát tekintse szemantikai forrásnak;
3. küldjön egy cella/parancs WIRE_LAYOUT műveleteket aktuális epoch/revisionnel, és stale válasznál reconciliáljon;
4. kezelje az egyenes, sarok és CROSS blockstate/model vetítést;
5. a `discovery.diagnostics` és a bind `diagnostics` felhasználói jelzését különítse el;
6. ne hozzon létre lokálisan GraphEdge-et, és ne következtessen PUBLISH állapotra;
7. sérüléskor ne töröljön edge-et/route-ot, csak a hiteles wire snapshotot vetítse;
8. őrizze meg a T8 authoritative Stage → Graph → Wire → SAVE-tábla reconciliation sorrendet;
9. tesztelje a CK-04 egycellás E–W kompatibilitást és a többcellás reconnectet;
10. production deploy/restart csak külön jóváhagyással történjen.

## 10. Következő lépés

Javasolt következő mérföldkő: **CK-05/A4 – Fabric többcellás wire DTO, blockstate és authoritative világprojekció**, kizárólag külön projektgazdai GO után.

A CK-05/A3 itt megáll; nem történt automatikus továbblépés A4-re.
