# CK-03/B0 – Graph snapshot és PostgreSQL adatmodell

- Dátum: 2026-10-23
- Státusz: implementáció előtti szerződés- és DDL-tervezet
- Hatókör: teljes Stage snapshot, normalizált PostgreSQL-reprezentáció, portkatalógus és tesztterv
- Nem része: migráció, produkciós Python-kód, endpoint, Fabric vagy világmódosítás

## 1. Kiinduló állapot

**Megfigyelt tény.** A jelenlegi `StageWorkspace` mezői: `stage`, `blocks`, `saved_version`, `dirty`, `revision`, `lock` (`python-service/src/hangar/stage.py:16-22`). A `blocks` módosítható dictionary-lista; külön graph snapshot nincs.

**Megfigyelt tény.** A `stage_blocks.instance_id` a stabil GraphNode-azonosító. A tábla megőrzi a típust, relatív koordinátát, orientációt, címkét és leírást (`python-service/src/hangar/migrations/004_program_stages.sql:35-50`).

**Megfigyelt tény.** A repository jelenleg csak a Stage-blokkok teljes halmazát tölti és menti. A SAVE a `stages` sort `FOR UPDATE` zárolja, összeveti a `saved_version` értéket, törli/újraírja a blokkokat, majd növeli a verziót (`python-service/src/hangar/repository.py:184-198`).

**Megfigyelt tény.** A graph-domain a következő perzisztens tartalmakat különíti el:

- `GraphNode`: Stage-blokk vetülete;
- `GraphEdge`: stabil logikai kapcsolat;
- `EdgeRoute`: opcionálisan edge-hez kötött fizikai útvonal;
- `WireCell`: SINGLE vagy független NS/EW CROSS csatornák;
- `WireStep`: sorrendezett pozíció és channel;
- `working_revision`, `validated_revision`, `saved_version`, dirty és aktuális validation report.

**Megfigyelt eltérés.** A régi `StageService.save()` a sikeres SAVE végén `revision += 1` műveletet végez. A CK-02/03 cél-szemantikája szerint SAVE nem tartalomváltozás, ezért a working revision nem nőhet.

## 2. Elfogadott tervezési döntések

1. A StageWorkspace az egyetlen hiteles, process-lokális szerkesztési munkapéldány.
2. A GraphWorkspaceState tranzitív, immutable domainnézet és domaineredmény; nem külön registry.
3. A StageWorkspace egyetlen, atomikusan lecserélhető teljes `StageSnapshot` objektumot tartson.
4. A snapshot blokkelemei együtt hordozzák a GraphNode mezőit és a nem gráfos mezőket. Nem készül külön, eltérhető node-lista.
5. PostgreSQL-ben a `stage_blocks` marad a GraphNode tárolási alapja; `graph_nodes` tábla nem készül.
6. Edge, route, route-step és wire normalizált táblákban tárolódik.
7. A részletes diagnosztika és validation report nem perzisztálódik; REBUILD után újraszámítandó.
8. A portkatalógus első verzióban verziózott Python-konfiguráció.
9. DRAFT, hibás és BROKEN graph snapshot is menthető, ezért a DB nem kényszeríthet ki minden domain-validációs invariánst.
10. SAVE egyetlen tranzakcióban cseréli a blokk- és graph-snapshotot.

## 3. Logikai adatmodell

```text
stages
 ├─ 1 ── N stage_blocks              # GraphNode + label/description
 ├─ 1 ── N graph_edges               # stabil logikai kapcsolatok
 ├─ 1 ── N edge_routes               # bound vagy unbound route
 │          └─ 1 ── N route_steps    # rendezett fizikai útvonal
 └─ 1 ── N wire_cells
            └─ 1 ── 1/2 wire_channels
```

### 3.1 GraphEdge

- `edge_id` globálisan stabil UUID primary key.
- A szemantikus uniqueness: Stage + source node/port + target node/port + edge-kind.
- Route újrarajzolásakor a `graph_edges` sor változatlan marad.
- A source és target blokk ugyanazon Stage `stage_blocks` soraira hivatkozik.

### 3.2 EdgeRoute

- `route_id` stabil UUID primary key.
- `edge_id` nullable: unbound fizikai route tárolható.
- Source/target PortRef mindkét fele nullable, hogy INCOMPLETE route is veszteségmentesen tárolható legyen.
- `route_status` a domain összes értékét támogatja: `INCOMPLETE`, `VALID`, `BROKEN`, `AMBIGUOUS`, `ILLEGAL`.
- Több route ugyanahhoz az edge-hez DB-szinten nincs megtiltva: a domain ezt diagnosztizálható hibás DRAFT állapotként reprezentálja.

### 3.3 RouteStep

- Primary key: `(route_id, step_index)`.
- A sorrend explicit és nullától induló, hézagmentes kell legyen; a hézagmentességet a repository snapshot-validáció ellenőrzi.
- A pozíció Stage-relatív.
- A channel `SINGLE`, `NS` vagy `EW`.
- Szándékosan nincs FK a WireCell/WireChannel rekordra. Enélkül menthető az a BROKEN route, amelynek egy wire cellája hiányzik, vagy amely hibás csatornára hivatkozik. Ezeket REBUILD után a graph-validátor jelzi.

### 3.4 WireCell és WireChannel

- Egy cella Stage + relatív koordináta alapján azonosított.
- `cell_kind`: `SINGLE` vagy `CROSS`.
- SINGLE cellához pontosan egy SINGLE channel tartozik, legfeljebb két kapcsolatiránnyal.
- CROSS cellához pontosan NS és EW channel tartozik. NS kapcsolatai N/S, EW kapcsolatai E/W.
- A kapcsolatok négybites maszkban tárolhatók: N=1, E=2, S=4, W=8.
- Unbound WireCell megengedett; route nélkül is a fizikai draft része.

## 4. PostgreSQL DDL-tervezet

Az alábbi egy jövőbeli, számozott additív migráció tervezete. Ebben a műszakban nem került futtatásra vagy migrációs fájlba.

```sql
-- Snapshot format and port semantics used by the saved graph.
ALTER TABLE blockcommander.stages
    ADD COLUMN graph_snapshot_version integer NOT NULL DEFAULT 0,
    ADD COLUMN port_catalog_version varchar(64);

-- Enables same-Stage composite foreign keys without adding a graph_nodes table.
ALTER TABLE blockcommander.stage_blocks
    ADD CONSTRAINT stage_blocks_stage_instance_unique
    UNIQUE (stage_id, instance_id);

CREATE TABLE blockcommander.graph_edges (
    edge_id uuid PRIMARY KEY,
    stage_id uuid NOT NULL
        REFERENCES blockcommander.stages(stage_id) ON DELETE CASCADE,
    source_instance_id uuid NOT NULL,
    source_port_key varchar(64) NOT NULL
        CHECK (source_port_key ~ '^[A-Za-z][A-Za-z0-9_]*$'),
    target_instance_id uuid NOT NULL,
    target_port_key varchar(64) NOT NULL
        CHECK (target_port_key ~ '^[A-Za-z][A-Za-z0-9_]*$'),
    edge_kind varchar(32) NOT NULL
        CHECK (edge_kind IN ('CONTROL')),
    CONSTRAINT graph_edges_source_node_fk
        FOREIGN KEY (stage_id, source_instance_id)
        REFERENCES blockcommander.stage_blocks(stage_id, instance_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_edges_target_node_fk
        FOREIGN KEY (stage_id, target_instance_id)
        REFERENCES blockcommander.stage_blocks(stage_id, instance_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_edges_semantic_unique UNIQUE
        (stage_id, source_instance_id, source_port_key,
         target_instance_id, target_port_key, edge_kind),
    CONSTRAINT graph_edges_stage_edge_unique UNIQUE (stage_id, edge_id)
);

CREATE INDEX graph_edges_source_idx
    ON blockcommander.graph_edges(stage_id, source_instance_id);
CREATE INDEX graph_edges_target_idx
    ON blockcommander.graph_edges(stage_id, target_instance_id);

CREATE TABLE blockcommander.edge_routes (
    route_id uuid PRIMARY KEY,
    stage_id uuid NOT NULL
        REFERENCES blockcommander.stages(stage_id) ON DELETE CASCADE,
    edge_id uuid,
    source_instance_id uuid,
    source_port_key varchar(64),
    target_instance_id uuid,
    target_port_key varchar(64),
    route_status varchar(16) NOT NULL
        CHECK (route_status IN
            ('INCOMPLETE','VALID','BROKEN','AMBIGUOUS','ILLEGAL')),
    CONSTRAINT edge_routes_source_pair_check CHECK
        ((source_instance_id IS NULL) = (source_port_key IS NULL)),
    CONSTRAINT edge_routes_target_pair_check CHECK
        ((target_instance_id IS NULL) = (target_port_key IS NULL)),
    CONSTRAINT edge_routes_source_key_check CHECK
        (source_port_key IS NULL OR
         source_port_key ~ '^[A-Za-z][A-Za-z0-9_]*$'),
    CONSTRAINT edge_routes_target_key_check CHECK
        (target_port_key IS NULL OR
         target_port_key ~ '^[A-Za-z][A-Za-z0-9_]*$'),
    CONSTRAINT edge_routes_edge_fk
        FOREIGN KEY (stage_id, edge_id)
        REFERENCES blockcommander.graph_edges(stage_id, edge_id)
        ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT edge_routes_source_node_fk
        FOREIGN KEY (stage_id, source_instance_id)
        REFERENCES blockcommander.stage_blocks(stage_id, instance_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT edge_routes_target_node_fk
        FOREIGN KEY (stage_id, target_instance_id)
        REFERENCES blockcommander.stage_blocks(stage_id, instance_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT edge_routes_stage_route_unique UNIQUE (stage_id, route_id)
);

CREATE INDEX edge_routes_stage_idx
    ON blockcommander.edge_routes(stage_id);
CREATE INDEX edge_routes_edge_idx
    ON blockcommander.edge_routes(stage_id, edge_id);

CREATE TABLE blockcommander.route_steps (
    route_id uuid NOT NULL,
    stage_id uuid NOT NULL,
    step_index integer NOT NULL CHECK (step_index >= 0),
    rel_x integer NOT NULL,
    rel_y integer NOT NULL,
    rel_z integer NOT NULL,
    channel varchar(8) NOT NULL
        CHECK (channel IN ('SINGLE','NS','EW')),
    PRIMARY KEY (route_id, step_index),
    CONSTRAINT route_steps_route_fk
        FOREIGN KEY (stage_id, route_id)
        REFERENCES blockcommander.edge_routes(stage_id, route_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX route_steps_stage_position_idx
    ON blockcommander.route_steps(stage_id, rel_x, rel_y, rel_z);

CREATE TABLE blockcommander.wire_cells (
    stage_id uuid NOT NULL
        REFERENCES blockcommander.stages(stage_id) ON DELETE CASCADE,
    rel_x integer NOT NULL,
    rel_y integer NOT NULL,
    rel_z integer NOT NULL,
    cell_kind varchar(8) NOT NULL
        CHECK (cell_kind IN ('SINGLE','CROSS')),
    PRIMARY KEY (stage_id, rel_x, rel_y, rel_z),
    CONSTRAINT wire_cells_stage_position_kind_unique UNIQUE
        (stage_id, rel_x, rel_y, rel_z, cell_kind)
);

CREATE TABLE blockcommander.wire_channels (
    stage_id uuid NOT NULL,
    rel_x integer NOT NULL,
    rel_y integer NOT NULL,
    rel_z integer NOT NULL,
    cell_kind varchar(8) NOT NULL,
    channel varchar(8) NOT NULL,
    connections_mask smallint NOT NULL CHECK (connections_mask BETWEEN 0 AND 15),
    PRIMARY KEY (stage_id, rel_x, rel_y, rel_z, channel),
    CONSTRAINT wire_channels_cell_fk
        FOREIGN KEY (stage_id, rel_x, rel_y, rel_z, cell_kind)
        REFERENCES blockcommander.wire_cells
            (stage_id, rel_x, rel_y, rel_z, cell_kind)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT wire_channels_shape_check CHECK (
        (cell_kind = 'SINGLE' AND channel = 'SINGLE'
            AND bit_count(connections_mask::bit(4)) <= 2)
        OR
        (cell_kind = 'CROSS' AND channel = 'NS' AND connections_mask = 5)
        OR
        (cell_kind = 'CROSS' AND channel = 'EW' AND connections_mask = 10)
    )
);

CREATE INDEX wire_channels_stage_idx
    ON blockcommander.wire_channels(stage_id);
```

### 4.1 Cell/channel cardinalitás

A fenti sor-szintű constraint nem tudja önmagában bizonyítani, hogy egy SINGLE cellához pontosan egy SINGLE sor, CROSS cellához pedig pontosan NS és EW sor tartozik. A jövőbeli migrációhoz DEFERRABLE constraint trigger szükséges, amely tranzakció végén ellenőrzi:

```text
SINGLE → channel-set pontosan {SINGLE}
CROSS  → channel-set pontosan {NS, EW}
```

Alternatíva a channel-adatok oszlopokba sűrítése a `wire_cells` táblán, de ez kevésbé közvetlenül követi a domain `WireChannelState` modelljét. A normalizált `wire_channels` + deferred trigger az ajánlott megoldás.

### 4.2 Tudatosan alkalmazási rétegben maradó invariánsok

- route step indexek hézagmentesek és nullától indulnak;
- bound route endpointjai megegyeznek a hivatkozott edge endpointjaival;
- route step által jelölt WireCell/channel ténylegesen létezik;
- route lépések fizikailag szomszédosak és kétirányúan kapcsoltak;
- egy edge több route-ja vagy megosztott fizikai channel diagnosztizálható;
- a port létezik az elmentett `port_catalog_version` katalógusban.

Ezek DB constraintként történő kikényszerítése megakadályozná hibás, de szerkeszthető DRAFT/BROKEN snapshotok veszteségmentes mentését.

## 5. StageWorkspace snapshot szerződés

### 5.1 Célstruktúra

```python
@dataclass(frozen=True)
class StageBlockSnapshot:
    instance_id: UUID
    type_key: str
    rel_x: int
    rel_y: int
    rel_z: int
    orientation: str
    label: str
    description: str | None
    # display metadata only if it belongs to the workspace contract

@dataclass(frozen=True)
class StageSnapshot:
    blocks: tuple[StageBlockSnapshot, ...]
    edges: tuple[GraphEdge, ...]
    routes: tuple[EdgeRoute, ...]
    wire_cells: tuple[WireCell, ...]
    working_revision: int
    validated_revision: int | None
    last_validation: ValidationReport | None       # process-local only
    program_validation_status: ProgramValidationStatus
    port_catalog_version: str

@dataclass
class StageWorkspace:
    stage: dict[str, Any]
    snapshot: StageSnapshot
    saved_version: int
    dirty: bool
    workspace_epoch: UUID
    lock: threading.RLock
```

### 5.2 Egyetlen node-forrás

`StageSnapshot.blocks` az egyetlen tárolt node-/blokkhalmaz. A GraphWorkspaceAdapter ebből képezi a GraphNode tuple-t. A snapshot nem tart külön `nodes` tuple-t, így a pozíció, orientáció, type és instance ID nem térhet el két mutable reprezentáció között.

A nem gráfos `label`, `description` és későbbi display mezők ugyanabban a blokkrekordban maradnak. Graph domain eredmény alkalmazásakor:

- meglévő instance ID node-mezői frissíthetők, a nem graph mezők megmaradnak;
- törölt node-hoz tartozó teljes blokkrekord törlődik;
- új GraphNode csak teljes `StageBlockSnapshot` metadata mellett alkalmazható;
- metadata nélküli új node alkalmazása strukturáltan elutasítandó.

### 5.3 Atomi alkalmazás

A StageService ugyanazon Stage-lock alatt:

1. ellenőrzi a workspace epochot és expected working revisiont;
2. elkészíti a GraphWorkspaceState nézetet;
3. lefuttatja a domainműveletet;
4. ellenőrzi a GraphWorkspaceUpdatePlant;
5. elkészíti az új teljes `StageSnapshot` objektumot;
6. egyetlen referencia-cserével lecseréli `workspace.snapshot` értékét;
7. `dirty=True` értéket állít valós tartalomváltozásnál.

Nincs részleges edge-, route-, wire- vagy blokklista-módosítás.

## 6. SAVE/LOAD/REBUILD adatáramlás

### 6.1 SAVE

```text
Stage-lock megszerzése
  → owner + workspace_epoch + expected working_revision ellenőrzés
  → teljes snapshot másolata/immutable referenciája
  → repository tranzakció
       SELECT stages.saved_version FOR UPDATE
       compare-and-swap expected saved_version
       snapshot táblák teljes, konzisztens cseréje
       graph_snapshot_version és port_catalog_version frissítése
       saved_version = saved_version + 1
     COMMIT
  → csak sikeres COMMIT után workspace.saved_version frissítés
  → dirty=False
  → working_revision változatlan
Stage-lock feloldása
```

**Sikertelen SAVE.** Exception vagy verzióütközés esetén a DB-tranzakció rollbackel. A StageWorkspace `snapshot`, `saved_version`, `dirty`, `working_revision` mezője változatlan marad.

### 6.2 Snapshot írási sorrend

A legegyszerűbb első implementáció ugyanazon tranzakcióban child-first törli és újra beszúrja a Stage snapshot sorait:

1. `route_steps`;
2. `edge_routes`;
3. `graph_edges`;
4. `wire_channels`;
5. `wire_cells`;
6. `stage_blocks`;
7. új `stage_blocks`;
8. új `graph_edges`;
9. új `edge_routes`;
10. új `route_steps`;
11. új `wire_cells`, majd `wire_channels`;
12. Stage verziómezők frissítése.

Az UUID-k az objektumokból visszaíródnak, ezért az edge- és route-ID stabil marad. A teljes tranzakció izolálja a köztes üres állapotot. Később set-based upsert optimalizálható, de nem változtathatja meg a snapshot-szemantikát.

### 6.3 LOAD és REBUILD

```text
stages + stage_blocks + graph tables betöltése
  → teljes immutable StageSnapshot felépítése
  → port_catalog_version kompatibilitás ellenőrzése
  → GraphWorkspaceState előállítása
  → validate_graph() újraszámítása
  → report process-lokális rögzítése
  → dirty=False
```

Working revision szabály:

- új processben első LOAD: `working_revision=0`;
- ugyanazon processben aktív workspace REBUILD-je: az előző working revision +1, mert a teljes tartalom lecserélődött;
- a working revision nem perzisztens és újraindításokon át nem globálisan monoton;
- stale parancsok restart/REBUILD utáni kizárásához `workspace_epoch` is kell a későbbi DTO-parancsborítékba.

`saved_version` ezzel szemben perzisztens, PostgreSQL-ben monoton Stage-verzió.

### 6.4 Régi, graph nélküli Stage

- `graph_snapshot_version=0`, `port_catalog_version=NULL`.
- A meglévő `stage_blocks` változatlanul betöltődik.
- Edge-, route- és wire tuple üres.
- A Stage DRAFT/CHECK_REQUIRED állapotban indul.
- Nem történik geometria vagy él automatikus kitalálása.
- Első sikeres graph-aware SAVE állítja be az aktuális snapshot- és portkatalógus-verziót.
- UNSPECIFIED blokk jelenléte graphműveletet blokkol, de a régi Stage és a nem gráfos mezők nem vesznek el.

## 7. Portkatalógus

### 7.1 Javasolt Python-konfiguráció

```python
PORT_CATALOG_VERSION = "ck-control-v1"

BLOCK_PORT_SPECS = {
    "START": {
        "status": "SPECIFIED",
        "ports": (
            PortDefinition("out", CONTROL, OUT, FRONT, 0, 1, 1),
        ),
    },
    "STOP": {
        "status": "SPECIFIED",
        "ports": (
            PortDefinition("in", CONTROL, IN, BACK, 0, 1, 1),
        ),
    },
    "IF": {
        "status": "SPECIFIED",
        "ports": (
            PortDefinition("in", CONTROL, IN, BACK, 0, 1, 1),
            PortDefinition("true", CONTROL, OUT, RIGHT, 0, 1, 1, "1"),
            PortDefinition("false", CONTROL, OUT, LEFT, 0, 1, 1, "0"),
        ),
    },
    "FOR": {"status": "UNSPECIFIED", "ports": ()},
    "FUN": {"status": "UNSPECIFIED", "ports": ()},
}
```

START/STOP/IF a CK-02 elfogadott szerződését követi. A ténylegesen regisztrált FOR és FUN szemantikája még nincs véglegesítve, ezért ezek nem kapnak kitalált default portot, és graphműveletük blokkolt.

### 7.2 Konzisztencia és kompatibilitás

- Induláskor a Python katalógus type key halmazát össze kell vetni a `program_block_types` rekordokkal.
- DB-ben ismert, Pythonban hiányzó típus konfigurációs hiba.
- `UNSPECIFIED` típus megjeleníthető és menthető, de graphműveletben nem vehet részt.
- SAVE a `port_catalog_version` értéket a Stage snapshothoz rögzíti.
- LOAD azonos verzióval normálisan folytatható.
- Eltérő verziónál csak explicit kompatibilitási/migrációs függvény után engedhető graphmódosítás.
- Ismeretlen régi verzió nem kap automatikusan aktuális jelentést; read-only/rebuild-diagnosztika szükséges.

## 8. Migrációs kompatibilitás

1. A migráció additív: meglévő `stages`, `stage_blocks` és blokkadat nem törlődik vagy íródik át.
2. Új graph táblák kezdetben üresek.
3. Minden meglévő Stage `graph_snapshot_version=0` értékkel legacy snapshot.
4. `graph_nodes` tábla nincs; az existing `instance_id` identitások változatlanok.
5. A migráció nem próbál éleket következtetni Minecraft-geometriából vagy blokkpozícióból.
6. Első graph-aware SAVE teljes snapshotot ír és csak sikeres commitnál emeli a `saved_version` értéket.
7. Rollback esetén a legacy vagy előző snapshot érintetlen.
8. Migráció előtt repository-integrációs teszt külön PostgreSQL tesztadatbázison kötelező.

## 9. CK-03/B végrehajtható tesztterv

| # | Teszt | Szint | Fő ellenőrzés |
|---:|---|---|---|
| 1 | Üres Stage SAVE/LOAD | repository-integráció | üres blokkok és graph tuple-ok, version+1 |
| 2 | START → STOP round-trip | repository-integráció | edge, route, wire cell és endpointok azonosak |
| 3 | Stabil edge_id/route_id | repository-integráció | SAVE/LOAD után UUID-k változatlanok |
| 4 | Többlépéses route | repository-integráció | `step_index` szerinti pontos sorrend |
| 5 | CROSS NS/EW | repository-integráció | két channel és független route-step visszaépül |
| 6 | Unbound WireCell és route | repository-integráció | route `edge_id=NULL`, szabad cella megmarad |
| 7 | BROKEN_ROUTE | repository-integráció | hiányzó cellára mutató step és BROKEN státusz menthető |
| 8 | Dirty DRAFT SAVE | StageService-integráció | menthető, CLEAN lesz, nem áll automatikusan VALID-ra |
| 9 | Optimista verzióütközés | repository-integráció | második stale SAVE konfliktus, nincs részleges írás |
| 10 | Mesterséges köztes hiba | repository-integráció | tranzakció rollback minden snapshot táblán |
| 11 | SAVE revision | StageService-integráció | saved_version+1, working_revision változatlan |
| 12 | REBUILD-validáció | StageService-integráció | report újraszámolódik, nincs perzisztált diagnosztika |
| 13 | Legacy Stage LOAD | repository-integráció | blokkok megmaradnak, graph üres/CHECK_REQUIRED |
| 14 | Nem gráfos mezők | repository-integráció | label, description, display-releváns adatok megmaradnak |
| 15 | Owner-only SAVE | StageService-integráció | jogosulatlan mentés előtt nincs repository write |
| 16 | Két párhuzamos SAVE | repository-integráció | pontosan egy CAS siker, egy konfliktus |
| 17 | Snapshot merge | unit | graph node-frissítés mellett label/description megmarad |
| 18 | Metadata nélküli új node | unit | alkalmazási terv strukturált elutasítása |
| 19 | Portkatalógus-egyezés | unit | DB típus vs Python SPECIFIED/UNSPECIFIED ellenőrzés |
| 20 | Katalógusverzió-eltérés | StageService-integráció | graphmódosítás blokkolt explicit migráció nélkül |
| 21 | In-process REBUILD | StageService-integráció | workspace_epoch cserélődik, revision content-change szerint lép |
| 22 | Process-restart LOAD | StageService-integráció | revision 0, saved_version perzisztens marad |
| 23 | Wire cardinalitás trigger | repository-integráció | hiányos CROSS commit elutasított |
| 24 | Hibás route step referencia | repository-integráció | DB menti, validátor BROKEN/INVALID channel diagnosztikát ad |

Repository-integrációs tesztek izolált PostgreSQL konténeren vagy tranzakciónként visszagörgetett tesztadatbázison fussanak; éles DB nem használható.

## 10. Kockázatok és nyitott döntések

| Prioritás | Kérdés/kockázat | Javaslat |
|---|---|---|
| P0 | Mutable `blocks` és graph nodes kettős állapota | Egyetlen immutable StageSnapshot; nodes mindig blocks-ból vetülnek |
| P0 | BROKEN route DB-FK miatt elveszne | route_steps ne hivatkozzon FK-val wire channelre |
| P0 | Fél snapshot SAVE-hiba után | egyetlen DB-tranzakció + workspace csak commit után frissül |
| P0 | SAVE növeli a régi revisiont | StageService-módosításban megszüntetendő |
| P1 | Restart után revision újra 0 lehet | workspace_epoch + revision parancsszerződés |
| P1 | Portkatalógus változik | mentett catalog version + explicit kompatibilitási lépés |
| P1 | CROSS cardinalitás több táblán | deferred constraint trigger + repository unit/integration teszt |
| P1 | Edge endpoint és route endpoint eltér | betöltés/SAVE előtti domain-validáció; hibás DRAFT megőrizhető |
| P1 | Delete/reinsert elveszíti created_at értéket | graph tábláknál elfogadható első verzió; auditigény esetén később upsert |
| P2 | Nagy snapshot teljes újraírása | correctness-first; mérés után set-based diff/upsert |

Nyitott jóváhagyási döntések:

1. Elfogadható-e a `workspace_epoch` bevezetése a későbbi parancsborítékba?
2. Elfogadható-e, hogy hibás/BROKEN route miatt a route-step → wire-channel kapcsolat nem DB-FK, hanem domainvalidáció?
3. Elfogadott-e a `wire_channels` normalizálás és deferred cardinalitás-trigger?
4. Elfogadott-e a `ck-control-v1` Python katalógus és FOR/FUN `UNSPECIFIED` állapota?
5. Elfogadott-e az első verzió correctness-first teljes snapshot cseréje egy tranzakción belül?

## 11. CK-03/B implementációs műszakhatár

A következő implementációs műszak javasolt bontása:

### CK-03/B1 – Portkatalógus és in-memory StageSnapshot

- verziózott Python portkatalógus;
- immutable `StageBlockSnapshot` és `StageSnapshot`;
- adapter teljes, veszteségmentes mappingje;
- StageService lockolt, atomi plan-alkalmazása;
- SAVE revision eltérés javítása;
- DB-migráció nélkül.

### CK-03/B2 – Additív migráció és repository round-trip

- a jóváhagyott DDL számozott migrációja;
- repository teljes snapshot load/save;
- PostgreSQL integrációs tesztek és rollback/CAS;
- HTTP/Fabric továbbra sem része.

### CK-03/B3 – StageService SAVE/REBUILD összekötés

- owner/lock/epoch/revision szerződés;
- repository snapshot alkalmazás;
- validation újraszámítása rebuild után;
- teljes StageService-integrációs tesztek.

Nem része CK-03/B-nek: Fabric wire blokk, HTTP wire endpoint, Minecraft-világ reconciliation vagy deploy.

## 12. GO/NO-GO javaslat

**Jelenleg NO-GO a migráció közvetlen elkészítésére**, amíg az öt nyitott jóváhagyási döntés nincs elfogadva.

**GO a CK-03/B1 implementációra** a StageSnapshot és a verziózott Python portkatalógus irányának elfogadása után. Ez a legkisebb biztonságos lépés: megszünteti a node-kettősség kockázatát, definiálja az atomi alkalmazást és nem érinti még a DB-t.

**Feltételes GO a CK-03/B2 migrációra**, ha:

- a DDL és deferred wire-trigger review-zott;
- a BROKEN route tudatos FK-kivétele elfogadott;
- izolált PostgreSQL tesztkörnyezet rendelkezésre áll;
- a B1 teljes regressziója zöld;
- mentés előtt biztonsági mentési és rollback-eljárás dokumentált.

## Függelék – START.out → STOP.in példa

Szemléltető Stage:

```text
START node (0,0,0), EAST facing
    out port → wire cell (1,0,0), SINGLE, E/W
STOP node (2,0,0), EAST facing
    in port ← ugyanaz a wire cell
```

Rövidített rekordok:

```text
stage_blocks
  S1 | stage=A | START | (0,0,0) | EAST | label=START
  S2 | stage=A | STOP  | (2,0,0) | EAST | label=STOP

graph_edges
  E1 | stage=A | S1.out → S2.in | CONTROL

wire_cells
  A | (1,0,0) | SINGLE

wire_channels
  A | (1,0,0) | SINGLE | connections_mask=10  # EAST(2)+WEST(8)

edge_routes
  R1 | stage=A | edge=E1 | S1.out → S2.in | VALID

route_steps
  R1 | index=0 | stage=A | (1,0,0) | SINGLE
```

Visszaépítés:

1. `stage_blocks` → két GraphNode, az eredeti instance ID-kkal és metadata-megőrzéssel.
2. `graph_edges` → az E1 logikai kapcsolat.
3. `wire_cells` + `wire_channels` → az egyetlen E/W SINGLE WireCell.
4. `edge_routes` + index szerint rendezett `route_steps` → R1, E1-hez kötve.
5. `validate_graph()` új reportot számít az új process-lokális revisionhöz.
6. Egy későbbi Fabric-integráció ebből renderelheti a blokkot és vezetéket; a példa nem állítja, hogy ez már implementált.

Reroute esetén E1 változatlan. R1 megtartható, a route_steps cserélődik. Ha új route-identitás szükséges, csak R1 változik; az E1 edge-ID továbbra is stabil.
