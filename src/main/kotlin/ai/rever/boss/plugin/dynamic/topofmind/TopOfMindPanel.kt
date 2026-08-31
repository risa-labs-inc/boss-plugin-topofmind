package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossEmptyState
import ai.rever.boss.plugin.ui.BossSearchBar
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a just-moved row stays highlighted in its new group. */
private const val MOVED_FLASH_MS = 1_400L

private val PANEL_PADDING = 8.dp
private val SEARCH_HEIGHT = 28.dp

/** Depth indent for tabs and nested split sections, matching the tab bar's group nesting. */
private const val INDENT_STEP = 12

@Composable
fun TopOfMindContent(
    activeTabsProvider: ActiveTabsProvider?,
    workspaceDataProvider: WorkspaceDataProvider?,
    splitViewOperations: SplitViewOperations?,
    contextMenuProvider: ContextMenuProvider?,
    treeState: TabTreeState,
    dragState: TabDragState,
    scope: CoroutineScope,
) {
    BossTheme {
        if (activeTabsProvider == null) {
            Surface(modifier = Modifier.fillMaxSize(), color = BossThemeColors.SurfaceColor) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    BossEmptyState(
                        icon = Icons.Outlined.Workspaces,
                        message = "Top of Mind",
                        description = "Active tabs provider not available",
                    )
                }
            }
        } else {
            TabTree(
                activeTabsProvider = activeTabsProvider,
                workspaceDataProvider = workspaceDataProvider,
                splitViewOperations = splitViewOperations,
                contextMenuProvider = contextMenuProvider,
                treeState = treeState,
                dragState = dragState,
                scope = scope,
            )
        }
    }
}

@Composable
private fun TabTree(
    activeTabsProvider: ActiveTabsProvider,
    workspaceDataProvider: WorkspaceDataProvider?,
    splitViewOperations: SplitViewOperations?,
    contextMenuProvider: ContextMenuProvider?,
    treeState: TabTreeState,
    dragState: TabDragState,
    scope: CoroutineScope,
) {
    val activeTabs by activeTabsProvider.activeTabs.collectAsState()

    // One refresh when the panel appears, and one after anything this panel changes. The host
    // adapter runs its own 2s poll and pushes into this StateFlow, so the 1s loop that used to
    // live here was a second timer asking the same question twice as often.
    LaunchedEffect(activeTabsProvider) { activeTabsProvider.refreshTabs() }

    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val currentWorkspaceId = workspaceDataProvider?.currentWorkspace?.collectAsState()?.value?.id
    val transferSupported = remember(activeTabsProvider) { TabTransfer.isSupported(activeTabsProvider) }

    val treeNodes = remember(activeTabs) { TabTreeBuilder.buildTree(activeTabs, workspaceDataProvider) }
    LaunchedEffect(treeNodes) { treeState.syncDefaultExpansion(treeNodes) }

    val visibleNodes =
        remember(treeNodes, searchQuery) {
            if (searchQuery.isBlank()) treeNodes else TabTreeBuilder.filterTreeNodes(treeNodes, searchQuery)
        }

    // The flash is cleared here rather than by the row, so it survives the row being recomposed
    // into a different group - which is exactly what a move does to it.
    LaunchedEffect(dragState.recentlyMovedTabId) {
        if (dragState.recentlyMovedTabId != null) {
            delay(MOVED_FLASH_MS)
            dragState.recentlyMovedTabId = null
        }
    }

    fun moveTab(
        tab: ActiveTabData,
        targetWorkspaceId: String,
    ) {
        scope.launch {
            if (TabTransfer.move(activeTabsProvider, tab.tabId, targetWorkspaceId)) {
                dragState.recentlyMovedTabId = tab.tabId
                // Do not wait for the host's poll: the whole point of the flash is that the row
                // has already reappeared under its new workspace by the time you look.
                activeTabsProvider.refreshTabs()
            }
        }
    }

    // The panel's own position in the window. The drag reports the pointer in window coordinates
    // (a row and a workspace header share no parent, so nothing else is comparable), and the ghost
    // is placed inside this Box - so one of the two has to be converted, and this is the only place
    // that knows the offset between them.
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }

    Surface(modifier = Modifier.fillMaxSize(), color = BossThemeColors.SurfaceColor) {
        Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { panelOrigin = it.boundsInWindow().topLeft }) {
        Column(modifier = Modifier.fillMaxSize()) {
            BossSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search tabs",
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(SEARCH_HEIGHT)
                        .padding(horizontal = PANEL_PADDING),
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (visibleNodes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BossEmptyState(
                        icon = if (searchQuery.isNotBlank()) Icons.Outlined.Search else Icons.Outlined.Tab,
                        message = if (searchQuery.isNotBlank()) "No matches" else "No active tabs",
                        description =
                            if (searchQuery.isNotBlank()) {
                                "Nothing matching \"$searchQuery\""
                            } else {
                                "Open a tab and it will show up here"
                            },
                    )
                }
            } else {
                // Edge to edge, no gaps between rows. Separation comes from the fill, the way the
                // tab bar does it - a 2dp gutter between elevated cards was what made this panel
                // read as a list of widgets rather than a list of tabs.
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .lazyListScrollbar(
                                listState = listState,
                                direction = Orientation.Vertical,
                                config = getPanelScrollbarConfig(),
                            ),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    visibleNodes.forEachIndexed { index, node ->
                        workspaceGroup(
                            node = node,
                            isFirst = index == 0,
                            currentWorkspaceId = currentWorkspaceId,
                            allTabs = activeTabs,
                            activeTabsProvider = activeTabsProvider,
                            workspaceDataProvider = workspaceDataProvider,
                            splitViewOperations = splitViewOperations,
                            contextMenuProvider = contextMenuProvider,
                            treeState = treeState,
                            dragState = dragState.takeIf { transferSupported },
                            transferSupported = transferSupported,
                            scope = scope,
                            onMove = ::moveTab,
                        )
                    }
                }
            }
        }

        // Last child, so it paints over the list rather than under it. Outside the LazyColumn on
        // purpose: a ghost emitted from a row would be clipped to that row and would scroll with it.
        TabDragGhost(
            dragState = dragState,
            activeTabsProvider = activeTabsProvider,
            panelOrigin = panelOrigin,
        )
        }
    }
}

/**
 * One workspace and everything under it, emitted as flat list items.
 *
 * Flat rather than one item per workspace so a 200-tab window scrolls like a list instead of
 * measuring every group on every frame.
 */
@Suppress("LongParameterList")
private fun androidx.compose.foundation.lazy.LazyListScope.workspaceGroup(
    node: TabTreeNode,
    isFirst: Boolean,
    currentWorkspaceId: String?,
    allTabs: List<ActiveTabData>,
    activeTabsProvider: ActiveTabsProvider,
    workspaceDataProvider: WorkspaceDataProvider?,
    splitViewOperations: SplitViewOperations?,
    contextMenuProvider: ContextMenuProvider?,
    treeState: TabTreeState,
    dragState: TabDragState?,
    transferSupported: Boolean,
    scope: CoroutineScope,
    onMove: (ActiveTabData, String) -> Unit,
) {
    if (node !is TabTreeNode.WorkspaceNode) return

    item(key = node.id) {
        val expanded by treeState.expandedNodes.collectAsState()
        WorkspaceHeader(
            node = node,
            isExpanded = node.id in expanded,
            isCurrent = currentWorkspaceId == node.workspaceId,
            dragState = dragState,
            showRuleAbove = !isFirst,
            onToggleExpand = { treeState.toggleExpansion(node.id) },
            onActivate = {
                switchToWorkspace(node.workspaceId, workspaceDataProvider, splitViewOperations, scope)
            },
        )
    }

    item(key = "${node.id}-body") {
        val expanded by treeState.expandedNodes.collectAsState()
        if (node.id in expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabStructure(
                    structure = node.tabStructure,
                    workspaceId = node.workspaceId,
                    depth = 0,
                    currentWorkspaceId = currentWorkspaceId,
                    allTabs = allTabs,
                    activeTabsProvider = activeTabsProvider,
                    workspaceDataProvider = workspaceDataProvider,
                    contextMenuProvider = contextMenuProvider,
                    treeState = treeState,
                    dragState = dragState,
                    transferSupported = transferSupported,
                    onMove = onMove,
                )
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun TabStructure(
    structure: List<WorkspaceTabStructure>,
    workspaceId: String,
    depth: Int,
    currentWorkspaceId: String?,
    allTabs: List<ActiveTabData>,
    activeTabsProvider: ActiveTabsProvider,
    workspaceDataProvider: WorkspaceDataProvider?,
    contextMenuProvider: ContextMenuProvider?,
    treeState: TabTreeState,
    dragState: TabDragState?,
    transferSupported: Boolean,
    onMove: (ActiveTabData, String) -> Unit,
    sectionPath: String = "",
) {
    val expandedSections by treeState.expandedSections.collectAsState()

    structure.forEach { item ->
        when (item) {
            is WorkspaceTabStructure.TabItem -> {
                val tab = item.activeTab
                val targets =
                    if (transferSupported) {
                        TabTransfer.targetsFor(tab, activeTabsProvider, workspaceDataProvider, allTabs)
                    } else {
                        emptyList()
                    }
                TabRow(
                    tab = tab,
                    activeTabsProvider = activeTabsProvider,
                    contextMenuProvider = contextMenuProvider,
                    dragState = dragState,
                    transferTargets = targets,
                    inCurrentWorkspace = tab.workspaceId == currentWorkspaceId,
                    indent = (INDENT_STEP * (depth + 1)).dp,
                    onClick = { activeTabsProvider.selectTab(tab.tabId, tab.panelId) },
                    onClose = { activeTabsProvider.closeTab(tab.tabId) },
                    onMoveTo = { target -> onMove(tab, target) },
                )
            }

            is WorkspaceTabStructure.SplitSection -> {
                val path = if (sectionPath.isEmpty()) item.sectionName else "$sectionPath/${item.sectionName}"
                val key = "$workspaceId:$path"
                val isExpanded = key in expandedSections

                SplitSectionHeader(
                    sectionName = item.sectionName,
                    indentDp = INDENT_STEP * (depth + 1),
                    isExpanded = isExpanded,
                    onToggleExpansion = { treeState.toggleSectionExpansion(key) },
                )

                if (isExpanded) {
                    TabStructure(
                        structure = item.children,
                        workspaceId = workspaceId,
                        depth = depth + 1,
                        currentWorkspaceId = currentWorkspaceId,
                        allTabs = allTabs,
                        activeTabsProvider = activeTabsProvider,
                        workspaceDataProvider = workspaceDataProvider,
                        contextMenuProvider = contextMenuProvider,
                        treeState = treeState,
                        dragState = dragState,
                        transferSupported = transferSupported,
                        onMove = onMove,
                        sectionPath = path,
                    )
                }
            }
        }
    }
}

/**
 * Bring a workspace on screen: preserve what is showing, load the target, apply it.
 *
 * All three steps, in that order, or switching away and back loses a layout - the same sequence
 * the host's own `rememberWorkspaceSwitch` performs.
 */
private fun switchToWorkspace(
    workspaceId: String,
    workspaceDataProvider: WorkspaceDataProvider?,
    splitViewOperations: SplitViewOperations?,
    scope: CoroutineScope,
) {
    if (workspaceDataProvider == null || splitViewOperations == null) return
    scope.launch {
        val current = workspaceDataProvider.currentWorkspace.value
        if (current?.id == workspaceId) return@launch
        val target = workspaceDataProvider.workspaces.value.find { it.id == workspaceId } ?: return@launch
        if (current != null && current.id.isNotEmpty()) {
            splitViewOperations.preserveCurrentState(current.id, current.name)
        }
        workspaceDataProvider.loadWorkspace(target)
        splitViewOperations.applyWorkspace(target)
    }
}
