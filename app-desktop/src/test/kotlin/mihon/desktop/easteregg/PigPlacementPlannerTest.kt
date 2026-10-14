package mihon.desktop.easteregg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot
import kotlin.random.Random

class PigPlacementPlannerTest {

    @Test
    fun `every plan places seven pigs`() {
        repeat(64) { seed ->
            assertEquals(7, PigPlacementPlanner(Random(seed)).plan().size)
        }
    }

    @Test
    fun `placements stay inside the window and rotate within 45 degrees`() {
        repeat(64) { seed ->
            PigPlacementPlanner(Random(seed)).plan().forEach { placement ->
                assertTrue(placement.centerXFraction in 0f..1f, "x out of range: ${placement.centerXFraction}")
                assertTrue(placement.centerYFraction in 0f..1f, "y out of range: ${placement.centerYFraction}")
                assertTrue(placement.rotationDegrees in -45f..45f, "rotation out of range: ${placement.rotationDegrees}")
            }
        }
    }

    @Test
    fun `placements are spread instead of clustered`() {
        repeat(64) { seed ->
            val placements = PigPlacementPlanner(Random(seed)).plan()
            val closest = placements.indices.minOf { first ->
                placements.indices.filter { it != first }.minOf { second ->
                    val left = placements[first]
                    val right = placements[second]
                    hypot(left.centerXFraction - right.centerXFraction, left.centerYFraction - right.centerYFraction)
                }
            }
            assertTrue(closest > 0.25f, "seed $seed left two pigs only $closest apart")

            assertTrue(placements.minOf { it.centerXFraction } < 0.4f, "seed $seed never used the left side")
            assertTrue(placements.maxOf { it.centerXFraction } > 0.6f, "seed $seed never used the right side")
            assertTrue(placements.minOf { it.centerYFraction } < 0.4f, "seed $seed never used the top")
            assertTrue(placements.maxOf { it.centerYFraction } > 0.6f, "seed $seed never used the bottom")
        }
    }

    @Test
    fun `consecutive plans differ so a repeated trigger looks new`() {
        val planner = PigPlacementPlanner(Random(7))
        val first = planner.plan()
        val second = planner.plan()

        assertTrue(first != second, "a repeated trigger must not replay the previous layout")
    }

    @Test
    fun `rotation covers both directions across plans`() {
        val rotations = (0 until 32).flatMap { PigPlacementPlanner(Random(it)).plan() }.map { it.rotationDegrees }
        assertTrue(rotations.any { it < -5f }, "no counter-clockwise rotation was produced")
        assertTrue(rotations.any { it > 5f }, "no clockwise rotation was produced")
    }
}
