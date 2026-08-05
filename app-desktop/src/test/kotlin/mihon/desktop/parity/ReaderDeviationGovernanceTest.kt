package mihon.desktop.parity

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderDeviationGovernanceTest {
    private val repositoryRoot = repositoryRoot()
    private val fixturePath = repositoryRoot.resolve(FIXTURE_PATH)
    private val manifestPath = repositoryRoot.resolve(MANIFEST_PATH)

    @Test
    fun `Reader local deviations have exact owners classifications provenance and behavior evidence`() {
        val fixture = parseObject(fixturePath)
        val manifest = parseArray(manifestPath)
        val fixtureEntries = fixture.getValue("deviations").jsonArray.map { it.jsonObject }
        val manifestEntries = readerManifestDeviations(manifest)

        assertReaderDeviationContract(manifestEntries)
        assertReaderDeviationContract(fixtureEntries.map { OwnedDeviation(ownerCapabilityId(it), it) })

        val fixtureById = fixtureEntries.associateBy { it.requiredText("id") }
        val manifestById = manifestEntries.associateBy { it.value.requiredText("id") }
        REQUIRED_READER_DEVIATIONS.keys.forEach { id ->
            assertEquals(
                fixtureById.getValue(id),
                manifestById.getValue(id).value,
                "$id must have identical fixture and manifest evidence",
            )
        }
    }

    @Test
    fun `Reader deviation contract rejects every prohibited evidence mutation independently`() {
        val valid = requiredDeviationObjects()

        assertMutationFails(valid, "classification") { entries ->
            entries.replace("DUAL_FIXED_4_3_FRAME", "classification", JsonPrimitive("FIXED_ORIGINAL"))
        }
        assertMutationFails(valid, "introductionRefs") { entries ->
            entries.replace("DUAL_FIXED_4_3_FRAME", "introductionRefs", null)
        }
        assertMutationFails(valid, "introductionRefs") { entries ->
            entries.replace(
                "DUAL_FIXED_4_3_FRAME",
                "introductionRefs",
                JsonArray(listOf(JsonPrimitive(CURRENT_HEAD))),
            )
        }
        assertMutationFails(valid, "behaviorEvidenceRefs") { entries ->
            entries.replace(
                "DUAL_FIXED_4_3_FRAME",
                "behaviorEvidenceRefs",
                JsonArray(listOf(JsonPrimitive("app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DualPagePagerViewer.kt"))),
            )
        }
        assertMutationFails(valid, "ownerCapabilityId") { entries ->
            entries.map {
                if (it.value.requiredText("id") == "DUAL_FIXED_4_3_FRAME") it.copy(ownerCapabilityId = 45) else it
            }
        }
        assertMutationFails(valid, "duplicate deviation id") { entries ->
            entries.map {
                if (it.value.requiredText("id") == "DUAL_COVER_LEFT_SLOT") {
                    it.copy(value = JsonObject(it.value + ("id" to JsonPrimitive("DUAL_PHYSICAL_SLOT_IDENTITY"))))
                } else {
                    it
                }
            }
        }
    }

    private fun assertMutationFails(
        valid: List<OwnedDeviation>,
        expectedMessage: String,
        mutation: (List<OwnedDeviation>) -> List<OwnedDeviation>,
    ) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            assertReaderDeviationContract(mutation(valid))
        }
        assertTrue(failure.message.orEmpty().contains(expectedMessage), failure.message)
    }

    private fun assertReaderDeviationContract(entries: List<OwnedDeviation>) {
        val current = entries.filter { it.value.requiredText("id") in REQUIRED_READER_DEVIATIONS }
        val ids = current.map { it.value.requiredText("id") }
        require(ids.size == ids.toSet().size) { "duplicate deviation id" }
        require(ids.toSet() == REQUIRED_READER_DEVIATIONS.keys) { "exact Reader deviation ids" }

        current.forEach { owned ->
            val id = owned.value.requiredText("id")
            val expected = REQUIRED_READER_DEVIATIONS.getValue(id)
            require(owned.ownerCapabilityId == expected.ownerCapabilityId) { "$id ownerCapabilityId" }
            require(owned.value.requiredText("classification") == expected.classification) { "$id classification" }
            require(owned.value.requiredText("resolutionStatus") == expected.resolutionStatus) { "$id resolutionStatus" }
            require(owned.value.requiredText("description").isNotBlank()) { "$id description" }

            val introductionRefs = owned.value.requiredStringArray("introductionRefs")
            require(introductionRefs == expected.introductionRefs) { "$id introductionRefs" }
            require(introductionRefs.none { it == CURRENT_HEAD }) { "$id introductionRefs must not use current HEAD" }

            val behaviorEvidenceRefs = owned.value.requiredStringArray("behaviorEvidenceRefs")
            require(behaviorEvidenceRefs.isNotEmpty()) { "$id behaviorEvidenceRefs" }
            require(
                behaviorEvidenceRefs.all { ref ->
                    val split = ref.split('#', limit = 2)
                    split.size == 2 && split[0].contains("/src/test/") && split[0].endsWith("Test.kt") && split[1].isNotBlank()
                },
            ) { "$id behaviorEvidenceRefs must use test path#method production behavior evidence" }
            if (expected.resolutionStatus == "REMOVED") {
                require(owned.value.requiredText("closureTask") == "RNC-02") { "$id closureTask" }
                require(
                    owned.value.getValue("resolutionEvidence").jsonObject.requiredText("productionBehaviorTest") in
                        behaviorEvidenceRefs,
                ) { "$id resolutionEvidence" }
            }
        }
    }

    private fun requiredDeviationObjects(): List<OwnedDeviation> =
        REQUIRED_READER_DEVIATIONS.map { (id, expected) ->
            OwnedDeviation(
                expected.ownerCapabilityId,
                JsonObject(
                    buildMap {
                        put("id", JsonPrimitive(id))
                        put("classification", JsonPrimitive(expected.classification))
                        put("introductionRefs", JsonArray(expected.introductionRefs.map(::JsonPrimitive)))
                        put(
                            "behaviorEvidenceRefs",
                            JsonArray(
                                listOf(
                                    JsonPrimitive(
                                        "app-desktop/src/test/kotlin/mihon/desktop/reader/DesktopReaderSessionIntegrationTest.kt#production behavior",
                                    ),
                                ),
                            ),
                        )
                        put("description", JsonPrimitive("Explicit user result or reliability boundary."))
                        put("resolutionStatus", JsonPrimitive(expected.resolutionStatus))
                        if (expected.resolutionStatus == "REMOVED") {
                            put("closureTask", JsonPrimitive("RNC-02"))
                            put(
                                "resolutionEvidence",
                                JsonObject(
                                    mapOf(
                                        "productionBehaviorTest" to
                                            JsonPrimitive(
                                                "app-desktop/src/test/kotlin/mihon/desktop/reader/DesktopReaderSessionIntegrationTest.kt#production behavior",
                                            ),
                                    ),
                                ),
                            )
                        }
                    },
                ),
            )
        }

    private fun List<OwnedDeviation>.replace(id: String, key: String, value: JsonElement?): List<OwnedDeviation> =
        map { owned ->
            if (owned.value.requiredText("id") != id) return@map owned
            val fields = owned.value.toMutableMap()
            if (value == null) fields.remove(key) else fields[key] = value
            owned.copy(value = JsonObject(fields))
        }

    private fun readerManifestDeviations(manifest: JsonArray): List<OwnedDeviation> =
        manifest.map { it.jsonObject }
            .filter { it.getValue("id").jsonPrimitive.content.toInt() in READER_CAPABILITY_IDS }
            .flatMap { capability ->
                val owner = capability.getValue("id").jsonPrimitive.content.toInt()
                capability.getValue("deviations").jsonArray.map { OwnedDeviation(owner, it.jsonObject) }
            }

    private fun ownerCapabilityId(deviation: JsonObject): Int {
        val id = deviation.requiredText("id")
        return REQUIRED_READER_DEVIATIONS[id]?.ownerCapabilityId
            ?: HISTORICAL_DEVIATION_OWNERS[id]
            ?: error("Unexpected Reader deviation $id")
    }

    private fun JsonObject.requiredText(key: String): String =
        requireNotNull(this[key]) { "$key is required" }.jsonPrimitive.content.also {
            require(it.isNotBlank()) { "$key must not be blank" }
        }

    private fun JsonObject.requiredStringArray(key: String): List<String> =
        requireNotNull(this[key]) { "$key is required" }.jsonArray.map { it.jsonPrimitive.content }

    private fun parseObject(path: Path): JsonObject = Json.parseToJsonElement(Files.readString(path)).jsonObject

    private fun parseArray(path: Path): JsonArray = Json.parseToJsonElement(Files.readString(path)).jsonArray

    private fun repositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private data class OwnedDeviation(
        val ownerCapabilityId: Int,
        val value: JsonObject,
    )

    private data class ExpectedDeviation(
        val ownerCapabilityId: Int,
        val classification: String,
        val introductionRefs: List<String>,
        val resolutionStatus: String = "ACTIVE",
    )

    private companion object {
        const val FIXTURE_PATH = "app-desktop/src/test/resources/parity/fixed-main-reader-fixtures.json"
        const val MANIFEST_PATH = "app-desktop/src/test/resources/parity/parity-manifest.json"
        const val CURRENT_HEAD = "a1a9d009ef8b230f43c624e21434ad999690137c"
        val READER_CAPABILITY_IDS = setOf(43, 45, 53)
        val HISTORICAL_DEVIATION_OWNERS =
            mapOf(
                "GENERATION_HARDENING" to 45,
                "ADJACENT_PORTRAIT_PAIRING" to 43,
                "HTTP_RETRY_FORCE_DRIFT" to 45,
                "DUAL_PAGE_PROGRESS_FIRST_ONLY" to 53,
            )
        val REQUIRED_READER_DEVIATIONS =
            mapOf(
                "DISPLAY_UNIT_STABLE_IDENTITY" to expected(43, "DESKTOP_PRESENTATION_POLICY", "464cbefafc2153de286963224694eafceda5e0d1"),
                "WEBTOON_RELATIVE_ANCHOR_RECOVERY" to expected(43, "DESKTOP_PRESENTATION_POLICY", "71aa3c458ff5b9a24b04a14820d6d2bb636c3e04"),
                "WEBTOON_AUTOSCROLL_INTERACTION_PAUSE" to expected(43, "DESKTOP_PRODUCT_ENHANCEMENT", "71aa3c458ff5b9a24b04a14820d6d2bb636c3e04"),
                "DUAL_PHYSICAL_SLOT_IDENTITY" to expected(43, "DESKTOP_PRESENTATION_POLICY", "c01771573034d2ed3492db3ddd109533bb631d99"),
                "DUAL_COVER_LEFT_SLOT" to expected(43, "DESKTOP_PRODUCT_ENHANCEMENT", "c01771573034d2ed3492db3ddd109533bb631d99"),
                "DUAL_FIXED_4_3_FRAME" to
                    ExpectedDeviation(
                        43,
                        "DESKTOP_PRESENTATION_POLICY",
                        listOf("c01771573034d2ed3492db3ddd109533bb631d99"),
                        "REMOVED",
                    ),
                "DUAL_MAX_VISIBLE_PAGE_PROGRESS" to expected(
                    53,
                    "CROSS_PLATFORM_PRODUCT_ENHANCEMENT",
                    "6330c6198e827622a74dc48b1ac50f15e7470380",
                    "c01771573034d2ed3492db3ddd109533bb631d99",
                ),
                "LATEST_SETTLEMENT_ORDERING" to expected(53, "CROSS_PLATFORM_RELIABILITY_ENHANCEMENT", "6330c6198e827622a74dc48b1ac50f15e7470380"),
                "PROGRESS_IDEMPOTENCY_AND_DRAIN" to expected(
                    53,
                    "CROSS_PLATFORM_RELIABILITY_ENHANCEMENT",
                    "6330c6198e827622a74dc48b1ac50f15e7470380",
                    "dcb2dfb24cb4181d3703a5bf02c5b9e535adb0f8",
                ),
                "ENCODED_STORE_LIFECYCLE" to expected(
                    45,
                    "CROSS_PLATFORM_RELIABILITY_ENHANCEMENT",
                    "06a1138d2deefb67a565c1c33450d69e734029f7",
                    "dcb2dfb24cb4181d3703a5bf02c5b9e535adb0f8",
                ),
                "DESKTOP_ENCODED_CACHE_POLICY" to expected(45, "DESKTOP_CACHE_POLICY", "dcb2dfb24cb4181d3703a5bf02c5b9e535adb0f8"),
                "DESKTOP_FULL_NEXT_CHAPTER_PREFETCH" to expected(45, "DESKTOP_PRODUCT_ENHANCEMENT", "6ee9473cde839fe5ee932bdb5263c78567c2ff12"),
            )

        private fun expected(
            ownerCapabilityId: Int,
            classification: String,
            vararg introductionRefs: String,
        ) = ExpectedDeviation(ownerCapabilityId, classification, introductionRefs.toList())

    }
}
