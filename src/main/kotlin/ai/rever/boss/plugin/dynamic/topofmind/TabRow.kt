package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.ui.BossColors
import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.boss.plugin.ui.ContextMenuItemData
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The vertical tab bar's own metrics, so a Top of Mind row and a tab-bar row read as the same
// control. See BossTabButton.kt in the host: 32dp tall, flush, 3dp radius, 8dp inside, 6dp between.
internal val ROW_HEIGHT = 32.dp
internal val ROW_RADIUS = RoundedCornerShape(3.dp)
internal val ROW_INSET = 8.dp
internal val ROW_ITEM_GAP = 6.dp
internal val ROW_ICON = 14.dp

// Fills are alphas over theme tokens rather than a fixed wash, for the same reason the host gives:
// these rows sit on the panel surface, not on the content floor.
private const val HOVER_FILL_ALPHA = 0.55f
private const val UNSELECTED_TEXT_ALPHA = 0.8f
private const val DRAG_SOURCE_ALPHA = 0.35f
private const val MOVED_FLASH_ALPHA = 0.28f

/**
 * One tab in the tree.
 *
 * Single line on purpose. The URL used to be a second 9.sp line under the title, which made every
 * row twice as tall as the tab bar's and turned a list of ten tabs into a wall - it is a tooltip
 * now, where a person who wants it can ask for it.
 */
@Composable
internal fun TabRow(
    tab: ActiveTabData,
    activeTabsProvider: ActiveTabsProvider,
    contextMenuProvider: ContextMenuProvider?,
    dragState: TabDragState?,
    transferTargets: List<TransferTarget>,
    /**
     * Whether this tab belongs to the workspace currently on screen.
     *
     * Deliberately NOT "this is the selected tab": nothing available here can say which tab is
     * active in a given pane - [ActiveTabData] carries the workspace and the panel and no
     * selection - and a marker that guessed would be wrong for every pane but one. So this drives
     * the same full-strength-vs-dimmed TEXT the tab bar uses, and the accent marker lives on the
     * workspace header, which is a thing we can actually answer.
     */
    inCurrentWorkspace: Boolean,
    indent: Dp,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onMoveTo: (String) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val isDragSource = dragState?.dragging?.tabId == tab.tabId
    val justMoved = dragState?.recentlyMovedTabId == tab.tabId

    val fill =
        when {
            justMoved -> BossThemeColors.AccentColor.copy(alpha = MOVED_FLASH_ALPHA)
            isHovered -> BossColors.darkSurface.copy(alpha = HOVER_FILL_ALPHA)
            else -> Color.Transparent
        }

    val dragModifier =
        if (dragState != null) {
            rememberTabDragModifier(tab, dragState) { onMoveTo(it.targetWorkspaceId) }
        } else {
            Modifier
        }

    val menuItems = tabMenuItems(tab, transferTargets, onClick, onClose, onMoveTo)
    val contextMenuModifier =
        if (contextMenuProvider != null && menuItems.isNotEmpty()) {
            contextMenuProvider.applyContextMenu(Modifier, menuItems)
        } else {
            Modifier
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .then(contextMenuModifier)
                .then(dragModifier)
                .background(fill, ROW_RADIUS)
                .hoverable(interactionSource)
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(start = indent + ROW_INSET, end = ROW_INSET),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ROW_ITEM_GAP),
        ) {
            TabGlyph(tab, activeTabsProvider, dimmed = isDragSource)

            Text(
                text = tab.title.ifEmpty { "Untitled" },
                fontSize = 13.sp,
                fontWeight = if (inCurrentWorkspace) FontWeight.Medium else FontWeight.Normal,
                color =
                    if (inCurrentWorkspace) {
                        BossThemeColors.TextPrimary
                    } else {
                        BossThemeColors.TextPrimary.copy(alpha = UNSELECTED_TEXT_ALPHA)
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )

            // No reserved space: the title takes the width back when nothing is shown, which is
            // what keeps a narrow sidebar readable.
            if (isHovered) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close ${tab.title}",
                    modifier =
                        Modifier
                            .size(12.dp)
                            .clickable(onClick = onClose),
                    tint = BossThemeColors.TextSecondary,
                )
            }
        }
    }
}

/**
 * Long-press to pick a row up, then drag it onto a workspace header.
 *
 * Long-press rather than a plain drag because the row is also a click target and a scrollable list
 * item; a bare `detectDragGestures` would steal both.
 *
 * [rememberUpdatedState] on everything the gesture reads is load-bearing. `pointerInput` captures
 * its lambda once per key and keeps running it for the life of the block, so a value read directly
 * would be whatever it was at the composition that started the gesture - the stale-capture bug
 * that made split-drag gain jump.
 */
@Composable
private fun rememberTabDragModifier(
    tab: ActiveTabData,
    dragState: TabDragState,
    onDrop: (TabDragState.TransferRequest) -> Unit,
): Modifier {
    val currentTab by rememberUpdatedState(tab)
    val currentState by rememberUpdatedState(dragState)
    val currentOnDrop by rememberUpdatedState(onDrop)
    // Pointer events arrive local to this row; the drop targets are measured in window space and
    // share no parent with it, so the row's own origin is what makes the two comparable.
    val origin = remember { mutableStateOf(Rect.Zero) }

    return Modifier
        .onGloballyPositioned { origin.value = it.boundsInWindow() }
        .pointerInput(tab.tabId) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    currentState.startDrag(currentTab, origin.value.topLeft + offset)
                },
                onDrag = { change, _ ->
                    change.consume()
                    currentState.updateDrag(origin.value.topLeft + change.position)
                },
                onDragEnd = { currentState.endDrag()?.let(currentOnDrop) },
                onDragCancel = { currentState.cancelDrag() },
            )
        }
}

@Composable
private fun TabGlyph(
    tab: ActiveTabData,
    activeTabsProvider: ActiveTabsProvider,
    dimmed: Boolean,
) {
    val alpha = if (dimmed) DRAG_SOURCE_ALPHA else 1f
    val favicon = activeTabsProvider.loadFavicon(tab.faviconCacheKey)
    val fallback = activeTabsProvider.getFallbackIcon(tab.typeId)
    val modifier = Modifier.size(ROW_ICON)

    when {
        // A real favicon keeps the site's own colours, so it is an Image and never tinted.
        favicon != null -> Image(painter = favicon, contentDescription = null, modifier = modifier)
        fallback != null ->
            Icon(
                imageVector = fallback,
                contentDescription = null,
                modifier = modifier,
                tint = tabIconTint(tab.typeId).copy(alpha = alpha),
            )
        else ->
            Icon(
                imageVector = tabIcon(tab.typeId),
                contentDescription = null,
                modifier = modifier,
                tint = tabIconTint(tab.typeId).copy(alpha = alpha),
            )
    }
}

/**
 * Right-click menu for a row.
 *
 * "Move to workspace" is a submenu rather than a flat list because the destinations are named by
 * the user and there can be as many as they have workspaces running. It is omitted entirely when
 * there is nowhere to move to - a disabled item that is always disabled teaches nothing.
 */
private fun tabMenuItems(
    tab: ActiveTabData,
    transferTargets: List<TransferTarget>,
    onFocus: () -> Unit,
    onClose: () -> Unit,
    onMoveTo: (String) -> Unit,
): List<ContextMenuItemData> =
    buildList {
        add(ContextMenuItemData(label = "Focus", icon = Icons.AutoMirrored.Outlined.OpenInNew, onClick = onFocus))
        if (transferTargets.isNotEmpty()) {
            add(
                ContextMenuItemData(
                    label = "Move to workspace",
                    icon = Icons.Outlined.Workspaces,
                    subMenu =
                        transferTargets.map { target ->
                            ContextMenuItemData(
                                label = target.name,
                                onClick = { onMoveTo(target.workspaceId) },
                            )
                        },
                ),
            )
        }
        add(ContextMenuItemData(label = "", isDivider = true))
        add(ContextMenuItemData(label = "Close Tab", icon = Icons.Outlined.Close, onClick = onClose))
        // The tab's own workspace, last, as context rather than an action.
        add(ContextMenuItemData(label = "In: ${tab.workspaceName}"))
    }

internal fun tabIcon(typeId: String): ImageVector =
    when {
        typeId.contains("browser", ignoreCase = true) || typeId.contains("fluck", ignoreCase = true) ->
            Icons.Outlined.Language
        typeId.contains("terminal", ignoreCase = true) -> Icons.Outlined.Terminal
        typeId.contains("editor", ignoreCase = true) -> Icons.Outlined.Code
        else -> Icons.Outlined.Tab
    }

/**
 * Tint for a fallback glyph.
 *
 * Theme tokens, not the per-type literals this used to carry (a Google blue, a terminal green, an
 * editor red). Those were fixed values chosen against one dark theme and they do not survive a
 * theme switch, which every other colour here now does.
 */
@Composable
internal fun tabIconTint(typeId: String): Color =
    when {
        typeId.contains("browser", ignoreCase = true) || typeId.contains("fluck", ignoreCase = true) ->
            BossThemeColors.AccentColor
        typeId.contains("terminal", ignoreCase = true) -> BossThemeColors.SuccessColor
        typeId.contains("editor", ignoreCase = true) -> BossThemeColors.SecondaryColor
        else -> BossThemeColors.TextSecondary
    }
