package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.alpha
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

// The host's split-diagram size, verbatim (TabBarGroupHeader's GLYPH_WIDTH / GLYPH_HEIGHT).
private val GLYPH_WIDTH = 16.dp
private val GLYPH_HEIGHT = 12.dp
private val ACTION_RADIUS = RoundedCornerShape(4.dp)

// The host's summary row, constant for constant (TabBarGroupHeader.kt): a 24dp row inset 8dp at
// the start and 6dp at the end, its items 2dp apart, with 18dp chips - smaller than a tab row's
// icon, because this row is chrome rather than content.
// The tab bar's gap above a group rule (WindowVerticalTabBar's GROUP_RULE_GAP).
private const val COLLAPSED_GLYPH_ALPHA = 0.55f

private val GROUP_RULE_GAP = 10.dp

private val SUMMARY_ROW_HEIGHT = 24.dp
private val SUMMARY_ROW_INDENT = 8.dp
private val SUMMARY_ROW_END = 6.dp
private val SUMMARY_ROW_GAP = 2.dp
private val SUMMARY_CHIP_SIZE = 18.dp
private val SUMMARY_CHIP_RADIUS = RoundedCornerShape(4.dp)
private val SUMMARY_CHEVRON = 14.dp
private const val SUMMARY_COUNT_SP = 10

// How many favicons fit before the row starts counting instead. The host's number, for the host's
// reason: past that the row would either wrap - changing its height - or clip marks without
// saying it had.
// TabFaviconChip's INACTIVE_ICON_ALPHA.
private const val SUMMARY_CHIP_ALPHA = 0.55f

private const val MAX_SUMMARY_CHIPS = 8

// A chip's own hover fill. NOT `darkSurface`, which is what the row underneath is already filled
// with once the pointer is anywhere in it - a chip in the same token would be invisible exactly
// when it is being pointed at. `contextMenuBorder` is `lineStrong` under a menu-shaped name, the
// same token a tab row uses for a pane's inactive selection.
private const val SUMMARY_CHIP_HOVER_ALPHA = 0.55f

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
 * **The row's click PINS the pane open**, it does not toggle a set of section ids. Which pane is
 * showing all its tabs is [SplitPaneExpansion]'s question: the pane being worked in always is,
 * hovering a header chooses which of the others is, and a click is what makes that choice survive
 * the pointer leaving. A section standing for a nested split rather than for one pane gets neither
 * - see [onToggleExpansion].
 *
 * **The split glyph is a position marker, not a measured diagram.** The host's is honest because
 * it is drawn from the panes' real rectangles, so it follows a divider as it is dragged. This one
 * cannot be: the structure this panel renders comes from the workspace's SAVED [SplitConfig],
 * which carries the split TREE and no ratio at all, so the fill is a schematic half - it says
 * WHICH side this pane is on and nothing about how the split is actually divided. It brightens
 * when the pane is open, which is the state a chevron used to carry.
 *
 * [onCloseAll] closes every tab under this header, and is null when there is nothing to confirm
 * with - see [WorkspaceHeader].
 */
@Composable
internal fun SplitSectionHeader(
    sectionName: String,
    indentDp: Int,
    isExpanded: Boolean,
    /**
     * Pin this pane open, or unpin it. Null for a section that stands for a nested split rather
     * than for one pane: it has nothing to collapse to, so its header carries no toggle rather
     * than a click that does nothing.
     */
    onToggleExpansion: (() -> Unit)?,
    /**
     * The pointer reached this header, which CHOOSES this pane as the open one.
     *
     * Not "expand while hovered" - see [SplitPaneExpansion] for why that collapses the section the
     * moment the pointer moves down onto the rows it just revealed. Null alongside a null
     * [onToggleExpansion], for the same reason.
     */
    onHover: (() -> Unit)? = null,
    onCloseAll: (() -> Unit)? = null,
    /**
     * Draw the tab bar's group rule above this header.
     *
     * False for the first section under a workspace: the workspace header is already the boundary
     * there, and a rule directly under it would be a second edge a few dp below the first.
     */
    showRuleAbove: Boolean = false,
    /**
     * Whether this section is the pane the user is working in.
     *
     * Drives one tint shared by the glyph and the label, which is how the host does it: one group
     * is highlighted at a time and always the same one, so the two read as a single statement
     * rather than two competing marks.
     */
    isActivePane: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // accentText, not AccentColor. The latter is the FILL token and lands under 4.5:1 as text on
    // the default theme; the host tints an active pane's header with signalText for that reason.
    val tint = if (isActivePane) BossColors.accentText else BossThemeColors.TextSecondary

    // One interaction source doing two jobs - tinting the row and choosing the open pane - where
    // the host keeps two. Here they are the same event read twice, and a second `hoverable` on the
    // same row would only be a second name for it.
    LaunchedEffect(isHovered) { if (isHovered) onHover?.invoke() }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Inset to where this pane's header content starts, NOT full bleed as the host has it.
        // The host's panes are top level, so a rule across the whole bar can only mean "the next
        // pane". Here sections are nested under a workspace and indented, so a full-bleed rule
        // would divide the workspace group as readily as the pane, and at depth it would not say
        // which level it belonged to. Starting where the header starts does.
        if (showRuleAbove) {
            Divider(
                color = BossThemeColors.BorderColor,
                modifier = Modifier.padding(start = indentDp.dp + HEADER_START, top = GROUP_RULE_GAP),
            )
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(HEADER_HEIGHT)
                    .clip(HEADER_RADIUS)
                    .background(if (isHovered) BossColors.darkSurface else Color.Transparent)
                    .hoverable(interactionSource)
                    .then(
                        if (onToggleExpansion != null) Modifier.clickable(onClick = onToggleExpansion) else Modifier,
                    ).padding(start = indentDp.dp + HEADER_START, end = HEADER_END),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SECTION_GAP),
        ) {
            // The tab bar's split diagram, not a chevron. Losing the chevron loses nothing: the
            // whole row is the toggle, and its state is legible from whether rows follow it.
            SplitPositionGlyph(sectionName = sectionName, expanded = isExpanded, tint = tint)
            // weight(1f) on the label itself, as the host has it, rather than a spacer after it: a
            // spacer let the label push the trailing action off a narrow panel instead of
            // ellipsising.
            Text(
                text = sectionName.uppercase(),
                fontSize = HEADER_SIZE_SP.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = HEADER_TRACKING,
                color = tint,
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
}

/**
 * The split, drawn: an outline of the whole area with this section's half filled in.
 *
 * The host's `SplitPositionGlyph` in shape and size, but built from the section's NAME rather than
 * from a measured rectangle, and that difference is the whole caveat. A name says which edges a
 * pane touches and nothing about where the divider sits, so the fill is a schematic half - it says
 * WHICH side this pane is on, which is exactly what "Left" already claims, and it does not say how
 * the split is actually divided. A pane dragged to 20/80 still draws as a half. Read it as a
 * position marker, not a diagram of the layout.
 *
 * [paneAreaFor] is shared with the floors stack, so a header and a storey cannot place one pane on
 * two different sides.
 *
 * The host's version can be truthful about proportion because it is handed measured pane rects. If
 * the api ever exposes those, this is the function that should start using them.
 */
@Composable
private fun SplitPositionGlyph(
    sectionName: String,
    expanded: Boolean,
    tint: Color,
) {
    // Fractions of the frame, matching the host's PaneGlyph shape (left, top, right, bottom).
    // A name this does not place - "Pane 3" - gets the frame and no fill, for the host's stated
    // reason: the host numbers a pane precisely when no honest name fits, and a filled box would
    // be a claim about the split rather than an absence of one.
    val fill = paneAreaFor(sectionName)
    val outline = BossThemeColors.BorderColor
    // Dimmed while the pane is collapsed, so the glyph still carries the state the chevron used to
    // WITHOUT overriding the caller's tint - which is what says whether this is the active pane.
    val fillColor = if (expanded) tint else tint.copy(alpha = COLLAPSED_GLYPH_ALPHA)

    Canvas(modifier = Modifier.size(width = GLYPH_WIDTH, height = GLYPH_HEIGHT)) {
        val stroke = 1.dp.toPx()
        drawRect(
            color = outline,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke),
        )
        if (fill == null) return@Canvas
        // Inset by the outline so the fill sits INSIDE the frame rather than on top of it: a pane
        // that touches an edge should still read as bounded by the window.
        val inner = Size(size.width - stroke * 2f, size.height - stroke * 2f)
        drawRect(
            color = fillColor,
            topLeft = Offset(stroke + fill.left * inner.width, stroke + fill.top * inner.height),
            size = Size((fill.right - fill.left) * inner.width, (fill.bottom - fill.top) * inner.height),
        )
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

/**
 * The row standing in for a collapsed pane's other tabs.
 *
 * The host's `TabGroupSummaryRow` (TabBarGroupHeader.kt), ported: 24dp tall, indented to sit under
 * the pane's tabs rather than beside its header, its chevron where a tab row's icon is.
 *
 * **Favicons rather than a count.** "7 more tabs" said how many there were and nothing about what
 * they were, so finding one meant opening the pane and reading names; a row of marks is
 * recognisable at a glance and each one goes straight to its tab. Capped at [MAX_SUMMARY_CHIPS]
 * with a `+N` after it, so the row is always exactly one row tall whatever the pane holds.
 *
 * **Hovering anywhere here opens the pane**, exactly as hovering its header does - reaching for
 * the row of marks is reaching for what they stand for, and making the user click first was a step
 * for nothing. Opening removes this row, which leaves the pointer over one of the tabs it just
 * revealed, and that is fine because the choice is sticky. See [SplitPaneExpansion].
 *
 * The chips carry no tooltip, where the host's do: the plugin ui exposes no hover-tooltip
 * primitive (and a raw Compose `Popup` renders behind a hardware-composited browser surface). A
 * chip is therefore a mark you recognise or click, not one you can ask the name of - the tab's
 * full row is one hover away on the header.
 */
@Composable
internal fun PaneSummaryRow(
    hidden: List<ActiveTabData>,
    indentDp: Int,
    activeTabsProvider: ActiveTabsProvider,
    onHover: () -> Unit,
    onToggleExpansion: () -> Unit,
    onSelectTab: (ActiveTabData) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    LaunchedEffect(isHovered) { if (isHovered) onHover() }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SUMMARY_ROW_HEIGHT)
                .hoverable(interactionSource)
                .background(if (isHovered) BossColors.darkSurface else Color.Transparent)
                .padding(start = indentDp.dp + SUMMARY_ROW_INDENT, end = SUMMARY_ROW_END),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SUMMARY_ROW_GAP),
    ) {
        // The one target on this row that is about the PANE rather than about one tab in it.
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = "Show all tabs in this pane",
            tint = BossThemeColors.TextSecondary,
            modifier = Modifier.size(SUMMARY_CHEVRON).clickable(onClick = onToggleExpansion),
        )
        Spacer(modifier = Modifier.size(SUMMARY_ROW_GAP))

        hidden.take(MAX_SUMMARY_CHIPS).forEach { tab ->
            key(tab.tabId) {
                SummaryChip(
                    tab = tab,
                    activeTabsProvider = activeTabsProvider,
                    onClick = { onSelectTab(tab) },
                )
            }
        }

        if (hidden.size > MAX_SUMMARY_CHIPS) {
            Text(
                text = "+${hidden.size - MAX_SUMMARY_CHIPS}",
                color = BossThemeColors.TextSecondary,
                fontSize = SUMMARY_COUNT_SP.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One hidden tab, as its favicon and nothing else.
 *
 * The glyph is [TabGlyph], the same function a tab row draws - favicon first, the host's fallback
 * icon next, a typed icon last - so a tab looks the same whether its row is on screen or it has
 * been collapsed into this row. The chip is the hit target around it, 18dp to the glyph's 14dp,
 * which is margin worth clicking rather than decoration.
 */
@Composable
private fun SummaryChip(
    tab: ActiveTabData,
    activeTabsProvider: ActiveTabsProvider,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier =
            Modifier
                .size(SUMMARY_CHIP_SIZE)
                .clip(SUMMARY_CHIP_RADIUS)
                .background(
                    if (isHovered) {
                        BossColors.contextMenuBorder.copy(alpha = SUMMARY_CHIP_HOVER_ALPHA)
                    } else {
                        Color.Transparent
                    },
                ).hoverable(interactionSource)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Dimmed, as the host's chips are (TabFaviconChip's INACTIVE_ICON_ALPHA, applied because
        // the summary row passes isActive = false). These stand for tabs that are NOT showing, so
        // at full strength a row of eight of them out-shouted the one tab the pane is actually on.
        // Alpha on the wrapper rather than a tint, because most of these are real favicons drawn
        // as an Image and a tint would not touch them.
        Box(modifier = Modifier.alpha(SUMMARY_CHIP_ALPHA)) {
            TabGlyph(tab = tab, activeTabsProvider = activeTabsProvider)
        }
    }
}
