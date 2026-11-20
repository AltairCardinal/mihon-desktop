package eu.kanade.presentation.manga

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaActionRow
import eu.kanade.presentation.theme.colorscheme.StrawberryColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "zh-rCN")
class MangaActionRowCustomizationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `preview swipes through all actions without executing business or saving draft`() = withRow { clicks ->
        val customize = compose.onNodeWithTag(
            "manga-actions",
        ).fetchSemanticsNode().config[SemanticsActions.CustomActions].first()
        compose.runOnIdle { assertTrue(customize.action.invoke()) }
        compose.onNodeWithTag("manga-action-preview-TRACKING").performTouchInput {
            swipe(center, center - Offset(180f, 0f), 200)
        }
        val viewport = compose.onNodeWithTag("manga-action-preview-viewport").fetchSemanticsNode().boundsInRoot
        val revealed = compose.onNodeWithTag("manga-action-preview-DELETE_AFTER_READ").fetchSemanticsNode().boundsInRoot
        assertTrue(revealed.width > 40f && revealed.left >= viewport.left && revealed.right <= viewport.right)
        assertEquals(0, clicks())
        compose.onNodeWithText("长按拖动调整顺序，收藏也可移动").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `terminal scroll pulls show a shallow arc at either edge`() =
        withRow(colors = StrawberryColorScheme.darkScheme) {
            val strip = compose.onNodeWithTag("manga-actions")
            val viewport = compose.onNodeWithTag("manga-action-viewport").fetchSemanticsNode().boundsInRoot
            val sampleWidth = with(compose.density) { 28.dp.roundToPx() }
            val maxDepth = with(compose.density) { 10.dp.roundToPx() }
            fun assertShallow(rest: List<Int>, pulled: List<Int>) {
                val affected = rest.indices.filter { rest[it] != pulled[it] }
                assertTrue("An overscroll should change edge pixels", affected.isNotEmpty())
                assertTrue("The arc must stay within 10 dp of the edge: $affected", affected.last() < maxDepth)
            }
            fun boundaryPixels(left: Boolean) = windowPixels { image ->
                List(sampleWidth) { x ->
                    val column = if (left) viewport.left.toInt() + x else viewport.right.toInt() - 1 - x
                    image.getPixel(column, viewport.center.y.toInt())
                }
            }
            val leftAtRest = boundaryPixels(left = true)
            repeat(3) {
                strip.performTouchInput {
                    swipe(Offset(width * .85f, height * .25f), Offset(width * .05f, height * .25f), 200)
                }
            }
            val rightAtRest = boundaryPixels(left = false)
            strip.performTouchInput {
                down(Offset(width * .8f, height * .25f))
                moveBy(Offset(-width / 3f, 0f))
            }
            assertShallow(rightAtRest, boundaryPixels(left = false))
            strip.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(250)
            assertEquals(rightAtRest, boundaryPixels(left = false))

            strip.performTouchInput {
                down(Offset(width * .05f, height * .25f))
                moveBy(Offset(width * .9f, 0f))
                moveBy(Offset(width * .4f, 0f))
            }
            assertShallow(leftAtRest, boundaryPixels(left = true))
            compose.onNodeWithTag("manga-action-customization").assertDoesNotExist()
            val library = compose.onNodeWithTag("manga-action-LIBRARY").fetchSemanticsNode().boundsInRoot
            assertEquals(viewport.left, library.left, 1f)
            strip.performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(250)
            assertEquals(leftAtRest, boundaryPixels(left = true))
        }

    private fun <T> windowPixels(read: (android.graphics.Bitmap) -> T): T = compose.runOnIdle {
        val window = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
        val image = android.graphics.Bitmap.createBitmap(
            window.width,
            window.height,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        try {
            window.draw(android.graphics.Canvas(image))
            read(image)
        } finally {
            image.recycle()
        }
    }

    @Test
    fun `long dragging library reorders actual rail without customization or click`() = withRow { clicks ->
        val tracking = compose.onNodeWithTag("manga-action-TRACKING").fetchSemanticsNode().boundsInRoot.center
        val library = compose.onNodeWithTag("manga-action-LIBRARY").fetchSemanticsNode().boundsInRoot.center
        val origin = compose.onNodeWithTag("manga-actions").fetchSemanticsNode().boundsInRoot.topLeft
        compose.onNodeWithTag("manga-actions").performTouchInput {
            down(library - origin)
            advanceEventTime(650)
            moveBy(tracking - library, 200)
            up()
        }
        compose.onNodeWithTag("manga-action-customization").assertDoesNotExist()
        assertTrue(
            compose.onNodeWithTag("manga-action-TRACKING").fetchSemanticsNode().boundsInRoot.left <
                compose.onNodeWithTag("manga-action-LIBRARY").fetchSemanticsNode().boundsInRoot.left,
        )
        assertEquals(0, clicks())
    }

    @Test
    fun `cancelled long drag does not save or click`() = withRow { clicks ->
        val before = compose.onNodeWithTag("manga-action-LIBRARY").fetchSemanticsNode().boundsInRoot.left
        compose.onNodeWithTag("manga-action-LIBRARY").performTouchInput {
            down(center)
            advanceEventTime(650)
            moveBy(Offset(80f, 0f), 200)
            cancel()
        }
        assertEquals(before, compose.onNodeWithTag("manga-action-LIBRARY").fetchSemanticsNode().boundsInRoot.left, 1f)
        compose.onNodeWithTag("manga-action-customization").assertDoesNotExist()
        assertEquals(0, clicks())
    }

    @Test
    fun `customization has one sorting column and no checkbox`() = withRow {
        val customize = compose.onNodeWithTag("manga-actions").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].first()
        compose.runOnIdle { assertTrue(customize.action.invoke()) }
        assertTrue(
            compose.onAllNodes(
                SemanticsMatcher("toggleable") {
                    it.config.contains(SemanticsProperties.ToggleableState)
                },
            ).fetchSemanticsNodes().isEmpty(),
        )
        val library = compose.onNodeWithTag("manga-action-order-LIBRARY").fetchSemanticsNode().boundsInRoot
        val tracking = compose.onNodeWithTag("manga-action-order-TRACKING").fetchSemanticsNode().boundsInRoot
        assertEquals(library.left, tracking.left, 1f)
        assertTrue(tracking.top >= library.bottom)
    }

    @Test
    fun `left swipe exposes delete after read and its three state setting`() = withRow {
        compose.onNodeWithTag("manga-actions").performTouchInput {
            swipe(Offset(width * .85f, height * .25f), Offset(width * .05f, height * .25f), 200)
        }
        compose.onNodeWithText("读完即删").performClick()
        compose.onNodeWithTag("manga-delete-choice-INHERIT").assertExists()
        compose.onNodeWithTag("manga-delete-choice-ENABLED").assertExists()
        compose.onNodeWithTag("manga-delete-choice-DISABLED").assertExists()
    }

    @Test
    fun `copy link remains reachable from additional actions`() {
        var copyClicks = 0
        withRow(onCopyClicked = { copyClicks++ }) {
            compose.onNodeWithTag("manga-actions").performTouchInput {
                swipe(Offset(width * .85f, height * .25f), Offset(width * .05f, height * .25f), 200)
            }
            compose.onNodeWithTag("manga-action-COPY_LINK").performClick()
            assertEquals(1, copyClicks)
        }
    }

    @Test
    fun `slow drags settle with the next action fully visible in either direction`() = withRow { clicks ->
        val viewport = compose.onNodeWithTag("manga-action-viewport")
        val bounds = viewport.fetchSemanticsNode().boundsInRoot
        val library = compose.onNodeWithTag("manga-action-LIBRARY")
        val next = compose.onNodeWithTag("manga-action-DELETE_AFTER_READ")
        val slot = library.fetchSemanticsNode().boundsInRoot.width
        viewport.performTouchInput {
            down(center)
            moveBy(Offset(-slot * .65f, 0f), 240)
        }
        assertTrue(next.fetchSemanticsNode().boundsInRoot.width in 1f..slot - 2f)
        viewport.performTouchInput {
            advanceEventTime(200)
            up()
        }
        assertEquals(slot, next.fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(bounds.right, next.fetchSemanticsNode().boundsInRoot.right, 1f)

        viewport.performTouchInput {
            down(center)
            moveBy(Offset(slot * .65f, 0f), 240)
        }
        assertTrue(library.fetchSemanticsNode().boundsInRoot.width in 1f..slot - 2f)
        viewport.performTouchInput {
            advanceEventTime(200)
            up()
        }
        assertEquals(slot, library.fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(bounds.left, library.fetchSemanticsNode().boundsInRoot.left, 1f)
        assertEquals(0, clicks())
    }

    @Test
    fun `quick short fling reaches the last full slot without overshooting`() = withRow {
        val viewport = compose.onNodeWithTag("manga-action-viewport")
        val bounds = viewport.fetchSemanticsNode().boundsInRoot
        val slot = compose.onNodeWithTag("manga-action-LIBRARY").fetchSemanticsNode().boundsInRoot.width
        viewport.performTouchInput {
            down(center)
            repeat(5) { moveBy(Offset(-slot * .17f, 0f), 8) }
            up()
        }
        val sort = compose.onNodeWithTag("manga-action-customize").fetchSemanticsNode().boundsInRoot
        assertEquals(slot, sort.width, 1f)
        assertEquals(bounds.right, sort.right, 1f)
        compose.onNodeWithTag("manga-action-customization").assertDoesNotExist()
    }

    @Test
    fun `touching the rail again stops a running settle animation`() = withRow {
        val viewport = compose.onNodeWithTag("manga-action-viewport")
        val next = compose.onNodeWithTag("manga-action-DELETE_AFTER_READ")
        val slot = compose.onNodeWithTag("manga-action-LIBRARY").fetchSemanticsNode().boundsInRoot.width
        viewport.performTouchInput {
            down(center)
            moveBy(Offset(-slot * .65f, 0f), 240)
        }
        val partShown = next.fetchSemanticsNode().boundsInRoot.width
        compose.mainClock.autoAdvance = false
        try {
            viewport.performTouchInput {
                advanceEventTime(200)
                up()
            }
            compose.mainClock.advanceTimeBy(80)
            val moving = next.fetchSemanticsNode().boundsInRoot.width
            assertTrue("The rail should move gradually after release", moving > partShown && moving < slot)
            viewport.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(400)
            assertEquals(moving, next.fetchSemanticsNode().boundsInRoot.width, 1f)
            viewport.performTouchInput { cancel() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `swipe reaches last sort slot with edit icon and only a tap opens the panel`() = withRow {
        val strip = compose.onNodeWithTag("manga-actions")
        repeat(3) {
            strip.performTouchInput {
                swipe(Offset(width * .88f, height * .25f), Offset(width * .04f, height * .25f), 200)
            }
        }
        val sort = compose.onNodeWithTag("manga-action-customize")
        val viewport = compose.onNodeWithTag("manga-action-viewport").fetchSemanticsNode().boundsInRoot
        val bounds = sort.fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.right, bounds.right, 2f)
        val ink = lightColorScheme().onSurfaceVariant.toArgb()
        with(compose.density) {
            windowPixels { image ->
                // The edit square has a continuous left edge. Sort draws three separate horizontal bars.
                val left = (bounds.center.x - 8.dp.toPx()).toInt()
                val right = (bounds.center.x - 6.dp.toPx()).toInt()
                val top = (bounds.top + 8.dp.toPx()).toInt()
                val bottom = (bounds.top + 36.dp.toPx()).toInt()
                val edgeLength = 10.dp.roundToPx()
                assertTrue(
                    "The edit icon should show the left edge of its square",
                    (left..right).any { x ->
                        (top..bottom - edgeLength).any { y ->
                            (y until y + edgeLength).all { image.getPixel(x, it) == ink }
                        }
                    },
                )
            }
        }
        compose.onNodeWithText("排序").assertExists()
        sort.performTouchInput {
            down(center)
            advanceEventTime(650)
            up()
        }
        compose.onNodeWithTag("manga-action-customization").assertDoesNotExist()
        sort.performTouchInput {
            down(center)
            up()
        }
        compose.onNodeWithTag("manga-action-customization").assertExists()
    }

    private fun withRow(
        onCopyClicked: () -> Unit = {
        },
        colors: ColorScheme = lightColorScheme(),
        block: (() -> Int) -> Unit,
    ) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("detail-actions-${System.nanoTime()}", 0)
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val store = AndroidPreferenceStore(activity.get(), shared)
        Injekt.addSingleton<tachiyomi.core.common.preference.PreferenceStore>(store)
        Injekt.addSingleton(DownloadPreferences(store))
        var clicks = 0
        try {
            activity.get().setContent {
                MaterialTheme(colorScheme = colors) {
                    Column {
                        MangaActionRow(
                            mangaId = 7,
                            favorite = false,
                            trackingCount = 0,
                            nextUpdate = null,
                            isUserIntervalMode = false,
                            onAddToLibraryClicked = { clicks++ },
                            onWebViewClicked = {},
                            onWebViewLongClicked = onCopyClicked,
                            onTrackingClicked = {},
                            onEditIntervalClicked = {},
                            onEditCategory = {},
                        )
                    }
                }
            }
            block { clicks }
        } finally {
            activity.pause().stop().destroy()
            Injekt = previous
            shared.edit().clear().commit()
        }
    }
}
