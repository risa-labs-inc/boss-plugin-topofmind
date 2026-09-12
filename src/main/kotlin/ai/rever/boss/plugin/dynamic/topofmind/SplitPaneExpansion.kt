package ai.rever.boss.plugin.dynamic.topofmind

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which split pane in the tree is showing all its tabs.
 *
 * The host's `TabGroupExpansion` (BossConsole, `main_window_panels/TabGroupExpansion.kt`), ported.
 * A pane the user is not working in collapses to the tab it is currently showing, so a four-way
 * split costs a few rows rather than twenty, and a summary row of favicons stands in for the rest.
 * This tracks the panes that are open ANYWAY - the pane being worked in is always open and is
 * never asked about here, because that is a fact about the split rather than something hovering or
 * clicking decided.
 *
 * **Hover is sticky, and that is the whole design.** The obvious reading - expand while the pointer
 * is over the section - collapses it the instant the pointer moves down onto the rows it just
 * revealed, because those rows are underneath where the pointer was going. So hovering a section
 * header does not expand-while-hovered; it *chooses* which pane is the open one, and that choice
 * survives until another header is hovered or the pointer leaves the panel entirely. Moving
 * straight down from a header onto its tabs is then an ordinary thing to do.
 *
 * A pane can also be pinned open by clicking its header or its summary row's chevron, which is
 * what survives the pointer leaving. Pinned and hovered are tracked separately so that leaving the
 * panel cannot silently undo something the user clicked.
 *
 * A CLASS, one instance per mounted panel (see [TopofmindComponent]), exactly like [TabTreeState]
 * and [TabDragState]. A top-level `object` here would be the bug this plugin has already had
 * twice: two windows showing this panel would share one set of open panes.
 */
@Stable
class SplitPaneExpansion {
    private var hovered by mutableStateOf<String?>(null)
    private val pinned = mutableStateListOf<String>()

    /** Whether this pane is showing every tab it has. */
    fun isExpanded(panelId: String): Boolean = panelId == hovered || panelId in pinned

    /** The pointer reached this pane's header or summary row: it becomes the open one. */
    fun hover(panelId: String) {
        hovered = panelId
    }

    /** Keep this pane open after the pointer leaves, or stop keeping it open. */
    fun togglePinned(panelId: String) {
        if (!pinned.remove(panelId)) pinned.add(panelId)
        // A pane pinned open while it was the hovered one would otherwise stay open on the next
        // panel exit through the hover path, making the unpin look like it did nothing.
        if (panelId == hovered) hovered = null
    }

    /**
     * The pointer left the panel, so nothing is hover-open any more.
     *
     * Only the hover choice is dropped. Pinned panes are a decision someone made with a click and
     * are not something moving the mouse away should undo.
     */
    fun panelExited() {
        hovered = null
    }

    /**
     * Forget panes that no longer exist.
     *
     * Both collections here are keyed by panel id and neither is told when a pane closes, so
     * without this a long session accumulates ids for panes that are gone - and a new pane handed
     * a recycled id would come up pinned open for no reason the user could see. Called from the
     * panel whenever the tree is rebuilt, which is roughly every 2s.
     */
    fun retainOnly(panelIds: Set<String>) {
        pinned.retainAll(panelIds)
        val current = hovered
        if (current != null && current !in panelIds) hovered = null
    }
}
