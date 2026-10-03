package mihon.desktop.ui.migration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyLocation
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.source.MangaDexSource
import mihon.desktop.ui.theme.DesktopTheme
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

@OptIn(
    ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
    ExperimentalCoroutinesApi::class,
)
@Isolated
class MigrationSearchInteractionTest {
    @Test
    fun `actual migration search exposes source paging and recoverable HTTP errors`(@TempDir root: File) = runBlocking {
        withSearch(root) { scene, _, server ->
            scene.awaitLabel("Replacement")
            assertTrue(
                scene.labels().contains(MR.strings.label_sources.localized()),
                "Target source selection is a real visible entry",
            )
            assertTrue(
                scene.labels().contains(MR.strings.onboarding_action_next.localized()),
                "The source next page is reachable",
            )
            scene.click(MR.strings.onboarding_action_next.localized())
            scene.awaitLabel("Second page")
            assertTrue(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS) != null)
            val editor = scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
            editor.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString("failure"))
            scene.render()
            delay(20)
            scene.render()
            val submit = scene.nodes().first { it.config.contains(SemanticsActions.OnImeAction) }
            submit.config[SemanticsActions.OnImeAction].action!!.invoke()
            scene.awaitLabel(MR.strings.action_retry.localized())
            assertTrue(scene.labels().any { it.contains("500") }, "Actual HTTP failure has a reason and retry")
        }
    }

    @Test
    fun `actual confirmation includes applicable cover downloads and cancellation keeps original membership`(
        @TempDir root: File,
    ) = runBlocking {
        withSearch(root) { scene, dependencies, _ ->
            val source = dependencies.getManga.await(scene.sourceId)!!
            dependencies.customCoverStore.write(source.id, byteArrayOf(7, 8, 9))
            val directory = Injekt.get<DesktopDownloadProvider>().chapterDownloadDir(
                source.source,
                source.title,
                "Original",
            ).apply {
                mkdirs()
            }
            File(directory, "1.jpg").writeBytes(byteArrayOf(1, 2, 3))
            scene.awaitLabel("Replacement")
            scene.click(MR.strings.desktop_ui_select.localized())
            scene.awaitLabel(MR.strings.label_migration.localized())
            scene.awaitLabel(MR.strings.desktop_ui_copy_notes.localized())
            assertTrue(
                scene.labels().contains(MR.strings.custom_cover.localized()),
                "Applicable custom cover is selectable",
            )
            assertTrue(
                scene.labels().contains(MR.strings.delete_downloaded.localized()),
                "Original downloaded files have a confirmation option",
            )
            scene.click(MR.strings.action_cancel.localized())
            scene.awaitLabel(MR.strings.desktop_ui_select.localized())
            assertTrue(dependencies.getManga.await(source.id)!!.favorite)
            assertTrue(directory.isDirectory)
            assertEquals(
                null,
                dependencies.getManga.await(source.id)?.let {
                    dependencies.mangaRepository.migrationReceipt(it.id)
                },
            )
        }
    }

    @Test
    fun `actual batch migration continues after HTTP failure and retries only original failed item`(
        @TempDir root: File,
    ) = runBlocking {
        withSearch(root) { scene, dependencies, server ->
            val mangas = dependencies.mangaRepository
            val first = dependencies.getManga.await(scene.sourceId)!!
            val second = mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 42, url = "/second-original", title = "Second original")),
            ).single()
            mangas.updateAtomically(LibraryMembershipUpdate(second.id, true, 60, emptyList()))
            val targetSource = Injekt.get<SourceManager>().getCatalogueSources().first()
            val fail = java.util.concurrent.atomic.AtomicBoolean(true)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(
                    request: RecordedRequest,
                ): MockResponse = if (request.url!!.encodedPath.contains("/first-target/") &&
                    fail.get()
                ) {
                    MockResponse.Builder().code(500).build()
                } else {
                    MockResponse.Builder().body(
                        """
                        {"result":"ok","total":1,
                        "data":[{"id":"one-${request.url!!.encodedPath.hashCode()}",
                        "attributes":{"chapter":"1","title":"One","translatedLanguage":"en"},
                        "relationships":[]}]}""",
                    ).build()
                }
            }
            val controller = dependencies.batchMigrationController
            val queueId = controller.submit(
                listOf(
                    mihon.desktop.migration.BatchMigrationRequest(first.id, first.title),
                    mihon.desktop.migration.BatchMigrationRequest(second.id, second.title),
                ),
                mihon.desktop.migration.BatchMigrationOptions(copyNotes = false),
            )
            suspend fun waitFor(
                id: Long,
                status: mihon.desktop.migration.BatchMigrationItemStatus,
            ) = withTimeout(8000) {
                while (controller.queue(queueId)?.items?.first { it.mangaId == id }?.status !=
                    status
                ) {
                    scene.render()
                    delay(10)
                }
            }
            waitFor(first.id, mihon.desktop.migration.BatchMigrationItemStatus.WAITING_FOR_USER)
            controller.selectTarget(
                queueId,
                first.id,
                mihon.desktop.migration.BatchMigrationTargetSelection(
                    targetSource.id,
                    "/manga/first-target",
                    "First target",
                ),
                mihon.desktop.migration.BatchMigrationOptions(copyNotes = false),
            )
            waitFor(first.id, mihon.desktop.migration.BatchMigrationItemStatus.ERROR)
            waitFor(second.id, mihon.desktop.migration.BatchMigrationItemStatus.WAITING_FOR_USER)
            assertTrue(dependencies.getManga.await(first.id)!!.favorite)
            controller.selectTarget(
                queueId,
                second.id,
                mihon.desktop.migration.BatchMigrationTargetSelection(
                    targetSource.id,
                    "/manga/second-target",
                    "Second target",
                ),
                mihon.desktop.migration.BatchMigrationOptions(copyNotes = false),
            )
            waitFor(second.id, mihon.desktop.migration.BatchMigrationItemStatus.SUCCESS)
            withTimeout(5000) { while (mangas.migrationReceipt(second.id) != null) delay(10) }
            val savedSecond = mangas.getMangaByUrlAndSourceId("/manga/second-target", targetSource.id)!!
            assertEquals(60, savedSecond.dateAdded)
            fail.set(false)
            controller.retryItem(queueId, first.id)
            waitFor(first.id, mihon.desktop.migration.BatchMigrationItemStatus.SUCCESS)
            withTimeout(5000) { while (mangas.migrationReceipt(first.id) != null) delay(10) }
            assertFalse(dependencies.getManga.await(first.id)!!.favorite)
            assertEquals(savedSecond, mangas.getMangaById(savedSecond.id), "Retry does not replay a successful target")
            assertEquals(40, mangas.getMangaByUrlAndSourceId("/manga/first-target", targetSource.id)!!.dateAdded)
        }
    }

    @Test
    fun `actual SQL refusal keeps confirmation retryable and Cancel restores original context`(
        @TempDir root: File,
    ) = runBlocking {
        withSearch(root) { scene, dependencies, _ ->
            scene.awaitLabel("Replacement")
            scene.click(MR.strings.desktop_ui_select.localized())
            scene.awaitLabel(MR.strings.desktop_ui_copy_notes.localized())
            val driver = Injekt.get<app.cash.sqldelight.db.SqlDriver>()
            driver.execute(
                null,
                """CREATE TRIGGER reject_migration BEFORE UPDATE OF favorite ON mangas
                WHEN OLD._id = ${scene.sourceId} AND NEW.favorite = 0
                BEGIN SELECT RAISE(ABORT, 'reject migration'); END""",
                0,
            )
            try {
                scene.click(MR.strings.action_migrate.localized())
                withTimeout(8000) {
                    while (dependencies.mangaRepository.migrationReceipt(scene.sourceId) == null ||
                        scene.nodes().none {
                            MR.strings.action_cancel.localized() in flatten(it).flatMap(::label) &&
                                it.config.contains(SemanticsActions.OnClick) &&
                                !it.config.contains(SemanticsProperties.Disabled)
                        }
                    ) {
                        scene.render()
                        delay(10)
                    }
                }
                assertTrue(dependencies.getManga.await(scene.sourceId)!!.favorite)
                scene.click(MR.strings.action_cancel.localized())
                withTimeout(5000) {
                    while (dependencies.mangaRepository.migrationReceipt(scene.sourceId) !=
                        null
                    ) {
                        scene.render()
                        delay(10)
                    }
                }
                scene.awaitLabel(MR.strings.desktop_ui_select.localized())
                assertTrue(dependencies.getManga.await(scene.sourceId)!!.favorite)
            } finally {
                driver.execute(null, "DROP TRIGGER reject_migration", 0)
            }
        }
    }

    @Test
    fun `actual batch checkpoint refusal retains committed SQL and retry avoids another HTTP migration`(
        @TempDir root: File,
    ) = runBlocking {
        val refuse = java.util.concurrent.atomic.AtomicBoolean(true)
        withSearch(root, checkpointRefusal = refuse) { scene, dependencies, server ->
            val source = dependencies.getManga.await(scene.sourceId)!!
            val controller = dependencies.batchMigrationController
            val targetSource = Injekt.get<SourceManager>().getCatalogueSources().first()
            val id = controller.submit(listOf(mihon.desktop.migration.BatchMigrationRequest(source.id, source.title)))
            withTimeout(5000) {
                while (controller.queue(id)?.items?.single()?.status !=
                    mihon.desktop.migration.BatchMigrationItemStatus.WAITING_FOR_USER
                ) {
                    delay(10)
                }
            }
            controller.selectTarget(
                id,
                source.id,
                mihon.desktop.migration.BatchMigrationTargetSelection(targetSource.id, "/manga/target", "Replacement"),
                mihon.desktop.migration.BatchMigrationOptions(),
            )
            withTimeout(8000) {
                while (controller.queue(id)?.items?.single()?.status !=
                    mihon.desktop.migration.BatchMigrationItemStatus.ERROR
                ) {
                    delay(10)
                }
            }
            val receipt = dependencies.mangaRepository.migrationReceipt(source.id)!!
            assertTrue(receipt.committed && receipt.filesComplete)
            assertFalse(dependencies.getManga.await(source.id)!!.favorite)
            val persisted = dependencies.mangaRepository.getMangaById(receipt.request.targetMangaId)
            assertEquals(40, persisted.dateAdded)
            dependencies.mangaRepository.update(
                tachiyomi.domain.manga.model.MangaUpdate(persisted.id, notes = "User after committed migration"),
            )
            val requests = server.requestCount
            refuse.set(false)
            controller.retryItem(id, source.id)
            withTimeout(5000) { while (dependencies.mangaRepository.migrationReceipt(source.id) != null) delay(10) }
            assertEquals(
                mihon.desktop.migration.BatchMigrationItemStatus.SUCCESS,
                controller.queue(id)?.items?.single()?.status,
            )
            assertEquals(
                requests,
                server.requestCount,
                "Original SQL receipt avoids all fresh target HTTP on checkpoint retry",
            )
            assertEquals(40, dependencies.mangaRepository.getMangaById(persisted.id).dateAdded)
            assertEquals(
                "User after committed migration",
                dependencies.mangaRepository.getMangaById(persisted.id).notes,
            )
        }
    }

    @Test
    fun `actual batch carries all applicable file flags and excludes HTTP late downloads`(
        @TempDir root: File,
    ) = runBlocking {
        withSearch(root) { scene, dependencies, server ->
            val source = dependencies.getManga.await(scene.sourceId)!!
            dependencies.customCoverStore.write(source.id, byteArrayOf(7, 8, 9))
            val provider = Injekt.get<DesktopDownloadProvider>()
            val original = provider.chapterDownloadDir(source.source, source.title, "Original").apply { mkdirs() }
            File(original, "1.jpg").writeBytes(byteArrayOf(1, 2, 3))
            val started = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.url!!.encodedPath.contains("/feed")) {
                        started.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        return MockResponse.Builder().body(
                            """
                            {"result":"ok","total":1,
                            "data":[{"id":"file-target-one",
                            "attributes":{"chapter":"1","title":"One","translatedLanguage":"en"},
                            "relationships":[]}]}""",
                        ).build()
                    }
                    return MockResponse.Builder().body(search("Replacement", "target", 0)).build()
                }
            }
            val controller = dependencies.batchMigrationController
            val id = controller.submit(listOf(mihon.desktop.migration.BatchMigrationRequest(source.id, source.title)))
            withTimeout(5000) {
                while (controller.queue(id)?.items?.single()?.status !=
                    mihon.desktop.migration.BatchMigrationItemStatus.WAITING_FOR_USER
                ) {
                    delay(10)
                }
            }
            val targetSource = Injekt.get<SourceManager>().getCatalogueSources().first()
            try {
                controller.selectTarget(
                    id,
                    source.id,
                    mihon.desktop.migration.BatchMigrationTargetSelection(
                        targetSource.id,
                        "/manga/target",
                        "Replacement",
                    ),
                    mihon.desktop.migration.BatchMigrationOptions(
                        copyChapters = true,
                        copyCategories = true,
                        copyNotes = true,
                        copyCustomCover = true,
                        removeDownloads = true,
                    ),
                )
                assertTrue(withContext(Dispatchers.IO) { started.await(5, java.util.concurrent.TimeUnit.SECONDS) })
                val late = provider.chapterDownloadDir(source.source, source.title, "Late").apply { mkdirs() }
                File(late, "1.jpg").writeBytes(byteArrayOf(4, 5, 6))
                release.countDown()
                withTimeout(8000) {
                    while (dependencies.mangaRepository.migrationReceipt(source.id) != null ||
                        controller.queue(id)?.items?.single()?.status !=
                        mihon.desktop.migration.BatchMigrationItemStatus.SUCCESS
                    ) {
                        delay(10)
                    }
                }
                val target = dependencies.mangaRepository.getMangaByUrlAndSourceId("/manga/target", targetSource.id)!!
                assertFalse(original.exists())
                assertTrue(late.exists())
                assertArrayEquals(
                    byteArrayOf(7, 8, 9),
                    dependencies.customCoverStore.getCustomCoverFile(target.id).readBytes(),
                )
                assertEquals("Original notes", target.notes)
                assertEquals(40, target.dateAdded)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `empty source notes hide copying and preserve existing target notes through actual Copy`(
        @TempDir root: File,
    ) = runBlocking {
        withSearch(root) { scene, dependencies, _ ->
            dependencies.mangaRepository.update(tachiyomi.domain.manga.model.MangaUpdate(scene.sourceId, notes = ""))
            val targetSource = Injekt.get<SourceManager>().getCatalogueSources().first()
            val target = dependencies.mangaRepository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = targetSource.id,
                        url = "/manga/target",
                        title = "Existing",
                        notes = "Target user note",
                    ),
                ),
            ).single()
            dependencies.mangaRepository.update(
                tachiyomi.domain.manga.model.MangaUpdate(target.id, notes = "Target user note"),
            )
            assertEquals("Target user note", dependencies.getManga.await(target.id)!!.notes)
            scene.awaitLabel("Replacement")
            scene.click(MR.strings.desktop_ui_select.localized())
            withTimeout(5000) {
                while (scene.nodes().none {
                        MR.strings.copy.localized() in flatten(it).flatMap(::label) &&
                            it.config.contains(SemanticsActions.OnClick) &&
                            !it.config.contains(SemanticsProperties.Disabled)
                    }
                ) {
                    scene.render()
                    delay(10)
                }
            }
            assertFalse(MR.strings.desktop_ui_copy_notes.localized() in scene.labels())
            scene.click(MR.strings.copy.localized())
            scene.awaitLabel("Original page")
            assertTrue(dependencies.getManga.await(scene.sourceId)!!.favorite)
            val saved = dependencies.getManga.await(target.id)!!
            assertTrue(saved.favorite)
            assertEquals("Target user note", saved.notes)
            assertTrue(saved.id != scene.sourceId)
        }
    }

    @Test
    fun `changing target source discards stale result selection before next query renders`(
        @TempDir root: File,
    ) = runBlocking {
        withSearch(root, secondSource = true) { scene, dependencies, _ ->
            scene.awaitLabel("Replacement")
            val stale = scene.nodes().last {
                MR.strings.desktop_ui_select.localized() in flatten(it).flatMap(::label) &&
                    it.config.contains(SemanticsActions.OnClick)
            }
            scene.click(dependencies.sourceManager.getCatalogueSources().minBy { it.name }.name)
            scene.render()
            scene.click("Second source")
            stale.config[SemanticsActions.OnClick].action!!.invoke()
            scene.render()
            assertFalse(
                scene.labels().contains(MR.strings.desktop_ui_copy_chapter_read_status.localized()),
                "Old source result cannot open a new-source confirmation",
            )
            scene.awaitLabel("Second source result")
            scene.click(MR.strings.desktop_ui_select.localized())
            scene.awaitLabel(MR.strings.desktop_ui_copy_notes.localized())
            scene.click(MR.strings.copy.localized())
            scene.awaitLabel("Original page")
            val saved = dependencies.mangaRepository.getMangaByUrlAndSourceId("/manga/second-source-target", 98765)!!
            assertTrue(saved.favorite)
            assertTrue(dependencies.getManga.await(scene.sourceId)!!.favorite)
        }
    }

    @Test
    fun `legacy detail factory callback migrates to independent target without rewriting original identity`(
        @TempDir root: File,
    ) = runBlocking {
        withSearch(root) { scene, dependencies, _ ->
            val source = dependencies.getManga.await(scene.sourceId)!!
            val chapters = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
            chapters.addAll(
                listOf(
                    tachiyomi.domain.chapter.model.Chapter.create().copy(
                        mangaId = source.id,
                        url = "/original-one",
                        name = "Original one",
                        chapterNumber = 1.0,
                        read = true,
                        bookmark = true,
                        lastPageRead = 7,
                    ),
                ),
            )
            val before = chapters.getChapterByMangaId(source.id).single()
            val targetSource = dependencies.sourceManager.getCatalogueSources().first()
            val target = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                url = "/manga/target"
                title =
                    "Replacement"
            }
            mihon.desktop.library.MangaDetailScreenModelFactory.create(
                source.id,
            ).migrateTo(targetSource.id, target, source.title)
            val original = dependencies.getManga.await(source.id)!!
            assertEquals(source.source, original.source)
            assertEquals(source.url, original.url)
            assertEquals(before, chapters.getChapterByMangaId(source.id).single())
            assertFalse(original.favorite)
            val saved = dependencies.mangaRepository.getMangaByUrlAndSourceId(target.url, targetSource.id)!!
            assertTrue(saved.favorite && saved.id != source.id)
            val copied = chapters.getChapterByMangaId(saved.id).single()
            assertTrue(copied.id != before.id && copied.read && copied.bookmark)
        }
    }

    @Test
    fun `actual DI startup restores prepared files before opening new migration runtime`(
        @TempDir root: File,
    ) = runBlocking {
        lateinit var artifact: File
        var sourceId = 0L
        withSearch(root) { scene, dependencies, _ ->
            sourceId = scene.sourceId
            val source = dependencies.getManga.await(sourceId)!!
            val target = dependencies.mangaRepository.insertNetworkManga(
                listOf(Manga.create().copy(source = 888, url = "/startup-target", title = "Startup target")),
            ).single()
            dependencies.customCoverStore.write(source.id, byteArrayOf(7, 8, 9))
            dependencies.customCoverStore.write(target.id, byteArrayOf(4, 5, 6))
            artifact =
                Injekt.get<DesktopDownloadProvider>().chapterDownloadDir(
                    source.source,
                    source.title,
                    "Original",
                ).apply {
                    mkdirs()
                }
            File(artifact, "1.jpg").writeBytes(byteArrayOf(1, 2, 3))
            val accepted = dependencies.migrateManga.accept(
                source,
                mihon.desktop.domain.MigrationOptions(copyCustomCover = true, removeDownloads = true),
                true,
            )
            val command = mihon.domain.migration.MigrationCommit(
                source.id, source.source, source.url, target.id, target.source, target.url,
                setOf(
                    mihon.domain.migration.models.MigrationFlag.CUSTOM_COVER,
                    mihon.domain.migration.models.MigrationFlag.REMOVE_DOWNLOAD,
                ),
                true,
                accepted.acceptedAt, accepted.operationId, previousCoverVersion = 0, coverVersion = accepted.acceptedAt,
            )
            dependencies.mangaRepository.prepareMigration(command)
            val files = Injekt.get<mihon.desktop.domain.DesktopMigrationFiles>()
            files.staging(
                accepted.operationId,
            ).prepare(accepted.files!!, dependencies.customCoverStore.getCustomCoverFile(target.id))
            dependencies.mangaRepository.markMigrationFilesReady(command)
            assertFalse(artifact.exists())
        }
        val node = Preferences.userRoot().node("mihon-tests/migration-reopened-${UUID.randomUUID()}")
        val reopened = initDesktopDIForTest(root, DesktopPreferenceStore(node), startDownloadWorker = true)
        try {
            val repository = Injekt.get<MangaRepository>()
            assertTrue(
                artifact.exists(),
                "Production registration must consume original staging before returning its running graph",
            )
            assertArrayEquals(byteArrayOf(1, 2, 3), File(artifact, "1.jpg").readBytes())
            assertEquals(null, repository.migrationReceipt(sourceId))
            assertTrue(repository.getMangaById(sourceId).favorite)
        } finally {
            reopened.closeAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `changed accepted files explain reconfirmation and retain original favorite`(
        @TempDir root: File,
    ) = runBlocking {
        withSearch(root) { scene, dependencies, server ->
            val source = dependencies.getManga.await(scene.sourceId)!!
            val original = Injekt.get<DesktopDownloadProvider>().chapterDownloadDir(
                source.source,
                source.title,
                "Original",
            ).apply {
                mkdirs()
            }
            val page = File(original, "1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            scene.awaitLabel("Replacement")
            scene.click(MR.strings.desktop_ui_select.localized())
            scene.awaitLabel(MR.strings.delete_downloaded.localized())
            val checkbox = scene.nodes().filter { it.config.contains(SemanticsProperties.ToggleableState) }.last()
            checkbox.config[SemanticsActions.OnClick].action!!.invoke()
            scene.render()
            val started = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    started.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return MockResponse.Builder().body(
                        """
                        {"result":"ok","total":1,
                        "data":[{"id":"changed-target-one",
                        "attributes":{"chapter":"1","title":"One","translatedLanguage":"en"},
                        "relationships":[]}]}""",
                    ).build()
                }
            }
            try {
                scene.click(MR.strings.action_migrate.localized())
                assertTrue(withContext(Dispatchers.IO) { started.await(5, java.util.concurrent.TimeUnit.SECONDS) })
                page.writeBytes(byteArrayOf(4, 5, 6))
                release.countDown()
                withTimeout(5000) {
                    while (dependencies.mangaRepository.migrationReceipt(source.id) == null ||
                        scene.nodes().none {
                            (
                                MR.strings.action_cancel.localized() in flatten(it).flatMap(::label) ||
                                    MR.strings.action_close.localized() in flatten(it).flatMap(::label)
                                ) &&
                                it.config.contains(SemanticsActions.OnClick) &&
                                !it.config.contains(SemanticsProperties.Disabled)
                        }
                    ) {
                        scene.render()
                        delay(10)
                    }
                }
                assertTrue(
                    MR.strings.desktop_migration_files_changed.localized() in scene.labels(),
                    "Actual completed file refusal must explain reconfirmation: ${scene.labels()}",
                )
                assertTrue(dependencies.getManga.await(source.id)!!.favorite)
                assertArrayEquals(byteArrayOf(4, 5, 6), page.readBytes())
                val dialogOwner = scene.owners.last()
                scene.click(MR.strings.action_cancel.localized())
                withTimeout(5000) {
                    while (dialogOwner in scene.owners) {
                        scene.render()
                        delay(10)
                    }
                }
                assertEquals(null, dependencies.mangaRepository.migrationReceipt(source.id))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `committed cleanup failure permits Close without rolling back or reapplying SQL`(
        @TempDir root: File,
    ) = runBlocking {
        withSearch(root) { scene, dependencies, _ ->
            scene.awaitLabel("Replacement")
            scene.click(MR.strings.desktop_ui_select.localized())
            scene.awaitLabel(MR.strings.desktop_ui_copy_notes.localized())
            val driver = Injekt.get<app.cash.sqldelight.db.SqlDriver>()
            driver.execute(
                null,
                """CREATE TRIGGER reject_ui_ack BEFORE DELETE ON chapter_directory_phases
                WHEN json_extract(OLD.payload, '$.migrationReceipt.request.operationId') IS NOT NULL
                BEGIN SELECT RAISE(ABORT, 'ACK refused'); END""",
                0,
            )
            try {
                scene.click(MR.strings.action_migrate.localized())
                withTimeout(5000) {
                    while (dependencies.mangaRepository.migrationReceipt(scene.sourceId)?.committed !=
                        true ||
                        scene.nodes().none {
                            (
                                MR.strings.action_cancel.localized() in flatten(it).flatMap(::label) ||
                                    MR.strings.action_close.localized() in flatten(it).flatMap(::label)
                                ) &&
                                it.config.contains(SemanticsActions.OnClick) &&
                                !it.config.contains(SemanticsProperties.Disabled)
                        }
                    ) {
                        scene.render()
                        delay(10)
                    }
                }
                assertTrue(
                    MR.strings.desktop_migration_cleanup_pending.localized() in scene.labels(),
                    "Actual committed failure feedback: ${scene.labels()}",
                )
                val receipt = dependencies.mangaRepository.migrationReceipt(scene.sourceId)!!
                assertTrue(receipt.committed && receipt.filesComplete)
                assertFalse(dependencies.getManga.await(scene.sourceId)!!.favorite)
                assertTrue(
                    MR.strings.action_close.localized() in scene.labels(),
                    "A committed result must have a real Close exit instead of rollback cancellation",
                )
                val dialogOwner = scene.owners.last()
                scene.click(MR.strings.action_close.localized())
                withTimeout(5000) {
                    while (dialogOwner in scene.owners) {
                        scene.render()
                        delay(10)
                    }
                }
                assertEquals(receipt, dependencies.mangaRepository.migrationReceipt(scene.sourceId))
                dependencies.mangaRepository.update(
                    tachiyomi.domain.manga.model.MangaUpdate(
                        receipt.request.targetMangaId,
                        notes = "After committed cleanup failure",
                    ),
                )
            } finally {
                driver.execute(null, "DROP TRIGGER reject_ui_ack", 0)
            }
            val target = dependencies.migrateManga.recover(scene.sourceId)!!
            assertEquals(40, target.dateAdded)
            assertEquals("After committed cleanup failure", target.notes)
            assertEquals(null, dependencies.mangaRepository.migrationReceipt(scene.sourceId))
        }
    }

    @Test
    fun `cancel after durable batch SQL keeps Cancelled task and acknowledges original committed result`(
        @TempDir root: File,
    ) = runBlocking {
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withSearch(root, commitGate = committed to release) { scene, dependencies, server ->
            val source = dependencies.getManga.await(scene.sourceId)!!
            val controller = dependencies.batchMigrationController
            val targetSource = dependencies.sourceManager.getCatalogueSources().first()
            val id = controller.submit(listOf(mihon.desktop.migration.BatchMigrationRequest(source.id, source.title)))
            withTimeout(5000) {
                while (controller.queue(id)?.items?.single()?.status !=
                    mihon.desktop.migration.BatchMigrationItemStatus.WAITING_FOR_USER
                ) {
                    delay(10)
                }
            }
            try {
                controller.selectTarget(
                    id,
                    source.id,
                    mihon.desktop.migration.BatchMigrationTargetSelection(
                        targetSource.id,
                        "/manga/target",
                        "Replacement",
                    ),
                    mihon.desktop.migration.BatchMigrationOptions(),
                )
                withTimeout(5000) { committed.await() }
                assertFalse(dependencies.getManga.await(source.id)!!.favorite)
                val requests = server.requestCount
                controller.cancelAll(id)
                release.complete(Unit)
                withTimeout(5000) { controller.awaitStopped() }
                assertEquals(
                    mihon.domain.task.TaskStatus.Cancelled,
                    Injekt.get<mihon.desktop.task.DesktopTaskScheduler>().snapshot(id)?.status,
                )
                assertEquals(
                    mihon.desktop.migration.BatchMigrationItemStatus.SUCCESS,
                    controller.queue(id)?.items?.single()?.status,
                    "Already committed original unit must be recorded truthfully without uncancelling the task",
                )
                assertEquals(null, dependencies.mangaRepository.migrationReceipt(source.id))
                assertEquals(
                    40,
                    dependencies.mangaRepository.getMangaByUrlAndSourceId("/manga/target", targetSource.id)!!.dateAdded,
                )
                controller.recover()
                delay(30)
                assertEquals(requests, server.requestCount)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun `cancelled committed migration exposes original cleanup retry after checkpoint refusal`(
        @TempDir root: File,
    ) = runBlocking {
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val refusal = java.util.concurrent.atomic.AtomicBoolean(false)
        withSearch(root, checkpointRefusal = refusal, commitGate = committed to release) {
                scene,
                dependencies,
                server,
            ->
            val source = dependencies.getManga.await(scene.sourceId)!!
            val controller = dependencies.batchMigrationController
            val targetSource = dependencies.sourceManager.getCatalogueSources().first()
            val id = controller.submit(listOf(mihon.desktop.migration.BatchMigrationRequest(source.id, source.title)))
            withTimeout(5000) {
                while (controller.queue(id)?.items?.single()?.status !=
                    mihon.desktop.migration.BatchMigrationItemStatus.WAITING_FOR_USER
                ) {
                    delay(10)
                }
            }
            try {
                controller.selectTarget(
                    id,
                    source.id,
                    mihon.desktop.migration.BatchMigrationTargetSelection(
                        targetSource.id,
                        "/manga/target",
                        "Replacement",
                    ),
                    mihon.desktop.migration.BatchMigrationOptions(),
                )
                withTimeout(5000) { committed.await() }
                val requests = server.requestCount
                controller.cancelAll(id)
                refusal.set(true)
                release.complete(Unit)
                withTimeout(5000) { controller.awaitStopped() }
                assertTrue(dependencies.mangaRepository.migrationReceipt(source.id)!!.committed)
                assertTrue(controller.queue(id)!!.cancelled)
                assertTrue(controller.queue(id)!!.items.single().error != null)
                scene.setContent {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                        DesktopTheme {
                            Navigator(ParentScreen) { navigator ->
                                LaunchedEffect(id) { navigator.push(MigrationBatchQueueScreen(id)) }
                                CurrentScreen()
                            }
                        }
                    }
                }
                scene.awaitLabel(MR.strings.desktop_ui_queue_cancelled.localized())
                assertTrue(
                    MR.strings.desktop_migration_continue_cleanup.localized() in scene.labels(),
                    "Cancelled committed work needs an actual original cleanup retry entry",
                )
                assertTrue(MR.strings.desktop_migration_recovery_pending.localized() in scene.labels())
                refusal.set(false)
                scene.click(MR.strings.desktop_migration_continue_cleanup.localized())
                withTimeout(5000) {
                    while (dependencies.mangaRepository.migrationReceipt(source.id) !=
                        null
                    ) {
                        scene.render()
                        delay(10)
                    }
                }
                assertTrue(controller.queue(id)!!.cancelled)
                assertEquals(
                    mihon.domain.task.TaskStatus.Cancelled,
                    Injekt.get<mihon.desktop.task.DesktopTaskScheduler>().snapshot(id)!!.status,
                )
                assertEquals(
                    mihon.desktop.migration.BatchMigrationItemStatus.SUCCESS,
                    controller.queue(id)!!.items.single().status,
                )
                assertEquals(requests, server.requestCount)
                assertEquals(
                    40,
                    dependencies.mangaRepository.getMangaByUrlAndSourceId("/manga/target", targetSource.id)!!.dateAdded,
                )
            } finally {
                release.complete(Unit)
                refusal.set(false)
            }
        }
    }

    @Test
    fun `narrow confirmation supports both theme keyboard traversal Escape and trigger focus`(
        @TempDir root: File,
    ) = runBlocking {
        for (mode in listOf(eu.kanade.domain.ui.model.ThemeMode.LIGHT, eu.kanade.domain.ui.model.ThemeMode.DARK)) {
            withSearch(File(root, mode.name), mode, narrow = true) { scene, dependencies, _ ->
                val source = dependencies.getManga.await(scene.sourceId)!!
                dependencies.customCoverStore.write(source.id, byteArrayOf(7, 8, 9))
                val directory = Injekt.get<DesktopDownloadProvider>().chapterDownloadDir(
                    source.source,
                    source.title,
                    "Original",
                ).apply {
                    mkdirs()
                }
                File(directory, "1.jpg").writeBytes(byteArrayOf(1, 2, 3))
                scene.awaitLabel("Replacement")
                scene.pointer(MR.strings.desktop_ui_select.localized())
                scene.awaitLabel(MR.strings.desktop_ui_copy_notes.localized())
                val owner = scene.owners.last()
                val modalNodes = flatten(owner.unmergedRootSemanticsNode)
                assertEquals(5, modalNodes.count { it.config.contains(SemanticsProperties.ToggleableState) })
                for (text in listOf(
                    MR.strings.action_migrate.localized(),
                    MR.strings.copy.localized(),
                    MR.strings.action_cancel.localized(),
                )) {
                    val action = modalNodes.first {
                        text in flatten(it).flatMap(::label) &&
                            it.config.contains(SemanticsActions.OnClick)
                    }
                    assertTrue(
                        action.boundsInWindow.width > 0 && action.boundsInWindow.left >= 0 &&
                            action.boundsInWindow.right <= 320,
                        "Action '$text' must fit the narrow dialog",
                    )
                }
                val visited = mutableSetOf<Int>()
                repeat(12) {
                    scene.key(Key.Tab)
                    scene.render()
                    delay(10)
                    scene.render()
                    val focused = flatten(owner.unmergedRootSemanticsNode).filter {
                        it.config.getOrElse(SemanticsProperties.Focused) { false }
                    }
                    assertTrue(focused.isNotEmpty(), "Keyboard focus remains in the actual modal")
                    visited += focused.map { it.id }
                }
                assertTrue(visited.size >= 5)
                scene.key(Key.Tab, shift = true)
                scene.render()
                assertTrue(
                    flatten(owner.unmergedRootSemanticsNode).any {
                        it.config.getOrElse(SemanticsProperties.Focused) { false }
                    },
                )
                val scrolling = flatten(owner.unmergedRootSemanticsNode).first {
                    it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                }
                repeat(8) {
                    scene.wheel(scrolling.boundsInWindow.center, 8f)
                    scene.render()
                    delay(10)
                }
                val labelNode = flatten(owner.unmergedRootSemanticsNode).first {
                    MR.strings.delete_downloaded.localized() in
                        label(it)
                }
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                labelNode.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                assertTrue(
                    labelNode.boundsInWindow.height >= layouts.single().size.height,
                    "Last option's whole text must be visible after actual wheel scrolling",
                )
                val checkbox = flatten(owner.unmergedRootSemanticsNode).filter {
                    it.config.contains(SemanticsProperties.ToggleableState)
                }.last()
                assertTrue(checkbox.boundsInWindow.width > 0 && checkbox.boundsInWindow.height > 0)
                assertEquals(
                    checkbox.boundsInRoot.size,
                    checkbox.boundsInWindow.size,
                    "Entire checkbox is inside the actual scrolling viewport",
                )
                scene.pointerAt(checkbox.boundsInWindow.center)
                scene.render()
                assertEquals(
                    androidx.compose.ui.state.ToggleableState.On,
                    flatten(owner.unmergedRootSemanticsNode).filter {
                        it.config.contains(SemanticsProperties.ToggleableState)
                    }.last().config[SemanticsProperties.ToggleableState],
                )
                scene.savePng(
                    File(
                        System.getenv("MIHON_RI15_VISUAL_DIR") ?: root.absolutePath,
                        "ri15-confirmation-320-font200-${mode.name.lowercase()}.png",
                    ),
                )
                scene.key(Key.Escape)
                withTimeout(5000) {
                    while (owner in scene.owners) {
                        scene.render()
                        delay(10)
                    }
                }
                scene.render()
                delay(20)
                scene.render()
                assertTrue(
                    scene.nodes().any {
                        MR.strings.desktop_ui_select.localized() in flatten(it).flatMap(::label) &&
                            it.config.getOrElse(SemanticsProperties.Focused) { false }
                    },
                    "Escape restores the actual Select trigger",
                )
                assertTrue(dependencies.getManga.await(scene.sourceId)!!.favorite)
                assertTrue(directory.exists())
            }
        }
    }

    private suspend fun withSearch(
        root: File,
        mode: eu.kanade.domain.ui.model.ThemeMode = eu.kanade.domain.ui.model.ThemeMode.LIGHT,
        narrow: Boolean = false,
        checkpointRefusal: java.util.concurrent.atomic.AtomicBoolean? = null,
        secondSource: Boolean = false,
        commitGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null,
        block: suspend (Scene, DesktopUiDependencies, MockWebServer) -> Unit,
    ) {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url!!.queryParameter("title") == "failure" -> MockResponse.Builder().code(500).build()
                request.url!!.queryParameter(
                    "offset",
                ) == "20" -> MockResponse.Builder().body(search("Second page", "second", 20)).build()
                request.url!!.encodedPath.contains(
                    "/feed",
                ) -> MockResponse.Builder().body(
                    """
                    {"result":"ok","total":1,
                    "data":[{"id":"target-one",
                    "attributes":{"chapter":"1","title":"One","translatedLanguage":"en",
                    "publishAt":"2026-01-01T00:00:00Z"},
                    "relationships":[]}]}""",
                ).build()
                request.url!!.encodedPath.contains(
                    "/second-source/",
                ) -> MockResponse.Builder().body(search("Second source result", "second-source-target", 0)).build()
                else -> MockResponse.Builder().body(search("Replacement", "target", 0)).build()
            }
        }
        server.start()
        val source =
            MangaDexSource(
                OkHttpClient(),
                kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                },
                server.url("/").toString().trimEnd('/'),
                null,
            )
        val second =
            MangaDexSource(
                OkHttpClient(),
                kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                },
                server.url("/second-source/").toString().trimEnd('/'),
                null,
            )
        val alternate = object : eu.kanade.tachiyomi.source.CatalogueSource by second {
            override val id = 98765L
            override val name = "Second source"
        }
        val node = Preferences.userRoot().node("mihon-tests/migration-${UUID.randomUUID()}")
        val context =
            initDesktopDIForTest(
                root,
                DesktopPreferenceStore(node),
                startDownloadWorker = false,
                builtInSources = if (secondSource) listOf(source, alternate) else listOf(source),
                mangaRepositoryOverride = commitGate?.let { gate ->
                    { repository ->
                        object : MangaRepository by repository {
                            override suspend fun commitMigration(
                                commit: mihon.domain.migration.MigrationCommit,
                            ): Manga {
                                val result = repository.commitMigration(commit)
                                gate.first.complete(Unit)
                                withContext(NonCancellable) { gate.second.await() }
                                throw CancellationException("Owner cancelled after durable migration SQL")
                            }
                        }
                    }
                },
                taskStoreFactory = checkpointRefusal?.let { refusal ->
                    { path ->
                        mihon.desktop.task.FileTaskCheckpointStore(path, atomicMove = { from, to ->
                            if (refusal.get() &&
                                java.nio.file.Files.readString(from).contains("SUCCESS")
                            ) {
                                throw java.io.IOException("Migration checkpoint refused")
                            }
                            java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            true
                        })
                    }
                },
            )
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dependencies = DesktopUiDependencies.fromInjekt()
        val mangas = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
        val original = mangas.insertNetworkManga(
            listOf(Manga.create().copy(source = 42, url = "/original", title = "Original", notes = "Original notes")),
        ).single()
        mangas.updateAtomically(LibraryMembershipUpdate(original.id, true, 40, emptyList(), notes = "Original notes"))
        dependencies.appPreferences.themeMode.set(mode)
        val scene =
            Scene(
                kotlin.coroutines.coroutineContext,
                original.id,
                if (narrow) IntSize(320, 680) else IntSize(1000, 900),
                if (narrow) 2f else 1f,
            )
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    DesktopTheme {
                        Navigator(ParentScreen) { navigator ->
                            LaunchedEffect(Unit) { navigator.push(MigrationSearchScreen(original.id, original.title)) }
                            CurrentScreen()
                        }
                    }
                }
            }
            block(scene, dependencies, server)
        } finally {
            scene.close()
            Dispatchers.resetMain()
            context.closeAndJoin()
            node.removeNode()
            server.close()
        }
    }

    private fun search(title: String, id: String, offset: Int) =
        """
    {"result":"ok","total":21,"offset":$offset,
    "data":[{"id":"$id",
    "attributes":{"title":{"en":"$title"},
    "description":{"en":"Target"},"status":"ongoing"},
    "relationships":[]}]}"""
    private object ParentScreen : Screen {
        @Composable override fun Content() {
            androidx.compose.material3.Text("Original page")
        }
    }

    private class Scene(
        context: kotlin.coroutines.CoroutineContext,
        val sourceId: Long,
        val size: IntSize,
        fontScale: Float,
    ) : AutoCloseable {
        val owners = linkedSetOf<SemanticsOwner>()
        private val bitmap = ImageBitmap(1000, 900)
        private val canvas = Canvas(bitmap)
        private val scene =
            CanvasLayersComposeScene(
                size = size,
                density = Density(1f, fontScale),
                coroutineContext = context,
                platformContext = object : PlatformContext {
                    override val windowInfo = object : WindowInfo {
                        override val isWindowFocused = true
                        override val containerSize = size
                        override val containerDpSize = DpSize(size.width.dp, size.height.dp)
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
        fun nodes(): List<SemanticsNode> = owners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun labels(): List<String> = nodes().flatMap { label(it) }
        fun click(text: String) {
            val node = nodes().lastOrNull {
                text in flatten(it).flatMap(::label) &&
                    it.config.contains(SemanticsActions.OnClick)
            }
            assertNotNull(node, "Actual clickable control '$text' must be present")
            node!!.config[SemanticsActions.OnClick].action!!.invoke()
        }
        fun wheel(position: Offset, delta: Float) {
            scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(0f, delta))
        }
        fun pointer(text: String) {
            val node = nodes().last {
                text in flatten(it).flatMap(::label) &&
                    it.config.contains(SemanticsActions.OnClick)
            }
            pointerAt(node.boundsInWindow.center)
        }
        fun pointerAt(position: Offset) {
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
        fun key(key: Key, shift: Boolean = false) {
            val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            val factory = events.declaredMethods.single {
                it.name.startsWith("KeyEvent-") &&
                    !it.name.endsWith("\$default")
            }
            for (type in listOf("access\$getKeyDown\$cp", "access\$getKeyUp\$cp")) {
                val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType").getMethod(type).invoke(null)
                scene.sendKeyEvent(
                    ComposeKeyEvent(
                        factory.invoke(
                            null, key.keyCode, eventType, key.nativeKeyLocation, false, false, false, shift, null,
                        ),
                    ),
                )
            }
        }
        fun savePng(file: File) {
            file.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                val pixels = javax.imageio.ImageIO.read(
                    java.io.ByteArrayInputStream(requireNotNull(image.encodeToData()).bytes),
                )
                javax.imageio.ImageIO.write(pixels.getSubimage(0, 0, size.width, size.height), "png", file)
            }
        }
        suspend fun awaitLabel(text: String) = withTimeout(5000) {
            while (text !in
                labels()
            ) {
                render()
                delay(10)
            }
            render()
        }
        override fun close() = scene.close()
    }
    companion object {
        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        private fun label(node: SemanticsNode): List<String> =
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
    }
}
