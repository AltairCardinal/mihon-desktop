package mihon.desktop.parity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopProductCapabilityContractTest {
    @TempDir
    lateinit var tempDir: Path

    private val expectedIds =
        setOf(
            3, 4, 7, 8, 9, 10, 11, 12, 16, 17, 19, 22, 24, 26, 28, 29,
            30, 32, 33, 34, 35, 36, 37, 38, 39, 40, 43, 44, 45, 47, 49, 51,
            53, 54, 56, 57, 59, 61, 62, 64, 66, 67, 68, 69, 70, 71, 72, 73,
            74, 81, 82, 83, 84, 85, 86, 87, 88, 90, 91, 92, 93, 94, 95, 96,
        )
    private val validStatuses = setOf("NOT_STARTED", "CHARACTERIZED", "SHARED", "WIRED", "VERIFIED", "EXEMPT")
    private val validTags =
        setOf(
            "SHARE-DIRECT",
            "SHARE-EXTRACT",
            "PLATFORM-ADAPTER",
            "DESKTOP-PRODUCT",
            "TEMP-COMPAT",
            "PLATFORM-EXEMPT",
        )
    private val desktopProductEvidence =
        setOf(
            "app-desktop/src/test/kotlin/mihon/desktop/ui/authors/AuthorDetailBehaviorTest.kt",
            "app-desktop/src/test/kotlin/mihon/desktop/domain/GetUpcomingMangaTest.kt",
            "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DualPageLayoutPolicyTest.kt",
            "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/WebtoonAutoScrollTest.kt",
            "app-desktop/src/test/kotlin/mihon/desktop/extension/ApkToJarConverterTest.kt",
            "app-desktop/src/test/kotlin/mihon/desktop/test/navigation/TestNavigationControllerTest.kt",
            "app-desktop/src/test/kotlin/mihon/desktop/test/http/TestHttpServerJsonTest.kt",
        )
    private val expectedCapabilityEvidence =
        mapOf(
            34 to setOf("app-desktop/src/test/kotlin/mihon/desktop/extension/ApkToJarConverterTest.kt"),
            40 to setOf("app-desktop/src/test/kotlin/mihon/desktop/network/FlareSolverrClientTest.kt"),
            43 to
                setOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/VirtualPageListTest.kt",
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/EdgePixelMatcherTest.kt",
                ),
            49 to setOf("app-desktop/src/test/kotlin/mihon/desktop/ui/reader/TapZoneTest.kt"),
        )
    private val task3aBehaviorEvidence =
        mapOf(
            16 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryCategoryBehaviorTest.kt" to
                        setOf("category dialog intents perform create rename reorder and delete through production DI"),
                    "app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt" to
                        setOf("测试配置入口使用隔离内存存储并解析实际依赖"),
                ),
            17 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/library/LibrarySharedEvaluationWiringTest.kt" to
                        setOf("Android library production model owns the shared evaluator"),
                    "domain/src/commonTest/kotlin/tachiyomi/domain/library/interactor/EvaluateLibraryTest.kt" to
                        setOf(
                            "all tri-state filters preserve Android IS NOT and disabled semantics",
                            "download global local tracking and multiple flags match Android boundaries",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryScreenModelTest.kt" to
                        setOf(
                            "filter intent cycles include exclude any and immediately changes visible items",
                            "complete filter flags flow from state to visible list including local and tracking boundaries",
                        ),
                ),
            22 to
                mapOf(
                    "data/src/jvmTest/kotlin/tachiyomi/data/manga/MangaRepositoryMembershipIntegrationTest.kt" to
                        setOf(
                            "membership update commits favorite date and categories together",
                            "invalid category rolls back every manga membership update",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/domain/DesktopMigrateMangaUseCaseIntegrationTest.kt" to
                        setOf(
                            "category failure rolls back source and target migration membership",
                            "copy categories and replace move real library membership without half state",
                            "copy categories false and replace false preserve source and recalculate target date",
                        ),
                ),
            19 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryParityIntegrationTest.kt" to
                        setOf("library model exposes batch category partial failure to UI"),
                ),
            24 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaDetailScreenModelTest.kt" to
                        setOf("selected read action exposes partial failure in state"),
                ),
            26 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaDetailScreenModelTest.kt" to
                        setOf(
                            "cover selection cancellation has no side effects",
                            "cover update success exposes feedback and refreshed model",
                            "cover permission failure is visible and does not refresh cache",
                            "cover delete success refreshes model and reports feedback",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt" to
                        setOf("测试配置入口使用隔离内存存储并解析实际依赖"),
                ),
            66 to
                mapOf(
                    "domain/src/commonTest/kotlin/tachiyomi/domain/library/interactor/AggregateLibraryStatsTest.kt" to
                        setOf("distinct titles aggregate categories sources statuses and chapters"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/more/StatsScreenModelTest.kt" to
                        setOf("state moves from loading to shared aggregation and exposes errors"),
                ),
        )
    private val expectedTags =
        mapOf(
            3 to setOf("SHARE-EXTRACT"),
            4 to setOf("SHARE-EXTRACT"),
            7 to setOf("SHARE-EXTRACT"),
            8 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"), 9 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"),
            10 to setOf("PLATFORM-ADAPTER"), 11 to setOf("PLATFORM-ADAPTER"), 12 to setOf("PLATFORM-ADAPTER"),
            16 to setOf("SHARE-DIRECT", "SHARE-EXTRACT"), 17 to setOf("SHARE-EXTRACT"),
            19 to setOf("SHARE-EXTRACT"), 22 to setOf("SHARE-DIRECT"), 24 to setOf("SHARE-EXTRACT"),
            26 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"), 28 to setOf("SHARE-EXTRACT"),
            29 to setOf("SHARE-DIRECT", "SHARE-EXTRACT"), 30 to setOf("SHARE-DIRECT"),
            32 to setOf("SHARE-DIRECT"), 33 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"),
            34 to setOf("PLATFORM-ADAPTER", "DESKTOP-PRODUCT"),
            35 to setOf("PLATFORM-ADAPTER", "TEMP-COMPAT"), 36 to setOf("PLATFORM-ADAPTER"),
            37 to setOf("SHARE-EXTRACT"), 38 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"),
            39 to setOf("PLATFORM-ADAPTER"), 40 to setOf("PLATFORM-ADAPTER", "DESKTOP-PRODUCT"),
            43 to setOf("SHARE-EXTRACT", "DESKTOP-PRODUCT"), 44 to setOf("PLATFORM-ADAPTER"),
            45 to setOf("SHARE-EXTRACT"), 47 to setOf("SHARE-EXTRACT"),
            49 to setOf("SHARE-DIRECT", "DESKTOP-PRODUCT"), 51 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"),
            53 to setOf("SHARE-DIRECT"), 54 to setOf("SHARE-DIRECT"), 56 to setOf("SHARE-EXTRACT"),
            57 to setOf("SHARE-EXTRACT"), 59 to setOf("SHARE-DIRECT", "PLATFORM-ADAPTER"),
            61 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"), 62 to setOf("SHARE-DIRECT"),
            64 to setOf("SHARE-DIRECT"), 66 to setOf("SHARE-DIRECT", "SHARE-EXTRACT"),
            67 to setOf("SHARE-DIRECT", "SHARE-EXTRACT"), 68 to setOf("SHARE-EXTRACT"),
            69 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"), 70 to setOf("SHARE-DIRECT"),
            71 to setOf("SHARE-EXTRACT"), 72 to setOf("SHARE-EXTRACT"), 73 to setOf("PLATFORM-ADAPTER"),
            74 to setOf("SHARE-DIRECT", "TEMP-COMPAT"), 81 to setOf("PLATFORM-ADAPTER"),
            82 to setOf("PLATFORM-ADAPTER"), 83 to setOf("PLATFORM-ADAPTER"),
            84 to setOf("PLATFORM-ADAPTER", "PLATFORM-EXEMPT"), 85 to setOf("PLATFORM-EXEMPT"),
            86 to setOf("PLATFORM-ADAPTER"), 87 to setOf("SHARE-DIRECT"),
            88 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"), 90 to setOf("SHARE-EXTRACT"),
            91 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"),
            92 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"), 93 to setOf("SHARE-EXTRACT"),
            94 to setOf("SHARE-DIRECT", "PLATFORM-ADAPTER"), 95 to setOf("SHARE-EXTRACT"),
            96 to setOf("PLATFORM-ADAPTER", "TEMP-COMPAT"),
        )

    @Test
    fun `parity manifest defines the exact roadmap contract`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot)

        desktopProductEvidence.forEach { evidence ->
            assertTrue(Files.isRegularFile(repositoryRoot.resolve(evidence)), "Missing Desktop product evidence $evidence")
        }

        assertEquals(64, items.size)
        val ids = items.map { validatedId(it.jsonObject) }
        assertEquals(ids.size, ids.toSet().size, "Parity IDs must be unique")
        assertEquals(expectedIds, ids.toSet(), "Parity IDs must exactly match the 64-item design set")
        assertEquals(expectedIds, expectedTags.keys, "Every parity ID must have an exact design tag mapping")

        val requiredTextFields =
            setOf(
                "group",
                "status",
                "authoritativeImplementation",
                "desktopImplementation",
                "platformExemptionEvidence",
                "targetVersion",
            )
        items.forEach { element ->
            val item = element.jsonObject
            validateItem(item, repositoryRoot)
            val id = validatedId(item)
            requiredTextFields.forEach { field ->
                assertTrue(item.getValue(field).jsonPrimitive.content.isNotBlank(), "ID ${item["id"]}: $field must not be blank")
            }
            assertTrue(item.getValue("status").jsonPrimitive.content in validStatuses)

            val tags = item.getValue("tags").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(tags.isNotEmpty(), "ID ${item["id"]}: tags must not be empty")
            assertTrue(tags.all { it in validTags }, "ID ${item["id"]}: invalid tag")
            assertEquals(expectedTags.getValue(id), tags.toSet(), "ID $id: tags differ from design A-J tables")

            val protectionTests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }
            assertFalse(
                protectionTests.any { it.endsWith("DesktopProductCapabilityContractTest.kt") },
                "ID $id: parity contract cannot be its own protection evidence",
            )
            if ("DESKTOP-PRODUCT" in tags) {
                assertTrue(protectionTests.isNotEmpty(), "ID $id: DESKTOP-PRODUCT protectionTests must not be empty")
                assertEquals(expectedCapabilityEvidence.getValue(id), protectionTests.toSet(), "ID $id: unexpected evidence")
            } else if (item.getValue("status").jsonPrimitive.content == "NOT_STARTED") {
                assertTrue(protectionTests.isEmpty(), "ID $id: unstarted capabilities cannot claim evidence")
            }
            protectionTests.forEach { testEvidence ->
                assertFalse(testEvidence.startsWith("MISSING:"), "ID ${item["id"]}: $testEvidence")
                assertTrue(testEvidence.isNotBlank(), "ID ${item["id"]}: protection test must not be blank")
                val evidencePath = repositoryRoot.resolve(testEvidence)
                assertTrue(evidencePath.exists(), "ID ${item["id"]}: missing protection test $testEvidence")
                assertTrue(Files.isRegularFile(evidencePath), "ID ${item["id"]}: protection evidence must be a file")
            }
        }
        val productIds =
            items
                .filter {
                    "DESKTOP-PRODUCT" in
                        it.jsonObject.getValue("tags").jsonArray.map { tag -> tag.jsonPrimitive.content }
                }.map { it.jsonObject.getValue("id").jsonPrimitive.content.toInt() }
                .toSet()
        assertEquals(expectedCapabilityEvidence.keys, productIds)
        task3aBehaviorEvidence.forEach { (id, evidence) ->
            val item = items.single { validatedId(it.jsonObject) == id }.jsonObject
            val declared = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }.toSet()
            evidence.forEach { (path, methodNames) ->
                assertTrue(path in declared, "ID $id must declare dedicated behavior evidence $path")
                val source = Files.readString(repositoryRoot.resolve(path))
                methodNames.forEach { methodName ->
                    assertTrue(
                        source.contains("fun `$methodName`"),
                        "ID $id evidence $path must contain behavior test `$methodName`",
                    )
                }
            }
        }
    }

    @Test
    fun `EXEMPT item accepts real evidence and rejects NONE or missing files`() {
        val evidence = Files.createFile(tempDir.resolve("evidence.md"))
        validateItem(syntheticItem(84, "EXEMPT", evidence.toString()), tempDir)

        val noneFailure = assertThrows(AssertionError::class.java) {
            validateItem(syntheticItem(84, "EXEMPT", "NONE"), tempDir)
        }
        assertTrue(noneFailure.message.orEmpty().contains("ID 84"))

        val missingFailure = assertThrows(AssertionError::class.java) {
            validateItem(syntheticItem(84, "EXEMPT", "missing.md"), tempDir)
        }
        assertTrue(missingFailure.message.orEmpty().contains("ID 84"))
    }

    @Test
    fun `missing empty non-integer and wrong-type ids fail with item context`() {
        val invalidItems =
            listOf(
                buildJsonObject { put("status", "NOT_STARTED") },
                syntheticItem(3).toMutableMap().also { it["id"] = Json.parseToJsonElement("\"\"") }.let(::JsonObject),
                syntheticItem(3).toMutableMap().also { it["id"] = Json.parseToJsonElement("\"three\"") }.let(::JsonObject),
                syntheticItem(3).toMutableMap().also { it["id"] = Json.parseToJsonElement("\"3\"") }.let(::JsonObject),
                syntheticItem(3).toMutableMap().also { it["id"] = buildJsonObject { put("value", 3) } }.let(::JsonObject),
            )

        invalidItems.forEach { item ->
            val failure = assertThrows(AssertionError::class.java) { validateItem(item, tempDir) }
            assertTrue(failure.message.orEmpty().contains("ID"), failure.message)
            assertTrue(failure.message.orEmpty().contains("item="), failure.message)
        }
    }

    private fun validateItem(item: JsonObject, repositoryRoot: Path) {
        val id = validatedId(item)
        val status = item["status"]?.jsonPrimitive?.content
        assertTrue(status in validStatuses, "ID $id: status must be one of $validStatuses")
        val exemptionEvidence = item["platformExemptionEvidence"]?.jsonPrimitive?.content
        if (status == "EXEMPT") {
            assertTrue(exemptionEvidence != null && exemptionEvidence != "NONE", "ID $id: EXEMPT requires real platform evidence")
            assertTrue(Files.isRegularFile(repositoryRoot.resolve(exemptionEvidence!!)), "ID $id: missing exemption evidence $exemptionEvidence")
        } else {
            assertEquals("NONE", exemptionEvidence, "ID $id: non-EXEMPT evidence must be NONE")
        }
    }

    private fun validatedId(item: JsonObject): Int {
        val raw = item["id"] ?: throw AssertionError("ID <missing>: item has no id field; item=$item")
        val primitive = try {
            raw.jsonPrimitive
        } catch (_: IllegalArgumentException) {
            throw AssertionError("ID <invalid>: id must be an integer; item=$item")
        }
        val text = primitive.content
        if (primitive.isString) {
            throw AssertionError("ID <$text>: id must be a JSON integer, not a string; item=$item")
        }
        return text.toIntOrNull() ?: throw AssertionError("ID <$text>: id must be a non-empty integer; item=$item")
    }

    private fun syntheticItem(id: Int, status: String = "NOT_STARTED", evidence: String = "NONE") =
        buildJsonObject {
            put("id", id)
            put("status", status)
            put("platformExemptionEvidence", evidence)
        }

    private fun repositoryRoot() =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private fun manifestItems(repositoryRoot: Path) =
        repositoryRoot.resolve("app-desktop/src/test/resources/parity/parity-manifest.json").let { resource ->
            require(resource.exists()) { "Missing parity/parity-manifest.json" }
            Json.parseToJsonElement(Files.readString(resource)).jsonArray
        }
}
