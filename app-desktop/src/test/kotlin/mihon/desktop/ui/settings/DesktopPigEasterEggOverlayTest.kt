package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.center
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.easteregg.PIG2_EASTER_EGG_RESOURCE
import mihon.desktop.easteregg.PIG_EASTER_EGG_RESOURCE
import mihon.desktop.easteregg.PIG_EASTER_EGG_SVG_RESOURCE
import mihon.desktop.easteregg.loadPig2EasterEggImage
import mihon.desktop.easteregg.loadPigEasterEggImage
import mihon.desktop.easteregg.renderSvg
import mihon.domain.easteregg.PigEasterEggController
import mihon.domain.easteregg.PigEasterEggDefaults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import kotlin.coroutines.CoroutineContext
import kotlin.math.hypot
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalComposeUiApi::class)
@Isolated
class DesktopPigEasterEggOverlayTest {

    @Test
    fun `triggering the controller shows seven spread pigs that disappear when time expires`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            settle(fixture.scene)
            assertTrue(pigs(fixture.scene).isEmpty(), "nothing may show before the controller is triggered")

            fixture.easterEgg.fire()
            settle(fixture.scene) { pigs(fixture.scene).size == 7 }

            val shown = pigs(fixture.scene)
            assertEquals(7, shown.size)

            val width = SCENE_WIDTH
            val height = SCENE_HEIGHT
            val rotationTolerance = height * PigEasterEggDefaults.WINDOW_FRACTION
            shown.forEach { pig ->
                val bounds = pig.boundsInRoot
                assertTrue(
                    bounds.left >= -rotationTolerance && bounds.right <= width + rotationTolerance,
                    "pig escaped the window: $bounds",
                )
                assertTrue(
                    bounds.top >= -rotationTolerance && bounds.bottom <= height + rotationTolerance,
                    "pig escaped the window: $bounds",
                )
            }
            val centers = shown.map { it.boundsInRoot.center }
            centers.forEach { center ->
                assertTrue(center.x in 0f..width && center.y in 0f..height, "pig landed outside the window: $center")
            }
            val closest = centers.indices.minOf { first ->
                centers.indices.filter { it != first }.minOf { second ->
                    hypot(centers[first].x - centers[second].x, centers[first].y - centers[second].y)
                }
            }
            assertTrue(closest > 60f, "pigs were only $closest pixels apart")

            settle(fixture.scene, timeout = 20.seconds) { pigs(fixture.scene).isEmpty() }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `clicking a pig makes it disappear`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            settle(fixture.scene)
            fixture.easterEgg.fire()
            settle(fixture.scene) { pigs(fixture.scene).size == 7 }

            val firstPig = pigs(fixture.scene).first()
            fixture.click(firstPig)
            settle(fixture.scene) { pigs(fixture.scene).size == 6 }
            assertEquals(6, pigs(fixture.scene).size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `clicking all seven pigs shows the giant pig occupying 3-4 of screen min edge`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            settle(fixture.scene)
            fixture.easterEgg.fire()
            settle(fixture.scene) { pigs(fixture.scene).size == 7 }

            repeat(7) {
                val currentPigs = pigs(fixture.scene).filter { tag(it) != DESKTOP_PIG_EASTER_EGG_GIANT_TAG }
                if (currentPigs.isNotEmpty()) {
                    val countBefore = currentPigs.size
                    fixture.click(currentPigs.first())
                    settle(fixture.scene) {
                        pigs(fixture.scene).filter { tag(it) != DESKTOP_PIG_EASTER_EGG_GIANT_TAG }.size < countBefore ||
                            nodes(fixture.scene).any { tag(it) == DESKTOP_PIG_EASTER_EGG_GIANT_TAG }
                    }
                }
            }

            val minEdge = minOf(SCENE_WIDTH, SCENE_HEIGHT)
            val expectedSize = minEdge * PigEasterEggDefaults.GIANT_PIG_SCREEN_FRACTION

            settle(fixture.scene, timeout = 10.seconds) {
                val giant = nodes(fixture.scene).firstOrNull { tag(it) == DESKTOP_PIG_EASTER_EGG_GIANT_TAG }
                giant != null && giant.boundsInRoot.width >= expectedSize * 0.8f
            }

            val giantPig = nodes(fixture.scene).first { tag(it) == DESKTOP_PIG_EASTER_EGG_GIANT_TAG }
            val giantWidth = giantPig.boundsInRoot.width
            val giantHeight = giantPig.boundsInRoot.height
            // Check size is approximately 3/4 of the shorter edge (tolerance for bounding box / layout)
            assertTrue(
                giantWidth in (expectedSize * 0.8f)..(expectedSize * 1.2f),
                "giant pig width $giantWidth did not match 3/4 edge $expectedSize",
            )
            assertTrue(
                giantHeight in (expectedSize * 0.8f)..(expectedSize * 1.2f),
                "giant pig height $giantHeight did not match 3/4 edge $expectedSize",
            )

            // Giant pig stays around 1s and then disappears
            settle(fixture.scene, timeout = 20.seconds) {
                nodes(fixture.scene).none { tag(it) == DESKTOP_PIG_EASTER_EGG_GIANT_TAG }
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `the pigs zoom in with an accelerating pop that finishes in about a tenth of a second`() {
        val entrance = PigAppearanceSpec.entrance

        assertEquals(100, entrance.durationMillis, "the pop must finish in about 0.1s")
        val easing = entrance.easing
        assertTrue(easing.transform(0.5f) < 0.5f, "an accelerating zoom stays behind the clock at halfway")
        assertTrue(easing.transform(0.75f) < 0.75f, "the zoom is still catching up late in the pop")
        assertEquals(1f, easing.transform(1f), "the pop must land exactly on its target")

        val startScale = PigAppearanceSpec.scaleFor(0f)
        val midScale = PigAppearanceSpec.scaleFor(0.5f)
        assertTrue(midScale > startScale && midScale < 1f, "half way through the pop the pigs are still growing")
        assertEquals(1f, PigAppearanceSpec.scaleFor(1f), "the pop must end at full size")
    }

    @Test
    fun `the shipped pig images decode`() {
        val pig = loadPigEasterEggImage()
        assertTrue(pig.width > 0 && pig.height > 0, "the packaged pig image is empty")

        val pig2 = loadPig2EasterEggImage()
        assertTrue(pig2.width > 0 && pig2.height > 0, "the packaged pig2 image is empty")

        val packaged = requireNotNull(
            javaClass.getResourceAsStream(PIG_EASTER_EGG_RESOURCE),
        ).use { it.readBytes() }
        assertTrue(
            packaged.take(PIG_PNG_SIGNATURE.size).toByteArray().contentEquals(PIG_PNG_SIGNATURE),
            "the easter egg asset must be the packaged PNG",
        )

        val packaged2 = requireNotNull(
            javaClass.getResourceAsStream(PIG2_EASTER_EGG_RESOURCE),
        ).use { it.readBytes() }
        assertTrue(
            packaged2.take(PIG_PNG_SIGNATURE.size).toByteArray().contentEquals(PIG_PNG_SIGNATURE),
            "the easter egg asset 2 must be the packaged PNG",
        )
    }

    @Test
    fun `the shipped particle images decode`() {
        val snout = mihon.desktop.easteregg.loadSnoutParticleImage()
        assertTrue(snout.width > 0 && snout.height > 0, "the packaged snout particle image is empty")

        val star = mihon.desktop.easteregg.loadGoldStarImage()
        assertTrue(star.width > 0 && star.height > 0, "the packaged gold star image is empty")
    }

    @Test
    fun `clicking a pig spawns lingering snout particle before fading`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            settle(fixture.scene)
            fixture.easterEgg.fire()
            settle(fixture.scene) { pigs(fixture.scene).size == 7 }

            val firstPig = pigs(fixture.scene).first()
            fixture.click(firstPig)
            settle(fixture.scene) {
                nodes(fixture.scene).any { tag(it) == DESKTOP_PIG_EASTER_EGG_SNOUT_TAG }
            }
            assertTrue(
                nodes(fixture.scene).any { tag(it) == DESKTOP_PIG_EASTER_EGG_SNOUT_TAG },
                "a lingering snout particle must appear at the pig position",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `giant pig stars appear around the outer contour of the giant pig`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            settle(fixture.scene)
            fixture.easterEgg.fire()
            settle(fixture.scene) { pigs(fixture.scene).size == 7 }

            repeat(7) {
                val currentPigs = pigs(fixture.scene).filter { tag(it) != DESKTOP_PIG_EASTER_EGG_GIANT_TAG }
                if (currentPigs.isNotEmpty()) {
                    val countBefore = currentPigs.size
                    fixture.click(currentPigs.first())
                    settle(fixture.scene) {
                        pigs(fixture.scene).filter { tag(it) != DESKTOP_PIG_EASTER_EGG_GIANT_TAG }.size < countBefore ||
                            nodes(fixture.scene).any { tag(it) == DESKTOP_PIG_EASTER_EGG_GIANT_TAG }
                    }
                }
            }

            val minEdge = minOf(SCENE_WIDTH, SCENE_HEIGHT)
            val giantEdge = minEdge * PigEasterEggDefaults.GIANT_PIG_SCREEN_FRACTION
            val cx = SCENE_WIDTH / 2f
            val cy = SCENE_HEIGHT / 2f

            settle(fixture.scene, timeout = 10.seconds) {
                nodes(fixture.scene).any {
                    tag(it) == DESKTOP_PIG_EASTER_EGG_GIANT_TAG && it.boundsInRoot.width >= giantEdge * 0.9f
                } && nodes(fixture.scene).any { tag(it) == DESKTOP_PIG_EASTER_EGG_STAR_TAG }
            }

            val stars = nodes(fixture.scene).filter { tag(it) == DESKTOP_PIG_EASTER_EGG_STAR_TAG }
            assertTrue(stars.isNotEmpty(), "stars must appear with giant pig")

            // Stars must follow the giant pig contour (distance to center >= 0.35 * giantEdge)
            val minContourDist = giantEdge * 0.35f
            stars.forEach { star ->
                val starCenter = star.boundsInRoot.center
                val dist = hypot(starCenter.x - cx, starCenter.y - cy)
                assertTrue(
                    dist >= minContourDist,
                    "star was only $dist px away from center, but contour threshold is $minContourDist",
                )
            }
        } finally {
            fixture.close()
        }
    }

    private fun pigs(scene: ImageComposeScene) = nodes(scene).filter { tag(it) == DESKTOP_PIG_EASTER_EGG_TAG }

    private fun tag(node: SemanticsNode) =
        if (node.config.contains(SemanticsProperties.TestTag)) node.config[SemanticsProperties.TestTag] else null

    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private suspend fun settle(
        scene: ImageComposeScene,
        timeout: kotlin.time.Duration = 10.seconds,
        ready: () -> Boolean = { true },
    ) {
        withTimeout(timeout) {
            while (true) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                scene.render(System.nanoTime())
                yield()
                delay(10)
                if (ready()) {
                    repeat(3) {
                        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                        scene.render(System.nanoTime())
                        yield()
                    }
                    return@withTimeout
                }
            }
        }
    }

    private class Fixture(coroutineContext: CoroutineContext) {
        val easterEgg = PigEasterEggController()
        val scene = ImageComposeScene(SCENE_WIDTH.toInt(), SCENE_HEIGHT.toInt(), coroutineContext = coroutineContext) {}

        init {
            scene.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        DesktopPigEasterEggOverlay(
                            controller = easterEgg,
                            initialLifetimeMillis = LIFETIME_MILLIS,
                            giantLifetimeMillis = GIANT_LIFETIME_MILLIS,
                        )
                    }
                }
            }
        }

        fun click(node: SemanticsNode) {
            val center = node.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, center)
            scene.sendPointerEvent(PointerEventType.Release, center)
        }

        fun close() {
            scene.close()
        }
    }

    private companion object {
        const val LIFETIME_MILLIS = 800L
        const val GIANT_LIFETIME_MILLIS = 1_000L
        const val SCENE_WIDTH = 1_200f
        const val SCENE_HEIGHT = 800f
        val PIG_PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}
