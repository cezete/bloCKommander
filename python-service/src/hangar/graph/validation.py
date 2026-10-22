"""Pure validation of generic graph connectivity and representable route state.

This module validates node, port, edge, cardinality and basic route consistency.
It deliberately does *not* validate complete program structure: IF/END_IF branch
provenance, reachability and cycle rules remain deferred. A returned report must
therefore not be treated as a full-program VALID decision without that later
structural rule layer. The function never records its report on the workspace.
"""

from __future__ import annotations

from collections import Counter, defaultdict
from collections.abc import Mapping

from .diagnostics import Diagnostic, DiagnosticCode, Severity, ValidationReport
from .model import (
    CardinalDirection,
    EdgeRoute,
    GraphEdge,
    PortDefinition,
    PortDirection,
    PortRef,
    RouteStatus,
    WireChannel,
)
from .state import GraphWorkspaceState


PortCatalog = Mapping[str, tuple[PortDefinition, ...]]

VALIDATED_RULE_GROUPS = (
    "catalog",
    "node_references",
    "port_references",
    "port_direction_and_kind",
    "duplicate_edges",
    "port_cardinality",
    "representable_route_consistency",
)

DEFERRED_RULE_GROUPS = (
    "if_end_if_branch_provenance",
    "reachability",
    "cycle_detection",
)


def _error(
    code: DiagnosticCode,
    message: str,
    **references: object,
) -> Diagnostic:
    return Diagnostic(code, Severity.ERROR, message, **references)


def _catalog_index(
    port_catalog: PortCatalog,
) -> tuple[dict[str, dict[str, PortDefinition]], list[Diagnostic]]:
    index: dict[str, dict[str, PortDefinition]] = {}
    diagnostics: list[Diagnostic] = []
    for type_key, definitions in port_catalog.items():
        if not isinstance(definitions, tuple):
            raise TypeError("port catalog values must be tuples")
        counts = Counter(definition.port_key for definition in definitions)
        duplicate_keys = sorted(key for key, count in counts.items() if count > 1)
        for port_key in duplicate_keys:
            diagnostics.append(
                _error(
                    DiagnosticCode.UNKNOWN_PORT,
                    f"Port catalog type {type_key!r} contains duplicate port key {port_key!r}",
                )
            )
        index[type_key] = {}
        for definition in definitions:
            if counts[definition.port_key] == 1:
                index[type_key][definition.port_key] = definition
    return index, diagnostics


def _validate_edge(
    edge: GraphEdge,
    state: GraphWorkspaceState,
    nodes_by_id: dict,
    catalog_index: dict[str, dict[str, PortDefinition]],
    diagnostics: list[Diagnostic],
    cardinalities: Counter[PortRef],
) -> None:
    source = nodes_by_id.get(edge.source_instance_id)
    target = nodes_by_id.get(edge.target_instance_id)

    if source is None:
        diagnostics.append(
            _error(
                DiagnosticCode.UNKNOWN_NODE,
                "GraphEdge references an unknown source node",
                node_ids=(edge.source_instance_id,),
                edge_ids=(edge.edge_id,),
            )
        )
    if target is None:
        diagnostics.append(
            _error(
                DiagnosticCode.UNKNOWN_NODE,
                "GraphEdge references an unknown target node",
                node_ids=(edge.target_instance_id,),
                edge_ids=(edge.edge_id,),
            )
        )

    referenced_nodes = tuple(node for node in (source, target) if node is not None)
    if (
        edge.stage_id != state.stage_id
        or any(node.stage_id != state.stage_id for node in referenced_nodes)
        or (source is not None and target is not None and source.stage_id != target.stage_id)
    ):
        diagnostics.append(
            _error(
                DiagnosticCode.CROSS_STAGE_EDGE,
                "GraphEdge crosses a Stage boundary",
                node_ids=tuple(node.instance_id for node in referenced_nodes),
                edge_ids=(edge.edge_id,),
            )
        )

    source_definition = _resolve_port(
        edge,
        source,
        edge.source_port_key,
        "source",
        catalog_index,
        diagnostics,
    )
    target_definition = _resolve_port(
        edge,
        target,
        edge.target_port_key,
        "target",
        catalog_index,
        diagnostics,
    )

    source_ref = PortRef(edge.source_instance_id, edge.source_port_key)
    target_ref = PortRef(edge.target_instance_id, edge.target_port_key)
    if source_definition is not None:
        cardinalities[source_ref] += 1
        if source_definition.direction is not PortDirection.OUT:
            diagnostics.append(
                _error(
                    DiagnosticCode.INVALID_PORT_DIRECTION,
                    "GraphEdge source port must be OUT",
                    port_refs=(source_ref,),
                    edge_ids=(edge.edge_id,),
                )
            )
    if target_definition is not None:
        cardinalities[target_ref] += 1
        if target_definition.direction is not PortDirection.IN:
            diagnostics.append(
                _error(
                    DiagnosticCode.INVALID_PORT_DIRECTION,
                    "GraphEdge target port must be IN",
                    port_refs=(target_ref,),
                    edge_ids=(edge.edge_id,),
                )
            )

    incompatible_refs = []
    if source_definition is not None and source_definition.port_kind != edge.edge_kind:
        incompatible_refs.append(source_ref)
    if target_definition is not None and target_definition.port_kind != edge.edge_kind:
        incompatible_refs.append(target_ref)
    if incompatible_refs:
        diagnostics.append(
            _error(
                DiagnosticCode.INCOMPATIBLE_PORT_KIND,
                "GraphEdge kind is incompatible with one or more endpoint ports",
                port_refs=tuple(incompatible_refs),
                edge_ids=(edge.edge_id,),
            )
        )


def _resolve_port(
    edge: GraphEdge,
    node,
    port_key: str,
    endpoint_name: str,
    catalog_index: dict[str, dict[str, PortDefinition]],
    diagnostics: list[Diagnostic],
) -> PortDefinition | None:
    if node is None:
        return None
    definitions = catalog_index.get(node.type_key)
    if definitions is None:
        diagnostics.append(
            _error(
                DiagnosticCode.UNKNOWN_NODE,
                f"Node type {node.type_key!r} is absent from the port catalog",
                node_ids=(node.instance_id,),
                edge_ids=(edge.edge_id,),
            )
        )
        return None
    definition = definitions.get(port_key)
    if definition is None:
        diagnostics.append(
            _error(
                DiagnosticCode.UNKNOWN_PORT,
                f"GraphEdge references an unknown {endpoint_name} port",
                node_ids=(node.instance_id,),
                port_refs=(PortRef(node.instance_id, port_key),),
                edge_ids=(edge.edge_id,),
            )
        )
    return definition


def _validate_cardinalities(
    state: GraphWorkspaceState,
    catalog_index: dict[str, dict[str, PortDefinition]],
    cardinalities: Counter[PortRef],
    diagnostics: list[Diagnostic],
) -> None:
    for node in state.nodes:
        definitions = catalog_index.get(node.type_key)
        if definitions is None:
            if not any(
                item.code is DiagnosticCode.UNKNOWN_NODE
                and node.instance_id in item.node_ids
                for item in diagnostics
            ):
                diagnostics.append(
                    _error(
                        DiagnosticCode.UNKNOWN_NODE,
                        f"Node type {node.type_key!r} is absent from the port catalog",
                        node_ids=(node.instance_id,),
                    )
                )
            continue
        for port_key, definition in definitions.items():
            port_ref = PortRef(node.instance_id, port_key)
            count = cardinalities[port_ref]
            if count < definition.min_edges or count > definition.max_edges:
                diagnostics.append(
                    _error(
                        DiagnosticCode.PORT_CARDINALITY_VIOLATION,
                        (
                            f"Port {port_key!r} has {count} edges; expected between "
                            f"{definition.min_edges} and {definition.max_edges}"
                        ),
                        node_ids=(node.instance_id,),
                        port_refs=(port_ref,),
                    )
                )


def _validate_duplicates(
    edges: tuple[GraphEdge, ...],
    diagnostics: list[Diagnostic],
) -> None:
    groups: dict[tuple, list[GraphEdge]] = defaultdict(list)
    for edge in edges:
        groups[edge.semantic_endpoint_key].append(edge)
    for duplicate_edges in groups.values():
        if len(duplicate_edges) < 2:
            continue
        first = duplicate_edges[0]
        diagnostics.append(
            _error(
                DiagnosticCode.DUPLICATE_EDGE,
                "Multiple GraphEdges have the same semantic endpoint key",
                port_refs=(
                    PortRef(first.source_instance_id, first.source_port_key),
                    PortRef(first.target_instance_id, first.target_port_key),
                ),
                edge_ids=tuple(edge.edge_id for edge in duplicate_edges),
            )
        )


def _validate_route(
    route: EdgeRoute,
    state: GraphWorkspaceState,
    edges_by_id: dict,
    cells_by_position: dict,
    diagnostics: list[Diagnostic],
) -> None:
    if (
        route.status is RouteStatus.INCOMPLETE
        or route.edge_id is None
        or route.source_port is None
        or route.target_port is None
        or not route.steps
    ):
        diagnostics.append(
            _error(
                DiagnosticCode.OPEN_WIRE,
                "Route is incomplete or has an open endpoint",
                wire_positions=tuple(step.position for step in route.steps),
            )
        )
    elif route.status is RouteStatus.BROKEN:
        diagnostics.append(_route_error(DiagnosticCode.BROKEN_ROUTE, route, "Route is broken"))
    elif route.status is RouteStatus.AMBIGUOUS:
        diagnostics.append(
            _route_error(
                DiagnosticCode.AMBIGUOUS_CONNECTION,
                route,
                "Route has ambiguous connectivity",
            )
        )
    elif route.status is RouteStatus.ILLEGAL:
        diagnostics.append(
            _route_error(
                DiagnosticCode.ILLEGAL_JUNCTION,
                route,
                "Route contains an illegal topology",
            )
        )

    edge = None if route.edge_id is None else edges_by_id.get(route.edge_id)
    if route.edge_id is not None and edge is None:
        diagnostics.append(
            _route_error(
                DiagnosticCode.BROKEN_ROUTE,
                route,
                "Route references an unknown GraphEdge",
            )
        )
    if route.stage_id != state.stage_id or (
        edge is not None and edge.stage_id != route.stage_id
    ):
        diagnostics.append(
            _route_error(
                DiagnosticCode.BROKEN_ROUTE,
                route,
                "Route and GraphEdge do not belong to the workspace Stage",
            )
        )
    if edge is not None and route.source_port is not None and route.target_port is not None:
        expected_source = PortRef(edge.source_instance_id, edge.source_port_key)
        expected_target = PortRef(edge.target_instance_id, edge.target_port_key)
        if route.source_port != expected_source or route.target_port != expected_target:
            diagnostics.append(
                _route_error(
                    DiagnosticCode.BROKEN_ROUTE,
                    route,
                    "Route endpoints do not match its GraphEdge",
                )
            )

    for step in route.steps:
        cell = cells_by_position.get(step.position)
        if cell is None:
            diagnostics.append(
                _route_error(
                    DiagnosticCode.BROKEN_ROUTE,
                    route,
                    "Route step has no corresponding WireCell",
                    wire_positions=(step.position,),
                )
            )
            continue
        available_channels = {state.channel for state in cell.channels}
        if step.channel not in available_channels:
            diagnostics.append(
                _route_error(
                    DiagnosticCode.INVALID_CROSS_CHANNEL,
                    route,
                    "Route step selects a channel absent from its WireCell",
                    wire_positions=(step.position,),
                )
            )

    for current, following in zip(route.steps, route.steps[1:]):
        dx = following.position.x - current.position.x
        dy = following.position.y - current.position.y
        dz = following.position.z - current.position.z
        if abs(dx) + abs(dy) + abs(dz) != 1 or dy != 0:
            diagnostics.append(
                _route_error(
                    DiagnosticCode.BROKEN_ROUTE,
                    route,
                    "Consecutive route steps are not horizontally adjacent",
                    wire_positions=(current.position, following.position),
                )
            )
            continue
        direction = _direction_from_delta(dx, dz)
        opposite = _opposite(direction)
        current_cell = cells_by_position.get(current.position)
        following_cell = cells_by_position.get(following.position)
        current_connections = _connections(current_cell, current.channel)
        following_connections = _connections(following_cell, following.channel)
        if direction not in current_connections or opposite not in following_connections:
            diagnostics.append(
                _route_error(
                    DiagnosticCode.BROKEN_ROUTE,
                    route,
                    "Adjacent route steps are not connected in both directions",
                    wire_positions=(current.position, following.position),
                )
            )


def _route_error(
    code: DiagnosticCode,
    route: EdgeRoute,
    message: str,
    *,
    wire_positions=None,
) -> Diagnostic:
    return _error(
        code,
        message,
        port_refs=tuple(
            port for port in (route.source_port, route.target_port) if port is not None
        ),
        edge_ids=(() if route.edge_id is None else (route.edge_id,)),
        wire_positions=(
            tuple(step.position for step in route.steps)
            if wire_positions is None
            else wire_positions
        ),
    )


def _connections(cell, channel: WireChannel) -> frozenset[CardinalDirection]:
    if cell is None:
        return frozenset()
    for channel_state in cell.channels:
        if channel_state.channel is channel:
            return channel_state.connections
    return frozenset()


def _direction_from_delta(dx: int, dz: int) -> CardinalDirection:
    return {
        (0, -1): CardinalDirection.NORTH,
        (1, 0): CardinalDirection.EAST,
        (0, 1): CardinalDirection.SOUTH,
        (-1, 0): CardinalDirection.WEST,
    }[(dx, dz)]


def _opposite(direction: CardinalDirection) -> CardinalDirection:
    return {
        CardinalDirection.NORTH: CardinalDirection.SOUTH,
        CardinalDirection.EAST: CardinalDirection.WEST,
        CardinalDirection.SOUTH: CardinalDirection.NORTH,
        CardinalDirection.WEST: CardinalDirection.EAST,
    }[direction]


def validate_graph(
    state: GraphWorkspaceState,
    port_catalog: PortCatalog,
) -> ValidationReport:
    """Validate generic connectivity only, without mutating or recording state."""
    catalog_index, diagnostics = _catalog_index(port_catalog)
    nodes_by_id = {node.instance_id: node for node in state.nodes}
    cardinalities: Counter[PortRef] = Counter()

    for edge in state.edges:
        _validate_edge(
            edge,
            state,
            nodes_by_id,
            catalog_index,
            diagnostics,
            cardinalities,
        )
    _validate_duplicates(state.edges, diagnostics)
    _validate_cardinalities(state, catalog_index, cardinalities, diagnostics)

    edges_by_id = {edge.edge_id: edge for edge in state.edges}
    cells_by_position = {cell.position: cell for cell in state.wire_cells}
    for route in state.routes:
        _validate_route(route, state, edges_by_id, cells_by_position, diagnostics)

    return ValidationReport(state.working_revision, tuple(diagnostics))
