package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Top of Mind panel.
 *
 * Manages workspace state and tab navigation.
 */
class TopOfMindViewModel(
    private val workspaceDataProvider: WorkspaceDataProvider?,
    private val splitViewOperations: SplitViewOperations?,
    private val scope: CoroutineScope
) {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _expandedWorkspaces = MutableStateFlow<Set<String>>(emptySet())
    val expandedWorkspaces: StateFlow<Set<String>> = _expandedWorkspaces.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /**
     * Get workspaces from the provider.
     */
    val workspaces: StateFlow<List<LayoutWorkspace>>?
        get() = workspaceDataProvider?.workspaces

    /**
     * Get current workspace from the provider.
     */
    val currentWorkspace: StateFlow<LayoutWorkspace?>?
        get() = workspaceDataProvider?.currentWorkspace

    /**
     * Initialize the view model.
     */
    fun initialize() {
        // Expand all workspaces by default
        scope.launch {
            workspaceDataProvider?.workspaces?.collect { workspaceList ->
                _expandedWorkspaces.value = workspaceList.map { it.id }.toSet()
            }
        }
    }

    /**
     * Update search query.
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Toggle workspace expansion.
     */
    fun toggleWorkspace(workspaceId: String) {
        val current = _expandedWorkspaces.value
        _expandedWorkspaces.value = if (workspaceId in current) {
            current - workspaceId
        } else {
            current + workspaceId
        }
    }

    /**
     * Select a workspace.
     */
    fun selectWorkspace(workspace: LayoutWorkspace) {
        scope.launch {
            try {
                _isLoading.value = true
                workspaceDataProvider?.loadWorkspace(workspace)
                splitViewOperations?.applyWorkspace(workspace)
                _statusMessage.value = "Switched to ${workspace.name}"
            } catch (e: Exception) {
                _statusMessage.value = "Failed to switch workspace: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Delete a workspace.
     */
    fun deleteWorkspace(workspaceName: String) {
        scope.launch {
            try {
                workspaceDataProvider?.deleteWorkspace(workspaceName)
                _statusMessage.value = "Deleted workspace: $workspaceName"
            } catch (e: Exception) {
                _statusMessage.value = "Failed to delete workspace: ${e.message}"
            }
        }
    }

    /**
     * Rename a workspace.
     */
    fun renameWorkspace(oldName: String, newName: String) {
        scope.launch {
            try {
                workspaceDataProvider?.renameWorkspace(oldName, newName)
                _statusMessage.value = "Renamed workspace to: $newName"
            } catch (e: Exception) {
                _statusMessage.value = "Failed to rename workspace: ${e.message}"
            }
        }
    }

    /**
     * Save the current workspace.
     */
    fun saveCurrentWorkspace(name: String?) {
        scope.launch {
            try {
                val saved = workspaceDataProvider?.saveCurrentWorkspace(name)
                if (saved != null) {
                    _statusMessage.value = "Saved workspace: ${saved.name}"
                } else {
                    _statusMessage.value = "No workspace to save"
                }
            } catch (e: Exception) {
                _statusMessage.value = "Failed to save workspace: ${e.message}"
            }
        }
    }

    /**
     * Clear the status message.
     */
    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    /**
     * Check if providers are available.
     */
    fun isAvailable(): Boolean {
        return workspaceDataProvider != null && splitViewOperations != null
    }
}
