package ai.rever.boss.plugin.dynamic.topofmind

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The stack is a fixed height and the floors divide it.
 *
 * Every number in the floors view used to be a constant, so six workspaces drew a block six times
 * as tall as one and pushed the tree out of the panel a row at a time. [FLOORS_HEIGHT] is what is
 * fixed now; these pin that it holds, and that the two clamps which give it up - a huge bar for two
 * workspaces, a sliver for a dozen - give it up in the right direction.
 */
class FloorMetricsTest {
    /** What the stack measures: a floor each, with air between them. */
    private fun stackHeight(count: Int) =
        floorMetricsFor(count).let { it.height * count.toFloat() + it.gap * (count - 1).toFloat() }

    @Test
    fun `the stack is FLOORS_HEIGHT tall wherever the clamps do not bite`() {
        // 3 and 4: below that MAX_FLOOR bites and above it MIN_FLOOR does. Rounding on Dp division
        // is why this is a tolerance and not an equality.
        (3..4).forEach { count ->
            val height = stackHeight(count)
            assertTrue(
                abs((height - FLOORS_HEIGHT).value) < 1f,
                "$count floors measured $height, not $FLOORS_HEIGHT",
            )
        }
    }

    @Test
    fun `more floors means smaller ones, never a taller stack`() {
        val heights = (3..4).map { stackHeight(it).value }
        val floors = (3..8).map { floorMetricsFor(it).height.value }
        assertTrue(floors.zipWithNext().all { (a, b) -> b <= a }, "floors did not shrink: $floors")
        assertTrue(heights.all { abs(it - FLOORS_HEIGHT.value) < 1f }, "height moved: $heights")
    }

    @Test
    fun `past the exact range the stack only ever grows`() {
        // MIN_FLOOR bites from five workspaces up, and it has to give the height up UPWARDS - a
        // stack that shrank past its floor would be unreadable where a taller one merely scrolls.
        (5..10).forEach { count ->
            assertTrue(
                stackHeight(count) >= FLOORS_HEIGHT,
                "$count floors fitted in ${stackHeight(count)}, which means they shrank",
            )
        }
    }

    @Test
    fun `the air between floors is the same at every stack size`() {
        val gaps = (1..12).map { floorMetricsFor(it).gap.value }
        assertTrue(gaps.all { abs(it - gaps.first()) < 0.01f }, "the gap moved: $gaps")
    }

    @Test
    fun `a floor always has room for its name`() {
        (1..12).forEach { count ->
            val metrics = floorMetricsFor(count)
            assertTrue(metrics.height in MIN_FLOOR..MAX_FLOOR, "$count floors: ${metrics.height}")
            assertTrue(metrics.pitch > metrics.height, "$count floors lost the air between floors")
        }
    }

    @Test
    fun `two workspaces give height back rather than drawing two enormous bars`() {
        assertTrue(
            stackHeight(2) < FLOORS_HEIGHT,
            "two floors took ${stackHeight(2)}; MAX_FLOOR is meant to hand the difference to the tree",
        )
    }

    @Test
    fun `a dozen workspaces scroll rather than shrinking into slivers`() {
        assertTrue(floorMetricsFor(12).height == MIN_FLOOR, "the floor clamp did not hold")
        assertTrue(
            stackHeight(12) > FLOORS_HEIGHT,
            "twelve floors fitted the fixed height, which means they became slivers",
        )
    }
}
