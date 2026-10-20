package me.buciqua.blockommander

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab
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
    private fun block(path: String, sound: SoundType): Block {
        val blockKey = ResourceKey.create(BuiltInRegistries.BLOCK.key(), BlockCommanderMod.id(path))
        val itemKey = ResourceKey.create(BuiltInRegistries.ITEM.key(), BlockCommanderMod.id(path))
        val block = Registry.register(BuiltInRegistries.BLOCK, blockKey,
            CkProgramBlock(BlockBehaviour.Properties.of().strength(1.5f).sound(sound).setId(blockKey)))
        Registry.register(BuiltInRegistries.ITEM, itemKey,
            BlockItem(block, Item.Properties().useBlockDescriptionPrefix().setId(itemKey)))
        return block
    }

    private fun item(path: String): Item {
        val key = ResourceKey.create(BuiltInRegistries.ITEM.key(), BlockCommanderMod.id(path))
        return Registry.register(BuiltInRegistries.ITEM, key, Item(Item.Properties().stacksTo(1).setId(key)))
    }

    val START: Block = block("start", SoundType.GLASS)
    val STOP: Block = block("stop", SoundType.GLASS)
    val IF: Block = block("if", SoundType.GLASS)
    val FOR: Block = block("for", SoundType.GLASS)
    val FUN: Block = block("fun", SoundType.GLASS)
    val HANGAR_SWORD: Item = item("hangar_sword")

    private val tabKey = ResourceKey.create(BuiltInRegistries.CREATIVE_MODE_TAB.key(), BlockCommanderMod.id("ck"))
    private val tab: CreativeModeTab = FabricCreativeModeTab.builder()
        .icon { ItemStack(START) }
        .title(Component.translatable("creativeTab.blockommander"))
        .displayItems { _, output ->
            output.accept(START); output.accept(STOP); output.accept(IF)
            output.accept(FOR); output.accept(FUN); output.accept(HANGAR_SWORD)
        }.build()

    fun initialize() { Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, tabKey, tab) }

    fun typeOf(block: Block): String? = when (block) {
        START -> "START"; STOP -> "STOP"; IF -> "IF"; FOR -> "FOR"; FUN -> "FUN"; else -> null
    }
}
