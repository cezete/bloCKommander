# CK-08/B2 – Verziózott Block Editor HTTP API

Dátum: **2026-11-11**. Kiinduló branch/HEAD: **main / 66610d06e232717f02f9c9b1f87e48133ff72861**, tiszta working tree. A [B1/B domain](CK-08B1B-python-block-editor-domain.md) már commitolva volt. E feladat Python HTTP/transport implementáció; nincs commit, deploy, migráció vagy Fabric-módosítás.

## Végpontok és adatút

```http
GET  /api/v1/programs/{program_id}/stages/{stage_id}/blocks/{instance_id}/editor?player_uuid={player_uuid}
POST /api/v1/programs/{program_id}/stages/{stage_id}/blocks/{instance_id}/editor/apply
```

API-verzió: **ck-block-editor-v1**. A [block_editor_http.py](../python-service/src/hangar/block_editor_http.py) strict request DTO-t készít, majd kizárólag a meglévő `BlockEditorService.get/apply` belépési pontját hívja. Nincs második validátor, workspace, lock/CAS-algoritmus vagy persistence. A [ck_webhook.py](../python-service/src/ck_webhook.py) közös ProgramService példányához kapcsolt editor egyszer jön létre; a két endpoint név szerint bekerül a preflightba.

## Hitelesítés, context és trust boundary

GET és APPLY egyaránt kötelezően használja:

```http
X-BlockCommander-Graph-Secret: <backend shared secret>
X-BlockCommander-Hall-Id: 00000000-0000-0000-0000-000000009004
X-BlockCommander-Program-Id: 00000000-0000-0000-0000-000000009002
X-BlockCommander-Working-Copy-Id: <az adott Hall hiteles working-copy UUID-ja>
```

A példák UUID-i teszt-/illusztratív azonosítók; nem production mount kiválasztására szolgálnak.

Sorrend: backend-auth → URL UUID-k → mindhárom explicit context-header → strict query/body DTO → meglévő context resolver → domain owner/instance/type/kompatibilitás/CAS. Hibás auth mellett nincs identity lookup vagy body-parsing. Az editor akkor sem enged legacy contextet, ha a többi végpont számára a legacy compatibility engedélyezett.

A shared secret **a Fabric/backend szerverfolyamatot** hitelesíti, nem egy Minecraft-játékost. A player UUID a hitelesített szerver által továbbított állítás. A Python a Program ownerével összeveti, de a secret birtokosa technikailag bármely player UUID-t állíthat. Ez a meglévő `GraphTransportAuthenticator` trust boundaryja; nem új Web-client vagy böngészős autentikáció.

A B3 szerver ezért a saját hiteles `ServerPlayer` azonosságából építi a kérést; a secret soha nem kerülhet a Minecraft klienshez vagy böngészőhöz. A Python GET query player UUID-ja és az APPLY body player UUID-ja nem egy másik, független hitelesítési mechanizmus. Nincs új ACL, meghívás vagy Web-auth.

A target a URL-ben szerepel. Hall/Program/WC bodyban vagy queryben: `CONTEXT_LOCATION_INVALID`. A resolver ellenőrzi az URL/header Program-egyezést, az élő Hall–Program–WC mountot és Stage-tagságot. A domain ismét ellenőrzi a Program/Stage/instance azonosságot és ownert. WorkingCopy identity nem izolált tartalmi másolat: a content scope továbbra is SHARED_PROGRAM.

## Request parsing

Az editor a legacy `request.get_json()` helyett saját, kizárólag e két route-ra vonatkozó parsingot használ:

- APPLY: JSON content type, UTF-8, legfeljebb **64 KiB body**. A korlát a két rövid szövegmezőhöz és envelope-hoz bőséges, a parser erőforrásigényét célzottan korlátozza; nem globális Flask-limit.
- Csak objektum body. Hibás JSON, hibás UTF-8, túlzott nesting/decoder recursion: strukturált elutasítás.
- Duplikált JSON-kulcs bármely mélységben elutasított, a parser nem választ első/utolsó értéket.
- JSON számként csak signed 64-bit integer támogatott. Float, exponent, NaN/Infinity és túl nagy integer `INVALID_NUMBER`. Ez a jelen editor DTO transportkorlátja, nem CK numerikus típusrendszer.
- Kötelező mezők: `api_version`, `request_id`, `player_uuid`, `schema_id`, `schema_version`, `expected_stage_workspace_epoch`, `expected_working_revision`, `changes`. Ismeretlen top-level kulcs tiltott.
- API-verzió valódi string; ismeretlen stringverzió 409, hibás típus 400. Nincs fallback.
- URL, player, request és epoch UUID: stringként teljes kötőjeles 8-4-4-4-12 hexalak; nagybetű elfogadható, a válasz kanonikus UUID-t tartalmaz. Nincs coercion vagy compact/braced UUID.
- Sémaverzió pozitív egész; working revision nemnegatív egész. Boolean és lebegőpontos érték nem egész.
- `changes` nem üres objektum. A parser nem értelmezi vagy normalizálja a mezőértékeket: ismeretlen módosítási kulcs, label/description típus, null, strip, hossz és Unicode a B1/B domain felelőssége.
- GET csak egyetlen `player_uuid` queryt fogad, body nélkül. Duplikált vagy ismeretlen query kulcs elutasított. APPLY nem fogad querymezőt.

## GET példa

```http
GET /api/v1/programs/00000000-0000-0000-0000-000000009002/stages/00000000-0000-0000-0000-000000009003/blocks/00000000-0000-0000-0000-000000005002/editor?player_uuid=00000000-0000-0000-0000-000000009001
```

HTTP 200 példa:

```json
{
  "api_version": "ck-block-editor-v1",
  "status": "ok",
  "target": {
    "program_id": "00000000-0000-0000-0000-000000009002",
    "stage_id": "00000000-0000-0000-0000-000000009003",
    "instance_id": "00000000-0000-0000-0000-000000005002",
    "block_type": "START"
  },
  "schema": {
    "schema_id": "ck.block.START.metadata",
    "schema_version": 1,
    "block_type": "START",
    "fields": [
      {
        "key": "label", "type": "STRING", "required": true, "nullable": false,
        "default": {"kind": "NONE"},
        "constraints": {"min_length": 1, "max_length": 32, "length_unit": "UNICODE_CODE_POINT"},
        "normalization": {"trim": "PYTHON_STRIP_V1", "unicode_normalization": "NONE", "empty_to_null": false},
        "presentation": {"label": "Felirat", "order": 10},
        "readable": true, "editable": true
      },
      {
        "key": "description", "type": "STRING", "required": false, "nullable": true,
        "default": {"kind": "NONE"},
        "constraints": {"min_length": 0, "max_length": 256, "length_unit": "UNICODE_CODE_POINT"},
        "normalization": {"trim": "PYTHON_STRIP_V1", "unicode_normalization": "NONE", "empty_to_null": true},
        "presentation": {"label": "Leírás", "order": 20},
        "readable": true, "editable": true
      }
    ]
  },
  "schema_ref": {"schema_id": "ck.block.START.metadata", "schema_version": 1},
  "values": {"label": "Indulás", "description": null},
  "permissions": {"read": true, "apply": true, "reason_codes": []},
  "version_context": {
    "stage_workspace_epoch": "10000000-0000-4000-8000-000000000007",
    "working_revision": 12,
    "stage_saved_version": 3,
    "program_saved_version": 5,
    "stage_dirty": false,
    "program_dirty": false
  },
  "transport_context": {
    "mode": "explicit",
    "hall_id": "00000000-0000-0000-0000-000000009004",
    "program_id": "00000000-0000-0000-0000-000000009002",
    "working_copy_id": "10000000-0000-4000-8000-000000000006",
    "stage_id": "00000000-0000-0000-0000-000000009003",
    "content_scope": "SHARED_PROGRAM"
  }
}
```

STOP ugyanilyen alakú, a saját `ck.block.STOP.metadata` sémájával és authoritative értékeivel. A teljes schema, values, permissions és version context a domain egy konzisztens zárolt nézetéből származik; a context-echo a meglévő resolver ellenőrzött mountjából, nem ellenőrizetlen kliensmásolatból.

## APPLY és RESULT

Az előző target `/editor/apply` útvonalára:

```json
{
  "api_version": "ck-block-editor-v1",
  "request_id": "10000000-0000-4000-8000-000000000008",
  "player_uuid": "00000000-0000-0000-0000-000000009001",
  "schema_id": "ck.block.START.metadata",
  "schema_version": 1,
  "expected_stage_workspace_epoch": "10000000-0000-4000-8000-000000000007",
  "expected_working_revision": 12,
  "changes": {"label": "Új felirat", "description": null}
}
```

Siker és no-op HTTP 200. A domain `EditorApplyResult.as_dict()` teljes detached editor-nézetét kapjuk vissza: target, schema, schema_ref, values, permissions, version_context; továbbá `request_id` és `modified`, valamint a HTTP `api_version`, `status` és ellenőrzött `transport_context`.

A példában valódi változáskor `values.label="Új felirat"`, `description=null`, `modified=true`, revision 13, Stage/Program dirty true. Saved version nem nő APPLY-tól. No-opnál a domain aktuális revision/dirty/saved-version állapota változatlan; CAS no-op előtt is kötelező. Két mező egyetlen revisionváltozás. APPLY után GET az elfogadott értékeket adja.

HTTP 422 mezőhiba példa:

```json
{
  "api_version": "ck-block-editor-v1",
  "status": "error",
  "request_id": "10000000-0000-4000-8000-000000000008",
  "error_code": "VALIDATION_FAILED",
  "message": "The patch was not applied.",
  "diagnostics": [
    {"field": "label", "code": "STRING_TOO_LONG", "message": "Invalid label: STRING_TOO_LONG.",
     "details": {"max_length": 32, "actual_length": 33}}
  ]
}
```

Request ID csak már szintaktikailag ellenőrzött APPLY UUID-ból kerül a válaszba. Hibás auth, parse-elhetetlen body vagy hibás request ID mellett nem garantálható korreláció; nyers értéket nem echozunk. Nincs idempotency receipt, tartós műveleti napló vagy retry.

## Hibafordítás

| HTTP | Kategóriák |
|---|---|
| 400 | Hibás JSON/UTF-8/UUID/DTO, duplikált kulcs/query, INVALID_NUMBER, MISSING_FIELD, UNKNOWN_FIELD, EMPTY_CHANGES, hibás API-verziótípus |
| 401 | Meglévő INVALID_GRAPH_AUTH |
| 403 | Domain FORBIDDEN |
| 404 | Domain STAGE_NOT_FOUND/INSTANCE_NOT_FOUND és a meglévő resolver hiányzó mount/Hall hibái |
| 409 | CONTEXT_REQUIRED/mismatch; nem támogatott API/séma, SCHEMA_MISMATCH, stale epoch/revision, RELOAD_REQUIRED, inkompatibilis snapshot/típus/Stage identity |
| 413 | Editor body >64 KiB vagy alacsonyabb infrastruktúra-limit |
| 422 | Domain VALIDATION_FAILED, változatlan meződiagnosztikákkal |
| 503 | GRAPH_AUTH_NOT_CONFIGURED, CONTEXT_REPOSITORY_UNAVAILABLE, repository-elérhetetlenség |
| 500 | Sanitizált INTERNAL_ERROR; ismeretlen domainhibakód sem válik automatikusan sikeres/400 válasszá |

A meglévő auth/context hibakódok megmaradnak, de e végpontok mindig editor API-envelope-ot adnak. A resolver történeti elnevezéseit (például hiányzó Stage feloldásakor HALL_NOT_FOUND) sem neveztük át önkényesen. Auth/owner hibában nincs values, snapshot vagy context-echo; váratlan hibában nincs traceback/SQL/DSN a response-ban.

## Context echo és B3 fencing

A közös success-response hook a feloldott context `as_dict()` értékét adja hozzá. Az editor-nézetben nincs teljes `stage`/`state` snapshot, ezért a hook nem indít Stage/world-projekciót. A válasz targetje a domain ellenőrzött targetje, a context a resolver által ellenőrzött H/P/WC/Stage identity.

Ez a válasz **nem igazolja a Fabric Graph-projekció frissességét**. A metadata revision szabályosan nő; B2-ben nincs Graph-reconciliation vagy világírás.

B3 követelményei:

- Player UUID a Fabric szerver hiteles játékosobjektumából. A kliens nem választhat új player/context/epoch/revision adatot.
- Szerveroldali edit-session kötés: player/connection, world/Hall/Program/WC/Stage/instance/type, schema és megnyitási Stage epoch/revision; lifecycle/navigation generáció.
- Pending alatt nincs második APPLY. Mezőhiba mellett a bevitel megmarad; conflict és UNKNOWN külön állapot.
- Hall/Stage-váltás/reconnect érvényteleníti a sessiont. Késő vagy felcserélt választ nem szabad az új kontextusba vetíteni.
- Sikeres APPLY után szükség szerint authoritative Stage/Graph GET-reconciliation; a mezőválasz alapján régi Graph snapshot nem nyilvánítható synchronizednak.
- Domainben elfogadott változás + elutasított projekció nem új APPLY indoka. Timeout után UNKNOWN és GET; nincs automatikus APPLY retry vagy exactly-once ígéret.
- Az Alkalmazás nem Program-SAVE. SAVE, restart és reconnect meglévő szerződése megmarad.

## Tesztek és eredmények

Repository gyökeréből, helyi Python venvvel:

```powershell
$env:PYTHONPATH="$PWD\python-service\src;$PWD\python-service;$PWD\python-service\tests"
python-service/.venv/Scripts/python.exe -B -m unittest test_block_editor_http test_program_http_api test_transport_context test_webhook test_graph_http_api -q
python-service/.venv/Scripts/python.exe -B -m unittest discover -s python-service/tests -q
& python-service/tests/integration/postgres/run-tests.ps1 -Suites block_editor_http,block_editor,program_http,stage_service,transport_context
& python-service/tests/integration/postgres/run-tests.ps1 -Suites block_editor_http
git diff --check
```

| Végső ellenőrzés | PASS | FAIL | BLOCKED |
|---|---:|---:|---:|
| Új HTTP/transport tesztek | 30 | 0 | 0 |
| Célzott HTTP/legacy/context csoport, az előző 30-cal együtt | 69 | 0 | 0 |
| Teljes Python discovery, a célzottakat is tartalmazza | 575 | 0 | 0 |
| Új editor HTTP PostgreSQL integration | 5 | 0 | 0 |
| B1/B editor PostgreSQL regresszió | 5 | 0 | 0 |
| Program HTTP PostgreSQL regresszió | 2 | 0 | 0 |
| StageService PostgreSQL regresszió | 3 | 0 | 0 |
| Transport context PostgreSQL regresszió | 5 | 0 | 0 |

Az öt új PostgreSQL HTTP-tesztet a SAVE előtti persisted-value assertion pontosítása után külön is újrafuttattuk; ezt nem számoljuk újabb öt külön tesztnek. A runner a meglévő B2-T cél Docker-ownership, DB/role/identity-marker ellenőrzése után az elkülönített tesztsémán dolgozott. A már futó tesztadatbázis szolgáltatásához nem nyúltunk; csak átmeneti SSH-tunnel nyílt/zárult. Production DB/WH/Minecraft nem érintett.

Fő lefedés: START/STOP schema/values/context, GET/APPLY auth-paritás és auth-first, explicit mount/WC és URL identity, owner, instance, strict JSON/UUID/number/body-limit, schema/epoch/revision, no-op és atomikus validáció, valódi APPLY → külön Program-SAVE → repository reload, Graph-azonosság és legacy coercion megőrzése. Minden negatív APPLY transportteszt összeveti a snapshot/revision/dirty/saved állapotot, részleges módosítás nélkül. A B1/B 36 tesztje változatlanul a teljes discovery része.

Gradle/Kotlin, Minecraft acceptance és a többi PostgreSQL-suite nem futott: nem része e HTTP-réteg ellenőrzésének. A jelen futás nem bizonyít telepített Fabric editort vagy production-elérhetőséget.

## Kompatibilitás és záróhatár

A B1/B domain/schema és a legacy Stage update szemantikája változatlan. A régi Program/Stage/Graph route-ok, verziók és permisszívebb legacy contextútvonalak megmaradtak; az editor külön, szigorú útvonal. Nincs legacy fallback, automatikus SAVE/PUBLISH, migráció, új blokk, Web-auth vagy Kotlin/Fabric implementáció. A legacy CAS nélküli író később továbbra is felülírhat metaadatot; az új HTTP API ezt nem rejti el.

A GET/APPLY context-preflight a meglévő resolver kérésenkénti mount-ellenőrzése, nem egyetlen elosztott tranzakció a Minecraft navigation és a Python mutáció között. A B3 lifecycle/session fence ezért továbbra is szükséges.

**Minősítés: PASS a CK-08/B2 határán. STOP – projektgazdai felülvizsgálat B3 előtt; nincs commit vagy deploy.**
