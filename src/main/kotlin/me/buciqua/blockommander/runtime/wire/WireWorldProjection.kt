package me.buciqua.blockommander.runtime.wire

import me.buciqua.blockommander.CkWireBlock
import me.buciqua.blockommander.CkWireGeometry
import me.buciqua.blockommander.CkWireVisualVariant
import me.buciqua.blockommander.ModContent
import me.buciqua.blockommander.runtime.graph.GraphPosition
import me.buciqua.blockommander.runtime.graph.GraphStateSnapshot
import me.buciqua.blockommander.runtime.WorkingCopyTransportContext
import me.buciqua.blockommander.runtime.projectionKey
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import java.util.UUID

data class WireStageContext(
    val transportContext: WorkingCopyTransportContext,
    val stageId: UUID,
    val origin: GraphPosition,
    val size: GraphPosition,
    val workspaceEpoch: UUID,
    val lifecycleGeneration: Long,
) {
    val key get() = transportContext.projectionKey(stageId)
}

enum class WireWorldCell {
    REPLACEABLE,
    CK_WIRE_UNBOUND,
    CK_WIRE_BOUND_VALID,
    CK_WIRE_BOUND_NEEDS_REPAIR,
    CK_WIRE_GEOMETRY_MISMATCH,
    FOREIGN;

    val isCkWire: Boolean get() = this != REPLACEABLE && this != FOREIGN
}

interface WireWorldAccess {
    fun ckWirePositions(stage: WireStageContext): Set<GraphPosition>
    fun cell(position: GraphPosition): WireWorldCell
    fun geometry(position: GraphPosition): CkWireGeometry?
    fun setWire(position: GraphPosition, geometry: CkWireGeometry, visualState: WireVisualState)
    fun removeWire(position: GraphPosition)
    fun correct(position: GraphPosition)
}

data class WireProjectionIssue(val code: String, val position: GraphPosition?, val message: String)
data class WireProjectionResult(
    val rendered: Set<GraphPosition>,
    val removed: Set<GraphPosition>,
    val issues: List<WireProjectionIssue>,
) {
    val complete: Boolean get() = issues.isEmpty()
}

class WireProjectionPlan internal constructor(
    internal val desired: Map<GraphPosition,PlannedWire>,
    internal val removals: Set<GraphPosition>,
    val issues: List<WireProjectionIssue>,
)
internal data class PlannedWire(val geometry: CkWireGeometry, val visual: WireVisualState)

class WireWorldProjector {
    fun prepare(
        stage: WireStageContext,
        authoritativeRevision: Long,
        snapshot: GraphStateSnapshot,
        world: WireWorldAccess,
    ): WireProjectionPlan {
        if (snapshot.stageId != stage.stageId || snapshot.workspaceEpoch != stage.workspaceEpoch ||
            snapshot.workingRevision != authoritativeRevision) {
            return WireProjectionPlan(emptyMap(), emptySet(), listOf(
                WireProjectionIssue("STALE_WORLD_PROJECTION", null, "A graph snapshot nem az aktuális Stage lifecycle-hoz tartozik."),
            ))
        }

        val issues = mutableListOf<WireProjectionIssue>()
        val desired = linkedMapOf<GraphPosition, PlannedWire>()
        val visualStates = WireVisualProjection.states(snapshot)
        snapshot.wireCells.forEach { cell ->
            val relative = cell.position
            val inside = relative.x in 0 until stage.size.x && relative.y in 0 until stage.size.y &&
                relative.z in 0 until stage.size.z
            val geometry = cell.geometry()
            val absolute = relative + stage.origin
            when {
                !inside -> issues += WireProjectionIssue(
                    "WIRE_OUTSIDE_STAGE", absolute, "A wire snapshot cella a Stage területén kívül van.",
                )
                geometry == null -> issues += WireProjectionIssue(
                    "UNSUPPORTED_WIRE_GEOMETRY", absolute,
                    "A WireCell csatornái nem képeznek támogatott SINGLE vagy elkülönített CROSS geometriát.",
                )
                desired.containsKey(absolute) -> issues += WireProjectionIssue(
                    "DUPLICATE_WIRE_POSITION", absolute, "A snapshot ugyanazt a WireCell pozíciót többször tartalmazza.",
                )
                else -> desired[absolute] = PlannedWire(geometry, visualStates[relative] ?: WireVisualState.UNKNOWN)
            }
        }

        desired.keys.forEach { position ->
            if (world.cell(position) == WireWorldCell.FOREIGN) {
                issues += WireProjectionIssue(
                    "FOREIGN_BLOCK_AT_WIRE_POSITION", position, "A CK Wire nem írhat felül idegen blokkot.",
                )
            }
        }
        // A teljes snapshot preflightja megelőz minden világírást, így hibás geometriából vagy
        // idegen blokkal ütköző snapshotból nem maradhat részlegesen frissített projekció.
        val removals=(world.ckWirePositions(stage)-desired.keys).filter { position ->
            position.x.toLong()-stage.origin.x in 0L until stage.size.x.toLong() &&
                position.y.toLong()-stage.origin.y in 0L until stage.size.y.toLong() &&
                position.z.toLong()-stage.origin.z in 0L until stage.size.z.toLong()
        }.toSet()
        return WireProjectionPlan(desired.toMap(),removals,issues.toList())
    }

    fun apply(
        stage:WireStageContext, authoritativeRevision:Long, snapshot:GraphStateSnapshot, world:WireWorldAccess,
    ) = applyPrepared(prepare(stage,authoritativeRevision,snapshot,world),world)

    fun applyPrepared(plan:WireProjectionPlan, world:WireWorldAccess):WireProjectionResult {
        if (plan.issues.isNotEmpty()) return WireProjectionResult(emptySet(),emptySet(),plan.issues)

        val rendered = linkedSetOf<GraphPosition>()
        plan.desired.forEach { (position, desiredWire) ->
            val expected = desiredWire.visual.worldCell()
            when {
                world.cell(position) == expected && world.geometry(position) == desiredWire.geometry -> rendered += position
                world.cell(position) == WireWorldCell.REPLACEABLE -> {
                    world.setWire(position, desiredWire.geometry, desiredWire.visual)
                    rendered += position
                }
                world.cell(position).isCkWire -> {
                    world.setWire(position, desiredWire.geometry, desiredWire.visual)
                    rendered += position
                }
                else -> error("A preflight után nem jelenhet meg idegen blokk a célpozíción.")
            }
        }

        val removed = linkedSetOf<GraphPosition>()
        plan.removals.forEach { position ->
            if (world.cell(position).isCkWire) {
                world.removeWire(position)
                removed += position
            }
        }
        return WireProjectionResult(rendered, removed, emptyList())
    }
}

class MinecraftWireWorldAccess(private val level: ServerLevel) : WireWorldAccess {
    override fun ckWirePositions(stage: WireStageContext): Set<GraphPosition> = buildSet {
        for (x in 0 until stage.size.x) for (y in 0 until stage.size.y) for (z in 0 until stage.size.z) {
            val position = stage.origin + GraphPosition(x, y, z)
            if (level.getBlockState(position.blockPos()).block === ModContent.CK_WIRE) add(position)
        }
    }

    override fun cell(position: GraphPosition): WireWorldCell {
        val state = level.getBlockState(position.blockPos())
        return when {
            state.block === ModContent.CK_WIRE -> when (state.getValue(CkWireBlock.VISUAL)) {
                CkWireVisualVariant.CYAN -> WireWorldCell.CK_WIRE_UNBOUND
                CkWireVisualVariant.GREEN -> WireWorldCell.CK_WIRE_BOUND_VALID
                CkWireVisualVariant.ORANGE -> WireWorldCell.CK_WIRE_BOUND_NEEDS_REPAIR
            }
            state.isAir -> WireWorldCell.REPLACEABLE
            else -> WireWorldCell.FOREIGN
        }
    }

    override fun geometry(position: GraphPosition): CkWireGeometry? {
        val state = level.getBlockState(position.blockPos())
        return if (state.block === ModContent.CK_WIRE) state.getValue(CkWireBlock.GEOMETRY) else null
    }

    override fun setWire(position: GraphPosition, geometry: CkWireGeometry, visualState: WireVisualState) {
        val variant = when (visualState) {
            WireVisualState.UNBOUND -> CkWireVisualVariant.CYAN
            WireVisualState.BOUND_VALID -> CkWireVisualVariant.GREEN
            WireVisualState.BOUND_NEEDS_REPAIR, WireVisualState.UNKNOWN -> CkWireVisualVariant.ORANGE
        }
        val changed = level.setBlockAndUpdate(
            position.blockPos(), ModContent.CK_WIRE.defaultBlockState()
                .setValue(CkWireBlock.GEOMETRY, geometry)
                .setValue(CkWireBlock.VISUAL, variant),
        )
        check(changed) { "A CK Wire világvetítés nem írható ki." }
    }

    override fun removeWire(position: GraphPosition) {
        val pos = position.blockPos()
        if (level.getBlockState(pos).block === ModContent.CK_WIRE) {
            check(level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())) { "A CK Wire világvetítés nem távolítható el." }
        }
    }

    override fun correct(position: GraphPosition) {
        val pos = position.blockPos()
        val state = level.getBlockState(pos)
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL)
    }
}

operator fun GraphPosition.plus(other: GraphPosition) = GraphPosition(x + other.x, y + other.y, z + other.z)
operator fun GraphPosition.minus(other: GraphPosition) = GraphPosition(x - other.x, y - other.y, z - other.z)
fun GraphPosition.blockPos() = BlockPos(x, y, z)

private fun WireVisualState.worldCell(): WireWorldCell = when (this) {
    WireVisualState.UNBOUND -> WireWorldCell.CK_WIRE_UNBOUND
    WireVisualState.BOUND_VALID -> WireWorldCell.CK_WIRE_BOUND_VALID
    WireVisualState.BOUND_NEEDS_REPAIR -> WireWorldCell.CK_WIRE_BOUND_NEEDS_REPAIR
    WireVisualState.UNKNOWN -> WireWorldCell.CK_WIRE_BOUND_NEEDS_REPAIR
}

private fun me.buciqua.blockommander.runtime.graph.GraphWireCell.geometry(): CkWireGeometry? {
    if (channels.size == 1 && channels.single().channel == "SINGLE") {
        val connections = channels.single().connections
        if (connections.size != connections.toSet().size || connections.size > 2) return null
        return when (connections.toSet()) {
            emptySet<String>() -> CkWireGeometry.NONE
            setOf("NORTH") -> CkWireGeometry.NORTH
            setOf("EAST") -> CkWireGeometry.EAST
            setOf("SOUTH") -> CkWireGeometry.SOUTH
            setOf("WEST") -> CkWireGeometry.WEST
            setOf("NORTH", "SOUTH") -> CkWireGeometry.NORTH_SOUTH
            setOf("EAST", "WEST") -> CkWireGeometry.EAST_WEST
            setOf("NORTH", "EAST") -> CkWireGeometry.NORTH_EAST
            setOf("EAST", "SOUTH") -> CkWireGeometry.EAST_SOUTH
            setOf("SOUTH", "WEST") -> CkWireGeometry.SOUTH_WEST
            setOf("WEST", "NORTH") -> CkWireGeometry.WEST_NORTH
            else -> null
        }
    }
    if (channels.size == 2 && channels.map { it.channel }.toSet() == setOf("NS", "EW")) {
        val ns = channels.single { it.channel == "NS" }.connections
        val ew = channels.single { it.channel == "EW" }.connections
        if (ns.size == 2 && ns.toSet() == setOf("NORTH", "SOUTH") &&
            ew.size == 2 && ew.toSet() == setOf("EAST", "WEST")) return CkWireGeometry.CROSS
    }
    return null
}
