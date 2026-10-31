package me.buciqua.blockommander

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab
import net.minecraft.core.Direction
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour

object ModContent {
    private fun programBlock(
        path: String,
        sound: SoundType,
        dominantPortFace: CkProgramPortFace = CkProgramPortFace.NONE,
    ): Block {
        val blockKey = ResourceKey.create(BuiltInRegistries.BLOCK.key(), BlockCommanderMod.id(path))
        val itemKey = ResourceKey.create(BuiltInRegistries.ITEM.key(), BlockCommanderMod.id(path))
        val block = Registry.register(BuiltInRegistries.BLOCK, blockKey,
            CkProgramBlock(
                BlockBehaviour.Properties.of().strength(1.5f).sound(sound).setId(blockKey),
                dominantPortFace,
            ))
        Registry.register(BuiltInRegistries.ITEM, itemKey,
            BlockItem(block, Item.Properties().useBlockDescriptionPrefix().setId(itemKey)))
        return block
    }

    private fun wire(): Pair<Block, Item> {
        val blockKey = ResourceKey.create(BuiltInRegistries.BLOCK.key(), BlockCommanderMod.id("ck_wire"))
        val itemKey = ResourceKey.create(BuiltInRegistries.ITEM.key(), BlockCommanderMod.id("ck_wire"))
        val block = Registry.register(BuiltInRegistries.BLOCK, blockKey,
            CkWireBlock(BlockBehaviour.Properties.of().strength(0.6f).sound(SoundType.COPPER)
                .noOcclusion().setId(blockKey)))
        val item = Registry.register(BuiltInRegistries.ITEM, itemKey,
            BlockItem(block, Item.Properties().useBlockDescriptionPrefix().setId(itemKey)))
        return block to item
    }

    private fun item(path: String): Item {
        val key = ResourceKey.create(BuiltInRegistries.ITEM.key(), BlockCommanderMod.id(path))
        return Registry.register(BuiltInRegistries.ITEM, key, Item(Item.Properties().stacksTo(1).setId(key)))
    }

    val START: Block = programBlock("start", SoundType.GLASS, CkProgramPortFace.FRONT)
    val STOP: Block = programBlock("stop", SoundType.GLASS, CkProgramPortFace.BACK)
    val IF: Block = programBlock("if", SoundType.GLASS, CkProgramPortFace.FRONT)
    val FOR: Block = programBlock("for", SoundType.GLASS)
    val FUN: Block = programBlock("fun", SoundType.GLASS)
    private val wireContent = wire()
    val CK_WIRE: Block = wireContent.first
    val CK_WIRE_ITEM: Item = wireContent.second
    val HANGAR_SWORD: Item = item("hangar_sword")

    private val tabKey = ResourceKey.create(BuiltInRegistries.CREATIVE_MODE_TAB.key(), BlockCommanderMod.id("ck"))
    private val tab: CreativeModeTab = FabricCreativeModeTab.builder()
        .icon { ItemStack(HANGAR_SWORD) }
        .title(Component.literal("bloCKommander"))
        .displayItems { _, output ->
            output.accept(START); output.accept(STOP); output.accept(IF)
            output.accept(FOR); output.accept(FUN); output.accept(CK_WIRE); output.accept(HANGAR_SWORD)
        }.build()

    fun initialize() { Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, tabKey, tab) }

    fun typeOf(block: Block): String? = when (block) {
        START -> "START"; STOP -> "STOP"; IF -> "IF"; FOR -> "FOR"; FUN -> "FUN"; else -> null
    }

    fun placementFacing(type: String, playerDirection: Direction): Direction = when (type) {
        "START" -> (START as CkProgramBlock).facingForPlacement(playerDirection)
        "STOP" -> (STOP as CkProgramBlock).facingForPlacement(playerDirection)
        "IF" -> (IF as CkProgramBlock).facingForPlacement(playerDirection)
        else -> playerDirection
    }
}
