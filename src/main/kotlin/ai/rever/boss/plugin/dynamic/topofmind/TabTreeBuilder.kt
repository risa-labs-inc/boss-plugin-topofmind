package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.workspace.SplitConfig

/**
 * Utility to build tree structure from active tabs
 */
object TabTreeBuilder {

    /**
     * Extract all panel IDs from layout in depth-first order
     */
    private fun extractPanelIds(layout: SplitConfig): List<String> {
        return when (layout) {
            is SplitConfig.SinglePanel -> listOf(layout.panel.id)
            is SplitConfig.VerticalSplit -> extractPanelIds(layout.left) + extractPanelIds(layout.right)
            is SplitConfig.HorizontalSplit -> extractPanelIds(layout.top) + extractPanelIds(layout.bottom)
        }
    }

    /**
     * Build hierarchical tab structure from workspace layout and panel assignments
     * Uses position-based mapping instead of ID-based filtering to handle randomly-generated panel IDs
     */
    private fun buildTabStructure(
        tabs: List<ActiveTabData>,
        layout: SplitConfig?,
        panelIdMapping: Map<String, String>,
        level: Int = 0
    ): List<WorkspaceTabStructure> {
        if (layout == null || tabs.isEmpty() || panelIdMapping.isEmpty()) {
            // No layout info, no tabs, or panel count mismatch - return flat list
            return tabs.map { WorkspaceTabStructure.TabItem(it) }
        }

        return when (layout) {
            is SplitConfig.SinglePanel -> {
                // Map layout panel ID to runtime panel ID
                val runtimePanelId = panelIdMapping[layout.panel.id]
                val panelTabs = if (runtimePanelId != null) {
                    tabs.filter { it.panelId == runtimePanelId }
                } else {
                    // Fallback: if mapping fails, try direct ID match
                    tabs.filter { it.panelId == layout.panel.id }
                }
                panelTabs.map { WorkspaceTabStructure.TabItem(it) }
            }

            is SplitConfig.VerticalSplit -> {
                listOf(
                    WorkspaceTabStructure.SplitSection(
                        sectionName = "Left",
                        children = buildTabStructure(tabs, layout.left, panelIdMapping, level + 1),
                        level = level
                    ),
                    WorkspaceTabStructure.SplitSection(
                        sectionName = "Right",
                        children = buildTabStructure(tabs, layout.right, panelIdMapping, level + 1),
                        level = level
                    )
                )
            }

            is SplitConfig.HorizontalSplit -> {
                listOf(
                    WorkspaceTabStructure.SplitSection(
                        sectionName = "Top",
                        children = buildTabStructure(tabs, layout.top, panelIdMapping, level + 1),
                        level = level
                    ),
                    WorkspaceTabStructure.SplitSection(
                        sectionName = "Bottom",
                        children = buildTabStructure(tabs, layout.bottom, panelIdMapping, level + 1),
                        level = level
                    )
                )
            }
        }
    }

    /**
     * Build tree structure from active tabs, grouped by workspace
     *
     * @param activeTabs List of all active tabs
     * @param workspaceDataProvider Provider for workspace data (to access layouts)
     */
    fun buildTree(
        activeTabs: List<ActiveTabData>,
        workspaceDataProvider: WorkspaceDataProvider? = null
    ): List<TabTreeNode> {
        // Group tabs by workspace
        val workspaceGroups = activeTabs.groupBy { it.workspaceId }
        val rootNodes = mutableListOf<TabTreeNode>()

        workspaceGroups.forEach { (workspaceId, tabs) ->
            val workspaceName = tabs.firstOrNull()?.workspaceName ?: "Unknown"

            // Get workspace layout from WorkspaceDataProvider
            val workspace = workspaceDataProvider?.workspaces?.value?.find { it.id == workspaceId }
            val layout = workspace?.layout

            // Create panel ID mapping: layout panel ID -> runtime panel ID
            // Match panels by their position in depth-first traversal
            val panelIdMapping = if (layout != null) {
                val layoutPanelIds = extractPanelIds(layout)
                val runtimePanelIds = tabs.map { it.panelId }.distinct()

                // Validate panel count matches
                if (layoutPanelIds.size != runtimePanelIds.size) {
                    // Fallback: return empty map to trigger flat layout rendering
                    emptyMap()
                } else {
                    // Map layout panel IDs to runtime panel IDs by position
                    layoutPanelIds.zip(runtimePanelIds).toMap()
                }
            } else {
                emptyMap()
            }

            // Build tab structure based on layout with panel ID mapping
            val tabStructure = buildTabStructure(tabs, layout, panelIdMapping)

            val workspaceNode = TabTreeNode.WorkspaceNode(
                id = "workspace-$workspaceId",
                name = workspaceName,
                workspaceId = workspaceId,
                level = 0,
                tabStructure = tabStructure,
                tabCount = tabs.size
            )

            rootNodes.add(workspaceNode)
        }

        return rootNodes
    }

    /**
     * Filter tab structure based on search query
     */
    private fun filterTabStructure(
        structure: List<WorkspaceTabStructure>,
        searchQuery: String
    ): List<WorkspaceTabStructure> {
        return structure.mapNotNull { item ->
            when (item) {
                is WorkspaceTabStructure.TabItem -> {
                    val tabUrl = item.activeTab.url
                    val tabMatches = item.activeTab.title.contains(searchQuery, ignoreCase = true) ||
                            (tabUrl?.contains(searchQuery, ignoreCase = true) == true)
                    if (tabMatches) item else null
                }

                is WorkspaceTabStructure.SplitSection -> {
                    val matchingChildren = filterTabStructure(item.children, searchQuery)
                    if (matchingChildren.isNotEmpty()) {
                        item.copy(children = matchingChildren)
                    } else null
                }
            }
        }
    }

    /**
     * Filter tree nodes based on search query
     */
    fun filterTreeNodes(
        nodes: List<TabTreeNode>,
        searchQuery: String
    ): List<TabTreeNode> {
        return nodes.mapNotNull { node ->
            when (node) {
                is TabTreeNode.WorkspaceNode -> {
                    val filteredStructure = filterTabStructure(node.tabStructure, searchQuery)
                    val workspaceMatches = node.name.contains(searchQuery, ignoreCase = true)

                    if (workspaceMatches || filteredStructure.isNotEmpty()) {
                        node.copy(tabStructure = filteredStructure)
                    } else null
                }

                is TabTreeNode.TabNode -> {
                    val tabUrl = node.activeTab.url
                    val tabMatches = node.activeTab.title.contains(searchQuery, ignoreCase = true) ||
                            (tabUrl?.contains(searchQuery, ignoreCase = true) == true)
                    if (tabMatches) node else null
                }
            }
        }
    }
}
