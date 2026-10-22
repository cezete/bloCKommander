"""Immutable graph workspace state and pure content/version transitions.

The four top-level content collections are semantically unordered and are stored
in a deterministic canonical order. Route step order remains meaningful inside
each EdgeRoute and is never reordered.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from enum import Enum
from uuid import UUID

from .diagnostics import ValidationReport
from .model import EdgeRoute, GraphEdge, GraphNode, WireCell


class GraphStatus(str, Enum):
    DRAFT = "DRAFT"
    VALID = "VALID"


class SaveStatus(str, Enum):
    CLEAN = "CLEAN"
    DIRTY = "DIRTY"


class EdgeIndicatorStatus(str, Enum):
    CHECK_REQUIRED = "CHECK_REQUIRED"
    OK = "OK"
    ERRORS = "ERRORS"


def _node_key(node: GraphNode) -> tuple[object, ...]:
    return (
        node.instance_id.int,
        node.stage_id.int,
        node.type_key,
        node.position.x,
        node.position.y,
        node.position.z,
        node.orientation.value,
    )


def _edge_key(edge: GraphEdge) -> tuple[object, ...]:
    return (
        edge.edge_id.int,
        edge.stage_id.int,
        edge.source_instance_id.int,
        edge.source_port_key,
        edge.target_instance_id.int,
        edge.target_port_key,
        edge.edge_kind.value,
    )


def _wire_cell_key(cell: WireCell) -> tuple[object, ...]:
    channels = tuple(
        sorted(
            (
                state.channel.value,
                tuple(sorted(direction.value for direction in state.connections)),
            )
            for state in cell.channels
        )
    )
    return (cell.position.x, cell.position.y, cell.position.z, channels)


def _route_key(route: EdgeRoute) -> tuple[object, ...]:
    source = (
        (-1, "")
        if route.source_port is None
        else (route.source_port.instance_id.int, route.source_port.port_key)
    )
    target = (
        (-1, "")
        if route.target_port is None
        else (route.target_port.instance_id.int, route.target_port.port_key)
    )
    steps = tuple(
        (step.position.x, step.position.y, step.position.z, step.channel.value)
        for step in route.steps
    )
    return (
        route.route_id.int,
        route.stage_id.int,
        -1 if route.edge_id is None else route.edge_id.int,
        source,
        target,
        steps,
        route.status.value,
    )


@dataclass(frozen=True)
class GraphWorkspaceState:
    stage_id: UUID
    nodes: tuple[GraphNode, ...]
    edges: tuple[GraphEdge, ...]
    wire_cells: tuple[WireCell, ...]
    routes: tuple[EdgeRoute, ...]
    working_revision: int
    validated_revision: int | None
    saved_version: int
    dirty: bool
    last_validation: ValidationReport | None

    def __post_init__(self) -> None:
        if not isinstance(self.stage_id, UUID):
            raise TypeError("stage_id must be a UUID")
        if not isinstance(self.dirty, bool):
            raise TypeError("dirty must be a boolean")
        if self.last_validation is not None and not isinstance(
            self.last_validation, ValidationReport
        ):
            raise TypeError("last_validation must be a ValidationReport or None")
        collections = (
            ("nodes", self.nodes, GraphNode, _node_key),
            ("edges", self.edges, GraphEdge, _edge_key),
            ("wire_cells", self.wire_cells, WireCell, _wire_cell_key),
            ("routes", self.routes, EdgeRoute, _route_key),
        )
        for field_name, values, item_type, key in collections:
            if not isinstance(values, tuple):
                raise TypeError(f"{field_name} must be a tuple")
            if not all(isinstance(item, item_type) for item in values):
                raise ValueError(f"{field_name} contains an invalid value")
            object.__setattr__(self, field_name, tuple(sorted(values, key=key)))

        if not isinstance(self.working_revision, int) or isinstance(
            self.working_revision, bool
        ):
            raise TypeError("working_revision must be an integer")
        if not isinstance(self.saved_version, int) or isinstance(self.saved_version, bool):
            raise TypeError("saved_version must be an integer")
        if self.working_revision < 0:
            raise ValueError("working_revision must be non-negative")
        if self.saved_version < 0:
            raise ValueError("saved_version must be non-negative")
        if self.validated_revision is not None:
            if not isinstance(self.validated_revision, int) or isinstance(
                self.validated_revision, bool
            ):
                raise TypeError("validated_revision must be an integer or None")
            if self.validated_revision < 0:
                raise ValueError("validated_revision must be non-negative")
            if self.validated_revision > self.working_revision:
                raise ValueError("validated_revision cannot be in the future")
        if (
            self.last_validation is not None
            and self.last_validation.revision > self.working_revision
        ):
            raise ValueError("last_validation cannot refer to a future revision")

    @property
    def graph_status(self) -> GraphStatus:
        report = self.last_validation
        if (
            self.validated_revision == self.working_revision
            and report is not None
            and report.revision == self.working_revision
            and not report.has_blocking_errors
        ):
            return GraphStatus.VALID
        return GraphStatus.DRAFT

    @property
    def save_status(self) -> SaveStatus:
        return SaveStatus.DIRTY if self.dirty else SaveStatus.CLEAN

    @property
    def indicator_status(self) -> EdgeIndicatorStatus:
        report = self.last_validation
        if report is None or report.revision != self.working_revision:
            return EdgeIndicatorStatus.CHECK_REQUIRED
        if self.graph_status is GraphStatus.VALID:
            return EdgeIndicatorStatus.OK
        if report.has_blocking_errors:
            return EdgeIndicatorStatus.ERRORS
        return EdgeIndicatorStatus.CHECK_REQUIRED


def replace_content(
    state: GraphWorkspaceState,
    *,
    nodes: tuple[GraphNode, ...] | None = None,
    edges: tuple[GraphEdge, ...] | None = None,
    wire_cells: tuple[WireCell, ...] | None = None,
    routes: tuple[EdgeRoute, ...] | None = None,
) -> GraphWorkspaceState:
    """Replace unordered content collections and increment once only on a real change."""
    candidate = replace(
        state,
        nodes=state.nodes if nodes is None else nodes,
        edges=state.edges if edges is None else edges,
        wire_cells=state.wire_cells if wire_cells is None else wire_cells,
        routes=state.routes if routes is None else routes,
    )
    current_content = (state.nodes, state.edges, state.wire_cells, state.routes)
    candidate_content = (
        candidate.nodes,
        candidate.edges,
        candidate.wire_cells,
        candidate.routes,
    )
    if candidate_content == current_content:
        return candidate
    return replace(
        candidate,
        working_revision=state.working_revision + 1,
        dirty=True,
    )


def record_validation(
    state: GraphWorkspaceState,
    report: ValidationReport,
    *,
    validated: bool,
) -> GraphWorkspaceState:
    """Record an explicit validation outcome for exactly the current revision."""
    if not isinstance(validated, bool):
        raise TypeError("validated must be a boolean")
    if report.revision != state.working_revision:
        raise ValueError("validation report must match the current working revision")
    if validated and report.has_blocking_errors:
        raise ValueError("successful validation cannot contain blocking errors")
    if not validated and not report.has_blocking_errors:
        raise ValueError("failed validation must contain a blocking error")
    return replace(
        state,
        validated_revision=(report.revision if validated else state.validated_revision),
        last_validation=report,
    )


def mark_saved(
    state: GraphWorkspaceState,
    new_saved_version: int,
) -> GraphWorkspaceState:
    """Represent one successful optimistic save without changing content revision."""
    if not isinstance(new_saved_version, int) or isinstance(new_saved_version, bool):
        raise TypeError("new_saved_version must be an integer")
    if new_saved_version != state.saved_version + 1:
        raise ValueError("new_saved_version must be exactly saved_version + 1")
    return replace(state, saved_version=new_saved_version, dirty=False)
