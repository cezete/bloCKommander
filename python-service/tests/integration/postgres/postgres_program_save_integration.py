"""CK-06/A2 atomic Program aggregate repository tests on isolated PostgreSQL."""
from __future__ import annotations

import hashlib
import threading
import time
import unittest
from uuid import UUID, uuid5

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
    NewStageHeader,
    ProgramSavePlan,
    ProgramSavePlanError,
    ProgramStageLink,
    ProgramStageSavePlan,
    ProgramVersionConflict,
    StageVersionConflict,
)
from postgres_graph_integration import (
    HALL_ID,
    OWNER_ID,
    PROGRAM_ID,
    STAGE_ID,
    assert_database_identity,
    connect,
    create_stage,
    repository,
    reset_schema,
)


CHILD_ID = UUID("00000000-0000-0000-0000-000000007201")
NEW_PARENT_ID = UUID("00000000-0000-0000-0000-000000007202")
NEW_CHILD_ID = UUID("00000000-0000-0000-0000-000000007203")
OTHER_PROGRAM_ID = UUID("00000000-0000-0000-0000-000000007204")
OTHER_ROOT_ID = UUID("00000000-0000-0000-0000-000000007205")


def stable_id(stage_id: UUID, name: str) -> UUID:
    return uuid5(stage_id, name)


def stage_block(stage_id: UUID, name: str, type_key: str, x: int, label: str):
    return StageBlockSnapshot(
        stable_id(stage_id, name),type_key,GridPos(x,0,0),
        CardinalDirection.NORTH,label,f"{label} description",type_key,
        "TEST_MATERIAL",{},
    )


def stage_snapshot(
    stage_id: UUID,label: str,*,with_program_block: bool=False
) -> StageSnapshot:
    start=stage_block(stage_id,"start","START",0,label)
    stop=stage_block(stage_id,"stop","STOP",4,f"{label} stop")
    blocks=[start,stop]
    if with_program_block:
        blocks.append(stage_block(stage_id,"program","PROGRAM",6,"PROGRAM"))
    edge=GraphEdge(
        stable_id(stage_id,"edge"),stage_id,start.instance_id,"out",
        stop.instance_id,"in",EdgeKind.CONTROL,
    )
    route=EdgeRoute(
        stable_id(stage_id,"route"),stage_id,edge.edge_id,
        PortRef(start.instance_id,"out"),PortRef(stop.instance_id,"in"),
        tuple(WireStep(GridPos(x,0,0),WireChannel.SINGLE) for x in (1,2,3)),
        RouteStatus.VALID,
    )
    wires=tuple(WireCell(
        GridPos(x,0,0),(
            WireChannelState(
                WireChannel.SINGLE,
                frozenset({CardinalDirection.EAST,CardinalDirection.WEST}),
            ),
        ),
    ) for x in (1,2,3))
    return StageSnapshot(
        stage_id,tuple(blocks),(edge,),(route,),wires,0,None,None,
        ProgramValidationStatus.NOT_CHECKED,PORT_CATALOG_VERSION,
    )


def new_header(name: str) -> NewStageHeader:
    return NewStageHeader(name,"PROGRAM_BODY",0,0,0,20,20,20)


def program_fingerprint(program_id: UUID) -> str:
    queries=(
        ("SELECT * FROM blockcommander.programs WHERE program_id=%s",(str(program_id),)),
        ("SELECT * FROM blockcommander.stages WHERE program_id=%s ORDER BY stage_id",(str(program_id),)),
        ("SELECT * FROM blockcommander.program_stage_links WHERE program_id=%s ORDER BY parent_stage_id,program_block_instance_id,child_stage_id",(str(program_id),)),
        ("SELECT b.* FROM blockcommander.stage_blocks b JOIN blockcommander.stages s USING(stage_id) WHERE s.program_id=%s ORDER BY b.instance_id",(str(program_id),)),
        ("SELECT e.* FROM blockcommander.graph_edges e JOIN blockcommander.stages s USING(stage_id) WHERE s.program_id=%s ORDER BY e.edge_id",(str(program_id),)),
        ("SELECT r.* FROM blockcommander.edge_routes r JOIN blockcommander.stages s USING(stage_id) WHERE s.program_id=%s ORDER BY r.route_id",(str(program_id),)),
        ("SELECT r.* FROM blockcommander.route_steps r JOIN blockcommander.stages s USING(stage_id) WHERE s.program_id=%s ORDER BY r.route_id,r.step_index",(str(program_id),)),
        ("SELECT w.* FROM blockcommander.wire_cells w JOIN blockcommander.stages s USING(stage_id) WHERE s.program_id=%s ORDER BY w.stage_id,w.rel_x,w.rel_y,w.rel_z",(str(program_id),)),
        ("SELECT w.* FROM blockcommander.wire_channels w JOIN blockcommander.stages s USING(stage_id) WHERE s.program_id=%s ORDER BY w.stage_id,w.rel_x,w.rel_y,w.rel_z,w.channel",(str(program_id),)),
    )
    digest=hashlib.sha256()
    with connect("blockcommander-a2-fingerprint") as conn,conn.cursor() as cur:
        for sql,params in queries:
            cur.execute(sql,params);digest.update(repr(cur.fetchall()).encode("utf-8"))
    return digest.hexdigest()


class FailOnStageCursor:
    def __init__(self,inner,stage_id:UUID):
        self.inner=inner;self.stage_id=str(stage_id);self.failed=False
    def __enter__(self): self.inner.__enter__();return self
    def __exit__(self,*args): return self.inner.__exit__(*args)
    def execute(self,sql,params=None):
        result=self.inner.execute(sql,params)
        normalized=" ".join(sql.split())
        if (not self.failed and normalized.startswith("UPDATE blockcommander.stages")
                and params and str(params[-1])==self.stage_id):
            self.failed=True;raise RuntimeError("A2 injected second-Stage write failure")
        return result
    def __getattr__(self,name): return getattr(self.inner,name)


class FailOnStageConnection:
    def __init__(self,inner,stage_id:UUID): self.inner=inner;self.stage_id=stage_id
    def __enter__(self): self.inner.__enter__();return self
    def __exit__(self,*args): return self.inner.__exit__(*args)
    def cursor(self,*args,**kwargs):
        return FailOnStageCursor(self.inner.cursor(*args,**kwargs),self.stage_id)
    def __getattr__(self,name): return getattr(self.inner,name)


class PauseProgramCursor:
    def __init__(self,inner,program_id:UUID,locked:threading.Event,release:threading.Event):
        self.inner=inner;self.program_id=str(program_id);self.locked=locked;self.release=release
    def __enter__(self): self.inner.__enter__();return self
    def __exit__(self,*args): return self.inner.__exit__(*args)
    def execute(self,sql,params=None):
        result=self.inner.execute(sql,params)
        normalized=" ".join(sql.split())
        if (normalized.startswith("SELECT program_id,root_stage_id,saved_version")
                and params and str(params[0])==self.program_id):
            self.locked.set()
            if not self.release.wait(10): raise TimeoutError("A2 Program lock was not released")
        return result
    def __getattr__(self,name): return getattr(self.inner,name)


class PauseProgramConnection:
    def __init__(self,inner,program_id:UUID,locked:threading.Event,release:threading.Event):
        self.inner=inner;self.program_id=program_id;self.locked=locked;self.release=release
    def __enter__(self): self.inner.__enter__();return self
    def __exit__(self,*args): return self.inner.__exit__(*args)
    def cursor(self,*args,**kwargs):
        return PauseProgramCursor(
            self.inner.cursor(*args,**kwargs),self.program_id,self.locked,self.release
        )
    def __getattr__(self,name): return getattr(self.inner,name)


class ProgramSaveIntegrationTest(unittest.TestCase):
    def setUp(self):
        reset_schema();repository().migrate();create_stage()
        with connect() as conn,conn.cursor() as cur:
            cur.execute("""INSERT INTO blockcommander.program_block_types(
                    type_key,display_name,material_name,default_label,
                    default_description,display_definition)
                VALUES('PROGRAM','PROGRAM','TEST_MATERIAL','PROGRAM',NULL,'{}'::jsonb)""")
        repository().save_full_stage_snapshot(
            str(STAGE_ID),0,stage_snapshot(STAGE_ID,"root-v1",with_program_block=True)
        )

    @property
    def root_program_block(self): return stable_id(STAGE_ID,"program")

    def add_existing_child(self,child_id:UUID=CHILD_ID)->None:
        with connect() as conn,conn.cursor() as cur:
            cur.execute("""INSERT INTO blockcommander.stages(
                    stage_id,program_id,stage_kind,hall_id,stage_name,
                    origin_rel_x,origin_rel_y,origin_rel_z,size_x,size_y,size_z)
                VALUES(%s,%s,'PROGRAM_BODY',NULL,'existing child',0,0,0,20,20,20)""",
                (str(child_id),str(PROGRAM_ID)))
        repository().save_full_stage_snapshot(
            str(child_id),0,stage_snapshot(child_id,"child-v1")
        )
        with connect() as conn,conn.cursor() as cur:
            cur.execute("""INSERT INTO blockcommander.program_stage_links(
                    program_id,parent_stage_id,program_block_instance_id,
                    child_stage_id,link_state)
                VALUES(%s,%s,%s,%s,'ACTIVE')""",
                (str(PROGRAM_ID),str(STAGE_ID),str(self.root_program_block),str(child_id)))

    def existing_link(self,child_id:UUID=CHILD_ID):
        return ProgramStageLink(STAGE_ID,self.root_program_block,child_id,"ACTIVE")

    def two_stage_plan(self,*,root_version=1,child_version=1):
        return ProgramSavePlan(
            PROGRAM_ID,0,STAGE_ID,
            (
                ProgramStageSavePlan(
                    STAGE_ID,root_version,
                    stage_snapshot(STAGE_ID,"root-v2",with_program_block=True),
                ),
                ProgramStageSavePlan(
                    CHILD_ID,child_version,stage_snapshot(CHILD_ID,"child-v2"),
                ),
            ),
            (self.existing_link(),),
        )

    def test_load_program_root_stages_links_and_hall_mount(self):
        self.add_existing_child()
        loaded=repository().load_program(str(PROGRAM_ID))
        mounted=repository().load_program_for_hall(str(HALL_ID))
        self.assertEqual(str(loaded.program["root_stage_id"]),str(STAGE_ID))
        self.assertEqual({str(row["stage_id"]) for row in loaded.stages},{str(STAGE_ID),str(CHILD_ID)})
        self.assertEqual(loaded.links,(self.existing_link(),))
        self.assertEqual(str(loaded.mounts[0]["hall_id"]),str(HALL_ID))
        self.assertEqual(mounted.program,loaded.program)

    def test_two_dirty_stages_save_all_graph_content_atomically(self):
        self.add_existing_child()
        receipt=repository().save_program_snapshot(self.two_stage_plan())
        self.assertTrue(receipt.changed);self.assertEqual(receipt.saved_version,1)
        self.assertEqual(
            {(item.stage_id,item.saved_version,item.changed) for item in receipt.stages},
            {(STAGE_ID,2,True),(CHILD_ID,2,True)},
        )
        root=repository().load_full_stage_snapshot(str(STAGE_ID))
        child=repository().load_full_stage_snapshot(str(CHILD_ID))
        self.assertEqual(
            next(block.label for block in root.snapshot.blocks if block.type_key=="START"),
            "root-v2",
        )
        self.assertEqual(
            next(block.label for block in child.snapshot.blocks if block.type_key=="START"),
            "child-v2",
        )
        for loaded in (root,child):
            self.assertEqual((len(loaded.snapshot.edges),len(loaded.snapshot.routes),len(loaded.snapshot.wire_cells)),(1,1,3))

    def test_new_parent_new_child_and_links_save_without_parent_presave(self):
        parent_program_block=stable_id(NEW_PARENT_ID,"program")
        plan=ProgramSavePlan(
            PROGRAM_ID,0,STAGE_ID,
            (
                ProgramStageSavePlan(STAGE_ID,1),
                ProgramStageSavePlan(
                    NEW_PARENT_ID,None,
                    stage_snapshot(NEW_PARENT_ID,"new-parent",with_program_block=True),
                    new_header("new parent"),
                ),
                ProgramStageSavePlan(
                    NEW_CHILD_ID,None,stage_snapshot(NEW_CHILD_ID,"new-child"),
                    new_header("new child"),
                ),
            ),
            (
                ProgramStageLink(STAGE_ID,self.root_program_block,NEW_PARENT_ID),
                ProgramStageLink(NEW_PARENT_ID,parent_program_block,NEW_CHILD_ID),
            ),
        )
        receipt=repository().save_program_snapshot(plan)
        self.assertEqual(receipt.saved_version,1)
        self.assertEqual(
            {(item.stage_id,item.saved_version,item.changed) for item in receipt.stages},
            {(STAGE_ID,1,False),(NEW_PARENT_ID,1,True),(NEW_CHILD_ID,1,True)},
        )
        loaded=repository().load_program(str(PROGRAM_ID))
        self.assertEqual(len(loaded.stages),3);self.assertEqual(len(loaded.links),2)
        self.assertEqual(repository().load_full_stage_snapshot(str(NEW_CHILD_ID)).saved_version,1)

    def test_second_stage_failure_rolls_back_headers_blocks_edges_routes_and_wires(self):
        self.add_existing_child();before=program_fingerprint(PROGRAM_ID)
        failing=HallRepository(connect=lambda:FailOnStageConnection(
            connect("blockcommander-a2-fail-stage"),CHILD_ID
        ))
        with self.assertRaisesRegex(RuntimeError,"second-Stage"):
            failing.save_program_snapshot(self.two_stage_plan())
        self.assertEqual(program_fingerprint(PROGRAM_ID),before)

    def test_link_constraint_failure_rolls_back_complete_program(self):
        before=program_fingerprint(PROGRAM_ID)
        invalid=ProgramSavePlan(
            PROGRAM_ID,0,STAGE_ID,
            (
                ProgramStageSavePlan(
                    STAGE_ID,1,
                    stage_snapshot(STAGE_ID,"must-rollback",with_program_block=True),
                ),
                ProgramStageSavePlan(
                    NEW_CHILD_ID,None,stage_snapshot(NEW_CHILD_ID,"new-child"),
                    new_header("new child"),
                ),
            ),
            (ProgramStageLink(
                STAGE_ID,stable_id(STAGE_ID,"start"),NEW_CHILD_ID
            ),),
        )
        with self.assertRaisesRegex(ProgramSavePlanError,"matching PROGRAM block"):
            repository().save_program_snapshot(invalid)
        self.assertEqual(program_fingerprint(PROGRAM_ID),before)

    def test_stale_program_version_changes_nothing(self):
        before=program_fingerprint(PROGRAM_ID)
        plan=ProgramSavePlan(
            PROGRAM_ID,99,STAGE_ID,
            (ProgramStageSavePlan(STAGE_ID,1),),(),
        )
        with self.assertRaises(ProgramVersionConflict):
            repository().save_program_snapshot(plan)
        self.assertEqual(program_fingerprint(PROGRAM_ID),before)

    def test_stale_stage_version_changes_nothing(self):
        before=program_fingerprint(PROGRAM_ID)
        plan=ProgramSavePlan(
            PROGRAM_ID,0,STAGE_ID,
            (ProgramStageSavePlan(
                STAGE_ID,99,
                stage_snapshot(STAGE_ID,"stale",with_program_block=True),
            ),),(),
        )
        with self.assertRaises(StageVersionConflict):
            repository().save_program_snapshot(plan)
        self.assertEqual(program_fingerprint(PROGRAM_ID),before)

    def test_concurrent_same_program_save_has_exactly_one_winner(self):
        locked=threading.Event();release=threading.Event();results=[]
        plan_a=ProgramSavePlan(
            PROGRAM_ID,0,STAGE_ID,
            (ProgramStageSavePlan(STAGE_ID,1,stage_snapshot(STAGE_ID,"winner-a",with_program_block=True)),),(),
        )
        plan_b=ProgramSavePlan(
            PROGRAM_ID,0,STAGE_ID,
            (ProgramStageSavePlan(STAGE_ID,1,stage_snapshot(STAGE_ID,"winner-b",with_program_block=True)),),(),
        )
        first_repo=HallRepository(connect=lambda:PauseProgramConnection(
            connect("blockcommander-a2-same-first"),PROGRAM_ID,locked,release
        ))
        second_repo=HallRepository(connect=lambda:connect("blockcommander-a2-same-second"))
        def save(repo,plan):
            try: results.append(repo.save_program_snapshot(plan))
            except Exception as exc: results.append(exc)
        first=threading.Thread(target=save,args=(first_repo,plan_a));first.start()
        self.assertTrue(locked.wait(5));second=threading.Thread(target=save,args=(second_repo,plan_b));second.start()
        time.sleep(.25);release.set();first.join(10);second.join(10)
        self.assertFalse(first.is_alive() or second.is_alive())
        self.assertEqual(sum(not isinstance(item,Exception) for item in results),1)
        self.assertEqual(sum(isinstance(item,ProgramVersionConflict) for item in results),1)
        self.assertEqual(repository().load_program(str(PROGRAM_ID)).program["saved_version"],1)

    def create_other_program(self):
        with connect() as conn,conn.cursor() as cur:
            cur.execute("""INSERT INTO blockcommander.programs(
                    program_id,owner_uuid,program_name,root_stage_id)
                VALUES(%s,%s,'other program',%s)""",
                (str(OTHER_PROGRAM_ID),str(OWNER_ID),str(OTHER_ROOT_ID)))
            cur.execute("""INSERT INTO blockcommander.stages(
                    stage_id,program_id,stage_kind,hall_id,stage_name,
                    origin_rel_x,origin_rel_y,origin_rel_z,size_x,size_y,size_z)
                VALUES(%s,%s,'ROOT',NULL,'other root',0,0,0,20,20,20)""",
                (str(OTHER_ROOT_ID),str(OTHER_PROGRAM_ID)))
        repository().save_full_stage_snapshot(
            str(OTHER_ROOT_ID),0,stage_snapshot(OTHER_ROOT_ID,"other-v1")
        )

    def test_different_program_save_is_not_blocked_by_first_program_lock(self):
        self.create_other_program();locked=threading.Event();release=threading.Event();results={}
        first_plan=ProgramSavePlan(
            PROGRAM_ID,0,STAGE_ID,
            (ProgramStageSavePlan(STAGE_ID,1,stage_snapshot(STAGE_ID,"root-v2",with_program_block=True)),),(),
        )
        other_plan=ProgramSavePlan(
            OTHER_PROGRAM_ID,0,OTHER_ROOT_ID,
            (ProgramStageSavePlan(OTHER_ROOT_ID,1,stage_snapshot(OTHER_ROOT_ID,"other-v2")),),(),
        )
        first_repo=HallRepository(connect=lambda:PauseProgramConnection(
            connect("blockcommander-a2-independent-first"),PROGRAM_ID,locked,release
        ))
        def run(name,repo,plan):
            try: results[name]=repo.save_program_snapshot(plan)
            except Exception as exc: results[name]=exc
        first=threading.Thread(target=run,args=("first",first_repo,first_plan));first.start();self.assertTrue(locked.wait(5))
        other=threading.Thread(target=run,args=("other",repository(),other_plan));other.start();other.join(5)
        self.assertFalse(other.is_alive(),"another Program was blocked by the first Program lock")
        self.assertNotIsInstance(results.get("other"),Exception)
        release.set();first.join(10);self.assertNotIsInstance(results.get("first"),Exception)
        self.assertEqual(repository().load_program(str(PROGRAM_ID)).program["saved_version"],1)
        self.assertEqual(repository().load_program(str(OTHER_PROGRAM_ID)).program["saved_version"],1)

    def test_clean_plan_is_idempotent_noop(self):
        before=program_fingerprint(PROGRAM_ID)
        receipt=repository().save_program_snapshot(ProgramSavePlan(
            PROGRAM_ID,0,STAGE_ID,(ProgramStageSavePlan(STAGE_ID,1),),(),
        ))
        self.assertFalse(receipt.changed);self.assertEqual(receipt.saved_version,0)
        self.assertEqual(receipt.stages[0].saved_version,1)
        self.assertEqual(program_fingerprint(PROGRAM_ID),before)


if __name__=="__main__":
    assert_database_identity();unittest.main()
