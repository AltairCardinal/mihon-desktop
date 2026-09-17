package mihon.domain.extension

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import mihon.domain.extension.service.ExtensionInstallArbiter
import mihon.domain.extension.service.ExtensionInstallInvalidation
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.suggestion.ExtensionSuggestionBatchController
import mihon.domain.extension.suggestion.SuggestionBatchInstallPort
import mihon.domain.extension.suggestion.SuggestionBatchPause
import mihon.domain.extension.suggestion.SuggestionBatchResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionSuggestionBatchControllerTest {
    @Test
    fun `expected batch identity rejects stale start resume and retry under the owner lock`() = runTest {
        val acceptedResults = mutableListOf<Boolean>()
        for (outcome in listOf(
            SuggestionBatchResult.Installed,
            SuggestionBatchResult.Failed(AppError.Network()),
            SuggestionBatchResult.Paused(SuggestionBatchPause.SERVICE),
        )) {
            val artifact = ExtensionInstallArbiterTest.artifact()
            val batch = ExtensionSuggestionBatchController(
                backgroundScope,
                ExtensionInstallArbiter(),
                SuggestionBatchInstallPort { _, _ -> outcome },
            ) { null }
            assertTrue(batch.start(listOf(artifact)))
            runCurrent()
            val before = batch.state.value
            val accepted = when (outcome) {
                SuggestionBatchResult.Installed -> batch.start(listOf(artifact), expectedBatchId = 0)
                is SuggestionBatchResult.Failed -> batch.retryFailed(listOf(artifact), expectedBatchId = 0)
                else -> batch.resume(listOf(artifact), expectedBatchId = 0)
            }
            acceptedResults += accepted || before != batch.state.value
        }
        assertEquals(listOf(false, false, false), acceptedResults)
    }

    @Test
    fun `stop during commit retains a later interruption without making the batch resumable`() = runTest {
        val arbiter = ExtensionInstallArbiter()
        val finish = CompletableDeferred<Unit>()
        val a = ExtensionInstallArbiterTest.artifact()
        val batch =
            ExtensionSuggestionBatchController(
                backgroundScope,
                arbiter,
                SuggestionBatchInstallPort { lease, _ ->
                    arbiter.activate(lease, lease.artifact)
                    arbiter.enterCommit(lease)
                    finish.await()
                    SuggestionBatchResult.Paused(SuggestionBatchPause.SERVICE)
                },
            ) { null }
        batch.start(listOf(a, a.copy(packageName = "next")))
        runCurrent()
        batch.stop()
        finish.complete(Unit)
        runCurrent()
        assertTrue(batch.state.value.remaining.isEmpty())
        assertNull(batch.state.value.pauseReason)
        assertEquals(SuggestionBatchResult.Paused(SuggestionBatchPause.SERVICE), batch.state.value.items.first().result)
        assertEquals(SuggestionBatchResult.Stopped, batch.state.value.items.last().result)
    }

    @Test
    fun `retry retains successful records and only resubmits explicitly confirmed failures`() = runTest {
        val a = ExtensionInstallArbiterTest.artifact("failed")
        val b = a.copy(packageName = "done")
        var failure = true
        val batch =
            ExtensionSuggestionBatchController(
                backgroundScope,
                ExtensionInstallArbiter(),
                SuggestionBatchInstallPort {
                        lease,
                        _,
                    ->
                    if (lease.artifact.packageName == "failed" && failure) {
                        SuggestionBatchResult.Failed(AppError.Network())
                    } else {
                        SuggestionBatchResult.Installed
                    }
                },
            ) { null }
        batch.start(listOf(a, b))
        runCurrent()
        val id = batch.state.value.id
        failure = false
        assertFalse(batch.retryFailed(listOf(b)))
        assertTrue(batch.retryFailed(listOf(a.copy(versionCode = 2))))
        runCurrent()
        assertEquals(id, batch.state.value.id)
        assertEquals(b, batch.state.value.items.last().artifact)
        assertTrue(batch.state.value.items.all { it.result == SuggestionBatchResult.Installed })
    }

    @Test
    fun `declining current confirmation cancels only current and pauses only an existing remainder`() = runTest {
        for (count in listOf(1, 2)) {
            val batch =
                ExtensionSuggestionBatchController(
                    backgroundScope,
                    ExtensionInstallArbiter(),
                    SuggestionBatchInstallPort {
                            _,
                            _,
                        ->
                        SuggestionBatchResult.Cancelled
                    },
                ) { null }
            batch.start((1..count).map { ExtensionInstallArbiterTest.artifact("pkg.$it") })
            runCurrent()
            assertEquals(1, batch.state.value.completed)
            assertEquals(SuggestionBatchResult.Cancelled, batch.state.value.items.first().result)
            if (count == 1) {
                assertNull(batch.state.value.pauseReason)
                assertTrue(batch.state.value.remaining.isEmpty())
            } else {
                assertEquals(SuggestionBatchPause.CONFIRMATION_CANCELLED, batch.state.value.pauseReason)
                assertEquals(1, batch.state.value.remaining.size)
            }
        }
    }

    @Test
    fun `reconfirmation cannot introduce a source already supplied by a successful artifact`() = runTest {
        val source = mihon.domain.extension.model.ExtensionSourceDescriptor(1, "en", "A", "https://a")
        val a = ExtensionInstallArbiterTest.artifact("done").copy(sources = listOf(source))
        val b = a.copy(packageName = "pending", sources = listOf(source.copy(id = 2)))
        val batch =
            ExtensionSuggestionBatchController(
                backgroundScope,
                ExtensionInstallArbiter(),
                SuggestionBatchInstallPort {
                        lease,
                        _,
                    ->
                    if (lease.artifact.packageName == "pending") {
                        SuggestionBatchResult.Paused(SuggestionBatchPause.PERMISSION)
                    } else {
                        SuggestionBatchResult.Installed
                    }
                },
            ) { null }
        batch.start(listOf(a, b))
        runCurrent()
        assertFalse(batch.resume(listOf(b.copy(versionCode = 2, sources = a.sources + b.sources))))
        assertEquals(listOf(b), batch.state.value.remaining)
    }

    @Test
    fun `pause excludes pending items from completed and reconfirmation preserves prior results`() = runTest {
        val a = ExtensionInstallArbiterTest.artifact("done")
        val b = a.copy(packageName = "pending")
        var paused = true
        val batch =
            ExtensionSuggestionBatchController(
                backgroundScope,
                ExtensionInstallArbiter(),
                SuggestionBatchInstallPort {
                        lease,
                        _,
                    ->
                    if (lease.artifact.packageName == "pending" &&
                        paused
                    ) {
                        SuggestionBatchResult.Paused(SuggestionBatchPause.PERMISSION)
                    } else {
                        SuggestionBatchResult.Installed
                    }
                },
            ) { null }
        batch.start(listOf(a, b))
        runCurrent()
        val originalId = batch.state.value.id
        assertEquals(1, batch.state.value.completed)
        assertFalse(batch.resume(listOf(b, a.copy(packageName = "unconfirmed"))))
        paused = false
        assertTrue(batch.resume(listOf(b.copy(versionCode = 2))))
        runCurrent()
        assertEquals(originalId, batch.state.value.id)
        assertEquals(a, batch.state.value.items.first().artifact)
        assertEquals(2L, batch.state.value.items.last().artifact.versionCode)
        assertEquals(2, batch.state.value.completed)
        assertNull(batch.state.value.pauseReason)
        assertFalse(batch.resume(listOf(b)))
    }

    @Test
    fun `ineligible item is skipped without discarding unrelated items`() = runTest {
        for (reason in listOf(
            ExtensionInstallInvalidation.PRESENT,
            ExtensionInstallInvalidation.IGNORED,
            ExtensionInstallInvalidation.INELIGIBLE,
        )) {
            val a = ExtensionInstallArbiterTest.artifact()
            val b = a.copy(packageName = "b")
            val started = mutableListOf<String>()
            val batch =
                ExtensionSuggestionBatchController(
                    backgroundScope,
                    ExtensionInstallArbiter(),
                    SuggestionBatchInstallPort {
                            lease,
                            _,
                        ->
                        started += lease.artifact.packageName
                        SuggestionBatchResult.Installed
                    },
                ) { artifact -> reason.takeIf { artifact == a } }
            assertTrue(batch.start(listOf(a, b)))
            runCurrent()
            assertEquals(listOf("b"), started)
            assertEquals(SuggestionBatchResult.Invalidated(reason), batch.state.value.items.first().result)
            assertEquals(SuggestionBatchResult.Installed, batch.state.value.items.last().result)
        }
    }

    @Test
    fun `confirmation reserves immutable list skips busy and continues after individual failure`() = runTest {
        val arbiter = ExtensionInstallArbiter()
        val a = ExtensionInstallArbiterTest.artifact("a")
        val b = a.copy(packageName = "b")
        val c = a.copy(packageName = "c")
        val ordinary = requireNotNull(arbiter.reserve(a))
        val started = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val batch =
            ExtensionSuggestionBatchController(
                backgroundScope,
                arbiter,
                SuggestionBatchInstallPort { lease, event ->
                    assertTrue(arbiter.activate(lease, lease.artifact))
                    started += lease.artifact.packageName
                    event(lease.transactionId, ExtensionInstallState.Preparing)
                    gate.await()
                    if (lease.artifact == b) {
                        SuggestionBatchResult.Failed(AppError.Unknown())
                    } else {
                        arbiter.enterCommit(lease)
                        SuggestionBatchResult.Installed
                    }
                },
            ) { null }
        val input = mutableListOf(a, b, c)
        assertTrue(batch.start(input))
        input.clear()
        assertFalse(batch.start(listOf(c)))
        assertNull(arbiter.reserve(c.copy(versionCode = 2)))
        runCurrent()
        assertEquals(listOf("b"), started)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("b", "c"), started)
        assertFalse(batch.state.value.running)
        assertEquals(
            listOf(
                SuggestionBatchResult.Busy,
                SuggestionBatchResult.Failed(AppError.Unknown()),
                SuggestionBatchResult.Installed,
            ),
            batch.state.value.items.map {
                it.result
            },
        )
        assertTrue(arbiter.owns(ordinary, a))
        assertFalse(arbiter.isBusy("b"))
        assertFalse(arbiter.isBusy("c"))
    }

    @Test
    fun `download revalidation pauses remaining snapshot and stale callbacks cannot update retry`() = runTest {
        val arbiter = ExtensionInstallArbiter()
        val a = ExtensionInstallArbiterTest.artifact()
        var invalid: ExtensionInstallInvalidation? = null
        val gate = CompletableDeferred<Unit>()
        var late: (() -> Unit)? = null
        val batch =
            ExtensionSuggestionBatchController(
                backgroundScope,
                arbiter,
                SuggestionBatchInstallPort { lease, event ->
                    assertTrue(arbiter.activate(lease, lease.artifact))
                    late = { event(lease.transactionId, ExtensionInstallState.Reloading) }
                    gate.await()
                    arbiter.enterCommit(lease)
                    SuggestionBatchResult.Installed
                },
            ) { invalid }
        assertTrue(batch.start(listOf(a, a.copy(packageName = "next"))))
        runCurrent()
        invalid = ExtensionInstallInvalidation.CATALOG_CHANGED
        gate.complete(Unit)
        runCurrent()
        assertFalse(batch.state.value.running)
        assertEquals(SuggestionBatchResult.Invalidated(checkNotNull(invalid)), batch.state.value.items.first().result)
        assertEquals(
            SuggestionBatchResult.Paused(SuggestionBatchPause.CATALOG_CHANGED),
            batch.state.value.items.last().result,
        )
        assertEquals(SuggestionBatchPause.CATALOG_CHANGED, batch.state.value.pauseReason)
        val oldCallback = requireNotNull(late)
        invalid = null
        assertFalse(batch.start(listOf(a.copy(versionCode = 2))))
        assertTrue(batch.resume(listOf(a.copy(versionCode = 2), a.copy(packageName = "next"))))
        val before = batch.state.value
        oldCallback()
        assertEquals(before, batch.state.value)
        runCurrent()
        assertTrue(batch.state.value.items.all { it.result == SuggestionBatchResult.Installed })
    }

    @Test
    fun `stop cancels preparation but waits cleanup and never cancels committed current item`() = runTest {
        for (committed in listOf(false, true)) {
            val arbiter = ExtensionInstallArbiter()
            val a = ExtensionInstallArbiterTest.artifact()
            val cleanup = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val batch =
                ExtensionSuggestionBatchController(
                    backgroundScope,
                    arbiter,
                    SuggestionBatchInstallPort { lease, _ ->
                        assertTrue(arbiter.activate(lease, lease.artifact))
                        if (committed) {
                            arbiter.enterCommit(lease)
                            finish.await()
                            SuggestionBatchResult.Installed
                        } else {
                            try {
                                awaitCancellation()
                            } finally {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { cleanup.await() }
                            }
                        }
                    },
                ) { null }
            batch.start(listOf(a, a.copy(packageName = "next")))
            runCurrent()
            batch.stop()
            runCurrent()
            assertTrue(arbiter.isBusy(a.packageName))
            assertFalse(arbiter.isBusy("next"))
            cleanup.complete(Unit)
            finish.complete(Unit)
            runCurrent()
            assertFalse(batch.state.value.running)
            assertEquals(
                if (committed) SuggestionBatchResult.Installed else SuggestionBatchResult.Stopped,
                batch.state.value.items.first().result,
            )
            assertEquals(SuggestionBatchResult.Stopped, batch.state.value.items.last().result)
        }
    }
}
