package mihon.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.ui.reader.DualPageDisplayUnitCompositionIdentityKey
import mihon.desktop.ui.reader.DualPageDisplayUnitIdKey
import mihon.desktop.ui.reader.PairingLoad
import mihon.desktop.ui.reader.ReaderPagerNativePresentationKey
import mihon.domain.reader.ChapterPairingRepository
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.observability.ReaderIoEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterPairingRepositoryImpl
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

@OptIn(ExperimentalComposeUiApi::class)
class DesktopChapterPairingComposePersistenceTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun heldRecordLeavesNativeContentAndNavigationAvailable() = runBlocking {
        openDatabase(tempDir.resolve("held-read.sqlite"), create = true).use { (handler, driver) ->
            seed(driver)
            val repository = ChapterPairingRepositoryImpl(handler)
            val saved = repository.replace(7, 10, 0, 8, setOf(3))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val coordinator = DesktopChapterPairingCoordinator(object : ChapterPairingRepository by repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    entered.complete(Unit)
                    release.await()
                    return repository.load(chapterId, mangaId)
                }
            })
            mihon.desktop.test.state.readerState.open(7, 1, 8, false, "M", "C", false, false)
            val fixture = mounted(tempDir.resolve("held"), coordinator, 1, ConcurrentHashMap.newKeySet())
            try {
                fixture.releaseBackgroundGates()
                pump(fixture, "The original record read must enter") { entered.isCompleted }
                pump(fixture, "Actual first pixels must not wait for the original record") {
                    fixture.renderContainsFixturePixels(System.nanoTime()) && visibleFrames(fixture).isNotEmpty()
                }
                val state = requireNotNull(mihon.desktop.test.state.readerState.mountedSnapshot())
                assertEquals(PairingLoad.LOADING, state.pairingLoad)
                assertEquals(null, state.dualPairingState?.revision)
                awaitAdjustButton(fixture)
                assertTrue(adjustButton(fixture).config.contains(SemanticsProperties.Disabled))
                assertEquals(saved, repository.load(7, 10))
                // Real reader tap continues to a future frame while the original read remains held.
                tap(fixture, Offset(600f, 240f))
                pump(fixture, "Source navigation must remain usable during the held record") {
                    mihon.desktop.test.state.readerState.currentPage == 3 &&
                        fixture.visiblePagesAtViewportCenter() == setOf(3, 4) &&
                        fixture.renderContainsFixturePixels(System.nanoTime())
                }
                val before = nativeSnapshot(fixture)
                val pixels = blueBounds(fixture)
                assertTrue(pixels.isNotEmpty())
                release.complete(Unit)
                pump(fixture, "Only the original late record restores the same current frame") {
                    mihon.desktop.test.state.readerState.mountedSnapshot()?.pairingLoad == PairingLoad.READY
                }
                repeat(12) {
                    fixture.scene.render(System.nanoTime()).close()
                    assertEquals(before, nativeSnapshot(fixture))
                    assertEquals(pixels, blueBounds(fixture))
                    assertEquals(3, mihon.desktop.test.state.readerState.currentPage)
                    delay(5)
                }
                assertEquals(
                    setOf(3),
                    requireNotNull(
                        mihon.desktop.test.state.readerState.mountedSnapshot(),
                    ).forcedSinglePages,
                )
                assertEquals(saved, repository.load(7, 10))
                awaitAdjustButton(fixture)
                assertTrue(!adjustButton(fixture).config.contains(SemanticsProperties.Disabled))
                tap(fixture, Offset(600f, 240f))
                pump(fixture, "Next explicit navigation applies the restored boundary without skipping a source") {
                    fixture.visiblePagesAtViewportCenter() == setOf(4, 5) &&
                        fixture.renderContainsFixturePixels(System.nanoTime())
                }
                assertEquals(saved, repository.load(7, 10))
            } finally {
                release.complete(Unit)
                fixture.close()
                coordinator.stop()
                coordinator.awaitStopped()
                mihon.desktop.test.state.readerState.reset()
            }
        }
    }

    @Test
    fun failedRecordKeepsNativeContentWithOptionalReloadInSettings() = runBlocking {
        openDatabase(tempDir.resolve("optional-reload.sqlite"), create = true).use { (handler, driver) ->
            seed(driver)
            val repository = ChapterPairingRepositoryImpl(handler)
            val saved = repository.replace(7, 10, 0, 8, setOf(3))
            val reads = AtomicInteger()
            val failRead = java.util.concurrent.atomic.AtomicBoolean(true)
            val release = CompletableDeferred<Unit>()
            val coordinator = DesktopChapterPairingCoordinator(object : ChapterPairingRepository by repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    reads.incrementAndGet()
                    assertEquals(7L, chapterId)
                    assertEquals(10L, mangaId)
                    if (failRead.get()) error("Original record read failed")
                    release.await()
                    return repository.load(chapterId, mangaId)
                }
            })
            mihon.desktop.test.state.readerState.open(7, 1, 8, false, "M", "C", false, false)
            val fixture = mounted(tempDir.resolve("optional-reload"), coordinator, 1, ConcurrentHashMap.newKeySet())
            try {
                fixture.releaseBackgroundGates()
                pump(fixture, "The failed record must still permit native pixels") {
                    mihon.desktop.test.state.readerState.mountedSnapshot()?.pairingLoad == PairingLoad.ERROR &&
                        fixture.renderContainsFixturePixels(System.nanoTime())
                }
                awaitAdjustButton(fixture, Offset(320f, 400f))
                assertTrue(adjustButton(fixture).config.contains(SemanticsProperties.Disabled))
                val before = nativeSnapshot(fixture)
                for (succeeds in listOf(false, true)) {
                    val settings = requireReaderAction(fixture, "Reader Settings", description = true)
                    assertTrue(requireNotNull(settings.config[SemanticsActions.OnClick].action).invoke())
                    pump(fixture, "The existing settings must open") {
                        mihon.desktop.test.state.readerState.mountedSnapshot()?.showSettings == true
                    }
                    println(
                        "Optional reload before UI readiness succeeds=$succeeds reads=${reads.get()}; " +
                            readerActionSummary(fixture),
                    )
                    pump(fixture, "The actual optional Reload action must be composed before selecting it") {
                        readerActionNodes(fixture, "Reload page pairing").isNotEmpty()
                    }
                    println(
                        "Optional reload after UI readiness succeeds=$succeeds reads=${reads.get()}; " +
                            readerActionSummary(fixture),
                    )
                    assertTrue(
                        texts(fixture).none {
                            it == "Could not load the saved page pairing." || it == "Retry" ||
                                it == "Use default pairing this time"
                        },
                        "Dual settings must not present the obsolete error or Retry and Default choice",
                    )
                    val reload = requireReaderAction(fixture, "Reload page pairing")
                    val count = reads.get()
                    failRead.set(!succeeds)
                    assertTrue(requireNotNull(reload.config[SemanticsActions.OnClick].action).invoke())
                    pump(fixture, "Only the optional same-chapter reload performs a new read") {
                        reads.get() ==
                            count + 1
                    }
                    if (succeeds) {
                        release.complete(Unit)
                        pump(fixture, "Optional reload restores the original record") {
                            mihon.desktop.test.state.readerState.mountedSnapshot()?.pairingLoad == PairingLoad.READY
                        }
                        assertEquals(
                            saved.revision,
                            mihon.desktop.test.state.readerState.mountedSnapshot()?.pairingRevision,
                        )
                    } else {
                        pump(fixture, "A failed optional reload retains the true failure") {
                            mihon.desktop.test.state.readerState.mountedSnapshot()?.pairingLoad == PairingLoad.ERROR
                        }
                        assertEquals(
                            null,
                            mihon.desktop.test.state.readerState.mountedSnapshot()?.dualPairingState?.revision,
                        )
                    }
                    val close = requireReaderAction(fixture, "Close")
                    assertTrue(requireNotNull(close.config[SemanticsActions.OnClick].action).invoke())
                    pump(fixture, "Closing settings returns to the unchanged reader") {
                        mihon.desktop.test.state.readerState.mountedSnapshot()?.showSettings == false
                    }
                    assertEquals(before, nativeSnapshot(fixture))
                    assertTrue(fixture.renderContainsFixturePixels(System.nanoTime()))
                    assertEquals(saved, repository.load(7, 10))
                    assertEquals(5L, queryLong(driver, "SELECT last_page_read FROM chapters WHERE _id=7"))
                }
            } finally {
                release.complete(Unit)
                fixture.close()
                coordinator.stop()
                coordinator.awaitStopped()
                mihon.desktop.test.state.readerState.reset()
            }
        }
    }

    @Test
    fun singleRecordRecoveryNeverBlocksPixelsAndReloadKeepsCurrentAnchor() =
        recordRecovery(MountedReaderPresentationMode.SINGLE)

    @Test
    fun webtoonRecordRecoveryNeverBlocksPixelsAndReloadKeepsCurrentAnchor() =
        recordRecovery(MountedReaderPresentationMode.WEBTOON)

    @Test
    fun dualRecordRecoveryRetainsPendingReloadAction() =
        recordRecovery(MountedReaderPresentationMode.DUAL)

    private fun recordRecovery(mode: MountedReaderPresentationMode) = runBlocking {
        openDatabase(tempDir.resolve("${mode.name}-recovery.sqlite"), create = true).use { (handler, driver) ->
            seed(driver)
            val repository = ChapterPairingRepositoryImpl(handler)
            val saved = repository.replace(7, 10, 0, 8, setOf(3))
            val reads = AtomicInteger()
            val gate = AtomicReference(CompletableDeferred<Boolean>())
            val coordinator = DesktopChapterPairingCoordinator(object : ChapterPairingRepository by repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    assertEquals(7L, chapterId)
                    assertEquals(10L, mangaId)
                    reads.incrementAndGet()
                    check(gate.get().await()) { "Original pairing read failed" }
                    return repository.load(chapterId, mangaId)
                }
            })
            mihon.desktop.test.state.readerState.open(7, 1, 8, mode.isWebtoon, "M", "C", false, false)
            val fixture = mounted(
                tempDir.resolve(mode.name),
                coordinator,
                1,
                ConcurrentHashMap.newKeySet(),
                mode = mode,
            )
            try {
                fixture.releaseBackgroundGates()
                pump(fixture, "$mode must paint actual pixels while the original SQL read is held") {
                    reads.get() == 1 && fixture.renderContainsFixturePixels(System.nanoTime()) &&
                        blueBounds(fixture).isNotEmpty()
                }
                assertEquals(PairingLoad.LOADING, mihon.desktop.test.state.readerState.mountedSnapshot()?.pairingLoad)
                gate.get().complete(false)
                pump(fixture, "$mode must keep actual pixels after the original read fails") {
                    mihon.desktop.test.state.readerState.mountedSnapshot()?.pairingLoad == PairingLoad.ERROR &&
                        fixture.renderContainsFixturePixels(System.nanoTime())
                }
                openSettings(fixture)
                pump(fixture, "$mode settings must expose its optional recovery") {
                    readerActionNodes(fixture, "Reload page pairing").size == 1
                }
                assertTrue(
                    texts(fixture).none {
                        it == "Could not load the saved page pairing." || it == "Retry" ||
                            it == "Use default pairing this time"
                    },
                )
                for (succeeds in listOf(false, true)) {
                    val next = CompletableDeferred<Boolean>()
                    gate.set(next)
                    val reload = requireReaderAction(fixture, "Reload page pairing")
                    val click = requireNotNull(reload.config[SemanticsActions.OnClick].action)
                    val count = reads.get()
                    assertTrue(click.invoke())
                    pump(fixture, "Reload must read the current owner exactly once") { reads.get() == count + 1 }
                    pump(fixture, "The same Reload action must remain disabled while pending") {
                        readerActionNodes(fixture, "Reload page pairing").singleOrNull()
                            ?.config?.contains(SemanticsProperties.Disabled) == true
                    }
                    click.invoke()
                    repeat(3) {
                        fixture.scene.render(System.nanoTime()).close()
                        delay(5)
                    }
                    assertEquals(count + 1, reads.get())
                    if (mode == MountedReaderPresentationMode.SINGLE && succeeds) {
                        clickText(fixture, "Dual Page (side-by-side)")
                        pump(fixture, "Changing mode during reload must retain the pending owner action") {
                            mihon.desktop.test.state.readerState.mountedSnapshot()?.dualPageMode == false &&
                                readerActionNodes(fixture, "Reload page pairing").singleOrNull()
                                    ?.config?.contains(SemanticsProperties.Disabled) == true
                        }
                    }
                    clickText(fixture, "Close")
                    pump(fixture, "Closing pending settings must leave real pixels readable") {
                        mihon.desktop.test.state.readerState.mountedSnapshot()?.showSettings == false &&
                            fixture.renderContainsFixturePixels(System.nanoTime())
                    }
                    openSettings(fixture)
                    assertTrue(
                        requireReaderAction(
                            fixture,
                            "Reload page pairing",
                        ).config.contains(SemanticsProperties.Disabled),
                    )
                    clickText(fixture, "Close")
                    pump(fixture, "Reclosing pending settings returns to the same pixels") {
                        mihon.desktop.test.state.readerState.mountedSnapshot()?.showSettings == false &&
                            fixture.renderContainsFixturePixels(System.nanoTime())
                    }
                    val before = blueBounds(fixture)
                    val page = mihon.desktop.test.state.readerState.currentPage
                    next.complete(succeeds)
                    pump(fixture, "The actual read result must publish without changing the source anchor") {
                        mihon.desktop.test.state.readerState.mountedSnapshot()?.pairingLoad ==
                            if (succeeds) PairingLoad.READY else PairingLoad.ERROR
                    }
                    repeat(8) {
                        fixture.scene.render(System.nanoTime()).close()
                        assertEquals(page, mihon.desktop.test.state.readerState.currentPage)
                        assertEquals(before, blueBounds(fixture))
                    }
                    assertEquals(saved, repository.load(7, 10))
                    openSettings(fixture)
                    if (succeeds) {
                        assertTrue(readerActionNodes(fixture, "Reload page pairing").isEmpty())
                    } else {
                        pump(fixture, "Failed reload must become actionable again") {
                            readerActionNodes(fixture, "Reload page pairing").singleOrNull()?.let {
                                !it.config.contains(SemanticsProperties.Disabled)
                            } == true
                        }
                        if (mode == MountedReaderPresentationMode.SINGLE) {
                            clickText(fixture, "Dual Page (side-by-side)")
                            pump(fixture, "Actual mode change after failure must preserve the anchor") {
                                mihon.desktop.test.state.readerState.mountedSnapshot()?.dualPageMode == true
                            }
                            clickText(fixture, "Close")
                            pump(fixture, "Temporary dual group must include the same source with actual pixels") {
                                fixture.visiblePagesAtViewportCenter().contains(page) &&
                                    fixture.renderContainsFixturePixels(System.nanoTime())
                            }
                            assertEquals(page, mihon.desktop.test.state.readerState.currentPage)
                            openSettings(fixture)
                        }
                    }
                }
            } finally {
                gate.get().complete(false)
                fixture.close()
                coordinator.stop()
                coordinator.awaitStopped()
                mihon.desktop.test.state.readerState.reset()
            }
        }
    }

    private suspend fun openSettings(fixture: MountedReaderPresentationFixture) {
        repeat(12) {
            fixture.scene.render(System.nanoTime()).close()
            val settings = readerActionNodes(fixture, "Reader Settings", description = true).firstOrNull()
            if (settings != null) {
                assertTrue(requireNotNull(settings.config[SemanticsActions.OnClick].action).invoke())
                pump(fixture, "Existing settings must open") {
                    mihon.desktop.test.state.readerState.mountedSnapshot()?.showSettings == true &&
                        readerActionNodes(fixture, "Close").isNotEmpty()
                }
                return
            }
            if (fixture.case.mode == MountedReaderPresentationMode.WEBTOON) {
                // Webtoon exposes the common toolbar through the existing Space shortcut, not pager taps.
                val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
                val type = Class.forName("androidx.compose.ui.input.key.KeyEventType")
                    .getMethod("access\$getKeyDown\$cp").invoke(null)
                val factory = events.declaredMethods.single {
                    it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default")
                }
                val native = factory.invoke(
                    null, androidx.compose.ui.input.key.Key.Spacebar.keyCode, type,
                    0, false, false, false, false, null,
                )
                assertTrue(fixture.scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(native)))
            } else {
                tap(fixture, Offset(320f, 240f))
            }
            delay(5)
        }
        error("Reader settings toolbar unavailable: ${texts(fixture)}")
    }

    @Test
    fun legalUnknownPartnerAdjustmentUsesOriginalButtonWithoutDisablingMatching() = runBlocking {
        openDatabase(tempDir.resolve("unknown-partner.sqlite"), create = true).use { (handler, driver) ->
            seed(driver)
            val repository = ChapterPairingRepositoryImpl(handler)
            val coordinator = DesktopChapterPairingCoordinator(repository)
            mihon.desktop.test.state.readerState.open(7, 1, 8, false, "M", "C", false, false)
            val fixture = MountedReaderPresentationFixture(
                tempDir.resolve("unknown-partner"),
                currentCoroutineContext(),
                MountedReaderPresentationCase(MountedReaderPresentationMode.DUAL, MountedReaderContentRoute.ONLINE),
                pageCount = 8,
                mangaId = 10,
                pairingCoordinator = coordinator,
                initialPage = 1,
                automaticSpreadMatching = true,
                heldResponsePage = 2,
            )
            try {
                fixture.releaseBackgroundGates(excluding = setOf(ReaderIoGatePoint.NON_CURRENT_PAGE))
                pump(fixture, "A real first image and restored record must permit ordinary unknown fallback") {
                    mihon.desktop.test.state.readerState.mountedSnapshot()?.pairingLoad == PairingLoad.READY &&
                        fixture.renderContainsFixturePixels(System.nanoTime())
                }
                val state = requireNotNull(mihon.desktop.test.state.readerState.mountedSnapshot())
                val unit = state.dualPageUnits.single { it.slots.any { slot -> slot.pageId.sourcePageIndex == 1 } }
                assertTrue(!unit.certain)
                assertEquals(setOf(1, 2), unit.slots.map { it.pageId.sourcePageIndex }.toSet())
                assertTrue(state.autoSpreadMatching)
                assertTrue(
                    state.session.activeChapter.pages[2].loadState !=
                        mihon.domain.reader.session.ReaderPageLoadState.Ready,
                )
                awaitAdjustButton(fixture)
                assertTrue(!adjustButton(fixture).config.contains(SemanticsProperties.Disabled))
                assertTrue(requireNotNull(adjustButton(fixture).config[SemanticsActions.OnClick].action).invoke())
                pump(fixture, "Original legal fallback button must save through the real repository") {
                    repository.load(7, 10).record?.forcedSinglePages == setOf(1)
                }
                assertTrue(requireNotNull(mihon.desktop.test.state.readerState.mountedSnapshot()).autoSpreadMatching)
                assertEquals(5L, queryLong(driver, "SELECT last_page_read FROM chapters WHERE _id=7"))
            } finally {
                fixture.close()
                coordinator.stop()
                coordinator.awaitStopped()
                mihon.desktop.test.state.readerState.reset()
            }
        }
    }

    private suspend fun pump(
        fixture: MountedReaderPresentationFixture,
        message: String,
        condition: suspend () -> Boolean,
    ) {
        val end = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < end) {
            fixture.scene.render(System.nanoTime()).close()
            if (condition()) return
            delay(5)
        }
        val current = mihon.desktop.test.state.readerState.mountedSnapshot()
        throw AssertionError(
            "$message; pages=${fixture.visiblePagesAtViewportCenter()}; state=$current; " +
                "text=${texts(fixture)}",
        )
    }

    private fun tap(fixture: MountedReaderPresentationFixture, point: Offset) {
        fixture.scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
        fixture.scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
    }

    private fun nativeSnapshot(fixture: MountedReaderPresentationFixture): Pair<Any, Any> {
        val visible = nodes(fixture).filter { it.boundsInRoot.contains(Offset(500f, 240f)) }
        val presenter = visible.first { it.config.contains(ReaderPagerNativePresentationKey) }
            .config[ReaderPagerNativePresentationKey]
        val mount = visible.first { it.config.contains(DualPageDisplayUnitCompositionIdentityKey) }
            .config[DualPageDisplayUnitCompositionIdentityKey]
        return requireNotNull(presenter.frameId) to mount
    }

    private fun blueBounds(fixture: MountedReaderPresentationFixture): List<Int> {
        fixture.scene.render(System.nanoTime()).use { image ->
            val bitmap = image.toComposeImageBitmap().asSkiaBitmap()
            try {
                var left = bitmap.width
                var top = bitmap.height
                var right = -1
                var bottom = -1
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) {
                        if (bitmap.getColor(x, y) and 0x00FFFFFF == 0x0000FF) {
                            left = minOf(left, x)
                            top = minOf(top, y)
                            right = maxOf(right, x)
                            bottom = maxOf(bottom, y)
                        }
                    }
                }
                return if (right < 0) emptyList() else listOf(left, top, right, bottom)
            } finally {
                bitmap.close()
            }
        }
    }

    private fun clickDescription(fixture: MountedReaderPresentationFixture, description: String) {
        val button = nodes(fixture).first {
            it.config.contains(SemanticsActions.OnClick) &&
                it.config.contains(SemanticsProperties.ContentDescription) &&
                description in it.config[SemanticsProperties.ContentDescription]
        }
        assertTrue(requireNotNull(button.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun clickText(fixture: MountedReaderPresentationFixture, text: String) {
        val button = nodes(fixture).first {
            it.config.contains(SemanticsActions.OnClick) && text in textsFrom(it)
        }
        assertTrue(requireNotNull(button.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun textsFrom(node: SemanticsNode): List<String> =
        (
            if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text].map { it.text }
            } else {
                emptyList()
            }
            ) + node.children.flatMap(::textsFrom)

    @Test
    fun `online reader with saved chapter identity presents its first paired pages`() = runBlocking {
        openDatabase(tempDir.resolve("online-pairing.sqlite"), create = true).use { (handler, driver) ->
            seed(driver)
            val repository = ChapterPairingRepositoryImpl(handler)
            val saved = repository.replace(7, 10, 0, 8, setOf(3))
            val loads = AtomicInteger()
            val loaded = AtomicReference<ChapterPairingSnapshot?>()
            val coordinator = DesktopChapterPairingCoordinator(object : ChapterPairingRepository by repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    loads.incrementAndGet()
                    return repository.load(chapterId, mangaId).also(loaded::set)
                }
            })
            val decoded = ConcurrentHashMap.newKeySet<Int>()
            val fixture = mounted(
                tempDir.resolve("online"),
                coordinator,
                initialPage = 1,
                completedDecodes = decoded,
                route = MountedReaderContentRoute.ONLINE,
            )
            try {
                fixture.releaseBackgroundGates(excluding = setOf(ReaderIoGatePoint.NON_CURRENT_PAGE))
                withTimeout(20_000) {
                    while (!setOf(1, 2).all(decoded::contains) || visibleFrames(fixture).isEmpty()) {
                        if (fixture.events().any { it.type == "FIRST_PAGE_PRESENTED" }) {
                            fixture.releaseBackgroundGates()
                        }
                        fixture.scene.render().close()
                        delay(10)
                    }
                }
                assertEquals(1, loads.get())
                assertEquals(saved, loaded.get())
                assertTrue(fixture.httpRequestCount() > 0)
                assertTrue(visibleFrames(fixture).isNotEmpty())
            } finally {
                fixture.close()
                coordinator.stop()
                coordinator.awaitStopped()
            }
        }
    }

    @Test
    fun `deleting a chapter download preserves its saved pairing and reading progress`() = runBlocking {
        val databaseFile = tempDir.resolve("download-delete.sqlite")
        openDatabase(databaseFile, create = true).use { (handler, driver) ->
            seed(driver)
            val repository = ChapterPairingRepositoryImpl(handler)
            repository.replace(7, 10, 0, 8, setOf(3))
            val provider = DesktopDownloadProvider(tempDir.resolve("downloads"))
            val identity = DownloadChapterIdentity(
                sourceDisplayName = "Source",
                mangaTitle = "M",
                chapterName = "C",
                scanlator = null,
                chapterUrl = "/current",
                disallowNonAsciiFilenames = false,
            )
            val artifact = provider.canonicalChapterDownloadDir(identity).apply { mkdirs() }
            assertTrue(
                ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpg", artifact.resolve("001.jpg")),
            )
            assertTrue(provider.isChapterDownloaded(42, identity))

            assertTrue(provider.deleteChapterDownload(42, identity))

            assertTrue(!artifact.exists())
            assertTrue(!provider.isChapterDownloaded(42, identity))
            assertEquals(setOf(3), repository.load(7, 10).record?.forcedSinglePages)
            assertEquals(5L, queryLong(driver, "SELECT last_page_read FROM chapters WHERE _id=7"))
        }
        openDatabase(databaseFile).use { (handler, driver) ->
            assertEquals(setOf(3), ChapterPairingRepositoryImpl(handler).load(7, 10).record?.forcedSinglePages)
            assertEquals(5L, queryLong(driver, "SELECT last_page_read FROM chapters WHERE _id=7"))
        }
    }

    @Test
    fun `real reader adjustment button saves to file and reopened screen restores containing spread`() = runBlocking {
        val databaseFile = tempDir.resolve("pairing.sqlite")
        val receiverFile = tempDir.resolve("pairing-receiver.sqlite")
        openDatabase(databaseFile, create = true).use { (first, driver) ->
            seed(driver)
            val journal = mihon.data.sync.journal.SyncLocalJournal(first)
            journal.connect(
                "layout-space",
                1,
                mihon.domain.sync.transport.SyncRepository("owner", "sync", "sync"),
                "desktop",
                1,
            )
            val repository = ChapterPairingRepositoryImpl(first)
            val coordinator = DesktopChapterPairingCoordinator(repository)
            val completedDecodes = ConcurrentHashMap.newKeySet<Int>()
            val fixture =
                mounted(tempDir.resolve("first"), coordinator, initialPage = 1, completedDecodes = completedDecodes)
            try {
                fixture.releaseBackgroundGates(excluding = setOf(ReaderIoGatePoint.NON_CURRENT_PAGE))
                awaitDecodedPages(fixture, completedDecodes, setOf(1, 2))
                awaitAdjustButton(fixture)
                val button = adjustButton(fixture)
                assertTrue(requireNotNull(button.config[SemanticsActions.OnClick].action).invoke())
                withTimeout(10_000) {
                    while (repository.load(7, 10).record?.forcedSinglePages != setOf(1)) yield()
                }
                val events = journal.pendingEvents("layout-space", 1)
                val event = events.single { it.effects.any { effect -> effect.field.name == "CHAPTER_PAIRING" } }
                assertEquals(2, event.protocolVersion)
                assertEquals(mihon.domain.sync.SyncOrigin.USER, event.origin)
                assertEquals(mihon.domain.sync.SyncCategory.READING, event.category)
                val effect = event.effects.single()
                assertEquals("SET_PAIRING", effect.kind.name)
                assertEquals("/current", effect.objectKey.originalUrl)
                openDatabase(receiverFile, create = true).use { (receiver, receiverDriver) ->
                    seed(receiverDriver)
                    receivePairing(receiver, event)
                }
                assertEquals(5L, queryLong(driver, "SELECT last_page_read FROM chapters WHERE _id=7"))
            } finally {
                fixture.close()
                coordinator.stop()
                coordinator.awaitStopped()
            }
        }
        openDatabase(receiverFile).use { (reopened, _) ->
            val coordinator = DesktopChapterPairingCoordinator(ChapterPairingRepositoryImpl(reopened))
            val completedDecodes = ConcurrentHashMap.newKeySet<Int>()
            mihon.desktop.test.state.readerState.open(7, 5, 8, false, "M", "C", false, false)
            val fixture =
                mounted(tempDir.resolve("reopened"), coordinator, initialPage = 5, completedDecodes = completedDecodes)
            try {
                fixture.releaseBackgroundGates(excluding = setOf(ReaderIoGatePoint.NON_CURRENT_PAGE))
                awaitDecodedPages(fixture, completedDecodes, setOf(4, 5))
                fixture.releaseBackgroundGates()
                pump(fixture, "The reopened original record and original content must be available") {
                    mihon.desktop.test.state.readerState.mountedSnapshot()?.pairingLoad == PairingLoad.READY &&
                        fixture.events().any { it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED.name } &&
                        fixture.renderContainsFixturePixels(System.nanoTime())
                }
                val firstFrame = nativeSnapshot(fixture)
                repeat(3) {
                    fixture.scene.render(System.nanoTime()).close()
                    assertEquals(firstFrame, nativeSnapshot(fixture))
                }
                // A late record keeps the first frame. The original explicit setting starts a new context.
                awaitAdjustButton(fixture)
                clickDescription(fixture, "Reader Settings")
                pump(fixture, "The original settings dialog must open") {
                    nodes(fixture).any { textsFrom(it).contains("Dual Page (side-by-side)") }
                }
                clickText(fixture, "Dual Page (side-by-side)")
                pump(fixture, "Original Dual off must change the reader mode") {
                    mihon.desktop.test.state.readerState.mountedSnapshot()?.dualPageMode == false
                }
                clickText(fixture, "Dual Page (side-by-side)")
                pump(fixture, "Original Dual on must retain the source target") {
                    mihon.desktop.test.state.readerState.mountedSnapshot()?.dualPageMode == true
                }
                clickText(fixture, "Close")
                pump(fixture, "The explicit new context must retain the logical source target and actual pixels") {
                    mihon.desktop.test.state.readerState.currentPage == 5 &&
                        fixture.visiblePagesAtViewportCenter().contains(5) &&
                        fixture.renderContainsFixturePixels(System.nanoTime())
                }
                assertEquals(
                    setOf(1),
                    requireNotNull(mihon.desktop.test.state.readerState.mountedSnapshot()).forcedSinglePages,
                )
                // A cold local group has no global phase. Navigate normally to the saved manual boundary.
                repeat(6) {
                    val previous = mihon.desktop.test.state.readerState.currentPage
                    if (previous > 1) {
                        tap(fixture, Offset(40f, 240f))
                        pump(fixture, "The original previous-page tap must reach a prior source and actual pixels") {
                            mihon.desktop.test.state.readerState.currentPage < previous &&
                                fixture.renderContainsFixturePixels(System.nanoTime())
                        }
                    }
                }
                pump(fixture, "The saved boundary must make its actual source a singleton on normal revisit") {
                    mihon.desktop.test.state.readerState.currentPage == 1 &&
                        fixture.visiblePagesAtViewportCenter() == setOf(1) &&
                        fixture.renderContainsFixturePixels(System.nanoTime())
                }
                val visibleFrames = visibleFrames(fixture)
                assertEquals(1, visibleFrames.size)
                assertEquals(
                    setOf(1),
                    visibleFrames.single().config[DualPageDisplayUnitIdKey].slots.mapNotNull {
                        it.pageId?.sourcePageIndex
                    }.toSet(),
                )
                assertEquals(setOf(1), ChapterPairingRepositoryImpl(reopened).load(7, 10).record?.forcedSinglePages)
            } finally {
                fixture.close()
                coordinator.stop()
                coordinator.awaitStopped()
            }
        }
    }

    private suspend fun mounted(
        root: File,
        coordinator: DesktopChapterPairingCoordinator?,
        initialPage: Int,
        completedDecodes: MutableSet<Int>,
        route: MountedReaderContentRoute = MountedReaderContentRoute.DIRECTORY,
        mode: MountedReaderPresentationMode = MountedReaderPresentationMode.DUAL,
    ) =
        MountedReaderPresentationFixture(
            root = root,
            coroutineContext = currentCoroutineContext(),
            case = MountedReaderPresentationCase(mode, route),
            pageCount = 8,
            mangaId = 10,
            pairingCoordinator = coordinator,
            initialPage = initialPage,
            pageImageDecoder = DesktopReaderPageImageDecoder { encoded, key ->
                completedDecodes += key.pageIndex
                SkiaDesktopReaderPageImageDecoder().decode(encoded, key)
            },
        )

    private suspend fun awaitAdjustButton(
        fixture: MountedReaderPresentationFixture,
        point: Offset = Offset(320f, 240f),
    ) {
        try {
            withTimeout(10_000) {
                repeat(12) {
                    fixture.scene.render().close()
                    if (runCatching { adjustButton(fixture) }.isSuccess) return@withTimeout
                    fixture.scene.sendPointerEvent(
                        PointerEventType.Press,
                        point,
                        button = PointerButton.Primary,
                    )
                    fixture.scene.sendPointerEvent(
                        PointerEventType.Release,
                        point,
                        button = PointerButton.Primary,
                    )
                    fixture.scene.render().close()
                    yield()
                }
                throw AssertionError("Adjust button did not appear after 12 center taps")
            }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            val descriptions = nodes(fixture).flatMap { node ->
                if (node.config.contains(SemanticsProperties.ContentDescription)) {
                    node.config[SemanticsProperties.ContentDescription]
                } else {
                    emptyList()
                }
            }
            throw AssertionError(
                "Adjust button unavailable; text=${texts(fixture)}, descriptions=$descriptions",
                failure,
            )
        }
    }

    private suspend fun awaitDecodedPages(
        fixture: MountedReaderPresentationFixture,
        completedDecodes: Set<Int>,
        pages: Set<Int>,
    ) {
        withTimeout(10_000) {
            repeat(400) {
                fixture.scene.render().close()
                if (pages.all(completedDecodes::contains) && visibleFrames(fixture).isNotEmpty()) return@withTimeout
                yield()
            }
            throw AssertionError(
                "Visible pages $pages did not complete decode; completed=$completedDecodes; text=${texts(
                    fixture,
                )}; events=${fixture.events()}",
            )
        }
    }

    private fun readerActionNodes(
        fixture: MountedReaderPresentationFixture,
        label: String,
        description: Boolean = false,
    ): List<SemanticsNode> = nodes(fixture).filter { node ->
        node.config.contains(SemanticsActions.OnClick) && if (description) {
            node.config.contains(SemanticsProperties.ContentDescription) &&
                node.config[SemanticsProperties.ContentDescription].contains(label)
        } else {
            textsFrom(node).contains(label)
        }
    }

    private fun readerActionSummary(fixture: MountedReaderPresentationFixture): String {
        val state = mihon.desktop.test.state.readerState.mountedSnapshot()
        val descriptions = nodes(fixture).asSequence().filter {
            it.config.contains(SemanticsProperties.ContentDescription)
        }.flatMap { it.config[SemanticsProperties.ContentDescription].asSequence() }.take(16).toList()
        return "showSettings=${state?.showSettings} pairing=${state?.pairingLoad} " +
            "text=${texts(fixture).take(24)} descriptions=$descriptions"
    }

    private fun requireReaderAction(
        fixture: MountedReaderPresentationFixture,
        label: String,
        description: Boolean = false,
    ): SemanticsNode {
        val matches = readerActionNodes(fixture, label, description)
        assertEquals(1, matches.size, "Expected exactly one reader action '$label'; ${readerActionSummary(fixture)}")
        return matches.single()
    }

    private fun adjustButton(fixture: MountedReaderPresentationFixture): SemanticsNode =
        nodes(fixture).first { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                node.config.contains(SemanticsProperties.ContentDescription) &&
                node.config[SemanticsProperties.ContentDescription].any { it == "Adjust Spread" }
        }

    private fun texts(fixture: MountedReaderPresentationFixture): List<String> = nodes(fixture).flatMap { node ->
        if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }
    }

    private fun nodes(fixture: MountedReaderPresentationFixture): List<SemanticsNode> =
        fixture.scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun visibleFrames(fixture: MountedReaderPresentationFixture): List<SemanticsNode> = nodes(
        fixture,
    ).filter { node ->
        node.config.contains(DualPageDisplayUnitIdKey) && node.boundsInRoot.contains(Offset(320f, 200f))
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun openDatabase(path: File, create: Boolean = false): DatabaseFixture {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePath}")
        if (create) Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        val handler = JvmDatabaseHandler(
            Database(
                driver,
                historyAdapter = History.Adapter(DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            ),
            driver,
        )
        return DatabaseFixture(handler, driver)
    }

    private suspend fun receivePairing(
        handler: tachiyomi.data.DatabaseHandler,
        event: mihon.domain.sync.SyncEventEnvelope,
    ) {
        val journal = mihon.data.sync.journal.SyncLocalJournal(handler)
        journal.connect(
            "layout-space",
            1,
            mihon.domain.sync.transport.SyncRepository("owner", "sync", "sync"),
            "receiver",
            1,
        )
        val bootstrap = tachiyomi.data.creator.CreatorArchiveLegacyBootstrap(
            tachiyomi.data.creator.CreatorArchiveLegacyBridge(handler),
        )
        val creators = tachiyomi.data.creator.CreatorRepositoryImpl(handler, bootstrap = bootstrap)
        val writer = mihon.data.sync.projection.SyncRemoteProjectionWriter(
            handler,
            creators,
            creators,
            bootstrap,
            { true },
        )
        val batch = mihon.domain.sync.SyncBatch(1, "layout-space", 1, requireNotNull(event.batchId), listOf(event))
        assertTrue(mihon.data.sync.inbox.SyncInboxStore(handler).ingest(batch).accepted)
        assertEquals(1, mihon.data.sync.inbox.SyncInboxProjector(handler, writer).project("layout-space", 1))
        assertTrue(journal.pendingEvents("layout-space", 1).isEmpty())
    }

    private fun seed(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                "chapter_flags, cover_last_modified, date_added) VALUES (10, 42, '/', 'M', 0, 0, 0, 0, 0, 0, 0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, " +
                "chapter_number, source_order, date_fetch, date_upload) " +
                "VALUES (7, 10, '/current', 'C', 0, 0, 5, 1, 0, 0, 0)",
            0,
        )
    }

    private fun queryLong(driver: JdbcSqliteDriver, sql: String) = driver.executeQuery(null, sql, { cursor ->
        app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L)
    }, 0).value

    private data class DatabaseFixture(val handler: JvmDatabaseHandler, val driver: JdbcSqliteDriver) : AutoCloseable {
        override fun close() = handler.close()
    }
}
