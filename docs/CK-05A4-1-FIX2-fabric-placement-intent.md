# CK-05/A4/1-FIX/2 – Fabric integráció az authoritative WIRE_PLACE_INTENT API-hoz

## Státusz

**PASS**

A Fabric placement útvonala már nem számít lokális CK Wire topológiát. A játékosi művelet kizárólag a célpozíciót és az `AUTO` vagy `CROSS` intentet küldi a Python Graph applicationnek; a Minecraft-világ kizárólag a válasz teljes authoritative Graph snapshotjából frissül.

A FIX/1-ben bevezetett Python API-szerződés változatlan. Nem történt deploy, szolgáltatás-újraindítás, PostgreSQL-írás, SAVE, REBUILD, Minecraft-világmódosítás, commit vagy push.

## Transport és DTO

Az új Fabric command:

```text
WIRE_PLACE_INTENT
POST /api/v1/stages/{stage_id}/graph/wire/place
```

A szigorú payload:

```json
{
  "position": {"x": 2, "y": 0, "z": 3},
  "intent": "AUTO"
}
```

A `WirePlaceIntentCommand` a meglévő Graph envelope-ot, authentikációt, request ID-t, Stage ID-t, player UUID-t, workspace epochot és expected working revisiont használja. Támogatott intentek:

- `AUTO`: normál jobb kattintás;
- `CROSS`: guggolás közbeni jobb kattintás.

Az opcionális `placement` válaszmező szigorúan parse-olt diagnosztikai DTO. Az ismeretlen intent fail-closed protokollhiba. A `placement.affected_cells`, a `wire_diff` és a discovery eredménye nem helyettesítheti a teljes `state` snapshotot.

## Játékosi interaction útvonal

1. A kliens- és szerveroldali CK Wire item callback elfogyasztja a vanilla placementet, ezért nincs optimista Minecraft-blokk.
2. A szerver meghatározza a Stage-relatív célpozíciót és a játékosi intentet.
3. A coordinator pontosan egy `WIRE_PLACE_INTENT` POST-ot küld.
4. Siker esetén a lifecycle- és revision-fencelt teljes Graph snapshot kerül a `GraphProjectionStore`-ba.
5. A `WireWorldProjector` a teljes snapshotot előellenőrzi, majd abból vetíti az összes érintett cella geometriáját és színét.
6. A discovery diagnosztikák elkülönítve, sárga játéküzenetként jelennek meg; nem mosódnak össze a command/domain hibával.

A Fabric nem küld szomszédlistát, csatornamaszkot vagy többcellás changesetet, és nem választ topológiát három vagy négy szomszéd esetén. Az egyenes, sarok- és CROSS-geometria is kizárólag a Python által visszaadott `state.wire_cells` tartalomból származik.

## Fencing, hibák és reconciliation

- Azonos lifecycle-ban csak az aktuális vagy újabb authoritative revision alkalmazható.
- Stale epoch/revision vagy HTTP 409 esetén nincs automatikus szemantikai command-ismétlés.
- Bizonytalan transporteredmény, például timeout után a kliens egy Graph GET reconciliationt végez, de a placement POST-ot nem küldi el másodszor.
- Elutasított command vagy sikertelen reconciliation után nincs világírás.
- A késve érkező régi lifecycle-válasz nem írhatja felül az újabb projekciót.
- Azonos authoritative snapshot ismételt alkalmazása idempotens.

A Python által visszaadott strukturált placement hiba után a játékos új művelete szükséges. Ez megőrzi a request-idempotenciát, és elkerüli, hogy megváltozott topológia mellett a Fabric önállóan újraértelmezze az eredeti intentet.

## Authoritative világprojekció

A FIX/2 a CK-05/A4/1-ben elkészült `WireWorldProjector` útvonalát használja. A projektor teljes snapshot-preflightot végez, majd egyetlen konzisztens projekcióban:

- létrehozza vagy frissíti a célcellát;
- frissíti a Python által módosított szomszédok kölcsönös geometriáját;
- eltávolítja a snapshotból hiányzó CK Wire cellákat;
- megőrzi az idegen blokkokat, és ütközésnél részleges render nélkül megáll;
- a GraphEdge és EdgeRoute állapot alapján tartja meg a cyan/green/orange vizuális szemantikát.

A response `placement` és `wire_diff` mezője legfeljebb diagnosztikai vagy későbbi animációs célra használható. A render egyetlen hiteles forrása a teljes Graph snapshot.

## Break útvonal

A CK Wire törlése szándékosan változatlan: továbbra is `WIRE_LAYOUT` cellatörlést küld. A FIX/2 nem vezet be `WIRE_REMOVE_INTENT` műveletet, és nem módosítja a Python remove-szemantikát.

Ez azt jelenti, hogy a placement már authoritative többcellás művelet, de az eltávolítás körüli automatikus szomszédmaszk-rendezés külön domain- és API-döntés marad.

## Automatizált tesztek

### Célzott Fabric regresszió

```text
gradlew test \
  --tests GraphDtoAndCommandTest \
  --tests GraphApiClientTest \
  --tests GraphTransportAdapterTest \
  --tests WireInteractionCoordinatorTest \
  --tests WireWorldProjectorTest \
  --tests CkWireAssetsTest \
  --no-daemon

BUILD SUCCESSFUL
```

A célzott tesztek igazolják többek között:

- a pontos `/graph/wire/place` útvonalat és payloadot;
- az `AUTO` és explicit `CROSS` UX-et;
- az opcionális placement metadata szigorú parse-olását;
- az egyenes, sarok és CROSS authoritative renderelését;
- a cél- és szomszédcellák együttes snapshot-frissítését;
- a három- és négyszomszédos `AUTO` ambiguity Fabric-oldali találgatás nélküli elutasítását;
- a CROSS-elutasítás utáni nulla világmódosítást;
- a request-ID és projekció idempotenciáját;
- a 409 és bizonytalan transport utáni egyetlen POST + reconciliation GET viselkedést;
- a stale lifecycle és részleges világírás elleni védelmet;
- a meglévő break, edge és route regressziók megőrzését.

### Teljes build- és regressziós kapu

Java 25-tel:

```text
gradlew cleanTest buildAll --no-daemon

97 Kotlin/JVM teszt: PASS
432 Python teszt: PASS
BUILD SUCCESSFUL
```

A teljes Python suite külön, csendes `unittest discover` futással is ellenőrizve lett:

```text
Ran 432 tests
OK
```

A `git diff --check` hibamentes. A jelzett LF/CRLF üzenetek Git munkafapéldány-figyelmeztetések, nem whitespace-hibák.

## Módosított Fabric komponensek

- `GraphCommands.kt`: `GraphPlacementIntent` és `WirePlaceIntentCommand`;
- `GraphApiClient.kt`: a `/graph/wire/place` transportútvonal;
- `GraphDtos.kt`: opcionális placement response DTO és szigorú parser;
- `WireInteractionCoordinator.kt`: placement-intent küldés, authoritative snapshot alkalmazás és reconciliation;
- `Interactions.kt`: normál `AUTO`, guggolásos `CROSS`, vanilla placement blokkolása és discovery-visszajelzés;
- Graph transport-, DTO-, interaction-, projector- és asset-regressziós tesztek.

A korábbi A4/1 blockstate-, modell- és projector-változtatások változatlanul a koherens munkafa részei. A FIX/1 Python implementációját a FIX/2 nem módosította tovább.

## Megmaradó kockázatok és A4/2 előfeltételek

- A `CROSS` jelenlegi UX-e guggolás + jobb kattintás; ezt Minecraft acceptance során használhatósági szempontból is ellenőrizni kell.
- A timeout utáni reconciliation helyesen kerüli a dupla POST-ot, de az élő hálózati hibák és játékos-visszajelzések csak integrációs környezetben igazolhatók teljesen.
- A break továbbra is a régi egycellás remove szerződés; összetett elágazások eltávolításának kívánt szemantikája külön mérföldkő.
- Deploykor a Python FIX/1 és a Fabric/A4/1+FIX/2 verzióinak kompatibilis együttállását kell biztosítani.
- A blockstate és model assetek miatt a későbbi acceptance-hez szerver- és kliens-JAR frissítés is szükséges lehet, de ez most nem történt meg.
- A4/2 előtt külön projektgazdai engedély kell a build artifactok telepítésére, a szükséges szolgáltatás-restartokra és a Minecraft acceptance végrehajtására.

Az A4/1 korábbi `BLOCKED_A3_SINGLE_CELL_COMMAND` akadálya a FIX/1 és FIX/2 együttállásával feloldódott. A lokális Fabric-integráció eredménye: **PASS**.
