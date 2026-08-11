package mihon.desktop.parity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AuthorActionInventoryContractTest {

    @Test
    fun `author actions report the prototype as partial or gap with executable evidence boundaries`() {
        validate(inventory())
    }

    @Test
    fun `inventory rejects false completion missing follow up and unsafe decision feedback`() {
        val inventory = inventory()
        val index = inventory.actions.single { it.id == "index" }
        val confirm = inventory.actions.single { it.id == "confirm" }

        inventory.actions.forEach { action ->
            assertThrows(AssertionError::class.java) {
                validateAction(action.copy(status = "covered"))
            }
        }
        assertThrows(AssertionError::class.java) {
            validateAction(index.copy(followUpTasks = emptyList()))
        }
        val executableRole = index.entry
        val unsafeCoveredDecision = confirm.copy(
            status = "covered",
            entry = executableRole,
            effect = executableRole,
            feedback = executableRole,
        )
        assertThrows(AssertionError::class.java) {
            validateAction(unsafeCoveredDecision)
        }
        validateAction(unsafeCoveredDecision.copy(reversal = executableRole))
    }

    private fun validate(inventory: Inventory) {
        assertEquals(1, inventory.schemaVersion)
        assertEquals(expectedDesiredRedIds, inventory.desiredBehaviorBaselines.map { it.id }.toSet())
        inventory.desiredBehaviorBaselines.forEach { baseline ->
            assertEquals("recorded-red", baseline.state)
            assertTrue(baseline.coordinatorKey.isNotBlank())
            assertTrue(baseline.test.isNotBlank() && baseline.failureReason.isNotBlank())
        }
        assertEquals(expectedStatuses, inventory.actions.associate { it.id to it.status })
        assertEquals(expectedStatuses.keys, inventory.actions.map { it.id }.toSet())
        assertEquals(inventory.actions.size, inventory.actions.map { it.id }.toSet().size)

        inventory.actions.forEach(::validateAction)
    }

    private fun validateAction(action: Action) {
        val requiredRoles = listOf(action.entry, action.effect, action.feedback)
        requiredRoles.forEach(::validateRole)
        validateRole(action.confirmation)
        validateRole(action.reversal)
        assertEquals("recorded-red", action.redState, "${action.id} has no recorded RED baseline")
        assertTrue(action.redTest.isNotBlank() && action.redFailureReason.isNotBlank())

        when (action.status) {
            "covered" -> assertTrue(requiredRoles.all { it.state == "covered" })
            "partial" -> {
                assertTrue(requiredRoles.any { it.state == "covered" || it.state == "unverified" })
                assertTrue(requiredRoles.any { it.state != "covered" })
                assertTrue(action.followUpTasks.isNotEmpty() && action.gapReason.isNotBlank())
            }
            "gap" -> {
                assertTrue(requiredRoles.any { it.state == "missing" })
                assertTrue(action.followUpTasks.isNotEmpty() && action.gapReason.isNotBlank())
            }
            else -> throw AssertionError("${action.id} has unsupported status ${action.status}")
        }

        if (action.id in decisionActions && action.status == "covered") {
            assertTrue(
                action.confirmation.state == "covered" || action.reversal.state == "covered",
                "${action.id} needs executable confirmation or reversible feedback before it is covered",
            )
        }

    }

    private fun validateRole(role: RoleEvidence) {
        assertTrue(role.state in allowedRoleStates)
        when (role.state) {
            "covered" -> {
                assertTrue(role.productionEdge.isNotBlank())
                val (className, methodName) = role.runnerTest.split("#", limit = 2).also { assertEquals(2, it.size) }
                val method = Class.forName(className).declaredMethods.singleOrNull { it.name == methodName }
                assertNotNull(method, "$role runner is not compiled")
                assertTrue(method!!.isAnnotationPresent(Test::class.java), "$role runner is not a JUnit test")
                assertEquals(Void.TYPE, method.returnType, "$role runner is not JUnit-discoverable")
            }
            "unverified" -> assertTrue(role.productionEdge.isNotBlank())
            "missing" -> assertTrue(role.productionEdge.isBlank() && role.runnerTest.isBlank())
            "not-applicable" -> assertTrue(role.productionEdge.isNotBlank() && role.runnerTest.isBlank())
        }
    }

    private fun inventory(): Inventory {
        val root = Json.parseToJsonElement(Files.readString(repositoryRoot.resolve(inventoryPath))).jsonObject
        val actions = root.getValue("actions").jsonArray.map { element ->
            val value = element.jsonObject
            fun role(name: String): RoleEvidence {
                val evidence = value.getValue(name).jsonObject
                return RoleEvidence(
                    state = evidence.text("state"),
                    productionEdge = evidence.text("productionEdge"),
                    runnerTest = evidence.text("runnerTest"),
                )
            }
            val red = value.getValue("redBaseline").jsonObject
            Action(
                id = value.text("id"),
                status = value.text("status"),
                followUpTasks = value.getValue("followUpTasks").jsonArray.map { it.jsonPrimitive.content },
                gapReason = value.text("gapReason"),
                entry = role("ENTRY"),
                effect = role("EFFECT"),
                feedback = role("FEEDBACK"),
                confirmation = role("CONFIRMATION"),
                reversal = role("REVERSAL"),
                redState = red.text("state"),
                redTest = red.text("test"),
                redFailureReason = red.text("failureReason"),
            )
        }
        return Inventory(
            schemaVersion = root.getValue("schemaVersion").jsonPrimitive.content.toInt(),
            desiredBehaviorBaselines = root.getValue("desiredBehaviorBaselines").jsonArray.map { element ->
                val value = element.jsonObject
                DesiredBehaviorBaseline(
                    id = value.text("id"),
                    state = value.text("state"),
                    coordinatorKey = value.text("coordinatorKey"),
                    test = value.text("test"),
                    failureReason = value.text("failureReason"),
                )
            },
            actions = actions,
        )
    }

    private fun JsonObject.text(field: String) = getValue(field).jsonPrimitive.content

    private data class RoleEvidence(
        val state: String,
        val productionEdge: String,
        val runnerTest: String,
    )

    private data class Action(
        val id: String,
        val status: String,
        val followUpTasks: List<String>,
        val gapReason: String,
        val entry: RoleEvidence,
        val effect: RoleEvidence,
        val feedback: RoleEvidence,
        val confirmation: RoleEvidence,
        val reversal: RoleEvidence,
        val redState: String,
        val redTest: String,
        val redFailureReason: String,
    )

    private data class Inventory(
        val schemaVersion: Int,
        val desiredBehaviorBaselines: List<DesiredBehaviorBaseline>,
        val actions: List<Action>,
    )

    private data class DesiredBehaviorBaseline(
        val id: String,
        val state: String,
        val coordinatorKey: String,
        val test: String,
        val failureReason: String,
    )

    private companion object {
        val repositoryRoot: Path = Path.of(System.getProperty("user.dir")).parent
        const val inventoryPath = "app-desktop/src/test/resources/parity/author-action-inventory.json"
        val allowedRoleStates = setOf("covered", "unverified", "missing", "not-applicable")
        val decisionActions = setOf("confirm", "reject", "language-override")
        val expectedDesiredRedIds = setOf(
            "authors-navigation",
            "manual-discovery-call",
            "review-preserving-upsert",
            "late-notification-collector",
            "compare-route",
            "decision-safety-gate",
        )
        val expectedStatuses = mapOf(
            "index" to "partial",
            "follow" to "partial",
            "manual-scan" to "partial",
            "auto-scan" to "partial",
            "feed" to "gap",
            "group" to "gap",
            "confirm" to "gap",
            "reject" to "gap",
            "language-override" to "gap",
            "chapter-compare" to "gap",
        )
    }
}
