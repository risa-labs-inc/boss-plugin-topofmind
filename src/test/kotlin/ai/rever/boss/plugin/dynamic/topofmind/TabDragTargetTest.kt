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
            registerPaneTarget(TabDragState.PaneTarget("ws-b", "main"), leftPane)
            registerPaneTarget(TabDragState.PaneTarget("ws-b", "right"), rightPane)
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
                registerPaneTarget(TabDragState.PaneTarget("ws-b", "right"), leftPane)
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

        state.registerPaneTarget(target, rightPane.translate(0f, 5f))
        state.unregisterPaneTarget(target, rightPane)

        assertEquals(target, state.hoveredPane)
    }
}
