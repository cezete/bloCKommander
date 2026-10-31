# CK-05/A4/1 – Fabric többcellás CK Wire integráció

## Státusz

**PARTIAL**

Az authoritative többcellás világprojekció, a verziózott discovery DTO, a determinisztikus blockstate/geometria és az automatizált regressziós tesztek elkészültek. Az automatikus, szomszédokat kölcsönösen átmaszkoló játékos-placement **BLOCKED**, mert az A3 `WIRE_LAYOUT` szerződés egy parancsban pontosan egy cellát módosít. Emiatt az A4/1 nem minősíthető teljes PASS-nak.

## Megvalósított változások

### Discovery DTO és parser

A `GraphDtos.kt` explicit, read-only DTO-ként kezeli a felső szintű `discovery` mezőt:

- `schema_version=1`;
- komponensazonosító, státusz, pozíció/csatorna csúcsok, portok és nyitott végek;
- kapcsolatjelöltek és forrástól célig rendezett lépések;
- külön discovery-diagnosztikák.

Az ismeretlen additív mezőket a név szerinti parser figyelmen kívül hagyja. A discovery nélküli CK-04 válasz továbbra is érvényes. Ismeretlen discovery schema esetén a parser `GraphProtocolException` hibával, még snapshot-alkalmazás és világprojekció előtt leáll. A discovery nem kerül GraphEdge- vagy EdgeRoute-létrehozási útvonalra.

A `WireActionResult.discoveryDiagnostics` külön őrzi a fizikai discovery diagnosztikáit. A bind alkalmazási/domainhiba továbbra is a `detail` mezőben marad; így például az `OPEN_WIRE` és a `NO_BINDABLE_CONNECTIONS` nem mosódik össze.

### Blockstate és assetmodell

A korábbi `axis=x|z` propertyt a `geometry` váltotta fel. Támogatott értékek:

- `none`;
- `north`, `east`, `south`, `west`;
- `north_south`, `east_west`;
- `north_east`, `east_south`, `south_west`, `west_north`;
- `cross`.

A meglévő `visual=cyan|green|orange` szemantika változatlan. A deklaratív multipart blockstate három újrahasznosítható geometriai modellt használ színenként:

- középpont;
- forgatható kar;
- CROSS esetén megemelt N–S híd.

A CROSS E–W ága a normál síkban, az N–S ág vizuálisan felette fut. A két ág ettől nem válik közös logikai csatornává: a Kotlin projektor kizárólag a Python `NS` és `EW` csatornapár pontos maszkját fogadja el.

Régi CK-04 világban a blokkazonosító és itemazonosító változatlan. A régi, ismeretlen `axis` state-property betöltése után a blokk alapértelmezett `east_west` geometriára esik vissza; a következő authoritative reconciliation ezt a Python snapshot szerint kijavítja.

## Placement és break szerződés

A meglévő `WireInteractionCoordinator`, epoch/revision fencing és `WIRE_LAYOUT` útvonal maradt használatban.

- A CK-04 egycellás E–W placement változatlanul `SINGLE/EAST+WEST` parancsot küld.
- A break továbbra is egy cella explicit `null` értékű eltávolítása.
- A világ csak hiteles Python-válasz vagy sikeres reconciliation után változik.
- Stale vagy elutasított válaszból nincs tartós világírás.

Az automatikus szomszédkapcsolat státusza kódban is explicit:

`BLOCKED_A3_SINGLE_CELL_COMMAND`

Egy új cella lerakása általában az új cella és egy vagy több már létező szomszéd kölcsönös maszkját is megváltoztatná. Ezek egymás utáni egycellás parancsokkal részlegesen sikerülhetnének, ezért a Fabric nem számít és nem vetít optimista, hitelesnek látszó topológiát.

A feloldáshoz Python oldalon az alábbiak egyike szükséges:

1. egy revision-fencelt, atomi, többcellás `WIRE_LAYOUT` changeset; vagy
2. szerveroldali placement-intent művelet, amely a Python authoritative snapshotból számítja és atomikusan alkalmazza az érintett cellamaszkokat.

Mindkét esetben a Python domainnek kell ellenőriznie a kölcsönösséget, a `SINGLE` legfeljebb két irányát és a CROSS csatornafüggetlenségét.

## Authoritative világprojekció

A `WireWorldProjector` a teljes snapshotot előellenőrzi minden világírás előtt:

- Stage ID, workspace epoch és working revision egyezés;
- minden WireCell Stage-határon belül van;
- nincs duplikált pozíció;
- a geometria támogatott és szigorúan kanonikus;
- egyik célpozíción sincs idegen Minecraft blokk.

Ha bármely preflight feltétel hibás, nincs render, törlés vagy részleges javítás. Sikeres preflight után a Python snapshot elsőbbséget élvez a lokális blockstate-tel szemben; a projektor a geometriát és a CK-04 színt együtt állítja helyre. Az azonos snapshot ismételt alkalmazása idempotens. A projekció nem módosít GraphEdge-et, EdgeRoute-ot, revisiont vagy dirty állapotot.

A támogatott kanonikus alakok:

- `SINGLE`: 0, 1 vagy 2 különböző kardinális irány;
- kétirányú `SINGLE`: egyenes vagy a négy sarok egyike;
- `CROSS`: pontosan `NS=NORTH+SOUTH` és `EW=EAST+WEST`.

Három- vagy négyirányú `SINGLE`, duplikált irány, ismeretlen csatorna vagy fél CROSS nem vetíthető.

## Reconnect és felelősségi határ

A T8 sorrend nem változott:

1. authoritative Stage reload;
2. programblokkok vetítése;
3. Graph GET és reconciliation;
4. Wire világprojekció;
5. SAVE-tábla frissítése.

A reconnect ugyanazt a `GraphStateSnapshot.wireCells` tartalmat ugyanarra a blockstate-geometriára képezi. A discovery megjelenítési/diagnosztikai adat; nem írja felül a snapshotot és nem jogosít bindra. A félkész vezeték normális cián DRAFT állapot, automatikus bind vagy `BROKEN` írás nélkül.

## Automatizált ellenőrzés

Java 25-tel futtatva:

```powershell
.\gradlew.bat cleanTest buildAll --no-daemon
```

Eredmény:

- 86 Kotlin/JVM teszt: PASS, 0 failure, 0 error, 0 skipped;
- 413 Python teszt: PASS;
- `BUILD SUCCESSFUL`;
- létrejött a szerver/client közös build artifact, deploy nem történt.

A regressziók lefedik a CK-04 E–W útvonalat, N–S geometriát, négy sarkot, egy- és nullavégű DRAFT cellát, CROSS-t, többcellás egyenest és sarkot, ismételt projekciót, stale lifecycle-t, ismeretlen discovery schemát, idegen blokk védelmét, discovery/bind diagnosztika szétválasztását, GraphEdge/EdgeRoute megőrzését és reconnect-szerű authoritative geometriajavítást.

## Ismert korlátok és kockázatok

- A játékos továbbra is csak a CK-04 kompatibilis E–W cellát tudja biztonságosan létrehozni a jelenlegi Fabric interaction útvonalon.
- A többcellás/sarok/CROSS snapshotok helyesen vetíthetők, de automatikus játékosépítésükhöz hiányzik az atomi Python layout művelet.
- A multipart JSON és a CROSS vizuális híd csak automatizált resource-ellenőrzést kapott; tényleges Minecraft render-acceptance még nincs.
- A blokkstate séma `axis` → `geometry` változása miatt a deploy után kötelező a meglévő CK-04 E–W vezeték reconnect/reconciliation ellenőrzése.
- Külön egyedi renderer nem készült; a megjelenés a vanilla model/blockstate rendszer képességeire támaszkodik.

## A4/2 előfeltételek és javasolt acceptance

A4/2 csak külön projektgazdai GO után indulhat. Előtte szükséges:

1. SysOrg-döntés és implementáció az atomi többcellás Python layout szerződésről;
2. ennek Fabric placement-integrációja és új regressziós tesztjei;
3. Java 25 `cleanTest buildAll` ismételt PASS;
4. kontrollált kliens- és szerver-JAR deploy külön engedéllyel.

Javasolt kézi sorrend:

1. meglévő CK-04 E–W vezeték reconnect és színellenőrzése;
2. nullavégű és egyvégű DRAFT;
3. többcellás E–W és N–S egyenes;
4. mind a négy sarok;
5. két független út CROSS cellán át, hamis bind nélkül;
6. explicit `/ckbind`, szín és discovery-diagnosztika;
7. reconnect, majd külön Python-restart + reconnect;
8. idegen blokk és stale lifecycle fail-closed próbája.

Az A4/1 jelenlegi eredménye ezért: **authoritative projection PASS, automatic placement BLOCKED, összesített státusz PARTIAL**.
