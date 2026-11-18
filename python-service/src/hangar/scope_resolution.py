"""One uncached Python scope resolver; structural ancestry is an explicit B1 policy."""
from dataclasses import dataclass
from uuid import UUID
from .declarations import DeclarationError, ScopeRef, VariableDeclaration, validate_name

SCOPE_POLICY_VERSION = 1


class StageScopeParentProvider:
    version = SCOPE_POLICY_VERSION

    def chain(self, program, stage_id):
        headers = program.stage_headers
        root = program.root_stage_id
        if stage_id not in headers or root not in headers or headers[root]["stage_kind"] != "ROOT":
            raise DeclarationError("SCOPE_UNAVAILABLE", "Missing Stage or ROOT scope")
        parents = {}
        for link in program.links:
            if link.link_state == "RETAINED":
                continue
            if (link.link_state != "ACTIVE" or link.child_stage_id == root or
                    link.parent_stage_id not in headers or link.child_stage_id not in headers or
                    link.child_stage_id in parents or headers[link.child_stage_id]["stage_kind"] != "PROGRAM_BODY"):
                raise DeclarationError("SCOPE_UNAVAILABLE", "Invalid active scope ancestry")
            parents[link.child_stage_id] = link.parent_stage_id
        chain = []; seen = set(); current = stage_id
        while True:
            if current in seen:
                raise DeclarationError("SCOPE_UNAVAILABLE", "Scope cycle")
            seen.add(current); chain.append(current)
            if current == root:
                return tuple(chain)
            if current not in parents:
                raise DeclarationError("SCOPE_UNAVAILABLE", "Detached Stage has no inherited scope")
            current = parents[current]


@dataclass(frozen=True)
class ScopeVersion:
    scope: ScopeRef
    workspace_epoch: UUID
    working_revision: int


@dataclass(frozen=True)
class ResolutionBasis:
    program_id: UUID
    query_scope: ScopeRef
    policy_version: int
    program_workspace_epoch: UUID
    hierarchy_revision: int
    scopes: tuple[ScopeVersion, ...]


@dataclass(frozen=True)
class VisibleSymbol:
    declaration: VariableDeclaration
    origin_scope: ScopeRef
    distance: int

    @property
    def source(self):
        return "LOCAL" if self.distance == 0 else "INHERITED"

    @property
    def can_edit_declaration(self):
        return self.distance == 0


@dataclass(frozen=True)
class ShadowedSymbol:
    declaration: VariableDeclaration
    origin_scope: ScopeRef
    distance: int
    shadowed_by: UUID


@dataclass(frozen=True)
class SymbolResolution:
    status: str
    basis: ResolutionBasis
    visible: tuple[VisibleSymbol, ...]
    shadowed: tuple[ShadowedSymbol, ...]
    resolved: VisibleSymbol | None = None


def resolve_symbols(snapshots, basis, name=None):
    if name is not None:
        validate_name(name)
    if (not snapshots or len(snapshots) != len(basis.scopes) or
            basis.query_scope != basis.scopes[0].scope):
        raise DeclarationError("SCOPE_UNAVAILABLE", "Incomplete scope snapshots")
    visible = {}; shadowed = []
    for distance, (snapshot, version) in enumerate(zip(snapshots, basis.scopes)):
        if snapshot.stage_id != version.scope.owner_id or snapshot.working_revision != version.working_revision:
            raise DeclarationError("SCOPE_UNAVAILABLE", "Snapshot/scope mismatch")
        names = set()
        for declaration in snapshot.declarations:
            if declaration.scope_ref != version.scope or declaration.name in names:
                raise DeclarationError("DECLARATION_INTEGRITY_ERROR", "Duplicate or foreign declaration")
            names.add(declaration.name)
            if declaration.name not in visible:
                visible[declaration.name] = VisibleSymbol(declaration, version.scope, distance)
            else:
                shadowed.append(ShadowedSymbol(declaration, version.scope, distance,
                    visible[declaration.name].declaration.declaration_id))
    values = tuple(visible[key] for key in sorted(visible))
    hidden = tuple(sorted(shadowed, key=lambda s: (s.declaration.name, s.distance, s.declaration.declaration_id.int)))
    return SymbolResolution("AVAILABLE" if name is None else "FOUND" if name in visible else "NOT_FOUND",
                            basis, values, hidden, visible.get(name))
