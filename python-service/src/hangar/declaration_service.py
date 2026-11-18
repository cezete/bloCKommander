"""Declaration commands operate only on the existing Program/Stage workspace.

No HTTP, SAVE, automatic retries, request receipt cache or independent state store.
"""
from collections.abc import Mapping
from contextlib import ExitStack, contextmanager
from dataclasses import dataclass, replace
from uuid import UUID, uuid4
from .declarations import DeclarationError, ScopeRef, VariableDeclaration, require_uuid
from .graph.snapshot import GRAPH_SNAPSHOT_VERSION
from .graph.port_catalog import PORT_CATALOG_VERSION
from .repository import HallForbidden, HallNotFound, SnapshotCompatibilityError
from .scope_resolution import (ResolutionBasis, ScopeVersion, StageScopeParentProvider,
                               resolve_symbols)


@dataclass(frozen=True)
class DeclarationTarget:
    program_id: UUID
    stage_id: UUID

    def __post_init__(self):
        require_uuid(self.program_id, "program_id"); require_uuid(self.stage_id, "stage_id")


@dataclass(frozen=True)
class DeclarationView:
    target: DeclarationTarget
    declarations: tuple[VariableDeclaration, ...]
    stage_workspace_epoch: UUID
    working_revision: int
    stage_saved_version: int
    program_saved_version: int
    stage_dirty: bool
    program_dirty: bool


@dataclass(frozen=True)
class DeclarationMutation:
    declaration: VariableDeclaration
    modified: bool
    view: DeclarationView


class DeclarationService:
    def __init__(self, programs, *, parent_provider=None, id_factory=uuid4):
        self.programs = programs
        self.parents = parent_provider or StageScopeParentProvider()
        self.id_factory = id_factory

    @contextmanager
    def _locked(self, target, player_uuid):
        if not isinstance(target, DeclarationTarget):
            raise DeclarationError("INVALID_TARGET", "DeclarationTarget required")
        require_uuid(player_uuid, "player_uuid")
        try:
            with self.programs.locked_stage(target.program_id, target.stage_id, player_uuid) as pair:
                if pair[1].snapshot.stage_id != target.stage_id:
                    raise DeclarationError("STAGE_IDENTITY_MISMATCH", "Snapshot belongs to another Stage")
                yield pair
        except HallForbidden as exc:
            raise DeclarationError("FORBIDDEN", str(exc)) from exc
        except HallNotFound as exc:
            raise DeclarationError("STAGE_NOT_FOUND", str(exc)) from exc

    @staticmethod
    def _view(target, program, stage):
        return DeclarationView(target, stage.snapshot.declarations, stage.workspace_epoch,
            stage.revision, stage.saved_version, program.saved_version, stage.dirty, program.dirty)

    def list_own(self, target, player_uuid):
        with self._locked(target, player_uuid) as (program, stage):
            return self._view(target, program, stage)

    def _check(self, target, program, stage, epoch, revision):
        self.parents.chain(program, target.stage_id)
        if (stage.graph_snapshot_version != GRAPH_SNAPSHOT_VERSION or
                stage.snapshot.port_catalog_version != PORT_CATALOG_VERSION):
            raise SnapshotCompatibilityError("Declaration mutation requires a compatible complete snapshot")
        # Unspecified FOR/FUN ports do not prevent Stage metadata edits. This
        # service never invokes or relaxes the separate graph mutation gate.
        require_uuid(epoch, "expected_stage_workspace_epoch")
        if epoch != stage.workspace_epoch:
            raise DeclarationError("STALE_WORKSPACE_EPOCH", "Reload Stage")
        if type(revision) is not int or revision < 0:
            raise DeclarationError("INVALID_REVISION", "Nonnegative integer required")
        if revision != stage.revision:
            raise DeclarationError("STALE_WORKING_REVISION", "Reload Stage")

    @staticmethod
    def _find(stage, declaration_id):
        require_uuid(declaration_id, "declaration_id")
        for declaration in stage.snapshot.declarations:
            if declaration.declaration_id == declaration_id:
                return declaration
        raise DeclarationError("DECLARATION_NOT_FOUND", "No such declaration in this Stage")

    @staticmethod
    def _commit(stage, declarations):
        candidate = stage.snapshot.with_content_change(declarations=tuple(declarations))
        modified = candidate != stage.snapshot
        if modified:
            stage.snapshot = candidate; stage.dirty = True
        return modified

    def create(self, target, player_uuid, epoch, revision, *, name, variable_type, mutability):
        with self._locked(target, player_uuid) as (program, stage):
            self._check(target, program, stage, epoch, revision)
            declaration = VariableDeclaration(self.id_factory(), ScopeRef(target.stage_id), name, variable_type, mutability)
            modified = self._commit(stage, (*stage.snapshot.declarations, declaration))
            return DeclarationMutation(declaration, modified, self._view(target, program, stage))

    def update(self, target, player_uuid, epoch, revision, declaration_id, changes):
        with self._locked(target, player_uuid) as (program, stage):
            self._check(target, program, stage, epoch, revision)
            declaration = self._find(stage, declaration_id)
            if not isinstance(changes, Mapping) or not changes or set(changes) - {"name", "variable_type", "mutability"}:
                raise DeclarationError("INVALID_CHANGES", "Only name, variable_type and mutability may change")
            updated = replace(declaration, **changes)
            modified = self._commit(stage, (updated if d.declaration_id == declaration_id else d for d in stage.snapshot.declarations))
            return DeclarationMutation(updated, modified, self._view(target, program, stage))

    def delete(self, target, player_uuid, epoch, revision, declaration_id):
        with self._locked(target, player_uuid) as (program, stage):
            self._check(target, program, stage, epoch, revision)
            declaration = self._find(stage, declaration_id)
            modified = self._commit(stage, (d for d in stage.snapshot.declarations if d.declaration_id != declaration_id))
            return DeclarationMutation(declaration, modified, self._view(target, program, stage))

    def resolve(self, target, player_uuid, name=None):
        if not isinstance(target, DeclarationTarget):
            raise DeclarationError("INVALID_TARGET", "DeclarationTarget required")
        require_uuid(player_uuid, "player_uuid")
        program = self.programs.load(target.program_id)
        with program.lock:
            self.programs._active(program)
            try:
                self.programs._authorize(program, str(player_uuid))
            except HallForbidden as exc:
                raise DeclarationError("FORBIDDEN", str(exc)) from exc
            chain = self.parents.chain(program, target.stage_id)
            stages = {sid: self.programs._load_stage_locked(program, sid) for sid in chain}
            with ExitStack() as stack:
                for sid in sorted(stages, key=str):
                    stack.enter_context(stages[sid].lock)
                basis = ResolutionBasis(program.program_id, ScopeRef(target.stage_id), self.parents.version,
                    program.workspace_epoch, program.hierarchy_revision,
                    tuple(ScopeVersion(ScopeRef(sid), stages[sid].workspace_epoch, stages[sid].revision) for sid in chain))
                return resolve_symbols(tuple(stages[sid].snapshot for sid in chain), basis, name)

    def basis_is_current(self, basis, player_uuid):
        if not isinstance(basis, ResolutionBasis):
            raise DeclarationError("INVALID_BASIS", "ResolutionBasis required")
        try:
            return self.resolve(DeclarationTarget(basis.program_id, basis.query_scope.owner_id), player_uuid).basis == basis
        except DeclarationError as exc:
            if exc.code == "SCOPE_UNAVAILABLE":
                return False
            raise
