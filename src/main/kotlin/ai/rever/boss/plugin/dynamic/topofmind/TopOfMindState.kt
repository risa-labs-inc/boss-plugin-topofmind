package ai.rever.boss.plugin.dynamic.topofmind

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * State management for tree expansion
 */
object TabTreeState {
    private val _expandedNodes = MutableStateFlow<Set<String>>(emptySet())
    val expandedNodes: StateFlow<Set<String>> = _expandedNodes

    // Track expanded sections (workspace:sectionPath) - sections collapsed by default
    private val _expandedSections = MutableStateFlow<Set<String>>(emptySet())
    val expandedSections: StateFlow<Set<String>> = _expandedSections

    fun toggleExpansion(nodeId: String) {
        val current = _expandedNodes.value.toMutableSet()
        if (current.contains(nodeId)) {
            current.remove(nodeId)
        } else {
            current.add(nodeId)
        }
        _expandedNodes.value = current
    }

    fun initializeDefaultExpansion(nodes: List<TabTreeNode>) {
        // Expand all workspace nodes by default
        val workspaceNodes = nodes.filterIsInstance<TabTreeNode.WorkspaceNode>()
        _expandedNodes.value = workspaceNodes.map { it.id }.toSet()
    }

    fun toggleSectionExpansion(sectionKey: String) {
        val current = _expandedSections.value.toMutableSet()
        if (current.contains(sectionKey)) {
            current.remove(sectionKey)
        } else {
            current.add(sectionKey)
        }
        _expandedSections.value = current
    }

    fun isSectionExpanded(sectionKey: String): Boolean {
        return _expandedSections.value.contains(sectionKey)
    }

    fun isExpanded(nodeId: String): Boolean {
        return _expandedNodes.value.contains(nodeId)
    }
}
