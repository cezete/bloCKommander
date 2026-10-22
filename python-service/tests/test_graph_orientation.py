import unittest
from uuid import UUID

from hangar.graph.model import (
    CardinalDirection, GraphNode, GridPos, LocalSide, PortDefinition,
    PortDirection, PortKind,
)
from hangar.graph.orientation import adjacent_position, port_connection_position, world_side


NODE_ID = UUID("00000000-0000-0000-0000-000000000101")
STAGE_ID = UUID("00000000-0000-0000-0000-000000000102")


class GraphOrientationTest(unittest.TestCase):
    def test_full_local_to_world_rotation_matrix(self):
        expected = {
            CardinalDirection.NORTH: {
                LocalSide.FRONT: CardinalDirection.NORTH,
                LocalSide.RIGHT: CardinalDirection.EAST,
                LocalSide.BACK: CardinalDirection.SOUTH,
                LocalSide.LEFT: CardinalDirection.WEST,
            },
            CardinalDirection.EAST: {
                LocalSide.FRONT: CardinalDirection.EAST,
                LocalSide.RIGHT: CardinalDirection.SOUTH,
                LocalSide.BACK: CardinalDirection.WEST,
                LocalSide.LEFT: CardinalDirection.NORTH,
            },
            CardinalDirection.SOUTH: {
                LocalSide.FRONT: CardinalDirection.SOUTH,
                LocalSide.RIGHT: CardinalDirection.WEST,
                LocalSide.BACK: CardinalDirection.NORTH,
                LocalSide.LEFT: CardinalDirection.EAST,
            },
            CardinalDirection.WEST: {
                LocalSide.FRONT: CardinalDirection.WEST,
                LocalSide.RIGHT: CardinalDirection.NORTH,
                LocalSide.BACK: CardinalDirection.EAST,
                LocalSide.LEFT: CardinalDirection.SOUTH,
            },
        }
        for facing, local_sides in expected.items():
            for local_side, result in local_sides.items():
                with self.subTest(facing=facing, local_side=local_side):
                    self.assertEqual(world_side(facing, local_side), result)

    def test_north_neighbor(self):
        self.assertEqual(
            adjacent_position(GridPos(10, 20, 30), CardinalDirection.NORTH),
            GridPos(10, 20, 29),
        )

    def test_east_neighbor(self):
        self.assertEqual(
            adjacent_position(GridPos(10, 20, 30), CardinalDirection.EAST),
            GridPos(11, 20, 30),
        )

    def test_south_neighbor(self):
        self.assertEqual(
            adjacent_position(GridPos(10, 20, 30), CardinalDirection.SOUTH),
            GridPos(10, 20, 31),
        )

    def test_west_neighbor(self):
        self.assertEqual(
            adjacent_position(GridPos(10, 20, 30), CardinalDirection.WEST),
            GridPos(9, 20, 30),
        )

    def test_port_connection_position_for_every_rotation(self):
        definition = self._port_definition()
        expected = {
            CardinalDirection.NORTH: GridPos(11, 20, 30),
            CardinalDirection.EAST: GridPos(10, 20, 31),
            CardinalDirection.SOUTH: GridPos(9, 20, 30),
            CardinalDirection.WEST: GridPos(10, 20, 29),
        }
        for facing, result in expected.items():
            node = GraphNode(NODE_ID, STAGE_ID, "if_node", GridPos(10, 20, 30), facing)
            with self.subTest(facing=facing):
                self.assertEqual(port_connection_position(node, definition), result)

    def test_if_true_port_key_is_stable_across_rotations(self):
        definition = self._port_definition()
        for facing in CardinalDirection:
            node = GraphNode(NODE_ID, STAGE_ID, "if_node", GridPos(10, 20, 30), facing)
            with self.subTest(facing=facing):
                port_connection_position(node, definition)
                self.assertEqual(definition.port_key, "true_branch")

    def test_transforms_do_not_mutate_inputs(self):
        position = GridPos(10, 20, 30)
        definition = PortDefinition(
            "control_in", PortKind.CONTROL, PortDirection.IN,
            LocalSide.FRONT, 0, 1, 1,
        )
        node = GraphNode(
            NODE_ID, STAGE_ID, "command_node", position, CardinalDirection.WEST,
        )
        original_node = node
        original_definition = definition

        port_connection_position(node, definition)

        self.assertEqual(node, original_node)
        self.assertEqual(definition, original_definition)
        self.assertEqual(position, GridPos(10, 20, 30))

    @staticmethod
    def _port_definition():
        return PortDefinition(
            port_key="true_branch",
            port_kind=PortKind.CONTROL,
            direction=PortDirection.OUT,
            local_side=LocalSide.RIGHT,
            ordinal=0,
            min_edges=0,
            max_edges=1,
            branch_label="true",
        )


if __name__ == "__main__":
    unittest.main()
