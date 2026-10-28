# CK-05/A1 – Fizikai vezeték-topológia felderítése és tervezése

## 1. Döntési összefoglaló

**Felderítési eredmény:** a CK-05 többcellás vezetékmodellje nem igényel teljes Graph-domain újraírást. A Python domain, a PostgreSQL snapshot és a Fabric epoch-fencing több fontos alapot már tartalmaz, de a jelenlegi alkalmazási réteg és világprojekció szándékosan CK-04/B3 méretű: pontosan egy, kelet–nyugati `SINGLE` WireCellt kezel pontosan egy START és egy STOP között.

A legfontosabb szétválasztás:

- a **fizikai topológia** a WireCell-ekből, csatornákból, CK-blokkportokból és később CK Junction portokból felépülő, automatikusan újraszámolható állapot;
- a **felismert kapcsolatjelölt** egy átmeneti, Python által számított eredmény, még nem GraphEdge;
- a **GraphEdge** kizárólag az explicit Stage-szintű Élbekötés művelet eredménye;
- a **DRAFT SAVE** a munkapéldány tartós mentése, nem publikálás;
- a **PUBLISHED snapshot** külön, jelenleg még nem létező, változtathatatlan állapot;
- a Fabric kizárólag játékbeli eseményt közvetít és hiteles Python-állapotot vetít a világba.

**Javaslat:** a fejlesztés első végrehajtható lépése tisztán Python oldali, determinisztikus és ciklusbiztos fizikai topológia-felderítő legyen. A Fabric többcellás geometriát csak azután kapjon, hogy a domain eredménye és diagnosztikája stabil. A PUBLISH, a Junction és a Restore csákány külön mérföldkő legyen.

## 2. Vizsgálati keret és terminológia

Ez a dokumentum repository-felderítésen alapul; futó szolgáltatást, PostgreSQL-adatot vagy Minecraft-világot nem módosított. A forrásban nincs `WireContent`, `WireShape` vagy `WireDirection` nevű külön domainosztály. A tényleges megfelelőik:

- `WireCell`, `WireChannelState`, `WireChannel`, `CardinalDirection` és `WireStep` – `python-service/src/hangar/graph/model.py:43-46`, `146-219`;
- a fizikai elhelyezkedés és portirány számítása – `python-service/src/hangar/graph/orientation.py:28-45`;
- a Fabric megjelenítési tengelye és színe – `src/main/kotlin/me/buciqua/blockommander/CkWireBlock.kt:16-45`.

A dokumentumban:

- **cella**: egy Stage-relatív Minecraft-rácspont;
- **csatorna**: egy cellán belüli, egymástól logikailag elkülönített vezetési sík (`SINGLE`, `NS`, `EW`);
- **fizikai komponens**: kölcsönösen elérhető csatornák és portvégek halmaza;
- **kapcsolatjelölt**: két kompatibilis szemantikai port között felismert, rendezett fizikai út;
- **bekötött útvonal**: GraphEdge-hez rendelt EdgeRoute;
- **publikált útvonal**: egy későbbi, immutábilis PUBLISHED snapshotban szereplő útvonal.

## 3. Jelenlegi architektúra – bizonyított állapot

### 3.1. Python Graph domain

#### Ami általánosítható

1. A `WireCell` nem kizárólag egyenes vezetéket ábrázol. Egy `SINGLE` csatorna legfeljebb két tetszőleges kardinális kapcsolatot tartalmazhat, ezért az egyenes, a sarok, az egyvégű és a nulla végű DRAFT cella reprezentálható. Háromirányú junction szándékosan tiltott (`model.py:146-164`).
2. Egy cella vagy egyetlen `SINGLE` csatorna, vagy egymástól független `NS` és `EW` csatornapár. Ez már most kifejezi a **nem összekötő keresztezést** (`model.py:168-197`).
3. Az `EdgeRoute.steps` rendezett, több `WireStep`-ből álló tuple, tehát többcellás lineáris út tárolható (`model.py:200-219`).
4. A validátor ellenőrzi a lépések vízszintes szomszédosságát és a kétirányú csatlakozást. A CROSS kiválasztott csatornáját is ellenőrzi (`validation.py:352-403`).
5. A `bind_edges()` egyszerre több kapcsolatjelöltet tud atomi módon bekötni, meglévő szemantikai GraphEdge-et újrahasználni és az érintett route-okat cserélni (`binding.py:309-445`). Ezt a `test_bind_multiple_valid_connections_atomically` teszt hat külön kapcsolattal gyakorolja (`python-service/tests/test_graph_binding.py:126-154`).
6. A portkatalógus verziózott, orientációfüggetlen lokális oldalt és min/max élkardinalitást ad meg. START `out`, STOP `in`, IF `in/true/false` már szerepel benne (`port_catalog.py:13`, `145-200`).
7. A domain Graph-validáció tiszta, nem mutáló művelet. Jelenlegi határa dokumentált: a teljes programszerkezeti elérhetőség és ciklusszabályok későbbre vannak halasztva (`validation.py:1-7`, `31-45`, `455-481`).

#### Ami csak látszólag általános

1. Nincs fizikai **pathfinding/discovery** algoritmus. A validátor már megkapott EdgeRoute-ot ellenőriz; nem állít elő route-ot WireCell-hálózatból.
2. A HTTP alkalmazási réteg `_discover_single_cell_start_stop()` függvénye pontosan egy START-ot, egy STOP-ot és egyetlen E–W `SINGLE` cellát fogad el (`graph_application.py:462-491`).
3. A Wire lerakási út `_single_cell_start_stop_position()` alapján megköveteli az EAST orientációt és azt, hogy a két port ugyanarra az egy cellára mutasson (`graph_application.py:494-515`).
4. Egy `WIRE_LAYOUT` parancs jelenleg pontosan egy változást vihet, és csak a fenti egycellás geometriát fogadja el (`graph_application.py:352-395`). Ez **alkalmazási/API-adapter korlát**, nem a `WireCell` domain korlátja.
5. A Stage-szintű `BIND_EDGES` belépési pont létezik, de csak egyetlen, hard-coded kapcsolatjelöltet ad át az általános `bind_edges()` domainfüggvénynek (`graph_application.py:397-423`).

#### Több START-ot tartalmazó DRAFT

- A Stage snapshot több `GraphNode`-ot tárol, a teljes snapshot SAVE pedig nem követel publikálható vagy VALID programot (`stage.py:321-344`).
- A PostgreSQL `stage_blocks` és a Graph snapshot több START/STOP rekordot képes tárolni.
- A Graph validátor a START `out` portjára jelenleg `min_edges=1, max_edges=1` szabályt alkalmaz (`port_catalog.py:145-161`, `168-176`), így egy bekötetlen START validációs hibát adhat. Ez nem azonos a DRAFT menthetetlenségével.
- A jelenlegi `WIRE_LAYOUT` és `/ckbind` alkalmazási adapter viszont pontosan egy START/STOP párhoz kötött, tehát több START mellett a fizikai vezeték művelete `UNSUPPORTED_TOPOLOGY` eredménnyel elutasítható (`graph_application.py:465-485`, `497-505`).

**Következtetés:** a több START-ot tartalmazó DRAFT menthetősége domain/persistencia szinten megvan; a több START közötti fizikai kapcsolatfelderítés és bind nincs meg.

### 3.2. CROSS, TURN, Junction és ciklusok

| Képesség | Domain reprezentáció | Validáció | Automatikus felderítés | Fabric |
| --- | --- | --- | --- | --- |
| Többcellás egyenes | Igen, több `WireStep` | Igen | Nincs | Nincs |
| TURN | Igen, két kardinális kapcsolatú `SINGLE` | Igen, lépéspáronként | Nincs | Nincs sarok blockstate/model |
| Független CROSS | Igen, `NS` + `EW` | Igen, csatornánként | Nincs | Nincs |
| T-junction WireCellben | Szándékosan nem | `SINGLE` >2 tiltott | Nincs | Nincs |
| Külön CK Junction | Nincs | Nincs portkatalógus-bejegyzés | Nincs | Nincs Block/BlockItem |
| Ciklusbiztos fizikai bejárás | Nem releváns a tárolómodellben | A programszintű cycle detection deferred | Nincs | Nincs |

A független CROSS viselkedését teszt is rögzíti (`python-service/tests/test_graph_validation.py:285-303`). A `destruction.py` képes CROSS-csatornát egyértelműen kiválasztani és a maradó csatornát `SINGLE`-re alakítani (`destruction.py:126-165`, `235-272`), de a megerősített destrukció a GraphEdge-et és a route-ot is törli (`destruction.py:448-468`). Ez ellentétes a CK-05 elfogadott szabállyal, ezért **nem használható közvetlenül** automatikus vezeték-károsodás kezelésére. Legfeljebb a csatornaazonosítás és konfliktusdiagnosztika tiszta részei emelhetők át.

### 3.3. Fabric runtime

#### Blockstate és modellek

- A `CkWireBlock` jelenleg csak `HORIZONTAL_AXIS` és `VISUAL` propertyt tárol. Nincs NORTH/EAST/SOUTH/WEST kapcsolati bit, nincs sarok- vagy CROSS-alak (`CkWireBlock.kt:22-45`).
- A három tényleges vizuális változat `CYAN`, `GREEN`, `ORANGE` (`CkWireBlock.kt:16-19`). A tervezett sárga és olívazöld szemantika nincs külön modellezve.
- Az assetek a `src/main/resources/assets/blockommander/blockstates/ck_wire.json` és `models/block/ck_wire*.json` fájlokban vannak; jelenleg tengely/szín variánsokra készültek, nem négyirányú szomszédságra.

#### Placement/break és WIRE_LAYOUT

- A jobb kattintást és blokktörést szerveroldalon az `Interactions` elfogja, így a Fabric nem engedi a Minecraftot önálló domainíróvá válni (`Interactions.kt:61-106`).
- A `placeWire()`/`removeWire()` Stage-et azonosít, majd a `WireInteractionCoordinator` felé továbbít (`Interactions.kt:220-257`).
- Lerakáskor a coordinator mindig `SINGLE` + `EAST/WEST` cellát küld, függetlenül a szomszédoktól (`WireInteractionCoordinator.kt:96-124`).
- A művelet epoch/revision fencinget használ, szükség esetén Graph GET-tel reconciliál, és csak hiteles válasz után renderel (`WireInteractionCoordinator.kt:111-165`, `168-190`). Ez újrahasznosítandó.

#### World projection és szín

- A projektor csak egyetlen `SINGLE` E–W geometriát tekint renderelhetőnek; minden más `UNSUPPORTED_WIRE_GEOMETRY` (`WireWorldProjection.kt:55-82`).
- A világvetítés hiteles Stage ID/epoch/revision egyezést követel, nem ír felül idegen blokkot, és eltávolítja a snapshotban már nem szereplő CK Wire blokkokat (`WireWorldProjection.kt:55-111`).
- A szín jelenleg az EdgeRoute állapotából származik: unbound → cián, VALID és létező edge → zöld, hibás/unknown → narancs (`WireVisualProjection.kt:6-35`; `WireWorldProjection.kt:123-147`). Nincs PUBLISHED bemenet.

#### `/ckbind` és reconnect

- A Fabric bind Stage-szintű: a `BindEdgesCommand` nem tartalmaz kijelölt route-ot vagy cellát (`WireInteractionCoordinator.kt:57-93`). Ez illeszkedik az elfogadott egyetlen fali Élbekötés gombhoz.
- A tényleges korlát Python oldali: a Stage-szintű kérésből csak az egycellás START–STOP jelölt készül.
- A T8 authoritative reconnect sorrendje újrahasznosítható: Stage reload, programblokkok vetítése, Graph/wire reconciliation, majd SAVE-tábla (`StageReconnectReconciler.kt:8-62`; bekötés: `Interactions.kt:161-215`).
- A `HallRegistry` és `GraphProjectionStore` lifecycle-fencingje már kezeli az authoritative epochváltást (`HallRegistry.kt:72-109`; `GraphProjectionStore.kt:45-72`). Többcellás adatnál ugyanazt az elvet kell megtartani.

#### CK Junction megvalósíthatósága

Fabric oldalon technikailag ugyanazzal a regisztrációs mintával létrehozható külön Block és BlockItem, mint a CK Wire (`ModContent.kt:8-50`). Ez azonban önmagában nem elegendő. A Junctionnak Pythonban stabil identitás, Stage-relatív pozíció, orientáció, portkészlet és belső összekötési szabály kell. Enélkül a Fabric blokk csak dekoráció lenne, vagy jogosulatlanul vinné át a szemantikai tekintélyt a kliens/szerver modba.

### 3.4. Persistencia és verziózás

#### Ami ma mentődik

Az additív `006_graph_snapshots.sql` migráció külön tárolja:

- a GraphEdge-eket és szemantikai portvégeiket (`10-34`);
- az EdgeRoute-okat, státuszukat és opcionális edge/port kapcsolatukat (`41-76`);
- a route rendezett lépéseit, pozícióval és csatornával (`83-100`);
- a WireCell típusát (`SINGLE`/`CROSS`) és csatornánkénti négybites kapcsolatmaszkját (`102-130`);
- a `SINGLE` vagy pontosan `NS+EW` csatornakészlet DB-invariánsát (`136-177`).

A repository a teljes Stage snapshotot CAS-szal, egy tranzakcióban cseréli, majd növeli a `saved_version` értéket (`repository.py:469-531`). A SAVE után a workspace `dirty=false` lesz (`stage.py:321-344`). Többcellás `route_steps` és több WireCell tehát már most veszteség nélkül persistálható.

#### Ami nem létezik

- Nincs külön PUBLISHED snapshot, publication ID vagy published version.
- Nincs PUBLISH művelet vagy publikációs validációs kapu.
- A `saved_version` a módosítható DRAFT mentési CAS-verziója, ezért nem használható változtathatatlan publication ID-ként.
- A `graph_snapshot_version` formátumkompatibilitási verzió, nem kiadási verzió.
- A `workspace_epoch` és `working_revision` futó munkapéldány-lifecycle, nem tartós publikációazonosító.
- A jelenlegi EdgeRoute ID ugyan mentődik, de a következő SAVE lecserélheti a DRAFT route tartalmát; önmagában nem garantál „utolsó publikált útvonalat” a Restore csákánynak.

**Következtetés:** PUBLISH-hoz később additív persistencia szükséges, de ennek sémája csak a publikációs szemantika ADR-szintű lezárása után tervezhető. A jelen felderítés nem bizonyítja, hogy egyetlen JSON snapshot vagy normalizált publication táblák a jobb megoldás.

## 4. Javasolt célmodell

### 4.1. Fizikai topológiai gráf

A Python számítsa ki a fizikai gráfot az aktuális Stage snapshotból:

- csúcs: `(GridPos, WireChannel)`;
- portcsúcs: `(instance_id, port_key)` a portkatalógus és orientáció alapján;
- junction-portcsúcs: a CK Junction stabil azonosítója és portja;
- szomszédél: két vízszintesen szomszédos csatorna kölcsönösen egymás felé kapcsolódik;
- portél: a port előtti cella csatornája a port világoldala felé kapcsolódik;
- CROSS belső szabály: `NS` és `EW` között nincs él;
- Junction belső szabály: csak a Junction domain-definíciója által engedett portpárok között van él.

A bejárás BFS vagy DFS lehet, de kötelező a látogatott kulcsok halmaza, így ciklus esetén is véges. A determinisztikus eredményhez minden portot, szomszédot, komponenst és diagnosztikát stabil kulcs szerint kell rendezni.

Egy fizikai komponens kimenete legalább:

- érintett cellák/csatornák;
- elért szemantikai portok;
- teljesség és ambiguitás;
- rendezett, egyszerű út lépései, ha egyértelműen két kompatibilis portot köt össze;
- diagnosztikák nyitott végről, illegális leágazásról, ciklusról, inkompatibilis portirányról vagy többértelmű útvonalról.

Az automatikus discovery **csak származtatott eredményt** készíthet. Nem hívhat `bind_edges()`-t, nem törölhet GraphEdge-et, nem módosíthat EdgeRoute-státuszt és nem menthet.

### 4.2. CK Junction

A Junction ne legyen háromkapcsolatú `WireCell`: ezt a jelenlegi domain-invariáns helyesen tiltja. Javasolt külön Stage-entitás stabil instance ID-val és verziózott portdefinícióval.

Minimális tulajdonságok:

- `instance_id`, `stage_id`, pozíció, orientáció, junction type/version;
- névvel ellátott kardinális portok;
- deklarált belső kapcsolási politika;
- külön Fabric Block/BlockItem és authoritative világprojekció;
- portjai a Python discovery és bind bemenetei.

Nyitott döntés, hogy a Junction teljes értékű `GraphNode` legyen-e a szemantikai gráfban, vagy külön fizikai connector entitás. A „három vezeték egy közös WAGO-n” jelentése miatt ezt a portkardinalitási és végrehajtási modellel együtt kell lezárni. A jelenlegi `GraphEdge` pontosan egy source és egy target portot köt össze (`model.py:119-142`), ezért egy háromágú logikai kapcsolat várhatóan több GraphEdge/EdgeRoute lesz, nem egy elágazó EdgeRoute.

### 4.3. Kapcsolatjelölt és explicit bind

Az automatikus discovery készítsen stabil `RouteCandidate`-szerű értékeket, de tartsa őket ephemeral/derived állapotban. Az Élbekötés:

1. a teljes Stage aktuális fizikai topológiáját újraszámolja;
2. csak a teljes, egyértelmű, portirány és portkardinalitás szerint kompatibilis jelölteket gyűjti;
3. az egész jelölthalmazt az általános `bind_edges()` függvénynek adja;
4. atomi eredményt ad vissza;
5. meglévő szemantikai GraphEdge-et ID szerint újrahasznál, ha a végpontkulcs azonos;
6. nem törli automatikusan azokat a GraphEdge-eket, amelyekhez pillanatnyilag nincs ép fizikai út.

Ehhez a `bind_edges()` meglévő atomi preflightja jó alap, de az „érintetlen régi edge route-ja megmarad” és a „fizikailag sérült, mégis publikált edge” szabályát célzott tesztekkel rögzíteni kell.

### 4.4. Sérülés és javítás

WireCell lerakás/törlés után:

- a Python csak a fizikailag érintett komponenseket számolja újra;
- a WireCell-layout módosul és a workspace dirty lesz;
- a GraphEdge és a publikált snapshot változatlan;
- a meglévő DRAFT EdgeRoute-ot sem szabad automatikusan törölni vagy `BROKEN`-re írni;
- külön, származtatott `live_physical_status` jelzi, hogy az aktuális route fizikailag egyezik-e;
- javítás után ugyanaz a route újra felismerhető, de publikált zöld csak pontos published-egyezés esetén jár.

Ez szándékosan eltér a jelenlegi `destruction.confirm_destruction()` szemantikájától, amely eltávolítja az edge-et és route-ot. A két use case-t külön API-val és névvel kell tartani.

## 5. Javasolt adat- és eseményfolyam

### 5.1. Wire elhelyezése vagy eltávolítása

1. A Fabric elfogja a játékeseményt, Stage-et és relatív cellát azonosít.
2. A Fabric a cella fizikai tényét küldi Pythonba. Nem dönt GraphEdge-ről, route-ról vagy publikációról.
3. A Python epoch/revision ellenőrzés után módosítja a WireCell-layoutot.
4. A Python újraszámolja az érintett fizikai komponenseket, származtatott státuszt és diagnosztikát ad vissza.
5. A Fabric a teljes hiteles Stage/Graph snapshot alapján vetít; nem az optimista lokális blockstate lesz a tekintély.
6. A SAVE tábla a lifecycle-hoz illeszkedő hiteles dirty állapotot mutatja.

Kezdetben elfogadható a teljes Stage discovery újraszámítása; inkrementális optimalizáció csak az invariánsok és tesztek stabilizálása után indokolt.

### 5.2. Élbekötés

1. Stage-szintű `BIND_EDGES` kérés.
2. Python teljes fizikai discovery és diagnosztika.
3. Atomi bind az elfogadható jelöltekből.
4. GraphEdge/EdgeRoute változás miatt új working revision és dirty állapot.
5. A Fabric újravetít, de az új színmodellben a bind önmagában nem ad olívazöldet.

### 5.3. SAVE DRAFT

1. A jelenlegi full-snapshot CAS mentés újrahasznosítható.
2. Hiányos vezeték, több START/STOP, bekötetlen blokk és nem publikálható gráf is menthető.
3. A SAVE csak `saved_version`-t növel és dirty-t tisztít.
4. Nem változtat publicationt vagy vezetékszínt.

### 5.4. PUBLISH

1. Külön parancs és külön jogosultsági/validációs kapu.
2. A pontos, még elfogadandó programszerkezeti és fizikai szabályok ellenőrzése.
3. Siker esetén immutábilis publication és stabil publication ID/version létrehozása.
4. A Fabric az authoritative Graph GET-ben kapott publication-illeszkedésből számolja az olívazöldet.
5. Sikertelen publish nem ír részleges publicationt és nem változtat színt.

### 5.5. Reconnect és Python-restart

Megőrzendő T8 sorrend:

1. authoritative Stage reload és epochváltás;
2. programblokkok világprojekciója;
3. Graph GET/reconciliation;
4. többcellás Wire/Junction világprojekció;
5. SAVE-tábla frissítése.

Elavult vagy részleges válasz után nem lehet destruktív render. A többcellás projektor ugyanazt a Stage ID/epoch/revision fencinget használja, mint a jelenlegi `WireWorldProjector`.

## 6. Kötelező invariánsok

### 6.1. Geometria

1. Minden WireCell és Junction Stage-határon belül van.
2. Programblokk, WireCell és Junction nem foglalhatja ugyanazt a cellát.
3. `SINGLE` 0–2 kardinális kapcsolatot tartalmazhat; három vagy négy kapcsolat nem implicit junction.
4. CROSS pontosan két független csatorna: `NS={N,S}`, `EW={E,W}`.
5. Szomszédos kapcsolat csak kölcsönösen deklarált oldalakkal érvényes.
6. Egy route lépései vízszintesen szomszédosak és ugyanazon fizikai komponenshez tartoznak.
7. Egy route CROSS cellában explicit csatornát választ; nem válthat `NS` és `EW` között.
8. A sarok egy `SINGLE` csatorna két merőleges kapcsolatával reprezentálandó, nem külön inventoryelemmel.

### 6.2. Szemantika és lifecycle

1. Automatikus discovery nem hoz létre és nem töröl GraphEdge-et.
2. Wire sérülése nem töröl edge-et, nem módosít publikált snapshotot és nem ír automatikusan `BROKEN` route státuszt.
3. GraphEdge-et csak explicit bind hozhat létre vagy frissíthet az elfogadott szabályok szerint.
4. SAVE és PUBLISH külön tranzakció és külön állapotátmenet.
5. DRAFT menthető akkor is, ha a Graph validáció hibás vagy nem futott le.
6. Olívazöld csak az aktuális fizikai út és az aktív published route pontos egyezésekor jelenhet meg.
7. Epoch/revision mismatch esetén nincs világmutáció.
8. Az újraszámítás determinisztikus és ciklusbiztos.
9. Idegen Minecraft blokkot sem projection, sem Restore nem írhat felül.
10. Restore csak egy azonosított published EdgeRoute fizikai celláit érintheti; edge-et nem.

## 7. Színállapot-modell

### 7.1. Javasolt döntési sorrend

| Feltétel | Szín | Jelentés |
| --- | --- | --- |
| Lifecycle/snapshot bizonytalan vagy a fizikai komponens nyitott, sérült, illegális, többértelmű | cián | nincs megbízható teljes fizikai kapcsolat |
| Fizikailag teljes, egyértelmű port–port kapcsolat, de nem egyezik aktív publicationnel | sárga | felismert, de nem publikált fizikai kapcsolat |
| Fizikailag teljes és cella/csatorna/végpont szerint pontosan egyezik az aktív published route-tal | olívazöld | publikált kapcsolat élő fizikai vetülete |

A GraphEdge megléte és a DRAFT SAVE állapota egyik sor feltételéhez sem elegendő. Külön „narancs” csak akkor maradjon, ha a projektgazda önálló hiba/unknown vizuális állapotot akar; az elfogadott háromszínű modellben az ilyen esetek ciánra esnek vissza.

### 7.2. CK-04 kompatibilitás

A jelenlegi kód `VALID` EdgeRoute + létező GraphEdge esetén zöldet vetít (`WireVisualProjection.kt:12-34`). PUBLISHED snapshot hiányában ezt nem lehet egyszerűen átértelmezni, mert egy régi zöld route-ról nem bizonyítható, hogy publikált volt.

Adatvesztés nélküli átvezetési lehetőségek:

1. **Legacy vizuális mód:** a meglévő CK-04 Stage-ek zöldje átmenetileg megmarad `LEGACY_VALID` szabállyal; az első explicit PUBLISH után a Stage végleg az új modellre vált.
2. **Tulajdonosi bootstrap:** csak explicit, auditálható művelet emeli a kiválasztott mentett VALID snapshotot publication v1-re.
3. **Azonnali szigorítás:** publication nélkül minden régi route sárga. Ez egyszerű, de látható kompatibilitási törés.

Javaslat: 1 + 2 kombinációja. Automatikus DB-migrációval ne minősítsünk régi DRAFT SAVE-et publikáltnak. A váltás előtt külön acceptance kell a négy T8 csarnokra.

## 8. A tíz kötelező eset értékelése

| # | Eset | Ma | CK-05 célviselkedés / szükséges teszt |
| --- | --- | --- | --- |
| 1 | START → több WireCell → STOP, egyenes | Tárolás és route-validáció részben igen; placement/discovery/projection nem | BFS/DFS egy komponenst és rendezett route-ot talál; bind explicit; reconnect azonos cellákat vetít |
| 2 | START → sarok → STOP | `SINGLE` domain reprezentálja; Fabric nem | Szomszédokból merőleges kapcsolatok; sarokmodell automatikus; route-validáció PASS |
| 3 | Két független keresztező út | CROSS domain/DB igen; runtime nem | `NS` és `EW` külön komponens/route, sem discovery, sem render nem hidalja át |
| 4 | Három vezeték CK Junctionon | Nincs Junction | Külön connector entitás és portpolitika; több explicit GraphEdge; nem háromágú SINGLE |
| 5 | Félbehagyott vezeték | DRAFT cella tárolható, de B3 adapter elutasítja | Discovery OPEN/INCOMPLETE; cián; SAVE engedett; bind nem hoz létre edge-et |
| 6 | Középső cella kiütése | WIRE_LAYOUT töröl; régi destrukció edge-et is törölhet | Layout változik, edge/publication érintetlen, live státusz sérült és cián |
| 7 | Sérült vezeték javítása | B3 egy cellára korlátozott | Ugyanaz a komponens újra teljes; sárga vagy publication-egyezésnél zöld; stabil edge/route ID elvárás tisztázandó |
| 8 | Több független kapcsolat egy Stage-ben | Domain bind több jelöltet tud; adapter nem | Stage-wide discovery + atomi bind, determinisztikus több candidate |
| 9 | Több START-os menthető DRAFT | SAVE/persistencia igen; wire/bind adapter nem | SAVE PASS validációs hibák mellett; publish várhatóan külön szabály alapján FAIL vagy explicit döntés |
| 10 | Python-restart + reconnect többcellás route | Snapshot tárolható; projektor csak E–W egy cella | Új epoch, azonos mentett cellák/edge/route ID-k, idempotens authoritative többcellás projekció |

Minden esethez Python unit teszt, API contract teszt, Kotlin projector/coordinator teszt és ahol értelmes, PostgreSQL integration teszt szükséges. A végső Minecraft acceptance külön kapu.

## 9. Komponensenkénti felelősség

| Réteg | Felelősség | Nem lehet felelős érte |
| --- | --- | --- |
| Python Graph domain | cella/csatorna invariánsok, discovery, diagnosztika, portillesztés, bind, publish-validáció | Minecraft blockstate/model |
| Python application/API | auth, epoch/revision fencing, parancsok, idempotencia, DTO, Stage-szintű tranzakció | vizuális optimista világállapot |
| Fabric runtime | input interception, szomszédsági tények közvetítése, authoritative projection, játékos-visszajelzés | GraphEdge létrehozási döntés, publish döntés |
| PostgreSQL | DRAFT snapshot és később immutábilis publication tartóssága, CAS/FK/invariánsok | runtime discovery végrehajtása |
| Minecraft world | kizárólag Fabric által írt megjelenítési vetület | kanonikus Graph- vagy publication-állapot |

## 10. Újrahasznosítható komponensek

- `WireCell`, `WireChannelState`, `WireStep`, `EdgeRoute`, `GraphEdge` immutable domain értékek.
- `orientation.py` port–világoldal transzformációi.
- `validation.py` port-, kardinalitás-, route- és CROSS-csatorna ellenőrzése.
- `binding.bind_edges()` atomi, többjelöltes bind magja.
- `GraphWorkspaceState` dirty/revision/validation állapota és tiszta `replace_content` átmenetei.
- `006_graph_snapshots.sql` többcellás WireCell/route-step persistenciája.
- `save_full_stage_snapshot()` CAS-os teljes DRAFT mentése.
- Fabric `GraphTransport`, idempotens command envelope, `GraphProjectionStore` lifecycle-fencing.
- `WireInteractionCoordinator` hiteles válasz előtti korrekciós/reconciliation mintája.
- T8 authoritative reconnect sorrend és destruktív stale render elleni védelem.
- `WireWorldProjector` idegen blokk elleni védelme és snapshot-alapú diff-vetítése.

## 11. Hiányzó képességek

- általános fizikai topológia-felderítő és komponensmodell;
- érintett komponens újraszámítási szerződés;
- discovery DTO/diagnosztika és sárga állapot;
- többváltozásos vagy biztonságosan sorosítható WIRE_LAYOUT szerződés;
- Stage-wide többjelöltes bind alkalmazási adapter;
- többirányú CK Wire blockstate, sarok/CROSS modellek és neighbor update;
- Junction domain, API, persistencia, Block/BlockItem és projektor;
- DRAFT route és live fizikai státusz szétválasztása;
- PUBLISH parancs, validáció, immutábilis snapshot és verzió;
- published route identity a Restore csákányhoz;
- CK-04 legacy-green átvezetési szabály;
- dirty Stage elhagyási figyelmeztetés/megerősítés;
- többcellás restart/reconnect és világ-acceptance tesztek.

## 12. Javasolt inkrementális CK-05 terv

### CK-05/A2 – Tiszta Python fizikai discovery

- Új, I/O-mentes topológia modul.
- Egyenes, sarok, CROSS, nyitott vég, ambiguitás és ciklus fixture-ök.
- Sem edge-, route-, SAVE- vagy DB-módosítás.
- Kimeneti szerződés és determinisztikus diagnosztika.

**Kapunyitás:** a kötelező esetek 1, 2, 3, 5 és a ciklusbiztonság unit teszten PASS.

### CK-05/A3 – API és Stage-wide bind általánosítása

- Discovery eredmény a Graph GET-ben vagy külön read-only DTO-ban.
- `WIRE_LAYOUT` többcellás geometriát fogad; kezdetben maradhat egy cella/parancs, ha a neighbor-recompute Pythonban atomi.
- `BIND_EDGES` a teljes Stage candidate-listáját adja a meglévő bind magnak.
- Explicit teszt: auto discovery soha nem hoz létre/töröl edge-et.

### CK-05/A4 – Fabric többcellás Wire projection

- Négyirányú kapcsolati blockstate vagy determinisztikusan származtatott modellezés.
- Egyenes/sarok/CROSS shape és assetek.
- Neighbor placement/break input, authoritative snapshot-vetítés.
- Meglévő epoch fencing és idegenblokk-védelem megtartása.

### CK-05/A5 – Több kapcsolat és DRAFT SAVE acceptance

- Több független út Stage-wide bindja.
- Több START/STOP és félkész vezeték SAVE-je.
- Sérülés/javítás edge-törlés és automatikus BROKEN nélkül.
- Python restart + Fabric reconnect többcellás snapshoton.

### CK-05/A6 – CK Junction

- Előbb ADR: GraphNode vagy külön connector, portok, belső kapcsolás és edge-kardinalitás.
- Utána additív domain/API/persistencia és Fabric blokk.
- Háromágú acceptance, crossingtól egyértelműen eltérő megjelenéssel.

### CK-05/A7 – PUBLISH és új színmodell

- Publikálhatósági szabályok lezárása.
- Additív, immutábilis publication persistencia.
- Sárga/olívazöld/cían projection.
- CK-04 legacy átvezetés külön, auditálható eljárással.

### CK-05/A8 – Route Restore csákány

- Published route pontos azonosítása.
- Dry-run ütközéslista, idegen blokk tiltás, egy-route hatókör.
- Csak fizikai layout helyreállítása; GraphEdge és publication változatlan.

### CK-05/A9 – Stage elhagyás és szerkesztési UX

- dirty figyelmeztetés/megerősítés;
- mentett DRAFT authoritative visszatöltése későbbi belépéskor;
- Ctrl+Z külön történeti modellként, nem Restore-helyettesítőként.

Minden mérföldkő után külön build/test és Minecraft acceptance szükséges. DB-migráció csak az A6/A7 elfogadott ADR-je után, számozott és additív formában készülhet.

## 13. Kockázatok

1. **Régi zöld szemantika:** a VALID EdgeRoute ma zöld, az új modellben csak published-egyezés lehet zöld. Automatikus átminősítés hamis publicationt hozna létre.
2. **Destrukció újrahasznosítása:** a jelenlegi `confirm_destruction()` edge-et/route-ot töröl; véletlen bekötése adatvesztést okozna.
3. **DRAFT és live státusz összemosása:** route státusz automatikus `BROKEN`-re írása megsértené az elfogadott változatlanságot.
4. **Elágazó route reprezentáció:** az `EdgeRoute.steps` lineáris. Junction utáni fan-out több route/edge legyen, vagy új struktúra kell; implicit route-fa nem fér el veszteség nélkül.
5. **Portkardinalitás:** minden jelenlegi port min=1/max=1. Többágú Junction és későbbi programszemantika új, verziózott katalógust igényel.
6. **Neighbor race:** több gyors Minecraft update külön revision parancsokkal stale konfliktust okozhat. A szerializáció/batch szerződést explicit meg kell tervezni.
7. **Részleges projection:** többcellás render közben idegen blokk vagy hiba részleges világképet okozhat; authoritative újrafuttatásnak idempotensnek kell lennie.
8. **Publication séma:** a mutable DRAFT táblák másolása, normalizált publication táblák vagy snapshot blob között még nincs bizonyítékalapú döntés.
9. **Route identity:** fizikai újraépítéskor azonos szemantikai edge mellett a route ID stabilitásának szabálya nincs lezárva.
10. **Teljes programvalidáció:** a jelenlegi validátor a reachability és cycle rules csoportokat deferredként jelöli; ezért önmagában nem PUBLISH-kapu.

## 14. Projektgazdai döntést igénylő kérdések

1. A CK Junction szemantikai `GraphNode`, külön fizikai connector, vagy mindkettő legyen?
2. Egy háromágú Junction milyen irányított GraphEdge-eket jelent, és mely portok hány edge-et engednek?
3. Mi a PUBLISH minimális szabályrendszere: pontosan egy START, legalább egy STOP, teljes elérhetőség, ciklustilalom, minden port bekötése?
4. A régi CK-04 VALID/zöld Stage-ek kapjanak-e átmeneti `LEGACY_VALID` megjelenést, és ki indíthat explicit publication bootstrapot?
5. Maradjon-e külön narancs „ismeretlen/repair” szín, vagy minden nem teljes/nem megbízható állapot cián legyen?
6. Egy fizikai kapcsolat újraépítése megőrizze-e a route ID-t, ha a GraphEdge végpontjai azonosak, de a cellasor megváltozott?
7. A published snapshot a teljes Stage-et vagy csak a publikált program- és route-részhalmazt rögzítse?
8. A Junction belső kapcsolata fix WAGO-szerű, konfigurálható, vagy Junction-típusonként verziózott legyen?
9. A Fabric küldjön nyers placement/break eseményt, vagy szomszédságból képzett cellaállapotot? Javaslat: nyers tény + Python által számított kanonikus topológia.
10. Dirty Stage elhagyásakor a megerősítés csak eldobást, vagy SAVE/eldobás/mégse hármas választást kínáljon?

## 15. Végső állapotjelentés

### Már működik

- immutable Graph domain alapok;
- többcellás route-step tárolás és validáció;
- sarok reprezentálhatósága a Python modellben;
- független CROSS reprezentáció és DB-invariáns;
- több kapcsolat atomi bindjának tiszta domainmagja;
- teljes DRAFT snapshot CAS-os mentése PostgreSQL-be;
- epoch/revision fencing, authoritative Graph reconciliation és T8 reconnect-sorrend;
- idegen blokkot védő, idempotens snapshot-vetítési alap.

### Hiányzik

- az általános fizikai discovery;
- a többcellás Fabric geometria és kommunikáció;
- a Stage-wide bind általános alkalmazási adaptere;
- Junction teljes vertikális szelete;
- live fizikai státusz és a DRAFT route szétválasztása;
- PUBLISH/published snapshot és az új színmodell;
- route Restore és dirty-leave UX.

### Ajánlott sorrend

**Python discovery → API/Stage bind → Fabric többcellás projection → többkapcsolatos DRAFT acceptance → Junction → PUBLISH/színek → Restore → Stage-leave UX.**

Az implementáció nem kezdhető meg automatikusan. A legelső szükséges projektgazdai döntés a Junction szemantikai szerepe és a PUBLISH minimális szabályrendszere; a CK-04 zöld kompatibilitási politikát legkésőbb a PUBLISH tervezése előtt le kell zárni.
