# CK-03/B2 – PostgreSQL graph-perzisztencia és repository round-trip

- Dátum: 2026-10-23
- Hatókör: additív PostgreSQL-migráció, teljes StageSnapshot repository SAVE/LOAD
- Biztonsági státusz: a migráció elkészült, de izolált PostgreSQL hiányában nem került futtatásra

## 1. Megvalósított migráció

Új migráció: `python-service/src/hangar/migrations/006_graph_snapshots.sql`.

A migráció:

- két verziómezőt ad a `stages` táblához;
- ugyanazon Stage-re mutató composite FK-khoz unique constraintet ad a `stage_blocks` táblához;
- létrehozza a graph- és wire-táblákat;
- létrehozza a deferred wire-channel integritási függvényeket és két constraint triggert.

A migráció additív: nem töröl vagy ír át meglévő Stage- vagy blokkadatot. A régi Stage-ek `graph_snapshot_version=0`, `port_catalog_version=NULL` állapotot kapnak.

**Futtatási státusz: NOT RUN / BLOCKED.** A lokális környezetben sem DB-kapcsolati változók, sem elkülönített tesztadatbázis-konfiguráció nem áll rendelkezésre. Az alapértelmezett kapcsolat az ismert fejlesztői/üzemi `127.0.0.1:5433/bloCKommander` adatbázis lenne, ezért a biztonsági szabály szerint nem történt kapcsolódás.

## 2. Végleges DDL és eltérései a B0 tervtől

Létrejövő táblák:

- `graph_edges`
- `edge_routes`
- `route_steps`
- `wire_cells`
- `wire_channels`

A `stage_blocks` marad a GraphNode-ok tárolási alapja; `graph_nodes` tábla nincs.

A B0 tervhez képest a végleges `wire_channels` nem tartalmaz redundáns `cell_kind` oszlopot. A cellatípus egyetlen forrása a szülő `wire_cells.cell_kind`. Ez kizárja, hogy a gyermek és a szülő típusa eltérjen, és egyszerűbbé teszi a cellatípus-váltás ellenőrzését.

A route-step szándékosan nem kap WireCell FK-t. Ez szükséges a hiányzó cellára mutató BROKEN route veszteségmentes tárolásához.

A Stage-azonos composite FK-k biztosítják, hogy edge és route node-végpont ne mutathasson másik Stage blokkjára. A logikai edge-et nem tartalmazó route megengedett.

## 3. Repository API

Új API-k:

```python
HallRepository.save_full_stage_snapshot(
    stage_id: str,
    expected_version: int,
    snapshot: StageSnapshot,
) -> int

HallRepository.load_full_stage_snapshot(
    stage_id: str,
) -> LoadedStageSnapshot
```

A `LoadedStageSnapshot` tartalmazza:

- a Stage fejlécét;
- a teljes immutable StageSnapshotot;
- `saved_version`, `graph_snapshot_version` és tárolt portkatalógus-verzió értékét;
- `compatible_for_graph_mutation` jelzőt és strukturált kompatibilitási problémákat.

Új repository-hibák:

- `SnapshotStorageError`: a tartós reprezentáció szerkezeti hibája;
- `SnapshotCompatibilityError`: nem támogatott snapshot- vagy portkatalógus-verzió.

A meglévő `load_stage()` és blokk-only `save_stage_snapshot()` megmaradt. A legacy SAVE most explicit módon elutasítja a `graph_snapshot_version>0` Stage felülírását.

## 4. Snapshot SAVE

A teljes SAVE egyetlen psycopg2 connection-context tranzakcióban történik:

1. `stages` sor `FOR UPDATE` zárolása;
2. persistent `saved_version` CAS;
3. snapshot-verzió kompatibilitásának ellenőrzése;
4. régi sorok child-first törlése;
5. blocks → edges → routes → steps → cells → channels sorrendű beszúrás;
6. `graph_snapshot_version=1` és `port_catalog_version=ck-control-v1` rögzítése;
7. `saved_version` pontosan +1.

A repository nem olvassa és nem módosítja a process-lokális working revisiont. Az instance-, edge- és route-UUID-kat változatlanul írja vissza.

A `label` és `description` a `stage_blocks` rekordban marad. A `display_name`, `material_name` és `display_definition` továbbra is a `program_block_types` katalógusból származik; nem duplikálódik minden snapshot-blokk sorában.

## 5. Snapshot LOAD

A teljes LOAD determinisztikus `ORDER BY` lekérdezésekkel tölti be:

- Stage és blokkrekordok;
- GraphEdge-ek;
- EdgeRoute-ok;
- `step_index` szerint rendezett WireStep-ek;
- WireCell-ek és csatornáik.

A deszerializáló ellenőrzi a nullától induló, hézagmentes route-step indexeket, a wire cell típusa és csatornahalmaza közötti egyezést, valamint a connection mask tartományát.

A working revision 0, az epoch nincs repository-szinten, a graph report és a validált revision nem töltődik vissza. A repository nem állít programvalidációt VALID állapotba.

## 6. Legacy kompatibilitás

`graph_snapshot_version=0` esetén:

- a blokkok változatlanul betöltődnek;
- edge, route és wire tuple üres;
- nincs kitalált kapcsolat vagy geometria;
- a tárolt portkatalógus-verzió `NULL` marad;
- graphművelet előtt explicit formátum-/katalógus-upgrade szükséges;
- egy későbbi graph-aware SAVE frissítheti a formátumot 1-re.

Ha egy version 0 Stage mellett mégis graph-sorok találhatók, az tárolási integritási hiba, nem automatikusan értelmezett állapot.

Ismeretlen graph- vagy portkatalógus-verzió adatai beolvashatók megőrzésre, de `compatible_for_graph_mutation=False`; nincs automatikus újraértelmezés.

## 7. DRAFT és BROKEN tárolás

A repository támogatja:

- route nélküli edge-et;
- edge nélküli route-ot;
- source/target nélküli INCOMPLETE vagy BROKEN route-ot;
- hiányzó WireCellre mutató route-stepet;
- unbound WireCellt és CROSS cellát;
- nem teljes programgráfot.

Tárolási integritási hiba marad például a másik Stage-hez tartozó edge, hiányzó blokk-végpont, nem létező edge-re bound route, duplikált stabil ID vagy hibás route-step indexelés. Ezek különböznek a későbbi graph- és programvalidációs hibáktól.

## 8. Wire-channel integritás

Sor-szintű szabályok:

- channel csak `SINGLE`, `NS` vagy `EW`;
- SINGLE mask 0–2 irányt tartalmazhat;
- NS mask pontosan 5, azaz NORTH+SOUTH;
- EW mask pontosan 10, azaz EAST+WEST.

A `wire_cells_channel_cardinality` és `wire_channels_cell_cardinality` deferred constraint trigger egyaránt INSERT/UPDATE/DELETE után fut. A trigger az OLD és NEW koordinátát is ellenőrzi, ezért cellatípus-váltással, koordinátamódosítással vagy csatornatörléssel nem kerülhető meg.

Commitkor:

```text
SINGLE → pontosan {SINGLE}
CROSS  → pontosan {EW, NS}
```

A trigger SQL statikusan ellenőrzött, de valódi PostgreSQL commit-viselkedése az izolált tesztadatbázis hiánya miatt még BLOCKED.

## 9. CAS és rollback

A CAS a zárolt `stages.saved_version` értéket veti össze az `expected_version` értékkel. Eltérésnél `StageVersionConflict` keletkezik még a snapshot-sorok törlése előtt.

A connection context exception esetén rollbacket kér a psycopg2-től. Unit hibainjektálás bizonyítja, hogy edge-írási exception után a Stage verziófrissítő SQL nem fut le, és a tranzakció exceptionnel zárul.

Ez csak a Python vezérlési út unit bizonyítéka. A blokk-, edge-, route- és wire-írás utáni valódi PostgreSQL rollback, valamint két valóban párhuzamos connection CAS-viselkedése még nincs bizonyítva.

## 10. Tesztkörnyezet és visszaállítás

Felderített állapot:

- `BLOCKCOMMANDER_DB_*`: nincs beállítva;
- `TEST_DATABASE_URL`: nincs beállítva;
- külön teszt-DB vagy Compose-konfiguráció: nincs a repositoryban;
- az alapértelmezett cél az ismert, nem tesztcélú `bloCKommander` adatbázis lenne.

Ezért DB-kapcsolat, migrációfuttatás és sémaírás nem történt.

A migrációfuttató a migráció checksumát a `schema_migrations` táblában tárolja, és egy connection tranzakciójában hajtja végre a még hiányzó fájlokat. Nincs automatikus down-migration. Jövőbeli integrációs futtatás kizárólag eldobható adatbázison történhet; visszaállítása az adatbázis drop/recreate művelete. Üzemi alkalmazáshoz külön backup és restore eljárás szükséges.

## 11. Teszteredmények

- Új repository/migráció unit és statikus tesztek: **27/27 PASS**.
- Graph-regresszió: **270/270 PASS**.
- Teljes Python-regresszió: **346/346 PASS**.
- Valódi PostgreSQL-integráció: **BLOCKED / NOT RUN**.

Az unit tesztek lefedik a teljes sorleképezés round-tripjét, stabil ID-ket, többlépéses route-ot, CROSS maskokat, unbound és BROKEN állapotot, verziókezelést, CAS vezérlési utat és hibainjektált transaction contextet.

Nem futottak valódi PostgreSQL-en:

- fresh és upgrade migráció;
- FK- és deferred trigger commit tesztek;
- párhuzamos SAVE;
- blokk/edge/route/wire pontokon injektált valódi rollback;
- legacy adatok változatlanságának DB-szintű bizonyítása.

## 12. BLOCKED képességek

- A 006 migráció végrehajthatóságának valódi PostgreSQL-bizonyítása.
- Deferred trigger INSERT/UPDATE/DELETE commit tesztje.
- Valós FK-, CAS-, konkurencia- és rollback-integráció.
- StageService teljes graph-aware SAVE/REBUILD bekötése, amely CK-03/B3 feladat.
- HTTP/Fabric graph-integráció.

## 13. CK-03/B3 előfeltételek

1. Külön, eldobható PostgreSQL adatbázis és explicit tesztkapcsolat.
2. A 006 migráció fresh és 005→006 upgrade PASS.
3. SINGLE/CROSS deferred trigger mátrix PASS valódi commitokkal.
4. Két connection CAS-teszt PASS.
5. Minden snapshot-írási fázis utáni rollback PASS.
6. Legacy Stage rekordok és blokkadatok változatlansága igazolt.
7. Csak ezután köthető a StageService SAVE/REBUILD az új repository API-ra.

## 14. GO/NO-GO

**GO** a migráció és repository-kód további review-jára, valamint egy izolált tesztadatbázis létrehozása után a PostgreSQL-integrációs tesztfutásra.

**NO-GO a CK-03/B3 implementáció automatikus megkezdésére**, mert a PostgreSQL trigger-, CAS- és rollback-kapu az izolált tesztkörnyezet hiánya miatt még nem teljesült.

## Függelék – START → STOP tényleges rekordpélda

Példaazonosítók:

```text
stage_id = 7001…
START instance_id = 7002…
STOP instance_id = 7003…
edge_id = 7004…
route_id = 7005…
```

`stage_blocks`:

```text
(7002…, 7001…, START, 0,0,0, NORTH, "Indulás", ...)
(7003…, 7001…, STOP,  3,0,0, NORTH, "Megállás", ...)
```

`graph_edges`:

```text
(7004…, 7001…, 7002…, out, 7003…, in, CONTROL)
```

`edge_routes`:

```text
(7005…, 7001…, 7004…, 7002…, out, 7003…, in, VALID)
```

`route_steps`:

```text
(7005…, 7001…, 0, 1,0,0, SINGLE)
(7005…, 7001…, 1, 2,0,0, SINGLE)
```

`wire_cells`:

```text
(7001…, 1,0,0, SINGLE)
(7001…, 2,0,0, SINGLE)
```

`wire_channels`:

```text
(7001…, 1,0,0, SINGLE, 10)  # EAST + WEST
(7001…, 2,0,0, SINGLE, 10)
```

LOAD során a `step_index` visszaadja az útvonalsorrendet, a channel-maskok visszaadják a fizikai csatlakozásokat, az edge és route UUID változatlan marad. A Minecraft-világ visszaépítése nem repository-feladat, és ebben a műszakban nem történt meg.
