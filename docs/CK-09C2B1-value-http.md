# CK-09/C2/B1 – LET HTTP és CONTROL-kompatibilitás

A Python webhook a közös Program-munkapéldányban kezeli a LET-et és saját üres
`VALUE_BODY` Stage-ét. Az explicit Program-wide SAVE menti együtt a konfigurációt,
a Stage-et és a tulajdonosi linket. A Fabric szerver marad a világ egyetlen írója.

## Útvonalak és DTO-k

A táblázatban `P` az `/api/v1/programs/{program_id}/stages/{stage_id}`, `S` az
`/api/v1/stages/{stage_id}`. A VALUE-műveletek `ck-value-v1` verziójúak.
Mindegyikhez kell az `X-BlockCommander-Graph-Secret`, valamint a meglévő
`X-BlockCommander-Hall-Id`, `X-BlockCommander-Program-Id` és
`X-BlockCommander-Working-Copy-Id` hármas. A webhook ellenőrzi a mountot, a
Stage tagságát és a Hall, illetve a Program tulajdonosát. A `player_uuid`-t
a hitelesített szerver adja. Az URL-ben szereplő blokk UUID a cél-Stage-hez tartozzon.

| Művelet | Kérés | Sikeres válasz többletmezői |
|---|---|---|
| `POST S/blocks` | Közös írási mezők, `type_key: "LET"`, `coordinate_space: "STAGE_RELATIVE"`, egész `x/y/z`, kardinális `orientation` | 201, `let` és fizikai `stage` projekció. A blokk és a WireCell is foglalja a celláját. |
| `GET P/blocks/{instance_id}/value` | `player_uuid` query | 200, `let`. A lekérés nem változtatja meg a konfigurációt vagy a hierarchiát. |
| `PATCH P/blocks/{instance_id}/value` | Közös írási mezők és `target_id` deklaráció-UUID | 200, a jóváhagyott `let`. Ugyanaz a cél friss basison no-op. |
| `POST P/children/ensure` | Közös írási mezők, `owner_role: "LET_RHS"`, `program_block_instance_id` | 201 új gyereknél, 200 meglévő vagy reaktivált gyereknél. `created`, `link` és `child` Stage-összefoglaló. |
| `POST S/blocks/{instance_id}/delete` | Közös írási mezők | 200, `deleted_instance_id`, fizikai `stage` projekció és a `RETAINED` `link`, ha a LET-nek volt gyereke. |

A `let` mezőben `instance_id`, `type_key: "LET"`, `semantic_config`, `target_id`
és `rhs_link` van. A konfiguráció `{}` vagy `{"version":1,"target_id":"<UUID>"}`.
A `target_id` beállítás előtt null, az `rhs_link` aktív kapcsolat hiányában null.
A nyugták `request_id`-t adnak vissza. Minden sikeres VALUE-válaszban szerepel a
transport context, a Program- és Stage-UUID, a Stage kind, a Stage epoch/revision,
a Program epoch/hierarchy revision, a két mentett verzió, a Stage és a Program
dirty állapota, valamint a teljes `resolution_basis` és a digestje.

A `link` mezői `parent_stage_id`, `program_block_instance_id`, `child_stage_id`,
`owner_role` és `state`. A régi PROGRAM ensure kérésben az elhagyott vagy explicit
`PROGRAM_BODY` role a meglévő PROGRAM-szerződést használja. A LET-hez explicit
`LET_RHS` és a VALUE-írási feltételek kellenek. Egy LET-nek legfeljebb egy aktív
gyereke van. Az ismételt ensure ugyanarra az UUID-ra mutat. A frissen létrehozott
`VALUE_BODY` üres. LET törléskor a domain eltávolítja a kapcsolódó edge-eket és
route-okat, a Stage és a deklarációk megmaradnak.

## ValueEditBasis és deklarációválasztás

A LET műveletei a `ROOT` és `PROGRAM_BODY` kontroll Stage-ek C1-kompatibilis,
teljes gráfsnapshotjain érhetők el. Régi, csak blokkokat tartalmazó Stage esetén a
VALUE művelet `INCOMPATIBLE_SNAPSHOT` választ ad. A közös írási mezők a következők.

```json
{
  "api_version": "ck-value-v1",
  "player_uuid": "<UUID>",
  "request_id": "<UUID>",
  "expected_stage_workspace_epoch": "<UUID>",
  "expected_working_revision": 12,
  "expected_resolution_basis": "ck-resolution-basis-v1:<64 kisbetűs hex karakter>"
}
```

A `GET P/symbols?player_uuid=…` a meglévő szerződés szerint ad deklaráció-UUID-t,
nevet, típust, mutabilityt (`MUTABLE` = VAR, `IMMUTABLE` = VAL), deklaráló scope-ot,
`LOCAL`, `INHERITED` vagy `SHADOWED` eredetet, teljes basist és lapozási tokent.
Az első LET elhelyezéséhez a symbols `version_context` és
`resolution_basis_digest` mezői használhatók. Egy megnyitott LET-nél a symbols
lap és a konfiguráció GET azonos basison értelmezhető együtt. A lapozó token
elavulása `STALE_PAGE`. Íráskor Python a közös Program-lock alatt újrafeloldja a
teljes láncot, ellenőrzi a digestet és Stage-verziót, majd a domain az aktuális
`ValueEditBasis`-t és a kiválasztott UUID elérhetőségét ellenőrzi. Az ősscope
változása a gyermek azonos working revisionje mellett is új basist ad.

## Idempotencia és hibák

A VALUE-írások szigorú, 64 KiB-ra korlátozott JSON objektumot fogadnak. A
folyamathelyi, legfeljebb 1024 nyugtát tároló cache kulcsa a mount, a Stage UUID,
az epoch, a játékos és a request UUID. Az ellenőrzött payload és a művelet
fingerprintje egyezéskor az eredeti HTTP-státuszt és nyugtát adja vissza. Eltérő
payload konfliktus. A cache-ből kiesett vagy más epochhoz tartozó nyugtára nincs
visszajátszási garancia. Az epoch és basis ellenőrzése továbbra is védi az írást.
A későn megérkező nyugta korábbi állapotot hordozhat. Timeoutnál hiteles GET-tel
kell egyeztetni, és új szerkesztéshez új request UUID-t kell adni.

| HTTP | Jellemző hibakód |
|---|---|
| 400 | `INVALID_FIELDS`, `INVALID_UUID`, `INVALID_BASIS`, `DUPLICATE_JSON_KEY` |
| 401 / 403 | `INVALID_GRAPH_AUTH` / `FORBIDDEN` |
| 404 | `BLOCK_NOT_FOUND`, `STAGE_NOT_FOUND` |
| 409 | `STALE_VALUE_BASIS`, `REQUEST_ID_CONFLICT`, `CONTEXT_REQUIRED`, `OCCUPIED_POSITION`, `CONTROL_STAGE_REQUIRED`, `VALUE_HTTP_REQUIRED` |
| 413 | `BODY_TOO_LARGE` |
| 422 | `DECLARATION_NOT_REACHABLE`, `BLOCK_TYPE_MISMATCH`, `BLOCK_OUTSIDE_STAGE`, `INVALID_ORIENTATION` |
| 503 | `REPOSITORY_ERROR` vagy a transzport/hitelesítés elérhetetlenségi hibája |

A VALUE-hibák tartalmazzák az `error_code`-ot, diagnosztikát és
`retry_automatically: false` jelzést. Az értelmezett request UUID hiba esetén is
visszajár. Az általános Stage blokk-CRUD az öt VALUE-típust a VALUE útvonalra
irányítja vagy elutasítja. A régi blokkok a korábbi szerződésükön szerkeszthetők.

## CONTROL és VALUE gráfhatár

A `CONTROL_WITH_LET` a `ck-control-v3` verziózott katalógust egészíti ki a LET
hátul lévő CONTROL `in` és elöl lévő CONTROL `out` portjával. A
`value_schema_version=1` kontroll Stage-eken ugyanaz a katalógus fut betöltésnél,
discovery, Wire placement, cellamódosítás és bind esetén. A PROGRAM továbbra is
port nélküli, a FOR és FUN `UNSPECIFIED`. A tárolt gráf alapformátuma `1`.
A kontroll íróút csak CONTROL edge-et fogad.

A `GET S/graph` a `VALUE_BODY`-t is visszaadja. A kind és a formátumjelző a
`state.stage_kind` és `state.value_schema_version` mezőkben érkezik, valamint
a `control_mutation_allowed` jelzi a CONTROL-íróút elérhetőségét. A
`VALUE_BODY` az olvasási discoveryhez a C1 VALUE-katalógust használja. Az
összes CONTROL Wire-írás és a bind `CONTROL_STAGE_REQUIRED` hibát ad ezen a
kindon. A C2/C-ben a VALUE Wire külön alkalmazási belépőt és a C1
`ValueService` műveleteit használhatja, a meglévő fizikai vetítéshez szükséges
edge-kind és portkulcs már szerepel a gráf DTO-ban.

## SAVE, reconnect és C2/B2

A Program-wide SAVE a meglévő Program SAVE útvonalakon marad. A nyugta és a
Program-status mentett verziókat, workspace-bélyegeket és dirty állapotot ad.
Az üres `VALUE_BODY` és a hiányzó RESULT nem tiltja a SAVE-et. Kontroll Stage
újratöltésekor a mentett LET konfiguráció, CONTROL élek, route-ok és WireCell-ek
azonos domainazonossággal térnek vissza. A REBUILD új Stage-epochot ad. Külön
migráció ehhez a szelethez nem kell. A telepítési előfeltétel a meglévő 012.

A `GET P` visszaadja a konkrét Stage layoutját, verzióit, szülő- és gyermeklinkjeit
és a LET konfigurációját. Explicit mounttal a `GET S/graph` válaszban ugyanaz a
Program-epoch, hierarchy revision, Program mentett verzió és aggregate dirty
szerepel a Graph state Stage-epochja és revisionje mellett. A mentett verziót
és a lokális SAVE-státusz generációt is figyelembe kell venni, mert egy SAVE
working revision növelése nélkül tisztíthatja a munkapéldányt.

C2/B2-ben az élő Fabric registry a ténylegesen megjelenített Stage UUID-jára
kérje le és egyeztesse a Stage-et és a gráfot. A Hall read modelben érkező,
másik kiválasztott Stage külön projekció. Teljes Minecraft-szerverrestart
után a perzisztált Hall selection az induló állapot. A Fabric kliens és szerver
azonos LET DTO/payload verziót használjon. Belépéskor ellenőrizze a
`LET_RHS → VALUE_BODY` linket, visszalépéskor az aktív szülőazonosságot.
Válasz alkalmazása előtt ellenőrizze a contextet, a Stage/blokk UUID-t, az
epochokat és revisionöket, valamint a session-, navigáció- és Graph-lifecycle
generációkat. A C2/B2 tartalmazza a blokkot, a választót, a navigációt és a
Minecraft acceptance-et.
