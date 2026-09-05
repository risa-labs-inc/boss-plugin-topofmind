package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a workspace's panes reach the panel, and where they land on a floor plate.
 *
 * The panel and the window's own vertical tab bar describe one arrangement, and used to do it from
 * two different sources - the bar from the panes' measured rectangles, this from the workspace's
 * SAVED `SplitConfig`. These pin the half that is testable without a screen: that a pane's section
 * is named by whatever the host said (`ActiveTabData.splitPosition`), that the sections are flat and
 * in the host's order, and that the floor plan only takes those names literally when they actually
 * describe a whole plate.
 */
class PaneStructureTest {
    private fun tab(
        id: String,
        panelId: String,
        splitPosition: String? = null,
        workspaceId: String = "ws",
        workspaceName: String = "Workspace",
    ) = ActiveTabData(
        tabId = id,
        typeId = "test",
        title = id,
        workspaceId = workspaceId,
        workspaceName = workspaceName,
        panelId = panelId,
        windowId = "window",
        splitPosition = splitPosition,
    )

    private fun sectionsOf(tabs: List<ActiveTabData>): List<WorkspaceTabStructure.SplitSection> =
        TabTreeBuilder
            .buildTree(tabs)
            .filterIsInstance<TabTreeNode.WorkspaceNode>()
            .single()
            .tabStructure
            .filterIsInstance<WorkspaceTabStructure.SplitSection>()

    private fun structureOf(tabs: List<ActiveTabData>): List<WorkspaceTabStructure> =
        TabTreeBuilder
            .buildTree(tabs)
            .filterIsInstance<TabTreeNode.WorkspaceNode>()
            .single()
            .tabStructure

    // ---- naming -------------------------------------------------------------------------------

    @Test
    fun `a pane's section is named by the host, in the host's order`() {
        val sections =
            sectionsOf(
                listOf(
                    tab("a", panelId = "main", splitPosition = "Left"),
                    tab("b", panelId = "p2", splitPosition = "Right"),
                    tab("c", panelId = "p2", splitPosition = "Right"),
                ),
            )

        assertEquals(listOf("Left", "Right"), sections.map { it.sectionName })
        assertEquals(listOf("a"), TabTreeBuilder.tabsIn(sections[0].children).map { it.tabId })
        assertEquals(listOf("b", "c"), TabTreeBuilder.tabsIn(sections[1].children).map { it.tabId })
    }

    @Test
    fun `a nested pane keeps the corner the host named, flat`() {
        // "Top right", one level deep - not "RIGHT > TOP" at two, which is what reading the saved
        // SplitConfig tree produced and is the drift this replaced.
        val sections =
            sectionsOf(
                listOf(
                    tab("a", panelId = "main", splitPosition = "Left"),
                    tab("b", panelId = "p2", splitPosition = "Top right"),
                    tab("c", panelId = "p3", splitPosition = "Bottom right"),
                ),
            )

        assertEquals(listOf("Left", "Top right", "Bottom right"), sections.map { it.sectionName })
        assertTrue(
            sections.all { section -> section.children.all { it is WorkspaceTabStructure.TabItem } },
            "sections must be flat: a section holding another section is the nesting this removed",
        )
    }

    @Test
    fun `one pane gets no section at all`() {
        // A heading over every tab in the workspace claims a divider that is not there.
        val structure = structureOf(listOf(tab("a", panelId = "main"), tab("b", panelId = "main")))

        assertTrue(structure.all { it is WorkspaceTabStructure.TabItem })
        assertEquals(listOf("a", "b"), TabTreeBuilder.tabsIn(structure).map { it.tabId })
    }

    @Test
    fun `a host too old to name a pane gets a number, not a wrong side`() {
        val sections =
            sectionsOf(
                listOf(
                    tab("a", panelId = "main"),
                    tab("b", panelId = "p2"),
                ),
            )

        assertEquals(listOf("Pane 1", "Pane 2"), sections.map { it.sectionName })
    }

    // ---- ordering -----------------------------------------------------------------------------

    @Test
    fun `workspaces are oldest first, so the newest is the bottom row`() {
        val provider =
            FakeWorkspaces(
                listOf(
                    saved("ws-b", "Beta", timestamp = 200L),
                    saved("ws-a", "Alpha", timestamp = 100L),
                ),
            )

        val names =
            TabTreeBuilder
                .buildTree(
                    listOf(
                        tab("b", panelId = "main", workspaceId = "ws-b", workspaceName = "Beta"),
                        tab("a", panelId = "main", workspaceId = "ws-a", workspaceName = "Alpha"),
                    ),
                    provider,
                ).map { it.name }

        // Alphabetically Alpha comes first anyway, so the timestamps are what this proves: reverse
        // them and the list reverses with them.
        assertEquals(listOf("Alpha", "Beta"), names)

        provider.set(
            listOf(
                saved("ws-b", "Beta", timestamp = 100L),
                saved("ws-a", "Alpha", timestamp = 200L),
            ),
        )
        val reordered =
            TabTreeBuilder
                .buildTree(
                    listOf(
                        tab("b", panelId = "main", workspaceId = "ws-b", workspaceName = "Beta"),
                        tab("a", panelId = "main", workspaceId = "ws-a", workspaceName = "Alpha"),
                    ),
                    provider,
                ).map { it.name }
        assertEquals(listOf("Beta", "Alpha"), reordered)
    }

    @Test
    fun `a workspace nothing has saved is the newest thing there is`() {
        val provider = FakeWorkspaces(listOf(saved("ws-a", "Alpha", timestamp = 100L)))

        val names =
            TabTreeBuilder
                .buildTree(
                    listOf(
                        tab("u", panelId = "main", workspaceId = "ws-new", workspaceName = "Aaa unsaved"),
                        tab("a", panelId = "main", workspaceId = "ws-a", workspaceName = "Alpha"),
                    ),
                    provider,
                ).map { it.name }

        // Named to sort first alphabetically, so position here is the timestamp rule and not the
        // tie-break underneath it.
        assertEquals(listOf("Alpha", "Aaa unsaved"), names)
    }

    // ---- floor plan ---------------------------------------------------------------------------

    @Test
    fun `names that describe a whole plate are taken literally`() {
        val panes =
            WorkspaceFloorPlan.panesOf(
                structureOf(
                    listOf(
                        tab("a", panelId = "main", splitPosition = "Left"),
                        tab("b", panelId = "p2", splitPosition = "Top right"),
                        tab("c", panelId = "p3", splitPosition = "Bottom right"),
                    ),
                ),
            )

        assertEquals(
            listOf(
                Rect(0f, 0f, 0.5f, 1f),
                Rect(0.5f, 0f, 1f, 0.5f),
                Rect(0.5f, 0.5f, 1f, 1f),
            ),
            panes.map { it.area },
        )
        assertEquals(listOf("main", "p2", "p3"), panes.map { it.paneId })
    }

    @Test
    fun `a numbered pane between two named ones falls back to equal slices`() {
        // The three-column split: the host numbers the middle pane because no honest name fits, and
        // taking "Left" and "Right" at face value would draw the third pane on top of both.
        val panes =
            WorkspaceFloorPlan.panesOf(
                structureOf(
                    listOf(
                        tab("a", panelId = "main", splitPosition = "Left"),
                        tab("b", panelId = "p2", splitPosition = "Pane 2"),
                        tab("c", panelId = "p3", splitPosition = "Right"),
                    ),
                ),
            )

        assertEquals(
            listOf(
                Rect(0f, 0f, 1f / 3f, 1f),
                Rect(1f / 3f, 0f, 2f / 3f, 1f),
                Rect(2f / 3f, 0f, 1f, 1f),
            ),
            panes.map { it.area },
        )
    }

    @Test
    fun `the fallback still reads the axis from the names`() {
        // A pane called "Top" runs the full width, so its siblings are stacked. Slicing into
        // columns here would draw a three-row split lying on its side.
        val panes =
            WorkspaceFloorPlan.panesOf(
                structureOf(
                    listOf(
                        tab("a", panelId = "main", splitPosition = "Top"),
                        tab("b", panelId = "p2", splitPosition = "Pane 2"),
                        tab("c", panelId = "p3", splitPosition = "Bottom"),
                    ),
                ),
            )

        assertEquals(
            listOf(
                Rect(0f, 0f, 1f, 1f / 3f),
                Rect(0f, 1f / 3f, 1f, 2f / 3f),
                Rect(0f, 2f / 3f, 1f, 1f),
            ),
            panes.map { it.area },
        )
    }

    @Test
    fun `names that leave a gap are refused, not drawn`() {
        // "Left" and "Top" do not overlap, and an overlap-only test would accept them and leave a
        // quarter of the plate blank.
        val panes =
            WorkspaceFloorPlan.panesOf(
                structureOf(
                    listOf(
                        tab("a", panelId = "main", splitPosition = "Left"),
                        tab("b", panelId = "p2", splitPosition = "Top"),
                    ),
                ),
            )

        assertEquals(
            listOf(Rect(0f, 0f, 1f, 0.5f), Rect(0f, 0.5f, 1f, 1f)),
            panes.map { it.area },
        )
    }

    @Test
    fun `a workspace with one pane is one undivided plate`() {
        val panes =
            WorkspaceFloorPlan.panesOf(
                structureOf(listOf(tab("a", panelId = "main"), tab("b", panelId = "main"))),
            )

        assertEquals(1, panes.size)
        assertEquals(Rect(0f, 0f, 1f, 1f), panes.single().area)
        assertEquals("main", panes.single().paneId)
        assertEquals(2, panes.single().tabCount)
    }

    // ---- fakes --------------------------------------------------------------------------------

    private fun saved(
        id: String,
        name: String,
        timestamp: Long,
    ) = LayoutWorkspace(
        id = id,
        name = name,
        description = "",
        layout = SplitConfig.SinglePanel(PanelConfig(id = "main", tabs = emptyList())),
        timestamp = timestamp,
    )

    /** Only [workspaces] is read by [TabTreeBuilder]; nothing else here is ever called. */
    private class FakeWorkspaces(
        initial: List<LayoutWorkspace>,
    ) : WorkspaceDataProvider {
        private val state = MutableStateFlow(initial)

        fun set(value: List<LayoutWorkspace>) {
            state.value = value
        }

        override val workspaces: StateFlow<List<LayoutWorkspace>> = state
        override val currentWorkspace: StateFlow<LayoutWorkspace?> = MutableStateFlow(null)

        override fun loadWorkspace(workspace: LayoutWorkspace) = Unit

        override fun updateCurrentWorkspace(newWorkspace: LayoutWorkspace) = Unit

        override fun saveCurrentWorkspace(name: String?): LayoutWorkspace? = null

        override fun exportWorkspace(workspace: LayoutWorkspace): String = ""

        override fun deleteWorkspace(name: String) = Unit

        override fun renameWorkspace(
            oldName: String,
            newName: String,
        ) = Unit
    }
}
