-- Destructive development reset for the blockcommander PostgreSQL schema.
--
-- Before running this script, stop both the Fabric server and the Python
-- webhook. Otherwise an in-memory workspace may write deleted data back.
--
-- Intentionally preserved:
--   * blockcommander.schema_migrations
--   * blockcommander.program_block_types
--
-- This resets canonical PostgreSQL data only. It cannot remove blocks or
-- hangar structures already present in a Minecraft world save.

BEGIN;

SET LOCAL lock_timeout = '5s';

DO $$
DECLARE
    unexpected_tables text;
BEGIN
    SELECT string_agg(table_name, ', ' ORDER BY table_name)
      INTO unexpected_tables
      FROM information_schema.tables
     WHERE table_schema = 'blockcommander'
       AND table_type = 'BASE TABLE'
       AND table_name NOT IN (
           'schema_migrations',
           'program_block_types',
           'developers',
           'halls',
           'hall_controls',
           'stages',
           'stage_blocks',
           'graph_edges',
           'edge_routes',
           'route_steps',
           'wire_cells',
           'wire_channels'
       );

    IF unexpected_tables IS NOT NULL THEN
        RAISE EXCEPTION
            'Reset aborted: unknown blockcommander tables exist: %',
            unexpected_tables;
    END IF;
END
$$;

-- One TRUNCATE statement lets PostgreSQL validate the complete FK-connected
-- set atomically. CASCADE is deliberately omitted so a future referencing
-- table cannot be erased silently.
TRUNCATE TABLE
    blockcommander.wire_channels,
    blockcommander.route_steps,
    blockcommander.edge_routes,
    blockcommander.graph_edges,
    blockcommander.wire_cells,
    blockcommander.stage_blocks,
    blockcommander.stages,
    blockcommander.hall_controls,
    blockcommander.halls,
    blockcommander.developers;

DO $$
DECLARE
    remaining_rows bigint;
BEGIN
    SELECT
        (SELECT count(*) FROM blockcommander.wire_channels) +
        (SELECT count(*) FROM blockcommander.route_steps) +
        (SELECT count(*) FROM blockcommander.edge_routes) +
        (SELECT count(*) FROM blockcommander.graph_edges) +
        (SELECT count(*) FROM blockcommander.wire_cells) +
        (SELECT count(*) FROM blockcommander.stage_blocks) +
        (SELECT count(*) FROM blockcommander.stages) +
        (SELECT count(*) FROM blockcommander.hall_controls) +
        (SELECT count(*) FROM blockcommander.halls) +
        (SELECT count(*) FROM blockcommander.developers)
      INTO remaining_rows;

    IF remaining_rows <> 0 THEN
        RAISE EXCEPTION
            'Reset verification failed: % mutable rows remain',
            remaining_rows;
    END IF;
END
$$;

COMMIT;

-- Expected result: zero mutable rows, while migrations and the CK block-type
-- catalog remain available.
SELECT
    (SELECT count(*) FROM blockcommander.schema_migrations) AS migration_rows,
    (SELECT count(*) FROM blockcommander.program_block_types) AS block_type_rows,
    (SELECT count(*) FROM blockcommander.halls) AS hall_rows,
    (SELECT count(*) FROM blockcommander.stages) AS stage_rows,
    (SELECT count(*) FROM blockcommander.stage_blocks) AS stage_block_rows,
    (SELECT count(*) FROM blockcommander.graph_edges) AS graph_edge_rows,
    (SELECT count(*) FROM blockcommander.edge_routes) AS edge_route_rows,
    (SELECT count(*) FROM blockcommander.wire_cells) AS wire_cell_rows;
