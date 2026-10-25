# CK-04/B2 – Fabric Graph Transport & State Projection

## 1. Architektúra

A B2 külön szerveroldali transport-réteget ad a meglévő Fabric–Python kapcsolathoz. A régi `ApiClient` és a programblokk/hangár HTTP út változatlan maradt.

```text
ServerPlayer
  → ServerPlayerIdentity
  → GraphCommand / GraphApiClient (async HTTP + shared secret)
  → GraphResponseParser (szigorú B1 DTO)
  → GraphTransportAdapter (szerver main-thread executor)
  → GraphProjectionStore
  → HallRegistry Stage-vetítés
```

A graph válasz feldolgozása nem hív `WorldRenderer`-t, nem importál `ServerLevel`-t, és nem ír Minecraft-blokkot. A `BlockCommanderMod` a dedikált/integrált szerver indulásakor létrehozza az adaptert a tényleges Minecraft server executorral, leálláskor pedig eldobja. B2 nem köt wire/bind parancsot játékeseményhez.

## 2. Új Kotlin osztályok és fájlok

- `runtime/graph/GraphDtos.kt`: B1 response DTO-k, szigorú parser és protokollhiba.
- `runtime/graph/GraphCommands.kt`: wire/bind command envelope és szerveroldali player identity.
- `runtime/graph/GraphApiClient.kt`: autentikált, aszinkron graph HTTP kliens.
- `runtime/graph/GraphProjectionStore.kt`: Stage-izolált vetítés, sync state és response fencing.
- `runtime/graph/GraphTransportAdapter.kt`: reconciliation, command response alkalmazás és egyszeri recovery út.
- `HallRegistry.kt`: Stage epoch/revision megőrzése és graph projection lifecycle bekötése.
- `BlockCommanderMod.kt`: szerver-lifecycle-höz kötött adapterpéldány.
- `src/test/kotlin/**`: DTO-, transport-, fencing-, adapter- és HallRegistry-tesztek.

A Gradle teszt-runtime explicit `junit-platform-launcher` függőséget kapott; termelési függőség nem változott.

## 3. Secret-konfiguráció

```text
BLOCKCOMMANDER_GRAPH_SHARED_SECRET=<erős véletlen titok>
```

A `GraphApiClient.fromEnvironment()` kizárólag processzkörnyezetből olvassa a titkot. A kliens minden graph kérésben `X-BlockCommander-Graph-Secret` headert küld. Hiányzó vagy whitespace-only secret esetén már a kliensben befejeződik a művelet `Unavailable` eredménnyel: nem készül és nem indul HTTP-kérés.

A secret nincs resource-ban, forráskonstansban, DTO-ban, `toString()`-ben, logban vagy exception szövegben. A régi, nem-graph `ApiClient` változatlan.

## 4. HTTP DTO-szerződés

Támogatott API-verzió: `ck-graph-v1`; command schema: `1`.

A response parser megőrzi:

- az envelope `request_id`, `stage_id`, `workspace_epoch`, `working_revision`, `modified` mezőit;
- a Stage metaadatot, `dirty` és kompatibilitási mezőket;
- a graph/edge-indicator/save/layout/program-validation státuszokat;
- node, wire cell/channel/connection, edge és route vetítéseket;
- validation és response diagnosztikákat;
- wire diffet és bind created/reused edge ID-kat.

A parser ellenőrzi a kötelező mezők JSON-típusát, az egész számok pontosságát, az UUID-ket, az API-verziót, valamint az envelope és a belső `state` Stage/epoch/revision egyezését. Ismeretlen extra mezőket előre kompatibilitási céllal figyelmen kívül hagy; hiányzó vagy hibás kötelező mező protokollhiba.

A Kotlin DTO kizárólag transport- és megjelenítési vetítés. Nem jár be wire hálózatot, nem validál topológiát és nem képez edge-et.

## 5. Stage graph vetítés

A `Stage` most megőrzi a Python `workspace_epoch` és `working_revision` értékét. A `GraphProjectionStore` Stage ID szerint külön slotokat tart. Egy slotban külön szerepel:

- az autoritatív Stage epoch/revision;
- a legutolsó elfogadott graph snapshot vagy `null`;
- a lifecycle- és request-generation;
- a sync státusz és strukturált problémakód.

Ezért a `NOT_REQUESTED + snapshot=null` különbözik a `SYNCED + nem-null, de üres listákat tartalmazó snapshot` állapottól.

Sync státuszok: `NOT_REQUESTED`, `RECONCILING`, `SYNCED`, `STALE`, `CONFLICT`, `ERROR`.

A legacy Stage blokk CRUD válasza frissíti a Stage envelope-ot, `STALE`-re jelöli a graph vetítést és növeli a lifecycle/request generationt. Epochváltó autoritatív hall/Stage reload törli a korábbi snapshotot.

## 6. Epoch/revision fencing

Az alkalmazási szabályok:

| Helyzet | Eredmény |
|---|---|
| azonos epoch, alacsonyabb revision | `STALE_REVISION`, régi snapshot marad |
| azonos epoch/revision, azonos snapshot | idempotens `SYNCED` |
| azonos epoch/revision, eltérő snapshot | `SAME_REVISION_CONFLICT`, régi snapshot marad |
| eltérő epoch | `EPOCH_CONFLICT`, automatikus átvétel nincs |
| autoritatív lifecycle reload új epochkal | régi snapshot törlődik; az új epoch következő snapshotja elfogadható |
| régi lifecycle/request token válasza | `IgnoredStaleRequest` |
| másik Stage válasza | `STAGE_ID_MISMATCH` |

Epoch UUID-k között nincs rendezés vagy lexikografikus „újabb” választás. Új epochot csak `HallRegistry.install()` által jelzett autoritatív Stage lifecycle reload nyithat.

## 7. Reconciliation és retry

Az explicit `reconcile(stageId, ServerPlayer)` GET-et indít a B1 graph-state végpontra. A válasz parse-olása HTTP háttérszálon történhet, de a projection alkalmazása a konstruktorban kapott Minecraft server executoron fut.

A command objektum maga őrzi a request ID-t, ezért ugyanannak az objektumnak az újraküldése ugyanazt az ID-t használja. Bizonytalan command eredménynél (`Unavailable`, hibás protokollbody), valamint HTTP 409 vagy 5xx esetén az adapter pontosan egy GET reconciliationt indít. Nincs automatikus végtelen retry-loop. A biztos 4xx hibák strukturált `ApiError` eredményként maradnak.

## 8. Player identity trust boundary

A publikus identity factory `ServerPlayerIdentity.from(ServerPlayer)`. A command nem nyers JSON-ból és nem önálló UUID-paraméterből veszi át a játékost. A teszt-only factory Kotlin `internal`, termelési hívó számára nincs publikus egyparaméteres UUID-konstruktor. A Python owner-check továbbra is kötelező második védelmi réteg.

B2 nem vezet be team, invited editor vagy publikációs jogosultságot.

## 9. Python worker-modell

A repository deployment scriptje egy `waitress-serve --call ck_webhook:create_app` processzt indít, explicit több-worker konfiguráció nélkül. Ez megfelel a B1 által támogatott helyi, egyprocesszes `GraphApplicationService` modellnek.

Több Python processz vagy egymástól független service instance esetén a processzmemóriás idempotencia nem ad exactly-once garanciát. Ilyen deployment B3 előtt blocker, amíg nincs egyprocesszes invariáns vagy megosztott/tartós request registry.

## 10. Tesztek és eredmények

Izolált Kotlin kapu:

```powershell
$env:JAVA_HOME="$PWD\.tools\jdk-25"
$env:GRADLE_USER_HOME='C:\Users\tamas\.gradle'
.\gradlew.bat test --no-daemon
```

Eredmény: **27/27 PASS**. A lokális fake HTTP szerverrel futó tesztek nem indítottak Minecraft szervert, PostgreSQL-t vagy Dockert.

Lefedés: secret fail-closed és redakció; autentikált GET/POST; teljes B1 HTTP hibamátrix; teljes snapshot, wire diff, binding és diagnosztika; API-verzió/kötelező mezők; üres kontra nem lekért graph; revision/epoch/generation fencing; Stage-izoláció; legacy invalidálás; wire/bind DTO; stabil request ID; egyszeri response-lost reconciliation; main-thread executor; WorldRenderer/ServerLevel-függetlenség.

Teljes Gradle kapu: `gradlew.bat build --no-daemon` – **BUILD SUCCESSFUL**. Teljes Python kapu helyes `python-service` munkakönyvtárból és `PYTHONPATH="$PWD\src;$PWD"` beállítással: **383/383 PASS**. Egy korábbi, repository gyökeréből indított Python parancs hibás `PYTHONPATH` miatt négy testmodult nem tudott importálni; ezt nem minősítettük teszteredménynek, majd a dokumentált paranccsal újrafuttattuk.

## 11. Ismert korlátok

- B2 nem hoz létre CK Wire blokkot, itemet, modellt, textúrát vagy játékeseményt.
- A graph transport még nem módosít Minecraft-világot.
- Nincs automatikus reconciliation minden player joinnál; az adapter explicit műveletet ad B3 számára.
- A projection processzmemóriás, szerverrestart után új GET szükséges.
- A command timeout utáni egyszeri GET megmondja a hiteles aktuális állapotot, de önmagában nem bizonyítja, hogy egy evictált régi request pontosan egyszer futott.
- A megjelenítési DTO stringként őrzi a Python státuszértékeket; új opcionális mező tolerálható, új kötelező szemantika API-verziót igényel.

## 12. CK-04/B3 előfeltételek

1. SysOrg review a secret injektálásról és az egyprocesszes Waitress invariánsról.
2. Döntés a wire placement Python-first sorrendjéről, klienspredikcióról és render-failure UX-ről.
3. A B3 eseményadapter kizárólag `ServerPlayer`-ből készítsen identityt.
4. Világírás csak elfogadott, aktuális projection után és csak a szerver főszálán történhet.
5. Epoch/revision konfliktus esetén a B3 ne próbáljon helyben topológiát javítani; explicit reconciliation szükséges.
6. A B1 egycellás E–W SINGLE topológiai korlátja külön döntés nélkül nem bővíthető.

Kapujavaslat: **CK-04/B3 CONDITIONAL GO** a fenti review-k és a teljes build/Python regresszió zöld eredménye mellett. B3 nem indult el.
