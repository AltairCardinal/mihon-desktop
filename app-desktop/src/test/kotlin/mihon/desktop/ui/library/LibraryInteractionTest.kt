package mihon.desktop.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import coil3.EventListener
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.sun.nio.file.ExtendedOpenOption
import eu.kanade.domain.ui.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.data.sync.journal.SyncLocalJournal
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.DesktopCoverUpdater
import mihon.desktop.domain.DesktopCustomCoverStore
import mihon.desktop.domain.LibraryUpdateScheduler
import mihon.desktop.domain.SetExcludedScanlators
import mihon.desktop.image.DesktopSourceImage
import mihon.desktop.image.createDesktopImageLoader
import mihon.desktop.library.LibraryScreenModelFactory
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.ui.theme.DesktopTheme
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.task.TaskState
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import coil3.PlatformContext as CoilPlatformContext

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
@Isolated
class LibraryInteractionTest {
    @Test
    fun `source selection frame alpha and list background use actual light dark cover pixels`(
        @TempDir root: File,
    ) = runBlocking {
        withLibrary(root, systemTheme = SystemTheme.Dark) { scene, _, preferences, _, dependencies ->
            val manga = seed(1).single()
            assertTrue(dependencies.coverUpdater(manga.id, png(0xFFFF0000.toInt())) is TaskState.Success)
            preferences.unreadBadge().set(false)
            preferences.downloadBadge().set(false)
            preferences.localBadge().set(false)
            preferences.showContinueReadingButton().set(false)
            val app = Injekt.get<mihon.desktop.settings.DesktopAppPreferences>()
            app.appTheme.set(eu.kanade.domain.ui.model.AppTheme.DEFAULT)
            val screenshots = mutableListOf<BufferedImage>()
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                app.themeMode.set(theme)
                val secondary = if (theme == ThemeMode.LIGHT) 0xFF0058CA.toInt() else 0xFFB0C6FF.toInt()
                for (mode in LibraryDisplayMode.values) {
                    preferences.displayMode().set(mode)
                    render(scene)
                    assertCover(scene, manga.title, 0xFFFF0000.toInt())
                    val beforeCover = coverBounds(scene, manga.title)
                    val beforeCard = nodes(scene).first {
                        it.config.contains(SemanticsActions.OnLongClick)
                    }.boundsInRoot
                    val before = scene.render(System.nanoTime()).toComposeImageBitmap().asSkiaBitmap()
                    val unselectedBackground = before.getColor(
                        (beforeCard.right - 12).toInt(),
                        beforeCard.center.y.toInt(),
                    )
                    mouseClick(scene, manga.title, PointerKeyboardModifiers(isCtrlPressed = true))
                    // Observe resting selection colors without the pointer hover state layer.
                    scene.sendPointerEvent(PointerEventType.Move, Offset(1100f, 500f))
                    repeat(4) { render(scene) }
                    val cover = coverBounds(scene, manga.title)
                    val card = nodes(scene).first { it.config.contains(SemanticsActions.OnLongClick) }.boundsInRoot
                    val pixels = scene.render(System.nanoTime()).toComposeImageBitmap().asSkiaBitmap()
                    if (mode == LibraryDisplayMode.List) {
                        assertEquals(48f, cover.width)
                        assertEquals(48f, cover.height)
                        assertEquals(beforeCover, cover, "selection must not resize or fade the list cover")
                        assertEquals(
                            0xFFFF0000.toInt(),
                            pixels.getColor((cover.left + 2).toInt(), (cover.top + 2).toInt()),
                        )
                        assertPixelNear(
                            blend(secondary, unselectedBackground, if (theme == ThemeMode.LIGHT) 0.22f else 0.16f),
                            pixels.getColor((card.right - 12).toInt(), card.center.y.toInt()),
                            "$theme list uses SOURCE secondary selection alpha over its real background",
                        )
                    } else {
                        assertEquals(4f, cover.left - card.left, "SOURCE selection inner padding is 4dp")
                        assertEquals(4f, cover.top - card.top)
                        assertEquals(secondary, pixels.getColor(card.center.x.toInt(), card.top.toInt() + 1))
                        assertPixelNear(
                            blend(0xFFFF0000.toInt(), secondary, 0.76f),
                            pixels.getColor(cover.center.x.toInt(), cover.top.toInt()),
                            "$theme $mode must draw the real source image with SOURCE alpha .76",
                        )
                        assertEquals(
                            (cover.width / 0.7f).roundToInt().toFloat(),
                            cover.height,
                            "7:10 uses actual pixel-rounded constraints",
                        )
                    }
                    screenshots += ImageIO.read(
                        java.io.ByteArrayInputStream(
                            requireNotNull(scene.render(System.nanoTime()).encodeToData()).bytes,
                        ),
                    )
                    clickLabel(scene, MR.strings.desktop_ui_clear_selection.localized())
                    render(scene)
                }
            }
            val sheet = BufferedImage(2400, 900, BufferedImage.TYPE_INT_RGB)
            val graphics = sheet.createGraphics()
            try {
                screenshots.forEachIndexed { index, image ->
                    graphics.drawImage(image, (index % 4) * 600, (index / 4) * 450, 600, 450, null)
                }
            } finally {
                graphics.dispose()
            }
            val directory = File(System.getenv("MIHON_RI06_VISUAL_DIR") ?: File(root, "visual").absolutePath)
            directory.mkdirs()
            ImageIO.write(sheet, "png", File(directory, "ri06-selection-layouts.png"))
        }
    }

    private fun blend(foreground: Int, background: Int, alpha: Float): Int =
        (0..2).fold(0xFF000000.toInt()) { result, channel ->
            val shift = channel * 8
            val sourceChannel = (foreground ushr shift) and 255
            val backgroundChannel = (background ushr shift) and 255
            val value = (sourceChannel * alpha + backgroundChannel * (1 - alpha)).roundToInt()
            result or (value shl shift)
        }

    private fun assertPixelNear(expected: Int, actual: Int, message: String) {
        for (shift in listOf(0, 8, 16)) {
            assertTrue(
                kotlin.math.abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255)) <= 1,
                "$message expected=${expected.toUInt().toString(16)} actual=${actual.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun `real four layout mouse modifiers select shrink append and never navigate while selecting`(
        @TempDir root: File,
    ) = runBlocking {
        withLibrary(root) { scene, navigator, preferences, _, _ ->
            val manga = seed(5)
            preferences.showContinueReadingButton().set(false)
            val ctrl = PointerKeyboardModifiers(isCtrlPressed = true)
            val shift = PointerKeyboardModifiers(isShiftPressed = true)
            val both = PointerKeyboardModifiers(isCtrlPressed = true, isShiftPressed = true)
            for (mode in LibraryDisplayMode.values) {
                preferences.displayMode().set(mode)
                render(scene)
                mouseClick(scene, manga[1].title)
                render(scene)
                assertTrue(navigator().lastItem is MangaDetailScreen, "$mode ordinary click opens detail")
                navigator().pop()
                render(scene)

                mouseClick(scene, manga[1].title, ctrl)
                render(scene)
                assertTrue(navigator().lastItem is LibraryRootScreen, "$mode Ctrl must select without navigating")
                assertSelection(scene, setOf(manga[1].title))
                mouseClick(scene, manga[4].title, shift)
                render(scene)
                assertSelection(scene, manga.subList(1, 5).mapTo(mutableSetOf()) { it.title })
                mouseClick(scene, manga[2].title, shift)
                render(scene)
                assertSelection(scene, setOf(manga[1].title, manga[2].title))
                mouseClick(scene, manga[4].title, ctrl)
                render(scene)
                mouseClick(scene, manga[3].title, both)
                render(scene)
                assertSelection(scene, manga.subList(1, 5).mapTo(mutableSetOf()) { it.title })
                mouseClick(scene, manga[0].title)
                render(scene)
                assertSelection(scene, manga.mapTo(mutableSetOf()) { it.title })
                manga.forEach { item ->
                    mouseClick(scene, item.title)
                    render(scene)
                }
                assertTrue(navigator().lastItem is LibraryRootScreen, "last deselection never opens detail")
                assertFalse(MR.strings.desktop_ui_clear_selection.localized() in labels(scene))
            }
        }
    }

    @Test
    fun `real alt secondary and long presses do not navigate or leak a release click`(
        @TempDir root: File,
    ) = runBlocking {
        withLibrary(root) { scene, navigator, preferences, model, _ ->
            val manga = seed(4)
            preferences.showContinueReadingButton().set(false)
            for (mode in LibraryDisplayMode.values) {
                preferences.displayMode().set(mode)
                render(scene)
                mouseClick(scene, manga[0].title, PointerKeyboardModifiers(isAltPressed = true))
                render(scene)
                assertTrue(navigator().lastItem is LibraryRootScreen, "$mode Alt must not navigate")
                assertFalse(MR.strings.desktop_ui_clear_selection.localized() in labels(scene))
                mouseClick(scene, manga[0].title, button = PointerButton.Secondary)
                render(scene)
                assertTrue(navigator().lastItem is LibraryRootScreen)
                model().setContextMenuManga(null)
                render(scene)
                mouseClick(scene, manga[1].title, holdMillis = 700)
                render(scene)
                assertTrue(navigator().lastItem is LibraryRootScreen, "long-press release must not open detail")
                assertSelection(scene, setOf(manga[1].title))
                mouseClick(scene, manga[3].title, holdMillis = 700)
                render(scene)
                assertSelection(scene, manga.subList(1, 4).mapTo(mutableSetOf()) { it.title })
                clickLabel(scene, MR.strings.desktop_ui_clear_selection.localized())
                render(scene)
            }
        }
    }

    @Test
    fun `partial real cover writes keep old bytes database version and leave no staging files`(
        @TempDir root: File,
    ) = runBlocking {
        withLibrary(root) { scene, _, _, _, dependencies ->
            val manga = seed(1).single()
            val oldBytes = png(0xFF00FF00.toInt())
            assertTrue(dependencies.coverUpdater(manga.id, oldBytes) is TaskState.Success)
            val repository = Injekt.get<MangaRepository>()
            val oldVersion = repository.getMangaById(manga.id).coverLastModified
            val oldFile = dependencies.customCoverStore.getCustomCoverFile(manga.id)
            val faultStore = DesktopCustomCoverStore(oldFile.parentFile) { file, bytes ->
                file.writeBytes(bytes.copyOfRange(0, bytes.size / 2))
                throw java.io.IOException("disk stopped after a partial write")
            }
            val result = DesktopCoverUpdater(faultStore, repository)(manga.id, png(0xFFFF0000.toInt()))
            assertTrue(result is TaskState.Failure)
            assertTrue(
                oldBytes.contentEquals(oldFile.readBytes()),
                "a mid-write failure must retain the original real cover",
            )
            assertEquals(oldVersion, repository.getMangaById(manga.id).coverLastModified)
            assertEquals(listOf(manga.id.toString()), oldFile.parentFile.listFiles()!!.map { it.name })
            render(scene)
            assertCover(scene, manga.title, 0xFF00FF00.toInt())
        }
    }

    @Test
    fun `four layouts consume source unread and download badge roles in actual pixels`(
        @TempDir root: File,
    ) = runBlocking {
        withLibrary(root) { scene, _, _, _, _ ->
            val manga = seed(1).single()
            Injekt.get<ChapterRepository>().addAll(
                (0 until 88).map {
                    Chapter.create().copy(
                        mangaId = manga.id,
                        name = "Chapter $it",
                        url = "/chapter-$it",
                        sourceOrder = it.toLong(),
                    )
                },
            )
            val item = Injekt.get<MangaRepository>().getLibraryManga().single()
            val scheme = lightColorScheme(
                secondary = Color(0xFFC8B900),
                onSecondary = Color(0xFF003300),
                tertiary = Color(0xFF00C8C8),
                onTertiary = Color(0xFF330000),
            )
            for (mode in LibraryDisplayMode.values) {
                scene.setContent {
                    MaterialTheme(colorScheme = scheme) {
                        if (mode == LibraryDisplayMode.List) {
                            LibraryList(
                                items = listOf(item),
                                selectionState = LibrarySelectionState(),
                                downloadCountsByManga = mapOf(item.id to 33),
                                showDownloadBadge = true,
                                onContextMenu = {},
                                onItemClick = { _, _ -> },
                                onItemLongClick = {},
                            )
                        } else {
                            LibraryGrid(
                                items = listOf(item), minCardWidth = 120.dp,
                                comfortable = mode == LibraryDisplayMode.ComfortableGrid,
                                coverOnly = mode == LibraryDisplayMode.CoverOnlyGrid,
                                selectionState = LibrarySelectionState(), downloadCountsByManga = mapOf(item.id to 33),
                                onContextMenu = {
                                }, onItemClick = { _, _ -> }, onItemLongClick = {}, onContinueReading = {},
                            )
                        }
                    }
                }
                render(scene)
                for ((text, background, foreground) in listOf(
                    Triple("88", scheme.secondary, scheme.onSecondary),
                    Triple("33", scheme.tertiary, scheme.onTertiary),
                )) {
                    val label = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }.single {
                        it.config.contains(SemanticsProperties.Text) &&
                            it.config[SemanticsProperties.Text].map { it.text } == listOf(text)
                    }.boundsInRoot
                    val bitmap = scene.render(System.nanoTime()).toComposeImageBitmap().asSkiaBitmap()
                    assertEquals(
                        background.toArgb(),
                        bitmap.getColor((label.left - 2).toInt(), label.center.y.toInt()),
                        "$mode $text background role",
                    )
                    val glyphPixels = (label.left.toInt() until label.right.toInt()).flatMap { x ->
                        (label.top.toInt() until label.bottom.toInt()).map { y -> bitmap.getColor(x, y) }
                    }
                    assertTrue(foreground.toArgb() in glyphPixels, "$mode $text foreground role")
                }
            }
        }
    }

    @Test
    fun `native light and dark layouts preserve grid ratio and independent list covers`(
        @TempDir root: File,
    ) = runBlocking {
        withLibrary(root) { scene, _, preferences, _, dependencies ->
            val mangas = seed(30)
            mangas.forEach { dependencies.coverUpdater(it.id, png(0xFF00FF00.toInt())) }
            val sheet = BufferedImage(2400, 900, BufferedImage.TYPE_INT_RGB)
            val graphics = sheet.createGraphics()
            try {
                for ((row, theme) in listOf(ThemeMode.LIGHT, ThemeMode.DARK).withIndex()) {
                    dependencies.appPreferences.themeMode.set(theme)
                    for ((column, mode) in LibraryDisplayMode.values.withIndex()) {
                        preferences.displayMode().set(mode)
                        render(scene)
                        assertCover(scene, mangas.first().title, 0xFF00FF00.toInt())
                        val bounds = coverBounds(scene, mangas.first().title)
                        if (mode == LibraryDisplayMode.List) {
                            assertEquals(48f, bounds.width)
                            assertEquals(48f, bounds.height)
                        } else {
                            assertEquals((bounds.width / 0.7f).roundToInt().toFloat(), bounds.height)
                        }
                        val bytes = requireNotNull(scene.render(System.nanoTime()).encodeToData()).bytes
                        val image = ImageIO.read(java.io.ByteArrayInputStream(bytes))
                        graphics.drawImage(image, column * 600, row * 450, 600, 450, null)
                    }
                }
            } finally {
                graphics.dispose()
            }
            val directory = File(System.getenv("MIHON_RI04_VISUAL_DIR") ?: File(root, "visual").absolutePath)
            directory.mkdirs()
            ImageIO.write(sheet, "png", File(directory, "ri04-library-layouts.png"))
        }
    }

    @Test
    @OptIn(coil3.annotation.DelicateCoilApi::class)
    fun `all layouts draw custom priority and latest version using the production Coil request`(
        @TempDir root: File,
    ) = runBlocking {
        val server = MockWebServer()
        var sourceColor = 0xFFFF0000.toInt()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder()
                .setHeader("Content-Type", "image/png").body(Buffer().write(png(sourceColor))).build()
        }
        server.start()
        val original = SingletonImageLoader.get(CoilPlatformContext.INSTANCE)
        val requests = java.util.concurrent.CopyOnWriteArrayList<ImageRequest>()
        val client = OkHttpClient()
        val loader = createDesktopImageLoader(
            CoilPlatformContext.INSTANCE,
            client,
            {
                client
            },
            { Headers.headersOf("Referer", "https://cover.example/") },
        ).newBuilder().eventListenerFactory { request ->
            requests += request
            EventListener.NONE
        }.build()
        SingletonImageLoader.setUnsafe(loader)
        try {
            withLibrary(root) { scene, _, preferences, _, dependencies ->
                val manga = seed(1).single()
                val repository = Injekt.get<MangaRepository>()
                val sourceUrl = server.url("/cover.png").toString()
                repository.update(MangaUpdate(manga.id, thumbnailUrl = sourceUrl, coverLastModified = 10))
                render(scene)
                assertCover(scene, manga.title, sourceColor)
                val request = requests.last { it.data is DesktopSourceImage }
                assertEquals(mangaCoverRequestKey(manga.id, sourceUrl, 10), request.memoryCacheKey)
                assertEquals(request.memoryCacheKey, request.diskCacheKey)
                assertEquals("https://cover.example/", server.takeRequest().headers["Referer"])
                val replacement = File(root, "replacement.png").apply { writeBytes(png(0xFF00FF00.toInt())) }
                assertTrue(dependencies.coverUpdater(manga.id, replacement.readBytes()) is TaskState.Success)
                for (mode in LibraryDisplayMode.values) {
                    preferences.displayMode().set(mode)
                    render(scene)
                    assertCover(scene, manga.title, 0xFF00FF00.toInt())
                    val customRequest = requests.last { it.data is String }
                    assertEquals(
                        dependencies.customCoverStore.getCustomCoverFile(manga.id).absolutePath,
                        customRequest.data,
                    )
                    assertEquals(customRequest.memoryCacheKey, customRequest.diskCacheKey)
                    val bounds = coverBounds(scene, manga.title)
                    if (mode == LibraryDisplayMode.List) {
                        assertEquals(48f, bounds.width)
                        assertEquals(48f, bounds.height)
                    } else {
                        assertEquals((bounds.width / 0.7f).roundToInt().toFloat(), bounds.height)
                    }
                }
                sourceColor = 0xFF0000FF.toInt()
                repository.update(MangaUpdate(manga.id, coverLastModified = 20))
                render(scene)
                assertCover(scene, manga.title, 0xFF00FF00.toInt())
                assertTrue(dependencies.coverUpdater.delete(manga.id) is TaskState.Success)
                render(scene)
                assertCover(scene, manga.title, sourceColor)
                sourceColor = 0xFFFFFF00.toInt()
                repository.update(MangaUpdate(manga.id, coverLastModified = 30))
                render(scene)
                assertCover(scene, manga.title, sourceColor)
                val latest = requests.last { it.data is DesktopSourceImage }
                assertEquals(mangaCoverRequestKey(manga.id, sourceUrl, 30), latest.memoryCacheKey)
                assertTrue(dependencies.coverUpdater(manga.id, png(0xFF00FFFF.toInt())) is TaskState.Success)
            }
            withLibrary(root) { scene, _, preferences, _, _ ->
                for (mode in LibraryDisplayMode.values) {
                    preferences.displayMode().set(mode)
                    render(scene)
                    assertCover(scene, "Work 0000", 0xFF00FFFF.toInt())
                }
            }
        } finally {
            SingletonImageLoader.setUnsafe(original)
            loader.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.close()
        }
    }

    @Test
    fun `real disk failures keep cover bytes version and visible detail feedback and permit retry`(
        @TempDir root: File,
    ) = runBlocking {
        withLibrary(root) { scene, navigator, _, _, dependencies ->
            val manga = seed(1).single()
            val oldBytes = png(0xFF00FF00.toInt())
            assertTrue(dependencies.coverUpdater(manga.id, oldBytes) is TaskState.Success)
            val oldVersion = Injekt.get<MangaRepository>().getMangaById(manga.id).coverLastModified
            val file = dependencies.customCoverStore.getCustomCoverFile(manga.id)
            val restoreAccess = denyCoverWrites(file)
            try {
                assertTrue(dependencies.coverUpdater(manga.id, png(0xFFFF0000.toInt())) is TaskState.Failure)
                assertTrue(oldBytes.contentEquals(file.readBytes()))
                assertEquals(oldVersion, Injekt.get<MangaRepository>().getMangaById(manga.id).coverLastModified)
                render(scene)
                click(
                    nodes(scene).first {
                        it.config.contains(SemanticsActions.OnLongClick) && manga.title in copy(it)
                    },
                )
                render(scene)
                assertTrue(navigator().lastItem is MangaDetailScreen)
                clickLabel(scene, MR.strings.action_edit_cover.localized())
                render(scene)
                clickLabel(scene, MR.strings.desktop_ui_delete_cover.localized())
                render(scene)
                assertTrue(
                    "Unable to delete custom cover" in labels(scene),
                    "the real filesystem failure must appear in the mounted detail",
                )
                assertTrue(oldBytes.contentEquals(file.readBytes()))
                assertEquals(oldVersion, Injekt.get<MangaRepository>().getMangaById(manga.id).coverLastModified)
            } finally {
                restoreAccess()
            }
            clickLabel(scene, MR.strings.action_edit_cover.localized())
            render(scene)
            clickLabel(scene, MR.strings.desktop_ui_delete_cover.localized())
            render(scene)
            assertFalse(file.exists())
            assertTrue(MR.strings.desktop_ui_cover_deleted.localized() in labels(scene))
            navigator().pop()
            render(scene)
            assertTrue(navigator().lastItem is LibraryRootScreen)
        }
    }

    @Test
    fun `real sync targets carry a frozen page and filtered external stale and read targets cannot fake continue`(
        @TempDir root: File,
    ) = runBlocking {
        withLibrary(root) { scene, navigator, preferences, model, _ ->
            SyncLocalJournal(Injekt.get()).connect(
                "library-resume",
                1,
                SyncRepository("owner", "sync", "sync"),
                "reader",
                1,
            )
            val manga = seed(1).single()
            val repository = Injekt.get<ChapterRepository>()
            Injekt.get<MangaRepository>().update(
                MangaUpdate(
                    manga.id,
                    chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
                ),
            )
            val chapters = repository.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        name = "First",
                        url = "/first",
                        sourceOrder = 0,
                        chapterNumber = 1.0,
                        lastPageRead = 2,
                        scanlator = "Excluded",
                    ),
                    Chapter.create().copy(
                        mangaId = manga.id,
                        name = "Second",
                        url = "/second",
                        sourceOrder = 1,
                        chapterNumber = 2.0,
                        lastPageRead = 4,
                    ),
                    Chapter.create().copy(
                        mangaId = manga.id,
                        name = "External",
                        url = "external:https://example.com/chapter",
                        sourceOrder = 2,
                        chapterNumber = 3.0,
                    ),
                ),
            )
            val recorder = Injekt.get<RecordReadingProgress>()
            suspend fun resume(id: Long) = recorder.await(
                ReadingProgressEvent(
                    id,
                    7,
                    20,
                    java.util.Date(),
                    0,
                    syncContext = SyncMutationContext.User,
                ),
            )
            resume(chapters[0].id)
            preferences.unreadBadge().set(false)
            preferences.showContinueReadingButton().set(true)
            for (mode in LibraryDisplayMode.values) {
                preferences.displayMode().set(mode)
                render(scene)
                clickLabel(scene, MR.strings.desktop_ui_continue_reading.localized())
                render(scene)
                val reader = navigator().lastItem as DesktopReaderScreen
                assertEquals(chapters[0].id, reader.chapterId)
                assertEquals(7, reader.initialPage)
                assertTrue(reader.initialContext().resumeSnapshot?.heads?.isNotEmpty() == true)
                assertEquals(listOf(chapters[0].id, chapters[1].id), reader.chapters.map { it.id })
                navigator().pop()
                render(scene)
            }
            Injekt.get<SetExcludedScanlators>().await(manga.id, setOf("Excluded"))
            render(scene)
            clickLabel(scene, MR.strings.desktop_ui_continue_reading.localized())
            render(scene)
            assertEquals(chapters[1].id, (navigator().lastItem as DesktopReaderScreen).chapterId)
            assertEquals(4, (navigator().lastItem as DesktopReaderScreen).initialPage)
            assertEquals(null, (navigator().lastItem as DesktopReaderScreen).initialContext().resumeSnapshot)
            navigator().pop()
            resume(chapters[2].id)
            render(scene)
            clickLabel(scene, MR.strings.desktop_ui_continue_reading.localized())
            render(scene)
            assertEquals(chapters[1].id, (navigator().lastItem as DesktopReaderScreen).chapterId)
            navigator().pop()
            repository.removeChaptersWithIds(listOf(chapters[2].id))
            render(scene)
            clickLabel(scene, MR.strings.desktop_ui_continue_reading.localized())
            render(scene)
            assertEquals(chapters[1].id, (navigator().lastItem as DesktopReaderScreen).chapterId)
            navigator().pop()
            repository.update(ChapterUpdate(chapters[1].id, read = true))
            render(scene)
            assertFalse(
                MR.strings.desktop_ui_continue_reading.localized() in labels(scene),
                "only excluded unread chapters cannot provide a real target",
            )
            assertFalse(model().state.value.isUpdating)
        }
    }

    @Test
    fun `category and detail navigation restore manga anchors through reorder layout and filtering`(
        @TempDir root: File,
    ) = runBlocking {
        withLibrary(root) { scene, navigator, preferences, model, _ ->
            val mangas = seed(300)
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Category A", 0, 0))
            categories.insert(Category(0, "Category B", 1, 0))
            val custom = categories.getAll().filterNot(Category::isSystemCategory)
            val repository = Injekt.get<MangaRepository>()
            repository.updateMembershipsAtomically(
                mangas.mapIndexed { index, manga ->
                    LibraryMembershipUpdate(manga.id, true, 1, listOf(custom[if (index < 200) 0 else 1].id))
                },
            )
            preferences.displayMode().set(LibraryDisplayMode.List)
            render(scene)
            scrollTo(scene, 130)
            render(scene)
            assertTrue("Work 0130" in labels(scene))
            val lazy = nodes(scene).first { it.config.contains(SemanticsActions.ScrollToIndex) }
            val rangeBefore = lazy.config[SemanticsProperties.VerticalScrollAxisRange].value()
            val boundsBefore = nodes(scene).first { "Work 0130" in copy(it) }.boundsInRoot
            val accepted = requireNotNull(lazy.config[SemanticsActions.ScrollBy].action).invoke(0f, 29f)
            repeat(3) { render(scene) }
            val rangeAfter = nodes(scene).first {
                it.config.contains(SemanticsActions.ScrollToIndex)
            }.config[SemanticsProperties.VerticalScrollAxisRange].value()
            val boundsAfter = nodes(scene).first { "Work 0130" in copy(it) }.boundsInRoot
            val anchorOffset = requireNotNull(model().browsePosition(custom[0].id)).offset
            assertTrue(
                anchorOffset > 0,
                "nonzero Lazy offset: accepted=$accepted, axis=$rangeBefore->$rangeAfter, " +
                    "bounds=$boundsBefore->$boundsAfter, anchor=${model().browsePosition(custom[0].id)}",
            )
            assertEquals(29, anchorOffset)
            assertTrue(rangeAfter > rangeBefore)
            assertEquals(boundsBefore.bottom - 29f, boundsAfter.bottom)
            clickLabel(scene, "Category B")
            render(scene)
            scrollTo(scene, 20)
            render(scene)
            assertTrue("Work 0220" in labels(scene))
            clickLabel(scene, "Category A")
            render(scene)
            assertTrue("Work 0130" in labels(scene), "category A must restore its own manga anchor")
            assertEquals(anchorOffset, model().browsePosition(custom[0].id)?.offset)
            click(nodes(scene).first { it.config.contains(SemanticsActions.OnLongClick) && "Work 0130" in copy(it) })
            render(scene)
            assertTrue(navigator().lastItem is MangaDetailScreen)
            navigator().pop()
            render(scene)
            assertTrue("Work 0130" in labels(scene), "detail return must restore the same entity")
            assertEquals(anchorOffset, model().browsePosition(custom[0].id)?.offset)
            for (mode in LibraryDisplayMode.values) {
                preferences.displayMode().set(mode)
                render(scene)
                assertTrue("Work 0130" in labels(scene), "$mode must anchor by entity rather than old row index")
            }
            preferences.portraitColumns().set(3)
            preferences.landscapeColumns().set(4)
            render(scene)
            assertTrue("Work 0130" in labels(scene), "column changes must preserve the manga anchor")
            scene.constraints = Constraints.fixed(680, 850)
            render(scene)
            assertTrue("Work 0130" in labels(scene), "resize must preserve the manga anchor")
            clickLabel(scene, MR.strings.action_filter.localized())
            render(scene)
            clickLabel(scene, MR.strings.action_sort.localized())
            render(scene)
            clickLabel(scene, MR.strings.action_sort_alpha.localized() + " ↑")
            render(scene)
            clickLabel(scene, MR.strings.action_close.localized())
            render(scene)
            assertTrue("Work 0130" in labels(scene), "reversed sort must locate the manga rather than the old index")
            categories.updatePartial(CategoryUpdate(custom[0].id, order = 1))
            categories.updatePartial(CategoryUpdate(custom[1].id, order = 0))
            render(scene)
            assertEquals(custom[0].id, model().state.value.categories[model().state.value.selectedCategoryIndex].id)
            assertTrue("Work 0130" in labels(scene), "category reorder cannot change the selected identity")
            repository.updateMembershipsAtomically(
                listOf(LibraryMembershipUpdate(mangas[130].id, false, 1, emptyList())),
            )
            render(scene)
            assertFalse("Work 0130" in labels(scene), "a removed anchor cannot remain visible")
            assertTrue(
                "Work 0129" in labels(scene) || "Work 0131" in labels(scene),
                "removed anchor must fall back near its last valid position",
            )
            clickLabel(scene, MR.strings.action_search.localized())
            render(scene)
            requireNotNull(
                nodes(scene).single {
                    it.config.contains(SemanticsActions.SetText)
                }.config[SemanticsActions.SetText].action,
            )
                .invoke(AnnotatedString("Work 0199"))
            render(scene)
            assertTrue("Work 0199" in labels(scene), "missing anchor must fall back inside the filtered collection")
            categories.delete(custom[0].id)
            render(scene)
            assertTrue(model().state.value.selectedCategoryIndex in model().state.value.categories.indices)
            assertTrue(MR.strings.no_results_found.localized() in labels(scene) || "Work 0199" in labels(scene))
            assertFalse(model().state.value.isUpdating)
        }
    }

    @Test
    fun `four layouts drag a thousand real works to the end and back without refreshing`(
        @TempDir root: File,
    ) = runBlocking {
        withLibrary(root) { scene, _, preferences, model, _ ->
            seed(1_000)
            for (mode in LibraryDisplayMode.values) {
                preferences.displayMode().set(mode)
                render(scene)
                assertTrue("Work 0000" in labels(scene), mode.toString())
                val content = nodes(scene).first { it.config.contains(SemanticsActions.ScrollToIndex) }.boundsInRoot
                drag(scene, Offset(1196f, content.top + 5f), Offset(1196f, content.bottom - 5f))
                render(scene)
                assertTrue("Work 0999" in labels(scene), "$mode scrollbar must reach a real last entity")
                drag(scene, Offset(1196f, content.bottom - 5f), Offset(1196f, content.top + 5f))
                render(scene)
                assertTrue("Work 0000" in labels(scene), "$mode scrollbar must return to the first entity")
                assertFalse(model().state.value.isUpdating)
                assertEquals(
                    null,
                    Injekt.get<LibraryUpdateScheduler>().taskSnapshot(),
                    "dragging cannot create a refresh task",
                )
            }
        }
    }

    @Test
    fun `hidden unread badge retains real reader buttons in all four layouts and selection never opens reader`(
        @TempDir root: File,
    ) = runBlocking {
        withLibrary(root) { scene, navigator, preferences, _, _ ->
            val manga = seed(1).single()
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        name = "Unread",
                        url = "/unread",
                        sourceOrder = 0,
                        lastPageRead = 3,
                    ),
                ),
            ).single()
            preferences.unreadBadge().set(false)
            preferences.showContinueReadingButton().set(true)
            for (mode in LibraryDisplayMode.values) {
                preferences.displayMode().set(mode)
                render(scene)
                val button = nodes(scene).firstOrNull {
                    it.config.contains(SemanticsActions.OnClick) &&
                        MR.strings.desktop_ui_continue_reading.localized() in copy(it)
                }
                assertTrue(button != null, "$mode must determine continue eligibility independently of badges")
                click(button!!)
                render(scene)
                val reader = navigator().lastItem as DesktopReaderScreen
                assertEquals(chapter.id, reader.chapterId)
                assertEquals(3, reader.initialPage)
                assertEquals(0, reader.currentChapterIndex)
                assertEquals(listOf(chapter.id), reader.chapters.map { it.id })
                navigator().pop()
                render(scene)
                val row = nodes(scene).first {
                    it.config.contains(SemanticsActions.OnLongClick) &&
                        manga.title in copy(it)
                }
                requireNotNull(row.config[SemanticsActions.OnLongClick].action).invoke()
                render(scene)
                click(
                    nodes(scene).first {
                        it.config.contains(SemanticsActions.OnClick) &&
                            MR.strings.desktop_ui_continue_reading.localized() in copy(it)
                    },
                )
                render(scene)
                assertTrue(navigator().lastItem is LibraryRootScreen, "selection must consume the small button")
                assertFalse(MR.strings.action_close.localized() in labels(scene))
            }
        }
    }

    @Test
    fun `single custom category is visible while sole default and explicit hidden tabs stay hidden`(
        @TempDir root: File,
    ) = runBlocking {
        withLibrary(root) { scene, _, preferences, _, _ ->
            val manga = seed(1).single()
            render(scene)
            assertFalse(
                nodes(scene).any {
                    it.config.contains(SemanticsProperties.Selected) &&
                        MR.strings.label_default.localized() in copy(it)
                },
            )
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Only custom", 0, 0))
            val custom = categories.getAll().single { !it.isSystemCategory }
            Injekt.get<MangaRepository>().setMangaCategories(manga.id, listOf(custom.id))
            render(scene)
            assertTrue(
                nodes(scene).any {
                    it.config.contains(SemanticsProperties.Selected) && "Only custom" in copy(it)
                },
            )
            preferences.categoryTabs().set(false)
            render(scene)
            assertFalse(
                nodes(scene).any {
                    it.config.contains(SemanticsProperties.Selected) && "Only custom" in copy(it)
                },
            )
            click(
                nodes(scene).first {
                    it.config.contains(SemanticsActions.OnClick) &&
                        MR.strings.action_search.localized() in copy(it)
                },
            )
            render(scene)
            requireNotNull(
                nodes(scene).single {
                    it.config.contains(SemanticsActions.SetText)
                }.config[SemanticsActions.SetText].action,
            )
                .invoke(AnnotatedString("Work"))
            render(scene)
            assertTrue(
                nodes(scene).any {
                    it.config.contains(SemanticsProperties.Selected) &&
                        "Only custom (1)" in copy(it)
                },
            )
        }
    }

    private suspend fun withLibrary(
        root: File,
        systemTheme: SystemTheme? = null,
        block: suspend (
            ImageComposeScene,
            () -> Navigator,
            LibraryPreferences,
            () -> LibraryScreenModel,
            DesktopUiDependencies,
        ) -> Unit,
    ) {
        val node = Preferences.userRoot().node("mihon-tests/library-interaction-${UUID.randomUUID()}")
        val context = initDesktopDIForTest(root, DesktopPreferenceStore(node), startDownloadWorker = false)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dependencies = DesktopUiDependencies.fromInjekt()
        val scene = ImageComposeScene(1200, 900, coroutineContext = Dispatchers.Unconfined) {}
        lateinit var navigator: Navigator
        lateinit var model: LibraryScreenModel
        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalSystemTheme provides (systemTheme ?: LocalSystemTheme.current),
                ) {
                    ProvideLibraryScreenModelFactory({ LibraryScreenModelFactory.create().also { model = it } }) {
                        DesktopTheme {
                            Navigator(LibraryRootScreen()) {
                                navigator = it
                                if (it.lastItem !is DesktopReaderScreen) CurrentScreen()
                            }
                        }
                    }
                }
            }
            render(scene)
            block(scene, { navigator }, Injekt.get(), { model }, dependencies)
        } finally {
            scene.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
            node.removeNode()
        }
    }

    private suspend fun seed(count: Int): List<Manga> {
        val repository = Injekt.get<MangaRepository>()
        val items = repository.insertNetworkManga(
            (0 until count).map {
                Manga.create().copy(
                    source = 0,
                    url = "/work-$it",
                    title = "Work ${it.toString().padStart(4, '0')}",
                    initialized = true,
                )
            },
        )
        repository.updateMembershipsAtomically(items.map { LibraryMembershipUpdate(it.id, true, 1, emptyList()) })
        return items
    }

    private suspend fun render(scene: ImageComposeScene) {
        repeat(12) {
            scene.render(System.nanoTime())
            delay(15)
        }
    }

    private fun png(color: Int): ByteArray {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        repeat(4) { x -> repeat(4) { y -> image.setRGB(x, y, color) } }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun denyCoverWrites(file: File): () -> Unit {
        val path = file.toPath()
        if (System.getProperty("os.name").startsWith("Windows")) {
            val handle = FileChannel.open(
                path,
                StandardOpenOption.READ,
                ExtendedOpenOption.NOSHARE_WRITE,
                ExtendedOpenOption.NOSHARE_DELETE,
            )
            return { handle.close() }
        }
        val parent = path.parent
        val filePermissions = Files.getPosixFilePermissions(path)
        val parentPermissions = Files.getPosixFilePermissions(parent)
        val writes = setOf(
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE,
        )
        Files.setPosixFilePermissions(path, filePermissions - writes)
        Files.setPosixFilePermissions(parent, parentPermissions - writes)
        return {
            Files.setPosixFilePermissions(parent, parentPermissions)
            Files.setPosixFilePermissions(path, filePermissions)
        }
    }

    private suspend fun assertCover(scene: ImageComposeScene, title: String, expected: Int) {
        var actual = 0
        repeat(40) {
            render(scene)
            val bounds = coverBounds(scene, title)
            actual =
                scene.render(System.nanoTime()).toComposeImageBitmap().asSkiaBitmap().getColor(
                    (bounds.left + bounds.width / 3).toInt(),
                    (
                        bounds.top +
                            bounds.height / 3
                        ).toInt(),
                )
            if (coverPixelMatches(actual, expected)) return
        }
        assertTrue(
            coverPixelMatches(actual, expected),
            "real cover RGB channels for $title: expected=${expected.toUInt().toString(
                16,
            )}, actual=${actual.toUInt().toString(16)}",
        )
    }

    private fun coverBounds(scene: ImageComposeScene, title: String): Rect =
        scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }.filter {
            it.config.contains(SemanticsProperties.ContentDescription) &&
                title in it.config[SemanticsProperties.ContentDescription] &&
                !it.config.contains(SemanticsActions.OnClick)
        }.minBy { it.boundsInRoot.width * it.boundsInRoot.height }
            .boundsInRoot

    // Compact's existing black title gradient darkens the fixture; zero channels remain exactly zero.
    private fun coverPixelMatches(actual: Int, expected: Int) = listOf(0, 8, 16).all { shift ->
        val channel = (actual ushr shift) and 255
        if (((expected ushr shift) and 255) == 0) channel == 0 else channel > 100
    }

    private suspend fun drag(scene: ImageComposeScene, start: Offset, end: Offset) {
        scene.sendPointerEvent(
            PointerEventType.Press,
            start,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        repeat(12) { index ->
            scene.sendPointerEvent(
                PointerEventType.Move,
                start + (end - start) * ((index + 1) / 12f),
                buttons = PointerButtons(isPrimaryPressed = true),
            )
            render(scene)
        }
        scene.sendPointerEvent(PointerEventType.Release, end, button = PointerButton.Primary)
    }

    private fun click(node: SemanticsNode) = requireNotNull(node.config[SemanticsActions.OnClick].action).invoke()

    private suspend fun mouseClick(
        scene: ImageComposeScene,
        title: String,
        modifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
        button: PointerButton = PointerButton.Primary,
        holdMillis: Long = 0,
    ) {
        val target = nodes(scene).first {
            it.config.contains(SemanticsActions.OnLongClick) && title in copy(it)
        }.boundsInRoot
        val point = Offset(target.left + target.width / 3, target.top + target.height / 3)
        scene.sendPointerEvent(
            PointerEventType.Press,
            point,
            buttons = PointerButtons(
                isPrimaryPressed = button == PointerButton.Primary,
                isSecondaryPressed = button == PointerButton.Secondary,
            ),
            keyboardModifiers = modifiers,
            button = button,
        )
        if (holdMillis > 0) {
            delay(holdMillis)
            render(scene)
        }
        scene.sendPointerEvent(PointerEventType.Release, point, keyboardModifiers = modifiers, button = button)
    }

    private fun assertSelection(scene: ImageComposeScene, expectedTitles: Set<String>) {
        val selectedTitles = nodes(scene).filter {
            it.config.getOrElse(SemanticsProperties.Selected) { false } &&
                it.config.contains(SemanticsActions.OnLongClick)
        }.flatMap(::copy).filter { it.startsWith("Work ") }.toSet()
        assertEquals(expectedTitles, selectedTitles, "the real card selection must match the mouse range")
    }
    private fun clickLabel(scene: ImageComposeScene, label: String) = click(
        nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) && label in copy(it)
        },
    )
    private fun scrollTo(scene: ImageComposeScene, index: Int) = requireNotNull(
        nodes(scene).first {
            it.config.contains(SemanticsActions.ScrollToIndex)
        }.config[SemanticsActions.ScrollToIndex].action,
    ).invoke(index)
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun copy(node: SemanticsNode): List<String> =
        (
            if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text].map {
                    it.text
                }
            } else {
                emptyList()
            }
            ) +
            (
                if (node.config.contains(
                        SemanticsProperties.ContentDescription,
                    )
                ) {
                    node.config[SemanticsProperties.ContentDescription]
                } else {
                    emptyList()
                }
                )
    private fun labels(scene: ImageComposeScene) = nodes(scene).flatMap(::copy)
}
