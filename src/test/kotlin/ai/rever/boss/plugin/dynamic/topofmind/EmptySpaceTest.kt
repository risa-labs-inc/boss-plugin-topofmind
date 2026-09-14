package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Space with no tabs is still a Space, and still belongs in the tree.
 *
 * `buildTree` groups `activeTabs` by `workspaceId`, so a Space contributing zero tabs contributed
 * zero rows and was invisible - even when it was the one ON SCREEN. This is exactly the case
 * `ActiveTabsProvider.liveWorkspaceIds` was added for, and its own KDoc says so: "a workspace with
 * no tabs contributes no rows there, so a freshly created empty workspace would be invisible".
 * The plugin has used that member for drag targets, the transfer menu and the picker all along,
 * and never for the one list it was written about.
 *
 * **Live, not saved.** An empty row is offered for a Space this window is RUNNING plus the one on
 * screen, never for every Space on disk - the tree is what this window is doing, and listing cold
 * Spaces here would make it a second, worse copy of the Space picker.
 */
class EmptySpaceTest {
    private fun tab(
        workspaceId: String,
        workspaceName: String,
        title: String,
    ) = ActiveTabData(
        tabId = "$workspaceId-$title",
        typeId = "terminal",
        title = title,
        workspaceId = workspaceId,
        workspaceName = workspaceName,
        panelId = "main",
        windowId = "w1",
    )

    private fun saved(
        id: String,
        name: String,
        timestamp: Long,
    ) = LayoutWorkspace(
        id = id,
        name = name,
        description = "d",
        layout = SplitConfig.SinglePanel(PanelConfig(id = "main", tabs = emptyList())),
        timestamp = timestamp,
    )

    private class FakeWorkspaces(
        spaces: List<LayoutWorkspace>,
    ) : WorkspaceDataProvider {
        override val workspaces: StateFlow<List<LayoutWorkspace>> = MutableStateFlow(spaces)
        override val currentWorkspace: StateFlow<LayoutWorkspace?> = MutableStateFlow(null)

        override fun loadWorkspace(workspace: LayoutWorkspace) {}

        override fun updateCurrentWorkspace(newWorkspace: LayoutWorkspace) {}

        override fun saveCurrentWorkspace(name: String?): LayoutWorkspace? = null

        override fun exportWorkspace(workspace: LayoutWorkspace): String = ""

        override fun deleteWorkspace(name: String) {}

        override fun renameWorkspace(oldName: String, newName: String) {}
    }

    private val populated = listOf(tab("workspace-gemini", "Gemini", "docs"))

    private val provider =
        FakeWorkspaces(
            listOf(
                saved("workspace-gemini", "Gemini", timestamp = 1_000),
                saved("workspace-empty", "Scratch", timestamp = 2_000),
            ),
        )

    private fun ids(nodes: List<TabTreeNode>) =
        nodes.filterIsInstance<TabTreeNode.WorkspaceNode>().map { it.workspaceId }

    // ==================== the row exists ====================

    @Test
    fun `a live Space with no tabs still gets a row`() {
        val nodes =
            TabTreeBuilder.buildTree(
                activeTabs = populated,
                workspaceDataProvider = provider,
                liveWorkspaceIds = setOf("workspace-gemini", "workspace-empty"),
            )

        assertEquals(setOf("workspace-gemini", "workspace-empty"), ids(nodes).toSet())
    }

    @Test
    fun `the Space on screen gets a row even when nothing else is running`() {
        // The worst reading of the old behaviour: the Space you are LOOKING at, absent from the
        // panel that exists to say what you are looking at.
        val nodes =
            TabTreeBuilder.buildTree(
                activeTabs = emptyList(),
                workspaceDataProvider = provider,
                currentWorkspaceId = "workspace-empty",
            )

        assertEquals(listOf("workspace-empty"), ids(nodes))
    }

    @Test
    fun `an empty row carries no tabs and no sections`() {
        val node =
            TabTreeBuilder
                .buildTree(emptyList(), provider, liveWorkspaceIds = setOf("workspace-empty"))
                .filterIsInstance<TabTreeNode.WorkspaceNode>()
                .single()

        assertEquals(0, node.tabCount)
        assertEquals(emptyList(), node.tabStructure)
        assertTrue(
            TabTreeBuilder.tabsIn(node.tabStructure).isEmpty(),
            "which is what leaves `onCloseAll` null, so no close action is offered for nothing",
        )
    }

    @Test
    fun `a cold Space on disk gets no row`() {
        // The tree is what this WINDOW is doing. Listing every saved Space here would make it a
        // second copy of the Space picker, and a worse one.
        val nodes = TabTreeBuilder.buildTree(populated, provider, liveWorkspaceIds = setOf("workspace-gemini"))

        assertEquals(listOf("workspace-gemini"), ids(nodes))
    }

    // ==================== naming ====================

    @Test
    fun `an empty Space is named from the saved list, since it has no tab to name it`() {
        val node =
            TabTreeBuilder
                .buildTree(emptyList(), provider, liveWorkspaceIds = setOf("workspace-empty"))
                .filterIsInstance<TabTreeNode.WorkspaceNode>()
                .single()

        assertEquals("Scratch", node.name)
    }

    @Test
    fun `a live Space nothing has saved is still drawn, under the switcher's own fallback word`() {
        val node =
            TabTreeBuilder
                .buildTree(emptyList(), provider, liveWorkspaceIds = setOf("workspace-1789000000001"))
                .filterIsInstance<TabTreeNode.WorkspaceNode>()
                .single()

        assertEquals("Space", node.name, "a row saying nothing beats no row at all")
        assertEquals("workspace-1789000000001", node.workspaceId, "and it is still itself, for the tint and the drop")
    }

    // ==================== ordering ====================

    @Test
    fun `an empty Space takes an arrival slot and keeps it across rebuilds`() {
        // The tree rebuilds roughly every 2s. A row that moved between rebuilds would drift under
        // the cursor, which is the failure `WorkspaceArrival` exists to prevent - and an empty
        // Space has no tabs to have arrived with, so it is the one most likely to be forgotten.
        val arrival = WorkspaceArrival()
        val live = setOf("workspace-gemini", "workspace-empty")

        val first = ids(TabTreeBuilder.buildTree(populated, provider, arrival, live))
        val second = ids(TabTreeBuilder.buildTree(populated, provider, arrival, live))
        val third = ids(TabTreeBuilder.buildTree(populated, provider, arrival, live))

        assertEquals(first, second)
        assertEquals(second, third)
        assertEquals(2, first.size)
    }

    @Test
    fun `an empty Space opened later lands at the bottom, like any other`() {
        val arrival = WorkspaceArrival()

        TabTreeBuilder.buildTree(populated, provider, arrival, setOf("workspace-gemini"))
        val after =
            ids(
                TabTreeBuilder.buildTree(
                    populated,
                    provider,
                    arrival,
                    setOf("workspace-gemini", "workspace-empty"),
                ),
            )

        assertEquals(listOf("workspace-gemini", "workspace-empty"), after)
    }

    @Test
    fun `a Space that gains its first tab keeps the slot it had while empty`() {
        // Otherwise dragging a tab into an empty Space would make that Space jump, which is the
        // row moving out from under the pointer that just dropped on it.
        val arrival = WorkspaceArrival()
        val live = setOf("workspace-gemini", "workspace-empty")

        val before = ids(TabTreeBuilder.buildTree(populated, provider, arrival, live))
        val filled = populated + tab("workspace-empty", "Scratch", "notes")
        val after = ids(TabTreeBuilder.buildTree(filled, provider, arrival, live))

        assertEquals(before, after)
    }

    @Test
    fun `a Space with tabs is never listed twice`() {
        // It is in `activeTabs` AND in `liveWorkspaceIds`, which is the obvious way to get two rows
        // for one Space.
        val nodes =
            TabTreeBuilder.buildTree(
                populated,
                provider,
                liveWorkspaceIds = setOf("workspace-gemini"),
                currentWorkspaceId = "workspace-gemini",
            )

        assertEquals(listOf("workspace-gemini"), ids(nodes))
        assertEquals(1, nodes.filterIsInstance<TabTreeNode.WorkspaceNode>().single().tabCount)
    }
}
