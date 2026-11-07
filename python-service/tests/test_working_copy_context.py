import unittest
import uuid
from dataclasses import FrozenInstanceError
from unittest.mock import MagicMock

from hangar.repository import (
    ForeignWorkingCopy, HallMountNotFound, HallNotFound, HallProgramMismatch,
    HallRepository, WorkingCopyProgramMismatch,
)
from hangar.working_copy import WorkingCopyContext


class WorkingCopyContextTest(unittest.TestCase):
    def setUp(self):
        self.hall,self.program,self.copy=(uuid.uuid4() for _ in range(3))
        connection=MagicMock()
        self.cursor=connection.__enter__.return_value.cursor.return_value.__enter__.return_value
        self.cursor.fetchone.return_value={"hall_id":str(self.hall),
            "program_id":str(self.program),"working_copy_id":str(self.copy)}
        self.repo=HallRepository(connect=lambda:connection)

    def test_context_is_immutable_typed_identity_only(self):
        context=self.repo.resolve_working_copy_context(self.hall,
            program_id=self.program,working_copy_id=self.copy)
        self.assertEqual(context,WorkingCopyContext(self.hall,self.program,self.copy))
        self.assertEqual(set(context.as_dict()),{"hall_id","program_id","working_copy_id"})
        with self.assertRaises(FrozenInstanceError): context.program_id=uuid.uuid4()
        with self.assertRaises(TypeError): WorkingCopyContext(str(self.hall),self.program,self.copy)

    def test_expected_program_and_working_copy_are_checked_against_repository(self):
        with self.assertRaises(HallProgramMismatch):
            self.repo.resolve_working_copy_context(self.hall,program_id=uuid.uuid4())
        self.cursor.fetchone.side_effect=[
            {"hall_id":str(self.hall),"program_id":str(self.program),
                "working_copy_id":str(self.copy)},
            None,
        ]
        with self.assertRaises(ForeignWorkingCopy):
            self.repo.resolve_working_copy_context(self.hall,working_copy_id=uuid.uuid4())

    def test_working_copy_from_another_program_is_distinct_from_foreign_copy(self):
        other_program,other_copy=uuid.uuid4(),uuid.uuid4()
        self.cursor.fetchone.side_effect=[
            {"hall_id":str(self.hall),"program_id":str(self.program),
                "working_copy_id":str(self.copy)},
            {"program_id":str(other_program)},
        ]
        with self.assertRaises(WorkingCopyProgramMismatch):
            self.repo.resolve_working_copy_context(
                self.hall,program_id=self.program,working_copy_id=other_copy,
            )

    def test_missing_mount_and_malformed_identity_are_rejected(self):
        self.cursor.fetchone.return_value=None
        with self.assertRaises(HallNotFound): self.repo.resolve_working_copy_context(self.hall)
        self.cursor.fetchone.return_value={
            "hall_id":str(self.hall),"program_id":None,"working_copy_id":None,
        }
        with self.assertRaises(HallMountNotFound):
            self.repo.resolve_working_copy_context(self.hall)
        with self.assertRaises(ValueError): self.repo.resolve_working_copy_context("not-a-uuid")
