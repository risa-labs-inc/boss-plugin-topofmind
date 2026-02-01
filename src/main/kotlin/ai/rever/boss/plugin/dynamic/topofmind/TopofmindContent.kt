package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.SplitConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope

/**
 * Top of Mind panel content (Dynamic Plugin).
 *
 * Displays workspaces and allows quick switching between them.
 */
@Composable
fun TopOfMindContent(
    workspaceDataProvider: WorkspaceDataProvider?,
    splitViewOperations: SplitViewOperations?,
    scope: CoroutineScope
) {
    val viewModel = remember(workspaceDataProvider, splitViewOperations, scope) {
        TopOfMindViewModel(workspaceDataProvider, splitViewOperations, scope)
    }

    BossTheme {
        if (!viewModel.isAvailable()) {
            NoProviderMessage()
        } else {
            WorkspaceList(viewModel)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initialize()
    }
}

@Composable
private fun NoProviderMessage() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Language,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colors.primary.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Top of Mind",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Workspace provider not available",
                fontSize = 13.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Please ensure the host provides workspace access",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun WorkspaceList(viewModel: TopOfMindViewModel) {
    val workspaces by viewModel.workspaces?.collectAsState() ?: return
    val currentWorkspace by viewModel.currentWorkspace?.collectAsState() ?: return
    val searchQuery by viewModel.searchQuery.collectAsState()
    val expandedWorkspaces by viewModel.expandedWorkspaces.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val listState = rememberLazyListState()

    // Filter workspaces by search query
    val filteredWorkspaces = remember(workspaces, searchQuery) {
        if (searchQuery.isEmpty()) {
            workspaces
        } else {
            workspaces.filter { workspace ->
                workspace.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Toolbar with search
            WorkspaceToolbar(
                searchQuery = searchQuery,
                onSearchChange = viewModel::updateSearchQuery,
                onSave = { viewModel.saveCurrentWorkspace(null) }
            )

            Divider(color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f))

            // Status message
            if (statusMessage != null) {
                StatusMessage(
                    message = statusMessage!!,
                    onDismiss = viewModel::clearStatusMessage
                )
            }

            // Loading indicator
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colors.primary
                )
            }

            // Workspace list
            Box(modifier = Modifier.fillMaxSize()) {
                if (filteredWorkspaces.isEmpty()) {
                    EmptyWorkspaceMessage(
                        isSearching = searchQuery.isNotEmpty()
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .lazyListScrollbar(listState, Orientation.Vertical, getPanelScrollbarConfig())
                    ) {
                        items(
                            items = filteredWorkspaces,
                            key = { it.id }
                        ) { workspace ->
                            WorkspaceItem(
                                workspace = workspace,
                                isExpanded = workspace.id in expandedWorkspaces,
                                isCurrent = currentWorkspace?.id == workspace.id,
                                onToggleExpand = { viewModel.toggleWorkspace(workspace.id) },
                                onSelect = { viewModel.selectWorkspace(workspace) },
                                onDelete = { viewModel.deleteWorkspace(workspace.name) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceToolbar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Search field
        Row(
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colors.background.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colors.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search workspaces...",
                                fontSize = 11.sp,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (searchQuery.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "Clear",
                    modifier = Modifier
                        .size(12.dp)
                        .clickable { onSearchChange("") },
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Save button
        IconButton(
            onClick = onSave,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Save,
                contentDescription = "Save Workspace",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun StatusMessage(
    message: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.primary.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            fontSize = 11.sp,
            color = MaterialTheme.colors.primary,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colors.primary
            )
        }
    }
}

@Composable
private fun EmptyWorkspaceMessage(isSearching: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isSearching) Icons.Outlined.Search else Icons.Outlined.Folder,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colors.onBackground.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isSearching) "No matching workspaces" else "No workspaces yet",
            fontSize = 12.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f)
        )
        if (!isSearching) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Save current layout to create one",
                fontSize = 10.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
private fun WorkspaceItem(
    workspace: LayoutWorkspace,
    isExpanded: Boolean,
    isCurrent: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .background(
                    if (isCurrent) MaterialTheme.colors.primary.copy(alpha = 0.1f)
                    else MaterialTheme.colors.background
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Expand/collapse icon
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onToggleExpand),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Folder icon
            Icon(
                imageVector = if (isExpanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isCurrent) MaterialTheme.colors.primary
                       else MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Workspace name
            Text(
                text = workspace.name,
                fontSize = 12.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colors.primary
                        else MaterialTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Current indicator
            if (isCurrent) {
                Text(
                    text = "Current",
                    fontSize = 9.sp,
                    color = MaterialTheme.colors.primary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // Delete button
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
                )
            }
        }

        // Workspace details when expanded
        if (isExpanded) {
            WorkspaceDetails(workspace)
        }

        // Delete confirmation dialog
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete Workspace") },
                text = { Text("Are you sure you want to delete \"${workspace.name}\"?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDelete()
                            showDeleteConfirm = false
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colors.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun WorkspaceDetails(workspace: LayoutWorkspace) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 8.dp, bottom = 4.dp)
    ) {
        // Project path
        if (!workspace.projectPath.isNullOrEmpty()) {
            DetailRow(
                label = "Project",
                value = workspace.projectPath ?: ""
            )
        }

        // Panel count
        val panelCount = countPanels(workspace)
        if (panelCount > 0) {
            DetailRow(
                label = "Panels",
                value = "$panelCount panel${if (panelCount > 1) "s" else ""}"
            )
        }

        // Tab count
        val tabCount = countTabs(workspace)
        if (tabCount > 0) {
            DetailRow(
                label = "Tabs",
                value = "$tabCount tab${if (tabCount > 1) "s" else ""}"
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label: ",
            fontSize = 10.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
        )
        Text(
            text = value,
            fontSize = 10.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Count the number of panels in a workspace layout.
 */
private fun countPanels(workspace: LayoutWorkspace): Int {
    return countPanelsInSplitConfig(workspace.layout)
}

/**
 * Count panels in a SplitConfig recursively.
 */
private fun countPanelsInSplitConfig(config: SplitConfig): Int {
    return when (config) {
        is SplitConfig.SinglePanel -> 1
        is SplitConfig.VerticalSplit ->
            countPanelsInSplitConfig(config.left) + countPanelsInSplitConfig(config.right)
        is SplitConfig.HorizontalSplit ->
            countPanelsInSplitConfig(config.top) + countPanelsInSplitConfig(config.bottom)
    }
}

/**
 * Count the total number of tabs in a workspace.
 */
private fun countTabs(workspace: LayoutWorkspace): Int {
    return countTabsInSplitConfig(workspace.layout)
}

/**
 * Count tabs in a SplitConfig recursively.
 */
private fun countTabsInSplitConfig(config: SplitConfig): Int {
    return when (config) {
        is SplitConfig.SinglePanel -> config.panel.tabs.size
        is SplitConfig.VerticalSplit ->
            countTabsInSplitConfig(config.left) + countTabsInSplitConfig(config.right)
        is SplitConfig.HorizontalSplit ->
            countTabsInSplitConfig(config.top) + countTabsInSplitConfig(config.bottom)
    }
}
