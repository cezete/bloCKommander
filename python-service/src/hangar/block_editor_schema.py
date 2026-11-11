"""Versioned editor metadata and strict validation, independent of HTTP/storage."""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from types import MappingProxyType


@dataclass(frozen=True)
class FieldDiagnostic:
    field: str
    code: str
    message: str
    details: tuple[tuple[str, int], ...] = ()

    def as_dict(self):
        return {"field": self.field, "code": self.code, "message": self.message,
                "details": dict(self.details)}


class BlockEditorError(Exception):
    """Stable domain category; HTTP mapping belongs to a future adapter."""

    def __init__(self, code: str, message: str, diagnostics=()):
        super().__init__(message)
        self.code = code
        self.diagnostics = tuple(diagnostics)

    def as_dict(self):
        return {"error_code": self.code, "message": str(self),
                "diagnostics": [item.as_dict() for item in self.diagnostics]}


@dataclass(frozen=True)
class StringField:
    key: str
    required: bool
    nullable: bool
    min_length: int
    max_length: int
    label: str
    order: int
    empty_to_null: bool = False

    def as_dict(self):
        return {
            "key": self.key, "type": "STRING", "required": self.required,
            "nullable": self.nullable, "default": {"kind": "NONE"},
            "constraints": {"min_length": self.min_length, "max_length": self.max_length,
                            "length_unit": "UNICODE_CODE_POINT"},
            "normalization": {"trim": "PYTHON_STRIP_V1", "unicode_normalization": "NONE",
                              "empty_to_null": self.empty_to_null},
            "presentation": {"label": self.label, "order": self.order},
            "readable": True, "editable": True,
        }


@dataclass(frozen=True)
class BlockEditorSchema:
    schema_id: str
    schema_version: int
    block_type: str
    fields: tuple[StringField, ...]

    def as_dict(self):
        return {"schema_id": self.schema_id, "schema_version": self.schema_version,
                "block_type": self.block_type, "fields": [f.as_dict() for f in self.fields]}


_FIELDS = (
    StringField("label", True, False, 1, 32, "Felirat", 10),
    StringField("description", False, True, 0, 256, "Leírás", 20, True),
)
SCHEMAS = MappingProxyType({
    key: BlockEditorSchema(f"ck.block.{key}.metadata", 1, key, _FIELDS)
    for key in ("START", "STOP")
})


def schema_for(block_type: str, *, schema_id=None, schema_version=1) -> BlockEditorSchema:
    if not isinstance(block_type, str) or block_type not in SCHEMAS:
        raise BlockEditorError("UNSUPPORTED_BLOCK_TYPE", "Only START and STOP are supported.")
    schema = SCHEMAS[block_type]
    if schema_id is not None and schema_id != schema.schema_id:
        raise BlockEditorError("SCHEMA_MISMATCH", "Schema does not match the block type.")
    if type(schema_version) is not int or schema_version != schema.schema_version:
        raise BlockEditorError("UNSUPPORTED_SCHEMA_VERSION", "Unsupported editor schema version.")
    return schema


def validate_changes(schema: BlockEditorSchema, changes: Mapping) -> dict[str, str | None]:
    """Normalize a whole patch before any workspace mutation. Missing means keep."""
    if not isinstance(changes, Mapping):
        raise BlockEditorError("INVALID_CHANGES", "Changes must be an object.")
    if not changes:
        raise BlockEditorError("EMPTY_CHANGES", "At least one field is required.")
    known = {field.key for field in schema.fields}
    if any(key not in known for key in changes):
        raise BlockEditorError("UNKNOWN_FIELD", "Unknown editor field.")
    normalized = {}
    errors = []
    for field in schema.fields:
        if field.key not in changes:
            continue
        raw = changes[field.key]
        code = None
        details = ()
        if raw is None:
            if field.nullable:
                normalized[field.key] = None
                continue
            code = "NULL_NOT_ALLOWED"
        elif type(raw) is not str:
            code = "INVALID_TYPE"
        elif "\x00" in raw:
            code = "INVALID_CHARACTER"
        elif any(0xD800 <= ord(char) <= 0xDFFF for char in raw):
            code = "INVALID_UNICODE"
        else:
            value = raw.strip()
            if len(value) < field.min_length:
                code = "FIELD_REQUIRED"
            elif len(value) > field.max_length:
                code = "STRING_TOO_LONG"
                details = (("max_length", field.max_length), ("actual_length", len(value)))
            else:
                normalized[field.key] = None if field.empty_to_null and not value else value
                continue
        errors.append(FieldDiagnostic(field.key, code, f"Invalid {field.key}: {code}.", details))
    if errors:
        raise BlockEditorError("VALIDATION_FAILED", "The patch was not applied.", errors)
    return normalized
