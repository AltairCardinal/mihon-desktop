package mihon.test.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopFinalParityRunnerContractTest {
    private val repositoryRoot: Path = Path.of(System.getProperty("user.dir")).parent
    private val runner = repositoryRoot.resolve("scripts/desktop-final-parity-test.sh")
    private val inventory = repositoryRoot.resolve("app-desktop/src/test/resources/parity/test-mode-coverage-inventory.json")

    @Test
    fun `default command targets a real test desktop client entry`() {
        val source = Files.readString(runner)
        val client = repositoryRoot.resolve("test-desktop/src/main/python/mihon_desktop_final_parity_client.py")

        assertTrue(client.exists())
        assertTrue(source.contains("test-desktop/src/main/python/mihon_desktop_final_parity_client.py"))
        assertFalse(source.contains("FinalParityScenarioSuite"))
    }

    @Test
    fun `missing fixed executable fails with the build command and exact path`() {
        val sandbox = createTempDirectory("mihon-final-runner-missing")
        val missing = sandbox.resolve("Mihon Desktop.exe")

        val result = runRunner(mapOf("MIHON_FINAL_PARITY_EXE" to missing.bashPath()))

        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("Fixed unpacked EXE is missing"))
        assertTrue(result.output.contains(missing.bashPath()))
        assertTrue(result.output.contains("./scripts/build-desktop.sh evidence"))
    }

    @Test
    fun `missing trusted build provenance fails before starting a process`() {
        val fixture = fixture()
        Files.delete(fixture.provenance)

        val result =
            runRunner(
                fixture.environment -
                    "MIHON_FINAL_PARITY_PROVENANCE_COMMAND",
            )

        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("Trusted build provenance is missing"))
        assertTrue(result.output.contains("./scripts/build-desktop.sh evidence"))
        assertFalse(fixture.processLog.exists())
    }

    @Test
    fun `rejected source or artifact provenance fails closed before launch`() {
        val fixture = fixture("rejected-provenance")

        val result =
            runRunner(
                fixture.environment +
                    ("MIHON_FINAL_PARITY_PROVENANCE_COMMAND" to "false"),
            )

        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("Trusted build provenance rejected"))
        assertTrue(result.output.contains("./scripts/build-desktop.sh evidence"))
        assertFalse(fixture.processLog.exists())
    }

    @Test
    fun `health endpoint already owned before launch fails without starting another process`() {
        val fixture = fixture("occupied", healthyAfter = 1)

        val result = runRunner(fixture.environment)

        assertEquals(4, result.exitCode)
        assertTrue(result.output.contains("already responding before launch"))
        assertFalse(fixture.processLog.exists())
    }

    @Test
    fun `health success is rejected when this launch pid already exited`() {
        val fixture = fixture("exited", healthAfterProcessStart = true, exitImmediately = true)

        val result = runRunner(fixture.environment)

        assertEquals(4, result.exitCode)
        assertTrue(result.output.contains("health responded but launched process is not alive"))
    }

    @Test
    fun `fake process is polled summarized exactly and always torn down`() {
        val fixture = fixture()

        val result = runRunner(fixture.environment)

        assertEquals(0, result.exitCode, result.output)
        assertFalse(fixture.environment.containsKey("MIHON_FINAL_PARITY_TEST_COMMAND"))
        assertTrue(result.output.contains("Families: 13/13"))
        assertTrue(result.output.contains("Permanent protections: 5/5"))
        assertTrue(result.output.contains("Capabilities: 64/64 unmapped=0"))
        assertTrue(result.output.contains("Family library: PASS"))
        assertTrue(Files.readString(fixture.healthCount).trim().toInt() >= 2)
        assertTrue(awaitLog(fixture.processLog, "stopped"), "fake process was not torn down: ${result.output}")
    }

    @Test
    fun `incomplete family protection and capability summaries fail precisely`() {
        val mutations =
            listOf(
                "family" to
                    { summary: JsonObject ->
                        summary.withArray("families") { it.dropLast(1) }
                    },
                "protection" to
                    { summary: JsonObject ->
                        summary.withArray("permanentProtections") { it.dropLast(1) }
                    },
                "capability" to
                    { summary: JsonObject ->
                        summary.withArray("mappedCapabilityIds") { it.dropLast(1) }
                    },
            )

        mutations.forEach { (name, mutation) ->
            val fixture = fixture(name)
            writeSummary(fixture.summaryFixture, mutation)

            val result =
                runRunner(
                    fixture.environment +
                        ("MIHON_FINAL_PARITY_TEST_COMMAND" to fixture.writeSummaryCommand),
                )

            assertEquals(3, result.exitCode, "$name: ${result.output}")
            assertTrue(result.output.contains("Final parity summary is incomplete"), "$name: ${result.output}")
            assertTrue(awaitLog(fixture.processLog, "stopped"), "$name fake process was not torn down")
        }
    }

    @Test
    fun `malformed summary schema always exits three without a traceback`() {
        val fixture = fixture("schema")
        writeSummary(fixture.summaryFixture)
        val valid = Json.parseToJsonElement(Files.readString(fixture.summaryFixture)).jsonObject
        val malformed =
            listOf(
                "top-level" to "[]",
                "families-type" to JsonObject(valid.toMutableMap().apply { put("families", JsonPrimitive("wrong")) }).toString(),
                "member-field" to
                    JsonObject(
                        valid.toMutableMap().apply {
                            put(
                                "families",
                                JsonArray(
                                    valid.getValue("families").jsonArray.mapIndexed { index, element ->
                                        if (index == 0) {
                                            JsonObject(element.jsonObject.toMutableMap().apply { put("status", JsonPrimitive(7)) })
                                        } else {
                                            element
                                        }
                                    },
                                ),
                            )
                        },
                    ).toString(),
                "capability-type" to
                    JsonObject(
                        valid.toMutableMap().apply {
                            put("mappedCapabilityIds", JsonArray(listOf(buildJsonObject { put("id", JsonPrimitive(1)) })))
                        },
                    ).toString(),
            )

        malformed.forEach { (name, body) ->
            Files.deleteIfExists(fixture.healthCount)
            Files.writeString(fixture.summaryFixture, body)
            val result =
                runRunner(
                    fixture.environment +
                        ("MIHON_FINAL_PARITY_TEST_COMMAND" to fixture.writeSummaryCommand),
                )
            assertEquals(3, result.exitCode, "$name: ${result.output}")
            assertTrue(result.output.contains("Final parity summary schema is invalid"), "$name: ${result.output}")
            assertFalse(result.output.contains("Traceback"), "$name leaked a traceback: ${result.output}")
        }
    }

    @Test
    fun `startup timeout is actionable and tears down the fake process`() {
        val fixture = fixture("timeout", healthyAfter = 100)
        writeSummary(fixture.summaryFixture)

        val result =
            runRunner(
                fixture.environment +
                    mapOf("MIHON_FINAL_PARITY_STARTUP_TIMEOUT_SECONDS" to "1"),
            )

        assertEquals(4, result.exitCode)
        assertTrue(result.output.contains("Timed out waiting for Test Mode health"))
        assertTrue(result.output.contains("MIHON_FINAL_PARITY_STARTUP_TIMEOUT_SECONDS"))
        assertTrue(awaitLog(fixture.processLog, "stopped"), "timed-out fake process was not torn down")
    }

    private fun fixture(
        name: String = "success",
        healthyAfter: Int = 2,
        healthAfterProcessStart: Boolean = false,
        exitImmediately: Boolean = false,
    ): Fixture {
        val root = createTempDirectory("mihon-final-runner-$name")
        val executable = root.resolve("Mihon Desktop.exe")
        val processLog = root.resolve("process.log")
        val healthCount = root.resolve("health-count")
        val health = root.resolve("health.sh")
        val summaryFixture = root.resolve("summary.json")
        val writeSummary = root.resolve("write-summary.sh")
        val inventoryFixture = root.resolve("coverage-inventory.json")
        val provenance = root.resolveSibling("${root.fileName}.task151-provenance.json")

        executable.writeText(
            """
            #!/usr/bin/env bash
            trap 'echo stopped >> "${processLog.bashPath()}"; exit 0' TERM INT EXIT
            echo started >> "${processLog.bashPath()}"
            ${if (exitImmediately) "exit 0" else "while true; do sleep 0.1; done"}
            """.trimIndent() + "\n",
        )
        health.writeText(
            if (healthAfterProcessStart) {
                """
                #!/usr/bin/env bash
                [[ -f "${processLog.bashPath()}" ]]
                """.trimIndent() + "\n"
            } else {
                """
                #!/usr/bin/env bash
                count=0
                [[ -f "${healthCount.bashPath()}" ]] && count=$(cat "${healthCount.bashPath()}")
                count=$((count + 1))
                echo "${'$'}count" > "${healthCount.bashPath()}"
                [[ "${'$'}count" -ge "$healthyAfter" ]]
                """.trimIndent() + "\n"
            },
        )
        writeSummary.writeText(
            """
            #!/usr/bin/env bash
            cp "${summaryFixture.bashPath()}" "${'$'}MIHON_FINAL_PARITY_SUMMARY_FILE"
            """.trimIndent() + "\n",
        )
        listOf(executable, health, writeSummary).forEach { it.toFile().setExecutable(true) }
        provenance.writeText("{}\n")
        writeCoveredInventory(inventoryFixture)

        return Fixture(
            executable = executable,
            processLog = processLog,
            healthCount = healthCount,
            summaryFixture = summaryFixture,
            provenance = provenance,
            writeSummaryCommand = "bash \"${writeSummary.bashPath()}\"",
            environment =
                mapOf(
                    "MIHON_FINAL_PARITY_EXE" to executable.bashPath(),
                    "MIHON_FINAL_PARITY_PROVENANCE" to provenance.bashPath(),
                    "MIHON_FINAL_PARITY_PROVENANCE_COMMAND" to "true",
                    "MIHON_FINAL_PARITY_INVENTORY" to inventoryFixture.bashPath(),
                    "MIHON_FINAL_PARITY_HEALTH_COMMAND" to "bash \"${health.bashPath()}\"",
                    "MIHON_FINAL_PARITY_POLL_INTERVAL_SECONDS" to "0.05",
                ),
        )
    }

    private fun writeCoveredInventory(path: Path) {
        val root = Json.parseToJsonElement(Files.readString(inventory)).jsonObject
        val covered =
            JsonObject(
                root.toMutableMap().apply {
                    put(
                        "scenarios",
                        JsonArray(
                            root.getValue("scenarios").jsonArray.map { element ->
                                JsonObject(
                                    element.jsonObject.toMutableMap().apply {
                                        put("status", JsonPrimitive("covered"))
                                    },
                                )
                            },
                        ),
                    )
                },
            )
        Files.writeString(path, covered.toString())
    }

    private fun writeSummary(
        path: Path,
        mutation: (JsonObject) -> JsonObject = { it },
    ) {
        val inventoryRoot = Json.parseToJsonElement(Files.readString(inventory)).jsonObject
        val families =
            inventoryRoot.getValue("scenarios").jsonArray.map {
                it.jsonObject.getValue("family").jsonPrimitive.content
            }
        val protections =
            inventoryRoot.getValue("permanentProtections").jsonArray.map {
                it.jsonObject.getValue("id").jsonPrimitive.content
            }
        val mappedIds =
            (inventoryRoot.getValue("scenarios").jsonArray + inventoryRoot.getValue("boundaries").jsonArray)
                .flatMap { entry -> entry.jsonObject.getValue("capabilityIds").jsonArray }
                .map { it.jsonPrimitive.content.toInt() }
        val summary =
            buildJsonObject {
                put(
                    "families",
                    buildJsonArray {
                        families.forEach { family ->
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(family))
                                    put("status", JsonPrimitive("PASS"))
                                    put("detail", JsonPrimitive("fixture"))
                                },
                            )
                        }
                    },
                )
                put(
                    "permanentProtections",
                    buildJsonArray {
                        protections.forEach { protection ->
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(protection))
                                    put("status", JsonPrimitive("PASS"))
                                    put("detail", JsonPrimitive("fixture"))
                                },
                            )
                        }
                    },
                )
                put("mappedCapabilityIds", JsonArray(mappedIds.map(::JsonPrimitive)))
            }
        Files.writeString(path, mutation(summary).toString())
    }

    private fun JsonObject.withArray(
        field: String,
        transform: (List<kotlinx.serialization.json.JsonElement>) -> List<kotlinx.serialization.json.JsonElement>,
    ) = JsonObject(toMutableMap().apply { put(field, JsonArray(transform(getValue(field).jsonArray))) })

    private fun runRunner(overrides: Map<String, String>): RunResult {
        if (!runner.exists()) return RunResult(-1, "runner script is missing: $runner")
        val exports = overrides.entries.joinToString("; ") { (name, value) -> "export $name=${value.shellQuote()}" }
        val process =
            ProcessBuilder("bash", "-lc", "$exports; exec bash ${runner.bashPath().shellQuote()}")
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        return RunResult(process.waitFor(), output)
    }

    private fun awaitLog(path: Path, marker: String): Boolean {
        repeat(50) {
            if (path.exists() && Files.readString(path).contains(marker)) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun Path.bashPath(): String {
        val normalized = toString().replace('\\', '/')
        return if (normalized.length >= 3 && normalized[1] == ':') {
            "/mnt/${normalized[0].lowercaseChar()}/${normalized.substring(3)}"
        } else {
            normalized
        }
    }

    private fun String.shellQuote() = "'${replace("'", "'\"'\"'")}'"

    private data class Fixture(
        val executable: Path,
        val processLog: Path,
        val healthCount: Path,
        val summaryFixture: Path,
        val provenance: Path,
        val writeSummaryCommand: String,
        val environment: Map<String, String>,
    )

    private data class RunResult(val exitCode: Int, val output: String)
}
