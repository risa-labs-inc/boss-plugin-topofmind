package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a dragged tab is over, and where letting go sends it.
 *
 * A drop used to name a workspace and nothing else, so the host picked the pane - fine for a drop
 * onto a workspace header, useless for a drop onto a pane, and unable to express a move between two
 * panes of one workspace at all. These pin the pane targets: that they are preferred, that a pane of
 * the tab's OWN workspace counts, and that its own pane never does.
 */
class TabDragTargetTest {
    private fun tab(
        tabId: String,
        workspaceId: String,
        panelId: String,
    ) = ActiveTabData(
        tabId = tabId,
        typeId = "test",
        title = tabId,
        workspaceId = workspaceId,
        workspaceName = workspaceId,
        panelId = panelId,
        windowId = "window",
    )

    private val header = Rect(0f, 0f, 100f, 20f)
    private val leftPane = Rect(0f, 40f, 100f, 60f)
    private val rightPane = Rect(0f, 80f, 100f, 100f)

    private fun stateOver(
        point: Offset,
        dragged: ActiveTabData,
    ): TabDragState =
        TabDragState().apply {
            registerTarget("ws-b", header)
            registerPaneTarget("pane:ws-b:main", TabDragState.PaneTarget("ws-b", "main"), leftPane)
            registerPaneTarget("pane:ws-b:right", TabDragState.PaneTarget("ws-b", "right"), rightPane)
            startDrag(dragged, point)
        }

    @Test
    fun `dropping on a pane names that pane`() {
        val state = stateOver(rightPane.center, tab("t", "ws-a", "main"))

        assertEquals(TabDragState.PaneTarget("ws-b", "right"), state.hoveredPane)
        val request = state.endDrag()
        assertEquals("ws-b", request?.targetWorkspaceId)
        assertEquals("right", request?.targetPanelId)
    }

    @Test
    fun `dropping on a workspace header leaves the pane to the host`() {
        val state = stateOver(header.center, tab("t", "ws-a", "main"))

        assertNull(state.hoveredPane)
        val request = state.endDrag()
        assertEquals("ws-b", request?.targetWorkspaceId)
        assertNull(request?.targetPanelId)
    }

    @Test
    fun `a pane of the tab's OWN workspace is a target`() {
        // The whole second half of the feature. `hoveredWorkspaceId` refuses the tab's own
        // workspace, because a workspace-level move to where it already is does nothing - but a
        // different PANE of that workspace is a real destination.
        val state = stateOver(rightPane.center, tab("t", "ws-b", "main"))

        assertEquals(TabDragState.PaneTarget("ws-b", "right"), state.hoveredPane)
        assertNull(state.hoveredWorkspaceId)
        assertEquals("right", state.endDrag()?.targetPanelId)
    }

    @Test
    fun `the tab's own pane is never a target`() {
        val state = stateOver(rightPane.center, tab("t", "ws-b", "right"))

        assertNull(state.hoveredPane)
        assertNull(state.endDrag())
    }

    @Test
    fun `where both could match, the pane wins`() {
        // On screen they cannot overlap - a workspace target is its header row and a pane target is
        // a split header further down - so this is the rule stated rather than the geometry
        // observed. Without it, `endDrag` could answer either way and no test would notice.
        val state =
            TabDragState().apply {
                registerTarget("ws-b", leftPane)
                registerPaneTarget("pane:ws-b:right", TabDragState.PaneTarget("ws-b", "right"), leftPane)
                startDrag(tab("t", "ws-a", "main"), leftPane.center)
            }

        assertEquals("right", state.endDrag()?.targetPanelId)
    }

    @Test
    fun `a pointer over nothing lands nowhere`() {
        val state = stateOver(Offset(500f, 500f), tab("t", "ws-a", "main"))

        assertNull(state.hoveredPane)
        assertNull(state.hoveredWorkspaceId)
        assertNull(state.endDrag())
    }

    @Test
    fun `unregistering a pane is guarded on the bounds still matching`() {
        // A section that re-lays out disposes its old node AFTER the replacement has registered.
        // An unguarded removal would drop the live target and that pane would silently stop
        // accepting drops - the exact bug the workspace targets were fixed for.
        val target = TabDragState.PaneTarget("ws-b", "right")
        val state = stateOver(rightPane.center, tab("t", "ws-a", "main"))

        state.registerPaneTarget("pane:ws-b:right", target, rightPane.translate(0f, 5f))
        state.unregisterPaneTarget("pane:ws-b:right", rightPane)

        assertEquals(target, state.hoveredPane)
    }

    @Test
    fun `the half of a row the pointer is in picks above or below it`() {
        val target = TabDragState.PaneTarget("ws-b", "right")
        val row = Rect(0f, 100f, 100f, 120f)
        val state =
            TabDragState().apply {
                registerPaneTarget("tab:two", target, row, index = 2)
                startDrag(tab("t", "ws-a", "main"), Offset(50f, 104f))
            }

        // Top half: above the tab at index 2, so index 2.
        assertEquals(2, state.hoveredIndex)
        // Bottom half: below it.
        state.updateDrag(Offset(50f, 116f))
        assertEquals(3, state.hoveredIndex)
        assertEquals(3, state.endDrag()?.targetIndex)
    }

    @Test
    fun `a pane header names the pane and no position`() {
        val state = stateOver(rightPane.center, tab("t", "ws-a", "main"))

        assertEquals(TabDragState.PaneTarget("ws-b", "right"), state.hoveredPane)
        assertNull(state.hoveredIndex)
        assertNull(state.endDrag()?.targetIndex)
    }

    @Test
    fun `a row of the tab's own pane is a reorder, but its own row is not`() {
        // Its own pane is otherwise refused - dropping a tab back where it is does nothing - and a
        // POSITION in that pane is the exception, because a reorder is a real move.
        val target = TabDragState.PaneTarget("ws-a", "main")
        val other = Rect(0f, 100f, 100f, 120f)
        val own = Rect(0f, 120f, 100f, 140f)
        val dragged = tab("t", "ws-a", "main")
        val state =
            TabDragState().apply {
                registerPaneTarget("tab:other", target, other, index = 0)
                registerPaneTarget("tab:t", target, own, index = 1)
                startDrag(dragged, other.center)
            }

        assertEquals(target, state.hoveredPane)
        assertEquals(true, state.hoveredReorder)

        // Over its OWN row, both halves name a position it already holds.
        state.updateDrag(own.center)
        assertNull(state.hoveredPane)
        assertNull(state.endDrag())
    }

    @Test
    fun `every row of a pane is its own rectangle for the same pane`() {
        // Keyed by pane, the last row to compose would be the only rectangle left and the rest of
        // the pane would silently stop accepting drops. Rows are why the key is a registration.
        val target = TabDragState.PaneTarget("ws-b", "right")
        val rowOne = Rect(0f, 120f, 100f, 140f)
        val rowTwo = Rect(0f, 140f, 100f, 160f)
        val state =
            TabDragState().apply {
                registerPaneTarget("tab:one", target, rowOne, index = 0)
                registerPaneTarget("tab:two", target, rowTwo, index = 1)
                startDrag(tab("t", "ws-a", "main"), rowOne.center)
            }

        assertEquals(target, state.hoveredPane)
        state.updateDrag(rowTwo.center)
        assertEquals(target, state.hoveredPane)
    }
}
