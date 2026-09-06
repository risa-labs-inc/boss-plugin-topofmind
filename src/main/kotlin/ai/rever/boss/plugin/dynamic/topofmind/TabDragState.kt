package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Dragging a tab row onto a workspace or a pane, and the highlight left behind after a move.
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
     * Pane -> that split header's bounds, in window coordinates.
     *
     * A separate map from [dropTargets] rather than one keyed by a nullable pane, because the two
     * answer different questions and a drop needs both: a pane target names where exactly, a
     * workspace target says "you pick". They never overlap on screen - a workspace target is its
     * header row and a pane target is a split header row further down - so [hoveredPane] winning
     * over [hoveredWorkspaceId] is a rule about precision, not about geometry.
     */
    private val paneTargets = mutableStateMapOf<PaneTarget, Rect>()

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

    /** Register (or re-register) one pane's header as a drop target. */
    fun registerPaneTarget(
        target: PaneTarget,
        bounds: Rect,
    ) {
        paneTargets[target] = bounds
    }

    /** Forget a pane header that has left composition. Guarded like [unregisterTarget]. */
    fun unregisterPaneTarget(
        target: PaneTarget,
        bounds: Rect,
    ) {
        if (paneTargets[target] == bounds) paneTargets.remove(target)
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
     * The pane the pointer is over, or null.
     *
     * Never the tab's OWN pane, which is a move to where it already is - the host refuses it and
     * lighting the header up would promise a drop that does not happen. Its own workspace is fine
     * here, unlike [hoveredWorkspaceId]: moving a tab to another pane of the workspace it is
     * already in is the second half of what pane targets are for.
     */
    val hoveredPane: PaneTarget?
        get() {
            val tab = dragging ?: return null
            val at = pointer.takeIf { it != Offset.Unspecified } ?: return null
            return paneTargets.entries
                .firstOrNull { (target, bounds) ->
                    target.panelId != tab.panelId && bounds.contains(at)
                }?.key
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
        // A pane beats a workspace: it is the more precise answer to the same gesture, and the two
        // targets cannot be under the pointer at once anyway.
        val pane = hoveredPane
        val workspace = hoveredWorkspaceId
        dragging = null
        pointer = Offset.Unspecified
        if (tab == null) return null
        return when {
            pane != null -> TransferRequest(tab, pane.workspaceId, pane.panelId)
            workspace != null -> TransferRequest(tab, workspace, targetPanelId = null)
            else -> null
        }
    }

    fun cancelDrag() {
        dragging = null
        pointer = Offset.Unspecified
    }

    /** One pane of one workspace. Both halves, because a panel id is unique only within a tree. */
    data class PaneTarget(
        val workspaceId: String,
        val panelId: String,
    )

    data class TransferRequest(
        val tab: ActiveTabData,
        val targetWorkspaceId: String,
        /** The pane to land in, or null to let the host pick the workspace's active one. */
        val targetPanelId: String?,
    )
}
