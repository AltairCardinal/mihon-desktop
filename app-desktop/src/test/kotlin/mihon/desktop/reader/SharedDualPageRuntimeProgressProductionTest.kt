package mihon.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.ui.reader.ReaderPagerNativePresentationKey
import mihon.desktop.ui.reader.ReaderPagerPresentationBinding
import mihon.desktop.ui.reader.ReaderScreenModel
import mihon.domain.reader.ReaderAnalysisFailure
import mihon.domain.reader.ReaderDualPageFactLimits
import mihon.domain.reader.ReaderDualPageViewportTicket
import mihon.domain.reader.ReaderPageAnalysisEvidence
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.ReaderPairingCertification
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.reader.SqlDelightReadingProgressRepository
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Native paint -> factory model -> real reader session -> real tracker -> SQL transaction. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SharedDualPageRuntimeProgressProductionTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `real EXIF header READY and original producer share normalized geometry`() = runTest {
        val raw = requireNotNull(javaClass.classLoader.getResourceAsStream("reader/exif-corners-raw.jpg"))
            .use { it.readBytes() }
        val exif = "45786966000049492a0008000000010012010300010000000600000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val encoded = raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) + exif +
            raw.copyOfRange(2, raw.size)
        val fixture = MountedReaderPresentationFixture(
            tempDir,
            currentCoroutineContext(),
            MountedReaderPresentationCase(MountedReaderPresentationMode.DUAL, MountedReaderContentRoute.ONLINE),
            heldResponsePage = -1,
            encodedPageBytes = encoded,
        )
        fun pumpUntil(done: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                fixture.scene.render().close()
                runCurrent()
                if (done()) return
                Thread.sleep(5)
            } while (System.nanoTime() < deadline)
            assertTrue(done(), "Real EXIF input must reach normal preparation")
        }
        var independent: ReaderScreenModel? = null
        try {
            fixture.releaseBackgroundGates()
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            var binding: ReaderPagerPresentationBinding? = null
            pumpUntil {
                val native = fixture.scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                    .firstOrNull { it.config.contains(ReaderPagerNativePresentationKey) }
                    ?.config?.get(ReaderPagerNativePresentationKey)
                if (native != null) {
                    binding = native.javaClass.getDeclaredField("binding")
                        .run {
                            isAccessible = true
                            get(native) as ReaderPagerPresentationBinding
                        }
                }
                binding?.model?.state?.value?.session?.activeChapter?.pages?.getOrNull(1)
                    ?.loadState is ReaderPageLoadState.Ready
            }
            val model = requireNotNull(binding).model
            val runtime = requireNotNull(model.runtime)
            fun owner(of: ReaderScreenModel) = of.javaClass.getDeclaredField("dualSession")
                .run {
                    isAccessible = true
                    get(of) as SharedDualPageSession
                }
            val id = runtime.session.core.snapshot.activeChapter.pages[1].id
            pumpUntil { owner(model).pageFact(id)?.width?.let { it > 0 } == true }
            assertEquals(60, owner(model).pageFact(id)?.width, "Original headers use EXIF-normalized axes")
            assertEquals(40, owner(model).pageFact(id)?.height)
            pumpUntil { owner(model).originalAnalysis(id) is ReaderPageAnalysisEvidence.Computed }
            val readyFact = (owner(model).originalAnalysis(id) as ReaderPageAnalysisEvidence.Computed).fact
            assertEquals(ReaderPageFactState.SPREAD, readyFact.state)
            assertEquals(60, readyFact.width)
            val isolated = ReaderScreenModel(
                initialSessionState = runtime.session.state.value,
                prefs = runtime.prefs,
                runtime = runtime,
            ).also { independent = it }
            isolated.setDualPageMode(true)
            isolated.setAutoSpreadMatching(true)
            isolated.javaClass.declaredMethods.single { it.name == "startSpreadSamplesLocked" }.run {
                isAccessible = true
                invoke(isolated, owner(isolated), runtime, CoroutineScope(currentCoroutineContext()), Semaphore(1))
            }
            pumpUntil { owner(isolated).originalAnalysis(id) is ReaderPageAnalysisEvidence.Computed }
            val fact = (owner(isolated).originalAnalysis(id) as ReaderPageAnalysisEvidence.Computed).fact
            assertEquals(60, fact.width)
            assertEquals(40, fact.height)
            assertEquals(ReaderPageFactState.SPREAD, fact.state)
        } finally {
            independent?.onDispose()
            fixture.close()
        }
    }

    @Test
    fun `READY consumption cannot abandon another admitted original page`() = runTest {
        val fixture = MountedReaderPresentationFixture(
            tempDir,
            currentCoroutineContext(),
            MountedReaderPresentationCase(
                MountedReaderPresentationMode.DUAL,
                MountedReaderContentRoute.ONLINE,
            ),
            heldResponsePage = -1,
        )
        fun pumpUntil(done: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                fixture.scene.render().close()
                runCurrent()
                if (done()) return
                Thread.sleep(5)
            } while (System.nanoTime() < deadline)
            assertTrue(done()) {
                fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children
                    .flatMap(::flatten)
                val native = fixture.scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                    .first { it.config.contains(ReaderPagerNativePresentationKey) }
                    .config[ReaderPagerNativePresentationKey]
                val binding = native.javaClass.getDeclaredField("binding").run {
                    isAccessible = true
                    get(native) as ReaderPagerPresentationBinding
                }
                val owner = binding.model.javaClass.getDeclaredField("dualSession").run {
                    isAccessible = true
                    get(binding.model) as SharedDualPageSession
                }
                "Controlled publication window unavailable: ${native.describe()}; proof=${owner.certifyPairing()}; " +
                    "facts=${owner.identity.pageIds.map { it to owner.pageFact(it) }}; " +
                    "runtime=${binding.model.runtime!!.session.state.value.snapshot.activeChapter.pages}"
            }
        }
        var model: ReaderScreenModel? = null
        val serial = Semaphore(1)
        try {
            fixture.releaseBackgroundGates()
            pumpUntil {
                fixture.visiblePagesAtViewportCenter() == setOf(1, 2) && fixture
                    .renderContainsFixturePixels()
            }
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children
                .flatMap(::flatten)
            val native = fixture.scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                .first { it.config.contains(ReaderPagerNativePresentationKey) }
                .config[ReaderPagerNativePresentationKey]
            val binding = native.javaClass.getDeclaredField("binding").run {
                isAccessible = true
                get(native) as ReaderPagerPresentationBinding
            }
            val runtime = requireNotNull(binding.model.runtime)
            pumpUntil {
                runtime.session.state.value.snapshot.activeChapter.pages.all {
                    it
                        .loadState is ReaderPageLoadState.Ready
                }
            }
            val source = runtime.session.state.value
            val independent = ReaderScreenModel(
                initialSessionState = source,
                prefs = runtime.prefs,
                runtime = runtime,
            ).also { model = it }
            independent.setDualPageMode(true)
            independent.setAutoSpreadMatching(true)
            for (page in source.snapshot.activeChapter.pages) {
                val content = ReaderPageContentOpenRequest(
                    page.id,
                    source.snapshot.generation,
                    requireNotNull(page.encodedPageRef),
                    page.attemptGeneration,
                    page
                        .encodedPageProvenance,
                )
                runtime.pageContentOwner.acquireHeader(content)!!.use { lease ->
                    val size = requireNotNull(SkiaImageDecoder.peekSize(lease.content))
                    assertTrue(
                        independent.acceptDualFact(
                            requireNotNull(
                                independent
                                    .captureDualFact(page.id),
                            ),
                            ReaderPageFact.dimensions(
                                size.first,
                                size
                                    .second,
                            ),
                        ),
                    )
                }
            }
            val owner = independent.javaClass.getDeclaredField("dualSession").run {
                isAccessible = true
                get(independent) as SharedDualPageSession
            }
            val location = owner.location
            val budget = owner.factBudget
            serial.acquire()
            independent.javaClass.declaredMethods.single { it.name == "startSpreadSamplesLocked" }
                .run {
                    isAccessible = true
                    invoke(
                        independent,
                        owner,
                        runtime,
                        CoroutineScope(currentCoroutineContext()),
                        serial,
                    )
                }
            runCurrent()
            assertEquals(0, serial.availablePermits)
            val job = independent.javaClass.getDeclaredField("spreadPublisherJob").run {
                isAccessible = true
                get(independent) as Job
            }
            assertTrue(job.isActive)
            val holders = runtime.presentationImageOwner.javaClass.getDeclaredField("holders").run {
                isAccessible = true
                @Suppress("UNCHECKED_CAST")
                get(runtime.presentationImageOwner) as Set<DesktopReaderPresentationImageHolder>
            }
            val holder = holders.first {
                it.identity.pageId.sourcePageIndex == 1 && it.state
                    .value is DesktopReaderPresentationImageState.Ready
            }
            assertTrue(
                independent.acceptDecodedOriginal(
                    source.snapshot.activeChapter.pages[1],
                    holder,
                ),
            )
            assertTrue(
                owner.originalAnalysis(
                    owner.identity
                        .pageIds[1],
                ) is ReaderPageAnalysisEvidence.Computed,
            )
            serial.release()
            pumpUntil { job.isCompleted }
            val active = budget.javaClass.getDeclaredField("active").run {
                isAccessible = true
                get(budget) as Set<*>
            }
            assertTrue(
                active.isEmpty(),
                "Every admitted capability must leave the same location's budget: $active",
            )
            assertTrue(
                owner.originalAnalysis(owner.identity.pageIds[2]) is ReaderPageAnalysisEvidence
                    .Computed,
                "Second admitted page must finish after READY consumes first request: ${owner.certifyPairing()}",
            )
            assertTrue(owner.certifyPairing() is ReaderPairingCertification.Certified)
            assertSame(budget, owner.factBudget)
            assertEquals(location, owner.location)
            assertEquals(1, serial.availablePermits)
        } finally {
            model?.onDispose()
            fixture.close()
        }
    }

    @Test
    fun `real core replacement rejects original batch before delayed UI binding`() =
        verifyCoreReplacement(true, 2)

    @Test
    fun `old normal header cannot certify auto off before delayed UI binding`() =
        verifyCoreReplacement(false, 1)

    @Test
    fun `original normal decode expires with its existing location budget and recovers by retry`() =
        verifyCoreReplacement(true, 2, expires = true)

    private fun verifyCoreReplacement(
        automaticMatching: Boolean,
        heldPage: Int,
        expires: Boolean =
            false,
    ) = runTest {
        val secondEntered = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val armed = AtomicBoolean(false)
        val delegate = SkiaDesktopReaderPageImageDecoder()
        val fixture = MountedReaderPresentationFixture(
            tempDir,
            currentCoroutineContext(),
            MountedReaderPresentationCase(
                MountedReaderPresentationMode.DUAL,
                MountedReaderContentRoute.ONLINE,
            ),
            pageImageDecoder = DesktopReaderPageImageDecoder { encoded, key ->
                if (armed.get() && key.pageIndex == heldPage) {
                    secondEntered.complete(Unit)
                    releaseSecond.await()
                }
                delegate.decode(encoded, key)
            },
            heldResponsePage = -1,
        )
        fun pumpUntil(done: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                fixture.scene.render().close()
                runCurrent()
                if (done()) return
                Thread.sleep(5)
            } while (System.nanoTime() < deadline)
            if (!done()) {
                fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children
                    .flatMap(::flatten)
                val native = fixture.scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                    .first { it.config.contains(ReaderPagerNativePresentationKey) }
                    .config[ReaderPagerNativePresentationKey]
                throw AssertionError("Physical-content window unavailable: ${native.describe()}")
            }
        }
        var independent: ReaderScreenModel? = null
        try {
            fixture.releaseBackgroundGates()
            pumpUntil {
                fixture.visiblePagesAtViewportCenter() == setOf(1, 2) && fixture
                    .renderContainsFixturePixels()
            }
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children
                .flatMap(::flatten)
            val native = fixture.scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                .first { it.config.contains(ReaderPagerNativePresentationKey) }
                .config[ReaderPagerNativePresentationKey]
            val binding = native.javaClass.getDeclaredField("binding").run {
                isAccessible = true
                get(native) as ReaderPagerPresentationBinding
            }
            val runtime = requireNotNull(binding.model.runtime)
            pumpUntil {
                runtime.session.state.value.snapshot.activeChapter.pages.all {
                    it
                        .loadState is ReaderPageLoadState.Ready
                }
            }
            val initial = runtime.session.state.value
            // This real model deliberately receives its next ordinary state binding only after publication.
            val model = ReaderScreenModel(
                initialSessionState = initial,
                prefs = runtime.prefs,
                runtime = runtime,
            )
                .also { independent = it }
            model.setDualPageMode(true)
            model.setAutoSpreadMatching(automaticMatching)
            for (page in initial.snapshot.activeChapter.pages) {
                if (!automaticMatching && page.id.sourcePageIndex == 1) continue
                val content = ReaderPageContentOpenRequest(
                    page.id,
                    initial.snapshot.generation,
                    requireNotNull(page.encodedPageRef),
                    page.attemptGeneration,
                    page.encodedPageProvenance,
                )
                runtime.pageContentOwner.acquireHeader(content)!!.use { lease ->
                    val size = requireNotNull(SkiaImageDecoder.peekSize(lease.content))
                    assertTrue(
                        model.acceptDualFact(
                            requireNotNull(model.captureDualFact(page.id)),
                            ReaderPageFact.dimensions(size.first, size.second),
                        ),
                    )
                }
            }
            fun owner() = model.javaClass.getDeclaredField("dualSession").run {
                isAccessible = true
                get(model) as SharedDualPageSession
            }
            val producerScope = CoroutineScope(currentCoroutineContext())
            fun start() {
                model.javaClass.declaredMethods.single { it.name == "startSpreadSamplesLocked" }
                    .run {
                        isAccessible = true
                        invoke(model, owner(), runtime, producerScope, Semaphore(1))
                    }
            }
            val oldOwner = owner()
            val location = oldOwner.location
            val ids = oldOwner.identity.pageIds
            runtime.pageImagePipeline.clear()
            armed.set(true)
            if (expires) {
                // Advance no navigation or budget: this is the same existing budget, near its deadline.
                oldOwner.factBudget.reserve(
                    emptyList(),
                    System
                        .nanoTime() / 1_000_000 - ReaderDualPageFactLimits.MAX_WAIT_MILLIS + 250,
                )
            }
            val delayedHeader = if (automaticMatching) {
                null
            } else {
                val page = initial.snapshot.activeChapter.pages[1]
                val physical = ReaderPageContentOpenRequest(
                    page.id,
                    initial.snapshot.generation,
                    requireNotNull(page.encodedPageRef),
                    page.attemptGeneration,
                    page.encodedPageProvenance,
                )
                val request = requireNotNull(model.captureDualFact(page.id))
                val fact = runtime.pageContentOwner.acquireHeader(physical)!!.use { lease ->
                    val size = requireNotNull(SkiaImageDecoder.peekSize(lease.content))
                    ReaderPageFact.dimensions(size.first, size.second)
                }
                Triple(request, physical, fact)
            }
            if (automaticMatching) {
                start()
                pumpUntil { secondEntered.isCompleted }
            }
            assertTrue(oldOwner.originalAnalysis(ids[1]) !is ReaderPageAnalysisEvidence.Computed)
            if (expires) {
                val job = model.javaClass.getDeclaredField("spreadPublisherJob").run {
                    isAccessible = true
                    get(model) as Job
                }
                pumpUntil { job.isCompleted }
                val blocked = oldOwner.certifyPairing() as? ReaderPairingCertification
                    .RetryableBlocked
                assertEquals(ReaderAnalysisFailure.RESOURCE, blocked?.failures?.get(ids[2]))
                releaseSecond.complete(Unit)
                pumpUntil { runtime.pageImagePipeline.foregroundWork.value == 0 }
                model.retryPage(ids[2])
                start()
                pumpUntil { owner().certifyPairing() is ReaderPairingCertification.Certified }
                assertEquals(location, owner().location)
                return@runTest
            }
            runtime.session.retryPage(ids[1])
            pumpUntil {
                runtime.session.state.value.snapshot.activeChapter.pages[1].let {
                    it.attemptGeneration > initial.snapshot.activeChapter.pages[1]
                        .attemptGeneration &&
                        it.loadState is ReaderPageLoadState.Ready
                }
            }
            assertEquals(
                initial.snapshot.activeChapter.pages[1].attemptGeneration,
                model.state
                    .value.session.activeChapter.pages[1].attemptGeneration,
            )
            if (automaticMatching) {
                val holders = runtime.presentationImageOwner.javaClass.getDeclaredField("holders")
                    .run {
                        isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        get(runtime.presentationImageOwner) as Set<DesktopReaderPresentationImageHolder>
                    }
                val oldHolder = holders.first {
                    it.identity.pageId == ids[1] && it.identity
                        .attemptGeneration == 0L && it.state
                        .value is DesktopReaderPresentationImageState.Ready
                }
                org.junit.jupiter.api.Assertions.assertFalse(
                    model.acceptDecodedOriginal(initial.snapshot.activeChapter.pages[1], oldHolder),
                    "A current-looking READY holder must not certify while the actual core has replaced its content",
                )
            }
            if (delayedHeader != null) {
                org.junit.jupiter.api.Assertions.assertFalse(
                    model.acceptDualFact(delayedHeader.first, delayedHeader.third, delayedHeader.second),
                    "The actual bounded header callback cannot consume the old physical content",
                )
            } else {
                releaseSecond.complete(Unit)
                val job = model.javaClass.getDeclaredField("spreadPublisherJob").run {
                    isAccessible = true
                    get(model) as Job
                }
                pumpUntil { job.isCompleted }
            }
            assertTrue(
                oldOwner.originalAnalysis(ids[1]) !is ReaderPageAnalysisEvidence.Computed,
                "The live core replaced the completed page: its old batch must not certify through a stale model",
            )
            assertTrue(
                oldOwner.originalAnalysis(ids[2]) !is ReaderPageAnalysisEvidence.Computed,
                "Reject the physical batch atomically, including its unchanged partner",
            )
            if (!automaticMatching) {
                // A fresh physical read must not be attached to an old logical binding either.
                start()
                val staleBindingJob = model.javaClass.getDeclaredField("spreadPublisherJob").run {
                    isAccessible = true
                    get(model) as Job
                }
                pumpUntil { staleBindingJob.isCompleted }
                assertTrue(
                    oldOwner.pageFact(ids[1])?.state != ReaderPageFactState.PORTRAIT,
                    "Stale normal metadata must not become trusted geometry before final batch preflight",
                )
                assertTrue(oldOwner.certifyPairing() !is ReaderPairingCertification.Certified)
            }
            model.acceptSessionState(runtime.session.state.value)
            // Rejected work cannot silently obtain a fresh location budget; use the existing explicit retry.
            model.retryPage(ids[1])
            start()
            val recoveryDeadline = System.nanoTime() + 5_000_000_000L
            while (owner().certifyPairing() !is ReaderPairingCertification.Certified && System
                    .nanoTime() < recoveryDeadline
            ) {
                fixture.scene.render().close()
                runCurrent()
                Thread.sleep(5)
            }
            assertTrue(
                owner().certifyPairing() is ReaderPairingCertification.Certified,
                "Recovery: ${owner().certifyPairing()}; " +
                    "analyses=${ids.map { it to owner().originalAnalysis(it) }}; " +
                    "core=${runtime.session.core.snapshot.activeChapter.pages}; " +
                    "model=${model.state.value.session.activeChapter.pages}",
            )
            assertEquals(location?.pageId, owner().location?.pageId)
        } finally {
            releaseSecond.complete(Unit)
            independent?.onDispose()
            fixture.close()
        }
    }

    @Test
    fun `old READY original asset cannot certify a real replacement attempt`() = runTest {
        val heldRetry = CompletableDeferred<Unit>()
        val originalKeys = ConcurrentLinkedQueue<ReaderPageDecodeKey>()
        val delegate = SkiaDesktopReaderPageImageDecoder()
        val fixture = MountedReaderPresentationFixture(
            tempDir,
            currentCoroutineContext(),
            MountedReaderPresentationCase(
                MountedReaderPresentationMode.DUAL,
                MountedReaderContentRoute.ONLINE,
            ),
            pageImageDecoder = DesktopReaderPageImageDecoder { encoded, key ->
                if (key.contentKey.attemptGeneration > 0) heldRetry.await()
                delegate.decode(encoded, key).also { if (it != null) originalKeys += key }
            },
            heldResponsePage = -1,
        )
        fun pumpUntil(
            diagnostic: () -> String =
                { "The real replacement attempt must reach its controlled decoded state" },
            done: () ->
            Boolean,
        ) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                fixture.scene.render().close()
                runCurrent()
                if (done()) return
                Thread.sleep(5)
            } while (System.nanoTime() < deadline)
            assertTrue(done(), diagnostic())
        }
        var retained: DesktopReaderImageAssetLease? = null
        try {
            fixture.releaseBackgroundGates()
            pumpUntil { originalKeys.any { it.pageIndex == 1 } }
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children
                .flatMap(::flatten)
            val native = fixture.scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                .first { it.config.contains(ReaderPagerNativePresentationKey) }
                .config[ReaderPagerNativePresentationKey]
            val binding = native.javaClass.getDeclaredField("binding").run {
                isAccessible = true
                get(native) as ReaderPagerPresentationBinding
            }
            val model = binding.model
            val runtime = requireNotNull(model.runtime)
            val imageOwner = runtime.presentationImageOwner
            val holders = imageOwner.javaClass.getDeclaredField("holders").run {
                isAccessible = true
                @Suppress("UNCHECKED_CAST")
                get(imageOwner) as Set<DesktopReaderPresentationImageHolder>
            }
            pumpUntil {
                holders.any {
                    it.identity.pageId.sourcePageIndex == 1 && it.state
                        .value is DesktopReaderPresentationImageState.Ready
                }
            }
            val holder = holders.first {
                it.identity.pageId.sourcePageIndex == 1 && it.state
                    .value is DesktopReaderPresentationImageState.Ready
            }
            val oldKey = holder.decodeKey
            val lease = requireNotNull(holder.retainReadyAssetForRender(holder.identity))
                .also { retained = it }
            val id = oldKey.contentKey.pageId
            model.retryPage(id)
            pumpUntil({
                "runtime=${runtime.session.state.value.snapshot.activeChapter.pages.first { it.id == id }}; " +
                    "model=${model.state.value.session.activeChapter.pages.first { it.id == id }}"
            }) {
                model.state.value.session.activeChapter.pages.first { it.id == id }.let {
                    it.attemptGeneration > oldKey.contentKey.attemptGeneration && it
                        .loadState is ReaderPageLoadState.Ready
                }
            }
            val current = model.state.value.session.activeChapter.pages.first { it.id == id }
            val owner = model.javaClass.getDeclaredField("dualSession").run {
                isAccessible = true
                get(model) as SharedDualPageSession
            }
            assertTrue(model.isCurrentDualContent(current))
            assertTrue(owner.originalAnalysis(id) !is ReaderPageAnalysisEvidence.Computed)
            org.junit.jupiter.api.Assertions.assertFalse(
                model.acceptDecodedOriginal(current, holder),
                "A physically retained old FULL_PAGE asset cannot be bound to the new attempt capability",
            )
            assertTrue(owner.originalAnalysis(id) !is ReaderPageAnalysisEvidence.Computed)
            heldRetry.complete(Unit)
            pumpUntil {
                val currentOwner = model.javaClass.getDeclaredField("dualSession").run {
                    isAccessible = true
                    get(model) as SharedDualPageSession
                }
                currentOwner.originalAnalysis(id) is ReaderPageAnalysisEvidence.Computed
            }
            assertEquals(
                1,
                originalKeys.count {
                    it.pageIndex == 1 && it.contentKey
                        .attemptGeneration == current.attemptGeneration
                },
            )
        } finally {
            heldRetry.complete(Unit)
            retained?.close()
            fixture.close()
        }
    }

    @Test
    fun `actual normal producer certifies original evidence before native pixels and SQL progress`() = runTest {
        val (driver, database) = database()
        val tracker =
            ReaderProgressTracker(RecordReadingProgress(SqlDelightReadingProgressRepository(database)))
        val fixture = MountedReaderPresentationFixture(
            tempDir,
            currentCoroutineContext(),
            MountedReaderPresentationCase(
                MountedReaderPresentationMode.DUAL,
                MountedReaderContentRoute.ONLINE,
            ),
            mangaId = 10,
            progressTracker = tracker,
            automaticSpreadMatching = true,
        )
        try {
            assertEquals(
                0L,
                database.reading_eventsQueries.countByChapter(7).executeAsOne(),
                "Encoded preparation cannot persist reading progress",
            )
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children
                .flatMap(::flatten)
            fun currentNative() = fixture.scene.semanticsOwners.flatMap {
                flatten(
                    it
                        .rootSemanticsNode,
                )
            }
                .firstOrNull { it.config.contains(ReaderPagerNativePresentationKey) }
                ?.config
                ?.get(ReaderPagerNativePresentationKey)
            val setupDeadline = System.nanoTime() + 5_000_000_000L
            while (currentNative() == null && System.nanoTime() < setupDeadline) {
                fixture.scene.render().close()
                runCurrent()
                Thread.sleep(5)
            }
            val initialNative = requireNotNull(currentNative())
            val initialBinding = initialNative.javaClass.getDeclaredField("binding").run {
                isAccessible = true
                get(initialNative) as ReaderPagerPresentationBinding
            }
            val settlements = CopyOnWriteArrayList<Set<ReaderPageId>>()
            val callback = initialBinding.model.javaClass.getDeclaredField("onViewportSettled")
                .apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val original = callback.get(initialBinding.model) as (
                Set<ReaderPageId>,
                ReaderPageId,
            ) -> Unit
            val observing: (Set<ReaderPageId>, ReaderPageId) -> Unit = { pages, anchor ->
                val currentOwner = initialBinding.model.javaClass.getDeclaredField("dualSession")
                    .run {
                        isAccessible = true
                        get(initialBinding.model) as SharedDualPageSession
                    }
                assertTrue(
                    currentOwner.displayedHistory.any { it.drawnPageIds.containsAll(pages) },
                    "Settlement can contain only actual native draw receipts",
                )
                settlements += pages.toSet()
                original(pages, anchor)
            }
            callback.set(initialBinding.model, observing)
            assertTrue(settlements.isEmpty())
            assertEquals(0L, database.reading_eventsQueries.countByChapter(7).executeAsOne())
            fixture.releaseBackgroundGates()
            val deadline = System.nanoTime() + 5_000_000_000L
            // The right page alone may legally advance max progress to 2 before its partner paints.
            while ((
                    settlements.none { pages ->
                        pages.map { it.sourcePageIndex }.toSet() == setOf(
                            1,
                            2,
                        )
                    } ||
                        database.reading_eventsQueries.countByChapter(7)
                            .executeAsOne() != settlements.size.toLong()
                    ) &&
                System.nanoTime() < deadline
            ) {
                fixture.scene.render().close()
                runCurrent()
                Thread.sleep(5)
            }
            val native = requireNotNull(currentNative())
            val binding = native.javaClass.getDeclaredField("binding").run {
                isAccessible = true
                get(native) as ReaderPagerPresentationBinding
            }
            val owner = binding.model.javaClass.getDeclaredField("dualSession").run {
                isAccessible = true
                get(binding.model) as SharedDualPageSession
            }
            val evidence = owner.certifyPairing(owner.identity.pageIds[1])
            assertEquals(
                2L,
                database.chaptersQueries.getChapterById(7).executeAsOne().last_page_read,
                "native=${native.describe()}; location=${owner.location}; proof=$evidence; " +
                    "failures=${binding.model.state.value.dualPairingFailures}; " +
                    "events=${fixture.events().takeLast(10).map { it.type }}",
            )
            assertTrue(
                evidence is ReaderPairingCertification.Certified,
                "The real source producer must certify before pixels settle: $evidence",
            )
            assertTrue(fixture.renderContainsFixturePixels())
            assertEquals(setOf(1, 2), fixture.visiblePagesAtViewportCenter())
            val ticket = native.javaClass.getDeclaredField("ticket").run {
                isAccessible = true
                get(native) as ReaderDualPageViewportTicket
            }
            val drawn = requireNotNull(owner.frame(ticket)).drawnPageIds.map { it.sourcePageIndex }
                .toSet()
            assertEquals(
                setOf(1, 2),
                drawn,
                "Both sources must be acknowledged by actual native image paint",
            )
            fun events() = driver.executeQuery(
                null,
                "SELECT idempotency_key, page FROM reading_events WHERE chapter_id=7 ORDER BY _id",
                { cursor ->
                    app.cash.sqldelight.db.QueryResult.Value(
                        buildList {
                            while (cursor.next().value) {
                                add(
                                    requireNotNull(
                                        cursor
                                            .getString(0),
                                    ) to requireNotNull(cursor.getLong(1)),
                                )
                            }
                        },
                    )
                },
                0,
            ).value
            val persisted = events()
            assertTrue(settlements.isNotEmpty())
            assertEquals(
                settlements.map { pages -> pages.maxOf { it.sourcePageIndex }.toLong() },
                persisted.map { it.second },
                "The real tracker must persist each accepted drawn-set settlement exactly once",
            )
            val analysisBeforeEviction = drawn.associateWith { index ->
                owner.originalAnalysis(owner.identity.pageIds[index])
            }
            val nativeDecodes = fixture.events().count { it.type == "DECODE" }
            binding.model.runtime!!.pageImagePipeline.clear()
            repeat(3) {
                fixture.scene.render().close()
                runCurrent()
            }
            val analysisAfterEviction = drawn.associateWith { index ->
                owner.originalAnalysis(owner.identity.pageIds[index])
            }
            assertEquals(
                analysisBeforeEviction,
                analysisAfterEviction,
                "Scalar evidence survives actual native cache eviction",
            )
            assertEquals(
                nativeDecodes,
                fixture.events().count { it.type == "DECODE" },
                "Retained pixels and confirmed scalars require no repeated normal decode",
            )
            assertTrue(fixture.renderContainsFixturePixels())
            assertEquals(
                persisted,
                events(),
                "Repeated native paint acknowledgements cannot duplicate settlements",
            )
            assertEquals(persisted.size, settlements.size)
        } finally {
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun `partial native viewport persists first page then adds delayed partner once without navigation`() = runTest {
        val (driver, database) = database()
        val tracker =
            ReaderProgressTracker(RecordReadingProgress(SqlDelightReadingProgressRepository(database)))
        val partner = CompletableDeferred<Unit>()
        val delegate = SkiaDesktopReaderPageImageDecoder()
        val fixture = MountedReaderPresentationFixture(
            tempDir,
            currentCoroutineContext(),
            MountedReaderPresentationCase(
                MountedReaderPresentationMode.DUAL,
                MountedReaderContentRoute.ONLINE,
            ),
            pageImageDecoder = DesktopReaderPageImageDecoder { encoded, key ->
                if (key.pageIndex == 2) partner.await()
                delegate.decode(encoded, key)
            },
            mangaId = 10,
            progressTracker = tracker,
        )
        fun lastRead() = database.chaptersQueries.getChapterById(7).executeAsOne().last_page_read
        fun pumpUntil(done: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!done() && System.nanoTime() < deadline) {
                fixture.scene.render().close()
                runCurrent()
                Thread.sleep(5)
            }
            assertTrue(done(), "Native runtime progress did not reach the SQL repository")
        }
        try {
            fixture.releaseBackgroundGates()
            pumpUntil { lastRead() == 1L }
            assertEquals(1L, database.reading_eventsQueries.countByChapter(7).executeAsOne())
            assertEquals(false, database.chaptersQueries.getChapterById(7).executeAsOne().read)
            partner.complete(Unit)
            pumpUntil { lastRead() == 2L }
            repeat(40) {
                fixture.scene.render().close()
                runCurrent()
                Thread.sleep(5)
            }
            assertEquals(2L, database.reading_eventsQueries.countByChapter(7).executeAsOne())
            assertEquals(false, database.chaptersQueries.getChapterById(7).executeAsOne().read)
            assertEquals(setOf(1, 2), fixture.visiblePagesAtViewportCenter())
        } finally {
            partner.complete(Unit)
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun `partner first pixels persist with logical current pending and append cannot regress`() =
        runTest {
            val (driver, database) = database()
            val tracker =
                ReaderProgressTracker(RecordReadingProgress(SqlDelightReadingProgressRepository(database)))
            val current = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val delegate = SkiaDesktopReaderPageImageDecoder()
            val fixture = MountedReaderPresentationFixture(
                tempDir,
                currentCoroutineContext(),
                MountedReaderPresentationCase(
                    MountedReaderPresentationMode.DUAL,
                    MountedReaderContentRoute.ONLINE,
                ),
                pageImageDecoder = DesktopReaderPageImageDecoder { encoded, key ->
                    if (key.pageIndex == 1) {
                        entered.complete(Unit)
                        current.await()
                    }
                    delegate.decode(encoded, key)
                },
                mangaId = 10,
                progressTracker = tracker,
                pageCount = 6,
            )
            fun lastRead() = database.chaptersQueries.getChapterById(7).executeAsOne().last_page_read
            var diagnostic: () -> String = { "SQL=${lastRead()}" }
            var frameTimeNanos = 0L
            fun renderFrame() {
                frameTimeNanos += 16_000_000L
                fixture.scene.render(frameTimeNanos).close()
            }
            fun pumpUntil(done: () -> Boolean) {
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!done() && System.nanoTime() < deadline) {
                    renderFrame()
                    runCurrent()
                    Thread.sleep(5)
                }
                assertTrue(done(), "Partner-first native runtime did not reach SQL: ${diagnostic()}")
            }
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children
                .flatMap(::flatten)
            fun model() = fixture.scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                .first {
                    it.config.contains(ReaderPagerNativePresentationKey) &&
                        it.boundsInRoot.contains(Offset(320f, 200f))
                }
                .config[ReaderPagerNativePresentationKey].let { native ->
                native.javaClass.getDeclaredField("binding").apply { isAccessible = true }.get(native)
                    as ReaderPagerPresentationBinding
            }.model
            diagnostic = {
                val active = model()
                val owner = active.javaClass.getDeclaredField("dualSession").apply {
                    isAccessible =
                        true
                }
                    .get(active) as SharedDualPageSession
                "page=${active.state.value.currentPage}/visible=${active.state.value.visiblePageIds}/" +
                    "location=${owner.location}/unit=${owner.targetUnit}/history=${owner.displayedHistory}/" +
                    "SQL=${lastRead()}"
            }
            try {
                fixture.releaseBackgroundGates()
                pumpUntil { lastRead() == 2L }
                assertTrue(entered.isCompleted, "The original current decoder remains in flight")
                assertTrue(!current.isCompleted)
                assertTrue(fixture.renderContainsFixturePixels())
                assertEquals(1, model().state.value.currentPage)
                assertEquals(1, model().runtime!!.session.state.value.context.initialPage)
                assertEquals(
                    setOf(2),
                    model().state.value.visiblePageIds.map { it.sourcePageIndex }
                        .toSet(),
                )
                assertEquals(1L, database.reading_eventsQueries.countByChapter(7).executeAsOne())
                // Ordinary LTR navigation visits the next whole group even while C is still decoding.
                fixture.scene.sendPointerEvent(
                    PointerEventType.Press,
                    Offset(600f, 240f),
                    button =
                    PointerButton.Primary,
                )
                fixture.scene.sendPointerEvent(
                    PointerEventType.Release,
                    Offset(600f, 240f),
                    button =
                    PointerButton.Primary,
                )
                runCurrent()
                assertEquals(3, model().state.value.currentPage)
                pumpUntil { lastRead() == 4L }
                fixture.scene.sendPointerEvent(
                    PointerEventType.Press,
                    Offset(40f, 240f),
                    button =
                    PointerButton.Primary,
                )
                fixture.scene.sendPointerEvent(
                    PointerEventType.Release,
                    Offset(40f, 240f),
                    button =
                    PointerButton.Primary,
                )
                runCurrent()
                assertEquals(2, model().state.value.currentPage)
                current.complete(Unit)
                pumpUntil {
                    model().state.value.visiblePageIds.map { it.sourcePageIndex }
                        .toSet() == setOf(1, 2)
                }
                val appendedEvents = database.reading_eventsQueries.countByChapter(7).executeAsOne()
                repeat(20) {
                    renderFrame()
                    runCurrent()
                    Thread.sleep(5)
                    assertEquals(
                        4L,
                        lastRead(),
                        "Returning and appending earlier C cannot regress E/F actual progress",
                    )
                }
                assertEquals(
                    setOf(1, 2),
                    model().state.value.visiblePageIds.map { it.sourcePageIndex }
                        .toSet(),
                )
                val returnedAnchor = model().state.value.currentPage
                assertTrue(returnedAnchor in 1..2)
                // The runtime materialization context retains its initial page; scheduling has its own anchor.
                assertEquals(1, model().runtime!!.session.state.value.context.initialPage)
                assertEquals(
                    appendedEvents,
                    database.reading_eventsQueries.countByChapter(7)
                        .executeAsOne(),
                )
                val actualCurrent = model().runtime!!.session.state.value.snapshot.activeChapter
                    .pages[1]
                assertTrue(actualCurrent.loadState is ReaderPageLoadState.Ready)
                assertEquals(false, database.chaptersQueries.getChapterById(7).executeAsOne().read)
            } finally {
                current.complete(Unit)
                fixture.close()
                driver.close()
            }
        }

    private fun database(): Pair<JdbcSqliteDriver, Database> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = tachiyomi.data.History.Adapter(DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(
                StringListColumnAdapter,
                UpdateStrategyColumnAdapter,
            ),
        )
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, notes, is_syncing) VALUES (10, 42, '/', 'Manga', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NULL, 0, '', 0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, chapter_number, source_order, date_fetch, date_upload) VALUES (7, 10, '/current', 'Chapter 1', 0, 0, 0, 1, 0, 0, 0)",
            0,
        )
        return driver to database
    }
}
