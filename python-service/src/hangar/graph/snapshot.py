"""Immutable application snapshot for one editable Stage workspace."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, replace
from enum import Enum
from typing import Any
from uuid import UUID

from .diagnostics import ValidationReport
from .model import (
    CardinalDirection,
    EdgeRoute,
    GraphEdge,
    GraphNode,
    GridPos,
    WireCell,
)


GRAPH_SNAPSHOT_VERSION = 1


class ProgramValidationStatus(str, Enum):
    NOT_CHECKED = "NOT_CHECKED"
    VALID = "VALID"
    INVALID = "INVALID"
    NOT_APPLICABLE = "NOT_APPLICABLE"


FrozenJson = None | bool | int | float | str | tuple["FrozenJson", ...] | tuple[
    tuple[str, "FrozenJson"], ...
]


def freeze_json(value: Any) -> FrozenJson:
    """Copy JSON-like metadata into an immutable, deterministic representation."""
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, Mapping):
        return tuple(sorted((str(key), freeze_json(item)) for key, item in value.items()))
    if isinstance(value, (list, tuple)):
        return tuple(freeze_json(item) for item in value)
    raise TypeError("display metadata must contain only JSON-compatible values")


def thaw_json(value: FrozenJson) -> Any:
    if isinstance(value, tuple):
        if all(
            isinstance(item, tuple) and len(item) == 2 and isinstance(item[0], str)
            for item in value
        ):
            return {key: thaw_json(item) for key, item in value}
        return [thaw_json(item) for item in value]
    return value


@dataclass(frozen=True)
class StageBlockSnapshot:
    instance_id: UUID
    type_key: str
    position: GridPos
    orientation: CardinalDirection
    label: str
    description: str | None
    display_name: str
    material_name: str
    display_definition: FrozenJson = ()

    def __post_init__(self) -> None:
        if not isinstance(self.instance_id, UUID):
            raise TypeError("instance_id must be a UUID")
        if not isinstance(self.type_key, str) or not self.type_key:
            raise ValueError("type_key must be a non-empty string")
        if not isinstance(self.position, GridPos):
            raise TypeError("position must be a GridPos")
        if not isinstance(self.orientation, CardinalDirection):
            raise TypeError("orientation must be a CardinalDirection")
        if not isinstance(self.label, str) or not self.label:
            raise ValueError("label must be a non-empty string")
        if self.description is not None and not isinstance(self.description, str):
            raise TypeError("description must be a string or None")
        if not isinstance(self.display_name, str) or not isinstance(
            self.material_name, str
        ):
            raise TypeError("display_name and material_name must be strings")
        object.__setattr__(self, "display_definition", freeze_json(self.display_definition))

    def as_legacy_dict(self, stage_id: UUID) -> dict[str, Any]:
        return {
            "instance_id": str(self.instance_id),
            "stage_id": str(stage_id),
            "type_key": self.type_key,
            "rel_x": self.position.x,
            "rel_y": self.position.y,
            "rel_z": self.position.z,
            "orientation": self.orientation.value,
            "label": self.label,
            "description": self.description,
            "display_name": self.display_name,
            "material_name": self.material_name,
            "display_definition": thaw_json(self.display_definition),
        }


@dataclass(frozen=True)
class StageSnapshot:
    stage_id: UUID
    blocks: tuple[StageBlockSnapshot, ...]
    edges: tuple[GraphEdge, ...]
    routes: tuple[EdgeRoute, ...]
    wire_cells: tuple[WireCell, ...]
    working_revision: int
    validated_revision: int | None
    last_graph_validation: ValidationReport | None
    program_validation_status: ProgramValidationStatus
    port_catalog_version: str | None

    def __post_init__(self) -> None:
        if not isinstance(self.stage_id, UUID):
            raise TypeError("stage_id must be a UUID")
        collections = (
            ("blocks", self.blocks, StageBlockSnapshot),
            ("edges", self.edges, GraphEdge),
            ("routes", self.routes, EdgeRoute),
            ("wire_cells", self.wire_cells, WireCell),
        )
        for field_name, values, item_type in collections:
            if not isinstance(values, tuple):
                raise TypeError(f"{field_name} must be a tuple")
            if not all(isinstance(item, item_type) for item in values):
                raise ValueError(f"{field_name} contains an invalid value")
        block_ids = tuple(item.instance_id for item in self.blocks)
        if len(set(block_ids)) != len(block_ids):
            raise ValueError("Stage blocks must have unique instance IDs")
        if not isinstance(self.working_revision, int) or isinstance(
            self.working_revision, bool
        ) or self.working_revision < 0:
            raise ValueError("working_revision must be a non-negative integer")
        if self.validated_revision is not None and (
            not isinstance(self.validated_revision, int)
            or isinstance(self.validated_revision, bool)
            or self.validated_revision < 0
            or self.validated_revision > self.working_revision
        ):
            raise ValueError("validated_revision must be current or historical")
        if self.last_graph_validation is not None and (
            not isinstance(self.last_graph_validation, ValidationReport)
            or self.last_graph_validation.revision > self.working_revision
        ):
            raise ValueError("last_graph_validation cannot refer to a future revision")
        if not isinstance(self.program_validation_status, ProgramValidationStatus):
            raise TypeError("program_validation_status has an invalid value")
        if self.port_catalog_version is not None and (
            not isinstance(self.port_catalog_version, str)
            or not self.port_catalog_version
        ):
            raise ValueError("port_catalog_version must be a non-empty string or None")

        object.__setattr__(
            self, "blocks", tuple(sorted(self.blocks, key=lambda item: item.instance_id.int))
        )

    def graph_nodes(self) -> tuple[GraphNode, ...]:
        return tuple(
            GraphNode(
                block.instance_id,
                self.stage_id,
                block.type_key,
                block.position,
                block.orientation,
            )
            for block in self.blocks
        )

    @property
    def has_unpersisted_graph_content(self) -> bool:
        return bool(self.edges or self.routes or self.wire_cells)

    def with_content_change(
        self,
        *,
        blocks: tuple[StageBlockSnapshot, ...] | None = None,
        edges: tuple[GraphEdge, ...] | None = None,
        routes: tuple[EdgeRoute, ...] | None = None,
        wire_cells: tuple[WireCell, ...] | None = None,
    ) -> "StageSnapshot":
        candidate = replace(
            self,
            blocks=self.blocks if blocks is None else blocks,
            edges=self.edges if edges is None else edges,
            routes=self.routes if routes is None else routes,
            wire_cells=self.wire_cells if wire_cells is None else wire_cells,
        )
        if candidate == self:
            return self
        return replace(
            candidate,
            working_revision=self.working_revision + 1,
            program_validation_status=ProgramValidationStatus.NOT_CHECKED,
        )
