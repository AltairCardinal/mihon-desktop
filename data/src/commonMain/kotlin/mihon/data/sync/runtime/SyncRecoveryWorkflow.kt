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
    val externalScopes: List<SyncRecoveryExternalScope> = emptyList(),
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
                it.copy(
                    request = request,
                    repairMadeNoProgress = it.repairMadeNoProgress &&
                        it.repairReportFingerprint == report.recoveryFingerprint(),
                )
            } else {
                it.copy(
                    outcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                    conditionsVerified = false,
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
        return SyncRecoveryFacts(
            flow,
            report,
            oldScopes(context),
            flow?.externalScopes.orEmpty() + runtime.onboarding.storage.unboundRecoveryFlow()?.externalScopes.orEmpty(),
        )
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

    suspend fun officialOpened(action: SyncRecoveryAction) {
        val context = runtime.recoveryBinding()
        update(context) {
            it.copy(
                officialAction = action,
                outcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                step = SyncRecoveryFlowStep.EXTERNAL_ACTION,
            )
        }
    }

    suspend fun conditionsChecked(succeeded: Boolean, expected: SyncRecoveryBinding): Boolean {
        if (!succeeded) return false
        val context = runtime.recoveryBinding()
        if (!expected.matches(context)) return false
        update(context) {
            it.copy(
                officialAction = null,
                conditionsVerified = true,
                secondaryFailure = null,
                step = SyncRecoveryFlowStep.CHECK_CONDITIONS,
            )
        }
        return true
    }

    suspend fun platformCompleted(requestId: String, result: SyncRecoveryPlatformResult): Boolean {
        val context = runtime.recoveryBinding()
        val flow = runtime.onboarding.storage.recoveryFlow(context.stored) ?: return false
        val request = flow.request?.takeIf { it.requestId == requestId && !it.failed } ?: return false
        if (flow.credentialRevision != context.credentialRevision) return false
        if (result is SyncRecoveryPlatformResult.Changed && request.objects.isNotEmpty() &&
            result.objects.any { it !in request.objects }
        ) {
            return false
        }
        val restart = result == SyncRecoveryPlatformResult.RestartRequired
        update(context) {
            it.copy(
                step = if (restart) SyncRecoveryFlowStep.EXTERNAL_ACTION else SyncRecoveryFlowStep.CHECK_CONDITIONS,
                outcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                request = request.copy(
                    result = result.boundedMetadata(),
                    failed = result is SyncRecoveryPlatformResult.Failed,
                    restartRequired = restart,
                ),
                externalScopes = it.externalScopes.afterPlatformResult(request, result),
            )
        }
        val unbound = runtime.onboarding.storage.unboundRecoveryFlow()
        if (!unbound?.externalScopes.isNullOrEmpty()) {
            runtime.onboarding.storage.saveUnboundRecoveryFlow(
                requireNotNull(unbound).copy(
                    externalScopes = unbound.externalScopes.afterPlatformResult(
                        request,
                        result,
                        recordNewFailure = false,
                    ),
                ),
                unbound,
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
        val beforeRepair = repairOffset?.let {
            repair.inspect(context.descriptor.spaceId, context.descriptor.generation, it)
        }
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
                    conditionsVerified = false,
                    request = null,
                    result = null,
                )
            }
            val checked = runtime.recheckSpace()
            val afterCheck = runtime.recoveryBinding()
            require(context.matches(afterCheck)) { "sync recovery context changed" }
            context = afterCheck
            if (checked.problem != null || checked.recovery != null) {
                val failure = SyncRecoveryFailure(
                    problem = checked.problem ?: when (checked.recovery?.reason) {
                        SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED -> SyncRunProblem.AUTHORIZATION
                        SyncSpaceRecoveryReason.SPACE_DATA_INVALID -> SyncRunProblem.INVALID_DATA
                        else -> SyncRunProblem.SPACE_UNAVAILABLE
                    },
                    discovery = checked.discoveryProblem,
                    networkPhase = checked.networkPhase,
                    httpStatus = checked.httpStatus,
                )
                update(context) {
                    it.copy(
                        outcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                        failure = it.failure ?: failure,
                        secondaryFailure = failure.takeIf { _ -> it.failure != null && it.failure != failure },
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
            val external = runtime.onboarding.storage.recoveryFlow(context.stored)?.externalScopes.orEmpty() +
                runtime.onboarding.storage.unboundRecoveryFlow()?.externalScopes.orEmpty()
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
                unpublished == 0L && external.isEmpty()
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
                    external.isNotEmpty() ||
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
                    step = if (verified) {
                        SyncRecoveryFlowStep.COMPLETE
                    } else {
                        SyncRecoveryFlowStep.CHECK_CONDITIONS
                    },
                    outcome = outcome,
                    repairMadeNoProgress = beforeRepair?.let { before ->
                        val sameFacts = report.counts == before.counts &&
                            report.batches == before.batches &&
                            report.fields == before.fields &&
                            report.events == before.events &&
                            report.missingDependencies == before.missingDependencies &&
                            report.failedBulkItems == before.failedBulkItems
                        before.remaining > 0 && sameFacts
                    } ?: it.repairMadeNoProgress,
                    repairReportFingerprint = if (beforeRepair != null) {
                        report.recoveryFingerprint()
                    } else {
                        it.repairReportFingerprint
                    },
                    conditionsVerified = result.problem == null,
                    result = result,
                    verifiedRunId = verifiedRunId,
                    failure = if (verified) {
                        null
                    } else {
                        it.failure ?: result.problem?.let { problem ->
                            SyncRecoveryFailure(
                                problem,
                                networkPhase = result.networkPhase,
                                httpStatus = result.httpStatus,
                            )
                        }
                    },
                    secondaryFailure = if (verified) {
                        null
                    } else {
                        result.problem?.let { problem ->
                            SyncRecoveryFailure(
                                problem,
                                networkPhase = result.networkPhase,
                                httpStatus = result.httpStatus,
                            )
                        }
                    },
                )
            }
            return SyncRecoveryFacts(
                runtime.onboarding.storage.recoveryFlow(context.stored),
                report,
                old,
                external,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A result for an old target is never written onto a newer binding.
            if (context.matches(runtime.recoveryBinding())) {
                update(context) {
                    it.copy(
                        outcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                        conditionsVerified = false,
                        failure = it.failure ?: error.recoveryFailure(),
                        secondaryFailure = error.recoveryFailure(),
                    )
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
        val local = runtime.onboarding.storage.unboundRecoveryFlow()
        for (id in local?.archivedSetupIds.orEmpty().take(64)) {
            val setup = runtime.onboarding.storage.archivedSetup(id)
                ?: local?.accountId?.let { runtime.onboarding.storage.pending(it) }?.takeIf { it.attemptId == id }
            if (setup == null) {
                scopes += SyncRecoveryScopeSummary(
                    null,
                    null,
                    "此前未完成的同步设置",
                    0,
                    remoteUnverified = true,
                    remainingUnknown = true,
                )
            } else {
                val descriptor = setup.material.material().descriptor
                val report = repair.inspect(descriptor.spaceId, descriptor.generation)
                scopes += SyncRecoveryScopeSummary(
                    descriptor.spaceId,
                    descriptor.generation,
                    setup.repository().fullName,
                    report.remaining,
                    remoteUnverified = true,
                    remainingUnknown = true,
                )
            }
        }
        if (local?.archivedSetupsTruncated == true) {
            scopes += SyncRecoveryScopeSummary(
                null,
                null,
                "还有此前未核验的设置",
                0,
                remoteUnverified = true,
                remainingUnknown = true,
            )
        }
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
                val external = runtime.onboarding.storage.recoveryFlow(intent.oldConnection)?.externalScopes.orEmpty()
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
                    external.mapNotNull { it.remaining }.fold(report.remaining, Math::addExact),
                    queued,
                    remoteUnverified = true,
                    remainingUnknown = external.any { it.remaining == null },
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

private fun SyncRepairReport.recoveryFingerprint(): String =
    mihon.data.sync.transport.SyncSnapshotManifestCrypto.sha256(
        listOf(
            counts.toString(),
            offset.toString(),
            batches.toString(),
            fields.toString(),
            events.toString(),
            missingDependencies.toString(),
            failedBulkItems.toString(),
        ).joinToString("\u0000").encodeToByteArray(),
    )
