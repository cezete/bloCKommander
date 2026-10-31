# CK-05/A4/1-FIX/1 – Python authoritative CK Wire placement-intent

## Státusz

**PASS**

A Python Graph application új, magas szintű placement-intent műveletet kapott. A Fabricnek nem kell és nem szabad többcellás authoritative changesetet számítania: kizárólag a célpozíciót és a játékosi intentet közli. A Python az aktuális immutable `GraphWorkspaceState`, a portkatalógus és a Stage-méret alapján egyetlen atomi állapotátmenetben hozza létre az új cellát és bővíti a kapcsolódó szomszédcellák kölcsönös maszkját.

A meglévő `WIRE_LAYOUT` és `BIND_EDGES` szerződés változatlan.

## Publikus command és HTTP API

Új operation:

```text
WIRE_PLACE_INTENT
```

Új endpoint:

```text
POST /api/v1/stages/{stage_id}/graph/wire/place
```

A transport-authentikáció, command schema, player-azonosítás, epoch/revision fencing és request-idempotencia megegyezik a meglévő Graph commandokéval.

### Kérés

```json
{
  "schema_version": 1,
  "request_id": "UUID",
  "stage_id": "UUID",
  "player_uuid": "UUID",
  "workspace_epoch": "UUID",
  "expected_working_revision": 12,
  "operation": "WIRE_PLACE_INTENT",
  "payload": {
    "position": {"x": 2, "y": 0, "z": 3},
    "intent": "AUTO"
  }
}
```

Támogatott intentek:

- `AUTO`: nulla, egy vagy két egyértelmű fizikai végpont automatikus kapcsolása;
- `CROSS`: explicit CROSS-kérés, amely csak mind a négy irány egyértelmű elérhetősége esetén fogadható el.

Az ismeretlen intent `422 UNSUPPORTED_INTENT` válasz. Preferált rész-iránylista ebben a mérföldkőben nincs; három vagy több `AUTO` lehetőség nem oldható fel önkényesen.

### Sikeres válasz

A normál Graph command envelope kiegészül:

```json
{
  "status": "ok",
  "modified": true,
  "working_revision": 13,
  "placement": {
    "intent": "AUTO",
    "affected_cells": []
  },
  "wire_diff": [],
  "discovery": {},
  "state": {}
}
```

- `state`: a teljes authoritative Graph snapshot;
- `discovery`: az új állapotból újraszámolt, read-only discovery DTO;
- `placement.affected_cells`: a létrehozott célcella és a ténylegesen módosított szomszédcellák végleges állapota;
- `wire_diff`: ugyanennek a meglévő Graph transport-formátumú változáslistája;
- `working_revision`: valós tartalomváltozásnál pontosan eggyel nő.

Az idempotens request-ID újrajátszása mély másolatként ugyanazt a választ adja, második Stage-módosítás nélkül.

## Python kapcsolódási szabályok

### Közös preflight

1. A cél Stage-relatív és a Stage határain belül van.
2. A célon nincs programblokk vagy meglévő WireCell.
3. A meglévő wire layout nem tartalmaz duplikált cellapozíciót.
4. A közvetlen szomszéd csak akkor potenciális végpont, ha a megfelelő csatornája kölcsönösen bővíthető meglévő kapcsolat elvesztése nélkül.
5. A programblokkport iránya kizárólag a `CK_CONTROL_PORT_CATALOG`, a blokk orientációja és a meglévő `port_connection_position()` alapján számítható.
6. Ha egy irányban több fizikai forrás lenne, a placement többértelmű.

### `AUTO`

| Egyértelmű végpontok száma | Eredmény |
| --- | --- |
| 0 | nullavégű `SINGLE` DRAFT |
| 1 | egyvégű `SINGLE`, kölcsönös szomszédmaszk-frissítéssel |
| 2 | egyenes vagy sarok `SINGLE` |
| 3–4 | `AMBIGUOUS_PLACEMENT`, nulla módosítás |

A `SINGLE` továbbra is legfeljebb két irányt tartalmazhat. A Python nem választ ki kettőt három vagy négy potenciális kapcsolatból, és négy szomszédból nem készít implicit CROSS-t.

### `CROSS`

Az explicit `CROSS` csak akkor érvényes, ha NORTH, EAST, SOUTH és WEST irányban is pontosan egy biztonságosan kapcsolható wire-végpont vagy port található. A célcella két külön csatornája:

- `NS = NORTH + SOUTH`;
- `EW = EAST + WEST`.

A szomszédcellák kölcsönös irányai frissülnek, de az NS és EW csatorna nem egyesül. A discovery a CROSS két tengelyét külön fizikai komponensként járja be.

### Meglévő szomszédok védelme

A művelet kizárólag hozzáadja a cél felé mutató reciprok irányt. Meglévő irányt nem töröl és nem cserél le. Ha a szomszéd `SINGLE` csatornája már két másik iránnyal foglalt, a placement `INVALID_CONNECTION`; nem jön létre mellette hallgatólagosan leválasztott cella.

## Atomikusság és fencing

Az application útvonal:

1. a közös `_execute()` ellenőrzi az owner jogosultságot, workspace epochot, working revisiont és request-ID-t;
2. a StageService ugyanazon epoch/revision alapján, Stage-lock alatt adja át az aktuális `GraphWorkspaceState` értéket;
3. `place_wire_intent()` csak lokális immutable jelöltobjektumokat épít;
4. minden cél- és szomszédcella domainkonstruktora lefut commit előtt;
5. az összes végleges cella egyetlen `replace_content()` hívásban kerül a candidate state-be;
6. a StageService egyetlen snapshotcserével alkalmazza a teljes eredményt.

Következmények:

- siker esetén legfeljebb egy working-revision átmenet;
- hiba esetén nincs részleges szomszédfrissítés;
- stale epoch vagy revision esetén a domainművelet nem fut le;
- request replay nem módosít másodszor;
- a candidate state immutable;
- SAVE, adatbázis-írás vagy világmódosítás nem történik.

## Graph- és route-védelem

A placement csak a `wire_cells` kollekciót cseréli. Változatlan marad:

- `GraphEdge`;
- `EdgeRoute`, annak ID-ja és státusza;
- programblokkok és portazonosságok;
- `saved_version`;
- perzisztált PostgreSQL snapshot.

A művelet nem hív `bind_edges()`-t, nem hoz létre vagy töröl edge-et, nem ír `BROKEN` státuszt, nem végez SAVE/PUBLISH műveletet. A discovery kizárólag a sikeres candidate state-ből származtatott válaszadat.

## Strukturált elutasítások

| Kód | Jelentés |
| --- | --- |
| `AMBIGUOUS_PLACEMENT` | három vagy több AUTO végpont, illetve egy irányban több forrás |
| `STAGE_BOUNDARY` | célpozíció Stage-en kívül |
| `OCCUPIED_CELL` | célpozíción node vagy WireCell van |
| `INVALID_CONNECTION` | szomszéd nem bővíthető biztonságosan vagy a CROSS nem teljes |
| `UNSUPPORTED_INTENT` | ismeretlen placement intent |
| `STALE_WORKSPACE_EPOCH` | elavult lifecycle |
| `STALE_WORKING_REVISION` | elavult vagy eltérő revision |

A placement hibák a felső szintű `diagnostics` listában jelennek meg. A sikeres állapot fizikai DRAFT-diagnosztikája továbbra is a külön `discovery.diagnostics` mezőben található; bind domainhibát a placement nem állít elő.

## Teszteredmények

Futtatási környezet: `python-service/.venv`, `PYTHONPATH=src;tests`. PostgreSQL-, webhook- vagy Minecraft-processz nem kellett.

### Célzott application/API kapu

```text
python -m unittest -q \
  test_graph_placement_intent \
  test_graph_application_discovery \
  test_graph_application \
  test_graph_http_api

Ran 64 tests
OK
```

### Teljes Graph-regresszió

```text
python -m unittest discover -s tests -p "test_graph_*.py" -q
Ran 346 tests
OK
```

### Teljes Python suite

```text
python -m unittest discover -s tests -q
Ran 432 tests
OK
```

A tesztek lefedik:

- nulla, egy és két szomszéd;
- egyenes és sarok;
- három- és négyszomszédos ambiguity;
- explicit CROSS és csatornafüggetlenség;
- meglévő szomszédkapcsolat megőrzése;
- nem bővíthető szomszéd elutasítása;
- programblokkport;
- foglalt cél és Stage-határ;
- epoch/revision fencing és request-idempotencia;
- edge/route változatlanság;
- discovery újraszámolása;
- explicit CROSS-hiba teljes rollbackje;
- ismeretlen intent;
- HTTP transport és authoritative response;
- CK-04 E–W `WIRE_LAYOUT` regressziója.

## Ismert korlátok

- `AUTO` nem fogad preferált iránylistát. Három vagy több potenciális végpont feloldásához később új, verziózott explicit intent szükséges.
- Az explicit CROSS mind a négy egyértelmű végpontot megköveteli; félkész CROSS placement nincs.
- A remove/break útvonal változatlanul a meglévő `WIRE_LAYOUT` cellatörlést használja. Automatikus szomszédmaszk-javítás eltávolításkor külön `WIRE_REMOVE_INTENT` döntést igényelne.
- Közvetlenül szomszédos, de kapcsolatvesztés nélkül nem bővíthető WireCell mellett az `AUTO` fail-closed `INVALID_CONNECTION` választ ad.
- Nincs Junction, PUBLISH, Restore vagy színmodell-változás.

## FIX/2 Fabric-integrációs követelmények

A FIX/2 csak külön projektgazdai GO után indulhat. A Fabricnek:

1. új `WIRE_PLACE_INTENT` command DTO-t kell küldenie a `/graph/wire/place` végpontra;
2. normál lerakáskor `intent=AUTO`, explicit CROSS UX esetén `intent=CROSS` értéket kell használnia;
3. nem szabad szomszédmaszkot vagy többcellás changesetet számítania;
4. a választ a meglévő request-ID, epoch és revision fencinggel kell alkalmaznia;
5. a teljes authoritative `state.wire_cells` snapshotból kell renderelnie, nem a requestből;
6. a több elemű `wire_diff` és `placement.affected_cells` mezőt diagnosztikai/animációs célra használhatja, de authoritative forrás továbbra is a teljes state;
7. `409` esetén Graph GET/reconciliation után új játékosi próbát kell kérnie, nem automatikus szemantikai újrajátszást végeznie;
8. a strukturált placement hibákat külön kell tartania a discovery és bind diagnosztikától;
9. a meglévő break útvonalat változatlanul kell hagynia;
10. a korábbi `BLOCKED_A3_SINGLE_CELL_COMMAND` jelölést csak a FIX/2 tesztjeinek PASS eredménye után szabad feloldania.

Nem történt Fabric-, resource-, PostgreSQL-séma-, deploy-, szolgáltatás- vagy Minecraft-világmódosítás. A FIX/1 végeredménye: **PASS**.
