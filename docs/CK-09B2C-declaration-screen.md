# CK-09/B2/C – Fabric DeclarationScreen

Implementation/build scope only. Minecraft acceptance: **NOT RUN**.
Baseline: `main`, `6e85f8239876f6ceb199b1525ae91d93ccafe924`, clean.
No commit, deployment, production call, restart or live-world operation.
Exception to the requested log preservation: the existing Minecraft test logger
rewrote local `logs/latest.log` during the first regression runs. That file was
not restored/deleted. A console-only `src/test/resources/log4j2-test.xml` isolates
subsequent test runs; this configuration is not packaged into the runtime JAR.
Three local rollover archives (`2026-11-18-1.log.gz` through `-3.log.gz`) were also
created by those test runs and are preserved. The clean baseline's exact log blob
was copied from Git to `build/test-log-backup/latest.before-ck09b2c.log`, outside
`logs/`, without restoring or editing any log. Its SHA-256 is
`5a8be2a8cb5875414735337ebe46ba0b7773b9e24fabd907750dc2656bcc29dd`.

## Opening and trust

`/ck declarations` resolves the authenticated server player, actual connection,
dimension/world, and the single physical Hall containing the player's position.
The whole Hall volume is considered, including the door/walls, rather than only
the Stage volume. Owner-only editing follows the existing model. Ambiguous or
foreign Halls, missing mounts, navigation in progress and invalid lifecycle fences
are rejected. The target is `hall.stage`, the **displayed Stage**, regardless of
the persisted Hall Selection or Program ROOT.

The [runtime](../src/main/kotlin/me/buciqua/blockommander/runtime/declarations/DeclarationSessionRuntime.kt)
captures player/connection generation, world/dimension, Hall/Program/WorkingCopy/
Stage and Hall/projection/navigation generations plus Stage epoch. The original
HTTP GET page's revision/basis remains server-side until an explicit read or a
successful mutation followed by its authoritative read replaces it. Local registry
revision is never substituted into an old GET's CAS.

## HTTP and packets

The [typed client](../src/main/kotlin/me/buciqua/blockommander/runtime/declarations/DeclarationApiClient.kt)
implements all six endpoints of the unchanged
[B2/B HTTP contract](CK-09B2B-declaration-http-api.md). It sends the server-derived
player and captured mount headers, keeps the shared secret server-side, validates
strict JSON, UUIDs, enums, version/context/basis/page echoes, and mutation receipts.
Unknown JSON fields, duplicate keys, malformed numbers/UTF-8 and oversized bodies
fail closed. GET responses are capped at 16 KiB while receiving, with cancellation
before unbounded accumulation. Requests are capped at 64 KiB; connect timeout is
3 seconds and request timeout is 10 seconds. There is no retry loop.

A SAVE between GET and mutation can change saved/dirty metadata without changing
the working CAS: receipt validation checks that original epoch/revision and the
receipt's authoritative previous/new state, allowing monotonic saved versions.
No declaration domain validation, resolver, shadowing calculation or persistence
logic is implemented in Kotlin.

[C2S](../src/main/kotlin/me/buciqua/blockommander/network/DeclarationPackets.kt)
contains session key/nonce, increasing operation sequence, action, optional
declaration UUID and edited name/type/mutability. It contains no target Hall,
Program, WorkingCopy, Stage, epoch, revision, basis or secret. The authenticated
network player must own the session/connection. UPDATE/DELETE target a writable
own row from the server's current authoritative page; inherited/shadowed IDs are
not granted edit capability.

S2C contains bounded presentation data, one HTTP page (at most 16 rows), context
labels/IDs, current state, dirty/saved versions, projection result and error code.
The full scope chain/digest is not sent to the Minecraft client. Binary payloads
are limited to 16,000 bytes, leaving space for the byte-array length prefix below
16 KiB; unknown versions/tags, trailing bytes and malformed field shapes are
rejected. No rows are silently truncated.

## Session and surface lifecycle

States: OPENING, READY, PENDING, APPLIED_TO_WORKSPACE, REJECTED, UNKNOWN, STALE,
RELOAD_REQUIRED, CLOSED. PENDING prevents a second dispatch. All future completions
return to the server executor and recheck session/lifecycle before use. Leaving
the Hall, Stage/Hall Selection changes, reconnect, dimension/epoch/projection
changes, CLOSE and server shutdown invalidate the old session.

Opening either editor invalidates the other server session. Both use a shared,
monotonic per-player S2C surface sequence. Client routing checks that sequence,
player/session key, actual connection object, received/current level object and
dimension. Dismissed surfaces are tombstoned; a delayed old response cannot reopen
them. Block Editor's state envelope now carries the dimension: its packet version
and channels are **v2** (`editor_state_v2`, `editor_intent_v2`). Its existing
session/domain/metadata committer is otherwise preserved. Future deployment needs
matching new client and server JARs; copying only one side is insufficient.

After a successful receipt the session records APPLIED_TO_WORKSPACE, adopts the
receipt epoch/revision and performs an authoritative editor GET followed by
separate metadata reconciliation. A failed GET or projection retains the receipt
and the explicit applied status; it never claims Python rollback.

A timeout, invalid mutation response, exceptional completion or server-side 5xx
means UNKNOWN. No automatic mutation resend or automatic UNKNOWN recovery GET.
Explicit read-only REFRESH is allowed, keeps UNKNOWN and does not turn current
list contents into proof for the lost request. The UI keeps SAVE status unknown
for that unresolved request and disables further writes in that session; CLOSE
and a deliberate new opening start a new session. Already-dispatched backend
operations may finish on the original Stage after CLOSE/navigation. There is no
cancellation or rollback guarantee.

REFRESH keeps local unsent form data. New/row selection requires explicit second
selection to discard a dirty draft. Successful mutations reset the sent draft
only after authoritative GET and successful reconciliation. A failed read leaves
the last authoritative rows visibly stale. Page changes retain the same opaque
HTTP page token and reject stale pages. Known local Program changes, including
parent declaration notifications arriving during GET/reconciliation, fence the
view. No external Python push/polling is added; Python's basis precondition remains
the authority for changes that Fabric has not observed.

## Narrow metadata reconciliation

[DeclarationReconciliation](../src/main/kotlin/me/buciqua/blockommander/runtime/declarations/DeclarationReconciliation.kt)
uses existing Program Stage GET, Graph GET, StageProjectionAssembler, registry
metadata commit, Wire verification and SAVE-sign rendering. It does not reuse a
fake START/STOP target, refactor the Block Editor committer, place/remove blocks,
write Wire geometry, change GraphEdges, invoke bind, SAVE or REBUILD.

Before commit it verifies capture/observation/lifecycle/identity, exact editor GET
epoch/revision/saved/dirty/Program hierarchy, unchanged blocks (including labels),
unchanged Graph/Wire topology, physical blocks/Wires and SAVE-sign availability.
The final fence is checked after preflight. Registry/Graph revision and dirty
state advance through the existing metadata commit; Wire is verified, then the
SAVE sign is rendered. Failures after a partial projection fence Wire for repair;
they do not restore an older Python state.

Dispatch pessimistically invalidates old Program-status requests and marks mounted
SAVE signs dirty. Metadata commit invalidates older status generations again.
The reconciliation result includes its local Program stamp so a later queued
session callback cannot publish CURRENT over a newer local change.

## GUI and limits

[DeclarationScreen](../src/client/kotlin/me/buciqua/blockommander/client/screen/DeclarationScreen.kt)
is client-only. It displays Program/Stage, owner Stage and declaration UUID,
LOCAL/INHERITED/SHADOWED rows, name, type/mutability selectors, New, CREATE,
UPDATE, confirmed DELETE, NEXT/PREV, REFRESH, CLOSE, error and workspace/SAVE
status. It contains no SAVE action. Inherited/shadowed fields are read-only.

The HTTP editor snapshot includes both OWN and LOCAL sections; this distinction
is preserved rather than deduplicated with a second scope algorithm. OWN is
labelled `LOCAL/saját`; the normal LOCAL visibility row is also shown. On the
minimum 320x240 scaled viewport only a few rows fit; ▲/▼ scroll within the complete
16-row page, while NEXT/PREV navigate HTTP pages. Layout/model tests cover 320x240,
426x240, 640x360 and 1280x720. Actual rendering/input usability still needs Minecraft
acceptance. Very deep scope bases can hit B2/B's explicit response limit.

Declarations remain metadata only (BOOLEAN/INTEGER/STRING, MUTABLE/IMMUTABLE).
No initializer, expression evaluation, assignment or code generation is added.

## Automated verification

Four new test groups cover real Python-generated DTO fixtures/HTTP transport,
server sessions, binary codecs/presentation/drafts/surface ordering, and actual
HallRegistry/GraphProjectionStore/Wire reconciliation with a fake physical backend.
They are automated checks, not Minecraft acceptance. Existing Block Editor,
Graph/Wire and Stage navigation regression tests run with the full Kotlin suite.
The unchanged Python HTTP/PostgreSQL suite is not rerun in this Fabric-only slice.

Final `gradlew.bat test build` under Java 25: **PASS**, 449 tests, 0 failures,
0 errors, 0 skipped; 50 new declaration tests (transport 11, sessions 19,
GUI/network 13, reconciliation 7). Main source and compiled main classes contain
no client-package references. Relative links and `git diff --check` pass.
The last console-only test run left `logs/latest.log` byte-identical (SHA-256
`44d65862fb2f9d9415f6371058eafeaca3de5fc7e1447ae64ae7156a53053847`).

JAR: `build/libs/blockommander-fabric-0.1.0.jar`, 8,993,752 bytes;
SHA-256 `15e1f68e51aee4fda295cc35a99d27ba73020278119262475c4f5a006ad0076a`.
Both runtime and client Screen classes are packaged, while the test log config is
absent. Functional implementation/build: **PASS**. Overall task: **PARTIAL**
because the initial test runs changed the protected local logs. Minecraft
acceptance remains **NOT RUN**, as required by the separate deployment gate.

## Changed-file inventory

Kotlin paths below use the common package prefix
`src/{main,client,test}/kotlin/me/buciqua/blockommander/`.

| Category | Files |
|---|---|
| Existing main source | `BlockCommanderMod.kt`; `network/EditorPackets.kt`; `network/Networking.kt`; `runtime/Interactions.kt`; `runtime/editor/BlockEditorSessionRuntime.kt` |
| Existing client source | `client/BlockCommanderClient.kt` |
| New main runtime | `runtime/declarations/DeclarationDtos.kt`; `DeclarationApiClient.kt`; `DeclarationSessionCoordinator.kt`; `DeclarationSessionRuntime.kt`; `DeclarationReconciliation.kt` (all under `runtime/declarations/`) |
| New main network | `network/DeclarationPackets.kt`; `network/DeclarationUiModel.kt` |
| New client source | `client/screen/DeclarationScreen.kt` |
| New tests | `runtime/declarations/DeclarationTransportTest.kt`; `DeclarationSessionTest.kt`; `DeclarationGuiTest.kt`; `DeclarationReconciliationTest.kt` (all under test `runtime/declarations/`) |
| New fixtures | `src/test/resources/declarations/create.json`; `editor-empty.json`; `editor-local.json`; `editor-child.json`; `own.json`; `symbols.json` (all in the same fixture directory) |
| Test-only logging | `src/test/resources/log4j2-test.xml` |
| Documentation | `docs/CK-09B2C-declaration-screen.md` |

This is 26 task files (6 existing, 20 new), plus the separately reported local
log side effects. Python/B1/B2B code, migrations, the Block Editor metadata
committer and diagnostic branch are unchanged. HEAD remains the baseline.

## Minecraft acceptance – külön GO után

Használj külön teszthangárt és kontrollált tesztprogramot ROOT + PROGRAM/CHILD
Stage-ekkel. Értékes meglévő Stage-en ne kezdj próbát. Kliens/szerver telepítés,
SAVE, restart és a tesztkörnyezet előkészítése külön engedélyhez kötött.

1. Saját teszthangárban `/ck declarations`: a fejléc a megjelenített Stage-et
   mutatja. Hangáron kívül vagy idegen tulajdonossal a parancs elutasítandó.
2. ROOT-ban New → `counter`, INTEGER, MUTABLE → CREATE. Egy dispatch, majd
   APPLIED_TO_WORKSPACE; UUID látható, working copy dirty, SAVE-tábla NINCS MENTVE.
3. CLOSE, lépj a PROGRAM blokkon át CHILD-be, `/ck declarations`. A perzisztált
   ROOT Selection ellenére CHILD jelenjen meg. `counter` INHERITED, read-only.
4. New → `counter`, BOOLEAN, IMMUTABLE → CREATE. Saját LOCAL és a ROOT
   `counter` SHADOWED sor látható; az örökölt/árnyékolt sor nem szerkeszthető.
5. Saját sor kijelölése, név `childCounter` → UPDATE. UUID változatlan; ROOT
   `counter` újra INHERITED. A ROOT deklaráció nem neveződött át.
6. DELETE első kattintás → `Tényleg?`; REFRESH megszakítja a megerősítést,
   a sor megmarad. Ezután DELETE és megerősítés → a saját sor eltűnik.
7. Hozz létre `persistedChild: STRING, IMMUTABLE` deklarációt. Írj be egy
   további, el nem küldött draftot; REFRESH után maradjon meg. Új sor/form
   választása csak a második, kifejezett megerősítésre dobhatja el.
8. NEXT/PREV és ▲/▼ próba egy 16 sornál hosszabb kontrollált nézetben;
   kisebb GUI-skálán minden vezérlő elérhető. Nincs sorvesztés vagy kevert oldal.
9. CLOSE, külön a meglévő Program-wide SAVE gombbal ments. A ROOT és CHILD
   deklarációi egy Programként mentődjenek, a SAVE-tábla MENTVE legyen.
10. Reconnect, majd külön engedélyezett izolált Python-restart és reconnect
    után ellenőrizd a `counter`/`persistedChild` neveket, típusokat és UUID-kat.
    RELOAD_REQUIRED esetén dokumentáld a meglévő reconnect-helyreállítás eredményét;
    ne kerüld meg a fence-et és ne végezz automatikus REBUILD-et.
11. Hozz létre mentetlen `temporary` deklarációt, ne SAVE-elj. Külön engedélyezett
    izolált restart/reconnect után ez vesszen el; a mentett deklarációk maradjanak.
12. Próbáld a két editort gyorsan egymás után mindkét sorrendben. Régi válasz
    ne nyisson vissza másik képernyőt. CLOSE, Stage-váltás és reconnect után
    a korábbi session ne küldhessen mutációt.
13. Ellenőrizd a START/STOP editor, CK Wire place/break, Graph/bind és
    ROOT↔CHILD navigáció meglévő acceptance-lépéseit. Deklarációs szerkesztéstől
    blokk/Wire/topológia nem változhat.

Minden lépésnél rögzíts PASS/FAIL-t; hiányzó tesztprogram, telepítés vagy restart-GO
esetén az érintett eset **BLOCKED/NOT RUN**, nem PASS. UNKNOWN esetén csak
read-only REFRESH; ne kattints újra automatikusan CREATE/UPDATE/DELETE-re.
