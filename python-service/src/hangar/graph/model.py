"""Immutable value objects for the Fabric graph domain.

Constructors reject only locally impossible representations. Incomplete routes and
wire endpoints remain representable so later validation can produce useful graph
diagnostics instead of losing the user's draft state.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum
from uuid import UUID


class CardinalDirection(str, Enum):
    NORTH = "NORTH"
    EAST = "EAST"
    SOUTH = "SOUTH"
    WEST = "WEST"


class LocalSide(str, Enum):
    FRONT = "FRONT"
    RIGHT = "RIGHT"
    BACK = "BACK"
    LEFT = "LEFT"


class PortKind(str, Enum):
    CONTROL = "CONTROL"


class PortDirection(str, Enum):
    IN = "IN"
    OUT = "OUT"


class EdgeKind(str, Enum):
    CONTROL = "CONTROL"


class WireChannel(str, Enum):
    SINGLE = "SINGLE"
    NS = "NS"
    EW = "EW"


class RouteStatus(str, Enum):
    INCOMPLETE = "INCOMPLETE"
    VALID = "VALID"
    BROKEN = "BROKEN"
    AMBIGUOUS = "AMBIGUOUS"
    ILLEGAL = "ILLEGAL"


_SEMANTIC_KEY_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_]*$")


def _validate_semantic_key(value: str, field_name: str) -> None:
    """Require a semantic identifier instead of a physical coordinate encoding."""
    if not isinstance(value, str) or not _SEMANTIC_KEY_PATTERN.fullmatch(value):
        raise ValueError(
            f"{field_name} must be a non-empty semantic identifier containing only "
            "letters, digits, and underscores, and must start with a letter"
        )


@dataclass(frozen=True, order=True)
class GridPos:
    x: int
    y: int
    z: int


@dataclass(frozen=True)
class PortDefinition:
    port_key: str
    port_kind: PortKind
    direction: PortDirection
    local_side: LocalSide
    ordinal: int
    min_edges: int
    max_edges: int
    branch_label: str | None = None

    def __post_init__(self) -> None:
        _validate_semantic_key(self.port_key, "port_key")
        if self.ordinal < 0:
            raise ValueError("ordinal must be non-negative")
        if self.min_edges < 0 or self.max_edges < 0:
            raise ValueError("edge cardinalities must be non-negative")
        if self.min_edges > self.max_edges:
            raise ValueError("min_edges must not exceed max_edges")


@dataclass(frozen=True, order=True)
class PortRef:
    instance_id: UUID
    port_key: str

    def __post_init__(self) -> None:
        _validate_semantic_key(self.port_key, "port_key")


@dataclass(frozen=True)
class GraphNode:
    instance_id: UUID
    stage_id: UUID
    type_key: str
    position: GridPos
    orientation: CardinalDirection

    def __post_init__(self) -> None:
        _validate_semantic_key(self.type_key, "type_key")


@dataclass(frozen=True)
class GraphEdge:
    edge_id: UUID
    stage_id: UUID
    source_instance_id: UUID
    source_port_key: str
    target_instance_id: UUID
    target_port_key: str
    edge_kind: EdgeKind

    def __post_init__(self) -> None:
        _validate_semantic_key(self.source_port_key, "source_port_key")
        _validate_semantic_key(self.target_port_key, "target_port_key")

    @property
    def semantic_endpoint_key(self) -> tuple[UUID, UUID, str, UUID, str, EdgeKind]:
        """Stable semantic identity; deliberately excludes the generated edge UUID."""
        return (
            self.stage_id,
            self.source_instance_id,
            self.source_port_key,
            self.target_instance_id,
            self.target_port_key,
            self.edge_kind,
        )


@dataclass(frozen=True)
class WireChannelState:
    channel: WireChannel
    connections: frozenset[CardinalDirection]

    def __post_init__(self) -> None:
        if not isinstance(self.connections, frozenset):
            raise TypeError("connections must be a frozenset")
        if not all(isinstance(direction, CardinalDirection) for direction in self.connections):
            raise ValueError("connections must contain only cardinal directions")
        if self.channel is WireChannel.SINGLE and len(self.connections) > 2:
            raise ValueError("a SINGLE wire channel cannot form a junction")
        if self.channel is WireChannel.NS and self.connections != frozenset(
            {CardinalDirection.NORTH, CardinalDirection.SOUTH}
        ):
            raise ValueError("an NS channel must connect NORTH and SOUTH")
        if self.channel is WireChannel.EW and self.connections != frozenset(
            {CardinalDirection.EAST, CardinalDirection.WEST}
        ):
            raise ValueError("an EW channel must connect EAST and WEST")


@dataclass(frozen=True)
class WireCell:
    position: GridPos
    channels: tuple[WireChannelState, ...]

    def __post_init__(self) -> None:
        if not isinstance(self.channels, tuple):
            raise TypeError("channels must be a tuple")
        if not self.channels:
            raise ValueError("a wire cell must contain at least one channel")
        if not all(isinstance(state, WireChannelState) for state in self.channels):
            raise ValueError("channels must contain only WireChannelState values")

        channel_names = tuple(state.channel for state in self.channels)
        if len(set(channel_names)) != len(channel_names):
            raise ValueError("a wire cell cannot contain duplicate channels")

        if channel_names == (WireChannel.SINGLE,):
            return

        states_by_channel = {state.channel: state for state in self.channels}
        if set(channel_names) != {WireChannel.NS, WireChannel.EW}:
            raise ValueError("a wire cell must be SINGLE or an independent NS/EW cross")
        if states_by_channel[WireChannel.NS].connections != frozenset(
            {CardinalDirection.NORTH, CardinalDirection.SOUTH}
        ):
            raise ValueError("the NS cross channel must connect NORTH and SOUTH")
        if states_by_channel[WireChannel.EW].connections != frozenset(
            {CardinalDirection.EAST, CardinalDirection.WEST}
        ):
            raise ValueError("the EW cross channel must connect EAST and WEST")


@dataclass(frozen=True)
class WireStep:
    position: GridPos
    channel: WireChannel


@dataclass(frozen=True)
class EdgeRoute:
    route_id: UUID
    stage_id: UUID
    edge_id: UUID | None
    source_port: PortRef | None
    target_port: PortRef | None
    steps: tuple[WireStep, ...]
    status: RouteStatus

    def __post_init__(self) -> None:
        if not isinstance(self.steps, tuple):
            raise TypeError("steps must be a tuple")
        if not all(isinstance(step, WireStep) for step in self.steps):
            raise ValueError("steps must contain only WireStep values")
