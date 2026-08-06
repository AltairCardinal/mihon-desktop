package mihon.desktop.parity

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class NonReaderEvidencePolicyTest {
    private val policy = ManualInventoryEvidencePolicy()

    @TestFactory
    fun `handwritten destructive examples reject invalid semantic evidence`(): List<DynamicTest> =
        invalidFixtures().map { fixture ->
            DynamicTest.dynamicTest(fixture.name) {
                val failure = assertThrows(IllegalArgumentException::class.java) {
                    policy.validate(fixture.action)
                }
                assertTrue(
                    failure.message.orEmpty().contains(fixture.expectedFailure),
                    "${fixture.name} failed for an unexpected reason: ${failure.message}",
                )
            }
        }

    @Test
    fun `two interactive actions and one reviewed shared executor remain valid`() {
        val validShare = validInteractiveAction(
            id = "external.share",
            capabilityId = 82,
            behavior = "invoke-platform-share-sheet",
        )
        val validSetting = ManualActionDecision(
            id = "settings.theme.persist",
            ownerCapabilityId = 90,
            scope = InventoryScope.NON_READER,
            migrationTag = MigrationTag.SHARED_EXECUTOR,
            expectedBehavior = "persist-theme-preference",
            requiredRoles = setOf(EvidenceRole.ENTRY, EvidenceRole.EFFECT, EvidenceRole.PERSISTENCE),
            evidence = listOf(
                productionEvidence(
                    id = "theme-ui-handler",
                    capabilityId = 90,
                    behavior = "persist-theme-preference",
                    roles = setOf(EvidenceRole.ENTRY, EvidenceRole.EFFECT),
                ),
                productionEvidence(
                    id = "theme-preference-write",
                    capabilityId = 90,
                    behavior = "persist-theme-preference",
                    roles = setOf(EvidenceRole.PERSISTENCE),
                ),
            ),
            roleDecisions = presentOnDesktop(),
        )
        val reviewedSharedExecutor = validInteractiveAction(
            id = "library-item.remove",
            capabilityId = 17,
            behavior = "remove-library-membership",
        ).copy(
            evidence = listOf(
                productionEvidence(
                    id = "library-remove-entry",
                    capabilityId = 17,
                    behavior = "remove-library-membership",
                    roles = setOf(EvidenceRole.ENTRY, EvidenceRole.FEEDBACK),
                ),
                productionEvidence(
                    id = "shared-membership-use-case",
                    capabilityId = 16,
                    behavior = "remove-library-membership",
                    roles = setOf(EvidenceRole.EFFECT),
                    sharedUses = mapOf(
                        17 to SharedUseReview(
                            consumerPath = "app-desktop/src/main/kotlin/example/LibraryItemScreenModel.kt",
                            consumerLocator = "updateLibraryMembership(mangaId, false)",
                            reason = "Capability 17 production handler calls the capability 16 membership use case.",
                        ),
                    ),
                ),
            ),
        )

        listOf(validShare, validSetting, reviewedSharedExecutor).forEach { action ->
            assertDoesNotThrow { policy.validate(action) }
        }
    }

    private fun invalidFixtures(): List<InvalidFixture> {
        val validShare = validInteractiveAction(
            id = "external.share",
            capabilityId = 82,
            behavior = "invoke-platform-share-sheet",
        )
        return listOf(
            InvalidFixture(
                name = "external share cannot bind AndroidCookieJar",
                action = validShare.copy(
                    evidence = validShare.evidence + productionEvidence(
                        id = "AndroidCookieJar",
                        capabilityId = 82,
                        behavior = "store-network-cookies",
                        roles = setOf(EvidenceRole.EFFECT),
                    ),
                ),
                expectedFailure = "behavior contract mismatch",
            ),
            InvalidFixture(
                name = "delete cannot bind a generic Cancel control",
                action = invalidSingleEvidenceAction(
                    id = "history.delete-all",
                    behavior = "delete-history-records",
                    evidenceKind = EvidenceKind.GENERIC_CONTAINER_CONTROL,
                ),
                expectedFailure = "GENERIC_CONTAINER_CONTROL is forbidden",
            ),
            InvalidFixture(
                name = "pause cannot bind an interface default false implementation",
                action = invalidSingleEvidenceAction(
                    id = "downloads.pause-all",
                    behavior = "pause-download-execution",
                    evidenceKind = EvidenceKind.DECLARATION_ONLY,
                ),
                expectedFailure = "DECLARATION_ONLY is forbidden",
            ),
            InvalidFixture(
                name = "setting cannot bind only a preference key or default",
                action = invalidSingleEvidenceAction(
                    id = "settings.language.persist",
                    behavior = "persist-app-language",
                    evidenceKind = EvidenceKind.PREFERENCE_DECLARATION_ONLY,
                ),
                expectedFailure = "PREFERENCE_DECLARATION_ONLY is forbidden",
            ),
            InvalidFixture(
                name = "detail refresh cannot bind a test or preview symbol",
                action = invalidSingleEvidenceAction(
                    id = "manga-detail.refresh",
                    behavior = "refresh-manga-details",
                    evidenceKind = EvidenceKind.TEST_OR_PREVIEW,
                ),
                expectedFailure = "TEST_OR_PREVIEW is forbidden",
            ),
            InvalidFixture(
                name = "history clear cannot bind navigator pop",
                action = invalidSingleEvidenceAction(
                    id = "history.clear",
                    behavior = "clear-history-records",
                    evidenceKind = EvidenceKind.GENERIC_CONTAINER_CONTROL,
                ),
                expectedFailure = "GENERIC_CONTAINER_CONTROL is forbidden",
            ),
            InvalidFixture(
                name = "tracking update cannot bind a repository getter",
                action = invalidSingleEvidenceAction(
                    id = "tracking.update-status",
                    behavior = "push-tracker-status",
                    evidenceKind = EvidenceKind.READ_ONLY_ACCESSOR,
                ),
                expectedFailure = "READ_ONLY_ACCESSOR is forbidden",
            ),
            InvalidFixture(
                name = "save cannot bind an unrelated browser adapter",
                action = validInteractiveAction(
                    id = "external.save",
                    capabilityId = 82,
                    behavior = "save-exported-file",
                ).let { action ->
                    action.copy(
                        evidence = action.evidence + productionEvidence(
                            id = "BrowserLauncher",
                            capabilityId = 82,
                            behavior = "open-browser-url",
                            roles = setOf(EvidenceRole.EFFECT),
                        ),
                    )
                },
                expectedFailure = "behavior contract mismatch",
            ),
            InvalidFixture(
                name = "source cannot cross capability boundaries without reviewed consumers",
                action = validInteractiveAction(
                    id = "library-item.remove",
                    capabilityId = 17,
                    behavior = "remove-library-membership",
                ).let { action ->
                    action.copy(
                        evidence = action.evidence + productionEvidence(
                            id = "unreviewed-shared-source",
                            capabilityId = 16,
                            behavior = "remove-library-membership",
                            roles = setOf(EvidenceRole.EFFECT),
                        ),
                    )
                },
                expectedFailure = "missing reviewed shared-use consumer",
            ),
            InvalidFixture(
                name = "Desktop product cannot forge fixed-original PRESENT",
                action = validInteractiveAction(
                    id = "desktop.window-privacy",
                    capabilityId = 85,
                    behavior = "hide-window-from-capture",
                ).copy(
                    migrationTag = MigrationTag.DESKTOP_PRODUCT,
                    roleDecisions = presentOnDesktop() +
                        (AuthorityRole.FIXED_ORIGINAL to RoleDecision(ImplementationStatus.PRESENT)),
                ),
                expectedFailure = "desktop-product cannot claim fixed-original PRESENT",
            ),
            InvalidFixture(
                name = "platform gap cannot be automatically marked EXEMPT",
                action = validShare.copy(
                    roleDecisions = mapOf(
                        AuthorityRole.DESKTOP to RoleDecision(
                            status = ImplementationStatus.EXEMPT,
                            mode = DecisionMode.AUTOMATIC,
                            reason = "No candidate was found.",
                        ),
                    ),
                ),
                expectedFailure = "EXEMPT must be a manual decision",
            ),
            InvalidFixture(
                name = "Reader page progress cannot enter non-reader inventory",
                action = validInteractiveAction(
                    id = "reader.page-progress",
                    capabilityId = 43,
                    behavior = "persist-reader-page-progress",
                ).copy(scope = InventoryScope.READER_INTERNAL),
                expectedFailure = "reader-internal action is outside non-reader inventory",
            ),
            InvalidFixture(
                name = "tracked path existence cannot hide removed behavior",
                action = validShare.copy(
                    trackedReview = TrackedReview(
                        declared = TrackedDisposition.UNCHANGED,
                        observed = TrackedDisposition.REMOVED,
                        sourcePathStillExists = true,
                        reason = "The file remains, but the share handler was deleted.",
                    ),
                ),
                expectedFailure = "tracked UNCHANGED contradicts observed REMOVED",
            ),
        )
    }

    private fun validInteractiveAction(
        id: String,
        capabilityId: Int,
        behavior: String,
    ) = ManualActionDecision(
        id = id,
        ownerCapabilityId = capabilityId,
        scope = InventoryScope.NON_READER,
        migrationTag = MigrationTag.PLATFORM_ADAPTER,
        expectedBehavior = behavior,
        requiredRoles = setOf(EvidenceRole.ENTRY, EvidenceRole.EFFECT, EvidenceRole.FEEDBACK),
        evidence = listOf(
            productionEvidence(
                id = "$id-entry-and-feedback",
                capabilityId = capabilityId,
                behavior = behavior,
                roles = setOf(EvidenceRole.ENTRY, EvidenceRole.FEEDBACK),
            ),
            productionEvidence(
                id = "$id-effect",
                capabilityId = capabilityId,
                behavior = behavior,
                roles = setOf(EvidenceRole.EFFECT),
            ),
        ),
        roleDecisions = presentOnDesktop(),
    )

    private fun invalidSingleEvidenceAction(
        id: String,
        behavior: String,
        evidenceKind: EvidenceKind,
    ) = ManualActionDecision(
        id = id,
        ownerCapabilityId = 66,
        scope = InventoryScope.NON_READER,
        migrationTag = MigrationTag.SHARED_EXECUTOR,
        expectedBehavior = behavior,
        requiredRoles = setOf(EvidenceRole.ENTRY, EvidenceRole.EFFECT),
        evidence = listOf(
            ManualEvidence(
                id = "$id-invalid-evidence",
                ownerCapabilityId = 66,
                kind = evidenceKind,
                behavior = behavior,
                roles = setOf(EvidenceRole.ENTRY, EvidenceRole.EFFECT),
            ),
        ),
        roleDecisions = presentOnDesktop(),
    )

    private fun productionEvidence(
        id: String,
        capabilityId: Int,
        behavior: String,
        roles: Set<EvidenceRole>,
        sharedUses: Map<Int, SharedUseReview> = emptyMap(),
    ) = ManualEvidence(
        id = id,
        ownerCapabilityId = capabilityId,
        kind = EvidenceKind.PRODUCTION,
        behavior = behavior,
        roles = roles,
        sharedUses = sharedUses,
    )

    private fun presentOnDesktop() = mapOf(
        AuthorityRole.DESKTOP to RoleDecision(
            status = ImplementationStatus.PRESENT,
            mode = DecisionMode.MANUAL,
            reason = "The handwritten fixture includes a production entry, effect, and observable result.",
        ),
    )

    private data class InvalidFixture(
        val name: String,
        val action: ManualActionDecision,
        val expectedFailure: String,
    )
}
