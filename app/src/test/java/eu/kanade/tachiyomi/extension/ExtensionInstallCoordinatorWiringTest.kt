package eu.kanade.tachiyomi.extension

import android.content.Context
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallFailure
import mihon.domain.extension.service.ExtensionInstallPort
import mihon.domain.extension.service.ExtensionInstallRequest
import mihon.domain.extension.service.ExtensionInstallRollbackToken
import mihon.domain.extension.service.PreparedExtensionInstallToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import java.io.File
import java.nio.file.Path

class ExtensionInstallCoordinatorWiringTest {

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
    fun `shared install port does not expose Android package or signing types`() {
        val boundaryTypes = ExtensionInstallPort::class.java.declaredMethods.flatMap { method ->
            method.genericParameterTypes.toList() + method.genericReturnType
        }

        assertTrue(boundaryTypes.none { it.typeName.startsWith("android.") || "<android." in it.typeName })
    }

    @Test
    fun `Android adapter rollback removes a fresh private install when no previous APK exists`(
        @TempDir directory: Path,
    ) = runTest {
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns directory.toFile()
        }
        val port = androidPort(context, this)
        val installed = File(directory.toFile(), "exts/$PACKAGE_NAME.ext").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("new")
        }
        addPreparedInstall(port, "fresh", artifact(), directory.resolve("download.apk").toFile(), null)

        port.rollback(ExtensionInstallRollbackToken("fresh"))

        assertFalse(installed.exists(), "fresh install must not survive a failed runtime reload")
    }

    @Test
    fun `Android adapter rollback restores the previous private APK bytes`(
        @TempDir directory: Path,
    ) = runTest {
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns directory.toFile()
        }
        val port = androidPort(context, this)
        val installed = File(directory.toFile(), "exts/$PACKAGE_NAME.ext").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("new")
        }
        val snapshot = directory.resolve("old.apk").toFile().apply { writeText("old") }
        addPreparedInstall(port, "update", artifact(), directory.resolve("download.apk").toFile(), snapshot)

        port.rollback(ExtensionInstallRollbackToken("update"))

        assertEquals("old", installed.readText())
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

    private fun androidPort(context: Context, scope: TestScope): ExtensionInstallPort {
        val outer = installerWith(RecordingInstallPort(), scope, context)
        val type = ExtensionInstaller::class.java.declaredClasses.single { it.simpleName == "AndroidInstallPort" }
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        return constructor.newInstance(outer) as ExtensionInstallPort
    }

    private fun addPreparedInstall(
        port: ExtensionInstallPort,
        id: String,
        artifact: ExtensionArtifact,
        download: File,
        rollback: File?,
    ) {
        val type = ExtensionInstaller::class.java.declaredClasses.single { it.simpleName == "AndroidPreparedInstall" }
        val prepared = type.declaredConstructors.single { it.parameterCount == 3 }.apply { isAccessible = true }
            .newInstance(artifact, download, rollback)
        val field = port.javaClass.getDeclaredField("prepared").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(port) as MutableMap<String, Any>)[id] = prepared
    }

    private fun installerWith(
        port: ExtensionInstallPort,
        scope: TestScope,
        context: Context,
    ): ExtensionInstaller {
        val constructor = ExtensionInstaller::class.java.declaredConstructors.single {
            it.parameterTypes.size == 4 && it.parameterTypes.any(ExtensionInstallPort::class.java::isAssignableFrom)
        }.apply { isAccessible = true }
        val runtimeReloader: suspend (String) -> Unit = {}
        return constructor.newInstance(context, runtimeReloader, scope, port) as ExtensionInstaller
    }

    private fun managerWith(installer: ExtensionInstaller): ExtensionManager {
        val enabledLanguages = mockk<Preference<Set<String>>>()
        every { enabledLanguages.isSet() } returns true
        val preferences = mockk<SourcePreferences> {
            every { enabledLanguages() } returns enabledLanguages
        }
        val manager = ExtensionManager(
            context = mockk(relaxed = true),
            preferences = preferences,
            trustExtension = mockk<TrustExtension>(relaxed = true),
            installedExtensionsLoader = { emptyList() },
            installReceiverRegistrar = {},
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

    private fun artifact() = ExtensionArtifact(
        name = "Example",
        packageName = PACKAGE_NAME,
        versionName = "1.4.1",
        versionCode = 1,
        language = "en",
        isNsfw = false,
        sources = emptyList(),
        repository = RepositoryIdentity("https://repo.example", "Repository", "fingerprint"),
        downloadUrl = "https://repo.example/example.apk",
        iconUrl = "",
        declaredSha256 = null,
    )

    private class RecordingInstallPort(
        private val failValidation: Boolean = false,
        private val failFirstReload: Boolean = false,
        private val blockCommit: Boolean = false,
    ) : ExtensionInstallPort {
        val calls = mutableListOf<String>()
        var request: ExtensionInstallRequest? = null
        private var reloads = 0

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
            if (blockCommit) awaitCancellation()
        }

        override suspend fun reload(packageName: String) {
            calls += "reload"
            if (failFirstReload && reloads++ == 0) {
                throw ExtensionInstallFailure(AppError.MalformedData())
            }
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
    }
}
