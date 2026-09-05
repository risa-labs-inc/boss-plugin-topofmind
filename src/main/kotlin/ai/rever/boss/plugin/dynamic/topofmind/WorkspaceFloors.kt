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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Divider
import androidx.compose.material.Text
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------------------------
// A floor is a flat rectangle seen head on. There is no projection: no skew, no plate, no faces.
// The stack went through four goes at an isometric one and every one of them died on the same
// impossible corner - a lower box's top showing under the box above it. A front elevation has no
// such corner, is legible at half the height, and says the one thing the view is for: which
// workspaces are running, how each is split, and which one you are in.
// ---------------------------------------------------------------------------------------------

/** Room at each side of the stack, matching the tree's own 10dp header inset. */
private val FLOORS_SIDE_INSET = 10.dp

/**
 * Air between the rule above the stack and its top floor.
 *
 * Without it the top floor's edge sits on the rule and the two read as one line.
 */
private val FLOORS_TOP_GAP = 8.dp

/**
 * How tall the whole stack is, however many storeys it has.
 *
 * Fixed, and sized against the host's own navigation map: that is a 1.5 aspect-ratio box inside a
 * 10dp inset, so in the sidebar's usual 200dp it stands about 140dp tall. The two are the same kind
 * of thing - a small picture of where you are - and a building that grew a storey taller every time
 * a workspace opened would push the tree out of the panel one row at a time.
 *
 * The FLOORS scale to fit instead. Past the clamps in [floorMetricsFor] the stack scrolls rather
 * than shrinking into slivers.
 */
internal val FLOORS_HEIGHT = 140.dp

/** Air between one floor and the next. Constant: the floors resize, the joins do not. */
private val FLOOR_GAP = 3.dp

/** A floor shorter than this cannot hold an 11sp name, so the stack scrolls instead. */
internal val MIN_FLOOR = 26.dp

/** Above this two workspaces draw two enormous bars instead of a building. */
internal val MAX_FLOOR = 48.dp

/**
 * The height of one floor in a stack of [count], and the air under it.
 *
 * `height = count * floor + (count - 1) * gap`, solved for the floor. The clamps trade the fixed
 * height away deliberately, and the DIRECTION is the part that matters: [MAX_FLOOR] makes a
 * one- or two-workspace stack SHORTER than [FLOORS_HEIGHT] and hands the difference to the tree,
 * where [MIN_FLOOR] makes a crowded one TALLER, so it scrolls rather than becoming unreadable.
 */
internal data class FloorMetrics(
    val height: Dp,
    val gap: Dp,
) {
    /** The repeat distance between two floors. */
    val pitch: Dp get() = height + gap
}

internal fun floorMetricsFor(count: Int): FloorMetrics {
    val ideal = (FLOORS_HEIGHT - FLOOR_GAP * (count - 1).toFloat()) / count.toFloat()
    return FloorMetrics(height = ideal.coerceIn(MIN_FLOOR, MAX_FLOOR), gap = FLOOR_GAP)
}

/** Room between a floor's edge and the name on it. */
private val LABEL_INSET = 8.dp

/** Between the workspace name and its tab count, the panel's own header spacing. */
private val LABEL_GAP = 6.dp
private const val FLOOR_NAME_SP = 11
private const val FLOOR_COUNT_SP = 10

// Fills, all derived from the accent token rather than stated as colours. The lit floor is the one
// on screen; the pane being worked in inside it is lit harder still.
private const val ACTIVE_PANE_ALPHA = 0.62f
private const val CURRENT_PANE_ALPHA = 0.34f
private const val HOVER_PANE_ALPHA = 0.18f

/** How far the current floor's ground is tinted toward the accent. A blend, not an alpha. */
private const val CURRENT_FLOOR_ALPHA = 0.12f
private const val PANE_EDGE_ALPHA = 0.7f

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
 * A workspace's panes, turned into rectangles on a floor plate.
 *
 * The panes and their names come from [TabTreeBuilder], which takes both from the host - so a
 * storey and its group in the tree above it can never disagree about what the workspace looks
 * like. Where a pane goes on the plate comes from [paneAreaFor], the same mapping the section
 * headers' position glyph uses.
 *
 * **Structurally true, schematically proportioned.** Which panes there are and which side each one
 * is on is exact. The PROPORTIONS are not, and cannot be: a name says which edges a pane touches
 * and nothing about where the divider sits, so a split dragged to 20/80 is drawn as halves. The
 * host's own `SplitMap` can be truthful about proportion because it is handed the panes' measured
 * rectangles; nothing on the plugin api hands a plugin those.
 *
 * No ratio is invented from tab counts or from anything else: a guess that looked like a
 * measurement would be worse than a stated schematic.
 */
internal object WorkspaceFloorPlan {
    private val WHOLE_PLATE = Rect(0f, 0f, 1f, 1f)

    /** Rounding room on the "do these rectangles cover the plate" test. */
    private const val COVERAGE_TOLERANCE = 0.001

    /**
     * The panes of one workspace, in the order [TabTreeBuilder] built them.
     *
     * Takes the structure the tree is already drawing rather than reading `SplitConfig` a second
     * time, so a floor and its group in the tree can never disagree about what the workspace looks
     * like - including the flat fallback [TabTreeBuilder] uses when the layout's panel count does
     * not match the running one, which lands here as a single undivided plate.
     */
    fun panesOf(structure: List<WorkspaceTabStructure>): List<FloorPane> {
        val sections = structure.filterIsInstance<WorkspaceTabStructure.SplitSection>()
        if (sections.isEmpty()) {
            // A workspace with one pane: [TabTreeBuilder] emits its tabs with no section over them,
            // because one pane is not a split. `paneIdOf` answers null for an empty one, which is
            // right - it has no tabs to take an id from, and it is still drawn.
            return listOf(
                FloorPane(
                    paneId = TabTreeBuilder.paneIdOf(structure),
                    tabCount = TabTreeBuilder.tabsIn(structure).size,
                    area = WHOLE_PLATE,
                ),
            )
        }

        val named = sections.map { paneAreaFor(it.sectionName) }
        val areas = if (tiles(named)) named.filterNotNull() else evenSlices(sections)
        return sections.mapIndexed { index, section ->
            FloorPane(
                paneId = TabTreeBuilder.paneIdOf(section.children),
                tabCount = TabTreeBuilder.tabsIn(section.children).size,
                area = areas[index],
            )
        }
    }

    /**
     * Whether these rectangles are a floor plan: every pane placed, none over another, no gaps.
     *
     * The exact path is worth having and worth refusing. "Left" plus "Top right" plus
     * "Bottom right" is a real arrangement and the names describe it completely, so it is drawn
     * as it is. "Left" plus "Pane 2" plus "Right" is the three-column split, where the host
     * numbers the middle pane because no honest name fits - taking the two names at face value
     * would draw two halves with the third pane on top of them both.
     *
     * The area test is what catches a gap; overlap alone would accept two panes named "Left" and
     * "Top" and leave half the plate blank.
     */
    private fun tiles(areas: List<Rect?>): Boolean {
        val placed = areas.filterNotNull()
        if (placed.size != areas.size) return false
        val covered = placed.sumOf { (it.width * it.height).toDouble() }
        if (kotlin.math.abs(covered - 1.0) > COVERAGE_TOLERANCE) return false
        return placed.indices.none { i ->
            (i + 1..placed.lastIndex).any { j -> placed[i].overlaps(placed[j]) }
        }
    }

    /**
     * Equal parts, in the panel's own order, when the names do not place every pane.
     *
     * The AXIS still comes from the names: a pane the host called "Top" or "Bottom" runs the full
     * width of the workspace, so its siblings are stacked and slicing into columns would draw a
     * three-row split lying on its side. With nothing to go on it slices into columns, which is
     * the sidebar's wider axis.
     *
     * Equal, and never weighted by tab count - a guess that looked like a measurement would be
     * worse than a stated schematic.
     */
    private fun evenSlices(sections: List<WorkspaceTabStructure.SplitSection>): List<Rect> {
        val stacked = sections.any { namesStackedPane(it.sectionName) }
        val step = 1f / sections.size
        return sections.indices.map { index ->
            if (stacked) {
                Rect(0f, step * index, 1f, step * (index + 1))
            } else {
                Rect(step * index, 0f, step * (index + 1), 1f)
            }
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
 *
 * **Always on, and with no heading**, like the host's own navigation map. It had a FLOORS heading
 * with a chevron and a `FloorsViewState` behind it; both are gone. A heading over a picture of the
 * window's workspaces is a label on something that is already showing what it is, and it cost a
 * 24dp row to say so. The block is bounded by [FLOORS_HEIGHT] and shrinks to fit a window with
 * two workspaces in it, so there was never much height for a toggle to give back.
 */
@Composable
internal fun WorkspaceFloors(
    nodes: List<TabTreeNode>,
    currentWorkspaceId: String?,
    activePanelId: String?,
    onSelectWorkspace: (String) -> Unit,
) {
    val workspaces = remember(nodes) { nodes.filterIsInstance<TabTreeNode.WorkspaceNode>() }
    if (workspaces.isEmpty()) return

    // One shape for the whole building, from how many storeys it has. Read here rather than inside
    // a floor, because a storey's size is a fact about the stack and not about itself.
    val metrics = remember(workspaces.size) { floorMetricsFor(workspaces.size) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Divider(color = BossThemeColors.BorderColor)
        Spacer(modifier = Modifier.height(FLOORS_TOP_GAP))

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            // heightIn rather than height: a three-workspace window gets a three-storey block and
            // gives the rest back to the tree, and a twelve-workspace one scrolls inside the cap.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = FLOORS_HEIGHT)
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
                    metrics = metrics,
                    isCurrent = currentWorkspaceId == node.workspaceId,
                    activePanelId = activePanelId,
                    onClick = { onSelectWorkspace(node.workspaceId) },
                )
            }
        }
    }
}

/**
 * One floor: a flat bar, divided into the workspace's panes, with its name written across it.
 *
 * **Seen head on. There is no projection here and that is the point.** This was an isometric slab
 * for four rounds and every one of them foundered on the same corner - a lower box's top face
 * showing under the box above it, which two identical stacked boxes cannot do. It came back as a
 * wedge, then a flat crop, then a column, then a skirt, then a step. A front elevation has no such
 * corner: floors are rectangles, they stack, and the drawing is finished.
 *
 * What is left is what the view was ever for - which workspaces are running, how each one is split,
 * and which is on screen - at half the height and with none of the geometry.
 */
@Composable
private fun Floor(
    node: TabTreeNode.WorkspaceNode,
    metrics: FloorMetrics,
    isCurrent: Boolean,
    activePanelId: String?,
    onClick: () -> Unit,
) {
    val panes = remember(node.tabStructure) { WorkspaceFloorPlan.panesOf(node.tabStructure) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val accent = BossThemeColors.AccentColor
    val lit = isCurrent || hovered
    // Opaque blends, never a surface at an alpha: a translucent floor lets the panel's ground
    // through and reads as a wash over the page rather than a bar drawn on it. Only the PANES are
    // translucent, and they have an opaque floor under them to be translucent against.
    val ground = lerp(BossColors.darkSurface, accent, if (isCurrent) CURRENT_FLOOR_ALPHA else 0f)
    val outline = if (lit) accent else BossThemeColors.BorderColor
    val paneEdge = BossThemeColors.BorderColor.copy(alpha = PANE_EDGE_ALPHA)
    val paneFills =
        panes.map { pane ->
            when {
                // An empty pane is left as the floor: the split is still drawn, and nothing claims
                // there is something in it.
                pane.tabCount == 0 -> Color.Transparent
                isCurrent && pane.paneId != null && pane.paneId == activePanelId ->
                    accent.copy(alpha = ACTIVE_PANE_ALPHA)
                isCurrent -> accent.copy(alpha = CURRENT_PANE_ALPHA)
                hovered -> accent.copy(alpha = HOVER_PANE_ALPHA)
                else -> BossColors.darkSurface
            }
        }
    // The accent is a FILL token and lands under 4.5:1 as text, which is written down under Colours
    // and has caught this plugin before - so on a floor already tinted with it, the name is
    // TextPrimary. The tint is what says which floor this is.
    val labelColor = if (lit) BossThemeColors.TextPrimary else BossThemeColors.TextSecondary

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(metrics.pitch)
                .hoverable(interaction)
                .clickable(onClick = onClick),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(metrics.height)
                    .padding(horizontal = FLOORS_SIDE_INSET),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 1.dp.toPx()
                drawRect(ground)

                // The panes, as fractions of the bar. `WorkspaceFloorPlan` already answers in a
                // 0..1 box, so a front elevation needs no transform at all - the fractions ARE the
                // rectangle. A left/right split draws as two columns and a top/bottom one as two
                // rows, which is what those words mean.
                panes.forEachIndexed { index, pane ->
                    val topLeft = Offset(pane.area.left * size.width, pane.area.top * size.height)
                    val paneSize =
                        Size(
                            (pane.area.right - pane.area.left) * size.width,
                            (pane.area.bottom - pane.area.top) * size.height,
                        )
                    val fill = paneFills.getOrElse(index) { Color.Transparent }
                    if (fill != Color.Transparent) drawRect(fill, topLeft = topLeft, size = paneSize)
                    // Outlined whether or not it was filled, so the divisions survive both an empty
                    // pane and the label sitting over them.
                    drawRect(paneEdge, topLeft = topLeft, size = paneSize, style = Stroke(width = stroke))
                }

                drawRect(
                    color = outline,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke),
                )
            }

            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = LABEL_INSET),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LABEL_GAP),
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
                    color = if (lit) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}
