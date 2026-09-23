package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.ui.BossColors
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossSecondaryButton
import ai.rever.boss.plugin.ui.BossTextField
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.boss.plugin.ui.ContextMenuItemData
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val SPACE_RENAME_MENU_LABEL = "Rename Space..."

/** The host's `LAST_SESSION_ID`, copied for the reason [isTemplate]'s ids are: a plugin cannot import it. */
internal const val LAST_SESSION_ID = "last-session"

/**
 * The Space a rename is aimed at: its id, and the SAVED name the host will look it up by.
 *
 * The name is the one in the saved list rather than the tree's label, because the api's
 * `renameWorkspace(oldName, newName)` finds the Space by name - a label that drifted from the saved
 * name would rename nothing, silently.
 */
internal data class RenameTarget(
    val workspaceId: String,
    val currentName: String,
)

/**
 * Whether the Space with [workspaceId] can be renamed from here, and under which name.
 *
 * Null - and so no menu row - for four kinds of Space, each of which a rename would not stick on,
 * which is a menu row that does nothing:
 *
 * - **Not saved.** A Space that is running but was never written has no entry to rename.
 * - **A template.** The host keeps the shipped layouts pristine (`isUserOwnedSpace`).
 * - **Last Session.** The host allows it, but the slot is restamped "Last Session" on every
 *   autosave (`asLastSession`), so the new name lasts until the next layout change. Save Space is
 *   the way to keep that layout under a name.
 * - **A name another saved Space shares.** The api renames BY NAME, so it would rename whichever
 *   of the two the host found first - possibly not the one that was right-clicked.
 */
internal fun renameTargetFor(
    workspaceId: String,
    saved: List<LayoutWorkspace>,
): RenameTarget? {
    val workspace = saved.firstOrNull { it.id == workspaceId } ?: return null
    if (workspace.isTemplate() || workspace.id == LAST_SESSION_ID) return null
    if (saved.count { it.name == workspace.name } > 1) return null
    return RenameTarget(workspaceId, workspace.name)
}

/**
 * Why [candidate] cannot be the new name, or null when it can.
 *
 * Mirrors the host's own refusals in `WorkspaceManager.renameWorkspaceById` - an empty name, the
 * name it already has, a name another Space holds - so the dialog says so instead of closing on
 * a rename the host then quietly ignores. The unchanged name returns an empty message: nothing to
 * complain about, but nothing to do either.
 */
internal fun renameProblem(
    candidate: String,
    target: RenameTarget,
    saved: List<LayoutWorkspace>,
): String? {
    val name = candidate.trim()
    return when {
        name.isEmpty() -> "Enter a name"
        name == target.currentName -> ""
        saved.any { it.id != target.workspaceId && it.name == name } -> "A Space named \"$name\" already exists"
        else -> null
    }
}

/**
 * The right-click rows for a Space, rename first and theme after.
 *
 * One builder for the tree's header and the floor map, so the two menus cannot drift apart. Either
 * half is left out when its callback is null, and an empty list means no menu at all.
 */
internal fun spaceMenuItems(
    onRename: (() -> Unit)?,
    onPickTheme: (() -> Unit)?,
): List<ContextMenuItemData> =
    buildList {
        onRename?.let {
            add(ContextMenuItemData(label = SPACE_RENAME_MENU_LABEL, icon = Icons.Outlined.Edit, onClick = it))
        }
        onPickTheme?.let { addAll(spaceThemeMenuItems(it)) }
    }

/**
 * Rename a Space. [BossDialog] for the reason [SpaceThemeDialog] is one: a plain Compose dialog
 * renders behind JxBrowser's surface.
 */
@Composable
internal fun SpaceRenameDialog(
    target: RenameTarget,
    saved: List<LayoutWorkspace>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    BossDialog(onDismissRequest = onDismiss) {
        BossTheme {
            SpaceRenameContent(target = target, saved = saved, onDismiss = onDismiss, onRename = onRename)
        }
    }
}

@Composable
private fun SpaceRenameContent(
    target: RenameTarget,
    saved: List<LayoutWorkspace>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(target.currentName) }
    val problem = renameProblem(name, target, saved)
    val submit = { if (problem == null) onRename(name.trim()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(
        modifier =
            Modifier
                .requiredWidthIn(min = RENAME_DIALOG_MIN_WIDTH, max = RENAME_DIALOG_MAX_WIDTH)
                .border(1.dp, BossThemeColors.BorderColor, RENAME_DIALOG_RADIUS),
        shape = RENAME_DIALOG_RADIUS,
        color = BossColors.contextMenuBackground,
    ) {
        Column(modifier = Modifier.padding(RENAME_DIALOG_INSET)) {
            Text(
                text = "Rename \"${target.currentName}\"",
                fontSize = RENAME_TITLE_SP.sp,
                fontWeight = FontWeight.SemiBold,
                color = BossThemeColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(RENAME_DIALOG_INSET))

            BossTextField(
                value = name,
                onValueChange = { name = it },
                label = "Space name",
                // An empty message is "unchanged": not an error, so nothing is drawn in red.
                isError = !problem.isNullOrEmpty(),
                errorMessage = problem?.takeIf { it.isNotEmpty() },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Enter, Key.NumPadEnter -> {
                                    submit()
                                    true
                                }
                                Key.Escape -> {
                                    onDismiss()
                                    true
                                }
                                else -> false
                            }
                        },
            )

            Spacer(modifier = Modifier.height(RENAME_DIALOG_INSET))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BossSecondaryButton(text = "Cancel", onClick = onDismiss)
                Spacer(modifier = Modifier.width(8.dp))
                BossPrimaryButton(text = "Rename", onClick = submit, enabled = problem == null)
            }
        }
    }
}

private val RENAME_DIALOG_MIN_WIDTH = 300.dp
private val RENAME_DIALOG_MAX_WIDTH = 380.dp
private val RENAME_DIALOG_INSET = 16.dp
private val RENAME_DIALOG_RADIUS = RoundedCornerShape(8.dp)
private const val RENAME_TITLE_SP = 14
