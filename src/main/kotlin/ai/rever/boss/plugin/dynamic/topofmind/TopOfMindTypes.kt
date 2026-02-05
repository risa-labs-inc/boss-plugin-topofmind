package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData

/**
 * Hierarchical structure for workspace tab sections
 */
sealed class WorkspaceTabStructure {
    data class TabItem(
        val activeTab: ActiveTabData
    ) : WorkspaceTabStructure()

    data class SplitSection(
        val sectionName: String,  // "Left", "Right", "Top", "Bottom"
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

    data class TabNode(
        override val id: String,
        override val name: String,
        override val level: Int,
        val activeTab: ActiveTabData
    ) : TabTreeNode()
}
