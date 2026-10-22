"""Atomic copy-on-write binding of resolved physical routes to semantic edges."""

from __future__ import annotations

from collections import Counter, defaultdict
from collections.abc import Callable
from dataclasses import dataclass
from uuid import UUID

from .diagnostics import Diagnostic, DiagnosticCode, Severity, sort_diagnostics
from .model import (
    EdgeKind,
    EdgeRoute,
    GraphEdge,
    PortDefinition,
    PortDirection,
    PortKind,
    PortRef,
    RouteStatus,
    WireStep,
)
from .state import GraphWorkspaceState, replace_content
from .validation import PortCatalog, validate_graph


@dataclass(frozen=True)
class RouteCandidate:
    source_port: PortRef
    target_port: PortRef
    steps: tuple[WireStep, ...]
    route_id: UUID | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.steps, tuple):
            raise TypeError("steps must be a tuple")
        if not all(isinstance(step, WireStep) for step in self.steps):
            raise ValueError("steps must contain only WireStep values")


@dataclass(frozen=True)
class BindEdgesCommand:
    stage_id: UUID
    player_uuid: UUID
    expected_working_revision: int
    candidates: tuple[RouteCandidate, ...]

    def __post_init__(self) -> None:
        if not isinstance(self.stage_id, UUID) or not isinstance(self.player_uuid, UUID):
            raise TypeError("stage_id and player_uuid must be UUID values")
        if not isinstance(self.expected_working_revision, int) or isinstance(
            self.expected_working_revision, bool
        ):
            raise TypeError("expected_working_revision must be an integer")
        if self.expected_working_revision < 0:
            raise ValueError("expected_working_revision must be non-negative")
        if not isinstance(self.candidates, tuple):
            raise TypeError("candidates must be a tuple")
        if not all(isinstance(item, RouteCandidate) for item in self.candidates):
            raise ValueError("candidates must contain only RouteCandidate values")


@dataclass(frozen=True)
class BindingResult:
    accepted: bool
    state: GraphWorkspaceState
    diagnostics: tuple[Diagnostic, ...]
    created_edge_ids: tuple[UUID, ...] = ()
    reused_edge_ids: tuple[UUID, ...] = ()

    def __post_init__(self) -> None:
        for field_name in ("diagnostics", "created_edge_ids", "reused_edge_ids"):
            if not isinstance(getattr(self, field_name), tuple):
                raise TypeError(f"{field_name} must be a tuple")


def _error(code: DiagnosticCode, message: str, **references: object) -> Diagnostic:
    return Diagnostic(code, Severity.ERROR, message, **references)


def _candidate_key(candidate: RouteCandidate) -> tuple[object, ...]:
    return (
        candidate.source_port.instance_id.int,
        candidate.source_port.port_key,
        candidate.target_port.instance_id.int,
        candidate.target_port.port_key,
        tuple(
            (step.position.x, step.position.y, step.position.z, step.channel.value)
            for step in candidate.steps
        ),
        -1 if candidate.route_id is None else candidate.route_id.int,
    )


def _semantic_key(stage_id: UUID, candidate: RouteCandidate) -> tuple:
    return (
        stage_id,
        candidate.source_port.instance_id,
        candidate.source_port.port_key,
        candidate.target_port.instance_id,
        candidate.target_port.port_key,
        EdgeKind.CONTROL,
    )


def _catalog_index(port_catalog: PortCatalog) -> dict[str, dict[str, PortDefinition]]:
    index: dict[str, dict[str, PortDefinition]] = {}
    for type_key, definitions in port_catalog.items():
        counts = Counter(item.port_key for item in definitions)
        index[type_key] = {
            item.port_key: item for item in definitions if counts[item.port_key] == 1
        }
    return index


def _preflight(
    state: GraphWorkspaceState,
    candidates: tuple[RouteCandidate, ...],
    port_catalog: PortCatalog,
) -> tuple[Diagnostic, ...]:
    diagnostics: list[Diagnostic] = []
    nodes = {node.instance_id: node for node in state.nodes}
    catalog = _catalog_index(port_catalog)
    seen_keys: dict[tuple, RouteCandidate] = {}
    route_ids: dict[UUID, RouteCandidate] = {}
    existing_routes_by_id = {route.route_id: route for route in state.routes}
    existing_keys = {edge.semantic_endpoint_key for edge in state.edges}
    candidate_additions: Counter[PortRef] = Counter()
    resolved: dict[PortRef, PortDefinition] = {}

    for candidate in candidates:
        key = _semantic_key(state.stage_id, candidate)
        if key in seen_keys:
            diagnostics.append(
                _error(
                    DiagnosticCode.DUPLICATE_EDGE,
                    "Bind command contains duplicate semantic candidates",
                    port_refs=(candidate.source_port, candidate.target_port),
                )
            )
        else:
            seen_keys[key] = candidate

        if candidate.route_id is not None:
            if candidate.route_id in route_ids:
                diagnostics.append(
                    _error(
                        DiagnosticCode.AMBIGUOUS_CONNECTION,
                        "Multiple candidates request the same route identifier",
                        port_refs=(candidate.source_port, candidate.target_port),
                    )
                )
            route_ids[candidate.route_id] = candidate
            existing_route = existing_routes_by_id.get(candidate.route_id)
            if existing_route is not None and existing_route.edge_id is not None:
                matching_edge = next(
                    (edge for edge in state.edges if edge.edge_id == existing_route.edge_id),
                    None,
                )
                if matching_edge is not None and matching_edge.semantic_endpoint_key != key:
                    diagnostics.append(
                        _error(
                            DiagnosticCode.AMBIGUOUS_CONNECTION,
                            "Requested route identifier belongs to another GraphEdge",
                            port_refs=(candidate.source_port, candidate.target_port),
                            edge_ids=(matching_edge.edge_id,),
                        )
                    )

        endpoint_definitions: list[tuple[PortRef, PortDefinition | None, str]] = []
        for port_ref, endpoint_name in (
            (candidate.source_port, "source"),
            (candidate.target_port, "target"),
        ):
            node = nodes.get(port_ref.instance_id)
            if node is None:
                diagnostics.append(
                    _error(
                        DiagnosticCode.UNKNOWN_NODE,
                        f"Route candidate references an unknown {endpoint_name} node",
                        node_ids=(port_ref.instance_id,),
                        port_refs=(port_ref,),
                    )
                )
                endpoint_definitions.append((port_ref, None, endpoint_name))
                continue
            if node.stage_id != state.stage_id:
                diagnostics.append(
                    _error(
                        DiagnosticCode.CROSS_STAGE_EDGE,
                        "Route candidate references a node from another Stage",
                        node_ids=(node.instance_id,),
                        port_refs=(port_ref,),
                    )
                )
            definitions = catalog.get(node.type_key)
            definition = None if definitions is None else definitions.get(port_ref.port_key)
            if definitions is None:
                diagnostics.append(
                    _error(
                        DiagnosticCode.UNKNOWN_NODE,
                        "Route candidate node type is absent from the port catalog",
                        node_ids=(node.instance_id,),
                        port_refs=(port_ref,),
                    )
                )
            elif definition is None:
                diagnostics.append(
                    _error(
                        DiagnosticCode.UNKNOWN_PORT,
                        f"Route candidate references an unknown {endpoint_name} port",
                        node_ids=(node.instance_id,),
                        port_refs=(port_ref,),
                    )
                )
            endpoint_definitions.append((port_ref, definition, endpoint_name))
            if definition is not None:
                resolved[port_ref] = definition

        source_definition = endpoint_definitions[0][1]
        target_definition = endpoint_definitions[1][1]
        if source_definition is not None and source_definition.direction is not PortDirection.OUT:
            diagnostics.append(
                _error(
                    DiagnosticCode.INVALID_PORT_DIRECTION,
                    "Route candidate source port must be OUT",
                    port_refs=(candidate.source_port,),
                )
            )
        if target_definition is not None and target_definition.direction is not PortDirection.IN:
            diagnostics.append(
                _error(
                    DiagnosticCode.INVALID_PORT_DIRECTION,
                    "Route candidate target port must be IN",
                    port_refs=(candidate.target_port,),
                )
            )
        incompatible = tuple(
            port_ref
            for port_ref, definition, _ in endpoint_definitions
            if definition is not None and definition.port_kind != PortKind.CONTROL
        )
        if incompatible:
            diagnostics.append(
                _error(
                    DiagnosticCode.INCOMPATIBLE_PORT_KIND,
                    "Route candidate supports CONTROL ports only",
                    port_refs=incompatible,
                )
            )
        if not candidate.steps:
            diagnostics.append(
                _error(
                    DiagnosticCode.OPEN_WIRE,
                    "Route candidate has no physical steps",
                    port_refs=(candidate.source_port, candidate.target_port),
                )
            )
        if key not in existing_keys and key in seen_keys and seen_keys[key] is candidate:
            candidate_additions[candidate.source_port] += 1
            candidate_additions[candidate.target_port] += 1

    existing_counts: Counter[PortRef] = Counter()
    for edge in state.edges:
        existing_counts[PortRef(edge.source_instance_id, edge.source_port_key)] += 1
        existing_counts[PortRef(edge.target_instance_id, edge.target_port_key)] += 1
    for port_ref, additions in candidate_additions.items():
        definition = resolved.get(port_ref)
        if definition is not None and existing_counts[port_ref] + additions > definition.max_edges:
            diagnostics.append(
                _error(
                    DiagnosticCode.PORT_CARDINALITY_VIOLATION,
                    "Binding would exceed the port maximum edge count",
                    port_refs=(port_ref,),
                )
            )
    return sort_diagnostics(diagnostics)


def _relevant_binding_diagnostics(
    diagnostics: tuple[Diagnostic, ...],
    affected_edge_ids: frozenset[UUID],
    affected_ports: frozenset[PortRef],
) -> tuple[Diagnostic, ...]:
    relevant = []
    for diagnostic in diagnostics:
        if diagnostic.edge_ids and affected_edge_ids.intersection(diagnostic.edge_ids):
            relevant.append(diagnostic)
            continue
        if (
            diagnostic.code is DiagnosticCode.PORT_CARDINALITY_VIOLATION
            and affected_ports.intersection(diagnostic.port_refs)
        ):
            relevant.append(diagnostic)
            continue
        if not (
            diagnostic.node_ids
            or diagnostic.port_refs
            or diagnostic.edge_ids
            or diagnostic.wire_positions
        ):
            relevant.append(diagnostic)
    return sort_diagnostics(relevant)


def _rejected(state: GraphWorkspaceState, diagnostics) -> BindingResult:
    return BindingResult(False, state, sort_diagnostics(diagnostics))


def bind_edges(
    state: GraphWorkspaceState,
    command: BindEdgesCommand,
    port_catalog: PortCatalog,
    *,
    edge_id_factory: Callable[[], UUID],
    route_id_factory: Callable[[], UUID],
) -> BindingResult:
    """Atomically bind resolved candidates without authorization, I/O or implicit save."""
    command_diagnostics = []
    if command.stage_id != state.stage_id:
        command_diagnostics.append(
            _error(
                DiagnosticCode.CROSS_STAGE_EDGE,
                "Bind command Stage does not match the workspace Stage",
            )
        )
    if command.expected_working_revision != state.working_revision:
        command_diagnostics.append(
            _error(
                DiagnosticCode.STALE_WORKING_REVISION,
                "Bind command working revision is stale",
            )
        )
    if command_diagnostics:
        return _rejected(state, command_diagnostics)

    candidates = tuple(sorted(command.candidates, key=_candidate_key))
    preflight = _preflight(state, candidates, port_catalog)
    if preflight:
        return _rejected(state, preflight)

    edges_by_key = {edge.semantic_endpoint_key: edge for edge in state.edges}
    existing_edge_ids = {edge.edge_id for edge in state.edges}
    routes_by_edge: dict[UUID, list[EdgeRoute]] = defaultdict(list)
    routes_by_id = {route.route_id: route for route in state.routes}
    for route in state.routes:
        if route.edge_id is not None:
            routes_by_edge[route.edge_id].append(route)

    ambiguity = []
    for candidate in candidates:
        existing_edge = edges_by_key.get(_semantic_key(state.stage_id, candidate))
        if existing_edge is not None and len(routes_by_edge[existing_edge.edge_id]) > 1:
            ambiguity.append(
                _error(
                    DiagnosticCode.AMBIGUOUS_CONNECTION,
                    "Existing GraphEdge has multiple active routes",
                    port_refs=(candidate.source_port, candidate.target_port),
                    edge_ids=(existing_edge.edge_id,),
                )
            )
    if ambiguity:
        return _rejected(state, ambiguity)

    new_edges = list(state.edges)
    new_routes: list[EdgeRoute] = []
    affected_edge_ids: set[UUID] = set()
    created_ids: list[UUID] = []
    reused_ids: list[UUID] = []
    planned_routes: list[EdgeRoute] = []
    allocated_route_ids = set(routes_by_id)

    for candidate in candidates:
        key = _semantic_key(state.stage_id, candidate)
        graph_edge = edges_by_key.get(key)
        if graph_edge is None:
            edge_id = edge_id_factory()
            if not isinstance(edge_id, UUID) or edge_id in existing_edge_ids:
                return _rejected(
                    state,
                    (_error(DiagnosticCode.DUPLICATE_EDGE, "Edge ID factory returned a collision"),),
                )
            graph_edge = GraphEdge(
                edge_id,
                state.stage_id,
                candidate.source_port.instance_id,
                candidate.source_port.port_key,
                candidate.target_port.instance_id,
                candidate.target_port.port_key,
                EdgeKind.CONTROL,
            )
            edges_by_key[key] = graph_edge
            existing_edge_ids.add(edge_id)
            new_edges.append(graph_edge)
            created_ids.append(edge_id)
        else:
            reused_ids.append(graph_edge.edge_id)

        existing_routes = routes_by_edge.get(graph_edge.edge_id, [])
        if candidate.route_id is not None:
            route_id = candidate.route_id
        elif existing_routes:
            route_id = existing_routes[0].route_id
        else:
            route_id = route_id_factory()
        if not isinstance(route_id, UUID):
            return _rejected(
                state,
                (_error(DiagnosticCode.AMBIGUOUS_CONNECTION, "Route ID factory returned a non-UUID"),),
            )
        existing_same_route = routes_by_id.get(route_id)
        if (
            route_id in allocated_route_ids
            and not (
                existing_same_route is not None
                and existing_same_route.edge_id == graph_edge.edge_id
            )
        ):
            return _rejected(
                state,
                (_error(DiagnosticCode.AMBIGUOUS_CONNECTION, "Route ID collision"),),
            )
        allocated_route_ids.add(route_id)
        affected_edge_ids.add(graph_edge.edge_id)
        planned_routes.append(
            EdgeRoute(
                route_id,
                state.stage_id,
                graph_edge.edge_id,
                candidate.source_port,
                candidate.target_port,
                candidate.steps,
                RouteStatus.VALID,
            )
        )

    new_routes.extend(
        route for route in state.routes if route.edge_id not in affected_edge_ids
    )
    new_routes.extend(planned_routes)
    candidate_state = replace_content(
        state,
        edges=tuple(new_edges),
        routes=tuple(new_routes),
    )
    report = validate_graph(candidate_state, port_catalog)
    affected_ports = frozenset(
        port
        for candidate in candidates
        for port in (candidate.source_port, candidate.target_port)
    )
    blocking = _relevant_binding_diagnostics(
        report.diagnostics,
        frozenset(affected_edge_ids),
        affected_ports,
    )
    if any(item.severity is Severity.ERROR for item in blocking):
        return _rejected(state, blocking)

    return BindingResult(
        True,
        candidate_state,
        blocking,
        tuple(created_ids),
        tuple(reused_ids),
    )
