# CK-08/A – Fejlesztési frontok és blokkrendszer-felderítés

Dátum: **2026-11-09**, Europe/Budapest. Művelet: **STIRLITZ**.
Mód: read-only reconnaissance; az egyetlen engedélyezett változás ez a jelentés.
Nincs implementáció, tesztfuttatás, migráció, DB-/világmódosítás, deploy, restart vagy commit.

## 1. Executive summary

**20 elkülöníthető fejlesztési front** azonosítható. Ez felelősségi bontás, nem húsz egyszerre elindítandó projekt. A működő alap már nem egy „blokkokat lerakó” prototípus: Program-aggregátum, Stage-fa, teljes Graph/Wire snapshot, Program-SAVE, explicit bind, Hall-aware projekció és perzisztált Hall Selection van.

A programozási szemantika viszont jóval szűkebb:
- START/STOP és az IF CONTROL-portjai implementáltak; **IF-feltétel nincs**.
- PROGRAM strukturális, nulla portos navigációs elem; nem függvényhívás.
- FOR/FUN fizikailag regisztrált, de portszemantikájuk **UNSPECIFIED**.
- SET/PRINT/END_IF szerepel teszt-fixture-ben, de nem production blokktípus.
- Nincs production változó-, deklaráció-, értékadás-, kifejezés-, FUN-definíció-, CALL- vagy Condition Stage-modell.
- A jelenlegi blokkdialog csak feliratot és leírást szerkeszt. Ezek nem típusos programparaméterek.
- A teljes programvalidáció és végrehajtás nincs meg; egy zöld Wire nem igazolja egy IF-program szemantikai helyességét.

**Javasolt következő mérföldkő:** projektgazdai feltételreprezentáció-döntés után egyetlen IF-paraméterezési vertical slice, a meglévő három port és két külön STOP-ág felhasználásával. Minecraftban új képesség: a feltétel szerkeszthető, szerveroldalon ellenőrzött és SAVE/LOAD után változatlanul visszanyitható. Nem kell hozzá IR, generátor, CALL, automatikus Hall-forgatás vagy általános plugin-framework. Condition Stage választásakor ez nagyobb, külön előfeltételes szelet; a jelentés nem választ helyette szöveges kifejezést.

**Értékelés:** GO a következő szerződés/szelet kiválasztására; CONDITIONAL GO IF-implementációra a nyitott döntések lezárása után. NO-GO „teljes programozási nyelv kész”, N28 lezárás vagy automatikus implementáció állítására.

## 2. Baseline, módszer és bizonyítékhatárok

### 2.1 Repository
- Gyökér: C:/Users/tamas/IdeaProjects/bloCKommanderFabric.
- Branch: **main**.
- HEAD: **b98b995eff3a0702a0b5602e54695598e6ccfed1**.
- Commit: **feat(CK-07): add hall selection projection and graph diagnostics**.
- A kért b98b995 baseline elérhető, HEAD-del azonos; azóta commit **nincs**.
- Kezdő munkafa: egy meglévő módosítás, **logs/latest.log**. Nem production bizonyítékként használtam.
- Kezdő SHA-256: **f52304aa6fde27315eb42eab984f20ba5bb6870cc8afb516f104c653937afb2f**. Megőrzendő.
- A .tools alatti korábbi G1/DIAG3 worktree nem a vizsgált baseline; eredményeit nem keverem a b98b995 képességeivel.

Statikus leltár a jelentés létrehozása előtt: 32 main Kotlin, 2 client Kotlin, 22 Kotlin tesztfájl; 27 Python production modul, 33 Python unit tesztmodul, 10 PostgreSQL-integrációs suite, 10 migráció és 39 docs-dokumentum. A koncepció, README, build/deployment és erőforrások külön is vizsgálva.

### 2.2 Bizonyítékszintek
- **DECISION:** elfogadott szerződés; önmagában nem implementáció.
- **IMPLEMENTED:** konkrét production adatút létezik.
- **TESTED:** tesztforrás és/vagy korábbi futási jegyzőkönyv; nem most futtatott teszt.
- **MC-ACCEPTED:** játékbeli acceptance, megnevezett forrásból.
- **PLAN:** ötlet/javaslat, nem kész funkció.
- **SUPERSEDED:** korábbi, későbbi szerződéssel/implementációval felülírt állapot.
- **UNKNOWN:** nem igazolható a rendelkezésre álló bizonyítékból.

Most **nem futott** Gradle/Python/PostgreSQL suite. A meglévő JUnit XML-ben 201 teszt, 0 failure/error/skip szerepel; az első suite-időbélyegek 2026-11-08T18:16Z körüliek. A [DIAG2-jelentés](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/CK-07A1.3-DIAG2-graph-save-instrumentation.md) 201 Kotlin és 509 Python PASS-t rögzít. Statikus AST-számlálás: 509 Python unit tesztmetódus; ez nem friss PASS-eredmény. A [B1-jegyzőkönyv](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/CK-07A1.3-G3-B1-hall-selection.md) 56 valódi PostgreSQL-teszt korábbi sikerét rögzíti.

### 2.3 Jelenlegi acceptance-forrás

A CK-08/A megbízás **új projektgazdai bizonyíték**:
- Hall Selection T1–T8 PASS.
- T9 fizikai acceptance PASS: **NewHope ROOT + Hófehérke CHILD**, közös Program.
- A selection revision/WorkingCopy UUID-k SQL-ellenőrzése még hátravan.
- A két Hall bejárati orientációja 90°-kal különbözik; automatikus Stage-forgatás nincs.

Ez felülírja a B2-dokumentum történeti „native acceptance not run” státuszát, de nem ad meg nem történt SQL-ellenőrzést. **Hófehérke nem WhiteHouse:** a korábbi WhiteHouse-mérethiány miatti NO-GO másik cél-Hallra vonatkozott, nem cáfolja az új T9-et. Az új T9 nyers log-/SQL-artefaktumai e műszakban nem vizsgáltak: UNKNOWN. Production rendszerhez most nem kapcsolódtam.

## 3. Teljes fejlesztésifront-leltár

Rövidítések: K = src/main/kotlin/me/buciqua/blockommander; P = python-service/src/hangar; KT = src/test/kotlin/me/buciqua/blockommander; PT = python-service/tests. Ezek a repository alatti útvonalak.

A „teszt” oszlop lefedettségi forrás, nem új futás. A nyitott hibák B-azonosítói a 14. szakaszban találhatók.

| Front; cél | Jelenlegi implementáció / döntési forrás | Teszt és utolsó ismert acceptance | Hiány / hiba | Függőség → következő mérföldkő |
|---|---|---|---|---|
| **F01 Hall-infrastruktúra**: fizikai szerkesztőhely | P/service.py, builder.py, repository.py; K/runtime/WorldRenderer.kt, Interactions.kt; migráció001–003; README, FUNCTION-INVENTORY | PT/test_hall_service.py, test_webhook.py; régi MC-használat és új selection acceptance előfeltétele | Geometriai korlátok, nincs automatikus átméretezés; B06 | F18/F05 → dokumentált fit-precheck és Hall-fixture |
| **F02 Program/Stage-fa**: ROOT és PROGRAM_BODY | P/program.py, program_http.py; migráció007/008; StageNavigation.kt; CK-06/A1-R | test_program_service.py, test_program_block_domain.py; StageNavigationCoordinatorTest, ProgramBlockIntegrationTest; ROOT/CHILD/GCHILD navigáció és T1–T8 projektgazdai PASS | Condition/FUN Stage-kind nincs; retained tartalomhoz nincs teljes UX | F04/F05 → egy új Stage-kind csak szemantikai szerződés után |
| **F03 WorkingCopy/transport identity** | P/working_copy.py, transport_context.py; K/runtime/TransportContext.kt, ProjectionKey.kt; migráció009; CK-07/A1.2, G1 | test_working_copy_context.py, test_transport_context.py; TransportContextTest; PG working_copy/transport_context suite | Tartalmi izoláció nincs, SHARED_PROGRAM él | F02/F18 → T9 WC/revision SQL-bizonyíték; ne implementáljunk implicit forkot |
| **F04 Perzisztencia/Program SAVE** | StageSnapshot, ProgramService.save, repository.save_program_snapshot; migráció004/006/007; CK-03/B3, CK-06/A1-R | test_stage_graph_persistence_service.py, test_program_service.py; PG graph/program_save/stage_service; történeti T8 ACCEPTED, új GCHILD SAVE megfigyelés | B02 ROOT SAVE státuszhiba OPEN; kétfázisú DB/workspace commit | F02/F03/F06 → valódi ROOT SAVE regresszió külön körben |
| **F05 Lifecycle és authoritative projekció** | HallRegistry, GraphProjectionStore, StageReconnectReconciler, ProjectionCompletions, StageSwitchProjection; G2/R1 | LifecycleHardeningTest, HallProjectionIsolationTest, StageSwitchProjectionTest, StageReconnectReconcilerTest; történeti T8 és új T1–T8 | B01 N28 OPEN; fizikailag látható tartalom nem frissességbizonyíték | F03/F04 → mismatch/restart első-művelet célzott regresszió |
| **F06 Graph/port/validáció** | P/graph/model.py, port_catalog.py, validation.py, snapshot.py; CK-01/B, CK-03/B1 | test_graph_model.py, test_port_catalog.py, test_graph_validation.py, test_stage_snapshot.py | Csak CONTROL; teljes strukturális validáció hiányzik; B04 | F09/F04 → IF-ág/merge-szerződés és határolt szabályréteg |
| **F07 Wire topológia/discovery/bind** | P/graph/discovery.py, binding.py, placement.py; graph_application.py; CK-05/A2/A3/FIX1 | test_graph_discovery.py, test_graph_binding.py, test_graph_placement_intent.py, test_graph_application_discovery.py; N26 bind/reroute felhasználói PASS | Junction nincs; többport ugyanazon cellán korlátozott; explicit edge-törlés UI nincs | F06/F08 → két IF-kimenet külön route-jának acceptance-e |
| **F08 Fizikai Wire és színezés** | CkWireBlock, WireWorldProjection, WireVisualProjection, WireInteractionCoordinator; CK-04/B4A, CK-05/FIX2 | CkWireAssetsTest, WireWorldProjectorTest, WireInteractionCoordinatorTest, WireVisualProjectionTest; többcellás játékbeli használat, N28 új builden nem reprodukált | B01; többcsatornás színezés jelentése nem végrehajtási igazságérték | F05/F07 → IF 0/1 ágak, négy orientáció, reconnect |
| **F09 BlockType/registry/assets/inventory** | ModContent, CkProgramBlock, model/blockstates/items JSON; migráció004/005/008 | ProgramBlockIntegrationTest, CkProgramPortOrientationTest; fizikai blokkhasználat ismert, minden típus külön acceptance-e UNKNOWN | B03 FUN-modell hiányzik; nincs dinamikus típusregiszter vagy inventory-konfiguráció | F06/F10 → teljes assetkonzisztencia és egy IF-felülnézeti jelölés |
| **F10 Blokkparaméter/UI** | CkFormScreen, FormPayloads, Networking, Interactions.submitForm, StageService.update | test_stage_service.py, test_stage_snapshot.py; általános label/description út; natív képernyőteszt-harness nincs | Típusos szemantikai paraméter nincs; csak két szövegmező | F04/F05/F09 → egyetlen típus szervervalidált paraméter-round-tripje |
| **F11 IF / Condition / merge** | IF három port, 0/1 oldalsó marker; koncepció END_IF terve | test_port_catalog.py, test_graph_validation.py, test_graph_orientation.py; teljes feltételes MC-program acceptance UNKNOWN | Feltétel, Condition Stage, END_IF, ágproveniencia hiányzik; B04 | F06/F07/F10; változó csak választott feltételforma esetén → első paraméterezett IF |
| **F12 FUN/CALL** | FUN placeholder; PROGRAM Stage-hierarchia mint újrahasználható navigációs alap | test_port_catalog.py az UNSPECIFIED állapotot rögzíti; PROGRAM-navigáció tesztelt, FUN/CALL szemantika nem | B03; definíció/hívás/szignatúra/return/binding nincs | F02/F06/F10/F13 → elfogadott FUN vs CALL szerződés, utána modal |
| **F13 Változó/scope/típus** | Production modell nincs; koncepció jövőbeli változókat/scope-ot említ | Dedikált production teszt/MC acceptance nincs | Identitás, deklaráció, névfeloldás, típus, élettartam nyitott; lexikális döntés UNKNOWN | F02/F04/F10 → minimális, célfüggetlen scope/variable szerződés |
| **F14 Értékadás/kifejezések** | SET csak graph teszt-fixture; nincs production assignment/expression | A generikus SET-portfixture nem assignmentteszt | lhs/rhs, hivatkozás, típusellenőrzés, szerkesztő, mentés hiányzik | F13/F10/F06/F04 → egy változóhoz kötött literal-értékadás pilot, külön döntés után |
| **F15 Több Hall/selection/monitor/orientáció** | HallSelectionLifecycle/Coordinator/Api, HallRegistry, hall_projection.py; migráció010; B1/B2 | HallSelectionApiTest/CoordinatorTest, test_hall_selection.py, PG hall_selection; T1–T8 és Hófehérke T9 fizikai PASS | B05 SQL-bizonyíték és monitorrefresh nyitott; B06 nincs Hall-forgatás | F03/F05/F04 → T9 identity/revision ellenőrzés, külön B3 refresh-szerződés |
| **F16 Diagnosztika/runtime acceptance** | Diagnostics.kt, P/diagnostics.py; DIAG2 trace UUID-k; régi kézi runbookok | DiagnosticsTest, test_diagnostics.py; Fabric-logból sikeres N28-parancslánc | B07 tartós WH-log nincs igazolva; nincs natív automatizált MC-harness | F05/F18 → két PID-n át tartós WH-log, acceptance-artefaktumok |
| **F17 IR/export/generálás** | Csak koncepció és régi jövőbeli itiner; nincs itt működő exporter | Sem production round-trip, sem MC/export acceptance | Nyelvi szemantika/validáció még nincs; nem kezdendő | F06/F11–14 → később külön döntés; most csak függőségleltár |
| **F18 Üzemeltetés/build/biztonság** | build.gradle, deployment, AGENTS, OPERATIONS; owner/shared-secret/context, Java25, main/client split | Gradle/JUnit és guarded PG infrastructure; korábbi deploy megfigyelés | DB-világ nem elosztott tranzakció; egyfolyamatos workspace; publikus együttműködési auth nincs | keresztmetszeti → megbízható log/backup és reprodukálható release-manifest |
| **F19 Magasabb modell/annotáció/UI-modellezés** | koncepció ModelNode/ModelRelation/Class/Screen/Annotation terve; CKParser csak külső történeti szolgáltatás | Ebben a repóban implementáció/teszt nincs; CKParser jelen állapota UNKNOWN | Nem azonos a PROGRAM-val; nincs Class/Method/Screen domain | F02/F13/F17 → későbbi önálló szerződés, nem következő blokkszelet |
| **F20 Layout eszközök**: REFORMAT/COPY/MOVE/ConnectionDestroyer UX | Domain destruction.py preview/confirm kész; koncepció layout-eszközök | test_graph_destruction.py/test_graph_lifecycle.py; játékbeli destroyer acceptance nincs | Nincs megerősítő MC-eszköz, copy/move/reformat motor; nem Hall DESTROY | F06/F07/F05 → külön játékbeli preview/confirm, ha prioritást kap |

## 4. Élő döntések, dokumentumok és ellentmondások

### 4.1 Továbbra is irányadó
1. Fabric a kizárólagos Minecraft-világíró; client nem éri el a DB-t/Pythont. Python alapértelmezett fabric driver mcpi nélkül. Forrás: [AGENTS](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/AGENTS.md), [README](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/README.md), [CK-01/B](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/CK-01B-mini-ADR.md).
2. PostgreSQL a **mentett** állapot forrása; dirty Stage/Program tartalom Python-memóriában. Nem helyes a „minden pillanatban kizárólag DB az aktuális szerkesztett modell” egyszerűsítés.
3. GraphNode azonosság = instance_id; portazonosság = instance_id + port_key; GraphEdge és fizikai route külön. Normál Wire break nem töröl élt; SAVE nem bind.
4. DRAFT/BROKEN menthető; mentés nem teljes programvalidáció, nem végrehajtás és nem PUBLISH.
5. Program a SAVE-aggregátum, Stage relatív domain, Hall projekció. Navigáció nem ment. ProgramBlock aktív/retained Stage-linket jelent, nem CALL-t.
6. Shared Program workspace; WorkingCopy identitás és fencing, nem fork. Hall Selection desired state és sikeres physical projection külön állapot.
7. Kliensoldali Screen a src/client alatt; dedikált szerver nem importál client osztályokat.
8. Nincs új döntés Hall-orientáció automatikus transzformációjáról vagy feltételreprezentációról.

### 4.2 Időrendi eltérésjegyzék

| Korábbi dokumentum/állítás | Későbbi állapot és besorolás |
|---|---|
| [koncepcio.md](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/koncepcio.md), 2026-10-22: SET/VAR/CALL/END IF, expression nézetek, több exportcél | **PLAN**, a fájl maga sem implementációs specifikáció. A production katalógus nem tartalmazza ezeket. |
| CK-01/B 2026-10-22: IF/END_IF strukturált demó és későbbi SET/PRINT katalógus | Az alap Graph/port/route döntés DECISION; a teljes demó nincs implementálva. A CK-02/03/… régi műszaksorszámok nem azonosak automatikusan a mostani CK-08/A feladattal. |
| [CK-02/F](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/CK-02F-integracios-kapu.md), 2026-10-22: nincs runtime adapter/Graph DB | Történetileg igaz; CK-03/B1/B2-T/B3 és jelen production kód felülírja. Nem aktuális BLOCKED. |
| [CK-03/B2](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/CK-03B2-postgresql-graph-perzisztencia.md), 2026-10-23: valódi PostgreSQL-kapu BLOCKED | [B2-T](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/CK-03B2T-postgresql-integracios-kapu.md) ugyanazon napi későbbi kapu és [B3](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/CK-03B3-stageservice-save-rebuild.md) feloldja. |
| [CK-04/T8](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/CK-04-T8-acceptance.md): authoritative reconnect ACCEPTED | Tényleges korábbi MC acceptance, de szűkebb, korábbi modellre. Nem bizonyítja az összes későbbi hierarchikus N28 esetet. |
| [CK-05/A4/1](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/CK-05A4-1-fabric-multicell-wire.md): automatic placement BLOCKED | [FIX1](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/CK-05A4-1-FIX1-python-placement-intent.md) és [FIX2](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/CK-05A4-1-FIX2-fabric-placement-intent.md) vezeti be a placement-intent utat. A régi BLOCKED nem aktuális. |
| [CK-06/A](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/CK-06A-stage-navigation-reconnaissance.md), 2026-10-30: Hallhoz kötött Stage, parent SAVE előfeltétel | [A1-R](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/CK-06A1R-program-stage-hall-architecture.md) projektgazdai korrekció: Hall-független Program, egyetlen aggregate SAVE. |
| A1-R: első körben restart után ROOT fallback | G3/B1-B2: perzisztált active_stage_id selection; legacy navigáció ettől különbözik. A régi fallback nem írhatja felül automatikusan a selectiont. |
| B2-jelentés 2026-11-08: native acceptance not run, HEAD e739770, változások uncommitted | Történeti szeletjelentés. Jelen HEAD b98b995, a megbízás szerint T1–T8 és T9 fizikai PASS. SQL-identitásbizonyíték továbbra is nyitott. |
| DIAG2: futó G1/009, teljes bundle NO-GO | Akkori telepítési állapot. Később b98b995/B1+B2 fejlesztői build futott; ez nem G1-only instrumentációs kontroll. Most nincs új runtime-preflight. |
| „FUN jelenleg navigációs belépési pont” a feladat feltevésében | **Nem igazolható a kódból.** Interactions csak PROGRAM típusra hív enterProgramBlockot; FUN az általános label/description formot nyitja. Nincs néma FUN=PROGRAM szerződésváltás. |
| Régi FUNCTION-INVENTORY/README: öt programelem, minden blokk azonos szerkesztő | Történeti belépőleírás; most hat típus, PROGRAM speciális jobb kattintással. A jelen kód az implementáció bizonyítéka. |

### 4.3 Dokumentumtérkép
- **Elfogadott döntés + részben/egészben implementáció:** CK-01B, CK-06A1R; későbbi program/identity/selection szerződésekkel együtt olvasandók.
- **Történeti tervek/felderítések:** CK-02-itiner, CK-03A, CK-03B0, CK-04A, CK-05A1, CK-05A4-2-T0, CK-06A. Egyikből sem következik automatikusan feature-PASS.
- **Implementációs és tesztjegyzőkönyvek:** CK-03B1/B2/B2T/B3; CK-04B1/B2/B3/B4A; CK-05A2/A3/A4-1/FIX1/FIX2; CK-06A4; CK-07A1.2, A1.3, G2, G2-R1, G3-B1/B2, DIAG2.
- **Konkrét Minecraft acceptance:** CK-04-T8; CK-04B4A runtime-megfigyelések. A legújabb selection/N28 státusz forrása ezen felül a jelen megbízás és korábbi chatbeli futáselemzés.
- **Runbook/belépő/történeti állapot:** README, FUNCTION-INVENTORY, MANUAL-TEST, OPERATIONS, VERIFICATION (2026-10-20), START-HERE-next-chat (2026-10-24), START-HERE-next-chat-2026-11-02, „001 - CK-04 dobbantó”.
- **Koncepció:** koncepcio.md; nem helyettesíti a production szerződéseket.

A 39 dokumentum közül egyik sem jelent jelen műszakban újra lefuttatott tesztet. „Elfogadott” és „implementált” külön tengely.

## 5. BlockType-szerződésmátrix

Közös regisztráció: [ModContent](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/main/kotlin/me/buciqua/blockommander/ModContent.kt), [CkProgramBlock](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/main/kotlin/me/buciqua/blockommander/CkProgramBlock.kt).
Közös domain: [StageBlockSnapshot](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/graph/snapshot.py), [StageService.place/update](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/stage.py).
Közös portforrás: [ck-control-v3](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/graph/port_catalog.py).

| Típus | Kotlin + DB/domain | Szemantikai paraméter | Production port / GraphEdge / Wire | Megjelenítés és interakció | SAVE/rebuild, bizonyíték |
|---|---|---|---|---|---|
| START | regisztrált; 004 DB-típus; stabil instance ID | nincs; label/description van | out: OUT/FRONT; 1 kötelező CONTROL él; szomszédos cella | lime terracotta, start_port marker; általános form | közös blokk/Graph round-trip; test_port_catalog, stage snapshot/service, orientation teszt; START–Wire–STOP MC ismert |
| STOP | regisztrált; 004 | nincs | in: IN/BACK; 1 kötelező CONTROL él | red terracotta, stop_port; általános form | ugyanaz; két STOP két külön IF-ág végpontja lehet |
| IF | regisztrált; 005 | **condition nincs**; label nem feltétel | in IN/FRONT; true OUT/RIGHT, branch_label 1; false OUT/LEFT, branch_label 0. Mind 1..1 él. Három független terminál. | yellow terracotta + multipart IN/0/1 oldalsó modellek; általános form, nincs Condition-enter | geometria/port/snapshot tesztelt; teljes condition/IF MC acceptance UNKNOWN |
| FOR | regisztrált; 005 | nincs ciklusváltozó, határ vagy lépés | UNSPECIFIED; nincs kitalált body/exit/back port | cyan terracotta; általános form | blokkadat menthető; teljes Graph-használhatóság nincs |
| FUN | regisztrált; 005 | nincs név/szignatúra/return modell; label csak felirat | UNSPECIFIED; nem CALL | blockstate van, hivatkozott block/fun modell **hiányzik**; általános form; nincs Stage-enter | label/description közös round-trip; saját programozási acceptance nincs |
| PROGRAM | regisztrált; 008; parent-instance/child Stage-link a Programban | strukturális link, nem tetszőleges paraméterlista | SPECIFIED, **0 port**, nem GraphEdge-végpont | blue terracotta; jobb kattintás ensure_child + Stage-váltás, /ckback vissza | új child/link csak Program-SAVE-val perzisztál; retained részfa; dedikált Python/PG/Kotlin tesztek, navigációs MC PASS |
| CK_WIRE | külön Block/BlockItem, nem program GraphNode | geometry/visual blockstate; nem expression-adat | SINGLE, két független CROSS-csatorna; nincs junction | alacsony fizikai forma, AUTO normál placement, Shift+CROSS, színezés authoritative állapotból | WireCell/channel/route snapshot; unit/PG/projector tesztek, break/reroute MC megfigyelés |
| SET/PRINT/END_IF/CALL/VAR és továbbiak | **nincsenek production regisztrálva** | nincs | SET/PRINT/END_IF csak PT/graph_validation_fixtures.py katalógusában | nincs runtime item/model/UI | a fixture nem production vagy DB-semantic round-trip bizonyíték |

A hat programelem és CK Wire/Hangár Kard egyetlen CK creative fülön van. A registry és a fül Kotlin-kódban rögzített; jelenleg nem adatvezérelt. A DB display_definition mező sem runtime típus/plugin-regiszter.

**Fizikai létezés ≠ programozási használhatóság.** A [GraphWorkspaceAdapter](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/graph/adapter.py) UNSPECIFIED típusra compatibility issue-t ad; FOR/FUN jelenléte a Stage Graph-mutatálhatóságát is akadályozhatja. Nem elegendő egy modellt és DB-típust felvenni.

A label/description a snapshot része, a típus display_definition metaadata is átvihető, de nincs példányonkénti, típusos szemantikai payload. A WorldRenderer a típust fix Kotlin mappinggel helyezi el; a DB display_definition nem generál dinamikus feliratot a blokk oldalára.

## 6. Graph, port és CK Wire bővíthetősége

### 6.1 Ami már elég az IF két kimenetéhez
- PortDefinition listája tetszőleges számú szemantikus kulcsot tud reprezentálni.
- PortRef és GraphEdge végpont stabil, koordinátától független; a DB portkulcsokat tárol.
- PortDirection, LocalSide, ordinal, min/max cardinality, branch_label külön mezők.
- A jelenlegi v3 katalógus az IF-et már explicit háromportosként ismeri.
- Discovery fizikai komponensből port–port jelölteket képez; bind ezekből atomi és idempotens logikai éleket készít.
- Wire break/reroute nem hoz létre/töröl élt. Új útvonal explicit ckbind után kötődik.
- Nincs szükség junctionra ahhoz, hogy az IF true és false portjáról **két külön** vezeték fusson két STOP-ra.

### 6.2 Valós korlátok
- PortKind/EdgeKind production értéke csak CONTROL, nincs DATA-port/adatfolyam.
- Fizikai portcella = blokk közvetlen vízszintes szomszédja. Az ordinal nem ad külön fizikai cellát; két port ugyanazon oldalon nem lesz automatikusan külön beköthető.
- SINGLE legfeljebb két irány; CROSS NS/EW független. Háromágú csomópont nem engedélyezett.
- A fizikai discovery CYCLIC_COMPONENT felismerése nem teljes szemantikus CFG-ciklusvalidáció.
- A generic validate_graph katalógust, referenciákat, irány/kind, duplikált élt, kardinalitást és route-konzisztenciát vizsgál. A DEFERRED_RULE_GROUPS explicit: IF/END_IF branch provenance, reachability, cycle detection.
- A teljes program ProgramValidationStatus külön jelzés. Sem generikus hibamentes report, sem zöld Wire, sem SAVE nem nevezhető automatikusan „futtatható programnak”.
- Egy STOP bemenete 1..1: **két IF-ág nem vezethető egyszerűen ugyanarra a STOP-portra**. Strukturált összevezetéshez külön elfogadott merge-szerződés kell.
- Domain destruction.py preview/confirm létezik, de játékbeli ConnectionDestroyer workflow nincs.

### 6.3 Következő bővítés ellenőrzési pontjai
Új típusnál a registry/DB-katalógus/portkatalógus/snapshot/DTO/projektor/asset/test láncot együtt kell ellenőrizni. Portkatalógus-változásnál a verziókompatibilitás nem kerülhető meg. A [008 migráció](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/migrations/008_program_block_catalog.sql) jó precedens: PROGRAM=0 port és korlátozott, igazolt v2→v3 átvezetés; nem korlátlan automatikus régi-snapshot elfogadás.

## 7. IF: meglévő képesség, történeti szerződés és nyitott döntések

Az elvi IN → IF → TRUE/FALSE rajz már leképezhető a meglévő portokra. A „TRUE/FALSE jelentés” két külön dolog:
1. **Ágazonosság:** ma a port_key true/false és branch_label 1/0 rögzíti; GraphEdge a szemantikus portkulcsra hivatkozik.
2. **Feltétel és döntés:** ma nincs modell, szerkesztő vagy kiértékelés. Nem tárolható igazolt programadatként a díszítő feliratba.

| Kérdés | Igazolt válasz / nyitott rész |
|---|---|
| Több kimenet? | Igen, IF true/false külön port. Nem kell új általános többportos Graph-framework. |
| Azonosítás? | instance_id + port_key. A 90° forgatás nem cserélheti fel a true/false identitást. |
| Fizikai vezeték? | Külön komponensek a két termináltól; meglévő egyenes/sarok/CROSS elegendő az egyszerű két-STOP pilothoz. |
| Két ág ellenőrzése? | Kötelező portok/cardinality már vizsgálható; az ágak összetartozása, céljai és teljes programstrukturális helyessége még külön munka. |
| Ágak összevezetése? | Két terminális ág minimálisan megépíthető. Közös folytatáshoz a régi END_IF szerződés és ma hiányzó típus/ruleset rendezése kell. Nincs hallgatólagos junction-merge. |
| Régi END IF? | Koncepció és CK-01/B igen; PT fixture in_true/in_false/out porttal igen; production katalógus/blokk/DB-típus nincs. |
| Condition Stage? | A koncepció lehetséges expression nézetként említi; a 007 Stage-kind csak ROOT/PROGRAM_BODY. Nincs condition-link vagy belépési flow. |
| Minimális editor? | A választott reprezentációnak megfelelő input; Mégse, szervervalidáció, kontextus/epoch/revision védelem, dirty és round-trip. A szabad szövegmező önmagában nem kifejezésmodell. |
| Felülnézet? | A már rögzített IN/0/1 irányok top-face-en jelölhetők; asset-jelölés nem helyettesít condition-értelmet. |

### Nem eldöntött reprezentációk
- **Szöveges kifejezés:** gyorsabb szerkesztő, de nyelvfüggetlen grammatika, névfeloldás, típus és hibahely kell. Nyers Python/Java eval nem elfogadott rövidítés.
- **Típusos mezős feltétel:** korlátozott literal/összehasonlítási pilot lehetséges, de elfogadott operandus/típus/scope-szerződés még kell.
- **Condition Stage:** térbeli szerkesztési élmény, de új Stage-kind, link/lifecycle, ki-/bemeneti jelentés, navigáció és aggregate SAVE szükséges.

A három út közül most **egyik sincs kiválasztva**. A Condition Stage nem kötelező előfeltétel minden IF-hez. A következő implementációs promptnak kell kimondania, melyik az elfogadott út. A teljes END_IF/reachability/cycle szemantika külön döntési pont; nem rejtjük a GUI-fejlesztésbe.

## 8. Értékadás: jelen állapot és minimális szükséges szerződés

Production Assignment, VariableRef, Expression, Type vagy Scope objektum nincs. A SET-fixture csak egy IN/BACK és OUT/FRONT portú GraphNode; nincs x=5 jelentése.

Az értékadás következő szükséges elemei – **nem IR-terv és nem implementáció**:
- **Bal oldal:** mely stabil változóra történik írás; név helyett/ mellett tisztázott identitás és scope. A felirat átnevezése nem módosíthat véletlenül referenciát.
- **Jobb oldal:** érték/kifejezés külön a célváltozótól; literal vagy későbbi expression forma projektgazdai döntés.
- **Típus:** létezik-e a cél, írható-e, illeszkedik-e az érték; hibánál nincs félbemaradt workspace-változás.
- **Port:** az 1 IN/1 OUT természetes korábbi terv/fixture, de production SET-portszemantika még nincs elfogadva/implementálva.
- **UI:** külön célváltozó-választás és értékmező; hibás hivatkozás/típus visszajelzése. A jelen két általános szövegmező nem tudja bizonyítani ezt.
- **Tartósság:** példányparaméter a snapshotban, DTO-ban és repository round-tripben; Program-SAVE atomikusan menti. Nem display_definition-be rejtett szemantika és nem labelből újraparszolás.
- **Scope-élettartam:** a deklaráció tartós modelladat; a majdani végrehajtási érték élettartama ettől külön kérdés. Most nincs végrehajtómotor.

Első értékadási pilot csak szűk szerződéssel értelmes, például egy már deklarált változó és egy típushelyes literal. Ez lehetséges szeletjavaslat, nem kötelező integer/Python/Java szintaxis választása.

## 9. FUN, CALL és a modal

### 9.1 Ami ma ténylegesen történik
[Interactions.register/submitForm](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt):
- PROGRAM jobb kattintás → enterProgramBlock → mounted Program → ensure_child → Stage/Graph → fizikai commit.
- FUN jobb kattintás → általános blokk form: label/description.
- A cél gyermek-Stage-t ProgramService.ensure_child és a ProgramStageLink azonosítja a parent Stage + PROGRAM instance alapján. Nem függvénynév szerinti keresés.
- Nincs FUN-definíció/CALL-hivatkozás szétválasztás, paraméterbinding, visszatérési érték, hívási gráf, rekurzió vagy külön FUN Stage.
- A PROGRAM-link strukturális tartalmazás. Egy majdani CALL hivatkozása nem vezethető le automatikusan a parent-child fából.

### 9.2 Hogyan illeszthető a kért modal?
Meglévő alap: [CkFormScreen](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/client/kotlin/me/buciqua/blockommander/client/screen/CkFormScreen.kt) valódi kliensoldali Screen; a játékos a szerver S2C payloadjára nyitja meg, C2S submit után a szerver intézi a módosítást. Ebből következően **lehetséges** külön FUN név/Paraméterezés/Kifejtés/Mégse Screen. Most nincs ilyen modal.

A jelen OpenFormPayload/SubmitFormPayload csak kind/contextId/value1/value2 karakterláncokat hordoz. Egy típusos paraméterlista és stale-biztos FUN/CALL választás nem attól lesz kész, hogy új gombszöveget kap a régi Screen. Egyetlen konkrét FUN-szerződésre szabott payload/editor kell; általános dinamikus űrlap-framework nem előfeltétel.

- **Kliens szükséges** a modális gombokhoz, mezőkhöz, választóhoz és hibamegjelenítéshez.
- **Szerver feladata** owner/context/jogosultság, célfeloldás, validáció, workspace-módosítás és navigáció.
- Egy egyszerű belépési parancshoz/interakcióhoz elég a szerver; a kért modal nem szerver-only.
- Név, paraméterlista, return-típus és CALL target/binding tárolására ma nincs production mező. Ezek előbb szerződéses döntések, utána snapshot/perzisztencia.
- FUN és CALL Wire-portja külön döntendő: a definíció strukturális nulla-portos elem-e, a CALL 1 IN/1 OUT-e, van-e DATA eredmény? Ezeket a jelen UNSPECIFIED FUN nem dönti el.

## 10. Változók és scope: három megjelenítés, külön domain

A repositoryban a „lexikális scope” részletes elfogadott döntése **UNKNOWN**. A koncepció névfeloldást/scope-ot későbbi előfeltételként nevezi meg; ProgramBlock/Stage-hierarchia implementált, de az nem hoz létre automatikusan lexikális változóscope-ot.

| Megoldás | Játékbeli élmény | Előny / kockázat | Domain-kapcsolat |
|---|---|---|---|
| **A. Fali változótábla** | Stage/scope összes deklarációja együtt, editorral | Jól áttekinthető; falméret, lapozás, távolság és több Hall stale megjelenítése kezelendő | Nem maga a DB-azonosság; ugyanaz a változó több panelen látszhat |
| **B. Cetli/mágnes** | Egy változó külön kiválasztható jelölő | Könnyebb referencia-választás; fizikai zsúfoltság és „jelölő törlése = deklaráció törlése?” kérdés | A megjelenítő példány identitása külön a variable ID-tól |
| **C. Deklaráció első értékadáskor** | Az első hozzárendelés hozza létre a nevet | Kevés UI; elírás, duplikált név, scope/ágakban születő deklaráció és implicit típus veszélye | Kifejezett névfeloldási/hoisting/láthatósági szabály kell; nem létező policy |

A/B/C nem kizáró alternatívák: például ugyanazt a deklarációt tábla listázhatja és cetli hivatkozhatja, akár C-szabállyal létrehozva.

Külön rendezendő: stabil azonosság; deklaráció szabálya; Stage/Program/FUN scope; típus; kezdőérték opcionálissága; megjelenítés; szerkesztés/átnevezés/törlés; modell-élettartam és jövőbeli végrehajtási élettartam. Megvizsgálandó döntés, hogy a PROGRAM_BODY jelent-e lexikális blokkot, örököl-e szülőscope-ot, megengedett-e árnyékolás, és hogyan kap külön paraméterscope-ot egy FUN. **Ezek közül egyikre sem vezetünk be implicit új szerződést.**

## 11. Felülnézeti rajz és orientáció

Források: [IF blockstate](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/main/resources/assets/blockommander/blockstates/if.json), [IF alapmodell](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/main/resources/assets/blockommander/models/block/if.json), [IF portmodell](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/main/resources/assets/blockommander/models/block/if_ports.json), [Python orientáció](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/graph/orientation.py), [Kotlin orientációteszt](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/test/kotlin/me/buciqua/blockommander/CkProgramPortOrientationTest.kt).

- A blokkok JSON modellek, többnyire vanilla cube_all + terracotta textúrák. A START/STOP/IF portjelölés külön, multipart geometriai modell.
- Az IF mai IN/0/1 jelölése **oldalsó**, nem kész felső folyamatábra. Külön top-face textúra/model face technikailag beilleszthető: az up és a többi oldal külön textúrahivatkozást kaphat, vagy külön felső modellelem készülhet.
- A négy blockstate FACING variáns NORTH/EAST/SOUTH/WEST; a JSON y=0/90/180/270 forgatja a modellt és a port-overlayt. A felülnézeti rajznak ugyanahhoz a blokk-lokális FRONT/RIGHT/LEFT szerződéshez kell igazodnia.
- IF NORTH esetén IN=NORTH, true=EAST, false=WEST; EAST esetén IN=EAST, true=SOUTH, false=NORTH. A szemantikus true/false kulcs nem változik.
- Az aktuális CkProgramBlock domináns portja IF-nél FRONT; placementkor a bemenet a játékos felé néz. Nincs külön jobb kattintásos forgatóeszköz; a tárolt orientation más kérdés, mint a Hall ajtóorientációja.
- Erőforrásfájlokból a már regisztrált típus **kinézete** konfigurálható, kliens resource reload/megfelelő pack mellett. Új regisztrált típus, port, GUI vagy runtime-paraméter nem hozható létre puszta JSON-modellel.
- Nincs saját modelldatagen a build.gradle-ben. Hatékony statikus ellenőrzés: minden custom blockstate hivatkozás célmodellje létezik-e.
- **B03 új finding:** blockstates/fun.json a blockommander:block/fun-ra mutat, a models/block/fun.json nincs a forrásban és a meglévő JAR-ban sem. A statikus audit egy ilyen hiányt talált. A natív kliens tényleges hiánytextúráját most nem ellenőriztem; nem állítom, hogy screenshot bizonyítja.
- Hall-orientáció ma nem forgatja a Stage-et: [hall_projection.py](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/hall_projection.py) a relatív origóhoz Hall-minimumot ad; nincs orientációtranszformáció. Egy jövőbeli 90° Hall-forgatásnak a blokkhelyet, FACING-et, porthelyet, Wire-csatornát/kapcsolatot és a nem négyzetes Stage fitjét együtt kell kezelnie. Ez külön front, nem IF v1 kötelező előfeltétel.

Nem készült textúra, modell vagy erőforrás-módosítás.

## 12. Függőségi térkép

Jelölés: **kemény** = nélküle az adott választott funkció nem helyes; **puha** = használhatóság; **független** = nem blokkolja a kis szeletet. A feltételes élek nem minden IF/FUN-változat univerzális előfeltételei.

~~~mermaid
flowchart TD
    Param["Egy típus paraméterszerződése"] --> Round["Snapshot + SAVE/LOAD"]
    Param --> GUI["Szerkesztő és szervervalidáció"]
    Round --> IF["Paraméterezett IF"]
    GUI --> IF
    Ports["Meglévő többportos Graph"] --> IF
    Wire["Meglévő Wire + bind"] --> IF
    Orient["Blokk FACING/port kontraktus"] --> IF
    Scope["Scope/névfeloldás"] --> Var["Változómodell"]
    Var --> Assign["Értékadás"]
    Param --> Assign
    Round --> Assign
    GUI --> Assign
    Var -->|"ha változóra hivatkozik"| IF
    Condition["Condition Stage + link/lifecycle"] -->|"ha ezt választjuk"| IF
    Stage["Stage-fa + lifecycle"] --> Condition
    Stage --> Fun["FUN/CALL szerződés"]
    Scope --> Fun
    Param --> Fun
    Round --> Fun
    GUI --> Modal["FUN modal"]
    Fun --> Modal
    Top["Felülnézeti rajz"] -. "puha" .-> IF
    Orient --> Top
    HallRot["Hall-orientációs projekció"] -. "külön fejlesztési front" .-> Top
~~~

| Kapcsolat | Minősítés és indok |
|---|---|
| Paraméter → snapshot/repository/dirty/fence/UI → IF/SET/FUN-paraméter | **Kemény** a szemantikai adatok szerkesztéséhez és visszatöltéséhez. Ne csak GUI-string legyen. |
| Többportos Graph + külön fizikai terminál/Wire → IF két ág | **Kemény**, de az egyszerű IF-hez már megvan; nem kell új Graph-framework. |
| Blokkorientáció → porttal egyező rajz/Wire | **Kemény** geometriai konzisztencia; a jelenlegi transzformáció újrahasználható. |
| Felülnézeti jelölés → első IF használhatósága | **Puha**, a side markerrel technikailag működhet. |
| Modal → paraméterezés | Egy szerkesztési felület **kemény**; a konkrét háromgombos FUN modal **puha** a domainhez, és független az első IF-től. |
| Scope/típus/variable ref → értékadás | **Kemény** a hivatkozott változóra íráshoz; egy állandó feltételű IF-hez nem feltétlenül szükséges. |
| Condition Stage → IF | **Feltételes kemény**, csak a Condition Stage reprezentáció választásakor. |
| END_IF/ruleset → közös folytatás | **Kemény** strukturált merge-hez; két külön STOP-ágas IF-pilothoz nem. |
| Hall-orientáció automatikus forgatása → új blokk | **Független** a fix világirányú kis szelettől. Ha bevezetjük, minden geometriai kontraktusra keresztmetszeti hatás. |
| Teljes programvalidáció → export/futtathatósági állítás | **Kemény**; a mostani szerkesztési pilothoz elég a pontosan határolt validációs állítás. |
| Tartós WH-log, N28/ROOT SAVE regresszió, T9 SQL | **Független külön munkaszálak**, az érintett lifecycle/release elfogadásánál bizonyíték-előfeltételek. Nem feltételmodellbe rejtett feladatok. |

Közös alapok: instance-hoz kötött típusos adat; snapshot/SAVE round-trip; owner/context/epoch/revision validáció; célzott szerkesztő; katalógus/model/port konzisztencia. Ezeket egy valós pilotból kell kialakítani, nem feltételezett jövőbeli típusok frameworkjéből.

## 13. Két fejlesztési sorrend és a javasolt hibrid

Minden következő sor **javaslat, nem implementációs felhatalmazás**. S/M/L/XL relatív komplexitás; nincs időbecslés. A Condition Stage választása az IF-szeletet XL-re emelheti.

### A – Gyorsan kipróbálható Minecraft-funkciók

| Szelet | Függőség / méret | Kockázat és tesztstratégia | Minecraft acceptance |
|---|---|---|---|
| **A0 IF-portok felülnézeti olvashatósága** | meglévő v3/FACING; **S** | Nem új feltételes szemantika. JSON referenciák, négy irányú marker/port kontraktus; nem cserélhető true/false. | Új, felülről olvasható IN/0/1 rajz, három külön bekötés két STOP-pal. Ez önmagában még nem új programozási IF. |
| **A1 Egyetlen paraméterezett IF** | reprezentáció-döntés; minimális típusos adat és persistence/UI; **L** | A „gyors” út sem hagyhatja el a szervervalidációt/round-tripet. Unit invalid/no-op/dirty, HTTP stale/owner, PG rollback/round-trip, Kotlin payload/lifecycle. | Feltétel megadható, újranyitható, elutasítás nem félír; két ág bindolható; SAVE/újratöltés után adat és portazonosság egyezik. Nincs végrehajtási/generálási állítás. |
| **A2 Szűk változó + literal-értékadás** | előzetesen választott scope/típus/deklaráció; A1 tanulságai; **L** | Név és identity összekeverése; nincs implicit Python/Java eval. Reference/type és átnevezési teszt; snapshot/PG. | Célváltozó választható, érték szerkeszthető, hiba jelzett, újranyitás/SAVE után ugyanaz a kötés. |
| **A3 IF merge / END_IF pilot** | merge/terminál szerződés, új runtime típus és katalógus; **L** | Generic validáció nem elég. Két ág felcserélése, elhagyott ág, tiltott merge és stabil route teszt. | Két ág közös folytatása explicit elemben, egyértelmű hibajelzéssel. |
| **A4 FUN/CALL és modal** | definíció/hívás/param/return/scope döntések; **XL** | A PROGRAM-ot nem szabad CALL-ként átértelmezni. Külön referencia/binding/lifecycle round-trip. | Külön definíció és hívási hely, választómodal, biztos Stage-visszatérés; pontosan megnevezett nem végrehajtó képesség. |

Előny: korán látható eredmény. Hátrány: közös paraméterszerződés nélkül az A1/A2 könnyen két inkompatibilis stringes megoldássá válna.

### B – Közös alapok először

| Szelet | Függőség / méret | Kockázat és tesztstratégia | Minecraft acceptance |
|---|---|---|---|
| **B0 Nyitott szerződések lezárása** | e jelentés; **S** | Túltervezés helyett IF-reprezentáció, változó scope/deklaráció és merge hatóköre. Döntési példák, nem motor. | Dokumentációs kapu, önmagában nincs új játékbeli képesség. |
| **B1 Minimális blokkparaméter-round-trip egy valós IF-pilottal** | B0; **L** | Kerülendő a készülő általános framework. Szervervalidáció/snapshot/DTO/repository egyetlen típusra, kompatibilitás és rollback. | Egy konkrét IF-paraméter szerkeszthető és visszatölthető; ne csak tesztpayload létezzen. |
| **B2 Scope/variable és hivatkozás** | B0 + B1; **L** | Lexikális határok, shadowing és törlés tisztázatlan. Paraméteradatot nem lehet variable ID-val összekeverni. | A választott tábla/cetli/editor út egyetlen változót biztosan azonosít több nézetből is. |
| **B3 Assignment pilot** | B2 + 1 IN/1 OUT portdöntés; **M** | Típus- és referenciavédelem, SAVE/LOAD invariáns. | Az első, értelmes x=literal kapcsolat Minecraftban szerkeszthető és ellenőrzött. |
| **B4 IF condition/merge bővítés** | B1; változó esetén B2; Condition Stage esetén külön lifecycle; **L/XL** | A feltétel és branch-proveniencia külön tesztcsoport. | Feltételes modell és a választott közös folytatás round-tripje. |
| **B5 FUN-definíció/CALL/modal** | B1/B2, saját szerződés; **XL** | Referenciák, scope, argumentum-illesztés; sem navigáció, sem Wire nem hívási szemantika. | Definíció és CALL tartósan különbözik, modal és navigáció nem írja félre a célt. |

Előny: kevesebb átírás. Hátrány: B0/B2 könnyen elnyeli a következő több műszakot látható játékbeli eredmény nélkül.

### Hibrid ajánlás
1. **Rövid döntési kapu**, kizárólag az első IF feltételének reprezentációjáról és a pilot hatóköréről.
2. **B1 + A1 együtt:** egyetlen valódi IF-adattal épüljön ki a minimális paraméter–UI–SAVE lánc; az A0 jelölés opcionálisan ugyanebbe a kicsi, ellenőrizhető UX-be fér.
3. Változó/scope-szerződés és egyetlen assignment pilot.
4. Külön IF merge/ruleset vagy Condition Stage, ha a választott használat megkívánja.
5. FUN/CALL, már a konkrét paraméter- és referenciakezelési tapasztalat alapján.
6. IR/export később, nem része ennek a sorrendnek a mostani implementációs felhatalmazás szempontjából.

A N28, ROOT SAVE, WH-log, T9 SQL és Hall-orientáció külön issue/front marad; nem kerülnek automatikusan a következő IF-PR-be.

## 14. Nyitott hibák és külön munkaszálak

| ID | Státusz | Bizonyíték / határ | Következő önálló vizsgálat |
|---|---|---|---|
| **B01 N28** | **OPEN** | Kétszer WH restart utáni stale CKW-hiba; b98b995 sikeres menetben nem reprodukált. Reconnect akkor ROOT/ROOT/ROOT, friss CHILD/GCHILD Graph és sikeres HTTP 200 REMOVE volt. Ez nem a régi ok megszűnésének bizonyítéka. | Perzisztált ROOT/megjelenített GCHILD mismatch és epochváltás; régi callback/első break regresszió. Ne indítsuk most. |
| **B02 ROOT SAVE státuszhiba** | **OPEN / regresszió hiányzik** | Korábbi első ROOT SAVE után utódok NINCS MENTVE, második után clean. Új GCHILD SAVE nem azonos reprodukció. | Egyszeri valódi ROOT SAVE, receipt/státuszfence/táblák idősora. |
| **B03 FUN asset-hiány** | **ÚJ statikus finding** | FUN blockstate → hiányzó models/block/fun.json; meglévő JAR-ban is hiányzik. Native megjelenítés UNKNOWN. | Külön kicsi assetjavítás és minden custom modellreferencia ellenőrzése. Most nincs javítás. |
| **B04 Programszerkezet-validáció** | **Ismert hiány** | validation.py deferred provenance/reachability/cycle; IF condition és END_IF nincs productionben | Döntött IF/merge-szerződéshez határolt szabályréteg. |
| **B05 Hall Selection bizonyíték/refresh** | **Fizikai acceptance PASS, SQL nyitott** | T1–T8 és Hófehérke T9 projektgazdai PASS; selection revision/WC SQL még nincs. Live push nincs. | Read-only SQL-azonosság és külön B3 monitorrefresh UX/acceptance. |
| **B06 Hall-orientáció és fit** | **Fit korlát implementált; forgatás PLAN** | WhiteHouse túl kicsi a korábbi CHILD-hoz; T9 másik Hallal PASS. Stage-projekció csak eltolás, a 90° eltérő bejárat nem forgatja | Külön orientációs szerződés, nem automatikus következő javítás. |
| **B07 Tartós WH-naplózás** | **UNKNOWN / nincs igazolva** | Utolsó ismert WH stderr terminál/PTY; Python logger StreamHandler, tartós file/sink nincs a kódból garantálva | Külön operátori/loggyűjtési feladat, mindkét restart-PID megőrzésével. |
| **B08 Forrás-dokumentáció drift** | **Igazolt** | Régi README/START-HERE és szeletjelentések stale HEAD/funkciószám/acceptance állításai | Ez a jelentés egyeztet; a régi fájlokat most nem írjuk át. |
| **B09 Paraméter/scope/CALL hiány** | **PLAN, nem regressziós bug** | Production modell nincs; fixture és label nem bizonyítja | Külön elfogadott, szűk implementációs szeletek. |

A jelen műszak nem próbálja a hiányzó WH-logokat utólag kitalálni, a sikeres HTTP 200 választ elutasításnak átnevezni, vagy a T9 fizikai PASS-ból SQL-bizonyítékot következtetni.

## 15. Konkrét következő szelet-javaslat

**Munkacím: CK-08/B – Egyetlen IF feltételparaméter szerkesztése és tartós round-tripje.** Ez javasolt név, nem már elfogadott szerződés.

### Belépési döntés
A projektgazda előbb kiválasztja a feltételreprezentációt a 7. szakasz alternatívái közül, és kimondja:
- mi számít menthető, illetve hibás/hiányzó feltételnek;
- kell-e már változóhivatkozás és milyen scope-ból;
- a pilot két külön STOP-ágra korlátozódik-e, vagy END_IF/merge is része;
- a feltétel szerkesztett modelladat, és ebben a szeletben nincs-e futtatás.

Ha Condition Stage a döntés, a jelen kis szelet helyett előbb annak Stage-kind/link/szerkesztési szerződése következik. Nem cseréljük le önkényesen szövegmezőre.

### Szűk implementációs határ egy későbbi megbízáshoz
Egy IF példány paramétere → célzott editor → szerveroldali validáció → snapshot/dirty/revision → Program-SAVE/LOAD → ugyanazon példány újranyitása. Meglévő portok, explicit ckbind és két külön végpont újrahasználata. Nincs általános editor-framework, forrásgenerátor vagy változónyilvántartó „előre”.

### Ellenőrizhető kész-feltételek
1. Minecraftban az IF-hez a döntött feltétel megadható; Mégse semmit sem módosít.
2. Hibás adat szerveroldalon elutasítva, nincs részleges snapshot-/dirty-változás.
3. Érvényes valódi változás dirtyt és megfelelő revisiont eredményez; no-op külön tesztelt.
4. Ugyanazon instance újranyitva ugyanazt az adatot mutatja; a label/description külön marad.
5. SAVE/újratöltés után a paraméter és az instance/port/edge identitás megmarad.
6. IN/true/false ágak négy FACING mellett helyesek; két külön STOP-pal a meglévő bind működik.
7. Stale/Hall-váltás utáni formsubmit nem írhat másik Stage-be vagy WorkingCopy-ba.
8. A GUI és visszajelzés nem állít teljes programvalidációt, futást vagy kódgenerálást.
9. A felhasználó tényleges új képességet kap: **az IF feltételét szerkeszti és visszatölti**, nem csak új díszblokkot vagy teszt-fixture-t.

Futási kapu egy későbbi implementációban: Python domain/HTTP/snapshot unit; izolált valódi PG round-trip/rollback/migráció, ha változik a tárolás; Kotlin payload/context/lifecycle/asset kontraktus; Java25 teljes regresszió; külön engedélyezett Minecraft acceptance. Most ezek egyike sem futott.

## 16. GO / CONDITIONAL GO / NO-GO és átadás

- **GO:** a következő mérföldkő kiválasztásához a production képességek, hiányok és közös függőségek azonosítottak.
- **CONDITIONAL GO:** egyetlen paraméterezett IF vertical slice az explicit reprezentáció- és hatókördöntés után.
- **NO-GO:** FUN=PROGRAM átnevezés, fixture SET=értékadás állítás, automatikus N28-lezárás, feltétel helyett labelbe rejtett programszöveg, illetve IR/export megkezdése.
- **UNKNOWN:** a teljes lexikális scope korábban elfogadott részletes szerződése; FUN/CALL tervezett pontos portjai; az új T9 SQL identity/revision bizonyítéka; a tartós WH-log tényleges beállítása; blokktípusonkénti teljes natív acceptance.

A jelentés elkészültével megállunk projektgazdai felülvizsgálatra. Nincs automatikus CK-08/B indítás.

### Fő ellenőrizhető forráskapcsolatok

- [Program domain](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/program.py), [repository](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/repository.py), [HTTP/DTO](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/program_http.py), [endpointok](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/ck_webhook.py).
- [Graph modell](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/graph/model.py), [katalógus](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/graph/port_catalog.py), [validátor](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/graph/validation.py), [discovery](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/graph/discovery.py), [binding](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/src/hangar/graph/binding.py).
- [HallRegistry](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/main/kotlin/me/buciqua/blockommander/runtime/HallRegistry.kt), [StageNavigation](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/main/kotlin/me/buciqua/blockommander/runtime/program/StageNavigation.kt), [StageSwitchProjection](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/main/kotlin/me/buciqua/blockommander/runtime/program/StageSwitchProjection.kt), [Minecraft adapter](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/main/kotlin/me/buciqua/blockommander/runtime/program/MinecraftStageSwitchCommitter.kt).
- [Selection coordinator](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/main/kotlin/me/buciqua/blockommander/runtime/program/HallSelectionCoordinator.kt), [selection API](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/main/kotlin/me/buciqua/blockommander/runtime/program/HallSelectionApi.kt), [lifecycle](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/main/kotlin/me/buciqua/blockommander/runtime/HallSelectionLifecycle.kt).
- [Portkatalógus-tesztek](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/tests/test_port_catalog.py), [SET/END_IF fixture](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/tests/graph_validation_fixtures.py), [Graph-validációtesztek](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/tests/test_graph_validation.py), [snapshot-tesztek](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/tests/test_stage_snapshot.py), [Program-tesztek](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/tests/test_program_service.py).
- [HallSelection tesztek](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/test/kotlin/me/buciqua/blockommander/runtime/program/HallSelectionCoordinatorTest.kt), [StageNavigation tesztek](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/src/test/kotlin/me/buciqua/blockommander/runtime/program/StageNavigationCoordinatorTest.kt), [PG runner](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/python-service/tests/integration/postgres/run-tests.ps1).
- [Build/teszt/deploy feladatok](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/build.gradle), [üzemeltetés](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/OPERATIONS.md), [kézi runbook](C:/Users/tamas/IdeaProjects/bloCKommanderFabric/docs/MANUAL-TEST.md). A runbookokban szereplő mutáló parancsokat most nem futtattam.
