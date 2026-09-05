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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
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
import androidx.compose.ui.draw.clipToBounds
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

/** The plate's own vertical extent. Two stacked panes get 13dp each, which is a readable band. */
private val PLATE_DEPTH = 26.dp

/**
 * The slab's thickness: the two vertical faces under the plate that make it read as solid.
 *
 * This is what carries the workspace's NAME, which is why it is 18dp and not the 4dp it started
 * at. At 4 the front face was a line under the plate and the stack read as a pile of cards; at 18
 * it is a face with writing on it, which is what a storey of a building looks like. It also takes
 * the label off the plate, so the pane rectangles are no longer drawn under a row of text.
 */
private val RISER_DEPTH = 18.dp

/** One slab, top of plate to bottom of face. What a floor draws, before any overlap. */
private val SLAB_HEIGHT = PLATE_DEPTH + RISER_DEPTH

/**
 * How far a storey BITES INTO the one below it.
 *
 * Flush was not enough. With the slabs merely touching, the left silhouette stepped in by [SKEW] at
 * every seam - a floor's face ends at its plate's front-left corner and the next floor's plate
 * begins at its BACK-left one, which is the skew further right - so the outline restarted at each
 * storey and the stack read as a pile of trays. Overlapping puts the back of a plate UNDER the face
 * above it, which is where a real storey's slab goes, and the corner columns then run straight down
 * the whole building.
 *
 * **How much is not a free choice, and 6dp was not enough.** The step left at a seam is
 * `(PLATE_DEPTH - FLOOR_OVERLAP) / PLATE_DEPTH * SKEW`, because the plate's left edge travels the
 * whole skew over the whole plate depth. At 6dp of 26 that left 17dp of the 22dp step still there,
 * which is why the storeys still read as separate. Closing it COMPLETELY means overlapping by the
 * entire plate - identical boxes stacked with no gap hide each other's top faces exactly - and that
 * is the version with no panes visible below the top floor, which is most of what this view is for.
 *
 * 16dp is the compromise: the step drops to about 8dp, and 10dp of plate is left showing, which is
 * enough to read a two- or four-way split. Raising it further trades the panes for the silhouette.
 */
private val FLOOR_OVERLAP = 16.dp

/**
 * Air between the rule above the stack and its top floor.
 *
 * Without it the top plate's back edge sits on the rule and the two read as one line, which makes
 * the building look like it is hanging off the tree rather than standing under it.
 */
private val FLOORS_TOP_GAP = 8.dp

/** The clickable band for one workspace: its slab, less what the storey above takes back. */
private val BAND_HEIGHT = SLAB_HEIGHT - FLOOR_OVERLAP

/**
 * How many storeys are on screen before the stack scrolls.
 *
 * A cap on the HEIGHT rather than on the floor count: a window running three workspaces gives the
 * tree back the room it does not need, and one running a dozen scrolls rather than hiding the
 * bottom of the list behind a "+N more" that cannot be clicked.
 *
 * Four. A slab is 44dp - a 26dp plate over an 18dp face with the name on it - and each one bites
 * 16dp into the storey below, so a band is 28dp and four of them plus the bottom slab's own full
 * height come to 128dp. The deeper bite bought the fourth storey: the stack takes about what it did
 * at three shallower ones, and shows one more workspace.
 */
private const val FLOORS_VISIBLE_MAX = 4

/** The TOP floor shows a whole slab; the rest show a band each. */
private val FLOORS_MAX_HEIGHT = SLAB_HEIGHT + BAND_HEIGHT * (FLOORS_VISIBLE_MAX - 1)

/**
 * Room between the front face's edges and the name written on it.
 *
 * The face is a plain rectangle - a vertical extrusion stays vertical in this projection - so this
 * is an ordinary inset, where the label needed `SKEW / 2` of clearance while it sat on the slanted
 * plate. The face's RIGHT edge is the plate's front-right corner, which is [SKEW] short of the
 * drawing area, so the trailing pad carries the skew as well.
 */
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
private const val CURRENT_PLATE_ALPHA = 0.12f
private const val PLATE_BASE_ALPHA = 0.45f
private const val CURRENT_RISER_FRONT_ALPHA = 0.45f
private const val CURRENT_RISER_SIDE_ALPHA = 0.22f
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
 * 24dp row to say so. The block is bounded by [FLOORS_MAX_HEIGHT] and shrinks to fit a window with
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
                    // Nothing is stacked on the top floor, so it is the one storey that shows its
                    // whole slab - back edge included. Every other floor draws only the part the
                    // storey above does not cover.
                    isTop = index == 0,
                    isCurrent = currentWorkspaceId == node.workspaceId,
                    activePanelId = activePanelId,
                    onClick = { onSelectWorkspace(node.workspaceId) },
                )
            }
        }
    }
}

/**
 * One storey: the plate with its panes on it, and the workspace's name on the face underneath.
 *
 * The label is on the slab rather than beside it, and that is a width decision. A name in its own
 * column to the right wants ~60dp permanently, which at the 120dp the sidebar can be dragged to
 * would leave the plate under 40dp - four panes of 10dp, the illegible outcome this view exists to
 * avoid. On the slab it costs nothing horizontally and ellipsises like every other row here.
 *
 * On the FACE rather than on the plate, which is the second decision. The plate is the picture -
 * the panes are drawn there - and a row of text across it sat over the very rectangles it was meant
 * to caption. The face is a plain rectangle (a vertical extrusion stays vertical in this
 * projection), so text on it needs no clearance for the slant, and a storey with writing on its
 * front is what a floor of a building looks like.
 */
@Composable
private fun Floor(
    node: TabTreeNode.WorkspaceNode,
    isTop: Boolean,
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
    // The name sits ON the front face, and for the current floor that face is the accent at
    // CURRENT_RISER_FRONT_ALPHA - a fill. So the accent cannot also be the text: the fill is
    // already saying which floor this is, and TextPrimary is what reads against it. Same rule as
    // the split-section header, applied to a label that moved onto a filled surface.
    val labelColor = if (lit) BossThemeColors.TextPrimary else BossThemeColors.TextSecondary

    Box(
        // The BAND takes the click, not the parallelogram: selection is per floor, so inverting the
        // projection on every press would buy a hit test nobody can tell apart from this one.
        //
        // A full slab tall for the TOP storey and FLOOR_OVERLAP shorter for the rest, which is what
        // the overlap costs each of them. `clipToBounds` is what makes it an overlap rather than a
        // collision: the slab below is pulled UP by that much and would otherwise paint over the
        // face of the storey above it, name and all.
        modifier =
            Modifier
                .fillMaxWidth()
                .height(if (isTop) SLAB_HEIGHT else BAND_HEIGHT)
                .clipToBounds()
                .hoverable(interaction)
                .clickable(onClick = onClick),
    ) {
        // The slab is always drawn whole, then slid up under the storey above and clipped. Drawing
        // a partial slab instead would mean clipping the plate, its panes and the outline by hand;
        // sliding it means every floor draws exactly the same shape and the band decides how much
        // of it survives. No z-order to get right either - nothing overlaps anything, so the list
        // can stay in reading order.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .requiredHeight(SLAB_HEIGHT)
                    .offset(y = if (isTop) 0.dp else -FLOOR_OVERLAP),
        ) {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxSize()
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

            // On the FRONT FACE, under the plate. `at(0f, 1f)` puts that face's left edge at the
            // drawing area's left and its right edge SKEW short of the right, so the trailing pad
            // carries the skew; it is PLATE_DEPTH down from the top of the slab and RISER_DEPTH tall,
            // which is the face exactly.
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = PLATE_DEPTH)
                        .height(RISER_DEPTH)
                        .padding(
                            start = FLOORS_SIDE_INSET + LABEL_INSET,
                            end = FLOORS_SIDE_INSET + SKEW + LABEL_INSET,
                        ),
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
