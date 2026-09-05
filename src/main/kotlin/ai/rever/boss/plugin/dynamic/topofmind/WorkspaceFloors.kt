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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

/**
 * Air between the rule above the stack and its top floor.
 *
 * Without it the top plate's back edge sits on the rule and the two read as one line, which makes
 * the building look like it is hanging off the tree rather than standing under it.
 */
private val FLOORS_TOP_GAP = 8.dp

/**
 * How tall the whole stack is, however many storeys it has.
 *
 * Fixed, and sized against the host's own navigation map: that is a 1.5 aspect-ratio box inside a
 * 10dp inset, so in the sidebar's usual 200dp it stands about 140dp tall including its inset. The
 * two are the same kind of thing - a small picture of where you are - and a building that grew a
 * storey taller every time a workspace opened would push the tree out of the panel one row at a
 * time.
 *
 * The SLABS scale to fit instead - the bite between them is a constant. See [floorMetricsFor].
 * Above about five floors they hit their minimum and the stack scrolls rather than shrinking into
 * slivers.
 */
internal val FLOORS_HEIGHT = 140.dp

/**
 * How far a storey bites into the one below it. A CONSTANT, where the slab is not.
 *
 * A fraction of the slab was the other option, and it makes the bite grow with the storeys -
 * deepest exactly when there are two workspaces and the plates are largest and most worth seeing.
 * A constant is also the thing a reader can hold onto: the storeys resize, the join between them
 * does not.
 *
 * 12dp closes most of the `SKEW`-wide step in the left silhouette (see [FloorMetrics.overlap]) at
 * the slab sizes a window actually reaches, and leaves the rest of the plate carrying panes.
 */
private val FLOOR_OVERLAP = 12.dp

/**
 * The most of a plate the bite may take, whatever [FLOOR_OVERLAP] says.
 *
 * A constant bite is only constant while there is a plate to take it out of. At [MIN_SLAB] the
 * plate is about 14dp, and 12 of that would leave a sliver with nothing readable on it - so past
 * this fraction the bite gives way rather than the plate.
 */
private const val MAX_OVERLAP_OF_PLATE = 0.55f

/** The share of a slab given to the face that carries the name; the plate takes the rest. */
private const val RISER_SHARE = 0.4f

/** A face shorter than this cannot hold an 11sp name, so the PLATE gives way first. */
internal val MIN_RISER = 16.dp

/** Below this a storey is a sliver: the stack scrolls rather than shrinking past it. */
internal val MIN_SLAB = 30.dp

/** Above this a two-workspace window draws two enormous slabs instead of a building. */
internal val MAX_SLAB = 56.dp

/** Two storeys that near cannot both show a name, so the stack scrolls instead. */
internal val MIN_PITCH = 18.dp

/**
 * Room between the plate's edge and the name, and the shape of one storey, for a given floor count.
 *
 * The slab used to be a constant, and a stack of six workspaces was simply six times as tall as one.
 * [FLOORS_HEIGHT] is what is fixed now, and the SLAB is what gives: the bite between two storeys
 * ([FLOOR_OVERLAP]) is the same wherever it is.
 *
 * @property slab one storey, top of plate to bottom of face - what a floor DRAWS.
 * @property pitch the repeat distance between two storeys - what a floor OCCUPIES. Smaller than
 *   [slab], and the difference is the overlap.
 */
internal data class FloorMetrics(
    val slab: Dp,
    val pitch: Dp,
    val plate: Dp,
    val riser: Dp,
) {
    /**
     * How far this storey bites into the one below it: [FLOOR_OVERLAP], less any clamp.
     *
     * The step left in the left silhouette at a seam is `(plate - overlap) / plate * SKEW`, because
     * the plate's left edge travels the whole skew over the whole plate depth. Closing it entirely
     * means biting the WHOLE plate - identical boxes stacked with no gap hide each other's top faces
     * exactly - which is the version with no panes visible below the top floor, and the panes are
     * what this view is for.
     */
    val overlap: Dp get() = (slab - pitch).coerceAtLeast(0.dp)
}

/**
 * The shape of a storey in a stack of [count], sized so the whole stack is [FLOORS_HEIGHT] tall.
 *
 * `height = slab + (count - 1) * pitch` with `pitch = slab - FLOOR_OVERLAP`, since the top floor
 * shows a whole slab and every other one shows a pitch. Solving that for the slab is the first line.
 *
 * The height is exact while no clamp bites, which is three to five workspaces. Each clamp trades it
 * away deliberately, and the DIRECTION is the part that matters:
 *
 * - **[MAX_SLAB]**: one or two workspaces would otherwise draw 70-80dp slabs. The stack is then
 *   SHORTER than [FLOORS_HEIGHT] and the tree gets the difference, which is the better answer -
 *   holding the full height empty would take room from the thing this panel is mostly for.
 * - **[MIN_SLAB] / [MIN_PITCH] / [MAX_OVERLAP_OF_PLATE]**: past about five floors the storeys would
 *   be slivers and two faces would overlap so far that neither name is readable. The stack is then
 *   TALLER than [FLOORS_HEIGHT] and scrolls inside it, rather than shrinking into nothing.
 * - **[MIN_RISER]**: a face has to hold an 11sp name whatever the slab is, so the plate gives way
 *   first. At the minimum slab that is a 14dp plate, which still shows a two- or four-way split.
 */
internal fun floorMetricsFor(count: Int): FloorMetrics {
    // height = count * slab - (count - 1) * FLOOR_OVERLAP, solved for the slab.
    val ideal = (FLOORS_HEIGHT + FLOOR_OVERLAP * (count - 1).toFloat()) / count.toFloat()
    val slab = ideal.coerceIn(MIN_SLAB, MAX_SLAB)
    val riser = (slab * RISER_SHARE).coerceAtLeast(MIN_RISER)
    val plate = slab - riser
    val overlap = FLOOR_OVERLAP.coerceAtMost(plate * MAX_OVERLAP_OF_PLATE)
    val pitch = (slab - overlap).coerceAtLeast(MIN_PITCH)
    return FloorMetrics(slab = slab, pitch = pitch, plate = plate, riser = riser)
}

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

/** How far the current floor's plate is tinted toward the accent. A blend, not an alpha. */
private const val CURRENT_PLATE_ALPHA = 0.12f

/** The same for its front face, which takes more light than its plate and so more of the accent. */
private const val CURRENT_RISER_FRONT_ALPHA = 0.45f

/**
 * How far the side face is shaded past the front one.
 *
 * The two faces are one material under one light, so this is the whole of the difference between
 * them. Too little and the slab reads flat; too much and the side reads as a hole, which is the bug
 * this replaced.
 */
private const val SIDE_FACE_SHADE = 0.35f
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
    metrics: FloorMetrics,
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
    // The slab is SOLID: every face is an opaque blend of one surface toward the accent, never that
    // surface at an alpha. A translucent face lets the panel's ground through, which reads as the
    // slab being a wash over the page rather than a block sitting on it - and it made the current
    // floor's front face, at a higher alpha than its plate, look like the brighter of two washes
    // instead of the lit face of one box. Only the PANES on top of the plate are translucent, and
    // they have an opaque plate under them to be translucent against.
    val plateBase = lerp(BossColors.darkSurface, accent, if (isCurrent) CURRENT_PLATE_ALPHA else 0f)
    val riserFront = lerp(BossColors.darkSurface, accent, if (isCurrent) CURRENT_RISER_FRONT_ALPHA else 0f)
    // The SAME material as the front with less light on it, which is the only lighting cue the slab
    // gets and the thing that stops it reading as a flat card with a line under it.
    //
    // DERIVED from the front rather than stated, because stating it got it wrong twice. A
    // non-current floor's side was `BackgroundColor` - the panel's own ground - so the slab had a
    // hole cut in its right side rather than a shaded face, and a lit one's side was the accent at
    // a LOWER alpha than its front, which is the page showing through more, not a face in shadow.
    // Lerping toward black darkens and opacifies together, which is what a shaded face does.
    val riserSide = lerp(riserFront, Color.Black, SIDE_FACE_SHADE)
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
        // A full slab tall for the TOP storey and one pitch for the rest, which is what the overlap
        // costs each of them. The clip that makes it an overlap is inside the Canvas and is a SHAPE,
        // not this rectangle - see `covered` there.
        modifier =
            Modifier
                .fillMaxWidth()
                .height(if (isTop) metrics.slab else metrics.pitch)
                .hoverable(interaction)
                .clickable(onClick = onClick),
    ) {
        // The slab is always drawn whole, then slid up under the storey above and clipped to what
        // that storey leaves showing. Drawing a partial slab instead would mean clipping the plate,
        // its panes and the outline by hand; sliding it means every floor draws exactly the same
        // shape. No z-order to get right either - what is hidden is never drawn, so the list can
        // stay in reading order.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .requiredHeight(metrics.slab)
                    .offset(y = if (isTop) 0.dp else -metrics.overlap),
        ) {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = FLOORS_SIDE_INSET),
            ) {
                val skew = SKEW.toPx()
                val riser = metrics.riser.toPx()
                val overlapPx = metrics.overlap.toPx()
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

                fun drawSlab() {
                    // The two vertical faces first, so the plate lands on top of them. A vertical
                    // extrusion stays vertical in this projection, so these are a straight drop.
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
                        // Outlined whether or not it was filled, so the divisions survive both an
                        // empty pane and the label sitting over them.
                        drawPath(shape, paneEdge, style = Stroke(width = stroke))
                    }

                    drawPath(front, outline, style = Stroke(width = stroke))
                    drawPath(side, outline, style = Stroke(width = stroke))
                    drawPath(plate, outline, style = Stroke(width = stroke))
                }

                if (isTop) {
                    drawSlab()
                    return@Canvas
                }

                // Everything the storey ABOVE leaves showing, which is not a rectangle and was
                // drawn as one. That storey's underside runs level across its front face and then
                // SLOPES UP along its side face, so a horizontal cut at the band's top took the
                // back-right corner off every plate but the top one - visible as a flat crop
                // across the right of each slab, with nothing above it to justify the cut.
                //
                // This slab sits `overlap` lower than the one above, so in these coordinates that
                // underside is `overlap` down at the front and a whole plate-depth higher at the
                // far right, where the side face's bottom edge has climbed.
                val covered =
                    Path().apply {
                        moveTo(0f, overlapPx)
                        lineTo(plateWidth, overlapPx)
                        lineTo(size.width, overlapPx - depth)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                clipPath(covered) { drawSlab() }
            }

            // On the FRONT FACE, under the plate. `at(0f, 1f)` puts that face's left edge at the
            // drawing area's left and its right edge SKEW short of the right, so the trailing pad
            // carries the skew; it is the plate's depth down from the top of the slab and the riser's
            // which is the face exactly.
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = metrics.plate)
                        .height(metrics.riser)
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
