"""Pure, deterministic VALUE analysis. Diagnostics never gate persistence."""
from collections import defaultdict
from heapq import heappush, heappop
from uuid import UUID
from .graph.model import EdgeKind
from .graph.snapshot import thaw_json
from .value_model import OPERATORS, ValueAnalysis, ValueDiagnostic
from .value_ports import VALUE_PORT_CATALOG


def analyze_value(snapshot, resolution, *, target_id=None, owner_id=None):
    blocks = {b.instance_id: b for b in snapshot.blocks}
    diagnostics = []
    def issue(code, bid=None, port=None, message=""):
        diagnostics.append(ValueDiagnostic(code, bid, port, message))
    symbols = {s.declaration.declaration_id: s.declaration for s in (*resolution.visible, *resolution.shadowed)}
    incoming = defaultdict(list); outgoing = defaultdict(list)
    indegree = {bid: 0 for bid in blocks}
    edges = sorted(snapshot.edges, key=lambda e: e.edge_id.int)
    dependencies = defaultdict(list)
    for edge in edges:
        source = blocks.get(edge.source_instance_id); dest = blocks.get(edge.target_instance_id)
        if not source or not dest:
            issue("BROKEN_EDGE", edge.target_instance_id, edge.target_port_key); continue
        sp = next((p for p in VALUE_PORT_CATALOG.get(source.type_key, ()) if p.port_key == edge.source_port_key), None)
        tp = next((p for p in VALUE_PORT_CATALOG.get(dest.type_key, ()) if p.port_key == edge.target_port_key), None)
        if edge.edge_kind != EdgeKind.VALUE or not sp or not tp or sp.direction.value != "OUT" or tp.direction.value != "IN":
            issue("INVALID_VALUE_EDGE", dest.instance_id, edge.target_port_key); continue
        incoming[(dest.instance_id, edge.target_port_key)].append(edge)
        outgoing[source.instance_id].append(edge)
        dependencies[dest.instance_id].append(source.instance_id)
        indegree[dest.instance_id] += 1
    for (bid, key), items in sorted(incoming.items(), key=lambda x: (x[0][0].int, x[0][1])):
        if len(items) > 1: issue("MULTIPLE_OPERANDS", bid, key)
    for bid in sorted(blocks, key=lambda x: x.int):
        if len(outgoing[bid]) > 1: issue("VALUE_FANOUT", bid, "out")
    queue = []
    for bid, degree in indegree.items():
        if degree == 0: heappush(queue, bid)
    order = []
    while queue:
        bid = heappop(queue); order.append(bid)
        for edge in outgoing[bid]:
            child = edge.target_instance_id; indegree[child] -= 1
            if indegree[child] == 0: heappush(queue, child)
    # Kahn's residual also contains downstream consumers. Identify actual cycle
    # members separately, so a RESULT depending on a cycle is not called cyclic.
    residual = set(blocks) - set(order)
    colors = {}; cycle_nodes = set()
    for start in sorted(residual, key=lambda x: x.int):
        if colors.get(start): continue
        path = [start]; indices = {start: 0}; colors[start] = 1
        stack = [(start, iter(e.target_instance_id for e in outgoing[start]))]
        while stack:
            bid, children = stack[-1]
            child = next(children, None)
            if child is None:
                colors[bid] = 2; stack.pop(); path.pop(); indices.pop(bid)
            elif colors.get(child) == 1:
                cycle_nodes.update(path[indices[child]:])
            elif child in residual and not colors.get(child):
                colors[child] = 1; indices[child] = len(path); path.append(child)
                stack.append((child, iter(e.target_instance_id for e in outgoing[child])))
    for bid in sorted(residual, key=lambda x: x.int):
        issue("VALUE_CYCLE" if bid in cycle_nodes else "DEPENDENCY_CYCLE", bid)
    results = sorted((b.instance_id for b in blocks.values() if b.type_key == "RESULT"), key=lambda x: x.int)
    if len(results) != 1: issue("MISSING_RESULT" if not results else "MULTIPLE_RESULTS")
    reachable = set(); pending = list(results)
    while pending:
        bid = pending.pop()
        if bid in reachable: continue
        reachable.add(bid)
        pending.extend(dependencies[bid])
    types = {}; text = {}; constants = {}
    for bid in order:
        block = blocks[bid]; config = thaw_json(block.semantic_config)
        if block.type_key not in VALUE_PORT_CATALOG:
            issue("INVALID_VALUE_BLOCK", bid); continue
        if block.type_key != "RESULT" and not config:
            issue("UNCONFIGURED_BLOCK", bid); continue
        if block.type_key == "LITERAL":
            types[bid] = "INTEGER"; constants[bid] = config["value"]; text[bid] = str(config["value"])
        elif block.type_key == "REF":
            declaration = symbols.get(UUID(config["target_id"]))
            if declaration is None:
                issue("BROKEN_REF", bid); text[bid] = "<missing:" + config["target_id"] + ">"
            else:
                types[bid] = declaration.variable_type.value; text[bid] = declaration.name
        else:
            keys = ("lhs", "rhs") if block.type_key == "OPERATOR" else ("in",)
            sources = []
            for key in keys:
                items = incoming[(bid, key)]
                if len(items) != 1:
                    if not items: issue("MISSING_OPERAND", bid, key)
                    sources.append(None)
                else: sources.append(items[0].source_instance_id)
            if any(source not in types for source in sources): continue
            if block.type_key == "RESULT":
                source = sources[0]; types[bid] = types[source]; text[bid] = text[source]
                if source in constants: constants[bid] = constants[source]
                continue
            for key, source in zip(keys, sources):
                if types[source] != "INTEGER": issue("TYPE_MISMATCH", bid, key)
            if any(types[source] != "INTEGER" for source in sources): continue
            lhs, rhs = sources; operation = config["operation"]
            types[bid] = "INTEGER"
            text[bid] = f"({text[lhs]} {OPERATORS[operation]} {text[rhs]})"
            if operation == "DIVIDE" and constants.get(rhs) == 0:
                issue("DIVISION_BY_ZERO", bid, "rhs"); continue
            if lhs in constants and rhs in constants:
                a, b = constants[lhs], constants[rhs]
                if operation == "ADD": value = a + b
                elif operation == "SUBTRACT": value = a - b
                elif operation == "MULTIPLY": value = a * b
                else: value = (abs(a) // abs(b)) * (-1 if (a < 0) != (b < 0) else 1)
                if not -(2**31) <= value < 2**31: issue("INTEGER_OVERFLOW", bid)
                else: constants[bid] = value
    result = results[0] if len(results) == 1 else None
    if target_id is not None:
        target = symbols.get(target_id)
        if target is None: issue("BROKEN_LET_TARGET", owner_id)
        elif result in types and target.variable_type.value != types[result]: issue("LET_TYPE_MISMATCH", owner_id)
    expression = text.get(result)
    if expression and expression.startswith("(") and blocks[result].type_key == "RESULT":
        expression = expression[1:-1]
    return ValueAnalysis(tuple(diagnostics), tuple(bid for bid in order if bid in reachable),
                         expression, types.get(result), resolution.basis)
