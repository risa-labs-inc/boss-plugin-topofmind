package ai.rever.boss.plugin.dynamic.topofmind

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which WORKSPACE groups in the tree are open.
 *
 * Split sections are not here: which pane is showing all its tabs is [SplitPaneExpansion], because
 * that answer is mostly derived (the pane being worked in, plus whichever one the pointer chose)
 * rather than a set the user edits.
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

    // Split sections used to have a set of their own here, toggled by clicking the header, and
    // collapsed meant showing NOTHING under it. That is [SplitPaneExpansion]'s job now: a pane
    // collapses to the tab it is showing rather than to nothing, the pane being worked in is
    // always open, and hovering a header chooses which of the others is. Two notions of "this
    // section is expanded" would have been two answers to one question.

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

    fun isWorkspaceExpanded(workspaceId: String): Boolean = _expandedWorkspaces.value.contains(workspaceId)
}
