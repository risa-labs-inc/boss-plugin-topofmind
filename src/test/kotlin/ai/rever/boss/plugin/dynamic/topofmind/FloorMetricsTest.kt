package ai.rever.boss.plugin.dynamic.topofmind

import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The stack is a fixed height and the storeys divide it.
 *
 * Every number in the floors view used to be a constant, so six workspaces drew a building six
 * times as tall as one and pushed the tree out of the panel a row at a time. [FLOORS_HEIGHT] is
 * what is fixed now; these pin that it actually holds, and that the two clamps which give it up -
 * a huge slab for two workspaces, a sliver for a dozen - give it up in the right direction.
 */
class FloorMetricsTest {
    /** What the stack measures: the top floor shows a whole slab, every other one shows a pitch. */
    private fun stackHeight(count: Int) =
        floorMetricsFor(count).let { it.slab + it.pitch * (count - 1).toFloat() }

    @Test
    fun `the stack is FLOORS_HEIGHT tall wherever the clamps do not bite`() {
        // 3 through 7: the range a window is actually in. Rounding on Dp division is why this is a
        // tolerance and not an equality.
        (3..7).forEach { count ->
            val height = stackHeight(count)
            assertTrue(
                abs((height - FLOORS_HEIGHT).value) < 1f,
                "$count floors measured $height, not $FLOORS_HEIGHT",
            )
        }
    }

    @Test
    fun `more storeys means smaller ones, never taller`() {
        val heights = (3..7).map { stackHeight(it).value }
        val slabs = (3..7).map { floorMetricsFor(it).slab.value }
        assertTrue(slabs.zipWithNext().all { (a, b) -> b < a }, "slabs did not shrink: $slabs")
        assertTrue(heights.all { abs(it - FLOORS_HEIGHT.value) < 1f }, "height moved: $heights")
    }

    @Test
    fun `a face always has room for the name, and the plate gives way first`() {
        (1..12).forEach { count ->
            val metrics = floorMetricsFor(count)
            assertTrue(metrics.riser >= MIN_RISER, "$count floors left a ${metrics.riser} face")
            assertTrue(metrics.plate > 0.dp, "$count floors left no plate")
            assertTrue(metrics.slab in MIN_SLAB..MAX_SLAB, "$count floors: slab ${metrics.slab}")
        }
    }

    @Test
    fun `a storey never buries the one below it`() {
        // The overlap is what the storey above takes back; a pitch larger than a slab is a GAP, and
        // both are fine. A pitch of zero or less would stack every floor on one spot.
        (1..12).forEach { count ->
            val metrics = floorMetricsFor(count)
            assertTrue(metrics.pitch > 0.dp, "$count floors: pitch ${metrics.pitch}")
            assertTrue(metrics.overlap < metrics.plate, "$count floors hid a whole plate")
        }
    }

    @Test
    fun `two workspaces give height back rather than drawing two enormous slabs`() {
        assertTrue(
            stackHeight(2) < FLOORS_HEIGHT,
            "two floors took ${stackHeight(2)}; MAX_SLAB is meant to hand the difference to the tree",
        )
    }

    @Test
    fun `a dozen workspaces scroll rather than shrinking into slivers`() {
        assertTrue(floorMetricsFor(12).slab == MIN_SLAB, "the slab clamp did not hold")
        assertTrue(
            stackHeight(12) > FLOORS_HEIGHT,
            "twelve floors fitted the fixed height, which means they became slivers",
        )
    }
}
