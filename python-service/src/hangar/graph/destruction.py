"""Two-step, atomic ConnectionDestroyer domain operations."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, replace
from uuid import UUID

from .diagnostics import Diagnostic, DiagnosticCode, Severity, sort_diagnostics
from .model import (
    EdgeRoute,
    GraphEdge,
    GridPos,
    WireCell,
    WireChannel,
    WireChannelState,
    WireStep,
)
from .state import GraphWorkspaceState, replace_content


@dataclass(frozen=True)
class ConnectionSelection:
    stage_id: UUID
    player_uuid: UUID
    expected_working_revision: int
    position: GridPos
    channel: WireChannel | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.stage_id, UUID) or not isinstance(self.player_uuid, UUID):
            raise TypeError("stage_id and player_uuid must be UUID values")
        if not isinstance(self.expected_working_revision, int) or isinstance(
            self.expected_working_revision, bool
        ):
            raise TypeError("expected_working_revision must be an integer")
        if self.expected_working_revision < 0:
            raise ValueError("expected_working_revision must be non-negative")


@dataclass(frozen=True)
class CellChange:
    position: GridPos
    before: WireCell | None
    after: WireCell | None


@dataclass(frozen=True)
class RouteChange:
    route_id: UUID
    before: EdgeRoute
    after: EdgeRoute


@dataclass(frozen=True)
class DestructionPreview:
    allowed: bool
    stage_id: UUID
    player_uuid: UUID
    working_revision: int
    position: GridPos
    channel: WireChannel | None
    route_id: UUID | None
    edge_id: UUID | None
    affected_steps: tuple[WireStep, ...]
    cell_changes: tuple[CellChange, ...]
    route_changes: tuple[RouteChange, ...]
    route_before: EdgeRoute | None
    edge_before: GraphEdge | None
    diagnostics: tuple[Diagnostic, ...]


@dataclass(frozen=True)
class DestructionResult:
    applied: bool
    state: GraphWorkspaceState
    diagnostics: tuple[Diagnostic, ...]


def _error(code: DiagnosticCode, message: str, **references: object) -> Diagnostic:
    return Diagnostic(code, Severity.ERROR, message, **references)


def _preview(
    selection: ConnectionSelection,
    *,
    allowed: bool,
    diagnostics=(),
    route: EdgeRoute | None = None,
    edge: GraphEdge | None = None,
    channel: WireChannel | None = None,
    cell_changes=(),
    route_changes=(),
) -> DestructionPreview:
    return DestructionPreview(
        allowed=allowed,
        stage_id=selection.stage_id,
        player_uuid=selection.player_uuid,
        working_revision=selection.expected_working_revision,
        position=selection.position,
        channel=channel if channel is not None else selection.channel,
        route_id=None if route is None else route.route_id,
        edge_id=None if edge is None else edge.edge_id,
        affected_steps=() if route is None else route.steps,
        cell_changes=tuple(cell_changes),
        route_changes=tuple(route_changes),
        route_before=route,
        edge_before=edge,
        diagnostics=sort_diagnostics(diagnostics),
    )


def _selection_error(
    selection: ConnectionSelection,
    code: DiagnosticCode,
    message: str,
    **references: object,
) -> DestructionPreview:
    return _preview(
        selection,
        allowed=False,
        diagnostics=(_error(code, message, **references),),
    )


def _resolved_channel(
    selection: ConnectionSelection,
    cell: WireCell,
) -> tuple[WireChannel | None, Diagnostic | None]:
    available = {item.channel for item in cell.channels}
    if available == {WireChannel.SINGLE}:
        if selection.channel in (None, WireChannel.SINGLE):
            return WireChannel.SINGLE, None
        return None, _error(
            DiagnosticCode.INVALID_CROSS_CHANNEL,
            "Selection channel does not exist in the SINGLE WireCell",
            wire_positions=(selection.position,),
        )
    if selection.channel is None:
        return None, _error(
            DiagnosticCode.AMBIGUOUS_CONNECTION,
            "CROSS selection requires an explicit NS or EW channel",
            wire_positions=(selection.position,),
        )
    if selection.channel not in available:
        return None, _error(
            DiagnosticCode.INVALID_CROSS_CHANNEL,
            "Selected CROSS channel does not exist",
            wire_positions=(selection.position,),
        )
    return selection.channel, None


def _route_users(
    routes: tuple[EdgeRoute, ...],
) -> dict[tuple[GridPos, WireChannel], list[EdgeRoute]]:
    users: dict[tuple[GridPos, WireChannel], list[EdgeRoute]] = defaultdict(list)
    for route in routes:
        seen = set()
        for step in route.steps:
            key = (step.position, step.channel)
            if key not in seen:
                users[key].append(route)
                seen.add(key)
    return users


def _plan_layout_changes(
    state: GraphWorkspaceState,
    route: EdgeRoute,
) -> tuple[tuple[CellChange, ...], tuple[RouteChange, ...], tuple[Diagnostic, ...]]:
    cells = {cell.position: cell for cell in state.wire_cells}
    users = _route_users(state.routes)
    diagnostics: list[Diagnostic] = []
    cell_changes: list[CellChange] = []
    replacement_channels: dict[UUID, dict[GridPos, WireChannel]] = defaultdict(dict)

    channels_by_position: dict[GridPos, set[WireChannel]] = defaultdict(set)
    for step in route.steps:
        channels_by_position[step.position].add(step.channel)

    for position in sorted(channels_by_position):
        target_channels = channels_by_position[position]
        if len(target_channels) != 1:
            diagnostics.append(
                _error(
                    DiagnosticCode.INVALID_CROSS_CHANNEL,
                    "Selected route switches channels inside one WireCell",
                    edge_ids=(() if route.edge_id is None else (route.edge_id,)),
                    wire_positions=(position,),
                )
            )
            continue
        target_channel = next(iter(target_channels))
        cell = cells.get(position)
        if cell is None:
            diagnostics.append(
                _error(
                    DiagnosticCode.BROKEN_ROUTE,
                    "Selected route references a missing WireCell",
                    edge_ids=(() if route.edge_id is None else (route.edge_id,)),
                    wire_positions=(position,),
                )
            )
            continue
        states = {item.channel: item for item in cell.channels}
        if target_channel not in states:
            diagnostics.append(
                _error(
                    DiagnosticCode.INVALID_CROSS_CHANNEL,
                    "Selected route channel is absent from its WireCell",
                    edge_ids=(() if route.edge_id is None else (route.edge_id,)),
                    wire_positions=(position,),
                )
            )
            continue
        other_target_users = [
            item
            for item in users[(position, target_channel)]
            if item.route_id != route.route_id
        ]
        if other_target_users:
            diagnostics.append(
                _error(
                    DiagnosticCode.AMBIGUOUS_CONNECTION,
                    "Physical route channel is shared by multiple routes",
                    edge_ids=tuple(
                        item.edge_id for item in other_target_users if item.edge_id is not None
                    ),
                    wire_positions=(position,),
                )
            )
            continue

        if target_channel is WireChannel.SINGLE:
            cell_changes.append(CellChange(position, cell, None))
            continue

        remaining_channel = (
            WireChannel.EW if target_channel is WireChannel.NS else WireChannel.NS
        )
        remaining_state = states[remaining_channel]
        remaining_users = users[(position, remaining_channel)]
        if len(remaining_users) > 1:
            diagnostics.append(
                _error(
                    DiagnosticCode.AMBIGUOUS_CONNECTION,
                    "Remaining CROSS channel is shared by multiple routes",
                    edge_ids=tuple(
                        item.edge_id for item in remaining_users if item.edge_id is not None
                    ),
                    wire_positions=(position,),
                )
            )
            continue
        after = WireCell(
            position,
            (WireChannelState(WireChannel.SINGLE, remaining_state.connections),),
        )
        cell_changes.append(CellChange(position, cell, after))
        for remaining_route in remaining_users:
            replacement_channels[remaining_route.route_id][position] = WireChannel.SINGLE

    route_changes = []
    routes_by_id = {item.route_id: item for item in state.routes}
    for route_id, replacements in replacement_channels.items():
        before = routes_by_id[route_id]
        after_steps = tuple(
            WireStep(step.position, replacements.get(step.position, step.channel))
            for step in before.steps
        )
        route_changes.append(RouteChange(route_id, before, replace(before, steps=after_steps)))

    return (
        tuple(sorted(cell_changes, key=lambda item: item.position)),
        tuple(sorted(route_changes, key=lambda item: item.route_id.int)),
        sort_diagnostics(diagnostics),
    )


def preview_destruction(
    state: GraphWorkspaceState,
    selection: ConnectionSelection,
) -> DestructionPreview:
    """Resolve one exact route and describe deletion without mutating state."""
    if selection.stage_id != state.stage_id:
        return _selection_error(
            selection,
            DiagnosticCode.CROSS_STAGE_EDGE,
            "Selection Stage does not match the workspace Stage",
        )
    if selection.expected_working_revision != state.working_revision:
        return _selection_error(
            selection,
            DiagnosticCode.STALE_WORKING_REVISION,
            "Selection working revision is stale",
        )
    cell = next(
        (item for item in state.wire_cells if item.position == selection.position),
        None,
    )
    if cell is None:
        return _selection_error(
            selection,
            DiagnosticCode.BROKEN_ROUTE,
            "Selection does not reference a WireCell",
            wire_positions=(selection.position,),
        )
    channel, channel_error = _resolved_channel(selection, cell)
    if channel_error is not None:
        return _preview(selection, allowed=False, diagnostics=(channel_error,))

    matches = tuple(
        route
        for route in state.routes
        if any(
            step.position == selection.position and step.channel is channel
            for step in route.steps
        )
    )
    if not matches:
        return _selection_error(
            selection,
            DiagnosticCode.BROKEN_ROUTE,
            "No route uses the selected WireCell channel",
            wire_positions=(selection.position,),
        )
    if len(matches) > 1:
        return _selection_error(
            selection,
            DiagnosticCode.AMBIGUOUS_CONNECTION,
            "Multiple routes use the selected WireCell channel",
            edge_ids=tuple(
                route.edge_id for route in matches if route.edge_id is not None
            ),
            wire_positions=(selection.position,),
        )
    route = matches[0]
    if route.stage_id != state.stage_id:
        return _selection_error(
            selection,
            DiagnosticCode.BROKEN_ROUTE,
            "Selected route belongs to another Stage",
            wire_positions=(selection.position,),
        )

    edge = None
    if route.edge_id is not None:
        matching_edges = tuple(edge for edge in state.edges if edge.edge_id == route.edge_id)
        if len(matching_edges) != 1:
            return _selection_error(
                selection,
                DiagnosticCode.BROKEN_ROUTE,
                "Bound route does not resolve to exactly one GraphEdge",
                edge_ids=(route.edge_id,),
            )
        edge = matching_edges[0]
        routes_for_edge = tuple(item for item in state.routes if item.edge_id == route.edge_id)
        if len(routes_for_edge) != 1:
            return _selection_error(
                selection,
                DiagnosticCode.AMBIGUOUS_CONNECTION,
                "GraphEdge is assigned to multiple routes",
                edge_ids=(route.edge_id,),
            )

    cell_changes, route_changes, diagnostics = _plan_layout_changes(state, route)
    if diagnostics:
        return _preview(
            selection,
            allowed=False,
            diagnostics=diagnostics,
            route=route,
            edge=edge,
            channel=channel,
        )
    return _preview(
        selection,
        allowed=True,
        route=route,
        edge=edge,
        channel=channel,
        cell_changes=cell_changes,
        route_changes=route_changes,
    )


def _result(state: GraphWorkspaceState, *, applied: bool, diagnostics=()) -> DestructionResult:
    return DestructionResult(applied, state, sort_diagnostics(diagnostics))


def confirm_destruction(
    state: GraphWorkspaceState,
    preview: DestructionPreview,
    *,
    confirmed: bool,
) -> DestructionResult:
    """Apply only an exact, current preview in one immutable content transition."""
    if not confirmed:
        return _result(state, applied=False)
    if not preview.allowed:
        return _result(state, applied=False, diagnostics=preview.diagnostics)
    if preview.stage_id != state.stage_id:
        return _result(
            state,
            applied=False,
            diagnostics=(
                _error(
                    DiagnosticCode.CROSS_STAGE_EDGE,
                    "Destruction preview Stage is no longer current",
                ),
            ),
        )
    if preview.working_revision != state.working_revision:
        return _result(
            state,
            applied=False,
            diagnostics=(
                _error(
                    DiagnosticCode.STALE_WORKING_REVISION,
                    "Destruction preview working revision is stale",
                ),
            ),
        )

    selection = ConnectionSelection(
        preview.stage_id,
        preview.player_uuid,
        preview.working_revision,
        preview.position,
        preview.channel,
    )
    current_preview = preview_destruction(state, selection)
    if not current_preview.allowed or current_preview != preview:
        return _result(
            state,
            applied=False,
            diagnostics=(
                _error(
                    DiagnosticCode.STALE_WORKING_REVISION,
                    "Destruction target no longer matches the preview",
                    edge_ids=(() if preview.edge_id is None else (preview.edge_id,)),
                    wire_positions=(preview.position,),
                ),
            ),
        )

    new_edges = tuple(
        edge for edge in state.edges if edge.edge_id != preview.edge_id
    )
    route_changes = {item.route_id: item.after for item in preview.route_changes}
    new_routes = tuple(
        route_changes.get(route.route_id, route)
        for route in state.routes
        if route.route_id != preview.route_id
    )
    cell_changes = {item.position: item.after for item in preview.cell_changes}
    new_cells = tuple(
        cell_changes.get(cell.position, cell)
        for cell in state.wire_cells
        if cell.position not in cell_changes or cell_changes[cell.position] is not None
    )
    new_state = replace_content(
        state,
        edges=new_edges,
        routes=new_routes,
        wire_cells=new_cells,
    )
    return _result(new_state, applied=True)
