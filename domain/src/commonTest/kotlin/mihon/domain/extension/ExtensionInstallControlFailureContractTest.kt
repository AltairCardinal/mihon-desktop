package mihon.domain.extension

import eu.kanade.tachiyomi.network.inspectSourceFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallCoordinator
import mihon.domain.extension.service.ExtensionInstallPort
import mihon.domain.extension.service.ExtensionInstallRequest
import mihon.domain.extension.service.ExtensionInstallRollbackToken
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.service.PreparedExtensionInstallToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class ExtensionInstallControlFailureContractTest {
    @Test
    fun `rollback and cleanup fatal failures propagate after owned cleanup is attempted`() = runTest {
        for (phase in listOf("rollback", "cleanup")) {
            val fatal = object : VirtualMachineError("controlled $phase fatal") {}
            val observed = mutableListOf<Throwable>()
            val scope = CoroutineScope(
                SupervisorJob() + StandardTestDispatcher(testScheduler) +
                    CoroutineExceptionHandler { _, error -> observed += error },
            )
            val port = ControlledPort(
                IOException("ordinary trigger"),
                cleanupError = fatal.takeIf {
                    phase == "cleanup"
                },
                rollbackError = fatal.takeIf { phase == "rollback" },
            )
            try {
                val coordinator = ExtensionInstallCoordinator(port, scope)
                val outcome = async { runCatching { coordinator.install(request("bad.package")).last() } }
                runCurrent()
                assertTrue(
                    checkNotNull(outcome.await().exceptionOrNull()).inspectSourceFailure().causes.any {
                        it ===
                            fatal
                    },
                )
                assertTrue(port.badEvents.contains("rollback"))
                assertEquals("cleanup", port.badEvents.last())
                assertTrue(observed.any { it === fatal })
                assertInstanceOf(
                    ExtensionInstallState.Installed::class.java,
                    coordinator.install(request("healthy.package")).last(),
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `reload control failures restore and clean while healthy independent install completes`() = runTest {
        for (expected in listOf<Throwable>(
            object : VirtualMachineError("controlled reload fatal") {},
            AssertionError("host reload bug"),
            CancellationException("reload cancelled"),
        )) {
            val observed = mutableListOf<Throwable>()
            val handler = CoroutineExceptionHandler { _, error -> observed += error }
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + handler)
            val port = ControlledPort(expected)
            val coordinator = ExtensionInstallCoordinator(port, scope)
            try {
                val bad = async { runCatching { coordinator.install(request("bad.package")).last() } }
                val healthy = async { coordinator.install(request("healthy.package")).last() }
                runCurrent()
                if (expected is CancellationException) {
                    assertEquals(
                        AppError.Cancelled,
                        assertInstanceOf(ExtensionInstallState.Failed::class.java, bad.await().getOrThrow()).error,
                    )
                } else {
                    assertTrue(
                        checkNotNull(bad.await().exceptionOrNull()).inspectSourceFailure().causes.any {
                            it ===
                                expected
                        },
                    )
                    assertTrue(observed.any { it === expected })
                }
                assertInstanceOf(ExtensionInstallState.Installed::class.java, healthy.await())
                assertEquals(
                    listOf("prepare", "validate", "commit", "reload", "rollback", "reload", "cleanup"),
                    port.badEvents,
                )
                assertInstanceOf(
                    ExtensionInstallState.Installed::class.java,
                    coordinator.install(request("bad.package")).last(),
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `primary fatal remains dominant when cleanup has an unrelated host Error`() = runTest {
        val fatal = object : VirtualMachineError("primary controlled fatal") {}
        val observed = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> observed += error },
        )
        val port = ControlledPort(fatal, AssertionError("cleanup host bug"))
        try {
            val outcome =
                async {
                    runCatching { ExtensionInstallCoordinator(port, scope).install(request("bad.package")).last() }
                }
            runCurrent()
            assertTrue(
                checkNotNull(outcome.await().exceptionOrNull()).inspectSourceFailure().causes.any {
                    it === fatal
                },
            )
            assertTrue(port.badEvents.contains("rollback"))
            assertEquals("cleanup", port.badEvents.last())
            assertTrue(observed.any { it === fatal })
        } finally {
            scope.cancel()
        }
    }

    private class ControlledPort(
        private val error: Throwable,
        private val cleanupError: Throwable? = null,
        private val rollbackError: Throwable? = null,
    ) : ExtensionInstallPort {
        val badEvents = mutableListOf<String>()
        var failed = false
        override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken {
            record(request.artifact.packageName, "prepare")
            return PreparedExtensionInstallToken(request.artifact.packageName)
        }
        override suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken {
            record(token.value, "validate")
            return ExtensionInstallRollbackToken(token.value)
        }
        override suspend fun commit(token: PreparedExtensionInstallToken) {
            record(token.value, "commit")
        }
        override suspend fun reload(packageName: String) {
            record(packageName, "reload")
            if (packageName == "bad.package" && !failed) {
                failed = true
                throw IOException("wrapped reload", error)
            }
        }
        override suspend fun rollback(token: ExtensionInstallRollbackToken) {
            record(token.value, "rollback")
            rollbackError?.let { throw IOException("wrapped rollback", it) }
        }
        override suspend fun cleanup(token: PreparedExtensionInstallToken) {
            record(token.value, "cleanup")
            if (token.value == "bad.package") cleanupError?.let { throw IOException("wrapped cleanup", it) }
        }
        private fun record(pkg: String, phase: String) {
            if (pkg == "bad.package") badEvents += phase
        }
    }

    private fun request(pkg: String) = ExtensionInstallRequest(
        ExtensionArtifact(
            "Fixture", pkg, "1.6.2", 2, "en", false, emptyList(),
            RepositoryIdentity(
                "https://fixture.invalid",
                "Fixture",
                "key",
            ),
            "https://fixture.invalid/fixture.jar", "", null,
        ),
    )
}
