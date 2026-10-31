package me.buciqua.blockommander

import net.minecraft.core.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CkProgramPortOrientationTest {
    private val horizontal = listOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)

    @Test fun `START FRONT port faces the placing player in all four directions`() {
        horizontal.forEach { playerDirection ->
            val blockFacing = CkProgramPortFace.FRONT.facingForPlacement(playerDirection)
            assertEquals(playerDirection.opposite, blockFacing)
            assertEquals(playerDirection.opposite, CkProgramPortFace.FRONT.worldPortDirection(blockFacing))
        }
    }

    @Test fun `STOP BACK port faces the placing player in all four directions`() {
        horizontal.forEach { playerDirection ->
            val blockFacing = CkProgramPortFace.BACK.facingForPlacement(playerDirection)
            assertEquals(playerDirection, blockFacing)
            assertEquals(playerDirection.opposite, CkProgramPortFace.BACK.worldPortDirection(blockFacing))
        }
    }

    @Test fun `IF FRONT input and LEFT 0 RIGHT 1 outputs rotate with placement`() {
        horizontal.forEach { playerDirection ->
            val blockFacing = CkProgramPortFace.FRONT.facingForPlacement(playerDirection)
            assertEquals(playerDirection.opposite, blockFacing)
            assertEquals(playerDirection.opposite, CkProgramPortFace.FRONT.worldPortDirection(blockFacing))
            assertEquals(rightOf(blockFacing), CkProgramPortFace.RIGHT.worldPortDirection(blockFacing))
            assertEquals(leftOf(blockFacing), CkProgramPortFace.LEFT.worldPortDirection(blockFacing))
            assertEquals(playerDirection, CkProgramPortFace.BACK.worldPortDirection(blockFacing))
        }
    }

    @Test fun `stored orientation keeps the existing FRONT and BACK port contract`() {
        horizontal.forEach { storedFacing ->
            assertEquals(storedFacing, CkProgramPortFace.FRONT.worldPortDirection(storedFacing))
            assertEquals(storedFacing.opposite, CkProgramPortFace.BACK.worldPortDirection(storedFacing))
        }
    }

    private fun rightOf(direction: Direction): Direction = when (direction) {
        Direction.NORTH -> Direction.EAST
        Direction.EAST -> Direction.SOUTH
        Direction.SOUTH -> Direction.WEST
        Direction.WEST -> Direction.NORTH
        else -> direction
    }

    private fun leftOf(direction: Direction): Direction = rightOf(direction.opposite)
}
