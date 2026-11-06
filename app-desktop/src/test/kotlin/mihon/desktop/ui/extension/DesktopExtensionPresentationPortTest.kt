package mihon.desktop.ui.extension

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionInstallStart
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.ExtensionMeta
import mihon.desktop.extension.ExtensionOrigin
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.extension.readExtensionMeta
import mihon.desktop.extension.writeExtensionMeta
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.service.ExtensionInstallArbiter
import mihon.domain.extension.service.ExtensionInstallState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.io.File

class DesktopExtensionPresentationPortTest {
    @Test
    fun `source rows and enable actions consume the same prepared item without reentering getters`() =
        kotlinx.coroutines.runBlocking {
            val source = io.mockk.spyk(mihon.desktop.source.FakeHttpSource(71, "en", "Reader"))
            var broken = false
            val failure = NoSuchMethodError("future source metadata after projection")
            every { source.id } answers { if (broken) throw failure else 71L }
            every { source.name } answers { if (broken) throw failure else "Reader" }
            every { source.lang } answers { if (broken) throw failure else "en" }
            every { source.baseUrl } answers { if (broken) throw failure else "https://reader.example" }
            val node = java.util.prefs.Preferences.userRoot().node("/mihon/ec-source-rows/${System.nanoTime()}")
            val preferences = DesktopAppPreferences(DesktopPreferenceStore(node))
            val installed = InstalledExtension(File("pkg.reader.jar"), listOf(source))
            val emptyCatalog = ExtensionCatalogResult(emptyList(), emptyList())
            val api = mockk<DesktopExtensionApi> {
                every { installArbiter } returns ExtensionInstallArbiter()
                coEvery { refreshCatalog() } returns emptyCatalog
                every { availableExtensions(emptyCatalog) } returns emptyList()
            }
            val installedFlow = MutableStateFlow(listOf(installed))
            val runtime =
                MutableStateFlow(
                    mihon.desktop.extension.DesktopExtensionRuntimeSnapshot(installed = installedFlow.value),
                )
            val manager = mockk<DesktopExtensionManager> {
                every { installedExtensions } returns installedFlow
                every { runtimeSnapshot } returns runtime
            }
            val port = DesktopExtensionPresentationPort(
                api,
                manager,
                sourcePreferences = DesktopExtensionSourcePreferenceAdapter(preferences),
            )
            val model = ExtensionsScreenModel(port, this, ExtensionPresentationOptions(false, setOf("en")))
            try {
                model.refresh().join()
                val item = checkNotNull(model.state.value.projection).installed.single()
                broken = true
                var escaped: Throwable? = null
                try {
                    val row = model.extensionSources(item).single()
                    assertSame(source, row.source)
                    assertEquals("English", row.displayName(java.util.Locale.ENGLISH))
                    model.setSourcesEnabled(item, false)
                } catch (error: Throwable) {
                    escaped = error
                }
                assertEquals(null, escaped)
                assertEquals(setOf("71"), preferences.disabledSources.get())
                kotlinx.coroutines.withTimeout(5_000) {
                    while ("71" !in model.state.value.disabledSourceIds) kotlinx.coroutines.delay(10)
                }
                assertTrue(checkNotNull(model.state.value.projection).loadFailures.isEmpty())
                assertSame(installed, checkNotNull(model.state.value.projection).installed.single().installed)
                model.refresh().join()
                assertTrue(model.state.value.loadFailures.single().error is AppError.ExtensionIncompatible)
                broken = false
                val replacement = installed.copy(displayName = "Replacement frame")
                runtime.value = mihon.desktop.extension.DesktopExtensionRuntimeSnapshot(installed = listOf(replacement))
                kotlinx.coroutines.withTimeout(5_000) {
                    while (model.state.value.loadFailures.isNotEmpty()) kotlinx.coroutines.delay(10)
                }
                assertSame(replacement, checkNotNull(model.state.value.projection).installed.single().installed)
            } finally {
                model.closeAndJoin()
                node.removeNode()
            }
        }

    @Test
    fun `projection uses one supplied runtime snapshot for repository candidate ownership`() {
        val supplied = InstalledExtension(File("pkg.reader.jar"), emptyList(), repoUrl = "https://old.example")
        val live = supplied.copy(repoUrl = "https://new.example")
        val port = DesktopExtensionPresentationPort(mockk(), mockk(), MutableStateFlow(listOf(live)))
        val oldCandidate = candidate("https://old.example", 3)
        val newCandidate = candidate("https://new.example", 3)
        val projection = port.project(
            DesktopExtensionCatalogState(
                ExtensionCatalogResult(emptyList(), emptyList()),
                listOf(oldCandidate, newCandidate),
            ),
            mihon.desktop.extension.DesktopExtensionRuntimeSnapshot(installed = listOf(supplied)),
        )
        assertSame(oldCandidate, projection.available.single().available)
        assertSame(supplied, projection.installed.single().installed)
    }

    @Test
    fun `failed installed package hides same and older catalog rows but keeps explicit newer update`() {
        val failure = mihon.desktop.extension.ExtensionLoadDiagnostic(
            "arbitrary.jar",
            mihon.desktop.extension.ExtensionFailureCategory.LOAD_ERROR,
            "NoSuchMethodError",
            "private diagnostic",
            metadata = ExtensionMeta("pkg.reader", 2, "1.4.2"),
        )
        val port = DesktopExtensionPresentationPort(mockk(), mockk(), MutableStateFlow(emptyList()))
        val runtime = mihon.desktop.extension.DesktopExtensionRuntimeSnapshot(loadFailures = listOf(failure))
        for (version in listOf(1L, 2L, 3L)) {
            val reader = candidate("https://repo.example", version)
            val healthy = reader.copy(pkgName = "pkg.healthy", name = "Healthy")
            val projection = port.project(
                DesktopExtensionCatalogState(ExtensionCatalogResult(emptyList(), emptyList()), listOf(reader, healthy)),
                runtime,
            )
            assertEquals(
                listOf("pkg.healthy"),
                projection.available.filterNot { it.presentation.hasUpdate }
                    .map { it.operationPackageName },
            )
            assertEquals(
                if (version > 2) listOf("pkg.reader") else emptyList<String>(),
                projection.available.filter { it.presentation.hasUpdate }.map { it.operationPackageName },
            )
            assertSame(failure, projection.loadFailures.single())
        }
    }

    @Test
    fun `baseUrl linkage becomes a same projection failure without mutating runtime or warming client`() {
        var reads = 0
        val missing = NoSuchMethodError("future baseUrl ABI")
        val source = io.mockk.spyk(mihon.desktop.source.FakeHttpSource(71, "en", "Reader"))
        every { source.baseUrl } answers {
            reads++
            throw missing
        }
        val installed = InstalledExtension(File("pkg.reader.jar"), listOf(source), versionCode = 2)
        val healthy = InstalledExtension(File("pkg.healthy.jar"), emptyList())
        val state = MutableStateFlow(listOf(installed, healthy))
        val port = DesktopExtensionPresentationPort(mockk(), mockk(), state)
        var escaped: Throwable? = null
        val projection = try {
            port.project(DesktopExtensionCatalogState(ExtensionCatalogResult(emptyList(), emptyList()), emptyList()))
        } catch (error: Throwable) {
            escaped = error
            null
        }
        assertEquals(null, escaped)
        val result = checkNotNull(projection)
        assertSame(state.value[0], installed)
        assertEquals(listOf(healthy), result.installed.map { it.installed })
        assertEquals(1, reads)
        val failure = result.loadFailures.single()
        assertEquals("ExtensionIncompatible", failure.error?.javaClass?.simpleName)
        assertSame(missing, failure.cause?.cause)
        assertEquals(null, failure.metadata)
        assertEquals(eu.kanade.tachiyomi.network.SourceOperationPhase.METADATA, failure.phase)
        io.mockk.verify(exactly = 0) { source.client }
    }

    private fun candidate(repo: String, version: Long) = DesktopAvailableExtension(
        "Reader", "pkg.reader", "1.4.$version", version, lang = "en", isNsfw = false,
        jarUrl = "$repo/reader.jar", iconUrl = "", repoUrl = repo,
    )

    @Test
    fun `old sidecar defaults and new presentation metadata round trip`(@TempDir directory: File) {
        val jar = directory.resolve("reader.jar").also { it.writeText("jar") }
        directory.resolve("reader.meta.json").writeText(
            """{"pkgName":"reader","versionCode":1,"versionName":"1.4.1"}""",
        )
        val legacy = checkNotNull(readExtensionMeta(jar))
        assertEquals(listOf("reader", "", false), listOf(legacy.name, legacy.language, legacy.isNsfw))

        val current = ExtensionMeta(
            "reader",
            2,
            "1.4.2",
            name = "Reader",
            language = "en",
            isNsfw = true,
            source = ExtensionOrigin.CONVERTED_APK,
        )
        writeExtensionMeta(jar, current)
        assertEquals(current, readExtensionMeta(jar))
    }

    @Test
    fun `typed port preserves manager authority and repository failures`() = runTest {
        val failure = RepositoryCatalogFailure(
            RepositoryIdentity("https://failed.example", "Failed", "key"),
            AppError.Network(),
        )
        val repository = RepositoryIdentity("https://ok.example", "OK", "key")
        val artifact = ExtensionArtifact(
            "Reader", "pkg.reader", "1.4.2", 2, "en", false, emptyList(), repository,
            "https://ok.example/reader.jar", "", null,
        )
        val catalog = ExtensionCatalogResult(
            listOf(ExtensionCatalogEntry(artifact, ExtensionCompatibility.Compatible)),
            listOf(failure),
        )
        val installed = MutableStateFlow<List<InstalledExtension>>(emptyList())
        val available = DesktopAvailableExtension(
            "Reader", "pkg.reader", "1.4.2", 2, lang = "en", isNsfw = false,
            jarUrl = artifact.downloadUrl, iconUrl = "", repoUrl = repository.baseUrl,
        )
        val api = mockk<DesktopExtensionApi> {
            io.mockk.every { installArbiter } returns ExtensionInstallArbiter()
            coEvery { refreshCatalog() } returns catalog
        }
        val manager = mockk<DesktopExtensionManager>()
        every { api.availableExtensions(catalog) } returns listOf(available)
        every { api.discardTrust("request") } returns true
        val states = flowOf(ExtensionInstallState.Preparing)
        val start = DesktopExtensionInstallStart.Started(states)
        coEvery { api.beginInstall(available, manager) } returns start
        every { api.confirmTrust("request", manager) } returns states
        val port = DesktopExtensionPresentationPort(api, manager, installed)

        val refreshed = port.refresh()

        assertEquals(installed, port.installedExtensions)
        assertEquals(listOf(failure), refreshed.catalog.failures)
        assertEquals(listOf("pkg.reader"), refreshed.available.map(DesktopAvailableExtension::pkgName))
        assertSame(start, port.beginInstall(available))
        assertSame(states, port.confirmTrust("request"))
        assertEquals(true, port.discardTrust("request"))
    }
}
