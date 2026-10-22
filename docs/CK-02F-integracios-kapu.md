# CK-02/F – Integrációs kapuellenőrzés és szerződésfelmérés

- Dátum: 2026-10-22
- Hatókör: CK-02/A–E Python graph-domain, meglévő Fabric/Python/PostgreSQL kapcsolódási pontok
- Végrehajtási mód: in-memory teszt és read-only forrásfelderítés
- Döntés: **NO-GO a teljes, perzisztens Fabric CK Wire integráció azonnali megkezdésére; GO a következő, adapter- és DTO-szerződést rögzítő kis műszakra.**

## 1. Executive summary

**Megfigyelt tény.** A CK-02/A–E domain együtt működik egy teljes in-memory életciklusban. A `test_graph_lifecycle.py` 12 keresztmodulos forgatókönyve valódi `bind_edges()`, `validate_graph()`, `record_validation()`, `mark_saved()`, `preview_destruction()`, `confirm_destruction()` és `replace_content()` hívásokat használ mock nélkül. A külön futtatott új csomag 12/12 teszttel sikeres.

**Megfigyelt tény.** A domain és a runtime között még nincs adapter. A meglévő `StageWorkspace` csak blokk-dictionaryket, `saved_version`, `dirty` és `revision` mezőt tart (`python-service/src/hangar/stage.py:16-22`). A jelenlegi DB-snapshot csak `stage_blocks` rekordokat ment (`python-service/src/hangar/repository.py:184-198`). GraphEdge-, EdgeRoute-, WireCell- vagy route-step perzisztencia nincs.

**Megfigyelt tény.** Két szerződéses eltérés nem domainhiba, de integráció előtt döntést igényel:

1. A generikus, hibamentes gráfreportból a `record_validation(..., validated=True)` után `GraphStatus.VALID` lesz, miközben a generikus validátor nem bizonyít teljes programstrukturális helyességet.
2. A régi `StageService.save()` a mentés végén `revision += 1` műveletet végez (`stage.py:106-114`), míg a graph-domain szerződésében a SAVE nem tartalmi változás, ezért a `working_revision` változatlan.

**Javaslat.** A következő legkisebb műszak egy tiszta `GraphWorkspaceAdapter` és verziózott DTO-séma specifikálása és in-memory adaptertesztje legyen. HTTP endpoint, DB-migráció és Minecraft wire blokk csak ezután készüljön.

## 2. Vizsgált források

### 2.1 Irányadó dokumentumok

- `AGENTS.md`
- `koncepcio.md`
- `docs/CK-01B-mini-ADR.md`
- `docs/CK-02-implementacios-itiner.md`

### 2.2 Tényleges graph-domain és tesztek

- `python-service/src/hangar/graph/model.py`
- `orientation.py`, `diagnostics.py`, `state.py`, `validation.py`, `binding.py`, `destruction.py`, `__init__.py`
- `python-service/tests/graph_validation_fixtures.py`
- az összes `test_graph_*.py` teszt

### 2.3 Runtime és perzisztencia

- `python-service/src/ck_webhook.py`
- `python-service/src/hangar/stage.py`, `repository.py`, `service.py`, `builder.py`
- `python-service/src/hangar/migrations/001_halls.sql`–`005_ck_block_types.sql`
- `Interactions.kt`, `ApiClient.kt`, `WorldRenderer.kt`, `HallRegistry.kt`
- `BlockCommanderMod.kt`, `ModContent.kt`, `CkProgramBlock.kt`
- `Networking.kt`, `FormPayloads.kt`

**Módszertani határ.** A dokumentáció tervezési bizonyíték, a fenti források a jelenlegi implementáció bizonyítékai. E dokumentum eltérés esetén nem tekinti automatikusan késznek a dokumentált, de kódban még nem létező képességet.

## 3. CK-02/A–E domainképességek

**Megfigyelt tény.** A domain jelenlegi képességei:

- immutable node-, port-, edge-, route-, WireCell- és pozíciómodellek;
- lokális portoldal → világirány transzformáció;
- strukturált, determinisztikusan rendezett diagnosztika;
- canonicalizált `GraphWorkspaceState` és külön `working_revision` / `saved_version`;
- tiszta, állapotot nem módosító gráfvalidáció;
- copy-on-write, atomi, idempotens binding stabil szemantikus edge-ID-val;
- kétlépcsős ConnectionDestroyer, stale-preview védelemmel;
- CROSS NS/EW csatornák független törlése és a megmaradó route SINGLE-re alakítása.

**Megfigyelt tény.** A `replace_content()` csak valós tartalomváltozásnál növeli egyszer a revíziót (`graph/state.py:145`), a `record_validation()` az aktuális revízió reportját rögzíti (`graph/state.py:178`), a `mark_saved()` pedig csak `saved_version` és `dirty` állapotot változtat (`graph/state.py:200`). A binding saját Stage- és revision-preflightot végez (`graph/binding.py:309-334`); a destroyer ugyanezt preview/confirm határon végzi (`graph/destruction.py:281`, `392`).

**Nem állított képesség.** A domain nem végez jogosultságellenőrzést, lockolást, I/O-t, Minecraft-topológiafelderítést, adatbázis-mentést vagy teljes programszemantikai validációt.

## 4. In-memory integrációs teszteredmények

Az új modul: `python-service/tests/test_graph_lifecycle.py`.

| Forgatókönyv | Bizonyított szerződés |
|---|---|
| Teljes START → STOP életciklus | bind → validate → record → save → preview → cancel → confirm |
| SAVE után törlés | saved_version stabil; törlés revision+1 és DIRTY |
| Törlés visszavonása | eredeti state-identitás és VALID/CLEAN állapot megmarad |
| Elavult preview | stale revision miatt elutasítás, részleges változás nélkül |
| Újrabekötés törlés után | új fizikai route és új logikai edge-ID |
| Meglévő kapcsolat újrarajzolása | edge-ID és route-ID megmarad |
| Hibás, majd javított binding | első atomi reject, második sikeres |
| CROSS NS törlés | EW edge/route megmarad és SINGLE-re vált |
| CROSS EW törlés | NS edge/route megmarad és SINGLE-re vált |
| Report elavulása | tartalomváltozás után DRAFT / CHECK_REQUIRED |
| Idempotens újrabinding | nincs revíziónövelés vagy új ID |
| Vegyes jó/hibás jelöltek | teljes parancs atomi visszautasítása |

Az önálló CK-02/F tesztfutás eredménye:

```text
Ran 12 tests
OK
```

A teljes graph-csomag eredménye:

```text
Ran 228 tests in 0.443s
OK
```

A teljes Python regresszió eredménye:

```text
Ran 241 tests in 0.566s
OK
```

**Megfigyelt tény.** A teljes életciklusban a generikus START → STOP report hibamentes, a validátor nem módosít state-et, és csak az explicit `record_validation()` állítja elő a jelenlegi `GraphStatus.VALID` értéket.

**Szerződéses kockázat.** A `GraphStatus.VALID` név jelenleg erősebb jelentést sugallhat, mint amit a generikus CK-02 validátor bizonyít. Ez nem teszthiba: a teszt reprodukálja a meglévő állapotgépet.

## 5. Fabric/Kotlin jelenlegi kapcsolódási pontok

### 5.1 Blokkok és interakciók

**Megfigyelt tény.** A CK programblokkokat a `ModContent` regisztrálja: START, STOP, IF, FOR és FUN (`ModContent.kt:32-36`). CK Wire blokk még nincs. A `CkProgramBlock` csak vízszintes orientációt tárol.

**Megfigyelt tény.** A szerveroldali játékosinterakció központja az `Interactions` osztály (`Interactions.kt:23`). Az eseményforrások:

- `AttackBlockCallback` – hangárkard A pont (`Interactions.kt:32`);
- `UseBlockCallback` – vezérlők, kard B pont, blokkdialog, programblokk-lerakás (`Interactions.kt:41`);
- `PlayerBlockBreakEvents.BEFORE` – Stage-blokk törlésének API-hívása (`Interactions.kt:72`);
- custom payload – kliens form submit a `Networking` közvetítésével és `submitForm()` belépési ponttal (`Interactions.kt:87`).

**Megfigyelt tény.** A Fabric szerver a `ApiClient` HTTP klienssel kommunikál a Python backenddel. Az alapcím `http://127.0.0.1:5001` (`ApiClient.kt:17`), GET és POST támogatással (`ApiClient.kt:19-20`). Nincs graph/wire-specifikus endpoint vagy DTO.

**Megfigyelt tény.** A Minecraft világot a `WorldRenderer` módosítja `setBlockAndUpdate` hívásokkal. Itt történik a hangár építése, bontása és Stage-visszarajzolása (`WorldRenderer.kt:23-58`). A Python alapértelmezett `fabric` world driver mellett nem írja a világot (`ck_webhook.py:15-22`).

**Megfigyelt tény.** A `HallRegistry` Fabric-oldali futásidejű cache; Stage-blokkot tud pozíció alapján keresni (`HallRegistry.kt:53-57`), de wire/csatorna/route adatot nem tárol.

**Javaslat.** A CK Wire placement/removal fogadási helye az `Interactions.register()` szerveroldali `UseBlockCallback` és `PlayerBlockBreakEvents.BEFORE` ága legyen. Ezek csak parancsot küldjenek a Python workspace felé. A Python válaszának elfogadása után a `WorldRenderer` hajtsa végre a világváltozást. Így a Fabric marad az egyetlen világíró.

**Még ellenőrizendő feltételezés.** A Minecraft 26.3 Fabric API pontos placement/break callback sorrendjét és a klienspredikció visszagörgetését egy izolált prototípustesztben ellenőrizni kell, mielőtt a wire blokk világmódosítási protokollja végleges lesz.

## 6. Python HTTP és StageService kapcsolódási pontok

### 6.1 Meglévő Stage API-k

**Megfigyelt tény.** A Flask runtime jelenleg az alábbi Stage műveleteket adja:

- blokk lerakás: `POST /api/v1/stages/<stage_id>/blocks` (`ck_webhook.py:81`);
- blokk módosítás: `POST /api/v1/stages/<stage_id>/blocks/<instance_id>` (`:86`);
- blokk törlés: `POST .../<instance_id>/delete` (`:91`);
- SAVE: `POST /api/v1/stages/<stage_id>/save` (`:96`);
- DB snapshotból rebuild: `POST /api/v1/stages/<stage_id>/rebuild` (`:101`);
- fizikai SAVE gomb: `POST /api/v1/halls/save-button` (`:106`).

Graph-, wire-, binding-, destruction- vagy diagnostics-endpoint nincs.

### 6.2 Workspace, jogosultság és konkurencia

**Megfigyelt tény.** A munkapéldány tulajdonosa futásidőben a `StageService._workspaces` map (`stage.py:24-42`). Van process-szintű globális map-guard és Stage-enként `threading.RLock`. Ez egy Python processen belül sorosít, de nem elosztott lock.

**Megfigyelt tény.** A módosítás és SAVE owner-only ellenőrzése `StageService._authorize()` (`stage.py:47`) alapján történik. A Flask/Fabric által küldött `player_uuid` az input; önálló hitelesítési vagy aláírási mechanizmus nincs.

**Megfigyelt tény.** A SAVE a `save_stage_snapshot(stage_id, expected_version, blocks)` hívással DB-szintű `SELECT ... FOR UPDATE` és optimista `saved_version` ellenőrzést használ (`repository.py:184-198`). A teljes blokkhalmaz törlése és újraírása egy tranzakcióban történik.

**Megfigyelt tény.** A snapshot visszaépítése `StageService.rebuild()` → `repository.load_stage()` útvonalon történik (`stage.py:117-124`). Dirty workspace explicit `confirm_discard` nélkül nem dobható el.

### 6.3 Javasolt adapter belépési pont

**Javaslat.** A `StageService` legyen az alkalmazási koordinátor, de ne kapjon graph-szabályokat. Egy későbbi `GraphWorkspaceAdapter` a `StageService` Stage-lockján belül:

1. a `StageWorkspace` blokkjaiból és graph-layout részéből `GraphWorkspaceState`-et készít;
2. ellenőrzi az owner jogosultságot és a kliens `expected_working_revision` értékét;
3. meghívja a tiszta domainműveletet;
4. csak siker esetén írja vissza az új immutable state tartalmát;
5. DTO-választ készít, világot nem módosít;
6. SAVE-nél egy repository tranzakcióban menti a teljes snapshotot.

Javasolt tényleges belépési pont: a `StageService._workspace()` által visszaadott, Stage-lockkal védett workspace köré épített publikus alkalmazási metódusok. A privát `_workspace()` közvetlen HTTP használata nem javasolt.

## 7. PostgreSQL jelenlegi állapot

**Megfigyelt tény.** A Stage metaadatot a `blockcommander.stages` tábla tárolja (`004_program_stages.sql:20`), benne a kanonikus `saved_version` mezővel (`:30`).

**Megfigyelt tény.** A Stage-blokkok a `blockcommander.stage_blocks` táblában vannak (`:35`); a stabil blokkazonosító az `instance_id` UUID primary key (`:36`). A blokktípus-katalógus a `program_block_types` tábla (`:1`).

**Megfigyelt tény.** Nincs GraphEdge-, EdgeRoute-, WireCell-, channel- vagy route-step tábla és a repository sem olvas/ír ilyen adatot.

**Javaslat.** Későbbi, számozott additív migrációban legalább az alábbi perzisztens elemek kellenek:

- `graph_edges`: `edge_id`, `stage_id`, source/target `instance_id` + `port_key`, kind;
- `edge_routes`: `route_id`, `stage_id`, nullable `edge_id`, endpointok, route status;
- `route_steps`: `route_id`, sorrend, relatív x/y/z, channel;
- `wire_cells` és `wire_channels`: relatív pozíció, SINGLE/NS/EW és kapcsolódási maszk;
- szükség esetén Stage-szintű graph-validation metadata, de a report részletes diagnosztikája első körben újraszámítható.

**Javaslat.** A `stage_blocks`, graph edge-ek, route-ok és wire layout ugyanazon SAVE tranzakcióban cserélődjenek, ugyanazzal a `saved_version` compare-and-swap ellenőrzéssel. Fél snapshot nem lehet látható.

**Nyitott döntés.** A route-step és wire-channel normalizált táblák vagy verziózott JSONB snapshot közötti választást a lekérdezési és migrációs igények alapján kell meghozni. A stabil ID-k és az atomi snapshot fontosabbak, mint a konkrét fizikai séma.

## 8. Javasolt DTO-k és adatáramlás

### 8.1 Alapboríték

Minden módosító parancs kötelező mezői:

```text
schema_version
request_id
stage_id
player_uuid
expected_working_revision
```

Minden válasz közös mezői:

```text
accepted/applied
stage_id
working_revision
saved_version
save_status
edge_validation_status
diagnostics[]
request_id
```

Hibák HTTP-szinten: `400` formahiba, `403` jogosultság, `404` Stage/objektum, `409` stale revision vagy saved-version konfliktus, `422` domain-diagnosztika. Az atomi domain-reject válasz lehet `422`, a state változatlan revíziójával.

### 8.2 Műveleti DTO-k

| DTO | Kötelező műveleti mezők | Hiteles adatforrás |
|---|---|---|
| WirePlacement | relatív `position`, kívánt channel/shape, Fabric block-state megfigyelés | Python workspace fogadja el; Fabric világ csak elfogadott válasz után változik |
| WireRemoval | `position`, explicit channel CROSS esetén | Python workspace és destroy preview |
| RouteDiscovery | megfigyelt wire cellák/csatornák, endpoint-jelöltek, discovery verzió | Python discovery eredmény; Fabric csak fizikai megfigyelést szolgáltat |
| BindEdgesCommand | domainmezők szerinti `candidates[]`, endpoint `instance_id/port_key`, rendezett steps | Python domain |
| BindingResult | accepted, created/reused edge ID-k, diagnostics, új revízió | Python domain |
| DestructionPreview | selection, route/edge ID, affected steps, cell/route changes, preview revision | Python domain; kliens nem módosíthatja |
| DestructionConfirmation | `preview_token` vagy teljes canonical preview hash, `confirmed` | Python újraellenőrzött workspace |
| GraphDiagnostics | code, severity, message, node/port/edge/position referenciák, report revision | Python validátor |
| StageStatus | három külön státuszdimen­zió és két verzió | Python workspace + DB saved_version |

**Javaslat.** A preview HTTP DTO ne bízzon a kliens által visszaküldött diffben. A Python tároljon rövid élettartamú preview-t, vagy írjon alá/canonical hash-sel azonosítson egy teljes preview-t; confirmationkor mindenképp számolja újra az aktuális state-ből.

### 8.3 Adatáramlás

```text
Fabric szerver – fizikai esemény és játékosazonosság
    ↓ versioned command DTO
Python StageService – owner check + Stage lock + revision check
    ↓
GraphWorkspaceAdapter
    ↓
GraphWorkspaceState → tiszta domain operation → új GraphWorkspaceState
    ↓
Stage workspace frissítése + response DTO
    ↓
Fabric WorldRenderer – kizárólag elfogadott diff világba írása
    ↓
SAVE esetén repository snapshot tranzakció → PostgreSQL saved_version+1
```

**Hitelességi szabály.** Mentett állapotban PostgreSQL a hiteles forrás. Mentetlen szerkesztés alatt a Python Stage workspace a hiteles szemantikus és layout állapot. A Fabric világ ennek szerkesztői vetülete; eltérés esetén nem írhatja felül automatikusan a Python workspace-et.

## 9. Validációs státuszok szétválasztása

Három egymástól független dimenzió szükséges:

1. `edge_validation_status`: `CHECK_REQUIRED | OK | ERRORS` – a jelenlegi `EdgeIndicatorStatus` jó alap.
2. `program_validation_status`: legalább `NOT_CHECKED | VALID | INVALID | NOT_APPLICABLE` – teljes Stage programstruktúra, későbbi validátor felelőssége.
3. `save_status`: `CLEAN | DIRTY` – a DB snapshothoz viszonyított mentettség.

Javasolt falitábla-leképezés:

| Domainérték | Felirat |
|---|---|
| CHECK_REQUIRED | Élek: ellenőrzendő |
| OK | Élek rendben |
| ERRORS | Élek: hibásak |

**Megfigyelt tény.** A jelenlegi `GraphWorkspaceState.graph_status` csak DRAFT/VALID, és a hibamentes aktuális graph reportból VALID lesz. Ez nem hordoz külön programstrukturális státuszt.

**Javaslat.** A minimális későbbi bővítés külön `program_validation_status` és opcionális `program_validated_revision`; a meglévő `GraphStatus` jelentését pedig dokumentáltan graph/kapcsolati státuszra kell szűkíteni vagy egyértelműbben átnevezni. A SAVE státusz változatlanul külön maradjon.

## 10. Kockázatok és nyitott döntések

| Prioritás | Kockázat | Jelenlegi tényállás | Következmény | Felelős réteg | Következő döntés/teszt |
|---|---|---|---|---|---|
| P0 | Generikus report → teljes Stage-VALID | Hibamentes aktuális report `GraphStatus.VALID` értéket ad | UI vagy SAVE túl erős készültséget állíthat | domain státuszmodell + alkalmazási réteg | három státuszdimen­zió véglegesítése |
| P0 | Snapshot SAVE atomikussága | csak blokkok mentése atomi; graph/layout nincs DB-ben | blokk és gráf eltérő verzióba kerülhet | repository/DB | teljes graph snapshot tranzakciós teszt |
| P0 | Világ–Python Layout eltérés | Fabric világ és Python graph még nincs összekötve | ghost wire, elvesző vagy téves route | adapter + reconciliation | authority és repair protokoll ADR |
| P0 | Régi SAVE növeli working revisiont | `StageService.save()` `revision += 1` | mentés után kliensparancsok indokolatlanul stale-ek | StageService adapter | revíziószemantika egységesítése teszttel |
| P1 | Két párhuzamos binding | domain expected revisiont ellenőriz; runtime adapter még nincs | két kérés közötti race | Stage lock + domain | egy lock alatt adapter/concurrency teszt |
| P1 | Elavult kliensparancs | domain elutasítja, HTTP szerződés nincs | kliens téves világképet rajzolhat | HTTP adapter + Fabric | 409 + refresh/retry UX teszt |
| P1 | Két játékos ugyanazon Stage-en | process-local RLock van, owner-only módosítás van | több process vagy nézők esetén bizonytalan viselkedés | StageService/session | kollaborációs policy és lock scope ADR |
| P1 | Owner-only SAVE | StageService ellenőrzi a payload UUID-t, erős hitelesítés nincs | hamisított localhost kérés menthet | transport/auth + StageService | Fabric–Python shared trust vagy socket policy |
| P1 | Fizikai wire kiütése, logikai edge megőrzése | destroyer explicit confirmationkor edge-et is töröl; ADR szerint puszta route-break nem feltétlen edge-delete | véletlen blokktörés szemantikai adatvesztéshez vezethet | interaction workflow | break → BROKEN route vagy destroy preview UX döntés |
| P1 | CROSS külön kezelés | domain csatornánként helyes; Fabric blokkstate nincs | játékbeli break nem tudja, melyik ágat célozza | Fabric UX + DTO | csatornakiválasztás prototípus |
| P1 | Reload utáni edge-ID | domain stabil, DB-tábla nincs | újratöltéskor ID-k elveszhetnek | persistence adapter | SAVE/LOAD round-trip ID teszt |
| P2 | Fabric–Python kommunikációs hiba | async HTTP hibaüzenet van; idempotens wire request nincs | világ és workspace féloldalas maradhat | request protocol + renderer | request_id, retry, reconciliation teszt |

Egy domainvédelem önmagában egyik runtime-kockázatot sem minősíti megoldottnak.

## 11. Következő implementációs műszakok

1. **CK-03/A – GraphWorkspaceAdapter szerződés és in-memory adaptertesztek.** Stage-blokkok ↔ GraphNode leképezés, owner/revision/lock határ, státusz DTO; runtime endpoint nélkül.
2. **CK-03/B – Additív graph snapshot migráció és repository round-trip teszt.** Stabil edge/route ID, teljes tranzakció, saved_version CAS.
3. **CK-03/C – Verziózott Python HTTP graph DTO-k.** Wire/bind/preview/confirm/diagnostics; Fabric nélkül.
4. **CK-03/D – Izolált Fabric CK Wire blokk és eseményprototípus.** Még nem teljes szemantikus integráció; callback és CROSS UX bizonyítása.
5. **CK-03/E – End-to-end localhost integráció és reconciliation.** Python elfogadás után kizárólag Fabric világírás, hiba- és retry-forgatókönyvekkel.

## 12. Go/No-Go javaslat

### Döntés

**NO-GO** a teljes, perzisztens Fabric CK Wire funkció közvetlen implementálására.

Indokok:

- nincs StageWorkspace ↔ GraphWorkspaceState adapter;
- nincs graph/wire HTTP szerződés;
- nincs graph/layout perzisztencia;
- a két revíziószemantika eltér;
- a graph-valid és program-valid státusz nincs elkülönítve;
- nincs világ/workspace reconciliation protokoll.

**GO** a CK-03/A kis műszakra: adapterhatár és DTO-k véglegesítése, kizárólag in-memory tesztekkel. Ettől a CK-02 domain lezártnak tekinthető, de a runtime integráció még nem.

## Függelék A – Integrációs invariánsok

1. `working_revision` csak tartalomváltozáskor nő.
2. `saved_version` csak sikeres, atomi DB SAVE után nő pontosan eggyel.
3. SAVE nem tesz DRAFT gráfot VALID-dá, és nem évültet el változatlan tartalomhoz tartozó reportot.
4. Minden módosító kliensparancs expected revisiont hordoz.
5. Jogosultság és Stage-lock az alkalmazási réteg felelőssége; a graph-domain tiszta marad.
6. A PostgreSQL mentett snapshot, a Python mentetlen workspace, a Fabric pedig a fizikai vetület hiteles forrása a saját határán.
7. Fabric csak elfogadott Python-válasz alapján ír világot.
8. Egy SAVE tranzakció együtt ment blokkot, edge-et, route-ot és wire layoutot.
9. Edge-ID route-átrajzolás és SAVE/LOAD után stabil.
10. Preview confirmation mindig aktuális state-ből újraellenőrzött.
