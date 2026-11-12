# CK-08/B3/B – Szerveroldali Block Editor session

Dátum: **2026-11-12**. Kiinduló és záró branch: **main**; HEAD: **08bde655923296fa0f54fbd34c5b099786dfd870**, `feat(CK-08): add Fabric block editor transport and typed DTOs`. A kiinduló working tree tiszta; a [B3/A transport](CK-08B3A-fabric-block-editor-transport.md) commitolva volt. A [B2 HTTP-szerződés](CK-08B2-block-editor-http-api.md) változatlan.

**Minősítés: PARTIAL.** A session/lifecycle, GET/APPLY, UNKNOWN/recovery és az authoritative Stage/Graph lekérdezési-előkészítési határ elkészült, tesztjeik PASS. A fizikai Stage/Graph/Wire újraprojekció és annak végponttól végpontig tartó ellenőrzése nincs bekötve; ez nem kap PASS minősítést. Az itiner T7 scope-korlátja alapján ezt külön integrációs feladatként adjuk át. Nincs commit vagy deploy.

## Fájlok és API

Új fájlok:

- [BlockEditorSessionCoordinator.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorSessionCoordinator.kt): állapotgép, session-generation, aszinkron callback-fence, snapshot és egyszeri reconciliation-átadás.
- [BlockEditorSessionRuntime.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorSessionRuntime.kt): hiteles ServerPlayer/Hall capture, kapcsolatgeneráció, runtime lifecycle és meglévő HTTP-kliensek bekötése.
- [BlockEditorReconciliation.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorReconciliation.kt): editor-verzióhoz ellenőrzött, írásmentes átadás a meglévő StageProjectionAssemblernek.
- [BlockEditorSessionTest.kt](../src/test/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorSessionTest.kt): 55 determinisztikus teszt.
- [BlockEditorReconciliationTest.kt](../src/test/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorReconciliationTest.kt): 9 teszt a tényleges assemblerrel.
- Ez a jelentés.

Módosított meglévő fájl: [BlockCommanderMod.kt](../src/main/kotlin/me/buciqua/blockommander/BlockCommanderMod.kt). A SERVER_STARTED létrehozza az internal runtime-ot; JOIN/DISCONNECT, END_SERVER_TICK, AFTER_PLAYER_CHANGE_LEVEL és SERVER_STOPPING lifecycle-eseményeket kap. A SERVER_STOPPED elengedi a runtime-ot. Nincs új interaction, parancs, packet vagy GUI.

A későbbi szerveroldali hívó a `BlockCommanderMod.editorSessions` példányt használhatja, kizárólag a szerver threadjén:

```kotlin
open(player: ServerPlayer, hallId: UUID, instanceId: UUID): EditorSessionId?
apply(player: ServerPlayer, id: EditorSessionId, changes: EditorChanges): Boolean
snapshot(player: ServerPlayer, id: EditorSessionId): EditorSessionSnapshot?
close(player: ServerPlayer, id: EditorSessionId)
withReconciliation(player: ServerPlayer, id: EditorSessionId,
    consumer: (PreparedStageSwitch) -> Unit): Boolean
```

A Hall/instance célpontot a jövőbeli szerveroldali interakció oldja fel; e függvények nem hálózati payload-szerződések. Az APPLY csak sessionazonosítót és a két támogatott mező változtatási szándékát kapja. A Boolean a lokális indítás/átadás eredménye, nem Python-mutációs receipt vagy világprojekciós siker.

## Állapotgép

| Állapot | Jelentés és továbblépés |
|---|---|
| OPENING | GET folyamatban; APPLY tilos. |
| EDITING | Teljes authoritative GET tárolva; permissions és mezőszintű editable alapján engedhető APPLY. |
| PENDING | Egy APPLY folyamatban; második APPLY tilos. |
| ACCEPTED | Bizonyított authoritative APPLY/no-op eredmény; új APPLY-hoz új OPEN/GET szükséges. |
| VALIDATION_ERROR | Kizárólag HTTP 422 + VALIDATION_FAILED; mezőhibák megmaradnak, javított APPLY az eredeti basisból. |
| CONFLICT | HTTP 409 vagy a megnyitó GET és a registry epochjának eltérése; nincs merge vagy CAS-frissítés. |
| UNKNOWN | A mutáció megtörténhetett; külön GET recovery, automatikus APPLY retry nélkül. |
| CLOSED | GET-hiba, egyéb terminális APPLY-hiba, explicit zárás vagy lifecycle-váltás; késői callback eldobva. |

Sikeres APPLY után a kliens szándéka helyett az `accepted.editor` teljes authoritative nézete használható. No-op ugyanígy ACCEPTED, a B3/A adapter ellenőrzi a változatlan revisiont. A session lezárása nem küld rollbacket, nem töröl workspace-t és nem vonja vissza a már elindult Python-műveletet.

## Identity és lifecycle fence

Az EditorSessionId player UUID-t, connection-generationt, játékosonként monoton session-generationt és egyedi nonce-ot tartalmaz. Minden OPEN lecseréli és lezárja az előző aktív sessiont. A generációk disconnectkor sem nullázódnak a runtime élettartamán belül; nincs globális editor epoch vagy perzisztens session.

Az EditorCapture tartalma:

- Hiteles player/world UUID, Hall/Program/WorkingCopy, Stage, instance és START/STOP típus.
- Tényleges `player.connection` objektumhoz kötött, játékosonként monoton connection-generation.
- HallRegistry Hall-generation, Graph projection lifecycle-generation, navigation-generation és Stage workspace epoch.

A B3/A `captureBlockEditorContext` változatlanul végzi az owner/world/target ellenőrzését és az eredeti opened GET schema/epoch/revision basisának megtartását. A Python ownervizsgálata továbbra is végső domainellenőrzés; a Fabric ezt nem helyettesíti.

A runtime csak a playerList aktuális ServerPlayer objektumát fogadja el. Régi disconnect nem zárhatja le az ugyanazzal az UUID-val érkezett új kapcsolat sessionjét. A játékosnak a cél Hall teljes fizikai befoglaló területén belül kell lennie; hiányzó vagy többértelmű Hall, navigation-in-progress, hibás target és blokkolt projection esetén a capture elutasított.

Minden GET/APPLY indítás, completion, snapshot-lekérés és reconciliation-átadás újracapture-t végez. Az epoch és a lifecycle-generációk egyezése kötelező; a registry újabb working revisionje nem válik új APPLY CAS-sá. A monoton navigation/Hall/Graph-generáció miatt a Stage/Hall Selection A → B → A visszatérés sem éleszti fel a régi callbacket. A sessionobjektum azonossága és teljes SessionId is véd a dupla OPEN utáni késői válaszoktól.

Disconnect, kapcsolatcsere, world/dimension-váltás és shutdown eseményből is lezár. A Hall-határ átlépését és a registry változásait a tick-poll és a műveleti kapuk észlelik. Két ilyen megfigyelés közötti, azonos világon belüli tisztán fizikai ki- és visszateleportálás történetét a polling nem rögzíti; ehhez nincs új movement hook ebben a szeletben. A registry lifecycle-generációval jelölt navigáció/projection ABA ettől függetlenül védett.

## GET, APPLY és UNKNOWN

A megnyitó GET teljes parserből származó immutable schema/value/permissions/version/target/context DTO-ja az `opened` mezőben marad. GET-hibában CLOSED és strukturált failure keletkezik. Eltérő GET epoch esetén CONFLICT, nem automatikus registry-reload.

APPLY előtt a hiteles contextet ismét a ServerPlayer és registry adja. A coordinator ellenőrzi, hogy a recapture pontosan az eredeti opened GET basisát tartotta-e meg. Csak a szerkeszthető label/description mező küldhető, Python-normalizálás és domainvalidáció megismétlése nélkül. A kétmezős patch egyetlen transporthívás.

HTTP 422 VALIDATION_FAILED után a failure teljes meződiagnosztikája megmarad; a basis változatlan. Más 422 nem kap automatikusan retry-engedélyt. HTTP 409 CONFLICT; más biztos hiba nem szerkeszthető CLOSED állapot. HTTP 5xx, bizonytalan timeout/unavailable, APPLY protokollhiba vagy exceptional/mismatched completion UNKNOWN. Biztosan el nem küldött NOT_CONFIGURED/LOCAL_REJECTED nem indít recoveryt.

UNKNOWN esetén pontosan egy külön GET indul, ha a lifecycle érvényes. Az eredmény `recovery`, hibája `recoveryFailure`, folyamatjelzője `recoveryPending`. Az eredeti `failure`, `opened` és CAS nem íródik át. Az állapot sikeres recovery mellett is UNKNOWN, `accepted` továbbra is null, és nincs újabb APPLY. Az azonos mezőérték sem bizonyítja az eredeti mutációt. Új szerkesztéshez explicit új OPEN/GET kell; sikertelen recovery nem indít végtelen retryt.

## Stage/Graph reconciliation és a fennmaradó kapu

Elfogadott APPLY/no-op és sikeres UNKNOWN recovery után a runtime a meglévő `ProgramGateway.stage` és a tiszta `GraphApiClient.getState` függvényeket hívja. A Stage/Graph kérés párhuzamos, a context mindkettőn az eredeti hiteles H/P/WC/Stage. A két válasz egyezését a meglévő StageProjectionAssembler ellenőrzi, fizikai Hall/world/origin/size ellenőrzéssel. Ezen felül a Graph epochja az editor-nézetével egyező, revisionje legalább az editorban látott revision kell legyen. Egymással versenyző, eltérő revisionű GET-pár biztonságosan FAILED; nincs automatikus újrapróbálás.

Az eredmény a szerver executorára érkezik vissza, és csak aktuális session/lifecycle mellett tárolható. A reconciliation állapot külön NONE/PENDING/AVAILABLE_NOT_PROJECTED/FAILED. Hibája nem írja át az ACCEPTED domaineredményt. Nincs READY/SYNCHRONIZED jelzés, GraphProjectionStore-írás, Wire-szinkronjelzés vagy világírás az editor eredményéből.

A `withReconciliation` egyszeri, szinkron szerver-threades fogyasztási pont: közvetlenül a consumer előtt ismét ellenőrzi a sessiont. Régi frame nem kerülhet új session consumeréhez. A consumer nem őrizheti meg későbbi, fence nélküli világíráshoz. A B3/B ezt a consumert nem köti világíróhoz.

A meglévő GraphTransportAdapter.reconcile már az editor callbackje előtt módosítaná a GraphProjectionStore-t, ezért nem használható közvetlen session-fence-ként. A StageSwitchProjection.commit navigation-tokenhez kötött és előzetesen SYNCED Graph snapshotot vár; új navigation indítása az editor saját fence-ét is érvénytelenítené. Ezek változatlanul hagyása mellett teljes fizikai újraprojekciót nem állítunk elkészültnek.

**Szűk következő feladat:** session-azonosságot megőrző, meglévő Hall/Graph/world fence-eket használó metadata-reconciliation committer és integrációs teszt. Ellenőrizze újra a frame epoch/revisionjét, az aktuális Graph/Stage-verziókat és a világ állapotát a tényleges commit előtt; a Graph store, Stage metaadat, Wire koordinátor és SAVE-tábla frissítését együtt kezelje. Régi eredmény ne írjon, projekcióhiba ne rollbackelje a Python-mutációt. Nem új Graph-domain/Python/SQL feladat, és nem editor APPLY retry.

## B3/C GUI-integrációs szerződés

| GUI-információ | Szerveroldali forrás |
|---|---|
| Authoritative schema/values | `opened`, elfogadott APPLY után `accepted.editor`; recovery külön `recovery`. |
| Szerkeszthetőség | `canApply` és az authoritative schema mezőnkénti editable flagje. |
| Folyamatjelzés | OPENING, PENDING, recoveryPending és reconciliation=PENDING. |
| Mezőhibák | `failure` ApiError diagnostics; csak VALIDATION_ERROR enged javított APPLY-t. |
| Conflict/reload | CONFLICT; explicit új GET/reload szükséges, nincs kliensoldali CAS-javítás. |
| Bizonytalan kimenet | UNKNOWN + külön recovery/recoveryFailure; nem sikerüzenet. |
| Lezárás | `onChanged` CLOSED snapshot, SessionId és closedReason. |

A `onChanged` csak szerver-threades adapterpont, jelenleg üres observerrel. A GUI packettervezése és a lokális beviteli draft kezelése B3/C. Minden értesítést a teljes SessionId alapján kell route-olni; régi CLOSED értesítés nem zárhat új ablakot. Az observerben reentráns OPEN elutasított; új megnyitást későbbi szerverfeladatra kell ütemezni. Explicit close megengedett, és a coordinator az értesítés utáni HTTP-indítás előtt újra fence-el. Observerkivétel nem okoz APPLY retryt.

A GUI nem birtokol authoritative CAS-t; APPLY-ból csak mezőértékeket küldhet a szerver által hozzá rendelt sessionre. A target és az OPEN hiteles interakciós feloldását B3/C-nek kell kialakítania. A payload jogosultságát és méretét a későbbi hálózati belépésnél is ellenőrizni kell. A világprojekció készenlétét a mostani AVAILABLE_NOT_PROJECTED alapján nem szabad kiírni.

## Ellenőrzések

Repository gyökeréből, Java 25-tel:

```powershell
$env:JAVA_HOME="$PWD\.tools\jdk-25"
.\gradlew.bat --offline compileKotlin --console=plain
.\gradlew.bat --offline test --tests '*editor.BlockEditorSessionTest' --console=plain
.\gradlew.bat --offline test --tests '*editor.BlockEditor*' --console=plain
.\gradlew.bat --offline build --console=plain
git diff --check
```

| Ellenőrzés | Végső eredmény |
|---|---|
| Session/lifecycle/APPLY/UNKNOWN/fenced-handoff unit | 55 PASS, 0 hiba/kihagyás. |
| Authoritative Stage/Graph assembler-integráció | 9 PASS, 0 hiba/kihagyás. |
| B3/A DTO/parser regresszió | 15 PASS. |
| B3/A HTTP/transport regresszió | 17 PASS. |
| Célzott editor csoport összesen | 96 PASS. |
| Teljes Kotlin készlet, az előzőkkel együtt | 297 PASS, 25 suite, 0 failure/error/skipped. |
| Offline Java 25 Gradle build | PASS; main/client fordítás, JAR és sourcesJar. |
| Fizikai editor reconciliation, Minecraft acceptance | NEM VÉGREHAJTOTT; a committer-bekötés hiányzik, nem PASS. |
| Diff/whitespace és új dokumentum linkjei | PASS. |

Az 55 sessiontesztben külön esetek fedik a timeoutot, connection resetet, malformed/exceptional completiont, HTTP 500/503-at, sikeres/sikertelen/késői recoveryt, az APPLY bizonyíthatatlanságát recovery után és az automatikus retry hiányát. A Graph-handoff fake consumerrel, a 9 assembler-teszt tényleges StageProjectionAssemblerrel fut: ez nem fizikai world-commit teszt.

A teljes készletben a releváns regresszió is lefutott: GraphProjectionStore 11, GraphTransportAdapter 6, HallProjectionIsolation 11, HallRegistryGraph 20, LifecycleHardening 5, HallSelectionCoordinator 19, StageNavigationCoordinator 14, StageSwitchProjection 7, StageReconnectReconciler 4 és WireInteractionCoordinator 22 teszt. Ezek nem új editor world-commit tesztek. A validateAccessWidener NO-SOURCE, nem külön PASS teszt.

Az első bővített editor fordításban a régi Fabric `WorldChange` eseménynév hibás volt; a helyi API JAR alapján `ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL`-re javítva a végső célzott és teljes build sikeres. A célzott tesztek módosítás utáni ismétléseit nem számoljuk több külön tesztnek.

A tesztlogger helyi logs/latest.log forgatási mellékhatását az indulás előtt elmentett, Git-baseline-nal ellenőrzött bájtokra visszaállítottuk; csak az e futás által létrehozott és ellenőrzött archívumot távolítottuk el. A záró diff kizárólag a fenti implementációt, teszteket és jelentést tartalmazza, nincs log/build output.

Python/PostgreSQL és Minecraft acceptance nem futott; nincs Python/API/SQL/domain-szerződés változás. Nincs CkFormScreen-átalakítás, új parancs/interaction, automatikus SAVE/PUBLISH, production szolgáltatás-/adatbázis-/világmódosítás vagy restart. A B3/A fájlok változatlanok. A záró working tree szándékosan nem tiszta: egy meglévő Kotlin fájl módosított, hat új fájl untracked; az index és HEAD változatlan.

**STOP – projektgazdai felülvizsgálat.** A sessionréteg átadható review-ra; a B3/C játékbeli editor teljes működésének előfeltétele a fenti reconciliation-committer és a külön GUI/network integráció. Ez a jelentés nem telepítési vagy production GO.
