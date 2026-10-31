"""CK-06/A1 Program–Stage–Hall migration tests on the isolated PostgreSQL target."""
from __future__ import annotations

import hashlib
import unittest
from pathlib import Path
from uuid import UUID

from psycopg2 import errors

from hangar.repository import HallRepository
from postgres_graph_integration import (
    HALL_ID,
    OWNER_ID,
    STAGE_ID,
    apply_migrations_through,
    assert_database_identity,
    connect,
    create_stage,
    full_snapshot,
    repository,
    reset_schema,
)


MIGRATION = (
    Path(__file__).parents[3]
    / "src"
    / "hangar"
    / "migrations"
    / "007_program_stage_hall_separation.sql"
)
SECOND_PROGRAM_ID = UUID("00000000-0000-0000-0000-000000007101")
SECOND_STAGE_ID = UUID("00000000-0000-0000-0000-000000007102")
SECOND_BLOCK_ID = UUID("00000000-0000-0000-0000-000000007103")


FINGERPRINT_QUERIES = (
    """SELECT stage_id,hall_id,stage_name,origin_rel_x,origin_rel_y,origin_rel_z,
              size_x,size_y,size_z,saved_version,created_at,updated_at,
              graph_snapshot_version,port_catalog_version
         FROM blockcommander.stages ORDER BY stage_id""",
    "SELECT * FROM blockcommander.stage_blocks ORDER BY instance_id",
    "SELECT * FROM blockcommander.graph_edges ORDER BY edge_id",
    "SELECT * FROM blockcommander.edge_routes ORDER BY route_id",
    "SELECT * FROM blockcommander.route_steps ORDER BY route_id,step_index",
    "SELECT * FROM blockcommander.wire_cells ORDER BY stage_id,rel_x,rel_y,rel_z",
    """SELECT * FROM blockcommander.wire_channels
         ORDER BY stage_id,rel_x,rel_y,rel_z,channel""",
)


def storage_fingerprint(*, omit_legacy_hall: bool = False) -> str:
    digest = hashlib.sha256()
    with connect("blockcommander-a1-fingerprint") as conn, conn.cursor() as cur:
        for index, query in enumerate(FINGERPRINT_QUERIES):
            if omit_legacy_hall and index == 0:
                query = """SELECT stage_id,stage_name,origin_rel_x,origin_rel_y,
                                  origin_rel_z,size_x,size_y,size_z,saved_version,
                                  created_at,updated_at,graph_snapshot_version,
                                  port_catalog_version
                             FROM blockcommander.stages ORDER BY stage_id"""
            cur.execute(query)
            digest.update(repr(cur.fetchall()).encode("utf-8"))
    return digest.hexdigest()


class ProgramModelMigrationIntegrationTest(unittest.TestCase):
    def setUp(self):
        reset_schema()
        apply_migrations_through("006_graph_snapshots.sql")
        create_stage()
        repository().save_full_stage_snapshot(str(STAGE_ID), 0, full_snapshot())

    def migrate(self) -> None:
        repository().migrate()

    def program_id(self) -> str:
        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT program_id FROM blockcommander.stages WHERE stage_id=%s",
                (str(STAGE_ID),),
            )
            return str(cur.fetchone()[0])

    def test_backfill_preserves_stage_and_complete_graph_fingerprint(self):
        before = storage_fingerprint()
        self.migrate()
        self.assertEqual(storage_fingerprint(), before)

        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                """SELECT s.stage_id,s.stage_kind,s.saved_version,
                          p.root_stage_id,p.saved_version,p.owner_uuid,
                          m.hall_id
                     FROM blockcommander.stages s
                     JOIN blockcommander.programs p ON p.program_id=s.program_id
                     JOIN blockcommander.hall_program_mounts m
                       ON m.program_id=p.program_id
                    WHERE s.stage_id=%s""",
                (str(STAGE_ID),),
            )
            self.assertEqual(
                cur.fetchone(),
                (
                    str(STAGE_ID),
                    "ROOT",
                    1,
                    str(STAGE_ID),
                    1,
                    str(OWNER_ID),
                    str(HALL_ID),
                ),
            )

    def test_repository_creates_program_root_and_mount_for_new_hall(self):
        self.migrate()
        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                """INSERT INTO blockcommander.halls(
                       hall_id,create_request_id,owner_uuid,hall_name,world_uuid,
                       world_name,dimension_key,min_x,min_y,min_z,max_x,max_y,max_z,
                       occupied_min_x,occupied_min_y,occupied_min_z,
                       occupied_max_x,occupied_max_y,occupied_max_z,orientation,
                       material_name,material_id,generator_version,generator_params,
                       lifecycle,hall_code)
                   VALUES(%s,%s,%s,'A1 new hall',%s,'world','minecraft:overworld',
                          30,0,0,50,20,20,30,0,0,50,20,20,'EAST','STONE',1,2,
                          '{}'::jsonb,'ACTIVE','a1new')""",
                (
                    "00000000-0000-0000-0000-000000007110",
                    "00000000-0000-0000-0000-000000007111",
                    str(OWNER_ID),
                    "00000000-0000-0000-0000-000000007112",
                ),
            )
        hall = {
            "hall_id": "00000000-0000-0000-0000-000000007110",
            "owner_uuid": str(OWNER_ID),
            "hall_name": "A1 new hall",
            "world_uuid": "00000000-0000-0000-0000-000000007112",
            "min_x": 30,
            "min_y": 0,
            "min_z": 0,
            "max_x": 50,
            "max_y": 20,
            "max_z": 20,
        }
        stage = HallRepository(connect=connect).ensure_stage(hall)
        self.assertEqual(stage["stage_kind"], "ROOT")
        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                """SELECT p.root_stage_id,m.hall_id
                     FROM blockcommander.programs p
                     JOIN blockcommander.hall_program_mounts m
                       ON m.program_id=p.program_id
                    WHERE p.program_id=%s""",
                (str(stage["program_id"]),),
            )
            self.assertEqual(
                cur.fetchone(),
                (str(stage["stage_id"]), hall["hall_id"]),
            )

    def test_root_uniqueness_and_cross_program_links_are_rejected(self):
        self.migrate()
        program_id = self.program_id()
        with self.assertRaises(errors.UniqueViolation):
            with connect() as conn, conn.cursor() as cur:
                cur.execute(
                    """INSERT INTO blockcommander.stages(
                           stage_id,program_id,stage_kind,hall_id,stage_name,
                           origin_rel_x,origin_rel_y,origin_rel_z,size_x,size_y,size_z)
                       VALUES(%s,%s,'ROOT',NULL,'duplicate root',0,0,0,1,1,1)""",
                    (str(SECOND_STAGE_ID), program_id),
                )

        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                """INSERT INTO blockcommander.programs(
                       program_id,owner_uuid,program_name,root_stage_id)
                   VALUES(%s,%s,'Second program',%s)""",
                (str(SECOND_PROGRAM_ID), str(OWNER_ID), str(SECOND_STAGE_ID)),
            )
            cur.execute(
                """INSERT INTO blockcommander.stages(
                       stage_id,program_id,stage_kind,hall_id,stage_name,
                       origin_rel_x,origin_rel_y,origin_rel_z,size_x,size_y,size_z)
                   VALUES(%s,%s,'ROOT',NULL,'second root',0,0,0,1,1,1)""",
                (str(SECOND_STAGE_ID), str(SECOND_PROGRAM_ID)),
            )

        with self.assertRaises(errors.ForeignKeyViolation):
            with connect() as conn, conn.cursor() as cur:
                cur.execute(
                    """INSERT INTO blockcommander.program_stage_links(
                           program_id,parent_stage_id,program_block_instance_id,
                           child_stage_id,link_state)
                       VALUES(%s,%s,%s,%s,'ACTIVE')""",
                    (
                        program_id,
                        str(STAGE_ID),
                        str(SECOND_BLOCK_ID),
                        str(SECOND_STAGE_ID),
                    ),
                )
                cur.execute("SET CONSTRAINTS ALL IMMEDIATE")

    def test_hall_delete_preserves_program_stage_and_graph_content(self):
        self.migrate()
        program_id = self.program_id()
        before = storage_fingerprint(omit_legacy_hall=True)

        HallRepository(connect=connect).delete(str(HALL_ID))

        self.assertEqual(storage_fingerprint(omit_legacy_hall=True), before)
        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                """SELECT
                       (SELECT count(*) FROM blockcommander.halls WHERE hall_id=%s),
                       (SELECT count(*) FROM blockcommander.hall_program_mounts
                         WHERE hall_id=%s),
                       (SELECT count(*) FROM blockcommander.programs
                         WHERE program_id=%s),
                       (SELECT count(*) FROM blockcommander.stages
                         WHERE stage_id=%s),
                       (SELECT hall_id FROM blockcommander.stages WHERE stage_id=%s)""",
                (
                    str(HALL_ID),
                    str(HALL_ID),
                    program_id,
                    str(STAGE_ID),
                    str(STAGE_ID),
                ),
            )
            self.assertEqual(cur.fetchone(), (0, 0, 1, 1, None))
        loaded = HallRepository(connect=connect).load_full_stage_snapshot(str(STAGE_ID))
        self.assertEqual(loaded.saved_version, 1)
        self.assertEqual(len(loaded.snapshot.edges), 2)
        self.assertEqual(len(loaded.snapshot.routes), 2)
        self.assertEqual(len(loaded.snapshot.wire_cells), 3)

    def test_migration_transaction_can_roll_back_without_partial_schema_or_data(self):
        before = storage_fingerprint()
        conn = connect("blockcommander-a1-rollback")
        try:
            with conn.cursor() as cur:
                cur.execute(MIGRATION.read_text(encoding="utf-8"))
            conn.rollback()
        finally:
            conn.close()

        self.assertEqual(storage_fingerprint(), before)
        with connect() as conn, conn.cursor() as cur:
            cur.execute("SELECT to_regclass('blockcommander.programs')")
            self.assertIsNone(cur.fetchone()[0])
            cur.execute(
                """SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='blockcommander' AND table_name='stages'
                      AND column_name IN ('program_id','stage_kind')"""
            )
            self.assertEqual(cur.fetchone()[0], 0)


if __name__ == "__main__":
    assert_database_identity()
    unittest.main()
