package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossColors
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossSearchBar
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val DIALOG_WIDTH = 600.dp
private val DIALOG_HEIGHT = 480.dp
private val DIALOG_INSET = 16.dp
private val DIALOG_RADIUS = RoundedCornerShape(8.dp)
private val SEARCH_HEIGHT = 28.dp
private val GROUP_HEIGHT = 24.dp
private val GROUP_INSET = 10.dp
private const val TITLE_SP = 15
private const val GROUP_SP = 10
private const val HINT_SP = 11

/**
 * How often the switcher re-collects every window's tabs while it is open.
 *
 * A poll, not a subscription, because `refreshAllWindowTabs` is the only thing that walks the
 * other windows: `activeTabs`' 2s host-side loop covers one window, and nothing pushes when a tab
 * opens in the window behind this one. One second is what the host's own switcher used, and it
 * only runs while this dialog is on screen.
 */
private const val REFRESH_MS = 1_000L

/** Where a tab is, when it is not here. `ActiveTabData.windowId` is an id, not a name. */
private const val OTHER_WINDOW = "Other window"

/**
 * Every tab in every open window, searchable, with the keyboard.
 *
 * This is the host's old `TopOfMindDialog` moved into the plugin, so there is ONE switcher rather
 * than two. Three things changed on the way across:
 *
 * - **The source is [ActiveTabsProvider.allWindowTabs]**, refreshed through
 *   [ActiveTabsProvider.refreshAllWindowTabs] when the dialog opens. `activeTabs` is this window's
 *   tabs alone, which is right for the tree behind this dialog and wrong for a switcher: the tab
 *   you are reaching for may be in the window behind this one, and a switcher that cannot see it
 *   is a switcher you stop trusting. On a host that cannot answer across windows the api default
 *   makes `allWindowTabs` this window's list, so this degrades to what it replaced rather than to
 *   nothing.
 * - **[BossDialog], never a raw Compose `Dialog`.** Under JxBrowser's HARDWARE_ACCELERATED surface
 *   an ordinary dialog renders BEHIND the page, which is the whole reason the wrapper exists. The
 *   content is wrapped in [BossTheme] because the heavyweight path composes it in a window of its
 *   own, where the panel's theme is not in scope.
 * - **Rows are grouped by workspace**, and a group in another window says so. The host's version
 *   put a workspace badge on every row; a header says it once per group and leaves the row's width
 *   to the title.
 *
 * Selecting goes through [ActiveTabsProvider.selectTab], which reaches a tab in a workspace this
 * window is running behind the current one and brings that workspace forward (the host's
 * `selectTabAnywhere` plus its workspace switch). Nothing here reimplements that.
 *
 * **Known: selecting a tab in ANOTHER WINDOW does not focus it.** `selectTab` resolves against the
 * split-view state of the window whose provider this is, so a tab from a different window is not
 * found and the click does nothing visible. Listing them is still worth it - it answers "where is
 * that tab" - and closing it needs a window-aware verb on the api rather than a workaround here.
 */
@Composable
internal fun QuickSwitcherDialog(
    activeTabsProvider: ActiveTabsProvider,
    thisWindowId: String?,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    val tabs by activeTabsProvider.allWindowTabs.collectAsState()

    // Ask before showing, then keep asking while the dialog is up. Cross-window state is collected
    // on demand rather than pushed, so opening on whatever the last window to publish happened to
    // leave behind would be showing a stale list at the one moment it matters.
    LaunchedEffect(activeTabsProvider) {
        activeTabsProvider.refreshAllWindowTabs()
        while (true) {
            delay(REFRESH_MS)
            activeTabsProvider.refreshAllWindowTabs()
        }
    }

    // A text field nobody focused is a search box you have to click first, which for a keyboard
    // shortcut is the whole feature. Guarded because requestFocus throws when the node is not
    // attached, and a switcher that crashed rather than opening would be a worse trade.
    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }

    val rows = remember(tabs, query, thisWindowId) { switcherRows(tabs, query, thisWindowId) }
    val matches = remember(rows) { rows.filterIsInstance<SwitcherRow.Tab>() }

    // Clamped here rather than at every read: the list re-filters under the selection on every
    // keystroke and on every refresh, so an index that was valid a frame ago need not be.
    LaunchedEffect(matches.size) {
        if (selectedIndex > matches.lastIndex) selectedIndex = matches.size - 1
        if (selectedIndex < 0) selectedIndex = 0
    }
    LaunchedEffect(selectedIndex, matches.size) {
        matches.getOrNull(selectedIndex)?.let { listState.animateScrollToItem(it.rowIndex) }
    }

    fun choose(tab: ActiveTabData) {
        activeTabsProvider.selectTab(tab.tabId, tab.panelId)
        onDismiss()
    }

    BossDialog(onDismissRequest = onDismiss) {
        BossTheme {
            Surface(
                modifier =
                    Modifier
                        .requiredWidth(DIALOG_WIDTH)
                        .requiredHeight(DIALOG_HEIGHT)
                        .border(1.dp, BossThemeColors.BorderColor, DIALOG_RADIUS)
                        // Preview, so the arrows and Enter are taken before the focused search
                        // field sees them - the field would otherwise move a caret instead of the
                        // selection, and Escape would do nothing at all.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else {
                                when (event.key) {
                                    Key.Escape -> {
                                        onDismiss()
                                        true
                                    }

                                    Key.DirectionUp -> {
                                        selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                        true
                                    }

                                    Key.DirectionDown -> {
                                        selectedIndex = (selectedIndex + 1).coerceAtMost(matches.lastIndex.coerceAtLeast(0))
                                        true
                                    }

                                    Key.Enter -> {
                                        matches.getOrNull(selectedIndex)?.let { choose(it.tab) }
                                        true
                                    }

                                    else -> false
                                }
                            }
                        },
                shape = DIALOG_RADIUS,
                color = BossColors.contextMenuBackground,
            ) {
                QuickSwitcherBody(
                    query = query,
                    onQueryChange = {
                        query = it
                        selectedIndex = 0
                    },
                    searchFocus = searchFocus,
                    rows = rows,
                    matchCount = matches.size,
                    selectedIndex = selectedIndex,
                    listState = listState,
                    activeTabsProvider = activeTabsProvider,
                    onChoose = ::choose,
                )
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun QuickSwitcherBody(
    query: String,
    onQueryChange: (String) -> Unit,
    searchFocus: FocusRequester,
    rows: List<SwitcherRow>,
    matchCount: Int,
    selectedIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    activeTabsProvider: ActiveTabsProvider,
    onChoose: (ActiveTabData) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(DIALOG_INSET)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Switch to Tab",
                fontSize = TITLE_SP.sp,
                fontWeight = FontWeight.SemiBold,
                color = BossThemeColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (matchCount == 1) "1 tab" else "$matchCount tabs",
                fontSize = HINT_SP.sp,
                color = BossThemeColors.TextMuted,
            )
        }
        Spacer(modifier = Modifier.height(DIALOG_INSET))

        BossSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = "Search tabs by title, address or workspace",
            modifier = Modifier.fillMaxWidth().height(SEARCH_HEIGHT).focusRequester(searchFocus),
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = if (query.isBlank()) "No open tabs" else "Nothing matching \"$query\"",
                    fontSize = 13.sp,
                    color = BossThemeColors.TextMuted,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .lazyListScrollbar(
                            listState = listState,
                            direction = Orientation.Vertical,
                            config = getPanelScrollbarConfig(),
                        ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(rows.size) { index ->
                    when (val row = rows[index]) {
                        is SwitcherRow.Group -> SwitcherGroupHeader(row)
                        is SwitcherRow.Tab ->
                            SwitcherTabRow(
                                tab = row.tab,
                                activeTabsProvider = activeTabsProvider,
                                isSelected = row.matchIndex == selectedIndex,
                                onClick = { onChoose(row.tab) },
                            )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Up and Down to move - Enter to switch - Esc to close",
            fontSize = HINT_SP.sp,
            color = BossThemeColors.TextMuted,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/**
 * A workspace's name, and where that workspace is when it is not in this window.
 *
 * The same 10sp SemiBold on 0.8sp tracking the panel's own headers use, so the dialog and the tree
 * behind it read as one thing.
 */
@Composable
private fun SwitcherGroupHeader(group: SwitcherRow.Group) {
    Row(
        modifier = Modifier.fillMaxWidth().height(GROUP_HEIGHT).padding(horizontal = GROUP_INSET),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = group.workspaceName.uppercase(),
            fontSize = GROUP_SP.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = BossThemeColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (group.elsewhere) {
            Text(
                text = OTHER_WINDOW,
                fontSize = GROUP_SP.sp,
                color = BossThemeColors.TextMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * One tab: the panel's own row, at the panel's own metrics.
 *
 * `TabGlyph` rather than a second favicon cascade, for the reason it was made `internal` for in
 * the first place - a second copy is a second place for a favicon to stop appearing.
 */
@Composable
private fun SwitcherTabRow(
    tab: ActiveTabData,
    activeTabsProvider: ActiveTabsProvider,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val fill =
        when {
            isSelected -> BossThemeColors.AccentColor.copy(alpha = SELECTED_FILL_ALPHA)
            isHovered -> BossColors.contextMenuHover
            else -> Color.Transparent
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .background(fill, ROW_RADIUS)
                .hoverable(interactionSource)
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = GROUP_INSET),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ROW_ITEM_GAP),
        ) {
            TabGlyph(tab, activeTabsProvider)
            Text(
                text = tab.title.ifEmpty { "Untitled" },
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                color = BossThemeColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )
            // The address when there is one, the tab type otherwise - the one line that tells two
            // tabs called "Untitled" apart. Capped so a long URL cannot squeeze the title out.
            val secondary = tab.url?.takeIf { it.isNotBlank() } ?: tab.typeId
            Text(
                text = secondary,
                fontSize = HINT_SP.sp,
                color = BossThemeColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        if (isSelected) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(3.dp)
                        .background(BossThemeColors.AccentColor, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** BossTabButton's SELECTED_FILL_ALPHA, the same wash the panel's selected row carries. */
private const val SELECTED_FILL_ALPHA = 0.16f

/**
 * The list the dialog draws: group headers and tab rows, already filtered and already ordered.
 *
 * Flat, and carrying both indices, because two different things count. [SwitcherRow.Tab.matchIndex]
 * is the position among TABS, which is what the arrow keys move through - headers are not stops.
 * [SwitcherRow.Tab.rowIndex] is the position in this list, which is what `animateScrollToItem`
 * takes. Deriving one from the other at the call site is the arithmetic that silently scrolls to
 * the wrong row as soon as a group has a header.
 */
private sealed interface SwitcherRow {
    data class Group(
        val workspaceName: String,
        val elsewhere: Boolean,
    ) : SwitcherRow

    data class Tab(
        val tab: ActiveTabData,
        val matchIndex: Int,
        val rowIndex: Int,
    ) : SwitcherRow
}

/**
 * Filter by [query], group by workspace, and flatten.
 *
 * **Grouped by (window, workspace), not by workspace alone.** The same workspace can be running in
 * two windows, and merging them would put a tab you cannot reach under a heading that says it is
 * here.
 *
 * **This window's groups come first**, then everything else, each block ordered by workspace name
 * case-insensitively with the id as a tie-break. Name order rather than arrival order for the
 * reason `TabTreeBuilder.workspaceOrder` records: the host emits the current workspace's tabs
 * first, so arrival order moves a group to the top the moment you switch to it - rows shifting out
 * from under the pointer, in a list being read.
 *
 * Pure, so what the dialog shows is a function of what it was given.
 */
private fun switcherRows(
    tabs: List<ActiveTabData>,
    query: String,
    thisWindowId: String?,
): List<SwitcherRow> {
    val needle = query.trim()
    val matching =
        if (needle.isEmpty()) {
            tabs
        } else {
            tabs.filter { tab ->
                tab.title.contains(needle, ignoreCase = true) ||
                    tab.workspaceName.contains(needle, ignoreCase = true) ||
                    tab.url?.contains(needle, ignoreCase = true) == true
            }
        }

    val groups =
        matching
            .groupBy { it.windowId to it.workspaceId }
            .toList()
            .sortedWith(
                // thisWindowId null means the host did not say which window this panel is in. Every
                // group then sorts as "here", which is the honest reading: nothing is known to be
                // elsewhere, so nothing is labelled elsewhere either.
                compareBy(
                    { (key, _) -> if (thisWindowId == null || key.first == thisWindowId) 0 else 1 },
                    { (_, groupTabs) -> groupTabs.first().workspaceName.lowercase() },
                    { (key, _) -> key.second },
                ),
            )

    val rows = mutableListOf<SwitcherRow>()
    var matchIndex = 0
    groups.forEach { (key, groupTabs) ->
        rows.add(
            SwitcherRow.Group(
                workspaceName = groupTabs.first().workspaceName.ifEmpty { "Workspace" },
                elsewhere = thisWindowId != null && key.first != thisWindowId,
            ),
        )
        groupTabs.forEach { tab ->
            rows.add(SwitcherRow.Tab(tab = tab, matchIndex = matchIndex, rowIndex = rows.size))
            matchIndex++
        }
    }
    return rows
}
