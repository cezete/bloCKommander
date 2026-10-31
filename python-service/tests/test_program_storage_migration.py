import unittest
from pathlib import Path


class ProgramStorageMigrationStaticTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = (
            Path(__file__).parents[1]
            / "src"
            / "hangar"
            / "migrations"
            / "007_program_stage_hall_separation.sql"
        ).read_text(encoding="utf-8")
        cls.upper_sql = cls.sql.upper()

    def test_introduces_program_hierarchy_and_mount_tables(self):
        for table in ("programs", "program_stage_links", "hall_program_mounts"):
            self.assertIn(f"CREATE TABLE blockcommander.{table}", self.sql)
        self.assertIn("ADD COLUMN program_id uuid", self.sql)
        self.assertIn("ADD COLUMN stage_kind varchar(32)", self.sql)

    def test_root_and_stage_program_relations_are_database_enforced(self):
        self.assertIn("stages_one_root_per_program_idx", self.sql)
        self.assertIn("programs_root_stage_fkey", self.sql)
        self.assertIn("stages_program_id_fkey", self.sql)
        self.assertIn("GENERATED ALWAYS AS ('ROOT'::varchar(32)) STORED", self.sql)
        self.assertIn("ON DELETE RESTRICT", self.sql)

    def test_hall_delete_only_cascades_its_mount(self):
        self.assertIn("stages_legacy_hall_id_fkey", self.sql)
        self.assertIn("REFERENCES blockcommander.halls(hall_id) ON DELETE SET NULL", self.sql)
        self.assertIn("hall_id uuid PRIMARY KEY", self.sql)
        self.assertIn("REFERENCES blockcommander.halls(hall_id) ON DELETE CASCADE", self.sql)
        self.assertNotIn(
            "REFERENCES BLOCKCOMMANDER.HALLS(HALL_ID) ON DELETE CASCADE,\n    STAGE_ID",
            self.upper_sql,
        )

    def test_backfill_does_not_rewrite_saved_graph_tables(self):
        for table in (
            "stage_blocks",
            "graph_edges",
            "edge_routes",
            "route_steps",
            "wire_cells",
            "wire_channels",
        ):
            self.assertNotIn(f"UPDATE blockcommander.{table}", self.sql)
            self.assertNotIn(f"DELETE FROM blockcommander.{table}", self.sql)
        self.assertNotIn("DROP TABLE", self.upper_sql)


if __name__ == "__main__":
    unittest.main()
