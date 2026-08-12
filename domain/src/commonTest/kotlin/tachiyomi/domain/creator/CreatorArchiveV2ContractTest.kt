package tachiyomi.domain.creator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.ArchiveDeletionPolicy
import tachiyomi.domain.creator.model.ArchiveUpsertOutcome
import tachiyomi.domain.creator.model.CanonicalWorkPortableKey
import tachiyomi.domain.creator.model.CreatorArchiveLanguageTag
import tachiyomi.domain.creator.model.CreatorArchivePhysicalSchema
import tachiyomi.domain.creator.model.CreatorArchiveSubjectKey
import tachiyomi.domain.creator.model.CreatorArchiveV2Contract
import tachiyomi.domain.creator.model.CreatorArchiveV2Policy
import tachiyomi.domain.creator.model.CreatorPortableKey
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.DiscoveryCommitPlan
import tachiyomi.domain.creator.model.DiscoveryKind
import tachiyomi.domain.creator.model.DiscoveryLease
import tachiyomi.domain.creator.model.DiscoveryReadState
import tachiyomi.domain.creator.model.DiscoveryRunState
import tachiyomi.domain.creator.model.DiscoveryStateVector
import tachiyomi.domain.creator.model.LanguageAssertionContract
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.LeaseAcquireResult
import tachiyomi.domain.creator.model.NotificationDeliveryState
import tachiyomi.domain.creator.model.ReviewDisposition
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WatchBaselineState
import tachiyomi.domain.creator.model.WatchResultPolicyContract
import tachiyomi.domain.creator.model.WatchScopeContract
import tachiyomi.domain.creator.model.WorkDecisionContract
import tachiyomi.domain.creator.model.WorkDecisionState

class CreatorArchiveV2ContractTest {

    @Test
    fun `schema migration backup field and legacy expiry points are frozen`() {
        assertEquals(15L, CreatorArchiveV2Contract.CURRENT_SCHEMA_VERSION)
        assertEquals(16L, CreatorArchiveV2Contract.TARGET_SCHEMA_VERSION)
        assertEquals("15.sqm", CreatorArchiveV2Contract.TARGET_MIGRATION)
        assertEquals(107, CreatorArchiveV2Contract.BACKUP_ENVELOPE_FIELD)
        assertEquals(1, CreatorArchiveV2Contract.BACKUP_SECTION_VERSION)
        assertEquals("AA4-02", CreatorArchiveV2Contract.LEGACY_READ_BRIDGE_REMOVAL_TASK)
        assertEquals("AA7-02", CreatorArchiveV2Contract.LEGACY_TABLE_REMOVAL_TASK)
    }

    @Test
    fun `repository upsert outcome distinguishes inserted updated and unchanged`() {
        val key = SourceWorkNaturalKey(1L, "/stable/work")

        assertEquals(key, ArchiveUpsertOutcome.Inserted(key).value)
        assertEquals(key, ArchiveUpsertOutcome.Updated(key).value)
        assertEquals(key, ArchiveUpsertOutcome.Unchanged(key).value)
    }

    @Test
    fun `language assertion subject keys use portable identities instead of local ids`() {
        assertEquals(
            "source:7:/stable/work",
            CreatorArchiveSubjectKey.sourceWork(SourceWorkNaturalKey(7L, "/stable/work")),
        )
        assertEquals(
            "canonical:work-portable",
            CreatorArchiveSubjectKey.canonicalWork(CanonicalWorkPortableKey("work-portable")),
        )
        assertEquals(
            "creator:creator-portable",
            CreatorArchiveSubjectKey.creator(CreatorPortableKey("creator-portable")),
        )
    }

    @Test
    fun `language tags share one normalized validity contract`() {
        val vectors = mapOf(
            "EN" to "en",
            " zh-Hans " to "zh-hans",
            "pt-BR" to "pt-br",
            "BL" to "und",
            "unknown" to "und",
            "abcd" to "und",
            "en-a" to "und",
            "en-abcdefghi" to "und",
            "en_US" to "und",
        )

        vectors.forEach { (raw, expected) ->
            assertEquals(expected, CreatorArchiveLanguageTag.normalize(raw), raw)
        }
    }

    @Test
    fun `physical table names unique keys and deletion policies are frozen`() {
        val tables = CreatorArchivePhysicalSchema.tables

        assertEquals(20, tables.size)
        assertEquals(tables.size, tables.map { it.name }.toSet().size)
        assertTrue(tables.all { it.name.startsWith("author_archive_") && it.uniqueKeys.isNotEmpty() })
        assertEquals(
            ArchiveDeletionPolicy.SOFT_DELETE,
            tables.single { it.name == "author_archive_creators" }.deletionPolicy,
        )
        assertEquals(
            ArchiveDeletionPolicy.SET_NULL_OR_RETAIN,
            tables.single { it.name == "author_archive_source_works" }.deletionPolicy,
        )
        assertEquals(
            ArchiveDeletionPolicy.RETAIN_HISTORY,
            tables.single { it.name == "author_archive_work_decisions" }.deletionPolicy,
        )
    }

    @Test
    fun `read review and delivery remain independent state dimensions`() {
        val original = DiscoveryStateVector(
            readState = DiscoveryReadState.UNSEEN,
            reviewDisposition = ReviewDisposition.PENDING,
            deliveryState = NotificationDeliveryState.FAILED,
        )

        val reviewed = original.copy(reviewDisposition = ReviewDisposition.IGNORED)

        assertEquals(DiscoveryReadState.UNSEEN, reviewed.readState)
        assertEquals(NotificationDeliveryState.FAILED, reviewed.deliveryState)
        assertEquals(ReviewDisposition.IGNORED, reviewed.reviewDisposition)
        assertEquals(
            ReviewDisposition.IGNORED,
            CreatorArchiveV2Policy.preserveReviewDisposition(ReviewDisposition.IGNORED, ReviewDisposition.PENDING),
        )
    }

    @Test
    fun `source scope and reading language result policy are separate values`() {
        val scope = WatchScopeContract(sourceIds = setOf(1L, 2L))
        val resultPolicy = WatchResultPolicyContract(
            readingLanguageTags = setOf("ja"),
            includeProbable = true,
        )

        assertEquals(setOf(1L, 2L), scope.sourceIds)
        assertEquals(setOf("ja"), resultPolicy.readingLanguageTags)
        assertTrue(resultPolicy.includeProbable)
        assertFalse(resultPolicy.includeUnknown)
    }

    @Test
    fun `baseline archives while later inserted watch relation emits event and outbox together`() {
        val key = SourceWorkNaturalKey(1L, "/stable/work")

        assertEquals(
            DiscoveryCommitPlan.BaselineArchive,
            CreatorArchiveV2Policy.planDiscoveryCommit(
                baselineState = WatchBaselineState.NEEDS_BASELINE,
                relationVerification = CreatorRelationVerification.VERIFIED,
                watchRelationOutcome = ArchiveUpsertOutcome.Inserted(key),
                alreadyInLibraryOrHistory = false,
                confirmedCanonicalWork = false,
                idempotencyKey = "baseline-does-not-use-this",
            ),
        )
        val event = CreatorArchiveV2Policy.planDiscoveryCommit(
            baselineState = WatchBaselineState.BASELINED,
            relationVerification = CreatorRelationVerification.VERIFIED,
            watchRelationOutcome = ArchiveUpsertOutcome.Inserted(key),
            alreadyInLibraryOrHistory = false,
            confirmedCanonicalWork = true,
            idempotencyKey = "watch-1:source-1:/stable/work",
        )

        assertInstanceOf(DiscoveryCommitPlan.EventWithOutbox::class.java, event)
        event as DiscoveryCommitPlan.EventWithOutbox
        assertEquals(DiscoveryKind.NEW_SOURCE_VERSION, event.kind)
        assertEquals("watch-1:source-1:/stable/work", event.idempotencyKey)
    }

    @Test
    fun `existing relation possible identity and library history do not create discovery`() {
        val key = SourceWorkNaturalKey(1L, "/stable/work")
        val existing = CreatorArchiveV2Policy.planDiscoveryCommit(
            WatchBaselineState.BASELINED,
            CreatorRelationVerification.VERIFIED,
            ArchiveUpsertOutcome.Unchanged(key),
            alreadyInLibraryOrHistory = false,
            confirmedCanonicalWork = false,
            idempotencyKey = "unused",
        )
        val possible = CreatorArchiveV2Policy.planDiscoveryCommit(
            WatchBaselineState.BASELINED,
            CreatorRelationVerification.POSSIBLE,
            ArchiveUpsertOutcome.Inserted(key),
            alreadyInLibraryOrHistory = false,
            confirmedCanonicalWork = false,
            idempotencyKey = "unused",
        )
        val archived = CreatorArchiveV2Policy.planDiscoveryCommit(
            WatchBaselineState.BASELINED,
            CreatorRelationVerification.VERIFIED,
            ArchiveUpsertOutcome.Inserted(key),
            alreadyInLibraryOrHistory = true,
            confirmedCanonicalWork = false,
            idempotencyKey = "unused",
        )

        assertInstanceOf(DiscoveryCommitPlan.NoEvent::class.java, existing)
        assertInstanceOf(DiscoveryCommitPlan.NoEvent::class.java, possible)
        assertInstanceOf(DiscoveryCommitPlan.NoEvent::class.java, archived)
    }

    @Test
    fun `manual work decision survives algorithm and changes only through explicit action`() {
        val rejected = WorkDecisionContract(WorkDecisionState.REJECTED, DecisionActor.USER, explicit = true)
        val suggestion = WorkDecisionContract(WorkDecisionState.SUGGESTED, DecisionActor.ALGORITHM, explicit = false)
        val restoreConflict = WorkDecisionContract(WorkDecisionState.CONFIRMED, DecisionActor.RESTORE, explicit = true)
        val explicitUndo = WorkDecisionContract(WorkDecisionState.SUGGESTED, DecisionActor.USER, explicit = true)

        assertEquals(rejected, CreatorArchiveV2Policy.resolveWorkDecision(rejected, suggestion))
        assertEquals(rejected, CreatorArchiveV2Policy.resolveWorkDecision(rejected, restoreConflict))
        assertEquals(explicitUndo, CreatorArchiveV2Policy.resolveWorkDecision(rejected, explicitUndo))
        assertThrows(IllegalArgumentException::class.java) {
            CreatorArchiveV2Policy.resolveWorkDecision(
                current = null,
                proposed = WorkDecisionContract(
                    WorkDecisionState.CONFIRMED,
                    DecisionActor.ALGORITHM,
                    explicit = false,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CreatorArchiveV2Policy.resolveWorkDecision(
                current = null,
                proposed = WorkDecisionContract(
                    WorkDecisionState.CONFIRMED,
                    DecisionActor.USER,
                    explicit = false,
                ),
            )
        }
    }

    @Test
    fun `manual language wins and withdrawal restores automatic projection per dimension`() {
        val structured = LanguageAssertionContract(
            dimension = LanguageDimension.READING,
            tag = "ja",
            confidence = 1.0,
            evidenceKind = LanguageEvidenceKind.STRUCTURED_METADATA,
        )
        val manual = LanguageAssertionContract(
            dimension = LanguageDimension.READING,
            tag = "zh-Hans",
            confidence = 1.0,
            evidenceKind = LanguageEvidenceKind.MANUAL,
        )

        val overridden = CreatorArchiveV2Policy.projectLanguage(LanguageDimension.READING, listOf(structured, manual))
        val restored = CreatorArchiveV2Policy.projectLanguage(
            LanguageDimension.READING,
            listOf(structured, manual, manual.copy(withdrawn = true)),
        )
        val original = CreatorArchiveV2Policy.projectLanguage(LanguageDimension.ORIGINAL, listOf(structured, manual))

        assertEquals("zh-Hans", overridden.tag)
        assertEquals(LanguageCertainty.CONFIRMED, overridden.certainty)
        assertEquals("ja", restored.tag)
        assertEquals("und", original.tag)
        assertEquals(LanguageCertainty.UNKNOWN, original.certainty)
    }

    @Test
    fun `lease excludes concurrent owner and cancellation is terminal`() {
        val current = DiscoveryLease("owner-a", expiresAtMillis = 200L)
        val competing = DiscoveryLease("owner-b", expiresAtMillis = 300L)

        assertInstanceOf(
            LeaseAcquireResult.Busy::class.java,
            CreatorArchiveV2Policy.acquireLease(current, competing, nowMillis = 100L),
        )
        assertInstanceOf(
            LeaseAcquireResult.Acquired::class.java,
            CreatorArchiveV2Policy.acquireLease(current, competing, nowMillis = 200L),
        )
        assertTrue(CreatorArchiveV2Policy.canTransitionRun(DiscoveryRunState.RUNNING, DiscoveryRunState.CANCELLED))
        assertFalse(CreatorArchiveV2Policy.canTransitionRun(DiscoveryRunState.CANCELLED, DiscoveryRunState.SUCCEEDED))
    }

    @Test
    fun `baseline read review delivery and run transitions remain explicit`() {
        assertTrue(
            CreatorArchiveV2Policy.canTransitionBaseline(
                WatchBaselineState.NEEDS_BASELINE,
                WatchBaselineState.BASELINED,
                sourceScopeChanged = false,
            ),
        )
        assertFalse(
            CreatorArchiveV2Policy.canTransitionBaseline(
                WatchBaselineState.BASELINED,
                WatchBaselineState.NEEDS_BASELINE,
                sourceScopeChanged = false,
            ),
        )
        assertTrue(CreatorArchiveV2Policy.canTransitionRead(DiscoveryReadState.UNSEEN, DiscoveryReadState.SEEN))
        assertFalse(CreatorArchiveV2Policy.canTransitionRead(DiscoveryReadState.SEEN, DiscoveryReadState.UNSEEN))
        assertTrue(
            CreatorArchiveV2Policy.canTransitionReview(
                ReviewDisposition.PENDING,
                ReviewDisposition.IGNORED,
                explicitUserAction = true,
            ),
        )
        assertFalse(
            CreatorArchiveV2Policy.canTransitionReview(
                ReviewDisposition.PENDING,
                ReviewDisposition.IGNORED,
                explicitUserAction = false,
            ),
        )
        assertFalse(
            CreatorArchiveV2Policy.canTransitionReview(
                ReviewDisposition.IGNORED,
                ReviewDisposition.PENDING,
                explicitUserAction = false,
            ),
        )
        assertTrue(
            CreatorArchiveV2Policy.canTransitionDelivery(
                NotificationDeliveryState.FAILED,
                NotificationDeliveryState.PENDING,
            ),
        )
        assertFalse(
            CreatorArchiveV2Policy.canTransitionDelivery(
                NotificationDeliveryState.DELIVERED,
                NotificationDeliveryState.PENDING,
            ),
        )
    }
}
