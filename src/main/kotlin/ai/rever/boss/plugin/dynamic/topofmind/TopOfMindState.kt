package ai.rever.boss.plugin.dynamic.topofmind

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which groups in the tree are open.
 *
 * A CLASS, one instance per mounted panel (see [TopofmindComponent]), where this used to be a
 * top-level `object`. Process-global expansion meant collapsing a workspace in one window
 * collapsed it in every other panel instance too, which read as the panel losing its place.
 */
class TabTreeState {
    private val _expandedNodes = MutableStateFlow<Set<String>>(emptySet())
    val expandedNodes: StateFlow<Set<String>> = _expandedNodes

    // Split sections, keyed "workspaceId:sectionPath". Collapsed by default: a workspace that is
    // not split has no sections at all, and one that is usually wants its shape summarised first.
    private val _expandedSections = MutableStateFlow<Set<String>>(emptySet())
    val expandedSections: StateFlow<Set<String>> = _expandedSections

    /**
     * Workspaces the user has explicitly collapsed.
     *
     * Tracked separately from [_expandedNodes] because the default is EXPANDED and the tree is
     * rebuilt from scratch on every refresh. Seeding "everything expanded" on each rebuild would
     * re-open a group the user just closed; seeding nothing would collapse everything the moment a
     * tab changed. Recording the exceptions is the only version of this that survives a rebuild.
     */
    private val collapsedWorkspaces = MutableStateFlow<Set<String>>(emptySet())

    fun toggleExpansion(nodeId: String) {
        val collapsed = collapsedWorkspaces.value.toMutableSet()
        if (!collapsed.remove(nodeId)) collapsed.add(nodeId)
        collapsedWorkspaces.value = collapsed
        _expandedNodes.value =
            _expandedNodes.value.toMutableSet().also {
                if (nodeId in collapsed) it.remove(nodeId) else it.add(nodeId)
            }
    }

    /** Open every workspace the user has not explicitly closed. Safe to call on every rebuild. */
    fun syncDefaultExpansion(nodes: List<TabTreeNode>) {
        val collapsed = collapsedWorkspaces.value
        _expandedNodes.value =
            nodes
                .filterIsInstance<TabTreeNode.WorkspaceNode>()
                .map { it.id }
                .filterNot { it in collapsed }
                .toSet()
    }

    fun toggleSectionExpansion(sectionKey: String) {
        val current = _expandedSections.value.toMutableSet()
        if (!current.remove(sectionKey)) current.add(sectionKey)
        _expandedSections.value = current
    }

    fun isSectionExpanded(sectionKey: String): Boolean = _expandedSections.value.contains(sectionKey)

    fun isExpanded(nodeId: String): Boolean = _expandedNodes.value.contains(nodeId)
}
