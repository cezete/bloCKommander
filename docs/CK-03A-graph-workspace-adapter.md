# CK-03/A – GraphWorkspaceAdapter és alkalmazási szerződések

- Dátum: 2026-10-23
- Hatókör: tiszta, in-memory StageWorkspace → graph-domain határ
- Nem része: StageService-módosítás, HTTP, repository write, migráció, Fabric vagy világmódosítás
- Eredmény: részleges node-mapping és teljes graph eredményt megőrző immutable alkalmazási terv

## 1. Kiinduló runtime állapot

**Megfigyelt tény.** A `StageService` a process memóriájában, `_workspaces` mapben tartja a munkapéldányokat. A mapet egy globális `RLock`, az egyes `StageWorkspace` példányokat saját `RLock` védi (`python-service/src/hangar/stage.py:24-43`).

**Megfigyelt tény.** A tulajdonosi ellenőrzést a `StageService._authorize()` végzi (`stage.py:47-50`). Az adapter nem kap hitelesített felhasználói kontextust, ezért nem végezhet jogosultságellenőrzést.

**Megfigyelt tény.** A repository csak Stage-blokkokat tölt és ment. Edge-, route-, wire- és graph-validation snapshot nincs (`python-service/src/hangar/repository.py:158-198`).

**Implementált képesség.** Az adapter nem importál StageService-t vagy repository-t, nem szerez lockot, nem végez I/O-t és nem módosít alkalmazási objektumot.

## 2. StageWorkspace mezőtérkép

### 2.1 Workspace-szint

| StageWorkspace mező | Jelenlegi jelentés | Graph leképezés |
|---|---|---|
| `stage` | DB-ből betöltött Stage/hall metadata dictionary | `stage["stage_id"]` → `GraphWorkspaceState.stage_id` |
| `blocks` | módosítható blokk-dictionary lista | elemenként `GraphNode` |
| `saved_version` | utolsó kanonikus DB snapshot optimista verziója | `GraphWorkspaceState.saved_version` |
| `dirty` | a blokk-workspace eltér a mentett snapshottól | `GraphWorkspaceState.dirty` |
| `revision` | jelenlegi StageService munkarevízió | `GraphWorkspaceState.working_revision` |
| `lock` | process-local Stage `RLock` | nem kerül domain state-be; StageService-felelősség |

### 2.2 Blokk-dictionary

| Blokkmező | GraphNode mező | Szabály |
|---|---|---|
| `instance_id` | `instance_id` | ugyanaz a UUID; nincs második node-ID |
| workspace `stage_id` | `stage_id` | minden node a workspace Stage-éhez tartozik |
| `type_key` | `type_key` | authoritative blokk-katalógusban és portkatalógusban is szerepelnie kell |
| `rel_x`, `rel_y`, `rel_z` | `GridPos` | Stage-lokális, egész koordináták |
| `orientation` | `CardinalDirection` | explicit, case-sensitive enumkonverzió |
| `label`, `description`, display mezők | nincs CK-02 GraphNode-mező | a StageWorkspace-ben maradnak, az adapter nem írja vissza őket |

**Megfigyelt tény.** A StageService által újonnan létrehozott blokk tartalmaz `stage_id` mezőt, a repository `load_stage()` blokklekérdezése viszont nem választja ki. Ezért a node Stage-ID-jának stabil forrása a workspace `stage["stage_id"]`, nem a blokkon belüli opcionális másolat.

## 3. GraphNode-azonosság

**Implementált képesség.** A Stage-blokk `instance_id` értéke közvetlenül a `GraphNode.instance_id`. String UUID és már konvertált UUID elfogadott; hibás vagy Stage-en belül duplikált UUID strukturált mapping hibát eredményez.

**Szerződés.** Meglévő blokk címke-, leírás- vagy orientációmódosítása nem hoz létre új ID-t. Törölt és később újra létrehozott blokkhoz a StageService új `instance_id` értéket ad; ezt az adapter változtatás nélkül követi.

## 4. Orientációs leképezés

```text
"NORTH" → CardinalDirection.NORTH
"EAST"  → CardinalDirection.EAST
"SOUTH" → CardinalDirection.SOUTH
"WEST"  → CardinalDirection.WEST
```

**Implementált képesség.** A konverzió explicit és case-sensitive. `UP`, `north`, üres vagy más ismeretlen érték `UNKNOWN_ORIENTATION` hibát ad. Nincs automatikus NORTH fallback.

## 5. Portkatalógus forrása

**Megfigyelt tény.** A DB `program_block_types` katalógusa a létező programblokktípusokat írja le, de nem tartalmaz CK-02 `PortDefinition` objektumokat. A CK-02 portkatalógus jelenleg csak domain-/tesztoldali szerződés.

**Implementált képesség.** `to_graph_workspace()` két külön hiteles bemenetet kér:

- `known_block_types`: az alkalmazási programblokk-katalógus típusazonosítói;
- `port_catalog`: a graph-domain authoritative `PortDefinition` katalógusa.

Ismeretlen blokk `UNKNOWN_BLOCK_TYPE`; ismert, de portdefiníció nélküli blokk `MISSING_PORT_CATALOG`. Az adapter nem generál alkalmi portokat.

**Blokkoló hiány.** A produkciós portkatalógus betöltési/konfigurációs forrása még nincs implementálva. CK-03/B előtt el kell dönteni, hogy verziózott Python konfiguráció vagy DB-katalógus lesz-e a hiteles forrás.

## 6. GraphWorkspaceAdapter API

### 6.1 Leképezés

```python
to_graph_workspace(
    workspace,
    port_catalog,
    *,
    known_block_types,
) -> GraphWorkspaceMapping
```

Az eredmény:

- `state`: immutable `GraphWorkspaceState`;
- `graph_content_complete`: jelzi, hogy a teljes graph snapshot elérhető volt-e;
- `issues`: strukturált adapterproblémák;
- `permits_graph_mutation`: csak teljes graph tartalomnál igaz.

A jelenlegi StageWorkspace esetén a mapping mindig `graph_content_complete=False`, mert edge/route/wire/validation mezők nincsenek. A node state elkészül, de graph-módosításra nem állítható teljes snapshotnak.

### 6.2 Alkalmazási terv

```python
prepare_graph_workspace_update(
    current,
    result,
    *,
    operation_accepted,
) -> GraphWorkspaceUpdatePlan
```

Az immutable terv tartalmazza:

- elfogadta-e a domain a műveletet;
- szükséges-e alkalmazás;
- alkalmazható-e a jelenlegi StageWorkspace szerződéssel;
- expected/result revisiont;
- a teljes cél `GraphWorkspaceState` objektumot;
- strukturált issue-kat.

Elutasított művelethez nincs target state. Idempotens elfogadott művelethez van target, de nincs alkalmazandó változás. Sikeres tartalomváltozás teljes target state-et ad, ugyanakkor `STAGE_GRAPH_STORAGE_UNAVAILABLE` miatt még nem alkalmazható a jelenlegi StageWorkspace-re.

### 6.3 Státuszprojekció

```python
to_stage_status_dto(
    state,
    *,
    program_validation_status=ProgramValidationStatus.NOT_CHECKED,
) -> StageStatusDTO
```

A DTO `as_dict()` metódusa UUID- és enumértékeket primitív JSON-kompatibilis stringekké alakít. HTTP-t nem végez.

## 7. Veszteségmentes és hiányos leképezések

### Veszteségmentesen támogatott

- Stage-ID;
- GraphNode-ID;
- blokktípus;
- relatív blokkpozíció;
- orientáció;
- working revision;
- saved version;
- dirty flag.

### Explicit részleges/BLOCKED

- GraphEdge betöltése;
- EdgeRoute betöltése;
- WireCell és channel betöltése;
- előző validation report és validated revision visszaállítása;
- graph domain eredmény visszaírása a jelenlegi StageWorkspace-be;
- teljes SAVE/LOAD round-trip.

**Implementált védelem.** A mapping `GRAPH_CONTENT_UNAVAILABLE` issue-t és `graph_content_complete=False` értéket ad. Az update plan a teljes edge/route/wire halmazt megtartja a `target_state` mezőben, de a jelenlegi alkalmazási szerződésre `can_apply_to_current_stage_workspace=False`. Így nincs néma adatvesztés és nincs párhuzamos hosszú életű registry.

## 8. Revízió- és SAVE-szemantika

### Cél-szemantika

1. Tartalomváltozás: `working_revision + 1`, `dirty=True`.
2. Idempotens vagy visszautasított művelet: változatlan working revision.
3. Domain tartalomművelet: változatlan `saved_version`.
4. Sikeres SAVE: `saved_version + 1`, `dirty=False`, változatlan working revision.

**Implementált képesség.** Az update plan ellenőrzi a tartalom és revision összhangját, a saved version változatlanságát, valamint a dirty flag átmenetét. Nem tart fenn rejtett revízióeltolást.

**Megfigyelt eltérés.** A jelenlegi `StageService.save()` siker után `ws.revision += 1` műveletet végez (`stage.py:106-115`). Ezt a CK-03/A nem módosítja.

**Javasolt következő változtatás.** A későbbi StageService-integrációban a SAVE-ből el kell távolítani a tartalmi revíziónövelést. Külön regresszióteszt rögzítse: SAVE növeli a `saved_version` értékét, de nem változtatja a `working_revision` értéket és nem évülteti el a változatlan tartalom reportját.

## 9. Owner- és Stage-lock határ

Az alkalmazási sorrend kötelező szerződése:

```text
1. StageService azonosítja a Stage-et és a játékost.
2. StageService ellenőrzi az owner jogosultságot.
3. StageService megszerzi a StageWorkspace.lock lockot.
4. StageService ellenőrzi az expected working revisiont.
5. Adapter elkészíti a graph-domain nézetet.
6. Lefut a tiszta domainművelet.
7. Adapter elkészíti és ellenőrzi az immutable update plant.
8. StageService egy lépésben alkalmazza a teljes plant, ha alkalmazható.
9. StageService feloldja a lockot.
```

**Implementált képesség.** Az adapter nem szerez lockot, nem olvassa a `_workspaces` registryt és nem fogad player UUID-t jogosultsági bizonyítékként.

**Javasolt következő változtatás.** A StageService kapjon publikus graph-operation koordinátormetódust; az tartsa végig ugyanazt a Stage-lockot a revision check, mapping, domainhívás és alkalmazás között.

## 10. Háromdimenziós státusz DTO

`StageStatusDTO` mezői:

```text
stage_id
working_revision
saved_version
edge_validation_status
program_validation_status
save_status
```

| Dimenzió | Értékek | Forrás |
|---|---|---|
| Edge/route | CHECK_REQUIRED, OK, ERRORS | jelenlegi graph state `indicator_status` |
| Teljes program | NOT_CHECKED, VALID, INVALID, NOT_APPLICABLE | külön, későbbi programvalidátor |
| Mentettség | CLEAN, DIRTY | graph state `save_status` |

**Implementált védelem.** Az alap programstátusz mindig `NOT_CHECKED`. Egy generikus graph report és a jelenlegi `GraphStatus.VALID` nem állítja automatikusan `VALID` értékre.

## 11. Későbbi StageService-integráció módosítási pontjai

1. A `StageWorkspace` szerződésének bővítése edge-, route-, wire- és graph-validation snapshot mezőkkel vagy egyetlen, teljes graph snapshot mezővel.
2. Publikus, lockolt graph-operation koordinátormetódusok hozzáadása.
3. Expected working revision ellenőrzése ugyanazon lock alatt.
4. A `GraphWorkspaceUpdatePlan.target_state` teljes, egy lépéses alkalmazása.
5. SAVE working-revision növelésének megszüntetése.
6. Repository snapshot kiterjesztése blokk + graph + layout atomi mentésére.
7. Rebuild során a teljes graph snapshot visszaállítása stabil edge- és route-ID-val.
8. Authoritative produkciós portkatalógus bekötése.

Ezek egyike sem része a CK-03/A kódváltozásának.

## 12. Teszteredmények

Az új `test_graph_adapter.py` 42 tesztet tartalmaz. Lefedi a mezőleképezést, identitást, orientációt, strukturált hibákat, státuszdimenziókat, idempotens/elutasított/sikeres domain eredményeket, graph-tartalom megőrzését és az I/O/lock/registry határokat.

Az adapter célteszt:

```text
Ran 42 tests in 0.044s
OK
```

A teljes graph-csomag:

```text
Ran 270 tests in 0.925s
OK
```

A teljes Python-regresszió:

```text
Ran 283 tests in 0.676s
OK
```

## 13. Nyitott döntések

1. A StageWorkspace külön immutable graph snapshot mezőt kapjon, vagy a graph gyűjtemények külön mezőként kerüljenek bele?
2. A portkatalógus verziózott Python konfiguráció vagy PostgreSQL adat legyen?
3. A graph validation report perzisztálandó vagy minden rebuild után újraszámítandó?
4. A programvalidátor mely Stage-típusokra ad `NOT_APPLICABLE` értéket?
5. A repository normalizált graph táblákat vagy verziózott JSONB snapshotot használjon?
6. Hogyan történjen Fabric/Python kommunikációs hiba után a világ–workspace reconciliation?

## 14. CK-03/B előfeltételei

CK-03/B csak az alábbi döntések után indítható:

- a StageWorkspace minimális graph snapshot mezőinek elfogadása;
- a produkciós portkatalógus hiteles forrásának kijelölése;
- a SAVE cél-revíziószemantikájának elfogadása;
- a repository snapshot reprezentáció kiválasztása;
- a Stage-lock alatt végrehajtott atomi alkalmazás szerződésének elfogadása.

**Kapudöntés.** A CK-03/A adapterhatár használható és tesztelt a ténylegesen alátámasztható node-/status-/plan esetekben. A veszteségmentes graph round-trip továbbra is BLOCKED a StageWorkspace és a repository graph mezőinek hiánya miatt.
