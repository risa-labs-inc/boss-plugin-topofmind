package ai.rever.boss.plugin.dynamic.topofmind

import androidx.compose.ui.Alignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which row draws the insertion line, and on which edge.
 *
 * The rule is that every slot is drawn by the row BENEATH it, with the final slot drawn on the
 * bottom edge of the last row. Get it wrong in either direction and the drag shows either two lines
 * at one boundary or none at the end of the list.
 */
class InsertionEdgeTest {
    @Test
    fun `a slot is drawn by the row beneath it, once`() {
        // Slot 2 in a three-row pane: row 2 draws it on top, row 1 draws nothing. Both rows touch
        // that boundary, and a rule that let each draw its own side would double the line.
        assertEquals(Alignment.TopCenter, insertionEdgeFor(2, indexInPane = 2, isLastInPane = true))
        assertNull(insertionEdgeFor(2, indexInPane = 1, isLastInPane = false))
        assertNull(insertionEdgeFor(2, indexInPane = 0, isLastInPane = false))
    }

    @Test
    fun `the slot after the last row is drawn on its bottom edge`() {
        // The one slot with no row beneath it. Without this it would be undrawable, and dropping
        // below the final tab would look like dropping nowhere.
        assertEquals(Alignment.BottomCenter, insertionEdgeFor(3, indexInPane = 2, isLastInPane = true))
    }

    @Test
    fun `a row that is not last never draws below itself`() {
        assertNull(insertionEdgeFor(3, indexInPane = 2, isLastInPane = false))
    }

    @Test
    fun `no slot or no position means no line`() {
        // A pane header carries no slot; the ghost and a collapsed pane's summary row carry no
        // position, because that row is not at the index it appears to be.
        assertNull(insertionEdgeFor(null, indexInPane = 1, isLastInPane = true))
        assertNull(insertionEdgeFor(1, indexInPane = null, isLastInPane = true))
    }

    @Test
    fun `every slot of a pane is drawn exactly once`() {
        val rows = 4
        (0..rows).forEach { slot ->
            val drawn =
                (0 until rows).count { row ->
                    insertionEdgeFor(slot, indexInPane = row, isLastInPane = row == rows - 1) != null
                }
            assertEquals(1, drawn, "slot $slot was drawn $drawn times")
        }
    }
}
