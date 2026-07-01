package mihon.desktop.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

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

    private fun countMatches(root: File, pattern: Regex): Int {
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sumOf { file -> file.readLines().count { pattern.containsMatchIn(it) } }
    }

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
            "app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailComponents.kt" to 707,
            "app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryTab.kt" to 452,
            "app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryComponents.kt" to 684,
            "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DesktopReaderScreen.kt" to 677,
        )
    }
}
