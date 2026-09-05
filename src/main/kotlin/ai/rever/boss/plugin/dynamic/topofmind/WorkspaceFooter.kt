package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.DialogChoice
import ai.rever.boss.plugin.api.FilePickerProvider
import ai.rever.boss.plugin.api.GenericDialogProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.ui.BossColors
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossSearchBar
import ai.rever.boss.plugin.ui.BossSecondaryButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.font.FontWeight
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.WorkspaceSerializer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// The host's own sidebar action button, to the dp, because these sit in the same column as it.
//
// A 32dp target (`SIDEBAR_ICON_SIZE` in the host's FocusModeQuickActions) around a 20dp glyph
// (`BossActionButton`'s `iconSize` default, with its 2dp content padding). The glyph was 14dp,
// which is a tab row's bare icon rather than a footer button's, and next to the host's own action
// row these read as a smaller class of control than the one they sit beside. The 4dp radius and
// the hover fill are unchanged.
private val ACTION_SIZE = 32.dp
private val ACTION_RADIUS = RoundedCornerShape(4.dp)
private val ACTION_ICON = 20.dp

// Matches the host's own foot (HostActionsFlowRow): 4dp between icons, 6dp of air above and below,
// 8dp either side.
private val FOOTER_GAP = 4.dp
private val FOOTER_INSET = 6.dp
private val FOOTER_SIDE_INSET = 8.dp

private val DIALOG_MIN_WIDTH = 320.dp
private val DIALOG_MAX_WIDTH = 420.dp
private val DIALOG_INSET = 16.dp
private val DIALOG_LIST_MAX_HEIGHT = 320.dp
private const val DIALOG_TITLE_SP = 15
private val SEARCH_HEIGHT = 28.dp
private val MENU_RADIUS = RoundedCornerShape(4.dp)
private val MENU_ROW_HEIGHT = 28.dp
private val MENU_ROW_INSET = 10.dp
private val MENU_DOT = 8.dp
private const val MENU_TEXT_SP = 12

/**
 * The workspace actions, pinned to the foot of the panel.
 *
 * The same menu the host hangs off `WorkspaceButton` at the foot of its vertical tab bar, minus the
 * two entries a plugin cannot reach (see below), laid out as icons rather than a labelled button
 * because this panel's whole width is a tree and a labelled control would compete with it.
 *
 * **A FlowRow, not a Row**, for the reason the host writes up on `HostActionsFlowRow`: a panel
 * column goes down to about 120dp, four 32dp buttons plus their gaps need more than that, and a Row
 * that will not wrap answers a too-narrow measure by giving its LAST child zero width - an absent
 * button rather than a clipped one, at a width the user can reach by dragging.
 *
 * **A full-width rule above it**, again the host's call for a panel foot specifically: what is above
 * is the plugin's own content on the same fill, and without the rule these actions read as part of
 * the tree rather than as chrome under it.
 *
 * **Two host actions are deliberately absent**: `Open Workspace Folder` and `Reset to Default`.
 * Neither has an equivalent on [WorkspaceDataProvider] - they need `WorkspaceManager`'s
 * `getWorkspaceDirectory()` and `resetToDefault()`, which the api does not expose - so there is
 * nothing honest to wire them to. A disabled button would just be the same absence taking up room.
 *
 * Every provider here is nullable and any of them can be null at runtime, so a button whose provider
 * is missing is NOT DRAWN. A shown-but-dead control is a worse answer than a smaller row: it says
 * the action exists and then swallows the click.
 */
@Composable
internal fun WorkspaceActionsFooter(
    workspaceDataProvider: WorkspaceDataProvider?,
    splitViewOperations: SplitViewOperations?,
    filePickerProvider: FilePickerProvider?,
    genericDialogProvider: GenericDialogProvider?,
    /**
     * Workspace ids this window is running, read when the menu OPENS rather than collected.
     *
     * `ActiveTabsProvider.liveWorkspaceIds` is a plain getter over host state, not a flow, so
     * reading it during composition would not recompose when it changed. A snapshot taken as the
     * menu opens is the honest version of what it can answer.
     */
    runningWorkspaceIds: () -> Set<String>,
    scope: CoroutineScope,
) {
    // Every action here reads or writes the workspace list, so without that provider there is no
    // footer at all - not a footer of dead buttons.
    if (workspaceDataProvider == null) return

    // Bound as non-null locals so each button's condition below states what THAT button needs.
    // Switching needs the split view as well as the list: it preserves what is on screen before it
    // applies the new layout (see switchToWorkspace). Save and Delete are prompts first, and the
    // host's dialog provider IS the prompt here, so without it neither can ask anything.
    val splits = splitViewOperations
    val dialogs = genericDialogProvider
    val picker = filePickerProvider
    if (splits == null && dialogs == null) return

    val workspaces by workspaceDataProvider.workspaces.collectAsState()
    val currentWorkspace by workspaceDataProvider.currentWorkspace.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(emptySet<String>()) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Divider(color = BossThemeColors.BorderColor)
        FlowRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FOOTER_SIDE_INSET, vertical = FOOTER_INSET),
            horizontalArrangement = Arrangement.spacedBy(FOOTER_GAP, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(FOOTER_GAP),
        ) {
            if (splits != null) {
                FooterAction(
                    icon = Icons.Outlined.Workspaces,
                    description = "Open workspace",
                    onClick = {
                        // Snapshot what is running on the way OPEN. A dialog outlives a couple of
                        // refresh ticks, and re-reading it under the user would move the dots
                        // around while they are reading the list.
                        if (!menuOpen) running = runningWorkspaceIds()
                        menuOpen = !menuOpen
                    },
                ) {
                    if (menuOpen) {
                        WorkspacePickerDialog(
                            workspaces = workspaces,
                            currentWorkspaceId = currentWorkspace?.id,
                            runningWorkspaceIds = running,
                            onDismiss = { menuOpen = false },
                            onPick = { workspace ->
                                menuOpen = false
                                // The panel's one switch, shared with a click on a workspace header.
                                switchToWorkspace(
                                    workspaceId = workspace.id,
                                    workspaceDataProvider = workspaceDataProvider,
                                    splitViewOperations = splits,
                                    scope = scope,
                                )
                            },
                        )
                    }
                }
            }

            if (dialogs != null) {
                FooterAction(
                    icon = Icons.Outlined.Save,
                    description = "Save workspace",
                    onClick = { scope.launch { saveWorkspace(workspaceDataProvider, dialogs) } },
                )
            }

            if (splits != null && picker != null) {
                FooterAction(
                    icon = Icons.Outlined.Upload,
                    description = "Open workspace from file",
                    onClick = {
                        openWorkspaceFromFile(
                            filePicker = picker,
                            workspaceDataProvider = workspaceDataProvider,
                            splitViewOperations = splits,
                            dialogs = dialogs,
                            scope = scope,
                        )
                    },
                )
            }

            if (dialogs != null) {
                FooterAction(
                    icon = Icons.Outlined.Delete,
                    description = "Delete workspace",
                    onClick = { scope.launch { deleteWorkspace(workspaceDataProvider, dialogs) } },
                )
            }
        }
    }
}

/**
 * One icon button in the foot.
 *
 * [overlay] is emitted INSIDE the button's box, which is where a dialog raised by this button
 * lives. A dialog is a window and sizes itself, so nesting it here costs the row no width - unlike
 * a popup, which would inherit this 32dp button as its measuring parent.
 */
@Composable
private fun FooterAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    overlay: @Composable () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier =
            Modifier
                .size(ACTION_SIZE)
                .clip(ACTION_RADIUS)
                .background(if (isHovered) BossColors.darkSurface else Color.Transparent)
                .hoverable(interactionSource)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            // The action's name, so a screen reader and a hover both have something to say.
            contentDescription = description,
            modifier = Modifier.size(ACTION_ICON),
            tint = BossThemeColors.TextSecondary,
        )
        overlay()
    }
}

/**
 * Every saved workspace, in a dialog with a search field.
 *
 * A dialog rather than the popup this used to be, for two reasons. The list is as long as the user
 * has workspaces, so it needs filtering, and a search field wants focus and room - both awkward in
 * a menu hanging off a 32dp button. It also sidesteps that button entirely as a measuring parent:
 * the popup inherited the anchor's 32dp width constraint and rendered as a strip with every name
 * clipped away, which needed `requiredWidthIn` to defeat. A dialog is sized by the window.
 *
 * [BossDialog], never a plain Compose `Dialog`: under JxBrowser's hardware-accelerated surface an
 * ordinary dialog renders BEHIND the browser, which is the whole reason the wrapper exists.
 *
 * Content is wrapped in [BossTheme] because the heavyweight path composes it in a window of its
 * own, where the panel's theme is not in scope.
 */
@Composable
private fun WorkspacePickerDialog(
    workspaces: List<LayoutWorkspace>,
    currentWorkspaceId: String?,
    runningWorkspaceIds: Set<String>,
    onDismiss: () -> Unit,
    onPick: (LayoutWorkspace) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Filtering is derived, not stored: a stored copy is a second thing to keep in step with the
    // workspace list, which refreshes underneath this dialog while it is open.
    val matches =
        remember(workspaces, query) {
            if (query.isBlank()) {
                workspaces
            } else {
                workspaces.filter { it.name.contains(query.trim(), ignoreCase = true) }
            }
        }

    BossDialog(onDismissRequest = onDismiss) {
        BossTheme {
            Surface(
                modifier =
                    Modifier
                        .requiredWidthIn(min = DIALOG_MIN_WIDTH, max = DIALOG_MAX_WIDTH)
                        .border(1.dp, BossThemeColors.BorderColor, MENU_RADIUS),
                shape = MENU_RADIUS,
                color = BossColors.contextMenuBackground,
            ) {
                Column(modifier = Modifier.padding(DIALOG_INSET)) {
                    Text(
                        text = "Open Workspace",
                        fontSize = DIALOG_TITLE_SP.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BossThemeColors.TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(DIALOG_INSET))

                    BossSearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = "Search workspaces",
                        modifier = Modifier.fillMaxWidth().height(SEARCH_HEIGHT),
                    )
                    Spacer(modifier = Modifier.height(FOOTER_GAP))

                    Column(
                        modifier =
                            Modifier
                                .heightIn(max = DIALOG_LIST_MAX_HEIGHT)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        if (matches.isEmpty()) {
                            Text(
                                text =
                                    if (workspaces.isEmpty()) {
                                        "No saved workspaces"
                                    } else {
                                        "Nothing matching \"$query\""
                                    },
                                fontSize = MENU_TEXT_SP.sp,
                                color = BossThemeColors.TextMuted,
                                modifier = Modifier.padding(horizontal = MENU_ROW_INSET, vertical = FOOTER_GAP),
                            )
                        }
                        matches.forEach { workspace ->
                            // Three states, as the host's menu has them: the workspace on screen,
                            // one that is merely running behind it, and one that only exists on disk.
                            val isCurrent = workspace.id == currentWorkspaceId
                            val isRunning = !isCurrent && workspace.id in runningWorkspaceIds
                            WorkspaceMenuRow(
                                name = workspace.name,
                                isCurrent = isCurrent,
                                isRunning = isRunning,
                                onClick = { onPick(workspace) },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(DIALOG_INSET))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        BossSecondaryButton(text = "Cancel", onClick = onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceMenuRow(
    name: String,
    isCurrent: Boolean,
    isRunning: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(MENU_ROW_HEIGHT)
                .background(if (isHovered) BossColors.contextMenuHover else Color.Transparent)
                .hoverable(interactionSource)
                .clickable(onClick = onClick)
                .padding(horizontal = MENU_ROW_INSET),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MENU_ROW_INSET),
    ) {
        Text(
            text = name,
            fontSize = MENU_TEXT_SP.sp,
            // Never the accent: BossColors exposes `signal`, a FILL colour, which lands under
            // 4.5:1 as text on the default theme.
            color = if (isCurrent) BossThemeColors.TextPrimary else BossThemeColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Filled for the one on screen, outlined for one that is merely running: the same mark at
        // two strengths says "running" once and "yours" only on the one that is.
        val dot =
            when {
                isCurrent -> Icons.Filled.Circle
                isRunning -> Icons.Outlined.Circle
                else -> null
            }
        if (dot != null) {
            Icon(
                imageVector = dot,
                contentDescription = if (isCurrent) "Current workspace" else "Running",
                modifier = Modifier.size(MENU_DOT),
                tint = if (isCurrent) BossThemeColors.SuccessColor else BossThemeColors.TextSecondary,
            )
        }
    }
}

/**
 * Ask for a name and save the current workspace under it.
 *
 * The prompt is the HOST's dialog ([GenericDialogProvider.showTextInputDialog]) rather than one this
 * plugin draws: it is a suspend call that returns the answer, so there is no dialog-visibility state
 * to keep, and it is drawn by the host, which is the only party that can guarantee where a modal
 * lands relative to a browser surface.
 *
 * The host's own Save updates the current workspace with the live split tree first. A plugin cannot:
 * nothing on `SplitViewOperations` hands back the layout that is on screen, so this saves whatever
 * the host currently holds as the current workspace.
 */
private suspend fun saveWorkspace(
    workspaceDataProvider: WorkspaceDataProvider,
    dialogs: GenericDialogProvider,
) {
    val current = workspaceDataProvider.currentWorkspace.value
    val name =
        dialogs
            .showTextInputDialog(
                title = "Save Workspace",
                message = "Save the current layout under a name.",
                initialValue = current?.name.orEmpty(),
                placeholder = "Workspace name",
                validation = { if (it.isBlank()) "Enter a name" else null },
            )?.trim()
            .orEmpty()
    if (name.isEmpty()) return
    workspaceDataProvider.saveCurrentWorkspace(name)
}

/**
 * Choose a workspace, confirm, and delete it.
 *
 * Deleting is by NAME on this api - `WorkspaceDataProvider.deleteWorkspace(name: String)` - which is
 * worth stating next to the id-keyed rest of the interface, since passing an id there deletes
 * nothing and says nothing.
 */
private suspend fun deleteWorkspace(
    workspaceDataProvider: WorkspaceDataProvider,
    dialogs: GenericDialogProvider,
) {
    val saved = workspaceDataProvider.workspaces.value
    if (saved.isEmpty()) {
        dialogs.showAlertDialog(
            title = "Delete Workspace",
            message = "There are no saved workspaces to delete.",
        )
        return
    }
    val choice =
        dialogs.showChoiceDialog(
            title = "Delete Workspace",
            message = "Pick the workspace to delete.",
            // Keyed by NAME, because that is what deleteWorkspace takes.
            choices = saved.map { DialogChoice(id = it.name, label = it.name, description = it.description) },
        ) ?: return
    val confirmed =
        dialogs.showConfirmationDialog(
            title = "Delete Workspace",
            message = "Delete \"${choice.label}\"? This removes the saved layout and cannot be undone.",
            confirmText = "Delete",
            isDestructive = true,
        )
    if (confirmed) workspaceDataProvider.deleteWorkspace(choice.id)
}

/**
 * Open a workspace saved to a file: pick it, read it, load it, apply it.
 *
 * The read is off the UI thread and guarded: a file the user picked is arbitrary input, and
 * `WorkspaceSerializer.deserialize` throws on anything that is not a workspace. The failure is
 * reported when there is a dialog provider to report it with, and swallowed rather than crashing the
 * panel when there is not.
 */
private fun openWorkspaceFromFile(
    filePicker: FilePickerProvider,
    workspaceDataProvider: WorkspaceDataProvider,
    splitViewOperations: SplitViewOperations,
    dialogs: GenericDialogProvider?,
    scope: CoroutineScope,
) {
    filePicker.pickFile(title = "Open Workspace", filters = listOf("json")) { path ->
        if (path.isNullOrBlank()) return@pickFile
        scope.launch {
            val workspace =
                withContext(Dispatchers.IO) {
                    runCatching { WorkspaceSerializer.deserialize(File(path).readText()) }.getOrNull()
                }
            if (workspace == null) {
                dialogs?.showAlertDialog(
                    title = "Open Workspace",
                    message = "That file could not be read as a workspace.",
                )
                return@launch
            }
            workspaceDataProvider.loadWorkspace(workspace)
            splitViewOperations.applyWorkspace(workspace)
        }
    }
}
