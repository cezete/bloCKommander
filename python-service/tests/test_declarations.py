import threading
import unittest
from dataclasses import FrozenInstanceError, replace
from uuid import uuid4
from hangar.declarations import (DeclarationError, ScopeRef, VariableDeclaration, VariableType, Mutability)
from hangar.declaration_service import DeclarationService, DeclarationTarget
from hangar.scope_resolution import StageScopeParentProvider, resolve_symbols
from hangar.program import ProgramService
from hangar.stage import StageService, StageWorkspace
from hangar.repository import SnapshotCompatibilityError, SnapshotStorageError, _snapshot_rows, _snapshot_from_rows
from hangar.graph import (CK_CONTROL_PORT_CATALOG, to_graph_workspace, to_stage_snapshot,
    prepare_graph_workspace_update, replace_content)
from test_program_service import FakeProgramRepository, OWNER, PROGRAM, ROOT, OTHER_PROGRAM, OTHER_ROOT, block
from test_stage_snapshot_adapter import full_snapshot


class Fixture(unittest.TestCase):
    def setUp(self):
        self.repo=FakeProgramRepository()
        self.repo.types=[t for t in self.repo.types if t['type_key'] not in ('CLASS','INTERFACE')]
        self.stages=StageService(self.repo); self.programs=ProgramService(self.repo,self.stages)
        self.service=DeclarationService(self.programs); self.target=DeclarationTarget(PROGRAM,ROOT)
        self.root=self.programs.root(PROGRAM); self.program=self.programs.load(PROGRAM)

    def create(self,name='x',target=None,variable_type=VariableType.INTEGER,mutability=Mutability.MUTABLE):
        target=target or self.target
        stage=self.programs.stage(target.program_id,target.stage_id)
        return self.service.create(target,OWNER,stage.workspace_epoch,stage.revision,
            name=name,variable_type=variable_type,mutability=mutability)

    def update(self,declaration_id,changes,target=None):
        target=target or self.target; stage=self.programs.stage(target.program_id,target.stage_id)
        return self.service.update(target,OWNER,stage.workspace_epoch,stage.revision,declaration_id,changes)

    def child(self,parent=None):
        parent=parent or self.target
        stage=self.programs.stage(PROGRAM,parent.stage_id)
        item=self.stages.place(str(parent.stage_id),{'player_uuid':str(OWNER),'type_key':'PROGRAM','x':3,'y':0,'z':0})
        placed=next(b for b in item['blocks'] if b['rel_x']==3 and b['rel_y']==0 and b['rel_z']==0)
        child=self.programs.ensure_child(PROGRAM,parent.stage_id,placed['instance_id'],str(OWNER))
        return DeclarationTarget(PROGRAM,child.snapshot.stage_id)

    def check_reject(self,code,action):
        before=(self.root.snapshot,self.root.dirty,self.program.saved_version,self.program.dirty)
        with self.assertRaises(DeclarationError) as error: action()
        self.assertEqual(code,error.exception.code)
        self.assertEqual(before,(self.root.snapshot,self.root.dirty,self.program.saved_version,self.program.dirty))


class DeclarationDomainTest(Fixture):
    def test_create_revision_dirty_without_repository_write(self):
        before=self.root.revision; result=self.create()
        self.assertTrue(result.modified); self.assertEqual(before+1,self.root.revision)
        self.assertTrue(result.view.stage_dirty and result.view.program_dirty)
        self.assertEqual(0,self.repo.program_save_calls)
        self.assertEqual(ScopeRef(ROOT),result.declaration.scope_ref)

    def test_identifier_boundaries_ascii_case_and_keywords(self):
        for name in ('_', 'a'*64, 'x', 'X', 'class'):
            self.create(name)
        self.assertEqual(5,len(self.root.snapshot.declarations))

    def test_invalid_names_are_atomic(self):
        for name in ('','a'*65,' x','x ','1x','ár','a-b','a\n',None,False,1):
            with self.subTest(name=name): self.check_reject('INVALID_DECLARATION_NAME',lambda:self.create(name))

    def test_all_types_and_mutabilities_are_metadata_only(self):
        for kind in VariableType:
            for mutability in Mutability:
                result=self.create(kind.name+mutability.name,variable_type=kind,mutability=mutability)
                self.assertEqual(kind,result.declaration.variable_type)
                self.assertFalse(hasattr(result.declaration,'value'))

    def test_strict_type_and_mutability_rejection(self):
        self.check_reject('UNKNOWN_VARIABLE_TYPE',lambda:self.create(variable_type='INTEGER'))
        self.check_reject('INVALID_MUTABILITY',lambda:self.create(mutability='MUTABLE'))

    def test_duplicate_even_different_type_is_atomic(self):
        self.create(); self.check_reject('DUPLICATE_DECLARATION_NAME',lambda:self.create(variable_type=VariableType.STRING))

    def test_rename_type_mutability_preserve_uuid(self):
        original=self.create(mutability=Mutability.IMMUTABLE).declaration
        updated=self.update(original.declaration_id,dict(name='y',variable_type=VariableType.BOOLEAN,mutability=Mutability.MUTABLE))
        self.assertEqual(original.declaration_id,updated.declaration.declaration_id)
        self.assertEqual(original.scope_ref,updated.declaration.scope_ref)
        self.assertEqual('y',updated.declaration.name)

    def test_rename_conflict_and_identity_changes_are_atomic(self):
        x=self.create().declaration; self.create('y')
        self.check_reject('DUPLICATE_DECLARATION_NAME',lambda:self.update(x.declaration_id,{'name':'y'}))
        for changes in ({'scope_ref':ScopeRef(uuid4())},{'declaration_id':uuid4()},{}, {'value':1}):
            self.check_reject('INVALID_CHANGES',lambda:self.update(x.declaration_id,changes))

    def test_noop_after_save_keeps_clean_revision_and_identity(self):
        d=self.create().declaration; self.programs.save(PROGRAM,str(OWNER)); before=self.root.snapshot
        result=self.update(d.declaration_id,{'name':'x'})
        self.assertFalse(result.modified); self.assertIs(before,self.root.snapshot); self.assertFalse(self.root.dirty)

    def test_delete_recreate_has_new_identity(self):
        d=self.create().declaration; revision=self.root.revision
        self.service.delete(self.target,OWNER,self.root.workspace_epoch,revision,d.declaration_id)
        self.assertEqual(revision+1,self.root.revision)
        self.assertNotEqual(d.declaration_id,self.create().declaration.declaration_id)

    def test_stale_epoch_even_noop(self):
        d=self.create().declaration
        self.check_reject('STALE_WORKSPACE_EPOCH',lambda:self.service.update(self.target,OWNER,uuid4(),self.root.revision,d.declaration_id,{'name':'x'}))

    def test_stale_and_invalid_revision_even_noop(self):
        d=self.create().declaration
        for revision,code in ((0,'STALE_WORKING_REVISION'),(True,'INVALID_REVISION'),(-1,'INVALID_REVISION')):
            self.check_reject(code,lambda:self.service.update(self.target,OWNER,self.root.workspace_epoch,revision,d.declaration_id,{'name':'x'}))

    def test_foreign_owner_get_write_and_resolve(self):
        for action in (lambda:self.service.list_own(self.target,uuid4()),
                       lambda:self.service.resolve(self.target,uuid4()),
                       lambda:self.service.create(self.target,uuid4(),self.root.workspace_epoch,0,name='x',variable_type=VariableType.STRING,mutability=Mutability.MUTABLE)):
            self.check_reject('FORBIDDEN',action)

    def test_cross_program_and_declaration_do_not_mutate(self):
        self.repo.add_program(OTHER_PROGRAM,OTHER_ROOT)
        self.check_reject('STAGE_NOT_FOUND',lambda:self.service.list_own(DeclarationTarget(OTHER_PROGRAM,ROOT),OWNER))
        self.check_reject('DECLARATION_NOT_FOUND',lambda:self.update(uuid4(),{'name':'x'}))

    def test_immutable_detached_values(self):
        d=self.create().declaration
        with self.assertRaises(FrozenInstanceError): d.name='other'
        raw=d.as_dict(); raw['name']='other'
        self.assertEqual('x',self.service.list_own(self.target,OWNER).declarations[0].name)

    def test_two_concurrent_commands_have_one_cas_winner(self):
        epoch=self.root.workspace_epoch; revision=self.root.revision; barrier=threading.Barrier(2); results=[]
        def run(name):
            barrier.wait()
            try:
                self.service.create(self.target,OWNER,epoch,revision,name=name,variable_type=VariableType.INTEGER,mutability=Mutability.MUTABLE)
                results.append('ok')
            except DeclarationError as e: results.append(e.code)
        threads=[threading.Thread(target=run,args=(name,)) for name in ('x','y')]
        for t in threads:t.start()
        for t in threads:t.join(5); self.assertFalse(t.is_alive())
        self.assertCountEqual(['ok','STALE_WORKING_REVISION'],results)

    def test_old_and_unknown_graph_format_not_upgraded(self):
        self.root.graph_snapshot_version=0
        with self.assertRaises(SnapshotCompatibilityError): self.create()
        self.assertFalse(self.root.snapshot.declarations)


class ScopeResolutionTest(Fixture):
    def test_pure_resolver_rejects_mismatched_or_empty_basis(self):
        view=self.service.resolve(self.target,OWNER)
        for basis in (replace(view.basis,scopes=()),
                      replace(view.basis,query_scope=ScopeRef(uuid4())),
                      replace(view.basis,scopes=(replace(view.basis.scopes[0],working_revision=99),))):
            with self.assertRaises(DeclarationError) as error:resolve_symbols((self.root.snapshot,),basis)
            self.assertEqual('SCOPE_UNAVAILABLE',error.exception.code)

    def test_child_change_invalidates_child_basis_without_changing_parent_basis(self):
        child=self.child(); parent=self.service.resolve(self.target,OWNER).basis
        basis=self.service.resolve(child,OWNER).basis
        self.create('local',child)
        self.assertFalse(self.service.basis_is_current(basis,OWNER))
        self.assertTrue(self.service.basis_is_current(parent,OWNER))

    def test_root_local_missing_and_deterministic_order(self):
        self.create('z'); self.create('a')
        view=self.service.resolve(self.target,OWNER,'a')
        self.assertEqual('FOUND',view.status); self.assertEqual('LOCAL',view.resolved.source)
        self.assertEqual(['a','z'],[v.declaration.name for v in view.visible])
        self.assertEqual('NOT_FOUND',self.service.resolve(self.target,OWNER,'missing').status)

    def test_child_inheritance_shadowing_and_external_write_rejected(self):
        outer=self.create('counter').declaration; child=self.child()
        inherited=self.service.resolve(child,OWNER,'counter').resolved
        self.assertEqual('INHERITED',inherited.source); self.assertFalse(inherited.can_edit_declaration)
        with self.assertRaises(DeclarationError) as error:self.update(outer.declaration_id,{'name':'bad'},child)
        self.assertEqual('DECLARATION_NOT_FOUND',error.exception.code)
        local=self.create('counter',child,VariableType.BOOLEAN,Mutability.IMMUTABLE).declaration
        view=self.service.resolve(child,OWNER,'counter')
        self.assertEqual(local,view.resolved.declaration); self.assertEqual(outer,view.shadowed[0].declaration)
        self.assertEqual(local.declaration_id,view.shadowed[0].shadowed_by)

    def test_siblings_have_independent_same_names(self):
        a=self.child(); # Use the original PROGRAM block for the second child.
        b=self.programs.ensure_child(PROGRAM,ROOT,block(ROOT,'program').instance_id,str(OWNER))
        b=DeclarationTarget(PROGRAM,b.snapshot.stage_id)
        first=self.create('x',a).declaration; second=self.create('x',b).declaration
        self.assertNotEqual(first.declaration_id,second.declaration_id)
        self.assertEqual((),self.service.resolve(a,OWNER).shadowed)

    def test_parent_rename_type_delete_invalidate_without_dirtying_child(self):
        root=self.create().declaration; child=self.child(); self.programs.save(PROGRAM,str(OWNER))
        basis=self.service.resolve(child,OWNER).basis
        for changes in ({'name':'y'},{'variable_type':VariableType.BOOLEAN}):
            self.update(root.declaration_id,changes)
            self.assertFalse(self.service.basis_is_current(basis,OWNER))
            self.assertFalse(self.programs.stage(PROGRAM,child.stage_id).dirty)
            basis=self.service.resolve(child,OWNER).basis
        self.service.delete(self.target,OWNER,self.root.workspace_epoch,self.root.revision,root.declaration_id)
        self.assertFalse(self.service.basis_is_current(basis,OWNER))

    def test_local_epoch_program_epoch_and_policy_invalidate_basis(self):
        self.create(); basis=self.service.resolve(self.target,OWNER).basis
        self.assertTrue(self.service.basis_is_current(basis,OWNER))
        self.root.workspace_epoch=uuid4(); self.assertFalse(self.service.basis_is_current(basis,OWNER))
        basis=self.service.resolve(self.target,OWNER).basis; self.program.workspace_epoch=uuid4()
        self.assertFalse(self.service.basis_is_current(basis,OWNER))
        basis=self.service.resolve(self.target,OWNER).basis
        self.service.parents=type('NextPolicy',(StageScopeParentProvider,),{'version':2})()
        self.assertFalse(self.service.basis_is_current(basis,OWNER))

    def test_retained_own_read_preserved_and_inherited_scope_unavailable(self):
        child=self.child(); d=self.create('x',child).declaration
        link=next(l for l in self.program.links if l.child_stage_id==child.stage_id)
        basis=self.service.resolve(child,OWNER).basis
        self.programs.retain_child(PROGRAM,ROOT,link.program_block_instance_id,str(OWNER))
        self.assertEqual((d,),self.service.list_own(child,OWNER).declarations)
        self.assertFalse(self.service.basis_is_current(basis,OWNER))
        with self.assertRaises(DeclarationError) as e:self.create('y',child)
        self.assertEqual('SCOPE_UNAVAILABLE',e.exception.code)

    def test_cycle_missing_parent_duplicate_parent_and_root_parent_fail_closed(self):
        a=self.child(); link=self.program.links[0]
        for links in ((replace(link,parent_stage_id=a.stage_id),),
                      (replace(link,parent_stage_id=uuid4()),), (link,link),
                      (replace(link,child_stage_id=ROOT),)):
            self.program.links=links
            with self.assertRaises(DeclarationError) as e:self.service.resolve(a,OWNER)
            self.assertEqual('SCOPE_UNAVAILABLE',e.exception.code)

    def test_reparent_invalidates_basis_and_changes_inheritance(self):
        a=self.child(); self.create('inside',a)
        grand=self.child(a); before=self.service.resolve(grand,OWNER).basis
        link=next(l for l in self.program.links if l.child_stage_id==grand.stage_id)
        self.programs.retain_child(PROGRAM,a.stage_id,link.program_block_instance_id,str(OWNER))
        self.programs.link_child(PROGRAM,ROOT,block(ROOT,'program').instance_id,grand.stage_id,str(OWNER))
        self.assertFalse(self.service.basis_is_current(before,OWNER))
        self.assertEqual('NOT_FOUND',self.service.resolve(grand,OWNER,'inside').status)


class DeclarationSnapshotTest(Fixture):
    def test_snapshot_constructor_rejects_duplicates_foreign_scope_and_format(self):
        d=self.create().declaration
        for changes in ({'declarations':(d,d)},
                        {'declarations':(replace(d,scope_ref=ScopeRef(uuid4())),)},
                        {'declaration_schema_version':7}, {'declaration_schema_version':0}):
            with self.assertRaises(DeclarationError): replace(self.root.snapshot,**changes)

    def test_legacy_constructors_are_empty(self):
        self.assertEqual((),StageWorkspace({'stage_id':str(ROOT)},blocks=[]).snapshot.declarations)

    def test_legacy_constructors_cannot_silently_discard_known_or_unknown_metadata(self):
        for version in (1,77):
            header={'stage_id':str(ROOT),'declaration_schema_version':version}
            with self.assertRaises(SnapshotCompatibilityError):StageWorkspace(header,blocks=[])
            with self.assertRaises(SnapshotCompatibilityError):StageService._snapshot_from_legacy(header,[],working_revision=0)
        with self.assertRaises(SnapshotStorageError):
            _snapshot_from_rows({'stage_id':str(ROOT),'declaration_schema_version':1},[],[],[],[],[],[])

    def test_graph_adapter_block_wire_and_bind_preserve_declarations(self):
        value=full_snapshot()
        self.root.snapshot=replace(value,stage_id=ROOT,
            blocks=(*value.blocks,block(ROOT,'program',x=5)),
            edges=tuple(replace(e,stage_id=ROOT) for e in value.edges),
            routes=tuple(replace(r,stage_id=ROOT) for r in value.routes))
        self.create(); before=self.root.snapshot.declarations
        for _ in range(2):
            state=to_graph_workspace(self.root,CK_CONTROL_PORT_CATALOG,known_block_types={t['type_key'] for t in self.repo.types}).state
            target=replace_content(state,wire_cells=state.wire_cells[:-1])
            self.root.snapshot=to_stage_snapshot(self.root.snapshot,prepare_graph_workspace_update(state,target,operation_accepted=True))
            self.assertEqual(before,self.root.snapshot.declarations)
        placed=self.stages.place(str(ROOT),{'player_uuid':str(OWNER),'type_key':'STOP','x':8,'y':0,'z':0})
        bid=next(b['instance_id'] for b in placed['blocks'] if b['rel_x']==8)
        self.stages.update(str(ROOT),bid,{'player_uuid':str(OWNER),'label':'changed'})
        self.stages.delete(str(ROOT),bid,{'player_uuid':str(OWNER)})
        self.assertEqual(before,self.root.snapshot.declarations)

    def test_save_restart_rebuild_and_unsaved_loss(self):
        saved=self.create().declaration; self.programs.save(PROGRAM,str(OWNER))
        self.create('unsaved')
        fresh=DeclarationService(ProgramService(self.repo,StageService(self.repo)))
        self.assertEqual((saved,),fresh.list_own(self.target,OWNER).declarations)
        old_epoch=self.root.workspace_epoch
        self.stages.rebuild(str(ROOT),{'player_uuid':str(OWNER),'confirm_discard':True})
        self.assertEqual((saved,),self.root.snapshot.declarations)
        self.assertNotEqual(old_epoch,self.root.workspace_epoch)

    def test_failed_save_keeps_dirty_snapshot(self):
        self.create(); before=self.root.snapshot; self.repo.fail_save=True
        with self.assertRaises(Exception):self.programs.save(PROGRAM,str(OWNER))
        self.assertIs(before,self.root.snapshot); self.assertTrue(self.root.dirty)

    def test_normalized_rows_and_unknown_format_fail_closed(self):
        d=self.create().declaration; rows=_snapshot_rows(self.root.snapshot)
        self.assertEqual(d.name,rows.declarations[0][2])
        header={'stage_id':str(ROOT),'port_catalog_version':CK_CONTROL_PORT_CATALOG.version,'declaration_schema_version':1}
        loaded=_snapshot_from_rows(header,[],[],[],[],[],[],[dict(declaration_id=d.declaration_id,name=d.name,type_key='INTEGER',mutability='MUTABLE')])
        self.assertEqual((d,),loaded.declarations)
        with self.assertRaises(SnapshotCompatibilityError):_snapshot_from_rows({**header,'declaration_schema_version':2},[],[],[],[],[],[])


if __name__=='__main__':unittest.main()
