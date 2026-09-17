package eu.kanade.tachiyomi.extension

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import logcat.LogPriority
import logcat.LogcatLogger
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["success", "cancel", "false-success"])
    fun `default gateway requires real removal and observed absence before deleting trust`(
        outcome: String,
        @TempDir directory: Path,
    ) = runTest {
        val files = directory.resolve("files").toFile().apply(File::mkdirs)
        val cache = directory.resolve("cache").toFile().apply(File::mkdirs)
        val metadata = files.resolve("extension-install-metadata/system-$PACKAGE_NAME.properties").apply {
            parentFile.mkdirs()
            writeText("original-trust")
        }
        var installed = true
        val platform = mockk<PackageInstaller>(relaxed = true)
        val pm = mockk<PackageManager>(relaxed = true) {
            every { packageInstaller } returns platform
            every { getApplicationInfo(PACKAGE_NAME, any<Int>()) } answers {
                if (installed) ApplicationInfo() else throw PackageManager.NameNotFoundException()
            }
        }
        val context = gatewayContext(files, cache, pm)
        every { context.packageName } returns "app.mihon.eis.dev"
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val result = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateway = defaultGateway(context, removeSystemPackage = {
            assertEquals(PACKAGE_NAME, it)
            started.complete(Unit)
            result.await()
            if (outcome == "cancel") throw java.io.IOException("Uninstall cancelled")
            if (outcome == "success") installed = false
        })
        withUninstallCallbacks(context, platform) {
            val terminal = backgroundScope.async { runCatching { gateway.removeSystem(PACKAGE_NAME) } }
            runCurrent()
            assertTrue(started.isCompleted, "Production removal must use the original result bridge")
            advanceTimeBy(5 * 60_000)
            runCurrent()
            assertFalse(terminal.isCompleted)
            assertEquals("original-trust", metadata.readText())
            result.complete(Unit)
            runCurrent()
            assertEquals(outcome == "success", terminal.await().isSuccess)
            assertEquals(outcome != "success", metadata.exists())
            if (metadata.exists()) assertEquals("original-trust", metadata.readText())
            verify(exactly = 0) { platform.uninstall(any<String>(), any()) }
        }
    }

    @Test
    fun `legacy origin refusal and changed bytes never commit`(@TempDir directory: Path) = runTest {
        for (change in listOf("cancel", "candidate", "installed", "repository", "signer", "digest")) {
            withServer(CANDIDATE_BYTES) { server ->
                val gateway = FakeGateway(directory.resolve(change).toFile()).apply {
                    systemPackage = installed(directory, "legacy-$change", null).copy(trust = null)
                    if (change == "signer") candidate = candidate.copy(signers = setOf("wrong"))
                    if (change == "repository") {
                        systemPackage = systemPackage!!.copy(
                            trust = InstalledExtensionTrustRecord(
                                REPOSITORY.copy(baseUrl = "https://other.example"),
                                null,
                            ),
                        )
                    }
                }
                var prompts = 0
                val port = AndroidInstallPort(
                    gateway = gateway,
                    client = OkHttpClient(),
                    confirmLegacyOrigin = {
                        prompts++
                        when (change) {
                            "candidate" -> gateway.transactionRoot.walkTopDown()
                                .first { it.name == "candidate.apk" }.writeText("candidate-tampered")
                            "installed" -> gateway.systemPackage!!.apk.writeText("changed-system")
                        }
                        change != "cancel"
                    },
                )
                val request = artifact(
                    server,
                    declaredSha = if (change ==
                        "digest"
                    ) {
                        "wrong"
                    } else {
                        Hash.sha256(CANDIDATE_BYTES)
                    },
                )
                val terminal = coordinator(port, this).install(ExtensionInstallRequest(request)).last()
                assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal, change)
                if (change ==
                    "cancel"
                ) {
                    assertEquals(AppError.Cancelled, (terminal as ExtensionInstallState.Failed).error)
                }
                assertEquals(if (change in listOf("repository", "signer", "digest")) 0 else 1, prompts, change)
                assertEquals(null, gateway.privatePackage, change)
                assertEquals(0, gateway.systemInstallCount, change)
            }
        }
    }

    @Test
    fun `production installer publishes scoped origin request and consumes only its answer`(
        @TempDir directory: Path,
    ) = runTest {
        for (accepted in listOf(false, true)) {
            withServer(CANDIDATE_BYTES) { server ->
                val gateway = FakeGateway(directory.resolve(accepted.toString()).toFile()).apply {
                    systemPackage = installed(directory, "system-$accepted", null).copy(trust = null)
                }
                val installer = eu.kanade.tachiyomi.extension.util.ExtensionInstaller(
                    context = mockk(relaxed = true),
                    runtimeReloader = {},
                    scope = backgroundScope,
                    gateway = gateway,
                    client = OkHttpClient(),
                    installerProvider = { BasePreferences.ExtensionInstaller.PRIVATE },
                )
                val artifact = artifact(server)
                val extension = eu.kanade.tachiyomi.extension.model.Extension.Available(
                    name = artifact.name,
                    pkgName = artifact.packageName,
                    versionName = artifact.versionName,
                    versionCode = artifact.versionCode,
                    libVersion = artifact.libVersion,
                    lang = "en",
                    isNsfw = false,
                    sources = emptyList(),
                    apkName = "example.apk",
                    iconUrl = "",
                    repoUrl = REPOSITORY.baseUrl,
                    repoName = REPOSITORY.name,
                    repoFingerprint = REPOSITORY.signingKeyFingerprint,
                    declaredSha256 = artifact.declaredSha256,
                )
                val steps = installer.downloadAndInstall(artifact.downloadUrl, extension)
                runCurrent()
                val prompt = installer.originConfirmations.value.single()
                assertEquals(artifact.copy(declaredLibVersion = artifact.libVersion), prompt.artifact)
                installer.answerOriginConfirmation("stale-id", true)
                runCurrent()
                assertEquals(null, gateway.privatePackage)
                installer.answerOriginConfirmation(prompt.id, accepted)
                runCurrent()
                assertTrue(installer.originConfirmations.value.isEmpty())
                assertEquals(accepted, gateway.privatePackage != null)
                assertEquals("system-$accepted", gateway.systemPackage!!.apk.readText())
                assertEquals(
                    if (accepted) {
                        eu.kanade.tachiyomi.extension.model.InstallStep.Installed
                    } else {
                        eu.kanade.tachiyomi.extension.model.InstallStep.Idle
                    },
                    steps.first(),
                )
            }
        }
    }

    @Test
    fun `legacy system origin confirmation permits private upgrade without changing system bytes`(
        @TempDir directory: Path,
    ) = runTest {
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.PRIVATE).apply {
                systemPackage = installed(directory, "legacy-system", null).copy(trust = null)
            }
            val original = gateway.systemPackage
            var confirmations = 0
            val port = AndroidInstallPort(
                gateway = gateway,
                client = OkHttpClient(),
                confirmLegacyOrigin = { candidate ->
                    confirmations++
                    assertEquals(artifact(server), candidate)
                    assertEquals(original, gateway.systemPackage)
                    assertEquals(null, gateway.privatePackage)
                    true
                },
            )
            val terminal = coordinator(port, this).install(ExtensionInstallRequest(artifact(server))).last()
            assertInstanceOf(ExtensionInstallState.Installed::class.java, terminal)
            assertEquals(1, confirmations)
            assertEquals(original, gateway.systemPackage)
            assertEquals("legacy-system", original!!.apk.readText())
            assertEquals(REPOSITORY, gateway.privatePackage!!.trust!!.repository)
        }
    }

    @Test
    fun `first install binds candidate signer to the current repository before either commit target`(
        @TempDir directory: Path,
    ) = runTest {
        for (target in AndroidInstallLocation.entries) {
            withServer(CANDIDATE_BYTES) { server ->
                val gateway = FakeGateway(directory.resolve(target.name).toFile(), target).apply {
                    candidate = candidate.copy(signers = setOf("another-trusted-repository"))
                }
                val port = port(gateway, server)
                val token = port.prepare(ExtensionInstallRequest(artifact(server)))
                assertInstanceOf(
                    AppError.Authentication::class.java,
                    runCatching { port.validate(token) }.exceptionOrNull().installError(),
                )
                assertEquals(0, gateway.copyCount)
                assertEquals(0, gateway.systemInstallCount)
                assertEquals(PhysicalState(null, null), gateway.physicalState())
            }
        }
    }

    @Test
    fun `rejected APK logs its fixed validation reason without request secrets`(@TempDir directory: Path) = runTest {
        val messages = mutableListOf<String>()
        val logger = object : LogcatLogger {
            override fun isLoggable(priority: LogPriority) = true
            override fun isLoggable(priority: LogPriority, tag: String) = true
            override fun log(priority: LogPriority, tag: String, message: String) {
                messages += message
            }
        }
        val wasInstalled = LogcatLogger.isInstalled
        if (!wasInstalled) LogcatLogger.install()
        LogcatLogger.loggers += logger
        try {
            withServer(CANDIDATE_BYTES) { server ->
                val gateway = FakeGateway(directory.toFile()).apply {
                    candidate = candidate.copy(libVersion = null)
                }
                val port = port(gateway, server)
                val token = port.prepare(ExtensionInstallRequest(artifact(server)))
                assertInstanceOf(
                    AppError.MalformedData::class.java,
                    runCatching { port.validate(token) }.exceptionOrNull().installError(),
                )
                assertTrue(messages.contains("Downloaded APK metadata does not match repository metadata"))
                assertFalse(
                    messages.any {
                        it.contains(directory.toString()) || it.contains(server.url("/").toString())
                    },
                )
                port.cleanup(token)
            }
        } finally {
            LogcatLogger.loggers -= logger
            if (!wasInstalled) LogcatLogger.uninstall()
        }
    }

    @Test
    fun `candidate protocol must be supported and match the catalog before snapshot`(
        @TempDir directory: Path,
    ) = runTest {
        for (protocol in listOf(null, 1.45, 1.7, 1.6, Double.NaN)) {
            withServer(CANDIDATE_BYTES) { server ->
                val gateway = FakeGateway(directory.toFile()).apply {
                    candidate = candidate.copy(libVersion = protocol)
                    privatePackage = installed(directory, "old-private", REPOSITORY)
                }
                val port = port(gateway, server)
                val token = port.prepare(ExtensionInstallRequest(artifact(server)))
                assertInstanceOf(
                    AppError.MalformedData::class.java,
                    runCatching {
                        port.validate(token)
                    }.exceptionOrNull().installError(),
                )
                assertEquals(0, gateway.copyCount)
                assertEquals("old-private", gateway.privatePackage!!.apk.readText())
            }
        }
    }

    @Test
    fun `explicit installed protocol prevents downgrade hidden by modern version name`(
        @TempDir directory: Path,
    ) = runTest {
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile()).apply {
                privatePackage =
                    installed(directory, "old-private", REPOSITORY, versionCode = 3, versionName = "release-3")
                        .copy(libVersion = 1.6)
            }
            val port = port(gateway, server)
            val token = port.prepare(ExtensionInstallRequest(artifact(server)))
            assertInstanceOf(
                AppError.MalformedData::class.java,
                runCatching {
                    port.validate(token)
                }.exceptionOrNull().installError(),
            )
            assertEquals(0, gateway.copyCount)
        }
    }

    @Test
    fun `modern protocol is retained through normal private commit`(@TempDir directory: Path) = runTest {
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile()).apply {
                candidate = candidate.copy(versionName = "release-2", libVersion = 1.6)
            }
            val port = port(gateway, server)
            val token = port.prepare(
                ExtensionInstallRequest(artifact(server, versionName = "release-2").copy(declaredLibVersion = 1.6)),
            )
            port.validate(token)
            port.commit(token)
            assertEquals(1.6, gateway.privatePackage!!.libVersion)
        }
    }

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
    fun `rollback before system commit leaves healthy package untouched`(@TempDir directory: Path) = runTest {
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.SYSTEM).apply {
                systemPackage = installed(directory, "healthy-system", REPOSITORY)
            }
            val before = gateway.systemPackage
            val port = port(gateway, server)
            val token = port.prepare(ExtensionInstallRequest(artifact(server)))
            val rollback = port.validate(token)

            port.rollback(rollback)

            assertEquals(0, gateway.systemRemoveCount)
            assertEquals(0, gateway.systemInstallCount)
            assertEquals(before, gateway.systemPackage)
            assertEquals(AndroidLoaderOrigin.SYSTEM, gateway.topology(PACKAGE_NAME).loaderOrigin)
        }
    }

    @Test
    fun `rollback restores matching metadata when system APK bytes differ`(@TempDir directory: Path) = runTest {
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.SYSTEM).apply {
                systemPackage = installed(directory, "original-system", REPOSITORY)
            }
            val port = port(gateway, server)
            val token = port.prepare(ExtensionInstallRequest(artifact(server)))
            val rollback = port.validate(token)
            val differentApk = directory.resolve("different-system.apk").toFile().apply {
                writeText("different-system")
            }
            gateway.systemPackage = checkNotNull(gateway.systemPackage).copy(apk = differentApk)

            port.rollback(rollback)

            assertEquals(1, gateway.systemRemoveCount)
            assertEquals(1, gateway.systemInstallCount)
            assertEquals("original-system", gateway.systemPackage?.apk?.readText())
        }
    }

    @Test
    fun `rollback restores a downgraded system package and is idempotent`(@TempDir directory: Path) = runTest {
        withServer(CANDIDATE_BYTES) { server ->
            val gateway = FakeGateway(directory.toFile(), AndroidInstallLocation.SYSTEM).apply {
                systemPackage = installed(directory, "system-v1", REPOSITORY)
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

            port.rollback(rollback)
            port.rollback(rollback)

            assertEquals(1, gateway.systemPackage?.versionCode)
            assertEquals("system-v1", gateway.systemPackage?.apk?.readText())
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
            val oldMetadata = readProperties(metadataFile)
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
                assertEquals(oldMetadata, readProperties(metadataFile))
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
    fun `coordinator system rollback keeps ownership until actual removal completes`(@TempDir directory: Path) =
        runTest {
            val filesDirectory = directory.resolve("files").toFile().apply(File::mkdirs)
            val cacheDirectory = directory.resolve("cache").toFile().apply(File::mkdirs)
            val installedSystem = directory.resolve("installed-system.apk").toFile()
            var installed = false
            val removalRequested = kotlinx.coroutines.CompletableDeferred<Unit>()
            val removalResult = kotlinx.coroutines.CompletableDeferred<Unit>()
            var reloads = 0
            val packageInstaller = mockk<PackageInstaller>(relaxed = true)
            val packageManager = mockk<PackageManager>(relaxed = true)
            every { packageManager.getPackageInfo(PACKAGE_NAME, any<Int>()) } answers {
                if (!installed) throw PackageManager.NameNotFoundException()
                PackageInfo().apply {
                    packageName = PACKAGE_NAME
                    applicationInfo = ApplicationInfo().apply { sourceDir = installedSystem.absolutePath }
                }
            }
            every { packageManager.getApplicationInfo(PACKAGE_NAME, any<Int>()) } answers {
                if (installed) ApplicationInfo() else throw PackageManager.NameNotFoundException()
            }
            every { packageManager.packageInstaller } returns packageInstaller
            val context = gatewayContext(filesDirectory, cacheDirectory, packageManager)
            every { context.packageName } returns "eu.kanade.tachiyomi"
            val gateway = DefaultAndroidInstallGateway(
                context = context,
                removeSystemPackage = {
                    removalRequested.complete(Unit)
                    removalResult.await()
                    installed = false
                },
                installSystem = { _, file, _ ->
                    file.copyTo(installedSystem, overwrite = true)
                    installed = true
                },
                commitPlanProvider = {
                    AndroidCommitPlan(
                        AndroidInstallLocation.SYSTEM,
                        BasePreferences.ExtensionInstaller.PACKAGEINSTALLER,
                    )
                },
                apkInspector = {
                    AndroidApk(PACKAGE_NAME, "1.4.2", 2, setOf("signer-a"), true)
                },
            )

            withServer(CANDIDATE_BYTES) { server ->
                withUninstallCallbacks(context) {
                    val terminal = async {
                        coordinator(
                            AndroidInstallPort(
                                gateway = gateway,
                                client = OkHttpClient(),
                                runtimeReloader = { if (++reloads == 1) error("reload failed") },
                            ),
                            this,
                        ).install(ExtensionInstallRequest(artifact(server))).last()
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        kotlinx.coroutines.withTimeout(5_000) { removalRequested.await() }
                    }
                    advanceTimeBy(5 * 60_000)
                    runCurrent()
                    assertFalse(terminal.isCompleted, "Elapsed time cannot complete the original removal window")
                    assertTrue(installed)
                    removalResult.complete(Unit)
                    runCurrent()
                    val failure = terminal.await()
                    assertInstanceOf(ExtensionInstallState.Failed::class.java, failure)
                    assertEquals("reload failed", (failure as ExtensionInstallState.Failed).error.cause?.message)
                    assertFalse(installed)
                    // Restoring an originally absent package verifies absence instead of reloading a missing source.
                    assertEquals(1, reloads)
                    verify(exactly = 0) { packageInstaller.uninstall(any<String>(), any()) }
                }
            }
        }

    @Test
    fun `default gateway removes system trust metadata when package is already absent`(@TempDir directory: Path) =
        runTest {
            val filesDirectory = directory.resolve("files").toFile().apply(File::mkdirs)
            val cacheDirectory = directory.resolve("cache").toFile().apply(File::mkdirs)
            val metadataFile = filesDirectory.resolve("extension-install-metadata/system-$PACKAGE_NAME.properties")
                .apply {
                    parentFile?.mkdirs()
                    writeText("stale-system-trust")
                }
            val context = gatewayContext(filesDirectory, cacheDirectory)

            defaultGateway(context).removeSystem(PACKAGE_NAME)

            assertFalse(metadataFile.exists())
        }

    @Test
    fun `default gateway retries system trust cleanup after successful uninstall`(@TempDir directory: Path) =
        runTest {
            val filesDirectory = directory.resolve("files").toFile().apply(File::mkdirs)
            val cacheDirectory = directory.resolve("cache").toFile().apply(File::mkdirs)
            val metadataFile = filesDirectory.resolve("extension-install-metadata/system-$PACKAGE_NAME.properties")
                .apply {
                    parentFile?.mkdirs()
                    writeText("candidate-system-trust")
                }
            var installed = true
            var removals = 0
            var failMetadataDelete = true
            val packageInstaller = mockk<PackageInstaller>(relaxed = true)
            val packageManager = mockk<PackageManager>(relaxed = true)
            every { packageManager.getApplicationInfo(PACKAGE_NAME, any<Int>()) } answers {
                if (installed) ApplicationInfo() else throw PackageManager.NameNotFoundException()
            }
            every { packageManager.packageInstaller } returns packageInstaller
            val context = gatewayContext(filesDirectory, cacheDirectory, packageManager)
            every { context.packageName } returns "eu.kanade.tachiyomi"
            val gateway = defaultGateway(
                context,
                removeSystemPackage = {
                    installed = false
                    removals++
                },
                deleteFile = { file ->
                    if (file == metadataFile && failMetadataDelete) {
                        failMetadataDelete = false
                        false
                    } else {
                        !file.exists() || file.delete()
                    }
                },
            )

            val firstFailure = runCatching { gateway.removeSystem(PACKAGE_NAME) }.exceptionOrNull()
            gateway.removeSystem(PACKAGE_NAME)
            assertInstanceOf(AppError.Storage::class.java, firstFailure.installError())
            assertFalse(metadataFile.exists())
            assertEquals(1, removals)
            verify(exactly = 0) { packageInstaller.uninstall(any<String>(), any()) }
        }

    @Test
    fun `default gateway rejects physically present private and system APKs that cannot be inspected`(
        @TempDir directory: Path,
    ) = runTest {
        listOf(AndroidInstallLocation.PRIVATE, AndroidInstallLocation.SYSTEM).forEach { location ->
            val caseDirectory = directory.resolve(location.name.lowercase())
            val filesDirectory = caseDirectory.resolve("files").toFile().apply(File::mkdirs)
            val cacheDirectory = caseDirectory.resolve("cache").toFile().apply(File::mkdirs)
            val installedApk = when (location) {
                AndroidInstallLocation.PRIVATE -> filesDirectory.resolve("exts/$PACKAGE_NAME.ext")
                AndroidInstallLocation.SYSTEM -> caseDirectory.resolve("installed-system.apk").toFile()
            }.apply {
                parentFile?.mkdirs()
                writeText("uninspectable-old-${location.name.lowercase()}")
            }
            val metadataFile = filesDirectory.resolve(
                "extension-install-metadata/${location.name.lowercase()}-$PACKAGE_NAME.properties",
            )
            writeTrustMetadata(metadataFile, installedApk)
            val oldBytes = installedApk.readBytes()
            val oldMetadata = metadataFile.readBytes()
            val packageManager = mockk<PackageManager>(relaxed = true) {
                every { getPackageArchiveInfo(any<String>(), any<Int>()) } returns null
                if (location == AndroidInstallLocation.SYSTEM) {
                    every { getPackageInfo(any<String>(), any<Int>()) } returns PackageInfo().apply {
                        packageName = PACKAGE_NAME
                        applicationInfo = ApplicationInfo().apply { sourceDir = installedApk.absolutePath }
                    }
                    every {
                        getPackageInfo(any<String>(), any<PackageManager.PackageInfoFlags>())
                    } returns PackageInfo().apply {
                        packageName = PACKAGE_NAME
                        applicationInfo = ApplicationInfo().apply { sourceDir = installedApk.absolutePath }
                    }
                } else {
                    every { getPackageInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()
                    every {
                        getPackageInfo(any<String>(), any<PackageManager.PackageInfoFlags>())
                    } throws PackageManager.NameNotFoundException()
                }
            }
            val context = gatewayContext(filesDirectory, cacheDirectory, packageManager)
            val gateway = DefaultAndroidInstallGateway(
                context = context,
                installSystem = { _, _, _ -> error("commit must not run") },
                commitPlanProvider = {
                    AndroidCommitPlan(
                        location,
                        BasePreferences.ExtensionInstaller.PACKAGEINSTALLER.takeIf {
                            location == AndroidInstallLocation.SYSTEM
                        },
                    )
                },
                apkInspector = { file ->
                    if (file.readText().startsWith("candidate")) {
                        AndroidApk(PACKAGE_NAME, "1.4.2", 2, setOf("signer-a"), true)
                    } else {
                        null
                    }
                },
            )

            withServer(CANDIDATE_BYTES) { server ->
                val port = AndroidInstallPort(gateway, OkHttpClient())
                val token = port.prepare(ExtensionInstallRequest(artifact(server)))

                val failure = runCatching { port.validate(token) }.exceptionOrNull()

                assertInstanceOf(
                    AppError.MalformedData::class.java,
                    failure.installError(),
                    "$location failure=$failure cause=${failure?.cause}",
                )
                assertTrue(oldBytes.contentEquals(installedApk.readBytes()), location.name)
                assertTrue(oldMetadata.contentEquals(metadataFile.readBytes()), location.name)
            }
        }
    }

    @Test
    fun `default gateway rejects installed APK identity cross wiring before commit`(
        @TempDir directory: Path,
    ) = runTest {
        data class InvalidInstalledApk(
            val location: AndroidInstallLocation,
            val packageName: String,
            val isExtension: Boolean,
        )

        listOf(
            InvalidInstalledApk(AndroidInstallLocation.PRIVATE, "evil.package", true),
            InvalidInstalledApk(AndroidInstallLocation.PRIVATE, PACKAGE_NAME, false),
            InvalidInstalledApk(AndroidInstallLocation.SYSTEM, "evil.package", true),
            InvalidInstalledApk(AndroidInstallLocation.SYSTEM, PACKAGE_NAME, false),
        ).forEachIndexed { index, invalid ->
            val caseDirectory = directory.resolve("case-$index")
            val filesDirectory = caseDirectory.resolve("files").toFile().apply(File::mkdirs)
            val cacheDirectory = caseDirectory.resolve("cache").toFile().apply(File::mkdirs)
            val installedApk = when (invalid.location) {
                AndroidInstallLocation.PRIVATE -> filesDirectory.resolve("exts/$PACKAGE_NAME.ext")
                AndroidInstallLocation.SYSTEM -> caseDirectory.resolve("installed-system.apk").toFile()
            }.apply {
                parentFile?.mkdirs()
                writeText("installed-old-$index")
            }
            val metadataFile = filesDirectory.resolve(
                "extension-install-metadata/${invalid.location.name.lowercase()}-$PACKAGE_NAME.properties",
            )
            writeTrustMetadata(metadataFile, installedApk)
            val oldBytes = installedApk.readBytes()
            val oldMetadata = metadataFile.readBytes()
            val packageManager = mockk<PackageManager>(relaxed = true) {
                if (invalid.location == AndroidInstallLocation.SYSTEM) {
                    every { getPackageInfo(any<String>(), any<Int>()) } returns PackageInfo().apply {
                        packageName = PACKAGE_NAME
                        applicationInfo = ApplicationInfo().apply { sourceDir = installedApk.absolutePath }
                    }
                    every {
                        getPackageInfo(any<String>(), any<PackageManager.PackageInfoFlags>())
                    } returns PackageInfo().apply {
                        packageName = PACKAGE_NAME
                        applicationInfo = ApplicationInfo().apply { sourceDir = installedApk.absolutePath }
                    }
                } else {
                    every { getPackageInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()
                    every {
                        getPackageInfo(any<String>(), any<PackageManager.PackageInfoFlags>())
                    } throws PackageManager.NameNotFoundException()
                }
            }
            val context = gatewayContext(filesDirectory, cacheDirectory, packageManager)
            var systemCommitRan = false
            val gateway = DefaultAndroidInstallGateway(
                context = context,
                installSystem = { _, _, _ -> systemCommitRan = true },
                commitPlanProvider = {
                    AndroidCommitPlan(
                        invalid.location,
                        BasePreferences.ExtensionInstaller.PACKAGEINSTALLER.takeIf {
                            invalid.location == AndroidInstallLocation.SYSTEM
                        },
                    )
                },
                apkInspector = { file ->
                    if (file.readText().startsWith("candidate")) {
                        AndroidApk(PACKAGE_NAME, "1.4.2", 2, setOf("signer-a"), true)
                    } else {
                        AndroidApk(invalid.packageName, "1.4.1", 1, setOf("signer-a"), invalid.isExtension)
                    }
                },
            )

            withServer(CANDIDATE_BYTES) { server ->
                val port = AndroidInstallPort(gateway, OkHttpClient())
                val token = port.prepare(ExtensionInstallRequest(artifact(server)))

                val failure = runCatching { port.validate(token) }.exceptionOrNull()

                assertInstanceOf(
                    AppError.MalformedData::class.java,
                    failure.installError(),
                    "${invalid.location} package=${invalid.packageName} extension=${invalid.isExtension} failure=$failure",
                )
                assertTrue(oldBytes.contentEquals(installedApk.readBytes()), invalid.toString())
                assertTrue(oldMetadata.contentEquals(metadataFile.readBytes()), invalid.toString())
                assertFalse(systemCommitRan, invalid.toString())
                assertFalse(filesDirectory.resolve("exts/evil.package.ext").exists(), invalid.toString())
            }
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

    private fun gatewayContext(
        filesDirectory: File,
        cacheDirectory: File,
        packageManager: PackageManager = mockk<PackageManager> {
            every { getPackageInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()
            every { getApplicationInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()
        },
    ): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDirectory
        every { context.cacheDir } returns cacheDirectory
        every { context.packageManager } returns packageManager
        return context
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
        removeSystemPackage: suspend (String) -> Unit = { error("Unexpected system removal") },
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
        removeSystemPackage = removeSystemPackage,
    )

    private suspend fun withUninstallCallbacks(
        context: Context,
        packageInstaller: PackageInstaller? = null,
        onRegistered: (BroadcastReceiver) -> Unit = {},
        onUninstall: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        mockkConstructor(Intent::class)
        mockkStatic(PendingIntent::class)
        mockkStatic(ContextCompat::class)
        val pendingIntent = mockk<PendingIntent> { every { intentSender } returns mockk() }
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns pendingIntent
        every {
            ContextCompat.registerReceiver(context, any(), any(), ContextCompat.RECEIVER_NOT_EXPORTED)
        } answers {
            onRegistered(secondArg())
            null
        }
        packageInstaller?.let { installer ->
            every { installer.uninstall(PACKAGE_NAME, any()) } answers { onUninstall() }
        }
        try {
            block()
        } finally {
            unmockkStatic(ContextCompat::class)
            unmockkStatic(PendingIntent::class)
            unmockkConstructor(Intent::class)
        }
    }

    private fun writeTrustMetadata(file: File, apk: File) {
        file.parentFile?.mkdirs()
        Properties().apply {
            setProperty("repository.baseUrl", REPOSITORY.baseUrl)
            setProperty("repository.name", REPOSITORY.name)
            setProperty("repository.fingerprint", REPOSITORY.signingKeyFingerprint)
            setProperty("artifact.sha256", Hash.sha256(apk.readBytes()))
        }.also { values -> file.outputStream().use { values.store(it, null) } }
    }

    private fun readProperties(file: File): Properties =
        Properties().also { values -> file.inputStream().use(values::load) }

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
        var systemInstallCount = 0
        var systemRemoveCount = 0

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
            systemInstallCount++
            systemPackage =
                metadata.copy(apk = file.copyTo(File(transactionRoot, "installed-system.apk"), overwrite = true))
        }

        override fun removePrivate(packageName: String): Boolean {
            if (failRemove) return false
            privatePackage = null
            return true
        }

        override suspend fun removeSystem(packageName: String) {
            systemRemoveCount++
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
        const val SYSTEM_UNINSTALL_TIMEOUT_MILLIS = 2 * 60 * 1000L
        val CANDIDATE_BYTES = "candidate-v2".toByteArray()
        val REPOSITORY = RepositoryIdentity("https://repo.example", "Official", "signer-a")
    }
}
