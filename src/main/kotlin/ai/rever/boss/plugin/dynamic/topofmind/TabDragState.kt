package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Dragging a tab row onto a workspace header, and the highlight left behind after a move.
 *
 * Panel-scoped, one instance per mounted panel (see [TopofmindComponent]). Deliberately NOT a
 * top-level object: two windows each showing this panel would otherwise share one drag, so
 * picking up a tab in one would light up a drop target in the other.
 *
 * Coordinates are window-relative throughout. A row's pointer events arrive local to that row, so
 * the row adds its own [Rect.topLeft] before reporting - the drop targets are in another part of
 * the tree entirely and there is no common parent to measure against.
 */
class TabDragState {
    /** The tab currently being dragged, or null. */
    var dragging: ActiveTabData? by mutableStateOf(null)
        private set

    /** Pointer position in window coordinates while [dragging] is non-null. */
    var pointer: Offset by mutableStateOf(Offset.Unspecified)
        private set

    /** Workspace id -> that workspace header's bounds, in window coordinates. */
    private val dropTargets = mutableStateMapOf<String, Rect>()

    /**
     * The tab that most recently landed somewhere, highlighted so the move is visible when the row
     * reappears under a different workspace. Cleared on a timer by the panel.
     */
    var recentlyMovedTabId: String? by mutableStateOf(null)

    /** Register (or re-register) a workspace header as a drop target. */
    fun registerTarget(
        workspaceId: String,
        bounds: Rect,
    ) {
        dropTargets[workspaceId] = bounds
    }

    /**
     * Forget a header that has left composition.
     *
     * Guarded on the bounds still being the ones that header reported. A collapsing group disposes
     * its old row after the replacement has already registered, so an unguarded removal drops a
     * live target and the workspace silently stops accepting drops.
     */
    fun unregisterTarget(
        workspaceId: String,
        bounds: Rect,
    ) {
        if (dropTargets[workspaceId] == bounds) dropTargets.remove(workspaceId)
    }

    fun startDrag(
        tab: ActiveTabData,
        windowPosition: Offset,
    ) {
        dragging = tab
        pointer = windowPosition
    }

    fun updateDrag(windowPosition: Offset) {
        if (dragging != null) pointer = windowPosition
    }

    /**
     * The workspace the pointer is over, or null.
     *
     * Never the tab's own workspace: a tab cannot be moved to where it already is, and lighting up
     * that header would promise a drop that is refused.
     */
    val hoveredWorkspaceId: String?
        get() {
            val tab = dragging ?: return null
            val at = pointer.takeIf { it != Offset.Unspecified } ?: return null
            return dropTargets.entries
                .firstOrNull { (id, bounds) -> id != tab.workspaceId && bounds.contains(at) }
                ?.key
        }

    /**
     * End the drag and report where it landed, or null if it landed nowhere.
     *
     * Reads [hoveredWorkspaceId] before clearing, so the caller gets the answer even though the
     * state is reset in the same call - an end that left the drag up until the suspending move
     * finished would keep a stale row highlighted for the length of it.
     */
    fun endDrag(): TransferRequest? {
        val tab = dragging
        val target = hoveredWorkspaceId
        dragging = null
        pointer = Offset.Unspecified
        return if (tab != null && target != null) TransferRequest(tab, target) else null
    }

    fun cancelDrag() {
        dragging = null
        pointer = Offset.Unspecified
    }

    data class TransferRequest(
        val tab: ActiveTabData,
        val targetWorkspaceId: String,
    )
}
