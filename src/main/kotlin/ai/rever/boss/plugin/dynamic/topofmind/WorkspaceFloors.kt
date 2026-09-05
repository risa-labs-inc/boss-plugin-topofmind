package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossColors
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------------------------
// The shape of one storey. Every number here was chosen against the width the panel actually has,
// which is the host sidebar's ~200dp and a user who can drag it narrower. See the KDoc on
// [WorkspaceFloors] for the arithmetic and for why the stack does NOT drift sideways as it rises.
// ---------------------------------------------------------------------------------------------

/** Room at each side of the stack, matching the tree's own 10dp header inset. */
private val FLOORS_SIDE_INSET = 10.dp

/**
 * How far a plate's BACK edge sits to the right of its front edge.
 *
 * This is the whole cost of the projection, and it is paid ONCE for the building rather than once
 * per storey - see [WorkspaceFloors]. At the panel's usual 200dp it is 22 of 180 drawable dp, so a
 * plate is 158dp wide and a two-pane split draws two 79dp blocks; dragged down to 120dp the plate
 * is 78dp and a four-way split still draws four 19dp blocks rather than slivers.
 */
private val SKEW = 22.dp

/** The plate's own vertical extent. Two stacked panes get ~9dp each, which is a readable band. */
private val PLATE_DEPTH = 18.dp

/** The slab's thickness: the two vertical faces under the plate that make it read as solid. */
private val RISER_DEPTH = 4.dp

/** Air between one slab and the next. Small, so the stack reads as a building and not as cards. */
private val FLOOR_GAP = 5.dp

/** The clickable band for one workspace: the slab plus the air under it. */
private val BAND_HEIGHT = PLATE_DEPTH + RISER_DEPTH + FLOOR_GAP

/**
 * How many storeys are on screen before the stack scrolls.
 *
 * A cap on the HEIGHT rather than on the floor count: a window running three workspaces gives the
 * tree back the room it does not need, and one running a dozen scrolls rather than hiding the
 * bottom of the list behind a "+N more" that cannot be clicked.
 */
private const val FLOORS_VISIBLE_MAX = 5
private val FLOORS_MAX_HEIGHT = BAND_HEIGHT * FLOORS_VISIBLE_MAX

// The panel's header language, verbatim: 24dp tall, 10sp SemiBold on 0.8sp tracking, inset 10dp.
private val FLOORS_HEADER_HEIGHT = 24.dp
private val FLOORS_HEADER_GAP = 6.dp
private val FLOORS_CHEVRON = 12.dp
private const val FLOORS_HEADER_SP = 10
private val FLOORS_HEADER_TRACKING = 0.8.sp

/**
 * Room between the plate's edge and the label on top of it.
 *
 * Half the skew plus a little: at the plate's vertical middle its left edge has travelled `SKEW/2`
 * in from the drawing area's left, and its right edge is `SKEW/2` short of the right. Inset by
 * less and a long name's first and last glyphs hang off the slanted ends.
 */
private val LABEL_INSET = SKEW / 2 + 5.dp
private const val FLOOR_NAME_SP = 11
private const val FLOOR_COUNT_SP = 10

// Fills, all derived from the accent token rather than stated as colours. The lit floor is the one
// on screen; the pane being worked in inside it is lit harder still.
private const val ACTIVE_PANE_ALPHA = 0.62f
private const val CURRENT_PANE_ALPHA = 0.34f
private const val HOVER_PANE_ALPHA = 0.18f
private const val CURRENT_PLATE_ALPHA = 0.12f
private const val PLATE_BASE_ALPHA = 0.45f
private const val CURRENT_RISER_FRONT_ALPHA = 0.45f
private const val CURRENT_RISER_SIDE_ALPHA = 0.22f
private const val PANE_EDGE_ALPHA = 0.7f

/**
 * Whether the floors stack is showing.
 *
 * A CLASS, one instance per mounted panel (see [TopofmindComponent]), for the reason written on
 * [TabTreeState], [TabDragState], [SplitPaneExpansion] and [PanelDialogState]: a top-level `object`
 * here would mean collapsing the stack in one window collapsed it in every other panel too. This
 * plugin has had that bug twice.
 *
 * There is a toggle at all because the stack is a fixed-height block in a sidebar that also has to
 * hold the tree - it takes at most [FLOORS_MAX_HEIGHT], and on a short window that is room the tree
 * would rather have.
 */
@Stable
class FloorsViewState {
    var expanded by mutableStateOf(true)
        private set

    fun toggle() {
        expanded = !expanded
    }
}

/**
 * One pane's place on a floor plate, as FRACTIONS of the plate (0..1 in both axes).
 *
 * [tabCount] is what the tree draws under that pane, not what the workspace owns - both come from
 * the same [WorkspaceTabStructure], so a pane with nothing in it is drawn empty rather than filled.
 */
internal data class FloorPane(
    val paneId: String?,
    val tabCount: Int,
    val area: Rect,
)

/**
 * A workspace's split tree, turned into rectangles on a floor plate.
 *
 * **Structurally true, schematically proportioned.** The structure is exact: which panes there are,
 * whether a split is side-by-side or stacked, and how the nesting goes. The PROPORTIONS are not,
 * and cannot be. `SplitConfig` is `SinglePanel` / `VerticalSplit(left, right)` /
 * `HorizontalSplit(top, bottom)` and carries **no ratio**, so a divider dragged to 20/80 is drawn
 * as halves - and a workspace that is not on screen was never measured, so not even the host has
 * bounds for it. The host's own `SplitMap` can be truthful about proportion because it is handed
 * the panes' measured rectangles; nothing on the plugin api hands a plugin those. This is the same
 * caveat `SplitPositionGlyph` in `SectionHeaders.kt` already carries, and if the api ever exposes
 * measured pane rects, this is the second function that should start using them.
 *
 * Every level divides its area into EQUAL parts. No ratio is invented from tab counts or from
 * anything else: a guess that looked like a measurement would be worse than a stated schematic.
 */
internal object WorkspaceFloorPlan {
    /**
     * The panes of one workspace, in the order [TabTreeBuilder] built them.
     *
     * Takes the structure the tree is already drawing rather than reading `SplitConfig` a second
     * time, so a floor and its group in the tree can never disagree about what the workspace looks
     * like - including the flat fallback [TabTreeBuilder] uses when the layout's panel count does
     * not match the running one, which lands here as a single undivided plate.
     */
    fun panesOf(structure: List<WorkspaceTabStructure>): List<FloorPane> =
        panesIn(structure, Rect(0f, 0f, 1f, 1f))

    private fun panesIn(
        nodes: List<WorkspaceTabStructure>,
        area: Rect,
    ): List<FloorPane> {
        val sections = nodes.filterIsInstance<WorkspaceTabStructure.SplitSection>()
        if (sections.isEmpty()) {
            // A leaf: [TabTreeBuilder.buildTabStructure] wraps a SinglePanel's tabs directly, so a
            // list with no sections in it is exactly one pane. `paneIdOf` answers null for an empty
            // one, which is right - it has no tabs to take an id from, and it is still drawn.
            return listOf(
                FloorPane(
                    paneId = TabTreeBuilder.paneIdOf(nodes),
                    tabCount = TabTreeBuilder.tabsIn(nodes).size,
                    area = area,
                ),
            )
        }
        // Which way this split runs comes from the section NAMES, which is all there is: the
        // builder emits "Left"/"Right" for a VerticalSplit and "Top"/"Bottom" for a HorizontalSplit.
        val stacked = sections.first().sectionName.equals("Top", ignoreCase = true) ||
            sections.first().sectionName.equals("Bottom", ignoreCase = true)
        val parts = sections.size
        return sections.flatMapIndexed { index, section ->
            val slice =
                if (stacked) {
                    val step = area.height / parts
                    Rect(area.left, area.top + step * index, area.right, area.top + step * (index + 1))
                } else {
                    val step = area.width / parts
                    Rect(area.left + step * index, area.top, area.left + step * (index + 1), area.bottom)
                }
            panesIn(section.children, slice)
        }
    }
}

/**
 * The window's workspaces as the storeys of a building, above the footer and below the tree.
 *
 * The tree says where a tab is by naming its workspace and its pane; this says the same thing as a
 * shape - here is every workspace this window is running, each one divided into its panes, and the
 * one on screen is the lit floor. Click a storey to switch to it.
 *
 * **Why the stack does not drift sideways as it rises.** The obvious hand-drawn version offsets
 * each floor a little further right than the one below, which is a cavalier oblique: the skew is
 * then paid once per storey, so eight workspaces at 22dp a floor would want 176dp of lateral room
 * before the first plate is drawn, in a sidebar that has ~180dp in total. In a true isometric the
 * vertical world axis maps to the vertical screen axis, so a building's corner columns are drawn
 * as vertical lines and congruent floor plates sit squarely above one another. That is the
 * projection used here: the skew is a flat 22dp for the whole building however many storeys it
 * has, and the plate never becomes a sliver.
 *
 * The order is [TabTreeBuilder]'s, so a workspace's storey and its group in the tree are in the
 * same position and the first workspace in the tree is the top floor.
 *
 * Selection is per FLOOR, not per pane, because switching workspaces is what a floor is for - so
 * the whole band takes the click and nothing here ever inverts the projection to find out which
 * parallelogram a press landed in.
 */
@Composable
internal fun WorkspaceFloors(
    nodes: List<TabTreeNode>,
    currentWorkspaceId: String?,
    activePanelId: String?,
    floorsState: FloorsViewState,
    onSelectWorkspace: (String) -> Unit,
) {
    val workspaces = remember(nodes) { nodes.filterIsInstance<TabTreeNode.WorkspaceNode>() }
    if (workspaces.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Divider(color = BossThemeColors.BorderColor)
        FloorsHeader(
            floorCount = workspaces.size,
            expanded = floorsState.expanded,
            onToggle = floorsState::toggle,
        )
        if (!floorsState.expanded) return@Column

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            // heightIn rather than height: a three-workspace window gets a three-storey block and
            // gives the rest back to the tree, and a twelve-workspace one scrolls inside the cap.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = FLOORS_MAX_HEIGHT)
                    .lazyListScrollbar(
                        listState = listState,
                        direction = Orientation.Vertical,
                        config = getPanelScrollbarConfig(),
                    ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Keyed, so a workspace opening or closing does not recycle a floor's hover state
            // onto a different building.
            items(count = workspaces.size, key = { workspaces[it].id }) { index ->
                val node = workspaces[index]
                Floor(
                    node = node,
                    isCurrent = currentWorkspaceId == node.workspaceId,
                    activePanelId = activePanelId,
                    onClick = { onSelectWorkspace(node.workspaceId) },
                )
            }
        }
    }
}

/** The stack's own heading, and the only way to get its height back. */
@Composable
private fun FloorsHeader(
    floorCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(FLOORS_HEADER_HEIGHT)
                .clickable(onClick = onToggle)
                .padding(horizontal = FLOORS_SIDE_INSET),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FLOORS_HEADER_GAP),
    ) {
        Icon(
            imageVector =
                if (expanded) {
                    Icons.Filled.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
            contentDescription = if (expanded) "Hide the floors view" else "Show the floors view",
            modifier = Modifier.size(FLOORS_CHEVRON),
            tint = BossThemeColors.TextSecondary,
        )
        Text(
            text = "FLOORS",
            fontSize = FLOORS_HEADER_SP.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = FLOORS_HEADER_TRACKING,
            color = BossThemeColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = floorCount.toString(),
            fontSize = FLOORS_HEADER_SP.sp,
            color = BossThemeColors.TextMuted,
            maxLines = 1,
        )
    }
}

/**
 * One storey: the slab, its panes, and the workspace's name written on the plate.
 *
 * The label goes ON the plate rather than beside it, and that is a width decision. A name in its
 * own column to the right of the slab wants ~60dp permanently, which at the 120dp the sidebar can
 * be dragged to would leave the plate under 40dp - four panes of 10dp, which is the illegible
 * outcome this view exists to avoid. On the plate the name costs nothing horizontally, the pane
 * edges stay drawn as lines underneath it, and it ellipsises the way every other row here does.
 */
@Composable
private fun Floor(
    node: TabTreeNode.WorkspaceNode,
    isCurrent: Boolean,
    activePanelId: String?,
    onClick: () -> Unit,
) {
    val panes = remember(node.tabStructure) { WorkspaceFloorPlan.panesOf(node.tabStructure) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val accent = BossThemeColors.AccentColor
    val lit = isCurrent || hovered
    val plateBase =
        if (isCurrent) accent.copy(alpha = CURRENT_PLATE_ALPHA) else BossColors.darkSurface.copy(alpha = PLATE_BASE_ALPHA)
    val riserFront =
        if (isCurrent) accent.copy(alpha = CURRENT_RISER_FRONT_ALPHA) else BossColors.darkSurface
    // The side face is the darker of the two, which is the only lighting cue the slab gets and the
    // thing that stops it reading as a flat card with a line under it.
    val riserSide =
        if (isCurrent) accent.copy(alpha = CURRENT_RISER_SIDE_ALPHA) else BossThemeColors.BackgroundColor
    val outline = if (lit) accent else BossThemeColors.BorderColor
    val paneEdge = BossThemeColors.BorderColor.copy(alpha = PANE_EDGE_ALPHA)
    val paneFills =
        panes.map { pane ->
            when {
                // An empty pane is left as the plate: the split is still drawn, and nothing claims
                // there is something in it.
                pane.tabCount == 0 -> Color.Transparent
                isCurrent && pane.paneId != null && pane.paneId == activePanelId ->
                    accent.copy(alpha = ACTIVE_PANE_ALPHA)
                isCurrent -> accent.copy(alpha = CURRENT_PANE_ALPHA)
                hovered -> accent.copy(alpha = HOVER_PANE_ALPHA)
                else -> BossColors.darkSurface
            }
        }
    // accentText, not AccentColor: the latter is the FILL token and lands under 4.5:1 as text. Same
    // rule the split-section header follows.
    val labelColor =
        when {
            isCurrent -> BossColors.accentText
            hovered -> BossThemeColors.TextPrimary
            else -> BossThemeColors.TextSecondary
        }

    Box(
        // The BAND takes the click, not the parallelogram: selection is per floor, so inverting the
        // projection on every press would buy a hit test nobody can tell apart from this one.
        modifier =
            Modifier
                .fillMaxWidth()
                .height(BAND_HEIGHT)
                .hoverable(interaction)
                .clickable(onClick = onClick),
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(PLATE_DEPTH + RISER_DEPTH)
                    .padding(horizontal = FLOORS_SIDE_INSET),
        ) {
            val skew = SKEW.toPx()
            val riser = RISER_DEPTH.toPx()
            val depth = size.height - riser
            val plateWidth = size.width - skew
            // A panel dragged narrower than the projection needs draws nothing rather than folding
            // the plate inside out.
            if (plateWidth <= 1f || depth <= 1f) return@Canvas
            val stroke = 1.dp.toPx()

            // The projection, in one line: a point (fx, fy) on the plate slides right as it goes
            // BACK, and the back edge is the top one.
            fun at(
                fx: Float,
                fy: Float,
            ) = Offset(skew * (1f - fy) + fx * plateWidth, fy * depth)

            fun quad(
                a: Offset,
                b: Offset,
                c: Offset,
                d: Offset,
            ): Path =
                Path().apply {
                    moveTo(a.x, a.y)
                    lineTo(b.x, b.y)
                    lineTo(c.x, c.y)
                    lineTo(d.x, d.y)
                    close()
                }

            fun dropped(point: Offset) = Offset(point.x, point.y + riser)

            val frontLeft = at(0f, 1f)
            val frontRight = at(1f, 1f)
            val backRight = at(1f, 0f)

            // The two vertical faces first, so the plate lands on top of them. A vertical extrusion
            // stays vertical in this projection, which is why these are a straight drop.
            val front = quad(frontLeft, frontRight, dropped(frontRight), dropped(frontLeft))
            val side = quad(backRight, frontRight, dropped(frontRight), dropped(backRight))
            drawPath(front, riserFront)
            drawPath(side, riserSide)

            val plate = quad(at(0f, 0f), at(1f, 0f), at(1f, 1f), at(0f, 1f))
            drawPath(plate, plateBase)

            panes.forEachIndexed { index, pane ->
                val shape =
                    quad(
                        at(pane.area.left, pane.area.top),
                        at(pane.area.right, pane.area.top),
                        at(pane.area.right, pane.area.bottom),
                        at(pane.area.left, pane.area.bottom),
                    )
                val fill = paneFills.getOrElse(index) { Color.Transparent }
                if (fill != Color.Transparent) drawPath(shape, fill)
                // Outlined whether or not it was filled, so the divisions survive both an empty
                // pane and the label sitting over them.
                drawPath(shape, paneEdge, style = Stroke(width = stroke))
            }

            drawPath(front, outline, style = Stroke(width = stroke))
            drawPath(side, outline, style = Stroke(width = stroke))
            drawPath(plate, outline, style = Stroke(width = stroke))
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(PLATE_DEPTH)
                    .padding(horizontal = FLOORS_SIDE_INSET + LABEL_INSET),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FLOORS_HEADER_GAP),
        ) {
            Text(
                text = node.name,
                fontSize = FLOOR_NAME_SP.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = node.tabCount.toString(),
                fontSize = FLOOR_COUNT_SP.sp,
                color = if (isCurrent) BossColors.accentText else BossThemeColors.TextMuted,
                maxLines = 1,
            )
        }
    }
}
