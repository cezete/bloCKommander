import json
import unittest
from dataclasses import replace
from uuid import UUID, uuid4
from test_declarations import Fixture
from test_program_service import OWNER, PROGRAM, ROOT, OTHER_PROGRAM, OTHER_ROOT
from hangar.declaration_service import DeclarationTarget
from hangar.declarations import VariableType, Mutability
from hangar.program import ProgramService
from hangar.stage import StageService
from hangar.repository import (_snapshot_rows, _snapshot_from_rows, SnapshotCompatibilityError,
                               SnapshotStorageError, HallConflict)
from hangar.graph.model import (GraphEdge, EdgeKind, GridPos, CardinalDirection, WireCell,
                                WireChannelState, WireChannel)
from hangar.graph.snapshot import thaw_json
from hangar.graph.placement import WirePlacementIntent
from hangar.value_model import ValueDomainError, INTEGER_PROFILE
from hangar.value_service import ValueService


class ValueFixture(Fixture):
    def setUp(self):
        super().setUp()
        self.repo.types += [{"type_key": key} for key in ("LET", "LITERAL", "REF", "OPERATOR", "RESULT")]
        self.values = ValueService(self.programs, declarations=self.service)
        self.x = self.create('x').declaration
        self.y = self.create('y', mutability=Mutability.IMMUTABLE).declaration
        self.let = self.place('LET', self.target)
        self.values.set_let_target(self.target, OWNER, self.basis(self.target), self.let, self.x.declaration_id)
        self.rhs = self.values.ensure_rhs(self.target, OWNER, self.basis(self.target), self.let)

    def basis(self, target=None):
        return self.values.view(target or self.rhs, OWNER).basis

    def place(self, kind, target=None, position=None, orientation=CardinalDirection.NORTH):
        target = target or self.rhs
        stage = self.programs.stage(PROGRAM, target.stage_id)
        position = position or GridPos(len(stage.snapshot.blocks) + 1, 0, 1)
        return self.values.place_block(target, OWNER, self.basis(target), kind, position, orientation=orientation)

    def literal(self, value, **kwargs):
        bid = self.place('LITERAL', **kwargs)
        self.values.set_literal(self.rhs, OWNER, self.basis(), bid, value)
        return bid

    def operator(self, operation='ADD'):
        bid = self.place('OPERATOR')
        self.values.set_operator(self.rhs, OWNER, self.basis(), bid, operation)
        return bid

    def ref(self, declaration=None):
        bid = self.place('REF')
        self.values.set_ref(self.rhs, OWNER, self.basis(), bid, (declaration or self.y).declaration_id)
        return bid

    def edge(self, source, target, port='in', kind=EdgeKind.VALUE):
        # Domain fixture injects semantic edges; physical explicit-bind is tested separately.
        stage = self.programs.stage(PROGRAM, self.rhs.stage_id)
        edge = GraphEdge(uuid4(), self.rhs.stage_id, source, 'out', target, port, kind)
        self.values._commit(stage, edges=(*stage.snapshot.edges, edge))
        return edge

    def binary(self, a=2, b=3, operation='ADD'):
        lhs = self.literal(a); rhs = self.literal(b); op = self.operator(operation); result = self.place('RESULT')
        self.edge(lhs, op, 'lhs'); self.edge(rhs, op, 'rhs'); self.edge(op, result)
        return lhs, rhs, op, result

    def analysis(self):
        return self.values.view(self.rhs, OWNER).analysis

    def codes(self):
        return {d.code for d in self.analysis().diagnostics}


class ValueDomainTest(ValueFixture):
    def test_empty_child_idempotent_and_manual_result(self):
        self.assertEqual('VALUE_BODY', self.programs.stage(PROGRAM, self.rhs.stage_id).stage['stage_kind'])
        self.assertEqual((), self.values.view(self.rhs, OWNER).snapshot.blocks)
        self.assertEqual({'MISSING_RESULT'}, self.codes())
        self.assertEqual(self.rhs, self.values.ensure_rhs(self.target, OWNER, self.basis(self.target), self.let))
        self.assertEqual('LET_RHS', self.program.links[0].owner_role)

    def test_all_arithmetic_operations_order_and_display(self):
        self.binary(10, 3, 'SUBTRACT')
        self.assertEqual('10 - 3', self.analysis().expression)
        for op, symbol in [('ADD', '+'), ('MULTIPLY', '*'), ('DIVIDE', '/')]:
            bid = next(b.instance_id for b in self.values.view(self.rhs, OWNER).snapshot.blocks if b.type_key == 'OPERATOR')
            self.values.set_operator(self.rhs, OWNER, self.basis(), bid, op)
            self.assertEqual(f'10 {symbol} 3', self.analysis().expression)
            self.assertTrue(self.analysis().valid)

    def test_nested_precedence_and_deterministic_dependency_order(self):
        _, _, op, result = self.binary(10, 5)
        stage = self.programs.stage(PROGRAM, self.rhs.stage_id)
        self.values._commit(stage, edges=tuple(e for e in stage.snapshot.edges if e.target_instance_id != result))
        two = self.literal(2); multiply = self.operator('MULTIPLY')
        self.edge(op, multiply, 'lhs'); self.edge(two, multiply, 'rhs'); self.edge(multiply, result)
        first = self.analysis()
        self.assertEqual('(10 + 5) * 2', first.expression)
        self.assertLess(first.dependency_order.index(op), first.dependency_order.index(multiply))
        self.assertEqual(first, self.analysis())

    def test_ref_reads_var_and_val_and_let_targets_both(self):
        ref = self.ref(); result = self.place('RESULT'); self.edge(ref, result)
        self.assertEqual('y', self.analysis().expression)
        for declaration in (self.x, self.y):
            self.values.set_ref(self.rhs, OWNER, self.basis(), ref, declaration.declaration_id)
            self.values.set_let_target(self.target, OWNER, self.basis(self.target), self.let, declaration.declaration_id)
            self.assertTrue(self.analysis().valid)

    def test_rename_preserves_uuid_and_refreshes_expression(self):
        ref = self.ref(); result = self.place('RESULT'); self.edge(ref, result)
        old_basis = self.basis(); revision = old_basis.working_revision
        self.update(self.y.declaration_id, {'name': 'renamed'})
        self.assertEqual('renamed', self.analysis().expression)
        self.assertEqual(revision, self.basis().working_revision)
        with self.assertRaisesRegex(ValueDomainError, 'Reload'):
            self.values.set_ref(self.rhs, OWNER, old_basis, ref, self.x.declaration_id)

    def test_shadowing_preserves_outer_uuid(self):
        ref = self.ref(); result = self.place('RESULT'); self.edge(ref, result)
        local = self.create('y', target=self.rhs, variable_type=VariableType.STRING).declaration
        self.assertNotEqual(local.declaration_id, self.y.declaration_id)
        self.assertEqual('INTEGER', self.analysis().result_type)
        config = thaw_json(next(b for b in self.values.view(self.rhs, OWNER).snapshot.blocks if b.instance_id == ref).semantic_config)
        self.assertEqual(str(self.y.declaration_id), config['target_id'])

    def test_delete_recreate_keeps_broken_ref(self):
        ref = self.ref(); result = self.place('RESULT'); self.edge(ref, result)
        self.service.delete(self.target, OWNER, self.root.workspace_epoch, self.root.revision, self.y.declaration_id)
        fresh = self.create('y').declaration
        self.assertNotEqual(fresh.declaration_id, self.y.declaration_id)
        self.assertIn('BROKEN_REF', self.codes())
        config = thaw_json(next(b for b in self.values.view(self.rhs, OWNER).snapshot.blocks if b.instance_id == ref).semantic_config)
        self.assertEqual(str(self.y.declaration_id), config['target_id'])

    def test_result_cardinality_and_missing_operands(self):
        result = self.place('RESULT')
        self.assertIn('MISSING_OPERAND', self.codes())
        self.place('RESULT')
        self.assertIn('MULTIPLE_RESULTS', self.codes())

    def test_disconnected_cycle_is_detected(self):
        self.binary()
        a = self.operator(); b = self.operator()
        self.edge(a, b, 'lhs'); self.edge(b, a, 'lhs')
        self.assertIn('VALUE_CYCLE', self.codes())

    def test_reachable_cycle_is_detected_and_terminates(self):
        a = self.operator(); b = self.operator(); result = self.place('RESULT')
        self.edge(a, b, 'lhs'); self.edge(b, a, 'lhs'); self.edge(b, result)
        self.assertIn('VALUE_CYCLE', self.codes())
        self.assertIsNone(self.analysis().expression)

    def test_type_mismatch_and_let_compatibility(self):
        string = self.create('text', variable_type=VariableType.STRING).declaration
        ref = self.ref(string); result = self.place('RESULT'); self.edge(ref, result)
        self.assertIn('LET_TYPE_MISMATCH', self.codes())
        op = self.operator(); self.edge(ref, op, 'lhs')
        number = self.literal(1); self.edge(number, op, 'rhs')
        stage = self.programs.stage(PROGRAM, self.rhs.stage_id)
        self.values._commit(stage, edges=tuple(e for e in stage.snapshot.edges if e.target_instance_id != result))
        self.edge(op, result)
        self.assertIn('TYPE_MISMATCH', self.codes())

    def test_mixed_edge_kind_invalid(self):
        literal = self.literal(1); result = self.place('RESULT'); self.edge(literal, result, kind=EdgeKind.CONTROL)
        self.assertIn('INVALID_VALUE_EDGE', self.codes())

    def test_fanout_diagnostic(self):
        literal = self.literal(1); result = self.place('RESULT'); op = self.operator()
        self.edge(literal, result); self.edge(literal, op, 'lhs')
        self.assertIn('VALUE_FANOUT', self.codes())

    def test_integer_range_config_rejected_atomically(self):
        bid = self.literal(0); before = self.values.view(self.rhs, OWNER).snapshot
        for value in (2**31, -2**31-1, True, '2'):
            with self.assertRaises(ValueDomainError):
                self.values.set_literal(self.rhs, OWNER, self.basis(), bid, value)
            self.assertEqual(before, self.values.view(self.rhs, OWNER).snapshot)

    def test_static_zero_division_and_overflow_are_diagnostics(self):
        _, rhs, op, _ = self.binary(1, 0, 'DIVIDE')
        self.assertIn('DIVISION_BY_ZERO', self.codes())
        self.values.set_literal(self.rhs, OWNER, self.basis(), rhs, 2**31-1)
        self.values.set_operator(self.rhs, OWNER, self.basis(), op, 'ADD')
        self.assertIn('INTEGER_OVERFLOW', self.codes())

    def test_unknown_ref_variant_and_profile_rejected(self):
        ref = self.ref(); before = self.values.view(self.rhs, OWNER).snapshot
        for kind in ('EXPRESSION_REF', 'PFUN_REF', 'UNKNOWN'):
            with self.assertRaises(ValueDomainError):
                self.values.configure(self.rhs, OWNER, self.basis(), ref,
                    {'version': 1, 'kind': kind, 'target_id': str(self.y.declaration_id)})
        self.assertEqual(before, self.values.view(self.rhs, OWNER).snapshot)

    def test_stage_block_policy(self):
        for kind in ('START', 'STOP', 'LET', 'PROGRAM'):
            with self.assertRaises(HallConflict): self.place(kind)
        with self.assertRaises(HallConflict): self.place('LITERAL', self.target)

    def test_hierarchy_role_kind_and_cross_program_rejected(self):
        self.repo.add_program(OTHER_PROGRAM, OTHER_ROOT)
        with self.assertRaises(HallConflict):
            self.programs.link_child(PROGRAM, ROOT, self.let, OTHER_ROOT, str(OWNER), owner_role='LET_RHS')
        with self.assertRaises(HallConflict):
            self.programs.link_child(PROGRAM, ROOT, self.let, ROOT, str(OWNER), owner_role='LET_RHS')
        with self.assertRaises(HallConflict):
            self.programs.ensure_child(PROGRAM, ROOT, self.let, str(OWNER))

    def test_retained_child_and_scope_loss(self):
        self.programs.retain_child(PROGRAM, ROOT, self.let, str(OWNER))
        with self.assertRaisesRegex(Exception, 'Detached'):
            self.values.view(self.rhs, OWNER)
        self.assertEqual(self.rhs, self.values.ensure_rhs(self.target, OWNER, self.basis(self.target), self.let))

    def test_complete_and_semantically_invalid_save_restart(self):
        self.binary()
        self.programs.save(PROGRAM, str(OWNER))
        saved = self.values.view(self.rhs, OWNER).snapshot
        fresh = ValueService(ProgramService(self.repo, StageService(self.repo)))
        self.assertEqual(saved.blocks, fresh.view(self.rhs, OWNER).snapshot.blocks)
        self.assertEqual('2 + 3', fresh.view(self.rhs, OWNER).analysis.expression)
        self.place('RESULT'); self.assertIn('MULTIPLE_RESULTS', self.codes())
        self.programs.save(PROGRAM, str(OWNER))
        self.assertFalse(self.program.dirty)

    def test_empty_stage_save_restart(self):
        self.programs.save(PROGRAM, str(OWNER))
        fresh = ValueService(ProgramService(self.repo, StageService(self.repo)))
        self.assertEqual('MISSING_RESULT', fresh.view(self.rhs, OWNER).analysis.diagnostics[0].code)
        self.assertEqual(1, fresh.view(self.rhs, OWNER).snapshot.value_schema_version)

    def test_failed_save_keeps_workspace_and_no_fake_saved_state(self):
        self.binary(); before = self.values.view(self.rhs, OWNER).snapshot
        self.repo.fail_save = True
        with self.assertRaises(RuntimeError): self.programs.save(PROGRAM, str(OWNER))
        self.assertEqual(before, self.values.view(self.rhs, OWNER).snapshot)
        self.assertTrue(self.program.dirty)
        self.assertNotIn(self.rhs.stage_id, self.repo.snapshots)

    def test_normalized_snapshot_rows_roundtrip_and_unknown_format(self):
        self.binary(); snapshot = self.values.view(self.rhs, OWNER).snapshot
        from test_repository_graph_snapshot import row_dicts
        rows = row_dicts(snapshot)
        configs = dict(_snapshot_rows(snapshot).semantic_configs)
        for block in rows[0]:
            block['semantic_config'] = json.loads(configs.get(block['instance_id'], '{}'))
        header = {'stage_id': str(self.rhs.stage_id), 'port_catalog_version': snapshot.port_catalog_version,
                  'value_schema_version': 1}
        restored = _snapshot_from_rows(header, *rows)
        self.assertEqual(snapshot.blocks, tuple(replace(b, material_name='DOMAIN', display_definition=()) for b in restored.blocks))
        self.assertEqual(snapshot.edges, restored.edges)
        with self.assertRaises(SnapshotCompatibilityError): _snapshot_from_rows({**header, 'value_schema_version': 2}, *rows)
        with self.assertRaises(SnapshotStorageError): _snapshot_from_rows({**header, 'value_schema_version': 0}, *rows)

    def test_explicit_physical_bind_and_one_consumer(self):
        source = self.literal(2, position=GridPos(5, 0, 6))
        result = self.place('RESULT', position=GridPos(5, 0, 4))
        self.values.place_wire(self.rhs, OWNER, self.basis(), GridPos(5, 0, 5), WirePlacementIntent.AUTO)
        self.assertEqual((), self.values.view(self.rhs, OWNER).snapshot.edges)
        bound = self.values.bind(self.rhs, OWNER, self.basis())
        self.assertTrue(bound.accepted, bound.diagnostics)
        self.assertEqual(1, len(bound.created_edge_ids))
        self.assertEqual(EdgeKind.VALUE, bound.state.edges[0].edge_kind)
        self.assertEqual('2', self.analysis().expression)
        self.assertEqual((), self.values.bind(self.rhs, OWNER, self.basis()).created_edge_ids)

    def test_operand_orientation_keeps_semantic_keys(self):
        from hangar.value_ports import VALUE_PORT_CATALOG
        from hangar.graph.orientation import port_connection_position
        op = self.operator('SUBTRACT'); self.binary()
        block = next(b for b in self.values.view(self.rhs, OWNER).snapshot.blocks if b.instance_id == op)
        from hangar.graph.model import GraphNode
        for direction in CardinalDirection:
            node = GraphNode(op, self.rhs.stage_id, 'OPERATOR', block.position, direction)
            positions = {p.port_key: port_connection_position(node, p) for p in VALUE_PORT_CATALOG['OPERATOR']}
            self.assertEqual({'lhs', 'rhs', 'out'}, set(positions))
            self.assertEqual(3, len(set(positions.values())))

    def test_mutations_require_explicit_current_basis(self):
        for basis in (None, object(), replace(self.basis(), workspace_epoch=uuid4()),
                      replace(self.basis(), working_revision=self.basis().working_revision + 1)):
            before = self.values.view(self.rhs, OWNER).snapshot
            with self.assertRaises(ValueDomainError):
                self.values.place_block(self.rhs, OWNER, basis, 'RESULT', GridPos(10, 0, 10))
            self.assertEqual(before, self.values.view(self.rhs, OWNER).snapshot)

    def test_scope_reparent_breaks_uuid_without_rebinding(self):
        child = self.child()
        local = self.create('only_child', target=child).declaration
        let = self.place('LET', child)
        rhs = self.values.ensure_rhs(child, OWNER, self.basis(child), let)
        ref = self.values.place_block(rhs, OWNER, self.basis(rhs), 'REF', GridPos(1, 0, 1))
        self.values.set_ref(rhs, OWNER, self.basis(rhs), ref, local.declaration_id)
        result = self.values.place_block(rhs, OWNER, self.basis(rhs), 'RESULT', GridPos(2, 0, 1))
        stage = self.programs.stage(PROGRAM, rhs.stage_id)
        self.values._commit(stage, edges=(GraphEdge(uuid4(), rhs.stage_id, ref, 'out', result, 'in', EdgeKind.VALUE),))
        old = self.basis(rhs)
        self.programs.retain_child(PROGRAM, child.stage_id, let, str(OWNER))
        # Reparent the saved identity under a different LET whose scope lacks local.
        self.programs.retain_child(PROGRAM, ROOT, self.let, str(OWNER))
        self.programs.link_child(PROGRAM, ROOT, self.let, rhs.stage_id, str(OWNER), owner_role='LET_RHS')
        view = self.values.view(rhs, OWNER)
        self.assertIn('BROKEN_REF', {d.code for d in view.analysis.diagnostics})
        self.assertNotEqual(old.resolution_basis, view.basis.resolution_basis)
        self.assertEqual(str(local.declaration_id), thaw_json(next(b for b in view.snapshot.blocks if b.instance_id == ref).semantic_config)['target_id'])

    def test_let_delete_retains_child_and_saved_content(self):
        self.binary()
        self.stages.delete(str(ROOT), str(self.let), {'player_uuid': str(OWNER)})
        self.assertEqual('RETAINED', self.program.links[0].link_state)
        self.programs.save(PROGRAM, str(OWNER))
        self.assertEqual(4, len(self.repo.snapshots[self.rhs.stage_id].blocks))

    def test_unsaved_value_content_is_not_restored_after_restart(self):
        self.programs.save(PROGRAM, str(OWNER))
        self.binary()
        fresh = ValueService(ProgramService(self.repo, StageService(self.repo)))
        self.assertEqual((), fresh.view(self.rhs, OWNER).snapshot.blocks)

    def test_unknown_configuration_version_and_invalid_shape(self):
        bid = self.literal(1); before = self.values.view(self.rhs, OWNER).snapshot
        for config in ({'version': 2}, {'version': True}, [], None,
                       {'version': 1, 'type': 'INTEGER', 'value': 1, 'profile': 'future'}):
            with self.assertRaises(ValueDomainError):
                self.values.configure(self.rhs, OWNER, self.basis(), bid, config)
            self.assertEqual(before, self.values.view(self.rhs, OWNER).snapshot)

    def test_static_integer_division_truncates_toward_zero(self):
        a = self.literal(-7); b = self.literal(3); divide = self.operator('DIVIDE')
        self.edge(a, divide, 'lhs'); self.edge(b, divide, 'rhs')
        two = self.literal(2); add = self.operator()
        self.edge(divide, add, 'lhs'); self.edge(two, add, 'rhs')
        one = self.literal(1); outer = self.operator('DIVIDE'); result = self.place('RESULT')
        self.edge(one, outer, 'lhs'); self.edge(add, outer, 'rhs'); self.edge(outer, result)
        self.assertIn('DIVISION_BY_ZERO', self.codes())

    def test_min_integer_divided_by_minus_one_overflows(self):
        self.binary(-(2**31), -1, 'DIVIDE')
        self.assertIn('INTEGER_OVERFLOW', self.codes())

    def test_cycle_and_type_errors_do_not_gate_save(self):
        a = self.operator(); b = self.operator(); self.edge(a, b, 'lhs'); self.edge(b, a, 'lhs')
        self.assertIn('VALUE_CYCLE', self.codes())
        self.programs.save(PROGRAM, str(OWNER))
        self.assertFalse(self.program.dirty)

    def test_control_and_value_binding_rejects_mixed_ports(self):
        from hangar.graph.binding import bind_edges, BindEdgesCommand, RouteCandidate
        from hangar.graph.model import PortRef, WireStep
        from hangar.value_ports import VALUE_PORT_CATALOG
        from hangar.graph.port_catalog import CK_CONTROL_PORT_CATALOG
        literal = self.literal(1); result = self.place('RESULT')
        context = self.stages.read_graph_context(str(self.rhs.stage_id), str(OWNER))
        candidate = RouteCandidate(PortRef(literal, 'out'), PortRef(result, 'in'),
                                  (WireStep(GridPos(2, 0, 2), WireChannel.SINGLE),))
        catalog = {**VALUE_PORT_CATALOG, 'RESULT': CK_CONTROL_PORT_CATALOG['STOP']}
        outcome = bind_edges(context.state, BindEdgesCommand(self.rhs.stage_id, OWNER, context.state.working_revision, (candidate,)),
                             catalog, edge_id_factory=uuid4, route_id_factory=uuid4)
        self.assertFalse(outcome.accepted)
        self.assertIn('INCOMPATIBLE_PORT_KIND', {d.code.value for d in outcome.diagnostics})

    def test_structural_value_source_fanout_is_rejected_by_bind(self):
        from hangar.graph.binding import bind_edges, BindEdgesCommand, RouteCandidate
        from hangar.graph.model import PortRef, WireStep
        from hangar.value_ports import VALUE_PORT_CATALOG
        literal = self.literal(1); a = self.place('RESULT'); b = self.place('RESULT')
        context = self.stages.read_graph_context(str(self.rhs.stage_id), str(OWNER))
        candidates = tuple(RouteCandidate(PortRef(literal, 'out'), PortRef(target, 'in'),
                           (WireStep(GridPos(10, 0, 10), WireChannel.SINGLE),)) for target in (a, b))
        outcome = bind_edges(context.state, BindEdgesCommand(self.rhs.stage_id, OWNER, context.state.working_revision, candidates),
                             VALUE_PORT_CATALOG, edge_id_factory=uuid4, route_id_factory=uuid4)
        self.assertFalse(outcome.accepted)
        self.assertIn('PORT_CARDINALITY_VIOLATION', {d.code.value for d in outcome.diagnostics})


class ResultDependencyRegressionTest(ValueFixture):
    def detached_binary(self, operation, lhs=None, rhs=None):
        lhs = self.literal(1) if lhs is None else lhs
        rhs = self.literal(0) if rhs is None else rhs
        op = self.operator(operation)
        self.edge(lhs, op, 'lhs'); self.edge(rhs, op, 'rhs')
        return op

    def test_detached_zero_division_does_not_invalidate_result(self):
        self.binary(); before = self.analysis()
        detached = self.detached_binary('DIVIDE')
        after = self.analysis()
        self.assertTrue(after.valid); self.assertTrue(after.result_valid)
        self.assertEqual((), after.diagnostics)
        self.assertEqual(before.dependency_order, after.dependency_order)
        self.assertEqual('2 + 3', after.expression)
        self.assertNotIn(detached, after.dependency_order)

    def test_detached_type_error_does_not_invalidate_result(self):
        self.binary(); before = self.analysis()
        string = self.create('text', variable_type=VariableType.STRING).declaration
        self.detached_binary('ADD', self.ref(string), self.literal(1))
        after = self.analysis()
        self.assertTrue(after.valid); self.assertTrue(after.result_valid)
        self.assertEqual((), after.diagnostics)
        self.assertEqual(before.dependency_order, after.dependency_order)
        self.assertEqual(before.expression, after.expression)

    def test_detached_unconfigured_and_broken_ref_remain_drafts(self):
        self.binary(); self.place('OPERATOR'); self.ref(self.y)
        self.service.delete(self.target, OWNER, self.root.workspace_epoch, self.root.revision, self.y.declaration_id)
        self.assertTrue(self.analysis().valid)
        self.assertTrue(self.analysis().result_valid)

    def test_disconnected_cycle_keeps_global_invalid_and_result_valid(self):
        self.binary(); before = self.analysis()
        a = self.operator(); b = self.operator()
        self.edge(a, b, 'lhs'); self.edge(b, a, 'lhs')
        after = self.analysis()
        self.assertIn('VALUE_CYCLE', self.codes())
        self.assertFalse(after.valid); self.assertTrue(after.result_valid)
        self.assertEqual(before.dependency_order, after.dependency_order)
        self.assertEqual(before.expression, after.expression)

    def test_connected_cycle_invalidates_result(self):
        a = self.operator(); b = self.operator(); result = self.place('RESULT')
        self.edge(a, b, 'lhs'); self.edge(b, a, 'lhs'); self.edge(b, result)
        self.assertIn('VALUE_CYCLE', self.codes())
        self.assertFalse(self.analysis().valid)
        self.assertFalse(self.analysis().result_valid)

    def test_connected_zero_division_remains_invalid_and_saveable(self):
        self.binary(1, 0, 'DIVIDE')
        self.assertIn('DIVISION_BY_ZERO', self.codes())
        self.assertFalse(self.analysis().valid)
        self.assertFalse(self.analysis().result_valid)
        self.programs.save(PROGRAM, str(OWNER))
        fresh = ValueService(ProgramService(self.repo, StageService(self.repo)))
        restored = fresh.view(self.rhs, OWNER).analysis
        self.assertIn('DIVISION_BY_ZERO', {d.code for d in restored.diagnostics})
        self.assertFalse(self.program.dirty)

    def test_missing_and_multiple_results_have_no_single_valid_expression(self):
        self.detached_binary('DIVIDE')
        self.assertEqual({'MISSING_RESULT'}, self.codes())
        self.assertFalse(self.analysis().result_valid)
        self.assertIsNone(self.analysis().expression)
        self.assertEqual((), self.analysis().dependency_order)
        self.programs.save(PROGRAM, str(OWNER))
        self.place('RESULT'); self.place('RESULT')
        self.assertIn('MULTIPLE_RESULTS', self.codes())
        self.assertFalse(self.analysis().result_valid)
        self.assertIsNone(self.analysis().expression)
        self.programs.save(PROGRAM, str(OWNER))

    def test_connected_type_mismatch_is_saveable(self):
        string = self.create('text', variable_type=VariableType.STRING).declaration
        op = self.detached_binary('ADD', self.ref(string), self.literal(1))
        result = self.place('RESULT'); self.edge(op, result)
        self.assertIn('TYPE_MISMATCH', self.codes())
        self.assertFalse(self.analysis().result_valid)
        self.programs.save(PROGRAM, str(OWNER))
        fresh = ValueService(ProgramService(self.repo, StageService(self.repo)))
        self.assertIn('TYPE_MISMATCH', {d.code for d in fresh.view(self.rhs, OWNER).analysis.diagnostics})
        self.assertFalse(self.program.dirty)

    def assert_division_quotient(self, numerator, denominator, expected):
        # Observe the exact folded quotient through a zero-denominator diagnostic,
        # without adding a runtime-value API just for tests: 1 / (a/b - expected).
        divide = self.detached_binary('DIVIDE', self.literal(numerator), self.literal(denominator))
        difference = self.detached_binary('SUBTRACT', divide, self.literal(expected))
        outer = self.detached_binary('DIVIDE', self.literal(1), difference)
        result = self.place('RESULT'); self.edge(outer, result)
        self.assertEqual([('DIVISION_BY_ZERO', outer, 'rhs')],
                         [(d.code, d.block_id, d.port_key) for d in self.analysis().diagnostics])

    def test_division_positive_positive(self): self.assert_division_quotient(7, 2, 3)

    def test_division_negative_positive(self): self.assert_division_quotient(-7, 2, -3)

    def test_division_positive_negative(self): self.assert_division_quotient(7, -2, -3)

    def test_division_negative_negative(self): self.assert_division_quotient(-7, -2, 3)

    def test_unknown_runtime_denominator_is_not_proven_zero(self):
        op = self.detached_binary('DIVIDE', self.literal(100), self.ref(self.y))
        result = self.place('RESULT'); self.edge(op, result)
        analysis = self.analysis()
        self.assertEqual('100 / y', analysis.expression)
        self.assertTrue(analysis.valid); self.assertTrue(analysis.result_valid)
        self.assertEqual((), analysis.diagnostics)

    def test_max_plus_one_overflow(self):
        self.binary(2**31 - 1, 1, 'ADD')
        self.assertEqual({'INTEGER_OVERFLOW'}, self.codes())
        self.assertFalse(self.analysis().result_valid)

    def test_min_divide_minus_one_overflow(self):
        self.binary(-(2**31), -1, 'DIVIDE')
        self.assertEqual({'INTEGER_OVERFLOW'}, self.codes())
        self.assertFalse(self.analysis().result_valid)

    def test_let_target_mismatch_does_not_change_rhs_validity(self):
        self.binary()
        string = self.create('text', variable_type=VariableType.STRING).declaration
        self.values.set_let_target(self.target, OWNER, self.basis(self.target), self.let, string.declaration_id)
        self.assertEqual({'LET_TYPE_MISMATCH'}, self.codes())
        self.assertFalse(self.analysis().valid)
        self.assertTrue(self.analysis().result_valid)


if __name__ == '__main__': unittest.main()
