package mihon.domain.extension

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import org.junit.jupiter.api.Test

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
    private val expectedConcurrentPreparations: Int = 1,
) : ExtensionInstallPort {
    val events = mutableListOf<String>()
    val cleanedTokens = mutableListOf<PreparedExtensionInstallToken>()
    val rollbackTokens = mutableListOf<ExtensionInstallRollbackToken>()
    val preparationStarted = CompletableDeferred<Unit>()
    val expectedPreparationsStarted = CompletableDeferred<Unit>()
    val validationStarted = CompletableDeferred<Unit>()
    val cleanupCompleted = CompletableDeferred<Unit>()
    val releasePreparation = CompletableDeferred<Unit>()
    var prepareCalls = 0
    var commitCalls = 0
    var reloadCalls = 0
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

    override suspend fun validate(token: PreparedExtensionInstallToken) {
        events += "validate"
        validationStarted.complete(Unit)
        if (blockValidation) CompletableDeferred<Unit>().await()
        failAt("validate")
    }

    override suspend fun commit(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken {
        events += "commit"
        commitCalls++
        return ExtensionInstallRollbackToken("old-artifact-and-metadata")
    }

    override suspend fun reload(packageName: String) {
        events += "reload"
        reloadCalls++
        if (reloadCalls == 1) failAt("reload")
    }

    override suspend fun rollback(token: ExtensionInstallRollbackToken) {
        events += "rollback"
        rollbackTokens += token
        failAt("rollback")
    }

    override suspend fun cleanup(token: PreparedExtensionInstallToken) {
        events += "cleanup"
        cleanedTokens += token
        cleanupCompleted.complete(Unit)
    }

    private fun failAt(stage: String) {
        failures[stage]?.let { throw ExtensionInstallFailure(it) }
    }
}
