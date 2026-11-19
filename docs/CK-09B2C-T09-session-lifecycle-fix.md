# CK-09/B2/C – T09 session lifecycle repair

Baseline: `main`, `6139e1821cf88c1c82c870e0421367b0051777f5`.
The four existing T03 pagination files/changes are preserved.
Scope: Kotlin runtime, presentation and isolated tests; no Python/API/schema change.

## Own status versus workspace freshness

The old PENDING observer marked every mounted Hall dirty. Since the session's
local stamp included `programDirty`, its next poll treated this own pessimistic
status change as an external workspace edit and overwrote HTTP 422 diagnostics.

The runtime now performs an explicit optimistic transition only on the initiating
Hall. A [typed stamp](../src/main/kotlin/me/buciqua/blockommander/runtime/declarations/DeclarationStatusStamp.kt)
retains every old freshness component and adds mount/world, Graph epoch,
Hall/projection/navigation generations. Its proof accepts exactly the initiating
Hall's false→true or true→true dirty status. Other Hall/status changes and any
identity, revision, epoch, Graph-status, hierarchy or lifecycle change fail that
proof. The coordinator acknowledges it only when the proof's before/after stamps
match its accepted observation and the live observation. A failed proof prevents
HTTP dispatch; it never silently rebases the original GET/CAS.

After a confirmed successful mutation, the shared Program's other mounted Halls
are marked dirty too. Existing authoritative GET/reconciliation then synchronizes
the current Stage and SAVE board. There is no implicit SAVE or mutation retry.

## Confirmed rejection and SAVE status

HTTP 422 records REJECTED and a separate `mutationDiagnostic`. The concrete
`INVALID_DECLARATION_NAME` / `DUPLICATE_DECLARATION_NAME` remains visible while
status recovery is busy and after subsequent polls. The client draft is untouched.
No transient editable snapshot is emitted before the recovery GET becomes busy.

Recovery reads the original page index with the original page token. It must
return the same complete DeclarationPage, including epoch/revision, basis,
saved/dirty state, hierarchy, rows and token. Drift means STALE; it does not replace
the session's original CAS/basis. A matching authoritative page goes through the
existing fenced metadata reconciliation to restore the SAVE board. Blocks,
GraphEdges, Wire layout and saved versions do not change.

If that read/reconciliation fails, the concrete diagnostic remains visible and
editing stays disabled. The physical board remains conservatively dirty; the GUI
reports unknown SAVE status for a stale/unverified projection. A later explicit
REFRESH can recover from an authoritative snapshot. No cached clean state is
blindly restored. Success clears the old validation diagnostic only after that
explicit REFRESH has completed, or when a new explicit mutation is submitted.

Real external changes still invalidate freshness. Mutation diagnostics are
retained independently: the GUI can show the concrete error and a separate
Hungarian freshness/REFRESH notice. This does not suppress real stale epoch,
revision, parent-basis, hierarchy or lifecycle errors.

## UNKNOWN rule

A lost/invalid mutation reply, timeout or mutation-side 5xx leaves the current
session UNKNOWN. Poll, Program-change notifications, failed reads, version drift
and explicit read-only REFRESH cannot turn it into READY or permit another write.
Fresh GET/reconciliation data can update the view but is not proof for that
specific request. Its diagnostic remains and no mutation is resent.

There is no UNKNOWN-resolution action in this repair. CLOSE ends the session;
it neither cancels a dispatched operation nor proves its result. An explicit new
opening is a new session, not replay or retrospective confirmation of the old
request. The HTTP request UUID remains correlation-only; request-specific outcome
proof/deduplication would require a separately approved domain/API design. No such
contract or automatic resolution is introduced here.

## Verification and acceptance

[DeclarationSessionStatusTest](../src/test/kotlin/me/buciqua/blockommander/runtime/declarations/DeclarationSessionStatusTest.kt)
uses real HallRegistry/status fences, the production stamp proof, coordinator,
metadata reconciliation and GUI model, with isolated HTTP and physical backends.
It covers six input/code fixtures, multiple polls, saved version 3, unchanged
revision/basis, retained draft, successful CREATE, real external changes,
epoch/revision/basis conflicts, UNKNOWN→poll→REFRESH, late CLOSE/navigation replies,
repeated invalid CREATE after REFRESH, recovery failure and the pending/read gap.
Existing session/Graph/Wire and T03 pagination tests remain required regressions.

Final Java 25 `gradlew.bat test build`: **PASS**, 493 tests, 0 failures/errors/
skipped. The new status/session suite has 18 passing cases; all 26 T03 viewport
regressions also pass. The six local runtime log files are byte-identical to the
pre-task hash inventory. Python, schema and HTTP contracts are unchanged, so their
test suites were not rerun in this Kotlin-only repair. No live action was run.

Artifact: `build/libs/blockommander-fabric-0.1.0.jar`, 9,014,538 bytes;
SHA-256 `268ea487a81a4cf3a6012763e1666bb9bffdc10a943e017983a855164f95c04e`.
Implementation/build gate: **FIX READY FOR ACCEPTANCE**.

Minecraft acceptance is **NOT RUN**. After separate deployment GO:

1. In HomokkőCsarnok FUN CHILD, note saved version, revision and 16 own rows.
2. Try the six T09 names. Each rejection must keep its specific Hungarian message
   after several ticks; draft/count/revision/saved version stay unchanged.
3. Wait for status recovery: the SAVE board returns to authoritative MENTVE (3).
4. REFRESH, then repeat `1counter`; the specific validation message persists.
5. Check T03 pagination and selection/draft behavior again.
6. Run timeout/stale/late-response fault cases only in the isolated test harness;
   do not stop Monty or mutate production data to inject a fault.

Deployment components: Fabric server mod for session/status repair, client mod
for the separate Hungarian freshness/UNKNOWN text. Packet versions and codecs
are unchanged. Python/Monty and PostgreSQL need no deployment/migration.
Build is not deployment permission; no live action is authorized by this document.
