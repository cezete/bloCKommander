# CK-04/B3 – Első látható CK Wire

## 1. Implementált blokk és resource-ok

A Fabric content registry új blokk/item párja:

```text
blockommander:ck_wire
```

A `CkWireBlock` vékony, nem teljes kocka, középen futó vezetékalakot és azzal azonos outline shape-et ad. Az explicit `horizontal_axis` blockstate későbbi bővítést enged, de B3 kizárólag `axis=x`, SINGLE E–W állapotot renderel. A cián modell vizuálisan különbözik a vanilla redstone-tól. A blokk sima `Block`, nem vezet be signal-, redstone-, tick- vagy power-logikát, és nincs támaszblokkhoz kötött survival szabálya.

Resource-lánc:

- `assets/blockommander/blockstates/ck_wire.json`;
- `assets/blockommander/models/block/ck_wire.json`;
- `assets/blockommander/models/item/ck_wire.json`;
- `assets/blockommander/items/ck_wire.json`;
- `data/blockommander/loot_table/blocks/ck_wire.json`;
- angol és magyar nyelvi kulcsok.

A CK creative tab sorrendjében a START, STOP, IF, FOR és FUN után jelenik meg.

## 2. Placement interception

A `UseBlockCallback` külön kezeli a CK Wire itemet.

1. Kliensoldalon `SUCCESS` eredménnyel elnyeli a vanilla BlockItem placementet, ezért nincs hitelesnek látszó klienspredikció.
2. Szerveroldalon kiszámolja a kattintott oldal melletti célcellát.
3. `HallRegistry.stageAt(worldUuid, target)` alapján Stage-et keres.
4. Csak levegőt vagy már meglévő CK Wire cellát enged; idegen blokkot nem ír felül.
5. A callback `SUCCESS`-szal tér vissza, tehát a vanilla placement nem véglegesül.
6. Az aszinkron graph műveletet a `WireInteractionCoordinator` indítja.
7. A világírás csak elfogadott és fence-elt projection után történik.

HTTP-várakozás nincs a Minecraft főszálon. Elutasításnál a szerver `sendBlockUpdated()` korrekciót küld és chat-visszajelzést ad.

## 3. Python-first parancsfolyamat

Egy placement pontosan egy `WireLayoutCommand` objektumot készít, stabil request ID-val:

```text
schema_version = 1
operation = WIRE_LAYOUT
payload.changes[0].position = worldPosition - stage.origin
payload.changes[0].cell.channels[0].channel = SINGLE
payload.changes[0].cell.channels[0].connections = [EAST, WEST]
```

A sorrend:

```text
interception
  → szükség esetén graph GET reconciliation
  → Python wire command
  → DTO parse és B2 response fencing
  → GraphProjectionStore frissítés
  → újabb Stage epoch/revision ellenőrzés
  → szerver main-thread world projection
```

HTTP 200 önmagában nem enged világírást. `GraphApplyResult.Applied` vagy sikeres, autoritatív reconciliation szükséges. Kotlin nem képez `GraphEdge`-et, nem jár be hálózatot, és nem tartalmaz portkatalógus-szemantikát.

## 4. Stage/player/epoch/revision

- Stage ID és origin/size: `HallRegistry.Stage`.
- Player identity: kizárólag `ServerPlayerIdentity.from(ServerPlayer)`.
- Command epoch/revision: kizárólag a `SYNCED` `GraphProjectionStore` view-ból.
- Stale, conflict, error vagy nem lekért projection esetén előbb GET reconciliation fut.
- Az abszolút célpozíció Stage-relatív koordinátává alakul.
- Command után a render előtt a Stage ID, epoch és az autoritatív revision ismét ellenőrződik.
- Késői response vagy lifecycle-váltás `STALE` eredményt ad, világírás nélkül.

## 5. World projection

A `WireWorldProjector` tiszta projection-szabályt és Minecraft adaptert választ szét.

- Stage-relative `WireCell` → abszolút blokkpozíció.
- Pontosan egy `SINGLE` csatorna és pontosan `{EAST, WEST}` connections renderelhető.
- Ismeretlen csatorna, N–S, CROSS, kanyar vagy más geometria `UNSUPPORTED_WIRE_GEOMETRY`; találgatás nincs.
- Idegen blokk `FOREIGN_BLOCK_AT_WIRE_POSITION`; felülírás nincs.
- A Stage területén található CK Wire blokkok közül a snapshotból hiányzók eltávolíthatók.
- Más blokktípus soha nem törlődik.
- Az ismételt azonos reconciliation idempotens.

A Stage-lokális CK Wire scan nem graph discovery: kizárólag a saját blokkazonosítót keresi a Stage kis térfogatában a világvetítés javításához.

Renderhiba nem rollbackeli a Python workspace-t. A coordinator `NEEDS_REPAIR` állapotot rögzít, egyetlen GET reconciliationt indít és egyszer újrapróbálja a projectiont. Újabb hiba után nincs loop és nincs hamis sikerüzenet.

## 6. Break interception

A `PlayerBlockBreakEvents.BEFORE` minden CK Wire vanilla törését `false` eredménnyel leállítja.

1. Stage és aktuális graph projection feloldása.
2. `WIRE_LAYOUT` command ugyanarra a relative pozícióra, `cell: null` tartalommal.
3. Python reject esetén a világblokk megmarad.
4. Elfogadott/fence-elt snapshot után a world projector eltávolítja a CK Wire blokkot.

A break út nem küld `BIND_EDGES` parancsot és nem töröl edge-et. Sikeres aszinkron eltávolítás jelenleg nem ad automatikus item-dropot; ez a minimális B3 biztonsági szelet ismert UX-korlátja.

## 7. Reconciliation

Tulajdonosi hall restore után a runtime GET graph reconciliationt futtat és megjeleníti a hiteles wire snapshotot. Wire placement/break előtt szintén automatikus GET történik, ha a projection nem `SYNCED`.

Bizonytalan command válasz, request-ID eltérés, 409 vagy 5xx esetén a B2 adapter legfeljebb egy GET-et futtat. A B3 csak akkor renderel, ha az ebből származó snapshot a változatlan Stage lifecycle-hoz tartozik. Ha a command eredménye után a célállapot nem található az autoritatív snapshotban, a világ szinkronizálódik, de a játékos művelete `REJECTED`, nem siker.

## 8. Tesztek és eredmények

Izolált Kotlin/JUnit kapu:

```powershell
$env:JAVA_HOME="$PWD\.tools\jdk-25"
$env:GRADLE_USER_HOME='C:\Users\tamas\.gradle'
.\gradlew.bat cleanTest test --no-daemon
```

Eredmény: **42/42 PASS**.

- CK-04/B2 regresszió: 27/27.
- CK Wire asset/registration: 3/3.
- Wire coordinator placement/break/concurrency: 8/8.
- World projection: 4/4.

Bizonyított: resource-konzisztencia; nincs redstone override; vanilla placement/break interception; Python előtti világváltozatlanság; stale esetben reconciliation; szerver identity és stabil request ID; reject után nincs wire; success után render; késői epoch response után nincs render; main-thread world-write; break reject/success; edge megőrzése; snapshot recovery; idempotencia; unsupported geometria; idegen blokk védelme; renderhiba utáni bounded reconciliation.

Teljes Gradle kapu: `.\gradlew.bat cleanTest test build --no-daemon` – **BUILD SUCCESSFUL**, 42/42 Kotlin teszt PASS. Teljes Python kapu helyes `python-service` munkakönyvtárból, `PYTHONPATH="$PWD\src;$PWD"` beállítással – **383/383 PASS**. A buildelt JAR-ban a `CkWireBlock`, a wire runtime és mind az öt CK Wire resource megtalálható.

## 9. Ismert korlátok

- Csak vékony, vízszintes SINGLE E–W cella támogatott.
- Nincs kanyar, többcellás route discovery, T, CROSS vagy N–S render.
- Nincs Élbekötés gomb és nincs automatikus GraphEdge-képzés.
- Nincs redstone-funkció.
- Nincs wire item refund/drop az aszinkron break után.
- A world-repair állapot processzmemóriás; restart után owner restore/reconciliation szükséges.
- A Python idempotencia továbbra is egyetlen processz/egyetlen `GraphApplicationService` modellre korlátozott.
- A tényleges kliens–dedikált szerver acceptance jelenet ebben a műszakban nem futott.

## 10. Kézi Minecraft acceptance test

Státusz: **NOT VERIFIED**.

Későbbi, kifejezetten engedélyezett helyi teszt lépései:

1. Egyprocesszes Python Graph API és Fabric kliens/szerver indítása azonos shared secrettel.
2. Tulajdonosi játékos restore után Stage ellenőrzése.
3. START relatív `(0,0,0)`, EAST.
4. STOP relatív `(2,0,0)`, EAST.
5. CK Wire kiválasztása a CK tabból.
6. Placement relatív `(1,0,0)` cellába.
7. Python GET: pontosan egy SINGLE E–W `WireCell`, nulla `GraphEdge`.
8. Minecraft: látható cián CK Wire.
9. Break: Python wire cell eltűnik, Minecraft CK Wire eltűnik, edge továbbra sem keletkezik/törlődik.
10. Reject, secret-hiány és Python-leállás mellett ellenőrizni, hogy nincs tartós kliens/szerver wire.

## 11. CK-04/B4 előfeltételek

1. A fenti kézi acceptance jelenet végrehajtása és rögzítése.
2. SysOrg review a Stage-lokális scan és a render-repair UX felett.
3. Döntés az aszinkron break item-visszaadásról.
4. Egyprocesszes Python deployment invariáns megerősítése.
5. Csak ezután tervezhető külön Élbekötés control, amely a Python `BIND_EDGES` műveletét hívja.
6. B4 sem vezethet be Kotlin topológiafelderítést vagy automatikus edge-törlést.

Kapujavaslat: **CK-04/B4 CONDITIONAL GO** a kézi Minecraft acceptance és SysOrg review után. B4 nem indult el.
