package mihon.desktop.parity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

class NonReaderActionInventoryContractTest {
    private val repositoryRoot = repositoryRoot()
    private val sourceCache = mutableMapOf<Pair<String, String>, String>()

    @Test
    fun `manual inventory schema and references are structurally consistent`() {
        val actions = linkedMapOf<String, ActionRecord>()
        val sources = linkedMapOf<String, SourceRecord>()
        val surfaceReferences = mutableListOf<SurfaceReference>()

        manifestItems().forEach { item ->
            val capabilityId = item.requiredInt("id", "capability")
            val inventory = item.requiredObject("actionInventory", "capability $capabilityId")
            validateInventoryHeader(capabilityId, inventory)

            if (capabilityId in MANUAL_CHECKPOINT_BY_CAPABILITY) {
                validateManualInventory(capabilityId, inventory, actions)
            } else {
                collectLegacyInventory(capabilityId, inventory, actions, sources, surfaceReferences)
            }
        }

        assertEquals(
            MANUAL_CHECKPOINT_BY_CAPABILITY.keys,
            actions.values.filter(ActionRecord::isManual).mapTo(sortedSetOf(), ActionRecord::capabilityId),
            "manual checkpoints must contain exactly their reviewed capability clusters",
        )
        validateSourceRelationships(actions, sources, surfaceReferences)
    }

    @Test
    fun `NR0-01 F1 rejects an automatic authority decision`() {
        val (capabilityId, action) = firstManualAction()
        val provenance = action.requiredObject("provenance", action.requiredText("id", "manual action"))
        val roleName = PROVENANCE_ROLES.first()
        val role = provenance.requiredObject(roleName, "manual action/$roleName")
        val mutatedRole = role.replacing("decisionMode", JsonPrimitive("AUTOMATIC"))
        val mutatedAction = action.replacing(
            "provenance",
            JsonObject(provenance + (roleName to mutatedRole)),
        )

        val failure = assertThrows(AssertionError::class.java) {
            validateManualAction(capabilityId, mutatedAction)
        }
        assertTrue(failure.message.orEmpty().contains("manual decision"), failure.message)
    }

    @Test
    fun `NR0-01 F1 rejects removal of a required production evidence role`() {
        val (capabilityId, action, roleName, missingRole) = manualActions()
            .flatMap { (ownerId, candidate) ->
                candidate.requiredObject("provenance", candidate.requiredText("id", "manual action")).map { (role, value) ->
                    EvidenceMutationCandidate(ownerId, candidate, role, value.jsonObject)
                }
            }
            .firstNotNullOf { candidate ->
                val evidence = candidate.role.optionalArray("evidence") ?: return@firstNotNullOf null
                if (candidate.role.requiredText("status", "mutation role") != "PRESENT" || evidence.size < 2) {
                    return@firstNotNullOf null
                }
                val requiredRoles = candidate.action.requiredStringList("requiredEvidenceRoles", "mutation action")
                val missingRole = requiredRoles.firstOrNull { requiredRole ->
                    evidence.any { requiredRole in it.jsonObject.requiredStringList("roles", "mutation evidence") } &&
                        evidence.any { requiredRole !in it.jsonObject.requiredStringList("roles", "mutation evidence") }
                } ?: return@firstNotNullOf null
                candidate to missingRole
            }
            .let { (candidate, missingRole) ->
                EvidenceMutation(
                    capabilityId = candidate.capabilityId,
                    action = candidate.action,
                    roleName = candidate.roleName,
                    missingRole = missingRole,
                )
            }

        val provenance = action.requiredObject("provenance", action.requiredText("id", "manual action"))
        val role = provenance.requiredObject(roleName, "manual action/$roleName")
        val filteredEvidence = JsonArray(
            role.requiredArray("evidence", "manual action/$roleName").filter { evidenceElement ->
                missingRole !in evidenceElement.jsonObject.requiredStringList("roles", "manual evidence")
            },
        )
        val mutatedRole = role.replacing("evidence", filteredEvidence)
        val mutatedAction = action.replacing(
            "provenance",
            JsonObject(provenance + (roleName to mutatedRole)),
        )

        val failure = assertThrows(AssertionError::class.java) {
            validateManualAction(capabilityId, mutatedAction)
        }
        assertTrue(failure.message.orEmpty().contains("missing production evidence roles"), failure.message)
    }

    @Test
    fun `NR0-01 F1 rejects Reader internal scope`() {
        val item = manifestItems().single { it.requiredInt("id", "capability") == READER_BOUNDARY_CAPABILITY_ID }
        val inventory = item.requiredObject("actionInventory", "Reader boundary capability")
        val action = inventory.requiredArray("surfaces", "Reader boundary capability")
            .flatMap { it.jsonObject.requiredArray("actions", "Reader boundary surface") }
            .first()
            .jsonObject
        val mutatedAction = action.replacing("scope", JsonPrimitive("READER_INTERNAL"))

        val failure = assertThrows(AssertionError::class.java) {
            validateManualAction(READER_BOUNDARY_CAPABILITY_ID, mutatedAction)
        }
        assertTrue(failure.message.orEmpty().contains("non-reader or Reader boundary"), failure.message)
    }

    @Test
    fun `NR0-01 F2-1 rejects capability owner drift`() {
        val (capabilityId, action) = manualActions(F2_1_CAPABILITY_IDS).first()
        val mutatedAction = action.replacing("ownerCapabilityId", JsonPrimitive(999))

        val failure = assertThrows(AssertionError::class.java) {
            validateManualAction(capabilityId, mutatedAction)
        }
        assertTrue(failure.message.orEmpty().contains("owner drifted"), failure.message)
    }

    @Test
    fun `NR0-01 F2-1 rejects a destructive action without required confirmation`() {
        val (capabilityId, action) = manualActions(F2_1_CAPABILITY_IDS)
            .first { (_, candidate) -> candidate["risk"]?.jsonPrimitive?.content == "DESTRUCTIVE" }
        val confirmation = action.requiredObject("confirmation", action.requiredText("id", "destructive action"))
        val mutatedAction = action.replacing(
            "confirmation",
            confirmation.replacing("policy", JsonPrimitive("NONE")),
        )

        val failure = assertThrows(AssertionError::class.java) {
            validateManualAction(capabilityId, mutatedAction)
        }
        assertTrue(failure.message.orEmpty().contains("destructive action requires confirmation"), failure.message)
    }

    @Test
    fun `NR0-01 F2-1 rejects container evidence as an action effect`() {
        val (capabilityId, action) = manualActions(F2_1_CAPABILITY_IDS)
            .first { (_, candidate) ->
                candidate.requiredObject("provenance", "manual action").values.any { role ->
                    role.jsonObject.optionalArray("evidence")?.isNotEmpty() == true
                }
            }
        val provenance = action.requiredObject("provenance", action.requiredText("id", "manual action"))
        val (roleName, roleElement) = provenance.entries.first { (_, role) ->
            role.jsonObject.requiredArray("evidence", "manual role").isNotEmpty()
        }
        val role = roleElement.jsonObject
        val evidence = role.requiredArray("evidence", "manual role").toMutableList()
        evidence[0] = evidence[0].jsonObject.replacing("kind", JsonPrimitive("CONTAINER_ONLY"))
        val mutatedRole = role.replacing("evidence", JsonArray(evidence))
        val mutatedAction = action.replacing(
            "provenance",
            JsonObject(provenance + (roleName to mutatedRole)),
        )

        val failure = assertThrows(AssertionError::class.java) {
            validateManualAction(capabilityId, mutatedAction)
        }
        assertTrue(failure.message.orEmpty().contains("concrete production action evidence"), failure.message)
    }

    @Test
    fun `NR0-01 F2-2 rejects Reader boundary migration drift`() {
        val (capabilityId, action) = manualActions(F2_2_CAPABILITY_IDS)
            .first { (_, candidate) -> candidate["scope"]?.jsonPrimitive?.content == "READER_BOUNDARY" }
        val mutatedAction = action.replacing("migrationTag", JsonPrimitive("PLATFORM-PORT"))

        val failure = assertThrows(AssertionError::class.java) {
            validateManualAction(capabilityId, mutatedAction)
        }
        assertTrue(failure.message.orEmpty().contains("Reader boundary must remain Reader-owned"), failure.message)
    }

    @Test
    fun `NR0-01 F2-2 rejects a destructive detail action without confirmation`() {
        val (capabilityId, action) = manualActions(F2_2_CAPABILITY_IDS)
            .first { (_, candidate) -> candidate["risk"]?.jsonPrimitive?.content == "DESTRUCTIVE" }
        val confirmation = action.requiredObject("confirmation", action.requiredText("id", "destructive detail action"))
        val mutatedAction = action.replacing(
            "confirmation",
            confirmation.replacing("policy", JsonPrimitive("NONE")),
        )

        val failure = assertThrows(AssertionError::class.java) {
            validateManualAction(capabilityId, mutatedAction)
        }
        assertTrue(failure.message.orEmpty().contains("destructive action requires confirmation"), failure.message)
    }

    @Test
    fun `NR0-01 F2-2 rejects a GAP role without its manual search boundary`() {
        val (capabilityId, action, roleName) = manualActions(F2_2_CAPABILITY_IDS)
            .firstNotNullOf { (ownerId, candidate) ->
                candidate.requiredObject("provenance", "manual detail action").entries
                    .firstOrNull { (_, role) -> role.jsonObject.requiredText("status", "manual detail role") == "GAP" }
                    ?.let { (roleName, _) -> Triple(ownerId, candidate, roleName) }
            }
        val provenance = action.requiredObject("provenance", action.requiredText("id", "manual detail action"))
        val role = provenance.requiredObject(roleName, "manual detail action/$roleName")
        val mutatedAction = action.replacing(
            "provenance",
            JsonObject(provenance + (roleName to JsonObject(role - "searchBoundary"))),
        )

        val failure = assertThrows(AssertionError::class.java) {
            validateManualAction(capabilityId, mutatedAction)
        }
        assertTrue(failure.message.orEmpty().contains("requires object searchBoundary"), failure.message)
    }

    @Test
    fun `mechanical paths locators and context hashes resolve at their declared revisions`() {
        manifestItems().forEach { item ->
            val capabilityId = item.requiredInt("id", "capability")
            val inventory = item.requiredObject("actionInventory", "capability $capabilityId")

            inventory.requiredArray("sourceEntries", "capability $capabilityId").forEach { sourceElement ->
                val source = sourceElement.jsonObject
                val platform = source.requiredText("platform", "capability $capabilityId source")
                val path = source.requiredText("path", "capability $capabilityId source")
                assertProductionPath(path, "capability $capabilityId source")
                sourceAt(COMMIT_BY_PLATFORM.getValue(platform), path)
            }

            inventory.requiredArray("surfaces", "capability $capabilityId").forEach { surfaceElement ->
                val surface = surfaceElement.jsonObject
                surface.requiredArray("actions", "capability $capabilityId surface").forEach { actionElement ->
                    val action = actionElement.jsonObject
                    val actionId = action.requiredText("id", "capability $capabilityId action")
                    val provenance = action.requiredObject("provenance", actionId)
                    PROVENANCE_ROLES.forEach { role ->
                        val roleDecision = provenance.requiredObject(role, actionId)
                        if (capabilityId in MANUAL_CHECKPOINT_BY_CAPABILITY) {
                            roleDecision.requiredArray("evidence", "$actionId/$role").forEachIndexed { index, evidence ->
                                validateMechanicalEvidence(actionId, role, evidence.jsonObject, index)
                            }
                        } else {
                            validateLegacyMechanicalEvidence(actionId, role, roleDecision)
                        }
                    }
                }
            }
        }
    }

    private fun validateInventoryHeader(capabilityId: Int, inventory: JsonObject) {
        assertEquals("NR0-01", inventory.requiredText("task", "capability $capabilityId"))
        assertEquals(FIXED_REVISION, inventory.requiredText("fixedMainRef", "capability $capabilityId"))
        assertEquals(CURRENT_REVISION, inventory.requiredText("currentForkBaseline", "capability $capabilityId"))
    }

    private fun validateManualInventory(
        capabilityId: Int,
        inventory: JsonObject,
        actions: MutableMap<String, ActionRecord>,
    ) {
        assertEquals(2, inventory.requiredInt("schemaVersion", "capability $capabilityId"))
        val review = inventory.requiredObject("review", "capability $capabilityId")
        assertEquals("MANUAL", review.requiredText("mode", "capability $capabilityId review"))
        assertEquals(
            MANUAL_CHECKPOINT_BY_CAPABILITY.getValue(capabilityId),
            review.requiredText("checkpoint", "capability $capabilityId review"),
        )
        assertTrue(
            review.requiredText("status", "capability $capabilityId review") in setOf("REVIEW", "PASS"),
            "capability $capabilityId review status must be REVIEW or PASS",
        )
        assertTrue(
            inventory.requiredText("scopeDisposition", "capability $capabilityId") in
                setOf("NON_READER", "READER_OWNED_BOUNDARY"),
            "capability $capabilityId has invalid scope disposition",
        )
        assertEquals("PENDING_NR0-01.G", inventory.requiredText("sourceGraphStatus", "capability $capabilityId"))
        assertTrue(
            inventory.requiredArray("sourceEntries", "capability $capabilityId").isEmpty(),
            "capability $capabilityId source graph must remain empty until NR0-01.G",
        )

        inventory.requiredArray("surfaces", "capability $capabilityId").forEach { surfaceElement ->
            val surface = surfaceElement.jsonObject
            val surfaceId = surface.requiredText("id", "capability $capabilityId surface")
            surface.requiredText("kind", "capability $capabilityId/$surfaceId")
            surface.requiredArray("actions", "capability $capabilityId/$surfaceId").forEach { actionElement ->
                val action = actionElement.jsonObject
                validateManualAction(capabilityId, action)
                val actionId = action.requiredText("id", "$capabilityId/$surfaceId action")
                assertEquals(
                    null,
                    actions.put(
                        actionId,
                        ActionRecord(
                            capabilityId = capabilityId,
                            sourceIdsByRole = emptyMap(),
                            isManual = true,
                        ),
                    ),
                    "Action ID $actionId is duplicated",
                )
            }
        }
    }

    private fun validateManualAction(capabilityId: Int, action: JsonObject) {
        val actionId = action.requiredText("id", "capability $capabilityId action")
        assertEquals(capabilityId, action.requiredInt("ownerCapabilityId", actionId), "$actionId owner drifted")
        val scope = action.requiredText("scope", actionId)
        assertTrue(
            scope in setOf("NON_READER", "READER_BOUNDARY"),
            "$actionId scope must stay within the non-reader or Reader boundary inventory",
        )
        action.requiredText("surfaceId", actionId)
        action.requiredText("entryType", actionId)
        action.requiredText("intent", actionId)
        action.requiredText("expectedBehavior", actionId)
        action.requiredText("observableResult", actionId)

        val migrationTag = action.requiredText("migrationTag", actionId)
        assertTrue(migrationTag in MIGRATION_TAGS, "$actionId has invalid migration tag $migrationTag")
        if (scope == "READER_BOUNDARY") {
            assertEquals("READER-OWNED", migrationTag, "$actionId Reader boundary must remain Reader-owned")
        }
        if (migrationTag == "READER-OWNED") {
            assertEquals("READER_BOUNDARY", scope, "$actionId Reader-owned action must be a boundary action")
        }

        val implementationStatus = action.requiredText("implementationStatus", actionId)
        assertTrue(implementationStatus in ACTION_STATUSES, "$actionId has invalid implementation status")
        val followUp = action.requiredText("followUpTask", actionId)
        if (implementationStatus in setOf("PARTIAL", "GAP")) {
            assertTrue(followUp != "NONE", "$actionId $implementationStatus needs a follow-up task")
            action.requiredText("missingBehavior", actionId)
        }

        val confirmation = action.requiredObject("confirmation", actionId)
        val confirmationPolicy = confirmation.requiredText("policy", "$actionId confirmation")
        confirmation.requiredText("reason", "$actionId confirmation")
        val requiredRoles = action.requiredStringList("requiredEvidenceRoles", actionId).toSet()
        assertTrue(requiredRoles.isNotEmpty(), "$actionId needs explicit evidence roles")
        assertTrue(requiredRoles.all(EVIDENCE_ROLES::contains), "$actionId has invalid evidence roles")
        val risk = action["risk"]?.jsonPrimitive?.content ?: "NORMAL"
        assertTrue(risk in setOf("NORMAL", "DESTRUCTIVE"), "$actionId has invalid risk $risk")
        if (risk == "DESTRUCTIVE") {
            assertEquals("REQUIRED", confirmationPolicy, "$actionId destructive action requires confirmation")
            assertTrue("CONFIRMATION" in requiredRoles, "$actionId destructive action requires confirmation evidence")
        }

        val tracked = action.requiredObject("trackedUpstream", actionId)
        assertEquals("PENDING_NR0-01.G", tracked.requiredText("status", "$actionId tracked review"))
        val sourceGraph = action.requiredObject("sourceGraph", actionId)
        assertEquals("PENDING_NR0-01.G", sourceGraph.requiredText("status", "$actionId source graph"))
        assertTrue(
            sourceGraph.requiredArray("sourceEntryIds", "$actionId source graph").isEmpty(),
            "$actionId source graph must remain empty until NR0-01.G",
        )

        val provenance = action.requiredObject("provenance", actionId)
        assertEquals(PROVENANCE_ROLES, provenance.keys, "$actionId must have exactly three authority roles")
        provenance.forEach { (roleName, roleElement) ->
            validateManualRole(actionId, roleName, roleElement.jsonObject, requiredRoles)
        }

        if (migrationTag == "DESKTOP-PRODUCT") {
            listOf("fixedOriginal", "currentAndroid").forEach { role ->
                assertTrue(
                    provenance.requiredObject(role, actionId).requiredText("status", "$actionId/$role") != "PRESENT",
                    "$actionId Desktop product cannot forge $role PRESENT",
                )
            }
            assertTrue(
                provenance.requiredObject("desktop", actionId).requiredText("status", "$actionId/desktop") in
                    setOf("PRESENT", "PARTIAL"),
                "$actionId Desktop product needs a production Desktop chain",
            )
        }
    }

    private fun validateManualRole(
        actionId: String,
        roleName: String,
        role: JsonObject,
        requiredRoles: Set<String>,
    ) {
        val context = "$actionId/$roleName"
        assertEquals("MANUAL", role.requiredText("decisionMode", context), "$context must be a manual decision")
        val status = role.requiredText("status", context)
        assertTrue(status in ROLE_STATUSES, "$context has invalid status $status")
        role.requiredText("reason", context)
        val evidence = role.requiredArray("evidence", context)
        assertTrue(
            role.requiredArray("sourceEntryIds", context).isEmpty(),
            "$context source graph must remain empty until NR0-01.G",
        )

        evidence.forEach { evidenceElement ->
            val evidenceRecord = evidenceElement.jsonObject
            val roles = evidenceRecord.requiredStringList("roles", "$context evidence").toSet()
            assertEquals(
                "PRODUCTION",
                evidenceRecord["kind"]?.jsonPrimitive?.content ?: "PRODUCTION",
                "$context needs concrete production action evidence",
            )
            assertTrue(roles.isNotEmpty(), "$context evidence needs at least one role")
            assertTrue(roles.all(EVIDENCE_ROLES::contains), "$context evidence has invalid roles")
            evidenceRecord.requiredText("reason", "$context evidence")
        }

        when (status) {
            "PRESENT" -> {
                val provenRoles = evidence.flatMapTo(mutableSetOf()) {
                    it.jsonObject.requiredStringList("roles", "$context evidence")
                }
                val missingRoles = requiredRoles - provenRoles
                assertTrue(missingRoles.isEmpty(), "$context missing production evidence roles $missingRoles")
            }
            "PARTIAL" -> {
                assertTrue(evidence.isNotEmpty(), "$context PARTIAL needs a real production chain")
                role.requiredText("missingBehavior", context)
            }
            "GAP" -> {
                assertTrue(evidence.isEmpty(), "$context GAP cannot contain production evidence")
                role.requiredObject("searchBoundary", context).requiredArray("paths", "$context search boundary")
                role.requiredObject("searchBoundary", context).requiredArray("terms", "$context search boundary")
            }
            "EXEMPT" -> {
                assertTrue(evidence.isEmpty(), "$context EXEMPT cannot contain production evidence")
                role.requiredText("platformBoundary", context)
                role.requiredText("recheckCondition", context)
            }
        }
    }

    private fun collectLegacyInventory(
        capabilityId: Int,
        inventory: JsonObject,
        actions: MutableMap<String, ActionRecord>,
        sources: MutableMap<String, SourceRecord>,
        surfaceReferences: MutableList<SurfaceReference>,
    ) {
        inventory.requiredArray("surfaces", "capability $capabilityId").forEach { surfaceElement ->
            val surface = surfaceElement.jsonObject
            val surfaceId = surface.requiredText("id", "capability $capabilityId surface")
            val actionIds = surface.requiredArray("actions", "$capabilityId/$surfaceId").map { actionElement ->
                val action = actionElement.jsonObject
                val actionId = action.requiredText("id", "$capabilityId/$surfaceId action")
                val provenance = action.requiredObject("provenance", actionId)
                assertEquals(PROVENANCE_ROLES, provenance.keys, "$actionId must have exactly three authority roles")
                assertEquals(
                    null,
                    actions.put(
                        actionId,
                        ActionRecord(
                            capabilityId = capabilityId,
                            sourceIdsByRole = PROVENANCE_ROLES.associateWith { role ->
                                provenance.requiredObject(role, actionId).optionalStringList("sourceEntryIds")
                            },
                            isManual = false,
                        ),
                    ),
                    "Action ID $actionId is duplicated",
                )
                actionId
            }.toSet()

            surface.requiredObject("sourceEntryIds", "$capabilityId/$surfaceId").forEach { (role, ids) ->
                assertTrue(role in PROVENANCE_ROLES, "$capabilityId/$surfaceId has unknown role $role")
                surfaceReferences += SurfaceReference(
                    capabilityId = capabilityId,
                    surfaceId = surfaceId,
                    actionIds = actionIds,
                    role = role,
                    sourceIds = ids.jsonArray.map { it.jsonPrimitive.content },
                )
            }
        }

        inventory.requiredArray("sourceEntries", "capability $capabilityId").forEach { sourceElement ->
            val source = sourceElement.jsonObject
            val record = SourceRecord(
                capabilityId = capabilityId,
                id = source.requiredText("id", "capability $capabilityId source"),
                platform = source.requiredText("platform", "capability $capabilityId source"),
                kind = source.requiredText("kind", "capability $capabilityId source"),
                path = source.requiredText("path", "capability $capabilityId source"),
                symbol = source.requiredText("symbol", "capability $capabilityId source"),
                actionIds = source.optionalStringList("actionIds").toSet(),
            )
            assertEquals(sourceIdFor(record), record.id, "${record.id} is not a deterministic mechanical ID")
            assertEquals(null, sources.put(record.id, record), "Source ID ${record.id} is duplicated")
        }
    }

    private fun validateSourceRelationships(
        actions: Map<String, ActionRecord>,
        sources: Map<String, SourceRecord>,
        surfaceReferences: List<SurfaceReference>,
    ) {
        actions.filterValues { !it.isManual }.forEach { (actionId, action) ->
            action.sourceIdsByRole.forEach { (role, sourceIds) ->
                assertEquals(sourceIds.size, sourceIds.toSet().size, "$actionId/$role repeats a source reference")
                sourceIds.forEach sourceLoop@{ sourceId ->
                    // Legacy inventories are explicitly provisional while F1-F8 replace their source owners.
                    // G will rebuild every cross-capability relationship after all action semantics pass.
                    val source = sources[sourceId] ?: return@sourceLoop
                    if (source.capabilityId != action.capabilityId) return@sourceLoop
                    assertEquals(PLATFORM_BY_ROLE.getValue(role), source.platform, "$actionId/$role platform mismatch")
                    assertTrue(actionId in source.actionIds, "$sourceId must link back to $actionId")
                }
            }
        }

        sources.values.forEach { source ->
            source.actionIds.forEach actionLoop@{ actionId ->
                val action = actions[actionId] ?: return@actionLoop
                if (action.isManual || action.capabilityId != source.capabilityId) return@actionLoop
                assertTrue(
                    source.id in action.sourceIdsByRole.getValue(ROLE_BY_PLATFORM.getValue(source.platform)),
                    "${source.id}/$actionId relationship is not bidirectional",
                )
            }
        }

        surfaceReferences.forEach { reference ->
            assertEquals(
                reference.sourceIds.size,
                reference.sourceIds.toSet().size,
                "${reference.capabilityId}/${reference.surfaceId}/${reference.role} repeats a source reference",
            )
            reference.sourceIds.forEach sourceLoop@{ sourceId ->
                val source = sources[sourceId] ?: return@sourceLoop
                if (source.capabilityId != reference.capabilityId) return@sourceLoop
                assertEquals(PLATFORM_BY_ROLE.getValue(reference.role), source.platform)
                assertTrue(
                    source.actionIds.isEmpty() || source.actionIds.any(reference.actionIds::contains),
                    "$sourceId does not serve an action on ${reference.surfaceId}",
                )
            }
        }
    }

    private fun validateLegacyMechanicalEvidence(actionId: String, role: String, evidence: JsonObject) {
        val context = "$actionId/$role"
        val status = evidence.requiredText("status", context)
        if (status != "PRESENT") {
            evidence.requiredText("reason", context)
            return
        }
        validateMechanicalFields(context, role, evidence, requireManualFields = false)
    }

    private fun validateMechanicalEvidence(actionId: String, role: String, evidence: JsonObject, index: Int) {
        validateMechanicalFields("$actionId/$role/evidence[$index]", role, evidence, requireManualFields = true)
    }

    private fun validateMechanicalFields(
        context: String,
        role: String,
        evidence: JsonObject,
        requireManualFields: Boolean,
    ) {
        val expectedRevision = REVISION_BY_ROLE.getValue(role)
        val revision = evidence.requiredText("revision", context)
        assertEquals(expectedRevision, revision, "$context revision drifted")
        if (requireManualFields) {
            assertEquals(
                PLATFORM_BY_ROLE.getValue(role),
                evidence.requiredText("platform", context),
                "$context platform drifted",
            )
        }
        val path = evidence.requiredText("path", context)
        val line = evidence.requiredInt("line", context)
        val locator = evidence.requiredText("locator", context)
        evidence.requiredText("symbol", context)
        if (requireManualFields) evidence.requiredText("reason", context)
        assertProductionPath(path, context)

        val source = sourceAt(COMMIT_BY_ROLE.getValue(role), path)
        val lines = sourceLines(source)
        assertTrue(line in 1..lines.size, "$context line $line is outside $revision:$path")
        assertEquals(locator, lines[line - 1].trim(), "$context locator drifted")
        assertEquals(actionContextHash(lines, line), evidence.requiredText("contextHash", context), "$context hash drifted")
    }

    private fun firstManualAction(): Pair<Int, JsonObject> = manualActions(F1_CAPABILITY_IDS).first()

    private fun manualActions(
        capabilityIds: Set<Int> = MANUAL_CHECKPOINT_BY_CAPABILITY.keys,
    ): List<Pair<Int, JsonObject>> = manifestItems()
        .filter { it.requiredInt("id", "capability") in capabilityIds }
        .flatMap { item ->
            val capabilityId = item.requiredInt("id", "capability")
            item.requiredObject("actionInventory", "capability $capabilityId")
                .requiredArray("surfaces", "capability $capabilityId")
                .flatMap { surface ->
                    surface.jsonObject.requiredArray("actions", "capability $capabilityId surface")
                        .map { capabilityId to it.jsonObject }
                }
        }

    private fun actionContextHash(lines: List<String>, line: Int): String {
        val from = (line - 5).coerceAtLeast(0)
        val to = (line + 3).coerceAtMost(lines.lastIndex)
        return sha256(lines.subList(from, to + 1).joinToString("\n") { it.trimEnd() })
    }

    private fun sourceLines(source: String): List<String> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        if (lines.lastOrNull().isNullOrEmpty()) lines.removeLast()
        return lines
    }

    private fun sourceIdFor(record: SourceRecord): String =
        "src-${sha256(listOf(record.platform, record.kind, record.path, record.symbol).joinToString("|")).take(16)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun assertProductionPath(path: String, context: String) {
        val normalized = "/${path.replace('\\', '/').lowercase(Locale.ROOT)}/"
        assertFalse(TEST_PATH_MARKERS.any(normalized::contains), "$context points to non-production source $path")
    }

    private fun sourceAt(commit: String, path: String): String =
        sourceCache.getOrPut(commit to path) {
            val process = ProcessBuilder("git", "show", "$commit:$path")
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertEquals(0, process.waitFor(), "git show $commit:$path failed:\n$output")
            output
        }

    private fun manifestItems(): List<JsonObject> =
        Json.parseToJsonElement(Files.readString(repositoryRoot.resolve(PARITY_MANIFEST))).jsonArray.map { it.jsonObject }

    private fun JsonObject.requiredText(key: String, context: String): String {
        val value = get(key)?.jsonPrimitive?.content
        assertTrue(!value.isNullOrBlank(), "$context requires nonblank $key")
        return value.orEmpty()
    }

    private fun JsonObject.requiredInt(key: String, context: String): Int = requiredText(key, context).toInt()

    private fun JsonObject.requiredObject(key: String, context: String): JsonObject =
        get(key)?.jsonObject ?: throw AssertionError("$context requires object $key")

    private fun JsonObject.requiredArray(key: String, context: String): JsonArray =
        get(key)?.jsonArray ?: throw AssertionError("$context requires array $key")

    private fun JsonObject.optionalArray(key: String): JsonArray? = get(key)?.jsonArray

    private fun JsonObject.requiredStringList(key: String, context: String): List<String> =
        requiredArray(key, context).map { it.jsonPrimitive.content }

    private fun JsonObject.optionalStringList(key: String): List<String> =
        get(key)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun JsonObject.replacing(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))

    private fun repositoryRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .firstOrNull { Files.exists(it.resolve(PARITY_MANIFEST)) }
            ?: error("Could not locate repository root from ${System.getProperty("user.dir")}")

    private data class ActionRecord(
        val capabilityId: Int,
        val sourceIdsByRole: Map<String, List<String>>,
        val isManual: Boolean,
    )

    private data class SourceRecord(
        val capabilityId: Int,
        val id: String,
        val platform: String,
        val kind: String,
        val path: String,
        val symbol: String,
        val actionIds: Set<String>,
    )

    private data class SurfaceReference(
        val capabilityId: Int,
        val surfaceId: String,
        val actionIds: Set<String>,
        val role: String,
        val sourceIds: List<String>,
    )

    private data class EvidenceMutationCandidate(
        val capabilityId: Int,
        val action: JsonObject,
        val roleName: String,
        val role: JsonObject,
    )

    private data class EvidenceMutation(
        val capabilityId: Int,
        val action: JsonObject,
        val roleName: String,
        val missingRole: String,
    )

    private companion object {
        const val PARITY_MANIFEST = "app-desktop/src/test/resources/parity/parity-manifest.json"
        const val FIXED_COMMIT = "6fbf6dfca203d99d6dd32137f2df97ced40c81b8"
        const val CURRENT_COMMIT = "95b82fc1039f772d4f8688855f2b06e16f983eb5"
        const val FIXED_REVISION = "main@$FIXED_COMMIT"
        const val CURRENT_REVISION = "current-fork@$CURRENT_COMMIT"
        const val READER_BOUNDARY_CAPABILITY_ID = 9

        val F1_CAPABILITY_IDS = sortedSetOf(3, 4, 7, 8, 9, 10, 11, 12)
        val F2_1_CAPABILITY_IDS = sortedSetOf(16, 17, 19)
        val F2_2_CAPABILITY_IDS = sortedSetOf(22, 24, 26)
        val MANUAL_CHECKPOINT_BY_CAPABILITY = buildMap {
            F1_CAPABILITY_IDS.forEach { put(it, "NR0-01.F1") }
            F2_1_CAPABILITY_IDS.forEach { put(it, "NR0-01.F2.1") }
            F2_2_CAPABILITY_IDS.forEach { put(it, "NR0-01.F2.2") }
        }.toSortedMap()
        val PROVENANCE_ROLES = setOf("fixedOriginal", "currentAndroid", "desktop")
        val PLATFORM_BY_ROLE = mapOf(
            "fixedOriginal" to "FIXED_ORIGINAL",
            "currentAndroid" to "CURRENT_ANDROID",
            "desktop" to "DESKTOP",
        )
        val ROLE_BY_PLATFORM = PLATFORM_BY_ROLE.entries.associate { (role, platform) -> platform to role }
        val COMMIT_BY_ROLE = mapOf(
            "fixedOriginal" to FIXED_COMMIT,
            "currentAndroid" to CURRENT_COMMIT,
            "desktop" to CURRENT_COMMIT,
        )
        val COMMIT_BY_PLATFORM = mapOf(
            "FIXED_ORIGINAL" to FIXED_COMMIT,
            "CURRENT_ANDROID" to CURRENT_COMMIT,
            "DESKTOP" to CURRENT_COMMIT,
        )
        val REVISION_BY_ROLE = mapOf(
            "fixedOriginal" to FIXED_REVISION,
            "currentAndroid" to CURRENT_REVISION,
            "desktop" to CURRENT_REVISION,
        )
        val MIGRATION_TAGS = setOf(
            "SHARE-DIRECT",
            "SHARE-EXTRACT",
            "PLATFORM-PORT",
            "DESKTOP-PRODUCT",
            "READER-OWNED",
            "PLATFORM-EXEMPT",
        )
        val ACTION_STATUSES = setOf("PRESENT", "PARTIAL", "GAP")
        val ROLE_STATUSES = ACTION_STATUSES + "EXEMPT"
        val EVIDENCE_ROLES = setOf("ENTRY", "EFFECT", "PERSISTENCE", "CONFIRMATION", "FEEDBACK")
        val TEST_PATH_MARKERS = listOf("/test/", "/tests/", "/androidtest/", "/commontest/", "/jvmtest/")
    }
}
