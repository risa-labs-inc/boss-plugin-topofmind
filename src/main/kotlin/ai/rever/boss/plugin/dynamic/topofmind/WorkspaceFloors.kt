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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------------------------
// A floor is a shallow box, seen head on and a little from above: a front face carrying the name
// and the panes, plus a thin top face and a thin right face receding back-and-up. The depth is
// small on purpose - a bar with a hint of solidity, not a slab.
//
// The stack died five times as an isometric PLATE, with the panes on a top face seen from above.
// That model cannot work at all: two identical boxes stacked with no air between them hide each
// other's tops exactly, so every version that drew a lower workspace's plate was drawing a thing
// that cannot exist, and that plate's back corner had nowhere coherent to go. This one keeps the
// panes on the FRONT and fits each box's whole silhouette - front, top and side - inside its own
// band, so the gap between two storeys is real air and no face ever reaches the floor above.
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

/**
 * How far back a floor's far face sits: across, and up.
 *
 * This is the depth axis of the projection. A point on the front face plus
 * ([FLOOR_DEPTH_X], -[FLOOR_DEPTH_Y]) is the same point on the back of the box, so the whole
 * building is drawn with one vector and vertical world edges stay vertical on screen. The ratio is
 * about tan(30 degrees), the isometric one.
 *
 * The SIZE is what makes this view "slightly" isometric: the projection that was rejected five
 * times skewed by 22dp. It is paid once for the whole stack rather than once per storey, and it
 * comes off the drawable width, so with the sidebar dragged to its 120dp minimum the front face is
 * still about 92dp wide - room for a workspace name and its tab count.
 */
private val FLOOR_DEPTH_X = 8.dp
private val FLOOR_DEPTH_Y = 4.dp

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

// The two receding faces are SHADING of the front one, not colours of their own: each is a blend of
// whatever ground that floor already has, so a lit floor is lit on all three faces and the accent
// tint is stated once. The side goes darker and the top a little lighter, which is what makes three
// flat quadrilaterals read as one solid. An early version painted the side in
// `BossThemeColors.BackgroundColor` - a token with nothing to do with the floor - and it read as a
// hole punched in the bar rather than a face of it.
private const val SIDE_FACE_SHADE = 0.35f
private const val TOP_FACE_SHADE = 0.10f

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
 * each floor a little further right than the one below, which is a cavalier oblique: the depth is
 * then paid once per storey, so eight workspaces at 8dp a floor would want 64dp of lateral room
 * before the first face is drawn, in a sidebar that has ~180dp in total and can be dragged to 100dp.
 * Here the vertical world axis maps to the vertical screen axis, as it does in a true isometric, so
 * a building's corner edges are vertical lines and every storey is the same box drawn in the same
 * place. [FLOOR_DEPTH_X] is paid ONCE for the whole building however many storeys it has.
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

        // Bring a workspace that has just opened into view.
        //
        // It is appended to the bottom (see WorkspaceArrival), so past the four or so floors that
        // fit it opens BELOW the fold: the panel would answer a new workspace by showing nothing at
        // all. Scrolling to it is the whole of the feedback that it landed.
        //
        // The first sighting is not an opening. Everything the window was already running arrives
        // at once when the panel mounts, and scrolling then would jump the block to the bottom on
        // every launch - so the first batch is recorded and nothing is scrolled to. `seen` is the
        // record: ids that have been through here, which is also why a workspace that closes and
        // opens again is scrolled to a second time, the same way it takes a fresh slot in the
        // ordering.
        //
        // The LAST new id, not the first: two workspaces opening in one tick are both below the
        // fold, and the lower of them is the one that needs the scroll. Keyed on `workspaces`, so
        // the roughly-2s rebuild that changes nothing does not re-run it, and a rebuild that
        // changes a tab title finds no new ids and scrolls nowhere.
        val seen = remember { mutableSetOf<String>() }
        LaunchedEffect(workspaces) {
            val ids = workspaces.map { it.workspaceId }
            val firstSighting = seen.isEmpty()
            val opened = ids.filterNot { it in seen }
            seen.clear()
            seen.addAll(ids)
            if (firstSighting) return@LaunchedEffect
            val target = opened.lastOrNull() ?: return@LaunchedEffect
            val index = ids.indexOf(target)
            // A stack that fits its cap does not scroll, and this is a no-op there rather than a
            // special case: every floor is already in view.
            if (index >= 0) listState.animateScrollToItem(index)
        }

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
 * One floor: a shallow box, divided into the workspace's panes on its front face, with its name
 * written across the same face.
 *
 * **The panes stay on the FRONT, and that is what makes the depth safe.** This was an isometric
 * PLATE for five rounds - the panes on a top face seen from above, with the extrusion hanging below
 * it - and every round foundered on the same corner, because two identical boxes stacked with no
 * air between them hide each other's tops exactly. Showing a lower workspace's plate was therefore
 * drawing something that cannot exist, and its back corner had nowhere to go: it came back as a
 * wedge, then a flat crop, then a column, then a skirt, then a step.
 *
 * The box drawn here is free-standing. Its whole silhouette - front face, top face, side face - is
 * laid out inside this floor's own band, so the depth is taken out of the band rather than added on
 * top of it, and the [FloorMetrics.gap] between two bands stays air that nothing is drawn into. No
 * floor can occlude another, so there is no impossible corner to resolve.
 *
 * The depth is deliberately small ([FLOOR_DEPTH_X] across, [FLOOR_DEPTH_Y] up, where the rejected
 * version used 22dp). It should read as a bar with a little solidity, not as a slab, and the view
 * still says the three things it is for: which workspaces are running, how each one is split, and
 * which one is on screen.
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
    // What the front face mostly READS as: the ground, plus whatever wash the panes lay over it.
    // The two receding faces are shaded off THIS rather than off the bare ground, so a lit floor
    // gets a lit box - off the ground alone its top face came out darker than its own front and the
    // depth read as a shadow between two bars instead of as one solid.
    val body =
        when {
            isCurrent -> lerp(ground, accent, CURRENT_PANE_ALPHA)
            hovered -> lerp(ground, accent, HOVER_PANE_ALPHA)
            else -> ground
        }
    // Black and white are the shading here, not palette choices: a floor's colour is stated once,
    // in `ground`, and its faces follow it.
    val topFace = lerp(body, Color.White, TOP_FACE_SHADE)
    val sideFace = lerp(body, Color.Black, SIDE_FACE_SHADE)
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
                val half = stroke / 2f
                val depthX = FLOOR_DEPTH_X.toPx()
                val depthY = FLOOR_DEPTH_Y.toPx()

                // The whole box fits the band, inset half a stroke so the outline is not clipped
                // at the edges. The depth comes OUT of the band: the front face gives up depthY at
                // the top and depthX at the right, and what the box gains is a top face and a side
                // face in that room. Nothing is drawn outside these bounds, which is why the air
                // between two floors stays air.
                val backTop = half
                val backRight = size.width - half
                val faceLeft = half
                val faceTop = backTop + depthY
                val faceRight = backRight - depthX
                val faceBottom = size.height - half
                val faceWidth = faceRight - faceLeft
                val faceHeight = faceBottom - faceTop

                // A list, not a vararg: `Offset` is a value class and Kotlin refuses to spread one.
                fun face(corners: List<Offset>) =
                    Path().apply {
                        moveTo(corners.first().x, corners.first().y)
                        corners.drop(1).forEach { lineTo(it.x, it.y) }
                        close()
                    }

                // Every corner is a front-face corner plus the depth vector (depthX, -depthY), so
                // the two receding faces are the same projection applied twice and cannot disagree.
                val topFacePath =
                    face(
                        listOf(
                            Offset(faceLeft, faceTop),
                            Offset(faceRight, faceTop),
                            Offset(backRight, backTop),
                            Offset(faceLeft + depthX, backTop),
                        ),
                    )
                val sideFacePath =
                    face(
                        listOf(
                            Offset(faceRight, faceTop),
                            Offset(backRight, backTop),
                            Offset(backRight, faceBottom - depthY),
                            Offset(faceRight, faceBottom),
                        ),
                    )
                drawPath(path = topFacePath, color = topFace)
                drawPath(path = sideFacePath, color = sideFace)
                drawRect(ground, topLeft = Offset(faceLeft, faceTop), size = Size(faceWidth, faceHeight))

                // The panes, as fractions of the FRONT face. `WorkspaceFloorPlan` already answers
                // in a 0..1 box, so the fractions ARE the rectangle and nothing here is projected:
                // a left/right split draws as two columns and a top/bottom one as two rows, which
                // is what those words mean. Skewing them onto a receding face is exactly the plate
                // that was rejected five times.
                panes.forEachIndexed { index, pane ->
                    val topLeft =
                        Offset(faceLeft + pane.area.left * faceWidth, faceTop + pane.area.top * faceHeight)
                    val paneSize =
                        Size(
                            (pane.area.right - pane.area.left) * faceWidth,
                            (pane.area.bottom - pane.area.top) * faceHeight,
                        )
                    val fill = paneFills.getOrElse(index) { Color.Transparent }
                    if (fill != Color.Transparent) drawRect(fill, topLeft = topLeft, size = paneSize)
                    // Outlined whether or not it was filled, so the divisions survive both an empty
                    // pane and the label sitting over them.
                    drawRect(paneEdge, topLeft = topLeft, size = paneSize, style = Stroke(width = stroke))
                }

                // The box's own edges last, over the panes. Stroking the two faces and the front
                // rectangle draws every visible edge once each and the shared ones twice, which
                // costs nothing and never leaves a corner open.
                drawPath(path = topFacePath, color = outline, style = Stroke(width = stroke))
                drawPath(path = sideFacePath, color = outline, style = Stroke(width = stroke))
                drawRect(
                    color = outline,
                    topLeft = Offset(faceLeft, faceTop),
                    size = Size(faceWidth, faceHeight),
                    style = Stroke(width = stroke),
                )
            }

            Row(
                // The name lives on the FRONT face, unskewed, so the padding gives back exactly the
                // room the top and side faces took. Text sliding onto a receding face would be text
                // on a wall that is not facing the reader.
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(top = FLOOR_DEPTH_Y, end = FLOOR_DEPTH_X)
                        .padding(horizontal = LABEL_INSET),
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
