import unittest
from pathlib import Path


class ProgramBlockCatalogMigrationStaticTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql=(Path(__file__).parents[1]/"src"/"hangar"/"migrations"/
                 "008_program_block_catalog.sql").read_text(encoding="utf-8")

    def test_registers_production_program_type(self):
        self.assertIn("('PROGRAM', 'PROGRAM', 'BLUE_GLAZED_TERRACOTTA'",self.sql)
        self.assertIn("ON CONFLICT (type_key) DO UPDATE",self.sql)

    def test_catalog_upgrade_does_not_rewrite_domain_content(self):
        self.assertIn("port_catalog_version='ck-control-v3'",self.sql)
        self.assertIn("port_catalog_version='ck-control-v2'",self.sql)
        self.assertIn("block.type_key='PROGRAM'",self.sql)
        for table in ("stage_blocks","graph_edges","edge_routes","route_steps","wire_cells","wire_channels"):
            self.assertNotIn(f"UPDATE blockcommander.{table}",self.sql)
            self.assertNotIn(f"DELETE FROM blockcommander.{table}",self.sql)


if __name__=="__main__": unittest.main()
