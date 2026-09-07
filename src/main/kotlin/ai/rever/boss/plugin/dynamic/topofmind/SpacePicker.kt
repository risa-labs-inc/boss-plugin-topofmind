package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.ui.BossColors
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossSearchBar
import ai.rever.boss.plugin.ui.BossSecondaryButton
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.SplitConfig
import androidx.compose.foundation.Canvas
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.scrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

// The dialog. Wider than the list it replaced, because a grid needs a second column to be a grid:
// at DIALOG_MIN_WIDTH two tiles fit, at DIALOG_MAX_WIDTH three. The radius is the quick switcher's
// 8dp rather than the 4dp this dialog inherited from the popup it used to be - the plugin's two
// dialogs are the same class of thing and were 4dp apart for no reason.
private val DIALOG_MIN_WIDTH = 320.dp
private val DIALOG_MAX_WIDTH = 480.dp
private val DIALOG_INSET = 12.dp
private val DIALOG_RADIUS = RoundedCornerShape(8.dp)
private const val DIALOG_TITLE_SP = 15
private val SEARCH_HEIGHT = 28.dp

/**
 * The tallest the grid gets before it scrolls inside the dialog.
 *
 * Three rows of tiles plus their gaps is 328dp, so this shows three whole rows and the top of a
 * fourth - which is the point of not rounding it down to exactly three. A user with twenty Spaces
 * gets a scrolling grid rather than a dialog taller than the window.
 */
private val GRID_MAX_HEIGHT = 340.dp

/**
 * The narrowest a tile is allowed to be, which is what decides the column count.
 *
 * The host's home grid uses 132dp (`HomeToolMinWidth` there). This is 120dp instead, deliberately:
 * a home tile sits in the full width of the window where these sit in a 320..480dp dialog, and 132
 * would give the narrow dialog two columns with 24dp of dead air rather than two tiles. 120 lands
 * two columns at [DIALOG_MIN_WIDTH] and three at [DIALOG_MAX_WIDTH], with the tiles about 140dp
 * wide either way.
 *
 * The HEIGHT is the host's own 104dp unchanged: the thumbnail, a two-line name and a state line
 * need that much whatever the dialog is doing.
 */
internal val TILE_MIN_WIDTH = 120.dp
private val TILE_HEIGHT = 104.dp

/** `internal` with [TILE_MIN_WIDTH] so [tileColumnsFor]'s boundary can be tested in its own terms. */
internal val TILE_GAP = 8.dp
private val TILE_RADIUS = RoundedCornerShape(8.dp)
private val TILE_INSET = 8.dp
private val TILE_ITEM_GAP = 4.dp

/** The tools menu's hover strength, so a hovered tile looks the same in both dialogs. */
private const val TILE_HOVER_ALPHA = 0.22f

/**
 * How visible the grid's scrollbar is while there is anywhere to scroll.
 *
 * Matches the tools menu's own value. A little under the 0.8 the panel default animates to on a
 * gesture, because this one is always there and a permanent mark wants to be quieter.
 */
private const val SCROLLBAR_ALPHA = 0.7f

/**
 * The floor plan's frame: about the 1.5 aspect the host's navigation map uses, since the two are
 * the same kind of thing - a small picture of a window's panes.
 *
 * It stands where a home tile's 28dp icon slot stands, and is bigger than one because a division
 * has to be legible: at [SpaceLayoutPlan.MAX_DEPTH] the narrowest pane this can draw is an eighth
 * of the frame, 7dp, which is still a rectangle rather than a line.
 */
private val THUMB_WIDTH = 56.dp
private val THUMB_HEIGHT = 36.dp

/**
 * One fixed-height slot for the state line, so a tile with no state still lines up with one.
 *
 * 14dp rather than the 12 it was, and stated together with [HINT_LINE_SP], because a `Text` given
 * less height than its line box needs CLIPS rather than overflowing: the first render came back
 * with the bottom third of the word "On screen" sliced off, in a tile with 20dp of unused room
 * below it. A fixed slot has to be at least as tall as the tallest thing that goes in it.
 */
private val HINT_HEIGHT = 14.dp

/**
 * The dark line between two panes of the plan.
 *
 * 2dp, because it has to survive being halved into each pane's inset and still read at the
 * smallest pane the depth cap allows.
 */
private val PANE_GUTTER = 2.dp

private const val NAME_SP = 11
private const val NAME_LINE_SP = 13

/** Two lines of [NAME_LINE_SP], reserved whether the name needs them or not. See [SpaceTile]. */
private val NAME_BLOCK_HEIGHT = 26.dp
private const val HINT_SP = 10
private const val HINT_LINE_SP = 12
private const val INITIALS_SP = 12
private val STATE_DOT = 8.dp

// How far a pane is tinted toward the accent, per state. Blends, not alphas: the tint has to be
// the thing that separates the three states, and a translucent pane over the frame's ground would
// come out at whatever the ground happens to be. The floors view states the same rule.
private const val ON_SCREEN_PANE_TINT = 0.70f
private const val RUNNING_PANE_TINT = 0.30f

/**
 * What a Space is doing, which is the one thing a tile has to say beyond its shape.
 *
 * An enum rather than the two booleans this was: "on screen" and "running behind" are steps of one
 * state and a pair of flags can express the fourth combination that does not exist.
 */
internal enum class SpaceState {
    /** The Space this window is showing. */
    ON_SCREEN,

    /** Running in this window, behind the one on screen: its tabs are live, it is just not shown. */
    RUNNING,

    /** Saved to disk and not running anywhere in this window. */
    SAVED,
}

/**
 * Which of the three states a Space is in.
 *
 * **On screen WINS over running, and that precedence is load-bearing.** The host's
 * `liveWorkspaceIds` includes the current workspace - it is running, it is simply also the one
 * being shown - so a rule that checked the running set first would mark every Space the same and
 * the grid would never say which one you are looking at.
 */
internal fun spaceStateFor(
    workspaceId: String,
    currentWorkspaceId: String?,
    runningWorkspaceIds: Set<String>,
): SpaceState =
    when {
        workspaceId == currentWorkspaceId -> SpaceState.ON_SCREEN
        workspaceId in runningWorkspaceIds -> SpaceState.RUNNING
        else -> SpaceState.SAVED
    }

/**
 * A Space's SAVED layout, turned into rectangles on a unit plate.
 *
 * **Why this is not [WorkspaceFloorPlan].** That one reads the live tree [TabTreeBuilder] built,
 * which is the right source for a workspace this window is running and the only source that stays
 * fresh. This dialog lists Spaces that are running and Spaces that are not, and a Space sitting on
 * disk has no live tree at all - the only shape it has is the `SplitConfig` in its saved file. One
 * source for the whole grid, so twenty tiles are drawn the same way rather than two ways.
 *
 * The cost is stated rather than hidden: this is the layout as SAVED. Split a running Space without
 * saving it and its tile still shows the split it was saved with, exactly as the tree used to
 * before it stopped reading `SplitConfig` (see the freshness note in AGENTS.md). The floors stack
 * directly above this dialog is the live picture; this is the picture of the file.
 *
 * **Every split is drawn as equal halves.** [SplitConfig] carries no ratio - `VerticalSplit` has a
 * left and a right and nothing about where the divider sits - so a split dragged to 20/80 comes
 * back as halves. That is the same caveat `SplitPositionGlyph` and [WorkspaceFloorPlan] carry, and
 * for the same reason: proportion is not in the data. No ratio is invented from tab counts or from
 * anything else, because a guess that looked like a measurement would be worse than a stated
 * schematic.
 */
internal object SpaceLayoutPlan {
    private val WHOLE_PLATE = Rect(0f, 0f, 1f, 1f)

    /**
     * How many levels of split are drawn before a subtree is collapsed into the pane holding it.
     *
     * The recursion has to stop somewhere, because `SplitConfig` nests without limit and the frame
     * it is being drawn into does not grow. Three levels is where the arithmetic runs out: with
     * equal halves at every level the narrowest pane at depth 3 is an eighth of
     * [THUMB_WIDTH] - 7dp, of which 5dp survives the [PANE_GUTTER] either side of it - and at
     * depth 4 it is 3.5dp, which the gutter eats most of and what is left reads as a stripe rather
     * than a pane. Three levels also covers every split a person actually works in: eight panes.
     *
     * Deeper than that, the pane CONTAINING the split is drawn whole. The tile then says "there is
     * a pane here" and stops claiming to enumerate what is inside it, which is honest; drawing
     * slivers would claim to have counted them.
     */
    internal const val MAX_DEPTH = 3

    fun panesOf(layout: SplitConfig): List<Rect> = panesIn(layout, WHOLE_PLATE, 0)

    private fun panesIn(
        layout: SplitConfig,
        area: Rect,
        depth: Int,
    ): List<Rect> =
        when {
            layout is SplitConfig.VerticalSplit && depth < MAX_DEPTH -> {
                val mid = (area.left + area.right) / 2f
                panesIn(layout.left, Rect(area.left, area.top, mid, area.bottom), depth + 1) +
                    panesIn(layout.right, Rect(mid, area.top, area.right, area.bottom), depth + 1)
            }

            layout is SplitConfig.HorizontalSplit && depth < MAX_DEPTH -> {
                val mid = (area.top + area.bottom) / 2f
                panesIn(layout.top, Rect(area.left, area.top, area.right, mid), depth + 1) +
                    panesIn(layout.bottom, Rect(area.left, mid, area.right, area.bottom), depth + 1)
            }

            // A single panel, and anything past the depth cap: one rectangle, the whole area.
            else -> listOf(area)
        }
}

/**
 * One or two letters standing in for a Space with no division worth drawing.
 *
 * A copy of the host's `initialsFor` (`components/home/HomeToolIcon.kt`), behaviour for behaviour:
 * the initial of each of the first two words, the first character of anything unsplittable, and
 * "?" for a blank name so this never returns an empty string for a tile to render as a hole. A
 * plugin cannot import a host internal and the api has no home for it, which is the same reason
 * [SpaceIcon] exists twice.
 *
 * **The fallback, not the primary.** It is reached when [SpaceLayoutPlan] answers with ONE
 * rectangle, which is most saved Spaces: a frame with no division in it says nothing that the
 * tile's own border has not already said, so the frame becomes a single pane with the Space's
 * initials written in it. A Space with a real split gets the plan, and the initials are not drawn
 * at all.
 */
internal fun initialsFor(displayName: String): String {
    val words =
        displayName
            .split(' ', '-', '_', '.')
            .filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/**
 * How many tile columns fit in [available], never fewer than one.
 *
 * `n` tiles need `n * TILE_MIN_WIDTH + (n - 1) * TILE_GAP`, solved for n. The floor is what makes a
 * tile at least [TILE_MIN_WIDTH] wide; the caller spends the remainder by widening every tile
 * equally, so the grid is flush with both edges of the dialog at any width.
 *
 * The clamp is not defensive dressing: the dialog is a fixed width today, but a width under one
 * tile would otherwise ask for zero columns and divide by it.
 */
internal fun tileColumnsFor(available: Dp): Int =
    floor((available + TILE_GAP) / (TILE_MIN_WIDTH + TILE_GAP))
        .toInt()
        .coerceAtLeast(1)

/**
 * Every saved Space as a tile, in a dialog with a search field.
 *
 * [BossDialog], never a plain Compose `Dialog` or `Popup`: under JxBrowser's hardware-accelerated
 * surface those render BEHIND the page, which is the whole reason the wrapper exists.
 *
 * Content is wrapped in [BossTheme] because the heavyweight path composes it in a window of its
 * own, where the panel's theme is not in scope.
 */
@Composable
internal fun WorkspacePickerDialog(
    workspaces: List<LayoutWorkspace>,
    currentWorkspaceId: String?,
    runningWorkspaceIds: Set<String>,
    onDismiss: () -> Unit,
    onPick: (LayoutWorkspace) -> Unit,
) {
    BossDialog(onDismissRequest = onDismiss) {
        BossTheme {
            SpacePickerContent(
                workspaces = workspaces,
                currentWorkspaceId = currentWorkspaceId,
                runningWorkspaceIds = runningWorkspaceIds,
                onDismiss = onDismiss,
                onPick = onPick,
            )
        }
    }
}

/**
 * The picker's card, separated from the dialog that raises it.
 *
 * `internal` rather than private so it can be composed into an `ImageComposeScene` and rendered to
 * a PNG off-screen. That is not a nicety here: this grid was designed by looking at renders of one
 * Space, three, twelve, a name too long for a tile and a four-pane layout beside a single-pane one,
 * and every one of those found something the arithmetic had hidden. The dialog itself cannot be
 * rendered that way - it is a window.
 */
@Composable
internal fun SpacePickerContent(
    workspaces: List<LayoutWorkspace>,
    currentWorkspaceId: String?,
    runningWorkspaceIds: Set<String>,
    onDismiss: () -> Unit,
    onPick: (LayoutWorkspace) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Filtering is derived, not stored: a stored copy is a second thing to keep in step with the
    // workspace list, which refreshes underneath this dialog while it is open.
    val matches =
        remember(workspaces, query) {
            if (query.isBlank()) {
                workspaces
            } else {
                workspaces.filter { it.name.contains(query.trim(), ignoreCase = true) }
            }
        }

    Surface(
        modifier =
            Modifier
                .requiredWidthIn(min = DIALOG_MIN_WIDTH, max = DIALOG_MAX_WIDTH)
                .border(1.dp, BossThemeColors.BorderColor, DIALOG_RADIUS),
        shape = DIALOG_RADIUS,
        color = BossColors.contextMenuBackground,
    ) {
        Column(modifier = Modifier.padding(DIALOG_INSET)) {
            Text(
                text = "Open Space",
                fontSize = DIALOG_TITLE_SP.sp,
                fontWeight = FontWeight.SemiBold,
                color = BossThemeColors.TextPrimary,
            )
            Spacer(modifier = Modifier.height(DIALOG_INSET))

            BossSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search spaces",
                modifier = Modifier.fillMaxWidth().height(SEARCH_HEIGHT),
            )
            Spacer(modifier = Modifier.height(TILE_GAP))

            if (matches.isEmpty()) {
                // Two empty states, and they read differently on purpose: no saved Spaces at all is
                // a fact about the app, nothing matching a query is a fact about the query.
                Text(
                    text =
                        if (workspaces.isEmpty()) {
                            "No saved spaces"
                        } else {
                            "Nothing matching \"$query\""
                        },
                    fontSize = NAME_SP.sp,
                    color = BossThemeColors.TextMuted,
                    modifier = Modifier.padding(vertical = TILE_GAP),
                )
            } else {
                SpaceGrid(
                    workspaces = matches,
                    currentWorkspaceId = currentWorkspaceId,
                    runningWorkspaceIds = runningWorkspaceIds,
                    onPick = onPick,
                )
            }

            Spacer(modifier = Modifier.height(DIALOG_INSET))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BossSecondaryButton(text = "Cancel", onClick = onDismiss)
            }
        }
    }
}

/**
 * The tiles, wrapped into as many columns as the dialog is wide.
 *
 * **A FlowRow, not a LazyVerticalGrid.** The whole grid is capped at [GRID_MAX_HEIGHT] and a user's
 * Spaces number in the tens, so there is nothing here for a lazy layout to save - and a lazy grid
 * inside a `Column` needs a height of its own, which is exactly what this does not have. It is also
 * not a `Row`: the footer's own KDoc has the measurement, that a Row answers a too-narrow measure
 * by giving its LAST child zero width rather than clipping it.
 *
 * [BoxWithConstraints] is what makes the tiles flush. The column count comes from
 * [tileColumnsFor] and the leftover width is handed back to the tiles equally, so the grid fills
 * the dialog rather than leaving a ragged strip down one side. `maxItemsInEachRow` states the wrap
 * the arithmetic already implies, so a rounding error of a fraction of a pixel cannot break a row
 * one tile early.
 */
@Composable
private fun SpaceGrid(
    workspaces: List<LayoutWorkspace>,
    currentWorkspaceId: String?,
    runningWorkspaceIds: Set<String>,
    onPick: (LayoutWorkspace) -> Unit,
) {
    val gridScroll = rememberScrollState()
    // ONE BoxWithConstraints, with the scroll on the Column inside it rather than on the box
    // itself. The column count comes from `maxWidth`, which only exists inside this scope, and
    // whether the grid scrolls is derived from that count - so the modifier that needs the answer
    // has to sit where the answer is available.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = tileColumnsFor(maxWidth)
        val tileWidth = (maxWidth - TILE_GAP * (columns - 1).toFloat()) / columns.toFloat()

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = GRID_MAX_HEIGHT)
                    // Pinned visible while there is anywhere to scroll, absent when there is not.
                    // The cap used to cut a row in half deliberately, so the half-row was the only
                    // hint of more; a bar says it outright, and the tools menu says it the same way.
                    //
                    // The gate is load-bearing, because `Modifier.scrollbar` has no
                    // fits-the-viewport guard - unlike `lazyListScrollbar`, which refuses to draw.
                    // With content that fits it computes a FULL-LENGTH thumb, so pinning alpha
                    // unconditionally paints a permanent bar beside three tiles.
                    //
                    // And the gate is ARITHMETIC, not a scroll-state read. Both obvious reads are
                    // wrong before the scrollable has measured: `ScrollState.maxValue` starts at
                    // Int.MAX_VALUE, and `canScrollForward` is `value < maxValue`, so that is true
                    // as well. Both were tried and both drew the bar under three tiles; a probe
                    // printed 2147483647. The tile count and the column count are known right
                    // here, so [gridScrolls] answers on the first frame and can be tested alone.
                    .scrollbar(
                        scrollState = gridScroll,
                        direction = Orientation.Vertical,
                        config =
                            getPanelScrollbarConfig().copy(
                                alpha =
                                    SCROLLBAR_ALPHA.takeIf {
                                        gridScrolls(workspaces.size, columns)
                                    },
                            ),
                    ).verticalScroll(gridScroll),
        ) {
                FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                verticalArrangement = Arrangement.spacedBy(TILE_GAP),
                maxItemsInEachRow = columns,
            ) {
                workspaces.forEach { workspace ->
                    SpaceTile(
                        workspace = workspace,
                        state =
                            spaceStateFor(
                                workspaceId = workspace.id,
                                currentWorkspaceId = currentWorkspaceId,
                                runningWorkspaceIds = runningWorkspaceIds,
                            ),
                        onClick = { onPick(workspace) },
                        modifier = Modifier.width(tileWidth),
                    )
                }
            }
        }
    }
}

/**
 * Whether [count] tiles in [columns] columns are taller than the grid's cap, so a scrollbar means
 * something.
 *
 * Arithmetic rather than a scroll-state read, for the reason written at the call site: every
 * scroll-state answer is wrong until the scrollable has measured, and this one is right on the
 * first frame. `ceil` by integer division, so 13 tiles in 4 columns is 4 rows, not 3.
 */
internal fun gridScrolls(
    count: Int,
    columns: Int,
): Boolean {
    if (count <= 0 || columns <= 0) return false
    val rows = (count + columns - 1) / columns
    return TILE_HEIGHT * rows.toFloat() + TILE_GAP * (rows - 1).toFloat() > GRID_MAX_HEIGHT
}

/**
 * One Space: a picture of how it is split, its name, and what it is doing.
 *
 * The home screen's tile in the plugin's own tokens - a `cardShape`-ish radius, a 1dp border, an
 * icon slot over a centred name - with the slot holding this Space's floor plan instead of a
 * glyph. That is the point of the grid rather than decoration on a list: a big pane beside two
 * stacked ones looks nothing like a 50/50 split, so a Space becomes something you recognise
 * instead of something you read.
 *
 * **The three states, kept distinct three ways over.** The border, the pane fill and the line
 * under the name all move together, so the grid is legible at a glance and each mark still says
 * what it said in the list this replaced: a filled dot for the Space on screen, an outline dot for
 * one merely running behind it, and nothing at all for one that only exists on disk. The dots and
 * their colours are the ones the row used, since a user moving between this dialog and the floors
 * stack should not have to learn a second vocabulary.
 *
 * Hover is a fill change and never a scale: growing a tile in a wrapping grid nudges it over its
 * neighbours, which is the host's own note on the same tile.
 */
@Composable
private fun SpaceTile(
    workspace: LayoutWorkspace,
    state: SpaceState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val onScreen = state == SpaceState.ON_SCREEN

    // The accent as a BORDER, never as the name's colour: `BossColors` exposes `signal` as a fill
    // token and it lands under 4.5:1 as text on the default theme, which is written up under
    // Colours in AGENTS.md and has caught this plugin before.
    val border = if (onScreen) BossThemeColors.AccentColor else BossThemeColors.BorderColor

    Column(
        modifier =
            modifier
                .height(TILE_HEIGHT)
                .clip(TILE_RADIUS)
                // The tools menu's tile, token for token: a RAISED surface rather than the dialog
                // showing through, and a hover that tints toward the accent and brings the border
                // with it. The two dialogs do the same job - pick one of a grid of things - so
                // they are meant to read as one pattern rather than two that happen to be grids.
                // An on-screen Space keeps its accent border underneath, which is the one thing
                // this tile says that a tool tile has no need to.
                .background(
                    when {
                        isHovered -> BossThemeColors.AccentColor.copy(alpha = TILE_HOVER_ALPHA)
                        else -> BossColors.darkSurface
                    },
                ).border(1.dp, if (isHovered) BossThemeColors.AccentColor else border, TILE_RADIUS)
                .hoverable(interactionSource)
                .clickable(onClick = onClick)
                .padding(TILE_INSET),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TILE_ITEM_GAP, Alignment.CenterVertically),
    ) {
        SpaceThumbnail(layout = workspace.layout, name = workspace.name, state = state)

        // The name gets ROOM FOR TWO LINES whether or not it needs them, which is what keeps a
        // row of tiles from looking shuffled: with the block free to be one line or two, a Space
        // called "ok" beside one called "BossConsole tab transfer worktree" centred its thumbnail
        // 7dp higher than its neighbour's, and a grid whose icons do not line up reads as a
        // mistake. Two lines because a Space name is a project name and those do not fit in 140dp.
        Box(
            modifier = Modifier.fillMaxWidth().height(NAME_BLOCK_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = workspace.name,
                fontSize = NAME_SP.sp,
                // Stated rather than inherited, so the two lines and the block that holds them
                // cannot drift apart.
                lineHeight = NAME_LINE_SP.sp,
                fontWeight = if (onScreen) FontWeight.SemiBold else FontWeight.Normal,
                color = if (onScreen) BossThemeColors.TextPrimary else BossThemeColors.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        SpaceStateLine(state = state)
    }
}

/**
 * The Space's panes, as flat rectangles in a bordered frame.
 *
 * The frame is the window and the rectangles are its panes, drawn from [SpaceLayoutPlan] - so
 * `VerticalSplit` comes out as two columns and `HorizontalSplit` as two rows, which is what those
 * words mean. Nothing here is projected or skewed; the fractions ARE the rectangle.
 *
 * **One pane means initials instead.** A frame with a single rectangle in it is the frame, so it
 * would make every unsplit Space's tile identical. The frame becomes that one pane, filled for its
 * state, with [initialsFor] the Space's name written across it - which is the same fallback the
 * host's tool tiles use for a plugin with no icon, and for the same reason.
 *
 * The initials are [BossThemeColors.TextPrimary] whatever the state, unlike the name below them:
 * they sit on a FILLED pane rather than on the tile's ground, so they need the stronger ink to
 * clear it in all three fills.
 */
@Composable
private fun SpaceThumbnail(
    layout: SplitConfig,
    name: String,
    state: SpaceState,
) {
    val panes = remember(layout) { SpaceLayoutPlan.panesOf(layout) }
    val accent = BossThemeColors.AccentColor

    // Opaque blends off one surface token, so the three states are three steps of the same colour
    // rather than three colours. A translucent pane would come out at whatever the frame's ground
    // happens to be, which is the trap the floors view writes up.
    val paneFill =
        when (state) {
            SpaceState.ON_SCREEN -> lerp(BossColors.darkSurface, accent, ON_SCREEN_PANE_TINT)
            SpaceState.RUNNING -> lerp(BossColors.darkSurface, accent, RUNNING_PANE_TINT)
            SpaceState.SAVED -> BossColors.darkSurface
        }
    val edge = if (state == SpaceState.ON_SCREEN) accent else BossThemeColors.BorderColor
    // The frame's own ground is the content-area token, because that is literally what the frame is
    // a picture of: the part of the window the panes are cut out of.
    val ground = BossThemeColors.BackgroundColor

    Box(
        modifier = Modifier.size(width = THUMB_WIDTH, height = THUMB_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 1.dp.toPx()
            val half = stroke / 2f
            // Inset half a stroke, so the frame's outline is drawn inside the slot rather than
            // clipped in half by its edge.
            val left = half
            val top = half
            val frameWidth = size.width - stroke
            val frameHeight = size.height - stroke

            drawRect(ground, topLeft = Offset(left, top), size = Size(frameWidth, frameHeight))

            // Each pane is inset by half a gutter, so what separates two of them is the frame's
            // own ground showing through - a dark line between panes, which is what the gap
            // between two panes of a real window looks like. Stroking each pane in the border
            // token instead was the first render, and two greys 8 points apart put a divider
            // there that had to be hunted for: a two-row Space read as one block.
            val inset = PANE_GUTTER.toPx() / 2f
            panes.forEach { pane ->
                val paneLeft = left + pane.left * frameWidth + inset
                val paneTop = top + pane.top * frameHeight + inset
                val paneRight = left + pane.right * frameWidth - inset
                val paneBottom = top + pane.bottom * frameHeight - inset
                drawRect(
                    paneFill,
                    topLeft = Offset(paneLeft, paneTop),
                    // Never negative: a pane an eighth of the frame wide is 7dp, well clear of the
                    // gutter, but the coerce is what keeps a deeper cap from inverting a rectangle.
                    size =
                        Size(
                            (paneRight - paneLeft).coerceAtLeast(0f),
                            (paneBottom - paneTop).coerceAtLeast(0f),
                        ),
                )
            }

            // The frame's own edge, last and once. It is the window this Space's panes sit in, and
            // the only stroke here now that the panes divide themselves with the ground.
            drawRect(
                edge,
                topLeft = Offset(left, top),
                size = Size(frameWidth, frameHeight),
                style = Stroke(width = stroke),
            )
        }

        if (panes.size < 2) {
            Text(
                text = initialsFor(name),
                fontSize = INITIALS_SP.sp,
                fontWeight = FontWeight.SemiBold,
                color = BossThemeColors.TextPrimary,
                maxLines = 1,
            )
        }
    }
}

/**
 * The line under the name: which of the three states this Space is in, or nothing.
 *
 * A fixed-height slot whether or not it draws anything, so a saved Space's tile lines up with a
 * running one's instead of centring its name 6dp lower.
 *
 * The marks are the ones the list this replaced used - `Icons.Filled.Circle` in
 * [BossThemeColors.SuccessColor] for the Space on screen, `Icons.Outlined.Circle` in
 * [BossThemeColors.TextSecondary] for one merely running - now with the word beside them, because
 * a tile has the room a 28dp row did not and "a filled dot means the one you are looking at" is
 * not something a dot can say by itself.
 */
@Composable
private fun SpaceStateLine(state: SpaceState) {
    Box(
        modifier = Modifier.fillMaxWidth().height(HINT_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        val onScreen = state == SpaceState.ON_SCREEN
        if (state != SpaceState.SAVED) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TILE_ITEM_GAP),
            ) {
                Icon(
                    imageVector = if (onScreen) Icons.Filled.Circle else Icons.Outlined.Circle,
                    contentDescription = if (onScreen) "Current space" else "Running",
                    modifier = Modifier.size(STATE_DOT),
                    tint = if (onScreen) BossThemeColors.SuccessColor else BossThemeColors.TextSecondary,
                )
                Text(
                    text = if (onScreen) "On screen" else "Running",
                    fontSize = HINT_SP.sp,
                    lineHeight = HINT_LINE_SP.sp,
                    color = if (onScreen) BossThemeColors.TextSecondary else BossThemeColors.TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}
