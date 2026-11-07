import copy
import threading
import unittest
import uuid
from dataclasses import replace
from types import MappingProxyType

from hangar.graph import (
    CK_CONTROL_PORT_CATALOG,
    GridPos,
    ProgramValidationStatus,
    StageBlockSnapshot,
    StageSnapshot,
)
from hangar.graph.model import CardinalDirection
from hangar.program import (
    ProgramReloadRequired,
    ProgramService,
    ProgramWorkspaceState,
)
from hangar.repository import (
    ForeignWorkingCopy, HallMountNotFound, HallProgramMismatch,
    WorkingCopyProgramMismatch,
    HallConflict,
    HallNotFound,
    LegacyStageSaveBlocked,
    LoadedProgram,
    LoadedStageSnapshot,
    ProgramSaveReceipt,
    ProgramStageSavePlan,
    ProgramVersionConflict,
    StageSaveReceipt,
    StageVersionConflict,
)
from hangar.stage import StageService
from hangar.working_copy import WorkingCopyContext


OWNER = uuid.UUID("00000000-0000-0000-0000-000000009001")
PROGRAM = uuid.UUID("00000000-0000-0000-0000-000000009002")
ROOT = uuid.UUID("00000000-0000-0000-0000-000000009003")
HALL = uuid.UUID("00000000-0000-0000-0000-000000009004")
OTHER_PROGRAM = uuid.UUID("00000000-0000-0000-0000-000000009005")
OTHER_ROOT = uuid.UUID("00000000-0000-0000-0000-000000009006")


def block(stage_id, name, type_key="PROGRAM", x=0):
    return StageBlockSnapshot(
        uuid.uuid5(stage_id,name),type_key,GridPos(x,0,0),
        CardinalDirection.NORTH,name,None,type_key,"TEST",{},
    )


def snapshot(stage_id, *blocks):
    return StageSnapshot(
        stage_id,tuple(blocks),(),(),(),0,None,None,
        ProgramValidationStatus.NOT_CHECKED,CK_CONTROL_PORT_CATALOG.version,
    )


class FakeProgramRepository:
    def __init__(self):
        self.programs={};self.headers={};self.snapshots={};self.links={};self.mounts={}
        self.fail_save=False;self.program_save_calls=0;self.legacy_save_calls=0
        self.types=[
            {"type_key":key,"display_name":key,"material_name":"TEST",
             "default_label":key,"default_description":None,"display_definition":{}}
            for key in ("START","STOP","IF","FOR","FUN","PROGRAM","CLASS","INTERFACE")
        ]
        self.add_program(PROGRAM,ROOT,mounted_hall=HALL)

    def add_program(self,program_id,root_id,*,mounted_hall=None):
        self.programs[program_id]={
            "program_id":str(program_id),"root_stage_id":str(root_id),
            "owner_uuid":str(OWNER),"saved_version":0,"program_name":"test",
        }
        header={
            "stage_id":str(root_id),"program_id":str(program_id),"stage_kind":"ROOT",
            "hall_id":None if mounted_hall is None else str(mounted_hall),
            "stage_name":"root","origin_rel_x":0,"origin_rel_y":0,"origin_rel_z":0,
            "size_x":20,"size_y":20,"size_z":20,"saved_version":0,
            "owner_uuid":str(OWNER),"world_uuid":str(uuid.UUID(int=77)),
            "hall_min_x":0,"hall_min_y":0,"hall_min_z":0,
            "graph_snapshot_version":1,"port_catalog_version":CK_CONTROL_PORT_CATALOG.version,
        }
        self.headers[root_id]=header
        self.snapshots[root_id]=snapshot(root_id,block(root_id,"program"))
        self.links[program_id]=()
        if mounted_hall is not None: self.mounts[mounted_hall]=program_id

    def load_program(self,program_id):
        pid=uuid.UUID(str(program_id))
        if pid not in self.programs:raise HallNotFound("Missing Program")
        program=self.programs[pid]
        stages=tuple(MappingProxyType(copy.deepcopy(row)) for sid,row in sorted(
            self.headers.items(),key=lambda item:str(item[0])
        ) if uuid.UUID(str(row["program_id"]))==pid)
        mounts=tuple(MappingProxyType({"hall_id":str(hall),"program_id":str(pid)})
            for hall,mounted in self.mounts.items() if mounted==pid)
        mounts=tuple(MappingProxyType({**row,"working_copy_id":str(
            uuid.uuid5(uuid.UUID(row["hall_id"]),"working-copy"))}) for row in mounts)
        return LoadedProgram(
            MappingProxyType(copy.deepcopy(program)),stages,self.links[pid],mounts
        )

    def load_program_for_hall(self,hall_id):
        return self.load_program(self.mounts[uuid.UUID(str(hall_id))])

    def resolve_working_copy_context(self,hall_id,*,program_id=None,working_copy_id=None):
        hall=uuid.UUID(str(hall_id))
        if hall not in self.mounts:raise HallMountNotFound("Missing mount")
        pid=self.mounts[hall]
        context=WorkingCopyContext(hall,pid,uuid.uuid5(hall,"working-copy"))
        if working_copy_id is not None and uuid.UUID(str(working_copy_id))!=context.working_copy_id:
            expected_copy=uuid.UUID(str(working_copy_id))
            owner=next((mounted for candidate,mounted in self.mounts.items()
                if uuid.uuid5(candidate,"working-copy")==expected_copy),None)
            expected_program=pid if program_id is None else uuid.UUID(str(program_id))
            if owner is not None and owner!=expected_program:
                raise WorkingCopyProgramMismatch("WorkingCopy Program mismatch")
            raise ForeignWorkingCopy("WorkingCopy identity mismatch")
        if program_id is not None and uuid.UUID(str(program_id))!=pid:
            raise HallProgramMismatch("Program identity mismatch")
        return context

    def load_program_for_stage(self,stage_id):
        sid=uuid.UUID(str(stage_id))
        if sid not in self.headers:raise HallNotFound("Missing Stage")
        return self.load_program(self.headers[sid]["program_id"])

    def load_full_stage_snapshot(self,stage_id):
        sid=uuid.UUID(str(stage_id));header=self.headers[sid]
        return LoadedStageSnapshot(
            MappingProxyType(copy.deepcopy(header)),self.snapshots[sid],
            int(header["saved_version"]),1,CK_CONTROL_PORT_CATALOG.version,True,(),
        )

    def list_block_types(self): return copy.deepcopy(self.types)

    def save_program_snapshot(self,plan):
        self.program_save_calls+=1
        if self.fail_save: raise RuntimeError("database rollback")
        program=self.programs[plan.program_id]
        if int(program["saved_version"])!=plan.expected_saved_version:
            raise ProgramVersionConflict("stale program")
        existing={sid for sid,row in self.headers.items()
            if uuid.UUID(str(row["program_id"]))==plan.program_id}
        planned_existing={item.stage_id for item in plan.stages
            if item.expected_saved_version is not None}
        if existing!=planned_existing: raise HallConflict("stage set mismatch")
        for item in plan.stages:
            if item.expected_saved_version is not None:
                if int(self.headers[item.stage_id]["saved_version"])!=item.expected_saved_version:
                    raise StageVersionConflict("stale stage")

        programs=copy.deepcopy(self.programs);headers=copy.deepcopy(self.headers)
        snapshots=dict(self.snapshots);links=dict(self.links)
        versions={}
        changed=False;changed_ids=set()
        for item in plan.stages:
            if item.expected_saved_version is None:
                raw=item.new_stage
                headers[item.stage_id]={
                    "stage_id":str(item.stage_id),"program_id":str(plan.program_id),
                    "stage_kind":raw.stage_kind,"hall_id":None,"stage_name":raw.stage_name,
                    "origin_rel_x":raw.origin_rel_x,"origin_rel_y":raw.origin_rel_y,
                    "origin_rel_z":raw.origin_rel_z,"size_x":raw.size_x,"size_y":raw.size_y,
                    "size_z":raw.size_z,"saved_version":0,"owner_uuid":str(OWNER),
                    "world_uuid":None,"hall_min_x":0,"hall_min_y":0,"hall_min_z":0,
                    "graph_snapshot_version":1,"port_catalog_version":CK_CONTROL_PORT_CATALOG.version,
                }
                changed=True
            current=int(headers[item.stage_id]["saved_version"])
            if item.snapshot is not None:
                snapshots[item.stage_id]=item.snapshot;current+=1
                headers[item.stage_id]["saved_version"]=current
                changed=True;changed_ids.add(item.stage_id)
            versions[item.stage_id]=current
        if links[plan.program_id]!=plan.links:
            links[plan.program_id]=plan.links;changed=True
        if changed: programs[plan.program_id]["saved_version"]+=1
        self.programs=programs;self.headers=headers;self.snapshots=snapshots;self.links=links
        return ProgramSaveReceipt(
            plan.program_id,int(programs[plan.program_id]["saved_version"]),changed,
            tuple(StageSaveReceipt(sid,versions[sid],sid in changed_ids)
                for sid in sorted(versions,key=str)),
        )

    def save_full_stage_snapshot(self,stage_id,expected_version,target):
        self.legacy_save_calls+=1;sid=uuid.UUID(str(stage_id))
        program_id=uuid.UUID(str(self.headers[sid]["program_id"]))
        if int(self.programs[program_id]["saved_version"])>0:
            raise LegacyStageSaveBlocked("aggregate Program SAVE is authoritative")
        if self.headers[sid]["saved_version"]!=expected_version:
            raise StageVersionConflict("stale")
        self.snapshots[sid]=target;self.headers[sid]["saved_version"]+=1
        return self.headers[sid]["saved_version"]

    def save_stage_snapshot(self,*args):
        self.legacy_save_calls+=1
        raise AssertionError("legacy block-only save is outside this fixture")


class FailingReceiptProgramService(ProgramService):
    def _apply_receipt(self,*_args,**_kwargs):
        raise RuntimeError("injected memory apply failure")


class RecordingLock:
    def __init__(self,name,events):
        self.name=name;self.events=events;self.inner=threading.RLock()
    def __enter__(self): self.acquire();return self
    def __exit__(self,*_args): self.release()
    def acquire(self,*args,**kwargs):
        result=self.inner.acquire(*args,**kwargs)
        if result:self.events.append(self.name)
        return result
    def release(self): self.inner.release()


class ProgramServiceTest(unittest.TestCase):
    def setUp(self):
        self.repo=FakeProgramRepository();self.stages=StageService(self.repo)
        self.ids=iter(uuid.UUID(int=10000+index) for index in range(200))
        self.service=ProgramService(self.repo,self.stages,id_factory=lambda:next(self.ids))
        self.workspace=self.service.load(PROGRAM);self.root=self.service.root(PROGRAM)
        self.program_block=uuid.uuid5(ROOT,"program")

    def add_program_block(self,workspace,name="program"):
        item=block(workspace.snapshot.stage_id,name,"PROGRAM",len(workspace.snapshot.blocks))
        workspace.snapshot=workspace.snapshot.with_content_change(
            blocks=(*workspace.snapshot.blocks,item)
        );workspace.dirty=True
        return item.instance_id

    def add_start(self,workspace,name):
        item=block(workspace.snapshot.stage_id,name,"START",len(workspace.snapshot.blocks))
        workspace.snapshot=workspace.snapshot.with_content_change(
            blocks=(*workspace.snapshot.blocks,item)
        );workspace.dirty=True

    def test_multiple_stages_keep_independent_dirty_state(self):
        child=self.service.ensure_child(PROGRAM,ROOT,self.program_block,str(OWNER))
        self.add_start(child,"child start")
        self.assertFalse(self.root.dirty);self.assertTrue(child.dirty)
        self.stages.update(str(ROOT),str(self.program_block),{
            "player_uuid":str(OWNER),"label":"changed",
        })
        self.assertTrue(self.root.dirty);self.assertTrue(child.dirty)
        self.assertTrue(self.workspace.dirty)

    def test_program_blocks_are_allowed_in_root_and_program_body_and_retain_subtree(self):
        root_placement=self.stages.place(str(ROOT),{
            "player_uuid":str(OWNER),"type_key":"PROGRAM",
            "x":2,"y":0,"z":0,"orientation":"WEST",
        })
        root_block=uuid.UUID(next(item["instance_id"] for item in root_placement["blocks"]
            if item["type_key"]=="PROGRAM" and item["rel_x"]==2))
        child=self.service.ensure_child(PROGRAM,ROOT,root_block,str(OWNER))
        body_placement=self.stages.place(str(child.snapshot.stage_id),{
            "player_uuid":str(OWNER),"type_key":"PROGRAM",
            "x":3,"y":0,"z":0,"orientation":"SOUTH",
        })
        body_block=uuid.UUID(next(item["instance_id"] for item in body_placement["blocks"]
            if item["type_key"]=="PROGRAM" and item["rel_x"]==3))
        grandchild=self.service.ensure_child(
            PROGRAM,child.snapshot.stage_id,body_block,str(OWNER)
        )
        grandchild_id=grandchild.snapshot.stage_id

        receipt=self.service.save(PROGRAM,str(OWNER))
        self.assertIn(root_block,{item.instance_id for item in self.repo.snapshots[ROOT].blocks})
        self.assertIn(body_block,{item.instance_id for item in self.repo.snapshots[child.snapshot.stage_id].blocks})
        self.assertIn(grandchild_id,{item.stage_id for item in receipt.stages})

        self.stages.delete(
            str(child.snapshot.stage_id),str(body_block),{"player_uuid":str(OWNER)}
        )
        retained=next(item for item in self.workspace.links
            if item.program_block_instance_id==body_block)
        self.assertEqual(retained.link_state,"RETAINED")
        self.assertIn(grandchild_id,self.workspace.stage_headers)
        self.assertIn(grandchild_id,self.workspace.stage_workspaces)

    def test_first_legacy_mutation_resolves_program_before_stage_lock(self):
        repo=FakeProgramRepository();stages=StageService(repo)
        service=ProgramService(repo,stages)
        root=stages._workspace(str(ROOT))
        program_block=uuid.uuid5(ROOT,"program")
        stages.update(str(ROOT),str(program_block),{
            "player_uuid":str(OWNER),"label":"auto-resolved",
        })
        self.assertIs(service.for_stage(ROOT).stage_workspaces[ROOT],root)
        saved=stages.save(str(ROOT),{"player_uuid":str(OWNER)})
        self.assertFalse(saved["dirty"])
        self.assertEqual(repo.program_save_calls,1);self.assertEqual(repo.legacy_save_calls,0)

    def test_new_parent_child_and_link_save_together(self):
        parent=self.service.ensure_child(PROGRAM,ROOT,self.program_block,str(OWNER))
        nested_block=self.add_program_block(parent,"nested")
        child=self.service.ensure_child(
            PROGRAM,parent.snapshot.stage_id,nested_block,str(OWNER)
        )
        receipt=self.service.save(PROGRAM,str(OWNER))
        self.assertEqual(receipt.saved_version,1)
        self.assertFalse(self.workspace.dirty)
        self.assertEqual(parent.saved_version,1);self.assertEqual(child.saved_version,1)
        self.assertEqual(len(self.repo.links[PROGRAM]),2)

    def test_deep_hierarchy_has_no_artificial_depth_limit(self):
        parent=self.root;block_id=self.program_block
        for depth in range(40):
            parent=self.service.ensure_child(
                PROGRAM,parent.snapshot.stage_id,block_id,str(OWNER),
                stage_name=f"depth {depth}",
            )
            block_id=self.add_program_block(parent,f"nested {depth}")
        self.assertEqual(len(self.workspace.new_stage_ids),40)
        self.service.save(PROGRAM,str(OWNER))
        self.assertFalse(self.workspace.dirty)

    def test_child_ensure_is_idempotent(self):
        first=self.service.ensure_child(PROGRAM,ROOT,self.program_block,str(OWNER))
        second=self.service.ensure_child(PROGRAM,ROOT,self.program_block,str(OWNER))
        self.assertIs(first,second);self.assertEqual(len(self.workspace.links),1)
        self.assertEqual(self.repo.program_save_calls,0)

    def test_cycle_and_cross_program_link_are_rejected(self):
        first=self.service.ensure_child(PROGRAM,ROOT,self.program_block,str(OWNER))
        first_block=self.add_program_block(first,"first nested")
        second=self.service.ensure_child(PROGRAM,first.snapshot.stage_id,first_block,str(OWNER))
        second_block=self.add_program_block(second,"second nested")
        self.service.retain_child(PROGRAM,ROOT,self.program_block,str(OWNER))
        with self.assertRaisesRegex(HallConflict,"ciklust"):
            self.service.link_child(
                PROGRAM,second.snapshot.stage_id,second_block,
                first.snapshot.stage_id,str(OWNER),
            )
        self.repo.add_program(OTHER_PROGRAM,OTHER_ROOT)
        self.service.load(OTHER_PROGRAM)
        with self.assertRaisesRegex(HallConflict,"Cross-program"):
            self.service.link_child(
                PROGRAM,ROOT,self.program_block,OTHER_ROOT,str(OWNER)
            )

    def test_two_dirty_stages_save_in_one_repository_call(self):
        child=self.service.ensure_child(PROGRAM,ROOT,self.program_block,str(OWNER))
        self.service.save(PROGRAM,str(OWNER))
        self.add_start(self.root,"root dirty");self.add_start(child,"child dirty")
        receipt=self.service.save(PROGRAM,str(OWNER))
        self.assertEqual(self.repo.program_save_calls,2)
        changed={item.stage_id for item in receipt.stages if item.changed}
        self.assertEqual(changed,{ROOT,child.snapshot.stage_id})
        self.assertFalse(self.root.dirty);self.assertFalse(child.dirty)

    def test_repository_failure_keeps_every_workspace_unchanged(self):
        child=self.service.ensure_child(PROGRAM,ROOT,self.program_block,str(OWNER))
        self.add_start(child,"unsaved")
        before=(
            self.workspace.saved_version,self.workspace.links,
            frozenset(self.workspace.new_stage_ids),self.workspace.hierarchy_dirty,
            self.root.snapshot,self.root.saved_version,self.root.dirty,
            child.snapshot,child.saved_version,child.dirty,
        )
        self.repo.fail_save=True
        with self.assertRaisesRegex(RuntimeError,"rollback"):
            self.service.save(PROGRAM,str(OWNER))
        after=(
            self.workspace.saved_version,self.workspace.links,
            frozenset(self.workspace.new_stage_ids),self.workspace.hierarchy_dirty,
            self.root.snapshot,self.root.saved_version,self.root.dirty,
            child.snapshot,child.saved_version,child.dirty,
        )
        self.assertEqual(after,before)

    def test_noop_save_does_not_increment_versions(self):
        receipt=self.service.save(PROGRAM,str(OWNER))
        self.assertFalse(receipt.changed);self.assertEqual(receipt.saved_version,0)
        self.assertEqual(self.root.saved_version,0);self.assertFalse(self.workspace.dirty)

    def test_hierarchy_only_save_changes_only_program_version(self):
        child=self.service.ensure_child(PROGRAM,ROOT,self.program_block,str(OWNER))
        self.service.save(PROGRAM,str(OWNER));stage_versions=(self.root.saved_version,child.saved_version)
        self.assertTrue(self.service.retain_child(
            PROGRAM,ROOT,self.program_block,str(OWNER)
        ))
        self.assertTrue(self.workspace.hierarchy_dirty)
        receipt=self.service.save(PROGRAM,str(OWNER))
        self.assertTrue(receipt.changed);self.assertEqual(receipt.saved_version,2)
        self.assertEqual((self.root.saved_version,child.saved_version),stage_versions)

    def test_commit_then_memory_failure_marks_reload_required(self):
        repo=FakeProgramRepository();stages=StageService(repo)
        service=FailingReceiptProgramService(repo,stages)
        workspace=service.load(PROGRAM);root=service.root(PROGRAM);root.dirty=True
        calls=repo.program_save_calls
        with self.assertRaises(ProgramReloadRequired): service.save(PROGRAM,str(OWNER))
        self.assertEqual(workspace.state,ProgramWorkspaceState.RELOAD_REQUIRED)
        self.assertEqual(repo.programs[PROGRAM]["saved_version"],1)
        with self.assertRaises(ProgramReloadRequired): service.save(PROGRAM,str(OWNER))
        self.assertEqual(repo.program_save_calls,calls+1)
        with self.assertRaises(ProgramReloadRequired):
            stages.update(str(ROOT),str(uuid.uuid5(ROOT,"program")),{
                "player_uuid":str(OWNER),"label":"must not mutate",
            })

    def test_program_block_delete_retains_child_and_subtree(self):
        child=self.service.ensure_child(PROGRAM,ROOT,self.program_block,str(OWNER))
        child_snapshot=child.snapshot
        self.stages.delete(str(ROOT),str(self.program_block),{"player_uuid":str(OWNER)})
        self.assertEqual(self.workspace.links[0].link_state,"RETAINED")
        self.assertIs(self.workspace.stage_workspaces[child.snapshot.stage_id],child)
        self.assertIs(child.snapshot,child_snapshot)
        self.assertTrue(self.workspace.hierarchy_dirty);self.assertTrue(self.root.dirty)
        self.service.save(PROGRAM,str(OWNER))
        self.assertIn(child.snapshot.stage_id,self.repo.headers)

    def test_legacy_save_delegates_and_lock_order_is_program_then_stage(self):
        events=[];self.workspace.lock=RecordingLock("program",events)
        self.root.lock=RecordingLock("stage",events)
        self.stages.update(str(ROOT),str(self.program_block),{
            "player_uuid":str(OWNER),"label":"legacy edit",
        })
        self.assertLess(events.index("program"),events.index("stage"))
        saved=self.stages.save(str(ROOT),{"player_uuid":str(OWNER)})
        self.assertFalse(saved["dirty"]);self.assertEqual(self.repo.legacy_save_calls,0)
        self.assertEqual(self.repo.program_save_calls,1)
        self.assertEqual(self.workspace.saved_version,1)

        with self.assertRaises(LegacyStageSaveBlocked):
            self.repo.save_full_stage_snapshot(
                str(ROOT),self.root.saved_version,self.root.snapshot
            )
        self.repo.headers[ROOT]["saved_version"]+=1
        self.assertTrue(self.service.is_dirty(PROGRAM))
        self.assertEqual(self.workspace.state,ProgramWorkspaceState.RELOAD_REQUIRED)

    def test_stage_kind_policy_allows_program_but_rejects_class_and_interface(self):
        child=self.service.ensure_child(PROGRAM,ROOT,self.program_block,str(OWNER))
        ProgramService.assert_block_allowed("PROGRAM_BODY","PROGRAM")
        for type_key in ("CLASS","INTERFACE"):
            with self.assertRaises(HallConflict):
                ProgramService.assert_block_allowed("PROGRAM_BODY",type_key)
            with self.assertRaises(HallConflict):
                self.stages.place(str(child.snapshot.stage_id),{
                    "player_uuid":str(OWNER),"type_key":type_key,
                    "x":0,"y":0,"z":0,"orientation":"NORTH",
                })


if __name__=="__main__": unittest.main()
