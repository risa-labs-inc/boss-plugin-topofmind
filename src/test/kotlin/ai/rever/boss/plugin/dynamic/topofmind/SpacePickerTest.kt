package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
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

    // ---- gridScrolls ---------------------------------------------------------------------------

    @Test
    fun `a grid that fits its cap does not scroll`() {
        // The scrollbar has to be ABSENT here. `Modifier.scrollbar` draws a full-length thumb for
        // content that fits rather than refusing to draw, so a wrong answer is a permanent bar
        // beside three tiles - which is exactly what the first two attempts at this gate did.
        assertFalse(gridScrolls(count = 3, columns = 3))
        assertFalse(gridScrolls(count = 1, columns = 3))
    }

    @Test
    fun `a grid taller than its cap scrolls`() {
        assertTrue(gridScrolls(count = 20, columns = 4))
    }

    @Test
    fun `the row count rounds UP`() {
        // 13 tiles in 4 columns is 4 rows, not 3. Plain integer division answers 3 and hides the
        // last row behind a grid that looks complete.
        assertEquals(gridScrolls(count = 16, columns = 4), gridScrolls(count = 13, columns = 4))
    }

    @Test
    fun `nothing to lay out never scrolls`() {
        // Guards the arithmetic, not the UI: `columns` comes from a measured width, which is zero
        // for the frame before the dialog has one, and the row count would divide by it.
        assertFalse(gridScrolls(count = 0, columns = 4))
        assertFalse(gridScrolls(count = 5, columns = 0))
    }
}
