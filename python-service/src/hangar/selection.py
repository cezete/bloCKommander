"""Hall selection HTTP contract, deliberately separate from G1 context echo."""


class SelectionFailure(Exception):
    def __init__(self, code, message, status=409):
        self.code, self.message, self.status = code, message, status
        super().__init__(message)


def parse_selection(raw):
    from .program_http import ProgramHttpFailure, parse_json_object, parse_uuid
    data = parse_json_object(raw, required={
        "player_uuid", "program_id", "stage_id", "expected_selection_revision"})
    revision = data["expected_selection_revision"]
    if type(revision) is not int or not 0 <= revision < 2**63:
        raise ProgramHttpFailure(400, "INVALID_FIELD", "Invalid selection revision.",
                                 "expected_selection_revision")
    return {**{key: parse_uuid(data[key], key) for key in (
        "player_uuid", "program_id", "stage_id")}, "expected_selection_revision": revision}


def selection_dto(row):
    return {**{key: str(row[key]) for key in (
        "hall_id", "world_uuid", "program_id", "working_copy_id", "active_stage_id")},
        "selection_revision": int(row["selection_revision"]),
        "content_scope": "SHARED_PROGRAM", "projection_state": "DESIRED_ONLY"}
