package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.FilePickerProvider
import ai.rever.boss.plugin.api.GenericDialogProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossEmptyState
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
/**
 * How long a dragged tab must rest on a collapsed workspace before it opens.
 *
 * Long enough that dragging past a header on the way somewhere else costs nothing, short enough to
 * read as an answer rather than a wait. Every header between the tab and its destination is crossed
 * by an ordinary drag, and opening each one would reflow the tree under the pointer.
 */
private const val SPRING_LOAD_DELAY_MS = 550L

private const val MOVED_FLASH_MS = 1_400L

/** Depth indent for tabs and nested split sections, matching the tab bar's group nesting. */
private const val INDENT_STEP = 12

@Composable
@Suppress("LongParameterList")
fun TopOfMindContent(
    activeTabsProvider: ActiveTabsProvider?,
    workspaceDataProvider: WorkspaceDataProvider?,
    splitViewOperations: SplitViewOperations?,
    contextMenuProvider: ContextMenuProvider?,
    filePickerProvider: FilePickerProvider?,
    genericDialogProvider: GenericDialogProvider?,
    treeState: TabTreeState,
    dragState: TabDragState,
    paneExpansion: SplitPaneExpansion,
    panelDialogs: PanelDialogState,
    /** Which workspace opened last, so it is the bottom row. See [WorkspaceArrival]. */
    workspaceArrival: WorkspaceArrival,
    windowId: String?,
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
                filePickerProvider = filePickerProvider,
                genericDialogProvider = genericDialogProvider,
                treeState = treeState,
                dragState = dragState,
                paneExpansion = paneExpansion,
                panelDialogs = panelDialogs,
                workspaceArrival = workspaceArrival,
                windowId = windowId,
                scope = scope,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun TabTree(
    activeTabsProvider: ActiveTabsProvider,
    workspaceDataProvider: WorkspaceDataProvider?,
    splitViewOperations: SplitViewOperations?,
    contextMenuProvider: ContextMenuProvider?,
    filePickerProvider: FilePickerProvider?,
    genericDialogProvider: GenericDialogProvider?,
    workspaceArrival: WorkspaceArrival,
    treeState: TabTreeState,
    dragState: TabDragState,
    paneExpansion: SplitPaneExpansion,
    panelDialogs: PanelDialogState,
    windowId: String?,
    scope: CoroutineScope,
) {
    val activeTabs by activeTabsProvider.activeTabs.collectAsState()

    // One refresh when the panel appears, and one after anything this panel changes. The host
    // adapter runs its own 2s poll and pushes into this StateFlow, so the 1s loop that used to
    // live here was a second timer asking the same question twice as often.
    LaunchedEffect(activeTabsProvider) { activeTabsProvider.refreshTabs() }

    val listState = rememberLazyListState()
    val currentWorkspaceId = workspaceDataProvider?.currentWorkspace?.collectAsState()?.value?.id
    val transferSupported = remember(activeTabsProvider) { TabTransfer.isSupported(activeTabsProvider) }

    // Keyed on the saved workspaces too, not just the tabs: the tree's ORDER is their `timestamp`
    // (see TabTreeBuilder.workspaceOrder), so a workspace saved while the panel is open has to
    // move without waiting for a tab somewhere to change.
    val savedWorkspaces = workspaceDataProvider?.workspaces?.collectAsState()?.value.orEmpty()
    val treeNodes =
        remember(activeTabs, savedWorkspaces) {
            TabTreeBuilder.buildTree(activeTabs, workspaceDataProvider, workspaceArrival)
        }
    // Keyed on the current workspace as well as the tree: the default now says "the workspace on
    // screen is open, the rest are closed", so a switch that changes nothing else still has to
    // re-derive it. Re-running this is idempotent, and it never overrules an explicit toggle.
    LaunchedEffect(treeNodes, currentWorkspaceId) {
        treeState.syncDefaultExpansion(treeNodes, currentWorkspaceId)
    }

    // Panes come and go and [SplitPaneExpansion] is keyed by panel id, but nothing tells it when
    // one closes. Called on every rebuild, which is where the panel learns a pane is gone: a Set
    // compares by value, so this restarts only when the panes themselves change.
    val livePanelIds = remember(activeTabs) { activeTabs.map { it.panelId }.toSet() }
    LaunchedEffect(livePanelIds) { paneExpansion.retainOnly(livePanelIds) }

    // The flash is cleared here rather than by the row, so it survives the row being recomposed
    // into a different group - which is exactly what a move does to it.
    // SPRING-LOADED EXPANSION. Hovering a collapsed workspace with a tab in hand opens it, so the
    // panes inside become drop targets without letting go first.
    //
    // A delay, not an immediate open: dragging from the top of the panel to the bottom crosses
    // every header on the way, and opening each one would reflow the tree under the pointer mid
    // drag. 550ms is long enough that passing over costs nothing and short enough to feel like an
    // answer rather than a wait.
    //
    // Only ever OPENS. A group that closed again when the pointer left would take its panes with
    // it, and the reason to open it was to drop into one of those panes - so the timer that opens
    // it has no counterpart. `toggleExpansion` also records an override, which is right: the user
    // asked for this group by holding a tab over it.
    val hoveredForDrop = dragState.hoveredWorkspaceId
    LaunchedEffect(hoveredForDrop, dragState.dragging) {
        val workspaceId = hoveredForDrop ?: return@LaunchedEffect
        if (dragState.dragging == null) return@LaunchedEffect
        if (treeState.isWorkspaceExpanded(workspaceId)) return@LaunchedEffect
        delay(SPRING_LOAD_DELAY_MS)
        // Re-checked after the wait: the pointer may have moved on, or the drop may have happened.
        if (dragState.dragging != null &&
            dragState.hoveredWorkspaceId == workspaceId &&
            !treeState.isWorkspaceExpanded(workspaceId)
        ) {
            treeState.toggleExpansion(workspaceId)
        }
    }

    LaunchedEffect(dragState.recentlyMovedTabId) {
        if (dragState.recentlyMovedTabId != null) {
            delay(MOVED_FLASH_MS)
            dragState.recentlyMovedTabId = null
        }
    }

    fun moveTab(
        tab: ActiveTabData,
        targetWorkspaceId: String,
        targetPanelId: String? = null,
    ) {
        scope.launch {
            if (TabTransfer.move(activeTabsProvider, tab.tabId, targetWorkspaceId, targetPanelId)) {
                dragState.recentlyMovedTabId = tab.tabId
                // Do not wait for the host's poll: the whole point of the flash is that the row
                // has already reappeared under its new workspace by the time you look.
                activeTabsProvider.refreshTabs()
            }
        }
    }

    // Closing a whole header's worth of tabs, or NOT OFFERING IT. Every close action in the tree
    // hangs off this one lambda, and it is null when there is no `genericDialogProvider` - so a
    // host that cannot raise a confirm shows no close button rather than a button that destroys a
    // dozen tabs on one click with nothing to undo it.
    //
    // The message is composed at the call site, because only the header knows what it is about to
    // close; the title, the destructive styling, the close loop and the refresh are here, so every
    // one of these asks the same question the same way.
    val dialogs = genericDialogProvider
    val closeTabs: ((String, List<ActiveTabData>) -> Unit)? =
        if (dialogs == null) {
            null
        } else {
            { message: String, tabs: List<ActiveTabData> ->
                confirmAndCloseTabs(tabs, message, dialogs, activeTabsProvider, scope)
            }
        }

    // The panel's own position in the window. The drag reports the pointer in window coordinates
    // (a row and a workspace header share no parent, so nothing else is comparable), and the ghost
    // is placed inside this Box - so one of the two has to be converted, and this is the only place
    // that knows the offset between them.
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }

    // Leaving the PANEL is what drops the hover choice, and the whole panel is the only boundary
    // the sticky-hover model cares about - see [SplitPaneExpansion]. Per-section exits are exactly
    // what it must not react to: moving from a header down onto the rows it revealed leaves that
    // header.
    val panelInteraction = remember { MutableInteractionSource() }
    val panelHovered by panelInteraction.collectIsHoveredAsState()
    LaunchedEffect(panelHovered) { if (!panelHovered) paneExpansion.panelExited() }

    Surface(modifier = Modifier.fillMaxSize(), color = BossThemeColors.SurfaceColor) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .hoverable(panelInteraction)
                    .onGloballyPositioned { panelOrigin = it.boundsInWindow().topLeft },
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(6.dp))

            if (treeNodes.isEmpty()) {
                // weight, not fillMaxSize, for the same reason the list below takes one: the footer
                // is a sibling in this Column and a child that claims the whole height pushes it
                // off the bottom.
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    BossEmptyState(
                        icon = Icons.Outlined.Tab,
                        message = "No active tabs",
                        description = "Open a tab and it will show up here",
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
                            // The list takes what is left AFTER the footer, so the actions stay
                            // pinned to the bottom of the panel instead of scrolling with the tree.
                            .fillMaxWidth()
                            .weight(1f)
                            .lazyListScrollbar(
                                listState = listState,
                                direction = Orientation.Vertical,
                                config = getPanelScrollbarConfig(),
                            ),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    treeNodes.forEachIndexed { index, node ->
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
                            paneExpansion = paneExpansion,
                            transferSupported = transferSupported,
                            scope = scope,
                            onMove = ::moveTab,
                            onCloseTabs = closeTabs,
                        )
                    }
                }
            }

            // The same workspaces the tree just listed, drawn as the storeys of a building. A
            // sibling of the LazyColumn rather than an item in it, for the reason the footer is
            // one: it has a fixed height and must not scroll away with the tree. It is fed
            // `treeNodes` rather than reading `SplitConfig` again, so a floor and its group in the
            // tree can never disagree about the shape of a workspace.
            WorkspaceFloors(
                nodes = treeNodes,
                currentWorkspaceId = currentWorkspaceId,
                // A getter on the provider, not a flow - read the way the tree's own rows read it.
                // Null in a workspace that is not on screen, which is why only the lit floor ever
                // marks a pane as the one being worked in.
                activePanelId = activeTabsProvider.activePanelId,
                // The SAME switch the workspace headers use. A second copy is a second chance to
                // drop the preserve step and lose a layout.
                onSelectWorkspace = { workspaceId ->
                    switchToWorkspace(workspaceId, workspaceDataProvider, splitViewOperations, scope)
                },
            )

            // Last child of the Column, outside the list: the host's workspace menu, as the foot of
            // this panel. See WorkspaceActionsFooter for what it draws and what it leaves out.
            WorkspaceActionsFooter(
                workspaceDataProvider = workspaceDataProvider,
                splitViewOperations = splitViewOperations,
                filePickerProvider = filePickerProvider,
                genericDialogProvider = genericDialogProvider,
                panelDialogs = panelDialogs,
                onOpenQuickSwitcher = { panelDialogs.toggle(PanelDialog.QUICK_SWITCHER) },
                // Union rather than the live set alone: a workspace running under an id the saved
                // list has never seen still has tabs in this list, and an older host answers the
                // live-set getter with an empty default.
                runningWorkspaceIds = {
                    activeTabsProvider.liveWorkspaceIds + activeTabs.map { it.workspaceId }
                },
                scope = scope,
            )
        }

        // Last child, so it paints over the list rather than under it. Outside the LazyColumn on
        // purpose: a ghost emitted from a row would be clipped to that row and would scroll with it.
        TabDragGhost(
            dragState = dragState,
            activeTabsProvider = activeTabsProvider,
            panelOrigin = panelOrigin,
        )

        // Raised HERE rather than from the footer button that opens it, unlike the workspace
        // picker. The switcher needs `activeTabsProvider`, which is non-null exactly inside this
        // function, and the footer draws nothing at all without a `workspaceDataProvider` - so a
        // switcher hosted there would vanish on a host that serves tabs and no workspaces, taking
        // the deep-link action's only landing place with it.
        if (panelDialogs.isOpen(PanelDialog.QUICK_SWITCHER)) {
            QuickSwitcherDialog(
                activeTabsProvider = activeTabsProvider,
                thisWindowId = windowId,
                onDismiss = { panelDialogs.close() },
            )
        }
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
    paneExpansion: SplitPaneExpansion,
    transferSupported: Boolean,
    scope: CoroutineScope,
    onMove: (ActiveTabData, String, String?) -> Unit,
    onCloseTabs: ((String, List<ActiveTabData>) -> Unit)?,
) {
    if (node !is TabTreeNode.WorkspaceNode) return

    item(key = node.id) {
        val expanded by treeState.expandedWorkspaces.collectAsState()
        // What is UNDER this header right now, taken from the structure being drawn rather than
        // from the workspace id, so the confirm names the count it is about to close.
        val tabs = remember(node.tabStructure) { TabTreeBuilder.tabsIn(node.tabStructure) }
        WorkspaceHeader(
            node = node,
            isExpanded = node.workspaceId in expanded,
            isCurrent = currentWorkspaceId == node.workspaceId,
            dragState = dragState,
            showRuleAbove = !isFirst,
            onToggleExpand = { treeState.toggleExpansion(node.workspaceId) },
            onActivate = {
                switchToWorkspace(node.workspaceId, workspaceDataProvider, splitViewOperations, scope)
            },
            onCloseAll =
                onCloseTabs?.takeIf { tabs.isNotEmpty() }?.let { close ->
                    {
                        close(
                            "Close ${tabCountPhrase(tabs.size)} in \"${node.name}\"? " +
                                "Tabs close in that workspace whether or not it is on screen, " +
                                "and this cannot be undone.",
                            tabs,
                        )
                    }
                },
        )
    }

    item(key = "${node.id}-body") {
        val expanded by treeState.expandedWorkspaces.collectAsState()
        if (node.workspaceId in expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabStructure(
                    structure = node.tabStructure,
                    workspaceId = node.workspaceId,
                    workspaceName = node.name,
                    depth = 0,
                    currentWorkspaceId = currentWorkspaceId,
                    allTabs = allTabs,
                    activeTabsProvider = activeTabsProvider,
                    workspaceDataProvider = workspaceDataProvider,
                    contextMenuProvider = contextMenuProvider,
                    dragState = dragState,
                    paneExpansion = paneExpansion,
                    transferSupported = transferSupported,
                    onMove = onMove,
                    onCloseTabs = onCloseTabs,
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
    workspaceName: String,
    depth: Int,
    currentWorkspaceId: String?,
    allTabs: List<ActiveTabData>,
    activeTabsProvider: ActiveTabsProvider,
    workspaceDataProvider: WorkspaceDataProvider?,
    contextMenuProvider: ContextMenuProvider?,
    dragState: TabDragState?,
    paneExpansion: SplitPaneExpansion,
    transferSupported: Boolean,
    onMove: (ActiveTabData, String, String?) -> Unit,
    onCloseTabs: ((String, List<ActiveTabData>) -> Unit)?,
    sectionPath: String = "",
) {
    // Indexed so a split section can tell whether it is the first under its parent: the rule
    // divides one pane from the previous one, and the first has a workspace header above it
    // already.
    structure.forEachIndexed { index, item ->
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
                    // Asked per row rather than precomputed: selectedTabId is a cheap lookup and
                    // the alternative is a second map to keep in step with a tree that rebuilds.
                    isSelected = activeTabsProvider.selectedTabId(tab.workspaceId, tab.panelId) == tab.tabId,
                    // A pane in a workspace that is not on screen is never the focused one, so the
                    // panel-id match alone would light up a pane behind the current workspace.
                    isFocused =
                        tab.workspaceId == currentWorkspaceId &&
                            activeTabsProvider.activePanelId == tab.panelId,
                    indent = (INDENT_STEP * (depth + 1)).dp,
                    onClick = { activeTabsProvider.selectTab(tab.tabId, tab.panelId) },
                    onClose = { activeTabsProvider.closeTab(tab.tabId) },
                    onMoveTo = { workspaceId, panelId -> onMove(tab, workspaceId, panelId) },
                )
            }

            is WorkspaceTabStructure.SplitSection -> {
                val path = if (sectionPath.isEmpty()) item.sectionName else "$sectionPath/${item.sectionName}"
                // The whole subtree, not just this section's direct children: a nested split's
                // tabs belong to the section above it too, and closing "Left" has to mean the
                // rows drawn under "Left". Collapsed or not - a collapsed section is exactly the
                // one you want to clear without opening it first.
                val tabs = remember(item.children) { TabTreeBuilder.tabsIn(item.children) }

                // The pane this section stands for, or null when it is a container for a nested
                // split. Only a pane collapses: a container has no tab of its own to collapse TO.
                val panelId = TabTreeBuilder.paneIdOf(item.children)

                // The pane being worked in is always open, and is never asked about - that is a
                // fact about the split rather than something hover decided.
                //
                // A workspace that is NOT on screen has no focused pane at all: `activePanelId`
                // names a pane in the workspace the window is showing, and the panel-id match
                // alone would light up a preserved pane behind it. The consistent reading, and
                // the one taken here, is that EVERY pane of such a workspace is collapsed to the
                // tab it is showing until the pointer chooses one - those workspaces are the long
                // tail of this tree and the reason it collapses at all.
                val isActivePane =
                    panelId != null &&
                        workspaceId == currentWorkspaceId &&
                        activeTabsProvider.activePanelId == panelId

                val isExpanded =
                    panelId == null ||
                        isActivePane ||
                        paneExpansion.isExpanded(panelId)

                // What a collapsed pane still draws a full row for: the tab it is showing.
                // `selectedTabId` answers for a preserved workspace too. The first tab is the
                // fallback, because a collapsed pane with no row at all reads as an empty pane.
                val shownTab =
                    if (isExpanded) {
                        null
                    } else {
                        // panelId is non-null here without a check: `isExpanded` is true whenever
                        // it is null, so a collapsed section always has a pane behind it.
                        val selected = activeTabsProvider.selectedTabId(workspaceId, panelId)
                        tabs.firstOrNull { it.tabId == selected } ?: tabs.firstOrNull()
                    }
                val hiddenTabs = if (isExpanded) emptyList() else tabs.filter { it.tabId != shownTab?.tabId }

                SplitSectionHeader(
                    sectionName = item.sectionName,
                    indentDp = INDENT_STEP * (depth + 1),
                    isExpanded = isExpanded,
                    showRuleAbove = index > 0,
                    // The same test the tab rows use for isFocused: a pane in a workspace that is
                    // not on screen is never the one being worked in, however recently it was.
                    isActivePane =
                        panelId != null &&
                            workspaceId == currentWorkspaceId &&
                            activeTabsProvider.activePanelId == panelId,
                    onToggleExpansion = panelId?.let { id -> { paneExpansion.togglePinned(id) } },
                    onHover = panelId?.let { id -> { paneExpansion.hover(id) } },
                    // A pane can be dropped ON, which is what lets a tab go to a chosen pane rather
                    // than to whichever one the destination workspace happens to have active - and
                    // is the only way to move a tab between two panes of the workspace it is
                    // already in. Null for a section that stands for a nested split rather than a
                    // pane: it has no pane id, so there is nothing to name as the destination.
                    paneTarget = panelId?.let { TabDragState.PaneTarget(workspaceId, it) },
                    dragState = dragState,
                    onCloseAll =
                        onCloseTabs?.takeIf { tabs.isNotEmpty() }?.let { close ->
                            {
                                close(
                                    "Close ${tabCountPhrase(tabs.size)} in the ${item.sectionName} " +
                                        "split of \"$workspaceName\"? This cannot be undone.",
                                    tabs,
                                )
                            }
                        },
                )

                // Recursed through even when collapsed, with a one-item structure: the row a
                // collapsed pane keeps is an ordinary tab row, with the same markers, menu, drag
                // and close as any other, and building it here by hand would be a second copy of
                // all of that drifting away from the first.
                TabStructure(
                    structure =
                        if (isExpanded) {
                            item.children
                        } else {
                            shownTab?.let { listOf(WorkspaceTabStructure.TabItem(it)) }.orEmpty()
                        },
                    workspaceId = workspaceId,
                    workspaceName = workspaceName,
                    depth = depth + 1,
                    currentWorkspaceId = currentWorkspaceId,
                    allTabs = allTabs,
                    activeTabsProvider = activeTabsProvider,
                    workspaceDataProvider = workspaceDataProvider,
                    contextMenuProvider = contextMenuProvider,
                    dragState = dragState,
                    paneExpansion = paneExpansion,
                    transferSupported = transferSupported,
                    onMove = onMove,
                    onCloseTabs = onCloseTabs,
                    sectionPath = path,
                )

                // Nothing to stand in for when the pane holds one tab: it is already showing all
                // of them, and a summary row with no marks in it is a row that says nothing.
                if (panelId != null && hiddenTabs.isNotEmpty()) {
                    PaneSummaryRow(
                        hidden = hiddenTabs,
                        // Aligned with the tab rows above it, so its chevron sits where their
                        // icons do rather than under the header's label.
                        indentDp = INDENT_STEP * (depth + 2),
                        activeTabsProvider = activeTabsProvider,
                        onHover = { paneExpansion.hover(panelId) },
                        onToggleExpansion = { paneExpansion.togglePinned(panelId) },
                        onSelectTab = { tab -> activeTabsProvider.selectTab(tab.tabId, tab.panelId) },
                    )
                }
            }
        }
    }
}

/**
 * "1 tab" or "all 7 tabs".
 *
 * The count is the one the caller is actually about to close, not the header's own tab count -
 * under a search those differ, and a confirm that promises a different number from the one it
 * closes is worse than no confirm at all.
 */
private fun tabCountPhrase(count: Int): String = if (count == 1) "1 tab" else "all $count tabs"

/**
 * Ask, then close a group of tabs.
 *
 * The confirm is the HOST's ([GenericDialogProvider.showConfirmationDialog]), for the reasons the
 * footer's prompts are: it is a suspend call that returns the answer, so there is no dialog state
 * to hold, and the host is the only party that can guarantee a modal lands in FRONT of a
 * hardware-composited browser surface. Marked destructive, because it is.
 *
 * [tabs] is a snapshot taken when the header composed, and is used verbatim after the dialog
 * returns. That is deliberate: the tree is rebuilt roughly every 2s and would otherwise change
 * under an open dialog, so what gets closed is what the user was looking at when they asked.
 *
 * `closeTab` reaches tabs in workspaces that are not on screen (the host's `closeTabAnywhere`), so
 * this works on a workspace you are not currently in.
 */
private fun confirmAndCloseTabs(
    tabs: List<ActiveTabData>,
    message: String,
    dialogs: GenericDialogProvider,
    activeTabsProvider: ActiveTabsProvider,
    scope: CoroutineScope,
) {
    if (tabs.isEmpty()) return
    scope.launch {
        val confirmed =
            dialogs.showConfirmationDialog(
                title = "Close Tabs",
                message = message,
                confirmText = "Close",
                isDestructive = true,
            )
        if (!confirmed) return@launch
        tabs.forEach { activeTabsProvider.closeTab(it.tabId) }
        // Same as the move: do not wait on the host's 2s poll to notice the rows are gone.
        activeTabsProvider.refreshTabs()
    }
}

/**
 * Bring a workspace on screen: preserve what is showing, load the target, apply it.
 *
 * All three steps, in that order, or switching away and back loses a layout - the same sequence
 * the host's own `rememberWorkspaceSwitch` performs.
 *
 * `internal`, not private, because the footer's workspace menu switches too and there must be ONE
 * of these: a second copy is a second chance to drop the preserve step and lose a layout.
 */
internal fun switchToWorkspace(
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
