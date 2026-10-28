"""Pure, deterministic discovery of physical wire connectivity.

The module derives physical components and bindable port-to-port candidates from
an immutable ``GraphWorkspaceState``.  It deliberately performs no binding,
validation recording, persistence, revision update or other state mutation.
"""

from __future__ import annotations

from collections import Counter, defaultdict, deque
from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum
from uuid import UUID, uuid5

from .binding import RouteCandidate
from .model import (
    CardinalDirection,
    GridPos,
    PortDefinition,
    PortDirection,
    PortRef,
    WireChannel,
    WireStep,
)
from .orientation import adjacent_position, port_connection_position, world_side
from .state import GraphWorkspaceState


PortCatalog = Mapping[str, tuple[PortDefinition, ...]]

_DISCOVERY_NAMESPACE = UUID("8f2e5368-5260-4ad4-aa1b-05e610876c41")
_DIRECTION_ORDER = {
    CardinalDirection.NORTH: 0,
    CardinalDirection.EAST: 1,
    CardinalDirection.SOUTH: 2,
    CardinalDirection.WEST: 3,
}
_CHANNEL_ORDER = {WireChannel.SINGLE: 0, WireChannel.NS: 1, WireChannel.EW: 2}
_OPPOSITE = {
    CardinalDirection.NORTH: CardinalDirection.SOUTH,
    CardinalDirection.EAST: CardinalDirection.WEST,
    CardinalDirection.SOUTH: CardinalDirection.NORTH,
    CardinalDirection.WEST: CardinalDirection.EAST,
}


class PhysicalComponentStatus(str, Enum):
    COMPLETE = "COMPLETE"
    INCOMPLETE = "INCOMPLETE"
    INCOMPATIBLE = "INCOMPATIBLE"
    AMBIGUOUS = "AMBIGUOUS"
    ILLEGAL = "ILLEGAL"
    CYCLIC = "CYCLIC"


class DiscoveryDiagnosticCode(str, Enum):
    OPEN_WIRE = "OPEN_WIRE"
    INCOMPATIBLE_PORTS = "INCOMPATIBLE_PORTS"
    AMBIGUOUS_TOPOLOGY = "AMBIGUOUS_TOPOLOGY"
    ILLEGAL_TOPOLOGY = "ILLEGAL_TOPOLOGY"
    CYCLIC_COMPONENT = "CYCLIC_COMPONENT"


@dataclass(frozen=True, order=True)
class OpenWireEnd:
    position: GridPos
    channel: WireChannel
    direction: CardinalDirection


@dataclass(frozen=True)
class DiscoveryDiagnostic:
    code: DiscoveryDiagnosticCode
    component_id: UUID
    message: str
    port_refs: tuple[PortRef, ...] = ()
    wire_positions: tuple[GridPos, ...] = ()

    def __post_init__(self) -> None:
        if not isinstance(self.port_refs, tuple):
            raise TypeError("port_refs must be a tuple")
        if not isinstance(self.wire_positions, tuple):
            raise TypeError("wire_positions must be a tuple")


@dataclass(frozen=True)
class PhysicalWireComponent:
    component_id: UUID
    status: PhysicalComponentStatus
    vertices: tuple[WireStep, ...]
    ports: tuple[PortRef, ...]
    open_ends: tuple[OpenWireEnd, ...]

    def __post_init__(self) -> None:
        if not isinstance(self.vertices, tuple) or not all(
            isinstance(item, WireStep) for item in self.vertices
        ):
            raise TypeError("vertices must be a tuple of WireStep values")
        if not isinstance(self.ports, tuple) or not all(
            isinstance(item, PortRef) for item in self.ports
        ):
            raise TypeError("ports must be a tuple of PortRef values")
        if not isinstance(self.open_ends, tuple) or not all(
            isinstance(item, OpenWireEnd) for item in self.open_ends
        ):
            raise TypeError("open_ends must be a tuple of OpenWireEnd values")


@dataclass(frozen=True)
class DiscoveredConnection:
    candidate_id: UUID
    component_id: UUID
    source_port: PortRef
    target_port: PortRef
    steps: tuple[WireStep, ...]

    def __post_init__(self) -> None:
        if not isinstance(self.steps, tuple) or not all(
            isinstance(item, WireStep) for item in self.steps
        ):
            raise TypeError("steps must be a tuple of WireStep values")

    def as_route_candidate(self) -> RouteCandidate:
        """Create the existing binding-domain value without performing a bind."""
        return RouteCandidate(self.source_port, self.target_port, self.steps)


@dataclass(frozen=True)
class PhysicalTopologyDiscovery:
    components: tuple[PhysicalWireComponent, ...]
    candidates: tuple[DiscoveredConnection, ...]
    diagnostics: tuple[DiscoveryDiagnostic, ...]

    def __post_init__(self) -> None:
        expected = (
            ("components", self.components, PhysicalWireComponent),
            ("candidates", self.candidates, DiscoveredConnection),
            ("diagnostics", self.diagnostics, DiscoveryDiagnostic),
        )
        for field_name, values, item_type in expected:
            if not isinstance(values, tuple) or not all(
                isinstance(item, item_type) for item in values
            ):
                raise TypeError(f"{field_name} contains an invalid value")


@dataclass(frozen=True, order=True)
class _Vertex:
    position: GridPos
    channel: WireChannel


@dataclass(frozen=True)
class _PortAttachment:
    ref: PortRef
    definition: PortDefinition
    vertex: _Vertex
    direction: CardinalDirection


def _vertex_key(vertex: _Vertex) -> tuple[int, int, int, int]:
    return (
        vertex.position.x,
        vertex.position.y,
        vertex.position.z,
        _CHANNEL_ORDER[vertex.channel],
    )


def _port_key(attachment: _PortAttachment) -> tuple[int, str, tuple[int, int, int, int]]:
    return (attachment.ref.instance_id.int, attachment.ref.port_key, _vertex_key(attachment.vertex))


def _open_end_key(item: OpenWireEnd) -> tuple[int, int, int, int, int]:
    return (
        item.position.x,
        item.position.y,
        item.position.z,
        _CHANNEL_ORDER[item.channel],
        _DIRECTION_ORDER[item.direction],
    )


def _definitions(port_catalog: PortCatalog, type_key: str) -> tuple[PortDefinition, ...]:
    try:
        definitions = port_catalog[type_key]
    except (KeyError, LookupError):
        return ()
    return tuple(sorted(definitions, key=lambda item: (item.ordinal, item.port_key)))


def _component_id(
    vertices: tuple[_Vertex, ...],
    ports: tuple[_PortAttachment, ...],
) -> UUID:
    vertex_text = ";".join(
        f"{item.position.x},{item.position.y},{item.position.z},{item.channel.value}"
        for item in vertices
    )
    port_text = ";".join(
        f"{item.ref.instance_id}:{item.ref.port_key}" for item in ports
    )
    return uuid5(_DISCOVERY_NAMESPACE, f"component|{vertex_text}|ports|{port_text}")


def _candidate_id(
    component_id: UUID,
    source: PortRef,
    target: PortRef,
    steps: tuple[WireStep, ...],
) -> UUID:
    step_text = ";".join(
        f"{step.position.x},{step.position.y},{step.position.z},{step.channel.value}"
        for step in steps
    )
    return uuid5(
        _DISCOVERY_NAMESPACE,
        f"candidate|{component_id}|{source.instance_id}:{source.port_key}|"
        f"{target.instance_id}:{target.port_key}|{step_text}",
    )


def _diagnostic_key(item: DiscoveryDiagnostic) -> tuple[object, ...]:
    return (
        item.component_id.int,
        tuple(DiscoveryDiagnosticCode).index(item.code),
        tuple((ref.instance_id.int, ref.port_key) for ref in item.port_refs),
        tuple((pos.x, pos.y, pos.z) for pos in item.wire_positions),
        item.message,
    )


def _path(
    source: _Vertex,
    target: _Vertex,
    adjacency: Mapping[_Vertex, set[_Vertex]],
) -> tuple[_Vertex, ...]:
    queue = deque((source,))
    predecessor: dict[_Vertex, _Vertex | None] = {source: None}
    while queue:
        current = queue.popleft()
        if current == target:
            break
        for neighbor in sorted(adjacency[current], key=_vertex_key):
            if neighbor not in predecessor:
                predecessor[neighbor] = current
                queue.append(neighbor)
    if target not in predecessor:
        return ()
    result = []
    current: _Vertex | None = target
    while current is not None:
        result.append(current)
        current = predecessor[current]
    return tuple(reversed(result))


def discover_physical_topology(
    state: GraphWorkspaceState,
    port_catalog: PortCatalog,
) -> PhysicalTopologyDiscovery:
    """Derive physical components and bindable candidates without mutating ``state``."""
    vertex_counts: Counter[_Vertex] = Counter()
    position_counts: Counter[GridPos] = Counter(cell.position for cell in state.wire_cells)
    connections: dict[_Vertex, frozenset[CardinalDirection]] = {}
    vertices_by_position: dict[GridPos, list[_Vertex]] = defaultdict(list)
    for cell in state.wire_cells:
        for channel_state in cell.channels:
            vertex = _Vertex(cell.position, channel_state.channel)
            vertex_counts[vertex] += 1
            connections.setdefault(vertex, channel_state.connections)
            if vertex not in vertices_by_position[cell.position]:
                vertices_by_position[cell.position].append(vertex)

    vertices = tuple(sorted(connections, key=_vertex_key))
    adjacency: dict[_Vertex, set[_Vertex]] = {vertex: set() for vertex in vertices}
    illegal_vertices: set[_Vertex] = {
        vertex for vertex, count in vertex_counts.items() if count > 1
    }
    illegal_positions = {position for position, count in position_counts.items() if count > 1}

    for vertex in vertices:
        for direction in sorted(connections[vertex], key=_DIRECTION_ORDER.get):
            neighbor_position = adjacent_position(vertex.position, direction)
            reciprocal = [
                candidate
                for candidate in vertices_by_position.get(neighbor_position, ())
                if _OPPOSITE[direction] in connections[candidate]
            ]
            if len(reciprocal) > 1:
                illegal_vertices.add(vertex)
                illegal_vertices.update(reciprocal)
            for neighbor in reciprocal:
                adjacency[vertex].add(neighbor)
                adjacency[neighbor].add(vertex)

    attachments_by_vertex: dict[_Vertex, list[_PortAttachment]] = defaultdict(list)
    for node in state.nodes:
        for definition in _definitions(port_catalog, node.type_key):
            position = port_connection_position(node, definition)
            outward = world_side(node.orientation, definition.local_side)
            toward_node = _OPPOSITE[outward]
            for vertex in vertices_by_position.get(position, ()):
                if toward_node in connections[vertex]:
                    attachments_by_vertex[vertex].append(
                        _PortAttachment(
                            PortRef(node.instance_id, definition.port_key),
                            definition,
                            vertex,
                            toward_node,
                        )
                    )

    components: list[PhysicalWireComponent] = []
    candidates: list[DiscoveredConnection] = []
    diagnostics: list[DiscoveryDiagnostic] = []
    unvisited = set(vertices)
    while unvisited:
        start = min(unvisited, key=_vertex_key)
        queue = deque((start,))
        component_vertices: set[_Vertex] = set()
        while queue:
            current = queue.popleft()
            if current in component_vertices:
                continue
            component_vertices.add(current)
            unvisited.discard(current)
            queue.extend(sorted(adjacency[current] - component_vertices, key=_vertex_key))

        ordered_vertices = tuple(sorted(component_vertices, key=_vertex_key))
        component_ports = tuple(sorted(
            (
                attachment
                for vertex in ordered_vertices
                for attachment in attachments_by_vertex.get(vertex, ())
            ),
            key=_port_key,
        ))
        component_id = _component_id(ordered_vertices, component_ports)

        open_ends = []
        for vertex in ordered_vertices:
            for direction in sorted(connections[vertex], key=_DIRECTION_ORDER.get):
                has_neighbor = any(
                    neighbor.position == adjacent_position(vertex.position, direction)
                    for neighbor in adjacency[vertex]
                )
                has_port = any(
                    attachment.direction is direction
                    for attachment in attachments_by_vertex.get(vertex, ())
                )
                if not has_neighbor and not has_port:
                    open_ends.append(OpenWireEnd(vertex.position, vertex.channel, direction))
        ordered_open_ends = tuple(sorted(open_ends, key=_open_end_key))

        edge_count = sum(len(adjacency[vertex]) for vertex in ordered_vertices) // 2
        cyclic = edge_count >= len(ordered_vertices) and bool(ordered_vertices)
        illegal = any(
            vertex in illegal_vertices or vertex.position in illegal_positions
            for vertex in ordered_vertices
        )
        total_degrees = {
            vertex: len(adjacency[vertex]) + len(attachments_by_vertex.get(vertex, ()))
            for vertex in ordered_vertices
        }
        ambiguous = len(component_ports) > 2 or any(
            degree > 2 for degree in total_degrees.values()
        )
        port_refs = tuple(item.ref for item in component_ports)
        wire_positions = tuple(dict.fromkeys(item.position for item in ordered_vertices))

        if illegal:
            status = PhysicalComponentStatus.ILLEGAL
            diagnostics.append(DiscoveryDiagnostic(
                DiscoveryDiagnosticCode.ILLEGAL_TOPOLOGY,
                component_id,
                "Physical component contains duplicate or multiply connected wire vertices",
                port_refs,
                wire_positions,
            ))
        elif cyclic:
            status = PhysicalComponentStatus.CYCLIC
            diagnostics.append(DiscoveryDiagnostic(
                DiscoveryDiagnosticCode.CYCLIC_COMPONENT,
                component_id,
                "Physical wire component contains a cycle",
                port_refs,
                wire_positions,
            ))
        elif ambiguous:
            status = PhysicalComponentStatus.AMBIGUOUS
            diagnostics.append(DiscoveryDiagnostic(
                DiscoveryDiagnosticCode.AMBIGUOUS_TOPOLOGY,
                component_id,
                "Physical component has more than two endpoints or a branching attachment",
                port_refs,
                wire_positions,
            ))
        elif len(component_ports) < 2 or ordered_open_ends:
            status = PhysicalComponentStatus.INCOMPLETE
            diagnostics.append(DiscoveryDiagnostic(
                DiscoveryDiagnosticCode.OPEN_WIRE,
                component_id,
                "Physical component has an open end or fewer than two attached ports",
                port_refs,
                wire_positions,
            ))
        else:
            outgoing = tuple(
                item for item in component_ports
                if item.definition.direction is PortDirection.OUT
            )
            incoming = tuple(
                item for item in component_ports
                if item.definition.direction is PortDirection.IN
            )
            compatible = (
                len(outgoing) == 1
                and len(incoming) == 1
                and outgoing[0].definition.port_kind == incoming[0].definition.port_kind
            )
            if not compatible:
                status = PhysicalComponentStatus.INCOMPATIBLE
                diagnostics.append(DiscoveryDiagnostic(
                    DiscoveryDiagnosticCode.INCOMPATIBLE_PORTS,
                    component_id,
                    "Physical component endpoints are not one compatible OUT/IN port pair",
                    port_refs,
                    wire_positions,
                ))
            else:
                path = _path(outgoing[0].vertex, incoming[0].vertex, adjacency)
                if not path or len(path) != len(ordered_vertices):
                    status = PhysicalComponentStatus.ILLEGAL
                    diagnostics.append(DiscoveryDiagnostic(
                        DiscoveryDiagnosticCode.ILLEGAL_TOPOLOGY,
                        component_id,
                        "Physical component does not have one deterministic simple port-to-port path",
                        port_refs,
                        wire_positions,
                    ))
                else:
                    status = PhysicalComponentStatus.COMPLETE
                    steps = tuple(WireStep(item.position, item.channel) for item in path)
                    source = outgoing[0].ref
                    target = incoming[0].ref
                    candidates.append(DiscoveredConnection(
                        _candidate_id(component_id, source, target, steps),
                        component_id,
                        source,
                        target,
                        steps,
                    ))

        components.append(PhysicalWireComponent(
            component_id,
            status,
            tuple(WireStep(item.position, item.channel) for item in ordered_vertices),
            port_refs,
            ordered_open_ends,
        ))

    components.sort(key=lambda item: (
        _vertex_key(_Vertex(item.vertices[0].position, item.vertices[0].channel))
        if item.vertices else (0, 0, 0, 0)
    ))
    candidates.sort(key=lambda item: (
        item.source_port.instance_id.int,
        item.source_port.port_key,
        item.target_port.instance_id.int,
        item.target_port.port_key,
        item.candidate_id.int,
    ))
    diagnostics.sort(key=_diagnostic_key)
    return PhysicalTopologyDiscovery(
        tuple(components), tuple(candidates), tuple(diagnostics)
    )
