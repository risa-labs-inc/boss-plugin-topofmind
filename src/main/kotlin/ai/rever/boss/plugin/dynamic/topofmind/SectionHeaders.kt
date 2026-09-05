package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.ui.BossColors
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The vertical tab bar's section header, verbatim: 24dp tall, 10sp SemiBold with 0.8sp tracking in
// textSecondary, inset 10dp from the leading edge. See TabBarSections.kt in the host.
//
// The trailing inset is 10dp for the same reason the leading one is: the host's pane group header
// (`GroupHeaderRow` in TabBarGroupHeader.kt) is `padding(horizontal = 10.dp)` with 24dp actions in
// it. It was 4dp, which was fine while the trailing slot held only a tab count, and stops being
// fine the moment both header rows carry an action - two stacked rows whose buttons do not line up
// read as two different controls.
private val HEADER_HEIGHT = 24.dp
private val HEADER_START = 10.dp
private val HEADER_END = 10.dp
private const val HEADER_SIZE_SP = 10
private val HEADER_TRACKING = 0.8.sp
private val HEADER_RADIUS = RoundedCornerShape(3.dp)
private val CHEVRON_SIZE = 12.dp

// The pane group header's item gap in the host, and the tighter one the workspace header keeps.
// A split section is three things in a row; a workspace header is four (chevron, name, count,
// action) at the panel's full width, and 8dp between all of them squeezes the name for nothing.
private val SECTION_GAP = 8.dp
private val WORKSPACE_GAP = 6.dp

// The host's `HeaderAction`: a target as tall as the row, a 4dp radius, a 12dp glyph, and
// textSecondary that lifts to textPrimary under the pointer.
private val ACTION_TARGET = HEADER_HEIGHT
private val ACTION_GLYPH = 12.dp
private val ACTION_RADIUS = RoundedCornerShape(4.dp)

private const val COUNT_ALPHA = 0.7f
private const val DROP_TARGET_FILL_ALPHA = 0.22f

// The vertical tab bar's selected fill, same token and same alpha (BossTabButton's
// SELECTED_FILL_ALPHA): an accent wash rather than a solid, because the row sits on the panel
// surface and a solid block at this width reads as a button.
private const val CURRENT_FILL_ALPHA = 0.16f

/**
 * A workspace group header, and the drop target for moving a tab into that workspace.
 *
 * The header is the target rather than the group's whole area for a reason a drag makes obvious:
 * groups are collapsible, so a collapsed workspace has no area, and it is exactly the workspace
 * you are not looking at that you most want to file something into.
 *
 * [onCloseAll] closes every tab under this header. It is NULL when there is nothing to ask with,
 * and then the button is not drawn: a control that destroys this much has to be able to confirm
 * first, and an unconfirmable one is worse than an absent one.
 */
@Composable
internal fun WorkspaceHeader(
    node: TabTreeNode.WorkspaceNode,
    isExpanded: Boolean,
    isCurrent: Boolean,
    dragState: TabDragState?,
    showRuleAbove: Boolean,
    onToggleExpand: () -> Unit,
    onActivate: () -> Unit,
    onCloseAll: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isDropTarget = dragState?.hoveredWorkspaceId == node.workspaceId

    val dropTargetModifier =
        if (dragState != null) Modifier.workspaceDropTarget(node.workspaceId, dragState) else Modifier

    Column(modifier = Modifier.fillMaxWidth()) {
        // Full bleed, like the tab bar's group rule: it separates panes, so insetting it would make
        // it read as belonging to the group below rather than dividing the two.
        if (showRuleAbove) {
            Divider(color = BossThemeColors.BorderColor, modifier = Modifier.padding(top = 6.dp))
        }

        Box(modifier = Modifier.fillMaxWidth().then(dropTargetModifier)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(HEADER_HEIGHT)
                        .clip(HEADER_RADIUS)
                        .background(
                            // Order matches BossTabButton: a live drop beats everything, then
                            // the current workspace, then hover. Selection outranking hover is
                            // the tab bar's rule too - hovering the row you are already on
                            // should not dim the marker that says so.
                            when {
                                isDropTarget -> BossThemeColors.AccentColor.copy(alpha = DROP_TARGET_FILL_ALPHA)
                                isCurrent -> BossThemeColors.AccentColor.copy(alpha = CURRENT_FILL_ALPHA)
                                isHovered -> BossColors.darkSurface
                                else -> Color.Transparent
                            },
                        ).then(
                            if (isDropTarget) {
                                Modifier.border(1.dp, BossThemeColors.AccentColor, HEADER_RADIUS)
                            } else {
                                Modifier
                            },
                        ).hoverable(interactionSource)
                        .clickable(onClick = onActivate)
                        .padding(start = HEADER_START, end = HEADER_END),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WORKSPACE_GAP),
            ) {
                Icon(
                    imageVector =
                        if (isExpanded) {
                            Icons.Default.ExpandMore
                        } else {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        },
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(CHEVRON_SIZE).clickable(onClick = onToggleExpand),
                    tint = BossThemeColors.TextSecondary,
                )

                Text(
                    text = node.name.uppercase(),
                    fontSize = HEADER_SIZE_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = HEADER_TRACKING,
                    // The accent is a FILL and a stripe here, never text: BossColors exposes
                    // `signal`, not the dimmer `signalText` the host uses for accent-coloured
                    // glyphs, and signal as text lands under 4.5:1 on the default theme.
                    color = if (isCurrent) BossThemeColors.TextPrimary else BossThemeColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                // A plain number, where this used to be a filled pill. At 10sp inside a 24dp row
                // the pill was most of the row's height and read as a control.
                Text(
                    text = "${node.tabCount}",
                    fontSize = HEADER_SIZE_SP.sp,
                    color = BossThemeColors.TextMuted.copy(alpha = COUNT_ALPHA),
                )

                // Last, after the count, so the row reads "what, how many, and the one thing you
                // can do to all of it". Its own clickable consumes the press, so closing a
                // workspace's tabs never also switches to that workspace.
                onCloseAll?.let { close ->
                    HeaderAction(
                        icon = Icons.Outlined.Close,
                        description = "Close every tab in ${node.name}",
                        onClick = close,
                    )
                }
            }

            // The workspace on screen wears the tab bar's marker: full height, leading edge, 3dp.
            // It goes HERE rather than on a tab row because "which workspace is showing" is a
            // question this panel can answer and "which tab is active in that pane" is not.
            if (isCurrent) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .height(HEADER_HEIGHT)
                            .width(3.dp)
                            .background(BossThemeColors.AccentColor, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

/**
 * Publish this header's bounds so a drag can hit-test against it.
 *
 * Bounds go in and out with the composition rather than being recomputed per drag: a scroll moves
 * every header, and `onGloballyPositioned` already fires for that.
 */
@Composable
private fun Modifier.workspaceDropTarget(
    workspaceId: String,
    dragState: TabDragState,
): Modifier {
    var bounds by remember(workspaceId) { mutableStateOf(Rect.Zero) }

    DisposableEffect(workspaceId, bounds) {
        if (bounds != Rect.Zero) dragState.registerTarget(workspaceId, bounds)
        onDispose { if (bounds != Rect.Zero) dragState.unregisterTarget(workspaceId, bounds) }
    }

    return this.onGloballyPositioned { bounds = it.boundsInWindow() }
}

/**
 * A split section inside a workspace ("Left", "Top", ...).
 *
 * The host's pane group header (`GroupHeaderRow` in TabBarGroupHeader.kt), to the dp: a 24dp row
 * inset 10dp either side, its items 8dp apart, filled with `raised` under the pointer, a 10sp
 * SemiBold label on 0.8sp tracking taking `weight(1f)` and ellipsised, and its actions as 24dp
 * targets around a 12dp glyph. The two divider stubs either side of the label are long gone: with
 * the label already set in tracked small caps they were decoration on top of a signal that was
 * already doing the work.
 *
 * **The label stays uppercased**, where the host's is not. The host's group header has no other
 * heading near it; this one sits directly under a workspace header in the same tree, and the two
 * are the same kind of row. A tracked heading and an untracked one stacked 24dp apart read as a
 * mistake rather than as a hierarchy.
 *
 * **The chevron leads the row, where the host puts its split glyph.** These sections collapse and
 * the host's panes do not, so the disclosure marker is a real difference rather than drift - and
 * it belongs at the indent, because the indent is what says how deep a section is and a staircase
 * of chevrons down the left edge is what makes that depth legible. If the glyph below is ever
 * drawn it goes BETWEEN the chevron and the label, not before it: leading with the glyph would
 * push every chevron in by 16dp plus a gap and flatten that staircase.
 *
 * **The split glyph is deliberately not drawn.** The host's is honest because it is measured from
 * the panes' real rectangles, so it follows a divider as it is dragged. Nothing here can be: the
 * structure this panel renders comes from the workspace's SAVED [SplitConfig], which carries the
 * split TREE and no ratio at all, so any rectangle drawn from it would be an invented 50/50 - a
 * confident diagram of a split the user may have dragged to 20/80, over a layout that may itself
 * be behind what is on screen. `SplitPositionGlyph`'s own KDoc makes this argument for its null
 * case, and a wrong diagram is worse than none.
 *
 * [onCloseAll] closes every tab under this header, and is null when there is nothing to confirm
 * with - see [WorkspaceHeader].
 */
@Composable
internal fun SplitSectionHeader(
    sectionName: String,
    indentDp: Int,
    isExpanded: Boolean,
    onToggleExpansion: () -> Unit,
    onCloseAll: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(HEADER_HEIGHT)
                .clip(HEADER_RADIUS)
                .background(if (isHovered) BossColors.darkSurface else Color.Transparent)
                .hoverable(interactionSource)
                .clickable(onClick = onToggleExpansion)
                .padding(start = indentDp.dp + HEADER_START, end = HEADER_END),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        Icon(
            imageVector =
                if (isExpanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier = Modifier.size(CHEVRON_SIZE),
            tint = BossThemeColors.TextSecondary,
        )
        // weight(1f) on the label itself, as the host has it, rather than a spacer after it: a
        // spacer let the label push the trailing action off a narrow panel instead of ellipsising.
        Text(
            text = sectionName.uppercase(),
            fontSize = HEADER_SIZE_SP.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = HEADER_TRACKING,
            color = BossThemeColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        onCloseAll?.let { close ->
            HeaderAction(
                icon = Icons.Outlined.Close,
                description = "Close every tab in $sectionName",
                onClick = close,
            )
        }
    }
}

/**
 * One icon button on a header row, the host's `HeaderAction` verbatim.
 *
 * Always drawn rather than revealed on hover, for the host's reason: a control that only exists
 * once you are already pointing at it cannot be found by someone looking for it. Its own
 * `clickable` consumes the press, so it never also fires the row's click underneath it.
 */
@Composable
private fun HeaderAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier =
            Modifier
                .size(ACTION_TARGET)
                .clip(ACTION_RADIUS)
                .background(if (isHovered) BossColors.darkSurface else Color.Transparent)
                .hoverable(interactionSource)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(ACTION_GLYPH),
            tint = if (isHovered) BossThemeColors.TextPrimary else BossThemeColors.TextSecondary,
        )
    }
}
