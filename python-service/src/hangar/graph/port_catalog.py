"""Versioned, database-independent CK control-port catalog."""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from enum import Enum
from types import MappingProxyType

from .model import LocalSide, PortDefinition, PortDirection, PortKind


PORT_CATALOG_VERSION = "ck-control-v2"


class BlockPortStatus(str, Enum):
    SPECIFIED = "SPECIFIED"
    UNSPECIFIED = "UNSPECIFIED"


class PortCatalogIssueCode(str, Enum):
    UNKNOWN_BLOCK_TYPE = "UNKNOWN_BLOCK_TYPE"
    UNSPECIFIED_BLOCK_TYPE = "UNSPECIFIED_BLOCK_TYPE"
    RUNTIME_TYPE_MISSING_FROM_CATALOG = "RUNTIME_TYPE_MISSING_FROM_CATALOG"
    CATALOG_TYPE_MISSING_FROM_RUNTIME = "CATALOG_TYPE_MISSING_FROM_RUNTIME"


@dataclass(frozen=True)
class PortCatalogIssue:
    code: PortCatalogIssueCode
    type_key: str
    message: str


class PortCatalogLookupError(LookupError):
    def __init__(self, issue: PortCatalogIssue):
        self.issue = issue
        super().__init__(f"{issue.code.value}: {issue.message}")


@dataclass(frozen=True)
class BlockPortSpecification:
    type_key: str
    status: BlockPortStatus
    ports: tuple[PortDefinition, ...]

    def __post_init__(self) -> None:
        if not isinstance(self.ports, tuple):
            raise TypeError("ports must be a tuple")
        if self.status is BlockPortStatus.UNSPECIFIED and self.ports:
            raise ValueError("UNSPECIFIED block types cannot define ports")
        keys = tuple(item.port_key for item in self.ports)
        if len(set(keys)) != len(keys):
            raise ValueError("port keys must be unique per block type")


@dataclass(frozen=True)
class RuntimeCatalogCheck:
    compatible: bool
    issues: tuple[PortCatalogIssue, ...]


class VersionedPortCatalog(Mapping[str, tuple[PortDefinition, ...]]):
    """Immutable mapping for validators plus explicit type specification state."""

    def __init__(self, version: str, specifications: tuple[BlockPortSpecification, ...]):
        if not version:
            raise ValueError("catalog version must not be empty")
        by_type = {item.type_key: item for item in specifications}
        if len(by_type) != len(specifications):
            raise ValueError("catalog type keys must be unique")
        self._version = version
        self._specifications = tuple(sorted(specifications, key=lambda item: item.type_key))
        self._by_type = MappingProxyType(by_type)

    @property
    def version(self) -> str:
        return self._version

    @property
    def specifications(self) -> tuple[BlockPortSpecification, ...]:
        return self._specifications

    def __getitem__(self, type_key: str) -> tuple[PortDefinition, ...]:
        specification = self.specification(type_key)
        if specification.status is BlockPortStatus.UNSPECIFIED:
            raise PortCatalogLookupError(
                PortCatalogIssue(
                    PortCatalogIssueCode.UNSPECIFIED_BLOCK_TYPE,
                    type_key,
                    f"{type_key} has no accepted port semantics in {self.version}",
                )
            )
        return specification.ports

    def __iter__(self):
        return (
            item.type_key
            for item in self._specifications
            if item.status is BlockPortStatus.SPECIFIED
        )

    def __len__(self) -> int:
        return sum(
            item.status is BlockPortStatus.SPECIFIED for item in self._specifications
        )

    def specification(self, type_key: str) -> BlockPortSpecification:
        try:
            return self._by_type[type_key]
        except KeyError as exc:
            raise PortCatalogLookupError(
                PortCatalogIssue(
                    PortCatalogIssueCode.UNKNOWN_BLOCK_TYPE,
                    type_key,
                    f"{type_key} is absent from {self.version}",
                )
            ) from exc

    def status(self, type_key: str) -> BlockPortStatus:
        return self.specification(type_key).status

    def check_runtime_types(self, runtime_types: Iterable[str]) -> RuntimeCatalogCheck:
        runtime = frozenset(str(item) for item in runtime_types)
        catalog = frozenset(self._by_type)
        issues = [
            PortCatalogIssue(
                PortCatalogIssueCode.RUNTIME_TYPE_MISSING_FROM_CATALOG,
                type_key,
                "Runtime program block type has no catalog entry",
            )
            for type_key in sorted(runtime - catalog)
        ]
        issues.extend(
            PortCatalogIssue(
                PortCatalogIssueCode.CATALOG_TYPE_MISSING_FROM_RUNTIME,
                type_key,
                "Port catalog type is absent from the runtime program block catalog",
            )
            for type_key in sorted(catalog - runtime)
        )
        return RuntimeCatalogCheck(not issues, tuple(issues))


def _port(
    key: str,
    direction: PortDirection,
    side: LocalSide,
    *,
    ordinal: int = 0,
    branch_label: str | None = None,
) -> PortDefinition:
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


CK_CONTROL_PORT_CATALOG = VersionedPortCatalog(
    PORT_CATALOG_VERSION,
    (
        BlockPortSpecification(
            "START",
            BlockPortStatus.SPECIFIED,
            (_port("out", PortDirection.OUT, LocalSide.FRONT),),
        ),
        BlockPortSpecification(
            "STOP",
            BlockPortStatus.SPECIFIED,
            (_port("in", PortDirection.IN, LocalSide.BACK),),
        ),
        BlockPortSpecification(
            "IF",
            BlockPortStatus.SPECIFIED,
            (
                _port("in", PortDirection.IN, LocalSide.FRONT),
                _port(
                    "true",
                    PortDirection.OUT,
                    LocalSide.RIGHT,
                    ordinal=0,
                    branch_label="1",
                ),
                _port(
                    "false",
                    PortDirection.OUT,
                    LocalSide.LEFT,
                    branch_label="0",
                ),
            ),
        ),
        BlockPortSpecification("FOR", BlockPortStatus.UNSPECIFIED, ()),
        BlockPortSpecification("FUN", BlockPortStatus.UNSPECIFIED, ()),
    ),
)
