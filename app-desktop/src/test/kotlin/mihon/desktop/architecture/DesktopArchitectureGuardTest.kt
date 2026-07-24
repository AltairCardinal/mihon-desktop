package mihon.desktop.architecture

import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.ui.library.LibraryScreenModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetLibraryManga
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import java.util.spi.ToolProvider

class DesktopArchitectureGuardTest {

    private val repoRoot = File(System.getProperty("user.dir")).parentFile

    @Test
    fun `desktop ui DI and repository debt does not grow beyond baseline`() {
        val uiDir = File(repoRoot, "app-desktop/src/main/kotlin/mihon/desktop/ui")
        val violations = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val pattern = forbiddenUiPatterns.firstOrNull { it.containsMatchIn(line) }
                    if (pattern == null) {
                        null
                    } else {
                        "${file.relativeTo(repoRoot).path}:${index + 1}: ${line.trim()}"
                    }
                }
            }
            .toList()

        val unexpectedFiles = violations
            .map { it.substringBefore(":") }
            .filterNot { it in desktopUiDebtFiles }
            .distinct()

        assertTrue(
            unexpectedFiles.isEmpty(),
            "Desktop UI dependency debt appeared in new files:\n${unexpectedFiles.joinToString("\n")}",
        )
        assertTrue(
            violations.size <= DESKTOP_UI_DEPENDENCY_DEBT_BASELINE,
            "Desktop UI dependency debt increased. Baseline=$DESKTOP_UI_DEPENDENCY_DEBT_BASELINE actual=${violations.size}\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `large desktop ui files do not grow beyond debt baseline`() {
        val currentLineCounts = desktopUiLineDebtBaseline.mapValues { (path, _) ->
            File(repoRoot, path).readLines().size
        }
        val violations = currentLineCounts.filter { (path, count) ->
            count > desktopUiLineDebtBaseline.getValue(path)
        }

        assertTrue(
            violations.isEmpty(),
            "Large Desktop UI files grew beyond baseline:\n" +
                violations.entries.joinToString("\n") { (path, count) ->
                    "$path baseline=${desktopUiLineDebtBaseline.getValue(path)} actual=$count"
                },
        )
    }

    @Test
    fun `desktop startup and temporary path debt does not grow beyond baseline`() {
        val desktopDir = File(repoRoot, "app-desktop/src/main/kotlin")
        val runBlockingDebt = countMatches(desktopDir, Regex("""\brunBlocking\b"""))
        val temporaryPathDebt = countMatches(desktopDir, Regex(""""(/tmp|/private/tmp|/Applications/)"""))

        assertTrue(
            runBlockingDebt <= RUN_BLOCKING_DEBT_BASELINE,
            "Desktop runBlocking debt increased. Baseline=$RUN_BLOCKING_DEBT_BASELINE actual=$runBlockingDebt",
        )
        assertTrue(
            temporaryPathDebt <= TEMPORARY_PATH_DEBT_BASELINE,
            "Desktop hard-coded path debt increased. Baseline=$TEMPORARY_PATH_DEBT_BASELINE actual=$temporaryPathDebt",
        )
    }

    @Test
    fun `android main source must not depend on desktop runtime or awt swing`() {
        val androidMain = File(repoRoot, "app/src/main")
        val violations = androidMain.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val forbidden = androidForbiddenPatterns.firstOrNull { it.containsMatchIn(line) }
                    if (forbidden == null) {
                        null
                    } else {
                        "${file.relativeTo(repoRoot).path}:${index + 1}: ${line.trim()}"
                    }
                }
            }
            .toList()

        assertTrue(
            violations.isEmpty(),
            "Android main source must not import Desktop runtime or AWT/Swing:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `compiled production UI dependency graph matches finite violation inventory`() {
        val edges = compiledEdges(LibraryScreenModel::class.java).map(CompiledEdge::canonical).toSet()
        requiredProductionEdges.forEach { edge ->
            assertTrue(edge in edges, "Missing compiled production edge $edge")
        }
        val violations = edges.filter { it.origin.startsWith("mihon.desktop.ui.") && it.isForbiddenUiDependency() }.toSet()

        assertEquals(acknowledgedProductionViolations, violations)
    }

    @Test
    fun `compiled fixtures reject a forbidden manager and a disconnected use case`() {
        val edges = compiledEdges(DesktopArchitectureGuardTest::class.java)
        val legalEdge = CompiledEdge("${DesktopArchitectureGuardTest::class.java.name}\$LegalUseCaseFixture", GetLibraryManga::class.java.name)
        assertRequiredEdge(edges, legalEdge)
        val disconnectedOrigin = "${DesktopArchitectureGuardTest::class.java.name}\$DisconnectedUseCaseFixture"
        assertThrows(AssertionError::class.java) {
            assertRequiredEdge(edges.filterTo(mutableSetOf()) { it.origin == disconnectedOrigin }, legalEdge.copy(origin = disconnectedOrigin))
        }
        val forbiddenEdge = CompiledEdge("${DesktopArchitectureGuardTest::class.java.name}\$ForbiddenManagerFixture", DesktopExtensionManager::class.java.name)
        assertTrue(forbiddenEdge in edges)
        assertThrows(AssertionError::class.java) { assertNoForbiddenEdges(setOf(forbiddenEdge)) }
    }

    @Test
    fun `platform adapter allowlist requires compiled edges and nonblank reasons`() {
        val edges = compiledEdges(LibraryScreenModel::class.java).map(CompiledEdge::canonical).toSet()
        assertPlatformAdapterAllowlist(edges, permittedPlatformAdapters)

        val blankReason = permittedPlatformAdapters.toMutableMap().also { it[it.keys.first()] = "" }
        assertThrows(AssertionError::class.java) { assertPlatformAdapterAllowlist(edges, blankReason) }
        assertThrows(AssertionError::class.java) {
            assertPlatformAdapterAllowlist(edges, permittedPlatformAdapters + (CompiledEdge("missing.Origin", "missing.Adapter") to "reason"))
        }
    }

    private fun compiledEdges(anchor: Class<*>): Set<CompiledEdge> {
        val output = StringWriter()
        val errors = StringWriter()
        val location = Path.of(anchor.protectionDomain.codeSource.location.toURI()).toString()
        val jdeps = checkNotNull(ToolProvider.findFirst("jdeps").orElse(null)) { "The running JDK must provide jdeps" }
        val exitCode = jdeps.run(
            PrintWriter(output), PrintWriter(errors), "--multi-release", "base", "-verbose:class", "-filter:none", "-recursive", location,
        )
        assertEquals(0, exitCode, errors.toString())
        return dependencyLine.findAll(output.toString()).map { CompiledEdge(it.groupValues[1], it.groupValues[2]) }.toSet()
    }

    private fun assertRequiredEdge(edges: Set<CompiledEdge>, required: CompiledEdge) =
        assertTrue(required in edges, "Missing required compiled edge $required")

    private fun assertNoForbiddenEdges(edges: Set<CompiledEdge>) {
        val forbidden = edges.filter(CompiledEdge::isForbiddenUiDependency)
        assertTrue(forbidden.isEmpty(), "Forbidden compiled dependencies: $forbidden")
    }

    private fun assertPlatformAdapterAllowlist(edges: Set<CompiledEdge>, allowlist: Map<CompiledEdge, String>) {
        allowlist.forEach { (edge, reason) ->
            assertTrue(reason.isNotBlank(), "Platform adapter $edge requires a nonblank reason")
            assertTrue(edge in edges, "Platform adapter allowlist entry is not a compiled production edge: $edge")
        }
    }

    private fun countMatches(root: File, pattern: Regex): Int {
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sumOf { file -> file.readLines().count { pattern.containsMatchIn(it) } }
    }

    private data class CompiledEdge(val origin: String, val target: String) {
        fun canonical() = CompiledEdge(origin.substringBefore('$'), target.removeSuffix("\$Companion"))

        fun isForbiddenUiDependency(): Boolean {
            return repositoryType.matches(target) ||
                dataQueryType.matches(target) ||
                target.startsWith("okhttp3.") ||
                target.startsWith("io.ktor.client.") ||
                target in forbiddenConcreteTypes
        }
    }

    private class LegalUseCaseFixture(@Suppress("unused") val useCase: GetLibraryManga)
    private class DisconnectedUseCaseFixture
    private class ForbiddenManagerFixture(@Suppress("unused") val manager: DesktopExtensionManager)

    private companion object {
        const val DESKTOP_UI_DEPENDENCY_DEBT_BASELINE = 0
        const val RUN_BLOCKING_DEBT_BASELINE = 0
        const val TEMPORARY_PATH_DEBT_BASELINE = 0

        val forbiddenUiPatterns = listOf(
            Regex("""\bInjekt\.get<"""),
            Regex("""\b[a-zA-Z0-9_]*(manga|chapter|category|source|history|updates)Repository\."""),
        )

        val androidForbiddenPatterns = listOf(
            Regex("""^\s*import\s+mihon\.desktop\."""),
            Regex("""^\s*import\s+java\.awt\."""),
            Regex("""^\s*import\s+javax\.swing\."""),
        )

        val desktopUiDebtFiles = setOf(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/home/HomeScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/browse/LocalSourceSettingsScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/more/StatsScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/history/HistoryTab.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/AdvancedSettingsScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/authors/AuthorsTab.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/browse/GlobalSearchScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/browse/SourceBrowseScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/extension/SourcePreferencesScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryTab.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/browse/BrowseTab.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/updates/UpcomingScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/browse/LocalBrowseScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/updates/UpdatesTab.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/ReaderSettingsScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/migration/MigrationSearchScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/extension/ExtensionListScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/migration/MigrationSourceScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/BackupSettingsScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/DownloadSettingsScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/GeneralSettingsScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/LibrarySettingsScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/MoreRootScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/migration/MigrationMangaScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/AppearanceSettingsScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/library/DesktopMangaNotesScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/ExtensionRepoScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/AboutScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/theme/DesktopTheme.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/download/DownloadQueueScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DesktopReaderScreen.kt",
        )

        val desktopUiLineDebtBaseline = mapOf(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailScreen.kt" to 907,
            "app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailComponents.kt" to 710,
            "app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryTab.kt" to 493,
            "app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryComponents.kt" to 710,
            "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DesktopReaderScreen.kt" to 677,
        )

        val dependencyLine = Regex("""(?m)^\s*(\S+)\s+->\s+(\S+)""")
        val repositoryType = Regex("""^(tachiyomi|mihon)\.domain\..*\.repository\..+$""")
        val dataQueryType = Regex("""^tachiyomi\.data\..*\.db\..+$""")
        val forbiddenConcreteTypes =
            setOf(
                "java.lang.ClassLoader",
                "java.net.URLClassLoader",
                "mihon.desktop.download.DesktopDownloadManager",
                "mihon.desktop.extension.DesktopExtensionManager",
                "mihon.desktop.network.CloudflareChallengeManager",
                "mihon.desktop.platform.DesktopNetworkHelper",
                "eu.kanade.tachiyomi.extension.ExtensionManager",
            )
        val requiredProductionEdges =
            setOf(
                CompiledEdge("mihon.desktop.source.MangaDexSource", "mihon.domain.network.NetworkErrorMapperKt"),
                CompiledEdge("mihon.desktop.di.DesktopAppModuleKt", "mihon.desktop.platform.DesktopNetworkHelper"),
                CompiledEdge("mihon.desktop.ui.library.LibraryScreenModel", "tachiyomi.domain.manga.interactor.GetLibraryManga"),
            )
        val permittedPlatformAdapters =
            mapOf(
                CompiledEdge("mihon.desktop.ui.ExternalActionNavigator", "mihon.desktop.platform.DesktopExternalActionTarget") to
                    "OS URI ingress is a Desktop navigation adapter with typed rejection feedback.",
                CompiledEdge("mihon.desktop.ui.library.MangaDetailComponentsKt", "mihon.desktop.platform.DesktopShareService") to
                    "Host share is an OS side-effect port with structured success, fallback and failure results.",
            )
        val acknowledgedProductionViolations =
            """
            mihon.desktop.ui.authors.AuthorDetailScreen -> tachiyomi.domain.creator.repository.CreatorRepository;mihon.desktop.ui.authors.AuthorsRootScreen -> tachiyomi.domain.creator.repository.CreatorRepository;mihon.desktop.ui.authors.WorkCompareScreen -> tachiyomi.domain.creator.repository.CreatorRepository;mihon.desktop.ui.library.LibraryScreenModel -> tachiyomi.domain.category.repository.CategoryRepository
            mihon.desktop.ui.library.LibraryScreenModel -> tachiyomi.domain.chapter.repository.ChapterRepository;mihon.desktop.ui.library.LibraryScreenModel -> tachiyomi.domain.manga.repository.MangaRepository;mihon.desktop.ui.library.LibraryScreenModel -> tachiyomi.domain.track.repository.TrackRepository;mihon.desktop.ui.library.MangaDetailScreenModel -> tachiyomi.domain.category.repository.CategoryRepository
            mihon.desktop.ui.library.MangaDetailScreenModel -> tachiyomi.domain.chapter.repository.ChapterRepository;mihon.desktop.ui.library.MangaDetailScreenModel -> tachiyomi.domain.creator.repository.CreatorRepository;mihon.desktop.ui.library.MangaDetailScreenModel -> tachiyomi.domain.manga.repository.LibraryMembershipRepository;mihon.desktop.ui.library.MangaDetailScreenModel -> tachiyomi.domain.manga.repository.MangaRepository
            mihon.desktop.ui.tracking.TrackingScreenModel -> tachiyomi.domain.track.repository.TrackRepository;mihon.desktop.ui.tracking.TrackingSettingsScreen -> tachiyomi.domain.track.repository.TrackRepository;mihon.desktop.ui.browse.DesktopSourceCookieHeaderParser -> okhttp3.HttpUrl;mihon.desktop.ui.browse.DesktopSourceLastUsedRecorder -> mihon.desktop.extension.DesktopExtensionManager
            mihon.desktop.ui.browse.DesktopSourceLoginController -> okhttp3.HttpUrl;mihon.desktop.ui.browse.DesktopSourceLoginUiActions -> okhttp3.HttpUrl;mihon.desktop.ui.browse.SourceBrowseScreen -> mihon.desktop.extension.DesktopExtensionManager;mihon.desktop.ui.cloudflare.DesktopChallengeLoginController -> mihon.desktop.network.CloudflareChallengeManager
            mihon.desktop.ui.cloudflare.DesktopChallengeLoginController -> okhttp3.HttpUrl;mihon.desktop.ui.extension.DesktopExtensionPresentationPort -> mihon.desktop.extension.DesktopExtensionManager;mihon.desktop.ui.extension.ExtensionDetailsScreen -> mihon.desktop.platform.DesktopNetworkHelper;mihon.desktop.ui.extension.SourcePreferencesScreen -> mihon.desktop.extension.DesktopExtensionManager
            mihon.desktop.ui.extension.SourcePreferencesScreenKt -> java.lang.ClassLoader;mihon.desktop.ui.home.HomeScreen -> mihon.desktop.network.CloudflareChallengeManager;mihon.desktop.ui.library.LibraryRootScreen -> mihon.desktop.download.DesktopDownloadManager;mihon.desktop.ui.settings.AboutScreen -> mihon.desktop.extension.DesktopExtensionManager
            mihon.desktop.ui.settings.AdvancedSettingsScreen -> mihon.desktop.platform.DesktopNetworkHelper;mihon.desktop.ui.settings.AdvancedSettingsScreen -> okhttp3.HttpUrl;mihon.desktop.ui.settings.AdvancedSettingsScreenKt -> okhttp3.HttpUrl;mihon.desktop.ui.settings.MoreRootScreen -> mihon.desktop.download.DesktopDownloadManager
            """.trimIndent()
                .split(';', '\n')
                .filter(String::isNotBlank)
                .map { edge -> edge.trim().split(" -> ").let { CompiledEdge(it[0], it[1]) } }
                .toSet()
    }
}
