package me.buciqua.blockommander.network

import me.buciqua.blockommander.BlockCommanderMod
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

data class OpenFormPayload(val kind: String, val contextId: String, val value1: String, val value2: String) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenFormPayload>(BlockCommanderMod.id("open_form"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenFormPayload> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, OpenFormPayload::kind,
            ByteBufCodecs.STRING_UTF8, OpenFormPayload::contextId,
            ByteBufCodecs.STRING_UTF8, OpenFormPayload::value1,
            ByteBufCodecs.STRING_UTF8, OpenFormPayload::value2,
            ::OpenFormPayload)
    }
}

data class SubmitFormPayload(val kind: String, val contextId: String, val value1: String, val value2: String) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<SubmitFormPayload>(BlockCommanderMod.id("submit_form"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SubmitFormPayload> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SubmitFormPayload::kind,
            ByteBufCodecs.STRING_UTF8, SubmitFormPayload::contextId,
            ByteBufCodecs.STRING_UTF8, SubmitFormPayload::value1,
            ByteBufCodecs.STRING_UTF8, SubmitFormPayload::value2,
            ::SubmitFormPayload)
    }
}
