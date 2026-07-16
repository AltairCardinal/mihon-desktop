package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.extension.util.AndroidApk
import eu.kanade.tachiyomi.extension.util.AndroidInstallGateway
import eu.kanade.tachiyomi.extension.util.AndroidInstallLocation
import eu.kanade.tachiyomi.extension.util.AndroidInstallPort
import eu.kanade.tachiyomi.extension.util.AndroidInstallTopology
import eu.kanade.tachiyomi.extension.util.AndroidInstalledPackage
import eu.kanade.tachiyomi.extension.util.AndroidLoaderOrigin
import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.InstalledExtensionTrustRecord
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallCoordinator
import mihon.domain.extension.service.ExtensionInstallRequest
import mihon.domain.extension.service.ExtensionInstallState
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream
import java.nio.file.Path

class AndroidExtensionInstallSecurityRollbackTest {

    @Test
    fun `downloaded digest repository continuity and signer are enforced`(@TempDir directory: Path) = runTest {
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.PRIVATE).apply {
                privatePackage = installed(directory, "old-private", REPOSITORY, setOf("signer-a"))
            }
            val port = port(gateway, server)
            val token = port.prepare(ExtensionInstallRequest(artifact(server, declaredSha = "bad-sha")))

            val digestFailure = runCatching { port.validate(token) }.exceptionOrNull()

            assertInstanceOf(AppError.MalformedData::class.java, digestFailure.installError())
        }
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.PRIVATE).apply {
                privatePackage = installed(
                    directory,
                    "old-repository",
                    REPOSITORY.copy(signingKeyFingerprint = "old-fingerprint"),
                    setOf("signer-a"),
                )
            }
            val port = port(gateway, server)
            val token = port.prepare(ExtensionInstallRequest(artifact(server)))

            val repositoryFailure = runCatching { port.validate(token) }.exceptionOrNull()

            assertInstanceOf(AppError.Authentication::class.java, repositoryFailure.installError())
        }
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.PRIVATE).apply {
                privatePackage = installed(directory, "old-signer", REPOSITORY, setOf("different-signer"))
            }
            val port = port(gateway, server)
            val token = port.prepare(ExtensionInstallRequest(artifact(server)))

            val signerFailure = runCatching { port.validate(token) }.exceptionOrNull()

            assertInstanceOf(AppError.Authentication::class.java, signerFailure.installError())
        }
    }

    @Test
    fun `untrusted confirmation remains a failed terminal state`(@TempDir directory: Path) = runTest {
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.PRIVATE).apply {
                privatePackage = installed(directory, "legacy", repository = null, signers = setOf("signer-a"))
            }
            val terminal = coordinator(port(gateway, server), this)
                .install(ExtensionInstallRequest(artifact(server)))
                .last()

            assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
            assertInstanceOf(AppError.Authentication::class.java, (terminal as ExtensionInstallState.Failed).error)
        }
    }

    @Test
    fun `rollback restores exact private and system topology after reload failure`(@TempDir directory: Path) = runTest {
        val cases = listOf(
            TopologyCase("fresh-private", AndroidInstallLocation.PRIVATE, null, null),
            TopologyCase("fresh-system", AndroidInstallLocation.SYSTEM, null, null),
            TopologyCase("existing-system", AndroidInstallLocation.SYSTEM, null, "old-system"),
            TopologyCase("private-to-system", AndroidInstallLocation.SYSTEM, "old-private", null),
            TopologyCase("system-to-private", AndroidInstallLocation.PRIVATE, null, "old-system"),
            TopologyCase("dual-private", AndroidInstallLocation.PRIVATE, "old-private", "old-system"),
            TopologyCase("dual-system", AndroidInstallLocation.SYSTEM, "old-private", "old-system"),
        )
        cases.forEach { case ->
            withServer(CANDIDATE_BYTES) { server ->
                val gateway = FakeGateway(directory.resolve(case.name).toFile(), case.target).apply {
                    privatePackage = case.privateBytes?.let { installed(directory, "${case.name}-$it", REPOSITORY) }
                    systemPackage = case.systemBytes?.let { installed(directory, "${case.name}-$it", REPOSITORY) }
                }
                val before = gateway.physicalState()
                val port = AndroidInstallPort(
                    gateway = gateway,
                    client = OkHttpClient(),
                    runtimeReloader = {
                        gateway.runtimeReloads++
                        error("reload failed")
                    },
                )

                val terminal = coordinator(port, this)
                    .install(ExtensionInstallRequest(artifact(server)))
                    .last()

                assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal, case.name)
                assertEquals(before, gateway.physicalState(), case.name)
                if (before == PhysicalState(null, null)) {
                    assertEquals(1, gateway.runtimeReloads, "expected-absent must not trigger a second loader failure")
                }
            }
        }
    }

    @Test
    fun `rollback restores a downgraded system package and is idempotent`(@TempDir directory: Path) = runTest {
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.SYSTEM).apply {
                systemPackage = installed(directory, "system-v2", REPOSITORY, versionCode = 2)
                candidate = candidate.copy(versionCode = 3)
            }
            val port = AndroidInstallPort(
                gateway = gateway,
                client = OkHttpClient(),
                runtimeReloader = { error("reload failed") },
            )
            val token = port.prepare(ExtensionInstallRequest(artifact(server, versionCode = 3)))
            val rollback = port.validate(token)
            port.commit(token)
            gateway.systemPackage = gateway.systemPackage?.copy(versionCode = 1)

            port.rollback(rollback)
            port.rollback(rollback)

            assertEquals(2, gateway.systemPackage?.versionCode)
            assertEquals("system-v2", gateway.systemPackage?.apk?.readText())
        }
    }

    @Test
    fun `shared update policy rejects extension library downgrade at equal version code`(@TempDir directory: Path) =
        runTest {
            withServer(CANDIDATE_BYTES) { server ->
                val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.PRIVATE).apply {
                    privatePackage = installed(
                        directory,
                        "lib-v15",
                        REPOSITORY,
                        versionName = "1.5.2",
                        versionCode = 2,
                    )
                    candidate = candidate.copy(versionName = "1.4.2", versionCode = 2)
                }
                val port = port(gateway, server)
                val token = port.prepare(
                    ExtensionInstallRequest(artifact(server, versionName = "1.4.2", versionCode = 2)),
                )

                val failure = runCatching { port.validate(token) }.exceptionOrNull()

                assertInstanceOf(AppError.MalformedData::class.java, failure.installError())
            }
        }

    @Test
    fun `dual install prestate snapshots both APKs as readonly`(@TempDir directory: Path) = runTest {
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.PRIVATE).apply {
                privatePackage = installed(directory, "dual-private", REPOSITORY)
                systemPackage = installed(directory, "dual-system", REPOSITORY)
            }
            val port = port(gateway, server)
            val token = port.prepare(ExtensionInstallRequest(artifact(server)))

            port.validate(token)

            assertEquals(2, gateway.copyCount)
            assertEquals(2, gateway.readonlyCount)
        }
    }

    @Test
    fun `storage and containment failures stay structured`(@TempDir directory: Path) = runTest {
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.PRIVATE).apply {
                canonicalEscape = true
            }
            val failure = runCatching {
                port(gateway, server).prepare(ExtensionInstallRequest(artifact(server)))
            }.exceptionOrNull()
            assertInstanceOf(AppError.Storage::class.java, failure.installError())
        }
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.PRIVATE).apply {
                failReadonly = true
                privatePackage = installed(directory, "readonly-old", REPOSITORY)
            }
            val port = port(gateway, server)
            val token = port.prepare(ExtensionInstallRequest(artifact(server)))
            val failure = runCatching { port.validate(token) }.exceptionOrNull()
            assertInstanceOf(AppError.Storage::class.java, failure.installError())
        }
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.PRIVATE).apply {
                failCopy = true
                privatePackage = installed(directory, "copy-old", REPOSITORY)
            }
            val port = port(gateway, server)
            val token = port.prepare(ExtensionInstallRequest(artifact(server)))
            val failure = runCatching { port.validate(token) }.exceptionOrNull()
            assertInstanceOf(AppError.Storage::class.java, failure.installError())
        }
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.PRIVATE)
            val port = port(gateway, server)
            val token = port.prepare(ExtensionInstallRequest(artifact(server)))
            val rollback = port.validate(token)
            port.commit(token)
            gateway.failRemove = true
            val failure = runCatching { port.rollback(rollback) }.exceptionOrNull()
            assertInstanceOf(AppError.Storage::class.java, failure.installError())
        }
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.PRIVATE).apply {
                failWrite = true
            }
            val failure = runCatching {
                port(gateway, server).prepare(ExtensionInstallRequest(artifact(server)))
            }.exceptionOrNull()
            assertInstanceOf(AppError.Storage::class.java, failure.installError())
        }
    }

    @Test
    fun `download HTTP taxonomy remains distinct`(@TempDir directory: Path) = runTest {
        listOf(
            403 to AppError.Authentication::class.java,
            429 to AppError.RateLimited::class.java,
            500 to AppError.Server::class.java,
        ).forEach { (status, expected) ->
            MockWebServer().also { it.start() }.use { server ->
                server.enqueue(MockResponse(code = status, body = "failure"))
                val failure = runCatching {
                    port(FakeGateway(directory.resolve("http-$status").toFile()), server)
                        .prepare(ExtensionInstallRequest(artifact(server)))
                }.exceptionOrNull()
                assertInstanceOf(expected, failure.installError())
            }
        }
        val server = MockWebServer().also { it.start() }
        val disconnectedUrl = server.url("/apk/example.apk").toString()
        server.close()
        val disconnectedArtifact = artifactForUrl(disconnectedUrl)
        val failure = runCatching {
            AndroidInstallPort(FakeGateway(directory.resolve("offline").toFile()), OkHttpClient())
                .prepare(ExtensionInstallRequest(disconnectedArtifact))
        }.exceptionOrNull()
        assertInstanceOf(AppError.Network::class.java, failure.installError())
    }

    private fun port(gateway: FakeGateway, server: MockWebServer) = AndroidInstallPort(
        gateway = gateway,
        client = OkHttpClient(),
        runtimeReloader = { gateway.runtimeReloads++ },
    )

    private fun coordinator(port: AndroidInstallPort, scope: CoroutineScope) = ExtensionInstallCoordinator(port, scope)

    private fun artifact(
        server: MockWebServer,
        declaredSha: String = Hash.sha256(CANDIDATE_BYTES),
        versionName: String = "1.4.2",
        versionCode: Long = 2,
    ) = ExtensionArtifact(
        name = "Example",
        packageName = PACKAGE_NAME,
        versionName = versionName,
        versionCode = versionCode,
        language = "en",
        isNsfw = false,
        sources = emptyList(),
        repository = REPOSITORY,
        downloadUrl = server.url("/apk/example.apk").toString(),
        iconUrl = "",
        declaredSha256 = declaredSha,
    )

    private fun artifactForUrl(url: String) = ExtensionArtifact(
        name = "Example",
        packageName = PACKAGE_NAME,
        versionName = "1.4.2",
        versionCode = 2,
        language = "en",
        isNsfw = false,
        sources = emptyList(),
        repository = REPOSITORY,
        downloadUrl = url,
        iconUrl = "",
        declaredSha256 = Hash.sha256(CANDIDATE_BYTES),
    )

    private fun installed(
        directory: Path,
        contents: String,
        repository: RepositoryIdentity?,
        signers: Set<String> = setOf("signer-a"),
        versionCode: Long = 1,
        versionName: String = "1.4.$versionCode",
    ): AndroidInstalledPackage {
        val apk = directory.resolve("$contents.apk").toFile().apply {
            parentFile?.mkdirs()
            writeText(contents)
        }
        return AndroidInstalledPackage(
            apk = apk,
            versionName = versionName,
            versionCode = versionCode,
            signers = signers,
            trust = InstalledExtensionTrustRecord(repository, Hash.sha256(apk.readBytes())),
        )
    }

    private suspend fun withServer(bytes: ByteArray, block: suspend (MockWebServer) -> Unit) {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse(body = bytes.decodeToString()))
            block(server)
        }
    }

    private fun Throwable?.installError(): AppError? =
        (this as? mihon.domain.extension.service.ExtensionInstallFailure)?.error

    private data class TopologyCase(
        val name: String,
        val target: AndroidInstallLocation,
        val privateBytes: String?,
        val systemBytes: String?,
    )

    private data class PhysicalState(val privateBytes: String?, val systemBytes: String?)

    private class FakeGateway(
        override val transactionRoot: File,
        override var commitTarget: AndroidInstallLocation = AndroidInstallLocation.PRIVATE,
    ) : AndroidInstallGateway {
        var privatePackage: AndroidInstalledPackage? = null
        var systemPackage: AndroidInstalledPackage? = null
        var candidate = AndroidApk(PACKAGE_NAME, "1.4.2", 2, setOf("signer-a"), isExtension = true)
        var runtimeReloads = 0
        var canonicalEscape = false
        var failReadonly = false
        var failWrite = false
        var failCopy = false
        var failRemove = false
        var copyCount = 0
        var readonlyCount = 0

        override fun canonical(file: File): File = if (canonicalEscape && file.name == "candidate.apk") {
            File(
                transactionRoot.parentFile,
                "escape.apk",
            )
        } else {
            file.canonicalFile
        }

        override fun writeDownload(input: InputStream, destination: File) {
            if (failWrite) error("disk full")
            destination.outputStream().use { input.copyTo(it) }
        }

        override fun inspect(file: File): AndroidApk = if (file.readText().startsWith("candidate")) {
            candidate
        } else {
            val installed = listOfNotNull(privatePackage, systemPackage).firstOrNull {
                it.apk.readText() ==
                    file.readText()
            }
            AndroidApk(
                PACKAGE_NAME,
                installed?.versionName ?: "1.4.1",
                installed?.versionCode ?: 1,
                installed?.signers ?: setOf("signer-a"),
                true,
            )
        }

        override fun topology(packageName: String): AndroidInstallTopology = AndroidInstallTopology(
            privatePackage = privatePackage,
            systemPackage = systemPackage,
            loaderOrigin = when {
                privatePackage == null && systemPackage == null -> AndroidLoaderOrigin.ABSENT
                privatePackage != null &&
                    (
                        systemPackage == null ||
                            requireNotNull(privatePackage).versionCode > requireNotNull(systemPackage).versionCode
                        ) ->
                    AndroidLoaderOrigin.PRIVATE
                else -> AndroidLoaderOrigin.SYSTEM
            },
        )

        override fun copy(source: File, destination: File): Boolean = !failCopy && runCatching {
            copyCount++
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }.isSuccess

        override fun makeReadOnly(file: File): Boolean {
            readonlyCount++
            return !failReadonly && file.setReadOnly()
        }

        override fun installPrivate(file: File, metadata: AndroidInstalledPackage): Boolean {
            privatePackage =
                metadata.copy(apk = file.copyTo(File(transactionRoot, "installed-private.apk"), overwrite = true))
            return true
        }

        override suspend fun installSystem(transactionId: String, file: File, metadata: AndroidInstalledPackage) {
            systemPackage =
                metadata.copy(apk = file.copyTo(File(transactionRoot, "installed-system.apk"), overwrite = true))
        }

        override fun removePrivate(packageName: String): Boolean {
            if (failRemove) return false
            privatePackage = null
            return true
        }

        override suspend fun removeSystem(packageName: String) {
            systemPackage = null
        }

        override fun delete(file: File): Boolean = !file.exists() || file.delete()

        fun physicalState() = PhysicalState(privatePackage?.apk?.readText(), systemPackage?.apk?.readText())
    }

    private companion object {
        const val PACKAGE_NAME = "example.extension"
        val CANDIDATE_BYTES = "candidate-v2".toByteArray()
        val REPOSITORY = RepositoryIdentity("https://repo.example", "Official", "fingerprint")
    }
}
