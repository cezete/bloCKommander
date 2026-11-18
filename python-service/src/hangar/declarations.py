"""Stage-owned declarations. No graph nodes, runtime values or target-language rules."""
from dataclasses import dataclass
from enum import Enum
import re
from uuid import UUID

DECLARATION_SCHEMA_VERSION = 1


class DeclarationError(ValueError):
    def __init__(self, code, message, *, field=None):
        self.code, self.field = code, field
        super().__init__(message)

    def as_dict(self):
        return {"error_code": self.code, "message": str(self), "field": self.field}


def require_uuid(value, field):
    if not isinstance(value, UUID):
        raise DeclarationError("INVALID_IDENTITY", "UUID required", field=field)
    return value


def validate_name(value):
    if type(value) is not str or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", value):
        raise DeclarationError("INVALID_DECLARATION_NAME", "Expected a case-sensitive ASCII identifier of 1–64 characters", field="name")
    return value


class VariableType(str, Enum):
    BOOLEAN = "BOOLEAN"
    INTEGER = "INTEGER"
    STRING = "STRING"


class Mutability(str, Enum):
    IMMUTABLE = "IMMUTABLE"
    MUTABLE = "MUTABLE"


@dataclass(frozen=True)
class ScopeRef:
    owner_id: UUID
    kind: str = "STAGE"

    def __post_init__(self):
        require_uuid(self.owner_id, "stage_id")
        if self.kind != "STAGE":
            raise DeclarationError("UNSUPPORTED_SCOPE_KIND", "B1 supports Stage scopes only")


@dataclass(frozen=True)
class VariableDeclaration:
    declaration_id: UUID
    scope_ref: ScopeRef
    name: str
    variable_type: VariableType
    mutability: Mutability

    def __post_init__(self):
        require_uuid(self.declaration_id, "declaration_id")
        if not isinstance(self.scope_ref, ScopeRef):
            raise DeclarationError("INVALID_SCOPE", "ScopeRef required")
        validate_name(self.name)
        if not isinstance(self.variable_type, VariableType):
            raise DeclarationError("UNKNOWN_VARIABLE_TYPE", "VariableType required", field="variable_type")
        if not isinstance(self.mutability, Mutability):
            raise DeclarationError("INVALID_MUTABILITY", "Mutability required", field="mutability")

    def as_dict(self):
        return {"declaration_id": str(self.declaration_id),
                "scope": {"kind": self.scope_ref.kind, "owner_id": str(self.scope_ref.owner_id)},
                "name": self.name, "type_key": self.variable_type.value, "mutability": self.mutability.value}
