package mihon.desktop.architecture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ReaderArchitectureGuardTest {

    private val repositoryRoot = repositoryRoot()

    @Test
    fun `reader migration legacy surface is closed`() {
        legacyReaderFiles.forEach { path ->
            assertFalse(Files.exists(repositoryRoot.resolve(path)), "Legacy reader file is reachable again: $path")
        }

        val compatibilitySources = desktopReaderProductionRoots
            .flatMap { sourceRoot -> kotlinSources(sourceRoot).entries }
            .associate { entry -> entry.key to entry.value } + readerStringResources.associateWith(::source)
        val violations = legacyReaderMarkers.flatMap { marker ->
            compatibilitySources.filterValues { marker in it }.keys.map { path -> "$path: $marker" }
        }

        assertEquals(emptyList<String>(), violations, "Legacy reader compatibility surface must stay empty")
    }

    @Test
    fun `reader core presentation and adapter dependency boundaries stay closed`() {
        assertNoMarkers(
            sourceRoot = "domain/src/commonMain/kotlin/mihon/domain/reader",
            forbiddenMarkers = corePlatformMarkers,
        )
        assertNoMarkers(
            sourceRoot = "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/presentation",
            forbiddenMarkers = presentationIoMarkers,
        )

        val decodeAdapter = source("app-desktop/src/main/kotlin/mihon/desktop/reader/PagePreloader.kt")
        assertTrue("encodedPageReader" in decodeAdapter)
        presentationIoMarkers.forEach { marker ->
            assertFalse(marker in decodeAdapter, "Decoded viewport adapter regained I/O dependency: $marker")
        }
        assertNoMarkers(platformReaderProductionRoots, legacyDecisionOwnerMarkers)

        val runtimeFactory = executableSource(
            source("app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderRuntimeFactory.kt"),
        )
        assertEquals(
            1,
            Regex("""\bPagePreloader\s*\(""").findAll(runtimeFactory).count(),
            "Desktop runtime must keep one decoded-only preloader owner",
        )
        assertTrue(
            "preloader = PagePreloader(encodedPageReader = store::read, windowSize = 3)" in
                runtimeFactory.replace(Regex("""\s+"""), " "),
            "Desktop runtime preloader must read only from its session encoded store",
        )

        assertSourceOwners("ReaderRequestScheduler(", schedulerOwners)
        assertSourceOwners("ReaderProgressPolicy.reduce(", progressPolicyOwners)
        assertSourceOwners("ReaderChapterWindowReducer.reduce(", chapterWindowOwners)
        assertSourceOwners("ReaderSessionReducer.reduce(", sessionReducerOwners)
        assertSourceOwners("PagePreloader(", preloaderOwners)
        assertMarkerOccurrenceCount("PagePreloader(", expectedCount = 2)
        decisionDeclarationOwners.forEach { (declaration, expectedPaths) ->
            assertRegexOwners(declaration, expectedPaths)
        }
        decisionAliasPatterns.forEach { alias -> assertRegexOwners(alias, emptySet()) }
    }

    @Test
    fun `parity manifest records RG01 cleanup and enforced reader guard`() {
        val capabilities = Json.parseToJsonElement(
            source("app-desktop/src/test/resources/parity/parity-manifest.json"),
        ).jsonArray.associateBy { item -> item.jsonObject.getValue("id").jsonPrimitive.content.toInt() }

        readerCapabilityIds.forEach { id ->
            val scope = capabilities.getValue(id).jsonObject.getValue("readerCoreMigrationScope").jsonObject
            assertEquals("RG-01", scope.getValue("legacyCleanupTask").jsonPrimitive.content, "ID $id cleanup task")
            assertEquals("REMOVED", scope.getValue("legacyReaderExecutors").jsonPrimitive.content, "ID $id legacy state")
            assertEquals("ENFORCED", scope.getValue("readerArchitectureGuard").jsonPrimitive.content, "ID $id guard state")
        }
    }

    private fun assertNoMarkers(sourceRoot: String, forbiddenMarkers: Set<String>) {
        val violations = kotlinSources(sourceRoot).flatMap { (path, content) ->
            forbiddenMarkers.filter { marker -> marker in content }.map { marker -> "$path: $marker" }
        }
        assertEquals(emptyList<String>(), violations, "Forbidden reader dependency crossed its boundary")
    }

    private fun assertNoMarkers(sourceRoots: List<String>, forbiddenMarkers: Set<String>) {
        val violations = sourceRoots
            .flatMap { sourceRoot -> kotlinSources(sourceRoot).entries }
            .flatMap { (path, content) ->
                forbiddenMarkers.filter { marker -> marker in content }.map { marker -> "$path: $marker" }
            }
        assertEquals(emptyList<String>(), violations, "Legacy or private reader decision owner is reachable again")
    }

    private fun assertSourceOwners(marker: String, expectedPaths: Set<String>) {
        val actualPaths = productionReaderRoots
            .flatMap { sourceRoot ->
                kotlinSources(sourceRoot).filterValues { content -> marker in executableSource(content) }.keys
            }
            .toSet()
        assertEquals(expectedPaths, actualPaths, "Unexpected production owner for $marker")
    }

    private fun assertRegexOwners(marker: Regex, expectedPaths: Set<String>) {
        val actualPaths = productionReaderRoots
            .flatMap { sourceRoot ->
                kotlinSources(sourceRoot).filterValues { content -> marker.containsMatchIn(executableSource(content)) }.keys
            }
            .toSet()
        assertEquals(expectedPaths, actualPaths, "Unexpected production decision declaration for ${marker.pattern}")
    }

    private fun assertMarkerOccurrenceCount(marker: String, expectedCount: Int) {
        val occurrence = Regex(Regex.escape(marker))
        val actualCount = productionReaderRoots.sumOf { sourceRoot ->
            kotlinSources(sourceRoot).values.sumOf { content -> occurrence.findAll(executableSource(content)).count() }
        }
        assertEquals(expectedCount, actualCount, "Unexpected production occurrence count for $marker")
    }

    private fun executableSource(content: String): String = content
        .replace(Regex("""(?s)\"\"\".*?\"\"\""""), "\"\"")
        .replace(Regex("""\"(?:\\.|[^\"\\])*\""""), "\"\"")
        .replace(Regex("""'(?:(?:\\.)|[^'\\])'"""), "''")
        .replace(Regex("""(?s)/\*.*?\*/"""), "")
        .replace(Regex("""(?m)//.*$"""), "")

    private fun kotlinSources(relativeRoot: String): Map<String, String> =
        repositoryRoot.resolve(relativeRoot).toFile().walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .associate { file ->
                file.relativeTo(repositoryRoot.toFile()).invariantSeparatorsPath to file.readText(Charsets.UTF_8)
            }

    private fun source(path: String): String = repositoryRoot.resolve(path).toFile().readText(Charsets.UTF_8)

    private fun repositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("domain")) }

    private companion object {
        val readerCapabilityIds = setOf(9, 43, 44, 45, 47, 49, 51, 53, 54)

        val legacyReaderFiles = setOf(
            "app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderPageLoader.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/presentation/LegacyDesktopReaderPresentationAdapter.kt",
        )

        val legacyReaderMarkers = setOf(
            "resolvedUrls",
            "navigator.replace(",
            "onContinue =",
            "onContinue:",
            "showContinue",
            "showDismiss",
            "desktop_ui_continue.localized",
            "desktop_ui_dismiss.localized",
            "<string name=\"desktop_ui_continue\">",
            "<string name=\"desktop_ui_dismiss\">",
        )

        val readerStringResources = setOf(
            "i18n/src/commonMain/moko-resources/base/strings.xml",
            "i18n/src/commonMain/moko-resources/zh-rCN/strings.xml",
            "i18n/src/commonMain/moko-resources/zh-rTW/strings.xml",
        )

        val legacyDecisionOwnerMarkers = setOf(
            "ReaderPreloadPlanner",
            "DesktopReaderPageLoader",
            "AdjacentChapterLoader",
            "TransitionLoadKey",
            "transitionLoadsInFlight",
            "resolvePageUrls(",
            "loadAdjacentChapter(",
            "loadChapterTransition(",
            "retryChapterTransition(",
            "setChapterTransitionState(",
            "PageLoadResult",
            "setLoadingPageSlots(",
            "appendLoadedPage(",
            "setLoadedPages(",
            "setLoadError(",
            "readerExitEventId",
            "readerProgressPageForTracking(",
            "fetcher: suspend (url: String) -> ByteArray?",
        )

        val corePlatformMarkers = setOf(
            "import android.",
            "import androidx.compose.",
            "import cafe.adriel.voyager.",
            "import coil.",
            "import mihon.desktop.",
            "import org.jetbrains.skia.",
            "ReadingMode.DUAL",
        )

        val presentationIoMarkers = setOf(
            "import io.ktor.client.",
            "import okhttp3.",
            "mihon.desktop.download.",
            "mihon.desktop.network.",
            "mihon.desktop.source.",
            ".repository.",
            "DesktopReaderMaterializePorts",
            "ReaderChapterContentPort",
            "ReaderPageFetchPort",
            "SourcePageFetcher",
        )

        val productionReaderRoots = listOf(
            "domain/src/commonMain/kotlin",
            "app/src/main/java",
            "app-desktop/src/main/kotlin",
        )

        val platformReaderProductionRoots = listOf(
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader",
            "app-desktop/src/main/kotlin/mihon/desktop/reader",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/reader",
        )

        val desktopReaderProductionRoots = listOf(
            "app-desktop/src/main/kotlin/mihon/desktop/reader",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/reader",
        )

        val schedulerOwners = setOf(
            "domain/src/commonMain/kotlin/mihon/domain/reader/scheduler/ReaderRequestScheduler.kt",
            "domain/src/commonMain/kotlin/mihon/domain/reader/session/ReaderSessionCore.kt",
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderRuntimeFactory.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/reader/PagePreloader.kt",
        )

        val progressPolicyOwners = setOf(
            "domain/src/commonMain/kotlin/mihon/domain/reader/session/ReaderSessionCore.kt",
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt",
        )

        val chapterWindowOwners = setOf(
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt",
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapterWindowOwner.kt",
        )

        val sessionReducerOwners = setOf(
            "domain/src/commonMain/kotlin/mihon/domain/reader/session/ReaderChapterWindow.kt",
            "domain/src/commonMain/kotlin/mihon/domain/reader/session/ReaderSessionCore.kt",
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapter.kt",
        )

        val preloaderOwners = setOf(
            "app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderRuntimeFactory.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/reader/PagePreloader.kt",
        )

        val decisionDeclarationOwners = mapOf(
            Regex("""\b(?:class|object)\s+Reader\w*RequestScheduler\b""") to setOf(
                "domain/src/commonMain/kotlin/mihon/domain/reader/scheduler/ReaderRequestScheduler.kt",
            ),
            Regex("""\b(?:class|object)\s+Reader\w*ProgressPolicy\b""") to setOf(
                "domain/src/commonMain/kotlin/mihon/domain/reader/progress/ReaderProgressPolicy.kt",
            ),
            Regex("""\b(?:class|object)\s+Reader\w*ChapterWindow(?:Reducer|Planner|Policy)\b""") to setOf(
                "domain/src/commonMain/kotlin/mihon/domain/reader/session/ReaderChapterWindow.kt",
            ),
            Regex("""\b(?:class|object)\s+Reader\w*SessionReducer\b""") to setOf(
                "domain/src/commonMain/kotlin/mihon/domain/reader/session/ReaderSession.kt",
            ),
            Regex("""\b(?:class|object)\s+Reader\w*(?:QueueCoordinator|CompletionPolicy)\b""") to emptySet(),
            Regex("""\bfun\s+reader\w*(?:Progress|Completion|ChapterWindow|RequestQueue)\w*\s*\(""") to emptySet(),
        )

        val decisionAliasPatterns = setOf(
            Regex(
                """\bimport\s+(?:mihon\.domain\.reader\.(?:scheduler|progress|session)|mihon\.desktop\.reader)\.""" +
                    """(?:ReaderRequestScheduler|ReaderProgressPolicy|ReaderChapterWindowReducer|ReaderSessionReducer|PagePreloader)\s+as\s+\w+\b""",
            ),
            Regex(
                """\btypealias\s+\w+\s*=\s*(?:(?:mihon\.domain\.reader\.(?:scheduler|progress|session)|""" +
                    """mihon\.desktop\.reader)\.)?(?:ReaderRequestScheduler|ReaderProgressPolicy|""" +
                    """ReaderChapterWindowReducer|ReaderSessionReducer|PagePreloader)\b""",
            ),
        )
    }
}
