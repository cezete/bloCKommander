from uuid import UUID

from hangar.graph.model import (
    CardinalDirection,
    EdgeKind,
    EdgeRoute,
    GraphEdge,
    GraphNode,
    GridPos,
    LocalSide,
    PortDefinition,
    PortDirection,
    PortKind,
    PortRef,
    RouteStatus,
    WireCell,
    WireChannel,
    WireChannelState,
    WireStep,
)
from hangar.graph.state import GraphWorkspaceState


STAGE_ID = UUID("00000000-0000-0000-0000-000000000100")
OTHER_STAGE_ID = UUID("00000000-0000-0000-0000-000000000200")
START_ID = UUID("00000000-0000-0000-0000-000000000101")
IF_ID = UUID("00000000-0000-0000-0000-000000000102")
SET_ID = UUID("00000000-0000-0000-0000-000000000103")
PRINT_ID = UUID("00000000-0000-0000-0000-000000000104")
END_IF_ID = UUID("00000000-0000-0000-0000-000000000105")
STOP_ID = UUID("00000000-0000-0000-0000-000000000106")
UNKNOWN_ID = UUID("00000000-0000-0000-0000-000000000199")


def port(key, direction, side, ordinal=0, branch_label=None):
    return PortDefinition(
        key,
        PortKind.CONTROL,
        direction,
        side,
        ordinal,
        1,
        1,
        branch_label,
    )


PORT_CATALOG = {
    "START": (port("out", PortDirection.OUT, LocalSide.FRONT),),
    "IF": (
        port("in", PortDirection.IN, LocalSide.FRONT),
        port("true", PortDirection.OUT, LocalSide.RIGHT, branch_label="1"),
        port("false", PortDirection.OUT, LocalSide.LEFT, branch_label="0"),
    ),
    "SET": (
        port("in", PortDirection.IN, LocalSide.BACK),
        port("out", PortDirection.OUT, LocalSide.FRONT),
    ),
    "PRINT": (
        port("in", PortDirection.IN, LocalSide.BACK),
        port("out", PortDirection.OUT, LocalSide.FRONT),
    ),
    "END_IF": (
        port("in_true", PortDirection.IN, LocalSide.RIGHT, branch_label="1"),
        port("in_false", PortDirection.IN, LocalSide.LEFT, branch_label="0"),
        port("out", PortDirection.OUT, LocalSide.FRONT),
    ),
    "STOP": (port("in", PortDirection.IN, LocalSide.BACK),),
}


NODE_SPECS = (
    (START_ID, "START", 0),
    (IF_ID, "IF", 2),
    (SET_ID, "SET", 4),
    (PRINT_ID, "PRINT", 6),
    (END_IF_ID, "END_IF", 8),
    (STOP_ID, "STOP", 10),
)


def node(instance_id, type_key, x, stage_id=STAGE_ID):
    return GraphNode(
        instance_id,
        stage_id,
        type_key,
        GridPos(x, 0, 0),
        CardinalDirection.NORTH,
    )


def demo_nodes():
    return tuple(node(instance_id, type_key, x) for instance_id, type_key, x in NODE_SPECS)


def edge(number, source_id, source_port, target_id, target_port, stage_id=STAGE_ID):
    return GraphEdge(
        UUID(f"00000000-0000-0000-0000-{number:012d}"),
        stage_id,
        source_id,
        source_port,
        target_id,
        target_port,
        EdgeKind.CONTROL,
    )


def demo_edges():
    return (
        edge(1001, START_ID, "out", IF_ID, "in"),
        edge(1002, IF_ID, "true", SET_ID, "in"),
        edge(1003, SET_ID, "out", END_IF_ID, "in_true"),
        edge(1004, IF_ID, "false", PRINT_ID, "in"),
        edge(1005, PRINT_ID, "out", END_IF_ID, "in_false"),
        edge(1006, END_IF_ID, "out", STOP_ID, "in"),
    )


def workspace(
    *,
    nodes=(),
    edges=(),
    wire_cells=(),
    routes=(),
    revision=7,
):
    return GraphWorkspaceState(
        stage_id=STAGE_ID,
        nodes=tuple(nodes),
        edges=tuple(edges),
        wire_cells=tuple(wire_cells),
        routes=tuple(routes),
        working_revision=revision,
        validated_revision=None,
        saved_version=3,
        dirty=True,
        last_validation=None,
    )


def full_demo_state():
    return workspace(nodes=demo_nodes(), edges=demo_edges())


def start_stop_state(*, wire_cells=(), routes=()):
    nodes = (
        node(START_ID, "START", 0),
        node(STOP_ID, "STOP", 2),
    )
    connection = edge(1101, START_ID, "out", STOP_ID, "in")
    return workspace(
        nodes=nodes,
        edges=(connection,),
        wire_cells=wire_cells,
        routes=routes,
    )


def straight_cell(position=GridPos(1, 0, 0)):
    return WireCell(
        position,
        (
            WireChannelState(
                WireChannel.SINGLE,
                frozenset({CardinalDirection.EAST, CardinalDirection.WEST}),
            ),
        ),
    )


def cross_cell(position=GridPos(1, 0, 0)):
    return WireCell(
        position,
        (
            WireChannelState(
                WireChannel.NS,
                frozenset({CardinalDirection.NORTH, CardinalDirection.SOUTH}),
            ),
            WireChannelState(
                WireChannel.EW,
                frozenset({CardinalDirection.EAST, CardinalDirection.WEST}),
            ),
        ),
    )


def bound_route(
    *,
    status=RouteStatus.VALID,
    source_port=None,
    target_port=None,
    steps=None,
    edge_id=None,
    stage_id=STAGE_ID,
):
    return EdgeRoute(
        UUID("00000000-0000-0000-0000-000000002001"),
        stage_id,
        UUID("00000000-0000-0000-0000-000000001101") if edge_id is None else edge_id,
        PortRef(START_ID, "out") if source_port is None else source_port,
        PortRef(STOP_ID, "in") if target_port is None else target_port,
        (WireStep(GridPos(1, 0, 0), WireChannel.SINGLE),) if steps is None else steps,
        status,
    )
