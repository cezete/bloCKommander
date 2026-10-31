import unittest
from dataclasses import FrozenInstanceError
from uuid import UUID

from hangar.repository import (
    HallRepository,
    ProgramSavePlan,
    ProgramSavePlanError,
    ProgramSaveReceipt,
    ProgramStageLink,
    ProgramStageSavePlan,
    StageSaveReceipt,
)


PROGRAM_ID = UUID("00000000-0000-0000-0000-000000008100")
ROOT_ID = UUID("00000000-0000-0000-0000-000000008101")
BODY_A_ID = UUID("00000000-0000-0000-0000-000000008102")
BODY_B_ID = UUID("00000000-0000-0000-0000-000000008103")
BLOCK_A_ID = UUID("00000000-0000-0000-0000-000000008104")
BLOCK_B_ID = UUID("00000000-0000-0000-0000-000000008105")


class ProgramRepositoryContractTest(unittest.TestCase):
    def test_save_plan_and_receipt_are_immutable(self):
        plan = ProgramSavePlan(
            PROGRAM_ID,0,ROOT_ID,(ProgramStageSavePlan(ROOT_ID,0),),(),
        )
        receipt = ProgramSaveReceipt(
            PROGRAM_ID,1,True,(StageSaveReceipt(ROOT_ID,1,True),),
        )
        with self.assertRaises(FrozenInstanceError):
            plan.expected_saved_version = 2
        with self.assertRaises(FrozenInstanceError):
            receipt.changed = False
        self.assertIsInstance(plan.stages,tuple)
        self.assertIsInstance(receipt.stages,tuple)

    def test_mutable_plan_collections_are_rejected_before_connect(self):
        connected = False
        def connect():
            nonlocal connected
            connected = True
            raise AssertionError("must not connect")
        plan = ProgramSavePlan(PROGRAM_ID,0,ROOT_ID,[ProgramStageSavePlan(ROOT_ID,0)],())
        with self.assertRaisesRegex(ProgramSavePlanError,"immutable tuples"):
            HallRepository(connect=connect).save_program_snapshot(plan)
        self.assertFalse(connected)

    def test_active_hierarchy_cycle_is_rejected_before_connect(self):
        connected = False
        def connect():
            nonlocal connected
            connected = True
            raise AssertionError("must not connect")
        plan = ProgramSavePlan(
            PROGRAM_ID,0,ROOT_ID,
            (
                ProgramStageSavePlan(ROOT_ID,0),
                ProgramStageSavePlan(BODY_A_ID,0),
                ProgramStageSavePlan(BODY_B_ID,0),
            ),
            (
                ProgramStageLink(BODY_A_ID,BLOCK_A_ID,BODY_B_ID),
                ProgramStageLink(BODY_B_ID,BLOCK_B_ID,BODY_A_ID),
            ),
        )
        with self.assertRaisesRegex(ProgramSavePlanError,"cycle"):
            HallRepository(connect=connect).save_program_snapshot(plan)
        self.assertFalse(connected)


if __name__ == "__main__":
    unittest.main()
