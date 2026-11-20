package mihon.desktop.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DetailActionTestPreferences
import mihon.desktop.download.DesktopDownloadPreferences
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.download.service.MangaDownloadDeletion
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaDetailAction
import tachiyomi.domain.manga.service.MangaDetailPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class MangaDetailActionCustomizationTest {
    @Test
    fun `dragging preview changes only draft until saved and cancel discards it`() = runBlocking {
        val store = DesktopPreferenceStore(DetailActionTestPreferences())
        val original = MangaDetailPreferences(store).layout().get()
        withRow(store, httpSource = true) { scene ->
            scene.openCustomization()
            scene.longDrag("manga-action-preview-LIBRARY", "manga-action-preview-TRACKING")
            val library = scene.node("manga-action-preview-LIBRARY").boundsInRoot
            val tracking = scene.node("manga-action-preview-TRACKING").boundsInRoot
            assertTrue(library.left > tracking.left)
            assertEquals(original, MangaDetailPreferences(store).layout().get())
            scene.clickLabel(MR.strings.action_cancel.localized())
            assertEquals(original, MangaDetailPreferences(store).layout().get())
            scene.openCustomization()
            scene.longDrag("manga-action-preview-LIBRARY", "manga-action-preview-TRACKING")
            scene.clickLabel(MR.strings.action_save.localized())
            val saved = MangaDetailPreferences(store).layout().get().order
            assertTrue(saved.indexOf(MangaDetailAction.LIBRARY) > saved.indexOf(MangaDetailAction.TRACKING))
        }
    }

    @Test
    fun `edit preview wheel browses all actions without clicking or saving`() = runBlocking {
        val store = DesktopPreferenceStore(DetailActionTestPreferences())
        val original = MangaDetailPreferences(store).layout().get()
        withRow(store, httpSource = true) { scene ->
            scene.openCustomization()
            scene.wheel(1f, "manga-action-preview-LIBRARY")
            val viewport = scene.node("manga-action-preview-viewport").boundsInRoot
            val revealed = scene.node("manga-action-preview-DELETE_AFTER_READ").boundsInRoot
            assertTrue(revealed.width > 40f && revealed.left >= viewport.left && revealed.right <= viewport.right)
            assertFalse(scene.nodes().any { "Library can also be moved" in scene.labels(it).joinToString() })
            assertFalse(scene.libraryClicked)
            scene.clickLabel(MR.strings.action_cancel.localized())
            assertEquals(original, MangaDetailPreferences(store).layout().get())
        }
    }

    @Test
    fun `long dragging collection changes preview and persists its position on save`() = runBlocking {
        val store = DesktopPreferenceStore(DetailActionTestPreferences())
        withRow(store, httpSource = true) { scene ->
            scene.openCustomization()
            scene.longDrag("manga-action-order-LIBRARY", "manga-action-order-TRACKING")
            assertTrue(
                scene.node("manga-action-preview-LIBRARY").boundsInRoot.left >
                    scene.node("manga-action-preview-TRACKING").boundsInRoot.left,
            )
            assertEquals(MangaDetailAction.defaults, MangaDetailPreferences(store).primaryActions().get())
            scene.clickLabel(MR.strings.action_save.localized())
            assertEquals(
                listOf(MangaDetailAction.TRACKING, MangaDetailAction.LIBRARY),
                MangaDetailPreferences(store).layout().get().order.take(2),
            )
        }
    }

    @Test
    fun `cancel after long dragging collection discards both membership and order`() = runBlocking {
        val store = DesktopPreferenceStore(DetailActionTestPreferences())
        val original = MangaDetailPreferences(store).layout().get()
        withRow(store, httpSource = true) { scene ->
            scene.openCustomization()
            scene.longDrag("manga-action-order-LIBRARY", "manga-action-order-TRACKING")
            scene.clickLabel(MR.strings.action_cancel.localized())
            assertEquals(original, MangaDetailPreferences(store).layout().get())
        }
    }

    @Test
    fun `long dragging actual library action reorders rail without opening customization or clicking`() = runBlocking {
        val store = DesktopPreferenceStore(DetailActionTestPreferences())
        withRow(store, httpSource = true) { scene ->
            scene.longDrag("manga-action-LIBRARY", "manga-action-TRACKING")
            assertEquals(
                listOf(MangaDetailAction.TRACKING, MangaDetailAction.LIBRARY),
                MangaDetailPreferences(store).layout().get().order.take(2),
            )
            assertFalse(scene.nodes().any { scene.tag(it, "manga-action-customization") })
            assertFalse(scene.libraryClicked)
        }
    }

    @Test
    fun `holding business action without moving never clicks or opens customization`() = runBlocking {
        withRow { scene ->
            val point = scene.node("manga-action-LIBRARY").boundsInRoot.center
            scene.pointer(PointerEventType.Press, point, true)
            repeat(42) {
                scene.render()
                delay(16)
            }
            scene.pointer(PointerEventType.Release, point, false)
            repeat(3) {
                scene.render()
                delay(20)
            }
            assertFalse(scene.libraryClicked)
            assertFalse(scene.nodes().any { scene.tag(it, "manga-action-customization") })
        }
    }

    @Test
    fun `cancelled long press leaves rail order unchanged without clicking`() = runBlocking {
        val store = DesktopPreferenceStore(DetailActionTestPreferences())
        val original = MangaDetailPreferences(store).layout().get()
        withRow(store) { scene ->
            val point = scene.node("manga-action-LIBRARY").boundsInRoot.center
            scene.pointer(PointerEventType.Press, point, true)
            repeat(42) {
                scene.render()
                delay(16)
            }
            scene.pointer(PointerEventType.Move, point + Offset(90f, 0f), true)
            scene.render()
            scene.scene.cancelPointerInput()
            scene.render()
            assertEquals(original, MangaDetailPreferences(store).layout().get())
            assertFalse(scene.libraryClicked)
            assertFalse(scene.nodes().any { scene.tag(it, "manga-action-customization") })
        }
    }

    @Test
    fun `held action highlight fills the button without painting its outer corners`() = runBlocking {
        withRow { scene ->
            val action = scene.node("manga-action-TRACKING").boundsInRoot
            fun highlightSamples(): Pair<Color, Color> {
                val pixels = scene.pixels()
                val y = action.top.toInt() + 1
                return pixels[action.left.toInt() + 1, y] to pixels[action.center.x.toInt(), y]
            }
            scene.pointer(PointerEventType.Move, action.center, false)
            repeat(16) {
                scene.render()
                delay(16)
            }
            val hovered = highlightSamples()
            scene.pointer(PointerEventType.Press, action.center, true)
            repeat(48) {
                scene.render()
                delay(16)
            }
            val held = highlightSamples()
            assertTrue(held.second != hovered.second, "A held button should show a highlight")
            assertEquals(hovered.first, held.first, "Highlight must preserve the rounded corner")
            scene.pointer(PointerEventType.Release, action.center, false)
            scene.pointer(PointerEventType.Move, Offset(action.center.x, action.bottom + 15f), false)
            repeat(24) {
                scene.render()
                delay(16)
            }
            val released = highlightSamples()
            assertEquals(hovered.first, released.first)
            assertTrue(released.second != held.second, "The held highlight must clear once the action is released")
        }
    }

    @Test
    fun `holding a business button at the edge does not reorder or scroll before movement`() = runBlocking {
        val store = DesktopPreferenceStore(DetailActionTestPreferences())
        withRow(store, httpSource = true) { scene ->
            val before = MangaDetailPreferences(store).layout().get().order
            val viewport = scene.node("manga-action-viewport").boundsInRoot
            val edge = Offset(viewport.right - 4f, viewport.center.y)
            val library = scene.node("manga-action-LIBRARY").boundsInRoot
            scene.pointer(PointerEventType.Press, edge, true)
            repeat(90) {
                scene.render()
                delay(16)
            }
            assertEquals(library.left, scene.node("manga-action-LIBRARY").boundsInRoot.left, 1f)
            scene.pointer(PointerEventType.Release, edge, false)
            scene.render()
            assertEquals(before, MangaDetailPreferences(store).layout().get().order)
        }
    }

    @Test
    fun `held long drag at viewport edge reaches actions beyond the first page`() = runBlocking {
        val store = DesktopPreferenceStore(DetailActionTestPreferences())
        withRow(store, httpSource = true) { scene ->
            scene.longDrag("manga-action-LIBRARY", "manga-action-TRACKING", holdAtEdge = true)
            val order = MangaDetailPreferences(store).layout().get().order
            assertTrue(order.indexOf(MangaDetailAction.LIBRARY) > order.indexOf(MangaDetailAction.SHARE))
            assertFalse(scene.libraryClicked)
        }
    }

    @Test
    fun `failed main rail reorder preserves saved order and offers visible feedback`() = runBlocking {
        val backend = DetailActionTestPreferences()
        val store = DesktopPreferenceStore(backend)
        val original = MangaDetailPreferences(store).layout().get()
        withRow(store, httpSource = true) { scene ->
            backend.failWrites = true
            scene.longDrag("manga-action-LIBRARY", "manga-action-TRACKING")
            assertEquals(original, MangaDetailPreferences(store).layout().get())
            assertTrue(scene.nodes().any { scene.tag(it, "manga-action-reorder-error") })
            assertTrue(
                scene.node("manga-action-LIBRARY").boundsInRoot.left <
                    scene.node("manga-action-TRACKING").boundsInRoot.left,
            )
            backend.failWrites = false
            scene.longDrag("manga-action-LIBRARY", "manga-action-TRACKING")
            assertEquals(MangaDetailAction.TRACKING, MangaDetailPreferences(store).layout().get().order.first())
        }
    }

    @Test
    fun `customization is a single column of sorting actions without selection controls`() = runBlocking {
        withRow(httpSource = true, width = 600) { scene ->
            scene.openCustomization()
            assertFalse(scene.nodes().any { it.config.contains(SemanticsProperties.ToggleableState) })
            val library = scene.node("manga-action-order-LIBRARY").boundsInRoot
            val tracking = scene.node("manga-action-order-TRACKING").boundsInRoot
            assertEquals(library.left, tracking.left, 1f)
            assertTrue(tracking.top >= library.bottom)
            assertFalse(scene.nodes().any { "At most 4" in scene.labels(it).joinToString() })
        }
    }

    @Test
    fun `desktop mouse drag does not replace business actions`() = runBlocking {
        withRow { scene ->
            scene.drag(Offset(40f, 40f), Offset(320f, 40f))
            assertFalse(scene.nodes().any { "Customize buttons" in scene.labels(it) })
            assertTrue(scene.nodes().any { MR.strings.add_to_library.localized() in scene.labels(it) })
        }
    }

    @Test
    fun `wheel pages forwards and returns to the first actions without opening the dialog`() = runBlocking {
        withRow(httpSource = true) { scene ->
            scene.wheel(1f)
            assertTrue(scene.nodes().any { scene.tag(it, "manga-action-COPY_LINK") })
            scene.wheel(-1f)
            assertTrue(scene.nodes().any { scene.tag(it, "manga-action-LIBRARY") })
            assertTrue(scene.node("manga-action-page-previous").config.contains(SemanticsProperties.Disabled))
            scene.wheel(-1f)
            assertFalse(scene.nodes().any { scene.tag(it, "manga-action-customization") })
        }
    }

    @Test
    fun `cancel discards draft and unavailable actions do not block remaining actions`() = runBlocking {
        val store = DesktopPreferenceStore(DetailActionTestPreferences())
        withRow(store) { scene ->
            scene.openCustomization()
            scene.moveAction("TRACKING", 1)
            scene.clickLabel(MR.strings.action_cancel.localized())
            assertEquals(MangaDetailAction.defaults, MangaDetailPreferences(store).primaryActions().get())
            scene.openCustomization()
            assertFalse(scene.nodes().any { scene.tag(it, "manga-action-order-WEB") })
            assertTrue(scene.nodes().any { scene.tag(it, "manga-action-order-DELETE_AFTER_READ") })
            scene.clickLabel(MR.strings.action_cancel.localized())
            scene.wheel(1f)
            assertTrue(scene.nodes().any { scene.tag(it, "manga-action-DELETE_AFTER_READ") })
        }
    }

    @Test
    fun `narrow large text detail actions and customization remain reachable`() = runBlocking {
        withRow(httpSource = true, width = 320, fontScale = 2f) { scene ->
            val library = scene.node("manga-action-LIBRARY")
            assertTrue(library.boundsInRoot.width > 0 && library.boundsInRoot.right <= 320f)
            val arrow = scene.node("manga-action-page-next")
            assertTrue(library.boundsInRoot.height > 72f)
            assertEquals(library.boundsInRoot.height, arrow.boundsInRoot.height, 1f)
            scene.savePng(File(".test-tmp/detail-actions-visual/primary-320-font200.png"))
            scene.openCustomization()
            assertTrue(scene.nodes().any { scene.tag(it, "manga-action-customization") })
            assertTrue(scene.node("manga-action-order-LIBRARY").boundsInRoot.width > 200f)
            assertTrue(
                scene.nodes().any {
                    MR.strings.action_save.localized() in scene.labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                },
            )
            scene.savePng(File(".test-tmp/detail-actions-visual/customize-320-font200.png"))
        }
    }

    @Test
    fun `left drag exposes delete after read with three setting choices`() = runBlocking {
        val store = DesktopPreferenceStore(DetailActionTestPreferences())
        withRow(store) { scene ->
            scene.wheel(1f)
            val delete = scene.nodes().firstOrNull {
                "Delete after read" in scene.labels(it) && it.config.contains(SemanticsActions.OnClick)
            }
            assertTrue(delete != null, "More actions must contain the new per-manga setting")
            delete!!.config[SemanticsActions.OnClick].action!!.invoke()
            scene.render()
            assertTrue(scene.nodes().any { "Follow global" in scene.labels(it) })
            assertTrue(scene.nodes().any { "On" in scene.labels(it) })
            assertTrue(scene.nodes().any { "Off" in scene.labels(it) })
            scene.clickTag("manga-delete-choice-ENABLED")
            val enabled = scene.node("manga-delete-choice-ENABLED").boundsInRoot
            val retention = scene.node("manga-delete-retention").boundsInRoot
            val bookmarks = scene.node("manga-delete-bookmarked").boundsInRoot
            val disabled = scene.node("manga-delete-choice-DISABLED").boundsInRoot
            assertTrue(enabled.bottom <= retention.top)
            assertTrue(retention.bottom <= bookmarks.top)
            val inherit = scene.node("manga-delete-choice-INHERIT").boundsInRoot
            assertEquals(inherit.top, enabled.top, 0.1f)
            assertTrue(disabled.bottom <= retention.top)
            scene.savePng(File(".test-tmp/detail-actions-visual/deletion-tiles-600-light.png"))
            scene.clickTag("manga-delete-retention")
            scene.clickLabel(MR.strings.third_to_last.localized())
            scene.clickTag("manga-delete-bookmarked")
            for (mode in listOf("DISABLED", "INHERIT")) {
                scene.clickTag("manga-delete-choice-$mode")
                assertTrue(scene.node("manga-delete-choice-$mode").config[SemanticsProperties.Selected])
                assertFalse(scene.nodes().any { scene.tag(it, "manga-delete-retention") })
            }
            scene.clickTag("manga-delete-choice-ENABLED")
            scene.clickTag("manga-delete-retention")
            scene.clickTag("manga-delete-retention-2")
            assertTrue(scene.nodes().any { scene.tag(it, "manga-delete-retention-picker") })
            scene.clickTag("manga-delete-retention-cancel")
            scene.clickLabel(MR.strings.action_close.localized())
            val saved = DownloadPreferences(store).mangaDeletionSettings(7).get()
            assertEquals(2, saved.slots)
            assertTrue(saved.removeBookmarked)
        }
    }

    @Test
    fun `narrow deletion tiles wrap above immediate settings`() = runBlocking {
        val store = DesktopPreferenceStore(DetailActionTestPreferences())
        withRow(store, width = 320) { scene ->
            scene.wheel(1f)
            scene.clickLabel("Delete after read")
            scene.clickTag("manga-delete-choice-ENABLED")
            val inherit = scene.node("manga-delete-choice-INHERIT").boundsInRoot
            val disabled = scene.node("manga-delete-choice-DISABLED").boundsInRoot
            scene.savePng(File(".test-tmp/detail-actions-visual/deletion-tiles-320-light.png"))
            assertTrue(inherit.bottom <= disabled.top)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            scene.node(
                "manga-delete-choice-INHERIT",
            ).config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertTrue(
                layouts.single().size.height + 16 <= inherit.height,
                "Tile must fit its complete label and padding",
            )
            assertFalse(layouts.single().didOverflowHeight, "Tile must not clip a wrapped label")
            assertTrue(disabled.bottom <= scene.node("manga-delete-retention").boundsInRoot.top)
            assertTrue(disabled.left >= 0 && disabled.right <= 320)
            scene.clickTag("manga-delete-bookmarked")
            scene.clickLabel(MR.strings.action_close.localized())
            assertTrue(DownloadPreferences(store).mangaDeletionSettings(7).get().removeBookmarked)
        }
    }

    @Test
    fun `saved sorting survives a new detail composition and collection may leave first slot`() = runBlocking {
        val store = DesktopPreferenceStore(DetailActionTestPreferences())
        withRow(store) { scene ->
            scene.openCustomization()
            scene.moveAction("LIBRARY", 1)
            scene.clickLabel(MR.strings.action_save.localized())
            assertEquals(MangaDetailAction.TRACKING, MangaDetailPreferences(store).layout().get().order.first())
        }
        withRow(store) { scene ->
            assertTrue(
                scene.node("manga-action-TRACKING").boundsInRoot.left <
                    scene.node("manga-action-LIBRARY").boundsInRoot.left,
            )
        }
    }

    @Test
    fun `deletion settings apply immediately and closing preserves changes for selected work`() = runBlocking {
        val store = DesktopPreferenceStore(DetailActionTestPreferences())
        val preferences = DownloadPreferences(store)
        withRow(store) { scene ->
            scene.wheel(1f)
            scene.clickLabel("Delete after read")
            scene.clickTag("manga-delete-choice-ENABLED")
            assertEquals(MangaDownloadDeletion.ENABLED, preferences.mangaDeletion(7).get())
            assertFalse(scene.nodes().any { MR.strings.action_save.localized() in scene.labels(it) })
            assertFalse(scene.nodes().any { MR.strings.action_cancel.localized() in scene.labels(it) })
            scene.clickTag("manga-delete-retention")
            scene.clickTag("manga-delete-retention-2")
            assertEquals(2, preferences.mangaDeletionSettings(7).get().slots)
            scene.clickTag("manga-delete-bookmarked")
            assertTrue(preferences.mangaDeletionSettings(7).get().removeBookmarked)
            scene.clickLabel(MR.strings.action_close.localized())
            scene.clickLabel("Delete after read")
            assertTrue(MR.strings.third_to_last.localized() in scene.labels(scene.node("manga-delete-retention")))
            assertEquals(MangaDownloadDeletion.INHERIT, preferences.mangaDeletion(8).get())
            assertFalse(DesktopDownloadPreferences(store).deleteAfterRead.get())
        }
    }

    @Test
    fun `failed immediate deletion write keeps previous state and can retry without closing`() = runBlocking {
        val backend = DetailActionTestPreferences()
        val store = DesktopPreferenceStore(backend)
        withRow(store) { scene ->
            scene.wheel(1f)
            scene.clickLabel("Delete after read")
            backend.failWrites = true
            scene.clickTag("manga-delete-choice-ENABLED")
            assertTrue(scene.nodes().any { scene.tag(it, "manga-delete-save-error") })
            assertEquals(MangaDownloadDeletion.INHERIT, DownloadPreferences(store).mangaDeletion(7).get())
            assertTrue(scene.node("manga-delete-choice-INHERIT").config[SemanticsProperties.Selected])
            backend.failWrites = false
            scene.clickTag("manga-delete-choice-ENABLED")
            assertFalse(scene.nodes().any { scene.tag(it, "manga-delete-save-error") })
            assertTrue(scene.nodes().any { scene.tag(it, "manga-delete-setting") })
            assertEquals(MangaDownloadDeletion.ENABLED, DownloadPreferences(store).mangaDeletion(7).get())
        }
    }

    @Test
    fun `failed customization save keeps its draft and retries without closing`() = runBlocking {
        val backend = DetailActionTestPreferences()
        val store = DesktopPreferenceStore(backend)
        withRow(store) { scene ->
            scene.openCustomization()
            scene.moveAction("TRACKING", 1)
            backend.failWrites = true
            scene.clickLabel(MR.strings.action_save.localized())
            assertTrue(scene.nodes().any { scene.tag(it, "manga-action-save-error") })
            assertTrue(scene.nodes().any { scene.tag(it, "manga-action-customization") })
            assertEquals(MangaDetailAction.defaults, MangaDetailPreferences(store).primaryActions().get())
            backend.failWrites = false
            scene.clickLabel(MR.strings.action_save.localized())
            assertEquals(MangaDetailAction.DELETE_AFTER_READ, MangaDetailPreferences(store).layout().get().order[1])
        }
    }

    @Test
    fun `arrows remain disabled at the boundaries and page to the last sort slot`() = runBlocking {
        withRow(httpSource = true) { scene ->
            val previous = "manga-action-page-previous"
            val next = "manga-action-page-next"
            fun disabled(tag: String) = scene.node(tag).config.contains(SemanticsProperties.Disabled)
            val original = scene.node("manga-action-LIBRARY").boundsInRoot
            assertTrue(disabled(previous))
            assertFalse(disabled(next))
            val viewport = scene.node("manga-action-viewport").boundsInRoot
            assertTrue(scene.node(previous).boundsInRoot.right <= viewport.left)
            assertTrue(scene.node(next).boundsInRoot.left >= viewport.right)
            assertEquals(original.height, scene.node(next).boundsInRoot.height, 1f)
            scene.clickTag(next)
            assertFalse(disabled(previous))
            assertTrue(scene.node("manga-action-page-dot-1").config[SemanticsProperties.Selected])
            scene.clickTag(previous)
            assertEquals(original.left, scene.node("manga-action-LIBRARY").boundsInRoot.left, 1f)
            assertTrue(disabled(previous))
            repeat(4) { if (!disabled(next)) scene.clickTag(next) }
            assertTrue(disabled(next))
            val sortTag = "manga-action-customize"
            val viewportEnd = scene.node("manga-action-viewport").boundsInRoot.right
            assertEquals(viewportEnd, scene.node(sortTag).boundsInRoot.right, 2f)
            val sort = scene.node(sortTag).boundsInRoot.center
            scene.pointer(PointerEventType.Press, sort, true)
            repeat(40) {
                scene.render()
                delay(16)
            }
            scene.pointer(PointerEventType.Release, sort, false)
            scene.render()
            assertFalse(scene.nodes().any { scene.tag(it, "manga-action-customization") })
            scene.clickTag(sortTag)
            assertTrue(scene.nodes().any { scene.tag(it, "manga-action-customization") })
        }
    }

    private suspend fun withRow(
        store: DesktopPreferenceStore = DesktopPreferenceStore(DetailActionTestPreferences()),
        httpSource: Boolean = false,
        width: Int = 360,
        fontScale: Float = 1f,
        block: suspend (Scene) -> Unit,
    ) {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton<PreferenceStore>(store)
        Injekt.addSingleton(DesktopDownloadPreferences(store))
        val fixture = Scene(kotlin.coroutines.coroutineContext, if (width == 320) 320 else 600)
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true)
        try {
            fixture.scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalDensity provides Density(1f, fontScale),
                ) {
                    MaterialTheme {
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.width(width.dp)) {
                                MangaDetailActionRow(
                                    manga = Manga.create().copy(id = 7),
                                    mangaUrl = if (httpSource) "https://example.org/work" else null,
                                    hasUnreadChapters = false,
                                    onToggleLibrary = { fixture.libraryClicked = true },
                                    onEditFetchInterval = {},
                                    onTracking = {},
                                    onOpenInBrowser = {},
                                )
                            }
                        }
                    }
                }
            }
            repeat(3) {
                fixture.render()
                delay(20)
            }
            block(fixture)
        } finally {
            fixture.scene.close()
            Injekt = previous
        }
    }

    private class Scene(context: kotlin.coroutines.CoroutineContext, private val width: Int) {
        private val owners = mutableSetOf<SemanticsOwner>()
        var libraryClicked = false
        private val bitmap = ImageBitmap(width, 680)
        private val canvas = Canvas(bitmap)
        val scene = CanvasLayersComposeScene(
            size = IntSize(width, 680),
            coroutineContext = context,
            platformContext = object : PlatformContext {
                override val windowInfo = object : androidx.compose.ui.platform.WindowInfo {
                    override val isWindowFocused = true
                    override val containerSize = IntSize(width, 680)
                    override val containerDpSize = androidx.compose.ui.unit.DpSize(width.dp, 680.dp)
                }
                override val inputModeManager = object : androidx.compose.ui.input.InputModeManager {
                    override val inputMode = androidx.compose.ui.input.InputMode.Keyboard
                    override fun requestInputMode(inputMode: androidx.compose.ui.input.InputMode) = true
                }
                override fun requestFocus() = true
                override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                    override fun onSemanticsOwnerAppended(owner: SemanticsOwner) {
                        owners += owner
                    }
                    override fun onSemanticsOwnerRemoved(owner: SemanticsOwner) {
                        owners -= owner
                    }
                    override fun onSemanticsChange(owner: SemanticsOwner) = Unit
                    override fun onLayoutChange(owner: SemanticsOwner, nodeId: Int) = Unit
                }
            },
            invalidate = {},
        )
        fun render() {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            scene.render(canvas, System.nanoTime())
        }
        fun pixels() = bitmap.toPixelMap()

        fun savePng(file: File) {
            file.parentFile.mkdirs()
            Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                val pixels = ImageIO.read(ByteArrayInputStream(requireNotNull(image.encodeToData()).bytes))
                ImageIO.write(pixels.getSubimage(0, 0, width, 680), "png", file)
            }
        }
        fun pointer(type: PointerEventType, point: Offset, pressed: Boolean) {
            scene.sendPointerEvent(
                type,
                point,
                buttons = PointerButtons(isPrimaryPressed = pressed),
                button = PointerButton.Primary,
            )
        }
        suspend fun wheel(delta: Float, target: String = "manga-actions") {
            delay(350)
            val point = node(target).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, delta))
            repeat(4) {
                render()
                delay(20)
            }
        }
        suspend fun longDrag(from: String, to: String, holdAtEdge: Boolean = false) {
            val start = node(from).boundsInRoot.center
            val end = if (holdAtEdge) {
                val viewport = node("manga-action-viewport").boundsInRoot
                Offset(viewport.right - 2f, viewport.center.y)
            } else {
                node(to).boundsInRoot.center
            }
            pointer(PointerEventType.Press, start, true)
            repeat(42) {
                render()
                delay(16)
            }
            repeat(12) { step ->
                pointer(PointerEventType.Move, start + (end - start) * ((step + 1) / 12f), true)
                render()
                delay(20)
            }
            if (holdAtEdge) {
                repeat(60) {
                    render()
                    delay(20)
                }
            }
            pointer(PointerEventType.Release, end, false)
            repeat(12) {
                render()
                delay(20)
            }
        }
        suspend fun drag(start: Offset, end: Offset) {
            pointer(PointerEventType.Press, start, true)
            repeat(12) { index ->
                pointer(PointerEventType.Move, start + (end - start) * ((index + 1) / 12f), true)
                render()
                delay(16)
            }
            pointer(PointerEventType.Release, end, false)
            repeat(22) {
                render()
                delay(16)
            }
        }
        fun nodes(): List<SemanticsNode> = owners.flatMap { flatten(it.rootSemanticsNode) }
            .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        fun node(value: String): SemanticsNode = nodes().first { tag(it, value) }
        suspend fun clickTag(tag: String) {
            assertTrue(node(tag).config[SemanticsActions.OnClick].action!!.invoke())
            repeat(3) {
                render()
                delay(20)
            }
        }
        suspend fun clickLabel(label: String) {
            val node = nodes().first { label in labels(it) && it.config.contains(SemanticsActions.OnClick) }
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
            repeat(3) {
                render()
                delay(20)
            }
        }
        suspend fun moveAction(id: String, step: Int) {
            val action = node("manga-action-order-$id").config[SemanticsActions.CustomActions][if (step < 0) 0 else 1]
            assertTrue(action.action.invoke())
            repeat(3) {
                render()
                delay(20)
            }
        }
        suspend fun openCustomization() {
            assertTrue(node("manga-actions").config[SemanticsActions.CustomActions].first().action.invoke())
            repeat(3) {
                render()
                delay(20)
            }
        }
        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun labels(node: SemanticsNode): List<String> =
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
        fun tag(node: SemanticsNode, value: String): Boolean =
            node.config.getOrElse(SemanticsProperties.TestTag) { "" } == value
    }
}
