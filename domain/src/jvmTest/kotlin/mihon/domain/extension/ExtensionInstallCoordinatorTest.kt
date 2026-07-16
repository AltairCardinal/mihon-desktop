package mihon.domain.extension

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallCoordinator
import mihon.domain.extension.service.ExtensionInstallFailure
import mihon.domain.extension.service.ExtensionInstallPort
import mihon.domain.extension.service.ExtensionInstallRequest
import mihon.domain.extension.service.ExtensionInstallRollbackToken
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.service.PreparedExtensionInstallToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext

class ExtensionInstallCoordinatorTest {

    @Test
    fun `successful install emits stages in order and only installs after reload`() = runTest {
        val port = RecordingInstallPort()
        val request = request()

        val states = ExtensionInstallCoordinator(port, backgroundScope).install(request).toList()

        assertEquals(
            listOf(
                ExtensionInstallState.Preparing,
                ExtensionInstallState.Validating,
                ExtensionInstallState.Committing,
                ExtensionInstallState.Reloading,
                ExtensionInstallState.Installed(request.artifact),
            ),
            states,
        )
        assertEquals(listOf("prepare", "validate", "commit", "reload", "cleanup"), port.events)
    }

    @Test
    fun `prepare failure does not commit`() = runTest {
        val error = AppError.Network(IllegalStateException("download failed"))
        val port = RecordingInstallPort(failures = mapOf("prepare" to error))

        val states = ExtensionInstallCoordinator(port, backgroundScope).install(request()).toList()

        assertEquals(error, (states.last() as ExtensionInstallState.Failed).error)
        assertEquals(listOf("prepare"), port.events)
    }

    @Test
    fun `validation failure cleans prepared artifact without committing`() = runTest {
        val error = AppError.MalformedData(IllegalArgumentException("invalid artifact"))
        val port = RecordingInstallPort(failures = mapOf("validate" to error))

        val states = ExtensionInstallCoordinator(port, backgroundScope).install(request()).toList()

        assertEquals(error, (states.last() as ExtensionInstallState.Failed).error)
        assertEquals(listOf("prepare", "validate", "cleanup"), port.events)
        assertEquals(listOf(PreparedExtensionInstallToken("prepared")), port.cleanedTokens)
    }

    @Test
    fun `reload failure rolls back artifact and metadata then verifies old runtime`() = runTest {
        val reloadError = AppError.Unknown(IllegalStateException("new runtime failed"))
        val port = RecordingInstallPort(failures = mapOf("reload" to reloadError))

        val states = ExtensionInstallCoordinator(port, backgroundScope).install(request()).toList()

        assertEquals(
            listOf(
                ExtensionInstallState.Preparing,
                ExtensionInstallState.Validating,
                ExtensionInstallState.Committing,
                ExtensionInstallState.Reloading,
                ExtensionInstallState.RollingBack,
                ExtensionInstallState.RestoringRuntime,
                ExtensionInstallState.Failed(reloadError),
            ),
            states,
        )
        assertEquals(
            listOf("prepare", "validate", "commit", "reload", "rollback", "reload", "cleanup"),
            port.events,
        )
        assertEquals(listOf(ExtensionInstallRollbackToken("old-artifact-and-metadata")), port.rollbackTokens)
        assertEquals(2, port.reloadCalls)
    }

    @Test
    fun `rollback failure has priority over triggering reload failure`() = runTest {
        val reloadError = AppError.Unknown(IllegalStateException("new runtime failed"))
        val rollbackError = AppError.Storage(IllegalStateException("restore failed"))
        val port = RecordingInstallPort(
            failures = mapOf("reload" to reloadError, "rollback" to rollbackError),
        )

        val states = ExtensionInstallCoordinator(port, backgroundScope).install(request()).toList()

        assertEquals(rollbackError, (states.last() as ExtensionInstallState.Failed).error)
        assertEquals(1, port.reloadCalls)
        assertEquals(listOf(ExtensionInstallRollbackToken("old-artifact-and-metadata")), port.rollbackTokens)
        assertFalse(states.any { it is ExtensionInstallState.Installed })
    }

    @Test
    fun `commit side effect followed by failure rolls back from pre-commit snapshot`() = runTest {
        val commitError = AppError.Storage(IllegalStateException("commit failed after replacement"))
        val port = RecordingInstallPort(failures = mapOf("commit" to commitError))

        val states = ExtensionInstallCoordinator(port, backgroundScope).install(request()).toList()

        assertEquals(commitError, (states.last() as ExtensionInstallState.Failed).error)
        assertEquals(
            listOf("prepare", "validate", "commit", "rollback", "reload", "cleanup"),
            port.events,
        )
        assertEquals(1, port.commitSideEffects)
        assertEquals(1, port.rollbackCalls)
    }

    @Test
    fun `cancelling during commit rolls back restores runtime and cleans temporary artifact`() = runTest {
        val port = RecordingInstallPort(blockFirstCommit = true)
        val collection = async {
            ExtensionInstallCoordinator(port, backgroundScope).install(request()).toList()
        }
        port.commitStarted.await()

        collection.cancelAndJoin()
        port.cleanupCompleted.await()

        assertEquals(1, port.commitSideEffects)
        assertEquals(1, port.rollbackCalls)
        assertEquals(1, port.reloadCalls)
        assertEquals(1, port.cleanupCalls)
    }

    @Test
    fun `second reload failure preserves triggering and recovery errors`() = runTest {
        val trigger = AppError.Unknown(IllegalStateException("new runtime failed"))
        val recovery = AppError.Unknown(IllegalStateException("old runtime failed"))
        val port = RecordingInstallPort(
            failures = mapOf("reload-1" to trigger, "reload-2" to recovery),
        )

        val states = ExtensionInstallCoordinator(port, backgroundScope).install(request()).toList()

        val partial = assertInstanceOf(
            AppError.PartialFailure::class.java,
            (states.last() as ExtensionInstallState.Failed).error,
        )
        assertEquals(listOf(trigger, recovery), partial.failures)
        assertEquals(1, port.rollbackCalls)
        assertEquals(2, port.reloadCalls)
    }

    @Test
    fun `cancelling the only collector cleans temporary artifact`() = runTest {
        val port = RecordingInstallPort(blockValidation = true)
        val coordinator = ExtensionInstallCoordinator(port, backgroundScope)
        val collection = async { coordinator.install(request()).toList() }
        port.validationStarted.await()

        collection.cancelAndJoin()
        port.cleanupCompleted.await()

        assertEquals(listOf(PreparedExtensionInstallToken("prepared")), port.cleanedTokens)
        assertFalse(port.events.contains("commit"))
    }

    @Test
    fun `concurrent installs for same package share one in-flight transaction`() = runTest {
        val port = RecordingInstallPort(blockPreparation = true)
        val coordinator = ExtensionInstallCoordinator(port, backgroundScope)
        val first = async { coordinator.install(request()).toList() }
        port.preparationStarted.await()
        val second = async { coordinator.install(request()).toList() }
        runCurrent()

        assertEquals(1, port.prepareCalls)
        port.releasePreparation.complete(Unit)

        assertEquals(first.await(), second.await())
        assertEquals(1, port.prepareCalls)
        assertEquals(1, port.commitCalls)
    }

    @Test
    fun `one of two collectors can cancel without cancelling shared transaction`() = runTest {
        val port = RecordingInstallPort(blockPreparation = true)
        val coordinator = ExtensionInstallCoordinator(port, backgroundScope)
        val first = async { coordinator.install(request()).toList() }
        port.preparationStarted.await()
        val second = async { coordinator.install(request()).toList() }
        runCurrent()

        first.cancelAndJoin()
        port.releasePreparation.complete(Unit)

        assertInstanceOf(ExtensionInstallState.Installed::class.java, second.await().last())
        assertEquals(1, port.prepareCalls)
        assertEquals(1, port.commitCalls)
    }

    @Test
    fun `cleanup failure is retried deterministically after rollback`() = runTest {
        val cleanupError = AppError.Storage(IllegalStateException("temporary artifact busy"))
        val port = RecordingInstallPort(failures = mapOf("cleanup-1" to cleanupError))

        val states = ExtensionInstallCoordinator(port, backgroundScope).install(request()).toList()

        assertEquals(cleanupError, (states.last() as ExtensionInstallState.Failed).error)
        assertEquals(1, port.rollbackCalls)
        assertEquals(2, port.reloadCalls)
        assertEquals(2, port.cleanupCalls)
        assertEquals(1, port.cleanedTokens.size)
        assertFalse(states.any { it is ExtensionInstallState.Installed })
    }

    @Test
    fun `cancelling blocked cleanup rolls back and completes cleanup in non-cancellable context`() = runTest {
        val port = RecordingInstallPort(blockFirstCleanup = true)
        val collection = async {
            ExtensionInstallCoordinator(port, backgroundScope).install(request()).toList()
        }
        port.cleanupStarted.await()

        collection.cancelAndJoin()
        runCurrent()

        assertEquals(1, port.rollbackCalls)
        assertEquals(2, port.reloadCalls)
        assertEquals(2, port.cleanupCalls)
        assertEquals(1, port.cleanedTokens.size)
    }

    @Test
    fun `new same-package install waits for cancelled transaction recovery and cleanup`() = runTest {
        val port = RecordingInstallPort(blockFirstReload = true, blockFirstRollback = true)
        val coordinator = ExtensionInstallCoordinator(port, backgroundScope)
        val first = async { coordinator.install(request()).toList() }
        port.reloadStarted.await()
        first.cancel()
        port.rollbackStarted.await()

        val second = async { coordinator.install(request()).toList() }
        runCurrent()
        val preparesWhileRecovering = port.prepareCalls
        port.releaseRollback.complete(Unit)
        first.join()

        assertInstanceOf(ExtensionInstallState.Installed::class.java, second.await().last())
        assertEquals(1, preparesWhileRecovering)
        assertEquals(2, port.prepareCalls)
        assertTrue(port.events.indexOf("cleanup") < port.events.lastIndexOf("prepare"))
    }

    @Test
    fun `cancelling last collector returns only after flight cleanup and permits immediate retry`() = runTest {
        val port = RecordingInstallPort(blockFirstReload = true, blockFirstRollback = true)
        val coordinator = ExtensionInstallCoordinator(port, backgroundScope)
        val first = async { coordinator.install(request()).toList() }
        port.reloadStarted.await()

        first.cancel()
        port.rollbackStarted.await()

        assertFalse(first.isCompleted, "collector cancellation must wait for flight recovery and cleanup")

        port.releaseRollback.complete(Unit)
        first.join()

        assertEquals(1, port.cleanupCalls)
        assertTrue(port.cleanupCompleted.isCompleted)

        val second = async { coordinator.install(request()).toList() }
        runCurrent()

        assertEquals(2, port.prepareCalls, "completed flight must not delay a same-package retry")
        assertInstanceOf(ExtensionInstallState.Installed::class.java, second.await().last())
    }

    @Test
    fun `request started from terminal state creates a new transaction without replaying old artifact`() = runTest {
        val port = RecordingInstallPort()
        val coordinator = ExtensionInstallCoordinator(port, backgroundScope)
        val secondRequest = request("example.extension").copy(
            artifact = request("example.extension").artifact.copy(versionCode = 2),
        )
        var second: Deferred<List<ExtensionInstallState>>? = null

        coordinator.install(request()).onEach { state ->
            if (state is ExtensionInstallState.Installed) {
                second = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.install(secondRequest).toList()
                }
            }
        }.toList()

        val secondStates = requireNotNull(second).await()
        assertEquals(2, port.prepareCalls)
        assertEquals(secondRequest.artifact, (secondStates.last() as ExtensionInstallState.Installed).artifact)
    }

    @Test
    fun `cancelled scope terminates flight and same package can install in next lifecycle`() = runTest {
        val cancelledJob = SupervisorJob().apply { cancel() }
        var cancelledLifecycle = true
        val switchableScope = object : CoroutineScope {
            override val coroutineContext: CoroutineContext
                get() = if (cancelledLifecycle) {
                    backgroundScope.coroutineContext + cancelledJob
                } else {
                    backgroundScope.coroutineContext
                }
        }
        val port = RecordingInstallPort()
        val coordinator = ExtensionInstallCoordinator(port, switchableScope)

        val cancelledStates = runCatching {
            withTimeout(1_000) { coordinator.install(request()).toList() }
        }.getOrNull()
        cancelledLifecycle = false
        val installedStates = runCatching {
            withTimeout(1_000) { coordinator.install(request()).toList() }
        }.getOrNull()

        assertEquals(listOf(ExtensionInstallState.Failed(AppError.Cancelled)), cancelledStates)
        assertInstanceOf(ExtensionInstallState.Installed::class.java, installedStates?.last())
        assertEquals(1, port.prepareCalls)
    }

    @Test
    fun `scope cancelled while lazy flight starts emits one cancelled terminal`() = runTest {
        val lifecycle = SupervisorJob()
        val cancelBeforeBody = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                lifecycle.cancel()
                block.run()
            }
        }
        val port = RecordingInstallPort()
        val coordinator = ExtensionInstallCoordinator(port, CoroutineScope(lifecycle + cancelBeforeBody))

        val states = withTimeout(1_000) { coordinator.install(request()).toList() }

        assertEquals(listOf(ExtensionInstallState.Failed(AppError.Cancelled)), states)
        assertEquals(0, port.prepareCalls)
    }

    @Test
    fun `scope cancelled while worker is suspended emits one cancelled terminal`() = runTest {
        val lifecycle = SupervisorJob()
        val port = RecordingInstallPort(blockValidation = true)
        val coordinator = ExtensionInstallCoordinator(
            port,
            CoroutineScope(backgroundScope.coroutineContext + lifecycle),
        )
        val collection = async {
            withTimeout(1_000) { coordinator.install(request()).toList() }
        }
        port.validationStarted.await()

        lifecycle.cancel()

        assertEquals(
            listOf(
                ExtensionInstallState.Preparing,
                ExtensionInstallState.Validating,
                ExtensionInstallState.Failed(AppError.Cancelled),
            ),
            collection.await(),
        )
    }

    @Test
    fun `different packages install in parallel`() = runTest {
        val port = RecordingInstallPort(blockPreparation = true, expectedConcurrentPreparations = 2)
        val coordinator = ExtensionInstallCoordinator(port, backgroundScope)
        val first = async { coordinator.install(request("example.one")).toList() }
        val second = async { coordinator.install(request("example.two")).toList() }

        port.expectedPreparationsStarted.await()
        assertEquals(2, port.maxConcurrentPreparations)
        port.releasePreparation.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun `port and opaque tokens expose no File or Android types`() {
        val types = (
            ExtensionInstallPort::class.java.declaredMethods.flatMap { method ->
                listOf(method.genericReturnType) + method.genericParameterTypes
            } + PreparedExtensionInstallToken::class.java.declaredFields.map { it.genericType } +
                ExtensionInstallRollbackToken::class.java.declaredFields.map { it.genericType }
            ).joinToString { it.typeName }

        assertFalse("java.io.File" in types || "android." in types)
    }

    private fun request(packageName: String = "example.extension") = ExtensionInstallRequest(
        artifact = ExtensionArtifact(
            name = "Example",
            packageName = packageName,
            versionName = "1.4.1",
            versionCode = 1,
            language = "en",
            isNsfw = false,
            sources = emptyList(),
            repository = RepositoryIdentity("https://repo.example", "Repo", "fingerprint"),
            downloadUrl = "https://repo.example/example.apk",
            iconUrl = "https://repo.example/example.png",
            declaredSha256 = "digest",
        ),
    )
}

private class RecordingInstallPort(
    private val failures: Map<String, AppError> = emptyMap(),
    private val blockPreparation: Boolean = false,
    private val blockValidation: Boolean = false,
    private val blockFirstCommit: Boolean = false,
    private val blockFirstReload: Boolean = false,
    private val blockFirstRollback: Boolean = false,
    private val blockFirstCleanup: Boolean = false,
    private val expectedConcurrentPreparations: Int = 1,
) : ExtensionInstallPort {
    val events = mutableListOf<String>()
    val cleanedTokens = mutableListOf<PreparedExtensionInstallToken>()
    val rollbackTokens = mutableListOf<ExtensionInstallRollbackToken>()
    val preparationStarted = CompletableDeferred<Unit>()
    val expectedPreparationsStarted = CompletableDeferred<Unit>()
    val validationStarted = CompletableDeferred<Unit>()
    val commitStarted = CompletableDeferred<Unit>()
    val reloadStarted = CompletableDeferred<Unit>()
    val rollbackStarted = CompletableDeferred<Unit>()
    val cleanupStarted = CompletableDeferred<Unit>()
    val cleanupCompleted = CompletableDeferred<Unit>()
    val releasePreparation = CompletableDeferred<Unit>()
    val releaseCommit = CompletableDeferred<Unit>()
    val releaseReload = CompletableDeferred<Unit>()
    val releaseRollback = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    var prepareCalls = 0
    var commitCalls = 0
    var reloadCalls = 0
    var rollbackCalls = 0
    var cleanupCalls = 0
    var commitSideEffects = 0
    var maxConcurrentPreparations = 0
    private var activePreparations = 0

    override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken {
        events += "prepare"
        prepareCalls++
        activePreparations++
        maxConcurrentPreparations = maxOf(maxConcurrentPreparations, activePreparations)
        preparationStarted.complete(Unit)
        if (activePreparations == expectedConcurrentPreparations) expectedPreparationsStarted.complete(Unit)
        if (blockPreparation) releasePreparation.await()
        activePreparations--
        failAt("prepare")
        return PreparedExtensionInstallToken("prepared")
    }

    override suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken {
        events += "validate"
        validationStarted.complete(Unit)
        if (blockValidation) CompletableDeferred<Unit>().await()
        failAt("validate")
        return ExtensionInstallRollbackToken("old-artifact-and-metadata")
    }

    override suspend fun commit(token: PreparedExtensionInstallToken) {
        events += "commit"
        commitCalls++
        commitSideEffects++
        commitStarted.complete(Unit)
        if (blockFirstCommit && commitCalls == 1) releaseCommit.await()
        failAt("commit")
    }

    override suspend fun reload(packageName: String) {
        events += "reload"
        reloadCalls++
        reloadStarted.complete(Unit)
        if (blockFirstReload && reloadCalls == 1) releaseReload.await()
        failAt("reload-$reloadCalls")
        if (reloadCalls == 1) failAt("reload")
    }

    override suspend fun rollback(token: ExtensionInstallRollbackToken) {
        events += "rollback"
        rollbackCalls++
        rollbackTokens += token
        rollbackStarted.complete(Unit)
        if (blockFirstRollback && rollbackCalls == 1) releaseRollback.await()
        failAt("rollback")
    }

    override suspend fun cleanup(token: PreparedExtensionInstallToken) {
        events += "cleanup"
        cleanupCalls++
        cleanupStarted.complete(Unit)
        if (blockFirstCleanup && cleanupCalls == 1) releaseCleanup.await()
        failAt("cleanup-$cleanupCalls")
        cleanedTokens += token
        cleanupCompleted.complete(Unit)
    }

    private fun failAt(stage: String) {
        failures[stage]?.let { throw ExtensionInstallFailure(it) }
    }
}
