# CK-03/B2-T – PostgreSQL integrációs kapu

- Dátum: 2026-10-23
- Hatókör: eldobható PostgreSQL-környezet, migráció, StageSnapshot SAVE/LOAD, CAS, rollback és wire-integritás
- Eredmény: **GO – minden CK-03/B2-T elfogadási feltétel PASS**
- Biztonsági állapot: az üzemi `bloCKommander` adatbázishoz nem történt kapcsolódás, migráció vagy írás

## 1. Környezet és PostgreSQL-verzió

A Windows fejlesztői gépen nem volt Docker CLI. A Nagypapi Pageant-alapú, batch SSH-kapcsolattal volt biztonságosan elérhető:

- host: `192.168.3.4`, SSH port `2227`;
- Docker Engine: `29.1.4`;
- Docker Compose: `v5.0.1`;
- cél PostgreSQL: `18.2 (Debian 18.2-1.pgdg13+1)`;
- rögzített image: `postgres@sha256:b6b4d0b75c699a2c94dfc5a94fe09f38630f3b67ab0e1653ede1b7ac8e13c197`;
- Python: 3.12-es projekt-venv;
- driver: `psycopg2-binary==2.9.11`.

A felderítés igazolta, hogy az 5434 foglalt. A meglévő PostgreSQL portok 5433, 5434, 5435 és 5436 voltak; a B2-T a felderítéskor szabad 5437-et választotta.

## 2. Docker/Compose izoláció

A konfiguráció helye: `python-service/tests/integration/postgres/`.

Az izoláció elemei:

- Compose project: `blockcommander-ck03-b2t`;
- container: `blockcommander-ck03-b2t-postgres`;
- volume: `blockcommander-ck03-b2t_pgdata`;
- network: `blockcommander-ck03-b2t_default`;
- adatbázis: `blockcommander_b2t`;
- alkalmazási tesztrole: `blockcommander_b2t`;
- port: kizárólag Nagypapi loopback `127.0.0.1:5437 → 5432`;
- kliensút: ideiglenes Pageant SSH tunnel a helyi `127.0.0.1:55437` portról;
- restart policy: `"no"`;
- healthcheck: `pg_isready`;
- külső Docker-hálózat vagy publikus host binding nincs.

A PostgreSQL bootstrap role külön `postgres`. Az alkalmazási tesztrole az adatbázis tulajdonosa, de `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`, `NOREPLICATION`. A repositoryban szereplő jelszó kizárólag ismert, eldobható tesztjelszó; üzemi titkot nem tartalmaz. A bootstrap jelszó futásonként véletlen, csak a Nagypapin lévő, 0600 módú teszt `.env` fájlban él.

## 3. A tesztcélpont azonosítása

A futtatás fail-closed: nincs fallback a repository alapértelmezett DB-konfigurációjára. Kötelező környezeti változók nélkül a külön integrációs program már importkor megtagadja a futást.

Az ellenőrzési lánc:

1. teljes, 64 karakteres Docker container ID;
2. Compose project label;
3. `com.blockcommander.environment=ck03-b2t` label;
4. `com.blockcommander.identity=5f4b53b6-2c82-4d68-b788-7860daf2b70f` label;
5. pontos loopback portkötés;
6. volume Compose project és identity label;
7. network Compose project label;
8. adatbázison belüli, initkor létrehozott identity rekord;
9. `current_database()`, `current_user`, szerverport és PostgreSQL major verzió;
10. a tesztrole tiltott jogosultságainak ellenőrzése.

A végleges futó container ID:

`baabf4692b1724ad7728e1baa62dff4127ddec0661250a5cee43738e3a82b166`

Bármely eltérés blokkolja a readiness, teszt, stop és cleanup műveletet.

## 4. Migrációs eredmények

### Fresh lánc

Üres `blockcommander` sémán a 001–006 migrációs lánc PASS. Ellenőrzött eredmények:

- mind az öt graph/wire tábla létrejött;
- a két deferred constraint trigger létezik, `tgdeferrable=true` és `tginitdeferred=true`;
- FK-, UNIQUE- és CHECK-szabályok valódi írásokkal működnek;
- minden migráció bekerült a `schema_migrations` táblába;
- az ismételt `migrate()` idempotens.

Az első valódi futás PostgreSQL-hibát talált a 006-ban: a `smallint::bit(4)` közvetlen cast nem megengedett PostgreSQL 18-on. Mivel a 006 minden próbája ugyanabban a tranzakcióban visszagördült, és bizonyítottan nem került alkalmazásra megőrzendő adatbázison, helyben javítható volt. A végleges kifejezés:

```sql
bit_count(connections_mask::integer::bit(4))
```

### 005 → 006 upgrade

A teszt új sémát migrált 005-ig, legacy Stage-et és blokkot írt, majd futtatta a repository migrátorát. PASS:

- blokk UUID, típus, koordináták, orientáció, label és description változatlan;
- `saved_version=0`;
- `graph_snapshot_version=0`;
- `port_catalog_version=NULL`.

## 5. Deferred trigger tesztmátrix

Minden eset valódi tranzakció és tényleges commit vagy commitkori elutasítás volt.

| Eset | Eredmény |
|---|---|
| SINGLE + pontosan SINGLE | PASS |
| CROSS + NS és EW | PASS |
| SINGLE csatorna nélkül | REJECT + rollback PASS |
| SINGLE extra/hibás NS csatornával | REJECT + rollback PASS |
| CROSS csak NS csatornával | REJECT + rollback PASS |
| CROSS csak EW csatornával | REJECT + rollback PASS |
| CROSS duplikált csatornával | REJECT + rollback PASS |
| CROSS hibás NS connection maskkal | REJECT + rollback PASS |
| SINGLE hibás connection maskkal | REJECT + rollback PASS |
| SINGLE → CROSS teljes átépítéssel | PASS |
| CROSS → SINGLE teljes átépítéssel | PASS |
| CROSS → SINGLE csatornaátépítés nélkül | REJECT + eredeti állapot megmaradt |
| CROSS egyik csatornájának DELETE-je | REJECT + eredeti állapot megmaradt |
| Channel UPDATE hibás végállapotra | REJECT + eredeti állapot megmaradt |
| Cell és channel együtt mozgatva | PASS |
| Csak cellakoordináta mozgatva | REJECT + eredeti állapot megmaradt |
| Csak csatornakoordináta mozgatva | REJECT + eredeti állapot megmaradt |

Az OLD és NEW koordináták együttes ellenőrzése nem hagyott integritási rést.

## 6. CAS és rollback bizonyíték

### Két valódi connection CAS

A teszt két külön psycopg2 kapcsolatot és két threadet használt. Az első connection valódi `SELECT ... FOR UPDATE` után tesztoldali wrapperben megállt. A második connection `pg_stat_activity.wait_event_type='Lock'` állapotát egy harmadik kapcsolat igazolta.

Az első kliens `WINNER` tartalommal commitolt, a második `LOSER` tartalommal indult ugyanabból a 0-s verzióból. A lock feloldása után:

- első SAVE: PASS, új verzió 1;
- második SAVE: `StageVersionConflict`;
- végső label: `WINNER`, tehát a vesztes nem írt felül részletet;
- végső `saved_version=1`.

### Valódi rollback

Kontrollált, kizárólag tesztoldali cursor wrapper dobott hibát a tényleges SQL végrehajtása után az alábbi hét ponton:

1. StageBlock INSERT;
2. GraphEdge INSERT;
3. EdgeRoute INSERT;
4. WireStep INSERT;
5. WireCell INSERT;
6. WireChannel INSERT;
7. Stage-verzió UPDATE után, commit előtt.

Minden eset után új kapcsolaton végzett LOAD teljes `StageSnapshot`-egyezést és változatlan `saved_version=1` értéket igazolt. A publikus üzemi repository API nem változott.

## 7. Teljes StageSnapshot round-trip

A konkrét fixture tartalmazott:

- START és STOP blokkot stabil UUID-kkel;
- `START.out → STOP.in` CONTROL edge-et;
- kötött, rendezett, kétlépéses route-ot;
- SINGLE cellákat EAST+WEST maskkal;
- label és description mezőket;
- route nélküli második edge-et;
- edge nélküli BROKEN route-ot hiányzó WireCellre mutató steppel;
- unbound wire cellákat és CROSS cellát;
- NOT_CHECKED, azaz DRAFT programszemantikát.

A `SAVE → közvetlen SQL-sorellenőrzés → LOAD` út PASS. Bizonyított:

- stabil block-, edge- és route-UUID;
- route-step indexsorrend és koordináták;
- blokkpozíció és NORTH orientáció;
- portkulcsok;
- label/description;
- katalógusból visszatöltött START/STOP display material;
- `graph_snapshot_version=1` és aktuális portkatalógus-verzió;
- `saved_version` pontosan 0 → 1;
- a bemeneti working revision változatlanul 12 maradt;
- LOAD után a dokumentált process-lokális working revision 0;
- kompatibilis snapshotnál `compatible_for_graph_mutation=true`;
- ismeretlen graph- vagy portkatalógus-verziónál read-only betöltés és kompatibilitási hiba.

Sem a BROKEN, sem a DRAFT állapot nem lett automatikusan VALID.

## 8. Legacy kompatibilitás

A 005→006 fixture valódi `load_full_stage_snapshot()` hívása:

- megtartotta a legacy blokkot és metaadatait;
- nem talált ki edge-et, route-ot vagy wire-t;
- `compatible_for_graph_mutation=false` értéket adott;
- explicit upgrade-ig version 0 maradt.

Graph-aware SAVE után a legacy `save_stage_snapshot()` valós adatbázison `SnapshotCompatibilityError` hibával megtagadta a graph snapshot felülírását.

## 9. Regressziós eredmények

| Tesztcsoport | Eredmény |
|---|---:|
| CK-03/B2 repository unit/statikus | 27/27 PASS |
| Graph-regresszió | 270/270 PASS |
| Teljes Python-regresszió | 346/346 PASS |
| Valódi PostgreSQL-integráció | 13/13 PASS |
| SKIPPED | 0 |
| BLOCKED | 0 |
| végleges FAIL | 0 |

Az integrációs fájl neve `postgres_graph_integration.py`, ezért nem illeszkedik a normál `test*.py` discovery mintára. Kizárólag a fail-closed `run-tests.ps1` indítja.

## 10. Nyitott hibák és kockázatok

- Nyitott CK-03/B2-T funkcionális hiba nincs.
- A futtatás Pageant kulcsot, PuTTY `plink`/`pscp` programot és a Nagypapi elérhetőségét igényli.
- A host- és tunnel-port futás előtt újra ellenőrzendő; ütközésnél a script leáll.
- Az ismert tesztjelszó csak a loopbackre kötött, eldobható környezetben elfogadható. A port publikus kitétele esetén a konfiguráció biztonsági szempontból érvénytelenné válna.
- A container ID minden tiszta újralétrehozásnál változik; a state és a Docker metadata együtt a mérvadó.
- A jelen futás végén a séma az utolsó integrációs teszt fixture-ét tartalmazza, ezért kizárólag tesztadatként kezelendő.
- CK-03/B3, Kotlin, Fabric és HTTP integráció nem készült és nem indult el.

## 11. Módosított fájlok

A B2-T műszak közvetlen módosításai:

- `python-service/src/hangar/migrations/006_graph_snapshots.sql` – PostgreSQL 18 kompatibilis bit cast;
- `python-service/tests/integration/postgres/compose.yaml`;
- `python-service/tests/integration/postgres/init-test-identity.sql`;
- `python-service/tests/integration/postgres/common.ps1`;
- `python-service/tests/integration/postgres/start.ps1`;
- `python-service/tests/integration/postgres/readiness.ps1`;
- `python-service/tests/integration/postgres/run-tests.ps1`;
- `python-service/tests/integration/postgres/stop.ps1`;
- `python-service/tests/integration/postgres/cleanup.ps1`;
- `python-service/tests/integration/postgres/postgres_graph_integration.py`;
- `docs/CK-03B2T-postgresql-integracios-kapu.md`.

A worktree emellett tartalmazza a korábbi CK-03/B2 nem commitolt repository-, snapshot-, unit teszt- és dokumentációs változásait is; ezek nem kerültek visszaállításra vagy felülírásra.

## 12. Git státusz

A B2-T lezárásakor a worktree szándékosan nem tiszta: a CK-03/B2 és CK-03/B2-T források még nem commitoltak. Commit és push nem történt. Generált `.env`, adatbázisjelszó vagy state fájl nincs a Git worktree-ben; a runtime state a Windows ideiglenes könyvtárában van.

A záró `git status --short` lényegi kimenete:

```text
 M python-service/src/hangar/migrations/006_graph_snapshots.sql
AM python-service/tests/integration/postgres/cleanup.ps1
AM python-service/tests/integration/postgres/common.ps1
AM python-service/tests/integration/postgres/compose.yaml
AM python-service/tests/integration/postgres/init-test-identity.sql
AM python-service/tests/integration/postgres/postgres_graph_integration.py
AM python-service/tests/integration/postgres/readiness.ps1
AM python-service/tests/integration/postgres/run-tests.ps1
AM python-service/tests/integration/postgres/start.ps1
AM python-service/tests/integration/postgres/stop.ps1
?? docs/CK-03B2T-postgresql-integracios-kapu.md
```

Az index staging állapotát ez a műszak nem rendezte át. A végleges working-tree `git diff --check` hiba nélkül futott; csak a Windows munkapéldány szokásos LF→CRLF figyelmeztetése jelent meg. A korábban staged új fájlverziók még tartalmazzák a javítás előtti felesleges EOF üres sort, ezért commit előtt az indexet tudatosan frissíteni kell; automatikus `git add` nem történt.

## 13. GO/NO-GO javaslat

**CK-03/B2-T: GO.**

Minden kötelező adatbázis-kapu valós PostgreSQL 18.2-n PASS, a teljes Python-regresszió zöld, és az üzemi adatbázis érintetlen maradt. A CK-03/B2 korábbi PostgreSQL-integrációs BLOCKED állapota feloldható.

Ez nem automatikus felhatalmazás CK-03/B3, deploy vagy üzemi migráció megkezdésére. A következő fázis előtt SysOrg szakmai review és külön feladatdöntés szükséges.

## 14. Megmaradt környezet és biztonságos lebontás

A jelentés elkészítésekor a B2-T konténer **running/healthy**, a named volume megmaradt. Automatikus teardown nem történt.

Readiness:

```powershell
& .\python-service\tests\integration\postgres\readiness.ps1
```

Új integrációs futás:

```powershell
& .\python-service\tests\integration\postgres\run-tests.ps1
```

Nem destruktív leállítás, a container és volume megtartásával:

```powershell
& .\python-service\tests\integration\postgres\stop.ps1
```

Destruktív cleanup csak külön emberi jóváhagyás után:

```powershell
& .\python-service\tests\integration\postgres\cleanup.ps1 -ConfirmCleanup
```

A cleanup előbb újra ellenőrzi a teljes container ID-t, a Compose projectet, a container/volume/network labeleket és a pontos portkötést. Eltérés esetén nem töröl. Nem futtat globális prune műveletet, és csak a B2-T Compose projekt ellenőrzött containerét, networkjét, volume-ját, távoli secret fájlját és helyi state fájlját távolítja el.

## Rövid összefoglaló

A CK-03/B2 perzisztencia immár nem csak mockokkal zöld. A 006 migráció valódi PostgreSQL 18 hibát fedett fel, a javítás után a fresh és upgrade utak, a deferred integritás, a teljes snapshot round-trip, a determinisztikus kétconnectionös CAS és mind a hét rollback-pont bizonyítottan működik. A szomszédos üzemi adatbázis nem vett részt a tesztben, a B2-T környezet pedig elkülönítve, vizsgálható állapotban megmaradt.
