# CK-09/C1 – Saved INTEGER value-expression domain

C1 provides Python domain/application services and persistence. It does not
execute Programs, store runtime variable values, add public HTTP routes, or
implement Fabric blocks/screens. Production deployment and migration are separate.

## Saved contracts

- `ROOT` and `PROGRAM_BODY` retain their control meaning. `VALUE_BODY` is a
  distinct kind, initially owned by a LET through role `LET_RHS`.
- The existing `program_block_instance_id` field is retained for compatibility;
  it identifies the owning block instance, including LET. `owner_role` is separate
  from both block type and Stage kind. Legacy links default to `PROGRAM_BODY`.
- ACTIVE hierarchy remains acyclic, same-Program, one active child per owner and
  one active parent per child. LET deletion retains its child and saved content.
- Empty VALUE_BODY has no generated START, STOP or RESULT. A player will place
  RESULT in C2. Incomplete/cyclic/ill-typed expressions remain saveable drafts.
- Stage `value_schema_version=1` versions the C1 semantics and VALUE port layout.
  `graph_snapshot_version=1` and the existing control catalog base remain unchanged.
  `ck-value-i32-v1` names the VALUE port catalog in application code; arithmetic
  configurations explicitly name their separate `ck-integer-i32-v1` profile.
- `stage_blocks.semantic_config` is independent of display text. Configuration
  objects have explicit `version: 1`; `{}` is an unconfigured draft.

| Block | Configuration beyond `version` | Ports |
|---|---|---|
| LET | `target_id` declaration UUID | CONTROL `in`, `out` |
| LITERAL | `type: INTEGER`, integer `value`, `profile: ck-integer-i32-v1` | VALUE `out` |
| REF | `kind: DECLARATION_REF`, `target_id` declaration UUID | VALUE `out` |
| OPERATOR | `operation: ADD/SUBTRACT/MULTIPLY/DIVIDE`, same integer profile | VALUE `lhs`, `rhs`, `out` |
| RESULT | No required configuration; `{}` is sufficient | VALUE `in` |

The integer profile is signed i32, no implicit conversion, division truncated
towards zero. Static zero division and overflow are diagnostics, not SAVE failures.
Unknown format/profile/REF variants are rejected rather than rewritten.
EXPRESSION_REF/PFUN_REF, BOOLEAN/STRING literals, concat, PrmStage, and execution
are intentionally not implemented.

## Scope and identity

VALUE_BODY follows its active owner ancestry. REF/LET selection uses declaration
UUID, including VAR and VAL; mutability does not prohibit selection. Existing
shadowed declarations in that ancestry remain reachable by their original UUID.
Rename preserves bindings. Delete/recreate, lost ancestry or changed types never
cause name-based rebinding. Broken UUIDs are saved without a declaration FK.
VAL definite-assignment analysis is deferred.

Every edit requires a `ValueEditBasis`: Stage epoch/revision plus the complete
`ResolutionBasis`. Parent edits and hierarchy changes invalidate it even when
the VALUE_BODY revision is unchanged. Basis is transient freshness evidence;
it is not stored as the declaration identity or cached as semantic validity.

## Internal application API

Construct `ValueService(programs, declarations=shared_declaration_service)` using
the same Program/Stage aggregate as the other services. Targets are existing
`DeclarationTarget(program_id, stage_id)` values; player identity is a UUID.

1. `view(target, player)` returns immutable snapshot, current edit basis and
   VALUE analysis when applicable. Use the newly returned basis after each edit.
2. `place_block(..., basis, type_key, GridPos, orientation=...)` creates an
   unconfigured LET or value block, enforcing kind, bounds and occupancy.
3. `set_let_target`, `set_ref`, `set_literal`, `set_operator` set typed configuration.
   `configure` is the strict versioned low-level equivalent.
4. `ensure_rhs(..., basis, let_id)` creates/returns the same empty VALUE_BODY and
   its ownership link, without SAVE. It reactivates an existing retained link.
5. `place_wire(..., basis, position, intent)` reuses physical Wire placement.
   `bind(..., basis)` explicitly discovers and binds VALUE connections. Physical
   placement alone creates no GraphEdge. An output supports one consumer.
6. `view(...).analysis` provides structured block/port diagnostics, RESULT
   dependency order, inferred result type and derived read-only expression text.
7. Existing Program-wide `save` is the only canonical persistence path. Existing
   Stage deletion/rebuild preserve the complete snapshot and owner retention.

Analysis checks cycles throughout the Stage, then processes RESULT dependencies
in deterministic topological order. It distinguishes actual cycle members from
consumers blocked by a cycle. Constant folding serves diagnostics only; it is
not Program execution. No derived text is persisted as source truth.

### C1-R1 – RESULT processing and validity

Type checks, constant diagnostic folding and expression rendering run only on
the dependencies of the placed RESULT blocks. Unconnected noncyclic drafts
(including an invalid operator type, broken REF or constant division by zero)
are not processed as part of the RESULT expression. Zero or multiple RESULT
blocks still produce cardinality diagnostics and no single expression text.

Whole-Stage topology integrity checks and cycle detection remain global.
`ValueAnalysis.valid` retains its meaning: there must be no diagnostics, including
global cycles and LET-target diagnostics. The additional read-only `result_valid`
property describes only a complete, typed single RESULT expression without
diagnostics on its dependency nodes. Therefore a disconnected cycle can yield
`valid=False, result_valid=True`; a cycle affecting RESULT yields both false.
A LET-target mismatch also leaves a correct RHS `result_valid=True` while the
whole analysis remains invalid. `result_valid` must not replace global validation
in a future finalization gate. Neither property is a SAVE condition.

## Storage and compatibility

[Migration 012](../python-service/src/hangar/migrations/012_value_stages.sql) adds
the kind/config/version/role data and VALUE edges. It replaces the generated child
kind constraint with a role-derived equivalent, preserving existing link rows.
Original block row tuple layout remains compatible with historical migration
fixtures; semantic configuration is a separate snapshot-row component.

Program CAS, Stage CAS, content replacement and ownership links commit in one
transaction. Semantic analysis is not a precondition of SAVE. Technical failures
roll back the entire tree. Saved snapshots can be reloaded/rebuilt; unsaved edits
remain process-local and are not recovered after a WH process loss.

`blockcommander.value_writer=1` is a transaction-local writer capability marker,
not an authorization mechanism against a malicious database owner. Guards cover
content, Stage headers, Program version and hierarchy, including empty VALUE_BODY.
Unsupported versions and version downgrade fail closed. Future incompatible
semantic formats must advance the Stage format marker before any old writer can
be considered compatible.

## C2 prerequisites

- Expose authenticated transport/DTOs for this internal API with the existing
  Hall/Program/WorkingCopy, owner, CAS and lifecycle constraints.
- Carry owning role and expected Stage kind through enter/back and projection.
  Existing PROGRAM-specific Fabric `enter` is not yet a LET navigator.
- Register/render the five new physical blocks and their distinct ports. OPERATOR
  `lhs` is LEFT, `rhs` RIGHT, `out` FRONT; RESULT `in` is BACK, relative to facing.
- Add target/operator/literal pickers and LET modal with derived RHS. Do not turn
  expression text into an editable source of truth.
- Keep the existing CONTROL HTTP path fenced from VALUE operations. C1 does not
  make old Fabric clients able to edit the new domain types. Kotlin still has no
  second semantic graph engine.
- Surface semantic warnings separately from technical SAVE failure; retain GUI
  draft/session protection and explicit user-driven bind/SAVE.

## Validation

Unit tests: [test_value_domain.py](../python-service/tests/test_value_domain.py).
Real PostgreSQL tests: [values suite](../python-service/tests/integration/postgres/postgres_values_integration.py).
Run the latter only through the existing identity-guarded
`python-service/tests/integration/postgres/run-tests.ps1 -Suites values` harness.
It targets the disposable test schema, never production.
