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
     * Every rectangle that stands for a pane, keyed by whoever registered it.
     *
     * Keyed by REGISTRATION, not by pane, because a pane has several: its split header and one per
     * tab row under it. Keyed by pane, the last row to compose would be the only rectangle left and
     * the rest of the pane would quietly stop accepting drops.
     *
     * A separate map from [dropTargets] rather than one keyed by a nullable pane, because the two
     * answer different questions and a drop needs both: a pane target names where exactly, a
     * workspace target says "you pick". [hoveredPane] winning over [hoveredWorkspaceId] is a rule
     * about precision - on screen they do not overlap, since a workspace target is its header row.
     */
    private val paneTargets = mutableStateMapOf<String, PaneRegion>()

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

    /**
     * Register (or re-register) one rectangle that means "drop here to land in [target]".
     *
     * [key] identifies the REGISTRATION - a pane's header and each of its tab rows pass different
     * ones - so that several rectangles can point at the same pane. See [paneTargets].
     */
    fun registerPaneTarget(
        key: String,
        target: PaneTarget,
        bounds: Rect,
        /** This tab's position in its pane, or null for a pane header, which appends. */
        index: Int? = null,
    ) {
        paneTargets[key] = PaneRegion(target, bounds, index)
    }

    /** Forget a registration that has left composition. Guarded like [unregisterTarget]. */
    fun unregisterPaneTarget(
        key: String,
        bounds: Rect,
    ) {
        if (paneTargets[key]?.bounds == bounds) paneTargets.remove(key)
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
        get() = hoveredRegion()?.target

    /**
     * Where in the hovered pane a drop would land, or null to append.
     *
     * Non-null only over a tab ROW, which is what carries a position; a pane header names the pane
     * and nothing more. Over its own pane this still answers, because a reorder within one pane is
     * a real move - see [hoveredReorder].
     */
    val hoveredIndex: Int?
        get() {
            val at = pointer.takeIf { it != Offset.Unspecified } ?: return null
            return hoveredRegion()?.slotFor(at.y)
        }

    /**
     * The rectangle under the pointer, or null.
     *
     * Its own PANE is excluded unless the region carries an index: dropping a tab back on its own
     * pane does nothing, but dropping it above or below a particular tab of that pane is a reorder.
     * The tab's own row is excluded either way - both halves of it name a position it already
     * holds, and lighting it up would promise a move that is refused.
     */
    private fun hoveredRegion(): PaneRegion? {
        val tab = dragging ?: return null
        val at = pointer.takeIf { it != Offset.Unspecified } ?: return null
        return paneTargets.entries
            .firstOrNull { (key, region) ->
                region.bounds.contains(at) &&
                    key != "tab:${tab.tabId}" &&
                    (region.index != null || region.target.panelId != tab.panelId)
            }?.value
    }

    /** True when the drop would only change this tab's position, not the pane it is in. */
    val hoveredReorder: Boolean
        get() = hoveredPane?.let { it.panelId == dragging?.panelId } ?: false

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
        val region = hoveredRegion()
        val pane = region?.target
        val index = pointer.takeIf { it != Offset.Unspecified }?.let { region?.slotFor(it.y) }
        val workspace = hoveredWorkspaceId
        dragging = null
        pointer = Offset.Unspecified
        if (tab == null) return null
        return when {
            pane != null -> TransferRequest(tab, pane.workspaceId, pane.panelId, index)
            workspace != null -> TransferRequest(tab, workspace, targetPanelId = null, targetIndex = null)
            else -> null
        }
    }

    fun cancelDrag() {
        dragging = null
        pointer = Offset.Unspecified
    }

    /**
     * One registered rectangle, the pane it stands for, and where in that pane it inserts.
     *
     * [index] is the position of the TAB this rectangle belongs to, or null for a pane header - a
     * header means the pane and no position in it, which is an append. A row uses the pointer's
     * half to choose between its own index and the one after: dropping on the top half of a tab
     * lands above it, on the bottom half below it.
     */
    private data class PaneRegion(
        val target: PaneTarget,
        val bounds: Rect,
        val index: Int?,
    ) {
        fun slotFor(pointerY: Float): Int? {
            val at = index ?: return null
            return if (pointerY < bounds.center.y) at else at + 1
        }
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
        /** Where in that pane's list, or null to append. Only ever set alongside a pane. */
        val targetIndex: Int?,
    )
}
