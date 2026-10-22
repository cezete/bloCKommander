"""Immutable graph-domain value objects and orientation helpers."""

from .model import (
    CardinalDirection, EdgeKind, EdgeRoute, GraphEdge, GraphNode, GridPos,
    LocalSide, PortDefinition, PortDirection, PortKind, PortRef, RouteStatus,
    WireCell, WireChannel, WireChannelState, WireStep,
)
from .orientation import adjacent_position, port_connection_position, world_side
from .diagnostics import (
    Diagnostic, DiagnosticCode, Severity, ValidationReport, sort_diagnostics,
)
from .state import (
    EdgeIndicatorStatus, GraphStatus, GraphWorkspaceState, SaveStatus,
    mark_saved, record_validation, replace_content,
)
from .validation import (
    DEFERRED_RULE_GROUPS, VALIDATED_RULE_GROUPS, PortCatalog, validate_graph,
)
from .binding import (
    BindEdgesCommand, BindingResult, RouteCandidate, bind_edges,
)
from .destruction import (
    CellChange, ConnectionSelection, DestructionPreview, DestructionResult,
    RouteChange, confirm_destruction, preview_destruction,
)

__all__ = [
    "CardinalDirection", "EdgeKind", "EdgeRoute", "GraphEdge", "GraphNode",
    "GridPos", "LocalSide", "PortDefinition", "PortDirection", "PortKind",
    "PortRef", "RouteStatus", "WireCell", "WireChannel", "WireChannelState",
    "WireStep", "adjacent_position", "port_connection_position", "world_side",
    "Diagnostic", "DiagnosticCode", "Severity", "ValidationReport",
    "sort_diagnostics", "EdgeIndicatorStatus", "GraphStatus",
    "GraphWorkspaceState", "SaveStatus", "mark_saved", "record_validation",
    "replace_content",
    "DEFERRED_RULE_GROUPS", "VALIDATED_RULE_GROUPS", "PortCatalog",
    "validate_graph",
    "BindEdgesCommand", "BindingResult", "RouteCandidate", "bind_edges",
    "CellChange", "ConnectionSelection", "DestructionPreview",
    "DestructionResult", "RouteChange", "confirm_destruction",
    "preview_destruction",
]
