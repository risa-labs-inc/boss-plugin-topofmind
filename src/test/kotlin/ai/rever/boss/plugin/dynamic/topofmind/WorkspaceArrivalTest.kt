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

/**
 * Where a workspace sits in the tree, and when it moves.
 *
 * It was ordered by `LayoutWorkspace.timestamp` alone, which answers "when was this last written"
 * and not "when did I open it". Opening a workspace saved months ago dropped it into the middle of
 * the list, and workspaces sharing a timestamp fell through to the name tie-break and came out
 * alphabetical. These pin the replacement: a first sighting keeps the timestamp seed, anything that
 * opens afterwards lands at the BOTTOM, and nothing already in the list moves when it does.
 */
class WorkspaceArrivalTest {
    private fun tab(
        workspaceId: String,
        workspaceName: String,
    ) = ActiveTabData(
        tabId = "$workspaceId-tab",
        typeId = "test",
        title = "tab",
        workspaceId = workspaceId,
        workspaceName = workspaceName,
        panelId = "main",
        windowId = "window",
    )

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

    private class FakeWorkspaces(
        initial: List<LayoutWorkspace>,
    ) : WorkspaceDataProvider {
        private val state = MutableStateFlow(initial)

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

    private val provider =
        FakeWorkspaces(
            listOf(
                saved("ws-b", "Beta", timestamp = 200L),
                saved("ws-a", "Alpha", timestamp = 100L),
                // Saved long before either, and named to sort first: if position came from the
                // timestamp or from the name, opening this later would put it at the TOP.
                saved("ws-old", "Aardvark", timestamp = 1L),
            ),
        )

    private fun namesFor(
        arrival: WorkspaceArrival,
        vararg workspaces: Pair<String, String>,
    ) = TabTreeBuilder
        .buildTree(
            workspaces.map { (id, name) -> tab(id, name) },
            provider,
            arrival,
        ).map { it.name }

    @Test
    fun `a first sighting keeps the timestamp seed`() {
        val arrival = WorkspaceArrival()
        assertEquals(
            listOf("Aardvark", "Alpha", "Beta"),
            namesFor(arrival, "ws-b" to "Beta", "ws-a" to "Alpha", "ws-old" to "Aardvark"),
        )
    }

    @Test
    fun `a workspace opened later goes to the bottom, whatever its timestamp or name`() {
        val arrival = WorkspaceArrival()
        namesFor(arrival, "ws-a" to "Alpha", "ws-b" to "Beta")

        // "Aardvark" is the oldest save AND first alphabetically. Both of the orderings this
        // replaced would have put it on top.
        assertEquals(
            listOf("Alpha", "Beta", "Aardvark"),
            namesFor(arrival, "ws-a" to "Alpha", "ws-b" to "Beta", "ws-old" to "Aardvark"),
        )
    }

    @Test
    fun `nothing already in the list moves when a workspace opens`() {
        val arrival = WorkspaceArrival()
        val before = namesFor(arrival, "ws-b" to "Beta", "ws-a" to "Alpha")
        val after = namesFor(arrival, "ws-b" to "Beta", "ws-a" to "Alpha", "ws-old" to "Aardvark")
        assertEquals(before, after.dropLast(1))
    }

    @Test
    fun `a rebuild with the same workspaces is a no-op`() {
        // The tree is rebuilt roughly every 2s. If that moved anything, rows would drift under the
        // cursor while the user was reading them.
        val arrival = WorkspaceArrival()
        val first = namesFor(arrival, "ws-b" to "Beta", "ws-a" to "Alpha")
        repeat(3) {
            assertEquals(first, namesFor(arrival, "ws-b" to "Beta", "ws-a" to "Alpha"))
        }
    }

    @Test
    fun `closing a workspace and opening it again puts it at the bottom`() {
        val arrival = WorkspaceArrival()
        namesFor(arrival, "ws-a" to "Alpha", "ws-b" to "Beta")
        namesFor(arrival, "ws-b" to "Beta")

        assertEquals(
            listOf("Beta", "Alpha"),
            namesFor(arrival, "ws-a" to "Alpha", "ws-b" to "Beta"),
        )
    }

    @Test
    fun `without an arrival the seed is the whole order`() {
        // The default argument. Nothing in the plugin passes null, but the builder is a public
        // object and the fallback must stay the old, deterministic behaviour rather than none.
        val names =
            TabTreeBuilder
                .buildTree(
                    listOf(tab("ws-b", "Beta"), tab("ws-old", "Aardvark")),
                    provider,
                ).map { it.name }
        assertEquals(listOf("Aardvark", "Beta"), names)
    }
}
