"""VALUE ports reuse the existing rotated, horizontal Wire geometry."""
from types import MappingProxyType
from .graph.model import PortDefinition, PortKind, PortDirection, LocalSide
from .graph.port_catalog import BlockPortSpecification, BlockPortStatus, CK_CONTROL_PORT_CATALOG, VersionedPortCatalog

VALUE_PORT_CATALOG_VERSION = "ck-value-i32-v1"


def port(key, direction, side, value_type="INTEGER"):
    return PortDefinition(key, PortKind.VALUE, direction, side, 0, 1, 1, value_type=value_type)


VALUE_PORT_CATALOG = MappingProxyType({
    "LITERAL": (port("out", PortDirection.OUT, LocalSide.FRONT),),
    "REF": (port("out", PortDirection.OUT, LocalSide.FRONT, None),),
    "OPERATOR": (port("lhs", PortDirection.IN, LocalSide.LEFT),
                 port("rhs", PortDirection.IN, LocalSide.RIGHT),
                 port("out", PortDirection.OUT, LocalSide.FRONT)),
    "RESULT": (port("in", PortDirection.IN, LocalSide.BACK, None),),
})

# LET is a control instruction; it never sends a VALUE edge across Stage boundaries.
CONTROL_WITH_LET = VersionedPortCatalog(CK_CONTROL_PORT_CATALOG.version, (
    *CK_CONTROL_PORT_CATALOG.specifications,
    BlockPortSpecification("LET", BlockPortStatus.SPECIFIED,
                           CK_CONTROL_PORT_CATALOG["STOP"] + CK_CONTROL_PORT_CATALOG["START"]),
))


def catalog_for(stage_kind, value_schema_version):
    if stage_kind == "VALUE_BODY":
        return VALUE_PORT_CATALOG
    return CONTROL_WITH_LET if value_schema_version else CK_CONTROL_PORT_CATALOG
