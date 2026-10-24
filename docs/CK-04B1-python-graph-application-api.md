# CK-04/B1 – Python Graph Application API

> Dátum: 2026-10-24  
> Kiinduló commit: `2db713c5fa2a35c6eb9b963f0e2b3ae09adf5cc0`  
> Hatókör: Python application/HTTP API, in-memory Stage workspace, SAVE nélkül

## 1. Eredmény

A CK-04/B1 elkészítette a meglévő Stage/Graph domain verziózott, idempotens application és HTTP határát. Egy Stage-relatív E–W SINGLE `WireCell` felvihető úgy, hogy nem keletkezik `GraphEdge`; a külön `BIND_EDGES` parancs a támogatott START–wire–STOP geometriából pontosan egy CONTROL edge-et és egy VALID route-ot hoz létre.

Nem változott Kotlin/Fabric kód, Minecraft resource vagy világ, PostgreSQL-séma, SAVE/REBUILD szerződés, deployment vagy Docker-erőforrás.

## 2. Architektúra

```text
Flask HTTP endpoint
  → szigorú GraphCommand DTO parser + transport auth
  → GraphApplicationService
      → bounded, epoch-bound request-id registry
      → minimális topology discovery
      → StageService.read_graph_context()/apply_graph_operation()
          → Stage RLock + owner/epoch/revision check
          → meglévő immutable graph domain
              replace_content() / port_connection_position() / bind_edges()
```

- `python-service/src/hangar/graph_application.py`: DTO/adapter, auth, idempotencia, response serializer, wire wrapper és minimális discovery.
- `python-service/src/hangar/stage.py`: új, read-only `StageGraphContext` és `StageService.read_graph_context()`. A hiteles módosítási kapu változatlanul `apply_graph_operation()`.
- `python-service/src/ck_webhook.py`: három vékony graph endpoint; nincs topológiai szabály a Flask route-okban.

A Python workspace a mentetlen graph/layout állapot hiteles forrása. B1 nem hív repository SAVE API-t.

## 3. Végleges HTTP endpointok

| Metódus | Útvonal | Feladat |
|---|---|---|
| `GET` | `/api/v1/stages/<stage_id>/graph?player_uuid=<uuid>` | Hiteles graph/layout reconciliation snapshot |
| `POST` | `/api/v1/stages/<stage_id>/graph/wire` | Egy wire cell felvitele, cseréje vagy törlése |
| `POST` | `/api/v1/stages/<stage_id>/graph/bind` | Explicit Élbekötés |

Mindhárom endpoint megköveteli az `X-BlockCommander-Graph-Secret` headert. A POST body `stage_id` értékének egyeznie kell az URL-lel.

## 4. Command DTO

Közös envelope:

```json
{
  "schema_version": 1,
  "request_id": "UUID",
  "stage_id": "UUID",
  "player_uuid": "UUID",
  "workspace_epoch": "UUID",
  "expected_working_revision": 2,
  "operation": "WIRE_LAYOUT vagy BIND_EDGES",
  "payload": {}
}
```

Minden felsorolt mező kötelező. Az ismeretlen mező, hibás UUID/típus, negatív revision, endpointtal nem egyező operation vagy Stage ID strukturált 400-as hibát ad. A `schema_version` jelenleg kizárólag `1` lehet. Boolean nem fogadható el integerként.

### 4.1 Wire payload

```json
{
  "operation": "WIRE_LAYOUT",
  "payload": {
    "changes": [
      {
        "position": {"x": 1, "y": 0, "z": 0},
        "cell": {
          "channels": [
            {"channel": "SINGLE", "connections": ["EAST", "WEST"]}
          ]
        }
      }
    ]
  }
}
```

- A pozíció Stage-relatív `GridPos`.
- `cell: null` törlést jelent.
- Nem-null cellából közvetlenül a meglévő `WireCell`/`WireChannelState` domainobjektum készül; nincs második graph reprezentáció.
- A DTO alakja több változtatást is képes hordozni, de B1 application limitként pontosan egy `changes` elemet fogad. Más elemszám `UNSUPPORTED_BATCH_SIZE`.
- A wrapper Stage-határt és node-ütközést ellenőriz, majd `replace_content()` segítségével alkalmaz.
- Wire módosítás nem hív `bind_edges()`-t és nem hoz létre edge-et.
- Meglévő edge/route esetén a wire törlése megtartja a logikai edge-et és a route-ot; a revision változása miatt a graph DRAFT/CHECK_REQUIRED lesz. Automatikus edge-törlés vagy route-javítás nincs.

### 4.2 Bind payload

```json
{
  "operation": "BIND_EDGES",
  "payload": {}
}
```

A discovery a jelenlegi workspace-ből dolgozik, a `ck-control-v1` katalógust és `port_connection_position()` orientációs függvényt használja, majd `RouteCandidate` → `BindEdgesCommand` → `bind_edges()` útvonalon halad. A domaineredményt a StageService egy snapshotcserével alkalmazza.

## 5. Response DTO

Sikeres command válasz fő mezői:

```json
{
  "api_version": "ck-graph-v1",
  "status": "ok",
  "request_id": "UUID",
  "stage_id": "UUID",
  "workspace_epoch": "UUID",
  "working_revision": 4,
  "modified": true,
  "diagnostics": [],
  "wire_diff": [],
  "state": {
    "dirty": true,
    "status": {
      "graph": "DRAFT",
      "edge_indicator": "CHECK_REQUIRED",
      "save": "DIRTY",
      "layout": "PRESENT",
      "program_validation": "NOT_CHECKED"
    },
    "nodes": [],
    "wire_cells": [],
    "edges": [],
    "routes": [],
    "validation": {"revision": null, "diagnostics": []}
  }
}
```

A teljes state tartalmazza az origót/méretet, kompatibilitási envelope-ot, stabil node/edge/route ID-kat, endpoint portokat, rendezett route stepeket és wire csatornákat. A bind response ezen felül `binding.created_edge_ids` és `binding.reused_edge_ids` listát ad.

A hibák `status=error`, `error_code`, `message`, `diagnostics[]` és – ha a command parse-olható és a Stage feloldható – request/stage/epoch/revision mezőket adnak. A domain diagnostic code/severity és node/port/edge/wire referenciák megmaradnak. Traceback nem kerül HTTP-válaszba.

Fontos: a VALID `EdgeRoute` nem állítja automatikusan teljesen VALID állapotúvá a graphot vagy a programot. A meglévő domain szerződés szerint a binding nem rögzít teljes validációs reportot; az összesített státusz ezért DRAFT/CHECK_REQUIRED maradhat.

## 6. Epoch, revision és idempotencia

Az idempotenciakulcs:

```text
(stage_id, workspace_epoch, player_uuid, request_id)
```

A rekord SHA-256 kanonikus request-lenyomatot és a teljes végleges HTTP/application választ tárolja.

- Azonos kulcs + azonos szemantikai payload: az eredeti status/body másolata tér vissza; nincs új domainhívás.
- Azonos kulcs + eltérő payload: `409 REQUEST_ID_CONFLICT`.
- Új request-id + stale revision: `409 STALE_WORKING_REVISION`.
- Stale epoch: `409 STALE_WORKSPACE_EPOCH`.
- No-op: `modified=false`, nincs revisionlépés.
- Ismételt bind új request-id-val a domain szemantikai kulcsa alapján ugyanazt az edge-et/route-ot használja, és nem növeli a revisiont.

Az első implementáció processzmemóriás `OrderedDict` registryt használ, alapértelmezett 1024 rekordos globális limittel. LRU-jelleggel a legrégebbi rekord távozik. Sikeres epochváltás utáni első command törli az adott Stage korábbi epochjainak rekordjait; `clear_idempotency()` explicit ürítést biztosít.

Egy `GraphApplicationService` példányon belül egy application `RLock` sorosítja a graph commandokat. Lock-sorrend: application lock → Stage `RLock`; nincs StageService → application callback, ezért nincs fordított lockút. Az idempotencia lookup, Stage módosítás és response-record ugyanabban az application kritikus szakaszban történik. Párhuzamos azonos request-id teszt szerint egyetlen módosítást okoz.

Korlátok:

- Nincs restartokon vagy több Python processen átívelő exactly-once garancia.
- Több worker külön request-registryt tartana; B2 előtt az üzemeltetési worker-modellt rögzíteni kell, vagy tartós/megosztott idempotencia szükséges.
- A cache eviction után egy nagyon régi retry már stale revisionként utasítható el; reconciliation GET használható.
- A legacy blokk CRUD nem az application lockot használja, de a Stage revision-check megakadályozza az elvesző graph update-et. A graph response egy későbbi, párhuzamos blokkedit utáni állapotot is láthat; tartalmi részleges swap nem történik.

## 7. Transport trust modell

Konfiguráció:

```text
BLOCKCOMMANDER_GRAPH_SHARED_SECRET=<erős véletlen titok>
```

- A titok nincs hardcode-olva, nincs repository-fájlba írva és nincs logolva.
- A graph endpointok a `X-BlockCommander-Graph-Secret` headert konstans idejű `hmac.compare_digest()` ellenőrzéssel vizsgálják.
- Hiányzó szerverkonfiguráció: `503 GRAPH_AUTH_NOT_CONFIGURED` – fail closed.
- Hiányzó vagy hibás kliensheader: `401 INVALID_GRAPH_AUTH`.
- A GET ugyanilyen védelem alatt áll, és ezen felül Python owner-checket futtat.

A secret a Fabric szerverfolyamatot hitelesíti. Nem bizonyítja önmagában, hogy a body `player_uuid` valódi; B2-ben a Fabricnek ezt kizárólag a szerveroldali `ServerPlayer` kontextusból szabad előállítania. A secret ismerője játékost imitálhat, ezért fájljogosultság, loopback binding és secret-rotáció továbbra is üzemeltetési követelmény.

## 8. Támogatott topológia

B1 explicit támogatott geometria:

```text
START: type=START, position=(0,0,0), facing=EAST
WIRE:  position=(1,0,0), SINGLE, connections={EAST,WEST}
STOP:  type=STOP,  position=(2,0,0), facing=EAST
EDGE:  START.out → STOP.in, CONTROL
```

A START FRONT/out és STOP BACK/in portja az orientációs domain szerint ugyanarra a wire cellára mutat. A discovery determinisztikus és pontosan egy wire cellát követel.

Elutasított B1 geometriák:

- hiányzó wire: `MISSING_WIRE`;
- eltérő node pozíció vagy orientáció;
- N–S SINGLE, több wire cella, kanyar, T, CROSS vagy más hálózat: `UNSUPPORTED_TOPOLOGY`;
- hibás wire domainalak: `INVALID_WIRE_CELL`;
- Stage-en kívüli koordináta: `WIRE_POSITION_OUT_OF_BOUNDS`;
- node-dal azonos cella: `WIRE_OCCUPIES_NODE`.

Általános útvonalbejárás és CROSS discovery tudatosan nem készült el.

## 9. Reconciliation

A GET endpoint az aktuális hiteles Python workspace immutable vetítését adja. Nem hív `replace_content()`, nem módosít revisiont és nem ír adatbázist. A Fabric ebből később helyreállíthatja:

- az epoch/revision envelope-ot;
- a Stage origót és méretet;
- a wire cellákat és csatornákat;
- az edge/route ID-kat és státuszokat;
- a graph/save/layout státuszt és validációs diagnosztikát.

Elveszett command response esetén először ugyanazzal a request-id/payloaddal ismételhető a kérés. Cache miss/eviction vagy bizonytalan kliensállapot esetén a GET a hiteles visszaállítási út.

## 10. HTTP státuszkódok

| Kód | Jelentés |
|---:|---|
| 200 | Siker vagy idempotens/no-op command; sikeres GET |
| 400 | DTO forma-, típus-, verzió-, UUID-, operation- vagy ismeretlenmező-hiba |
| 401 | Hiányzó/hibás shared secret |
| 403 | Nem tulajdonos |
| 404 | Stage nem található |
| 409 | Request-id konfliktus, stale epoch/revision vagy más alkalmazási konfliktus |
| 422 | Érvényes forma, de hibás/unsupported wire/topológia vagy domain reject |
| 503 | Graph shared secret nincs konfigurálva |
| 500 | Nem várt belső hiba; generikus üzenet, traceback nélkül |

## 11. Teszteredmények

Izolált fixture-ök készültek a `test_graph_application.py` és `test_graph_http_api.py` fájlokban. Nem futott PostgreSQL, Docker vagy Minecraft.

### B1 célkapu

```powershell
$env:PYTHONPATH="$PWD\src;$PWD\tests"
& .\.venv\Scripts\python.exe -m unittest -q test_graph_application test_graph_http_api
```

Eredmény: **28/28 PASS**.

Lefedés: wire upsert/replace/remove, edge nélküli wire, explicit bind, VALID route, DIRTY/no SAVE, request-id replay/collision, stale revision/epoch, no-op, repeated bind, párhuzamos azonos request, cache limit/clear/epoch-expiry, owner, koordináta/cella, missing wire, orientáció, unsupported topology, domain rollback, auth fail-closed, GET/retry reconciliation és traceback-mentes strukturált HTTP hiba.

### CK-02 graph regresszió

```powershell
$env:PYTHONPATH="$PWD\src;$PWD\tests"
& .\.venv\Scripts\python.exe -m unittest -q test_graph_adapter test_graph_binding test_graph_destruction test_graph_diagnostics test_graph_lifecycle test_graph_model test_graph_orientation test_graph_state test_graph_validation
```

Eredmény: **270/270 PASS**.

### CK-03 Stage/snapshot/repository regresszió

```powershell
$env:PYTHONPATH="$PWD\src;$PWD\tests"
& .\.venv\Scripts\python.exe -m unittest -q test_stage_snapshot test_stage_snapshot_adapter test_repository_graph_snapshot test_stage_service test_stage_graph_service test_stage_graph_persistence_service
```

Eredmény: **70/70 PASS**.

### Teljes Python regresszió

```powershell
$env:PYTHONPATH="$PWD\src"
& .\.venv\Scripts\python.exe -m unittest discover -s tests -q
```

Eredmény: **383/383 PASS**.

A valódi B2-T PostgreSQL-integrációs tesztek nem futottak újra; korábbi eredményük nem minősül ebben a műszakban friss tesztnek.

## 12. Ismert kockázatok és B2 előfeltételek

CK-04/B2 előtt szükséges:

1. A Fabric szerver kapja meg biztonságosan ugyanazt a shared secretet, és kizárólag szerveroldali `ServerPlayer.uuid` értéket küldjön.
2. A Kotlin Stage DTO őrizze meg az epoch/revision envelope-ot és a reconciliation state-et.
3. A Fabric wire placement csak Python-elfogadás után írjon világot; klienspredikció/rollback viselkedése izoláltan tesztelendő.
4. A B2 maradjon az egycellás E–W SINGLE geometriánál, vagy külön SysOrg döntéssel bővítse a discoveryt. CROSS nem következik automatikusan B1-ből.
5. Rögzíteni kell az éles Python worker-modellt. Több process esetén a processzlokális idempotencia nem elég exactly-once-hoz.
6. Ki kell dolgozni a response-lost/render-failure reconciliation UX-et.
7. A shared secret rotációja, fájljogosultsága és loopback/network policy deployment-review tárgya; B1 nem módosított deploy konfigurációt.
8. A graph GET teljes Stage layoutadatot ad, ezért naplózás/cache/proxy oldalon érzékeny alkalmazásadatként kezelendő.

## 13. Kapudöntés

**CK-04/B1: GO szakmai/SysOrg review-ra.**

A kiadott Python-szelet funkcionálisan és regressziósan zöld. **CK-04/B2 csak CONDITIONAL GO**: a Fabric secret-injektálás, szerveroldali player identity, epoch/revision tárolás, Python-first world-write sorrend és egyprocesszes vagy megosztott idempotencia-döntés review-ja szükséges. B2 ebben a műszakban nem indult el.
