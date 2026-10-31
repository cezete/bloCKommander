"""Authoritative, atomic CK Wire placement-intent domain operation."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum

from .model import (
    CardinalDirection,
    GridPos,
    PortDefinition,
    WireCell,
    WireChannel,
    WireChannelState,
)
from .orientation import adjacent_position, port_connection_position, world_side
from .state import GraphWorkspaceState, replace_content


PortCatalog = Mapping[str, tuple[PortDefinition, ...]]


class WirePlacementIntent(str, Enum):
    AUTO = "AUTO"
    CROSS = "CROSS"


class WirePlacementErrorCode(str, Enum):
    AMBIGUOUS_PLACEMENT = "AMBIGUOUS_PLACEMENT"
    STAGE_BOUNDARY = "STAGE_BOUNDARY"
    OCCUPIED_CELL = "OCCUPIED_CELL"
    INVALID_CONNECTION = "INVALID_CONNECTION"


class WirePlacementError(ValueError):
    def __init__(
        self,
        code: WirePlacementErrorCode,
        message: str,
        *,
        positions: tuple[GridPos, ...] = (),
    ):
        self.code = code
        self.positions = positions
        super().__init__(message)


@dataclass(frozen=True)
class WirePlacementResult:
    state: GraphWorkspaceState
    affected_cells: tuple[WireCell, ...]


@dataclass(frozen=True)
class _Source:
    direction: CardinalDirection
    neighbor_position: GridPos | None = None


_DIRECTION_ORDER = {
    CardinalDirection.NORTH: 0,
    CardinalDirection.EAST: 1,
    CardinalDirection.SOUTH: 2,
    CardinalDirection.WEST: 3,
}
_OPPOSITE = {
    CardinalDirection.NORTH: CardinalDirection.SOUTH,
    CardinalDirection.EAST: CardinalDirection.WEST,
    CardinalDirection.SOUTH: CardinalDirection.NORTH,
    CardinalDirection.WEST: CardinalDirection.EAST,
}


def _cell_key(cell: WireCell) -> tuple[int, int, int]:
    return (cell.position.x, cell.position.y, cell.position.z)


def _definitions(
    port_catalog: PortCatalog,
    type_key: str,
) -> tuple[PortDefinition, ...]:
    try:
        definitions = port_catalog[type_key]
    except (KeyError, LookupError):
        return ()
    return tuple(sorted(definitions, key=lambda item: (item.ordinal, item.port_key)))


def _port_sources(
    state: GraphWorkspaceState,
    position: GridPos,
    port_catalog: PortCatalog,
) -> dict[CardinalDirection, list[_Source]]:
    result = {direction: [] for direction in CardinalDirection}
    for node in state.nodes:
        for definition in _definitions(port_catalog, node.type_key):
            if port_connection_position(node, definition) != position:
                continue
            outward = world_side(node.orientation, definition.local_side)
            result[_OPPOSITE[outward]].append(_Source(_OPPOSITE[outward]))
    return result


def _neighbor_channel(
    cell: WireCell,
    reciprocal: CardinalDirection,
) -> WireChannelState | None:
    matching = tuple(
        channel for channel in cell.channels if reciprocal in channel.connections
    )
    if len(matching) == 1:
        return matching[0]
    if matching:
        return None
    if len(cell.channels) == 1:
        channel = cell.channels[0]
        if channel.channel is WireChannel.SINGLE and len(channel.connections) < 2:
            return channel
    return None


def _connection_sources(
    state: GraphWorkspaceState,
    position: GridPos,
    port_catalog: PortCatalog,
) -> tuple[dict[CardinalDirection, _Source], dict[GridPos, WireCell]]:
    cells: dict[GridPos, WireCell] = {}
    for cell in state.wire_cells:
        if cell.position in cells:
            raise WirePlacementError(
                WirePlacementErrorCode.INVALID_CONNECTION,
                "A hiteles wire layout duplikált cellapozíciót tartalmaz.",
                positions=(cell.position,),
            )
        cells[cell.position] = cell

    candidates = _port_sources(state, position, port_catalog)
    for direction in CardinalDirection:
        neighbor_position = adjacent_position(position, direction)
        neighbor = cells.get(neighbor_position)
        if neighbor is None:
            continue
        if _neighbor_channel(neighbor, _OPPOSITE[direction]) is None:
            raise WirePlacementError(
                WirePlacementErrorCode.INVALID_CONNECTION,
                "A közvetlen szomszéd WireCell nem kapcsolható át kapcsolatvesztés nélkül.",
                positions=(position, neighbor_position),
            )
        candidates[direction].append(_Source(direction, neighbor_position))

    selected: dict[CardinalDirection, _Source] = {}
    for direction, sources in candidates.items():
        if len(sources) > 1:
            raise WirePlacementError(
                WirePlacementErrorCode.AMBIGUOUS_PLACEMENT,
                "Egy irányban több fizikai csatlakozási lehetőség található.",
                positions=(position,),
            )
        if sources:
            selected[direction] = sources[0]
    return selected, cells


def _connect_neighbor(
    cell: WireCell,
    reciprocal: CardinalDirection,
) -> WireCell:
    channel = _neighbor_channel(cell, reciprocal)
    if channel is None:
        raise WirePlacementError(
            WirePlacementErrorCode.INVALID_CONNECTION,
            "A szomszédos WireCell nem bővíthető kapcsolatvesztés nélkül.",
            positions=(cell.position,),
        )
    if reciprocal in channel.connections:
        return cell
    updated = WireChannelState(
        channel.channel,
        frozenset((*channel.connections, reciprocal)),
    )
    channels = tuple(updated if item is channel else item for item in cell.channels)
    return WireCell(cell.position, channels)


def place_wire_intent(
    state: GraphWorkspaceState,
    position: GridPos,
    intent: WirePlacementIntent,
    stage_size: GridPos,
    port_catalog: PortCatalog,
) -> WirePlacementResult:
    """Compute and atomically apply one authoritative placement intent.

    The function is pure: failures raise before returning a candidate state, and
    callers commit only the returned immutable snapshot.
    """
    if not (
        0 <= position.x < stage_size.x
        and 0 <= position.y < stage_size.y
        and 0 <= position.z < stage_size.z
    ):
        raise WirePlacementError(
            WirePlacementErrorCode.STAGE_BOUNDARY,
            "A placement célpozíciója a Stage területén kívül van.",
            positions=(position,),
        )
    if any(node.position == position for node in state.nodes) or any(
        cell.position == position for cell in state.wire_cells
    ):
        raise WirePlacementError(
            WirePlacementErrorCode.OCCUPIED_CELL,
            "A placement célpozíciója már foglalt.",
            positions=(position,),
        )

    sources, cells = _connection_sources(state, position, port_catalog)
    directions = tuple(sorted(sources, key=_DIRECTION_ORDER.get))
    if intent is WirePlacementIntent.AUTO:
        if len(directions) >= 3:
            raise WirePlacementError(
                WirePlacementErrorCode.AMBIGUOUS_PLACEMENT,
                "Három vagy több potenciális végpontból nem választható önkényesen kapcsolat.",
                positions=(position,),
            )
        target = WireCell(
            position,
            (WireChannelState(WireChannel.SINGLE, frozenset(directions)),),
        )
    elif intent is WirePlacementIntent.CROSS:
        if set(directions) != set(CardinalDirection):
            raise WirePlacementError(
                WirePlacementErrorCode.INVALID_CONNECTION,
                "Explicit CROSS-hoz mind a négy irányban egyértelmű végpont szükséges.",
                positions=(position,),
            )
        target = WireCell(
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
    else:
        raise TypeError("intent must be a WirePlacementIntent")

    affected = {position: target}
    candidate_cells = dict(cells)
    candidate_cells[position] = target
    for direction in directions:
        source = sources[direction]
        if source.neighbor_position is None:
            continue
        neighbor = candidate_cells[source.neighbor_position]
        updated = _connect_neighbor(neighbor, _OPPOSITE[direction])
        candidate_cells[updated.position] = updated
        if updated != neighbor:
            affected[updated.position] = updated

    candidate = replace_content(
        state,
        wire_cells=tuple(candidate_cells.values()),
    )
    return WirePlacementResult(
        candidate,
        tuple(sorted(affected.values(), key=_cell_key)),
    )
