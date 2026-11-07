import copy
import unittest
from unittest.mock import patch
from uuid import uuid4

import psycopg2

from hangar.hall_projection import project_stage
from hangar.program import ProgramService
from hangar.program_http import ProgramHttpFailure, program_dto
from hangar.selection import parse_selection
from hangar.stage import StageService
from tests.test_program_service import FakeProgramRepository, PROGRAM, ROOT


class HallSelectionUnitTest(unittest.TestCase):
    def test_strict_revision_and_uuid_contract(self):
        body={"player_uuid":str(uuid4()),"program_id":str(uuid4()),
              "stage_id":str(uuid4()),"expected_selection_revision":0}
        self.assertEqual(body,parse_selection(body))
        for revision in (True,-1,2**63,1.2,"0",None):
            with self.subTest(revision=revision),self.assertRaises(ProgramHttpFailure):
                parse_selection({**body,"expected_selection_revision":revision})
        for extra in ({"working_copy_id":str(uuid4())},{"world_uuid":str(uuid4())}):
            with self.assertRaises(ProgramHttpFailure):parse_selection({**body,**extra})

    def test_projection_is_response_only_and_relative_layout_is_unchanged(self):
        header={"origin_rel_x":2,"origin_rel_y":1,"origin_rel_z":3,
                "size_x":10,"size_y":10,"size_z":10}
        view={"stage_id":str(uuid4()),"origin":{"x":999,"y":999,"z":999},
              "blocks":[{"rel_x":4,"rel_y":0,"rel_z":2,"x":1003}]}
        before=copy.deepcopy((view,header))
        hall={"hall_id":str(uuid4()),"world_uuid":str(uuid4()),"min_x":100,"min_y":20,"min_z":-30}
        projected=project_stage(view,header,hall)
        self.assertEqual({"x":102,"y":21,"z":-27},projected["origin"])
        self.assertEqual(106,projected["blocks"][0]["x"])
        self.assertEqual(before,(view,header))
        domain={"domain_layout":{"origin":header},"blocks":[{"position":{"x":4,"y":0,"z":2}}]}
        self.assertEqual(domain["blocks"],project_stage(domain,header,hall)["blocks"])

    def test_mount_refresh_preserves_dirty_workspace_and_retries_failure(self):
        repo=FakeProgramRepository();service=ProgramService(repo,StageService(repo))
        workspace=service.load(PROGRAM);root=service.root(PROGRAM)
        root.dirty=True
        snapshot=root.snapshot;epoch=root.workspace_epoch;mounts=workspace.mounts
        with patch.object(repo,"load_program",side_effect=psycopg2.OperationalError("offline")):
            with self.assertRaises(psycopg2.Error):program_dto(service,workspace)
        self.assertIs(mounts,workspace.mounts)
        other=uuid4();repo.mounts[other]=PROGRAM
        dto=program_dto(service,workspace)
        self.assertEqual(2,len(dto["mounts"]))
        self.assertIs(workspace,service.load(PROGRAM))
        self.assertIs(root,service.stage(PROGRAM,ROOT))
        self.assertIs(snapshot,root.snapshot)
        self.assertEqual(epoch,root.workspace_epoch)
        self.assertTrue(root.dirty)


if __name__=="__main__":unittest.main()
