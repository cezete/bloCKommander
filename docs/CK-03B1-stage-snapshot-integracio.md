# CK-03/B1 – Portkatalógus és in-memory StageSnapshot integráció

- Dátum: 2026-10-23
- Hatókör: Python alkalmazási réteg és graph adapter
- Nem része: PostgreSQL graph-perzisztencia, migráció, HTTP- vagy Fabric-módosítás

## 1. Megvalósított API-k

- `StageBlockSnapshot`: egy blokk gráfos és nem gráfos adatainak immutable rekordja.
- `StageSnapshot`: a Stage teljes in-memory blokk-, edge-, route-, wire- és validációs állapota.
- `VersionedPortCatalog`: verziózott, immutable portkatalógus és runtime-konzisztencia-ellenőrzés.
- `to_graph_workspace()`: teljes StageSnapshot → GraphWorkspaceState vetítés.
- `prepare_graph_workspace_update()`: immutable domaineredmény alkalmazhatósági terve.
- `to_stage_snapshot()`: GraphWorkspaceState → teljes StageSnapshot visszaleképezés.
- `StageService.apply_graph_operation()`: owner-, epoch- és revision-ellenőrzött atomi alkalmazási határ.

## 2. Portkatalógus

A produkciós katalógus verziója `ck-control-v1`. Nem kapcsolódik adatbázishoz.

| Típus | Státusz | Portok |
|---|---|---|
| START | SPECIFIED | `out`: CONTROL, OUT, FRONT, 1..1 |
| STOP | SPECIFIED | `in`: CONTROL, IN, BACK, 1..1 |
| IF | SPECIFIED | `in`: IN/BACK; `true`: OUT/RIGHT/`1`; `false`: OUT/LEFT/`0`; mind CONTROL és 1..1 |
| FOR | UNSPECIFIED | nincs kitalált port |
| FUN | UNSPECIFIED | nincs kitalált port |

A tényleges migrációkban jelenleg START, STOP, IF, FOR és FUN szerepel. SET, PRINT és END_IF csak graph-domain teszt-fixture-ben létezik, runtime programblokktípusként nincs regisztrálva, ezért nem került a produkciós katalógusba.

Az ismeretlen típus `PortCatalogLookupError` strukturált hibát ad. A `check_runtime_types()` tiszta függvényként jelzi a runtime- és portkatalógus eltérését. UNSPECIFIED blokkot tartalmazó snapshot leképezhető és megőrizhető, de graphműveletet nem enged.

## 3. StageSnapshot szerkezet

`StageBlockSnapshot` mezői:

- `instance_id`, `type_key`, relatív `GridPos`, `orientation`;
- `label`, `description`;
- `display_name`, `material_name`, immutable `display_definition`.

`StageSnapshot` mezői:

- `stage_id`, blokk-tuple;
- `GraphEdge`, `EdgeRoute` és `WireCell` tuple-ök;
- `working_revision`, `validated_revision`, `last_graph_validation`;
- külön `program_validation_status`;
- `port_catalog_version`.

Nincs külön node-registry. A GraphNode tuple mindig a blokkok `graph_nodes()` vetülete. A JSON-szerű megjelenítési metadata fagyasztott másolatként él a snapshotban, a legacy view pedig leválasztott mutable másolatot kap.

## 4. StageWorkspace átállítása

A StageWorkspace egyetlen `snapshot` referenciát tart. Mellette kizárólag a workspace-életciklushoz tartozó `stage`, `saved_version`, `dirty`, `workspace_epoch` és `lock` mezők maradtak.

A kompatibilitási `blocks` property mindig friss, leválasztott legacy vetítés, ezért nem második módosítható hiteles blokkhalmaz. A `revision` property a snapshot `working_revision` értékét vetíti.

A blokk CRUD immutable snapshotcserével működik. Törléskor az érintett edge-ek és route-ok is ugyanabban a cserében távoznak; megmaradó unbound wire cellák továbbra is reprezentálhatók.

## 5. Adapter round-trip

A teljes leképezés megőrzi:

- a node-, edge-, route- és wire-tartalmat;
- stabil `edge_id` és `route_id` értékeket;
- working/validated revisiont, saved versiont, dirty állapotot és graph reportot.

Teljes, kompatibilis snapshot esetén `graph_content_complete=True` és `permits_graph_mutation=True`. Verzióeltérés, runtime-katalógus-eltérés vagy UNSPECIFIED blokk esetén a tartalom olvasható marad, de a módosítás strukturált issue-val tiltott.

Visszaleképezéskor a meglévő blokk labelje, leírása és display metadata-ja változatlan marad. Törölt node blokkja eltűnik. Új node kizárólag teljes `StageBlockSnapshot` metadata mellett vehető át; ennek hiánya `MISSING_NEW_BLOCK_METADATA`.

## 6. StageService atomi alkalmazás

Az `apply_graph_operation(stage_id, payload, operation, new_block_metadata=())` sorrendje:

1. workspace feloldása és szerveroldali owner-ellenőrzés;
2. Stage RLock megszerzése;
3. expected epoch és expected working revision ellenőrzése;
4. runtime-katalógus és teljes GraphWorkspaceState előállítása;
5. domainművelet futtatása a lock alatt;
6. immutable update plan és teljes új snapshot elkészítése;
7. egyetlen snapshot-referenciacsere és dirty-frissítés.

Exception, domain-elutasítás, adapterhiba vagy stale parancs előtt nincs állapotcsere. Párhuzamos, azonos revisionre küldött műveletek közül legfeljebb az első alkalmazható.

A hitelesítés továbbra is a payload `player_uuid` értékének és a repositoryból betöltött `owner_uuid` értéknek az összevetése. Ez alkalmazási jogosultság-ellenőrzés, nem új autentikációs rendszer; a hívó transport hiteles azonosító-átadását külön kell biztosítsa.

## 7. Epoch és revision szemantika

- Első workspace-betöltés: új process-lokális UUID epoch, working revision 0.
- Tartalomváltozás: working revision pontosan +1.
- Idempotens domaineredmény: nincs snapshotcsere és nincs revisionlépés.
- SAVE: working revision változatlan.
- REBUILD: új epoch, az előző process-lokális working revision +1.
- A working revision újraindításokon át nem globálisan monoton. A parancsazonosság az epoch + revision pár.
- A saved version külön, repository CAS-verzió.

## 8. SAVE és REBUILD viselkedés

Legacy, kizárólag blokkokból álló snapshot továbbra is a meglévő repository-tranzakcióval menthető. Sikeres SAVE csak a `saved_version` értékét növeli és CLEAN-re állít; nem módosítja a working revisiont és nem évülteti el a változatlan tartalom validációját. Repository-hiba esetén minden workspace-mező változatlan.

Ha edge, route vagy wire cell van a snapshotban, a SAVE explicit `HallConflict` hibával leáll. Így a blokkok részleges mentése nem jelenthető a teljes graph snapshot sikeres mentéseként, és a Stage nem válhat hamisan CLEAN állapotúvá.

REBUILD csak blokkadatokat tud visszatölteni B1-ben. Dirty állapotnál explicit `confirm_discard` kell, ezért az in-memory graph nem veszhet el észrevétlenül. Sikeres REBUILD új epochot hoz létre, kiüríti a nem perzisztált graph tartalmat és törli a korábbi graph-validációs reportot.

## 9. Legacy kompatibilitás

- A `place`, `update`, `delete`, `save`, `rebuild`, `ensure`, `for_hall` és `view` publikus StageService-belépési pont megmaradt.
- A válasz meglévő mezői és blokkformája megmaradt; új mezők: `workspace_epoch`, `port_catalog_version`, `graph_persistence`.
- A repository interfész és a stage_blocks mentési formátuma változatlan.
- A legacy SAVE ismételt használata továbbra is CAS-verziót növel.
- Kotlin/Fabric, HTTP és DB-séma nem változott.

## 10. Explicit BLOCKED funkciók

- GraphEdge, EdgeRoute, route step és WireCell PostgreSQL SAVE/LOAD.
- Mentett `port_catalog_version` és graph snapshot format verzió DB-kezelése.
- REBUILD utáni teljes graph-validáció DB-ből visszaállított gráfon.
- Graphos workspace CLEAN állapotba mentése.
- HTTP/Fabric graphparancs DTO és autentikált transport.

Ezek CK-03/B2–B3 feladatok. A `graph_persistence=BLOCKED` view állapot jelzi, ha a workspace ilyen nem perzisztálható tartalmat hordoz.

## 11. Teszteredmények

- Új B1 céltesztek: **36/36 PASS**.
- Teljes graph-regresszió: **270/270 PASS**.
- Teljes Python-regresszió: **319/319 PASS**.
- Kiinduló teljes baseline: 283; növekmény: 36 új teszt.

A céltesztek külön modulokban fedik a portkatalógust, snapshotot, adapter round-tripet és StageService atomi alkalmazást. A meglévő CK-03/A adaptertesztek a teljes snapshot szerződésre lettek átállítva.

## 12. CK-03/B2 előfeltételek

CK-03/B2 akkor kezdhető meg, ha:

1. a B0 normalizált DDL és deferred wire-channel cardinalitás döntése elfogadott;
2. a graph snapshot format- és portkatalógus-verzió bekerül a Stage perzisztens szerződésébe;
3. a repository egy tranzakcióban, optimistic CAS mellett menti a blokkokat és a teljes graph snapshotot;
4. rollback-tesztek bizonyítják, hogy részleges blokk/edge/route/wire állapot nem marad;
5. LOAD/REBUILD a graph report tárolása helyett újravalidál;
6. legacy, graph nélküli Stage-ek additív migrációja és round-trip tesztje kész.

**B1 eredmény: GO a B2 tervezett repository/migrációs műszakára, de a graph-perzisztencia jelenleg továbbra is BLOCKED és nem tekinthető elkészültnek.**
