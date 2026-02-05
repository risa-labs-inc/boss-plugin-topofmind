package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Top of Mind panel content (Dynamic Plugin).
 *
 * Displays currently active/running tabs across all workspaces,
 * matching the bundled plugin behavior.
 */
@Composable
fun TopOfMindContent(
    activeTabsProvider: ActiveTabsProvider?,
    workspaceDataProvider: WorkspaceDataProvider?,
    splitViewOperations: SplitViewOperations?,
    scope: CoroutineScope
) {
    BossTheme {
        if (activeTabsProvider == null) {
            NoProviderMessage()
        } else {
            ActiveTabsTreeContent(
                activeTabsProvider = activeTabsProvider,
                workspaceDataProvider = workspaceDataProvider,
                splitViewOperations = splitViewOperations,
                scope = scope
            )
        }
    }
}

@Composable
private fun NoProviderMessage() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF2B2D30)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Workspaces,
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
                text = "Active tabs provider not available",
                fontSize = 13.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun ActiveTabsTreeContent(
    activeTabsProvider: ActiveTabsProvider,
    workspaceDataProvider: WorkspaceDataProvider?,
    splitViewOperations: SplitViewOperations?,
    scope: CoroutineScope
) {
    val activeTabs by activeTabsProvider.activeTabs.collectAsState()

    // Refresh tabs on composition and periodically poll for updates
    LaunchedEffect(activeTabsProvider) {
        activeTabsProvider.refreshTabs()  // Initial refresh
        while (true) {
            delay(1000)  // Poll every second
            activeTabsProvider.refreshTabs()
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var showCurrentWorkspace by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // Get current workspace ID for filtering
    val currentWorkspaceId = workspaceDataProvider?.currentWorkspace?.collectAsState()?.value?.id

    // Filter tabs based on showCurrentWorkspace toggle
    val filteredTabs = if (showCurrentWorkspace) {
        activeTabs
    } else {
        activeTabs.filter { it.workspaceId != currentWorkspaceId }
    }

    // Build tree structure from active tabs using workspace layout
    val treeNodes = remember(filteredTabs, workspaceDataProvider) {
        TabTreeBuilder.buildTree(filteredTabs, workspaceDataProvider)
    }

    // Initialize default expansion
    LaunchedEffect(treeNodes) {
        TabTreeState.initializeDefaultExpansion(treeNodes)
    }

    // Apply search filter
    val filteredTreeNodes = remember(treeNodes, searchQuery) {
        if (searchQuery.isBlank()) {
            treeNodes
        } else {
            TabTreeBuilder.filterTreeNodes(treeNodes, searchQuery)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF2B2D30)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Search bar
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search active tabs..."
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Header with toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Running Workspaces",
                    fontSize = 10.sp,
                    color = Color.Gray,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = if (showCurrentWorkspace) "Hide Current" else "Show Current",
                    fontSize = 9.sp,
                    color = MaterialTheme.colors.primary.copy(alpha = 0.8f),
                    modifier = Modifier.clickable { showCurrentWorkspace = !showCurrentWorkspace }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Content
            if (filteredTreeNodes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            if (searchQuery.isNotBlank()) Icons.Outlined.Search else Icons.Outlined.Tab,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = Color.Gray.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (searchQuery.isNotBlank())
                                "No tabs matching \"$searchQuery\""
                            else
                                "No active tabs",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .lazyListScrollbar(
                            listState = listState,
                            direction = Orientation.Vertical,
                            config = getPanelScrollbarConfig()
                        ),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredTreeNodes) { treeNode ->
                        TreeNodeItem(
                            node = treeNode,
                            currentWorkspaceId = currentWorkspaceId,
                            activeTabsProvider = activeTabsProvider,
                            workspaceDataProvider = workspaceDataProvider,
                            splitViewOperations = splitViewOperations,
                            scope = scope
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1E1F22))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = Color.Gray.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 11.sp,
                color = Color.White
            ),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = 11.sp,
                            color = Color.Gray.copy(alpha = 0.5f)
                        )
                    }
                    innerTextField()
                }
            }
        )
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = "Clear",
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onQueryChange("") },
                tint = Color.Gray.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun TreeNodeItem(
    node: TabTreeNode,
    currentWorkspaceId: String?,
    activeTabsProvider: ActiveTabsProvider,
    workspaceDataProvider: WorkspaceDataProvider?,
    splitViewOperations: SplitViewOperations?,
    scope: CoroutineScope
) {
    val expandedNodes by TabTreeState.expandedNodes.collectAsState()
    val isExpanded = expandedNodes.contains(node.id)

    Column {
        when (node) {
            is TabTreeNode.WorkspaceNode -> {
                WorkspaceFolderItem(
                    node = node,
                    isExpanded = isExpanded,
                    isActive = currentWorkspaceId == node.workspaceId,
                    onToggleExpand = { TabTreeState.toggleExpansion(node.id) },
                    onWorkspaceClick = {
                        // Switch to this workspace
                        if (splitViewOperations != null && workspaceDataProvider != null) {
                            scope.launch {
                                val currentWorkspace = workspaceDataProvider.currentWorkspace.value
                                val targetWorkspace = workspaceDataProvider.workspaces.value.find {
                                    it.id == node.workspaceId
                                }

                                if (targetWorkspace != null && currentWorkspace?.id != node.workspaceId) {
                                    // Preserve current state before switching
                                    if (currentWorkspace != null && currentWorkspace.id.isNotEmpty()) {
                                        splitViewOperations.preserveCurrentState(currentWorkspace.id, currentWorkspace.name)
                                    }
                                    // Load and apply the target workspace
                                    workspaceDataProvider.loadWorkspace(targetWorkspace)
                                    splitViewOperations.applyWorkspace(targetWorkspace)
                                }
                            }
                        }
                    }
                )

                if (isExpanded) {
                    RenderTabStructure(
                        structure = node.tabStructure,
                        workspaceId = node.workspaceId,
                        activeTabsProvider = activeTabsProvider,
                        onTabClick = { activeTab ->
                            scope.launch {
                                // Select the tab
                                activeTabsProvider.selectTab(activeTab.tabId, activeTab.panelId)
                            }
                        }
                    )
                }
            }

            is TabTreeNode.TabNode -> {
                TabCardItem(
                    activeTab = node.activeTab,
                    activeTabsProvider = activeTabsProvider,
                    onTabClick = {
                        scope.launch {
                            activeTabsProvider.selectTab(node.activeTab.tabId, node.activeTab.panelId)
                        }
                    },
                    indentation = 44.dp
                )
            }
        }
    }
}

@Composable
private fun WorkspaceFolderItem(
    node: TabTreeNode.WorkspaceNode,
    isExpanded: Boolean,
    isActive: Boolean,
    onToggleExpand: () -> Unit,
    onWorkspaceClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp)
            .then(
                if (isActive) {
                    Modifier
                        .background(
                            MaterialTheme.colors.primary.copy(alpha = 0.1f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(2.dp)
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Expand/collapse button
        Icon(
            if (isExpanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier = Modifier
                .size(16.dp)
                .clickable { onToggleExpand() },
            tint = Color.Gray.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Workspace content area (clickable)
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { onWorkspaceClick() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Workspaces,
                contentDescription = "Workspace",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colors.primary.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "${node.name}${if (isActive) " (Active)" else ""}",
                fontSize = 12.sp,
                color = if (isActive) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Tab count badge
            Text(
                text = "${node.tabCount}",
                fontSize = 9.sp,
                color = Color.Gray,
                modifier = Modifier
                    .background(Color(0xFF3C3F43), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun TabCardItem(
    activeTab: ActiveTabData,
    activeTabsProvider: ActiveTabsProvider,
    onTabClick: () -> Unit,
    indentation: Dp = 44.dp
) {
    // Get favicon using the provider - returns Painter? directly
    val faviconCacheKey = activeTab.faviconCacheKey
    val faviconPainter = activeTabsProvider.loadFavicon(faviconCacheKey)
    val fallbackIcon = activeTabsProvider.getFallbackIcon(activeTab.typeId)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentation, end = 24.dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable { onTabClick() },
        color = Color(0xFF3C3F43),
        elevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tab icon with favicon support
            if (faviconPainter != null) {
                Image(
                    painter = faviconPainter,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            } else if (fallbackIcon != null) {
                Icon(
                    imageVector = fallbackIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = getTabIconColor(activeTab.typeId)
                )
            } else {
                Icon(
                    imageVector = getTabIcon(activeTab.typeId),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = getTabIconColor(activeTab.typeId)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Tab title
                Text(
                    text = activeTab.title.ifEmpty { "Untitled" },
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Tab URL or type
                val tabUrl = activeTab.url
                val subtitle = if (!tabUrl.isNullOrEmpty()) {
                    tabUrl.removePrefix("https://").removePrefix("http://").take(50)
                } else {
                    activeTab.typeId
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        fontSize = 9.sp,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitSectionHeader(
    sectionName: String,
    level: Int,
    sectionKey: String,
    isExpanded: Boolean,
    onToggleExpansion: () -> Unit
) {
    val indentation = (44 + (level * 16)).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpansion() }
            .padding(start = indentation, end = 24.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Chevron icon
        Icon(
            imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier = Modifier.size(14.dp),
            tint = Color(0xFF9CA3AF)
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Left divider
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(1.dp)
                .background(Color(0xFF4B5563))
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Section name
        Text(
            text = sectionName,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF9CA3AF),
            letterSpacing = 0.5.sp
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Right divider
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Color(0xFF4B5563))
        )
    }
}

@Composable
private fun RenderTabStructure(
    structure: List<WorkspaceTabStructure>,
    workspaceId: String,
    activeTabsProvider: ActiveTabsProvider,
    onTabClick: (ActiveTabData) -> Unit,
    sectionPath: String = "",
    baseIndentation: Int = 44
) {
    val expandedSections by TabTreeState.expandedSections.collectAsState()

    structure.forEach { item ->
        when (item) {
            is WorkspaceTabStructure.TabItem -> {
                TabCardItem(
                    activeTab = item.activeTab,
                    activeTabsProvider = activeTabsProvider,
                    onTabClick = { onTabClick(item.activeTab) },
                    indentation = baseIndentation.dp
                )
            }

            is WorkspaceTabStructure.SplitSection -> {
                val currentPath = if (sectionPath.isEmpty()) item.sectionName else "$sectionPath/${item.sectionName}"
                val sectionKey = "$workspaceId:$currentPath"
                val isExpanded = expandedSections.contains(sectionKey)

                SplitSectionHeader(
                    sectionName = item.sectionName,
                    level = item.level,
                    sectionKey = sectionKey,
                    isExpanded = isExpanded,
                    onToggleExpansion = { TabTreeState.toggleSectionExpansion(sectionKey) }
                )

                if (isExpanded) {
                    RenderTabStructure(
                        structure = item.children,
                        workspaceId = workspaceId,
                        activeTabsProvider = activeTabsProvider,
                        onTabClick = onTabClick,
                        sectionPath = currentPath,
                        baseIndentation = baseIndentation + (item.level * 16)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TAB ICONS
// ═══════════════════════════════════════════════════════════════════════════

private fun getTabIcon(typeId: String): ImageVector = when {
    typeId.contains("browser", ignoreCase = true) -> Icons.Outlined.Language
    typeId.contains("terminal", ignoreCase = true) -> Icons.Outlined.Terminal
    typeId.contains("editor", ignoreCase = true) -> Icons.Outlined.Code
    else -> Icons.Outlined.Tab
}

@Composable
private fun getTabIconColor(typeId: String): Color = when {
    typeId.contains("browser", ignoreCase = true) -> Color(0xFF4285F4)
    typeId.contains("terminal", ignoreCase = true) -> Color(0xFF4EAA25)
    typeId.contains("editor", ignoreCase = true) -> Color(0xFFE06C75)
    else -> MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
}
