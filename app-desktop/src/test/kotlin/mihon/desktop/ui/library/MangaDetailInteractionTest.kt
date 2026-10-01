package mihon.desktop.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyLocation
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.library.MangaDetailScreenModelFactory
import mihon.desktop.platform.toDesktopNotification
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.ui.theme.DesktopTheme
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.missingChaptersCount
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.UpdateManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

@Isolated
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class MangaDetailInteractionTest {
    @Test
    fun `single downloaded chapter deletion confirms actual refusal and retries only its fixed file`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, chapters ->
            val provider = Injekt.get<mihon.desktop.download.DesktopDownloadProvider>()
            val resolver = Injekt.get<mihon.desktop.download.DesktopDownloadIdentityResolver>()
            fun create(chapter: Chapter) = provider.canonicalChapterDownloadDir(resolver.resolve(manga, chapter))
                .apply { mkdirs() }.resolve("001.png").apply { writeBytes(png(0xFF00FF00.toInt())) }
            val first = create(chapters[0])
            provider.notifyAvailabilityChanged()
            render(scene)
            click(scene, MR.strings.desktop_ui_delete_download.localized())
            render(scene)
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            assertTrue(first.exists())
            assertTrue(
                nodes(scene).any {
                    MR.strings.desktop_ui_delete_download.localized() in labels(it) &&
                        it.config.contains(SemanticsProperties.Focused) && it.config[SemanticsProperties.Focused]
                },
                "cancel restores the actual still-visible inline deletion trigger",
            )
            click(scene, MR.strings.desktop_ui_delete_download.localized())
            render(scene)
            val late = create(chapters[1])
            val restore = denyFileChanges(first)
            try {
                click(scene, MR.strings.action_delete.localized())
                render(scene)
                assertTrue(first.exists())
                assertTrue(
                    activeNodes(scene).any { "0 succeeded, 0 skipped, 1 failed" in labels(it) },
                    "the real provider Boolean refusal cannot close the single chapter confirmation as success",
                )
                assertTrue(activeNodes(scene).any { MR.strings.action_cancel.localized() in labels(it) })
            } finally {
                restore()
            }
            click(scene, MR.strings.action_delete.localized())
            render(scene)
            assertFalse(first.exists())
            assertTrue(late.exists())
            assertEquals(MangaDetailScreen(manga.id), scene.navigator.lastItem)
        }
    }

    @Test
    fun `external chapter delegates the actual browser result without Reader navigation and retries visible failure`(
        @TempDir root: File,
    ) = runBlocking {
        val urls = mutableListOf<String>()
        var accepted = false
        withDetail(root, externalUrlOpener = { url ->
            urls += url
            if (accepted) Result.success(Unit) else Result.failure(java.io.IOException("system browser refused"))
        }) { scene, _, manga, chapters ->
            Injekt.get<ChapterRepository>().update(
                ChapterUpdate(chapters[0].id, url = "external:https://example.com/external-chapter"),
            )
            render(scene)
            chapterMouse(scene, "Chapter 1")
            render(scene)
            assertEquals(
                listOf("https://example.com/external-chapter"),
                urls,
                "the actual row must consume the existing production browser port result",
            )
            assertEquals(MangaDetailScreen(manga.id), scene.navigator.lastItem)
            assertTrue(nodes(scene).any { MR.strings.desktop_external_chapter_open_failed.localized() in labels(it) })
            accepted = true
            chapterMouse(scene, "Chapter 1")
            render(scene)
            assertEquals(2, urls.size)
            assertTrue(nodes(scene).any { MR.strings.desktop_external_chapter_opened.localized() in labels(it) })
            assertEquals(MangaDetailScreen(manga.id), scene.navigator.lastItem)
            assertTrue(Injekt.get<mihon.desktop.download.DesktopDownloadManager>().queue.value.isEmpty())
        }
    }

    @Test
    fun `actual Reader mode writes isolate Manga flags reject visibly and persist through real detail return`(
        @TempDir root: File,
    ) = runBlocking {
        var reject = true
        withDetail(
            root,
            httpSource = true,
            mangaTransform = { it.copy(viewerFlags = 7L) },
            mangaRepositoryOverride = { actual ->
                object : MangaRepository by actual {
                    override suspend fun update(update: MangaUpdate): Boolean =
                        if (reject && update.viewerFlags != null) false else actual.update(update)
                }
            },
        ) { scene, _, manga, chapters ->
            val repository = Injekt.get<MangaRepository>()
            val other = repository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 42,
                        url = "/other-reader",
                        title = "Other reader",
                        viewerFlags = 2L,
                        initialized = true,
                    ),
                ),
            ).single()
            val preferences = Injekt.get<mihon.desktop.reader.ReaderPreferences>()
            val global = preferences.readingMode
            val provider = Injekt.get<mihon.desktop.download.DesktopDownloadProvider>()
            val resolver = Injekt.get<mihon.desktop.download.DesktopDownloadIdentityResolver>()
            provider.canonicalChapterDownloadDir(resolver.resolve(manga, chapters[0])).apply {
                mkdirs()
                resolve("001.png").writeBytes(png(0xFF00FF00.toInt()))
            }
            provider.notifyAvailabilityChanged()
            render(scene)
            chapterMouse(scene, "Chapter 1")
            render(scene)
            assertTrue(scene.navigator.lastItem is DesktopReaderScreen)
            scene.pointerClick(Offset(600f, 450f))
            render(scene)
            click(scene, MR.strings.desktop_ui_reader_settings.localized())
            render(scene)
            click(scene, MR.strings.left_to_right_viewer.localized())
            render(scene)
            assertEquals(7L, repository.getMangaById(manga.id).viewerFlags)
            assertTrue(
                activeNodes(scene).any { MR.strings.desktop_detail_save_failed.localized() in labels(it) },
                "a real SQL rejection stays visible in the reader settings and restores its authoritative mode",
            )
            assertTrue(
                activeNodes(scene).any { node ->
                    MR.strings.automatic_background.localized() in labels(node) &&
                        flatten(node).any {
                            it.config.contains(SemanticsProperties.Selected) &&
                                it.config[SemanticsProperties.Selected]
                        }
                },
                "rejected LTR restores the real persisted AUTO mode, without resetting the reader session",
            )
            reject = false
            click(scene, MR.strings.left_to_right_viewer.localized())
            render(scene)
            assertEquals(1L, repository.getMangaById(manga.id).viewerFlags and 7L)
            assertEquals(2L, repository.getMangaById(other.id).viewerFlags)
            assertEquals(global, preferences.readingMode)
            click(scene, MR.strings.action_close.localized())
            scene.navigator.pop()
            render(scene)
            chapterMouse(scene, "Chapter 1")
            render(scene)
            assertEquals(1L, (scene.navigator.lastItem as DesktopReaderScreen).mangaViewerFlags and 7L)
            scene.pointerClick(Offset(600f, 450f))
            render(scene)
            click(scene, MR.strings.desktop_ui_reader_settings.localized())
            render(scene)
            assertTrue(
                activeNodes(scene).any { node ->
                    MR.strings.left_to_right_viewer.localized() in labels(node) &&
                        flatten(node).any {
                            it.config.contains(SemanticsProperties.Selected) &&
                                it.config[SemanticsProperties.Selected]
                        }
                },
            )
            click(scene, MR.strings.action_close.localized())
            scene.navigator.pop()
            render(scene)
            assertEquals(MangaDetailScreen(manga.id), scene.navigator.lastItem)
        }
    }

    @Test
    fun `cover viewer saves the actual loaded typed image with overwrite cancellation failure retry and focus`(
        @TempDir root: File,
    ) = runBlocking {
        val source = File(root, "save-source.png").apply { writeBytes(png(0xFF00FF00.toInt())) }
        val destination = File(root, "saved-cover.png").apply { writeBytes(png(0xFFFF0000.toInt())) }
        var overwrite = false
        var reject = false
        val share = mihon.desktop.platform.DesktopShareService(
            isHeadless = { false },
            revealPort = mihon.desktop.platform.DesktopRevealPort {},
            savePort = mihon.desktop.platform.SwingDesktopSavePort(
                chooseDestination = { destination },
                overwriteConfirmation = { overwrite },
                contentWriter = { content, file ->
                    if (reject) {
                        file.writeBytes(byteArrayOf(1, 2))
                        throw java.io.IOException("cover save interrupted")
                    }
                    val image = (content as mihon.desktop.platform.DesktopSaveContent.Image).image
                    check(ImageIO.write(image, "png", file))
                },
            ),
        )
        withDetail(
            root,
            mangaTransform = { it.copy(thumbnailUrl = source.absolutePath) },
            coverRequests = mutableListOf(),
            shareService = share,
        ) { scene, _, manga, _ ->
            val notifications = mutableListOf<mihon.desktop.domain.DesktopNotification>()
            val collector = launch(Dispatchers.Unconfined) {
                Injekt.get<mihon.desktop.domain.DesktopNotificationService>().notifications.collect {
                    notifications +=
                        it
                }
            }
            try {
                assertCoverPixel(scene, manga.title, 0xFF00FF00.toInt())
                val cover = nodes(scene).first {
                    it.config.contains(SemanticsProperties.ContentDescription) && manga.title in labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                }
                scene.pointerClick(cover.boundsInRoot.center)
                render(scene)
                assertTrue(
                    activeNodes(scene).any { MR.strings.action_save.localized() in labels(it) },
                    "the real cover viewer exposes saving its successfully loaded typed Coil image",
                )
                click(scene, MR.strings.action_save.localized())
                render(scene)
                assertEquals(0xFFFF0000.toInt(), ImageIO.read(destination).getRGB(1, 1))
                assertTrue(activeNodes(scene).any { MR.strings.cancelled.localized() in labels(it) })
                overwrite = true
                reject = true
                click(scene, MR.strings.action_save.localized())
                render(scene)
                assertEquals(0xFFFF0000.toInt(), ImageIO.read(destination).getRGB(1, 1))
                assertTrue(activeNodes(scene).any { MR.strings.error_saving_picture.localized() in labels(it) })
                reject = false
                click(scene, MR.strings.action_save.localized())
                render(scene)
                assertEquals(0xFF00FF00.toInt(), ImageIO.read(destination).getRGB(1, 1))
                assertTrue(activeNodes(scene).any { MR.strings.picture_saved.localized() in labels(it) })
                key(scene, Key.Escape)
                render(scene)
                assertTrue(
                    nodes(scene).any {
                        it.config.contains(SemanticsProperties.Focused) && it.config[SemanticsProperties.Focused] &&
                            manga.title in labels(it)
                    },
                )
                assertEquals(MR.strings.action_save.localized(), notifications.last().title)
                assertEquals(MR.strings.picture_saved.localized(), notifications.last().message)
                for (mode in listOf(mihon.desktop.settings.ThemeMode.LIGHT, mihon.desktop.settings.ThemeMode.DARK)) {
                    scene.resize(320, 680)
                    scene.fontScale = 2f
                    Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().themeMode.set(mode)
                    render(scene)
                    val trigger = activeNodes(scene).first {
                        manga.title in labels(it) &&
                            it.config.contains(SemanticsProperties.ContentDescription) &&
                            it.config.contains(SemanticsActions.OnClick)
                    }
                    scene.pointerClick(trigger.boundsInRoot.center)
                    render(scene)
                    val required = listOf(
                        MR.strings.action_close.localized(),
                        MR.strings.action_save.localized(),
                        MR.strings.action_edit_cover.localized(),
                    )
                    for (label in required) {
                        val node = activeNodes(scene).first {
                            label in labels(it) &&
                                it.config.contains(SemanticsActions.OnClick)
                        }
                        val bounds = node.touchBoundsInRoot
                        assertTrue(
                            bounds.width >= 48f && bounds.height >= 48f && bounds.left >= 0 && bounds.right <= 320 &&
                                bounds.top >= 0 && bounds.bottom <= 680,
                            "viewer $label remains reachable at 320dp/font200: $bounds",
                        )
                    }
                    val seen = mutableSetOf<String>()
                    repeat(8) {
                        seen += labels(focused(scene))
                        assertFalse(
                            MR.strings.action_bar_up_description.localized() in labels(focused(scene)),
                            "native modal keyboard focus never reaches the background detail",
                        )
                        key(scene, Key.Tab)
                        render(scene)
                    }
                    required.forEach { assertTrue(it in seen, "forward Tab reaches viewer $it") }
                    seen.clear()
                    repeat(8) {
                        key(scene, Key.Tab, shift = true)
                        render(scene)
                        seen += labels(focused(scene))
                    }
                    required.forEach { assertTrue(it in seen, "reverse Tab reaches viewer $it") }
                    activeNodes(scene).first {
                        MR.strings.action_save.localized() in labels(it) &&
                            it.config.contains(SemanticsActions.RequestFocus)
                    }.config[SemanticsActions.RequestFocus].action!!.invoke()
                    key(scene, Key.Spacebar)
                    render(scene)
                    assertEquals(0xFF00FF00.toInt(), ImageIO.read(destination).getRGB(1, 1))
                    assertEquals(MR.strings.action_save.localized(), notifications.last().title)
                    scene.savePng(visualFile(root, "ri10-cover-save-320-font200-${mode.name.lowercase()}.png"))
                    key(scene, Key.Escape)
                    render(scene)
                    assertTrue(
                        nodes(scene).any {
                            manga.title in labels(it) &&
                                it.config.contains(SemanticsProperties.Focused) &&
                                it.config[SemanticsProperties.Focused]
                        },
                    )
                }
            } finally {
                collector.cancel()
            }
        }
    }

    @Test
    fun `download queue cancel refusal preserves persistent work and gives retry feedback`(
        @TempDir root: File,
    ) = runBlocking {
        var reject = false
        withDetail(root, httpSource = true, downloadManagerFactory = {
            val store = tachiyomi.data.download.PersistentDownloadStore(Injekt.get<tachiyomi.data.Database>())
            mihon.desktop.download.DesktopDownloadManager(
                provider = Injekt.get(),
                store = store,
                queuePersister = { entries ->
                    if (reject) throw java.io.IOException("persistent queue is unavailable")
                    store.replaceAll(entries)
                },
            )
        }) { scene, model, manga, chapters ->
            model.enqueueDownloads(manga, listOf(chapters[0]))
            render(scene)
            reject = true
            val bounds = chapterNode(scene, "Chapter 1").boundsInRoot
            scene.pointerSecondaryClick(Offset(bounds.left + bounds.width / 3, bounds.center.y))
            render(scene)
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            assertEquals(listOf(chapters[0].id), model.downloadQueueFlow().value.map { it.chapterId })
            assertTrue(
                activeNodes(scene).flatMap(::labels).any { "0 succeeded, 0 skipped, 1 failed" in it },
                "actual manager cancellation refusal is visible and leaves accepted work retryable",
            )
            reject = false
            scene.pointerSecondaryClick(Offset(bounds.left + bounds.width / 3, bounds.center.y))
            render(scene)
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            assertTrue(model.downloadQueueFlow().value.isEmpty())
        }
    }

    @Test
    fun `download queue retry persistence error remains visible and retry reuses chapter identity`(
        @TempDir root: File,
    ) = runBlocking {
        var reject = false
        withDetail(root, httpSource = true, downloadManagerFactory = {
            val store = tachiyomi.data.download.PersistentDownloadStore(Injekt.get<tachiyomi.data.Database>())
            mihon.desktop.download.DesktopDownloadManager(
                provider = Injekt.get(),
                store = store,
                queuePersister = { entries ->
                    if (reject) throw java.io.IOException("persistent queue is unavailable")
                    store.replaceAll(entries)
                },
            )
        }) { scene, model, manga, chapters ->
            val manager = Injekt.get<mihon.desktop.download.DesktopDownloadManager>()
            model.enqueueDownloads(manga, listOf(chapters[0]))
            assertTrue(manager.transition(chapters[0].id, mihon.domain.download.DownloadQueueStatus.DOWNLOADING))
            assertTrue(manager.transition(chapters[0].id, mihon.domain.download.DownloadQueueStatus.ERROR))
            render(scene)
            reject = true
            val action = flatten(chapterNode(scene, "Chapter 1")).first {
                MR.strings.desktop_ui_download_retry_error.localized() in labels(it) &&
                    it.config.contains(SemanticsActions.OnClick)
            }
            scene.pointerClick(action.boundsInRoot.center)
            render(scene)
            assertEquals(mihon.desktop.download.DownloadStatus.ERROR, manager.queue.value.single().status)
            assertTrue(
                activeNodes(scene).flatMap(::labels).any { "0 succeeded, 0 skipped, 1 failed" in it },
                "a persistent retry failure cannot escape the ordinary chapter UI callback",
            )
            reject = false
            scene.pointerClick(action.boundsInRoot.center)
            render(scene)
            assertEquals(mihon.desktop.download.DownloadStatus.QUEUED, manager.queue.value.single().status)
            assertEquals(manga.id, manager.queue.value.single().mangaId)
        }
    }

    @Test
    fun `download queue queued chapter context starts now by real factory without Reader or identity replacement`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, httpSource = true) { scene, model, manga, chapters ->
            assertEquals(
                chapters.take(3).map {
                    it.id
                },
                model.enqueueDownloadBatch(manga, chapters.take(3)).succeededIds,
            )
            val manager = Injekt.get<mihon.desktop.download.DesktopDownloadManager>()
            manager.pauseAll()
            render(scene)
            val bounds = chapterNode(scene, "Chapter 3").boundsInRoot
            scene.pointerSecondaryClick(Offset(bounds.left + bounds.width / 3, bounds.center.y))
            render(scene)
            assertTrue(
                activeNodes(scene).flatMap(::labels).contains(MR.strings.action_start_downloading_now.localized()),
                "queued ordinary context exposes the actual start-now manager command",
            )
            click(scene, MR.strings.action_start_downloading_now.localized())
            render(scene)
            assertEquals(
                listOf(chapters[2].id, chapters[0].id, chapters[1].id),
                manager.queue.value.map {
                    it.chapterId
                },
            )
            assertFalse(manager.isPaused.value)
            assertTrue(manager.queue.value.all { it.mangaId == manga.id })
            assertTrue(scene.navigator.lastItem is MangaDetailScreen)
        }
    }

    @Test
    fun `download queue missing source inline gesture reports reason without accepting work`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, _, _ ->
            val action = flatten(chapterNode(scene, "Chapter 1")).first {
                MR.strings.action_download.localized() in labels(it) && it.config.contains(SemanticsActions.OnClick)
            }
            scene.pointerClick(action.boundsInRoot.center)
            render(scene)
            assertTrue(
                model.downloadQueueFlow().value.isEmpty(),
                "ordinary inline route obeys the same source eligibility as the menu",
            )
            assertTrue(activeNodes(scene).flatMap(::labels).any { "Source not installed: 42" in it })
        }
    }

    @Test
    fun `download queue actual preflight no op is skipped rather than accepted by selection wiring`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, httpSource = true, downloadManagerFactory = {
            mihon.desktop.download.DesktopDownloadManager(
                provider = Injekt.get(),
                store = tachiyomi.data.download.PersistentDownloadStore(Injekt.get<tachiyomi.data.Database>()),
                enqueueFileOperations = object : mihon.desktop.download.DownloadEnqueueFileOperations by
                mihon.desktop.download.DefaultDownloadEnqueueFileOperations {
                    override fun isChapterDownloaded(
                        provider: mihon.desktop.download.DesktopDownloadProvider,
                        item: mihon.desktop.download.DownloadItem,
                    ): Boolean {
                        File(
                            provider.chapterDownloadDir(item.sourceId, item.mangaTitle, item.chapterName)
                                .apply { mkdirs() },
                            "001.png",
                        ).writeBytes(png(0xFF00FF00.toInt()))
                        return mihon.desktop.download.DefaultDownloadEnqueueFileOperations.isChapterDownloaded(
                            provider,
                            item,
                        )
                    }
                },
            )
        }) { scene, model, _, _ ->
            chapterMouse(scene, "Chapter 1", ctrl = true)
            clickChapterAction(scene, MR.strings.action_download.localized())
            render(scene)
            assertTrue(model.downloadQueueFlow().value.isEmpty())
            assertTrue(
                activeNodes(scene).flatMap(::labels).any { "0 succeeded, 1 skipped, 0 failed" in it },
                "the real manager no-op cannot be reported as accepted by a Unit-adapted factory port",
            )
        }
    }

    @Test
    fun `download manual accepted scope stays fixed while raw repository read is suspended`(
        @TempDir root: File,
    ) = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var gate = false
        withDetail(root, httpSource = true, mangaTransform = {
            it.copy(chapterFlags = Manga.CHAPTER_SHOW_UNREAD or Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC)
        }, chapterRepositoryOverride = { actual ->
            object : ChapterRepository by actual {
                override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
                    if (gate && !applyScanlatorFilter) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return actual.getChapterByMangaId(mangaId, applyScanlatorFilter)
                }
            }
        }) { scene, model, _, chapters ->
            Injekt.get<ChapterRepository>().update(ChapterUpdate(chapters[0].id, read = true, bookmark = true))
            render(scene)
            val preference = Injekt.get<mihon.desktop.reader.ReaderPreferences>()
            preference.skipFilteredChapters = false
            gate = true
            try {
                click(scene, MR.strings.desktop_ui_download_chapters.localized())
                render(scene)
                click(scene, MangaDetailDownloadAction.BOOKMARKED_CHAPTERS.label)
                kotlinx.coroutines.withTimeout(5000) { entered.await() }
                preference.skipFilteredChapters = true
                release.complete(Unit)
                render(scene)
                assertEquals(
                    listOf(chapters[0].id),
                    model.downloadQueueFlow().value.map { it.chapterId },
                    "the accepted All chapters scope is not replaced by a later preference write",
                )
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun `download manual raw query failure reports retry and cancellation remains cancellation`(
        @TempDir root: File,
    ) = runBlocking {
        var failure: Exception? = null
        withDetail(root, httpSource = true, chapterRepositoryOverride = { actual ->
            object : ChapterRepository by actual {
                override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
                    if (!applyScanlatorFilter) failure?.let { throw it }
                    return actual.getChapterByMangaId(mangaId, applyScanlatorFilter)
                }
            }
        }) { scene, model, _, _ ->
            failure = java.io.IOException("raw directory rejected")
            val result = model.downloadManualAction(MangaDetailDownloadAction.NEXT_1_CHAPTER)
            assertEquals(1, result.failures.size)
            assertTrue(model.downloadQueueFlow().value.isEmpty())
            click(scene, MR.strings.desktop_ui_download_chapters.localized())
            render(scene)
            click(scene, MangaDetailDownloadAction.NEXT_1_CHAPTER.label)
            render(scene)
            assertTrue(
                activeNodes(scene).flatMap(::labels).any {
                    MR.strings.desktop_ui_download_failed.localized() in
                        it
                },
            )
            failure = kotlinx.coroutines.CancellationException("cancel directory query")
            val cancelled = runCatching {
                model.downloadManualAction(MangaDetailDownloadAction.NEXT_1_CHAPTER)
            }.exceptionOrNull()
            assertTrue(cancelled is kotlinx.coroutines.CancellationException)
            failure = null
            assertEquals(1, model.downloadManualAction(MangaDetailDownloadAction.NEXT_1_CHAPTER).succeededIds.size)
        }
    }

    @Test
    fun `download manual next limits use eligible narrative candidates before taking`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, httpSource = true, mangaTransform = {
            it.copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC)
        }) { scene, model, manga, chapters ->
            val provider = Injekt.get<mihon.desktop.download.DesktopDownloadProvider>()
            val identity = Injekt.get<mihon.desktop.download.DesktopDownloadIdentityResolver>()
            File(
                provider.canonicalChapterDownloadDir(identity.resolve(manga, chapters[0]))
                    .apply { mkdirs() },
                "001.png",
            ).writeBytes(png(0xFF00FF00.toInt()))
            provider.notifyAvailabilityChanged()
            model.enqueueDownloads(manga, listOf(chapters[1]))
            Injekt.get<ChapterRepository>().update(
                ChapterUpdate(
                    chapters[2].id,
                    url = "external:https://example.com/chapter-3",
                ),
            )
            render(scene)
            val actions = listOf(
                MangaDetailDownloadAction.NEXT_1_CHAPTER to 1,
                MangaDetailDownloadAction.NEXT_5_CHAPTERS to 5,
                MangaDetailDownloadAction.NEXT_10_CHAPTERS to 10,
                MangaDetailDownloadAction.NEXT_25_CHAPTERS to 25,
            )
            val manager = Injekt.get<mihon.desktop.download.DesktopDownloadManager>()
            for (direction in listOf(Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_DESC)) {
                assertTrue(
                    Injekt.get<MangaRepository>().update(
                        MangaUpdate(
                            manga.id,
                            chapterFlags = Manga.CHAPTER_SORTING_NUMBER or direction,
                        ),
                    ),
                )
                render(scene)
                for ((action, limit) in actions) {
                    click(scene, MR.strings.desktop_ui_download_chapters.localized())
                    render(scene)
                    click(scene, action.label)
                    render(scene)
                    val expected = listOf(chapters[1].id) + chapters.drop(3).take(limit).map { it.id }
                    assertEquals(
                        expected,
                        manager.queue.value.map { it.chapterId },
                        "already downloaded, queued and external chapters never consume the requested limit",
                    )
                    assertTrue(
                        manager.cancelAndAwaitRetirements(
                            manager.queue.value.map { it.chapterId }
                                .filterNot { it == chapters[1].id },
                        ),
                    )
                    render(scene)
                }
            }
        }
    }

    @Test
    fun `download manual read bookmarks and real skip filtered preference share the mounted candidate range`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, httpSource = true, mangaTransform = {
            it.copy(chapterFlags = Manga.CHAPTER_SHOW_UNREAD or Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC)
        }) { scene, _, _, chapters ->
            val repository = Injekt.get<ChapterRepository>()
            repository.update(ChapterUpdate(chapters[0].id, read = true, bookmark = true))
            repository.update(ChapterUpdate(chapters[4].id, bookmark = true))
            val preferences = Injekt.get<mihon.desktop.reader.ReaderPreferences>()
            val manager = Injekt.get<mihon.desktop.download.DesktopDownloadManager>()
            for (skip in listOf(false, true)) {
                preferences.skipFilteredChapters = skip
                render(scene)
                click(scene, MR.strings.desktop_ui_download_chapters.localized())
                render(scene)
                click(scene, MangaDetailDownloadAction.BOOKMARKED_CHAPTERS.label)
                render(scene)
                assertEquals(
                    if (skip) listOf(chapters[4].id) else listOf(chapters[0].id, chapters[4].id),
                    manager.queue.value.map {
                        it.chapterId
                    },
                    "read bookmarks remain downloadable; skip uses the real preference",
                )
                assertTrue(manager.cancelAndAwaitRetirements(manager.queue.value.map { it.chapterId }))
                render(scene)
            }
        }
    }

    @Test
    fun `download manual scope label reflects the actual Reader preference before accepting work`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, httpSource = true) { scene, _, _, _ ->
            val preferences = Injekt.get<mihon.desktop.reader.ReaderPreferences>()
            for (skip in listOf(false, true)) {
                preferences.skipFilteredChapters = skip
                val top = nodes(scene).first {
                    MR.strings.desktop_ui_download_chapters.localized() in labels(it) &&
                        it.config.contains(SemanticsActions.OnClick) && it.boundsInRoot.height > 0
                }
                top.config[SemanticsActions.OnClick].action!!.invoke()
                render(scene)
                val expected = if (skip) "Filtered chapters" else "All chapters"
                assertTrue(
                    activeNodes(scene).flatMap(::labels).any { it == expected },
                    "menu declares the actual persisted download scope before accepting a snapshot",
                )
                key(scene, Key.Escape)
                render(scene)
                assertFalse(
                    activeNodes(scene).flatMap(::labels).any {
                        it ==
                            MangaDetailDownloadAction.NEXT_1_CHAPTER.label
                    },
                    "Escape closes only the actual download popup before the next Root trigger",
                )
            }
        }
    }

    @Test
    fun `download manual skip preference controls excluded scanlator raw input without changing visible rows`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, httpSource = true, mangaTransform = {
            it.copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC)
        }) { scene, model, manga, chapters ->
            Injekt.get<ChapterRepository>().update(ChapterUpdate(chapters[0].id, scanlator = "Team A"))
            assertTrue(model.updateExcludedScanlators(setOf("Team A")))
            render(scene)
            assertFalse(
                chapters[0].id in model.state.value.chapters.map { it.id },
                "existing repository-level scanlator filter remains authoritative for visible chapters",
            )
            val preferences = Injekt.get<mihon.desktop.reader.ReaderPreferences>()
            val manager = Injekt.get<mihon.desktop.download.DesktopDownloadManager>()
            for (skip in listOf(false, true)) {
                preferences.skipFilteredChapters = skip
                click(scene, MR.strings.desktop_ui_download_chapters.localized())
                render(scene)
                click(scene, MangaDetailDownloadAction.NEXT_1_CHAPTER.label)
                render(scene)
                assertEquals(listOf(chapters[if (skip) 1 else 0].id), manager.queue.value.map { it.chapterId })
                assertTrue(manager.cancelAndAwaitRetirements(manager.queue.value.map { it.chapterId }))
                render(scene)
                assertFalse(chapters[0].id in model.state.value.chapters.map { it.id })
            }
            assertEquals(manga.id, model.state.value.manga?.id)
        }
    }

    @Test
    fun `download manual local and missing source never enqueue meaningless remote work`(
        @TempDir root: File,
    ) = runBlocking {
        for (sourceId in listOf(0L, 42L)) {
            withDetail(File(root, "source-$sourceId"), mangaTransform = {
                it.copy(source = sourceId)
            }) { scene, model, _, _ ->
                click(scene, MR.strings.desktop_ui_download_chapters.localized())
                render(scene)
                click(scene, MangaDetailDownloadAction.NEXT_1_CHAPTER.label)
                render(scene)
                assertTrue(
                    model.downloadQueueFlow().value.isEmpty(),
                    "local and uninstalled sources never enter the queue",
                )
                val reason = if (sourceId == 0L) {
                    "Local source: chapters are already available on this device."
                } else {
                    "Cannot download: Source not installed: 42"
                }
                assertTrue(
                    activeNodes(scene).flatMap(::labels).any { reason in it },
                    "actual feedback explains the local or missing source instead of only reporting counts",
                )
            }
        }
    }

    @Test
    fun `download manual nonfavorite accepted work offers optional actual library membership`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, httpSource = true) { scene, model, manga, _ ->
            click(scene, MR.strings.desktop_ui_download_chapters.localized())
            render(scene)
            click(scene, MangaDetailDownloadAction.NEXT_1_CHAPTER.label)
            render(scene)
            assertEquals(1, model.downloadQueueFlow().value.size)
            assertFalse(Injekt.get<MangaRepository>().getMangaById(manga.id).favorite)
            assertTrue(
                activeNodes(scene).flatMap(::labels).any { MR.strings.snack_add_to_library.localized() in it },
                "accepted download provides the existing nonblocking add-to-library reminder",
            )
            click(scene, MR.strings.action_add.localized())
            render(scene)
            assertTrue(Injekt.get<MangaRepository>().getMangaById(manga.id).favorite)
        }
    }

    @Test
    fun `chapter result explicit read preserves shared User intent for matching local state`(
        @TempDir root: File,
    ) = runBlocking {
        val written = mutableListOf<tachiyomi.domain.chapter.model.ChapterUpdate>()
        var capture = false
        withDetail(root, chapterRepositoryOverride = { actual ->
            object : ChapterRepository by actual {
                override suspend fun updateAll(updates: List<tachiyomi.domain.chapter.model.ChapterUpdate>) {
                    if (capture) written += updates
                    actual.updateAll(updates)
                }
            }
        }) { scene, _, _, chapters ->
            Injekt.get<ChapterRepository>().update(
                tachiyomi.domain.chapter.model.ChapterUpdate(chapters[0].id, read = true),
            )
            render(scene)
            capture = true
            chapterMouse(scene, "Chapter 1", ctrl = true)
            chapterMouse(scene, "Chapter 2", ctrl = true)
            click(scene, MR.strings.action_mark_as_read.localized())
            render(scene)
            assertEquals(
                setOf(chapters[0].id, chapters[1].id),
                written.map { it.id }.toSet(),
                "explicit read retains every shared command, including locally matching state",
            )
            assertTrue(written.all { it.read == true && it.syncContext == mihon.domain.sync.SyncMutationContext.User })
            assertTrue(Injekt.get<ChapterRepository>().getChapterById(chapters[1].id)!!.read)
        }
    }

    @Test
    fun `chapter result bookmark processes only applicable objects and shows success skip failure`(
        @TempDir root: File,
    ) = runBlocking {
        val written = mutableListOf<Long>()
        var capture = false
        withDetail(root, chapterRepositoryOverride = { actual ->
            object : ChapterRepository by actual {
                override suspend fun update(chapterUpdate: tachiyomi.domain.chapter.model.ChapterUpdate) {
                    if (capture) written += chapterUpdate.id
                    actual.update(chapterUpdate)
                }
            }
        }) { scene, _, _, chapters ->
            Injekt.get<ChapterRepository>().update(
                tachiyomi.domain.chapter.model.ChapterUpdate(chapters[0].id, bookmark = true),
            )
            render(scene)
            capture = true
            chapterMouse(scene, "Chapter 1", ctrl = true)
            chapterMouse(scene, "Chapter 2", ctrl = true)
            click(scene, MR.strings.action_bookmark.localized())
            render(scene)
            assertEquals(
                listOf(chapters[1].id),
                written,
                "already bookmarked objects are skipped rather than rewritten",
            )
            assertTrue(activeNodes(scene).flatMap(::labels).any { "1 succeeded, 1 skipped, 0 failed" in it })
            assertTrue(
                selectedChapterNames(scene).isEmpty(),
                "successful and explicitly skipped applicable selection completes",
            )
        }
    }

    @Test
    fun `chapter result deletion confirms frozen actual files cancels and preserves partial failures for retry`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, manga, chapters ->
            val provider = Injekt.get<mihon.desktop.download.DesktopDownloadProvider>()
            val resolver = Injekt.get<mihon.desktop.download.DesktopDownloadIdentityResolver>()
            fun create(chapter: Chapter) = File(
                provider.canonicalChapterDownloadDir(resolver.resolve(manga, chapter))
                    .apply { mkdirs() },
                "001.png",
            ).apply { writeBytes(png(0xFF00FF00.toInt())) }
            val first = create(chapters[0])
            val second = create(chapters[1])
            provider.notifyAvailabilityChanged()
            render(scene)
            assertTrue(model.isChapterDownloaded(manga, chapters[0]))
            chapterMouse(scene, "Chapter 1", ctrl = true)
            chapterMouse(scene, "Chapter 2", ctrl = true)
            clickChapterAction(scene, MR.strings.action_delete.localized())
            render(scene)
            assertTrue(first.exists() && second.exists(), "opening deletion confirmation never deletes files")
            assertTrue(activeNodes(scene).flatMap(::labels).any { "2 selected" in it && "2" in it && "device" in it })
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            assertEquals(setOf("Chapter 1", "Chapter 2"), selectedChapterNames(scene))
            clickChapterAction(scene, MR.strings.action_delete.localized())
            render(scene)
            val late = create(chapters[2])
            val restore = denyFileChanges(first)
            try {
                click(scene, MR.strings.action_delete.localized())
                render(scene)
                assertTrue(first.exists())
                assertFalse(second.exists())
                assertTrue(late.exists(), "new files after opening are outside the accepted snapshot")
                assertEquals(setOf("Chapter 1"), selectedChapterNames(scene))
                assertTrue(activeNodes(scene).flatMap(::labels).any { "1 succeeded, 0 skipped, 1 failed" in it })
            } finally {
                restore()
            }
            val replacement = create(chapters[1])
            click(scene, MR.strings.action_delete.localized())
            render(scene)
            assertFalse(first.exists())
            assertTrue(late.exists())
            assertTrue(replacement.exists(), "retry must not delete a replacement at an already successful target")
            assertTrue(selectedChapterNames(scene).isEmpty())
        }
    }

    @Test
    fun `chapter result mixed download queue uses eligible snapshot without duplicate or local writes`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, httpSource = true) { scene, model, manga, chapters ->
            val provider = Injekt.get<mihon.desktop.download.DesktopDownloadProvider>()
            val resolver = Injekt.get<mihon.desktop.download.DesktopDownloadIdentityResolver>()
            File(
                provider.canonicalChapterDownloadDir(resolver.resolve(manga, chapters[0]))
                    .apply { mkdirs() },
                "001.png",
            ).writeBytes(png(0xFF00FF00.toInt()))
            provider.notifyAvailabilityChanged()
            model.enqueueDownloads(manga, listOf(chapters[1]))
            render(scene)
            chapterMouse(scene, "Chapter 1", ctrl = true)
            chapterMouse(scene, "Chapter 3", shift = true)
            val bottom = activeNodes(scene).first {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "chapter-selection-bottom-bar"
            }.let(::flatten).flatMap(::labels)
            assertTrue(MR.strings.action_download.localized() in bottom)
            assertTrue(
                MR.strings.action_delete.localized() in bottom,
                "mixed selection offers both applicable families",
            )
            clickChapterAction(scene, MR.strings.action_download.localized())
            render(scene)
            assertEquals(
                setOf(chapters[1].id, chapters[2].id),
                model.downloadQueueFlow().value.map {
                    it.chapterId
                }.toSet(),
            )
            assertTrue(activeNodes(scene).flatMap(::labels).any { "1 succeeded, 2 skipped, 0 failed" in it })
            assertTrue(selectedChapterNames(scene).isEmpty())
        }
        withDetail(File(root, "local"), mangaTransform = { it.copy(source = 0L) }) { scene, _, _, _ ->
            chapterMouse(scene, "Chapter 1", ctrl = true)
            val bottom = activeNodes(scene).first {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "chapter-selection-bottom-bar"
            }.let(::flatten).flatMap(::labels)
            assertFalse(MR.strings.action_download.localized() in bottom)
        }
    }

    @Test
    fun `chapter native context actual right click marks and bookmarks without entering Reader`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, chapters ->
            val bounds = chapterNode(scene, "Chapter 1").boundsInRoot
            scene.pointerSecondaryClick(Offset(bounds.left + bounds.width / 3, bounds.center.y))
            render(scene)
            assertTrue(scene.navigator.lastItem is MangaDetailScreen)
            assertTrue(
                activeNodes(scene).any { MR.strings.action_mark_as_read.localized() in labels(it) },
                "secondary context exposes actual mark read rather than Reader",
            )
            click(scene, MR.strings.action_mark_as_read.localized())
            render(scene)
            assertTrue(Injekt.get<ChapterRepository>().getChapterById(chapters[0].id)!!.read)
            scene.pointerSecondaryClick(Offset(bounds.left + bounds.width / 3, bounds.center.y))
            render(scene)
            click(scene, MR.strings.action_bookmark.localized())
            render(scene)
            assertTrue(Injekt.get<ChapterRepository>().getChapterById(chapters[0].id)!!.bookmark)
            assertEquals(manga.id, scene.createdModels.single().mangaId)
        }
    }

    @Test
    fun `chapter native selection Escape exits one layer restores focus and all inverse stay visible`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, _ ->
            chapterMouse(scene, "Chapter 1", ctrl = true)
            val count = activeNodes(scene).first {
                it.config.contains(SemanticsProperties.Text) &&
                    "1" in labels(it) && it.boundsInRoot.bottom <= 64f
            }
            assertFalse(count.config.contains(SemanticsActions.OnClick), "SOURCE number is not a select-all button")
            click(scene, MR.strings.action_select_all.localized())
            render(scene)
            assertTrue(activeNodes(scene).any { "200" in labels(it) && it.boundsInRoot.bottom <= 64f })
            click(scene, MR.strings.action_select_inverse.localized())
            render(scene)
            assertTrue(selectedChapterNames(scene).isEmpty())
            assertTrue(scene.navigator.lastItem is MangaDetailScreen)
            chapterMouse(scene, "Chapter 2", ctrl = true)
            chapterNode(scene, "Chapter 2").config[SemanticsActions.RequestFocus].action!!.invoke()
            key(scene, Key.Escape)
            render(scene)
            assertTrue(selectedChapterNames(scene).isEmpty(), "selection Escape does not navigate away")
            assertTrue(scene.navigator.lastItem is MangaDetailScreen)
            assertTrue(focused(scene).config.contains(SemanticsActions.OnClick))
            assertTrue(MR.strings.action_bar_up_description.localized() in labels(focused(scene)))
            assertEquals(manga.id, scene.createdModels.single().mangaId)
        }
    }

    @Test
    fun `chapter native selected row uses SOURCE effective theme alpha and transient long press label`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, _, _ ->
            Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().themeMode
                .set(mihon.desktop.settings.ThemeMode.LIGHT)
            render(scene)
            chapterMouse(scene, "Chapter 1", ctrl = true)
            activeNodes(scene).first {
                MR.strings.desktop_ui_clear_selection.localized() in labels(it) &&
                    it.config.contains(SemanticsActions.RequestFocus)
            }.config[SemanticsActions.RequestFocus].action!!.invoke()
            scene.pointer(PointerEventType.Move, Offset(20f, 70f), false)
            delay(300)
            render(scene)
            val bounds = chapterNode(scene, "Chapter 1").boundsInRoot
            val expected = scene.colors.secondary.copy(alpha = .22f).compositeOver(scene.colors.surface).toArgb()
            assertEquals(
                expected and 0xFFFFFF,
                scene.snapshot().getRGB(
                    (bounds.left + 3).toInt(),
                    (bounds.center.y).toInt(),
                ) and 0xFFFFFF,
                "selected row uses SOURCE secondary alpha in explicit light mode",
            )
            val button = activeNodes(scene).first {
                MR.strings.action_bookmark.localized() in labels(it) &&
                    it.config.contains(SemanticsActions.OnClick) && it.boundsInRoot.top > 600f
            }.boundsInRoot
            scene.pointer(PointerEventType.Press, button.center, true)
            try {
                delay(650)
                render(scene)
                assertTrue(
                    activeNodes(scene).any {
                        it.config.contains(SemanticsProperties.Text) &&
                            MR.strings.action_bookmark.localized() in labels(it)
                    },
                    "long press displays a transient real action name",
                )
                assertEquals(
                    setOf("Chapter 1"),
                    selectedChapterNames(scene),
                    "long press feedback never executes action",
                )
            } finally {
                scene.pointer(PointerEventType.Release, button.center, false)
            }
            render(scene)
            assertTrue(
                Injekt.get<ChapterRepository>().getChapterByMangaId(scene.createdModels.single().mangaId).none {
                    it.bookmark
                },
                "long press release does not execute the bookmark command",
            )
            assertEquals(setOf("Chapter 1"), selectedChapterNames(scene))
        }
    }

    @Test
    fun `chapter native long press shows transient SOURCE name without executing action`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, _, _ ->
            chapterMouse(scene, "Chapter 1", ctrl = true)
            val button = activeNodes(scene).first {
                MR.strings.action_bookmark.localized() in labels(it) &&
                    it.config.contains(SemanticsActions.OnClick) && it.boundsInRoot.top > 600f
            }.boundsInRoot
            scene.pointer(PointerEventType.Press, button.center, true)
            try {
                delay(650)
                render(scene)
                assertTrue(
                    activeNodes(scene).any {
                        it.config.contains(SemanticsProperties.Text) &&
                            MR.strings.action_bookmark.localized() in labels(it)
                    },
                    "long press displays actual action name",
                )
                assertEquals(setOf("Chapter 1"), selectedChapterNames(scene))
            } finally {
                scene.pointer(PointerEventType.Release, button.center, false)
            }
            render(scene)
            assertTrue(
                Injekt.get<ChapterRepository>().getChapterByMangaId(scene.createdModels.single().mangaId).none {
                    it.bookmark
                },
                "long press release does not execute the bookmark command",
            )
            assertEquals(setOf("Chapter 1"), selectedChapterNames(scene))
        }
    }

    @Test
    fun `chapter final deletion modal traps keys and background returns trigger before selection Escape`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, chapters ->
            val provider = Injekt.get<mihon.desktop.download.DesktopDownloadProvider>()
            val resolver = Injekt.get<mihon.desktop.download.DesktopDownloadIdentityResolver>()
            val file = File(
                provider.canonicalChapterDownloadDir(resolver.resolve(manga, chapters[0]))
                    .apply { mkdirs() },
                "001.png",
            ).apply { writeBytes(png(0xFF00FF00.toInt())) }
            provider.notifyAvailabilityChanged()
            render(scene)
            chapterMouse(scene, "Chapter 1", ctrl = true)
            val row = chapterNode(scene, "Chapter 2").boundsInRoot
            clickChapterAction(scene, MR.strings.action_delete.localized())
            render(scene)
            assertTrue(MR.strings.action_cancel.localized() in labels(focused(scene)))
            key(scene, Key.Tab)
            render(scene)
            assertTrue(MR.strings.action_delete.localized() in labels(focused(scene)))
            key(scene, Key.Tab)
            render(scene)
            assertTrue(MR.strings.action_cancel.localized() in labels(focused(scene)))
            key(scene, Key.Tab, shift = true)
            render(scene)
            assertTrue(MR.strings.action_delete.localized() in labels(focused(scene)))
            scene.pointerClick(Offset(row.left + 30f, row.center.y))
            render(scene)
            assertTrue(file.exists())
            assertEquals(
                setOf("Chapter 1"),
                selectedChapterNames(scene),
                "modal background cannot change selection or enter Reader",
            )
            assertTrue(
                MR.strings.action_delete.localized() in labels(focused(scene)),
                "outside dismissal returns selection trigger",
            )
            clickChapterAction(scene, MR.strings.action_delete.localized())
            render(scene)
            key(scene, Key.Escape)
            render(scene)
            assertEquals(setOf("Chapter 1"), selectedChapterNames(scene))
            assertTrue(
                MR.strings.action_delete.localized() in labels(focused(scene)),
                "dismiss returns to actual selection trigger",
            )
            clickChapterAction(scene, MR.strings.action_delete.localized())
            render(scene)
            val restore = denyFileChanges(file)
            try {
                click(scene, MR.strings.action_delete.localized())
                render(scene)
                assertTrue(file.exists())
                assertEquals(setOf("Chapter 1"), selectedChapterNames(scene))
                key(scene, Key.Escape)
                render(scene)
                assertTrue(
                    MR.strings.action_delete.localized() in labels(focused(scene)),
                    "partial failure Escape restores trigger",
                )
            } finally {
                restore()
            }
            key(scene, Key.Escape)
            render(scene)
            assertTrue(selectedChapterNames(scene).isEmpty())
            assertTrue(scene.navigator.lastItem is MangaDetailScreen)
            assertTrue(MR.strings.action_bar_up_description.localized() in labels(focused(scene)))
        }
    }

    @Test
    fun `chapter final native 320 font200 SOURCE controls remain reachable in light and dark`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, _, _ ->
            for (mode in listOf(mihon.desktop.settings.ThemeMode.LIGHT, mihon.desktop.settings.ThemeMode.DARK)) {
                scene.resize(1200, 800)
                scene.fontScale = 1f
                Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().themeMode.set(mode)
                render(scene)
                chapterMouse(scene, "Chapter 1", ctrl = true)
                activeNodes(scene).first {
                    MR.strings.desktop_ui_clear_selection.localized() in labels(it) &&
                        it.config.contains(SemanticsActions.RequestFocus)
                }.config[SemanticsActions.RequestFocus].action!!.invoke()
                scene.pointer(PointerEventType.Move, Offset(20f, 70f), false)
                delay(300)
                render(scene)
                val row = chapterNode(scene, "Chapter 1").boundsInRoot
                val alpha = if (mode == mihon.desktop.settings.ThemeMode.DARK) .16f else .22f
                val expected = scene.colors.secondary.copy(alpha = alpha).compositeOver(scene.colors.surface).toArgb()
                assertEquals(
                    expected and 0xFFFFFF,
                    scene.snapshot().getRGB((row.left + 3).toInt(), row.center.y.toInt()) and 0xFFFFFF,
                )
                scene.resize(320, 680)
                scene.fontScale = 2f
                render(scene)
                val bar = activeNodes(scene).first {
                    it.config.contains(SemanticsProperties.TestTag) &&
                        it.config[SemanticsProperties.TestTag] == "chapter-selection-bottom-bar"
                }
                assertEquals(0f, bar.boundsInRoot.left)
                assertEquals(320f, bar.boundsInRoot.right)
                val actions = flatten(bar).filter { it.config.contains(SemanticsActions.OnClick) }
                assertTrue(actions.size >= 4)
                actions.forEach {
                    val bounds = it.boundsInRoot
                    assertTrue(
                        bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.right <= 320f &&
                            bounds.top >= 0 && bounds.bottom <= 680f,
                    )
                }
                actions.first().config[SemanticsActions.RequestFocus].action!!.invoke()
                render(scene)
                val seen = mutableSetOf<String>()
                repeat(actions.size) {
                    seen += labels(focused(scene))
                    key(scene, Key.Tab)
                    render(scene)
                }
                actions.flatMap(::labels).forEach { assertTrue(it in seen, "Tab reaches source action $it") }
                repeat(actions.size) {
                    key(scene, Key.Tab, shift = true)
                    render(scene)
                }
                scene.savePng(visualFile(root, "ri09-selection-320-font200-${mode.name.lowercase()}.png"))
                key(scene, Key.Escape)
                render(scene)
                assertTrue(selectedChapterNames(scene).isEmpty())
            }
        }
    }

    @Test
    fun `chapter final ALWAYS 320 preserves whole page half width and every 48dp action target`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, tabletMode = eu.kanade.domain.ui.model.TabletUiMode.ALWAYS) { scene, _, _, _ ->
            chapterMouse(scene, "Chapter 1", ctrl = true)
            scene.resize(320, 680)
            scene.fontScale = 2f
            render(scene)
            val bar = activeNodes(scene).first {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "chapter-selection-bottom-bar"
            }
            assertEquals(160f, bar.boundsInRoot.left)
            assertEquals(320f, bar.boundsInRoot.right)
            assertEquals(160f, bar.boundsInRoot.width)
            val actions = flatten(bar).filter { it.config.contains(SemanticsActions.OnClick) }
            assertTrue(actions.size >= 4)
            actions.forEach {
                val bounds = it.boundsInRoot
                assertTrue(
                    bounds.width >= 48 && bounds.height >= 48 && bounds.left >= 160 && bounds.right <= 320 &&
                        bounds.top >= 0 && bounds.bottom <= 680,
                    "forced wide action remains a reachable 48dp target: $bounds",
                )
            }
            actions.first().config[SemanticsActions.RequestFocus].action!!.invoke()
            render(scene)
            val seen = mutableSetOf<String>()
            repeat(actions.size) {
                seen += labels(focused(scene))
                key(scene, Key.Tab)
                render(scene)
            }
            actions.flatMap(::labels).forEach { assertTrue(it in seen) }
        }
    }

    @Test
    fun `chapter batch previous follows four shared sorts stable ties and excludes current`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, manga, chapters ->
            val repo = Injekt.get<ChapterRepository>()
            repo.removeChaptersWithIds(chapters.drop(6).map { it.id })
            val names = listOf("Z", "B", "A", "C", "D", "E")
            val numbers = listOf(3.0, -1.0, 2.5, 2.5, 7.0, 1.0)
            repo.updateAll(
                chapters.take(6).mapIndexed { index, chapter ->
                    tachiyomi.domain.chapter.model.ChapterUpdate(
                        chapter.id,
                        name = names[index],
                        chapterNumber = numbers[index],
                        dateUpload = (6 - index).toLong(),
                    )
                },
            )
            val cases = listOf(
                Triple(Manga.CHAPTER_SORTING_SOURCE, false, listOf(6, 5, 4)),
                Triple(Manga.CHAPTER_SORTING_SOURCE, true, listOf(6, 5, 4)),
                Triple(Manga.CHAPTER_SORTING_NUMBER, false, listOf(2, 6)),
                Triple(Manga.CHAPTER_SORTING_NUMBER, true, listOf(2, 6)),
                Triple(Manga.CHAPTER_SORTING_UPLOAD_DATE, false, listOf(6, 5, 4)),
                Triple(Manga.CHAPTER_SORTING_UPLOAD_DATE, true, listOf(6, 5, 4)),
                Triple(Manga.CHAPTER_SORTING_ALPHABET, false, emptyList()),
                Triple(Manga.CHAPTER_SORTING_ALPHABET, true, emptyList()),
            )
            for ((sort, descending, before) in cases) {
                repo.updateAll(
                    chapters.take(6).map {
                        tachiyomi.domain.chapter.model.ChapterUpdate(it.id, read = false)
                    },
                )
                Injekt.get<MangaRepository>().update(
                    MangaUpdate(
                        manga.id,
                        chapterFlags = sort or if (descending) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC,
                    ),
                )
                render(scene)
                model.markAtOrBelowRead(model.visibleChapters(), setOf(chapters[2].id))
                assertEquals(
                    before.map { chapters[it - 1].id }.toSet(),
                    repo.getChapterByMangaId(manga.id).filter { it.read }.map { it.id }.toSet(),
                    "Shared sort=$sort descending=$descending: PROJECT_POLICY stable ID ties exclude current",
                )
            }
            repo.updateAll(chapters.take(6).map { tachiyomi.domain.chapter.model.ChapterUpdate(it.id, read = false) })
            model.markAtOrBelowRead(model.visibleChapters(), setOf(Long.MAX_VALUE))
            assertTrue(repo.getChapterByMangaId(manga.id).none { it.read })
        }
    }

    @Test
    fun `chapter batch native partial bookmark keeps failed selection for retry`(@TempDir root: File) = runBlocking {
        var reject = true
        withDetail(root, chapterRepositoryOverride = { actual ->
            object : ChapterRepository by actual {
                override suspend fun update(chapterUpdate: tachiyomi.domain.chapter.model.ChapterUpdate) {
                    if (reject && chapterUpdate.bookmark == true &&
                        actual.getChapterById(chapterUpdate.id)?.name == "Chapter 2"
                    ) {
                        error("actual bookmark write rejected")
                    }
                    actual.update(chapterUpdate)
                }
            }
        }) { scene, _, manga, _ ->
            chapterMouse(scene, "Chapter 1", ctrl = true)
            chapterMouse(scene, "Chapter 3", shift = true)
            click(scene, MR.strings.action_bookmark.localized())
            render(scene)
            assertEquals(
                setOf("Chapter 1", "Chapter 3"),
                Injekt.get<ChapterRepository>()
                    .getChapterByMangaId(manga.id).filter { it.bookmark }.map { it.name }.toSet(),
            )
            assertEquals(setOf("Chapter 2"), selectedChapterNames(scene), "only failed objects remain selected")
            reject = false
            click(scene, MR.strings.action_bookmark.localized())
            render(scene)
            assertTrue(selectedChapterNames(scene).isEmpty())
            assertEquals(3, Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).count { it.bookmark })
        }
    }

    @Test
    fun `chapter batch late successful write does not clear a later native selection`(
        @TempDir root: File,
    ) = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withDetail(root, chapterRepositoryOverride = { actual ->
            object : ChapterRepository by actual {
                override suspend fun update(chapterUpdate: tachiyomi.domain.chapter.model.ChapterUpdate) {
                    if (chapterUpdate.bookmark == true) {
                        started.complete(Unit)
                        release.await()
                    }
                    actual.update(chapterUpdate)
                }
            }
        }) { scene, _, manga, _ ->
            try {
                chapterMouse(scene, "Chapter 1", ctrl = true)
                click(scene, MR.strings.action_bookmark.localized())
                withTimeout(3000) { started.await() }
                click(scene, MR.strings.desktop_ui_clear_selection.localized())
                render(scene)
                chapterMouse(scene, "Chapter 4", ctrl = true)
                release.complete(Unit)
                render(scene)
                assertEquals(
                    setOf("Chapter 4"),
                    selectedChapterNames(scene),
                    "old completion cannot clear a new user selection",
                )
                assertEquals(
                    listOf("Chapter 1"),
                    Injekt.get<ChapterRepository>()
                        .getChapterByMangaId(manga.id).filter { it.bookmark }.map { it.name },
                )
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun `chapter batch SOURCE available actions follow actual selected object conditions`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, _, chapters ->
            chapterMouse(scene, "Chapter 1", ctrl = true)
            assertFalse(
                activeNodes(scene).any { MR.strings.action_mark_as_unread.localized() in labels(it) },
                "unread with no saved page has no mark unread action",
            )
            assertTrue(activeNodes(scene).any { MR.strings.action_mark_previous_as_read.localized() in labels(it) })
            chapterMouse(scene, "Chapter 2", ctrl = true)
            assertFalse(
                activeNodes(scene).any { MR.strings.action_mark_previous_as_read.localized() in labels(it) },
                "previous is a single chapter action",
            )
            Injekt.get<ChapterRepository>().updateAll(
                chapters.take(2).map {
                    tachiyomi.domain.chapter.model.ChapterUpdate(it.id, bookmark = true, read = true)
                },
            )
            render(scene)
            assertTrue(activeNodes(scene).any { MR.strings.action_remove_bookmark.localized() in labels(it) })
            assertFalse(activeNodes(scene).any { MR.strings.action_mark_as_read.localized() in labels(it) })
            assertTrue(activeNodes(scene).any { MR.strings.action_mark_as_unread.localized() in labels(it) })
        }
    }

    @Test
    fun `chapter selection real ctrl shift and stale pointer stay in the same detail owner`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, manga, chapters ->
            chapterMouse(scene, "Chapter 1")
            val reader = scene.navigator.lastItem
            assertTrue(reader is mihon.desktop.ui.reader.DesktopReaderScreen)
            assertEquals(chapters[0].id, (reader as mihon.desktop.ui.reader.DesktopReaderScreen).chapterId)
            assertFalse(reader is cafe.adriel.voyager.navigator.tab.Tab)
            scene.navigator.pop()
            render(scene)
            assertEquals(1, scene.createdModels.size)
            chapterMouse(scene, "Chapter 1", ctrl = true)
            assertTrue(scene.navigator.lastItem is MangaDetailScreen, "Ctrl enters selection instead of Reader")
            assertEquals(setOf("Chapter 1"), selectedChapterNames(scene))
            chapterMouse(scene, "Chapter 4", shift = true)
            assertEquals((1..4).map { "Chapter $it" }.toSet(), selectedChapterNames(scene))
            chapterMouse(scene, "Chapter 2", ctrl = true, shift = true)
            assertEquals((1..4).map { "Chapter $it" }.toSet(), selectedChapterNames(scene))
            chapterMouse(scene, "Chapter 4")
            assertEquals((1..3).map { "Chapter $it" }.toSet(), selectedChapterNames(scene))
            chapterMouse(scene, "Chapter 6", shift = true)
            assertEquals((1..6).map { "Chapter $it" }.toSet(), selectedChapterNames(scene))
            val oldClick = chapterNode(scene, "Chapter 5").config[SemanticsActions.OnClick].action!!
            Injekt.get<MangaRepository>().update(
                MangaUpdate(manga.id, chapterFlags = Manga.CHAPTER_SHOW_READ),
            )
            render(scene)
            assertTrue(model.state.value.manga!!.unreadFilterRaw == Manga.CHAPTER_SHOW_READ)
            oldClick.invoke()
            render(scene)
            assertTrue(scene.navigator.lastItem is MangaDetailScreen, "a filtered stale callback cannot open Reader")
            assertTrue(selectedChapterNames(scene).isEmpty())
            assertEquals(1, scene.createdModels.size)
        }
    }

    @Test
    fun `chapter selection real long press appends interval without a trailing reader click`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, _, _ ->
            chapterMouse(scene, "Chapter 1", holdMillis = 650)
            assertTrue(scene.navigator.lastItem is MangaDetailScreen)
            assertEquals(setOf("Chapter 1"), selectedChapterNames(scene))
            chapterMouse(scene, "Chapter 4", holdMillis = 650)
            assertEquals((1..4).map { "Chapter $it" }.toSet(), selectedChapterNames(scene))
            assertTrue(
                scene.navigator.lastItem is MangaDetailScreen,
                "long press release does not emit an ordinary click",
            )
        }
    }

    @Test
    fun `chapter selection SOURCE bars hide ordinary controls checkbox and occupy right half of full page`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, _ ->
            chapterNode(scene, "Chapter 1").config[SemanticsActions.OnLongClick].action!!.invoke()
            render(scene)
            val top = activeNodes(scene).filter { it.boundsInRoot.top >= 0f && it.boundsInRoot.bottom <= 64f }
            val copy = top.flatMap(::labels)
            assertTrue("1" in copy, "SOURCE count is a plain non-clickable number in the top bar")
            assertFalse(manga.title in copy)
            assertTrue(MR.strings.action_select_all.localized() in copy)
            assertTrue(MR.strings.action_select_inverse.localized() in copy)
            assertFalse(MR.strings.desktop_ui_filter_chapters.localized() in copy)
            assertFalse(MR.strings.desktop_ui_download_chapters.localized() in copy)
            assertFalse(MR.strings.label_more.localized() in copy)
            assertFalse(activeNodes(scene).any { it.config.contains(SemanticsProperties.ToggleableState) })
            assertFalse(activeNodes(scene).any { MR.strings.action_start.localized() in labels(it) })
            val bar = activeNodes(scene).first {
                MR.strings.action_bookmark.localized() in labels(it) &&
                    it.config.contains(SemanticsActions.OnClick)
            }.boundsInRoot
            assertTrue(bar.left >= 600f, "wide action menu occupies page right half, independent of 450dp information")
            assertTrue(bar.right <= 1200f)
            val container = activeNodes(scene).first {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "chapter-selection-bottom-bar"
            }.boundsInRoot
            assertEquals(600f, container.left)
            assertEquals(1200f, container.right)
            assertEquals(600f, container.width)
        }
    }

    @Test
    fun `notes summary Escape returns to its actual summary trigger without writing`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, mangaTransform = { it.copy(notes = "Saved summary") }) { scene, model, manga, _ ->
            val summary = activeNodes(scene).first {
                "Saved summary" in labels(it) &&
                    it.config.contains(SemanticsActions.OnClick)
            }
            scene.pointerClick(summary.boundsInRoot.center)
            render(scene)
            assertTrue(model.state.value.showNotesDialog)
            key(scene, Key.Escape)
            render(scene)
            assertFalse(model.state.value.showNotesDialog)
            assertEquals("Saved summary", Injekt.get<MangaRepository>().getMangaById(manga.id).notes)
            assertTrue(
                activeNodes(scene).any {
                    "Saved summary" in labels(it) &&
                        it.config.contains(SemanticsProperties.Focused) &&
                        it.config[SemanticsProperties.Focused]
                },
                "summary-origin editor returns focus to the same summary rather than More",
            )
        }
    }

    @Test
    fun `deleted viewport anchor falls to a chapter entity with its bounded offset rather than missing row`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, mangaTransform = {
            it.copy(
                chapterFlags =
                Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC,
            )
        }) { scene, model, _, chapters ->
            val list = nodes(scene).first {
                it.config.contains(SemanticsActions.ScrollToIndex) &&
                    it.boundsInRoot.left >= 450f
            }
            list.config[SemanticsActions.ScrollToIndex].action!!.invoke(100)
            render(scene)
            list.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 29f)
            render(scene)
            assertEquals(chapters[99].id, model.chapterPosition?.chapterId)
            assertEquals(29, model.chapterPosition?.offset)
            Injekt.get<ChapterRepository>().removeChaptersWithIds(listOf(chapters[99].id))
            render(scene)
            assertEquals(chapters[100].id, model.chapterPosition?.chapterId)
            assertEquals(29, model.chapterPosition?.offset, "the missing-count hint is not a restoration target")
            assertTrue(model.chapterPosition!!.rowIndex >= 100)
        }
    }

    @Test
    fun `duplicate dialog views and migrates actual existing screens then adds with the shared default category`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, manga, _ ->
            val repository = Injekt.get<MangaRepository>()
            val duplicate = repository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 99L,
                        url = "/duplicate",
                        title = manga.title,
                        favorite = true,
                        initialized = true,
                    ),
                ),
            ).single()
            val categoryRepository = Injekt.get<tachiyomi.domain.category.repository.CategoryRepository>()
            categoryRepository.insert(tachiyomi.domain.category.model.Category(0L, "Default actual", 0L, 0L))
            val category = categoryRepository.getAll().single { it.name == "Default actual" }
            Injekt.get<LibraryPreferences>().defaultCategory().set(category.id.toInt())
            click(scene, MR.strings.add_to_library.localized())
            render(scene)
            click(scene, duplicate.title)
            render(scene)
            assertEquals(MangaDetailScreen(duplicate.id), scene.navigator.lastItem)
            assertFalse(scene.navigator.lastItem is cafe.adriel.voyager.navigator.tab.Tab)
            scene.navigator.pop()
            render(scene)
            assertEquals(MangaDetailScreen(manga.id), scene.navigator.lastItem)
            click(scene, MR.strings.add_to_library.localized())
            render(scene)
            click(scene, MR.strings.action_migrate.localized())
            render(scene)
            val migration = scene.navigator.lastItem
            assertTrue(migration is mihon.desktop.ui.migration.MigrationSearchScreen)
            assertEquals(duplicate.id, (migration as mihon.desktop.ui.migration.MigrationSearchScreen).sourceMangaId)
            scene.navigator.pop()
            render(scene)
            click(scene, MR.strings.add_to_library.localized())
            render(scene)
            click(scene, MR.strings.action_add_anyway.localized())
            render(scene)
            assertTrue(repository.getMangaById(manga.id).favorite)
            assertEquals(setOf(category.id), model.categoryIdsForManga(manga.id))
            assertFalse(activeNodes(scene).any { MR.strings.action_add_anyway.localized() in labels(it) })
        }
    }

    @Test
    fun `interval six choices persist only after confirmation and cancellation keeps the previous value`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, mangaTransform = { it.copy(favorite = true) }) { scene, _, manga, _ ->
            val repository = Injekt.get<MangaRepository>()
            for (days in listOf(0, 1, 2, 7, 14, 30)) {
                click(scene, MR.strings.desktop_ui_edit_update_interval.localized())
                render(scene)
                val option = if (days ==
                    0
                ) {
                    MR.strings.label_default.localized()
                } else {
                    MR.strings.desktop_ui_days.localized(java.util.Locale.getDefault(), days)
                }
                click(scene, option)
                render(scene)
                assertTrue(activeNodes(scene).any { MR.strings.desktop_ui_update_interval.localized() in labels(it) })
                click(scene, MR.strings.action_ok.localized())
                render(scene)
                assertEquals(-days, repository.getMangaById(manga.id).fetchInterval)
            }
            click(scene, MR.strings.desktop_ui_edit_update_interval.localized())
            render(scene)
            click(scene, MR.strings.label_default.localized())
            render(scene)
            key(scene, Key.Escape)
            render(scene)
            assertEquals(-30, repository.getMangaById(manga.id).fetchInterval)
            assertFalse(
                activeNodes(scene).any {
                    MR.strings.desktop_ui_update_interval.localized() in labels(it)
                },
                "Escape closes exactly the interval modal",
            )
            assertTrue(
                activeNodes(scene).any {
                    MR.strings.desktop_ui_edit_update_interval.localized() in labels(it) &&
                        it.config.contains(SemanticsProperties.Focused) &&
                        it.config[SemanticsProperties.Focused]
                },
            )
        }
    }

    @Test
    fun `notes draft survives resize theme and background pointer cannot activate the underlying cover`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, manga, _ ->
            val rootCover = activeNodes(scene).first {
                manga.title in labels(it) &&
                    it.config.contains(SemanticsProperties.ContentDescription) &&
                    it.boundsInRoot.width > 0
            }.boundsInRoot.center
            click(scene, MR.strings.label_more.localized())
            render(scene)
            click(scene, MR.strings.action_notes.localized())
            render(scene)
            activeNodes(scene).single {
                it.config.contains(SemanticsActions.SetText)
            }.config[SemanticsActions.SetText].action!!.invoke(
                androidx.compose.ui.text.AnnotatedString("Preserved resize draft"),
            )
            render(scene)
            scene.resize(320, 680)
            scene.fontScale = 2f
            Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().themeMode.set(
                eu.kanade.domain.ui.model.ThemeMode.DARK,
            )
            render(scene)
            assertTrue(model.state.value.showNotesDialog)
            assertEquals("Preserved resize draft", editorText(scene).text)
            val bold = activeNodes(scene).first {
                MR.strings.desktop_notes_bold.localized() in labels(it) &&
                    it.config.contains(SemanticsActions.RequestFocus)
            }
            bold.config[SemanticsActions.RequestFocus].action!!.invoke()
            render(scene)
            key(scene, Key.Spacebar)
            render(scene)
            assertTrue(
                activeNodes(scene).first {
                    MR.strings.desktop_notes_bold.localized() in labels(it) &&
                        it.config.contains(SemanticsProperties.ToggleableState)
                }.config[SemanticsProperties.ToggleableState] ==
                    androidx.compose.ui.state.ToggleableState.On,
            )
            scene.resize(1200, 900)
            scene.fontScale = 1f
            render(scene)
            scene.pointerClick(rootCover)
            render(scene)
            assertFalse(
                activeNodes(scene).any {
                    MR.strings.action_edit.localized() in labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                },
                "background pointer is handled by the modal without opening the actual cover viewer",
            )
            assertEquals("", Injekt.get<MangaRepository>().getMangaById(manga.id).notes)
            assertEquals(1, scene.createdModels.size)
        }
    }

    @Test
    fun `favorite removal unchecked and cancelled download choices preserve actual files`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, mangaTransform = { it.copy(favorite = true) }) { scene, _, manga, chapters ->
            val provider = Injekt.get<mihon.desktop.download.DesktopDownloadProvider>()
            val identity = Injekt.get<mihon.desktop.download.DesktopDownloadIdentityResolver>()
            val file = File(
                provider.canonicalChapterDownloadDir(identity.resolve(manga, chapters.first())).apply {
                    mkdirs()
                },
                "001.png",
            ).apply { writeBytes(png(0xFF00FF00.toInt())) }
            provider.notifyAvailabilityChanged()
            render(scene)
            click(scene, MR.strings.in_library.localized())
            render(scene)
            click(scene, MR.strings.delete_downloads_for_manga.localized())
            render(scene)
            key(scene, Key.Escape)
            render(scene)
            assertFalse(
                activeNodes(scene).any {
                    MR.strings.action_remove.localized() in labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                },
                "Escape closes exactly the current removal modal",
            )
            assertTrue(
                activeNodes(scene).any {
                    MR.strings.in_library.localized() in labels(it) &&
                        it.config.contains(SemanticsProperties.Focused) &&
                        it.config[SemanticsProperties.Focused]
                },
                "cancel returns the membership trigger",
            )
            assertTrue(Injekt.get<MangaRepository>().getMangaById(manga.id).favorite)
            assertTrue(file.exists())
            click(scene, MR.strings.in_library.localized())
            render(scene)
            click(scene, MR.strings.action_remove.localized())
            render(scene)
            assertFalse(Injekt.get<MangaRepository>().getMangaById(manga.id).favorite)
            assertTrue(file.exists(), "unchecked files are outside the accepted removal operation")
        }
    }

    @Test
    fun `duplicate modal owns Escape and returns the actual membership trigger without adding`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, _ ->
            val repository = Injekt.get<MangaRepository>()
            repository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 99L,
                        url = "/duplicate",
                        title = manga.title,
                        favorite = true,
                        initialized = true,
                    ),
                ),
            )
            click(scene, MR.strings.add_to_library.localized())
            render(scene)
            assertTrue(activeNodes(scene).any { MR.strings.possible_duplicates_title.localized() in labels(it) })
            key(scene, Key.Escape)
            render(scene)
            assertFalse(
                activeNodes(scene).any {
                    MR.strings.possible_duplicates_title.localized() in labels(it)
                },
                "Escape closes only the duplicate modal",
            )
            assertFalse(repository.getMangaById(manga.id).favorite)
            assertTrue(
                activeNodes(scene).any {
                    MR.strings.add_to_library.localized() in labels(it) &&
                        it.config.contains(SemanticsProperties.Focused) &&
                        it.config[SemanticsProperties.Focused]
                },
            )
        }
    }

    @Test
    fun `actual persisted prediction is distinct from default custom and automatic check intervals`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, mangaTransform = { it.copy(favorite = true) }) { scene, _, manga, _ ->
            val repository = Injekt.get<MangaRepository>()
            Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().dateFormat.set("yyyy-MM-dd")
            val predicted = java.time.LocalDate.of(
                2026,
                10,
                6,
            ).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            assertTrue(
                repository.update(
                    MangaUpdate(
                        manga.id,
                        nextUpdate = predicted,
                        fetchInterval = -7,
                        status = eu.kanade.tachiyomi.source.model.SManga.ONGOING.toLong(),
                    ),
                ),
            )
            render(scene)
            assertTrue(
                activeNodes(scene).flatMap(::labels).any {
                    "2026-10-06" in it
                },
                "actual nextUpdate is shown using the saved date format",
            )
            assertTrue(
                activeNodes(scene).flatMap(::labels).any {
                    MR.strings.manga_interval_custom_amount.localized() in
                        it &&
                        "7" in it
                },
                "the custom check interval is not the predicted date",
            )
            assertTrue(repository.update(MangaUpdate(manga.id, nextUpdate = 0, fetchInterval = 0)))
            render(scene)
            assertTrue(
                activeNodes(scene).flatMap(::labels).any {
                    MR.strings.manga_interval_expected_update_null.localized() in
                        it
                },
            )
            assertFalse(activeNodes(scene).flatMap(::labels).any { "1970" in it })
            assertTrue(activeNodes(scene).flatMap(::labels).any { MR.strings.label_default.localized() in it })
            assertTrue(
                repository.update(
                    MangaUpdate(
                        manga.id,
                        nextUpdate = predicted,
                        fetchInterval = 14,
                        status = eu.kanade.tachiyomi.source.model.SManga.COMPLETED.toLong(),
                    ),
                ),
            )
            render(scene)
            assertTrue(
                activeNodes(scene).flatMap(::labels).any {
                    MR.strings.manga_interval_expected_update_null.localized() in
                        it
                },
            )
            assertFalse(activeNodes(scene).flatMap(::labels).any { "2026-10-06" in it })
            assertTrue(activeNodes(scene).flatMap(::labels).any { "Automatic" in it && "14" in it })
        }
    }

    @Test
    fun `narrow large font primary actions have real in-window bounds and sequential keyboard reachability`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, mangaTransform = { it.copy(favorite = true) }, httpSource = true) { scene, _, _, _ ->
            scene.resize(320, 680)
            scene.fontScale = 2f
            Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().themeMode.set(
                eu.kanade.domain.ui.model.ThemeMode.LIGHT,
            )
            render(scene)
            val labels =
                listOf(
                    MR.strings.in_library.localized(),
                    MR.strings.desktop_ui_edit_update_interval.localized(),
                    MR.strings.pref_category_tracking.localized(),
                    MR.strings.action_open_in_browser.localized(),
                    MR.strings.action_copy_link.localized(),
                    MR.strings.desktop_ui_share_link.localized(),
                )
            labels.forEach { label ->
                assertTrue(
                    activeNodes(scene).any {
                        label in labels(it) && it.config.contains(SemanticsActions.OnClick)
                    },
                    "actual $label action must be mounted with the real HTTP source",
                )
                val bounds = activeNodes(scene).first {
                    label in labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                }.boundsInRoot
                val hasSize = bounds.width > 0 && bounds.height > 0
                val withinWindow = bounds.left >= 0 && bounds.right <= 320 && bounds.top >= 0 && bounds.bottom <= 680
                assertTrue(hasSize && withinWindow, "$label actual actionable bounds=$bounds")
            }
            scene.savePng(visualFile(root, "ri08-detail-actions-320-font200-light.png"))
            activeNodes(scene).first {
                labels.first() in labels(it) && it.config.contains(SemanticsActions.RequestFocus)
            }.config[SemanticsActions.RequestFocus].action!!.invoke()
            render(scene)
            val reached = mutableSetOf<String>()
            repeat(6) {
                reached += labels.filter { it in labels(focused(scene)) }
                key(scene, Key.Tab)
                render(scene)
            }
            assertEquals(labels.toSet(), reached, "all original action capabilities stay reachable by native Tab")
        }
    }

    @Test
    fun `local chapter download placeholder cannot enqueue from actual pointer click`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, mangaTransform = { it.copy(source = 0L) }) { scene, _, _, _ ->
            val download = scene.owners.flatMap { flatten(it.unmergedRootSemanticsNode) }.first {
                MR.strings.action_download.localized() in
                    labels(it) &&
                    it.config.contains(SemanticsProperties.ContentDescription)
            }
            scene.pointerClick(download.boundsInRoot.center)
            render(scene)
            assertTrue(
                Injekt.get<mihon.desktop.download.DesktopDownloadManager>().queue.value.isEmpty(),
                "local download icon is only a disabled placeholder",
            )
        }
    }

    @Test
    fun `ordinary download indicator paints the upstream circular arrow rather than cloud`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, _, _ ->
            val icon = scene.owners.flatMap { flatten(it.unmergedRootSemanticsNode) }.first {
                MR.strings.action_download.localized() in
                    labels(it) &&
                    it.config.contains(SemanticsProperties.ContentDescription)
            }
            val point = Offset(icon.boundsInRoot.center.x, icon.boundsInRoot.top + 3.5f)
            val foreground = scene.colors.onSurfaceVariant.toArgb()
            val background = scene.colors.surface.toArgb()
            val alpha = 199 // .78 SOURCE alpha, represented by the native 8-bit premultiplied layer.
            val expected = listOf(16, 8, 0).fold(0) { color, shift ->
                val source = foreground ushr shift and 255
                val backdrop = background ushr shift and 255
                color or
                    (
                        ((source * alpha + 127) / 255 + (backdrop * (255 - alpha) + 127) / 255).coerceAtMost(
                            255,
                        ) shl shift
                        )
            }
            assertEquals(
                expected,
                scene.snapshot().getRGB(point.x.toInt(), point.y.toInt()) and 0xFFFFFF,
                "SOURCE circle arrow has a solid upper ring at this native pixel",
            )
        }
    }

    @Test
    fun `notes modal preserves long draft through rejection retry and explicit cancel`(
        @TempDir root: File,
    ) = runBlocking {
        var reject = true
        val note = (1..300).joinToString(" ") { "Long note $it" }
        withDetail(root, mangaRepositoryOverride = { actual ->
            object : MangaRepository by actual {
                override suspend fun update(update: MangaUpdate): Boolean {
                    if (reject && update.notes != null) {
                        throw java.io.IOException("notes storage refused")
                    }
                    return actual.update(update)
                }
            }
        }) { scene, model, manga, _ ->
            click(scene, MR.strings.label_more.localized())
            render(scene)
            click(scene, MR.strings.action_notes.localized())
            render(scene)
            setEditorText(scene, note)
            render(scene)
            click(scene, MR.strings.action_save.localized())
            render(scene)
            assertTrue(model.state.value.showNotesDialog)
            val failureMessage = MR.strings.desktop_notes_save_failed.localized()
            assertTrue(activeNodes(scene).any { failureMessage in labels(it) })
            assertEquals(note, editorText(scene).text)
            reject = false
            click(scene, MR.strings.action_save.localized())
            render(scene)
            assertEquals(note, Injekt.get<MangaRepository>().getMangaById(manga.id).notes)
            assertFalse(model.state.value.showNotesDialog)
            click(scene, MR.strings.label_more.localized())
            render(scene)
            click(scene, MR.strings.action_notes.localized())
            render(scene)
            assertEquals(note, editorText(scene).text)
            setEditorText(scene, "discarded draft")
            key(scene, Key.Escape)
            render(scene)
            assertEquals(note, Injekt.get<MangaRepository>().getMangaById(manga.id).notes)
        }
    }

    @Test
    fun `notes native modal keeps five formats save cancel and bidirectional focus reachable at 320 font200`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, manga, _ ->
            scene.resize(320, 680)
            scene.fontScale = 2f
            render(scene)
            for (mode in listOf(eu.kanade.domain.ui.model.ThemeMode.LIGHT, eu.kanade.domain.ui.model.ThemeMode.DARK)) {
                Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().themeMode.set(mode)
                render(scene)
                click(scene, MR.strings.label_more.localized())
                render(scene)
                click(scene, MR.strings.action_notes.localized())
                render(scene)
                assertTrue(focused(scene).config.contains(SemanticsProperties.EditableText))
                val controls =
                    listOf(
                        MR.strings.desktop_notes_bold.localized(),
                        MR.strings.desktop_notes_italic.localized(),
                        MR.strings.desktop_notes_underline.localized(),
                        MR.strings.desktop_notes_unordered.localized(),
                        MR.strings.desktop_notes_ordered.localized(),
                        MR.strings.action_save.localized(),
                        MR.strings.action_cancel.localized(),
                    )
                controls.forEach { name ->
                    val bounds = activeNodes(scene).first {
                        name in labels(it) &&
                            it.config.contains(SemanticsActions.OnClick)
                    }.boundsInRoot
                    assertTrue(
                        bounds.left >= 0f && bounds.right <= 320f && bounds.top >= 0f && bounds.bottom <= 680f &&
                            bounds.height >= 24f &&
                            bounds.width >= 24f,
                        "$mode $name is actually reachable, bounds=$bounds",
                    )
                }
                val original = focused(scene).id
                val forward = mutableSetOf(original)
                repeat(16) {
                    key(scene, Key.Tab)
                    render(scene)
                    forward += focused(scene).id
                }
                assertTrue(forward.size >= 8, "all eight modal controls are keyboard reachable: ids=$forward")
                val backward = mutableSetOf(focused(scene).id)
                repeat(16) {
                    key(scene, Key.Tab, shift = true)
                    render(scene)
                    backward += focused(scene).id
                }
                assertEquals(forward, backward, "Tab and Shift Tab stay inside the same modal controls")
                setEditorText(scene, "Native draft")
                render(scene)
                scene.savePng(visualFile(root, "ri08-notes-320-font200-${mode.name.lowercase()}.png"))
                key(scene, Key.Escape)
                render(scene)
                assertFalse(model.state.value.showNotesDialog)
                assertEquals("", Injekt.get<MangaRepository>().getMangaById(manga.id).notes)
                assertTrue(
                    activeNodes(scene).any {
                        MR.strings.label_more.localized() in labels(it) &&
                            it.config.contains(SemanticsProperties.Focused) &&
                            it.config[SemanticsProperties.Focused]
                    },
                    "Escape returns focus to the actual More trigger",
                )
                assertEquals(1, scene.createdModels.size)
            }
        }
    }

    @Test
    fun `title context copies actual value and reports clipboard success and refusal`(
        @TempDir root: File,
    ) = runBlocking {
        var copied: String? = null
        var reject = false
        val share = mihon.desktop.platform.DesktopShareService(
            clipboardPort = object : mihon.desktop.platform.DesktopClipboardPort {
                override fun copyText(
                    text: String,
                ) {
                    if (reject) throw IllegalStateException("clipboard busy")
                    copied =
                        text
                }
                override fun copyImage(image: java.awt.image.BufferedImage) = error("not used")
            },
            isHeadless = { false },
        )
        withDetail(root, shareService = share) { scene, _, manga, _ ->
            val notifications = mutableListOf<mihon.desktop.domain.DesktopNotification>()
            val collector =
                launch(Dispatchers.Unconfined) {
                    Injekt.get<mihon.desktop.domain.DesktopNotificationService>().notifications.collect {
                        notifications +=
                            it
                    }
                }
            try {
                val title = activeNodes(scene).first {
                    it.config.contains(SemanticsProperties.Text) &&
                        manga.title in labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                }
                scene.pointerSecondaryClick(title.boundsInRoot.center)
                render(scene)
                assertTrue(
                    activeNodes(scene).any {
                        MR.strings.action_copy_to_clipboard.localized() in labels(it)
                    },
                    "title context provides the actual copy action",
                )
                click(scene, MR.strings.action_copy_to_clipboard.localized())
                render(scene)
                assertEquals(manga.title, copied)
                assertEquals(
                    mihon.desktop.platform.DesktopShareResult.CopiedToClipboard.toDesktopNotification(),
                    notifications.last(),
                )
                reject = true
                scene.pointerSecondaryClick(
                    activeNodes(scene).first {
                        it.config.contains(SemanticsProperties.Text) &&
                            manga.title in labels(it) &&
                            it.config.contains(SemanticsActions.OnClick)
                    }.boundsInRoot.center,
                )
                render(scene)
                click(scene, MR.strings.action_copy_to_clipboard.localized())
                render(scene)
                assertEquals(
                    mihon.desktop.platform.DesktopShareResult.Failed(
                        mihon.desktop.platform.DesktopShareFailureReason.CLIPBOARD_BUSY,
                    ).toDesktopNotification(),
                    notifications.last(),
                )
            } finally {
                collector.cancel()
            }
        }
    }

    @Test
    fun `chapter unread marker precedes title and read typography consumes source alpha`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, _, chapters ->
            val title = scene.owners.flatMap { flatten(it.unmergedRootSemanticsNode) }.filter {
                "Chapter 1" in
                    labels(it) &&
                    it.config.contains(SemanticsActions.GetTextLayoutResult) &&
                    it.boundsInRoot.width > 0 &&
                    it.boundsInRoot.height > 0
            }.minBy { it.boundsInRoot.width * it.boundsInRoot.height }
            val dot = scene.owners.flatMap { flatten(it.unmergedRootSemanticsNode) }.filter {
                MR.strings.unread.localized() in
                    labels(it) &&
                    it.boundsInRoot.width > 0 &&
                    it.boundsInRoot.center.y in title.boundsInRoot.top..title.boundsInRoot.bottom
            }.minBy {
                it.boundsInRoot.width *
                    it.boundsInRoot.height
            }
            assertTrue(
                dot.boundsInRoot.right < title.boundsInRoot.left,
                "same chapter unread point=$dot title=${title.boundsInRoot}",
            )
            val dotBounds = dot.boundsInRoot
            val bitmap = scene.snapshot()
            val pixels = (dotBounds.left.toInt() until dotBounds.right.toInt()).flatMap { x ->
                (
                    dotBounds.top.toInt() until
                        dotBounds.bottom.toInt()
                    ).map { y -> bitmap.getRGB(x, y) and 0xFFFFFF }
            }
            Injekt.get<ChapterRepository>().update(
                tachiyomi.domain.chapter.model.ChapterUpdate(chapters.first().id, read = true),
            )
            render(scene)
            val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            activeNodes(scene).first {
                "Chapter 1" in labels(it) &&
                    it.config.contains(SemanticsActions.GetTextLayoutResult)
            }.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
            assertEquals(scene.colors.onSurface.copy(alpha = .38f), layout.single().layoutInput.style.color)
            assertTrue(
                scene.colors.primary.toArgb() and 0xFFFFFF in pixels,
                "Unread dot must paint exact primary: bounds=$dotBounds pixels=$pixels",
            )
        }
    }

    @Test
    fun `creator context role search and primary identity use real ordinary navigation`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, mangaTransform = {
            it.copy(author = "Actual Writer", artist = "Actual Artist")
        }) { scene, _, manga, _ ->
            val writer = activeNodes(scene).first {
                "Actual Writer" in labels(it) &&
                    it.config.contains(SemanticsActions.OnClick)
            }
            scene.pointerSecondaryClick(writer.boundsInRoot.center)
            render(scene)
            assertTrue(
                activeNodes(scene).any {
                    MR.strings.author.localized() in labels(it)
                },
                "context explicitly names the creator role",
            )
            assertTrue(
                activeNodes(scene).any {
                    MR.strings.action_search.localized() in labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                },
                "creator context provides actual role search",
            )
            click(scene, MR.strings.action_search.localized())
            render(scene)
            val search = scene.navigator.lastItem
            assertTrue(search is mihon.desktop.ui.browse.GlobalSearchScreen)
            assertEquals("Actual Writer", (search as mihon.desktop.ui.browse.GlobalSearchScreen).initialQuery)
            scene.navigator.pop()
            render(scene)
            click(scene, "Actual Writer")
            render(scene)
            assertTrue(scene.navigator.lastItem is mihon.desktop.ui.authors.AuthorDetailScreen)
            assertEquals(manga.id, scene.createdModels.first().mangaId)
        }
    }

    @Test
    fun `tag context global search is distinct from primary current source and long tags wrap`(
        @TempDir root: File,
    ) = runBlocking {
        val tag = "A very long genre name that remains fully readable in a narrow information column"
        withDetail(root, mangaTransform = { it.copy(genre = listOf(tag)) }) { scene, _, manga, _ ->
            val chip = activeNodes(scene).first { tag in labels(it) && it.config.contains(SemanticsActions.OnClick) }
            scene.pointerSecondaryClick(chip.boundsInRoot.center)
            render(scene)
            assertTrue(
                activeNodes(scene).any {
                    MR.strings.action_global_search.localized() in labels(it)
                },
                "tag context must offer a separate global search scope",
            )
            assertTrue(activeNodes(scene).any { MR.strings.action_copy_to_clipboard.localized() in labels(it) })
            click(scene, MR.strings.action_global_search.localized())
            render(scene)
            assertTrue(scene.navigator.lastItem is mihon.desktop.ui.browse.GlobalSearchScreen)
            assertEquals(tag, (scene.navigator.lastItem as mihon.desktop.ui.browse.GlobalSearchScreen).initialQuery)
            scene.navigator.pop()
            render(scene)
            click(scene, tag)
            render(scene)
            assertEquals(
                manga.source,
                (scene.navigator.lastItem as mihon.desktop.ui.browse.SourceBrowseScreen).sourceId,
            )
            scene.navigator.pop()
            render(scene)
            scene.resize(320, 680)
            scene.fontScale = 2f
            render(scene)
            val info = nodes(scene).first { it.config.contains(SemanticsActions.ScrollToIndex) }
            requireNotNull(info.config[SemanticsActions.ScrollToIndex].action).invoke(0)
            render(scene)
            val text = nodes(scene).first {
                tag in labels(it) &&
                    it.config.contains(SemanticsActions.GetTextLayoutResult)
            }
            val result = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            requireNotNull(text.config[SemanticsActions.GetTextLayoutResult].action).invoke(result)
            assertTrue(result.single().lineCount > 1)
            assertFalse((0 until result.single().lineCount).any { result.single().isLineEllipsized(it) })
        }
    }

    @Test
    fun `missing source metadata remains actionable through current source recovery screen`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, _ ->
            val expected = MR.strings.source_not_installed.localized(
                java.util.Locale.getDefault(),
                manga.source.toString(),
            )
            assertTrue(
                activeNodes(scene).any {
                    expected in labels(it) && it.config.contains(SemanticsActions.OnClick)
                },
                "missing source must be explicit and still provide the source route",
            )
            click(scene, expected)
            render(scene)
            assertTrue(scene.navigator.lastItem is mihon.desktop.ui.browse.SourceBrowseScreen)
            assertEquals(
                manga.source,
                (scene.navigator.lastItem as mihon.desktop.ui.browse.SourceBrowseScreen).sourceId,
            )
            assertTrue(
                activeNodes(scene).any {
                    expected in labels(it)
                },
                "missing source route must show an explicit recoverable state rather than an empty list",
            )
            click(scene, MR.strings.label_extensions.localized())
            render(scene)
            assertEquals("ExtensionListScreen", scene.navigator.lastItem::class.simpleName)
            scene.navigator.pop()
            render(scene)
            scene.navigator.pop()
            render(scene)
            assertEquals(MangaDetailScreen(manga.id), scene.navigator.lastItem)
        }
    }

    @Test
    fun `processed chapter header opens same settings and ordinary rows show only real bookmarks`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, _, chapters ->
            assertTrue(
                activeNodes(scene).any {
                    MR.strings.desktop_ui_chapter_count.localized(java.util.Locale.getDefault(), 200) in
                        labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                },
                "processed chapter count is the real settings entry",
            )
            click(scene, MR.strings.desktop_ui_chapter_count.localized(java.util.Locale.getDefault(), 200))
            render(scene)
            assertTrue(model.state.value.showFilterMenu)
            key(scene, Key.Escape)
            render(scene)
            assertFalse(
                activeNodes(scene).any {
                    MR.strings.action_bookmark.localized() in labels(it)
                },
                "ordinary unbookmarked rows do not advertise a duplicate hollow bookmark action",
            )
            Injekt.get<ChapterRepository>().update(
                tachiyomi.domain.chapter.model.ChapterUpdate(chapters.first().id, bookmark = true),
            )
            render(scene)
            assertTrue(activeNodes(scene).any { MR.strings.action_remove_bookmark.localized() in labels(it) })
            assertFalse(
                activeNodes(scene).any {
                    MR.strings.desktop_ui_continue_reading.localized() in labels(it) &&
                        it.config.contains(SemanticsProperties.ContentDescription)
                },
                "ordinary unread indication is a leading dot rather than a trailing duplicate read button",
            )
        }
    }

    @Test
    fun `cover editing keeps actual file and pixels on rejection cancellation and deletes back to source`(
        @TempDir root: File,
    ) = runBlocking {
        val source = File(root, "edit-source.png").apply { writeBytes(png(0xFF00FF00.toInt())) }
        var picked: ByteArray? = png(0xFFFF0000.toInt())
        withDetail(root, mangaTransform = {
            it.copy(thumbnailUrl = source.absolutePath)
        }, coverRequests = mutableListOf(), coverPicker = CoverFilePicker { picked }) { scene, model, manga, _ ->
            assertCoverPixel(scene, manga.title, 0xFF00FF00.toInt())
            nodes(scene).first {
                it.config.contains(SemanticsProperties.ContentDescription) &&
                    manga.title in it.config[SemanticsProperties.ContentDescription] &&
                    it.config.contains(SemanticsActions.OnClick)
            }.config[SemanticsActions.OnClick].action!!.invoke()
            render(scene)
            click(scene, MR.strings.action_edit_cover.localized())
            render(scene)
            assertFalse(activeNodes(scene).any { MR.strings.desktop_ui_delete_cover.localized() in labels(it) })
            click(scene, MR.strings.action_edit_cover.localized())
            assertCoverPixel(scene, manga.title, 0xFFFF0000.toInt())
            val store = Injekt.get<mihon.desktop.domain.DesktopCustomCoverStore>()
            val custom = store.getCustomCoverFile(manga.id)
            val old = custom.readBytes()
            val version = Injekt.get<MangaRepository>().getMangaById(manga.id).coverLastModified
            assertEquals(version, model.state.value.coverLastModified)
            picked = null
            click(scene, MR.strings.action_edit_cover.localized())
            render(scene)
            click(scene, MR.strings.action_edit_cover.localized())
            render(scene)
            assertEquals(version, Injekt.get<MangaRepository>().getMangaById(manga.id).coverLastModified)
            val restore = denyFileChanges(custom)
            try {
                picked = png(0xFF0000FF.toInt())
                click(scene, MR.strings.action_edit_cover.localized())
                render(scene)
                click(scene, MR.strings.action_edit_cover.localized())
                render(scene)
                assertTrue(old.contentEquals(custom.readBytes()), "rejected replacement preserves old image bytes")
                assertEquals(version, Injekt.get<MangaRepository>().getMangaById(manga.id).coverLastModified)
                assertTrue(model.state.value.coverTask is mihon.domain.task.TaskState.Failure)
                assertCoverPixel(scene, manga.title, 0xFFFF0000.toInt())
                click(scene, MR.strings.action_edit_cover.localized())
                render(scene)
                click(scene, MR.strings.desktop_ui_delete_cover.localized())
                render(scene)
                assertTrue(custom.exists())
                assertTrue(model.state.value.coverTask is mihon.domain.task.TaskState.Failure)
            } finally {
                restore()
            }
            source.writeBytes(png(0xFF0000FF.toInt()))
            click(scene, MR.strings.action_edit_cover.localized())
            render(scene)
            click(scene, MR.strings.desktop_ui_delete_cover.localized())
            assertCoverPixel(scene, manga.title, 0xFF0000FF.toInt())
            assertFalse(custom.exists())
            assertEquals(
                Injekt.get<MangaRepository>().getMangaById(manga.id).coverLastModified,
                model.state.value.coverLastModified,
            )
            key(scene, Key.Escape)
            render(scene)
            assertFalse(
                activeNodes(scene).any {
                    MR.strings.action_close.localized() in labels(it)
                },
                "Escape after editing closes the viewer itself",
            )
            val focusedCover = activeNodes(scene).first {
                it.config.contains(SemanticsProperties.Focused) &&
                    it.config[SemanticsProperties.Focused] &&
                    it.config.contains(SemanticsProperties.ContentDescription) &&
                    manga.title in it.config[SemanticsProperties.ContentDescription]
            }
            val coverBounds = focusedCover.boundsInRoot
            key(scene, Key.Tab)
            render(scene)
            assertCoverPixel(scene, manga.title, 0xFF0000FF.toInt())
            val feedbackLayout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            activeNodes(scene).first {
                MR.strings.desktop_ui_cover_deleted.localized() in labels(it) &&
                    it.config.contains(SemanticsActions.GetTextLayoutResult)
            }.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(feedbackLayout)
            assertEquals(
                scene.colors.primary,
                feedbackLayout.single().layoutInput.style.color,
                "delete success must not be presented as an error",
            )
        }
    }

    @Test
    fun `remove favorite reports actual fixed download deletion refusal and permits retry without expanding snapshot`(
        @TempDir root: File,
    ) = runBlocking {
        var membershipWrites = 0
        withDetail(root, mangaTransform = { it.copy(favorite = true) }, mangaRepositoryOverride = { actual ->
            object : MangaRepository by actual {
                override suspend fun updateAtomically(
                    update: tachiyomi.domain.manga.repository.LibraryMembershipUpdate,
                ) {
                    membershipWrites++
                    actual.updateAtomically(update)
                }
            }
        }) { scene, model, manga, chapters ->
            val provider = Injekt.get<mihon.desktop.download.DesktopDownloadProvider>()
            val identity = Injekt.get<mihon.desktop.download.DesktopDownloadIdentityResolver>()
            fun create(
                chapter: Chapter,
            ): File = File(
                provider.canonicalChapterDownloadDir(identity.resolve(manga, chapter)).apply {
                    mkdirs()
                },
                "001.png",
            ).apply { writeBytes(png(0xFF00FF00.toInt())) }
            Injekt.get<ChapterRepository>().update(ChapterUpdate(chapters[1].id, scanlator = "Excluded group"))
            Injekt.get<mihon.desktop.domain.SetExcludedScanlators>().await(manga.id, setOf("Excluded group"))
            val first = create(chapters.first())
            val refused = create(chapters[1])
            val orphan = File(provider.canonicalMangaDownloadDir(identity.resolve(manga)), "Existing orphan")
                .apply { mkdirs() }.resolve("001.png").apply { writeBytes(png(0xFF00FF00.toInt())) }
            provider.notifyAvailabilityChanged()
            render(scene)
            assertTrue(model.isChapterDownloaded(manga, chapters.first()))
            click(scene, MR.strings.in_library.localized())
            render(scene)
            click(scene, MR.strings.delete_downloads_for_manga.localized())
            render(scene)
            val later = create(chapters[2])
            val restore = denyFileChanges(refused)
            try {
                click(scene, MR.strings.action_remove.localized())
                render(scene)
                assertFalse(
                    Injekt.get<MangaRepository>().getMangaById(manga.id).favorite,
                    "membership was committed before file rejection",
                )
                assertTrue(
                    activeNodes(scene).any {
                        MR.strings.desktop_detail_removal_partial.localized() in labels(it)
                    },
                    "partial completion must be explicit rather than imply membership rollback",
                )
                assertFalse(first.exists(), "the first original artifact succeeds before the refused artifact")
                assertFalse(
                    orphan.exists(),
                    "the original known manga directory includes downloads without a SQL chapter",
                )
                assertTrue(refused.exists())
                assertTrue(later.exists())
            } finally {
                restore()
            }
            val replacement = create(chapters.first())
            click(scene, MR.strings.action_remove.localized())
            render(scene)
            assertTrue(replacement.exists(), "partial retry cannot delete a new file at an already successful artifact")
            assertFalse(refused.exists())
            assertTrue(later.exists(), "a download added after opening is outside the frozen snapshot")
            assertFalse(Injekt.get<MangaRepository>().getMangaById(manga.id).favorite)
            assertEquals(1, membershipWrites, "file retry does not repeat membership or sync journal writes")
            assertFalse(activeNodes(scene).any { MR.strings.action_remove.localized() in labels(it) })
        }
    }

    @Test
    fun `detail consumes actual persisted cover version in the real Coil request and pixels`(
        @TempDir root: File,
    ) = runBlocking {
        val file = File(root, "source-cover.png").apply { writeBytes(png(0xFF00FF00.toInt())) }
        val requests = java.util.concurrent.CopyOnWriteArrayList<coil3.request.ImageRequest>()
        withDetail(root, mangaTransform = {
            it.copy(thumbnailUrl = file.absolutePath, coverLastModified = 10)
        }, coverRequests = requests) { scene, _, manga, _ ->
            assertCoverPixel(scene, manga.title, 0xFF00FF00.toInt())
            assertEquals("manga-cover:${manga.id}:10:${file.absolutePath}", requests.last().memoryCacheKey)
            assertEquals(requests.last().memoryCacheKey, requests.last().diskCacheKey)
            file.writeBytes(png(0xFFFF0000.toInt()))
            assertTrue(Injekt.get<MangaRepository>().update(MangaUpdate(manga.id, coverLastModified = 20)))
            render(scene)
            assertCoverPixel(scene, manga.title, 0xFFFF0000.toInt())
            assertEquals("manga-cover:${manga.id}:20:${file.absolutePath}", requests.last().memoryCacheKey)
        }
    }

    @Test
    fun `cover primary click opens actual viewer with zoom and Escape restores the visible trigger`(
        @TempDir root: File,
    ) = runBlocking {
        val file = File(root, "viewer-cover.png").apply { writeBytes(png(0xFF00FF00.toInt())) }
        withDetail(root, mangaTransform = {
            it.copy(thumbnailUrl = file.absolutePath)
        }, coverRequests = mutableListOf()) { scene, _, manga, _ ->
            assertCoverPixel(scene, manga.title, 0xFF00FF00.toInt())
            val coverActions = nodes(scene).filter { node ->
                node.config.contains(SemanticsProperties.ContentDescription) &&
                    manga.title in node.config[SemanticsProperties.ContentDescription] &&
                    node.config.contains(SemanticsActions.OnClick)
            }
            assertTrue(
                coverActions.isNotEmpty(),
                "the cover body has a real viewer action distinct from its edit overlay",
            )
            requireNotNull(coverActions.first().config[SemanticsActions.OnClick].action).invoke()
            render(scene)
            assertTrue(activeNodes(scene).any { MR.strings.action_close.localized() in labels(it) })
            val before = activeNodes(scene).first {
                it.config.contains(SemanticsProperties.ContentDescription) &&
                    manga.title in it.config[SemanticsProperties.ContentDescription]
            }.boundsInRoot
            val zoom = activeNodes(scene).single { it.config.contains(SemanticsActions.SetProgress) }
            assertTrue(requireNotNull(zoom.config[SemanticsActions.SetProgress].action).invoke(2f))
            render(scene)
            val after = activeNodes(scene).first {
                it.config.contains(SemanticsProperties.ContentDescription) &&
                    manga.title in it.config[SemanticsProperties.ContentDescription]
            }.boundsInRoot
            assertTrue(after.width > before.width * 1.5f, "actual rendered image geometry responds to zoom")
            key(scene, Key.Escape)
            render(scene)
            assertEquals(MangaDetailScreen(manga.id), scene.navigator.lastItem)
            assertTrue(
                nodes(scene).any { node ->
                    node.config.contains(SemanticsProperties.Focused) &&
                        node.config[SemanticsProperties.Focused] &&
                        node.config.contains(SemanticsProperties.ContentDescription) &&
                        manga.title in node.config[SemanticsProperties.ContentDescription]
                },
            )
        }
    }

    @Test
    fun `stored notes summary renders rich text instead of raw markdown markers`(@TempDir root: File) = runBlocking {
        withDetail(root, mangaTransform = { it.copy(notes = "**Styled summary**") }) { scene, _, _, _ ->
            assertTrue(
                nodes(scene).any { node ->
                    node.config.contains(SemanticsProperties.Text) &&
                        node.config[SemanticsProperties.Text].any { text ->
                            text.text == "Styled summary" &&
                                text.spanStyles.any {
                                    it.item.fontWeight ==
                                        androidx.compose.ui.text.font.FontWeight.Bold
                                }
                        }
                },
                "persisted notes render through the same rich editor SDK and preserve their style",
            )
        }
    }

    @Test
    fun `short description has a measured expansion control in a narrow window at double font size`(
        @TempDir root: File,
    ) = runBlocking {
        val description = "A short description becomes tall when a narrow window uses a large font." +
            " Every word remains readable through a measured expansion control."
        assertTrue(description.length < 240)
        withDetail(root, mangaTransform = { it.copy(description = description) }) { scene, _, _, _ ->
            scene.resize(320, 680)
            scene.fontScale = 2f
            render(scene)
            val list = nodes(scene).single { it.config.contains(SemanticsActions.ScrollBy) }
            requireNotNull(list.config[SemanticsActions.ScrollBy].action).invoke(0f, 250f)
            render(scene)
            assertTrue(
                nodes(scene).any {
                    MR.strings.manga_info_expand.localized() in labels(it) &&
                        it.config.contains(SemanticsActions.OnClick) &&
                        it.boundsInRoot.top > 64f
                },
                "real text overflow must have a reachable expansion even below the old character threshold",
            )
        }
    }

    @Test
    fun `More migration pushes the existing ordinary search Screen in the detail navigator`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, _ ->
            click(scene, MR.strings.label_more.localized())
            render(scene)
            click(scene, MR.strings.desktop_ui_migrate_source.localized())
            render(scene)
            val migration = scene.navigator.lastItem
            assertTrue(
                migration is mihon.desktop.ui.migration.MigrationSearchScreen,
                "migration uses an ordinary nested Screen instead of the old picker dialog",
            )
            assertEquals(manga.id, (migration as mihon.desktop.ui.migration.MigrationSearchScreen).sourceMangaId)
            assertFalse(migration is cafe.adriel.voyager.navigator.tab.Tab)
            click(scene, MR.strings.desktop_ui_back.localized())
            render(scene)
            assertEquals(MangaDetailScreen(manga.id), scene.navigator.lastItem)
        }
    }

    @Test
    fun `duplicate library entry is resolved by the real factory before adding and cancellation does not add`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, _ ->
            val repository = Injekt.get<MangaRepository>()
            val duplicate = repository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 99,
                        url = "/duplicate",
                        title = manga.title,
                        favorite = true,
                        initialized = true,
                    ),
                ),
            ).single()
            click(scene, MR.strings.add_to_library.localized())
            render(scene)
            assertFalse(
                repository.getMangaById(manga.id).favorite,
                "the actual duplicate policy pauses membership until confirmation",
            )
            assertTrue(activeNodes(scene).any { MR.strings.possible_duplicates_title.localized() in labels(it) })
            assertTrue(activeNodes(scene).any { duplicate.title in labels(it) })
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            assertFalse(repository.getMangaById(manga.id).favorite)
            assertTrue(repository.getMangaById(duplicate.id).favorite)
        }
    }

    @Test
    fun `favorite removal asks before changing SQLite and cancellation preserves membership`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, mangaTransform = { it.copy(favorite = true) }) { scene, _, manga, _ ->
            click(scene, MR.strings.in_library.localized())
            render(scene)
            assertTrue(
                Injekt.get<MangaRepository>().getMangaById(manga.id).favorite,
                "opening a dangerous removal cannot itself remove the manga",
            )
            assertTrue(activeNodes(scene).any { MR.strings.remove_from_library.localized() in labels(it) })
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            assertTrue(Injekt.get<MangaRepository>().getMangaById(manga.id).favorite)
        }
    }

    @Test
    fun `interval write rejection keeps the current draft dialog and retries the shared negative encoding`(
        @TempDir root: File,
    ) = runBlocking {
        var reject = true
        withDetail(root, mangaTransform = { it.copy(favorite = true) }, mangaRepositoryOverride = { actual ->
            object : MangaRepository by actual {
                override suspend fun update(update: MangaUpdate): Boolean = if (reject &&
                    update.fetchInterval != null
                ) {
                    false
                } else {
                    actual.update(update)
                }
            }
        }) { scene, _, manga, _ ->
            click(scene, MR.strings.desktop_ui_edit_update_interval.localized())
            render(scene)
            click(scene, MR.strings.desktop_ui_days.localized(java.util.Locale.getDefault(), 7))
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertTrue(
                activeNodes(scene).any {
                    MR.strings.desktop_ui_update_interval.localized() in labels(it)
                },
                "a real rejected interval write keeps the draft owner",
            )
            assertEquals(0, Injekt.get<MangaRepository>().getMangaById(manga.id).fetchInterval)
            reject = false
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertEquals(-7, Injekt.get<MangaRepository>().getMangaById(manga.id).fetchInterval)
        }
    }

    @Test
    fun `five notes formats persist real markdown and render styled text after reopening`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, _ ->
            val formats = listOf(
                MR.strings.desktop_notes_bold to "**Format sample**",
                MR.strings.desktop_notes_italic to "*Format sample*",
                MR.strings.desktop_notes_underline to "<u>Format sample</u>",
                MR.strings.desktop_notes_unordered to "- Format sample",
                MR.strings.desktop_notes_ordered to "1. Format sample",
            )
            for ((format, markdown) in formats) {
                assertTrue(Injekt.get<MangaRepository>().update(MangaUpdate(manga.id, notes = "")))
                render(scene)
                click(scene, MR.strings.label_more.localized())
                render(scene)
                click(scene, MR.strings.action_notes.localized())
                render(scene)
                formats.forEach { (name, _) ->
                    assertTrue(
                        activeNodes(scene).any {
                            name.localized() in labels(it) &&
                                it.config.contains(SemanticsActions.OnClick)
                        },
                        "the explicit draft has a real ${name.localized()} editing action",
                    )
                }
                var editor = activeNodes(scene).single { it.config.contains(SemanticsActions.SetText) }
                requireNotNull(
                    editor.config[SemanticsActions.SetText].action,
                ).invoke(androidx.compose.ui.text.AnnotatedString("Format sample"))
                render(scene)
                editor = activeNodes(scene).single { it.config.contains(SemanticsActions.SetSelection) }
                assertTrue(requireNotNull(editor.config[SemanticsActions.SetSelection].action).invoke(0, 13, false))
                click(scene, format.localized())
                render(scene)
                click(scene, MR.strings.action_save.localized())
                render(scene)
                assertEquals(
                    markdown,
                    Injekt.get<MangaRepository>().getMangaById(manga.id).notes,
                    "the actual serializer must preserve ${format.localized()}",
                )
                val summary = nodes(scene).flatMap { node ->
                    if (node.config.contains(
                            SemanticsProperties.Text,
                        )
                    ) {
                        node.config[SemanticsProperties.Text]
                    } else {
                        emptyList()
                    }
                }.first { "Format sample" in it.text }
                when (format) {
                    MR.strings.desktop_notes_bold -> {
                        assertEquals("Format sample", summary.text)
                        assertTrue(
                            summary.spanStyles.any {
                                it.item.fontWeight ==
                                    androidx.compose.ui.text.font.FontWeight.Bold
                            },
                        )
                    }
                    MR.strings.desktop_notes_italic -> {
                        assertEquals("Format sample", summary.text)
                        assertTrue(
                            summary.spanStyles.any {
                                it.item.fontStyle ==
                                    androidx.compose.ui.text.font.FontStyle.Italic
                            },
                        )
                    }
                    MR.strings.desktop_notes_underline -> {
                        assertEquals("Format sample", summary.text)
                        assertTrue(
                            summary.spanStyles.any {
                                it.item.textDecoration?.contains(
                                    androidx.compose.ui.text.style.TextDecoration.Underline,
                                ) ==
                                    true
                            },
                        )
                    }
                    else -> assertTrue(
                        summary.text.startsWith(
                            if (format ==
                                MR.strings.desktop_notes_ordered
                            ) {
                                "1."
                            } else {
                                "•"
                            },
                        ),
                    )
                }
                click(scene, MR.strings.label_more.localized())
                render(scene)
                click(scene, MR.strings.action_notes.localized())
                render(scene)
                val restored = activeNodes(scene).single {
                    it.config.contains(SemanticsProperties.EditableText)
                }.config[SemanticsProperties.EditableText]
                assertTrue("Format sample" in restored.text)
                when (format) {
                    MR.strings.desktop_notes_bold -> assertTrue(
                        restored.spanStyles.any {
                            it.item.fontWeight ==
                                androidx.compose.ui.text.font.FontWeight.Bold
                        },
                    )
                    MR.strings.desktop_notes_italic -> assertTrue(
                        restored.spanStyles.any {
                            it.item.fontStyle ==
                                androidx.compose.ui.text.font.FontStyle.Italic
                        },
                    )
                    MR.strings.desktop_notes_underline -> assertTrue(
                        restored.spanStyles.any {
                            it.item.textDecoration?.contains(androidx.compose.ui.text.style.TextDecoration.Underline) ==
                                true
                        },
                    )
                    else -> assertTrue(
                        restored.text.startsWith(
                            if (format ==
                                MR.strings.desktop_notes_ordered
                            ) {
                                "1."
                            } else {
                                "•"
                            },
                        ),
                    )
                }
                key(scene, Key.Escape)
                render(scene)
            }
        }
    }

    @Test
    fun `notes pending save locks editor formatting and cancellation without losing the accepted draft`(
        @TempDir root: File,
    ) = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withDetail(root, mangaRepositoryOverride = { actual ->
            object : MangaRepository by actual {
                override suspend fun update(update: MangaUpdate): Boolean {
                    if (update.notes != null) {
                        started.complete(Unit)
                        release.await()
                    }
                    return actual.update(update)
                }
            }
        }) { scene, model, manga, _ ->
            try {
                click(scene, MR.strings.label_more.localized())
                render(scene)
                click(scene, MR.strings.action_notes.localized())
                render(scene)
                val editor = activeNodes(scene).single { it.config.contains(SemanticsActions.SetText) }
                requireNotNull(
                    editor.config[SemanticsActions.SetText].action,
                ).invoke(androidx.compose.ui.text.AnnotatedString("Accepted draft"))
                click(scene, MR.strings.action_save.localized())
                withTimeout(10_000) { started.await() }
                render(scene)
                val pendingEditor = activeNodes(scene).single { it.config.contains(SemanticsProperties.EditableText) }
                assertTrue(
                    pendingEditor.config.contains(SemanticsProperties.Disabled),
                    "a pending explicit save must prevent later input that would be discarded",
                )
                val bold = activeNodes(scene).first {
                    MR.strings.desktop_notes_bold.localized() in labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                }
                assertTrue(bold.config.contains(SemanticsProperties.Disabled))
                key(scene, Key.Escape)
                render(scene)
                assertTrue(model.state.value.showNotesDialog)
            } finally {
                release.complete(Unit)
            }
            render(scene)
            assertFalse(model.state.value.showNotesDialog)
            assertEquals("Accepted draft", Injekt.get<MangaRepository>().getMangaById(manga.id).notes)
        }
    }

    @Test
    fun `source status projection includes hiatus and unknown without a dangling separator`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, mangaTransform = {
            it.copy(status = eu.kanade.tachiyomi.source.model.SManga.ON_HIATUS.toLong())
        }) { scene, _, manga, _ ->
            assertTrue(
                nodes(scene).any {
                    MR.strings.on_hiatus.localized() in labels(it)
                },
                "actual hiatus is a first class source status",
            )
            assertTrue(Injekt.get<MangaRepository>().update(MangaUpdate(manga.id, status = 999L)))
            render(scene)
            assertTrue(nodes(scene).any { MR.strings.unknown_status.localized() in labels(it) })
            assertFalse(nodes(scene).flatMap(::labels).any { it.trim().endsWith("·") })
        }
    }

    @Test
    fun `long description can expand and collapse while retaining real rich content`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, mangaTransform = {
            it.copy(description = (1..24).joinToString("\n") { line -> "Description line $line" })
        }) { scene, _, _, _ ->
            val expand = nodes(scene).filter {
                MR.strings.manga_info_expand.localized() in labels(it) &&
                    it.config.contains(SemanticsActions.OnClick) &&
                    it.boundsInRoot.top > 64f
            }
            assertTrue(expand.isNotEmpty(), "the long description owns an expansion action distinct from toolbar More")
            requireNotNull(expand.first().config[SemanticsActions.OnClick].action).invoke()
            render(scene)
            val left = nodes(scene).first {
                it.config.contains(SemanticsActions.ScrollBy) && it.boundsInRoot.left < 450f
            }
            requireNotNull(left.config[SemanticsActions.ScrollBy].action).invoke(0f, 700f)
            render(scene)
            assertTrue(nodes(scene).flatMap(::labels).any { "Description line 24" in it })
            click(scene, MR.strings.manga_info_collapse.localized())
            render(scene)
            assertTrue(
                nodes(scene).any {
                    MR.strings.manga_info_expand.localized() in labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                },
            )
        }
    }

    @Test
    fun `actual chapter viewport observation supports bounded removal and does not restore from information`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, _, chapters ->
            val list = nodes(scene).first {
                it.config.contains(SemanticsActions.ScrollToIndex) &&
                    it.boundsInRoot.left >= 450f
            }
            requireNotNull(list.config[SemanticsActions.ScrollToIndex].action).invoke(100)
            render(scene)
            requireNotNull(list.config[SemanticsActions.ScrollBy].action).invoke(0f, 29f)
            render(scene)
            assertEquals(
                chapters[99].id,
                model.chapterPosition?.chapterId,
                "the actual string-keyed Lazy entity must reach its owner position port",
            )
            assertEquals(29, model.chapterPosition?.offset)
            Injekt.get<ChapterRepository>().removeChaptersWithIds(listOf(chapters[99].id))
            render(scene)
            assertTrue(model.chapterPosition?.chapterId in chapters.map { it.id } - chapters[99].id)
            scene.resize(420, 780)
            render(scene)
            val narrow = nodes(scene).single { it.config.contains(SemanticsActions.ScrollToIndex) }
            requireNotNull(narrow.config[SemanticsActions.ScrollToIndex].action).invoke(0)
            render(scene)
            assertNull(model.chapterPosition, "actively reading information clears the chapter restoration identity")
            Injekt.get<ChapterRepository>().removeChaptersWithIds(listOf(chapters[150].id))
            render(scene)
            assertTrue(nodes(scene).any { "Manga details" in labels(it) && it.boundsInRoot.top > 64f })
        }
    }

    @Test
    fun `detail title enters the actual nested global search and returns to the same owner`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, manga, _ ->
            val title = nodes(scene).filter {
                it.config.contains(SemanticsProperties.Text) && manga.title in labels(it) && it.boundsInRoot.top > 64f
            }
            assertTrue(
                title.any {
                    it.config.contains(SemanticsActions.OnClick)
                },
                "the information title is an actual global search action",
            )
            requireNotNull(
                title.first {
                    it.config.contains(SemanticsActions.OnClick)
                }.config[SemanticsActions.OnClick].action,
            ).invoke()
            render(scene)
            val search = scene.navigator.lastItem
            assertTrue(search is mihon.desktop.ui.browse.GlobalSearchScreen)
            assertEquals(manga.title, (search as mihon.desktop.ui.browse.GlobalSearchScreen).initialQuery)
            click(scene, MR.strings.action_bar_up_description.localized())
            render(scene)
            assertEquals(MangaDetailScreen(manga.id), scene.navigator.lastItem)
            assertEquals(1, scene.createdModels.size)
            assertEquals(manga.id, model.state.value.manga?.id)
        }
    }

    @Test
    fun `detail tag primary action searches only the current source through the actual navigator`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, mangaTransform = { it.copy(genre = listOf("Mystery")) }) { scene, _, manga, _ ->
            click(scene, "Mystery")
            render(scene)
            val destination = scene.navigator.lastItem
            assertTrue(
                destination is mihon.desktop.ui.browse.SourceBrowseScreen,
                "a tag primary action must retain the current source scope",
            )
            assertEquals(manga.source, (destination as mihon.desktop.ui.browse.SourceBrowseScreen).sourceId)
            assertEquals("Mystery", destination.initialQuery)
        }
    }

    @Test
    fun `nonempty notes summary precedes description and opens the same explicit draft editor`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root, mangaTransform = {
            it.copy(notes = "Saved summary", description = "Real description")
        }) { scene, model, manga, _ ->
            val summary = nodes(scene).filter { "Saved summary" in labels(it) }
            assertTrue(summary.isNotEmpty(), "persisted nonempty notes have a visible summary")
            val description = nodes(scene).first { "Real description" in labels(it) }
            assertTrue(summary.first().boundsInRoot.bottom <= description.boundsInRoot.top)
            val action = summary.first { it.config.contains(SemanticsActions.OnClick) }
            requireNotNull(action.config[SemanticsActions.OnClick].action).invoke()
            render(scene)
            assertTrue(model.state.value.showNotesDialog)
            assertTrue(
                activeNodes(scene).any {
                    it.config.contains(SemanticsProperties.EditableText) &&
                        it.config[SemanticsProperties.EditableText].text == "Saved summary"
                },
            )
            key(scene, Key.Escape)
            render(scene)
            assertFalse(model.state.value.showNotesDialog)
            assertEquals("Saved summary", Injekt.get<MangaRepository>().getMangaById(manga.id).notes)
        }
    }

    @Test
    fun `production wide header uses source content ratio and narrow header uses source maximum`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, _ ->
            val information = nodes(scene).first {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "manga-detail-information"
            }
            val cover = nodes(scene).first {
                it.config.contains(SemanticsProperties.ContentDescription) &&
                    manga.title in it.config[SemanticsProperties.ContentDescription]
            }.boundsInRoot
            assertEquals(
                (information.boundsInRoot.width - 32f) * .65f,
                cover.width,
                1f,
                "wide cover consumes 65 percent after source padding",
            )
            assertEquals(information.boundsInRoot.center.x, cover.center.x, 1f)
            assertEquals(.7f, cover.width / cover.height, .01f)
            scene.resize(420, 780)
            render(scene)
            val small = nodes(scene).first {
                it.config.contains(SemanticsProperties.ContentDescription) &&
                    manga.title in it.config[SemanticsProperties.ContentDescription]
            }.boundsInRoot
            assertEquals(100f, small.width, 1f, "source small layout maximum is 100dp")
            assertEquals(.7f, small.width / small.height, .01f)
        }
    }

    @Test
    fun `ordinary detail toolbar exposes only back download chapter settings and one More menu`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, _, _ ->
            val toolbar = nodes(scene).filter {
                it.config.contains(SemanticsActions.OnClick) &&
                    it.boundsInRoot.top < 64f
            }.flatMap(::labels)
            val expected = listOf(
                MR.strings.action_bar_up_description,
                MR.strings.desktop_ui_download_chapters,
                MR.strings.desktop_ui_filter_chapters,
                MR.strings.label_more,
            ).map {
                it.localized()
            }
            assertEquals(expected.toSet(), toolbar.toSet(), "ordinary actions move to the single More menu")
            assertEquals(4, toolbar.size)
            assertFalse(
                nodes(scene).any {
                    MR.strings.desktop_ui_reading_mode_e073e5df.localized() in labels(it)
                },
                "mode remains in the Reader",
            )
        }
    }

    @Test
    fun `right scrollbar pointer drag reaches the actual last chapter without scrolling information`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, manga, _ ->
            val chapters = nodes(scene).first {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "manga-detail-chapters"
            }.boundsInRoot
            val coverBefore = nodes(scene).first {
                it.config.contains(SemanticsProperties.ContentDescription) &&
                    manga.title in it.config[SemanticsProperties.ContentDescription]
            }.boundsInRoot
            val x = chapters.right - 4f
            scene.pointer(PointerEventType.Press, Offset(x, chapters.top + 24f), true)
            repeat(10) { step ->
                scene.pointer(
                    PointerEventType.Move,
                    Offset(
                        x,
                        chapters.top + 24f + (chapters.height - 48f) * (step + 1) / 10,
                    ),
                    true,
                )
                render(scene)
            }
            scene.pointer(PointerEventType.Release, Offset(x, chapters.bottom - 24f), false)
            render(scene)
            assertTrue(hasChapter(scene, "Chapter 200"), "real scrollbar drag must reach the actual chapter tail")
            val coverAfter = nodes(scene).first {
                it.config.contains(SemanticsProperties.ContentDescription) &&
                    manga.title in it.config[SemanticsProperties.ContentDescription]
            }.boundsInRoot
            assertEquals(coverBefore, coverAfter, "right scrolling never moves the information owner")
            assertFalse(model.state.value.isUpdating)
        }
    }

    @Test
    fun `chapter entity and nonzero offset survive narrow wide resize and sort without a new owner`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, manga, _ ->
            val list = nodes(scene).first {
                it.config.contains(SemanticsActions.ScrollToIndex) &&
                    it.boundsInRoot.left >= 450f
            }
            requireNotNull(list.config[SemanticsActions.ScrollToIndex].action).invoke(100)
            render(scene)
            requireNotNull(list.config[SemanticsActions.ScrollBy].action).invoke(0f, 29f)
            render(scene)
            fun anchor(): Pair<String, Float> {
                val pane = nodes(scene).first {
                    it.config.contains(SemanticsActions.ScrollToIndex) &&
                        (scene.navigator.size == 1) &&
                        (it.boundsInRoot.left >= 450f || it.boundsInRoot.width < 450f)
                }.boundsInRoot
                val row = nodes(scene).filter { node ->
                    node.config.contains(SemanticsActions.OnLongClick) &&
                        node.boundsInRoot.bottom > pane.top &&
                        labels(node).any { it.startsWith("Chapter ") }
                }.minBy { it.boundsInRoot.top }
                return labels(row).first { it.startsWith("Chapter ") } to (row.boundsInRoot.bottom - pane.top)
            }
            val before = anchor()
            assertEquals("Chapter 100", before.first)
            scene.resize(420, 780)
            render(scene)
            assertEquals(
                before,
                anchor(),
                "resize maps the same chapter entity and its nonzero offset past information headers",
            )
            scene.resize(1200, 900)
            render(scene)
            assertEquals(before, anchor())
            model.setChapterSort(manga, ChapterSortMode.BY_CHAPTER_NUMBER)
            render(scene)
            assertEquals(before, anchor(), "sorting preserves the same valid chapter entity")
            assertEquals(1, scene.createdModels.size)
        }
    }

    @Test
    fun `notes repository rejection preserves the mounted draft and allows explicit retry`(
        @TempDir root: File,
    ) = runBlocking {
        var reject = false
        withDetail(root, mangaRepositoryOverride = { actual ->
            object : MangaRepository by actual {
                override suspend fun update(update: MangaUpdate): Boolean = if (reject &&
                    update.notes != null
                ) {
                    false
                } else {
                    actual.update(update)
                }
            }
        }) { scene, model, manga, _ ->
            click(scene, MR.strings.label_more.localized())
            render(scene)
            click(scene, MR.strings.action_notes.localized())
            render(scene)
            val editor = activeNodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            assertTrue(
                requireNotNull(
                    editor.config[SemanticsActions.SetText].action,
                ).invoke(androidx.compose.ui.text.AnnotatedString("Persistent draft")),
            )
            render(scene)
            reject = true
            click(scene, MR.strings.action_save.localized())
            render(scene)
            assertTrue(
                model.state.value.showNotesDialog,
                "a rejected actual SQLite-backed write must keep the draft dialog open",
            )
            assertEquals("", Injekt.get<MangaRepository>().getMangaById(manga.id).notes)
            assertTrue(
                activeNodes(scene).any {
                    it.config.contains(SemanticsProperties.EditableText) &&
                        it.config[SemanticsProperties.EditableText].text == "Persistent draft"
                },
            )
            reject = false
            click(scene, MR.strings.action_save.localized())
            render(scene)
            assertFalse(model.state.value.showNotesDialog)
            assertEquals("Persistent draft", Injekt.get<MangaRepository>().getMangaById(manga.id).notes)
        }
    }

    private suspend fun withDetail(
        root: File,
        mangaRepositoryOverride: ((MangaRepository) -> MangaRepository)? = null,
        chapterRepositoryOverride: ((ChapterRepository) -> ChapterRepository)? = null,
        tabletMode: eu.kanade.domain.ui.model.TabletUiMode = eu.kanade.domain.ui.model.TabletUiMode.AUTOMATIC,
        backendFactory: (Preferences) -> Preferences = { it },
        mangaTransform: (Manga) -> Manga = { it },
        coverRequests: MutableList<coil3.request.ImageRequest>? = null,
        coverPicker: CoverFilePicker? = null,
        httpSource: Boolean = false,
        downloadManagerFactory: (
            (mihon.desktop.download.DesktopDownloadManager) -> mihon.desktop.download.DesktopDownloadManager
        )? = null,
        shareService: mihon.desktop.platform.DesktopShareService? = null,
        externalUrlOpener: ((String) -> Result<Unit>)? = null,
        block: suspend (NativeScene, MangaDetailScreenModel, Manga, List<Chapter>) -> Unit,
    ) {
        val node = Preferences.userRoot().node("mihon-tests/chapter-options-${UUID.randomUUID()}")
        val context = initDesktopDIForTest(
            root,
            DesktopPreferenceStore(backendFactory(node)),
            startDownloadWorker = false,
            mangaRepositoryOverride = mangaRepositoryOverride,
            chapterRepositoryOverride = chapterRepositoryOverride,
        )
        val replacementManager = downloadManagerFactory?.invoke(Injekt.get())
        if (replacementManager != null) {
            Injekt.addFactory<mihon.desktop.download.DesktopDownloadManager> { replacementManager }
        }
        Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().tabletUiMode.set(tabletMode)
        val detailsSourceManager = if (httpSource) {
            mihon.desktop.source.DesktopSourceManager(
                Injekt.get<mihon.desktop.extension.DesktopExtensionManager>(),
                builtinSources = listOf(mihon.desktop.source.FakeHttpSource(42L, "en", "Native source")),
            )
        } else {
            null
        }
        if (detailsSourceManager !=
            null
        ) {
            Injekt.addFactory<tachiyomi.domain.source.service.SourceManager> { detailsSourceManager }
        }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scene = NativeScene(kotlin.coroutines.coroutineContext)
        val previousLoader = coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
        val loader = coverRequests?.let { requests ->
            mihon.desktop.image.createDesktopImageLoader(
                coil3.PlatformContext.INSTANCE,
                Injekt.get<mihon.desktop.platform.DesktopNetworkHelper>(),
                Injekt.get<tachiyomi.domain.source.service.SourceManager>(),
            )
                .newBuilder().eventListenerFactory { request ->
                    requests += request
                    coil3.EventListener.NONE
                }.build()
                .also { coil3.SingletonImageLoader.setUnsafe(it) }
        }
        try {
            val mangas = Injekt.get<MangaRepository>()
            val manga = mangas.insertNetworkManga(
                listOf(
                    mangaTransform(
                        Manga.create().copy(
                            source = 42L,
                            url = "/chapter-options",
                            title = "Manga details",
                            initialized = true,
                        ),
                    ),
                ),
            ).single()
            val requestedNotes = mangaTransform(manga).notes
            if (requestedNotes.isNotBlank()) {
                assertTrue(mangas.update(MangaUpdate(manga.id, notes = requestedNotes)))
                assertEquals(requestedNotes, mangas.getMangaById(manga.id).notes)
            }
            val chapters = Injekt.get<ChapterRepository>().addAll(
                (1..200).map { number ->
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/chapter-$number",
                        name = "Chapter $number",
                        chapterNumber = number.toDouble(),
                        sourceOrder = number.toLong(),
                    )
                },
            )
            lateinit var model: MangaDetailScreenModel
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt().let {
                        it.copy(
                            sourceManager = detailsSourceManager ?: it.sourceManager,
                            shareService =
                            shareService ?: it.shareService,
                            externalUrlOpener = externalUrlOpener ?: it.externalUrlOpener,
                        )
                    },
                    LocalDensity provides Density(1f, scene.fontScale),
                ) {
                    ProvideMangaDetailScreenModelFactory({ id ->
                        (
                            if (coverPicker ==
                                null
                            ) {
                                MangaDetailScreenModelFactory.create(id)
                            } else {
                                MangaDetailScreenModelFactory.create(id, coverPicker)
                            }
                            ).also {
                            model = it
                            scene.createdModels += it
                        }
                    }) {
                        DesktopTheme {
                            scene.colors = MaterialTheme.colorScheme
                            Navigator(MangaDetailScreen(manga.id)) {
                                scene.navigator = it
                                CurrentScreen()
                            }
                        }
                    }
                }
            }
            render(scene)
            assertEquals(manga.id, model.state.value.manga?.id)
            assertTrue(hasChapter(scene, "Chapter 1"), "fixture must render a real chapter before the tested event")
            block(scene, model, manga, chapters)
        } finally {
            scene.close()
            if (loader != null) {
                coil3.SingletonImageLoader.setUnsafe(previousLoader)
                loader.shutdown()
            }
            replacementManager?.stopAndJoin()
            context.closeAndJoin()
            Dispatchers.resetMain()
            node.removeNode()
        }
    }

    private class NativeScene(context: CoroutineContext) : AutoCloseable {
        val owners = linkedSetOf<SemanticsOwner>()
        val createdModels = mutableListOf<MangaDetailScreenModel>()
        lateinit var navigator: Navigator
        lateinit var colors: androidx.compose.material3.ColorScheme
        private var windowSize by mutableStateOf(IntSize(1200, 900))
        var fontScale by mutableFloatStateOf(1f)
        private val bitmap = ImageBitmap(1400, 1000)
        private val canvas = Canvas(bitmap)
        private val scene = CanvasLayersComposeScene(
            size = windowSize,
            coroutineContext = context,
            platformContext = object : PlatformContext {
                override val windowInfo = object : WindowInfo {
                    override val isWindowFocused = true
                    override val containerSize get() = windowSize
                    override val containerDpSize get() = DpSize(windowSize.width.dp, windowSize.height.dp)
                }
                override val inputModeManager = object : InputModeManager {
                    override val inputMode = InputMode.Keyboard
                    override fun requestInputMode(inputMode: InputMode) = true
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
        fun setContent(content: @Composable () -> Unit) = scene.setContent(content)
        fun render() {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            scene.render(canvas, System.nanoTime())
        }
        fun sendKeyEvent(event: ComposeKeyEvent) = scene.sendKeyEvent(event)
        fun pointer(
            type: PointerEventType,
            position: Offset,
            pressed: Boolean,
            modifiers: androidx.compose.ui.input.pointer.PointerKeyboardModifiers =
                androidx.compose.ui.input.pointer.PointerKeyboardModifiers(),
        ) {
            scene.sendPointerEvent(
                type,
                position,
                buttons = PointerButtons(isPrimaryPressed = pressed),
                keyboardModifiers = modifiers,
                button = PointerButton.Primary,
            )
        }
        fun pointerClick(position: Offset) {
            scene.sendPointerEvent(
                PointerEventType.Press,
                position,
                buttons = PointerButtons(isPrimaryPressed = true),
                button = PointerButton.Primary,
            )
            scene.sendPointerEvent(
                PointerEventType.Release,
                position,
                buttons = PointerButtons(),
                button = PointerButton.Primary,
            )
        }
        fun pointerSecondaryClick(position: Offset) {
            scene.sendPointerEvent(
                PointerEventType.Press,
                position,
                buttons = PointerButtons(isSecondaryPressed = true),
                button = PointerButton.Secondary,
            )
            scene.sendPointerEvent(
                PointerEventType.Release,
                position,
                buttons = PointerButtons(),
                button = PointerButton.Secondary,
            )
        }
        fun resize(width: Int, height: Int) {
            windowSize = IntSize(width, height)
            scene.size = windowSize
        }
        fun savePng(file: File) {
            file.parentFile.mkdirs()
            Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                val pixels = ImageIO.read(ByteArrayInputStream(requireNotNull(image.encodeToData()).bytes))
                ImageIO.write(pixels.getSubimage(0, 0, windowSize.width, windowSize.height), "png", file)
            }
        }
        fun snapshot(): java.awt.image.BufferedImage = Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
            ImageIO.read(ByteArrayInputStream(requireNotNull(image.encodeToData()).bytes))
        }
        override fun close() = scene.close()
    }

    private fun clickChapterAction(scene: NativeScene, label: String) {
        val bar = nodes(scene).first {
            it.config.contains(SemanticsProperties.TestTag) &&
                it.config[SemanticsProperties.TestTag] == "chapter-selection-bottom-bar"
        }
        val action = flatten(bar).first {
            label in labels(it) && it.config.contains(SemanticsActions.OnClick) &&
                !it.config.contains(SemanticsProperties.Disabled)
        }
        action.config[SemanticsActions.OnClick].action!!.invoke()
    }

    private fun chapterNode(scene: NativeScene, title: String): SemanticsNode = nodes(scene).first {
        it.config.contains(SemanticsActions.OnLongClick) && title in labels(it) && it.boundsInRoot.height > 0
    }

    private fun selectedChapterNames(scene: NativeScene): Set<String> = nodes(scene).filter { node ->
        node.config.contains(SemanticsActions.OnLongClick) &&
            (
                node.config.getOrElse(SemanticsProperties.Selected) { false } || flatten(node).any {
                    it.config.contains(SemanticsProperties.ToggleableState) &&
                        it.config[SemanticsProperties.ToggleableState] == androidx.compose.ui.state.ToggleableState.On
                }
                )
    }.flatMap(::labels).filter { it.startsWith("Chapter ") }.toSet()

    private suspend fun chapterMouse(
        scene: NativeScene,
        title: String,
        ctrl: Boolean = false,
        shift: Boolean = false,
        holdMillis: Long = 0,
    ) {
        val bounds = chapterNode(scene, title).boundsInRoot
        val point = Offset(bounds.left + bounds.width / 3, bounds.top + bounds.height / 3)
        val modifiers = androidx.compose.ui.input.pointer.PointerKeyboardModifiers(
            isCtrlPressed = ctrl,
            isShiftPressed = shift,
        )
        scene.pointer(PointerEventType.Press, point, true, modifiers)
        if (holdMillis > 0) {
            delay(holdMillis)
            render(scene)
        }
        scene.pointer(PointerEventType.Release, point, false, modifiers)
        render(scene)
    }

    private suspend fun render(scene: NativeScene) {
        repeat(16) {
            scene.render()
            delay(15)
        }
    }
    private fun png(color: Int): ByteArray {
        val image = java.awt.image.BufferedImage(7, 10, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        repeat(7) { x -> repeat(10) { y -> image.setRGB(x, y, color) } }
        return java.io.ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
    private fun setEditorText(scene: NativeScene, text: String) {
        val editor = activeNodes(scene).single { it.config.contains(SemanticsActions.SetText) }
        requireNotNull(
            editor.config[SemanticsActions.SetText].action,
        ).invoke(androidx.compose.ui.text.AnnotatedString(text))
    }

    private fun editorText(scene: NativeScene): androidx.compose.ui.text.AnnotatedString =
        activeNodes(scene).single {
            it.config.contains(SemanticsProperties.EditableText)
        }.config[SemanticsProperties.EditableText]

    private fun visualFile(root: File, name: String): File = File(
        (
            System.getenv("MIHON_RI10_VISUAL_DIR") ?: System.getenv("MIHON_RI09_VISUAL_DIR")
                ?: System.getenv("MIHON_RI08_VISUAL_DIR")
            )?.let(::File)
            ?: File(root, "visuals"),
        name,
    )
    private fun denyFileChanges(file: File): () -> Unit {
        val path = file.toPath()
        if (System.getProperty("os.name").startsWith("Windows")) {
            val handle = java.nio.channels.FileChannel.open(
                path,
                java.nio.file.StandardOpenOption.READ,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE,
            )
            return { handle.close() }
        }
        val parent = path.parent
        val permissions = java.nio.file.Files.getPosixFilePermissions(path)
        val parentPermissions = java.nio.file.Files.getPosixFilePermissions(parent)
        val writes =
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
                java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
            )
        java.nio.file.Files.setPosixFilePermissions(path, permissions - writes)
        java.nio.file.Files.setPosixFilePermissions(parent, parentPermissions - writes)
        return {
            java.nio.file.Files.setPosixFilePermissions(parent, parentPermissions)
            java.nio.file.Files.setPosixFilePermissions(path, permissions)
        }
    }
    private suspend fun assertCoverPixel(scene: NativeScene, title: String, expected: Int) {
        withTimeout(10_000) {
            while (true) {
                render(scene)
                val bounds = activeNodes(scene).filter {
                    it.config.contains(SemanticsProperties.ContentDescription) &&
                        title in it.config[SemanticsProperties.ContentDescription] &&
                        it.boundsInRoot.width > 0 &&
                        it.boundsInRoot.height > 0
                }.minBy { it.boundsInRoot.width * it.boundsInRoot.height }.boundsInRoot
                val pixel = scene.snapshot().getRGB(
                    (bounds.left + bounds.width / 3).toInt(),
                    (
                        bounds.top +
                            bounds.height / 3
                        ).toInt(),
                )
                if ((pixel and 0xFFFFFF) == (expected and 0xFFFFFF)) return@withTimeout
            }
        }
    }
    private fun key(scene: NativeScene, key: Key, shift: Boolean = false) {
        scene.sendKeyEvent(keyEvent(key, KeyEventType.KeyDown, shift))
        scene.sendKeyEvent(keyEvent(key, KeyEventType.KeyUp, shift))
    }
    private fun keyEvent(key: Key, type: KeyEventType, shift: Boolean): ComposeKeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single {
            it.name.startsWith("KeyEvent-") &&
                !it.name.endsWith("\$default")
        }
        return ComposeKeyEvent(
            factory.invoke(null, key.keyCode, eventType, key.nativeKeyLocation, false, false, false, shift, null),
        )
    }
    private fun focused(scene: NativeScene): SemanticsNode {
        // Nonfocusable tooltip popups have a distinct, empty focus owner.
        val owner = scene.owners.last { candidate ->
            flatten(candidate.rootSemanticsNode).any { it.config.contains(SemanticsProperties.Focused) }
        }
        return flatten(owner.rootSemanticsNode).single {
            it.config.contains(SemanticsProperties.Focused) && it.config[SemanticsProperties.Focused]
        }
    }

    private fun nodes(scene: NativeScene) = scene.owners.flatMap { flatten(it.rootSemanticsNode) }
    private fun activeNodes(scene: NativeScene): List<SemanticsNode> {
        val editorOwner = scene.owners.lastOrNull { owner ->
            flatten(owner.rootSemanticsNode).any { it.config.contains(SemanticsProperties.EditableText) }
        }
        return flatten((editorOwner ?: scene.owners.last()).rootSemanticsNode)
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun labels(node: SemanticsNode): List<String> =
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
    private fun click(scene: NativeScene, title: String) {
        val node = activeNodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) && title in labels(it)
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun hasChapter(scene: NativeScene, title: String) = nodes(scene).any { title in labels(it) }
}
