package me.buciqua.blockommander.runtime

import java.util.UUID

/** Physical projection identity; never a Python content/workspace identity. */
data class ProjectionKey(
    val hallId: UUID,
    val programId: UUID,
    val workingCopyId: UUID,
    val stageId: UUID,
) {
    val transportContext get() = WorkingCopyTransportContext(hallId, programId, workingCopyId)
}

fun WorkingCopyTransportContext.projectionKey(stageId: UUID) =
    ProjectionKey(hallId, programId, workingCopyId, stageId)

fun Hall.projectionKey(stageId: UUID = UUID.fromString(requireNotNull(stage).id)) =
    transportContext.projectionKey(stageId)

fun Hall.permitsProjection(candidate: Stage): Boolean {
    if (candidate.sizeX<=0 || candidate.sizeY<=0 || candidate.sizeZ<=0) return false
    return candidate.origin.x>min.x && candidate.origin.y>min.y && candidate.origin.z>min.z &&
        candidate.origin.x.toLong()+candidate.sizeX<=max.x.toLong() &&
        candidate.origin.y.toLong()+candidate.sizeY<=max.y.toLong() &&
        candidate.origin.z.toLong()+candidate.sizeZ<=max.z.toLong() &&
        candidate.blocks.all { candidate.contains(it.pos) }
}
