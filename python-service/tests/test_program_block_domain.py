import unittest
import uuid

from hangar.graph import (
    CK_CONTROL_PORT_CATALOG,
    CardinalDirection,
    DiagnosticCode,
    EdgeKind,
    GraphEdge,
    GraphNode,
    GraphWorkspaceState,
    GridPos,
)
from hangar.graph.discovery import PhysicalComponentStatus, discover_physical_topology
from hangar.graph.model import WireCell, WireChannel, WireChannelState
from hangar.graph.validation import validate_graph


class ProgramBlockGraphDomainTest(unittest.TestCase):
    def setUp(self):
        self.stage_id=uuid.UUID("40000000-0000-0000-0000-000000000001")
        self.program_id=uuid.UUID("40000000-0000-0000-0000-000000000002")
        self.stop_id=uuid.UUID("40000000-0000-0000-0000-000000000003")

    def state(self,*,edges=(),wires=()):
        return GraphWorkspaceState(
            self.stage_id,
            (
                GraphNode(self.program_id,self.stage_id,"PROGRAM",GridPos(0,0,0),CardinalDirection.EAST),
                GraphNode(self.stop_id,self.stage_id,"STOP",GridPos(2,0,0),CardinalDirection.EAST),
            ),
            tuple(edges),tuple(wires),(),0,None,0,True,None,
        )

    def test_program_graph_edge_is_rejected_as_unknown_port(self):
        edge=GraphEdge(
            uuid.uuid4(),self.stage_id,self.program_id,"out",self.stop_id,"in",EdgeKind.CONTROL,
        )
        report=validate_graph(self.state(edges=(edge,)),CK_CONTROL_PORT_CATALOG)
        program_issues=[item for item in report.diagnostics
            if item.code is DiagnosticCode.UNKNOWN_PORT and self.program_id in item.node_ids]
        self.assertEqual(len(program_issues),1)

    def test_wire_next_to_program_never_becomes_a_program_endpoint(self):
        wire=WireCell(
            GridPos(1,0,0),
            (WireChannelState(WireChannel.SINGLE,frozenset({CardinalDirection.WEST})),),
        )
        discovery=discover_physical_topology(self.state(wires=(wire,)),CK_CONTROL_PORT_CATALOG)
        self.assertEqual(discovery.candidates,())
        self.assertEqual(discovery.components[0].ports,())
        self.assertEqual(discovery.components[0].status,PhysicalComponentStatus.INCOMPLETE)


if __name__=="__main__": unittest.main()
