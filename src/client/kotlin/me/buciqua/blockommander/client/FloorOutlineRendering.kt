package me.buciqua.blockommander.client

import me.buciqua.blockommander.network.FloorOutlineClientState
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

internal object FloorOutlineRendering {
    private val targetKey = RenderStateDataKey.create<BlockPos> { "blockommander floor outline target" }
    private const val AMBER = 0xFFFFB347.toInt()
    private const val LINE_WIDTH = 2.0f

    fun register(floor: FloorOutlineClientState) {
        LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION.register { context, hit ->
            val state = context.levelState()
            val data = state as FabricRenderState
            data.setData(targetKey, null)
            if (hit !is BlockHitResult || hit.type != HitResult.Type.BLOCK) return@register
            val outline = state.blockOutlineRenderState ?: return@register
            if (outline.highContrast() || outline.shape().isEmpty || outline.pos() != hit.blockPos) return@register
            val level = context.level()
            val view = floor.view(level) ?: return@register
            val pos = hit.blockPos
            val camera = context.camera().entity() ?: return@register
            val block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block)
            if (view.highlights(pos, block, camera.blockPosition())) data.setData(targetKey, pos)
        }

        LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register { context, outline ->
            val state = context.levelState()
            val marked = (state as FabricRenderState).getData(targetKey)
            if (marked != outline.pos() || outline.highContrast() || outline.shape().isEmpty) return@register true

            val pos = outline.pos()
            val camera = state.cameraRenderState.pos
            val pose = context.poseStack()
            pose.pushPose()
            try {
                pose.translate(pos.x - camera.x, pos.y - camera.y, pos.z - camera.z)
                context.submitNodeCollector().submitShapeOutline(
                    pose, outline.shape(), RenderTypes.linesDepthBias(), AMBER, LINE_WIDTH, outline.isTranslucent(),
                )
                false
            } catch (_: RuntimeException) {
                true
            } finally {
                pose.popPose()
            }
        }
    }
}
