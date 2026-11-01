"""CK-06/A5 PROGRAM block lifecycle on isolated PostgreSQL."""
from __future__ import annotations

import unittest
import uuid

from hangar.graph import (
    CK_CONTROL_PORT_CATALOG,PORT_CATALOG_VERSION,ProgramValidationStatus,StageSnapshot,
)
from hangar.program import ProgramService
from hangar.repository import HallRepository
from hangar.stage import StageService
from postgres_graph_integration import (
    OWNER_ID,PROGRAM_ID,STAGE_ID,apply_migrations_through,assert_database_identity,
    connect,create_stage,repository,reset_schema,
)
from postgres_program_save_integration import stage_snapshot


def empty_snapshot(stage_id):
    return StageSnapshot(
        stage_id,(),(),(),(),0,None,None,
        ProgramValidationStatus.NOT_CHECKED,PORT_CATALOG_VERSION,
    )


class ProgramBlockPostgreSQLIntegrationTest(unittest.TestCase):
    def setUp(self):
        reset_schema();repository().migrate();create_stage()
        repository().save_full_stage_snapshot(str(STAGE_ID),0,empty_snapshot(STAGE_ID))
        self.stages=StageService(HallRepository(connect=connect))
        self.programs=ProgramService(self.stages.repository,self.stages)

    def test_production_catalog_and_retained_subtree_round_trip(self):
        with connect() as conn,conn.cursor() as cur:
            cur.execute("""SELECT material_name,default_label
                FROM blockcommander.program_block_types WHERE type_key='PROGRAM'""")
            self.assertEqual(cur.fetchone(),("BLUE_GLAZED_TERRACOTTA","PROGRAM"))
            cur.execute("SELECT type_key FROM blockcommander.program_block_types ORDER BY type_key")
            runtime_types=[row[0] for row in cur.fetchall()]
        self.assertTrue(CK_CONTROL_PORT_CATALOG.check_runtime_types(runtime_types).compatible)

        root=self.stages.place(str(STAGE_ID),{
            "player_uuid":str(OWNER_ID),"type_key":"PROGRAM",
            "x":2,"y":0,"z":0,"orientation":"EAST",
        })
        root_block=uuid.UUID(root["blocks"][0]["instance_id"])
        child=self.programs.ensure_child(PROGRAM_ID,STAGE_ID,root_block,str(OWNER_ID))
        child_id=child.snapshot.stage_id
        body=self.stages.place(str(child_id),{
            "player_uuid":str(OWNER_ID),"type_key":"PROGRAM",
            "x":3,"y":0,"z":0,"orientation":"SOUTH",
        })
        body_block=uuid.UUID(body["blocks"][0]["instance_id"])
        grandchild=self.programs.ensure_child(
            PROGRAM_ID,child_id,body_block,str(OWNER_ID)
        )
        grandchild_id=grandchild.snapshot.stage_id
        self.programs.save(PROGRAM_ID,str(OWNER_ID))

        reloaded=HallRepository(connect=connect).load_program(str(PROGRAM_ID))
        self.assertEqual({uuid.UUID(str(row["stage_id"])) for row in reloaded.stages},
            {STAGE_ID,child_id,grandchild_id})
        persisted_root=HallRepository(connect=connect).load_full_stage_snapshot(str(STAGE_ID))
        self.assertIn(root_block,{item.instance_id for item in persisted_root.snapshot.blocks})
        self.assertEqual(persisted_root.snapshot.port_catalog_version,PORT_CATALOG_VERSION)

        self.stages.delete(str(child_id),str(body_block),{"player_uuid":str(OWNER_ID)})
        self.programs.save(PROGRAM_ID,str(OWNER_ID))
        after=HallRepository(connect=connect).load_program(str(PROGRAM_ID))
        retained=next(item for item in after.links if item.child_stage_id==grandchild_id)
        self.assertEqual(retained.link_state,"RETAINED")
        self.assertIn(grandchild_id,{uuid.UUID(str(row["stage_id"])) for row in after.stages})


class ProgramCatalogUpgradePostgreSQLIntegrationTest(unittest.TestCase):
    def test_v2_snapshot_without_program_upgrades_metadata_only(self):
        reset_schema();apply_migrations_through("007_program_stage_hall_separation.sql")
        create_stage()
        repository().save_full_stage_snapshot(
            str(STAGE_ID),0,stage_snapshot(STAGE_ID,"v2-compatible"),
        )
        with connect() as conn,conn.cursor() as cur:
            cur.execute("""UPDATE blockcommander.stages
                SET port_catalog_version='ck-control-v2' WHERE stage_id=%s""",(str(STAGE_ID),))
        tables=("stage_blocks","graph_edges","edge_routes","route_steps","wire_cells","wire_channels")
        def fingerprint():
            with connect() as conn,conn.cursor() as cur:
                rows=[]
                for table in tables:
                    cur.execute(f"SELECT * FROM blockcommander.{table} ORDER BY 1,2")
                    rows.append((table,cur.fetchall()))
                return rows
        before=fingerprint()
        repository().migrate()
        self.assertEqual(before,fingerprint())
        with connect() as conn,conn.cursor() as cur:
            cur.execute("""SELECT saved_version,port_catalog_version
                FROM blockcommander.stages WHERE stage_id=%s""",(str(STAGE_ID),))
            self.assertEqual(cur.fetchone(),(1,"ck-control-v3"))


if __name__=="__main__":
    assert_database_identity();unittest.main()
