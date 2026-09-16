package mihon.domain.extension

import kotlinx.coroutines.CancellationException
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallArbiter
import mihon.domain.extension.service.ExtensionInstallInvalidated
import mihon.domain.extension.service.ExtensionInstallInvalidation
import mihon.domain.extension.service.ExtensionInstallLease
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.service.ExtensionInstallStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExtensionInstallArbiterTest {
    @Test
    fun `all views observe the owning progress and uninstall cannot race an install reservation`() {
        val arbiter = ExtensionInstallArbiter()
        val artifact = artifact()
        val lease = requireNotNull(arbiter.reserve(artifact))
        assertEquals(artifact, arbiter.reservations.value[artifact.packageName]?.artifact)
        arbiter.progress(lease, ExtensionInstallState.Preparing)
        assertEquals(ExtensionInstallState.Preparing, arbiter.reservations.value[artifact.packageName]?.progress)
        var removed = false
        assertNull(arbiter.withRemoval(artifact.packageName) { removed = true })
        assertFalse(removed)
        arbiter.release(lease)
        assertEquals(
            true,
            arbiter.withRemoval(artifact.packageName) {
                assertNull(arbiter.reserve(artifact))
                true
            },
        )
        val retry = requireNotNull(arbiter.reserve(artifact))
        arbiter.progress(lease, ExtensionInstallState.Reloading)
        assertNull(arbiter.reservations.value[artifact.packageName]?.progress)
        assertEquals(retry.transactionId, arbiter.reservations.value[artifact.packageName]?.transactionId)
    }

    @Test
    fun `two real callers reserve only once and stop racing commit has one legal result`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val arbiter = ExtensionInstallArbiter()
            val barrier = CyclicBarrier(2)
            val requests = (1..2).map {
                executor.submit<ExtensionInstallLease?> {
                    barrier.await(2, TimeUnit.SECONDS)
                    arbiter.reserve(artifact())
                }
            }
            val leases = requests.mapNotNull { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1, leases.size)
            val lease = leases.single()
            assertTrue(arbiter.activate(lease, lease.artifact))
            val stop = executor.submit<ExtensionInstallStop> {
                barrier.await(2, TimeUnit.SECONDS)
                arbiter.stop(lease)
            }
            val commit = executor.submit<Boolean> {
                barrier.await(2, TimeUnit.SECONDS)
                try {
                    arbiter.enterCommit(lease)
                    true
                } catch (_: CancellationException) {
                    false
                }
            }
            val stopped = stop.get(3, TimeUnit.SECONDS)
            val committed = commit.get(3, TimeUnit.SECONDS)
            assertEquals(committed, stopped == ExtensionInstallStop.COMMITTING)
            assertTrue(arbiter.isBusy(lease.artifact.packageName))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `same package across origins is busy until exact owner releases after cleanup`() {
        val arbiter = ExtensionInstallArbiter()
        val artifact = artifact()
        val lease = requireNotNull(arbiter.reserve(artifact))
        assertNull(arbiter.reserve(artifact.copy(repository = artifact.repository.copy(baseUrl = "https://other"))))
        assertFalse(arbiter.owns(lease, artifact.copy(versionCode = 2)))
        assertFalse(ExtensionInstallArbiter().owns(lease, artifact))
        assertTrue(arbiter.activate(lease, artifact))
        assertFalse(arbiter.activate(lease, artifact))
        assertEquals(ExtensionInstallStop.CANCELLABLE, arbiter.stop(lease))
        assertTrue(arbiter.isBusy(artifact.packageName))
        assertThrows(CancellationException::class.java) { arbiter.enterCommit(lease) }
        assertTrue(arbiter.release(lease))
        val retry = requireNotNull(arbiter.reserve(artifact))
        assertFalse(arbiter.release(lease))
        assertTrue(arbiter.owns(retry, artifact))
    }

    @Test
    fun `commit gate revalidates download time changes and stop cannot cancel committed work`() {
        val arbiter = ExtensionInstallArbiter()
        var invalid: ExtensionInstallInvalidation? = null
        val lease = requireNotNull(arbiter.reserve(artifact()) { invalid })
        assertTrue(arbiter.activate(lease, lease.artifact))
        invalid = ExtensionInstallInvalidation.CATALOG_CHANGED
        assertEquals(
            invalid,
            assertThrows(ExtensionInstallInvalidated::class.java) {
                arbiter.enterCommit(lease)
            }.reason,
        )
        invalid = null
        arbiter.enterCommit(lease)
        assertEquals(ExtensionInstallStop.COMMITTING, arbiter.stop(lease))
        assertTrue(arbiter.isBusy(lease.artifact.packageName))
        arbiter.release(lease)
        assertThrows(IllegalStateException::class.java) { arbiter.enterCommit(lease) }
    }

    companion object {
        fun artifact(pkg: String = "pkg.a") = ExtensionArtifact(
            "Extension", pkg, "1.4.1", 1, "en", false, emptyList(),
            RepositoryIdentity("https://repo", "Repo", "fingerprint"), "https://repo/a.jar", "", null,
        )
    }
}
