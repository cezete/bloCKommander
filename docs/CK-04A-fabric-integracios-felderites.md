# CK-04/A – Fabric-integrációs felderítés

> Dátum: 2026-10-24  
> Hatókör: kizárólag read-only repository- és környezetvizsgálat; az egyetlen írás ez a jelentés  
> Kiinduló commit: `2db713c5fa2a35c6eb9b963f0e2b3ae09adf5cc0` (`CK-03/B3 – StageService graph-aware SAVE/REBUILD`)

## 1. Executive summary

A repository egyetlen, közös kliens/szerver Fabric modot és egy Flask/Python szolgáltatást tartalmaz. A meglévő hangár- és CK-programblokk út ténylegesen össze van drótozva a kódban: szerveroldali Fabric callback → localhost HTTP → `StageService` → JSON-válasz → Fabric főszálas világ-renderelés. Ez azonban csak a hangárra és a START/STOP/IF/FOR/FUN Stage-blokkokra terjed ki; friss játékbeli futtatás nem történt, a korábbi `docs/VERIFICATION.md` pedig kifejezetten nyitva hagyja a teljes klienses kézi próbát.

A CK Wire és az Élbekötés runtime-integrációja **NINCS**. Nincs wire blokk/item/resource, wire esemény, graph HTTP endpoint/DTO, topológiafelderítő alkalmazási szolgáltatás, Élbekötés control vagy Fabric-oldali graph állapotvetítés. A Pythonban ugyanakkor a szükséges szemantikai alapok már megvannak: `WireCell`, `EdgeRoute`, `GraphEdge`, `ck-control-v1`, orientációs transzformáció, `bind_edges()` és az owner/epoch/revision ellenőrzött `StageService.apply_graph_operation()`.

A legnagyobb akadály nem a blokkregisztráció, hanem a **verziózott, idempotens runtime-szerződés és a fizikai wire-layout → Python `RouteCandidate` topológiafelderítés hiánya**. A Kotlin oldal nem kaphat második gráfmotort. A javasolt CK-04/B egy szűk, egyenes, egycellás SINGLE `START.out → STOP.in` szelet: a wire lerakás először a Python workspace-ben változik, a világot csak elfogadott válasz után írja a Fabric szerver, az edge pedig kizárólag a külön Élbekötés gomb hatására jön létre.

## 2. Feltárt projektek és verziók

### 2.1 Projekt- és modulstruktúra

- Egy Gradle root projekt van: `settings.gradle`, `rootProject.name = 'bloCKommanderFabric'`. További Gradle/Maven/Fabric modulmarker nincs a repositoryban.
- A közös, dedikált szerveren is betöltődő Kotlin kód helye `src/main/kotlin`; a fizikai klienskód helye `src/client/kotlin`. Ezt a `build.gradle` `splitEnvironmentSourceSets()` beállítása választja szét.
- A Python szolgáltatás ugyanebben a repositoryban él: `python-service/src/ck_webhook.py` és `python-service/src/hangar/**`.
- A korábbi `BlockCommanderTransmitter`, `CKParser` és `CKpu` külön projektek; a jelenlegi runtime nem deklarál rájuk buildfüggőséget. A `docs/FUNCTION-INVENTORY.md` szerint a CKParsernek nincs Minecraft/Stage/PostgreSQL integrációja.

### 2.2 Verziók

| Összetevő | Verzió | Bizonyíték |
|---|---:|---|
| Minecraft | 26.3 | `gradle.properties`; `fabric.mod.json` |
| Fabric Loader | 0.19.5 | `gradle.properties`; `fabric.mod.json` |
| Fabric API | 0.164.0+26.3 | `gradle.properties` |
| Fabric Loom | 1.18-SNAPSHOT | `gradle.properties` |
| Kotlin Gradle plugin | 2.3.20 | `gradle.properties`, `build.gradle` |
| Fabric Language Kotlin | 1.13.10+kotlin.2.3.20 | `gradle.properties`, `build.gradle` |
| Java bytecode/source target | 25 | `build.gradle` (`JvmTarget.JVM_25`, `options.release = 25`) |
| Gradle wrapper | 9.7.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Mod verzió | 0.1.0 | `gradle.properties` |

### 2.3 Entrypointok

- Common/main entrypoint: `me.buciqua.blockommander.BlockCommanderMod.onInitialize()` (`src/main/kotlin/me/buciqua/blockommander/BlockCommanderMod.kt`). Regisztrálja a contentet, létrehozza az `ApiClient`/`HallRegistry`/`Interactions` láncot, majd regisztrálja a hálózati és szerveroldali callbackeket.
- Client entrypoint: `me.buciqua.blockommander.client.BlockCommanderClient.onInitializeClient()` (`src/client/kotlin/me/buciqua/blockommander/client/BlockCommanderClient.kt`). Csak az űrlapnyitó payloadot fogadja és a `CkFormScreen`-t nyitja.
- Külön `server` entrypoint nincs; a `main` entrypoint `environment: "*"` alatt fut (`src/main/resources/fabric.mod.json`). A `src/main` kód nem importál `net.minecraft.client` csomagot.

## 3. Képességmátrix

Az állapotok repository-bizonyítékra vonatkoznak. Ahol a kódút létezik, de jelen felderítésben nem futott játékos klienssel, ott ezt külön jelzi a megjegyzés.

| Képesség | Állapot | Bizonyíték / korlát |
|---|---|---|
| Fabric mod és common/client entrypoint | MŰKÖDIK | `fabric.mod.json`; `BlockCommanderMod`; `BlockCommanderClient` |
| Dedikált szerver klienscsomag-izoláció | MŰKÖDIK | `build.gradle` split source set; klienskód csak `src/client` alatt |
| Fabric szerver → Python HTTP alapút | MŰKÖDIK | `Interactions` → `ApiClient` → `ck_webhook.py`; GET/POST, JSON és főszálas callback |
| Teljes játékbeli hangár/Stage blokkút | RÉSZBEN | A híváslánc implementált; `docs/MANUAL-TEST.md` szerinti valódi klienses próba nincs frissen végrehajtva |
| Stage-azonosítás | MŰKÖDIK | `HallRegistry.stageAt()` világ UUID + befoglaló Stage alapján; `stage.id` kerül az URL-be |
| Owner ellenőrzés | RÉSZBEN | Python `StageService._authorize()` ellenőriz; a HTTP payload UUID-jét önálló transport-hitelesítés nem védi |
| Epoch/revision domainvédelem | MŰKÖDIK | `StageService.apply_graph_operation()`; a B3 dokumentált kapu szerint tesztelt |
| Epoch/revision Fabric átadás | NINCS | `StageService.view()` kiadja, de `HallRegistry.parseStage()` nem tárolja és a Fabric parancsok nem küldik |
| START blokk | MŰKÖDIK | Regisztrált blokk+item, CK tab, blockstate/model/item/loot/lang; port: `out`, OUT, FRONT |
| STOP blokk | MŰKÖDIK | Regisztrált blokk+item, teljes resource-lánc; port: `in`, IN, BACK |
| IF blokk | MŰKÖDIK | Regisztrált blokk+item, teljes resource-lánc; portok: `in`, `true`, `false` |
| FOR blokk | RÉSZBEN | Regisztrált és resource-okkal ellátott, de `ck-control-v1` státusza `UNSPECIFIED`, graph-módosításra nem kész |
| FUN blokk | RÉSZBEN | Regisztrált, blockstate/item/loot/lang van, de `models/block/fun.json` hiányzik; portstátusza `UNSPECIFIED` |
| Portszemantika és forgatás | RÉSZBEN | Pythonban kész (`port_catalog.py`, `orientation.py`); Minecraftban nincs portgeometria vagy látható portjelölés |
| CK Wire blokk/item/resource | NINCS | Nincs regisztráció, `wire` nevű asset vagy saját textúra |
| Wire placement/removal eseményút | NINCS | `Interactions` csak programblokkot és hall-controlt kezel |
| SINGLE/CROSS runtime reprezentáció | RÉSZBEN | Python domainben kész; Fabric blockstate/modell nincs |
| Stage abszolút ↔ relatív koordináta | MŰKÖDIK blokkokra | `StageService._origin()`/`_relative()`; Fabric `Stage.origin` és abszolút `StageBlock.pos` |
| Graph/wire HTTP DTO és endpoint | NINCS | `ck_webhook.py` endpointlistájában nincs graph/wire/bind/diagnostics út |
| Élbekötés hangárfali control | NINCS | Csak `SAVE_BUTTON` és `DESTROY_BUTTON` készül/kezelődik |
| Python edge-binding domain API | MŰKÖDIK | `RouteCandidate`, `BindEdgesCommand`, `bind_edges()`; ismételt binding idempotenciájára tesztek vannak |
| Fizikai topológiafelderítés | NINCS | Nincs raw wire layoutból `RouteCandidate`-et előállító runtime szolgáltatás |
| Strukturált graph diagnosztika Minecraft felé | NINCS | `ApiClient` csak egy `message` stringet mutat; graph diagnostic DTO nincs |
| Fabric automatizált tesztek | NINCS | Nincs verziózott Kotlin/Fabric tesztforrás |
| CK-04 runtime tényleges futása | NEM ELLENŐRIZHETŐ | A feladat tiltja a kliens/szerver indítást; korábbi kézi lista sincs lezárva |

## 4. Fabric → Python jelenlegi eseményút

### 4.1 Ténylegesen összekötött út

1. `BlockCommanderMod.onInitialize()` létrehozza az `Interactions(ApiClient(), HallRegistry())` objektumot.
2. `Interactions.register()` szerveroldali Fabric callbackeket regisztrál:
   - `ServerLifecycleEvents.SERVER_STARTED`: világonként hall-lista frissítés;
   - `ServerPlayConnectionEvents.JOIN`: tulajdonosi restore;
   - `AttackBlockCallback`: Hangár Kard A-pont;
   - `UseBlockCallback`: control, hallkészítés, blokkszerkesztés és programblokk-lerakás;
   - `PlayerBlockBreakEvents.BEFORE`: Stage-blokk törlés.
3. `ApiClient` Java `HttpClient.sendAsync()` hívással JSON GET/POST kérést küld a `blockommander.api` system propertyre, alapból `http://127.0.0.1:5001`.
4. A Flask `create_app()` végpontjai (`python-service/src/ck_webhook.py`) a `HallService` vagy `StageService` metódusait hívják.
5. Az aszinkron válasz kezelése visszakerül a Minecraft szerver főszálára (`server.execute(task)`), majd a `HallRegistry` frissül és a `WorldRenderer` írja a világot.
6. HTTP/JSON hiba esetén az `ApiClient.request()` piros `[CK]` chatüzenetet küld; sikeres `message` esetén zöldet.

Konkrét Stage-blokk lerakási út: `Interactions.placeProgramBlock()` → `POST /api/v1/stages/<stage_id>/blocks` → `StageService.place()` → `StageService.view()` → `HallRegistry.updateStage()` → `WorldRenderer.renderStage()`.

### 4.2 Azonosítók és jogosultság

- Világazonosító: `WorldIdentity.uuid()` a `blockommander:<levelName>:<dimension>` szöveg determinisztikus name-UUID-ja. Ez nem a world save-ból olvasott, véletlen perzisztens UUID (`runtime/WorldIdentity.kt`).
- Stage: a `HallRegistry` a világ UUID-je és az abszolút pozíció alapján választ Stage-et; az URL a Python által kiadott stabil `stage_id`-t használja.
- Játékos: a Fabric szerver a callbackből kapott `ServerPlayer.uuid` értékét teszi a JSON-ba (`Interactions.playerPayload()`), tehát a Minecraft kliens nem adhat szabadon UUID-t ezen a kódúton. A Python viszont a JSON-mezőt bízza meg; nincs aláírt kérés, token, mTLS vagy Unix-socket peer ellenőrzés.
- Owner-check: a Python `StageService._authorize()` a workspace `owner_uuid` értékéhez hasonlít.
- A Fabric `placeProgramBlock()` üzenete azt mondja, hogy csak „saját” Stage használható, de lokálisan csak a Stage-térfogatot ellenőrzi; a tényleges tulajdonosi döntést helyesen a Python végzi.

### 4.3 Epoch, revision és hibák

- A Python `StageService.view()` kiadja a `workspace_epoch`, `working_revision`, `saved_version`, `port_catalog_version` és kompatibilitási mezőket.
- A Kotlin `HallRegistry.Stage` csak `id`, `origin`, méret, `dirty` és blokkok mezőket tart; `parseStage()` eldobja az epoch/revision/graph envelope-ot.
- A jelenlegi blokk CRUD nem küld epoch/revisiont. A SAVE/REBUILD opcionálisan elfogadja a párt, de a Fabric nem adja át. Graphműveletnél az `apply_graph_operation()` kötelezően várná mindkettőt.
- A Python `GraphOperationRejected` diagnosztikát hordoz, de a Flasknek nincs graph-specifikus hibafordítója; a jelenlegi általános `HallConflict` válasz csak egy üzenetet adna vissza.

### 4.4 Ami csak egymás mellett létezik

A Fabric runtime nem hívja a `StageService.apply_graph_operation()`, `bind_edges()`, `validate_graph()` vagy destruction API-k egyikét sem. A Python graph domain és a Fabric interakciós réteg ezért jelenleg **nem alkot graph-integrációs eseményutat**, még akkor sem, ha ugyanabban a repositoryban vannak.

## 5. CK-blokkok és portok

### 5.1 Regisztráció és elhelyezés

`ModContent` öt `CkProgramBlock` + `BlockItem` párt regisztrál: `start`, `stop`, `if`, `for`, `fun`. Mind az öt bekerül a `blockommander:ck` creative tabba a `HANGAR_SWORD` itemmel együtt. A Stage-en történő elhelyezést a `UseBlockCallback` elfogja, `ModContent.typeOf()` alapján Python parancsot küld, majd csak a válaszból rendereli a blokkot.

`CkProgramBlock` egyetlen saját állapota a horizontális `FACING`; blokk-entitás, portállapot, csatlakozási maszk vagy külön portobjektum nincs. A jelenlegi `cube_all` modellek szimmetrikus vanilla terrakotta textúrákat használnak, ezért a tájolás és a portoldal vizuálisan nem olvasható.

### 5.2 Resource-állapot

- START, STOP, IF, FOR: blockstate, blokkmodell, itemdefiníció, itemmodell, loot és nyelvi kulcs megtalálható.
- FUN: blockstate, itemdefiníció, itemmodell, loot és nyelvi kulcs megvan, de a hivatkozott `assets/blockommander/models/block/fun.json` hiányzik.
- Saját PNG/textúra nincs; a modellek vanilla textúrákra hivatkoznak.

### 5.3 Illeszkedés a `ck-control-v1` katalógushoz

| Típus | Fabric | Python portszerződés | Illeszkedés |
|---|---|---|---|
| START | Regisztrált, `FACING` | `out`: CONTROL/OUT/FRONT, min=1, max=1 | Szemantikailag használható; nincs látható FRONT/out port |
| STOP | Regisztrált, `FACING` | `in`: CONTROL/IN/BACK, min=1, max=1 | Szemantikailag használható; nincs látható BACK/in port |
| IF | Regisztrált, `FACING` | `in` BACK; `true` RIGHT; `false` LEFT | Szemantikailag definiált; három port egyike sem jelenik meg |
| FOR | Regisztrált, `FACING` | `UNSPECIFIED` | Csak vizuális/Stage-blokk; graphműveletet blokkolhat |
| FUN | Regisztrált, `FACING` | `UNSPECIFIED` | Graphra nem használható; blokkmodell is hiányos |

A lokális oldal → világirány transzformáció már kész a Python `hangar.graph.orientation.world_side()` és `port_connection_position()` függvényeiben. Ezek a node Stage-relatív pozíciójából és orientációjából az azonos Y-szintű szomszéd wire cellát adják. Ezt nem szabad Kotlinban szemantikai szabályként lemásolni.

## 6. CK Wire és Élbekötés jelenlegi állapota

### 6.1 CK Wire

**NINCS** Fabric oldali CK Wire:

- nincs blokk- vagy itemregisztráció a `ModContent`-ben;
- nincs blockstate, modell, item, loot, lang vagy textúra;
- nincs placement/break callback ág;
- nincs Fabric `WireCell`, `WireChannel`, SINGLE/CROSS vagy route cache;
- nincs világkoordináta → graph wire koordináta DTO;
- nincs szerveroldali wire render/reconciliation.

**MŰKÖDIK a Python domainben**, de runtime-híd nélkül:

- `WireChannel`: `SINGLE`, `NS`, `EW` (`graph/model.py`);
- `WireCell` vagy egy SINGLE csatorna, vagy független NS+EW CROSS;
- `WireStep`, `EdgeRoute`, validáció és perzisztencia;
- a wire cellák koordinátái Stage-relatív `GridPos` értékek.

### 6.2 Élbekötés

**NINCS** hangárfali Élbekötés control. A Python `builder.control_elements()` csak `OWNER_SIGN`, `DESTROY_BUTTON`, `DESTROY_SIGN`, `SAVE_BUTTON`, `SAVE_SIGN` elemeket ad. A Fabric `Interactions.handleControl()` csak a DESTROY és SAVE gombot kezeli.

**MŰKÖDIK a domain binding magja**: a `graph.binding.bind_edges()` egy vagy több előre feloldott `RouteCandidate`-et atomi módon validál és köt; új edge-et hoz létre vagy a szemantikai endpointkulcs alapján meglévőt használ újra. Nem végez raw wire-hálózatból útvonalfelderítést. A `StageService.apply_graph_operation()` alkalmas arra, hogy ezt owner/epoch/revision ellenőrzéssel és Stage-lock alatt alkalmazza, de nincs hozzá publikus alkalmazási wrapper vagy HTTP endpoint.

Wire lerakásakor jelenleg azért sem keletkezhet automatikus `GraphEdge`, mert wire sincs; a CK-04 rögzített döntése szerint a későbbi megvalósításban is külön kell maradnia a layout-módosításnak és az explicit Élbekötésnek.

## 7. Hiányzó integrációs pontok

Prioritási sorrendben:

1. **Verziózott graph command/response DTO.** Kötelező mezők: `request_id`, `stage_id`, szerverből származó `player_uuid`, `workspace_epoch`, `expected_working_revision`, Stage-relatív pozíció/cellatartalom. A válasz adja vissza az új revisiont, graph státuszt, alkalmazott diffet és strukturált diagnosztikát.
2. **Fabric graph envelope megtartása.** A `HallRegistry.Stage` és `parseStage()` ma eldobja az epoch/revision/portkatalógus mezőket, és nem tart wire/route/edge vetítést.
3. **Python wire-layout alkalmazási wrapper.** A raw DTO-ból domain `WireCell` készül, majd `StageService.apply_graph_operation()` + `replace_content()` frissíti a teljes snapshotot GraphEdge létrehozása nélkül.
4. **Python topológiafelderítés.** A Stage-relatív wire cellák és `port_connection_position()` alapján determinisztikus `RouteCandidate` kell. Az első szeletben elég egy START és STOP közötti egyetlen SINGLE cella; általános kanyar/T/CROSS algoritmus későbbi bővítés.
5. **Explicit bind alkalmazási wrapper és endpoint.** A felderített jelöltet `BindEdgesCommand`/`bind_edges()` kapja, majd ugyanazon Stage-lock alatt kerül vissza a workspace-be.
6. **CK Wire Fabric blokk és resource-ok.** Első szeletre SINGLE, kétoldali egyenes vizuális állapot elegendő; CROSS ne kerüljön félig implementálva.
7. **Szerveroldali wire callback és world commit.** A programblokkok mintájára: Python elfogadás előtt a Fabric ne hagyjon tartós világváltozást; elfogadás után a szerver főszálán rendereljen.
8. **Élbekötés control.** Új `EDGE_BIND_BUTTON` a Python halltervben és ág az `Interactions.handleControl()` metódusban. A `hall_controls.kind` varchar, tehát ehhez önmagában nem látszik szükséges DB-sémamódosítás.
9. **Graph response/rendering.** A `StageService.view()` jelenleg blokkokat ad, de wire/edge/route részleteket nem; külön graph DTO vagy kibővített projekció szükséges.
10. **Strukturált hibavisszajelzés.** A domain diagnostic code/severity/referenciák ne vesszenek egyetlen generikus `message` stringbe.
11. **Resource-javítás.** A hiányzó FUN blokkmodell a CK-04/B-től független hiba, de a resource-validációban láthatóvá kell tenni.
12. **Tesztinfrastruktúra.** Python endpoint/application tesztek és legalább izolált Fabric callback/blockstate teszt szükséges; jelenleg nincs Kotlin/Fabric tesztforrás.

### Szükséges azonosítók és koordináták

- `world_uuid`: a megfelelő futó dimenzió/hall kiválasztására;
- `hall_id`: control és Stage tulajdonosi kontextus;
- `stage_id`: minden graph parancs célja;
- `player_uuid`: kizárólag a szerveroldali `ServerPlayer` kontextusból;
- `workspace_epoch + expected_working_revision`: processz- és tartalomverzió együtt;
- `request_id`: retry/idempotencia az aszinkron HTTP határon;
- `instance_id + port_key`: stabil szemantikus endpoint;
- `route_id + edge_id`: Python által kiosztott stabil identitások.

A hiteles graph koordináta Stage-relatív: `relative = absoluteWorldPos - stage.origin`; visszarendereléskor `absolute = stage.origin + relative`. A transzformációt a Fabric transport adapter végezheti, de a Python ellenőrizze a tartományt. A port világoldali szomszédját a Python `orientation.py` számolja ki a relatív node-pozícióból. Az első szelet egy horizontális síkot használjon; a pontos wire Y-szint külön döntés.

## 8. Lehetséges megvalósítási utak

### A. Minden wire művelet Python-first, külön explicit bind – **ajánlott**

Fabric szerver elfogja a wire placement/removal eseményt, elküldi a verziózott parancsot, a Python workspace atomi módon elfogadja, és csak ezután renderel a Fabric. Az Élbekötés külön endpoint, amely Pythonban derít fel és köt.

- Előny: betartja, hogy a mentetlen Stage állapot a Python workspace-ben hiteles; nincs Kotlin gráfmotor; epoch/revision és owner-check minden lépésen; a világ csak elfogadott állapotot vetít.
- Hátrány: minden wire művelet HTTP round-trip; klienspredikció és rollback UX prototípust igényel; request-id/reconciliation szükséges.

### B. Fabricben ideiglenes wire-draft, teljes layout küldése csak Élbekötéskor

A világban szabadon épül a wire, majd a gomb teljes Stage-layoutot küld a Pythonnak.

- Előny: kevés endpoint és kisebb placement-latency.
- Hátrány: sérti azt az alapelvet, hogy a mentetlen Stage edit a Python workspace-ben él; ghost wire és versenyhelyzet keletkezhet; a teljes világscan és trust határ bonyolult. **Nem ajánlott.**

### C. Python közvetlenül olvassa/írja a Minecraft-világot

- Előny: látszólag kevesebb Fabric DTO.
- Hátrány: sérti az `AGENTS.md` szerverautoritási szabályát és a `BLOCKCOMMANDER_WORLD_DRIVER=fabric` határt; visszahozná az mcpi-dupla világírót. **Elvetendő.**

## 9. Ajánlott CK-04/B minimális implementáció

### 9.1 Szűk cél

Egyetlen, egyenes, egy wire-cellás SINGLE kapcsolat két azonos Y-szintű blokk között:

```text
START (facing EAST) | wire SINGLE E–W | STOP (facing EAST)
                     ^ ugyanaz a cella a START.out és STOP.in számára
```

Ez bizonyítja a teljes architekturális utat anélkül, hogy a kanyar, T-elágazás vagy CROSS UX-et idő előtt rögzítené.

### 9.2 Javasolt implementációs sorrend

1. **Python szerződés és application API:** graph state DTO; idempotens wire-upsert parancs; explicit bind parancs; strukturált diagnosztikák. A meglévő `StageService.apply_graph_operation()`, `replace_content()`, `port_connection_position()` és `bind_edges()` legyen felhasználva.
2. **Python teszt:** wire-upsert után nulla edge; Élbekötés után pontosan egy `START.out → STOP.in` CONTROL edge és VALID route; ismétlés nem duplikál és nem léptet revisiont.
3. **Fabric állapotvetítés:** `HallRegistry.Stage` őrizze meg legalább a `workspace_epoch`, `working_revision`, graph státusz és wire diff mezőket.
4. **CK Wire blokk/item/resource:** kizárólag SINGLE E–W/N–S egyenes prototípus, vékony vizuális shape-pel; ne legyen CROSS vagy redstone-szemantika.
5. **Wire callback:** Stage/owner-kontekstus, relatív koordináta, request-id; Python siker után `WorldRenderer` írja a világot, hiba esetén nincs tartós elhelyezés.
6. **Élbekötés control:** `EDGE_BIND_BUTTON` generálás és kezelőág; siker/hiba chatüzenet a strukturált Python válaszból.
7. **Izolált integrációs próba**, csak külön CK-04/B és környezeti engedéllyel; ebben a CK-04/A műszakban nem futtatandó.

### 9.3 Várhatóan módosítandó rétegek/fájlok

- `python-service/src/ck_webhook.py`: graph state/wire/bind HTTP végpontok és hibafordítás;
- `python-service/src/hangar/stage.py`: publikus, szűk wire/bind application wrapper vagy annak hívási pontja;
- új Python integrációs modul a topológiafelderítéshez; a szabály ne kerüljön a Flask route-ba;
- `python-service/src/hangar/builder.py`: `EDGE_BIND_BUTTON` control;
- `src/main/kotlin/.../runtime/HallRegistry.kt`: graph envelope és wire vetítés;
- `src/main/kotlin/.../runtime/Interactions.kt`: wire placement/removal és bind gomb;
- `src/main/kotlin/.../runtime/WorldRenderer.kt`: elfogadott wire diff renderelése;
- `src/main/kotlin/.../ModContent.kt` és új `CkWireBlock` osztály;
- `src/main/resources/assets|data/blockommander/**`: wire blockstate/model/item/lang/loot;
- Python és Fabric tesztforrások.

Nem szükséges új DB-migráció az első, SAVE nélküli demonstrációhoz; a wire/edge perzisztenciatáblák már léteznek, de a CK-04/B nem hív SAVE-et.

## 10. Konkrét acceptance test

Előfeltétel: tulajdonosi játékos, betöltött Stage, kompatibilis `ck-control-v1` workspace; a teszt kezdetén ismert `(epoch=E, working_revision=R)`.

1. A játékos lerak egy EAST orientációjú START blokkot relatív `(0,0,0)` és egy EAST orientációjú STOP blokkot `(2,0,0)` pozícióra.
2. A játékos CK Wire-rel kitölti a köztes `(1,0,0)` cellát. A Fabric szerver parancsa a Python workspace-ben SINGLE `{EAST,WEST}` `WireCell`-t hoz létre, revisiont pontosan eggyel növel, majd a Fabric szerver megjeleníti a wire-t.
3. Ellenőrzés: a workspace-ben ekkor még **nincs GraphEdge**.
4. A játékos megnyomja az `EDGE_BIND_BUTTON` gombot. A szerver az aktuális epoch/revisionnel hívja a Pythont.
5. A Python a `START.out` FRONT és `STOP.in` BACK port szomszédjaként ugyanazt a wire cellát találja, `RouteCandidate`-et készít és `bind_edges()`-t futtat.
6. Siker esetén pontosan egy CONTROL `GraphEdge` van: `START.instance_id/out → STOP.instance_id/in`; pontosan egy VALID route tartozik hozzá; a Stage DIRTY; nincs PostgreSQL SAVE.
7. Minecraft zöld, egyértelmű sikerüzenetet mutat. Hibás orientáció, hiányzó wire vagy stale revision esetén piros, konkrét diagnosztika jelenik meg, edge nem keletkezik és a világ nem kap elfogadatlan diffet.
8. Ugyanazon aktuális állapoton ismételt Élbekötés nem hoz létre második edge-et/route-ot és nem növeli a working revisiont.
9. Másik játékos kérése 403-mal elutasul; a workspace és a világ változatlan marad.

## 11. Nyitott architekturális döntések

1. **Wire munkasík és voxel-geometria:** fix `rel_y=0`, vagy a programblokk Y-szintje? A jelenlegi Python porttranszformáció azonos Y-szintű szomszéd cellát vár.
2. **Első wire blockstate:** két explicit egyenes shape, négyirányú connection mask, vagy későbbi általános modell előkészítése? CK-04/B-ben CROSS ne legyen félkész.
3. **Transport trust:** elegendő-e a loopback-only HTTP + hostvédelem, vagy kell shared secret/aláírás? A `player_uuid` forrása a Fabric szerveren hiteles, de a Python transport nem hitelesített.
4. **Idempotencia tárolása:** processz-memóriás request-id cache vagy workspace-parancsnapló; az epoch+revision önmagában megakadályozza a stale írást, de elveszett válasz biztonságos retryához request-id kell.
5. **Reconciliation:** HTTP success után elveszett válasz vagy renderhiba esetén honnan kér újra graph/layout vetítést a Fabric? Szükséges egy read endpoint vagy teljes Stage graph response.
6. **Diagnostic DTO:** mely code/severity/referencia mezők jelenjenek meg chatben, és mi marad log/debug részlet?
7. **Élbekötés gomb helye:** a meglévő generátor geometriájában stabil, ütközésmentes új control-pozíció szükséges.
8. **FUN asset:** a hiányzó blokkmodell javítása a CK-04/B része legyen-e, vagy külön regressziójavítás?
9. **Világazonosság:** elfogadott-e hosszú távon a world name + dimension determinisztikus UUID-ja, vagy valódi per-world perzisztens azonosító szükséges?

## 12. Git státusz és változatlansági nyilatkozat

A felderítés kezdeti HEAD-je és a jelentés elkészítése előtti HEAD azonos:

```text
2db713c5fa2a35c6eb9b963f0e2b3ae09adf5cc0
CK-03/B3 – StageService graph-aware SAVE/REBUILD
```

A kiinduló tracked worktree és index tiszta volt. A felhasználó által rendelkezésre bocsátott két nem követett dokumentum:

```text
?? docs/001 - CK-04 dobbantó.md
?? docs/START-HERE-next-chat.md
```

Ehhez kizárólag ez az engedélyezett, új jelentés társult:

```text
?? docs/CK-04A-fabric-integracios-felderites.md
```

Nem történt `git add`, commit, push, build, tesztfuttatás, migráció, deploy, szolgáltatásindítás/leállítás, Docker-módosítás, adatbázisírás vagy Minecraft-világmódosítás. Meglévő kód, konfiguráció, index és tracked worktree nem változott. A korábbi B2-T/B3 teszteredmények dokumentált előzmények, nem ebben a felderítésben frissen futtatott eredmények.

**STOP:** CK-04/B implementáció csak külön SysOrg jóváhagyás után kezdhető.
