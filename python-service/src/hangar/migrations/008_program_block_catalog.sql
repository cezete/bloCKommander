INSERT INTO blockcommander.program_block_types
    (type_key, display_name, material_name, default_label, default_description, display_definition)
VALUES
    ('PROGRAM', 'PROGRAM', 'BLUE_GLAZED_TERRACOTTA', 'PROGRAM',
     'Gyermek PROGRAM_BODY Stage szerkezeti és navigációs eleme; nem végrehajtható.',
     '{}'::jsonb)
ON CONFLICT (type_key) DO UPDATE SET
    display_name=excluded.display_name,
    material_name=excluded.material_name,
    default_label=excluded.default_label,
    default_description=excluded.default_description,
    display_definition=excluded.display_definition;

-- ck-control-v3 adds only the previously absent, zero-port PROGRAM type.
-- A v2 snapshot without PROGRAM blocks has identical port semantics and can be
-- upgraded without touching blocks, edges, routes, wires or saved versions.
UPDATE blockcommander.stages AS stage
SET port_catalog_version='ck-control-v3'
WHERE stage.graph_snapshot_version=1
  AND stage.port_catalog_version='ck-control-v2'
  AND NOT EXISTS (
      SELECT 1
      FROM blockcommander.stage_blocks AS block
      WHERE block.stage_id=stage.stage_id
        AND block.type_key='PROGRAM'
  );
