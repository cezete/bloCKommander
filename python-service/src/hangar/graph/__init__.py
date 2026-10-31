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
from .adapter import (
    AdapterIssue, AdapterIssueCode, GraphWorkspaceMapping,
    GraphWorkspaceMappingError, GraphWorkspaceUpdatePlan,
    StageStatusDTO, prepare_graph_workspace_update, to_graph_workspace,
    to_stage_snapshot, to_stage_status_dto,
)
from .snapshot import (
    GRAPH_SNAPSHOT_VERSION, ProgramValidationStatus, StageBlockSnapshot,
    StageSnapshot,
)
from .port_catalog import (
    BlockPortSpecification, BlockPortStatus, CK_CONTROL_PORT_CATALOG,
    PORT_CATALOG_VERSION, PortCatalogIssue, PortCatalogIssueCode,
    PortCatalogLookupError, RuntimeCatalogCheck, VersionedPortCatalog,
)
from .discovery import (
    DiscoveredConnection, DiscoveryDiagnostic, DiscoveryDiagnosticCode,
    OpenWireEnd, PhysicalComponentStatus, PhysicalTopologyDiscovery,
    PhysicalWireComponent, discover_physical_topology,
)
from .placement import (
    WirePlacementError, WirePlacementErrorCode, WirePlacementIntent,
    WirePlacementResult, place_wire_intent,
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
    "AdapterIssue", "AdapterIssueCode", "GraphWorkspaceMapping",
    "GraphWorkspaceMappingError", "GraphWorkspaceUpdatePlan",
    "ProgramValidationStatus", "StageStatusDTO",
    "prepare_graph_workspace_update", "to_graph_workspace",
    "to_stage_snapshot", "to_stage_status_dto", "GRAPH_SNAPSHOT_VERSION",
    "StageBlockSnapshot",
    "StageSnapshot", "BlockPortSpecification", "BlockPortStatus",
    "CK_CONTROL_PORT_CATALOG", "PORT_CATALOG_VERSION", "PortCatalogIssue",
    "PortCatalogIssueCode", "PortCatalogLookupError", "RuntimeCatalogCheck",
    "VersionedPortCatalog",
    "DiscoveredConnection", "DiscoveryDiagnostic", "DiscoveryDiagnosticCode",
    "OpenWireEnd", "PhysicalComponentStatus", "PhysicalTopologyDiscovery",
    "PhysicalWireComponent", "discover_physical_topology",
    "WirePlacementError", "WirePlacementErrorCode", "WirePlacementIntent",
    "WirePlacementResult", "place_wire_intent",
]
