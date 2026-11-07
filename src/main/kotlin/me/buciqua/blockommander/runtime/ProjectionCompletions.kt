package me.buciqua.blockommander.runtime

import com.google.gson.JsonObject
import me.buciqua.blockommander.runtime.wire.WireActionResult
import me.buciqua.blockommander.runtime.wire.WireActionStatus
import java.util.concurrent.Executor

/** The startup list's actual application path, shared by runtime and regression tests. */
class StartupHallProjection(
    private val registry: HallRegistry,
    private val mainThread: Executor,
    private val project: (Hall)->Unit,
) {
    fun begin(worldUuid: String)=registry.bootstrapFence(worldUuid)
    fun apply(fence: BootstrapFence, json: JsonObject) {
        mainThread.execute { registry.applyBootstrap(json,fence,project) }
    }
}

/** No current-state fence may be minted by an obsolete Wire completion. */
class WireProjectionCompletion(
    private val registry: HallRegistry,
    private val mainThread: Executor,
    private val physicalWorld: ()->String,
    private val refreshStatus: (String)->Unit,
) {
    fun complete(fence: WireCompletionFence, result: WireActionResult, accepted: ()->Unit = {}) {
        mainThread.execute {
            if (result.status!=WireActionStatus.STALE) registry.completeWire(fence,physicalWorld()) {
                accepted()
                refreshStatus(fence.stage.hallId)
            }
        }
    }
}
