import unittest
from pathlib import Path
from uuid import UUID

from hangar.graph import (
    CardinalDirection,
    EdgeKind,
    EdgeRoute,
    GraphEdge,
    GridPos,
    PORT_CATALOG_VERSION,
    PortRef,
    ProgramValidationStatus,
    RouteStatus,
    StageBlockSnapshot,
    StageSnapshot,
    WireCell,
    WireChannel,
    WireChannelState,
    WireStep,
)
from hangar.repository import (
    HallRepository,
    SnapshotCompatibilityError,
    SnapshotStorageError,
    StageVersionConflict,
    _snapshot_from_rows,
    _snapshot_rows,
)


STAGE_ID = UUID("00000000-0000-0000-0000-000000007001")
START_ID = UUID("00000000-0000-0000-0000-000000007002")
STOP_ID = UUID("00000000-0000-0000-0000-000000007003")
EDGE_ID = UUID("00000000-0000-0000-0000-000000007004")
ROUTE_ID = UUID("00000000-0000-0000-0000-000000007005")
UNBOUND_ROUTE_ID = UUID("00000000-0000-0000-0000-000000007006")


def block(instance_id, type_key, x, label):
    return StageBlockSnapshot(
        instance_id,
        type_key,
        GridPos(x, 0, 0),
        CardinalDirection.NORTH,
        label,
        f"{label} description",
        type_key,
        "STONE",
        {"front": "label"},
    )


def single_cell(x, connections=None):
    if connections is None:
        connections = frozenset({CardinalDirection.EAST, CardinalDirection.WEST})
    return WireCell(
        GridPos(x, 0, 0),
        (WireChannelState(WireChannel.SINGLE, connections),),
    )


def cross_cell(x=3):
    return WireCell(
        GridPos(x, 0, 0),
        (
            WireChannelState(
                WireChannel.NS,
                frozenset({CardinalDirection.NORTH, CardinalDirection.SOUTH}),
            ),
            WireChannelState(
                WireChannel.EW,
                frozenset({CardinalDirection.EAST, CardinalDirection.WEST}),
            ),
        ),
    )


def full_snapshot(*, port_version=PORT_CATALOG_VERSION):
    edge = GraphEdge(
        EDGE_ID,
        STAGE_ID,
        START_ID,
        "out",
        STOP_ID,
        "in",
        EdgeKind.CONTROL,
    )
    route = EdgeRoute(
        ROUTE_ID,
        STAGE_ID,
        EDGE_ID,
        PortRef(START_ID, "out"),
        PortRef(STOP_ID, "in"),
        (
            WireStep(GridPos(1, 0, 0), WireChannel.SINGLE),
            WireStep(GridPos(2, 0, 0), WireChannel.SINGLE),
        ),
        RouteStatus.VALID,
    )
    unbound = EdgeRoute(
        UNBOUND_ROUTE_ID,
        STAGE_ID,
        None,
        None,
        None,
        (WireStep(GridPos(9, 0, 0), WireChannel.SINGLE),),
        RouteStatus.BROKEN,
    )
    return StageSnapshot(
        STAGE_ID,
        (
            block(START_ID, "START", 0, "Indulás"),
            block(STOP_ID, "STOP", 4, "Megállás"),
        ),
        (edge,),
        (route, unbound),
        (single_cell(1), single_cell(2), cross_cell()),
        12,
        11,
        None,
        ProgramValidationStatus.NOT_CHECKED,
        port_version,
    )


def row_dicts(snapshot):
    rows = _snapshot_rows(snapshot)
    block_rows = []
    for values in rows.blocks:
        item = dict(
            zip(
                ("instance_id", "stage_id", "type_key", "rel_x", "rel_y", "rel_z", "orientation", "label", "description"),
                values,
            )
        )
        item.update(
            display_name=item["type_key"],
            material_name="STONE",
            display_definition={"front": "label"},
        )
        block_rows.append(item)
    edge_rows = [
        dict(zip(("edge_id", "stage_id", "source_instance_id", "source_port_key", "target_instance_id", "target_port_key", "edge_kind"), values))
        for values in rows.edges
    ]
    route_rows = [
        dict(zip(("route_id", "stage_id", "edge_id", "source_instance_id", "source_port_key", "target_instance_id", "target_port_key", "route_status"), values))
        for values in rows.routes
    ]
    step_rows = [
        dict(zip(("route_id", "stage_id", "step_index", "rel_x", "rel_y", "rel_z", "channel"), values))
        for values in rows.steps
    ]
    cell_rows = [
        dict(zip(("stage_id", "rel_x", "rel_y", "rel_z", "cell_kind"), values))
        for values in rows.wire_cells
    ]
    channel_rows = [
        dict(zip(("stage_id", "rel_x", "rel_y", "rel_z", "channel", "connections_mask"), values))
        for values in rows.wire_channels
    ]
    return block_rows, edge_rows, route_rows, step_rows, cell_rows, channel_rows


def persisted_stage(port_version=PORT_CATALOG_VERSION):
    return {
        "stage_id": str(STAGE_ID),
        "saved_version": 8,
        "graph_snapshot_version": 1,
        "port_catalog_version": port_version,
    }


class RecordingCursor:
    def __init__(self, select_row=(4, 0), fail_on=None):
        self.select_row = select_row
        self.fail_on = fail_on
        self.calls = []

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def execute(self, sql, params=None):
        normalized = " ".join(sql.split())
        self.calls.append((normalized, params))
        if self.fail_on and self.fail_on in normalized:
            raise RuntimeError("injected unit-test failure")

    def fetchone(self):
        return self.select_row


class RecordingConnection:
    def __init__(self, cursor):
        self._cursor = cursor
        self.committed = False
        self.rolled_back = False

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        self.committed = exc_type is None
        self.rolled_back = exc_type is not None
        return False

    def cursor(self, **_kwargs):
        return self._cursor


class LoadCursor:
    def __init__(self, stage, row_sets):
        self.stage = stage
        self.row_sets = row_sets
        self.current = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def execute(self, sql, _params=None):
        normalized = " ".join(sql.split())
        if normalized.startswith("SELECT s.*"):
            self.current = [self.stage]
        elif "FROM blockcommander.stage_blocks" in normalized:
            self.current = self.row_sets[0]
        elif "FROM blockcommander.graph_edges" in normalized:
            self.current = self.row_sets[1]
        elif "FROM blockcommander.edge_routes" in normalized:
            self.current = self.row_sets[2]
        elif "FROM blockcommander.route_steps" in normalized:
            self.current = self.row_sets[3]
        elif "FROM blockcommander.wire_cells" in normalized:
            self.current = self.row_sets[4]
        elif "FROM blockcommander.wire_channels" in normalized:
            self.current = self.row_sets[5]
        else:
            raise AssertionError(normalized)

    def fetchone(self):
        return None if not self.current else self.current[0]

    def fetchall(self):
        return list(self.current or ())


class RepositoryGraphSnapshotUnitTest(unittest.TestCase):
    def deserialize(self, snapshot=None, **row_changes):
        snapshot = snapshot or full_snapshot()
        rows = list(row_dicts(snapshot))
        names = ("block_rows", "edge_rows", "route_rows", "step_rows", "cell_rows", "channel_rows")
        for index, name in enumerate(names):
            if name in row_changes:
                rows[index] = row_changes[name]
        return _snapshot_from_rows(persisted_stage(snapshot.port_catalog_version), *rows)

    def test_empty_snapshot_serializes_to_empty_table_sets(self):
        empty = StageSnapshot(
            STAGE_ID, (), (), (), (), 0, None, None,
            ProgramValidationStatus.NOT_CHECKED, PORT_CATALOG_VERSION,
        )
        self.assertEqual(_snapshot_rows(empty).blocks, ())
        self.assertEqual(_snapshot_rows(empty).wire_channels, ())

    def test_full_snapshot_round_trip_preserves_graph_and_metadata(self):
        source = full_snapshot()
        loaded = self.deserialize(source)
        self.assertEqual(loaded.blocks, source.blocks)
        self.assertEqual(loaded.edges, source.edges)
        self.assertEqual(loaded.routes, source.routes)
        self.assertEqual(loaded.wire_cells, source.wire_cells)

    def test_load_resets_process_local_state_only(self):
        loaded = self.deserialize()
        self.assertEqual(loaded.working_revision, 0)
        self.assertIsNone(loaded.validated_revision)
        self.assertIsNone(loaded.last_graph_validation)

    def test_edge_and_route_ids_are_stable(self):
        loaded = self.deserialize()
        self.assertEqual(loaded.edges[0].edge_id, EDGE_ID)
        self.assertEqual({item.route_id for item in loaded.routes}, {ROUTE_ID, UNBOUND_ROUTE_ID})

    def test_multistep_route_order_is_stable(self):
        loaded = self.deserialize()
        bound = next(item for item in loaded.routes if item.route_id == ROUTE_ID)
        self.assertEqual(tuple(step.position.x for step in bound.steps), (1, 2))

    def test_cross_channels_and_masks_round_trip_independently(self):
        rows = _snapshot_rows(full_snapshot())
        cross = tuple(item for item in rows.wire_channels if item[1] == 3)
        self.assertEqual({(item[4], item[5]) for item in cross}, {("NS", 5), ("EW", 10)})
        loaded = self.deserialize()
        cell = next(item for item in loaded.wire_cells if item.position.x == 3)
        self.assertEqual(tuple(item.channel for item in cell.channels), (WireChannel.NS, WireChannel.EW))

    def test_unbound_wire_and_route_are_preserved(self):
        loaded = self.deserialize()
        self.assertTrue(any(cell.position.x == 3 for cell in loaded.wire_cells))
        route = next(item for item in loaded.routes if item.route_id == UNBOUND_ROUTE_ID)
        self.assertIsNone(route.edge_id)
        self.assertIsNone(route.source_port)

    def test_broken_route_may_reference_missing_wire_cell(self):
        route = next(item for item in self.deserialize().routes if item.route_id == UNBOUND_ROUTE_ID)
        self.assertEqual(route.status, RouteStatus.BROKEN)
        self.assertEqual(route.steps[0].position, GridPos(9, 0, 0))

    def test_storage_rejects_edge_endpoint_missing_from_blocks(self):
        invalid = full_snapshot()
        invalid_edge = GraphEdge(EDGE_ID, STAGE_ID, UUID(int=999), "out", STOP_ID, "in", EdgeKind.CONTROL)
        invalid = StageSnapshot(
            invalid.stage_id, invalid.blocks, (invalid_edge,), (), invalid.wire_cells,
            invalid.working_revision, invalid.validated_revision, None,
            invalid.program_validation_status, invalid.port_catalog_version,
        )
        with self.assertRaises(SnapshotStorageError):
            _snapshot_rows(invalid)

    def test_storage_rejects_route_bound_to_missing_edge(self):
        source = full_snapshot()
        with self.assertRaises(SnapshotStorageError):
            _snapshot_rows(StageSnapshot(
                source.stage_id, source.blocks, (), (source.routes[0],), source.wire_cells,
                0, None, None, ProgramValidationStatus.NOT_CHECKED, PORT_CATALOG_VERSION,
            ))

    def test_deserializer_rejects_gapped_route_steps(self):
        rows = row_dicts(full_snapshot())
        rows[3][1]["step_index"] = 3
        with self.assertRaises(SnapshotStorageError):
            self.deserialize(step_rows=rows[3])

    def test_deserializer_rejects_cell_kind_channel_mismatch(self):
        rows = row_dicts(full_snapshot())
        rows[4][0]["cell_kind"] = "CROSS"
        with self.assertRaises(SnapshotStorageError):
            self.deserialize(cell_rows=rows[4])

    def test_deserializer_rejects_invalid_connection_mask(self):
        rows = row_dicts(full_snapshot())
        rows[5][0]["connections_mask"] = 16
        with self.assertRaises(SnapshotStorageError):
            self.deserialize(channel_rows=rows[5])

    def test_full_save_uses_one_context_transaction_and_increments_once(self):
        cursor = RecordingCursor((4, 0))
        connection = RecordingConnection(cursor)
        repository = HallRepository(connect=lambda: connection)
        self.assertEqual(repository.save_full_stage_snapshot(str(STAGE_ID), 4, full_snapshot()), 5)
        self.assertTrue(connection.committed)
        update = next(call for call in cursor.calls if call[0].startswith("UPDATE blockcommander.stages"))
        self.assertEqual(update[1][0], 5)
        self.assertEqual(update[1][1:3], (1, PORT_CATALOG_VERSION))

    def test_stale_saved_version_stops_before_snapshot_delete(self):
        cursor = RecordingCursor((5, 1))
        repository = HallRepository(connect=lambda: RecordingConnection(cursor))
        with self.assertRaises(StageVersionConflict):
            repository.save_full_stage_snapshot(str(STAGE_ID), 4, full_snapshot())
        self.assertFalse(any(call[0].startswith("DELETE") for call in cursor.calls))

    def test_unknown_persisted_snapshot_version_blocks_save(self):
        cursor = RecordingCursor((4, 99))
        repository = HallRepository(connect=lambda: RecordingConnection(cursor))
        with self.assertRaises(SnapshotCompatibilityError):
            repository.save_full_stage_snapshot(str(STAGE_ID), 4, full_snapshot())

    def test_unknown_port_catalog_blocks_before_connect(self):
        connected = False

        def connect():
            nonlocal connected
            connected = True
            raise AssertionError

        repository = HallRepository(connect=connect)
        with self.assertRaises(SnapshotCompatibilityError):
            repository.save_full_stage_snapshot(str(STAGE_ID), 4, full_snapshot(port_version="future"))
        self.assertFalse(connected)

    def test_injected_sql_failure_exits_transaction_as_rollback(self):
        cursor = RecordingCursor((4, 0), fail_on="INSERT INTO blockcommander.graph_edges")
        connection = RecordingConnection(cursor)
        repository = HallRepository(connect=lambda: connection)
        with self.assertRaises(RuntimeError):
            repository.save_full_stage_snapshot(str(STAGE_ID), 4, full_snapshot())
        self.assertTrue(connection.rolled_back)
        self.assertFalse(any(call[0].startswith("UPDATE blockcommander.stages") for call in cursor.calls))

    def test_legacy_block_save_cannot_overwrite_graph_snapshot(self):
        cursor = RecordingCursor((4, 1))
        repository = HallRepository(connect=lambda: RecordingConnection(cursor))
        with self.assertRaises(SnapshotCompatibilityError):
            repository.save_stage_snapshot(str(STAGE_ID), 4, [])

    def test_full_load_returns_compatible_persistent_envelope(self):
        source = full_snapshot()
        row_sets = row_dicts(source)
        stage = persisted_stage()
        cursor = LoadCursor(stage, row_sets)
        repository = HallRepository(connect=lambda: RecordingConnection(cursor))
        loaded = repository.load_full_stage_snapshot(str(STAGE_ID))
        self.assertTrue(loaded.compatible_for_graph_mutation)
        self.assertEqual(loaded.saved_version, 8)
        self.assertEqual(loaded.snapshot.edges, source.edges)
        self.assertEqual(loaded.snapshot.routes, source.routes)

    def test_legacy_load_preserves_blocks_without_inventing_graph(self):
        source = full_snapshot()
        block_rows = row_dicts(source)[0]
        stage = persisted_stage(None)
        stage["graph_snapshot_version"] = 0
        repository = HallRepository(
            connect=lambda: RecordingConnection(
                LoadCursor(stage, (block_rows, [], [], [], [], []))
            )
        )
        loaded = repository.load_full_stage_snapshot(str(STAGE_ID))
        self.assertFalse(loaded.compatible_for_graph_mutation)
        self.assertEqual(len(loaded.snapshot.blocks), 2)
        self.assertEqual((loaded.snapshot.edges, loaded.snapshot.routes, loaded.snapshot.wire_cells), ((), (), ()))
        self.assertIsNone(loaded.stored_port_catalog_version)
        self.assertIn("explicit graph format", loaded.compatibility_issues[0])

    def test_unknown_snapshot_version_loads_read_only_with_issue(self):
        source = full_snapshot()
        stage = persisted_stage()
        stage["graph_snapshot_version"] = 99
        repository = HallRepository(
            connect=lambda: RecordingConnection(LoadCursor(stage, row_dicts(source)))
        )
        loaded = repository.load_full_stage_snapshot(str(STAGE_ID))
        self.assertFalse(loaded.compatible_for_graph_mutation)
        self.assertIn("Unknown graph snapshot version", loaded.compatibility_issues[0])
        self.assertEqual(loaded.snapshot.edges, source.edges)

    def test_unknown_port_version_loads_read_only_with_issue(self):
        source = full_snapshot(port_version="future")
        repository = HallRepository(
            connect=lambda: RecordingConnection(
                LoadCursor(persisted_stage("future"), row_dicts(source))
            )
        )
        loaded = repository.load_full_stage_snapshot(str(STAGE_ID))
        self.assertFalse(loaded.compatible_for_graph_mutation)
        self.assertIn("Unsupported port catalog version", loaded.compatibility_issues[0])


class GraphMigrationStaticTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = (
            Path(__file__).parents[1]
            / "src"
            / "hangar"
            / "migrations"
            / "006_graph_snapshots.sql"
        ).read_text(encoding="utf-8")

    def test_migration_is_additive_and_has_no_graph_nodes_table(self):
        self.assertIn("ADD COLUMN graph_snapshot_version", self.sql)
        self.assertNotIn("CREATE TABLE blockcommander.graph_nodes", self.sql)
        self.assertNotIn("DROP TABLE", self.sql.upper())

    def test_migration_defines_all_graph_tables(self):
        for table in ("graph_edges", "edge_routes", "route_steps", "wire_cells", "wire_channels"):
            self.assertIn(f"CREATE TABLE blockcommander.{table}", self.sql)

    def test_cardinality_triggers_cover_cell_and_channel_mutations(self):
        self.assertIn("AFTER INSERT OR UPDATE OR DELETE ON blockcommander.wire_cells", self.sql)
        self.assertIn("AFTER INSERT OR UPDATE OR DELETE ON blockcommander.wire_channels", self.sql)
        self.assertEqual(self.sql.count("DEFERRABLE INITIALLY DEFERRED\nFOR EACH ROW"), 2)

    def test_wire_channel_does_not_duplicate_cell_kind(self):
        table = self.sql.split("CREATE TABLE blockcommander.wire_channels", 1)[1].split(");", 1)[0]
        self.assertNotIn("cell_kind", table)


if __name__ == "__main__":
    unittest.main()
