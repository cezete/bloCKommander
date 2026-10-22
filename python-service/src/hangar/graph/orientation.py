"""Pure transforms between local component sides and world coordinates."""

from .model import CardinalDirection, GraphNode, GridPos, LocalSide, PortDefinition


_CARDINAL_CYCLE = (
    CardinalDirection.NORTH,
    CardinalDirection.EAST,
    CardinalDirection.SOUTH,
    CardinalDirection.WEST,
)

_LOCAL_QUARTER_TURNS = {
    LocalSide.FRONT: 0,
    LocalSide.RIGHT: 1,
    LocalSide.BACK: 2,
    LocalSide.LEFT: 3,
}

_WORLD_DELTAS = {
    CardinalDirection.NORTH: (0, 0, -1),
    CardinalDirection.EAST: (1, 0, 0),
    CardinalDirection.SOUTH: (0, 0, 1),
    CardinalDirection.WEST: (-1, 0, 0),
}


def world_side(facing: CardinalDirection, local_side: LocalSide) -> CardinalDirection:
    """Rotate a component-local side into its world-space cardinal direction."""
    facing_index = _CARDINAL_CYCLE.index(facing)
    return _CARDINAL_CYCLE[(facing_index + _LOCAL_QUARTER_TURNS[local_side]) % 4]


def adjacent_position(position: GridPos, direction: CardinalDirection) -> GridPos:
    """Return the horizontally adjacent immutable grid position."""
    dx, dy, dz = _WORLD_DELTAS[direction]
    return GridPos(position.x + dx, position.y + dy, position.z + dz)


def port_connection_position(node: GraphNode, definition: PortDefinition) -> GridPos:
    """Locate the world cell immediately outside a node's declared local port."""
    return adjacent_position(
        node.position,
        world_side(node.orientation, definition.local_side),
    )
