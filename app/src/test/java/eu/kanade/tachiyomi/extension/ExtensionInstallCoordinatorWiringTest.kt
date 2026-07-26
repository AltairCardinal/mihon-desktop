package eu.kanade.tachiyomi.extension

import android.content.Context
import android.content.pm.PackageManager
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.AndroidApk
import eu.kanade.tachiyomi.extension.util.AndroidCommitPlan
import eu.kanade.tachiyomi.extension.util.AndroidInstallLocation
import eu.kanade.tachiyomi.extension.util.DefaultAndroidInstallGateway
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.network.AndroidNetworkResponseAdapter
import eu.kanade.tachiyomi.util.lang.Hash
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mihon.domain.error.AppError
import mihon.domain.extension.service.ExtensionCatalogService
import mihon.domain.extension.service.ExtensionInstallFailure
import mihon.domain.extension.service.ExtensionInstallPort
import mihon.domain.extension.service.ExtensionInstallRequest
import mihon.domain.extension.service.ExtensionInstallRollbackToken
import mihon.domain.extension.service.PreparedExtensionInstallToken
import mihon.domain.extensionrepo.model.ExtensionRepo
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import java.io.File
import java.nio.file.Path
import java.util.Properties

class ExtensionInstallCoordinatorWiringTest {

    @Test
    fun `catalog manager coordinator and default gateway install the catalog artifact unchanged`(
        @TempDir directory: Path,
    ) = runTest {
        val candidate = "production-candidate".toByteArray()
        mockkObject(ExtensionInstallReceiver.Companion)
        every { ExtensionInstallReceiver.notifyReplaced(any(), any()) } just runs
        try {
            MockWebServer().also { it.start() }.use { server ->
                val index = INDEX_JSON.replace(DECLARED_SHA, Hash.sha256(candidate))
                server.enqueue(MockResponse(body = index))
                server.enqueue(MockResponse(body = candidate.decodeToString()))
                val repository = ExtensionRepo(
                    baseUrl = server.url("/").toString().removeSuffix("/"),
                    name = "Official extensions",
                    shortName = "official",
                    website = server.url("/about").toString(),
                    signingKeyFingerprint = "AB:CD:EF",
                )
                val api = ExtensionApi(
                    client = OkHttpClient(),
                    json = Json { ignoreUnknownKeys = true },
                    repositories = { listOf(repository) },
                    catalogService = ExtensionCatalogService(),
                    responseAdapter = AndroidNetworkResponseAdapter(),
                )
                val packageManager = mockk<PackageManager> {
                    every { getPackageInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()
                }
                val context = mockk<Context>(relaxed = true) {
                    every { cacheDir } returns directory.resolve("cache").toFile().apply { mkdirs() }
                    every { filesDir } returns directory.resolve("files").toFile().apply { mkdirs() }
                    every { this@mockk.packageManager } returns packageManager
                }
                val gateway = DefaultAndroidInstallGateway(
                    context = context,
                    installSystem = { _, _, _ -> error("private install must not use PackageInstaller") },
                    commitPlanProvider = { AndroidCommitPlan(AndroidInstallLocation.PRIVATE) },
                    apkInspector = {
                        AndroidApk(PACKAGE_NAME, "1.4.1", 1, setOf("signer-a"), isExtension = true)
                    },
                )
                val installer = ExtensionInstaller(
                    context = context,
                    runtimeReloader = {},
                    scope = this,
                    gateway = gateway,
                    client = OkHttpClient(),
                    installerProvider = { BasePreferences.ExtensionInstaller.PRIVATE },
                )
                val manager = managerWith(installer)

                val available = api.findExtensions().single()
                val terminal = manager.installExtension(available).first(InstallStep::isCompleted)

                assertEquals(InstallStep.Installed, terminal)
                assertEquals(
                    candidate.decodeToString(),
                    directory.resolve("files/exts/$PACKAGE_NAME.ext").toFile().readText(),
                )
                val trust = Properties().apply {
                    directory.resolve("files/extension-install-metadata/private-$PACKAGE_NAME.properties")
                        .toFile().inputStream().use(::load)
                }
                assertEquals(repository.baseUrl, trust.getProperty("repository.baseUrl"))
                assertEquals(repository.name, trust.getProperty("repository.name"))
                assertEquals(repository.signingKeyFingerprint, trust.getProperty("repository.fingerprint"))
                assertEquals(Hash.sha256(candidate), trust.getProperty("artifact.sha256"))
            }
        } finally {
            unmockkObject(ExtensionInstallReceiver.Companion)
        }
    }

    @Test
    fun `catalog repository identity digest and download URL reach Android install request unchanged`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse(body = INDEX_JSON))
            val repository = ExtensionRepo(
                baseUrl = server.url("/").toString().removeSuffix("/"),
                name = "Official extensions",
                shortName = "official",
                website = server.url("/about").toString(),
                signingKeyFingerprint = "AB:CD:EF",
            )
            val api = ExtensionApi(
                client = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
                repositories = { listOf(repository) },
                catalogService = ExtensionCatalogService(),
                responseAdapter = AndroidNetworkResponseAdapter(),
            )
            val available = api.findExtensions().single()
            val port = RecordingInstallPort()
            val manager = managerWith(installerWith(port, this))

            assertEquals(InstallStep.Installed, manager.installExtension(available).first(InstallStep::isCompleted))

            val artifact = requireNotNull(port.request).artifact
            assertEquals(repository.baseUrl, artifact.repository.baseUrl)
            assertEquals(repository.name, artifact.repository.name)
            assertEquals(repository.signingKeyFingerprint, artifact.repository.signingKeyFingerprint)
            assertEquals(DECLARED_SHA, artifact.declaredSha256)
            assertEquals(server.url("/apk/example.apk").toString(), artifact.downloadUrl)
        }
    }

    @Test
    fun `Android manager install uses shared coordinator phases before publishing installed`() = runTest {
        val port = RecordingInstallPort()
        val manager = managerWith(installerWith(port, this))

        val terminal = manager.installExtension(availableExtension()).first(InstallStep::isCompleted)

        assertEquals(InstallStep.Installed, terminal)
        assertEquals(listOf("prepare", "validate", "commit", "reload", "cleanup"), port.calls)
        assertEquals(PACKAGE_NAME, port.request?.artifact?.packageName)
    }

    @Test
    fun `Android manager install does not bypass shared coordinator validation failure`() = runTest {
        val port = RecordingInstallPort(failValidation = true)
        val manager = managerWith(installerWith(port, this))

        val terminal = manager.installExtension(availableExtension()).first(InstallStep::isCompleted)

        assertEquals(InstallStep.Error, terminal)
        assertEquals(listOf("prepare", "validate", "cleanup"), port.calls)
        assertFalse(port.calls.contains("commit"))
    }

    @Test
    fun `Android manager reload failure restores previous install through shared coordinator`() = runTest {
        val port = RecordingInstallPort(failFirstReload = true)
        val manager = managerWith(installerWith(port, this))

        val terminal = manager.installExtension(availableExtension()).first(InstallStep::isCompleted)

        assertEquals(InstallStep.Error, terminal)
        assertEquals(
            listOf("prepare", "validate", "commit", "reload", "rollback", "reload", "cleanup"),
            port.calls,
        )
    }

    @Test
    fun `Android manager cancellation publishes idle while platform commit is suspended`() = runTest {
        val port = RecordingInstallPort(blockCommit = true)
        val installer = installerWith(port, this)
        val manager = managerWith(installer)
        val extension = availableExtension()
        val steps = manager.installExtension(extension)
        runCurrent()

        runCatching { manager.cancelInstallUpdateExtension(extension) }

        assertEquals(InstallStep.Idle, steps.first())
    }

    @Test
    fun `receiver callback cannot publish a new runtime while its install transaction is active`() = runTest {
        val port = RecordingInstallPort(blockCommit = true)
        val installer = installerWith(port, this)
        lateinit var listener: ExtensionInstallReceiver.Listener
        val manager = managerWith(installer) { listener = it }
        val extension = availableExtension()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.installedExtensionsFlow.collect {}
        }

        manager.installExtension(extension)
        runCurrent()
        runCatching { listener.onExtensionInstalled(installedExtension(versionCode = extension.versionCode)) }
        val remainedHidden = manager.installedExtensionsFlow.value.isEmpty()
        port.unblockCommit()
        runCurrent()

        assertTrue(remainedHidden)
    }

    @Test
    fun `untrusted loader result is an explicit failed terminal and is not published`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val enabledLanguages = mockk<Preference<Set<String>>> { every { isSet() } returns true }
        val manager = ExtensionManager(
            context = context,
            preferences = mockk(relaxed = true) { every { enabledLanguages() } returns enabledLanguages },
            trustExtension = mockk(relaxed = true),
            installedExtensionsLoader = { emptyList() },
            extensionLoader = { _, packageName ->
                LoadResult.Untrusted(
                    Extension.Untrusted("Example", packageName, "1.4.1", 1, 1.4, "untrusted-signer"),
                )
            },
            installerFactory = { reloader ->
                ExtensionInstaller(context, reloader, this, RecordingInstallPort(reloadAction = reloader))
            },
            installReceiverRegistrar = {},
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.untrustedExtensionsFlow.collect {}
        }

        val terminal = manager.installExtension(availableExtension()).first(InstallStep::isCompleted)

        assertEquals(InstallStep.Error, terminal)
        assertTrue(manager.untrustedExtensionsFlow.value.isEmpty())
    }

    @Test
    fun `shared install port does not expose Android package or signing types`() {
        val boundaryTypes = ExtensionInstallPort::class.java.declaredMethods.flatMap { method ->
            method.genericParameterTypes.toList() + method.genericReturnType
        }

        assertTrue(boundaryTypes.none { it.typeName.startsWith("android.") || "<android." in it.typeName })
    }

    private fun installerWith(port: ExtensionInstallPort, scope: TestScope): ExtensionInstaller {
        val constructor = ExtensionInstaller::class.java.declaredConstructors.singleOrNull { constructor ->
            constructor.parameterTypes.size == 4 &&
                constructor.parameterTypes.any(ExtensionInstallPort::class.java::isAssignableFrom)
        }
        requireNotNull(constructor) {
            "Android ExtensionInstaller must expose its platform port to the shared coordinator wiring"
        }
        constructor.isAccessible = true
        val runtimeReloader: suspend (String) -> Unit = {}
        @Suppress("UNCHECKED_CAST")
        return constructor.newInstance(
            mockk<Context>(relaxed = true),
            runtimeReloader,
            scope,
            port,
        ) as ExtensionInstaller
    }

    private fun managerWith(
        installer: ExtensionInstaller,
        registrar: (ExtensionInstallReceiver.Listener) -> Unit = {},
    ): ExtensionManager {
        val enabledLanguages = mockk<Preference<Set<String>>>()
        every { enabledLanguages.isSet() } returns true
        val preferences = mockk<SourcePreferences>(relaxed = true) {
            every { enabledLanguages() } returns enabledLanguages
        }
        val manager = ExtensionManager(
            context = mockk(relaxed = true),
            preferences = preferences,
            trustExtension = mockk<TrustExtension>(relaxed = true),
            installedExtensionsLoader = { emptyList() },
            installReceiverRegistrar = registrar,
        )
        val installerDelegate = ExtensionManager::class.java.declaredFields.single {
            it.name == "installer\$delegate"
        }
        installerDelegate.isAccessible = true
        installerDelegate.set(manager, lazyOf(installer))
        return manager
    }

    private fun availableExtension() = Extension.Available(
        name = "Example",
        pkgName = PACKAGE_NAME,
        versionName = "1.4.1",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        sources = emptyList(),
        apkName = "example.apk",
        iconUrl = "https://repo.example/icon.png",
        repoUrl = "https://repo.example",
    )

    private fun installedExtension(versionCode: Long) = Extension.Installed(
        name = "Example",
        pkgName = PACKAGE_NAME,
        versionName = "1.4.1",
        versionCode = versionCode,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
        isShared = false,
    )

    private class RecordingInstallPort(
        private val failValidation: Boolean = false,
        private val failFirstReload: Boolean = false,
        private val blockCommit: Boolean = false,
        private val reloadAction: (suspend (String) -> Unit)? = null,
    ) : ExtensionInstallPort {
        val calls = mutableListOf<String>()
        var request: ExtensionInstallRequest? = null
        private var reloads = 0
        private val commitGate = CompletableDeferred<Unit>()

        fun unblockCommit() {
            commitGate.complete(Unit)
        }

        override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken {
            calls += "prepare"
            this.request = request
            return PreparedExtensionInstallToken("prepared")
        }

        override suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken {
            calls += "validate"
            if (failValidation) throw ExtensionInstallFailure(AppError.MalformedData())
            return ExtensionInstallRollbackToken("rollback")
        }

        override suspend fun commit(token: PreparedExtensionInstallToken) {
            calls += "commit"
            if (blockCommit) commitGate.await()
        }

        override suspend fun reload(packageName: String) {
            calls += "reload"
            if (failFirstReload && reloads++ == 0) {
                throw ExtensionInstallFailure(AppError.MalformedData())
            }
            reloadAction?.invoke(packageName)
        }

        override suspend fun rollback(token: ExtensionInstallRollbackToken) {
            calls += "rollback"
        }

        override suspend fun cleanup(token: PreparedExtensionInstallToken) {
            calls += "cleanup"
        }
    }

    private companion object {
        const val PACKAGE_NAME = "example.extension"
        const val DECLARED_SHA = "0123456789abcdef"
        const val INDEX_JSON =
            """[{"name":"Tachiyomi: Example","pkg":"example.extension","apk":"example.apk","lang":"en","code":1,"version":"1.4.1","nsfw":0,"sha256":"$DECLARED_SHA","sources":[]}]"""
    }
}
