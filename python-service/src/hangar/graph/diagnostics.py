"""Structured, immutable and deterministically ordered graph diagnostics."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Iterable
from uuid import UUID

from .model import GridPos, PortRef


class Severity(Enum):
    ERROR = 0
    WARNING = 1
    INFO = 2


class DiagnosticCode(str, Enum):
    UNKNOWN_NODE = "UNKNOWN_NODE"
    UNKNOWN_PORT = "UNKNOWN_PORT"
    INVALID_PORT_DIRECTION = "INVALID_PORT_DIRECTION"
    INCOMPATIBLE_PORT_KIND = "INCOMPATIBLE_PORT_KIND"
    PORT_CARDINALITY_VIOLATION = "PORT_CARDINALITY_VIOLATION"
    CROSS_STAGE_EDGE = "CROSS_STAGE_EDGE"
    OPEN_WIRE = "OPEN_WIRE"
    AMBIGUOUS_CONNECTION = "AMBIGUOUS_CONNECTION"
    BROKEN_ROUTE = "BROKEN_ROUTE"
    ILLEGAL_JUNCTION = "ILLEGAL_JUNCTION"
    INVALID_CROSS_CHANNEL = "INVALID_CROSS_CHANNEL"
    MISSING_IF_BRANCH = "MISSING_IF_BRANCH"
    INVALID_IF_MERGE = "INVALID_IF_MERGE"
    UNREACHABLE_NODE = "UNREACHABLE_NODE"
    ILLEGAL_CYCLE = "ILLEGAL_CYCLE"
    DUPLICATE_EDGE = "DUPLICATE_EDGE"
    STALE_WORKING_REVISION = "STALE_WORKING_REVISION"


@dataclass(frozen=True)
class Diagnostic:
    code: DiagnosticCode
    severity: Severity
    message: str
    node_ids: tuple[UUID, ...] = ()
    port_refs: tuple[PortRef, ...] = ()
    edge_ids: tuple[UUID, ...] = ()
    wire_positions: tuple[GridPos, ...] = ()
    suggestion: str | None = None

    def __post_init__(self) -> None:
        tuple_fields = (
            ("node_ids", self.node_ids),
            ("port_refs", self.port_refs),
            ("edge_ids", self.edge_ids),
            ("wire_positions", self.wire_positions),
        )
        for field_name, value in tuple_fields:
            if not isinstance(value, tuple):
                raise TypeError(f"{field_name} must be a tuple")


_CODE_ORDER = {code: index for index, code in enumerate(DiagnosticCode)}


def _diagnostic_sort_key(item: Diagnostic) -> tuple[object, ...]:
    """Create a total, value-based order without relying on object identity."""
    return (
        item.severity.value,
        _CODE_ORDER[item.code],
        tuple(sorted(node_id.int for node_id in item.node_ids)),
        tuple(sorted((ref.instance_id.int, ref.port_key) for ref in item.port_refs)),
        tuple(sorted(edge_id.int for edge_id in item.edge_ids)),
        tuple(sorted((position.x, position.y, position.z) for position in item.wire_positions)),
        (item.message, item.suggestion is not None, item.suggestion or ""),
    )


def sort_diagnostics(items: Iterable[Diagnostic]) -> tuple[Diagnostic, ...]:
    """Return every diagnostic in deterministic contract order, without deduplication."""
    return tuple(sorted(items, key=_diagnostic_sort_key))


@dataclass(frozen=True)
class ValidationReport:
    revision: int
    diagnostics: tuple[Diagnostic, ...]

    def __post_init__(self) -> None:
        if not isinstance(self.revision, int) or isinstance(self.revision, bool):
            raise TypeError("revision must be an integer")
        if self.revision < 0:
            raise ValueError("revision must be non-negative")
        if not isinstance(self.diagnostics, tuple):
            raise TypeError("diagnostics must be a tuple")
        if not all(isinstance(item, Diagnostic) for item in self.diagnostics):
            raise ValueError("diagnostics must contain only Diagnostic values")
        object.__setattr__(self, "diagnostics", sort_diagnostics(self.diagnostics))

    @property
    def has_blocking_errors(self) -> bool:
        return any(item.severity is Severity.ERROR for item in self.diagnostics)
