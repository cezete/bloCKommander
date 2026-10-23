"""Pure boundary between the complete StageSnapshot and graph domain."""

from __future__ import annotations

from collections.abc import Collection, Mapping
from dataclasses import dataclass, replace
from enum import Enum
from typing import Any, Protocol
from uuid import UUID

from .port_catalog import BlockPortStatus, PortCatalogLookupError, VersionedPortCatalog
from .snapshot import ProgramValidationStatus, StageBlockSnapshot, StageSnapshot
from .state import EdgeIndicatorStatus, GraphWorkspaceState, SaveStatus
from .validation import PortCatalog


class AdapterIssueCode(str, Enum):
    MISSING_STAGE_FIELD = "MISSING_STAGE_FIELD"
    INVALID_STAGE_ID = "INVALID_STAGE_ID"
    INVALID_WORKSPACE_VERSION = "INVALID_WORKSPACE_VERSION"
    INVALID_DIRTY_FLAG = "INVALID_DIRTY_FLAG"
    INVALID_BLOCK_COLLECTION = "INVALID_BLOCK_COLLECTION"
    MISSING_BLOCK_FIELD = "MISSING_BLOCK_FIELD"
    INVALID_INSTANCE_ID = "INVALID_INSTANCE_ID"
    DUPLICATE_INSTANCE_ID = "DUPLICATE_INSTANCE_ID"
    INVALID_POSITION = "INVALID_POSITION"
    UNKNOWN_ORIENTATION = "UNKNOWN_ORIENTATION"
    UNKNOWN_BLOCK_TYPE = "UNKNOWN_BLOCK_TYPE"
    MISSING_PORT_CATALOG = "MISSING_PORT_CATALOG"
    UNSPECIFIED_BLOCK_TYPE = "UNSPECIFIED_BLOCK_TYPE"
    PORT_CATALOG_VERSION_MISMATCH = "PORT_CATALOG_VERSION_MISMATCH"
    RUNTIME_CATALOG_MISMATCH = "RUNTIME_CATALOG_MISMATCH"
    GRAPH_CONTENT_UNAVAILABLE = "GRAPH_CONTENT_UNAVAILABLE"
    OPERATION_REJECTED = "OPERATION_REJECTED"
    CROSS_STAGE_RESULT = "CROSS_STAGE_RESULT"
    INVALID_RESULT_REVISION = "INVALID_RESULT_REVISION"
    INVALID_RESULT_SAVED_VERSION = "INVALID_RESULT_SAVED_VERSION"
    INVALID_RESULT_DIRTY_FLAG = "INVALID_RESULT_DIRTY_FLAG"
    STAGE_GRAPH_STORAGE_UNAVAILABLE = "STAGE_GRAPH_STORAGE_UNAVAILABLE"
    MISSING_NEW_BLOCK_METADATA = "MISSING_NEW_BLOCK_METADATA"
    INVALID_NEW_BLOCK_METADATA = "INVALID_NEW_BLOCK_METADATA"


@dataclass(frozen=True)
class AdapterIssue:
    code: AdapterIssueCode
    message: str
    block_index: int | None = None
    field_name: str | None = None


class GraphWorkspaceMappingError(ValueError):
    def __init__(self, issues: tuple[AdapterIssue, ...]):
        self.issues = issues
        super().__init__("; ".join(f"{item.code.value}: {item.message}" for item in issues))


class StageWorkspaceView(Protocol):
    stage: dict[str, Any]
    snapshot: StageSnapshot
    saved_version: int
    dirty: bool


@dataclass(frozen=True)
class GraphWorkspaceMapping:
    state: GraphWorkspaceState
    graph_content_complete: bool
    issues: tuple[AdapterIssue, ...]

    @property
    def permits_graph_mutation(self) -> bool:
        return self.graph_content_complete and not self.issues


@dataclass(frozen=True)
class StageStatusDTO:
    stage_id: UUID
    working_revision: int
    saved_version: int
    edge_validation_status: EdgeIndicatorStatus
    program_validation_status: ProgramValidationStatus
    save_status: SaveStatus

    def as_dict(self) -> dict[str, object]:
        return {
            "stage_id": str(self.stage_id),
            "working_revision": self.working_revision,
            "saved_version": self.saved_version,
            "edge_validation_status": self.edge_validation_status.value,
            "program_validation_status": self.program_validation_status.value,
            "save_status": self.save_status.value,
        }


@dataclass(frozen=True)
class GraphWorkspaceUpdatePlan:
    accepted: bool
    requires_apply: bool
    can_apply_to_current_stage_workspace: bool
    expected_working_revision: int
    resulting_working_revision: int
    target_state: GraphWorkspaceState | None
    issues: tuple[AdapterIssue, ...]


def _issue(
    code: AdapterIssueCode,
    message: str,
    *,
    block_index: int | None = None,
    field_name: str | None = None,
) -> AdapterIssue:
    return AdapterIssue(code, message, block_index, field_name)


def _uuid(value: object, code: AdapterIssueCode, message: str) -> UUID:
    if isinstance(value, UUID):
        return value
    if isinstance(value, str):
        try:
            return UUID(value)
        except ValueError:
            pass
    raise GraphWorkspaceMappingError((_issue(code, message),))


def _non_negative_int(value: object, field_name: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise GraphWorkspaceMappingError(
            (
                _issue(
                    AdapterIssueCode.INVALID_WORKSPACE_VERSION,
                    f"{field_name} must be a non-negative integer",
                    field_name=field_name,
                ),
            )
        )
    return value


def to_graph_workspace(
    workspace: StageWorkspaceView,
    port_catalog: PortCatalog,
    *,
    known_block_types: Collection[str],
) -> GraphWorkspaceMapping:
    """Project a complete immutable StageSnapshot into graph-domain state."""
    snapshot = getattr(workspace, "snapshot", None)
    if not isinstance(snapshot, StageSnapshot):
        raise GraphWorkspaceMappingError(
            (
                _issue(
                    AdapterIssueCode.GRAPH_CONTENT_UNAVAILABLE,
                    "Workspace has no complete StageSnapshot",
                ),
            )
        )
    stage = getattr(workspace, "stage", None)
    if not isinstance(stage, Mapping) or "stage_id" not in stage:
        raise GraphWorkspaceMappingError(
            (
                _issue(
                    AdapterIssueCode.MISSING_STAGE_FIELD,
                    "StageWorkspace.stage must contain stage_id",
                    field_name="stage_id",
                ),
            )
        )
    stage_id = _uuid(
        stage["stage_id"],
        AdapterIssueCode.INVALID_STAGE_ID,
        "stage_id must be a UUID",
    )
    saved_version = _non_negative_int(
        getattr(workspace, "saved_version", None), "saved_version"
    )
    dirty = getattr(workspace, "dirty", None)
    if not isinstance(dirty, bool):
        raise GraphWorkspaceMappingError(
            (
                _issue(
                    AdapterIssueCode.INVALID_DIRTY_FLAG,
                    "dirty must be a boolean",
                    field_name="dirty",
                ),
            )
        )
    if stage_id != snapshot.stage_id:
        raise GraphWorkspaceMappingError(
            (
                _issue(
                    AdapterIssueCode.CROSS_STAGE_RESULT,
                    "Snapshot belongs to another Stage",
                ),
            )
        )
    known_types = frozenset(known_block_types)
    issues: list[AdapterIssue] = []
    if isinstance(port_catalog, VersionedPortCatalog):
        if snapshot.port_catalog_version != port_catalog.version:
            issues.append(
                _issue(
                    AdapterIssueCode.PORT_CATALOG_VERSION_MISMATCH,
                    "Snapshot and active port catalog versions differ",
                )
            )
        runtime_check = port_catalog.check_runtime_types(known_types)
        issues.extend(
            _issue(AdapterIssueCode.RUNTIME_CATALOG_MISMATCH, item.message)
            for item in runtime_check.issues
        )

    for index, block in enumerate(snapshot.blocks):
        if block.type_key not in known_types:
            issues.append(
                _issue(
                    AdapterIssueCode.UNKNOWN_BLOCK_TYPE,
                    "Block type is absent from the runtime program block catalog",
                    block_index=index,
                    field_name="type_key",
                )
            )
            continue
        if isinstance(port_catalog, VersionedPortCatalog):
            try:
                status = port_catalog.status(block.type_key)
            except PortCatalogLookupError:
                status = None
            if status is None:
                issues.append(
                    _issue(
                        AdapterIssueCode.MISSING_PORT_CATALOG,
                        "Known block type has no authoritative graph port definition",
                        block_index=index,
                        field_name="type_key",
                    )
                )
            elif status is BlockPortStatus.UNSPECIFIED:
                issues.append(
                    _issue(
                        AdapterIssueCode.UNSPECIFIED_BLOCK_TYPE,
                        f"{block.type_key} port semantics are UNSPECIFIED",
                        block_index=index,
                        field_name="type_key",
                    )
                )
        elif block.type_key not in port_catalog:
            issues.append(
                _issue(
                    AdapterIssueCode.MISSING_PORT_CATALOG,
                    "Known block type has no authoritative graph port definition",
                    block_index=index,
                    field_name="type_key",
                )
            )

    fatal_codes = {
        AdapterIssueCode.UNKNOWN_BLOCK_TYPE,
        AdapterIssueCode.MISSING_PORT_CATALOG,
    }
    if any(item.code in fatal_codes for item in issues):
        raise GraphWorkspaceMappingError(tuple(issues))

    state = GraphWorkspaceState(
        stage_id=stage_id,
        nodes=snapshot.graph_nodes(),
        edges=snapshot.edges,
        wire_cells=snapshot.wire_cells,
        routes=snapshot.routes,
        working_revision=snapshot.working_revision,
        validated_revision=snapshot.validated_revision,
        saved_version=saved_version,
        dirty=dirty,
        last_validation=snapshot.last_graph_validation,
    )
    return GraphWorkspaceMapping(state, True, tuple(issues))


def prepare_graph_workspace_update(
    current: GraphWorkspaceState,
    result: GraphWorkspaceState,
    *,
    operation_accepted: bool,
) -> GraphWorkspaceUpdatePlan:
    """Validate and retain a domain result without mutating StageService state."""
    if not operation_accepted:
        return GraphWorkspaceUpdatePlan(
            False,
            False,
            False,
            current.working_revision,
            current.working_revision,
            None,
            (
                _issue(
                    AdapterIssueCode.OPERATION_REJECTED,
                    "Rejected domain operations do not produce an applicable update",
                ),
            ),
        )
    if result.stage_id != current.stage_id:
        return _rejected_plan(
            current,
            AdapterIssueCode.CROSS_STAGE_RESULT,
            "Domain result belongs to another Stage",
        )
    current_content = (current.nodes, current.edges, current.wire_cells, current.routes)
    result_content = (result.nodes, result.edges, result.wire_cells, result.routes)
    content_changed = result_content != current_content
    expected_revision = current.working_revision + (1 if content_changed else 0)
    if result.working_revision != expected_revision:
        return _rejected_plan(
            current,
            AdapterIssueCode.INVALID_RESULT_REVISION,
            "Domain result revision does not match its content transition",
        )
    if result.saved_version != current.saved_version:
        return _rejected_plan(
            current,
            AdapterIssueCode.INVALID_RESULT_SAVED_VERSION,
            "A domain content operation must not change saved_version",
        )
    if (content_changed and not result.dirty) or (
        not content_changed and result.dirty != current.dirty
    ):
        return _rejected_plan(
            current,
            AdapterIssueCode.INVALID_RESULT_DIRTY_FLAG,
            "Domain result dirty flag does not match its content transition",
        )

    requires_apply = result != current
    return GraphWorkspaceUpdatePlan(
        True,
        requires_apply,
        True,
        current.working_revision,
        result.working_revision,
        result,
        (),
    )


def to_stage_snapshot(
    current: StageSnapshot,
    plan: GraphWorkspaceUpdatePlan,
    *,
    new_block_metadata: Collection[StageBlockSnapshot] = (),
) -> StageSnapshot:
    """Build a full immutable snapshot and preserve all non-graph block metadata."""
    if (
        not plan.accepted
        or not plan.can_apply_to_current_stage_workspace
        or plan.target_state is None
    ):
        raise GraphWorkspaceMappingError(
            plan.issues
            or (
                _issue(
                    AdapterIssueCode.OPERATION_REJECTED,
                    "Update plan is not applicable",
                ),
            )
        )
    target = plan.target_state
    if target.stage_id != current.stage_id:
        raise GraphWorkspaceMappingError(
            (
                _issue(
                    AdapterIssueCode.CROSS_STAGE_RESULT,
                    "Domain result belongs to another Stage",
                ),
            )
        )
    old_by_id = {item.instance_id: item for item in current.blocks}
    supplied_by_id = {item.instance_id: item for item in new_block_metadata}
    blocks: list[StageBlockSnapshot] = []
    issues: list[AdapterIssue] = []
    for node in target.nodes:
        metadata = old_by_id.get(node.instance_id) or supplied_by_id.get(node.instance_id)
        if metadata is None:
            issues.append(
                _issue(
                    AdapterIssueCode.MISSING_NEW_BLOCK_METADATA,
                    f"New node {node.instance_id} has no Stage block metadata",
                )
            )
            continue
        if metadata.type_key != node.type_key:
            issues.append(
                _issue(
                    AdapterIssueCode.INVALID_NEW_BLOCK_METADATA,
                    f"Metadata type does not match node {node.instance_id}",
                )
            )
            continue
        blocks.append(replace(metadata, position=node.position, orientation=node.orientation))
    if issues:
        raise GraphWorkspaceMappingError(tuple(issues))

    content_changed = (
        current.graph_nodes(),
        current.edges,
        current.wire_cells,
        current.routes,
    ) != (target.nodes, target.edges, target.wire_cells, target.routes)
    return StageSnapshot(
        stage_id=current.stage_id,
        blocks=tuple(blocks),
        edges=target.edges,
        routes=target.routes,
        wire_cells=target.wire_cells,
        working_revision=target.working_revision,
        validated_revision=target.validated_revision,
        last_graph_validation=target.last_validation,
        program_validation_status=(
            ProgramValidationStatus.NOT_CHECKED
            if content_changed
            else current.program_validation_status
        ),
        port_catalog_version=current.port_catalog_version,
    )


def _rejected_plan(
    current: GraphWorkspaceState,
    code: AdapterIssueCode,
    message: str,
) -> GraphWorkspaceUpdatePlan:
    return GraphWorkspaceUpdatePlan(
        False,
        False,
        False,
        current.working_revision,
        current.working_revision,
        None,
        (_issue(code, message),),
    )


def to_stage_status_dto(
    state: GraphWorkspaceState,
    *,
    program_validation_status: ProgramValidationStatus = ProgramValidationStatus.NOT_CHECKED,
) -> StageStatusDTO:
    """Project independent graph, program and save status dimensions."""
    if not isinstance(program_validation_status, ProgramValidationStatus):
        raise TypeError("program_validation_status must be ProgramValidationStatus")
    return StageStatusDTO(
        state.stage_id,
        state.working_revision,
        state.saved_version,
        state.indicator_status,
        program_validation_status,
        state.save_status,
    )
