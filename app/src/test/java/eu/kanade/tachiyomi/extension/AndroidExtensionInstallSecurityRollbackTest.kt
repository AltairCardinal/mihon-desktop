package eu.kanade.tachiyomi.extension

import android.content.Context
import android.content.pm.PackageManager
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.util.AndroidApk
import eu.kanade.tachiyomi.extension.util.AndroidCommitPlan
import eu.kanade.tachiyomi.extension.util.AndroidInstallGateway
import eu.kanade.tachiyomi.extension.util.AndroidInstallLocation
import eu.kanade.tachiyomi.extension.util.AndroidInstallPort
import eu.kanade.tachiyomi.extension.util.AndroidInstallTopology
import eu.kanade.tachiyomi.extension.util.AndroidInstalledPackage
import eu.kanade.tachiyomi.extension.util.AndroidLoaderOrigin
import eu.kanade.tachiyomi.extension.util.DefaultAndroidInstallGateway
import eu.kanade.tachiyomi.util.lang.Hash
import io.mockk.every
import io.mockk.mockk
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
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.Properties

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
    fun `dual install validates non-selected commit target repository and digest`(@TempDir directory: Path) = runTest {
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.resolve("fingerprint").toFile(), AndroidInstallLocation.PRIVATE).apply {
                privatePackage = installed(
                    directory,
                    "target-private",
                    REPOSITORY.copy(signingKeyFingerprint = "different-fingerprint"),
                    versionCode = 1,
                )
                systemPackage = installed(directory, "selected-system", REPOSITORY, versionCode = 2)
                candidate = candidate.copy(versionCode = 3)
            }
            val port = port(gateway, server)
            val token = port.prepare(ExtensionInstallRequest(artifact(server, versionCode = 3)))

            val failure = runCatching { port.validate(token) }.exceptionOrNull()

            assertInstanceOf(AppError.Authentication::class.java, failure.installError())
        }
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.resolve("digest").toFile(), AndroidInstallLocation.PRIVATE).apply {
                privatePackage = installed(directory, "target-private-digest", REPOSITORY, versionCode = 1).let {
                    it.copy(trust = it.trust?.copy(artifactSha256 = "recorded-wrong-digest"))
                }
                systemPackage = installed(directory, "selected-system-digest", REPOSITORY, versionCode = 2)
                candidate = candidate.copy(versionCode = 3)
            }
            val port = port(gateway, server)
            val token = port.prepare(ExtensionInstallRequest(artifact(server, versionCode = 3)))

            val failure = runCatching { port.validate(token) }.exceptionOrNull()

            assertInstanceOf(AppError.MalformedData::class.java, failure.installError())
        }
    }

    @Test
    fun `expected absent restore remains stable across topology failure and repeated reload`(@TempDir directory: Path) =
        runTest {
            withServer(CANDIDATE_BYTES) { server ->
                val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.PRIVATE)
                val port = port(gateway, server)
                val token = port.prepare(ExtensionInstallRequest(artifact(server)))
                val rollback = port.validate(token)
                port.commit(token)
                port.rollback(rollback)
                gateway.failTopologyOnce = true

                val firstFailure = runCatching { port.reload(PACKAGE_NAME) }.exceptionOrNull()
                port.reload(PACKAGE_NAME)
                port.reload(PACKAGE_NAME)

                assertInstanceOf(AppError.Storage::class.java, firstFailure.installError())
                assertEquals(0, gateway.runtimeReloads)
            }
        }

    @Test
    fun `cleanup and canonical storage failures retain retryable state`(@TempDir directory: Path) = runTest {
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.resolve("cleanup").toFile(), AndroidInstallLocation.PRIVATE)
            val port = port(gateway, server)
            val token = port.prepare(ExtensionInstallRequest(artifact(server)))
            gateway.failNextDelete = true

            val firstFailure = runCatching { port.cleanup(token) }.exceptionOrNull()
            port.cleanup(token)

            assertInstanceOf(AppError.Storage::class.java, firstFailure.installError())
            assertFalse(gateway.transactionRoot.resolve(token.value).exists())
        }
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.resolve("canonical").toFile(), AndroidInstallLocation.PRIVATE).apply {
                canonicalFailure = IOException("canonical unavailable")
            }

            val failure = runCatching {
                port(gateway, server).prepare(ExtensionInstallRequest(artifact(server)))
            }.exceptionOrNull()

            assertInstanceOf(AppError.Storage::class.java, failure.installError())
        }
    }

    @Test
    fun `failed prepare cleanup is journaled and retried before the next transaction`(@TempDir directory: Path) =
        runTest {
            withServer(CANDIDATE_BYTES) { server ->
                server.enqueue(MockResponse(body = CANDIDATE_BYTES.decodeToString()))
                val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.PRIVATE).apply {
                    failWrite = true
                    failDeleteAttempts = 2
                }
                var transactionId = "failed-prepare"
                val port = AndroidInstallPort(
                    gateway = gateway,
                    client = OkHttpClient(),
                    transactionIdProvider = { transactionId.also { transactionId = "retry-prepare" } },
                )

                val failure = runCatching {
                    port.prepare(ExtensionInstallRequest(artifact(server)))
                }.exceptionOrNull()
                gateway.failWrite = false
                val retry = port.prepare(ExtensionInstallRequest(artifact(server)))

                assertInstanceOf(AppError.Storage::class.java, failure.installError())
                assertEquals("retry-prepare", retry.value)
                assertEquals(listOf("retry-prepare"), gateway.transactionRoot.list()?.sorted())
                port.cleanup(retry)
            }
        }

    @Test
    fun `commit plan remains frozen when installer preference changes after prepare`(@TempDir directory: Path) =
        runTest {
            withServer(CANDIDATE_BYTES) { server ->
                val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.SYSTEM)
                val port = port(gateway, server)
                val token = port.prepare(ExtensionInstallRequest(artifact(server)))

                gateway.commitTarget = AndroidInstallLocation.PRIVATE
                port.validate(token)
                port.commit(token)

                assertEquals(null, gateway.privatePackage)
                assertEquals(CANDIDATE_BYTES.decodeToString(), gateway.systemPackage?.apk?.readText())
                port.cleanup(token)
            }
        }

    @Test
    fun `default gateway distinguishes missing malformed and IO sidecars`(@TempDir directory: Path) = runTest {
        val filesDirectory = directory.resolve("files").toFile().apply(File::mkdirs)
        val cacheDirectory = directory.resolve("cache").toFile().apply(File::mkdirs)
        val privateApk = filesDirectory.resolve("exts/$PACKAGE_NAME.ext").apply {
            parentFile?.mkdirs()
            writeText("old-private")
        }
        val context = gatewayContext(filesDirectory, cacheDirectory)
        val metadataFile = filesDirectory.resolve("extension-install-metadata/private-$PACKAGE_NAME.properties")

        withServer(CANDIDATE_BYTES) { server ->
            val gateway = defaultGateway(context)
            val port = AndroidInstallPort(gateway, OkHttpClient())
            val token = port.prepare(ExtensionInstallRequest(artifact(server)))

            val missingFailure = runCatching { port.validate(token) }.exceptionOrNull()

            assertInstanceOf(AppError.Authentication::class.java, missingFailure.installError())
        }

        metadataFile.parentFile?.mkdirs()
        metadataFile.writeText("repository.baseUrl=https://repo.example\n")
        val malformedFailure = runCatching { defaultGateway(context).topology(PACKAGE_NAME) }.exceptionOrNull()
        assertInstanceOf(AppError.MalformedData::class.java, malformedFailure.installError())

        writeTrustMetadata(metadataFile, privateApk)
        val ioFailure = runCatching {
            defaultGateway(context, trustInput = { throw IOException("metadata read failed") }).topology(PACKAGE_NAME)
        }.exceptionOrNull()
        assertInstanceOf(AppError.Storage::class.java, ioFailure.installError())
    }

    @Test
    fun `default gateway sidecar atomic failure rolls back bytes and preserves old metadata`(@TempDir directory: Path) =
        runTest {
            val filesDirectory = directory.resolve("files").toFile().apply(File::mkdirs)
            val cacheDirectory = directory.resolve("cache").toFile().apply(File::mkdirs)
            val privateApk = filesDirectory.resolve("exts/$PACKAGE_NAME.ext").apply {
                parentFile?.mkdirs()
                writeText("old-private")
            }
            val metadataFile = filesDirectory.resolve("extension-install-metadata/private-$PACKAGE_NAME.properties")
            writeTrustMetadata(metadataFile, privateApk)
            val oldMetadata = metadataFile.readText()
            var failNextSidecarMove = true
            val gateway = defaultGateway(
                gatewayContext(filesDirectory, cacheDirectory),
                atomicReplace = { source, target ->
                    if (target.extension == "properties" && failNextSidecarMove) {
                        failNextSidecarMove = false
                        throw java.nio.file.AtomicMoveNotSupportedException(source.path, target.path, "unsupported")
                    }
                    java.nio.file.Files.move(
                        source.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                },
            )
            withServer(CANDIDATE_BYTES) { server ->
                val terminal = ExtensionInstallCoordinator(AndroidInstallPort(gateway, OkHttpClient()), this)
                    .install(ExtensionInstallRequest(artifact(server)))
                    .last()

                assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
                assertInstanceOf(AppError.Storage::class.java, (terminal as ExtensionInstallState.Failed).error)
                assertEquals("old-private", privateApk.readText())
                assertEquals(oldMetadata, metadataFile.readText())
            }
        }

    @Test
    fun `default gateway sidecar deletion is retryable`(@TempDir directory: Path) {
        val filesDirectory = directory.resolve("files").toFile().apply(File::mkdirs)
        val cacheDirectory = directory.resolve("cache").toFile().apply(File::mkdirs)
        val privateApk = filesDirectory.resolve("exts/$PACKAGE_NAME.ext").apply {
            parentFile?.mkdirs()
            writeText("old-private")
        }
        val metadataFile = filesDirectory.resolve("extension-install-metadata/private-$PACKAGE_NAME.properties")
        writeTrustMetadata(metadataFile, privateApk)
        var failMetadataDelete = true
        val gateway = defaultGateway(
            gatewayContext(filesDirectory, cacheDirectory),
            deleteFile = { file ->
                if (file == metadataFile && failMetadataDelete) {
                    failMetadataDelete = false
                    false
                } else {
                    !file.exists() || file.delete()
                }
            },
        )

        assertFalse(gateway.removePrivate(PACKAGE_NAME))
        assertTrue(gateway.removePrivate(PACKAGE_NAME))
        assertFalse(metadataFile.exists())
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

    private fun gatewayContext(filesDirectory: File, cacheDirectory: File): Context {
        val packageManager = mockk<PackageManager> {
            every { getPackageInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()
        }
        return mockk(relaxed = true) {
            every { filesDir } returns filesDirectory
            every { cacheDir } returns cacheDirectory
            every { this@mockk.packageManager } returns packageManager
        }
    }

    private fun defaultGateway(
        context: Context,
        atomicReplace: (File, File) -> Unit = { source, target ->
            java.nio.file.Files.move(
                source.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        },
        deleteFile: (File) -> Boolean = { !it.exists() || it.delete() },
        trustInput: (File) -> InputStream = File::inputStream,
    ) = DefaultAndroidInstallGateway(
        context = context,
        installSystem = { _, _, _ -> },
        commitPlanProvider = { AndroidCommitPlan(AndroidInstallLocation.PRIVATE) },
        apkInspector = { file ->
            val candidate = file.readText().startsWith("candidate")
            AndroidApk(
                PACKAGE_NAME,
                if (candidate) "1.4.2" else "1.4.1",
                if (candidate) 2 else 1,
                setOf("signer-a"),
                true,
            )
        },
        atomicReplace = atomicReplace,
        deleteFile = deleteFile,
        trustInput = trustInput,
    )

    private fun writeTrustMetadata(file: File, apk: File) {
        file.parentFile?.mkdirs()
        Properties().apply {
            setProperty("repository.baseUrl", REPOSITORY.baseUrl)
            setProperty("repository.name", REPOSITORY.name)
            setProperty("repository.fingerprint", REPOSITORY.signingKeyFingerprint)
            setProperty("artifact.sha256", Hash.sha256(apk.readBytes()))
        }.also { values -> file.outputStream().use { values.store(it, null) } }
    }

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
        var commitTarget: AndroidInstallLocation = AndroidInstallLocation.PRIVATE,
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
        var failNextDelete = false
        var failDeleteAttempts = 0
        var failTopologyOnce = false
        var canonicalFailure: IOException? = null
        var copyCount = 0
        var readonlyCount = 0

        override fun commitPlan(packageName: String) = AndroidCommitPlan(
            location = commitTarget,
            systemInstaller = BasePreferences.ExtensionInstaller.PACKAGEINSTALLER,
        )

        override fun canonical(file: File): File = canonicalFailure?.let { throw it }
            ?: if (canonicalEscape && file.name == "candidate.apk") {
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

        override fun topology(packageName: String): AndroidInstallTopology {
            if (failTopologyOnce) {
                failTopologyOnce = false
                throw IOException("topology unavailable")
            }
            return AndroidInstallTopology(
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
        }

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

        override suspend fun installSystem(
            parentTransactionId: String,
            file: File,
            metadata: AndroidInstalledPackage,
            installer: BasePreferences.ExtensionInstaller,
        ) {
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

        override fun delete(file: File): Boolean {
            if (failDeleteAttempts > 0) {
                failDeleteAttempts--
                return false
            }
            if (failNextDelete) {
                failNextDelete = false
                return false
            }
            return !file.exists() || file.delete()
        }

        fun physicalState() = PhysicalState(privatePackage?.apk?.readText(), systemPackage?.apk?.readText())
    }

    private companion object {
        const val PACKAGE_NAME = "example.extension"
        val CANDIDATE_BYTES = "candidate-v2".toByteArray()
        val REPOSITORY = RepositoryIdentity("https://repo.example", "Official", "fingerprint")
    }
}
