"""Real PostgreSQL acceptance; only via identity-guarded run-tests.ps1 -Suites declarations."""
import logging
import threading
import unittest
from dataclasses import replace
from uuid import uuid4
import psycopg2
from hangar.declarations import DeclarationError, Mutability, VariableType
from hangar.declaration_service import DeclarationService, DeclarationTarget
from hangar.program import ProgramService
from hangar.stage import StageService
from hangar.repository import SnapshotCompatibilityError
from hangar.repository import HallRepository
from hangar.block_editor import BlockEditorService, EditorTarget, EditorApplyCommand
from hangar.graph_application import GraphApplicationService, GraphCommand, WireChange
from hangar.graph import GridPos, CardinalDirection
from postgres_graph_integration import (OWNER_ID, PROGRAM_ID, STAGE_ID,HALL_ID,
    assert_database_identity,connect,create_stage,repository,reset_schema)
from postgres_program_save_integration import stage_snapshot, stable_id, program_fingerprint


class DeclarationsPostgreSQLIntegrationTest(unittest.TestCase):
    def setUp(self):
        assert_database_identity(); reset_schema()
        self.repo=repository(); self.repo.migrate(); create_stage()
        initial=stage_snapshot(STAGE_ID,'Root',with_program_block=True)
        # This fixture's route runs along +X; real discovery requires matching ports.
        initial=replace(initial,blocks=tuple(replace(b,orientation=CardinalDirection.EAST) for b in initial.blocks))
        self.repo.save_full_stage_snapshot(str(STAGE_ID),0,initial)
        self.programs=ProgramService(self.repo,StageService(self.repo))
        self.service=DeclarationService(self.programs); self.root=DeclarationTarget(PROGRAM_ID,STAGE_ID)

    def stage(self,target=None):
        target=target or self.root
        return self.programs.stage(PROGRAM_ID,target.stage_id)

    def create(self,name='x',target=None,kind=VariableType.INTEGER,mutability=Mutability.MUTABLE):
        target=target or self.root; stage=self.stage(target)
        return self.service.create(target,OWNER_ID,stage.workspace_epoch,stage.revision,
            name=name,variable_type=kind,mutability=mutability).declaration

    def update(self,d,changes,target=None):
        target=target or self.root; stage=self.stage(target)
        return self.service.update(target,OWNER_ID,stage.workspace_epoch,stage.revision,d.declaration_id,changes)

    def new_child(self,parent=None,x=8):
        parent=parent or self.root
        placed=self.programs.stages.place(str(parent.stage_id),dict(player_uuid=str(OWNER_ID),type_key='PROGRAM',x=x,y=0,z=0))
        bid=next(b['instance_id'] for b in placed['blocks'] if b['rel_x']==x and b['rel_y']==0 and b['rel_z']==0)
        child=self.programs.ensure_child(PROGRAM_ID,parent.stage_id,bid,str(OWNER_ID))
        return DeclarationTarget(PROGRAM_ID,child.snapshot.stage_id)

    def rows(self):
        with connect() as conn,conn.cursor() as cur:
            cur.execute('SELECT declaration_id::text,stage_id::text,name,name_key,type_key,mutability FROM blockcommander.stage_declarations ORDER BY declaration_id')
            return cur.fetchall()

    def restart(self):
        return DeclarationService(ProgramService(repository(),StageService(repository())))

    def test_four_stage_acceptance_save_rebuild_restart_graph_and_shadowing(self):
        a=self.new_child(); b=self.new_child(x=10); grand=self.new_child(a)
        counter=self.create('counter'); self.create('name',a,VariableType.STRING,Mutability.IMMUTABLE)
        shadow=self.create('counter',b,VariableType.BOOLEAN,Mutability.IMMUTABLE)
        first=self.create('shared',a); second=self.create('shared',b)
        self.create('local',grand)
        self.assertNotEqual(first.declaration_id,second.declaration_id)
        view=self.service.resolve(b,OWNER_ID,'counter')
        self.assertEqual(shadow,view.resolved.declaration); self.assertEqual(counter,view.shadowed[0].declaration)
        inherited=self.service.resolve(grand,OWNER_ID,'counter')
        self.assertEqual('INHERITED',inherited.resolved.source)
        self.programs.save(PROGRAM_ID,str(OWNER_ID))
        self.assertFalse(self.programs.load(PROGRAM_ID).dirty)
        self.update(counter,{'name':'count'})
        self.assertFalse(self.service.basis_is_current(inherited.basis,OWNER_ID))
        self.assertFalse(self.stage(grand).dirty)
        self.assertEqual('NOT_FOUND',self.service.resolve(grand,OWNER_ID,'counter').status)
        self.assertEqual(shadow,self.service.resolve(b,OWNER_ID,'counter').resolved.declaration)
        self.assertTrue(self.stage().dirty)
        self.assertEqual(0,len([row for row in self.rows() if row[2]=='count']))
        self.programs.save(PROGRAM_ID,str(OWNER_ID)); saved=self.rows()
        fresh=self.restart()
        self.assertEqual(counter.declaration_id,fresh.resolve(grand,OWNER_ID,'count').resolved.declaration.declaration_id)
        for target in (self.root,a,b,grand):
            self.assertEqual(self.service.list_own(target,OWNER_ID).declarations,fresh.list_own(target,OWNER_ID).declarations)
        self.create('unsaved',grand); epoch=self.stage(grand).workspace_epoch
        self.programs.stages.rebuild(str(grand.stage_id),dict(player_uuid=str(OWNER_ID),confirm_discard=True))
        self.assertNotEqual(epoch,self.stage(grand).workspace_epoch)
        self.assertEqual('NOT_FOUND',self.service.resolve(grand,OWNER_ID,'unsaved').status)
        app=GraphApplicationService(self.programs.stages)
        def command(op,**kwargs):
            stage=self.stage()
            return GraphCommand(1,uuid4(),STAGE_ID,OWNER_ID,stage.workspace_epoch,stage.revision,op,**kwargs)
        before=self.stage().snapshot.declarations
        bound=app.bind_edges(command('BIND_EDGES'))
        self.assertEqual(200,bound.status_code,bound.body)
        self.assertEqual(before,self.stage().snapshot.declarations)
        self.assertEqual(200,app.apply_wire(command('WIRE_LAYOUT',wire_changes=(WireChange(GridPos(2,0,0),None),))).status_code)
        self.assertEqual(before,self.stage().snapshot.declarations)
        self.programs.save(PROGRAM_ID,str(OWNER_ID)); self.assertEqual(saved,self.rows())

    def test_metadata_editor_and_block_crud_do_not_drop_declarations(self):
        d=self.create(); editor=BlockEditorService(self.programs)
        target=EditorTarget(PROGRAM_ID,STAGE_ID,stable_id(STAGE_ID,'start')); current=editor.get(target,OWNER_ID)
        editor.apply(EditorApplyCommand(target,OWNER_ID,uuid4(),current.schema_ref,
            current.version_context.stage_workspace_epoch,current.version_context.working_revision,{'label':'Edited'}))
        placed=self.programs.stages.place(str(STAGE_ID),dict(player_uuid=str(OWNER_ID),type_key='STOP',x=12,y=0,z=0))
        bid=next(b['instance_id'] for b in placed['blocks'] if b['rel_x']==12)
        self.programs.stages.update(str(STAGE_ID),bid,dict(player_uuid=str(OWNER_ID),label='New'))
        self.programs.stages.delete(str(STAGE_ID),bid,dict(player_uuid=str(OWNER_ID)))
        self.assertEqual((d,),self.stage().snapshot.declarations)
        self.programs.save(PROGRAM_ID,str(OWNER_ID))
        self.assertEqual((d,),self.restart().list_own(self.root,OWNER_ID).declarations)

    def test_sql_failure_mid_save_rolls_back_all_stages_and_memory_stays_dirty(self):
        child=self.new_child(); self.programs.save(PROGRAM_ID,str(OWNER_ID))
        before=program_fingerprint(PROGRAM_ID); rows=self.rows()
        self.create('root_new'); self.create('explode',child)
        with connect() as conn,conn.cursor() as cur:
            cur.execute("""CREATE FUNCTION blockcommander.test_reject_declaration() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.name='explode' THEN RAISE EXCEPTION 'injected test constraint' USING ERRCODE='23514'; END IF; RETURN NEW; END; $$;
                CREATE TRIGGER test_reject_declaration BEFORE INSERT ON blockcommander.stage_declarations
                FOR EACH ROW EXECUTE FUNCTION blockcommander.test_reject_declaration()""")
        with self.assertRaises(psycopg2.errors.CheckViolation):self.programs.save(PROGRAM_ID,str(OWNER_ID))
        self.assertEqual(before,program_fingerprint(PROGRAM_ID)); self.assertEqual(rows,self.rows())
        self.assertTrue(self.stage().dirty and self.stage(child).dirty)

    def test_old_writer_delete_insert_and_header_only_update_fail_atomically(self):
        self.create(); self.programs.save(PROGRAM_ID,str(OWNER_ID)); before=program_fingerprint(PROGRAM_ID); rows=self.rows()
        for sql in ('DELETE FROM blockcommander.stage_blocks WHERE stage_id=%s',
                    'UPDATE blockcommander.stages SET saved_version=saved_version+1 WHERE stage_id=%s',
                    'DELETE FROM blockcommander.stage_declarations WHERE stage_id=%s'):
            with self.subTest(sql=sql), self.assertRaises(psycopg2.errors.ObjectNotInPrerequisiteState):
                with connect() as conn,conn.cursor() as cur:cur.execute(sql,(str(STAGE_ID),))
            self.assertEqual(before,program_fingerprint(PROGRAM_ID));self.assertEqual(rows,self.rows())
        self.create('second');self.programs.save(PROGRAM_ID,str(OWNER_ID));self.assertEqual(2,len(self.rows()))

    def test_empty_known_format_still_protected_and_cannot_downgrade(self):
        d=self.create(); stage=self.stage()
        self.service.delete(self.root,OWNER_ID,stage.workspace_epoch,stage.revision,d.declaration_id)
        self.programs.save(PROGRAM_ID,str(OWNER_ID)); self.assertEqual([],self.rows())
        with self.assertRaises(psycopg2.errors.ObjectNotInPrerequisiteState):
            with connect() as conn,conn.cursor() as cur:
                cur.execute("SELECT set_config('blockcommander.declaration_writer','1',true)")
                cur.execute('UPDATE blockcommander.stages SET declaration_schema_version=0 WHERE stage_id=%s',(str(STAGE_ID),))

    def test_unknown_persisted_format_not_read_as_empty_or_overwritten(self):
        self.create();self.programs.save(PROGRAM_ID,str(OWNER_ID))
        with connect() as conn,conn.cursor() as cur:
            cur.execute('ALTER TABLE blockcommander.stages DISABLE TRIGGER stage_declaration_writer_guard')
            cur.execute('UPDATE blockcommander.stages SET declaration_schema_version=77 WHERE stage_id=%s',(str(STAGE_ID),))
            cur.execute('ALTER TABLE blockcommander.stages ENABLE TRIGGER stage_declaration_writer_guard')
        with self.assertRaises(SnapshotCompatibilityError): self.repo.load_full_stage_snapshot(str(STAGE_ID))
        before=self.rows()
        with self.assertRaises(SnapshotCompatibilityError): self.programs.save(PROGRAM_ID,str(OWNER_ID))
        self.assertEqual(before,self.rows())

    def test_database_name_key_is_exact_case_sensitive_and_unique(self):
        self.create('x');self.create('X'); self.programs.save(PROGRAM_ID,str(OWNER_ID))
        self.assertEqual({'x','X'},{row[3] for row in self.rows()})
        with self.assertRaises(psycopg2.errors.UniqueViolation):
            with connect() as conn,conn.cursor() as cur:
                cur.execute("SELECT set_config('blockcommander.declaration_writer','1',true)")
                cur.execute("INSERT INTO blockcommander.stage_declarations(declaration_id,stage_id,name,type_key,mutability) VALUES(%s,%s,'x','STRING','IMMUTABLE')",(str(uuid4()),str(STAGE_ID)))

    def test_legacy_snapshot_unsaved_loss_and_old_epoch(self):
        self.assertEqual((),self.repo.load_full_stage_snapshot(str(STAGE_ID)).snapshot.declarations)
        self.assertEqual(0,self.repo.load_full_stage_snapshot(str(STAGE_ID)).snapshot.declaration_schema_version)
        self.create('unsaved'); fresh=self.restart()
        self.assertEqual((),fresh.list_own(self.root,OWNER_ID).declarations)
        with self.assertRaises(DeclarationError) as e:
            fresh.create(self.root,OWNER_ID,self.stage().workspace_epoch,self.stage().revision,
                name='stale',variable_type=VariableType.STRING,mutability=Mutability.MUTABLE)
        self.assertEqual('STALE_WORKSPACE_EPOCH',e.exception.code)

    def test_retained_subtree_and_hall_selection_preserve_scope_identity(self):
        child=self.new_child(); d=self.create('child',child)
        self.programs.save(PROGRAM_ID,str(OWNER_ID)); own=self.service.list_own(child,OWNER_ID)
        selection=self.repo.get_selection(str(HALL_ID),str(OWNER_ID))
        self.repo.select(str(HALL_ID),str(OWNER_ID),str(PROGRAM_ID),str(child.stage_id),selection['selection_revision'])
        self.assertEqual(own,self.service.list_own(child,OWNER_ID))
        link=next(l for l in self.programs.load(PROGRAM_ID).links if l.child_stage_id==child.stage_id)
        self.programs.retain_child(PROGRAM_ID,STAGE_ID,link.program_block_instance_id,str(OWNER_ID))
        self.programs.save(PROGRAM_ID,str(OWNER_ID)); fresh=self.restart()
        self.assertEqual((d,),fresh.list_own(child,OWNER_ID).declarations)
        with self.assertRaises(DeclarationError) as error:fresh.resolve(child,OWNER_ID)
        self.assertEqual('SCOPE_UNAVAILABLE',error.exception.code)

    def test_hall_deletion_detaches_projection_and_preserves_program_declarations(self):
        d=self.create();self.programs.save(PROGRAM_ID,str(OWNER_ID))
        self.repo.delete(str(HALL_ID))
        self.assertEqual((d,),self.restart().list_own(self.root,OWNER_ID).declarations)
        with connect() as conn,conn.cursor() as cur:
            cur.execute('SELECT hall_id FROM blockcommander.stages WHERE stage_id=%s',(str(STAGE_ID),))
            self.assertIsNone(cur.fetchone()[0])

    def test_known_snapshot_with_unspecified_for_ports_keeps_metadata_editable(self):
        self.programs.stages.place(str(STAGE_ID),dict(player_uuid=str(OWNER_ID),type_key='FOR',x=12,y=0,z=0))
        self.programs.save(PROGRAM_ID,str(OWNER_ID))
        fresh=self.restart(); stage=fresh.programs.root(PROGRAM_ID)
        self.assertFalse(stage.compatible_for_graph_mutation)
        created=fresh.create(self.root,OWNER_ID,stage.workspace_epoch,stage.revision,
            name='counter',variable_type=VariableType.INTEGER,mutability=Mutability.MUTABLE).declaration
        self.assertFalse(stage.compatible_for_graph_mutation)
        with self.assertRaises(SnapshotCompatibilityError):
            fresh.programs.stages.apply_graph_operation(str(STAGE_ID),dict(player_uuid=str(OWNER_ID)),lambda _:None)
        fresh.programs.save(PROGRAM_ID,str(OWNER_ID))
        self.assertEqual((created,),self.restart().list_own(self.root,OWNER_ID).declarations)

    def test_snapshot_header_and_declarations_are_consistent_during_concurrent_save(self):
        d=self.create();self.programs.save(PROGRAM_ID,str(OWNER_ID))
        header=threading.Event();resume=threading.Event();result=[];errors=[]
        class Cursor:
            def __init__(self,inner):self.inner=inner;self.is_header=False
            def __enter__(self):self.inner.__enter__();return self
            def __exit__(self,*args):return self.inner.__exit__(*args)
            def execute(self,sql,params=None):
                self.is_header='SELECT s.*' in sql
                return self.inner.execute(sql,params)
            def fetchone(self):
                row=self.inner.fetchone()
                if self.is_header:
                    header.set()
                    if not resume.wait(10):raise TimeoutError('Concurrent SAVE test did not resume')
                return row
            def __getattr__(self,name):return getattr(self.inner,name)
        class Connection:
            def __init__(self):self.inner=connect()
            def __enter__(self):self.inner.__enter__();return self
            def __exit__(self,*args):
                try:return self.inner.__exit__(*args)
                finally:self.inner.close()
            def cursor(self,**kwargs):return Cursor(self.inner.cursor(**kwargs))
        def read():
            try:result.append(HallRepository(connect=Connection).load_full_stage_snapshot(str(STAGE_ID)))
            except Exception as error:errors.append(error)
        worker=threading.Thread(target=read);worker.start()
        try:
            self.assertTrue(header.wait(10))
            self.update(d,{'name':'after'});self.programs.save(PROGRAM_ID,str(OWNER_ID))
        finally:resume.set();worker.join(10)
        self.assertFalse(worker.is_alive());self.assertEqual([],errors)
        self.assertEqual((d,),result[0].snapshot.declarations)
        self.assertEqual('after',self.restart().list_own(self.root,OWNER_ID).declarations[0].name)


if __name__=='__main__':
    assert_database_identity(); logging.disable(logging.CRITICAL); unittest.main()
