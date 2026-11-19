package me.buciqua.blockommander.network

import me.buciqua.blockommander.runtime.declarations.DeclarationRow
import me.buciqua.blockommander.runtime.declarations.DeclarationState

/** Presentation windows inside the existing 16-row Python page. Called only by
 * the UI model after its connection/session/sequence checks, never a scope engine. */
class DeclarationViewport {
    private data class Page(val session: EditorSessionKey, val index: Int, val total: Int, val rows: List<DeclarationRow>)
    private var page: Page? = null
    private var resetOperation: Long? = null
    var capacity: Int = 16
        private set
    var offset: Int = 0
        private set
    val pageIndex get() = page?.index ?: 0
    val total get() = page?.total ?: 0
    val pageCount get() = ((total.toLong() + API_PAGE_SIZE - 1) / API_PAGE_SIZE).coerceAtLeast(1)
    val visibleRows: List<DeclarationRow> get() = page?.rows?.drop(offset)?.take(capacity).orEmpty()
    val canUp get() = offset > 0
    val canDown get() = offset + capacity < (page?.rows?.size ?: 0)
    val firstRow: Long get() = if (visibleRows.isEmpty()) 0 else pageIndex.toLong() * API_PAGE_SIZE + offset + 1
    val lastRow: Long get() = if (visibleRows.isEmpty()) 0 else firstRow + visibleRows.size - 1
    val indicator get() = "oldal ${pageIndex.toLong() + 1}/$pageCount | sor $firstRow–$lastRow/$total"

    fun resize(rows: Int) {
        require(rows in 1..API_PAGE_SIZE)
        if (rows != capacity) { capacity = rows; offset = 0 }
    }
    fun up(): Boolean {
        if (!canUp) return false
        offset -= capacity
        return true
    }
    fun down(): Boolean {
        if (!canDown) return false
        offset += capacity
        return true
    }
    fun clear() { page = null; offset = 0; resetOperation = null }
    fun requested(intent: DeclarationIntent) {
        if (intent.action in setOf(DeclarationAction.NEXT, DeclarationAction.PREV, DeclarationAction.REFRESH))
            resetOperation = intent.operation
    }

    /** Progress/error notifications do not replace the last accepted page or
     * consume its scroll position. An explicit read resets only on completion. */
    fun receive(s: DeclarationUiSnapshot): Boolean {
        val resetAcknowledged = resetOperation?.let { s.acknowledgedOperation >= it } == true
        if (s.busy || s.projection != "CURRENT" || s.state !in setOf(
                DeclarationState.READY, DeclarationState.APPLIED_TO_WORKSPACE, DeclarationState.UNKNOWN)) {
            if (!s.busy && resetAcknowledged && (s.projection in setOf("STALE", "FAILED") ||
                    s.state in setOf(DeclarationState.REJECTED, DeclarationState.STALE, DeclarationState.RELOAD_REQUIRED, DeclarationState.CLOSED)))
                resetOperation = null
            return false
        }
        // Preserve the old presentation if a malformed page reaches this layer.
        val remaining = s.total.toLong() - s.page.toLong() * API_PAGE_SIZE
        if (s.page < 0 || s.total < 0 || remaining < 0 || (s.total > 0 && remaining == 0L) ||
            s.rows.size.toLong() != remaining.coerceIn(0, API_PAGE_SIZE.toLong())) return false
        val incoming = Page(s.session, s.page, s.total, s.rows.toList())
        if (incoming != page || resetAcknowledged) offset = 0
        page = incoming
        if (resetAcknowledged) resetOperation = null
        return true
    }

    companion object { private const val API_PAGE_SIZE = 16 }
}
