# CK-08/B1/B – Python blokkeditor-domain

Dátum: **2026-11-11**. Kiindulás: **main / c6e3f2db7a740b0b2571bcb61ab24f1ff01cb0ea**, tiszta working tree. Felhatalmazás: CK-08/B1/B, Python-domain és célzott tesztek; nincs commit vagy deploy.

## Megvalósított API és felelősségek

- [block_editor_schema.py](../python-service/src/hangar/block_editor_schema.py): immutable START/STOP sémák és STRING-validátor, HTTP-/repository-függés nélkül.
- [block_editor.py](../python-service/src/hangar/block_editor.py): typed target, schema reference, values, permissions, version context, GET/APPLY command/result, strukturált hiba. Nincs saját workspace-cache vagy session.
- [ProgramService.locked_stage](../python-service/src/hangar/program.py): aktív, owner-ellenőrzött aggregate és Stage közös zárolt elérése, Program → Stage sorrendben. A lock alatt visszaadott mutable objektumok nem hagyhatják el a hívó kritikus szakaszát; az editor detached eredményt ad.

Belépési pontok:

```python
from uuid import UUID, uuid4
from hangar.block_editor import (
    BlockEditorService, EditorTarget, EditorApplyCommand,
)

editor = BlockEditorService(programs)  # A már használt ProgramService példány.
target = EditorTarget(UUID(program_id), UUID(stage_id), UUID(instance_id))
current = editor.get(target, UUID(player_uuid))
command = EditorApplyCommand(
    target=target,
    player_uuid=UUID(player_uuid),
    request_id=uuid4(),
    schema_ref=current.schema_ref,
    expected_stage_workspace_epoch=current.version_context.stage_workspace_epoch,
    expected_working_revision=current.version_context.working_revision,
    changes={"label": "Indulás", "description": None},
)
result = editor.apply(command)
```

Az `as_dict()` JSON-kompatibilis detached másolatot ad. A domain DTO nem tartalmaz HTTP státuszkódot, API-envelope-ot, transport-contextet vagy hitelesítési titkot. A request ID kizárólag korreláció; nincs idempotency receipt/replay-cache. Az APPLY után külön, explicit Program-SAVE szükséges.

## Séma és validáció

Sémaazonosítók: `ck.block.START.metadata`, `ck.block.STOP.metadata`; verzió **1**. Stabil mezők: `label`, `description`, STRING; megjelenítési sorrend 10/20. A séma required/nullable, hossz-, Python-strip-, Unicode- és megjelenítési metaadatokat szolgáltat. Editor-default nincs (`default.kind=NONE`); a létrehozási katalógus alapértékei változatlanok.

- `label`: valódi string, nem null, Python `strip()` után 1–32 Unicode-kódpont.
- `description`: valódi string vagy null, strip után legfeljebb 256 kódpont; üres/whitespace-only → `None`.
- Hiányzó mező változatlan marad; explicit description-null töröl. Nincs coercion, NFC/NFKC, belső whitespace- vagy betűméret-átírás.
- NUL és Python stringben lévő surrogate kódpont elutasítandó még normalizálás előtt. Érvényes JSON surrogate escape-párból dekódolt Unicode skalár nem ilyen hibás Python string.
- Üres patch, ismeretlen kulcs, nem támogatott típus/séma elutasítandó. Mezőhibák a sémasorrendben, stabil mezőkulccsal és hibakóddal érkeznek.
- A validátor előállítja az egész normalizált patch-et; hiba esetén nincs módosítás. A meglévő immutable blokk `replace` útján kap jelöltértéket, csak ezután történhet snapshotcsere.

A label/description a meglévő [StageBlockSnapshot](../python-service/src/hangar/graph/snapshot.py) mezője maradt. Nincs `parameters.label`, JSONB-tároló vagy új programnyelvi típusrendszer. A [012](conception/012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md) nyitott szemantikáját nem oldja fel az editor.

## Zárolás, identity és CAS

GET és APPLY a tényleges közös ProgramService/StageService objektumokon dolgozik. Program-lock alatt történik az aktív állapot, owner és Stage-tagság ellenőrzése/betöltése; utána Stage-lock védi a blokkolvasást, CAS-t, módosítást és detached eredményképzést.

APPLY-sorrend: Program/Stage feloldás és owner → instance/START–STOP típus → schema ID/verzió → kompatibilitás → Stage epoch → working revision → patch-validáció → snapshotcsere/no-op → eredmény. Hiányzó instance megelőzi az epoch-hibát. A hibasorrend determinisztikus; no-op előtt is kötelező a CAS.

Két mező változása egyetlen `with_content_change` hívás: revision +1, Stage dirty, Program aggregate dirty. Változatlan normalizált értéknél ugyanaz a snapshot és minden dirty/saved-version érték megmarad. A Program-validáció változáskor NOT_CHECKED lesz a meglévő snapshot-szabály alapján.

A kompatibilitási kapu jelenleg az ismert Graph snapshot v1-et, aktuális portkatalógust és a meglévő `compatible_for_graph_mutation` jelzőt követeli meg. Ez konzervatív kapu: a legacy v0 és a meglévő jelző szerint inkompatibilis workspace az új editorban elutasított, miközben a legacy update változatlan marad. A gráf topológiai validációs hibáját külön nem követeli hibamentesnek. B2 ne alakítsa át ezt csendben alternatív írási úttá.

## Hibakategóriák

`BlockEditorError.code`, szöveges üzenet és immutable diagnosztikák; `FieldDiagnostic` mező/kód/üzenet/részletek. A HTTP-leképezés B2 feladata.

- Identity/command: `INVALID_ID`, `INVALID_TARGET`, `INVALID_COMMAND`, `INVALID_SCHEMA`, `INVALID_REVISION`.
- Jogosultság/lifecycle: `FORBIDDEN`, `RELOAD_REQUIRED`.
- Target/kompatibilitás: `STAGE_NOT_FOUND` (hiányzó Program vagy nem tag/hiányzó Stage), `INSTANCE_NOT_FOUND`, `STAGE_IDENTITY_MISMATCH`, `UNSUPPORTED_BLOCK_TYPE`, `SNAPSHOT_INCOMPATIBLE`.
- Séma/CAS: `SCHEMA_MISMATCH`, `UNSUPPORTED_SCHEMA_VERSION`, `STALE_WORKSPACE_EPOCH`, `STALE_WORKING_REVISION`.
- Patch: `INVALID_CHANGES`, `EMPTY_CHANGES`, `UNKNOWN_FIELD`, `VALIDATION_FAILED`.
- Meződiagnosztika: `INVALID_TYPE`, `NULL_NOT_ALLOWED`, `FIELD_REQUIRED`, `STRING_TOO_LONG`, `INVALID_CHARACTER`, `INVALID_UNICODE`.

## Perzisztencia és Graph

Új SQL/snapshotformátum nincs. A meglévő [Program-SAVE](../python-service/src/hangar/program.py), [repository](../python-service/src/hangar/repository.py) és [Graph adapter](../python-service/src/hangar/graph/adapter.py) menti és megőrzi a metaadatot. A legacy `StageService.update` kódja változatlan, beleértve a `str(value).strip()` viselkedést.

Tesztelt lánc: APPLY → külön Program-SAVE → repository → új servicepéldány → GET. SAVE nélkül az új service a mentett értéket kapja. Mentési hiba mellett a dirty working copy megmarad. Új service epochja eltér; régi command elutasított.

Az APPLY nem változtat instance/GraphNode/GraphEdge/route/WireCell/port/tartalmazási identitást vagy ownert. A revision szabályosan nő: régi Fabric Graph-projekció ettől elavulhat. A tesztek külön Graph adapter körön és repository reloadon is ellenőrzik a metaadatot és az azonosságokat.

## Tesztek és eredmények

A repository gyökeréből futtatott parancsok; a környezet nem használ production DB-t:

```powershell
$env:PYTHONPATH="$PWD\python-service\src;$PWD\python-service;$PWD\python-service\tests"
python-service/.venv/Scripts/python.exe -B -m unittest test_block_editor_schema test_block_editor -q
python-service/.venv/Scripts/python.exe -B -m unittest discover -s python-service/tests -q
& python-service/tests/integration/postgres/run-tests.ps1 -Suites block_editor,program_service,program_save,stage_service
git diff --check
```

| Csomag | Futtatott teszt | Eredmény |
|---|---:|---|
| Új séma/validátor unit | 11 | PASS |
| Új editor-domain unit | 18 | PASS |
| Új szolgáltatás/adapterszintű integráció fake repositoryval | 7 | PASS |
| Teljes Python discovery, az előző 36-ot is tartalmazza | 545 | PASS |
| Új PostgreSQL editor integration | 5 | PASS |
| PostgreSQL ProgramService regresszió | 1 | PASS |
| PostgreSQL Program-SAVE regresszió | 10 | PASS |
| PostgreSQL StageService regresszió | 3 | PASS |
| `git diff --check` | — | PASS |

A PostgreSQL suite a meglévő B2-T runnerrel, Docker-ownership és DB/role/identity-marker ellenőrzés után futott. A sandbox első SSH-próbája blokkolt volt; az engedélyezett futás a már futó, elkülönített tesztadatbázist használta. Nem indítottunk vagy indítottunk újra DB-/WH-/Minecraft-szolgáltatást. A runner átmeneti SSH-tunnelt nyitott és zárt.

A teljes discovery első próbájából hiányzott a `python-service` gyökér a PYTHONPATH-ból (`tests.*` importhibák). A fenti korrigált parancs 545/545 PASS. A kezdeti új tesztfixture a tágabb fake katalógus CLASS/INTERFACE elemeivel és két hibás fixture-feltételezéssel elbukott; a fixture javítása után 36/36 PASS. Nem production-kompatibilitási ellenőrzés lazításával javítottuk.

Nem futott Gradle/Kotlin vagy Minecraft acceptance; nem része e Python-domain megbízásnak. A többi PostgreSQL-suite nem futott ebben a körben. A mentési hibás új teszt injektált repository-hibát használ; a meglévő Program-SAVE PostgreSQL regresszió valódi SQL-rollbacket is vizsgál.

## B2 HTTP-adapter átadás

- Az API-verzió/envelope, route-ok, szigorú JSON-parse, UUID-konverzió és HTTP-hibafordítás új feladat; most nincs endpoint.
- Backend-auth az identity-adat feltárása előtt; explicit Hall/Program/WorkingCopy context és response-echo bekötése a meglévő preflightba. A domain target nem tartalmaz Hall/WC-t: az adapter igazolja a mountot; ezek nem külön tartalmi workspace-ek.
- Az owner UUID hiteles kliens-/szerverkontextusból származzon. Nem készült meghívásos ACL vagy Web-auth.
- GET teljes schema/values/permissions/version-context, APPLY request-ID és authoritative eredmény; az `as_dict` csak detached domainadat, nem önmagában HTTP-szerződés.
- Timeout = UNKNOWN; GET reconciliation, nincs automatikus APPLY-retry vagy pontosan-egyszer garancia. A request ID nem idempotencia-kulcs.
- A legacy route megmarad, így később érkező legacy CAS nélküli író ellen nem ígérhető teljes konkurenciavédelem.

## B3 Fabric átadás és korlátok

- Új szerveroldali edit-session: player/connection, world/Hall/Program/WC/Stage/instance/type, schema, megnyitási epoch/revision és lifecycle/navigation generáció. Ez külön mechanizmus a Python CAS-tól.
- Alkalmazás gomb, pending és dupla küldés tiltása, mezőhibák mellett bevitel megőrzése; conflict/UNKNOWN külön állapot. Késő open/apply-válaszok eldobása, Hall/Stage-váltás és reconnect sessionérvénytelenítés.
- Unicode-kódpontszámítás; a Kotlin UTF-16 hossz és trim nem lehet eltérő domainvalidátor. Nincs új világfelirat.
- Az editor-eredmény nem teljes Stage/Graph snapshot. Sikeres metadata-edit után a megváltozott revisiont authoritative Stage/Graph lekérdezéssel kell reconciliationbe vinni. Régi Graph snapshotot nem lehet csak a mezőválaszból synchronizednak nyilvánítani.
- Domainben elfogadott módosítás + stale projection nem újraküldési ok; az eredeti Stage-ben marad, az új kontextusba nem vetíthető. APPLY nem Program-SAVE.
- Egy webhook-processz meglévő közös workspace-életciklusa az alap; nincs elosztott lock, perzisztens session vagy draft-autosave.

**Minősítés: PASS a B1/B Python-domain határán. STOP: külön projektgazdai döntés kell B2/B3 folytatáshoz és commit/deploy művelethez.**
