package mihon.data.sync.runtime

import kotlinx.coroutines.CancellationException
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.inbox.SyncDataRepairService
import mihon.data.sync.inbox.SyncInboxExchange
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.inbox.SyncRepairReport
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.data.sync.transport.SyncSnapshotManifestStore
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import tachiyomi.data.DatabaseHandler
import java.util.UUID

internal data class SyncRecoveryFacts(
    val flow: StoredSyncRecoveryFlow?,
    val report: SyncRepairReport,
    val oldScopes: List<SyncRecoveryScopeSummary>,
)

/** Coordinates the existing engine and repairs. It does not grant access, relax guards, or upload its own events. */
internal class SyncRecoveryWorkflow(
    private val runtime: SyncRuntime,
    private val handler: DatabaseHandler,
    private val clock: () -> Long,
) {
    private val inbox = SyncInboxStore(handler)
    private val repair = SyncDataRepairService(handler, inbox, runtime.projector)

    suspend fun facts(offset: Long = 0): SyncRecoveryFacts {
        val context = runtime.recoveryBinding()
        val report = repair.inspect(context.descriptor.spaceId, context.descriptor.generation, offset)
        val queued = handler.await {
            sync_journalQueries.getPendingCategoryCounts(
                context.descriptor.spaceId,
                context.descriptor.generation,
            ).executeAsList().sumOf {
                it.count
            } +
                sync_importQueries.countPendingImports(
                    context.descriptor.spaceId,
                    context.descriptor.generation,
                ).executeAsOne()
        }
        val latest = runtime.runStore.latest(context.descriptor.spaceId, context.descriptor.generation)
        val flow = runtime.onboarding.storage.recoveryFlow(context.stored)?.let {
            val request = it.request?.let { request ->
                request.copy(
                    restartRequired = request.restartRequired &&
                        request.runtimeInstanceId == runtime.instanceId,
                )
            }
            val current = if (it.credentialRevision == context.credentialRevision) {
                it.copy(request = request)
            } else {
                it.copy(
                    outcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                    step = SyncRecoveryFlowStep.CHECK_CONDITIONS,
                    request = request?.copy(
                        failed = true,
                    ),
                )
            }
            if (
                current.outcome in setOf(
                    SyncRecoveryOutcome.ORIGINAL_VERIFIED,
                    SyncRecoveryOutcome.NEW_SCOPE_VERIFIED_WITH_OLD_REMAINING,
                )
            ) {
                when {
                    report.remaining > 0 ||
                        queued > 0 ->
                        current.copy(
                            outcome = SyncRecoveryOutcome.REMAINING,
                            step = SyncRecoveryFlowStep.CHECK_CONDITIONS,
                        )
                    latest?.state != SyncRunState.SUCCEEDED || current.verifiedRunId == null -> current.copy(
                        outcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                        step = SyncRecoveryFlowStep.CHECK_CONDITIONS,
                        failure = SyncRecoveryFailure(
                            runtime.coordinator.activity.value.result?.problem
                                ?: when (
                                    latest?.stopReason
                                )
                                {
                                    "network", "rate_limit", "retry_exhausted" -> SyncRunProblem.NETWORK
                                    else -> SyncRunProblem.UNKNOWN
                                },
                        ),
                    )
                    else -> current
                }
            } else {
                current
            }
        }
        return SyncRecoveryFacts(flow, report, oldScopes(context))
    }

    suspend fun openPlatform(
        action: SyncRecoveryPlatformAction,
        objectKey: SyncObjectKey?,
        offset: Long = 0,
    ): SyncRecoveryPlatformRequest {
        val context = runtime.recoveryBinding()
        val report = repair.inspect(context.descriptor.spaceId, context.descriptor.generation, offset)
        val readable = (
            report.fields.mapNotNull {
                it.objectKey
            } + report.failedBulkItems.mapNotNull {
                it.objectKey
            }
            ).distinct()
        if (objectKey != null && objectKey !in readable) throw SyncSetupException(SyncDiscoveryProblem.MALFORMED)
        if (
            action in setOf(
                SyncRecoveryPlatformAction.MIGRATION,
                SyncRecoveryPlatformAction.READER,
            ) &&
            objectKey == null
        ) {
            throw SyncSetupException(SyncDiscoveryProblem.MALFORMED)
        }
        val selected = objectKey?.let(::listOf) ?: readable
        val request = SyncRecoveryPlatformRequest(
            UUID.randomUUID().toString(),
            action,
            sourceIds = if (
                action == SyncRecoveryPlatformAction.EXTENSIONS
            ) {
                selected.mapNotNull {
                    it.sourceId?.toLongOrNull()
                }.toSet()
            } else {
                emptySet()
            },
            objects = if (
                action in setOf(
                    SyncRecoveryPlatformAction.MIGRATION,
                    SyncRecoveryPlatformAction.READER,
                )
            ) {
                selected.take(
                    1,
                )
            } else {
                emptyList()
            },
            originSpaceId = context.descriptor.spaceId,
            originGeneration = context.descriptor.generation,
            runtimeInstanceId = runtime.instanceId,
        )
        update(context) {
            it.copy(
                step = SyncRecoveryFlowStep.EXTERNAL_ACTION,
                outcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                request = request,
            )
        }
        return request
    }

    suspend fun platformFailed(requestId: String): Boolean {
        val context = runtime.recoveryBinding()
        val flow = runtime.onboarding.storage.recoveryFlow(context.stored) ?: return false
        val request = flow.request?.takeIf { it.requestId == requestId } ?: return false
        if (flow.credentialRevision != context.credentialRevision) return false
        update(
            context,
        )
        {
            it.copy(
                outcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                request = request.copy(
                    failed = true,
                ),
            )
        }
        return true
    }

    suspend fun platformReturned(requestId: String, restartRequired: Boolean): Boolean {
        val context = runtime.recoveryBinding()
        val flow = runtime.onboarding.storage.recoveryFlow(context.stored) ?: return false
        val request = flow.request?.takeIf { it.requestId == requestId && !it.failed } ?: return false
        if (flow.credentialRevision != context.credentialRevision) return false
        update(context) {
            it.copy(
                step = if (
                    restartRequired
                ) {
                    SyncRecoveryFlowStep.EXTERNAL_ACTION
                } else {
                    SyncRecoveryFlowStep.CHECK_CONDITIONS
                },
                outcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                request = if (restartRequired) request.copy(restartRequired = true) else null,
            )
        }
        return !restartRequired
    }

    suspend fun verify(repairOffset: Long? = null): SyncRecoveryFacts {
        var context = runtime.recoveryBinding()
        try {
            val active = runtime.runStore.active(context.descriptor.spaceId, context.descriptor.generation)
            val previous = runtime.onboarding.storage.recoveryFlow(context.stored)
            if (runtime.coordinator.activity.value.running || active?.state == SyncRunState.PAUSED_USER ||
                previous?.request?.let { it.restartRequired && it.runtimeInstanceId == runtime.instanceId } == true
            ) {
                update(context) { it.copy(outcome = SyncRecoveryOutcome.WAITING_EXTERNAL) }
                return facts(repairOffset ?: 0)
            }
            update(context) {
                it.copy(
                    step = SyncRecoveryFlowStep.CHECK_CONDITIONS,
                    outcome = null,
                    failure = null,
                    request = null,
                    result = null,
                )
            }
            val checked = runtime.recheckSpace()
            val afterCheck = runtime.recoveryBinding()
            require(context.matches(afterCheck)) { "sync recovery context changed" }
            context = afterCheck
            if (checked.problem != null || checked.recovery != null) {
                update(context) {
                    it.copy(
                        outcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                        failure = SyncRecoveryFailure(
                            problem = checked.problem ?: when (checked.recovery?.reason) {
                                SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED -> SyncRunProblem.AUTHORIZATION
                                SyncSpaceRecoveryReason.SPACE_DATA_INVALID -> SyncRunProblem.INVALID_DATA
                                else -> SyncRunProblem.SPACE_UNAVAILABLE
                            },
                            discovery = checked.discoveryProblem,
                            networkPhase = checked.networkPhase,
                            httpStatus = checked.httpStatus,
                        ),
                    )
                }
                return facts(repairOffset ?: 0)
            }
            if (repairOffset != null) {
                update(context) { it.copy(step = SyncRecoveryFlowStep.REPAIR_DATA) }
                val session = runtime.onboarding.session(context.stored.accountId)
                val material = context.stored.material.material()
                val transport = runtime.onboarding.transport(
                    session.token,
                    material,
                    context.stored.repositoryId,
                    manifestStore = SyncSnapshotManifestStore(handler),
                    manifestBinding = context.stored.snapshotManifestBinding(),
                    persistentObjectCacheDirectory = runtime.persistentObjectCacheDirectory,
                    requestGate = runtime.accountHttpRequestGate(context.stored.accountId),
                )
                val snapshot = transport.readSnapshot(
                    context.stored.repository(),
                    context.descriptor.spaceId,
                    context.descriptor.generation,
                ).getOrThrow()
                require(context.matches(runtime.recoveryBinding())) { "sync recovery context changed" }
                val page = repair.inspect(context.descriptor.spaceId, context.descriptor.generation, repairOffset)
                repair.refetch(
                    snapshot,
                    SyncInboxExchange(
                        inbox,
                        SyncBatchSyncService(
                            transport,
                            spaceMaterial = material,
                        ),
                    ),
                    page.batches.mapTo(
                        mutableSetOf(),
                    )
                        {
                            it.batchId
                        },
                    repairOffset,
                )
                require(context.matches(runtime.recoveryBinding())) { "sync recovery context changed" }
                repair.reproject(context.descriptor.spaceId, context.descriptor.generation, offset = repairOffset)
            }
            update(context) { it.copy(step = SyncRecoveryFlowStep.VERIFY_SYNCHRONIZATION) }
            val resumable = runtime.runStore.active(context.descriptor.spaceId, context.descriptor.generation)
            val trigger = if (
                resumable?.state in setOf(
                    SyncRunState.QUEUED,
                    SyncRunState.RUNNING,
                    SyncRunState.WAITING_SYSTEM,
                    SyncRunState.WAITING_NETWORK,
                    SyncRunState.WAITING_RETRY,
                )
            ) {
                SyncTrigger.RECOVERY
            } else {
                SyncTrigger.MANUAL
            }
            val completion = runtime.coordinator.activity.value.completion
            val resumed = if (resumable?.ownerSession != null) runtime.resumeIfNeeded() else false
            val result = if (resumed) {
                runtime.coordinator.activity.value.result?.takeIf {
                    runtime.coordinator.activity.value.completion > completion
                }
                    ?: mihon.domain.sync.runtime.SyncRunResult(SyncRunStatus.SKIPPED)
            } else {
                runtime.coordinator.synchronize(trigger)
            }
            require(context.matches(runtime.recoveryBinding())) { "sync recovery context changed" }
            val report = repair.inspect(context.descriptor.spaceId, context.descriptor.generation, repairOffset ?: 0)
            val old = oldScopes(context)
            val unpublished = handler.await {
                sync_journalQueries.getPendingCategoryCounts(
                    context.descriptor.spaceId,
                    context.descriptor.generation,
                ).executeAsList().sumOf {
                    it.count
                } +
                    sync_importQueries.countPendingImports(
                        context.descriptor.spaceId,
                        context.descriptor.generation,
                    ).executeAsOne()
            }
            val verified = result.status == SyncRunStatus.SUCCESS &&
                result.problem == null &&
                report.remaining == 0L &&
                unpublished == 0L
            val outcome = when {
                verified &&
                    (
                        old.isNotEmpty() ||
                            runtime.externalRecoveryOrigin() != null
                        )
                ->
                    SyncRecoveryOutcome.NEW_SCOPE_VERIFIED_WITH_OLD_REMAINING
                verified -> SyncRecoveryOutcome.ORIGINAL_VERIFIED
                report.remaining > 0 ||
                    unpublished > 0 ||
                    result.status == SyncRunStatus.PARTIAL ->
                    SyncRecoveryOutcome.REMAINING
                else -> SyncRecoveryOutcome.WAITING_EXTERNAL
            }
            val verifiedRunId = if (
                verified
            ) {
                runtime.runStore.latest(
                    context.descriptor.spaceId,
                    context.descriptor.generation,
                )
                    ?.runId
            } else {
                null
            }
            update(context) {
                it.copy(
                    step = if (verified) SyncRecoveryFlowStep.COMPLETE else SyncRecoveryFlowStep.CHECK_CONDITIONS,
                    outcome = outcome,
                    result = result,
                    verifiedRunId = verifiedRunId,
                    failure = result.problem?.let { problem ->
                        SyncRecoveryFailure(
                            problem,
                            networkPhase = result.networkPhase,
                            httpStatus = result.httpStatus,
                        )
                    },
                )
            }
            return SyncRecoveryFacts(runtime.onboarding.storage.recoveryFlow(context.stored), report, old)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A result for an old target is never written onto a newer binding.
            if (context.matches(runtime.recoveryBinding())) {
                update(context) {
                    it.copy(outcome = SyncRecoveryOutcome.WAITING_EXTERNAL, failure = error.recoveryFailure())
                }
            }
            return facts(repairOffset ?: 0)
        }
    }

    private suspend fun update(
        context: SyncRecoveryBinding,
        block: (
            StoredSyncRecoveryFlow,
        )
        ->
        StoredSyncRecoveryFlow,
    ) {
        require(context.matches(runtime.recoveryBinding())) { "sync recovery context changed" }
        val storage = runtime.onboarding.storage
        val before = storage.recoveryFlow(context.stored)
        val current = before
            ?: StoredSyncRecoveryFlow(
                bindingRevision = context.stored.recoveryRevision(),
                credentialRevision = context.credentialRevision,
            )
        val next = block(
            current,
        ).copy(
            bindingRevision = context.stored.recoveryRevision(),
            credentialRevision = context.credentialRevision,
            updatedAtMillis = clock(),
        )
        storage.saveRecoveryFlow(context.stored, next, before)
    }

    private suspend fun oldScopes(context: SyncRecoveryBinding): List<SyncRecoveryScopeSummary> {
        val scopes = mutableListOf<SyncRecoveryScopeSummary>()
        val visited = mutableSetOf<String>()
        var current = runtime.onboarding.storage.activeSwitch(context.stored.accountId)
        repeat(64) {
            val intent = current ?: return scopes
            require(visited.add(intent.intentId)) { "sync recovery switch history is inconsistent" }
            val descriptor = intent.oldConnection.material.material().descriptor
            if (
                (
                    descriptor.spaceId != context.descriptor.spaceId ||
                        descriptor.generation != context.descriptor.generation
                    ) &&
                scopes.none { it.spaceId == descriptor.spaceId && it.generation == descriptor.generation }
            ) {
                val report = repair.inspect(descriptor.spaceId, descriptor.generation)
                val queued = handler.await {
                    sync_journalQueries.getPendingCategoryCounts(
                        descriptor.spaceId,
                        descriptor.generation,
                    ).executeAsList().sumOf {
                        it.count
                    } +
                        sync_importQueries.countPendingImports(descriptor.spaceId, descriptor.generation).executeAsOne()
                }
                scopes += SyncRecoveryScopeSummary(
                    descriptor.spaceId,
                    descriptor.generation,
                    intent.oldConnection.repository().fullName,
                    report.remaining,
                    queued,
                    remoteUnverified = true,
                )
            }
            current = intent.previousIntentId?.let { runtime.onboarding.storage.switchIntent(it) }
        }
        scopes += SyncRecoveryScopeSummary(null, null, "此前仍未核验的空间", 0, remoteUnverified = true)
        return scopes
    }
}

internal fun Exception.recoveryFailure(): SyncRecoveryFailure = SyncRecoveryFailure(
    problem = syncProblem(),
    discovery = (this as? SyncSetupException)?.problem,
    initialization = (this as? SyncSetupException)?.initialization,
    networkPhase = (this as? SyncHttpException)?.networkPhase,
    httpStatus = (this as? SyncHttpException)?.code,
)
