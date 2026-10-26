# CK-04/B4A – GraphEdge-vetítés és állapotfüggő CK Wire színezés

## Eredmény

A Fabric szerver a Python graph snapshotból származtatja a CK Wire megjelenését. A Kotlin réteg nem keres útvonalat, nem validál portot vagy route-ot, és színváltás miatt nem küld graph-módosító parancsot. Egyetlen `blockommander:ck_wire` blokk és item maradt, a blokk `axis` mellett `visual=cyan|green|orange` blockstate propertyt kapott.

Az automatikus binding továbbra sem része ennek a mérföldkőnek. A meglévő Python `BIND_EDGES` művelethez ideiglenes, admin-only `/ckbind` parancs készült.

## A tényleges B1 DTO-szerződés

A `GET /api/v1/stages/{stage_id}/graph` válasz `state` objektumából a vetítés az alábbi meglévő mezőket használja:

- `wire_cells[].position` és `wire_cells[].channels` – a hiteles fizikai layout;
- `edges[].edge_id` – a Pythonban létező szemantikai él azonosítója;
- `routes[].edge_id` – a route és a GraphEdge kapcsolata, amely lehet `null`;
- `routes[].steps[].position` – a route-hoz tartozó WireCell pozíciója;
- `routes[].status` – route-szintű validációs állapot;
- `workspace_epoch` és `working_revision` – lifecycle/revision fencing.

A Python `RouteStatus` lehetséges értékei: `INCOMPLETE`, `VALID`, `BROKEN`, `AMBIGUOUS`, `ILLEGAL`. Ezek route-szintű státuszok. A `state.status.graph`, `edge_indicator`, `save`, `layout` és `program_validation` Stage-/workspace-szintű összegzett állapot; egyik sem helyettesíti az adott route `status` mezőjét.

## Route–cell hozzárendelés

A hozzárendelés kizárólag az explicit DTO-adatokból készül:

1. egy WireCellhez azok a route-ok tartoznak, amelyek legalább egy `steps[].position` értéke azonos a cella `position` értékével;
2. a route `edge_id` értékét az `edges[].edge_id` halmazban kell megtalálni;
3. a Kotlin kód ebből csak megjelenítési állapotot képez.

Nem történik szomszédbejárás, portgeometria-számítás, útvonalkeresés vagy Kotlin oldali graph-validáció. A jelenlegi B1 szerződés ezért elegendő a biztonságos megjelenítési hozzárendeléshez.

## Vizuális állapotok

| Kotlin állapot | Blockstate | Pontos feltétel |
| --- | --- | --- |
| `UNBOUND` | `visual=cyan` | A hiteles WireCellhez nincs GraphEdge-hez kötött route. Ide tartozik a route nélküli cella és az `edge_id=null` route. |
| `BOUND_VALID` | `visual=green` | A cellát tartalmazó route ismert GraphEdge-re mutat, és a route státusza pontosan `VALID`. |
| `BOUND_NEEDS_REPAIR` | `visual=orange` | Ismert GraphEdge-hez tartozó route státusza a dokumentált nem-VALID értékek egyike. |
| `UNKNOWN` | `visual=orange` | Hiányzó hivatkozott edge, ismeretlen jövőbeli státusz vagy más nem egyértelmű DTO-adat. Külön Kotlin állapot, fail-safe narancssárga render; soha nem zöld. |

Ha ugyanazt a cellát több route érinti, a determinisztikus prioritás: `UNKNOWN`, `BOUND_NEEDS_REPAIR`, `BOUND_VALID`, `UNBOUND`. Így ellentmondó vagy ismeretlen adat nem válhat zölddé.

Egy létező GraphEdge önmagában nem tesz zölddé egy cellát: explicit, a cellát tartalmazó route és `VALID` státusz is szükséges.

## Kotlin projection architektúra

- `WireVisualProjection` tiszta függvényként a `GraphStateSnapshot` DTO-kból `GraphPosition -> WireVisualState` nézetet készít.
- `WireWorldProjector` továbbra is ellenőrzi a Stage-határt, a támogatott SINGLE E–W geometriát, az epochot és a revisiont.
- A projektor csak a hiteles `wire_cells` pozícióit rendereli. Idegen blokkot nem ír felül, extra CK Wire-t eltávolít.
- Egy már létező CK Wire eltérő színénél ugyanazon blokk blockstate-je frissül; nincs eltávolítás és újralerakás.
- Minden világírás a `WireInteractionCoordinator` szerver-main-thread executorán fut.
- Az ismételt azonos projection nem írja újra az azonos blockstate-et.

A színprojektor nem ismeri a `GraphGateway`-t, ezért önmagában nem képes `WIRE_LAYOUT`, `BIND_EDGES` vagy más Python műveletet indítani, revisiont növelni vagy workspace-t DIRTY-re állítani.

## Blockstate és resource-lánc

A `CkWireBlock` megtartotta a `HORIZONTAL_AXIS` propertyt, az X/Z outline és collision alakot, valamint a blokk- és itemazonosítót. Az új `visual` enum property értékei `cyan`, `green`, `orange`.

Az asset-lánc:

- `blockstates/ck_wire.json`: mindhárom szín X/Z variánsa;
- `models/block/ck_wire.json`: meglévő cián modell és geometria;
- `models/block/ck_wire_green.json`: ugyanaz a parent/geometria, green concrete textúra;
- `models/block/ck_wire_orange.json`: ugyanaz a parent/geometria, orange concrete textúra;
- `models/item/ck_wire.json`: továbbra is az egyetlen, cián alapmodellre mutató CK Wire item.

Nem keletkezett új blokkazonosító vagy item.

## Ideiglenes binding tesztút

Az admin jogosultságot a Minecraft 26.3 permission API-ján keresztül ellenőrző parancs:

```text
/ckbind
```

Feltételek és működés:

- a parancsot játékos adja ki, admin command permissionnel;
- a játékosnak az aktuális Stage területén kell állnia;
- a `ServerPlayerIdentity.from(player)` a valódi szerveroldali UUID-t adja át;
- a coordinator szükség esetén előbb GET reconciliationt végez;
- a `BIND_EDGES` parancs a meglévő B2 `GraphGateway`/`GraphTransportAdapter` útvonalon megy, az aktuális epoch/revision értékekkel;
- Python reject esetén nincs hamis zöld állapot;
- 409/5xx/bizonytalan eredmény esetén a B2 adapter reconciliationt futtat;
- sikeres válasz után a visszakapott snapshot azonnal újravetítődik, és a játékos explicit üzenetet kap.

A parancs nem kerül meg Python jogosultsági, revision- vagy idempotencia-ellenőrzést, és nem végleges hangárfali vezérlő.

## Fizikai sérülés és domainmódosítás

A célarchitektúrában a puszta Minecraft-világbeli sérülés nem módosíthatná a GraphEdge-et, working revisiont, DIRTY állapotot vagy mentett Stage-et. A B3 jelenlegi break interceptionje azonban nem puszta lokális sérülést hoz létre: explicit `WIRE_LAYOUT` parancsot küld `cell: null` értékkel. Ez hiteles Python layout-módosítás, ezért revision- és DIRTY-változást okozhat.

B4A ezt szándékosan nem írja át. Következmények:

- nem hoztunk létre külön sérülés-adatbázist;
- a nem-VALID Python route narancssárgán megjeleníthető;
- egy idegen blokkal elfoglalt vagy hiányzó hiteles route-cella projection issue/`NEEDS_REPAIR`, de ahol nincs renderelhető CK Wire blokk, ott fizikailag nincs mit narancssárgára színezni;
- biztonságos, domain-konform „csak fizikai sérülés” játékbeli előállításához külön mechanizmus szükséges egy későbbi döntésben.

Javaslat: a későbbi sérüléskezelés válassza külön az admin/játékos által szándékosan kért layout-editet a világban észlelt drift eseménytől. Az utóbbi csak ideiglenes projection health legyen, ne új perzisztens domainmodell.

## Automatizált tesztek

A B4A tesztek lefedik:

- edge nélküli cella → cián;
- ismert edge + VALID route → zöld;
- ismert edge + nem-VALID route → narancssárga;
- ismeretlen státusz és hiányzó edge → nem zöld;
- hiányzó route-cell tagság → nem zöld;
- több route determinisztikus fail-safe prioritása;
- cián → zöld → narancssárga → zöld átmenet és idempotencia;
- változatlan blokk-/itemazonosító, axis és geometria, teljes resource-lánc;
- idegen blokk és Stage-határ védelme;
- stale epoch/revision és main-thread write regresszió;
- bind player identity, első/ismételt bind, reject és 409 reconciliation;
- CK-04/B2 és CK-04/B3 meglévő regressziós tesztjei.

Aktuális eredmény az implementáció lezárásakor:

- Gradle/JUnit: **59/59 PASS**, 0 failure, 0 error, 0 skipped;
- teljes Java 25 Gradle `cleanTest buildAll --no-daemon`: **BUILD SUCCESSFUL** (12 task, 10 executed, 2 up-to-date);
- teljes Python regresszió: **387/387 PASS** (`Ran 387 tests`, `OK`).

## Kézi Minecraft acceptance

**NOT VERIFIED ebben a munkamenetben.** Nem történt deploy, szerver-/kliens-újraindítás, produkciós DB-írás vagy világmódosítás.

A tényleges kliens/szerver teszten ellenőrizendő:

1. a START–wire–STOP sor kezdetben cián;
2. `/ckbind` után pontosan egy GraphEdge és egy VALID EdgeRoute létezik, a wire zöld;
3. ismételt `/ckbind` nem duplikál edge-et;
4. reconnect után zöld marad/helyreáll;
5. SAVE után a kapcsolat perzisztál;
6. izolált, biztonságosan előállított nem-VALID route narancssárga.

## CK-04/B4B előfeltételek és review-javaslat

A B4B automatikus binding előtt szükséges:

- a teljes vezeték és két kompatibilis port felismerésének Python-oldali, hiteles szerződése;
- pontos trigger-meghatározás: csak befejezett kapcsolatnál, nem minden wire-változásnál;
- idempotens retry és concurrency tesztek;
- explicit ConnectionDestroyer művelet a GraphEdge törléséhez;
- döntés a fizikai drift és a szándékos `WIRE_LAYOUT` edit elkülönítéséről;
- sikeres B4A kézi Minecraft acceptance.

Javaslat: **CONDITIONAL GO** a CK-04/B4B tervezésére. Feltétel a fenti kézi B4A acceptance és a break/drift szerződés SysOrg döntése; automatikus binding implementációja addig ne induljon.

## Módosítási és repository-státusz

A B4A közvetlenül érintett fájljai:

- `src/main/kotlin/me/buciqua/blockommander/CkWireBlock.kt`;
- `src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt`;
- `src/main/kotlin/me/buciqua/blockommander/runtime/wire/WireVisualProjection.kt`;
- `src/main/kotlin/me/buciqua/blockommander/runtime/wire/WireWorldProjection.kt`;
- `src/main/kotlin/me/buciqua/blockommander/runtime/wire/WireInteractionCoordinator.kt`;
- `src/main/resources/assets/blockommander/blockstates/ck_wire.json`;
- `src/main/resources/assets/blockommander/models/block/ck_wire_green.json`;
- `src/main/resources/assets/blockommander/models/block/ck_wire_orange.json`;
- a kapcsolódó wire tesztek;
- ez a dokumentum.

A munkafa a B4A indulásakor már tartalmazta a korábbi B3 acceptance során készült, még nem commitolt Python/Kotlin javításokat. Ezeket a B4A munka megőrizte. A lezáráskori HEAD `ea089f3`; commit, push és deploy nem történt, az index üresen maradt.
