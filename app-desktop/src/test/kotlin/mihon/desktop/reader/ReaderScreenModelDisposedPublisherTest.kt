package mihon.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsNode
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.ui.reader.ReaderPagerNativePresentationKey
import mihon.desktop.ui.reader.ReaderPagerPresentationBinding
import mihon.desktop.ui.reader.ReaderScreenModel
import mihon.domain.reader.ReaderDualPageFactBudget
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.ReaderPairingCertification
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.session.ReaderPageLoadState
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ReaderScreenModelDisposedPublisherTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `dispose while the actual optional reserve is blocked cannot publish a closed owner`() = runTest {
        val fixture = MountedReaderPresentationFixture(
            tempDir,
            currentCoroutineContext(),
            MountedReaderPresentationCase(MountedReaderPresentationMode.DUAL, MountedReaderContentRoute.ONLINE),
            mangaId = 10,
            pageCount = 6,
        )
        val failures = ConcurrentLinkedQueue<Throwable>()
        val producerScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> failures.add(error) },
        )
        var independent: ReaderScreenModel? = null
        try {
            fun pumpUntil(predicate: () -> Boolean) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                do {
                    fixture.scene.render().close()
                    runCurrent()
                    if (predicate()) return
                    Thread.sleep(5)
                } while (System.nanoTime() < deadline)
                error("Actual mounted reader prerequisite did not arrive")
            }
            fixture.releaseBackgroundGates()
            pumpUntil { fixture.visiblePagesAtViewportCenter() == setOf(1, 2) && fixture.renderContainsFixturePixels() }
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            val native = fixture.scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                .first { it.config.contains(ReaderPagerNativePresentationKey) }.config[ReaderPagerNativePresentationKey]
            val binding = native.javaClass.getDeclaredField("binding").run {
                isAccessible = true
                get(native) as ReaderPagerPresentationBinding
            }
            val runtime = requireNotNull(binding.model.runtime)
            pumpUntil {
                runtime.session.state.value.snapshot.activeChapter.pages.all {
                    it.loadState is ReaderPageLoadState.Ready
                }
            }
            val initial = runtime.session.state.value
            val model = ReaderScreenModel(initialSessionState = initial, prefs = runtime.prefs, runtime = runtime)
                .also { independent = it }
            model.setDualPageMode(true)
            model.setAutoSpreadMatching(true)
            initial.snapshot.activeChapter.pages.forEach { page ->
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
                            model.captureDualFact(page.id)!!,
                            ReaderPageFact.dimensions(size.first, size.second),
                        ),
                    )
                }
            }
            val owner = model.javaClass.getDeclaredField("dualSession").run {
                isAccessible = true
                get(model) as SharedDualPageSession
            }
            assertTrue(owner.certifyPairing() is ReaderPairingCertification.NeedFacts)
            val budget = owner.factBudget
            val budgetLock = budget.javaClass.getDeclaredField("lock").run {
                isAccessible = true
                get(budget)
            }
            val lifecycleLock = model.javaClass.getDeclaredField("productionRuntimeLifecycleLock").run {
                isAccessible = true
                get(model)
            }
            val completed = CountDownLatch(1)
            var disposedState = model.state.value
            synchronized(budgetLock) {
                synchronized(lifecycleLock) {
                    model.javaClass.declaredMethods.single { it.name == "startSpreadSamplesLocked" }.run {
                        isAccessible = true
                        invoke(model, owner, runtime, producerScope, Semaphore(2))
                    }
                }
                val job = model.javaClass.getDeclaredField("spreadPublisherJob").run {
                    isAccessible = true
                    get(model) as Job
                }
                job.invokeOnCompletion { completed.countDown() }
                val threads = ManagementFactory.getThreadMXBean()
                val blockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                var reserveBlocked = false
                while (!reserveBlocked && System.nanoTime() < blockedDeadline) {
                    reserveBlocked = threads.getThreadInfo(threads.allThreadIds, 30).filterNotNull().any { info ->
                        info.threadState == Thread.State.BLOCKED &&
                            info.lockOwnerId == Thread.currentThread().threadId() &&
                            info.lockInfo?.identityHashCode == System.identityHashCode(budgetLock) &&
                            info.stackTrace.any { frame ->
                                frame.className == ReaderDualPageFactBudget::class.java.name &&
                                    frame.methodName == "reserve"
                            }
                    }
                    if (!reserveBlocked) Thread.sleep(5)
                }
                assertTrue(reserveBlocked, "The actual producer must block at its real budget reserve before dispose")
                // The test owns this monitor, so budget.close re-enters instead of creating lock inversion.
                // The producer has already released lifecycleLock before it waits on budgetLock.
                model.onDispose()
                disposedState = model.state.value
            }
            assertTrue(completed.await(3, TimeUnit.SECONDS), "Cancelled physical producer must actually exit")
            assertTrue(failures.isEmpty(), "Late production publication certified a closed owner: $failures")
            assertSame(disposedState, model.state.value, "Disposed reader must not receive a late publication")
        } finally {
            independent?.onDispose()
            producerScope.coroutineContext[Job]?.cancel()
            fixture.close()
        }
    }
}
