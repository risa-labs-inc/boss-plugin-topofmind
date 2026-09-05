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
    // Workspaces that are open, keyed by workspaceId. Derived output, never written by the UI
    // except through [toggleExpansion] - see [syncDefaultExpansion] for the rule it follows.
    private val _expandedWorkspaces = MutableStateFlow<Set<String>>(emptySet())
    val expandedWorkspaces: StateFlow<Set<String>> = _expandedWorkspaces

    // Split sections, keyed "workspaceId:sectionPath". Collapsed by default: a workspace that is
    // not split has no sections at all, and one that is usually wants its shape summarised first.
    private val _expandedSections = MutableStateFlow<Set<String>>(emptySet())
    val expandedSections: StateFlow<Set<String>> = _expandedSections

    /**
     * Workspaces the user has explicitly opened or closed, keyed by workspaceId.
     *
     * A Map rather than the old Set of collapsed ids, because the default is no longer a constant:
     * the workspace on screen is open and every other one is closed. "Absent from a set" could
     * express exceptions to a fixed rule, but it cannot tell "the user closed this" from "the rule
     * closed this", and the tree is rebuilt from scratch roughly every 2s. Only an explicit
     * three-way answer - open, closed, never touched - survives a rebuild without either re-opening
     * a group the user just closed or collapsing one they just opened.
     *
     * An override lasts the session and is never cleared. Switching to a workspace the user had
     * explicitly collapsed leaves it collapsed: a group that stays shut until they open it is a
     * smaller surprise than the panel overruling a click they made on purpose.
     */
    private val workspaceOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    fun toggleExpansion(workspaceId: String) {
        val wasExpanded = workspaceId in _expandedWorkspaces.value
        workspaceOverrides.value = workspaceOverrides.value + (workspaceId to !wasExpanded)
        _expandedWorkspaces.value =
            _expandedWorkspaces.value.toMutableSet().also {
                if (wasExpanded) it.remove(workspaceId) else it.add(workspaceId)
            }
    }

    /**
     * Re-derive which workspaces are open: the one on screen, plus every override the user has set.
     *
     * A pure function of (nodes, current workspace, overrides), so calling it on every rebuild is a
     * no-op unless one of those three actually changed. Call it when the current workspace changes
     * too, not only when the tree does - the default depends on which workspace is current.
     */
    fun syncDefaultExpansion(
        nodes: List<TabTreeNode>,
        currentWorkspaceId: String?,
    ) {
        val overrides = workspaceOverrides.value
        _expandedWorkspaces.value =
            nodes
                .filterIsInstance<TabTreeNode.WorkspaceNode>()
                .map { it.workspaceId }
                .filter { overrides[it] ?: (it == currentWorkspaceId) }
                .toSet()
    }

    fun toggleSectionExpansion(sectionKey: String) {
        val current = _expandedSections.value.toMutableSet()
        if (!current.remove(sectionKey)) current.add(sectionKey)
        _expandedSections.value = current
    }

    fun isSectionExpanded(sectionKey: String): Boolean = _expandedSections.value.contains(sectionKey)

    fun isWorkspaceExpanded(workspaceId: String): Boolean = _expandedWorkspaces.value.contains(workspaceId)
}
