"""Versioned saved expression data, independent of display metadata and execution."""
from dataclasses import dataclass
from uuid import UUID

VALUE_SCHEMA_VERSION = 1
INTEGER_PROFILE = "ck-integer-i32-v1"
VALUE_TYPES = frozenset(("LITERAL", "REF", "OPERATOR", "RESULT"))
OPERATORS = {"ADD": "+", "SUBTRACT": "-", "MULTIPLY": "*", "DIVIDE": "/"}


class ValueDomainError(ValueError):
    def __init__(self, code, message):
        self.code = code
        super().__init__(message)


def validate_config(type_key, config):
    """Unconfigured blocks are drafts; unsupported representations fail closed."""
    if type(config) is dict and not config:
        return
    if type(config) is not dict or type(config.get("version")) is not int or config["version"] != 1:
        raise ValueDomainError("UNSUPPORTED_VALUE_FORMAT", "Expected value configuration version 1")
    fields = {"LET": {"target_id"}, "REF": {"kind", "target_id"},
              "LITERAL": {"type", "value", "profile"}, "OPERATOR": {"operation", "profile"}, "RESULT": set()}
    if type_key not in fields or set(config) != fields[type_key] | {"version"}:
        raise ValueDomainError("INVALID_VALUE_CONFIG", "Unexpected configuration fields")
    if type_key in ("LET", "REF"):
        try:
            UUID(config["target_id"])
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValueDomainError("INVALID_TARGET_ID", "A declaration UUID is required") from exc
    if type_key == "REF" and config["kind"] != "DECLARATION_REF":
        raise ValueDomainError("UNSUPPORTED_REF_KIND", "Only DECLARATION_REF is supported")
    if type_key in ("LITERAL", "OPERATOR") and config["profile"] != INTEGER_PROFILE:
        raise ValueDomainError("UNSUPPORTED_VALUE_PROFILE", "Unknown arithmetic profile")
    if type_key == "LITERAL" and (config["type"] != "INTEGER" or type(config["value"]) is not int
                                  or not -(2**31) <= config["value"] < 2**31):
        raise ValueDomainError("INTEGER_OUT_OF_RANGE", "Expected a signed 32-bit INTEGER")
    if type_key == "OPERATOR" and (type(config["operation"]) is not str or config["operation"] not in OPERATORS):
        raise ValueDomainError("UNSUPPORTED_OPERATOR", "Unknown operator")


@dataclass(frozen=True)
class ValueDiagnostic:
    code: str
    block_id: UUID | None = None
    port_key: str | None = None
    message: str = ""


@dataclass(frozen=True)
class ValueAnalysis:
    diagnostics: tuple[ValueDiagnostic, ...]
    dependency_order: tuple[UUID, ...]
    expression: str | None
    result_type: str | None
    resolution_basis: object

    @property
    def valid(self):
        return not self.diagnostics
