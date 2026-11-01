"""CK-06/A3 ProgramService lifecycle tests on isolated PostgreSQL."""
from __future__ import annotations

import unittest

from hangar.program import ProgramService
from hangar.repository import HallRepository
from hangar.stage import StageService
from postgres_graph_integration import (
    OWNER_ID,
    PROGRAM_ID,
    STAGE_ID,
    assert_database_identity,
    connect,
    create_stage,
    repository,
    reset_schema,
)
from postgres_program_save_integration import stable_id, stage_snapshot


class ProgramServicePostgreSQLIntegrationTest(unittest.TestCase):
    def setUp(self):
        reset_schema();repository().migrate();create_stage()
        repository().save_full_stage_snapshot(
            str(STAGE_ID),0,
            stage_snapshot(STAGE_ID,"root-v1",with_program_block=True),
        )
        self.stages=StageService(HallRepository(connect=connect))
        self.programs=ProgramService(self.stages.repository,self.stages)
        self.workspace=self.programs.load(str(PROGRAM_ID))

    def test_program_block_delete_retains_persisted_child_without_data_loss(self):
        root=self.programs.root(str(PROGRAM_ID))
        program_block_id=stable_id(STAGE_ID,"program")
        child=self.programs.ensure_child(
            str(PROGRAM_ID),str(STAGE_ID),str(program_block_id),str(OWNER_ID)
        )
        first=self.programs.save(str(PROGRAM_ID),str(OWNER_ID))
        self.assertEqual(first.saved_version,1)
        child_id=child.snapshot.stage_id

        self.stages.delete(
            str(STAGE_ID),str(program_block_id),{"player_uuid":str(OWNER_ID)}
        )
        self.assertEqual(self.workspace.links[0].link_state,"RETAINED")
        second=self.programs.save(str(PROGRAM_ID),str(OWNER_ID))
        self.assertEqual(second.saved_version,2)

        with connect() as conn,conn.cursor() as cur:
            cur.execute(
                """SELECT link_state FROM blockcommander.program_stage_links
                    WHERE program_id=%s AND child_stage_id=%s""",
                (str(PROGRAM_ID),str(child_id)),
            )
            self.assertEqual(cur.fetchone()[0],"RETAINED")
            cur.execute(
                "SELECT saved_version FROM blockcommander.stages WHERE stage_id=%s",
                (str(child_id),),
            )
            self.assertEqual(cur.fetchone()[0],1)
            cur.execute(
                "SELECT count(*) FROM blockcommander.stage_blocks WHERE stage_id=%s",
                (str(STAGE_ID),),
            )
            self.assertEqual(cur.fetchone()[0],2)


if __name__=="__main__":
    assert_database_identity();unittest.main()
