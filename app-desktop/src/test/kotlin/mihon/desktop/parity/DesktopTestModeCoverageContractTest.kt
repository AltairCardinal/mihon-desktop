package mihon.desktop.parity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopTestModeCoverageContractTest {

    @Test
    fun `inventory closes exactly thirteen scenarios five protections and every capability`() {
        val inventory = inventory()
        validate(inventory, compiledHandlers(inventory), compiledRunners(inventory))
    }

    @Test
    fun `inventory mutations reject status tuple wiring mapping protection and child boundary drift`() {
        val inventory = inventory()
        val handlers = compiledHandlers(inventory)
        val runners = compiledRunners(inventory)
        val plan = childPlan(inventory)
        val firstScenario = inventory.scenarios.first()
        val firstProtection = inventory.protections.first()
        val gapScenario = inventory.scenarios.first { it.status == "gap" }
        val coveredScenario = inventory.scenarios.first { it.status == "covered" }
        val nonUiBoundary = inventory.boundaries.first { it.status == "non-ui" }
        fun rejects(
            changed: Inventory = inventory,
            changedHandlers: Map<String, String> = handlers,
            changedRunners: Map<String, String> = runners,
            changedPlan: String = plan,
        ) = assertThrows(AssertionError::class.java) { validate(changed, changedHandlers, changedRunners, changedPlan) }

        rejects(inventory.replace(gapScenario, gapScenario.copy(status = "covered")), handlers + (gapScenario.id to "NONE"), runners + (gapScenario.id to "NONE"))
        rejects(inventory.replace(coveredScenario, coveredScenario.copy(status = "gap")))
        rejects(inventory.replace(nonUiBoundary, nonUiBoundary.copy(status = "covered")))
        rejects(inventory.replace(firstProtection, firstProtection.copy(status = "gap")))
        rejects(inventory.replace(coveredScenario, coveredScenario.copy(entryPoint = "wrong")))
        rejects(inventory.replace(firstScenario, firstScenario.copy(capabilityIds = firstScenario.capabilityIds.drop(1))))
        rejects(inventory.copy(scenarios = inventory.scenarios + firstScenario.copy(id = "duplicate-family")))
        rejects(inventory.replace(firstScenario, firstScenario.copy(family = "unknown")))
        rejects(changedHandlers = handlers - coveredScenario.id)
        rejects(changedRunners = runners - coveredScenario.id)
        rejects(inventory.copy(protections = inventory.protections - firstProtection))
        rejects(changedPlan = plan.replace(task173Dependency, "depends on unfinished product work"))
        rejects(changedPlan = plan.replace(task173Files, "**Files:** `domain/src/**` and product owners."))
    }

    private fun validate(
        inventory: Inventory,
        handlers: Map<String, String>,
        runners: Map<String, String>,
        plan: String = childPlan(inventory),
    ) {
        assertEquals(requiredFamilies.size, inventory.scenarios.size)
        assertEquals(requiredFamilies, inventory.scenarios.mapNotNull(Entry::family).toSet())
        assertEquals(expectedScenarioStatuses, inventory.scenarios.associate { requireNotNull(it.family) to it.status })
        assertEquals(expectedBoundaries, inventory.boundaries.associate { it.id to (it.status to it.capabilityIds.toSet()) })
        assertEquals(
            listOf(
                "scripts/desktop-final-parity-test.sh",
                "scripts/desktop-final-parity-test.sh",
                "exact family protection capability counts plus actionable artifact startup timeout and schema failures",
                "mihon.test.desktop.DesktopFinalParityRunnerContractTest#fake process is polled summarized exactly and always torn down",
            ),
            inventory.boundaries.single { it.id == "gap-final-runtime-runner" }.tuple(),
        )
        assertEquals(requiredProtections, inventory.protections.map(Entry::id).toSet())
        assertEquals(requiredProtections.associateWith { "covered" }, inventory.protections.associate { it.id to it.status })
        assertEquals((inventory.allEntries).size, inventory.allEntries.map(Entry::id).toSet().size)

        val mappedIds = (inventory.scenarios + inventory.boundaries).flatMap(Entry::capabilityIds)
        assertEquals(mappedIds.size, mappedIds.toSet().size, "capability IDs must map exactly once")
        assertEquals(manifestIds(), mappedIds.toSet(), "Test Mode inventory has unmapped capability IDs")
        coveredTuples.forEach { (family, expected) ->
            assertEquals(expected, inventory.scenarios.single { it.family == family }.tuple(), "$family witness tuple drift")
        }
        inventory.allEntries.forEach { entry ->
            assertTrue(entry.entryPoint.isNotBlank() && entry.productionHandler.isNotBlank())
            assertTrue(entry.observableFeedback.isNotBlank() && entry.runnerTest.isNotBlank() && entry.reason.isNotBlank())
            if (entry.status != "gap") {
                assertEquals(entry.productionHandler, handlers[entry.id], "${entry.id} compiled handler is disconnected")
                assertEquals(entry.runnerTest, runners[entry.id], "${entry.id} compiled runner is disconnected")
            }
        }
        val task173 = plan.substringAfter("### Task 173 ").substringBefore("### Task 174 ")
        assertTrue(task173Dependency in task173 && task173Files in task173)
        assertTrue(forbiddenTask173Files.none(task173::contains), "Task173 crosses the Desktop TestMode boundary")
    }

    private fun inventory(): Inventory {
        val root = Json.parseToJsonElement(Files.readString(repositoryRoot.resolve(inventoryPath))).jsonObject
        fun entries(field: String, familyRequired: Boolean) = root.getValue(field).jsonArray.map {
            val value = it.jsonObject
            Entry(
                id = value.text("id"),
                family = value["family"]?.jsonPrimitive?.content,
                capabilityIds = value.getValue("capabilityIds").jsonArray.map { id -> id.jsonPrimitive.content.toInt() },
                entryPoint = value.text("entryPoint"),
                productionHandler = value.text("productionHandler"),
                observableFeedback = value.text("observableFeedback"),
                runnerTest = value.text("runnerTest"),
                status = value.text("status"),
                reason = value.text("reason"),
            ).also { entry -> if (familyRequired) assertTrue(entry.family != null) }
        }
        return Inventory(
            scenarios = entries("scenarios", true),
            boundaries = entries("boundaries", false),
            protections = entries("permanentProtections", false),
            childPlan = root.text("childPlan"),
        )
    }

    private fun compiledHandlers(inventory: Inventory) = inventory.allEntries.mapNotNull { entry ->
        runCatching { Class.forName(entry.productionHandler); entry.id to entry.productionHandler }.getOrNull()
    }.toMap()

    private fun compiledRunners(inventory: Inventory) = inventory.allEntries.mapNotNull { entry ->
        val parts = entry.runnerTest.split("#", limit = 2)
        if (parts.size == 2 && runCatching { Class.forName(parts[0]).declaredMethods.any { it.name == parts[1] } }.getOrDefault(false)) entry.id to entry.runnerTest else null
    }.toMap()

    private fun childPlan(inventory: Inventory) = Files.readString(repositoryRoot.resolve(inventory.childPlan))

    private fun manifestIds(): Set<Int> {
        val root = Json.parseToJsonElement(Files.readString(repositoryRoot.resolve(manifestPath))).jsonArray
        return root.map { it.jsonObject.getValue("id").jsonPrimitive.content.toInt() }.toSet()
    }

    private fun JsonObject.text(field: String) = getValue(field).jsonPrimitive.content

    private data class Entry(
        val id: String,
        val family: String?,
        val capabilityIds: List<Int>,
        val entryPoint: String,
        val productionHandler: String,
        val observableFeedback: String,
        val runnerTest: String,
        val status: String,
        val reason: String,
    ) {
        fun tuple() = listOf(entryPoint, productionHandler, observableFeedback, runnerTest)
    }

    private data class Inventory(
        val scenarios: List<Entry>,
        val boundaries: List<Entry>,
        val protections: List<Entry>,
        val childPlan: String,
    ) {
        val allEntries get() = scenarios + boundaries + protections

        fun replace(old: Entry, new: Entry) = copy(
            scenarios = scenarios.map { if (it == old) new else it },
            boundaries = boundaries.map { if (it == old) new else it },
            protections = protections.map { if (it == old) new else it },
        )
    }

    private companion object {
        val repositoryRoot: Path = Path.of(System.getProperty("user.dir")).parent
        const val inventoryPath = "app-desktop/src/test/resources/parity/test-mode-coverage-inventory.json"
        const val manifestPath = "app-desktop/src/test/resources/parity/parity-manifest.json"
        val requiredFamilies = setOf(
            "library", "manga-detail", "browse-global-search-source-login", "extensions", "reader", "downloads",
            "updates-upcoming", "history", "migration", "backup-restore", "settings-platform", "tracking", "about",
        )
        val requiredProtections = setOf("authors-entry", "upcoming", "dual-page", "auto-scroll", "apk-to-jar")
        val coveredTuples = mapOf(
            "library" to listOf("POST /test/action/search|filter|sort|select", "mihon.desktop.test.http.LibraryMangaTestModeController", "HTTP status plus serialized production library rows and typed failure code", "mihon.desktop.test.http.LibraryMangaTestModeHttpTest#library filter sort and selection execute production state and expose rows"),
            "manga-detail" to listOf("POST /test/action/open_manga_detail|addToLibrary|removeFromLibrary|detail_categories|detail_chapter|detail_cover|download", "mihon.desktop.test.http.LibraryMangaTestModeController", "HTTP status plus serialized production manga detail mutations partial failures and load state", "mihon.desktop.test.http.LibraryMangaTestModeHttpTest#manga detail HTTP actions publish production mutations"),
            "extensions" to listOf("POST /test/action/extension_*", "mihon.desktop.test.http.SourceExtensionTestModeController", "HTTP status plus serialized production extension snapshot and failure code", "mihon.desktop.test.http.SourceExtensionTestModeHttpTest#http extension actions execute production state and serialize dynamic errors safely"),
            "reader" to listOf("POST /test/action/read_chapter then /test/reader/*", "mihon.desktop.test.navigation.TestNavigationController", "reader state endpoint page bounds close state and action history", "mihon.desktop.smoke.ReaderScenarioSmokeTestSuite#read chapter action opens reader state"),
            "migration" to listOf("POST /test/action/migration_*", "mihon.desktop.migration.DesktopBatchMigrationController", "migrationQueueCount and persistent queue state transitions", "mihon.desktop.migration.DesktopBatchMigrationTestModeTest#test mode migration actions drive persistent queue"),
            "about" to listOf("POST /test/action/update_* and GET /test/state", "mihon.desktop.ui.settings.DesktopUpdateScreenModel", "HTTP status plus updateStatus progress and release page", "mihon.desktop.test.http.DesktopPlatformTestModeControllerTest#update routes expose production state and reject illegal transitions"),
        )
        val expectedScenarioStatuses = requiredFamilies.associateWith { if (it in coveredTuples) "covered" else "gap" }
        val expectedBoundaries = mapOf(
            "boundary-shared-state" to ("gap" to setOf(3)), "boundary-di" to ("non-ui" to setOf(4)),
            "boundary-network-errors" to ("non-ui" to setOf(8)), "boundary-background-tasks" to ("non-ui" to setOf(10)),
            "boundary-notifications" to ("non-ui" to setOf(11)), "boundary-crash-handler" to ("non-ui" to setOf(12)),
            "gap-final-runtime-runner" to ("gap" to emptySet()),
        )
        const val task173Dependency = "depends on completed Task141 and Task142 artifacts"
        const val task173Files = "**Files:** Desktop TestMode/HTTP code under `app-desktop/src/main/kotlin/mihon/desktop/test/**`, plus HTTP/coverage tests under `app-desktop/src/test/kotlin/mihon/desktop/test/**` and `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopTestModeCoverageContractTest.kt` only."
        val forbiddenTask173Files = listOf("`app/src/", "`domain/src/", "shared state core", "Android and Desktop browse owners", "product owners")
    }
}
