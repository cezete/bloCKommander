package me.buciqua.blockommander.runtime

import net.minecraft.server.level.ServerLevel
import java.nio.charset.StandardCharsets
import java.util.UUID

object WorldIdentity {
    fun name(level: ServerLevel): String = level.server.worldData.levelName
    fun dimension(level: ServerLevel): String = level.dimension().identifier().toString()
    fun uuid(level: ServerLevel): UUID = UUID.nameUUIDFromBytes(
        "blockommander:${name(level)}:${dimension(level)}".toByteArray(StandardCharsets.UTF_8)
    )
}
