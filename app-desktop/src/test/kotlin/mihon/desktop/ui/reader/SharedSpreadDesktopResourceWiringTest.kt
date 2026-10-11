package mihon.desktop.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderPageImageDecoder
import mihon.desktop.reader.DesktopReaderPresentationImageHolder
import mihon.desktop.reader.DesktopReaderRuntimeFactory
import mihon.desktop.reader.DesktopReaderSpreadSampler
import mihon.desktop.reader.NextChapterPrefetchMode
import mihon.desktop.reader.ReaderIoGate
import mihon.desktop.reader.ReaderIoGatePoint
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.SkiaDesktopReaderPageImageDecoder
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.content.ReaderPageContentLease
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.session.ReaderPageLoadState
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.service.SourceManager
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import java.util.zip.ZipFile
import javax.imageio.ImageIO

@OptIn(ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedSpreadDesktopResourceWiringTest {
    @TempDir lateinit var temp: File

    @Test
    fun `replacement runtime waits for cancelled original normal materialization until its actual exit`() =
        runBlocking {
            Fixture(online = true, holdOldBackground = true).use { fixture ->
                fixture.mountReader()
                fixture.pump {
                    fixture.oldBackgroundEntered.isCompleted &&
                        fixture.owner()?.displayedHistory?.any { it.drawnPageIds.isNotEmpty() } == true
                }
                assertTrue(fixture.oldBackgroundEntered.isCompleted)
                assertTrue(fixture.owner()!!.displayedHistory.any { it.drawnPageIds.isNotEmpty() })
                fixture.replaceRuntime()
                fixture.mountReader()
                fixture.pump {
                    fixture.owner()?.displayedHistory?.any { it.drawnPageIds.isNotEmpty() } == true &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            it.loadState is ReaderPageLoadState.Ready
                        }
                }
                assertTrue(fixture.owner()!!.displayedHistory.any { it.drawnPageIds.isNotEmpty() })
                assertTrue(!fixture.oldBackgroundRelease.isCompleted)
                val owner = requireNotNull(fixture.owner())
                fixture.model.setAutoSpreadMatching(true, fixture.prefs)
                fixture.pump { fixture.regionReads.get() > 0 }
                assertEquals(
                    0,
                    fixture.regionReads.get(),
                    "Closed sessions cannot advertise cancelled real work as idle",
                )
                fixture.oldBackgroundRelease.complete(Unit)
                fixture.pump {
                    fixture.regionReads.get() > 0 && owner.spreadSampleDependencies().isEmpty() &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            owner.pageFact(it.id)?.state == ReaderPageFactState.PORTRAIT
                        }
                }
                assertTrue(owner.spreadSampleDependencies().isEmpty())
                assertTrue(
                    fixture.model.state.value.session.activeChapter.pages.all {
                        owner.pageFact(it.id)?.state == ReaderPageFactState.PORTRAIT
                    },
                )
                assertTrue(fixture.owner() === owner)
                assertTrue(fixture.regionReads.get() > 0)
            }
        }

    @Test
    fun `replacement runtime waits for original non cooperative optional Skia work and resumes on actual exit`() =
        runBlocking {
            Fixture(holdRegionNonCooperative = true).use { fixture ->
                fixture.mountReader()
                fixture.pump { fixture.owner()?.displayedHistory?.any { it.drawnPageIds.isNotEmpty() } == true }
                assertTrue(fixture.owner()!!.displayedHistory.any { it.drawnPageIds.isNotEmpty() })
                val release = CompletableDeferred<Unit>()
                fixture.regionGate.set(release)
                fixture.model.setAutoSpreadMatching(true, fixture.prefs)
                fixture.pump { fixture.regionEntered.isCompleted }
                assertTrue(fixture.regionEntered.isCompleted)
                assertEquals(1, fixture.activeRegionReads.get())
                fixture.model.setAutoSpreadMatching(false, fixture.prefs)
                fixture.replaceRuntime()
                fixture.mountReader()
                fixture.pump {
                    fixture.owner()?.displayedHistory?.any { it.drawnPageIds.isNotEmpty() } == true &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            fixture.owner()?.pageFact(it.id)?.state == ReaderPageFactState.PORTRAIT
                        }
                }
                assertTrue(fixture.owner()!!.displayedHistory.any { it.drawnPageIds.isNotEmpty() })
                assertEquals(1, fixture.activeRegionReads.get(), "Closing the old runtime cannot end real native work")
                val newOwner = requireNotNull(fixture.owner())
                fixture.model.setAutoSpreadMatching(true, fixture.prefs)
                fixture.pump { fixture.peakRegionReads.get() > 1 }
                assertEquals(1, fixture.peakRegionReads.get(), "New runtimes share the still occupied native resources")
                assertTrue(newOwner.spreadSampleDependencies().isNotEmpty())
                release.complete(Unit)
                fixture.pump { newOwner.spreadSampleDependencies().isEmpty() }
                assertTrue(newOwner.spreadSampleDependencies().isEmpty())
                assertTrue(fixture.owner() === newOwner)
                assertEquals(1, fixture.peakRegionReads.get())
            }
        }

    @Test
    fun `normal ready neighbor headers contribute before first displayed pixel`() = runBlocking {
        Fixture().use { fixture ->
            fixture.pump {
                fixture.model.state.value.session.activeChapter.pages.size == 5 &&
                    fixture.model.state.value.session.activeChapter.pages.all {
                        it.loadState is ReaderPageLoadState.Ready
                    } &&
                    fixture.owner()?.pageFact(fixture.model.state.value.session.activeChapter.pages[0].id) != null
            }
            val pages = fixture.model.state.value.session.activeChapter.pages
            assertEquals(5, pages.size)
            assertTrue(pages.all { it.loadState is ReaderPageLoadState.Ready })
            assertEquals(null, fixture.runtime.presentationImageOwner.firstPresentedGeneration.value)
            fixture.pump { pages.all { fixture.owner()?.pageFact(it.id)?.state == ReaderPageFactState.PORTRAIT } }
            assertTrue(
                pages.all { fixture.owner()?.pageFact(it.id)?.state == ReaderPageFactState.PORTRAIT },
                "Normal preloaded encoded headers cannot wait for the current image to be displayed",
            )
            assertTrue(fixture.owner()!!.displayedHistory.isEmpty())
            assertEquals(0, fixture.regionReads.get(), "Disabled matching does not decode optional regions")
        }
    }

    @Test
    fun `real foreground decode pauses optional regions and completion resumes existing content only`() = runBlocking {
        Fixture().use { fixture ->
            fixture.mountReader()
            fixture.pump {
                fixture.model.state.value.dualPageUnits.size == 3 &&
                    fixture.model.state.value.dualPageUnits.all { it.certain } &&
                    fixture.runtime.presentationImageOwner.firstPresentedGeneration.value != null
            }
            assertTrue(fixture.model.state.value.dualPageUnits.all { it.certain })
            fixture.scene.setContent {}
            fixture.pump { true }
            fixture.runtime.pageImagePipeline.clear()
            val page = fixture.model.state.value.session.activeChapter.pages[0]
            val gate = CompletableDeferred<Unit>()
            fixture.fullGate.set(gate)
            val pending = fixture.scope.async {
                fixture.runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        ReaderPageContentOpenRequest(
                            page.id,
                            fixture.model.state.value.session.generation,
                            requireNotNull(page.encodedPageRef),
                            page.attemptGeneration,
                            page.encodedPageProvenance,
                        ),
                        PageDecodePurpose.FULL_PAGE,
                        40,
                        60,
                    ),
                )
            }
            fixture.pump { fixture.fullEntered.isCompleted }
            assertTrue(fixture.fullEntered.isCompleted, "The original native decoder is genuinely running")
            fixture.model.setAutoSpreadMatching(true, fixture.prefs)
            fixture.pump { fixture.regionReads.get() > 0 }
            assertEquals(
                0,
                fixture.regionReads.get(),
                "Optional region decoding must not compete with real foreground work",
            )
            assertFalse(pending.isCompleted)
            gate.complete(Unit)
            fixture.pump { pending.isCompleted && fixture.regionReads.get() > 0 }
            pending.await()?.close()
            assertTrue(fixture.regionReads.get() > 0, "Original completion must re-evaluate optional work")
            assertTrue(
                fixture.model.state.value.session.activeChapter.pages.all {
                    it.loadState is ReaderPageLoadState.Ready
                },
            )
        }
    }

    @Test
    fun `reverse preparation uses normal HTTP window without extra earlier requests`() = runBlocking {
        Fixture(pageCount = 24, initialPage = 16, online = true).use { fixture ->
            fixture.mountReader()
            fixture.pump {
                fixture.owner()?.displayedHistory?.any { frame ->
                    frame.drawnPageIds.any { it.sourcePageIndex == 16 }
                } == true
            }
            assertEquals(16, fixture.model.state.value.currentPage)
            assertTrue(
                fixture.owner()!!.displayedHistory.any { it.drawnPageIds.any { page -> page.sourcePageIndex == 16 } },
            )
            assertTrue(fixture.paths.contains(16), "The target is fetched through the original production HTTP route")
            assertTrue(fixture.model.navigateDualPage(false))
            val target = fixture.model.state.value.currentPage
            assertTrue(target < 16)
            fixture.model.setAutoSpreadMatching(true, fixture.prefs)
            fixture.pump {
                fixture.owner()?.displayedHistory?.any { frame ->
                    frame.drawnPageIds.any { it.sourcePageIndex == target }
                } == true &&
                    (fixture.paths.any { it < target - 1 } || fixture.regionReads.get() > 0)
            }
            assertTrue(
                fixture.owner()!!.displayedHistory.any {
                    it.drawnPageIds.any { page -> page.sourcePageIndex == target }
                },
            )
            assertTrue(
                fixture.paths.all { it >= target - 1 },
                "Optional facts cannot extend normal backward-one preload: ${fixture.paths}",
            )
        }
    }

    @Test
    fun `cancelled non cooperative normal materialization stays busy until actual completion`() = runBlocking {
        Fixture(pageCount = 24, online = true, holdOldBackground = true).use { fixture ->
            fixture.mountReader()
            fixture.pump { fixture.oldBackgroundEntered.isCompleted }
            assertTrue(fixture.oldBackgroundEntered.isCompleted)
            fixture.model.goToPage(16)
            fixture.model.setAutoSpreadMatching(true, fixture.prefs)
            fixture.pump {
                fixture.owner()?.displayedHistory?.any { frame ->
                    frame.drawnPageIds.any { it.sourcePageIndex == 16 }
                } == true && fixture.regionReads.get() > 0
            }
            assertTrue(
                fixture.owner()!!.displayedHistory.any { it.drawnPageIds.any { page -> page.sourcePageIndex == 16 } },
                "The current real image still paints while old physical I/O cannot be cancelled",
            )
            assertEquals(
                0,
                fixture.regionReads.get(),
                "Cancellation cannot advertise still-running original work as idle",
            )
            fixture.oldBackgroundRelease.complete(Unit)
            fixture.pump { fixture.regionReads.get() > 0 }
            assertTrue(fixture.regionReads.get() > 0, "Actual old completion resumes the original optional consumer")
        }
    }

    @Test
    fun `real foreground work pauses sampler distinctly from corrupt encoded content`() = runBlocking {
        Fixture().use { fixture ->
            fixture.pump { fixture.model.state.value.session.activeChapter.pages.size == 5 }
            val page = fixture.model.state.value.session.activeChapter.pages[0]
            assertTrue(page.loadState is ReaderPageLoadState.Ready)
            val gate = CompletableDeferred<Unit>()
            fixture.fullGate.set(gate)
            val content = ReaderPageContentOpenRequest(
                page.id,
                fixture.model.state.value.session.generation,
                requireNotNull(page.encodedPageRef),
                page.attemptGeneration,
                page.encodedPageProvenance,
            )
            val pending = fixture.scope.async {
                fixture.runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(content, PageDecodePurpose.FULL_PAGE, 40, 60),
                )
            }
            fixture.pump { fixture.fullEntered.isCompleted }
            assertTrue(fixture.fullEntered.isCompleted)
            fixture.runtime.pageImagePipeline.openRegionSession(content).use { region ->
                assertThrows(CancellationException::class.java) {
                    runBlocking {
                        DesktopReaderSpreadSampler.sample(
                            fixture.runtime.pageImagePipeline,
                            region,
                            40,
                            60,
                            mihon.domain.reader.ReaderImageOrientation.NORMAL,
                            canContinue = { fixture.runtime.pageImagePipeline.foregroundWork.value == 0 },
                        )
                    }
                }
            }
            assertEquals(0, fixture.regionReads.get(), "Pause rejects work before optional allocation and decode")
            gate.complete(Unit)
            pending.await()?.close()
            assertEquals(0, fixture.runtime.pageImagePipeline.foregroundWork.value)
        }
    }

    @Test
    fun `foreground arriving during original edge decode pauses then resumes matching without retry`() = runBlocking {
        Fixture().use { fixture ->
            fixture.mountReader()
            fixture.pump {
                fixture.model.state.value.dualPageUnits.size == 3 &&
                    fixture.model.state.value.dualPageUnits.all { it.certain } &&
                    fixture.runtime.presentationImageOwner.firstPresentedGeneration.value != null
            }
            assertTrue(fixture.model.state.value.dualPageUnits.all { it.certain })
            fixture.scene.setContent {}
            fixture.pump { true }
            fixture.runtime.pageImagePipeline.clear()
            val edgeRelease = CompletableDeferred<Unit>()
            fixture.regionGate.set(edgeRelease)
            fixture.model.setAutoSpreadMatching(true, fixture.prefs)
            fixture.pump { fixture.regionEntered.isCompleted }
            assertTrue(fixture.regionEntered.isCompleted, "An original Skia edge task genuinely started")
            val publisher = ReaderScreenModel::class.java.getDeclaredField("spreadPublisherJob").apply {
                isAccessible = true
            }.get(fixture.model) as kotlinx.coroutines.Job
            val fullRelease = CompletableDeferred<Unit>()
            fixture.fullGate.set(fullRelease)
            val page = fixture.model.state.value.session.activeChapter.pages[0]
            val full = fixture.scope.async {
                fixture.runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        ReaderPageContentOpenRequest(
                            page.id,
                            fixture.model.state.value.session.generation,
                            requireNotNull(page.encodedPageRef),
                            page.attemptGeneration,
                            page.encodedPageProvenance,
                        ),
                        PageDecodePurpose.FULL_PAGE,
                        40,
                        60,
                    ),
                )
            }
            fixture.pump { fixture.fullEntered.isCompleted }
            assertTrue(fixture.fullEntered.isCompleted)
            edgeRelease.complete(Unit)
            fixture.pump { publisher.isCompleted }
            assertTrue(publisher.isCompleted, "Already-running bounded work exits while foreground is busy")
            assertEquals(1, fixture.regionReads.get(), "Busy foreground prevents the second edge")
            fullRelease.complete(Unit)
            fixture.pump {
                fixture.regionReads.get() > 1 && fixture.owner()!!.spreadSampleDependencies().isEmpty()
            }
            full.await()?.close()
            assertTrue(fixture.model.state.value.autoSpreadMatching)
            assertTrue(fixture.owner()!!.spreadSampleDependencies().isEmpty(), "Original sample capabilities completed")
            assertTrue(fixture.regionReads.get() > 1, "Original region decoding resumed")
            assertTrue(
                fixture.model.state.value.dualPageUnits.any {
                    it.pageIds.map { page -> page.sourcePageIndex } == listOf(2, 3)
                },
                "Completed original samples produce the expected quality pair without user Retry",
            )
            assertEquals(0, fixture.model.state.value.currentPage)
        }
    }

    @Test
    fun `actual pinned native lease pauses matching at reduced soft budget and release resumes it`() = runBlocking {
        Fixture().use { fixture ->
            fixture.mountReader()
            fixture.pump {
                fixture.model.state.value.dualPageUnits.size == 3 &&
                    fixture.model.state.value.dualPageUnits.all { it.certain } &&
                    fixture.runtime.presentationImageOwner.firstPresentedGeneration.value != null
            }
            assertTrue(fixture.model.state.value.dualPageUnits.all { it.certain })
            fixture.scene.setContent {}
            fixture.pump { true }
            fixture.runtime.pageImagePipeline.clear()
            val authority = fixture.runtime.pageImagePipeline.memoryAuthority
            authority.javaClass.getDeclaredField("maxBytes").apply { isAccessible = true }.setLong(authority, 128)
            assertEquals(128L, authority.snapshot().maxBytes, "Only the original soft budget is reduced")
            val page = fixture.model.state.value.session.activeChapter.pages[0]
            val lease = requireNotNull(
                fixture.runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        ReaderPageContentOpenRequest(
                            page.id,
                            fixture.model.state.value.session.generation,
                            requireNotNull(page.encodedPageRef),
                            page.attemptGeneration,
                            page.encodedPageProvenance,
                        ),
                        PageDecodePurpose.FULL_PAGE,
                        40,
                        60,
                    ),
                ),
            )
            val owner = requireNotNull(fixture.owner())
            try {
                assertEquals(40, lease.asset.bitmap.width)
                assertEquals(60, lease.asset.bitmap.height)
                assertTrue(authority.snapshot().pinnedBytes > 128)
                assertTrue(authority.snapshot().overBudgetBytes > 0)
                fixture.pump { fixture.runtime.session.foregroundRequestsIdle }
                assertTrue(fixture.runtime.session.foregroundRequestsIdle)
                assertEquals(0, fixture.runtime.pageImagePipeline.foregroundWork.value)
                fixture.model.setAutoSpreadMatching(true, fixture.prefs)
                assertTrue(fixture.owner() === owner)
                assertTrue(owner.identity.pageIds.all { owner.pageFact(it)?.state == ReaderPageFactState.PORTRAIT })
                assertTrue(owner.spreadSampleDependencies().isNotEmpty())
                fixture.pump { fixture.regionReads.get() > 0 }
                assertEquals(
                    0,
                    fixture.regionReads.get(),
                    "Pinned original bytes defer optional decoding under pressure",
                )
            } finally {
                lease.close()
            }
            assertEquals(0L, authority.snapshot().overBudgetBytes)
            fixture.pump { fixture.regionReads.get() > 1 && fixture.owner()!!.spreadSampleDependencies().isEmpty() }
            assertTrue(fixture.owner() === owner)
            assertTrue(owner.identity.pageIds.all { owner.pageFact(it)?.state == ReaderPageFactState.PORTRAIT })
            assertTrue(fixture.model.state.value.autoSpreadMatching)
            assertEquals(0, fixture.model.state.value.currentPage)
            assertTrue(fixture.regionReads.get() > 1, "Original ledger recovery automatically resumes work")
            assertTrue(fixture.owner()!!.spreadSampleDependencies().isEmpty())
        }
    }

    @Test
    fun `idle consumed while original encoded lease closes resumes paused matching at completion`() = runBlocking {
        Fixture().use { fixture ->
            fixture.mountReader()
            fixture.pump {
                fixture.model.state.value.dualPageUnits.size == 3 &&
                    fixture.model.state.value.dualPageUnits.all { it.certain } &&
                    fixture.runtime.presentationImageOwner.firstPresentedGeneration.value != null
            }
            assertTrue(fixture.model.state.value.dualPageUnits.all { it.certain })
            fixture.scene.setContent {}
            fixture.pump { true }
            fixture.runtime.pageImagePipeline.clear()
            val edgeRelease = CompletableDeferred<Unit>()
            fixture.regionGate.set(edgeRelease)
            fixture.model.setAutoSpreadMatching(true, fixture.prefs)
            fixture.pump { fixture.regionEntered.isCompleted }
            assertTrue(fixture.regionEntered.isCompleted)
            val owner = requireNotNull(fixture.owner())
            assertEquals(listOf(0), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
            assertTrue(owner.identity.pageIds.all { owner.pageFact(it)?.state == ReaderPageFactState.PORTRAIT })
            val publisher = ReaderScreenModel::class.java.getDeclaredField("spreadPublisherJob").apply {
                isAccessible = true
            }.get(fixture.model) as Job
            val physicalLease = fixture.originalRegionLease(requireNotNull(fixture.firstRegionContent.get()))
            val releaseField = physicalLease.javaClass.getDeclaredField("release").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val originalRelease = releaseField.get(physicalLease) as () -> Unit
            val closeEntered = CompletableDeferred<Unit>()
            val closeRelease = CountDownLatch(1)
            val forwarded = AtomicInteger()
            releaseField.set(physicalLease, {
                try {
                    closeEntered.complete(Unit)
                    check(closeRelease.await(10, TimeUnit.SECONDS)) { "Only the original encoded close may be held" }
                } finally {
                    forwarded.incrementAndGet()
                    originalRelease()
                }
            })
            val fullRelease = CompletableDeferred<Unit>()
            fixture.fullGate.set(fullRelease)
            val page = fixture.model.state.value.session.activeChapter.pages[0]
            val full = fixture.scope.async {
                fixture.runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        ReaderPageContentOpenRequest(
                            page.id,
                            fixture.model.state.value.session.generation,
                            requireNotNull(page.encodedPageRef),
                            page.attemptGeneration,
                            page.encodedPageProvenance,
                        ),
                        PageDecodePurpose.FULL_PAGE,
                        40,
                        60,
                    ),
                )
            }
            val originalModel = fixture.model
            val modelLock = ReaderScreenModel::class.java.getDeclaredField("productionRuntimeLifecycleLock").apply {
                isAccessible = true
            }.get(originalModel)
            try {
                fixture.pump { fixture.fullEntered.isCompleted }
                assertTrue(fixture.fullEntered.isCompleted)
                edgeRelease.complete(Unit)
                fixture.pump { closeEntered.isCompleted }
                assertTrue(closeEntered.isCompleted, "Original region finally is closing its acquired encoded lease")
                assertFalse(publisher.isCompleted)
                assertEquals(1, fixture.regionReads.get())
                assertEquals(0, forwarded.get())
                val idleConsumed = CompletableDeferred<Unit>()
                synchronized(modelLock) {
                    mockkObject(originalModel, recordPrivateCalls = true)
                    every {
                        originalModel invoke "startSpreadSamplesLocked" withArguments listOf(
                            any<SharedDualPageSession>(),
                            any<mihon.desktop.reader.DesktopReaderRuntime>(),
                            any<CoroutineScope>(),
                            any<kotlinx.coroutines.sync.Semaphore>(),
                        )
                    } answers {
                        callOriginal()
                        if (closeEntered.isCompleted && closeRelease.count == 1L && !publisher.isCompleted &&
                            fixture.runtime.session.foregroundRequestsIdle &&
                            fixture.runtime.pageImagePipeline.foregroundWork.value == 0
                        ) {
                            idleConsumed.complete(Unit)
                        }
                    }
                }
                assertTrue(fixture.model === originalModel)
                fullRelease.complete(Unit)
                fixture.pump {
                    full.isCompleted && fixture.runtime.pageImagePipeline.foregroundWork.value == 0 &&
                        idleConsumed.isCompleted
                }
                assertTrue(full.isCompleted)
                assertEquals(0, fixture.runtime.pageImagePipeline.foregroundWork.value)
                assertTrue(
                    idleConsumed.isCompleted,
                    "The original idle consumer returned while encoded cleanup was held",
                )
                assertFalse(publisher.isCompleted, "The prior publisher is still in original lease cleanup")
                assertEquals(1, fixture.regionReads.get())
                closeRelease.countDown()
                fixture.pump { publisher.isCompleted && owner.spreadSampleDependencies().isEmpty() }
                assertTrue(publisher.isCompleted)
                assertTrue(owner.spreadSampleDependencies().isEmpty(), "Actual completion resumes original sampling")
                assertTrue(fixture.regionReads.get() > 1)
                assertEquals(1, forwarded.get(), "The original coordinator release is forwarded exactly once")
                assertTrue(fixture.owner() === owner)
                assertTrue(owner.identity.pageIds.all { owner.pageFact(it)?.state == ReaderPageFactState.PORTRAIT })
                assertTrue(fixture.model.state.value.autoSpreadMatching)
                assertEquals(0, fixture.model.state.value.currentPage)
            } finally {
                synchronized(modelLock) { unmockkObject(fixture.model) }
                closeRelease.countDown()
                edgeRelease.complete(Unit)
                fullRelease.complete(Unit)
                full.await()?.close()
            }
            fixture.pump { fixture.runtime.pageContentOwner.snapshot().activeLeaseCounts.isEmpty() }
            assertTrue(fixture.runtime.pageContentOwner.snapshot().activeLeaseCounts.isEmpty())
        }
    }

    /** One opt-in development comparison; original B2 payloads, actual production Ready and draw calls. */
    @Test
    @EnabledIfEnvironmentVariable(named = "MIHON_READER_DEVELOPMENT_PERFORMANCE", matches = "true")
    fun `original payload four conditions six reading scenes have no matching dependency wait`() = runBlocking<Unit> {
        val archive = File(requireNotNull(System.getenv("MIHON_READER_ORIGINAL_ARCHIVES")), "udp-real-b2-original.cbz")
        val hash = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("760c81b12dee475288833b29b1f965fa097d4dbafbc36b6cc62f3e2d5a9ac439", hash)
        val encoded = ZipFile(archive).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }.sortedBy { it.name }
                .map { entry -> zip.getInputStream(entry).use { it.readBytes() } }.toList()
        }
        assertEquals(22, encoded.size)
        val scenes = listOf(
            "cold-home",
            "warm-sequential",
            "slow-partner",
            "rapid-keys",
            "cold-jump-reverse",
            "300-pages",
        ).filter { System.getenv("MIHON_READER_PERFORMANCE_SCENE")?.let { selected -> it == selected } ?: true }
        repeat(3) { repeat ->
            for (condition in listOf("off", "on", "suspended", "foreground-pressure")) {
                val donor = if (condition == "suspended") {
                    Fixture(online = true, encodedPages = encoded, holdRegionNonCooperative = true).also {
                        it.regionGate.set(CompletableDeferred())
                        it.mountReader()
                        it.pump { it.runtime.pageImagePipeline.foregroundWork.value == 0 && it.owner() != null }
                        it.model.setAutoSpreadMatching(true, it.prefs)
                        it.pump { it.regionEntered.isCompleted }
                        assertTrue(it.regionEntered.isCompleted, "The original optional decode must really enter")
                    }
                } else {
                    null
                }
                var measuredPipeline: mihon.desktop.reader.DesktopReaderPageImagePipeline? = null
                try {
                    for (sceneName in scenes) {
                        val count = if (sceneName == "300-pages") 300 else 22
                        val initial = when (sceneName) {
                            "slow-partner" -> 3
                            "cold-jump-reverse" -> 19
                            "300-pages" -> 199
                            else -> 0
                        }
                        Fixture(
                            pageCount = count,
                            initialPage = initial,
                            online = true,
                            encodedPages = encoded,
                            heldNetworkPage = 4.takeIf { sceneName == "slow-partner" },
                        ).use { fixture ->
                            measuredPipeline = fixture.runtime.pageImagePipeline
                            fixture.observePerformance(initial)
                            if (condition == "foreground-pressure") {
                                val authority = fixture.runtime.pageImagePipeline.memoryAuthority
                                authority.javaClass.getDeclaredField("maxBytes").apply { isAccessible = true }
                                    .setLong(authority, 128)
                                assertEquals(128L, authority.snapshot().maxBytes)
                            }
                            fixture.model.setAutoSpreadMatching(condition != "off", fixture.prefs)
                            fixture.measurementStart = System.nanoTime()
                            fixture.mountReader()
                            fixture.performancePaint(initial)
                            when (sceneName) {
                                "warm-sequential" -> {
                                    fixture.markInput()
                                    fixture.input(true)
                                    fixture.performancePaint(fixture.model.state.value.currentPage)
                                }
                                "slow-partner" -> {
                                    assertTrue(!fixture.networkRelease.get())
                                    fixture.networkRelease.set(true)
                                    fixture.performancePaint(4)
                                }
                                "rapid-keys" -> {
                                    fixture.markInput()
                                    repeat(3) { fixture.input(true) }
                                    fixture.performancePaint(fixture.model.state.value.currentPage)
                                }
                                "cold-jump-reverse" -> {
                                    fixture.markInput()
                                    fixture.input(false)
                                    fixture.performancePaint(fixture.model.state.value.currentPage)
                                }
                            }
                            fixture.printPerformance(repeat, condition, sceneName, initial)
                            if (donor != null) {
                                assertTrue(!donor.regionGate.get()!!.isCompleted)
                                assertEquals(1, fixture.runtime.pageImagePipeline.optionalWork.value)
                            }
                        }
                    }
                } finally {
                    donor?.regionGate?.get()?.complete(Unit)
                    donor?.close()
                }
                val until = System.nanoTime() + 5_000_000_000L
                while (requireNotNull(measuredPipeline).optionalWork.value > 0 &&
                    System.nanoTime() < until
                ) {
                    delay(5)
                }
                assertEquals(0, requireNotNull(measuredPipeline).optionalWork.value)
            }
        }
    }

    private inner class Fixture(
        pageCount: Int = 5,
        initialPage: Int = 0,
        online: Boolean = false,
        holdOldBackground: Boolean = false,
        holdRegionNonCooperative: Boolean = false,
        private val encodedPages: List<ByteArray>? = null,
        private val heldNetworkPage: Int? = null,
    ) : AutoCloseable {
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val uiScope = CoroutineScope(SupervisorJob() + dispatcher)
        val legacy = Preferences.userRoot().node("/mihon/spread-resource/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            readingMode = ReadingMode.RTL
            isDualPage = true
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val acceptedReady = ConcurrentHashMap<Int, Long>()
        val decoderReady = ConcurrentHashMap<Int, Long>()
        val actualPaint = ConcurrentHashMap<Int, Long>()
        val paintFrames = ConcurrentHashMap<Int, mihon.domain.reader.ReaderDualPageFrameId>()
        val paintSlots = ConcurrentHashMap<Int, List<Int>>()
        val readyIdentities = ConcurrentHashMap<Int, String>()
        val readyFrames = ConcurrentHashMap<Int, Int>()
        val paintedFrames = ConcurrentHashMap<Int, Int>()
        val frameNumber = AtomicInteger()
        val networkBytes = AtomicLong()
        val networkRelease = AtomicBoolean(false)
        val frameCosts = mutableListOf<Long>()
        val frameIntervals = mutableListOf<Long>()
        var previousRender = 0L
        var observedOverBudget = 0L
        var measurementStart = 0L
        var inputStarted = 0L
        var firstInputPaint = 0L
        var observedResident = 0L
        var observedPinned = 0L
        var observedHeap = 0L
        private var performanceObserved = false
        private val diagnostic = System.getenv("MIHON_READER_PERFORMANCE_DIAGNOSTIC") == "true"
        private val performanceEvents = CopyOnWriteArrayList<String>()

        private fun diagnose(label: String) {
            if (diagnostic) performanceEvents += "${System.nanoTime()}/render${frameNumber.get()}:$label"
        }
        private fun payload(index: Int) = encodedPages?.let { it[index % it.size] } ?: image(index)
        val regionReads = AtomicInteger()
        val activeRegionReads = AtomicInteger()
        val peakRegionReads = AtomicInteger()
        val firstRegionContent = AtomicReference<ReaderPageContentOpenRequest?>(null)
        val regionGate = AtomicReference<CompletableDeferred<Unit>?>(null)
        val regionEntered = CompletableDeferred<Unit>()
        val firstRegionGate = AtomicBoolean(true)
        val fullGate = AtomicReference<CompletableDeferred<Unit>?>(null)
        val fullEntered = CompletableDeferred<Unit>()
        val oldBackgroundEntered = CompletableDeferred<Unit>()
        val oldBackgroundRelease = CompletableDeferred<Unit>()
        val gateOldBackground = AtomicBoolean(holdOldBackground)
        val native = SkiaDesktopReaderPageImageDecoder()
        val decoder = DesktopReaderPageImageDecoder { bytes, key ->
            diagnose("decoderEnter:${key.pageIndex}:${key.purpose}")
            if (key.purpose == PageDecodePurpose.REGION_TILE) {
                firstRegionContent.compareAndSet(null, key.contentKey)
                regionReads.incrementAndGet()
                val active = activeRegionReads.incrementAndGet()
                peakRegionReads.accumulateAndGet(active, ::maxOf)
                try {
                    regionGate.get()?.takeIf { firstRegionGate.compareAndSet(true, false) }?.let {
                        regionEntered.complete(Unit)
                        if (holdRegionNonCooperative) withContext(NonCancellable) { it.await() } else it.await()
                    }
                    return@DesktopReaderPageImageDecoder native.decode(bytes, key).also {
                        diagnose("decoderReturn:${key.pageIndex}:${key.purpose}")
                    }
                } finally {
                    activeRegionReads.decrementAndGet()
                }
            }
            if (key.purpose == PageDecodePurpose.FULL_PAGE) {
                fullGate.get()?.let {
                    fullEntered.complete(Unit)
                    it.await()
                }
            }
            native.decode(bytes, key).also {
                decoderReady[key.pageIndex] = System.nanoTime()
                diagnose("decoderReturn:${key.pageIndex}:${key.purpose}")
            }
        }
        val original = temp.resolve("pages-${System.nanoTime()}").apply { mkdirs() }.also { directory ->
            if (!online) repeat(pageCount) { index -> directory.resolve("$index.png").writeBytes(payload(index)) }
        }
        val paths = CopyOnWriteArrayList<Int>()
        val server = if (online) {
            mockwebserver3.MockWebServer().apply {
                this.dispatcher = object : mockwebserver3.Dispatcher() {
                    override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                        val index = request.url.encodedPath.substringAfterLast('/').toInt()
                        paths += index
                        diagnose("normalHttp:$index")
                        if (index == heldNetworkPage) {
                            val until = System.nanoTime() + 30_000_000_000L
                            while (!networkRelease.get() && System.nanoTime() < until) Thread.sleep(2)
                        }
                        val body = payload(index)
                        networkBytes.addAndGet(body.size.toLong())
                        return mockwebserver3.MockResponse.Builder().addHeader(
                            "Content-Type",
                            if (encodedPages == null) "image/png" else "image/jpeg",
                        ).body(Buffer().write(body)).build()
                    }
                }
                start()
            }
        } else {
            null
        }
        val factory = DesktopReaderRuntimeFactory(
            prefs,
            DesktopDownloadProvider(temp.resolve("downloads")),
            if (server == null) {
                mockk<SourceManager>(relaxed = true)
            } else {
                FakeDesktopSourceManager(listOf(ReadyWindowSource(pageCount) { server.url("/page/$it").toString() }))
            },
            NetworkHelper(OkHttpClient()),
            mockk<ReaderProgressTracker>(relaxed = true),
            null,
            temp.resolve("cache-${System.nanoTime()}"),
            pageImageDecoder = decoder,
            readerIoGate = ReaderIoGate { point ->
                if (point == ReaderIoGatePoint.NON_CURRENT_PAGE && gateOldBackground.compareAndSet(true, false)) {
                    withContext(NonCancellable) {
                        oldBackgroundEntered.complete(Unit)
                        oldBackgroundRelease.await()
                    }
                }
            },
        )
        private val context = DesktopReaderChapterContext(
            87, 42, if (online) "/chapter" else original.absolutePath, "M", "C", 1.0, 0, initialPage, false,
            localChapterPath = original.absolutePath.takeUnless { online },
        )
        var runtime = factory.createRuntime(context, scope)
        var model = factory.createModel(runtime, false, 0, null)
        private var collector = scope.launch { runtime.session.state.collect { model.acceptSessionState(it) } }
        val scene = ImageComposeScene(700, 1200, coroutineContext = uiScope.coroutineContext) {}

        fun observePerformance(target: Int) {
            performanceObserved = true
            val original = runtime.presentationImageOwner
            mockkObject(original)
            every { original.acceptReadyAsset(any(), any(), any()) } answers {
                val accepted = callOriginal()
                if (accepted) {
                    val holder = firstArg<DesktopReaderPresentationImageHolder>()
                    val source = holder.identity.pageId.sourcePageIndex
                    acceptedReady.putIfAbsent(source, System.nanoTime())
                    readyIdentities[source] = holder.identity.toString()
                    readyFrames.putIfAbsent(source, frameNumber.get())
                    diagnose("readyAccepted:$source")
                }
                accepted
            }
            mockkObject(model)
            if (diagnostic) {
                every { model.prepareDecodedDualViewportPage(any(), any(), any(), any()) } answers {
                    val ticket = firstArg<mihon.domain.reader.ReaderDualPageViewportTicket>()
                    val page = secondArg<mihon.domain.reader.session.ReaderPageSession>()
                    val revision = owner()?.layoutRevision
                    val accepted = callOriginal()
                    if (page.id.sourcePageIndex == target) {
                        diagnose(
                            "prepare:$target:$accepted:revision$revision->${owner()?.layoutRevision}:" +
                                "frame${ticket.frameId}:unit${owner()?.frame(ticket)?.unit}",
                        )
                    }
                    accepted
                }
            }
            every { model.drawDualViewportPage(any(), any()) } answers {
                val accepted = callOriginal()
                if (accepted) {
                    val index = secondArg<mihon.domain.reader.session.ReaderPageId>().sourcePageIndex
                    val now = System.nanoTime()
                    actualPaint.putIfAbsent(index, now)
                    diagnose("actualPaint:$index")
                    paintedFrames.putIfAbsent(index, frameNumber.get())
                    val ticket = firstArg<mihon.domain.reader.ReaderDualPageViewportTicket>()
                    paintFrames.putIfAbsent(index, ticket.frameId)
                    owner()?.frame(ticket)?.let { frame ->
                        paintSlots[index] = frame.unit.slots.map { it.pageId.sourcePageIndex }
                    }
                    if (inputStarted > 0 && now >= inputStarted && index == model.state.value.currentPage) {
                        if (firstInputPaint == 0L) firstInputPaint = now
                    }
                }
                accepted
            }
        }

        fun markInput() {
            inputStarted = System.nanoTime()
            firstInputPaint = 0
        }

        fun input(forward: Boolean) {
            val key = if (forward) {
                androidx.compose.ui.input.key.Key.DirectionLeft
            } else {
                androidx.compose.ui.input.key.Key.DirectionRight
            }
            val type = Class.forName("androidx.compose.ui.input.key.KeyEventType")
                .getMethod("access\$getKeyDown\$cp").invoke(null)
            val factory = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").declaredMethods.single {
                it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default")
            }
            val event = factory.invoke(null, key.keyCode, type, 0, false, false, false, false, null)
            assertTrue(
                handleReaderKeyEvent(
                    androidx.compose.ui.input.key.KeyEvent(event),
                    model.state.value,
                    model,
                    mockk(),
                    null,
                    {},
                    {},
                ),
            )
            dispatcher.scheduler.runCurrent()
        }

        suspend fun performancePaint(source: Int) {
            val deadline = System.nanoTime() + 15_000_000_000L
            var pixels = false
            do {
                dispatcher.scheduler.runCurrent()
                val start = System.nanoTime()
                frameNumber.incrementAndGet()
                diagnose("renderStart:layout${owner()?.layoutRevision}")
                if (previousRender > 0L) frameIntervals += start - previousRender
                previousRender = start
                val bitmap = scene.render(start).toComposeImageBitmap().asSkiaBitmap()
                frameCosts += System.nanoTime() - start
                try {
                    val colors = (1..5).flatMap { y ->
                        (1..5).map { x ->
                            bitmap.getColor(bitmap.width * x / 6, bitmap.height * y / 6)
                        }
                    }
                    pixels = (
                        listOf(0, 8, 16).maxOf { shift ->
                            colors.maxOf { (it ushr shift) and 255 } - colors.minOf { (it ushr shift) and 255 }
                        } > 70
                        ) && actualPaint.containsKey(source)
                } finally {
                    bitmap.close()
                }
                val memory = runtime.pageImagePipeline.memoryAuthority.snapshot()
                observedResident = maxOf(observedResident, memory.residentBytes)
                observedPinned = maxOf(observedPinned, memory.pinnedBytes)
                observedOverBudget = maxOf(observedOverBudget, memory.overBudgetBytes)
                val java = Runtime.getRuntime()
                observedHeap = maxOf(observedHeap, java.totalMemory() - java.freeMemory())
                dispatcher.scheduler.advanceTimeBy(16)
                if (!pixels) delay(1)
            } while (!pixels && System.nanoTime() < deadline)
            assertTrue(pixels, "Original source $source must be actually drawn and nonuniform in this real scene")
            assertTrue(acceptedReady.containsKey(source), "The original holder must accept its drawable asset")
        }

        fun printPerformance(repeat: Int, condition: String, sceneName: String, target: Int) {
            val painted = requireNotNull(actualPaint[target])
            val ready = requireNotNull(acceptedReady[target])
            val frame = requireNotNull(paintFrames[target])
            val second = paintSlots[target].orEmpty().firstOrNull {
                it != target && paintFrames[it] == frame && actualPaint.containsKey(it)
            }
            if (condition == "foreground-pressure") assertTrue(observedOverBudget > 0)
            if (condition == "suspended") assertEquals(1, runtime.pageImagePipeline.optionalWork.value)
            val fields = linkedMapOf(
                "platform" to "desktop", "repeat" to repeat, "condition" to condition, "scene" to sceneName,
                "source" to target, "identity" to readyIdentities[target], "frame" to frame,
                "decoderNs" to decoderReady[target], "readyNs" to ready, "paintNs" to painted,
                "readyToPaintNs" to (painted - ready), "readyRender" to readyFrames[target],
                "paintRender" to paintedFrames[target], "secondSource" to second,
                "secondReadyNs" to second?.let { acceptedReady[it] },
                "secondPaintNs" to second?.let { actualPaint[it] },
                "inputNs" to inputStarted, "inputPaintNs" to firstInputPaint,
                "renderCostNs" to frameCosts.toList(), "renderIntervalNs" to frameIntervals.toList(),
                "requests" to paths.size, "requestIndices" to paths.toList(), "payloadBytes" to networkBytes.get(),
                "inputTarget" to model.state.value.currentPage, "diagnosticEvents" to performanceEvents.toList(),
                "residentPeak" to observedResident, "pinnedPeak" to observedPinned, "javaHeapPeak" to observedHeap,
                "optionalDecodePeak" to peakRegionReads.get(), "overBudgetObservedPeak" to observedOverBudget,
                "optionalWorkAtMeasurement" to runtime.pageImagePipeline.optionalWork.value,
                "deps" to owner()?.spreadSampleDependencies()?.size,
                "pageCount" to model.state.value.session.activeChapter.pages.size,
            )
            println("READER_PERFORMANCE " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" })
            assertTrue(painted >= ready)
            if (inputStarted > 0) assertTrue(firstInputPaint >= inputStarted)
        }

        fun replaceRuntime() {
            scene.setContent {}
            collector.cancel()
            model.onDispose()
            runtime.close()
            runtime = factory.createRuntime(context, scope)
            model = factory.createModel(runtime, false, 0, null)
            collector = scope.launch { runtime.session.state.collect { model.acceptSessionState(it) } }
        }

        fun owner(): SharedDualPageSession? = ReaderScreenModel::class.java.getDeclaredField("dualSession").apply {
            isAccessible = true
        }.get(model) as SharedDualPageSession?

        @Suppress("UNCHECKED_CAST")
        fun originalRegionLease(content: ReaderPageContentOpenRequest): ReaderPageContentLease<ByteArray> {
            val pipeline = runtime.pageImagePipeline
            val lock = pipeline.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(pipeline)
            val shared = synchronized(lock) {
                val sessions = pipeline.javaClass.getDeclaredField("contentSessions").apply {
                    isAccessible = true
                }.get(pipeline) as Map<*, *>
                val entry = requireNotNull(sessions[content])
                entry.javaClass.getDeclaredField("content").apply { isAccessible = true }.get(entry)
            }
            val completed = shared.javaClass.getDeclaredField("content").apply { isAccessible = true }.get(shared)
                as Deferred<ReaderPageContentLease<ByteArray>?>
            return requireNotNull(completed.getCompleted())
        }

        fun mountReader() {
            val appPreferences = DesktopAppPreferences(InMemoryPreferenceStore())
            val dependencies =
                mockk<DesktopUiDependencies> { every { this@mockk.appPreferences } returns appPreferences }
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    MaterialTheme {
                        val state by model.state.collectAsState()
                        ReaderContent(
                            state, model, uiScope, "M", "C", runtime.presentationImageOwner,
                            readerNav = null, onPrevChapter = {}, onNextChapter = {},
                        )
                    }
                }
            }
        }

        suspend fun pump(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 3_000_000_000L
            do {
                dispatcher.scheduler.runCurrent()
                scene.render(dispatcher.scheduler.currentTime * 1_000_000L).close()
                dispatcher.scheduler.advanceTimeBy(16)
                delay(5)
            } while (!condition() && System.nanoTime() < deadline)
        }

        override fun close() {
            networkRelease.set(true)
            if (performanceObserved) {
                unmockkObject(model)
                unmockkObject(runtime.presentationImageOwner)
            }
            fullGate.get()?.complete(Unit)
            regionGate.get()?.complete(Unit)
            oldBackgroundRelease.complete(Unit)
            scene.close()
            collector.cancel()
            model.onDispose()
            runtime.close()
            scope.cancel()
            uiScope.cancel()
            server?.close()
            legacy.removeNode()
        }
    }

    private class ReadyWindowSource(private val count: Int, private val imageUrl: (Int) -> String) : CatalogueSource {
        override val id = 42L
        override val name = "normal-window"
        override val lang = "en"
        override val supportsLatest = false

        @Suppress("unused")
        fun getClient() = OkHttpClient()
        override suspend fun getPageList(chapter: SChapter) = List(count) { Page(it, imageUrl = imageUrl(it)) }
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
            MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
    }

    private fun image(index: Int): ByteArray {
        val image = BufferedImage(40, 60, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 60) {
            for (x in 0 until 40) {
                val channel = if ((x == 2 && index == 2) || (x == 37 && index == 3)) 80 + y * 2 else index * 35
                image.setRGB(x, y, (255 shl 24) or (channel shl 16) or (80 shl 8) or 120)
            }
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
