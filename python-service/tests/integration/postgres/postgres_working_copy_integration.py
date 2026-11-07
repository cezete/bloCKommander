"""CK-07/A1.1 identity only, on the guarded disposable PostgreSQL target."""
import unittest
from pathlib import Path
from uuid import uuid4

from psycopg2 import errors

from ck_webhook import create_app
from hangar.graph_application import GRAPH_SECRET_HEADER
from hangar.program import ProgramService
from hangar.repository import HallConflict, HallNotFound
from hangar.stage import StageService
from postgres_graph_integration import (
    HALL_ID, OWNER_ID, STAGE_ID, WORLD_ID, apply_migrations_through,
    assert_database_identity, connect, create_stage, repository, reset_schema,
)
from postgres_program_model_integration import storage_fingerprint


MIGRATION=Path(__file__).parents[3]/"src/hangar/migrations/009_working_copy_identity.sql"


class WorkingCopyIdentityIntegrationTest(unittest.TestCase):
    def setUp(self):
        reset_schema()
        apply_migrations_through("008_program_block_catalog.sql")
        create_stage()
        with connect() as conn,conn.cursor() as cur:
            cur.execute("SELECT program_id FROM blockcommander.hall_program_mounts WHERE hall_id=%s",
                (str(HALL_ID),))
            self.program_id=str(cur.fetchone()[0])

    def add_hall(self):
        hall_id=str(uuid4())
        with connect() as conn,conn.cursor() as cur:
            cur.execute("""INSERT INTO blockcommander.halls(
                hall_id,create_request_id,owner_uuid,hall_name,world_uuid,
                world_name,dimension_key,min_x,min_y,min_z,max_x,max_y,max_z,
                occupied_min_x,occupied_min_y,occupied_min_z,
                occupied_max_x,occupied_max_y,occupied_max_z,orientation,
                material_name,material_id,generator_version,generator_params,lifecycle,hall_code)
                SELECT %s,%s,owner_uuid,'Second Hall',world_uuid,
                world_name,dimension_key,30,0,0,50,20,20,30,0,0,50,20,20,orientation,
                material_name,material_id,generator_version,generator_params,lifecycle,%s
                FROM blockcommander.halls WHERE hall_id=%s""",
                (hall_id,str(uuid4()),hall_id[:24],str(HALL_ID)))
        return hall_id

    def test_backfill_is_repeatable_and_preserves_program_stage_graph_data(self):
        second=self.add_hall()
        with connect() as conn,conn.cursor() as cur:
            cur.execute("INSERT INTO blockcommander.hall_program_mounts(hall_id,program_id) VALUES(%s,%s)",
                (second,self.program_id))
        before=storage_fingerprint()
        repository().migrate()
        first=repository().resolve_working_copy_context(HALL_ID)
        other=repository().resolve_working_copy_context(second)
        self.assertEqual(first.program_id,other.program_id)
        self.assertNotEqual(first.working_copy_id,other.working_copy_id)
        repository().migrate()
        with connect() as conn,conn.cursor() as cur:
            cur.execute(MIGRATION.read_text(encoding="utf-8"))
            cur.execute("SELECT count(*) FROM blockcommander.working_copies")
            self.assertEqual(2,cur.fetchone()[0])
        self.assertEqual(first,repository().resolve_working_copy_context(HALL_ID))
        self.assertEqual(other,repository().resolve_working_copy_context(second))
        self.assertEqual(before,storage_fingerprint())
        # Identity metadata must not claim an existing base or content snapshot.
        with connect() as conn,conn.cursor() as cur:
            cur.execute("""SELECT column_name FROM information_schema.columns
                WHERE table_schema='blockcommander' AND table_name='working_copies'""")
            self.assertEqual({"working_copy_id","program_id","created_at"},{row[0] for row in cur.fetchall()})

    def test_restart_read_dtos_and_shared_program_workspace_semantics(self):
        repository().migrate()
        context=repository().resolve_working_copy_context(HALL_ID)
        second=self.add_hall()
        # Fixture mount allocation; no new remount API is introduced.
        with connect() as conn,conn.cursor() as cur:
            copy_id=str(uuid4())
            cur.execute("INSERT INTO blockcommander.working_copies(working_copy_id,program_id) VALUES(%s,%s)",
                (copy_id,self.program_id))
            cur.execute("""INSERT INTO blockcommander.hall_program_mounts(hall_id,program_id,working_copy_id,active_stage_id)
                VALUES(%s,%s,%s,%s)""",(second,self.program_id,copy_id,str(STAGE_ID)))
        for _ in range(2):
            # Fresh app/services model a webhook restart without touching persisted rows.
            app=create_app(repository=repository(),migrate=False,graph_shared_secret="wc-test")
            client=app.test_client()
            mounted=client.get(f"/api/v1/halls/{HALL_ID}/program",
                headers={GRAPH_SECRET_HEADER:"wc-test"})
            self.assertEqual(200,mounted.status_code)
            self.assertEqual(str(context.working_copy_id),mounted.get_json()["mount"]["working_copy_id"])
            programs=app.extensions["blockcommander_program_service"]
            self.assertIs(programs.for_hall(HALL_ID),programs.for_hall(second))
            root=programs.root(self.program_id)
            self.assertIs(root,programs.stage(self.program_id,STAGE_ID))
            halls=client.get(f"/api/v1/worlds/{WORLD_ID}/halls")
            self.assertEqual(200,halls.status_code)
            view=next(row for row in halls.get_json()["halls"] if row["hall_id"]==str(HALL_ID))
            self.assertEqual(str(context.working_copy_id),view["working_copy_id"])
            self.assertEqual(str(context.program_id),view["program_id"])
        self.assertEqual(context,repository().resolve_working_copy_context(HALL_ID))

    def test_database_rejects_cross_program_missing_and_null_working_copy(self):
        repository().migrate()
        second=self.add_hall()
        new_stage=repository().ensure_stage(repository().get(second))
        other=repository().resolve_working_copy_context(second)
        self.assertNotEqual(str(other.program_id),self.program_id)
        # Use an unmounted foreign copy so this specifically exercises the
        # composite FK, independently of the newer mounted-copy uniqueness rule.
        foreign_copy=str(uuid4())
        with connect() as conn,conn.cursor() as cur:
            cur.execute("INSERT INTO blockcommander.working_copies(working_copy_id,program_id) VALUES(%s,%s)",
                (foreign_copy,str(other.program_id)))
        for copy_id in (foreign_copy,str(uuid4())):
            with self.assertRaises(errors.ForeignKeyViolation):
                with connect() as conn,conn.cursor() as cur:
                    cur.execute("UPDATE blockcommander.hall_program_mounts SET working_copy_id=%s WHERE hall_id=%s",
                        (copy_id,str(HALL_ID)))
        with self.assertRaises(errors.NotNullViolation):
            with connect() as conn,conn.cursor() as cur:
                cur.execute("UPDATE blockcommander.hall_program_mounts SET working_copy_id=NULL WHERE hall_id=%s",
                    (str(HALL_ID),))
        with self.assertRaises(HallConflict):
            repository().resolve_working_copy_context(HALL_ID,working_copy_id=other.working_copy_id)
        with self.assertRaises(HallConflict):
            repository().resolve_working_copy_context(HALL_ID,program_id=other.program_id)
        self.assertEqual(str(other.program_id),str(new_stage["program_id"]))

    def test_hall_delete_removes_mount_but_keeps_working_copy_and_program(self):
        repository().migrate()
        context=repository().resolve_working_copy_context(HALL_ID)
        second=self.add_hall()
        repository().delete(str(HALL_ID))
        with self.assertRaises(HallNotFound): repository().resolve_working_copy_context(HALL_ID)
        with connect() as conn,conn.cursor() as cur:
            cur.execute("SELECT program_id FROM blockcommander.working_copies WHERE working_copy_id=%s",
                (str(context.working_copy_id),))
            self.assertEqual(str(context.program_id),str(cur.fetchone()[0]))
            # A later explicit remount is schema-legal; identity is not owned by the old Hall.
            cur.execute("SELECT count(*) FROM blockcommander.hall_program_mounts")
            self.assertEqual(0,cur.fetchone()[0])
            cur.execute("""INSERT INTO blockcommander.hall_program_mounts(hall_id,program_id,working_copy_id,active_stage_id)
                VALUES(%s,%s,%s,%s)""",(second,str(context.program_id),str(context.working_copy_id),str(STAGE_ID)))
        self.assertEqual(str(context.program_id),str(repository().load_program(str(context.program_id)).program["program_id"]))
        remounted=repository().resolve_working_copy_context(second)
        self.assertEqual(context.working_copy_id,remounted.working_copy_id)

    def test_migration_rollback_leaves_old_mounts_intact(self):
        with connect() as conn,conn.cursor() as cur:
            cur.execute(MIGRATION.read_text(encoding="utf-8"))
            conn.rollback()
        with connect() as conn,conn.cursor() as cur:
            cur.execute("SELECT to_regclass('blockcommander.working_copies')")
            self.assertIsNone(cur.fetchone()[0])
            cur.execute("SELECT program_id FROM blockcommander.hall_program_mounts WHERE hall_id=%s",
                (str(HALL_ID),))
            self.assertEqual(self.program_id,str(cur.fetchone()[0]))
        repository().migrate()
        self.assertEqual(self.program_id,str(repository().resolve_working_copy_context(HALL_ID).program_id))

    def test_new_hall_allocates_identity_once_and_program_save_still_works(self):
        repository().migrate()
        hall_id=self.add_hall()
        repo=repository();hall=repo.get(hall_id)
        stage=repo.ensure_stage(hall)
        context=repo.resolve_working_copy_context(hall_id)
        self.assertEqual(str(context.program_id),str(stage["program_id"]))
        self.assertEqual(stage["stage_id"],repo.ensure_stage(hall)["stage_id"])
        self.assertEqual(context,repo.resolve_working_copy_context(hall_id))
        programs=ProgramService(repo,StageService(repo))
        root=programs.root(context.program_id)
        root.dirty=True
        receipt=programs.save(context.program_id,str(OWNER_ID))
        self.assertTrue(receipt.changed)
        self.assertFalse(root.dirty)
        self.assertEqual(context,repo.resolve_working_copy_context(hall_id))


if __name__=="__main__":
    assert_database_identity()
    unittest.main()
