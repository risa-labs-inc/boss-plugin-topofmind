package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the Space picker's tiles can be checked without a screen.
 *
 * Three pure rules feed a tile: the saved layout turned into rectangles, the initials that stand in
 * when there is no division to draw, and how many tiles fit the dialog. The fourth is which of the
 * three states a Space is in, where the PRECEDENCE is the whole rule.
 */
class SpacePickerTest {
    /**
     * What the grid is actually handed at the dialog's widest: 480dp of card less its 12dp inset
     * either side. Stated here rather than exported from the picker, so a change to either number
     * has to be noticed in both places.
     */
    private val DIALOG_CONTENT_WIDTH = 456.dp

    private var nextPanel = 0

    private fun panel(): SplitConfig.SinglePanel =
        SplitConfig.SinglePanel(PanelConfig(id = "panel-${nextPanel++}", tabs = emptyList()))

    private fun rect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = Rect(left, top, right, bottom)

    // --- the layout, as rectangles ------------------------------------------------------------

    @Test
    fun `a single panel is the whole plate`() {
        // And it is ONE rectangle, which is what makes the tile fall back to the Space's initials:
        // a frame with a single rectangle in it is the frame.
        assertEquals(listOf(rect(0f, 0f, 1f, 1f)), SpaceLayoutPlan.panesOf(panel()))
    }

    @Test
    fun `a vertical split is two equal columns, left first`() {
        // Equal halves, because SplitConfig carries no ratio - a VerticalSplit has a left and a
        // right and nothing about where the divider sits. A split dragged to 20/80 draws as halves,
        // and no proportion is invented from anywhere else.
        assertEquals(
            listOf(rect(0f, 0f, 0.5f, 1f), rect(0.5f, 0f, 1f, 1f)),
            SpaceLayoutPlan.panesOf(SplitConfig.VerticalSplit(panel(), panel())),
        )
    }

    @Test
    fun `a horizontal split is two equal rows, top first`() {
        assertEquals(
            listOf(rect(0f, 0f, 1f, 0.5f), rect(0f, 0.5f, 1f, 1f)),
            SpaceLayoutPlan.panesOf(SplitConfig.HorizontalSplit(panel(), panel())),
        )
    }

    @Test
    fun `a four-pane Space is the four quadrants PanePlacement names`() {
        // The cross-check that matters: this dialog draws a saved layout and the floors stack draws
        // a pane's NAME, so if the two disagreed about which corner "Top right" is, a Space would
        // change shape between the picker and the building above it.
        val quadrants =
            SpaceLayoutPlan.panesOf(
                SplitConfig.VerticalSplit(
                    left = SplitConfig.HorizontalSplit(panel(), panel()),
                    right = SplitConfig.HorizontalSplit(panel(), panel()),
                ),
            )
        assertEquals(
            listOf("top left", "bottom left", "top right", "bottom right").map { paneAreaFor(it) },
            quadrants,
        )
    }

    @Test
    fun `the rectangles always tile the plate exactly`() {
        // The invariant behind every one of these: no gap and no overlap, whatever the nesting. It
        // is what catches a midpoint that is not the midpoint, and a depth cap that drops the pane
        // it was supposed to draw whole.
        listOf(
            panel(),
            SplitConfig.VerticalSplit(panel(), panel()),
            SplitConfig.HorizontalSplit(SplitConfig.VerticalSplit(panel(), panel()), panel()),
            deeplyNested(levels = 6),
            SplitConfig.VerticalSplit(
                left = SplitConfig.HorizontalSplit(panel(), SplitConfig.VerticalSplit(panel(), panel())),
                right = SplitConfig.HorizontalSplit(panel(), panel()),
            ),
        ).forEach { layout ->
            val panes = SpaceLayoutPlan.panesOf(layout)
            val area = panes.sumOf { (it.width * it.height).toDouble() }
            assertEquals(1.0, area, 1e-6, "panes of $layout cover $area of the plate")
            val overlapping =
                panes.indices.any { i ->
                    (i + 1..panes.lastIndex).any { j -> panes[i].overlaps(panes[j]) }
                }
            assertTrue(!overlapping, "panes of $layout overlap each other")
        }
    }

    @Test
    fun `nesting past the cap draws the pane holding the split, not slivers`() {
        // Six levels of splitting would be 64 rectangles in a 56dp frame. The recursion stops at
        // MAX_DEPTH and draws the containing pane whole, so the tile says "there is a pane here"
        // rather than claiming to have counted what is inside it.
        val panes = SpaceLayoutPlan.panesOf(deeplyNested(levels = 6))
        val cap = 1 shl SpaceLayoutPlan.MAX_DEPTH
        assertTrue(panes.size <= cap, "drew ${panes.size} panes, cap is $cap")

        // And the cap is reached rather than merely respected: a perfectly balanced tree that deep
        // fills every slot the cap allows.
        assertEquals(cap, panes.size)
    }

    /** A balanced tree [levels] deep, alternating axis, so every leaf sits at the same depth. */
    private fun deeplyNested(levels: Int): SplitConfig =
        if (levels == 0) {
            panel()
        } else if (levels % 2 == 0) {
            SplitConfig.VerticalSplit(deeplyNested(levels - 1), deeplyNested(levels - 1))
        } else {
            SplitConfig.HorizontalSplit(deeplyNested(levels - 1), deeplyNested(levels - 1))
        }

    // --- the initials fallback ----------------------------------------------------------------

    @Test
    fun `initials are the first letter of the first two words`() {
        assertEquals("TO", initialsFor("Top of Mind"))
        assertEquals("CC", initialsFor("claude-code"))
        assertEquals("WT", initialsFor("work_tree.two"))
    }

    @Test
    fun `one word gives one letter`() {
        assertEquals("A", initialsFor("Arcade"))
        assertEquals("D", initialsFor("dev"))
    }

    @Test
    fun `a name with nothing in it still gives something to draw`() {
        // Never an empty string: a tile would render it as a hole. Separators alone count as blank,
        // which is what the filter is for.
        assertEquals("?", initialsFor(""))
        assertEquals("?", initialsFor("   "))
        assertEquals("?", initialsFor("--_."))
    }

    @Test
    fun `an unsplittable name gives its first character`() {
        assertEquals("Z", initialsFor("zzzzzzzzzzzzzzzzzzzz"))
        assertEquals("工", initialsFor("工作区"))
    }

    // --- how many tiles fit -------------------------------------------------------------------

    @Test
    fun `a column count is what fits at the tile minimum`() {
        val two = TILE_MIN_WIDTH * 2f + TILE_GAP
        val three = TILE_MIN_WIDTH * 3f + TILE_GAP * 2f
        // Exactly two tiles' worth of room is two columns, not one. The gap belongs BETWEEN tiles,
        // so a width that fits n tiles and n-1 gaps has to answer n - the arithmetic that forgets
        // the trailing gap it does not need wraps a row a tile early at exactly the fitting width.
        assertEquals(2, tileColumnsFor(two))
        assertEquals(3, tileColumnsFor(three))
        // A hair under is one fewer.
        assertEquals(1, tileColumnsFor(two - 1.dp))
        assertEquals(2, tileColumnsFor(three - 1.dp))
    }

    @Test
    fun `the picker's own two widths are two columns and three`() {
        // The dialog is 320dp at its narrowest and 480dp at its widest, less 16dp of inset a side.
        assertEquals(2, tileColumnsFor(288.dp))
        assertEquals(3, tileColumnsFor(448.dp))
    }

    @Test
    fun `there is never a zero-column grid`() {
        // The caller divides the available width by this, so zero is not an answer it can take.
        assertEquals(1, tileColumnsFor(TILE_MIN_WIDTH))
        assertEquals(1, tileColumnsFor(40.dp))
        assertEquals(1, tileColumnsFor(0.dp))
    }

    // --- which of the three states ------------------------------------------------------------

    @Test
    fun `on screen beats running`() {
        // The host's liveWorkspaceIds INCLUDES the workspace on screen - it is running, it is just
        // also the one being shown - so a rule that asked the running set first would mark every
        // Space the same and the grid would never say which one you are looking at.
        assertEquals(
            SpaceState.ON_SCREEN,
            spaceStateFor("a", currentWorkspaceId = "a", runningWorkspaceIds = setOf("a", "b")),
        )
    }

    @Test
    fun `running behind, and only saved, are different states`() {
        assertEquals(
            SpaceState.RUNNING,
            spaceStateFor("b", currentWorkspaceId = "a", runningWorkspaceIds = setOf("a", "b")),
        )
        assertEquals(
            SpaceState.SAVED,
            spaceStateFor("c", currentWorkspaceId = "a", runningWorkspaceIds = setOf("a", "b")),
        )
    }

    @Test
    fun `no current workspace does not make everything current`() {
        assertEquals(SpaceState.SAVED, spaceStateFor("a", currentWorkspaceId = null, runningWorkspaceIds = emptySet()))
        assertEquals(
            SpaceState.RUNNING,
            spaceStateFor("a", currentWorkspaceId = null, runningWorkspaceIds = setOf("a")),
        )
    }

    // ---- the tiles fit the row they were counted for --------------------------------------------

    /**
     * The bug this pins: the dialog computed THREE columns at its widest and laid out TWO, leaving
     * 150dp of dead air down the right and making AGENTS.md's "three at its widest" untrue of the
     * shipped build.
     *
     * **The mechanism is `roundToPx`, not float error, and getting that wrong cost two attempts.**
     * In dp the sum came out exactly right: `(456 - 16) / 3` is `146.6666717529297`, and three of
     * those plus the two gaps is `456.0` on the nose. But `Modifier.width(dp)` resolves through
     * `Density.roundToPx`, which ROUNDS - so at density 1 that tile is measured as **147px**, three
     * need 441px, and 440 are available. `FlowRow` wraps one. A dp-only assertion passes against
     * that, which is why everything here measures in PIXELS at a density.
     *
     * It is also why the bug is display-dependent, and worth knowing before hunting it on a mac: at
     * density 2 the same tile is 293px and three fit 912px with one to spare, so a 2x screen never
     * showed it.
     */
    @Test
    fun `three tiles fit the dialog at its widest`() {
        val available = DIALOG_CONTENT_WIDTH

        assertEquals(3, tileColumnsFor(available), "480dp of dialog less its 12dp insets holds three")
        assertTrue(
            rowWidthPx(available, columns = 3, density = 1f) <= availablePx(available, 1f),
            "three tiles plus two gaps must FIT in whole pixels, or FlowRow wraps one: " +
                "${rowWidthPx(available, 3, 1f)}px in ${availablePx(available, 1f)}px",
        )
    }

    /**
     * The invariant, over every width the grid can be handed and every density it can be drawn at.
     * One width at one density is how this survived a test suite once already - and a fractional
     * density is how the SECOND attempt at the fix (floor the dp) still overran a row by a pixel.
     */
    @Test
    fun `the columns counted always fit the width they were counted for`() {
        forEachWidthAndDensity { available, density ->
            val columns = tileColumnsFor(available)
            assertTrue(
                rowWidthPx(available, columns, density) <= availablePx(available, density),
                "$columns columns do not fit $available at ${density}x: " +
                    "${rowWidthPx(available, columns, density)}px in ${availablePx(available, density)}px",
            )
        }
    }

    @Test
    fun `a tile is never meaningfully narrower than the minimum that decided the column count`() {
        // A fix that fit by making the tiles small would pass the test above and be a different
        // bug: the column count would be a lie. One pixel of slack is the rounding, not a lie.
        forEachWidthAndDensity { available, density ->
            val columns = tileColumnsFor(available)
            // One column at a width under one tile is the clamp doing its job, not a violation.
            if (columns > 1) {
                val tilePx = (tileWidthFor(available, columns, density).value * density).roundToInt()
                val minPx = (TILE_MIN_WIDTH.value * density).roundToInt()
                assertTrue(
                    tilePx >= minPx - 1,
                    "a tile at $available (${density}x) came out ${tilePx}px, under the ${minPx}px minimum",
                )
            }
        }
    }

    @Test
    fun `the pixel floor spends almost all of the width`() {
        // The other direction, and the reason the fix rounds rather than shaving a margin off: at
        // most one PIXEL per column goes unspent, so the tiles stay flush with both edges.
        forEachWidthAndDensity { available, density ->
            val columns = tileColumnsFor(available)
            val unspent = availablePx(available, density) - rowWidthPx(available, columns, density)
            assertTrue(
                unspent in 0..columns,
                "$available at ${density}x left ${unspent}px unspent over $columns columns",
            )
        }
    }

    @Test
    fun `no columns and no density are answered without dividing by them`() {
        // `columns` comes from a measured width, which is zero before the dialog has one.
        assertEquals(0.dp, tileWidthFor(0.dp, columns = 0, density = 1f))
        assertEquals(100.dp, tileWidthFor(100.dp, columns = 2, density = 0f))
    }

    /**
     * Every width the grid is plausibly handed, at every density BOSS is drawn at.
     *
     * **The sweep is over PIXELS, and the dp is derived from them.** A measure constraint carries
     * whole pixels and `BoxWithConstraints.maxWidth` is `constraints.maxWidth.toDp()`, so "102dp at
     * 1.25x" is not a state the layout can be in - and inventing one made an earlier version of
     * this sweep fail on an arrangement that cannot happen. The densities are the ones a desktop
     * reports: 1x, the Windows scaling steps, and 2x.
     */
    private fun forEachWidthAndDensity(check: (available: Dp, density: Float) -> Unit) {
        listOf(1f, 1.25f, 1.5f, 1.75f, 2f).forEach { density ->
            // 100..600 dp of grid, swept in whole pixels at this density.
            ((100 * density).toInt()..(600 * density).toInt()).forEach { px ->
                check((px / density).dp, density)
            }
        }
    }

    /** The width in whole pixels: exact, because [forEachWidthAndDensity] derived the dp from it. */
    private fun availablePx(
        available: Dp,
        density: Float,
    ): Int = (available.value * density).roundToInt()

    /**
     * What one row of [columns] tiles measures in pixels: `Modifier.width` and
     * `Arrangement.spacedBy` both resolve through `Density.roundToPx`, so each is ROUNDED before
     * anything is added up. Modelling that is the whole point - adding dp and converting once
     * hides the rounding that wrapped the row.
     */
    private fun rowWidthPx(
        available: Dp,
        columns: Int,
        density: Float,
    ): Int {
        val tilePx = (tileWidthFor(available, columns, density).value * density).roundToInt()
        val gapPx = (TILE_GAP.value * density).roundToInt()
        return tilePx * columns + gapPx * (columns - 1)
    }

    // ---- gridScrolls ---------------------------------------------------------------------------

    private fun spacesOnly(count: Int) = SpaceSections(spaces = List(count) { space("s$it") }, templates = emptyList())

    @Test
    fun `a grid that fits its cap does not scroll`() {
        // The scrollbar has to be ABSENT here. `Modifier.scrollbar` draws a full-length thumb for
        // content that fits rather than refusing to draw, so a wrong answer is a permanent bar
        // beside three tiles - which is exactly what the first two attempts at this gate did.
        assertFalse(gridScrolls(spacesOnly(3), columns = 3))
        assertFalse(gridScrolls(spacesOnly(1), columns = 3))
    }

    @Test
    fun `a grid taller than its cap scrolls`() {
        assertTrue(gridScrolls(spacesOnly(20), columns = 4))
    }

    @Test
    fun `the row count rounds UP`() {
        // 13 tiles in 4 columns is 4 rows, not 3. Plain integer division answers 3 and hides the
        // last row behind a grid that looks complete.
        assertEquals(gridScrolls(spacesOnly(16), columns = 4), gridScrolls(spacesOnly(13), columns = 4))
    }

    @Test
    fun `nothing to lay out never scrolls`() {
        // Guards the arithmetic, not the UI: `columns` comes from a measured width, which is zero
        // for the frame before the dialog has one, and the row count would divide by it.
        assertFalse(gridScrolls(spacesOnly(0), columns = 4))
        assertFalse(gridScrolls(spacesOnly(5), columns = 0))
    }

    @Test
    fun `the headings and the section gap count toward the cap`() {
        // Nine tiles in three columns is three rows, which fits the cap on their own. Split into
        // two labelled sections they do not: two headings, the air under each, and the gap between
        // the sections add 68dp, and one of those rows now has a tile hidden under the fold. The
        // old count-the-tiles gate answered "fits" here and left no scrollbar.
        assertFalse(gridScrolls(spacesOnly(9), columns = 3))
        assertTrue(
            gridScrolls(
                SpaceSections(
                    spaces = List(6) { space("s$it") },
                    templates = List(3) { builtIn("built-in-$it") },
                ),
                columns = 3,
            ),
        )
    }

    // ---- the two sections ----------------------------------------------------------------------

    @Test
    fun `every layout BOSS ships is a template, Browser Only included`() {
        // The whole set, by id, because the rule is IDENTITY - "one of the eight we ship" - and
        // listing them here is what would catch the copy of the host's set going stale.
        val builtIns =
            listOf(
                "workspace-claude-code",
                "workspace-code-review",
                "workspace-gemini",
                "workspace-codex",
                "workspace-opencode",
                "workspace-terminal-browser",
                "workspace-dual-terminal",
                "workspace-browser",
            )

        builtIns.forEach { id ->
            assertTrue(builtIn(id).isTemplate(), "$id is a layout BOSS ships, so it is a template")
        }
    }

    @Test
    fun `Browser Only is a template even though it has nothing to substitute`() {
        // The case the placeholder scan got wrong, called out on its own: a single browser panel on
        // a fixed URL carries no `{projectPath}`, so the shape question answers "not a template"
        // and files one of the shipped layouts in with the user's own Spaces.
        val browserOnly = builtIn("workspace-browser", tab = TabConfig(type = "browser", title = "RISA Labs", url = "https://www.risalabs.ai"))

        assertTrue(browserOnly.isTemplate(), "it is one of the eight, whatever its layout says")
        assertEquals(listOf("workspace-browser"), spaceSectionsOf(listOf(browserOnly)).templates.map { it.id })
    }

    @Test
    fun `a saved Space is never a template, whatever it is called`() {
        // `generateId()` mints `workspace-<epoch millis>`, so a Space carries the same `workspace-`
        // prefix as a built-in and a prefix test would call every Space a template. And the NAME is
        // not the key either: a user may save a Space called "Claude Code".
        assertFalse(space("workspace-1788000000000").isTemplate())
        assertFalse(
            space("workspace-1788000000001", name = "Claude Code").isTemplate(),
            "sharing a built-in's NAME does not make a saved Space one of ours",
        )
        assertFalse(space("my-own-space").isTemplate())
    }

    @Test
    fun `the split keeps the host's order inside each section`() {
        // The workspace list arrives in one order the whole app agrees on, so partitioning must
        // not reshuffle either half - the Space button's menu lists the same rows.
        val sections =
            spaceSectionsOf(
                listOf(
                    builtIn("workspace-gemini"),
                    space("workspace-1788000000000"),
                    builtIn("workspace-codex"),
                    space("workspace-1788000000001"),
                ),
            )
        assertEquals(listOf("workspace-1788000000000", "workspace-1788000000001"), sections.spaces.map { it.id })
        assertEquals(listOf("workspace-gemini", "workspace-codex"), sections.templates.map { it.id })
    }

    @Test
    fun `headings appear only when both sections have something in them`() {
        // A lone heading over the only group there is says nothing the dialog title has not, and
        // costs a row of a capped grid to say it. A fresh install is exactly that case: every
        // layout in the list is one of ours until the user saves something.
        assertFalse(sectionsAreLabelled(spaceSectionsOf(listOf(space("workspace-1788000000000")))))
        assertFalse(
            sectionsAreLabelled(spaceSectionsOf(listOf(builtIn("workspace-gemini"), builtIn("workspace-browser")))),
            "a fresh install is all templates and no Spaces, so it gets no headings",
        )
        assertTrue(
            sectionsAreLabelled(spaceSectionsOf(listOf(builtIn("workspace-gemini"), space("workspace-1788000000000")))),
        )
    }

    // ---- fixtures ------------------------------------------------------------------------------

    /**
     * One of the layouts BOSS ships, identified by its [id].
     *
     * The layout is deliberately irrelevant to [isTemplate] now, which is the point of the rule: a
     * built-in is a built-in whether or not it has anything to substitute.
     */
    private fun builtIn(
        id: String,
        tab: TabConfig = TabConfig(type = "terminal", title = "T", workingDirectory = "{projectPath}"),
    ) = LayoutWorkspace(
        id = id,
        name = id,
        description = "",
        layout = SplitConfig.SinglePanel(PanelConfig(id = "main", tabs = listOf(tab))),
    )

    /** A Space the user saved: a `generateId()`-shaped id, real paths in it. */
    private fun space(
        id: String,
        name: String = id,
    ) = LayoutWorkspace(
        id = id,
        name = name,
        description = "",
        layout =
            SplitConfig.SinglePanel(
                PanelConfig(
                    id = "main",
                    tabs = listOf(TabConfig(type = "terminal", title = "T", workingDirectory = "/Users/me/Boss")),
                ),
            ),
    )
}
