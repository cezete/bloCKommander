# CK-02 implementációs itiner – tiszta Python gráf-domainmag

- **Célműszak:** CK-02
- **Alap:** `docs/CK-01B-mini-ADR.md`
- **Jelleg:** új, izolált Python domainmodul és unit tesztek
- **Kifejezett határ:** nincs DB, Flask, Fabric, Minecraft, deploy vagy produkciós integráció

## 1. A CK-02 pontos scope-ja

A CK-02 feladata a Port/GraphEdge/Layout szerződés futtatható, tiszta Python reprezentációja. A műszak végén legyen lehetséges memóriabeli fixture-ökből:

- portokat definiálni és orientálni;
- GraphEdge-eket és wire route-okat reprezentálni;
- diagnosztikát determinisztikusan előállítani és rendezni;
- DRAFT/VALID és CLEAN/DIRTY állapotot számítani;
- előre felderített route-jelöltekből atomi ÉLBEKÖTÉST szimulálni;
- normál wire-bontást és ConnectionDestroyer törlést domain-szinten szimulálni;
- a teljes kötelező tesztmátrixot DB és Minecraft nélkül futtatni.

### 1.1 Benne van

- Enumok és fagyasztott értékobjektumok.
- A `(instance_id, port_key)` portazonosság.
- PortDefinition, GraphEdge, WireCell, EdgeRoute.
- Központi local-side → world-side transzformáció.
- Generikus node/port/edge/kardinalitás-validáció.
- A demonstrációhoz teszt-fixture portkatalógus és strukturális ruleset.
- Strukturált diagnosztika és stabil sorrend.
- Revízióhoz kötött validation report.
- Copy-on-write binding: siker vagy nulla tartalmi változás.
- Edge-ID megőrzése azonos végpontpárnál.
- GraphEdge megőrzése normál wire-bontáskor.
- Tiszta ConnectionDestroyer domainművelet.

### 1.2 Nincs benne

- `StageService` runtime-integráció.
- Wire-topológia felderítése Minecraft-blokkokból.
- PostgreSQL repository vagy migráció.
- HTTP endpoint.
- Kotlin/Fabric forrás vagy resource.
- Valódi CK Wire blokk.
- Játékbeli ÉLBEKÖTÉS gomb vagy állapotjelző tábla.
- Játékbeli ConnectionDestroyer item/UI.
- ModelNode, ModelRelation, NavigationContext, WorkspaceView-bővítés.

## 2. Konkrét fájl- és moduljavaslat

Új produkciós fájlok:

```text
python-service/src/hangar/graph/
├─ __init__.py
├─ model.py
├─ orientation.py
├─ diagnostics.py
├─ validation.py
├─ state.py
├─ binding.py
└─ destruction.py
```

Új tesztfájlok:

```text
python-service/tests/
├─ graph_fixtures.py
├─ test_graph_model.py
├─ test_graph_orientation.py
├─ test_graph_validation.py
├─ test_graph_binding.py
├─ test_graph_state.py
└─ test_graph_destruction.py
```

### 2.1 Felelősségek

| Fájl | Felelősség |
|---|---|
| `model.py` | enumok és fagyasztott domainmodellek; semmilyen I/O |
| `orientation.py` | az egyetlen local-side → világirány transzformáció |
| `diagnostics.py` | diagnosztikai kódok, report és determinisztikus sort key |
| `validation.py` | generikus invariánsok és injektálható strukturális ruleset |
| `state.py` | immutable workspace snapshot, revízió- és státuszszámítás |
| `binding.py` | precomputed route candidate-ek copy-on-write edge-bekötése |
| `destruction.py` | egyértelmű route/edge kijelölés és explicit törlési eredmény |
| `graph_fixtures.py` | fix UUID-k, demo node-ok/portok/route-ok; nem produkciós katalógus |

### 2.2 Meglévő fájlok, amelyeket CK-02-ben nem szabad módosítani

```text
python-service/src/hangar/stage.py
python-service/src/hangar/repository.py
python-service/src/ck_webhook.py
python-service/src/hangar/migrations/*
src/main/**
src/client/**
src/main/resources/**
```

Az izoláció szándékos: a domainmag elkészíthető anélkül, hogy a működő Stage CRUD viselkedése változna.

## 3. Minimális Python API-terv

Az alábbi aláírások irányadóak. A pontos importelrendezés változhat, a szemantika nem.

### 3.1 `model.py`

```python
from dataclasses import dataclass
from enum import Enum
from uuid import UUID

class CardinalDirection(Enum): ...
class LocalSide(Enum): ...
class PortKind(Enum): CONTROL = "CONTROL"
class PortDirection(Enum): IN = "IN"; OUT = "OUT"
class EdgeKind(Enum): CONTROL = "CONTROL"
class WireChannel(Enum): SINGLE = "SINGLE"; NS = "NS"; EW = "EW"
class RouteStatus(Enum): ...

@dataclass(frozen=True, order=True)
class GridPos:
    x: int
    y: int
    z: int

@dataclass(frozen=True, order=True)
class PortRef:
    instance_id: UUID
    port_key: str

@dataclass(frozen=True)
class PortDefinition:
    port_key: str
    port_kind: PortKind
    direction: PortDirection
    local_side: LocalSide
    ordinal: int
    min_edges: int
    max_edges: int
    branch_label: str | None = None

@dataclass(frozen=True)
class GraphNode:
    # Adaptermodell a meglévő stage_blocks rekordhoz, nem új perzisztens node.
    instance_id: UUID
    stage_id: UUID
    type_key: str
    position: GridPos
    orientation: CardinalDirection

@dataclass(frozen=True)
class GraphEdge: ...
@dataclass(frozen=True)
class WireChannelState: ...
@dataclass(frozen=True)
class WireCell: ...
@dataclass(frozen=True)
class WireStep: ...
@dataclass(frozen=True)
class EdgeRoute: ...
```

A `GraphNode` itt csak immutable adapter/tesztérték a meglévő `stage_blocks` adataihoz. Nem engedélyez új DB-táblát vagy második node-életciklust.

### 3.2 `orientation.py`

```python
def world_side(
    facing: CardinalDirection,
    local_side: LocalSide,
) -> CardinalDirection: ...

def adjacent_position(
    position: GridPos,
    direction: CardinalDirection,
) -> GridPos: ...

def port_connection_position(
    node: GraphNode,
    definition: PortDefinition,
) -> GridPos: ...
```

Nem lehet második orientációs tábla a validatorban vagy binding modulban.

### 3.3 `diagnostics.py`

```python
class Severity(Enum): ERROR = 0; WARNING = 1; INFO = 2
class DiagnosticCode(Enum): ...

@dataclass(frozen=True)
class Diagnostic: ...

@dataclass(frozen=True)
class ValidationReport:
    revision: int
    diagnostics: tuple[Diagnostic, ...]

    @property
    def has_blocking_errors(self) -> bool: ...

def sort_diagnostics(items: Iterable[Diagnostic]) -> tuple[Diagnostic, ...]: ...
```

A `DiagnosticCode` enum deklarációs sorrendje része a stabil tesztszerződésnek.

### 3.4 `state.py`

```python
class GraphStatus(Enum): DRAFT = "DRAFT"; VALID = "VALID"
class SaveStatus(Enum): CLEAN = "CLEAN"; DIRTY = "DIRTY"
class EdgeIndicatorStatus(Enum):
    CHECK_REQUIRED = "CHECK_REQUIRED"
    OK = "OK"
    ERRORS = "ERRORS"

@dataclass(frozen=True)
class GraphWorkspaceState:
    stage_id: UUID
    nodes: tuple[GraphNode, ...]
    edges: tuple[GraphEdge, ...]
    wire_cells: tuple[WireCell, ...]
    routes: tuple[EdgeRoute, ...]
    working_revision: int
    validated_revision: int | None
    saved_version: int
    dirty: bool
    last_validation: ValidationReport | None

    @property
    def graph_status(self) -> GraphStatus: ...
    @property
    def save_status(self) -> SaveStatus: ...
    @property
    def indicator_status(self) -> EdgeIndicatorStatus: ...
```

Tiszta állapotátmenetek:

```python
def replace_content(state, *, nodes=None, edges=None, wire_cells=None, routes=None):
    """Eltérő tartalom esetén revision+1, dirty=True; validáció elavul."""

def record_validation(state, report, *, validated: bool): ...

def mark_saved(state, new_saved_version: int):
    """dirty=False; working_revision változatlan."""
```

### 3.5 `validation.py`

```python
PortCatalog = Mapping[str, Mapping[str, PortDefinition]]

class StructuralRule(Protocol):
    def validate(self, context: ValidationContext) -> Iterable[Diagnostic]: ...

def validate_graph(
    state: GraphWorkspaceState,
    catalog: PortCatalog,
    rules: Sequence[StructuralRule] = (),
) -> ValidationReport: ...
```

Generikus ellenőrzési sorrend:

1. node-index és stage-konzisztencia;
2. portlétezés;
3. irány és kind;
4. duplicate edge;
5. portkardinalitás;
6. route/cross/junction állapot;
7. strukturális ruleset (IF/END_IF, elérhetőség, ciklus);
8. diagnosztika stabil rendezése.

Ha egy korai hiba miatt egy későbbi ellenőrzés nem biztonságos, a validator ne dobjon kivételt. Adja vissza a korai diagnosztikát és hagyja ki az abból nem értelmezhető további hibát.

### 3.6 `binding.py`

A CK-02 nem derít fel topológiát Minecraftból. Előre elkészített route candidate-eket fogad.

```python
@dataclass(frozen=True)
class RouteCandidate:
    source_port: PortRef | None
    target_port: PortRef | None
    steps: tuple[WireStep, ...]
    status: RouteStatus

@dataclass(frozen=True)
class BindingResult:
    applied: bool
    state: GraphWorkspaceState
    diagnostics: tuple[Diagnostic, ...]

def bind_edges(
    state: GraphWorkspaceState,
    expected_revision: int,
    candidates: Sequence[RouteCandidate],
    catalog: PortCatalog,
    rules: Sequence[StructuralRule],
    edge_id_factory: Callable[[], UUID],
    route_id_factory: Callable[[], UUID],
) -> BindingResult: ...
```

Kötelező működés:

- stale revision → `applied=False`, `STALE_WORKING_REVISION`;
- ismert végpontkulcs → meglévő edge ID;
- új végpontkulcs → injektált ID factory;
- régi edge automatikusan nem törölhető;
- teljes jelölt állapot validálása alkalmazás előtt;
- blokkoló hiba → eredeti tartalom, nincs részleges edge-halmaz;
- siker → egyetlen új immutable state;
- ha csak validáció történt és tartalom azonos, revision nem nő;
- sikeres report a resulting revisionhöz kötött.

Az ID factory injektálása teszi determinisztikussá a teszteket.

### 3.7 `destruction.py`

```python
@dataclass(frozen=True)
class ConnectionSelection:
    stage_id: UUID
    wire_position: GridPos
    channel: WireChannel | None
    expected_revision: int

@dataclass(frozen=True)
class DestructionPreview:
    allowed: bool
    edge_id: UUID | None
    route_id: UUID | None
    affected_steps: tuple[WireStep, ...]
    cell_changes: tuple[CellChange, ...]
    diagnostics: tuple[Diagnostic, ...]

def preview_connection_destruction(state, selection) -> DestructionPreview: ...

def confirm_connection_destruction(
    state,
    preview,
    expected_revision,
) -> GraphWorkspaceState: ...
```

A CK-02-ben a confirmation token kriptográfiai/HTTP megvalósítása nem kell. A tesztelt szerződés: az előnézet célja és a revízió változatlan legyen a megerősítésig.

A `CellChange` egy koordinátához vagy `resulting_cell=None` törlést, vagy a törlés után megmaradó `WireCell` állapotot rendel. CROSS egyik csatornájának törlésekor a másik csatorna megmarad, ezért ilyen koordinátán nem teljes cellatörlés, hanem alakváltás az eredmény.

## 4. Port- és gráfadatstruktúrák

### 4.1 Demonstrációs fixture-katalógus

A produkciós katalógus CK-03 feladat. A CK-02 tesztjeiben az alábbi pontos fixture használható:

| Típus | Port | Kind | Irány | Local side | Ordinal | Min | Max | Branch label |
|---|---|---|---|---|---:|---:|---:|---|
| START | `out` | CONTROL | OUT | FRONT | 0 | 1 | 1 | – |
| IF | `in` | CONTROL | IN | BACK | 0 | 1 | 1 | – |
| IF | `true` | CONTROL | OUT | RIGHT | 0 | 1 | 1 | `1` |
| IF | `false` | CONTROL | OUT | LEFT | 0 | 1 | 1 | `0` |
| SET | `in` | CONTROL | IN | BACK | 0 | 1 | 1 | – |
| SET | `out` | CONTROL | OUT | FRONT | 0 | 1 | 1 | – |
| PRINT | `in` | CONTROL | IN | BACK | 0 | 1 | 1 | – |
| PRINT | `out` | CONTROL | OUT | FRONT | 0 | 1 | 1 | – |
| END_IF | `in_true` | CONTROL | IN | RIGHT | 0 | 1 | 1 | `1` |
| END_IF | `in_false` | CONTROL | IN | LEFT | 0 | 1 | 1 | `0` |
| END_IF | `out` | CONTROL | OUT | FRONT | 0 | 1 | 1 | – |
| STOP | `in` | CONTROL | IN | BACK | 0 | 1 | 1 | – |

A `local_side` vizuális alapérték, amelyet CK-04 előtt még össze kell vetni a végleges blokkmodellekkel. A `port_key`, irány, kardinalitás és branch label már szemantikai szerződés.

### 4.2 Demonstrációs edge-halmaz

```text
START.out   -> IF.in
IF.true     -> SET.in
SET.out     -> END_IF.in_true
IF.false    -> PRINT.in
PRINT.out   -> END_IF.in_false
END_IF.out  -> STOP.in
```

Az END_IF bemenetek ágprovenienciáját strukturális ruleset ellenőrzi; a két IN port nem cserélhető fel.

### 4.3 Fixture-azonosítók

A tesztek fix, olvasható UUID-ket használjanak, például:

```text
stage: 00000000-0000-0000-0000-000000000100
START: 00000000-0000-0000-0000-000000000101
IF:    00000000-0000-0000-0000-000000000102
SET:   00000000-0000-0000-0000-000000000103
PRINT: 00000000-0000-0000-0000-000000000104
END_IF:00000000-0000-0000-0000-000000000105
STOP:  00000000-0000-0000-0000-000000000106
```

Random UUID ne szerepeljen assertelt diagnosztikában.

## 5. Kötelező tesztmátrix

Minden teszt DB-, Flask-, psycopg2-, Fabric- és Minecraft-import nélkül fusson.

| # | Javasolt tesztnév | Fixture/művelet | Elvárt eredmény |
|---:|---|---|---|
| 1 | `test_complete_demo_is_valid` | teljes hatnode-os, hatélű demo, valid route-ok | `VALID`, nincs ERROR, indicator `OK` |
| 2 | `test_missing_if_true_branch_is_draft` | `IF.true` edge hiányzik | `DRAFT`, `MISSING_IF_BRANCH` vagy kardinalitási diagnosztika stabilan |
| 3 | `test_missing_if_false_branch_is_draft` | `IF.false` edge hiányzik | `DRAFT`, `MISSING_IF_BRANCH` |
| 4 | `test_out_to_out_is_rejected` | `START.out -> IF.true` | `INVALID_PORT_DIRECTION` |
| 5 | `test_in_to_in_is_rejected` | `IF.in -> STOP.in` | `INVALID_PORT_DIRECTION` |
| 6 | `test_two_edges_to_single_in_are_rejected` | két source ugyanarra a `STOP.in` portra | `PORT_CARDINALITY_VIOLATION` |
| 7 | `test_unknown_node_is_rejected` | edge egyik UUID-je nincs a node-indexben | `UNKNOWN_NODE`, nincs kivétel |
| 8 | `test_unknown_port_is_rejected` | létező node, hibás `port_key` | `UNKNOWN_PORT` |
| 9 | `test_cross_stage_edge_is_rejected` | target node más Stage-ből | `CROSS_STAGE_EDGE` |
| 10 | `test_open_wire_keeps_graph_draft` | route csak egy portot ér el | `OPEN_WIRE`, `DRAFT` |
| 11 | `test_broken_route_preserves_edge` | létező edge route-jából középső cella törölve | azonos edge tuple és ID; `BROKEN_ROUTE`; `DRAFT` |
| 12 | `test_repaired_route_preserves_edge_id` | #11 után azonos végpontok új route-tal | siker; eredeti `edge_id`; route változhat |
| 13 | `test_cross_ns_and_ew_are_independent` | ugyanazon cella NS és EW útvonal | két külön út, nincs csatornaváltás, nincs duplicate/junction |
| 14 | `test_t_junction_is_rejected` | SINGLE cella három kapcsolattal | `ILLEGAL_JUNCTION` |
| 15 | `test_four_rotations_preserve_port_keys` | ugyanaz a node NORTH/EAST/SOUTH/WEST | port key azonos; fizikai oldal a táblázat szerint forog |
| 16 | `test_reroute_preserves_logical_graph` | minden route geometriája változik, végpontok nem | edge-halmaz és edge ID-k azonosak |
| 17 | `test_connection_destroyer_removes_edge_and_route` | egyértelmű bound route + megerősítés | kiválasztott edge/route/cellák eltűnnek; revision+1; DIRTY/DRAFT |
| 18 | `test_failed_binding_is_atomic` | egyik route jó, másik ambiguous | `applied=False`; teljes korábbi edge/route tartalom azonos |
| 19 | `test_successful_binding_applies_complete_set` | minden route valid | `applied=True`; teljes konzisztens edge-halmaz egy állapotban |
| 20 | `test_draft_can_be_marked_saved` | DRAFT+DIRTY, `mark_saved(version+1)` | DRAFT+CLEAN; revision változatlan; saved_version nő |
| 21 | `test_valid_mutation_requires_revalidation` | VALID state wire/node/edge módosítása | revision+1; DRAFT; indicator `CHECK_REQUIRED` |

### 5.1 További kötelező kontrakttesztek

| Teszt | Elvárt eredmény |
|---|---|
| `test_save_does_not_increment_working_revision` | `mark_saved` csak `saved_version`/dirty mezőt változtat |
| `test_failed_validation_records_error_indicator_without_content_change` | indicator `ERRORS`, node/edge/layout változatlan |
| `test_stale_binding_revision_is_rejected` | `STALE_WORKING_REVISION`, `applied=False` |
| `test_diagnostics_have_deterministic_order` | bemeneti sorrendtől független azonos tuple |
| `test_duplicate_edge_is_rejected` | `DUPLICATE_EDGE` |
| `test_invalid_cross_channel_is_rejected` | `INVALID_CROSS_CHANNEL` |
| `test_cross_without_channel_is_ambiguous_for_destroyer` | törlés elutasítva, nincs állapotváltozás |
| `test_destroying_one_cross_channel_preserves_the_other` | a kijelölt edge/route megszűnik, a másik csatorna és route megmarad; cella-diff alakváltást kér |
| `test_unbound_route_destroyer_removes_only_layout` | edge-halmaz változatlan |
| `test_existing_unrouted_edge_is_not_silently_deleted_by_binding` | régi edge megmarad; blocking route diagnosztika |
| `test_same_content_revalidation_does_not_increment_revision` | revision változatlan, validated_revision beáll |

### 5.2 IF/END_IF szabálytesztek

A CK-02 fixture rulesetnek legalább ezeket kell igazolnia; CK-03-ban ugyanez kerül produkciós katalógusba/rulesetbe:

- `IF.true` csak az END_IF `in_true` ágprovenienciájába futhat.
- `IF.false` csak az END_IF `in_false` ágprovenienciájába futhat.
- Felcserélt merge portok → `INVALID_IF_MERGE`.
- START-ból el nem érhető node → `UNREACHABLE_NODE`.
- Nem támogatott visszaél/ciklus → `ILLEGAL_CYCLE`.

## 6. Implementációs sorrend a CK-02 műszakon belül

1. `model.py`: enumok és dataclassok, konstruktor-invariánsok.
2. `orientation.py`: 16 elemű forgatási táblateszt.
3. `diagnostics.py`: kódok, report, rendezés.
4. `state.py`: státuszszámítás és immutable átmenetek.
5. `validation.py`: generikus index-, port-, irány-, kind-, duplicate- és cardinality szabályok.
6. Teszt-fixture demo catalog + strukturális ruleset.
7. `binding.py`: copy-on-write candidate build, ID-megőrzés, atomic apply.
8. `destruction.py`: bound/unbound/ambiguous explicit törlés.
9. Teljes célzott és regressziós Python tesztfuttatás.

Minden lépés után a célzott tesztfájl fusson; ne várjon a teljes modul elkészültére.

## 7. Elfogadási kritériumok

A CK-02 kész, ha mind teljesül:

- [ ] Csak új `hangar/graph` és `test_graph_*`/fixture fájlok változtak.
- [ ] Nincs import Flaskból, psycopg2-ből, mcpi-ből vagy Minecraft/Fabric kódból.
- [ ] A `GraphNode` egyértelműen a `stage_blocks` adaptere, nem új perzisztens modell.
- [ ] A portazonosság kizárólag `(instance_id, port_key)`.
- [ ] Az orientációtranszformáció egyetlen implementációban él.
- [ ] GraphEdge ID független a route-tól.
- [ ] Normál wire-bontás megőrzi a GraphEdge-et.
- [ ] Sikertelen binding nem hagy részleges edge- vagy route-halmazt.
- [ ] Azonos végpontpár újrahuzalozása megőrzi az edge ID-t.
- [ ] CROSS NS/EW bejárása független.
- [ ] JUNCTION elutasított.
- [ ] DRAFT menthetősége modellezett.
- [ ] SAVE-szimuláció nem növeli a `working_revision` értéket.
- [ ] VALID kizárólag az aktuális `working_revision` értékhez tartozhat.
- [ ] Diagnosztika determinisztikus és strukturált.
- [ ] A kötelező tesztmátrix minden sora önálló teszttel vagy egyértelmű parametrizált esettel lefedett.
- [ ] Minden korábbi Python teszt változatlanul sikeres.

## 8. Futtatandó tesztparancsok

A projekt gyökeréből, Windows PowerShellben:

```powershell
$env:PYTHONPATH="$PWD\python-service\src"
& "$PWD\python-service\.venv\Scripts\python.exe" -m unittest discover `
  -s python-service/tests `
  -p "test_graph_*.py" `
  -v
```

Teljes Python regresszió:

```powershell
$env:PYTHONPATH="$PWD\python-service\src"
& "$PWD\python-service\.venv\Scripts\python.exe" -m unittest discover `
  -s python-service/tests `
  -v
```

Importfüggőség ellenőrzése:

```powershell
rg -n "flask|psycopg2|mcpi|minecraft|fabric" `
  python-service/src/hangar/graph `
  python-service/tests/test_graph_*.py
```

Az utolsó parancs kimenete üres legyen, kivéve ha tesztleíró szövegben szerepel a tiltott dependency neve; ilyen szöveget is célszerű kerülni.

CK-02-ben Gradle build nem kötelező, mert Kotlin/resource nem változhat. A teljes következő integrációs műszak előtt azonban a szokásos `buildAll` regresszió ismét szükséges lesz.

## 9. Várható kimenet

### 9.1 Tesztkimenet

```text
test_complete_demo_is_valid ... ok
...
test_same_content_revalidation_does_not_increment_revision ... ok

Ran <N> tests in <idő>
OK
```

`<N>` legalább a 21 kötelező és a 10 kiegészítő kontrakteset lefedését jelentse. Paraméterezés nélküli `unittest` esetén legalább 31 külön teszt várható. Ennél kevesebb csak akkor elfogadható, ha egyértelműen dokumentált parametrizált teszt minden táblázatsort külön subtestként futtat.

### 9.2 Domain eredmény példa

Egy teljes demonstráció validációja:

```text
graph_status       = VALID
save_status        = DIRTY vagy CLEAN, a fixture szerint
indicator_status   = OK
validated_revision = working_revision
diagnostics        = ()
```

Egy megszakított meglévő edge:

```text
graph_status       = DRAFT
edge_id            = változatlan
route_status       = BROKEN
diagnostic.code    = BROKEN_ROUTE
```

Egy sikertelen binding:

```text
applied            = False
edges/routes       = az eredeti state-tel érték szerint azonos
indicator_status   = ERRORS az aktuális ellenőrzött revízión
```

## 10. Függőségek és továbbadás CK-03-nak

### 10.1 Előfeltételek

- `docs/CK-01B-mini-ADR.md` elfogadása.
- Python 3.12.
- A meglévő `unittest` infrastruktúra.
- Külső új dependency nem szükséges és nem engedélyezett.

### 10.2 CK-03-nak átadandó stabil elemek

- domain enumok és dataclassok;
- orientáció API;
- diagnosztikai kódok és sorrend;
- `GraphWorkspaceState` státuszszámítása;
- generikus validációs pipeline;
- binding/destroyer tiszta tranzakciós viselkedése;
- demonstrációs fixture-ek által bizonyított portkatalógus-szerződés.

CK-03 feladata a fixture-katalógust produkciós katalógussá tenni és a strukturális validátort a későbbi Stage adapter számára használhatóvá tenni. CK-03 továbbra sem futtat DB-migrációt.

## 11. Rollback és visszaállítás

A CK-02 izolált, ezért rollbackje egyszerű:

1. Az új `python-service/src/hangar/graph/` fájlok eltávolítása.
2. Az új `graph_fixtures.py` és `test_graph_*.py` fájlok eltávolítása.
3. A meglévő Python regressziós tesztek újrafuttatása.

Nem szükséges DB-, világ-, mod-JAR- vagy szolgáltatás-visszaállítás, mert CK-02 ezek egyikét sem érintheti.

## 12. Kifejezetten tiltott módosítások

A CK-02 műszakban tilos:

- a 001–005 migrációk szerkesztése vagy új migráció futtatása;
- `stage_blocks` mellett új GraphNode perzisztens modell létrehozása;
- `stage.py`, `repository.py` vagy `ck_webhook.py` runtime-integrációja;
- Kotlin, Fabric resource vagy Gradle módosítása;
- CK Wire játékbeli blokk implementálása;
- BlockEntity bevezetése;
- HTTP végpont létrehozása;
- PostgreSQL vagy Minecraft elérése tesztből;
- szolgáltatás indítása/leállítása vagy deploy;
- ModelNode, ModelRelation, Annotation, NavigationContext vagy több WorkspaceView bevezetése;
- automatikus edge-létrehozás puszta wire-lerakásból;
- automatikus edge-törlés normál wire-bontásból;
- SAVE és ÉLBEKÖTÉS összevonása;
- random UUID-k használata determinisztikus tesztassertben.

## 13. Első, legkisebb ellenőrizhető kódmódosítás

Az első commit/műszakrész kizárólag ezt tartalmazza:

```text
python-service/src/hangar/graph/__init__.py
python-service/src/hangar/graph/model.py
python-service/src/hangar/graph/orientation.py
python-service/tests/test_graph_model.py
python-service/tests/test_graph_orientation.py
```

Elfogadása:

- a domainmodellek konstruktor-invariánsai teszteltek;
- a négy FACING × négy LocalSide teljes 16 esetes transzformációs mátrixa sikeres;
- nincs I/O vagy külső dependency;
- a meglévő rendszer egyetlen futási útja sem importálja még az új csomagot.

Ez a legkisebb lépés már rögzíti a portok és orientáció alapvető szemantikáját, miközben nulla hatással van a működő rendszerre.
