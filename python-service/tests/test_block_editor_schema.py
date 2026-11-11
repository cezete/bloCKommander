import json
import unittest
from dataclasses import FrozenInstanceError

from hangar.block_editor_schema import BlockEditorError, SCHEMAS, schema_for, validate_changes


class BlockEditorSchemaTest(unittest.TestCase):
    def setUp(self):
        self.schema = schema_for("START")

    def error(self, changes, code="VALIDATION_FAILED", field_code=None):
        with self.assertRaises(BlockEditorError) as caught:
            validate_changes(self.schema, changes)
        self.assertEqual(code, caught.exception.code)
        if field_code:
            self.assertEqual(field_code, caught.exception.diagnostics[0].code)
        return caught.exception

    def test_start_and_stop_schemas_are_immutable_and_detached(self):
        for key in ("START", "STOP"):
            schema = schema_for(key)
            self.assertEqual(f"ck.block.{key}.metadata", schema.schema_id)
            self.assertEqual(1, schema.schema_version)
            self.assertEqual(("label", "description"), tuple(f.key for f in schema.fields))
            self.assertEqual((10, 20), tuple(f.order for f in schema.fields))
            self.assertTrue(schema.fields[0].required)
            self.assertFalse(schema.fields[0].nullable)
            self.assertTrue(schema.fields[1].nullable)
            raw = schema.as_dict()
            json.dumps(raw, ensure_ascii=False)
            raw["fields"][0]["constraints"]["max_length"] = 999
            self.assertEqual(32, schema.as_dict()["fields"][0]["constraints"]["max_length"])
            with self.assertRaises(FrozenInstanceError):
                schema.schema_version = 2
        with self.assertRaises(TypeError):
            SCHEMAS["START"] = None

    def test_schema_selection_is_strict(self):
        for key, kwargs, code in [
            ("IF", {}, "UNSUPPORTED_BLOCK_TYPE"),
            ("start", {}, "UNSUPPORTED_BLOCK_TYPE"),
            ("START", {"schema_id": "ck.block.STOP.metadata"}, "SCHEMA_MISMATCH"),
            ("START", {"schema_version": 2}, "UNSUPPORTED_SCHEMA_VERSION"),
            ("START", {"schema_version": True}, "UNSUPPORTED_SCHEMA_VERSION"),
            ("START", {"schema_version": 1.0}, "UNSUPPORTED_SCHEMA_VERSION"),
        ]:
            with self.subTest(key=key, kwargs=kwargs), self.assertRaises(BlockEditorError) as caught:
                schema_for(key, **kwargs)
            self.assertEqual(code, caught.exception.code)

    def test_label_boundaries_after_strip(self):
        for size in (1, 32):
            self.assertEqual("x" * size, validate_changes(self.schema, {"label": " " + "x" * size + " "})["label"])
        self.error({"label": "x" * 33}, field_code="STRING_TOO_LONG")
        self.error({"label": " \t\n"}, field_code="FIELD_REQUIRED")

    def test_description_boundaries(self):
        self.assertEqual("x" * 256, validate_changes(self.schema, {"description": "x" * 256})["description"])
        self.error({"description": "x" * 257}, field_code="STRING_TOO_LONG")

    def test_nullable_and_missing_fields(self):
        for value in (None, "", " \n\t"):
            self.assertEqual({"description": None}, validate_changes(self.schema, {"description": value}))
        self.assertEqual({"label": "X"}, validate_changes(self.schema, {"label": "X"}))
        self.error({"label": None}, field_code="NULL_NOT_ALLOWED")

    def test_strict_types_no_coercion(self):
        for key in ("label", "description"):
            for value in (42, 1.2, True, [], {}, b"x"):
                with self.subTest(key=key, value=value):
                    self.error({key: value}, field_code="INVALID_TYPE")

    def test_whitespace_and_unicode_are_not_rewritten(self):
        value = "\u00a0A  B\nC\u3000"
        self.assertEqual("A  B\nC", validate_changes(self.schema, {"label": value})["label"])
        for value in ("\u00e9", "e\u0301", "\u200bX\u200b"):
            self.assertEqual(value, validate_changes(self.schema, {"label": value})["label"])

    def test_emoji_lengths_are_code_points(self):
        self.assertEqual("😀" * 32, validate_changes(self.schema, {"label": "😀" * 32})["label"])
        self.error({"label": "😀" * 33}, field_code="STRING_TOO_LONG")

    def test_nul_and_surrogates_are_rejected_before_strip(self):
        for key in ("label", "description"):
            for value, code in [("x\x00", "INVALID_CHARACTER"), ("\ud800x", "INVALID_UNICODE"),
                                ("x\udfff", "INVALID_UNICODE"), ("\ud83d\ude00", "INVALID_UNICODE")]:
                with self.subTest(key=key, code=code):
                    self.error({key: value}, field_code=code)

    def test_unknown_and_empty_changes(self):
        self.error({}, "EMPTY_CHANGES")
        self.error({"label": "ok", "other": "x"}, "UNKNOWN_FIELD")
        self.error({1: "x"}, "UNKNOWN_FIELD")
        self.error([], "INVALID_CHANGES")

    def test_diagnostics_have_stable_field_order_and_detached_json(self):
        error = self.error({"description": 42, "label": ""})
        self.assertEqual(["label", "description"], [d.field for d in error.diagnostics])
        first = error.as_dict()
        first["diagnostics"].clear()
        self.assertEqual(2, len(error.as_dict()["diagnostics"]))


if __name__ == "__main__":
    unittest.main()
