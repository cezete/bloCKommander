# CK-09/B2/B – Declaration HTTP API

DTO version: `ck-declarations-v1`. This slice implements Python HTTP only.
The B1 [domain service](../python-service/src/hangar/declaration_service.py),
[scope policy](../python-service/src/hangar/scope_resolution.py), migrations and
Program-wide SAVE contract are unchanged.

## Trust and identity

Every route requires `X-BlockCommander-Graph-Secret`, then all three headers:
`X-BlockCommander-Hall-Id`, `X-BlockCommander-Program-Id`,
`X-BlockCommander-Working-Copy-Id`. No legacy fallback. The secret authenticates
the trusted Fabric **server**, never a Minecraft client or browser. That server
must derive `player_uuid` from its authenticated connection. Python additionally
enforces the B1 Program-owner authorization. There is no visitor-edit ACL.

The existing context resolver checks the current mount and Stage membership.
A Stage target is explicit; ROOT and persisted Hall selection are not inferred.
Successful DTOs echo `target` and `transport_context`, including Hall/Program/
WorkingCopy/Stage identity and `content_scope: SHARED_PROGRAM`.

## Routes

Base: `/api/v1/programs/{program_id}/stages/{stage_id}`.

| Method | Suffix | Result |
|---|---|---|
| GET | `/declarations` | Own Stage declarations |
| GET | `/symbols` | LOCAL, INHERITED, SHADOWED symbols |
| GET | `/declaration-editor` | Atomic own + symbols screen snapshot |
| POST | `/declarations` | CREATE, HTTP 201 |
| PATCH | `/declarations/{declaration_id}` | UPDATE, HTTP 200 |
| DELETE | `/declarations/{declaration_id}` | DELETE with JSON receipt, HTTP 200 |

GET query: required `player_uuid`; optional `page` (default `0`) and `page_token`.
GET has no body. Unknown/duplicate query fields are rejected. UUIDs in paths,
queries and bodies must use hyphenated syntax; header identity follows the
existing context resolver. JSON is UTF-8, object-only, duplicate-key safe and
rejects floats/nonfinite numbers and integers outside signed 64-bit range.

## Atomic reads and pages

The adapter loads the shared Program, then holds its existing reentrant lock
through own-list reading, B1 resolution, DTO assembly and JSON serialization.
The B1 service already performs lazy Stage repository reads under this lock;
this uses that supported contract. No network send occurs under the lock.
There is no second resolver or scope cache. Managed mutations and Program SAVE
use the same Program-before-Stage locking order.

Common GET fields:

- `version_context`: `stage_workspace_epoch`, `working_revision`,
  `stage_saved_version`, `program_saved_version`, `stage_dirty`, `program_dirty`.
- `program_workspace_epoch`, `hierarchy_revision`.
- `resolution_basis`: `basis_version`, `program_id`, `query_scope`,
  `policy_version`, Program epoch, hierarchy revision and ordered `scopes`.
  Each scope includes its identity, Stage epoch and revision, nearest to ROOT.
- `resolution_basis_digest`: mutation precondition; distinct from a page token.
- `scope_status`, `scope_error_code`, `capabilities` (owner-only read/resolve/
  create/local-edit availability; never permission to edit inherited symbols).
- `page`: `index`, `size: 16`, `total_rows`, `token`, `has_next`; `rows`.

Row order is deterministic:

1. Own rows: `group: OWN`, declaration sorted by ASCII name, then UUID.
2. Visible LOCAL rows, then INHERITED rows: name, then UUID within each group.
3. SHADOWED rows: B1 name, ancestry distance, then UUID order.

Own endpoint includes group 1; symbols endpoint groups 2–3; editor includes
groups 1–3 as one paged sequence. Thus an own declaration appears both in OWN
and LOCAL sections of the editor snapshot. `total_rows` counts presentation
rows, not unique declarations. A declaration has `declaration_id`, `scope`
(`kind: STAGE`, `owner_id`), `name`, `type_key`, `mutability`. Symbol rows add
`origin_scope`, `distance`, `source`, `can_edit_declaration`, `shadowed_by`
(UUID for SHADOWED, otherwise null). Inherited and shadowed rows are read-only.

Every later page requires the first page's token. Tokens are deterministic
SHA-256 digests over endpoint, full basis, context, workspace versions and
saved/dirty state. A supplied token is checked even for page zero. Any drift,
including parent edits or SAVE without revision change, returns `409 STALE_PAGE`.
An out-of-range page is `400 PAGE_OUT_OF_RANGE`. There is no expiring cursor
cache, silent truncation, or mixed-version successful page assembly. The server
does recompute the full B1 resolution for each page.

RETAINED/detached scopes: own/editor reads preserve own declarations with
`scope_status: UNAVAILABLE`, `scope_error_code: SCOPE_UNAVAILABLE`, null basis/
digest and false resolve/create/edit capabilities. Symbol GET and mutations
return `409 SCOPE_UNAVAILABLE`. Unknown persisted snapshot formats return an
explicit `409 SNAPSHOT_INCOMPATIBLE`, never an empty successful list.

Limits: request 64 KiB; successful JSON response 16 KiB, including context.
Oversize response is `409 RESPONSE_TOO_LARGE`; no partial list is returned.
Very deep scope chains can exceed the response bound even with 16 rows because
the full basis remains explicit. Mutation receipts omit lists/full scope chain
and reserve a conservative 4 KiB bound before CRUD, avoiding a size rejection
after a workspace mutation. Packet envelopes in B2/C must respect their own
total bound: HTTP's 16 KiB is not a guarantee that raw JSON plus a Fabric packet
header fits 16 KiB. Keep the full basis server-side; send bounded typed GUI rows.

## Writes, CAS and receipts

Common mutation JSON fields (all required):

```json
{
  "api_version": "ck-declarations-v1",
  "request_id": "11111111-1111-4111-8111-111111111111",
  "player_uuid": "22222222-2222-4222-8222-222222222222",
  "expected_stage_workspace_epoch": "33333333-3333-4333-8333-333333333333",
  "expected_working_revision": 7,
  "expected_resolution_basis": "ck-resolution-basis-v1:<64 lowercase hex digits>",
  "declaration": {"name": "counter", "type_key": "INTEGER", "mutability": "MUTABLE"}
}
```

The shown digest is a placeholder; use the exact digest returned by GET.
POST adds exactly `declaration` with all three fields. PATCH instead adds
`changes` with a nonempty subset of those fields. DELETE adds neither. Unknown
fields, identity changes, scope changes, values and initializers are rejected.
Types: BOOLEAN, INTEGER, STRING. Mutability: IMMUTABLE, MUTABLE. B1 performs name,
duplicate-name and domain validation. UPDATE preserves UUID/scope; a no-op
preserves revision/dirty. Each actual change increments Stage revision once.

Basis canonical encoding is Python `json.dumps` with `sort_keys=True`,
`separators=(",", ":")`, `ensure_ascii=True`, ASCII bytes; SHA-256 prefixed by
`ck-resolution-basis-v1:`. It covers the versioned full `resolution_basis` DTO.
Scope array order is significant. Clients should retain the opaque returned
digest rather than recomputing it.

Under one Program lock: read current B1 view/resolution, check original Stage
epoch, revision, full basis digest, then invoke B1 CRUD. Parent edits invalidate
the child basis even if the child's own revision did not change. No automatic
CAS refresh or retry. Auth/parser/context/basis/domain rejections do not mutate
the workspace. Repository reads may populate the existing workspace cache.

Receipt: common identity and new `version_context`, `request_id`, `operation`
(CREATE/UPDATE/DELETE), `declaration_id`, declaration (deleted row for DELETE),
`modified`, `previous_version`, `mutation_scope: PYTHON_WORKING_COPY_ONLY`,
`persisted: false`, `request_id_semantics: CORRELATION_ONLY`,
`retry_automatically: false`. No SAVE, REBUILD, rebind or world update happens.
`persisted: false` describes this operation's persistence action, including a
no-op on a previously saved declaration.

Request UUID is correlation only. There is no receipt cache or deduplication.
Lost response means UNKNOWN to Fabric: never resend automatically. Read-only
REFRESH can show current state but cannot prove that specific request succeeded.
Only the existing separate Program-wide SAVE persists declarations.

## Errors

Sanitized error DTO: `api_version`, `status: error`, `error_code`, generic
`message`, `retry_automatically: false`, and `request_id` when safely parsed.
No raw exception text, SQL, stack trace, paths, secret or context is echoed.

| HTTP | Stable codes / cases |
|---|---|
| 400 | INVALID_JSON, DUPLICATE_JSON_KEY, INVALID_UUID, INVALID_FIELDS, INVALID_CHANGES, INVALID_NUMBER, INVALID_PAGE, INVALID_PAGE_TOKEN, UNKNOWN_QUERY_FIELD, DUPLICATE_QUERY_FIELD; strict parser/context syntax codes |
| 401 | INVALID_GRAPH_AUTH |
| 403 | FORBIDDEN |
| 404 | STAGE_NOT_FOUND, DECLARATION_NOT_FOUND, existing missing-mount/context codes |
| 409 | STALE_WORKSPACE_EPOCH, STALE_WORKING_REVISION, STALE_RESOLUTION_BASIS, STALE_PAGE, PAGE_TOKEN_REQUIRED, SCOPE_UNAVAILABLE, SNAPSHOT_INCOMPATIBLE, RELOAD_REQUIRED, CONTEXT_REQUIRED, existing context mismatch codes, UNSUPPORTED_API_VERSION, RESPONSE_TOO_LARGE, WORKSPACE_CONFLICT |
| 413 | BODY_TOO_LARGE |
| 422 | INVALID_DECLARATION_NAME, DUPLICATE_DECLARATION_NAME, UNKNOWN_VARIABLE_TYPE, INVALID_MUTABILITY |
| 503 | GRAPH_AUTH_NOT_CONFIGURED, REPOSITORY_UNAVAILABLE, CONTEXT_REPOSITORY_UNAVAILABLE |
| 500 | INTERNAL_ERROR |

## B2/C handoff

Use an authenticated server-side session binding player connection, world,
Hall/Program/WorkingCopy, displayed Stage, lifecycle generations and original
GET CAS/basis. C2S must not accept authoritative identity/CAS/secret from the
client. Coordinate routing with the Block Editor's session/screen sequences.
REFRESH is explicit; a successful CRUD receipt still needs the existing Stage
metadata reconciliation and SAVE-status flow in Fabric. A failed reconciliation
must not imply Python rolled back. No Kotlin or reconciliation code is added here.

Tests: [HTTP unit tests](../python-service/tests/test_declaration_http.py),
[isolated PostgreSQL acceptance](../python-service/tests/integration/postgres/postgres_declaration_http_integration.py).
Run PG through [the guarded runner](../python-service/tests/integration/postgres/run-tests.ps1)
with `-Suites declaration_http`; never point these destructive fixtures at a
production database.
