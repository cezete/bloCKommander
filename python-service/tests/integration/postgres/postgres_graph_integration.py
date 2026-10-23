"""Destructive PostgreSQL tests for the dedicated CK-03/B2-T database only.

The filename intentionally does not match unittest's default ``test*.py``
pattern. Run it through run-tests.ps1, which verifies Docker ownership first.
"""
from __future__ import annotations

import hashlib
import os
import threading
import time
import unittest
from pathlib import Path
from uuid import UUID

import psycopg2
from psycopg2 import errors

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
    StageVersionConflict,
)


EXPECTED_DATABASE = "blockcommander_b2t"
EXPECTED_USER = "blockcommander_b2t"
EXPECTED_IDENTITY = "5f4b53b6-2c82-4d68-b788-7860daf2b70f"
EXPECTED_PROJECT = "blockcommander-ck03-b2t"
REQUIRED_ENV = (
    "BLOCKCOMMANDER_TEST_DB_DSN",
    "BLOCKCOMMANDER_TEST_IDENTITY",
    "BLOCKCOMMANDER_TEST_COMPOSE_PROJECT",
    "BLOCKCOMMANDER_TEST_CONTAINER_ID",
)

STAGE_ID = UUID("00000000-0000-0000-0000-000000007001")
START_ID = UUID("00000000-0000-0000-0000-000000007002")
STOP_ID = UUID("00000000-0000-0000-0000-000000007003")
EDGE_ID = UUID("00000000-0000-0000-0000-000000007004")
ROUTE_ID = UUID("00000000-0000-0000-0000-000000007005")
UNBOUND_ROUTE_ID = UUID("00000000-0000-0000-0000-000000007006")
ROUTELESS_EDGE_ID = UUID("00000000-0000-0000-0000-000000007007")
OWNER_ID = UUID("00000000-0000-0000-0000-000000007010")
HALL_ID = UUID("00000000-0000-0000-0000-000000007011")
WORLD_ID = UUID("00000000-0000-0000-0000-000000007012")
REQUEST_ID = UUID("00000000-0000-0000-0000-000000007013")


def require_explicit_target() -> str:
    missing = [name for name in REQUIRED_ENV if not os.environ.get(name)]
    if missing:
        raise RuntimeError(
            "Refusing PostgreSQL integration without explicit B2-T environment: "
            + ", ".join(missing)
        )
    if os.environ["BLOCKCOMMANDER_TEST_IDENTITY"] != EXPECTED_IDENTITY:
        raise RuntimeError("B2-T identity environment mismatch")
    if os.environ["BLOCKCOMMANDER_TEST_COMPOSE_PROJECT"] != EXPECTED_PROJECT:
        raise RuntimeError("B2-T Compose project environment mismatch")
    if len(os.environ["BLOCKCOMMANDER_TEST_CONTAINER_ID"]) != 64:
        raise RuntimeError("B2-T container ID is not a full Docker ID")
    return os.environ["BLOCKCOMMANDER_TEST_DB_DSN"]


DSN = require_explicit_target()
MIGRATIONS = Path(__file__).parents[3] / "src" / "hangar" / "migrations"


def connect(application_name: str = "blockcommander-b2t"):
    return psycopg2.connect(DSN, application_name=application_name)


def assert_database_identity() -> None:
    with connect("blockcommander-b2t-identity") as conn, conn.cursor() as cur:
        cur.execute(
            """SELECT current_database(), current_user,
                      current_setting('server_version_num')::integer,
                      inet_server_port()"""
        )
        database, user, version, server_port = cur.fetchone()
        cur.execute(
            """SELECT identity::text, compose_project, purpose
                 FROM public.blockcommander_test_identity"""
        )
        marker = cur.fetchone()
        cur.execute(
            """SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication
                 FROM pg_roles WHERE rolname=current_user"""
        )
        privileges = cur.fetchone()
    if database != EXPECTED_DATABASE or user != EXPECTED_USER:
        raise RuntimeError(f"Refusing unexpected database identity: {database}/{user}")
    if database.lower() == "blockommander" or server_port != 5432:
        raise RuntimeError("Refusing a production-like database target")
    if version // 10000 != 18:
        raise RuntimeError(f"Expected PostgreSQL 18 compatibility target, got {version}")
    if marker != (
        EXPECTED_IDENTITY,
        EXPECTED_PROJECT,
        "disposable-postgresql-integration",
    ):
        raise RuntimeError(f"B2-T marker mismatch: {marker!r}")
    if privileges != (False, False, False, False):
        raise RuntimeError(f"B2-T role is over-privileged: {privileges!r}")


def reset_schema() -> None:
    assert_database_identity()
    conn = connect("blockcommander-b2t-reset")
    conn.autocommit = True
    try:
        with conn.cursor() as cur:
            cur.execute("DROP SCHEMA IF EXISTS blockcommander CASCADE")
    finally:
        conn.close()


def repository() -> HallRepository:
    return HallRepository(connect=connect)


def apply_migrations_through(last_name: str) -> None:
    with connect("blockcommander-b2t-upgrade") as conn, conn.cursor() as cur:
        cur.execute(
            """CREATE SCHEMA IF NOT EXISTS blockcommander;
               CREATE TABLE IF NOT EXISTS blockcommander.schema_migrations(
                   version text PRIMARY KEY,
                   checksum text NOT NULL,
                   applied_at timestamptz NOT NULL DEFAULT now()
               )"""
        )
        for path in sorted(MIGRATIONS.glob("*.sql")):
            if path.name > last_name:
                break
            sql = path.read_text(encoding="utf-8")
            cur.execute(sql)
            cur.execute(
                "INSERT INTO blockcommander.schema_migrations(version,checksum) VALUES(%s,%s)",
                (path.name, hashlib.sha256(sql.encode()).hexdigest()),
            )


def create_stage(*, with_legacy_block: bool = False) -> None:
    with connect("blockcommander-b2t-fixture") as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO blockcommander.developers(player_uuid,current_name) VALUES(%s,%s)",
            (str(OWNER_ID), "B2T owner"),
        )
        cur.execute(
            """INSERT INTO blockcommander.halls(
                   hall_id,create_request_id,owner_uuid,hall_name,world_uuid,
                   world_name,dimension_key,min_x,min_y,min_z,max_x,max_y,max_z,
                   occupied_min_x,occupied_min_y,occupied_min_z,
                   occupied_max_x,occupied_max_y,occupied_max_z,
                   orientation,material_name,material_id,generator_version,
                   generator_params,lifecycle,hall_code)
               VALUES(%s,%s,%s,'B2T hall',%s,'B2T world','minecraft:overworld',
                      0,0,0,20,20,20,0,0,0,20,20,20,
                      'NORTH','STONE',1,2,'{}'::jsonb,'ACTIVE','b2t')""",
            (str(HALL_ID), str(REQUEST_ID), str(OWNER_ID), str(WORLD_ID)),
        )
        cur.execute(
            """INSERT INTO blockcommander.stages(
                   stage_id,hall_id,stage_name,origin_rel_x,origin_rel_y,origin_rel_z,
                   size_x,size_y,size_z)
               VALUES(%s,%s,'B2T stage',0,0,0,20,20,20)""",
            (str(STAGE_ID), str(HALL_ID)),
        )
        if with_legacy_block:
            cur.execute(
                """INSERT INTO blockcommander.stage_blocks(
                       instance_id,stage_id,type_key,rel_x,rel_y,rel_z,
                       orientation,label,description)
                   VALUES(%s,%s,'START',1,2,3,'EAST','Legacy label','Legacy description')""",
                (str(START_ID), str(STAGE_ID)),
            )


def block(instance_id: UUID, type_key: str, x: int, label: str) -> StageBlockSnapshot:
    return StageBlockSnapshot(
        instance_id,
        type_key,
        GridPos(x, 0, 0),
        CardinalDirection.NORTH,
        label,
        f"{label} description",
        type_key,
        "IGNORED_CATALOG_VALUE",
        {"ignored": "catalog value"},
    )


def single_cell(x: int) -> WireCell:
    return WireCell(
        GridPos(x, 0, 0),
        (
            WireChannelState(
                WireChannel.SINGLE,
                frozenset({CardinalDirection.EAST, CardinalDirection.WEST}),
            ),
        ),
    )


def cross_cell(x: int = 3) -> WireCell:
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


def full_snapshot(label: str = "Indulás") -> StageSnapshot:
    edge = GraphEdge(
        EDGE_ID, STAGE_ID, START_ID, "out", STOP_ID, "in", EdgeKind.CONTROL
    )
    routeless_edge = GraphEdge(
        ROUTELESS_EDGE_ID,
        STAGE_ID,
        START_ID,
        "alternate_out",
        STOP_ID,
        "alternate_in",
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
    broken_unbound = EdgeRoute(
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
            block(START_ID, "START", 0, label),
            block(STOP_ID, "STOP", 4, "Megállás"),
        ),
        (edge, routeless_edge),
        (route, broken_unbound),
        (single_cell(1), single_cell(2), cross_cell()),
        12,
        11,
        None,
        ProgramValidationStatus.NOT_CHECKED,
        PORT_CATALOG_VERSION,
    )


class PostgreSQLMigrationIntegrationTest(unittest.TestCase):
    def setUp(self):
        reset_schema()

    def test_fresh_migration_on_empty_database(self):
        repository().migrate()
        repository().migrate()
        with connect() as conn, conn.cursor() as cur:
            cur.execute("SELECT version FROM blockcommander.schema_migrations ORDER BY version")
            self.assertEqual([row[0] for row in cur.fetchall()], [path.name for path in sorted(MIGRATIONS.glob('*.sql'))])
            cur.execute(
                """SELECT tablename FROM pg_tables
                     WHERE schemaname='blockcommander' ORDER BY tablename"""
            )
            tables = {row[0] for row in cur.fetchall()}
            self.assertTrue(
                {"graph_edges", "edge_routes", "route_steps", "wire_cells", "wire_channels"}
                <= tables
            )
            cur.execute(
                """SELECT tgname, tgdeferrable, tginitdeferred
                     FROM pg_trigger
                     WHERE tgname IN ('wire_cells_channel_cardinality',
                                      'wire_channels_cell_cardinality')
                     ORDER BY tgname"""
            )
            self.assertEqual(
                cur.fetchall(),
                [
                    ("wire_cells_channel_cardinality", True, True),
                    ("wire_channels_cell_cardinality", True, True),
                ],
            )

    def test_upgrade_005_to_006_preserves_legacy_rows(self):
        apply_migrations_through("005_ck_block_types.sql")
        create_stage(with_legacy_block=True)
        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                """SELECT instance_id,type_key,rel_x,rel_y,rel_z,orientation,label,description
                     FROM blockcommander.stage_blocks WHERE stage_id=%s""",
                (str(STAGE_ID),),
            )
            before = cur.fetchone()
        repository().migrate()
        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                """SELECT instance_id,type_key,rel_x,rel_y,rel_z,orientation,label,description
                     FROM blockcommander.stage_blocks WHERE stage_id=%s""",
                (str(STAGE_ID),),
            )
            self.assertEqual(cur.fetchone(), before)
            cur.execute(
                """SELECT saved_version,graph_snapshot_version,port_catalog_version
                     FROM blockcommander.stages WHERE stage_id=%s""",
                (str(STAGE_ID),),
            )
            self.assertEqual(cur.fetchone(), (0, 0, None))
        loaded = repository().load_full_stage_snapshot(str(STAGE_ID))
        self.assertFalse(loaded.compatible_for_graph_mutation)
        self.assertEqual(loaded.snapshot.blocks[0].label, "Legacy label")
        self.assertEqual(
            (loaded.snapshot.edges, loaded.snapshot.routes, loaded.snapshot.wire_cells),
            ((), (), ()),
        )
        repository().save_full_stage_snapshot(str(STAGE_ID), 0, full_snapshot())
        with self.assertRaises(SnapshotCompatibilityError):
            repository().save_stage_snapshot(str(STAGE_ID), 1, [])


class WireConstraintIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        reset_schema()
        repository().migrate()
        create_stage()

    def setUp(self):
        with connect() as conn, conn.cursor() as cur:
            cur.execute("DELETE FROM blockcommander.wire_channels WHERE stage_id=%s", (str(STAGE_ID),))
            cur.execute("DELETE FROM blockcommander.wire_cells WHERE stage_id=%s", (str(STAGE_ID),))

    def commit_case(self, statements, *, accepted: bool):
        try:
            with connect() as conn, conn.cursor() as cur:
                for sql, params in statements:
                    cur.execute(sql, params)
        except psycopg2.Error:
            if accepted:
                raise
        else:
            if not accepted:
                self.fail("Invalid wire shape unexpectedly committed")

    def rows(self):
        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                """SELECT c.rel_x,c.rel_y,c.rel_z,c.cell_kind,
                          array_agg(ch.channel ORDER BY ch.channel)
                     FROM blockcommander.wire_cells c
                     LEFT JOIN blockcommander.wire_channels ch
                       USING(stage_id,rel_x,rel_y,rel_z)
                    WHERE c.stage_id=%s
                    GROUP BY c.rel_x,c.rel_y,c.rel_z,c.cell_kind
                    ORDER BY c.rel_x,c.rel_y,c.rel_z""",
                (str(STAGE_ID),),
            )
            return cur.fetchall()

    def cell(self, kind="SINGLE", x=1):
        return (
            "INSERT INTO blockcommander.wire_cells(stage_id,rel_x,rel_y,rel_z,cell_kind) VALUES(%s,%s,0,0,%s)",
            (str(STAGE_ID), x, kind),
        )

    def channel(self, channel="SINGLE", mask=10, x=1):
        return (
            "INSERT INTO blockcommander.wire_channels(stage_id,rel_x,rel_y,rel_z,channel,connections_mask) VALUES(%s,%s,0,0,%s,%s)",
            (str(STAGE_ID), x, channel, mask),
        )

    def test_insert_matrix_at_real_commit(self):
        cases = (
            ("valid SINGLE", [self.cell(), self.channel()], True),
            ("valid CROSS", [self.cell("CROSS"), self.channel("NS", 5), self.channel("EW", 10)], True),
            ("SINGLE missing channel", [self.cell()], False),
            ("SINGLE wrong channel", [self.cell(), self.channel("NS", 5)], False),
            ("CROSS missing EW", [self.cell("CROSS"), self.channel("NS", 5)], False),
            ("CROSS missing NS", [self.cell("CROSS"), self.channel("EW", 10)], False),
            ("CROSS extra SINGLE", [self.cell("CROSS"), self.channel("NS", 5), self.channel("EW", 10), self.channel()], False),
            ("CROSS invalid NS mask", [self.cell("CROSS"), self.channel("NS", 10), self.channel("EW", 10)], False),
            ("invalid SINGLE mask", [self.cell(), self.channel("SINGLE", 15)], False),
        )
        for name, statements, accepted in cases:
            with self.subTest(name=name):
                with connect() as conn, conn.cursor() as cur:
                    cur.execute("DELETE FROM blockcommander.wire_channels WHERE stage_id=%s", (str(STAGE_ID),))
                    cur.execute("DELETE FROM blockcommander.wire_cells WHERE stage_id=%s", (str(STAGE_ID),))
                self.commit_case(statements, accepted=accepted)
                self.assertEqual(bool(self.rows()), accepted)

    def test_duplicate_cross_channel_is_rejected_and_rolled_back(self):
        statements = [
            self.cell("CROSS"),
            self.channel("NS", 5),
            self.channel("NS", 5),
            self.channel("EW", 10),
        ]
        self.commit_case(statements, accepted=False)
        self.assertEqual(self.rows(), [])

    def test_delete_one_cross_channel_is_rejected_without_partial_change(self):
        self.commit_case(
            [self.cell("CROSS"), self.channel("NS", 5), self.channel("EW", 10)],
            accepted=True,
        )
        before = self.rows()
        self.commit_case(
            [("DELETE FROM blockcommander.wire_channels WHERE stage_id=%s AND rel_x=1 AND channel='EW'", (str(STAGE_ID),))],
            accepted=False,
        )
        self.assertEqual(self.rows(), before)

    def test_cell_kind_update_is_rejected_without_channel_rewrite(self):
        self.commit_case(
            [self.cell("CROSS"), self.channel("NS", 5), self.channel("EW", 10)],
            accepted=True,
        )
        before = self.rows()
        self.commit_case(
            [("UPDATE blockcommander.wire_cells SET cell_kind='SINGLE' WHERE stage_id=%s AND rel_x=1", (str(STAGE_ID),))],
            accepted=False,
        )
        self.assertEqual(self.rows(), before)

    def test_single_cross_transitions_are_atomic_when_fully_rebuilt(self):
        self.commit_case([self.cell(), self.channel()], accepted=True)
        self.commit_case(
            [
                ("UPDATE blockcommander.wire_cells SET cell_kind='CROSS' WHERE stage_id=%s AND rel_x=1", (str(STAGE_ID),)),
                ("DELETE FROM blockcommander.wire_channels WHERE stage_id=%s AND rel_x=1 AND channel='SINGLE'", (str(STAGE_ID),)),
                self.channel("NS", 5),
                self.channel("EW", 10),
            ],
            accepted=True,
        )
        self.assertEqual(self.rows()[0][3:], ("CROSS", ["EW", "NS"]))
        self.commit_case(
            [
                ("UPDATE blockcommander.wire_cells SET cell_kind='SINGLE' WHERE stage_id=%s AND rel_x=1", (str(STAGE_ID),)),
                ("DELETE FROM blockcommander.wire_channels WHERE stage_id=%s AND rel_x=1", (str(STAGE_ID),)),
                self.channel(),
            ],
            accepted=True,
        )
        self.assertEqual(self.rows()[0][3:], ("SINGLE", ["SINGLE"]))

    def test_channel_update_to_invalid_final_shape_rolls_back(self):
        self.commit_case(
            [self.cell("CROSS"), self.channel("NS", 5), self.channel("EW", 10)],
            accepted=True,
        )
        before = self.rows()
        self.commit_case(
            [("UPDATE blockcommander.wire_channels SET channel='SINGLE' WHERE stage_id=%s AND rel_x=1 AND channel='EW'", (str(STAGE_ID),))],
            accepted=False,
        )
        self.assertEqual(self.rows(), before)

    def test_coordinate_moves_check_old_and_new_positions(self):
        self.commit_case([self.cell(), self.channel()], accepted=True)
        self.commit_case(
            [
                ("UPDATE blockcommander.wire_cells SET rel_x=2 WHERE stage_id=%s AND rel_x=1", (str(STAGE_ID),)),
                ("UPDATE blockcommander.wire_channels SET rel_x=2 WHERE stage_id=%s AND rel_x=1", (str(STAGE_ID),)),
            ],
            accepted=True,
        )
        self.assertEqual(self.rows()[0][0], 2)
        before = self.rows()
        self.commit_case(
            [("UPDATE blockcommander.wire_cells SET rel_x=3 WHERE stage_id=%s AND rel_x=2", (str(STAGE_ID),))],
            accepted=False,
        )
        self.assertEqual(self.rows(), before)
        self.commit_case(
            [("UPDATE blockcommander.wire_channels SET rel_x=3 WHERE stage_id=%s AND rel_x=2", (str(STAGE_ID),))],
            accepted=False,
        )
        self.assertEqual(self.rows(), before)


class PausingCursor:
    def __init__(self, inner, locked: threading.Event, release: threading.Event):
        self.inner = inner
        self.locked = locked
        self.release = release

    def __enter__(self):
        self.inner.__enter__()
        return self

    def __exit__(self, *args):
        return self.inner.__exit__(*args)

    def execute(self, sql, params=None):
        result = self.inner.execute(sql, params)
        if "FOR UPDATE" in sql:
            self.locked.set()
            if not self.release.wait(10):
                raise TimeoutError("CAS test did not release the first real transaction")
        return result

    def __getattr__(self, name):
        return getattr(self.inner, name)


class PausingConnection:
    def __init__(self, inner, locked: threading.Event, release: threading.Event):
        self.inner = inner
        self.locked = locked
        self.release = release

    def __enter__(self):
        self.inner.__enter__()
        return self

    def __exit__(self, *args):
        return self.inner.__exit__(*args)

    def cursor(self, *args, **kwargs):
        return PausingCursor(self.inner.cursor(*args, **kwargs), self.locked, self.release)

    def __getattr__(self, name):
        return getattr(self.inner, name)


class FailingCursor:
    def __init__(self, inner, fail_fragment: str):
        self.inner = inner
        self.fail_fragment = fail_fragment
        self.failed = False

    def __enter__(self):
        self.inner.__enter__()
        return self

    def __exit__(self, *args):
        return self.inner.__exit__(*args)

    def execute(self, sql, params=None):
        result = self.inner.execute(sql, params)
        if not self.failed and self.fail_fragment in " ".join(sql.split()):
            self.failed = True
            raise RuntimeError(f"B2-T injected failure after {self.fail_fragment}")
        return result

    def __getattr__(self, name):
        return getattr(self.inner, name)


class FailingConnection:
    def __init__(self, inner, fail_fragment: str):
        self.inner = inner
        self.fail_fragment = fail_fragment

    def __enter__(self):
        self.inner.__enter__()
        return self

    def __exit__(self, *args):
        return self.inner.__exit__(*args)

    def cursor(self, *args, **kwargs):
        return FailingCursor(self.inner.cursor(*args, **kwargs), self.fail_fragment)

    def __getattr__(self, name):
        return getattr(self.inner, name)


class RepositoryPostgreSQLIntegrationTest(unittest.TestCase):
    def setUp(self):
        reset_schema()
        repository().migrate()
        create_stage()

    def test_full_round_trip_and_special_cases(self):
        source = full_snapshot()
        self.assertEqual(repository().save_full_stage_snapshot(str(STAGE_ID), 0, source), 1)
        loaded = repository().load_full_stage_snapshot(str(STAGE_ID))
        self.assertTrue(loaded.compatible_for_graph_mutation)
        self.assertEqual(loaded.saved_version, 1)
        self.assertEqual(tuple(item.instance_id for item in loaded.snapshot.blocks), (START_ID, STOP_ID))
        self.assertEqual({item.edge_id for item in loaded.snapshot.edges}, {EDGE_ID, ROUTELESS_EDGE_ID})
        self.assertEqual({item.route_id for item in loaded.snapshot.routes}, {ROUTE_ID, UNBOUND_ROUTE_ID})
        broken = next(item for item in loaded.snapshot.routes if item.route_id == UNBOUND_ROUTE_ID)
        self.assertIsNone(broken.edge_id)
        self.assertEqual(broken.status, RouteStatus.BROKEN)
        self.assertEqual(broken.steps[0].position, GridPos(9, 0, 0))
        self.assertNotIn(GridPos(9, 0, 0), {item.position for item in loaded.snapshot.wire_cells})
        cross = next(item for item in loaded.snapshot.wire_cells if item.position == GridPos(3, 0, 0))
        self.assertEqual({item.channel for item in cross.channels}, {WireChannel.NS, WireChannel.EW})
        self.assertEqual(loaded.snapshot.blocks[0].label, "Indulás")
        self.assertEqual(loaded.snapshot.blocks[0].description, "Indulás description")
        self.assertEqual(loaded.snapshot.blocks[0].position, GridPos(0, 0, 0))
        self.assertEqual(loaded.snapshot.blocks[0].orientation, CardinalDirection.NORTH)
        self.assertEqual(loaded.snapshot.blocks[0].material_name, "LIME_GLAZED_TERRACOTTA")
        self.assertEqual(loaded.snapshot.blocks[1].material_name, "RED_GLAZED_TERRACOTTA")
        self.assertEqual(loaded.snapshot.routes[0].steps[0].position, GridPos(1, 0, 0))
        self.assertEqual(loaded.snapshot.routes[0].steps[1].position, GridPos(2, 0, 0))
        self.assertEqual(loaded.snapshot.program_validation_status, ProgramValidationStatus.NOT_CHECKED)
        self.assertEqual(source.working_revision, 12)
        self.assertEqual(loaded.snapshot.working_revision, 0)
        with connect() as conn, conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM blockcommander.stage_blocks WHERE stage_id=%s", (str(STAGE_ID),))
            self.assertEqual(cur.fetchone()[0], 2)
            cur.execute("SELECT edge_id,source_port_key,target_port_key FROM blockcommander.graph_edges WHERE stage_id=%s ORDER BY edge_id", (str(STAGE_ID),))
            self.assertEqual(cur.fetchall()[0], (str(EDGE_ID), "out", "in"))
            cur.execute("SELECT step_index,rel_x,channel FROM blockcommander.route_steps WHERE stage_id=%s AND route_id=%s ORDER BY step_index", (str(STAGE_ID), str(ROUTE_ID)))
            self.assertEqual(cur.fetchall(), [(0, 1, "SINGLE"), (1, 2, "SINGLE")])
            cur.execute("SELECT saved_version,graph_snapshot_version,port_catalog_version FROM blockcommander.stages WHERE stage_id=%s", (str(STAGE_ID),))
            self.assertEqual(cur.fetchone(), (1, 1, PORT_CATALOG_VERSION))

    def test_unknown_snapshot_and_port_versions_load_read_only(self):
        repository().save_full_stage_snapshot(str(STAGE_ID), 0, full_snapshot())
        with connect() as conn, conn.cursor() as cur:
            cur.execute("UPDATE blockcommander.stages SET graph_snapshot_version=99 WHERE stage_id=%s", (str(STAGE_ID),))
        unknown_graph = repository().load_full_stage_snapshot(str(STAGE_ID))
        self.assertFalse(unknown_graph.compatible_for_graph_mutation)
        self.assertIn("Unknown graph snapshot version", unknown_graph.compatibility_issues[0])
        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                "UPDATE blockcommander.stages SET graph_snapshot_version=1,port_catalog_version='future' WHERE stage_id=%s",
                (str(STAGE_ID),),
            )
        unknown_port = repository().load_full_stage_snapshot(str(STAGE_ID))
        self.assertFalse(unknown_port.compatible_for_graph_mutation)
        self.assertIn("Unsupported port catalog version", unknown_port.compatibility_issues[0])

    def test_two_real_connections_serialize_cas(self):
        locked = threading.Event()
        release = threading.Event()
        first_result = {}
        second_result = {}

        def first_connect():
            return PausingConnection(connect("blockcommander-b2t-cas-first"), locked, release)

        def save(repo, target, snapshot):
            try:
                target["value"] = repo.save_full_stage_snapshot(str(STAGE_ID), 0, snapshot)
            except Exception as exc:  # captured for assertions in the parent thread
                target["error"] = exc

        first = threading.Thread(target=save, args=(HallRepository(connect=first_connect), first_result, full_snapshot(label="WINNER")))
        second = threading.Thread(
            target=save,
            args=(HallRepository(connect=lambda: connect("blockcommander-b2t-cas-second")), second_result, full_snapshot(label="LOSER")),
        )
        first.start()
        self.assertTrue(locked.wait(5), "first connection did not acquire the row lock")
        second.start()
        deadline = time.monotonic() + 5
        waiting = False
        while time.monotonic() < deadline:
            with connect() as conn, conn.cursor() as cur:
                cur.execute(
                    """SELECT wait_event_type='Lock'
                         FROM pg_stat_activity
                        WHERE application_name='blockcommander-b2t-cas-second'"""
                )
                row = cur.fetchone()
                waiting = bool(row and row[0])
            if waiting:
                break
            time.sleep(0.05)
        self.assertTrue(waiting, "second real connection was not observed waiting on a DB lock")
        release.set()
        first.join(10)
        second.join(10)
        self.assertFalse(first.is_alive() or second.is_alive())
        self.assertEqual(first_result, {"value": 1})
        self.assertIsInstance(second_result.get("error"), StageVersionConflict)
        final = repository().load_full_stage_snapshot(str(STAGE_ID))
        self.assertEqual(final.saved_version, 1)
        self.assertEqual(final.snapshot.blocks[0].label, "WINNER")

    def test_real_transaction_rolls_back_after_every_write_phase(self):
        repository().save_full_stage_snapshot(str(STAGE_ID), 0, full_snapshot())
        baseline = repository().load_full_stage_snapshot(str(STAGE_ID)).snapshot
        phases = (
            "INSERT INTO blockcommander.stage_blocks",
            "INSERT INTO blockcommander.graph_edges",
            "INSERT INTO blockcommander.edge_routes",
            "INSERT INTO blockcommander.route_steps",
            "INSERT INTO blockcommander.wire_cells",
            "INSERT INTO blockcommander.wire_channels",
            "UPDATE blockcommander.stages",
        )
        for phase in phases:
            with self.subTest(phase=phase):
                failing = HallRepository(
                    connect=lambda phase=phase: FailingConnection(
                        connect("blockcommander-b2t-rollback"), phase
                    )
                )
                with self.assertRaisesRegex(RuntimeError, "B2-T injected failure"):
                    failing.save_full_stage_snapshot(
                        str(STAGE_ID), 1, full_snapshot(label="MUTATED")
                    )
                loaded = repository().load_full_stage_snapshot(str(STAGE_ID))
                self.assertEqual(loaded.saved_version, 1)
                self.assertEqual(loaded.snapshot, baseline)


if __name__ == "__main__":
    assert_database_identity()
    unittest.main()
