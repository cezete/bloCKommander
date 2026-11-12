# CK-08/B3/B2 – Metadata-reconciliation committer

Dátum: **2026-11-12**. Kiinduló és záró branch: **main**; HEAD: **e4ad0c573fd303c78e79f28b8758de5deecc0ade**, `feat(CK-08): add fenced Fabric block editor sessions`. Induláskor a working tree és az index tiszta volt; a [B3/B sessionréteg](CK-08B3B-block-editor-session.md) és a [B3/A transport](CK-08B3A-fabric-block-editor-transport.md) commitolva volt. Az index és HEAD változatlan maradt.

**Minősítés: PASS a szűk metadata-reconciliation határán.** A korábbi B3/B PARTIAL jelentés hiányzó metadata-committer kapuját ez a szelet beköti. Nincs általános Stage-/Wire-javító vagy topológiaváltó algoritmus. A determinisztikus integráció valódi registry/store/projector/coordinator komponensekkel, fake Minecraft-tárolással és HTTP-val bizonyított; élő Minecraft acceptance nem futott.

## Felderítés és a választott commitút

| Réteg / meglévő út | Tényleges szerep és sorrend |
|---|---|
| Python working copy | GET/APPLY authoritative domainállapot, az editor által elfogadott schema/value/CAS forrása. A Fabric projekciója ettől külön állapot. |
| PostgreSQL | Az explicit Program-SAVE mentett állapota; e szelet nem írja. |
| HallRegistry | A fizikailag megjelenített Stage, benne START/STOP label/description, epoch/revision és programDirty. |
| GraphProjectionStore | Hall/Program/WC/Stage kulcsú Graph snapshot, epoch/revision, request- és lifecycle-fence. A GraphTransportAdapter a completion main-threades feldolgozásában írja. |
| Stage-switch | Stage/Graph GET és assembler; a Graph store előzetesen SYNCED. A StageSwitchProjection preflightol, managed blokkokat cserél, Wire-t vetít, SAVE-táblát renderel, majd commitStageSwitch. Nem megfelelő metadata-only műveletnek. |
| Reconnect | authoritativeReload, Stage-render, aszinkron Graph/Wire-reconciliation, majd újraellenőrzött SAVE-board. Azonos epoch mellett is új Hall/Graph lifecycle-t hozhat létre. |
| WorldRenderer | A renderStageBlocks teljes CK-programblokk újraépítést végez; a renderSaveState a meglévő kétoldalas táblát írja. A label/description nem külön fizikai felirat és nem blokkentity-adat. |
| WireInteractionCoordinator | Az aktuális Graph view-ból készíti a következő REMOVE / PLACE-intent / BIND parancs CAS-át; fizikai szinkront külön tart nyilván. |

A szűk út ezért **metadata modellfrissítés + változatlan fizikai blokk/Wire ellenőrzés + meglévő SAVE-board render**. Nem másolja a Stage-switch algoritmust, nem navigál és nem cseréli ki a Stage-et. A Graph GET előkészítése továbbra is a mellékhatásmentes Program/Graph HTTP-klienseket és a meglévő StageProjectionAssemblert használja; nem indít korai store-írást a GraphTransportAdapteren át.

## Fájlok

Új fájlok:

- [BlockEditorReconciliationCommitter.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorReconciliationCommitter.kt): structured result, metadata-only preflight, fencelt commit és részleges hibakezelés.
- [MinecraftMetadataProjectionWorld.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/editor/MinecraftMetadataProjectionWorld.kt): szerveroldali blokk-/Wire-ellenőrzés és a meglévő SAVE-sign render visszaellenőrzése.
- [BlockEditorReconciliationCommitterTest.kt](../src/test/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorReconciliationCommitterTest.kt): 47 determinisztikus teszt, köztük session → committer integráció.
- Ez a jelentés.

Módosított fájlok:

- [BlockCommanderMod.kt](../src/main/kotlin/me/buciqua/blockommander/BlockCommanderMod.kt): a meglévő WireInteractionCoordinator átadása az editor runtime-nak.
- [BlockEditorSessionRuntime.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorSessionRuntime.kt): read-start megfigyelés, automatikus consumer, explicit reconcileAgain.
- [BlockEditorSessionCoordinator.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorSessionCoordinator.kt): kontextussal fencelt átadás, strukturált projekciós eredmény és GET-only helyreállítás.
- [BlockEditorReconciliation.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorReconciliation.kt): a prepared snapshot újraellenőrzése a commit határán.
- [HallRegistry.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/HallRegistry.kt): szűk commitMetadataProjection; Graph/Stage közös main-threades átvezetése, program-status invalidálás.
- [GraphProjectionStore.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/graph/GraphProjectionStore.kt): read-only observation, folyamatban lévő tokenek követése és a meglévő apply-t használó applyObserved.
- [WireWorldProjection.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/wire/WireWorldProjection.kt): a meglévő plan írásmentes fizikai egyezésvizsgálata.
- [WireInteractionCoordinator.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/wire/WireInteractionCoordinator.kt): ellenőrzött, változatlan Wire-projekció átvétele és NEEDS_REPAIR jelzés.

A StageSwitchProjection, StageReconnectReconciler, WorldRenderer, B3/A HTTP/DTO-szerződés és a korábbi koncepció-/jelentésfájlok változatlanok. A B3/B történeti PARTIAL jelentését nem írjuk át visszamenőleg.

## Committer API és eredmények

```kotlin
commit(
    id: EditorSessionId,
    capture: EditorCapture,
    editor: EditorGetResult,
    frame: EditorMetadataFrame,
    world: MetadataProjectionWorld,
    sessionCurrent: () -> Boolean,
): MetadataCommitResult
```

Az EditorMetadataFrame a B3/B PreparedStageSwitch mellett a lekérdezés indulásakor látott immutable Hall referenciát és GraphProjectionObservationt tartja. A megfigyelés a teljes StageGraphView-t, request-generationt és az aktív kérés jelenlétét tartalmazza. A Graph-fence nem HTTP-kérés, nem módosít státuszt vagy lifecycle-t.

| Státusz | Jelentés |
|---|---|
| COMMITTED | Stage metadata/revision, Graph snapshot, ellenőrzött változatlan Wire és ellenőrzött SAVE-board egy authoritative verzióhoz tartozik. |
| STALE_SESSION | A teljes session/connection-generation már nem aktuális; nincs projekciós írás. |
| STALE_PROJECTION | Megváltozott Hall/reference, navigation/lifecycle, Graph observation vagy függő Graph-kérés; nincs elavult frame-ből származó írás. |
| CONFLICT | Identity/epoch/revision/physical target, azonos revision eltérő Graph-tartalom vagy Program-hierarchia ellentmondás. |
| UNSUPPORTED | Hiányzó Graph baseline, eltérő topológia, fizikai Stage-szerkezet vagy saved-version; teljesebb meglévő helyreállítás szükséges. |
| PROJECTION_FAILED | Fizikai preflight vagy végrehajtás/utóellenőrzés hibás; soha nem részleges siker teljes COMMITTED néven. |

Minden nem COMMITTED eredmény `reconciliationRequired=true`. A státusz és a sanitizált reason külön átadható a GUI-nak. Az `EDITOR_METADATA_COMMIT` CK_DIAG esemény tartalmazza a H/P/WC/Stage kulcsot, connection/session-generationt, státuszt, reason-t, epochot és revisiont. A részleges hiba reasonje a GRAPH_STAGE_UPDATE / WORLD_VERIFICATION / WIRE_VERIFICATION / SAVE_BOARD / POST_COMMIT_VERIFICATION fázist azonosítja; nem naplóz mezőértéket vagy secretet.

## Fence és frissesség

Az automatikus runtime consumer a B3/B `withReconciliation` kapu kontextust is átadó `withReconciliationContext` változatát használja. Ez ugyanazt az egyszer használható frame-et veszi át, közvetlenül előtte ellenőrzi a teljes SessionId-t és a ServerPlayerből újracapture-olt lifecycle-t. A committer elején és az írás előtt újra meghívott predikátum a valódi aktuális kapcsolatot és teljes session-generationt ellenőrzi.

További kötelező egyezések: world/Hall/Program/WC/Stage/instance/type, Hall-generation, navigation-generation, Graph lifecycle, Stage epoch, prepared lifecycle, Stage/Graph revision és a minimálisan elfogadott editor-revision. A target fizikai Hall/world/origin/size adatai az assembler ellenőrzésén túl a commitnál is egyeznek a megjelenített Stage-dzsel. Ha a frame ugyanazt a revisiont reprezentálja, mint az editor-nézet, a cél metadata és saved/dirty értékek is azzal egyeznek.

A Hall referenciájának változása bármely köztes registry-frissítést észlel. A Graph observation a request-generation változását is észleli akkor is, ha az új kérés ugyanazt a snapshotot adta. Egy még be nem fejeződött, már superseded Graph-token is blokkol: a store minden, az adott lifecycle-ban aktív tokenjét követi, nem csak a legutóbbit. Stale callback csak a saját aktív tokenjét retires-eli; nem írhatja át az újabb snapshotot vagy státuszt. Lifecycle-cserénél az adott inkarnáció függő tokenjei leválnak.

A preflight utáni utolsó fence ismét ellenőrzi a Hall referenciát, Stage-fence-et és Graph observationt. Az applyObserved zár alatt ellenőriz még egyszer, majd a meglévő begin/apply ellenőrzéseket használja. Új, eltérő epoch/revision elfogadási algoritmus nincs. Régi snapshot nem kerülhet vissza; az azonos revisionű, eltérő Graph külön CONFLICT, írás előtt.

Ez a garancia a Fabric által ismert lifecycle-ra, requestekre és projekciós állapotra vonatkozik. Nincs elosztott Python–Minecraft tranzakció vagy backend-lock: egy másik folyamat/Hall még nem megfigyelt, GET utáni domainváltozását a meglévő protokoll nem tudja kizárni. A következő authoritative GET/mutation-CAS kezeli a később észlelt eltérést. E szelet nem ígér időkorlát nélküli globális frissességet.

## Commit sorrend és a COMMITTED feltételei

1. Szerver-thread ellenőrzés; session, Hall, navigation, Graph observation és frame identity/version ellenőrzés.
2. Metadata-only szerkezet: instance-készlet, típus, pozíció és orientáció változatlan; csak START/STOP label/description változhat. GraphNode/Edge/Route/WireCell, geometriai keret és katalógusváltozat változatlan. Saved-version változást ez az út nem vezet át.
3. Fizikai preflight: az aktuális Stage-ben pontosan a nyilvántartott CK-programblokkok vannak megfelelő típussal/orientációval; van a meglévő SAVE_SIGN helyén sign block entity. A meglévő Wire projector planje egyezik a tényleges cellakészlettel, geometriával és vizuális állapottal. Eltérésnél nincs automatikus wire-javítás vagy újraépítés.
4. Utolsó session/projection fence. A HallRegistry szűk adaptere a Graph snapshotot meglévő applyObserved/begin/apply útvonalon alkalmazza, majd a Stage metadata/revision és programDirty értékeit cseréli. A Program-status generációt invalidálja. A Hall/navigation/Graph lifecycle és a megjelenített Stage identity változatlan.
5. Programblokkok és Wire fizikai utóellenőrzése. A WireInteractionCoordinator csak a pontos, aktuális Graph snapshothoz igazolt, változatlan projekciót jelölheti SYNCED-nek. Nincs Wire set/remove hívás a metadata-committerben.
6. A meglévő WorldRenderer.renderSaveState az updated Hall authoritative programDirty értékét kapja. Mindkét oldalon, filtered/unfiltered szöveggel visszaellenőrizzük a tényleges táblát.
7. Végső registry-/Graph-/Wire-egyezés; csak ezután COMMITTED és GUI-értesítés.

A sikeres APPLY után a runtime ezt automatikusan végrehajtja a szerver threadjén; az observer nem kap utána időben visszafelé mutató AVAILABLE_NOT_PROJECTED értesítést. A pending/accepted domainállapot és a projekciós eredmény két külön fogalom.

## SAVE-board és hibatűrés

Stage DIRTY mellett a frame programDirty értéke nem lehet false. Dirty authoritative állapotban a board **NINCS MENTVE**, no-op clean állapotban **MENTVE** lehet. A Graph savedVersion megmarad, a Program saved-versionjét e kód nem növeli. Semmilyen SAVE/PUBLISH végpontot nem hív. A korábbi SAVE/status completionöket a meglévő revision/status fence-ek zárják ki.

Nincs általános vagy atomikus world-tranzakció. Előzetes hiba esetén a Stage/Graph/Wire/board nem frissül. Ha a Graph/Stage átvezetés után világ-/Wire-/board-/utóellenőrzési hiba történik, az authoritative új metadata megmaradhat a registryben és Graph snapshotban; a store STALE, a Wire NEEDS_REPAIR lesz. A programblokkokat és Wire-t a committer nem írja át, így azokat nem kell visszarollbackelnie. A tábla a hibaponttól függően régi, részlegesen frissült vagy már új lehet; hibánál nem tekinthető igazolt állapotjelzőnek. A korábbi MENTVE felirat fennmaradása board-I/O hibában nem zárható ki, ezt a structured PROJECTION_FAILED és CK_DIAG jelzi, nem hamis COMMITTED.

A Graph-store adapter kivételekor konzervatívan részleges hibával számolunk. A változás miatt elutasított adapterhívás STALE_PROJECTION; nem írunk rá utólag hibastátuszt az időközben megváltozott projekcióra. Domain rollback, régi Graph visszatöltése, CAS-átírás és APPLY retry nincs.

Az ismételt, már elfogyasztott régi frame STALE_PROJECTION: nem módosít ismét registryt/Graphot/boardot, nem duplikál Wire-t vagy Edge-et. Ez biztonságos ismétlés, nem tartós idempotency receipt. Friss, explicit GET-ből kapott, már aktuális metadata ismét ellenőrizhető és COMMITTED lehet.

## Helyreállítás és B3/C szerződés

Új szerveroldali művelet:

```kotlin
editorSessions.reconcileAgain(player: ServerPlayer, id: EditorSessionId): Boolean
```

Csak aktuális ACCEPTED/UNKNOWN sessionből indulhat; folyamatban lévő recovery/reconciliation mellett tiltott. Új editor GET, majd új Stage/Graph GET-pár és új observation/frame készül. A régi frame és projekciós eredmény eldobódik; GET-hiba FAILED állapotot ad, korábbi COMMITTED jelzés nem marad érvényben. Automatikus retry-loop nincs.

Az editor session eredeti opened GET/CAS-a és az elfogadott APPLY eredménye megmarad. UNKNOWN recovery COMMITTED projekciója után is UNKNOWN: az aktuális authoritative állapot megjelenítését igazolja, az eredeti APPLY végrehajtását nem. Új APPLY-hoz továbbra is explicit új OPEN/GET szükséges.

B3/C az `EditorSessionSnapshot.projectionResult` status/reason/reconciliationRequired mezőit és a reconciliation=PENDING/COMMITTED/FAILED állapotot használhatja. ACCEPTED + FAILED esetén a domainmódosítás elfogadott, a projekció nem igazolt. UNKNOWN + COMMITTED esetén az aktuális állapot projektálva, a korábbi művelet kimenete továbbra is ismeretlen. A teljes SessionId szerinti routing, observerből későbbi szerverfeladatra ütemezett új OPEN és a kliens CAS-nélkülisége változatlan B3/B követelmény. GUI/packet/parancs ebben a szeletben nem készült.

## Tesztek és ellenőrzések

Java 25, repository gyökeréből futtatott parancsok:

```powershell
$env:JAVA_HOME="$PWD\.tools\jdk-25"
.\gradlew.bat --offline compileKotlin --console=plain
.\gradlew.bat --offline test --tests '*editor.BlockEditor*' --console=plain
.\gradlew.bat --offline test --tests '*editor.BlockEditor*' --tests '*GraphProjectionStoreTest' --tests '*StageSwitchProjectionTest' --tests '*WireInteractionCoordinatorTest' --console=plain
.\gradlew.bat --offline build --console=plain
git diff --check
```

| Ellenőrzés | Végső eredmény |
|---|---|
| Új committer/session-integrációs suite | 47 PASS, 0 failure/error/skipped. |
| B3/B session regresszió | 55 PASS. |
| B3/B read-side assembler regresszió | 9 PASS. |
| B3/A DTO és transport | 15 + 17 PASS. |
| GraphProjectionStore / StageSwitchProjection / WireInteractionCoordinator | 11 / 7 / 22 PASS. |
| Teljes Kotlin regresszió | 344 PASS, 26 suite, 0 failure/error/skipped. |
| Java 25 offline build | PASS, main/client fordítás, JAR és sourcesJar. |
| Whitespace és új jelentés relatív linkjei | PASS. |
| Élő Minecraft acceptance | NEM FUTOTT; külön későbbi kapu, nem állítunk fake adapteres eredményt élő world-bizonyítéknak. |

A célzott editor/Graph/Stage/Wire futás az új suite első 46 esetével 182 tesztet futtatott. A végső explicit-refresh regresszióval 47 új teszt van; azt a végső teljes build is lefuttatta. Az ismétléseket nem számoljuk új teszteknek. A teljes suite tartalmazza a Hall/Stage lifecycle, reconnect, Hall Selection és Wire resource regressziókat is. A validateAccessWidener NO-SOURCE, nem tesztelt képességként szerepel.

Lefedés: START/STOP és kétmezős metadata, clean no-op, revision/dirty/saved állapot, topológia/cellakészlet megőrzése, ismételt frame, session/connection/navigation/Hall Selection ABA, identity/epoch/revision/physical konfliktusok, köztes újabb Stage/Graph és azonos eredményű új Graph GET, superseded függő request, késői frame, store/world/Wire/board hibák, részleges recovery és UNKNOWN jelentésének megőrzése. A következő remove/bind/place-intent a valódi WireInteractionCoordinatorral az új epoch/revisiont használja. A fake HTTP nem bizonyít Python route-/edge-számítást; az meglévő, változatlan domainréteg.

Nem futott élő szerveres integration, Python/PostgreSQL vagy Minecraft acceptance; e környezetet nem indítottuk el. A Kotlin komponensek fake fizikai adapteres integrációja futott. A logger helyi mellékhatását induló bájtokra visszaállítottuk, az általunk létrehozott, ellenőrzött forgatott tesztlogokat eltávolítottuk.

## Korlátok és záróállapot

- Eltérő topológia, origin/size, mentett verzió, hiányzó Graph baseline, fizikai sérülés vagy hiányzó SAVE-sign esetén ez az út elutasít. Ilyenkor a meglévő reconnect/Stage-projection helyreállítás szükséges; ez nem automatikus editor retry.
- Nagy Stage esetén a fizikai ellenőrzés a Stage térfogatát main threaden bejárja, akárcsak a meglévő Wire-projekció. Nincs új háttérszálas világolvasás; acceptance során a költség külön megfigyelendő.
- A backend GET-pár nem elosztott tranzakció; nem egyező Stage/Graph verzió biztonságos elutasítás. A B3/B fizikai Hall-mozgás tick-poll korlátja változatlan.
- B3/C előtt szükséges a GUI/network trust boundary és a kézi acceptance megtervezése. A jelen eredmény nem production telepítési GO.

A záró working tree a felsorolt nyolc meglévő Kotlin fájl módosítását és négy új fájlt tartalmazza; az index üres, HEAD változatlan. GUI, új interakció/command, Python HTTP/domain, SQL, Graph-domain algoritmus és automatikus SAVE/PUBLISH nincs. Production szolgáltatás, adatbázis és Minecraft-világ nem módosult; nincs deploy/restart vagy commit.

**STOP – projektgazdai felülvizsgálatra.**
