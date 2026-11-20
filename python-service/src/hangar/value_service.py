"""Internal C1 application API. No HTTP routes, implicit SAVE or runtime execution."""
from contextlib import contextmanager
from dataclasses import dataclass, replace
from uuid import UUID, uuid4
from .declaration_service import DeclarationService, DeclarationTarget
from .graph.binding import BindEdgesCommand, bind_edges
from .graph.discovery import discover_physical_topology
from .graph.model import GridPos, CardinalDirection
from .graph.snapshot import StageBlockSnapshot, StageSnapshot, thaw_json
from .scope_resolution import ResolutionBasis
from .graph.placement import place_wire_intent
from .value_analysis import analyze_value
from .value_model import ValueDomainError, INTEGER_PROFILE, VALUE_TYPES, ValueDiagnostic, ValueAnalysis
from .value_ports import VALUE_PORT_CATALOG

_READ = object()


@dataclass(frozen=True)
class ValueEditBasis:
    workspace_epoch: UUID
    working_revision: int
    resolution_basis: ResolutionBasis


@dataclass(frozen=True)
class ValueView:
    snapshot: StageSnapshot
    basis: ValueEditBasis
    analysis: ValueAnalysis | None


class ValueService:
    def __init__(self, programs, *, declarations=None, id_factory=uuid4):
        self.programs = programs
        self.declarations = declarations or DeclarationService(programs)
        self.id_factory = id_factory

    @contextmanager
    def _locked(self, target, player, basis=_READ):
        with self.programs.locked_stage(target.program_id, target.stage_id, player) as (program, stage):
            if stage.graph_snapshot_version != 1 or stage.snapshot.port_catalog_version != "ck-control-v3":
                raise ValueDomainError("INCOMPATIBLE_SNAPSHOT", "A known complete snapshot is required")
            # Program lock serializes the entire scope chain, including parent edits.
            resolution = self.declarations.resolve(target, player)
            current = ValueEditBasis(stage.workspace_epoch, stage.revision, resolution.basis)
            if basis is not _READ and (not isinstance(basis, ValueEditBasis) or basis != current):
                raise ValueDomainError("STALE_VALUE_BASIS", "Reload expression and visible declarations")
            yield program, stage, resolution, current

    @staticmethod
    def _commit(stage, **changes):
        candidate = stage.snapshot.with_content_change(**changes)
        if candidate != stage.snapshot:
            stage.snapshot = candidate; stage.dirty = True

    def view(self, target, player):
        with self._locked(target, player) as (program, stage, resolution, basis):
            analysis = None
            if stage.stage["stage_kind"] == "VALUE_BODY":
                links = [l for l in program.links if l.child_stage_id == target.stage_id and l.link_state == "ACTIVE"]
                target_id = None; owner = None
                if len(links) == 1:
                    parent = self.programs._load_stage_locked(program, links[0].parent_stage_id)
                    owner = next((b for b in parent.snapshot.blocks if b.instance_id == links[0].program_block_instance_id), None)
                    config = thaw_json(owner.semantic_config) if owner else {}
                    if config: target_id = UUID(config["target_id"])
                analysis = analyze_value(stage.snapshot, resolution, target_id=target_id, owner_id=owner.instance_id if owner else None)
                if owner and target_id is None:
                    analysis = replace(analysis, diagnostics=(*analysis.diagnostics, ValueDiagnostic("UNCONFIGURED_LET_TARGET", owner.instance_id)))
            return ValueView(stage.snapshot, basis, analysis)

    def place_block(self, target, player, basis, type_key, position, *, orientation=CardinalDirection.NORTH):
        with self._locked(target, player, basis) as (_, stage, _, _):
            self.programs.assert_block_allowed(stage.stage["stage_kind"], type_key)
            if not isinstance(orientation, CardinalDirection):
                raise ValueDomainError("INVALID_ORIENTATION", "CardinalDirection required")
            if type_key not in VALUE_TYPES | {"LET"}:
                raise ValueDomainError("INVALID_VALUE_BLOCK", "Use the existing control block service")
            if not isinstance(position, GridPos) or any(type(v) is not int for v in (position.x, position.y, position.z)):
                raise ValueDomainError("INVALID_POSITION", "Integer GridPos required")
            if any(not 0 <= value < int(stage.stage[key]) for value, key in zip(
                    (position.x, position.y, position.z), ("size_x", "size_y", "size_z"))):
                raise ValueDomainError("BLOCK_OUTSIDE_STAGE", "Block must fit the Stage")
            if any(b.position == position for b in stage.snapshot.blocks) or any(c.position == position for c in stage.snapshot.wire_cells):
                raise ValueDomainError("OCCUPIED_POSITION", "Block/Wire already occupies the position")
            bid = self.id_factory()
            if not isinstance(bid, UUID) or any(b.instance_id == bid for b in stage.snapshot.blocks):
                raise ValueDomainError("INVALID_BLOCK_ID", "Duplicate or invalid block UUID")
            block = StageBlockSnapshot(bid, type_key, position, orientation, type_key, None, type_key, "DOMAIN")
            self._commit(stage, blocks=(*stage.snapshot.blocks, block))
            return bid

    def configure(self, target, player, basis, block_id, config):
        with self._locked(target, player, basis) as (_, stage, resolution, _):
            block = next((b for b in stage.snapshot.blocks if b.instance_id == block_id), None)
            if block is None: raise ValueDomainError("BLOCK_NOT_FOUND", "No such block")
            updated = replace(block, semantic_config=config)  # strict tagged format validation
            if block.type_key in ("LET", "REF") and config:
                ids = {s.declaration.declaration_id for s in (*resolution.visible, *resolution.shadowed)}
                if UUID(config["target_id"]) not in ids:
                    raise ValueDomainError("DECLARATION_NOT_REACHABLE", "Target declaration is outside the scope chain")
            self._commit(stage, blocks=tuple(updated if b.instance_id == block_id else b for b in stage.snapshot.blocks))

    def set_let_target(self, target, player, basis, block_id, declaration_id):
        self._configure_kind(target, player, basis, block_id, "LET", {"version": 1, "target_id": str(declaration_id)})

    def set_ref(self, target, player, basis, block_id, declaration_id):
        self._configure_kind(target, player, basis, block_id, "REF",
                             {"version": 1, "kind": "DECLARATION_REF", "target_id": str(declaration_id)})

    def set_literal(self, target, player, basis, block_id, value):
        self._configure_kind(target, player, basis, block_id, "LITERAL",
                             {"version": 1, "type": "INTEGER", "value": value, "profile": INTEGER_PROFILE})

    def set_operator(self, target, player, basis, block_id, operation):
        self._configure_kind(target, player, basis, block_id, "OPERATOR",
                             {"version": 1, "operation": operation, "profile": INTEGER_PROFILE})

    def _configure_kind(self, target, player, basis, bid, kind, config):
        with self._locked(target, player, basis) as (_, stage, _, _):
            if not any(b.instance_id == bid and b.type_key == kind for b in stage.snapshot.blocks):
                raise ValueDomainError("BLOCK_TYPE_MISMATCH", "Wrong configuration operation for block")
            self.configure(target, player, basis, bid, config)

    def ensure_rhs(self, target, player, basis, block_id):
        with self._locked(target, player, basis):
            child = self.programs.ensure_child_result(target.program_id, target.stage_id, block_id,
                                                     str(player), owner_role="LET_RHS")
            return DeclarationTarget(target.program_id, child.workspace.snapshot.stage_id)

    def place_wire(self, target, player, basis, position, intent):
        with self._locked(target, player, basis) as (_, stage, _, _):
            self._require_value(stage)
            context = self.programs.stages.read_graph_context(str(target.stage_id), str(player))
            result = place_wire_intent(context.state, position, intent, context.size, VALUE_PORT_CATALOG)
            self._commit(stage, wire_cells=result.state.wire_cells, routes=result.state.routes, edges=result.state.edges)
            return result

    def bind(self, target, player, basis):
        with self._locked(target, player, basis) as (_, stage, _, _):
            self._require_value(stage)
            context = self.programs.stages.read_graph_context(str(target.stage_id), str(player))
            discovery = discover_physical_topology(context.state, VALUE_PORT_CATALOG)
            candidates = tuple(c.as_route_candidate() for c in discovery.candidates)
            result = bind_edges(context.state, BindEdgesCommand(target.stage_id, player, stage.revision, candidates),
                                VALUE_PORT_CATALOG, edge_id_factory=self.id_factory, route_id_factory=self.id_factory)
            if result.accepted:
                self._commit(stage, edges=result.state.edges, routes=result.state.routes, wire_cells=result.state.wire_cells)
            return result

    @staticmethod
    def _require_value(stage):
        if stage.stage["stage_kind"] != "VALUE_BODY":
            raise ValueDomainError("VALUE_STAGE_REQUIRED", "Use the CONTROL service for control Stages")
