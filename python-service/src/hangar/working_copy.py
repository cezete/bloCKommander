"""Persistent identity context only; content still belongs to shared Program workspaces."""
from dataclasses import dataclass
from uuid import UUID


@dataclass(frozen=True)
class WorkingCopyContext:
    hall_id: UUID
    program_id: UUID
    working_copy_id: UUID

    def __post_init__(self):
        if not all(isinstance(value, UUID) for value in (
            self.hall_id, self.program_id, self.working_copy_id,
        )):
            raise TypeError("WorkingCopyContext identities must be UUIDs")

    def as_dict(self) -> dict[str, str]:
        return {
            "hall_id": str(self.hall_id),
            "program_id": str(self.program_id),
            "working_copy_id": str(self.working_copy_id),
        }
