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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The vertical tab bar's section header, verbatim: 24dp tall, 10sp SemiBold with 0.8sp tracking in
// textSecondary, inset 10dp from the leading edge. See TabBarSections.kt in the host.
private val HEADER_HEIGHT = 24.dp
private val HEADER_START = 10.dp
private val HEADER_END = 4.dp
private const val HEADER_SIZE_SP = 10
private val HEADER_TRACKING = 0.8.sp

private const val COUNT_ALPHA = 0.7f
private const val DROP_TARGET_FILL_ALPHA = 0.22f

/**
 * A workspace group header, and the drop target for moving a tab into that workspace.
 *
 * The header is the target rather than the group's whole area for a reason a drag makes obvious:
 * groups are collapsible, so a collapsed workspace has no area, and it is exactly the workspace
 * you are not looking at that you most want to file something into.
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
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            when {
                                isDropTarget -> BossThemeColors.AccentColor.copy(alpha = DROP_TARGET_FILL_ALPHA)
                                isHovered -> BossColors.darkSurface
                                else -> Color.Transparent
                            },
                        ).then(
                            if (isDropTarget) {
                                Modifier.border(1.dp, BossThemeColors.AccentColor, RoundedCornerShape(3.dp))
                            } else {
                                Modifier
                            },
                        ).hoverable(interactionSource)
                        .clickable(onClick = onActivate)
                        .padding(start = HEADER_START, end = HEADER_END),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector =
                        if (isExpanded) {
                            Icons.Default.ExpandMore
                        } else {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        },
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(12.dp).clickable(onClick = onToggleExpand),
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
 * Same typographic style as the workspace header one level up, indented by depth. The two divider
 * stubs either side of the label are gone: with the label already set in tracked small caps they
 * were decoration on top of a signal that was doing the work.
 */
@Composable
internal fun SplitSectionHeader(
    sectionName: String,
    indentDp: Int,
    isExpanded: Boolean,
    onToggleExpansion: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(HEADER_HEIGHT)
                .clip(RoundedCornerShape(3.dp))
                .background(if (isHovered) BossColors.darkSurface else Color.Transparent)
                .hoverable(interactionSource)
                .clickable(onClick = onToggleExpansion)
                .padding(start = indentDp.dp + HEADER_START, end = HEADER_END),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector =
                if (isExpanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier = Modifier.size(12.dp),
            tint = BossThemeColors.TextSecondary,
        )
        Text(
            text = sectionName.uppercase(),
            fontSize = HEADER_SIZE_SP.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = HEADER_TRACKING,
            color = BossThemeColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(modifier = Modifier.weight(1f))
    }
}
