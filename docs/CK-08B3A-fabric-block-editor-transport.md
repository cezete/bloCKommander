# CK-08/B3/A – Fabric Block Editor transport

Dátum: **2026-11-12**. Kiinduló és záró branch/HEAD: **main / 5b970828787811a36b25c1f1e718fbc202d68942**. A kiinduló working tree tiszta, a [B2 HTTP API](CK-08B2-block-editor-http-api.md) commitolva volt. Ez a szelet Kotlin DTO/HTTP/transport; nincs GUI, interaction, session, világírás vagy Python-változtatás.

## Fájlok és API-k

Új szerveroldali csomag: `me.buciqua.blockommander.runtime.editor`.

- [BlockEditorDtos.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorDtos.kt): immutable request context, typed GET/APPLY/séma/hiba DTO-k, hiányzó és null patchértékek, strict response parser.
- [BlockEditorApiClient.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorApiClient.kt): a meglévő JDK `HttpClient.sendAsync` minta, azonos base URL/shared secret konfiguráció és timeout; fake exchange tesztillesztés.
- [BlockEditorTransportAdapter.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorTransportAdapter.kt): szerveroldali identity capture, response identity/CAS/korreláció ellenőrzés és explicit executoron átadott outcome. Nincs saját editorállapot vagy registry/projekciós függőség az adapterben.

Minden editor DTO, client és adapter **Kotlin `internal`**. Az APPLY transportképesség **nincs játékosinterakcióhoz bekötve**: nincs új custom payload receiver, chatparancs, gomb vagy network endpoint Fabric oldalon. Az `internal` és az immutable context nem önálló biztonsági/session határ; a B3/B-nek kell ellenőriznie az indító játékosi műveletet.

## B2 JSON-szerződés és parser

API: `ck-block-editor-v1`; támogatott schema ID-k: `ck.block.START.metadata`, `ck.block.STOP.metadata`, sémaverzió **1**. Egyelőre kizárólag STRING mezők: label/description. Nem készült CK-programnyelvi típusrendszer vagy általános Expression/REFERENCE editor.

A parser feldolgozza a targetet, teljes sémát, schema reference-t, required/nullable/default/constraints/normalization/presentation metaadatokat, permissions-t, values-t, verziókontextust és transport echo-t. A B2 APPLY eredménye ugyanaz a lapos editor-nézet, plusz request_id/modified; nem feltételezünk új beágyazott HTTP-mezőt.

- Kötelező mező hiánya/nullja a típus szerint protokollhiba; description explicit JSON-null elfogadható, hiányzó description nem.
- A schema és schema_ref, valamint target/séma blokktípus/azonosító egyezése kötelező. Ismeretlen blokktípus, field type, schema version/default vagy normalizálási mód elutasított; nincs STRING fallback.
- Integer mező csak valódi, nemnegatív, Long-tartományú JSON egész; string/boolean/negatív/fraction/exponent nem fogadható el egésznek. UUID kanonikus, kötőjeles alakot követel, case-insensitive hexszel.
- Strict Gson JsonReader: duplikált kulcsok, trailing/lenient JSON és túlzott nesting elutasított. Parse-határ 262144 UTF-16 egység és 64 szint. Ez a már beérkezett response parsingját korlátozza, nem streamszintű HTTP body-limit.
- `status=ok` csak HTTP 200-nál siker. Strukturált `status=error` 400–599-nél API-hiba; status/body ellentmondás protokollhiba. Ismeretlen error_code szövegként megmaradhat, nem lesz belőle siker.
- A generikus hibadiagnosztikában field/details elhagyható; request_id opcionális, de ha jelen van, érvényes UUID kell. A schema/permissions/diagnosztika kollekciói a parserből módosíthatatlan listák/mapek.
- Ismeretlen kiegészítő objektummezőt nem használunk authorityként; a támogatott kötelező mezőket és a két támogatott schema fieldet szigorúan ellenőrizzük.

Hiteles JSON-példák a tényleges B2 Flask tesztkliensből, memóriabeli tesztrepositoryval rögzítve: [START GET](../src/test/resources/block-editor/start-get.json), [STOP GET](../src/test/resources/block-editor/stop-get.json), [START APPLY](../src/test/resources/block-editor/start-apply.json). Rögzítéskor csak az izolált fixture epoch/revision/saved-version/request-ID értékeit állítottuk determinisztikusra; nem történt production API-/DB-hívás.

## Immutable kéréskontextus és B3/B átadás

A pontos Kotlin belépési pontok, mind a szervermodulon belül:

```kotlin
captureBlockEditorContext(
    player: ServerPlayer,
    hall: Hall,
    instanceId: UUID,
    requestId: UUID = UUID.randomUUID(),
    opened: EditorGetResult? = null,
): BlockEditorRequestContext

BlockEditorTransportAdapter(client: BlockEditorApiClient, mainThread: Executor)

get(context: BlockEditorRequestContext):
    CompletableFuture<BlockEditorTransportOutcome<EditorGetResult>>

apply(context: BlockEditorRequestContext, changes: EditorChanges):
    CompletableFuture<BlockEditorTransportOutcome<EditorApplyResult>>
```

A `BlockEditorRequestContext` val mezői: playerUuid, worldUuid, transport (Hall/Program/WC UUID), stageId, instanceId, opcionális ismert blockType, requestId és opcionális applyBasis. Az `EditorApplyBasis` a schema reference, Stage workspace epoch és working revision immutable hármasa. Az outcome **ugyanazt az eredeti contextobjektumot** és a typed eredményt adja vissza.

A capture szerver main threaden, hiteles ServerPlayerből és registryből feloldott Hallból hívandó. Ellenőrzi a player owner/world egyezését, Hall/transport identityt és az instance jelenlétét a Hall megjelenített Stage-jén. Player UUID sosem egy kliens által küldött mezőből származik. Ez olvasás és request-capture, nem session implementáció.

GET: `opened=null`. APPLY: a B3/B sessionben megtartott, elfogadott GET eredményt kell `opened` alatt átadni. A capture ellenőrzi annak target/echo egyezését, és **a GET-ben látott epoch/revisiont őrzi meg**, nem a registry aktuális CAS-ára cseréli. Az új APPLY request ID külön kéréskorreláció; a B2 GET nem echoz ilyen request ID-t, annak korrelációja lokális future/context párosítás.

## HTTP-kliens és requestek

Ugyanaz a konfiguráció, mint a Program/Graph klienseknél:

- Base URL: `blockommander.api` system property; alapérték `http://127.0.0.1:5001`.
- Secret: `BLOCKCOMMANDER_GRAPH_SHARED_SECRET`; header `X-BlockCommander-Graph-Secret`.
- Connect timeout 3 s, request timeout 10 s.
- Hall/Program/WC headerek a meglévő `WorkingCopyTransportContext.addHeaders` útján.
- A diagnostic header a lokális request ID-t viszi; nem helyettesíti az APPLY body request_id-t.

GET:

```http
GET /api/v1/programs/{context.transport.programId}/stages/{context.stageId}/blocks/{context.instanceId}/editor?player_uuid={context.playerUuid}
```

APPLY ugyanott `/editor/apply`, POST, `application/json`:

```json
{
  "api_version": "ck-block-editor-v1",
  "request_id": "00000000-0000-0000-0000-000000002260",
  "player_uuid": "00000000-0000-0000-0000-000000009001",
  "schema_id": "ck.block.START.metadata",
  "schema_version": 1,
  "expected_stage_workspace_epoch": "00000000-0000-0000-0000-0000000002bc",
  "expected_working_revision": 12,
  "changes": {"label": " Raw 😀 ", "description": null}
}
```

Az `EditorChanges` kizárólag label/description mezőt tud szerializálni. `EditorStringChange.Missing` elhagyás, `Value(text)` változatlan string, `Clear` explicit JSON-null; label-clear és üres patch lokálisan tiltott. Nincs trim, Unicode-normalizálás vagy párhuzamos Kotlin-domainvalidáció. A szerver marad authoritative. Az APPLY body UTF-8 mérete nem lépheti túl a B2 64 KiB korlátját.

Nincs új secret vagy saját HTTP-executor konfiguráció, külön connection pool-rendszer, retry, legacy fallback, CAS-frissítés, merge vagy SAVE. A fake `EditorHttpExchange` kizárólag a JDK sendAsync hívás tesztillesztése. A production gyár ugyanazt a JDK HTTP-mintát használja, mint a meglévő kliensek.

## Eredmények és response-fencing előfeltételek

`BlockEditorResult<T>` változatai:

- `Success<T>`: parse-olt, requesthez ellenőrzött DTO.
- `ApiError`: HTTP status, stabil code/message, opcionális request ID és typed meződiagnosztikák.
- `TransportFailure`: NOT_CONFIGURED, TIMEOUT, UNAVAILABLE vagy LOCAL_REJECTED; jelzi, ha APPLY kimenetele bizonytalan lehet.
- `ProtocolFailure`: nincs sikeres editor RESULT; APPLY-nál a domainmódosítás ettől még történhetett, tehát a kimenet bizonytalan.

Siker esetén a Hall/Program/WC/Stage echo, a target Program/Stage/instance és az ismert block type kötelezően egyezik az eredeti contexttel. Az explicit mód és SHARED_PROGRAM scope is ellenőrzött. Eltérés valódi elutasítás, nem puszta log.

APPLY-nál a request ID, schema reference és epoch is egyezik. `modified=true` pontosan captured revision +1, no-op pontosan captured revision. Error request ID opcionális a B2 auth/korai parse hibái miatt; ha jelen van, egyeznie kell. Az ellenőrzések nem végzik el a B3/B session/connection/navigation fence-ét.

Timeout/I/O hiba után nincs automatikus APPLY-ismétlés. A strukturált HTTP 500/503 eredmény megmarad ApiErrornak; B3/B-nek ezeknél is számolnia kell bizonytalan APPLY-kimenettel. Hibás JSON/verzió/identity HTTP 200 mellett sem tekinthető sikernek. A client/result `toString` és a protokollhiba nem adja vissza a shared secretet vagy tetszőleges nyers backendbodyt.

## Async és main-thread határ

HTTP JDK `sendAsync`, nincs get/join/wait a production transportban. A response-parsing és ellenőrzés a transport-future láncban történik; nincs blokkoló hálózati művelet a Minecraft main threaden. Az outcome átadása `thenApplyAsync` útján a hívó által biztosított main-thread executoron történik, a Graph adapter meglévő átadási mintáját követve.

B3/B a Minecraft server executorát adja át. Már teljesült future/fake/local hiba esetén a kicsi, korlátozott parsing a hívó szálon is lefuthat; az adapter nem hoz létre külön parser-executort. Executor-shutdown/future cancellation kezelése a session coordinator feladata. A capture csak main-thread olvasásra szolgál, nem aszinkron ServerPlayer-elérésre.

## Tesztek és build

Java **25**, a repository `.tools/jdk-25` példányával; a host alapértelmezett Java 21-ét nem használtuk a buildhez. A sandbox a JDK `java.security` fájljának olvasását blokkolta; a helyi Gradle-parancsok engedélyezett emelt futtatással sikerültek. Offline mód, nincs dependency-/build-konfiguráció módosítás vagy deploy.

Repository gyökeréből:

```powershell
$env:JAVA_HOME="$PWD\.tools\jdk-25"
.\gradlew.bat --offline compileKotlin --console=plain
.\gradlew.bat --offline test --tests '*editor.BlockEditor*' --console=plain
.\gradlew.bat --offline build --console=plain
git diff --check
```

| Végső ellenőrzés | PASS | FAIL | BLOCKED |
|---|---:|---:|---:|
| Új DTO/parser tesztek | 15 | 0 | 0 |
| Új HTTP/adapter tesztek | 17 | 0 | 0 |
| Teljes Kotlin unit készlet, az új 32-vel együtt | 233 | 0 | 0 |
| Gradle compileKotlin/build | PASS | 0 | 0 |
| Wire resource/asset regresszió, a 233 része | 10 | 0 | 0 |
| Program-port orientáció, a 233 része | 4 | 0 | 0 |
| `git diff --check` | PASS | 0 | 0 |

A teljes készlet 23 suite; nincs skipped teszt. Lefutott a meglévő Graph és Program transport/DTO, TransportContext, Hall Selection és Stage navigation regresszió is. `processResources`, a main/client fordítás és mod JAR build sikeres; `validateAccessWidener` NO-SOURCE, nem létező access-widener tesztet nem állítunk PASS-nak.

Az új [DTO-tesztek](../src/test/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorDtosTest.kt) a tényleges B2 fixture-ökre épülnek. A [transporttesztek](../src/test/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorTransportTest.kt) determinisztikus fake exchange-t és queue-zott main executort használnak; egy teszt valódi JDK sendAsync-t ellenőriz loopback HTTP-tesztszerverrel. Nem indult Minecraft/WH/production szerver. Identity-mismatch, HTTP 400/401/403/404/409/413/422/500/503, timeout, malformed JSON, unsupported version, egyszeri APPLY és késleltetett eredményátadás mind lefedett.

A teljes tesztkészlet loggerének `logs/latest.log` forgatási mellékhatását visszaállítottuk: az archívum tartalmát a kiinduló Git-tartalommal ellenőriztük, az eredeti working-copy bájtokat visszaírtuk, és csak az általunk létrehozott archívumot távolítottuk el. A záró diff nem tartalmaz logváltozást.

Nem futott Minecraft acceptance vagy Python/PostgreSQL-regresszió; a Python-szerződés változatlan, a fixture-rögzítés memóriabeli B2 tesztkliensből történt. A régi START/STOP form, CkFormScreen és interaction kód változatlan; játékbeli működésüket ebben a szeletben nem teszteltük újra.

## B3/B kötelező integrációs kapu

1. Session coordinator még nincs: session/player/connection/world/navigation/Hall lifecycle egyezést a hívás előtt és minden outcome után külön ellenőrizni kell.
2. A hiteles játékost és registry-Hallt szerveroldalon kell feloldani; a kliens nem választhat player UUID-t, targetet, schema reference-t vagy CAS-t. Az opened GET eredményt a session tartja meg.
3. Pending alatt nincs új APPLY; mezőhiba mellett bevitel marad, konfliktusnál nincs automatikus merge/CAS-frissítés.
4. Timeout/protokollhiba/5xx után UNKNOWN és külön authoritative GET/reconciliation; nincs automatikus APPLY retry. A request ID nem tartós receipt.
5. Sikeres editor RESULT nem teljes Stage/Graph snapshot és nem synchronized jelzés. B3/B külön végzi a fencelt Stage/Graph-reconciliationt, világprojekciót és UI-visszajelzést.
6. Session/Hall/Stage-váltás után a késő választ nem szabad új kontextusra alkalmazni. Elfogadott domainmutáció + stale projection nem rollback és nem új APPLY indoka.
7. Alkalmazás és explicit Program-SAVE külön művelet marad.

**PASS a B3/A transportréteg határán. STOP – B3/B előtt projektgazdai felülvizsgálat. Nincs commit/deploy.**
