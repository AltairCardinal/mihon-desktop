package tachiyomi.data.creator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tachiyomi.data.Database

/** Versioned, component-local recovery evidence. Capture in the same transaction as graph collapse.
 * These fixed tables cover every row deleted or rewritten by mergeCreatorIdentityGraph; this is
 * deliberately not a user-facing undo API or a general database backup format.
 */
internal fun Database.captureCreatorIdentityRecovery(creatorIds: List<Long>): String {
    val tables = linkedMapOf<String, JsonElement>()
    tables["creators"] = JsonArray(
        author_identity_recoveryQueries.captureIdentityRecoveryCreators(creatorIds) {
                _id,
                portable_key,
                display_name,
                normalized_name,
                sort_name,
                status,
                merged_into_creator_id,
                needs_review,
                identity_revision,
                legacy_creator_id,
                created_at,
                last_modified_at,
            ->
            recoveryRow(
                "_id" to _id,
                "portable_key" to portable_key,
                "display_name" to display_name,
                "normalized_name" to normalized_name,
                "sort_name" to sort_name,
                "status" to status,
                "merged_into_creator_id" to merged_into_creator_id,
                "needs_review" to needs_review,
                "identity_revision" to identity_revision,
                "legacy_creator_id" to legacy_creator_id,
                "created_at" to created_at,
                "last_modified_at" to last_modified_at,
            )
        }.executeAsList(),
    )
    tables["identity_names"] =
        JsonArray(
            author_identity_recoveryQueries.captureIdentityRecoveryIdentityNames(creatorIds) {
                    name_text,
                    creator_id,
                    origin,
                    created_at,
                    last_modified_at,
                ->
                recoveryRow(
                    "name_text" to name_text,
                    "creator_id" to creator_id,
                    "origin" to origin,
                    "created_at" to created_at,
                    "last_modified_at" to last_modified_at,
                )
            }.executeAsList(),
        )
    tables["aliases"] = JsonArray(
        author_identity_recoveryQueries.captureIdentityRecoveryAliases(creatorIds) {
                _id,
                creator_id,
                raw_alias,
                normalized_alias,
                source,
                evidence,
                confidence,
                is_manual,
                created_at,
                last_modified_at,
            ->
            recoveryRow(
                "_id" to _id,
                "creator_id" to creator_id,
                "raw_alias" to raw_alias,
                "normalized_alias" to normalized_alias,
                "source" to source,
                "evidence" to evidence,
                "confidence" to confidence,
                "is_manual" to is_manual,
                "created_at" to created_at,
                "last_modified_at" to last_modified_at,
            )
        }.executeAsList(),
    )
    tables["manga_links"] = JsonArray(
        author_identity_recoveryQueries.captureIdentityRecoveryMangaLinks(creatorIds) {
                _id,
                manga_id,
                creator_id,
                role,
                creator_order,
                origin,
                source_text,
                confidence,
                evidence,
                created_at,
                last_modified_at,
            ->
            recoveryRow(
                "_id" to _id,
                "manga_id" to manga_id,
                "creator_id" to creator_id,
                "role" to role,
                "creator_order" to creator_order,
                "origin" to origin,
                "source_text" to source_text,
                "confidence" to confidence,
                "evidence" to evidence,
                "created_at" to created_at,
                "last_modified_at" to last_modified_at,
            )
        }.executeAsList(),
    )
    tables["source_works"] = JsonArray(
        author_identity_recoveryQueries.captureIdentityRecoverySourceWorks(creatorIds) {
                _id,
                source_id,
                stable_source_url,
                manga_id,
                title,
                normalized_title,
                author_text,
                artist_text,
                thumbnail_url,
                first_seen_at,
                first_seen_date,
                first_seen_zone,
                last_seen_at,
                details_fetched_at,
                chapter_count_state,
                catalog_chapter_count,
                latest_chapter_at,
                published_date_snapshot_at,
                published_date_snapshot_basis,
                published_date_snapshot_reason,
                legacy_candidate_id,
                legacy_review_snapshot,
            ->
            recoveryRow(
                "_id" to _id,
                "source_id" to source_id,
                "stable_source_url" to stable_source_url,
                "manga_id" to manga_id,
                "title" to title,
                "normalized_title" to normalized_title,
                "author_text" to author_text,
                "artist_text" to artist_text,
                "thumbnail_url" to thumbnail_url,
                "first_seen_at" to first_seen_at,
                "first_seen_date" to first_seen_date,
                "first_seen_zone" to first_seen_zone,
                "last_seen_at" to last_seen_at,
                "details_fetched_at" to details_fetched_at,
                "chapter_count_state" to chapter_count_state,
                "catalog_chapter_count" to catalog_chapter_count,
                "latest_chapter_at" to latest_chapter_at,
                "published_date_snapshot_at" to published_date_snapshot_at,
                "published_date_snapshot_basis" to published_date_snapshot_basis,
                "published_date_snapshot_reason" to published_date_snapshot_reason,
                "legacy_candidate_id" to legacy_candidate_id,
                "legacy_review_snapshot" to legacy_review_snapshot,
            )
        }.executeAsList(),
    )
    tables["source_work_creators"] =
        JsonArray(
            author_identity_recoveryQueries.captureIdentityRecoverySourceWorkCreators(creatorIds) {
                    _id,
                    source_work_id,
                    creator_id,
                    role,
                    creator_order,
                    origin,
                    verification,
                    source_text,
                    confidence,
                    evidence,
                    created_at,
                    last_modified_at,
                ->
                recoveryRow(
                    "_id" to _id,
                    "source_work_id" to source_work_id,
                    "creator_id" to creator_id,
                    "role" to role,
                    "creator_order" to creator_order,
                    "origin" to origin,
                    "verification" to verification,
                    "source_text" to source_text,
                    "confidence" to confidence,
                    "evidence" to evidence,
                    "created_at" to created_at,
                    "last_modified_at" to last_modified_at,
                )
            }.executeAsList(),
        )
    tables["canonical_works"] =
        JsonArray(
            author_identity_recoveryQueries.captureIdentityRecoveryCanonicalWorks(creatorIds) {
                    _id,
                    portable_key,
                    primary_title,
                    normalized_title,
                    status,
                    legacy_work_id,
                    created_at,
                    last_modified_at,
                ->
                recoveryRow(
                    "_id" to _id,
                    "portable_key" to portable_key,
                    "primary_title" to primary_title,
                    "normalized_title" to normalized_title,
                    "status" to status,
                    "legacy_work_id" to legacy_work_id,
                    "created_at" to created_at,
                    "last_modified_at" to last_modified_at,
                )
            }.executeAsList(),
        )
    tables["canonical_creators"] =
        JsonArray(
            author_identity_recoveryQueries.captureIdentityRecoveryCanonicalCreators(creatorIds) {
                    _id,
                    work_id,
                    creator_id,
                    role,
                    creator_order,
                    origin,
                    evidence,
                ->
                recoveryRow(
                    "_id" to _id,
                    "work_id" to work_id,
                    "creator_id" to creator_id,
                    "role" to role,
                    "creator_order" to creator_order,
                    "origin" to origin,
                    "evidence" to evidence,
                )
            }.executeAsList(),
        )
    tables["watches"] = JsonArray(
        author_identity_recoveryQueries.captureIdentityRecoveryWatches(creatorIds) {
                _id,
                creator_id,
                enabled,
                period_millis,
                lease_owner,
                lease_expires_at,
                last_checked_at,
                last_success_at,
                last_error,
                created_at,
                last_modified_at,
            ->
            recoveryRow(
                "_id" to _id,
                "creator_id" to creator_id,
                "enabled" to enabled,
                "period_millis" to period_millis,
                "lease_owner" to lease_owner,
                "lease_expires_at" to lease_expires_at,
                "last_checked_at" to last_checked_at,
                "last_success_at" to last_success_at,
                "last_error" to last_error,
                "created_at" to created_at,
                "last_modified_at" to last_modified_at,
            )
        }.executeAsList(),
    )
    tables["watch_sources"] =
        JsonArray(
            author_identity_recoveryQueries.captureIdentityRecoveryWatchSources(creatorIds) {
                    _id,
                    watch_id,
                    source_id,
                    baseline_state,
                    baseline_generation,
                    next_due_at,
                    created_at,
                    last_modified_at,
                ->
                recoveryRow(
                    "_id" to _id,
                    "watch_id" to watch_id,
                    "source_id" to source_id,
                    "baseline_state" to baseline_state,
                    "baseline_generation" to baseline_generation,
                    "next_due_at" to next_due_at,
                    "created_at" to created_at,
                    "last_modified_at" to last_modified_at,
                )
            }.executeAsList(),
        )
    tables["watch_result_policies"] =
        JsonArray(
            author_identity_recoveryQueries.captureIdentityRecoveryWatchResultPolicies(creatorIds) {
                    _id,
                    watch_id,
                    include_probable,
                    include_unknown,
                    notify_probable,
                    notify_unknown,
                    created_at,
                    last_modified_at,
                ->
                recoveryRow(
                    "_id" to _id,
                    "watch_id" to watch_id,
                    "include_probable" to include_probable,
                    "include_unknown" to include_unknown,
                    "notify_probable" to notify_probable,
                    "notify_unknown" to notify_unknown,
                    "created_at" to created_at,
                    "last_modified_at" to last_modified_at,
                )
            }.executeAsList(),
        )
    tables["watch_languages"] =
        JsonArray(
            author_identity_recoveryQueries.captureIdentityRecoveryWatchLanguages(creatorIds) {
                    _id,
                    policy_id,
                    language_tag,
                ->
                recoveryRow(
                    "_id" to _id,
                    "policy_id" to policy_id,
                    "language_tag" to language_tag,
                )
            }.executeAsList(),
        )
    tables["runs"] = JsonArray(
        author_identity_recoveryQueries.captureIdentityRecoveryRuns(creatorIds) {
                _id,
                run_key,
                watch_id,
                state,
                completed_sources,
                total_sources,
                truncated,
                error_code,
                error_message,
                queued_at,
                started_at,
                finished_at,
            ->
            recoveryRow(
                "_id" to _id,
                "run_key" to run_key,
                "watch_id" to watch_id,
                "state" to state,
                "completed_sources" to completed_sources,
                "total_sources" to total_sources,
                "truncated" to truncated,
                "error_code" to error_code,
                "error_message" to error_message,
                "queued_at" to queued_at,
                "started_at" to started_at,
                "finished_at" to finished_at,
            )
        }.executeAsList(),
    )
    tables["source_checkpoints"] =
        JsonArray(
            author_identity_recoveryQueries.captureIdentityRecoverySourceCheckpoints(creatorIds) {
                    _id,
                    watch_id,
                    source_id,
                    cursor,
                    result_state,
                    consecutive_failures,
                    backoff_until,
                    last_checked_at,
                    last_success_at,
                    error_code,
                    error_message,
                ->
                recoveryRow(
                    "_id" to _id,
                    "watch_id" to watch_id,
                    "source_id" to source_id,
                    "cursor" to cursor,
                    "result_state" to result_state,
                    "consecutive_failures" to consecutive_failures,
                    "backoff_until" to backoff_until,
                    "last_checked_at" to last_checked_at,
                    "last_success_at" to last_success_at,
                    "error_code" to error_code,
                    "error_message" to error_message,
                )
            }.executeAsList(),
        )
    tables["discoveries"] = JsonArray(
        author_identity_recoveryQueries.captureIdentityRecoveryDiscoveries(creatorIds) {
                _id,
                watch_id,
                source_work_id,
                kind,
                reason,
                baseline_generation,
                read_state,
                review_disposition,
                first_discovered_at,
                last_modified_at,
            ->
            recoveryRow(
                "_id" to _id,
                "watch_id" to watch_id,
                "source_work_id" to source_work_id,
                "kind" to kind,
                "reason" to reason,
                "baseline_generation" to baseline_generation,
                "read_state" to read_state,
                "review_disposition" to review_disposition,
                "first_discovered_at" to first_discovered_at,
                "last_modified_at" to last_modified_at,
            )
        }.executeAsList(),
    )
    tables["notification_outbox"] =
        JsonArray(
            author_identity_recoveryQueries.captureIdentityRecoveryNotificationOutbox(creatorIds) {
                    _id,
                    discovery_id,
                    channel,
                    idempotency_key,
                    attempt_count,
                    state,
                    last_error,
                    next_attempt_at,
                    created_at,
                    last_attempt_at,
                    delivered_at,
                ->
                recoveryRow(
                    "_id" to _id,
                    "discovery_id" to discovery_id,
                    "channel" to channel,
                    "idempotency_key" to idempotency_key,
                    "attempt_count" to attempt_count,
                    "state" to state,
                    "last_error" to last_error,
                    "next_attempt_at" to next_attempt_at,
                    "created_at" to created_at,
                    "last_attempt_at" to last_attempt_at,
                    "delivered_at" to delivered_at,
                )
            }.executeAsList(),
        )
    tables["language_assertions"] =
        JsonArray(
            author_identity_recoveryQueries.captureIdentityRecoveryLanguageAssertions(creatorIds) {
                    _id,
                    subject_type,
                    subject_key,
                    dimension,
                    language_tag,
                    confidence,
                    evidence_kind,
                    evidence_payload,
                    actor,
                    algorithm_version,
                    withdrawn,
                    asserted_at,
                    idempotency_key,
                ->
                recoveryRow(
                    "_id" to _id,
                    "subject_type" to subject_type,
                    "subject_key" to subject_key,
                    "dimension" to dimension,
                    "language_tag" to language_tag,
                    "confidence" to confidence,
                    "evidence_kind" to evidence_kind,
                    "evidence_payload" to evidence_payload,
                    "actor" to actor,
                    "algorithm_version" to algorithm_version,
                    "withdrawn" to withdrawn,
                    "asserted_at" to asserted_at,
                    "idempotency_key" to idempotency_key,
                )
            }.executeAsList(),
        )
    return JsonObject(mapOf("version" to JsonPrimitive(1), "tables" to JsonObject(tables))).toString()
}

private fun recoveryRow(vararg fields: Pair<String, Any?>): JsonObject = JsonObject(
    fields.associate { (name, value) ->
        name to when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> error("Unsupported recovery field: $name")
        }
    },
)
