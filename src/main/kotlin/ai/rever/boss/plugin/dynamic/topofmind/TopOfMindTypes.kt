package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData

/**
 * Hierarchical structure for workspace tab sections
 */
sealed class WorkspaceTabStructure {
    data class TabItem(
        val activeTab: ActiveTabData
    ) : WorkspaceTabStructure()

    /**
     * One pane's tabs under the name the host gave that pane.
     *
     * [sectionName] is `ActiveTabData.splitPosition` - "Left", "Bottom right", "Pane 3" - which is
     * what the window's own vertical tab bar prints on its group headers. See [TabTreeBuilder].
     *
     * [children] are [TabItem]s. The type still allows nesting because the renderer is written
     * against it, but [TabTreeBuilder] emits one flat section per pane: the bar's panes are flat,
     * and the branch a pane hangs off is not what a reader is asking.
     */
    data class SplitSection(
        val sectionName: String,
        val children: List<WorkspaceTabStructure>,
        val level: Int = 0
    ) : WorkspaceTabStructure()
}

/**
 * Tree node structure for organizing workspaces and tabs
 */
sealed class TabTreeNode {
    abstract val id: String
    abstract val name: String
    abstract val level: Int

    data class WorkspaceNode(
        override val id: String,
        override val name: String,
        override val level: Int = 0,
        val workspaceId: String,
        var isExpanded: Boolean = true,
        val tabStructure: List<WorkspaceTabStructure> = emptyList(),
        val tabCount: Int = 0
    ) : TabTreeNode()

    // There was a `TabNode` here, a tab as a top-level tree node. Nothing ever built one: the
    // tree's leaves are `WorkspaceTabStructure.TabItem` inside a workspace, and the only code
    // that matched on it was the search filter this panel no longer has. It went with that
    // filter rather than being left as a branch every `when` has to answer and nothing reaches.
}
