package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.WorkspaceDataProvider

/**
 * Utility to build tree structure from active tabs
 */
object TabTreeBuilder {

    /**
     * A workspace with no saved record of when it was last written sorts to the bottom.
     *
     * Which is where it belongs under this ordering: a workspace running in the window but absent
     * from `workspaces` is one nothing has saved yet, so it is newer than everything that has been.
     */
    private const val UNSAVED = Long.MAX_VALUE

    /**
     * Oldest first, so the newest workspace is the bottom row.
     *
     * `LayoutWorkspace.timestamp` is when the workspace was last written, which is the only clock
     * the api offers - `ActiveTabData` carries no time at all and the ids are names
     * (`workspace-claude-code`), not the `workspace-<epoch millis>` that `generateId` produces, so
     * neither can answer this. The consequence worth knowing: saving a workspace moves it down.
     *
     * The name is the tie-break, and the id after it, because two workspaces written in the same
     * millisecond must not swap places between one rebuild and the next.
     *
     * NOT arrival order, which is the obvious thing and is wrong: the host emits the CURRENT
     * workspace's tabs first and the preserved ones after, so grouping in arrival order made
     * whichever workspace you switched to jump to the top of the panel - rows moving out from under
     * the cursor. Which workspace is current is said with the accent stripe in WorkspaceHeader,
     * not with position.
     */
    private fun workspaceOrder(addedAt: Map<String, Long>): Comparator<TabTreeNode.WorkspaceNode> =
        compareBy(
            { addedAt[it.workspaceId] ?: UNSAVED },
            { it.name.lowercase() },
            { it.workspaceId },
        )

    /**
     * One section per PANE, named exactly the way the window's own vertical tab bar names it.
     *
     * This used to rebuild the split TREE from the workspace's saved `SplitConfig` and emit a
     * section per branch, which was a second source of truth for one arrangement and disagreed with
     * the bar three ways at once:
     *
     * - **Shape.** A nested pane arrived as "RIGHT > TOP", two levels of indent, where the bar an
     *   inch to the left called the same pane "Top right" in a flat list. The branch a pane hangs
     *   off on the way down is not what a reader is asking; which pane the tab is in is.
     * - **Freshness.** `SplitConfig` is the SAVED layout. Split a pane without saving and the
     *   layout's panel count no longer matches the running one, at which point the whole workspace
     *   fell back to one undivided list - the panes vanished from the panel while the bar still
     *   drew them.
     * - **Identity.** Layout panel ids were matched to runtime ones by their position in a
     *   depth-first walk, so a mismatch anywhere put a pane's tabs under another pane's heading.
     *
     * `ActiveTabData.splitPosition` is the host's own answer, from the same function the bar's
     * group headers use, so the two agree by construction rather than by two derivations happening
     * to match.
     *
     * **Order is the host's**, not this function's: tabs arrive pane by pane in the order the panes
     * are laid out, and `groupBy` keeps first-encounter order. So a section's place in the panel is
     * its pane's place in the window.
     *
     * A host too old to populate `splitPosition` leaves every tab's null, and a pane then gets
     * "Pane N" - the same word the bar uses for a pane no honest name fits. Fewer names, never a
     * wrong one, and no version gate: the field has always been on `ActiveTabData`.
     */
    private fun buildTabStructure(tabs: List<ActiveTabData>): List<WorkspaceTabStructure> {
        val panes = tabs.groupBy { it.panelId }
        // One pane is not a split. A heading over every tab in the workspace would be a claim
        // about a divider that is not there, which is the bar's rule too.
        if (panes.size <= 1) return tabs.map { WorkspaceTabStructure.TabItem(it) }

        return panes.values.mapIndexed { index, paneTabs ->
            WorkspaceTabStructure.SplitSection(
                sectionName = paneTabs.firstNotNullOfOrNull { it.splitPosition } ?: "Pane ${index + 1}",
                children = paneTabs.map { WorkspaceTabStructure.TabItem(it) },
            )
        }
    }

    /**
     * The panel's whole tree: one node per workspace, each holding one section per pane.
     *
     * [workspaceDataProvider] is read for ONE thing, each workspace's `timestamp` - see
     * [workspaceOrder]. It used to be read for each workspace's saved layout as well, which is
     * what [buildTabStructure] records getting rid of. A null provider costs the ordering and
     * nothing else: every workspace then sorts as unsaved and the tie-break carries the list.
     */
    fun buildTree(
        activeTabs: List<ActiveTabData>,
        workspaceDataProvider: WorkspaceDataProvider? = null
    ): List<TabTreeNode> {
        val addedAt =
            workspaceDataProvider
                ?.workspaces
                ?.value
                .orEmpty()
                .associate { it.id to it.timestamp }

        val rootNodes =
            activeTabs.groupBy { it.workspaceId }.map { (workspaceId, tabs) ->
                TabTreeNode.WorkspaceNode(
                    id = "workspace-$workspaceId",
                    name = tabs.firstOrNull()?.workspaceName ?: "Unknown",
                    workspaceId = workspaceId,
                    level = 0,
                    tabStructure = buildTabStructure(tabs),
                    tabCount = tabs.size
                )
            }

        return rootNodes.sortedWith(workspaceOrder(addedAt))
    }

    /**
     * Every tab under a piece of the tree, in the order it is drawn.
     *
     * Takes the STRUCTURE rather than a workspace id, so it answers for exactly what is on screen:
     * a "close everything here" built on the workspace id would close tabs that are not under the
     * header that was clicked. The count in the confirm comes from this same list, so what the
     * dialog says and what it does are one thing.
     */
    fun tabsIn(structure: List<WorkspaceTabStructure>): List<ActiveTabData> =
        structure.flatMap { item ->
            when (item) {
                is WorkspaceTabStructure.TabItem -> listOf(item.activeTab)
                is WorkspaceTabStructure.SplitSection -> tabsIn(item.children)
            }
        }

    /**
     * The pane a split section stands for, or null when the section is not one pane.
     *
     * [buildTabStructure] now emits one section per pane and never nests, so in practice this
     * answers for every section it is given. The other branches are kept because the renderer is
     * written against the sealed type rather than against that promise: a section holding further
     * sections has no pane of its own and nothing to collapse to, an empty one has no tab to take
     * an id from, and one holding tabs from two panes is impossible by construction and cheap to
     * check for.
     */
    fun paneIdOf(children: List<WorkspaceTabStructure>): String? {
        if (children.isEmpty()) return null
        if (children.any { it !is WorkspaceTabStructure.TabItem }) return null
        return children
            .filterIsInstance<WorkspaceTabStructure.TabItem>()
            .map { it.activeTab.panelId }
            .distinct()
            .singleOrNull()
    }
}
