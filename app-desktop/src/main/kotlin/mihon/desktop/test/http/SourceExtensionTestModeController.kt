package mihon.desktop.test.http

import kotlinx.serialization.Serializable
import mihon.desktop.ui.extension.DesktopExtensionItem
import mihon.desktop.ui.extension.ExtensionsScreenModel
import mihon.desktop.ui.extension.toExtensionListUiProjection
import mihon.domain.error.StoredAppError
import mihon.domain.error.toStoredAppError
import mihon.domain.extension.presentation.extensionActionEligibility
import java.util.concurrent.atomic.AtomicReference
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.suggestion.SuggestionBatchResult
import mihon.domain.extension.suggestion.suggestionIdentityKey

@Serializable data class SourceExtensionTestSource(val id: Long, val language: String, val name: String, val baseUrl: String?)
@Serializable data class SourceExtensionTestItem(
    val packageName: String, val name: String, val language: String?, val installed: Boolean, val available: Boolean,
    val hasUpdate: Boolean, val sources: List<SourceExtensionTestSource>,
)
@Serializable data class SourceExtensionTrustSnapshot(
    val packageName: String, val requestId: String, val existingFingerprint: String, val incomingFingerprint: String,
    val reasons: List<String>,
)
@Serializable data class SourceExtensionRepositoryError(
    val repositoryBaseUrl: String, val repositoryName: String, val repositoryFingerprint: String,
    val error: StoredAppError,
)
@Serializable data class SourceExtensionSuggestionRow(
    val identity: String, val packageName: String, val name: String, val versionName: String, val versionCode: Long,
    val repositoryUrl: String, val repositoryFingerprint: String, val canInstall: Boolean, val canIgnore: Boolean,
    val websites: List<SourceExtensionTestSource>, val sources: List<SourceExtensionSuggestedSource>,
)
@Serializable data class SourceExtensionSuggestedSource(val id: Long, val name: String, val language: String, val count: Long)
@Serializable data class SourceExtensionBatchArtifact(
    val packageName: String, val name: String, val versionName: String, val versionCode: Long,
    val repositoryUrl: String, val repositoryFingerprint: String, val identity: String,
)
@Serializable data class SourceExtensionBatchConfirmation(
    val id: String, val artifacts: List<SourceExtensionBatchArtifact>, val mode: String,
    val unavailablePackages: List<String>, val hasSourceConflict: Boolean,
    val replacements: Map<String, List<SourceExtensionBatchArtifact>>,
)
@Serializable data class SourceExtensionBatchItem(
    val artifact: SourceExtensionBatchArtifact, val transactionId: Long?, val progress: String?, val result: String?,
)
@Serializable data class SourceExtensionBatchSnapshot(
    val id: Long, val running: Boolean, val stopping: Boolean, val pauseReason: String?,
    val completed: Int, val items: List<SourceExtensionBatchItem>,
)
@Serializable data class SourceExtensionSuggestionSnapshot(
    val expanded: Boolean, val loading: Boolean, val total: Int, val canUndo: Boolean,
    val rows: List<SourceExtensionSuggestionRow>,
)
@Serializable data class SourceExtensionTestSnapshot(
    val searchQuery: String, val refreshing: Boolean, val installed: List<SourceExtensionTestItem>,
    val available: List<SourceExtensionTestItem>, val updates: List<SourceExtensionTestItem>,
    val installSteps: Map<String, String>, val errors: Map<String, StoredAppError>,
    val repositoryErrors: List<SourceExtensionRepositoryError>, val pendingTrust: SourceExtensionTrustSnapshot?,
    val suggestions: SourceExtensionSuggestionSnapshot,
    val confirmation: SourceExtensionBatchConfirmation?, val batch: SourceExtensionBatchSnapshot,
    val navigationRequestId: Long?, val displayedRequestId: Long?,
)
@Serializable enum class SourceExtensionActionFailureCode {
    MISSING_PARAMETER, UNKNOWN_PACKAGE, ACTION_UNAVAILABLE, NO_PENDING_TRUST, TRUST_PACKAGE_MISMATCH,
    OPERATION_REJECTED, UNSUPPORTED_ACTION,
}
@Serializable data class SourceExtensionActionResult(
    val success: Boolean, val snapshot: SourceExtensionTestSnapshot, val failureCode: SourceExtensionActionFailureCode? = null,
)

class SourceExtensionTestModeController(private val model: ExtensionsScreenModel) {
    private enum class ConfirmationMode { START, RESUME, RETRY }
    private data class PendingConfirmation(val id: String, val artifacts: List<ExtensionArtifact>, val batchId: Long, val mode: ConfirmationMode = ConfirmationMode.START)
    private var confirmation: PendingConfirmation? = null
    private var navigationRequestId: Long? = null
    @Synchronized
    fun snapshot(): SourceExtensionTestSnapshot {
        val state = model.state.value
        val ui = state.toExtensionListUiProjection(state.searchQuery)
        val trust = state.pendingTrust?.let {
            SourceExtensionTrustSnapshot(
                it.packageName, it.request.requestId, it.request.existingFingerprint, it.request.incomingFingerprint,
                it.request.reasons.map { reason -> reason::class.simpleName ?: "Unknown" },
            )
        }
        return SourceExtensionTestSnapshot(
            state.searchQuery, state.actions.isRefreshing, ui.installed.map(DesktopExtensionItem::testSnapshot),
            ui.available.map(DesktopExtensionItem::testSnapshot), ui.updates.map(DesktopExtensionItem::testSnapshot),
            state.actions.installSteps.mapValues { it.value.name }, state.installErrors.mapValues { it.value.toStoredAppError() },
            state.projection?.failures.orEmpty().map {
                SourceExtensionRepositoryError(
                    it.repository.baseUrl, it.repository.name, it.repository.signingKeyFingerprint,
                    it.error.toStoredAppError(),
                )
            }, trust,
            model.suggestionPanel.state.value.let { panel ->
                SourceExtensionSuggestionSnapshot(panel.expanded, panel.loading, panel.total, panel.canUndo,
                    panel.rows.map { row ->
                        val artifact = row.suggestion.artifact
                        SourceExtensionSuggestionRow(suggestionIdentityKey(row.suggestion.identity), artifact.packageName,
                            artifact.name, artifact.versionName, artifact.versionCode, artifact.repository.baseUrl,
                            artifact.repository.signingKeyFingerprint, row.canInstall, row.canIgnore,
                            row.websites.map { SourceExtensionTestSource(it.id, it.language, it.name, it.baseUrl) },
                            row.suggestion.sources.map { SourceExtensionSuggestedSource(it.source.id, it.source.name, it.source.language, it.count) })
                    })
            },
            confirmation?.let { pending ->
                val unavailable = pending.artifacts.filterNot(model::isCurrentBatchArtifact)
                SourceExtensionBatchConfirmation(pending.id, pending.artifacts.map(ExtensionArtifact::batchSnapshot), pending.mode.name,
                    unavailable.map { it.packageName }, model.batchSourcesConflict(pending.artifacts, pending.mode != ConfirmationMode.START),
                    unavailable.associate { it.packageName to model.batchReplacementCandidates(it).map(ExtensionArtifact::batchSnapshot) })
            },
            model.suggestionBatch.state.value.let { batch ->
                SourceExtensionBatchSnapshot(batch.id, batch.running, batch.stopping, batch.pauseReason?.name,
                    batch.completed, batch.items.map { SourceExtensionBatchItem(it.artifact.batchSnapshot(),
                        it.transactionId, it.progress?.javaClass?.simpleName, it.result?.javaClass?.simpleName) })
            },
            navigationRequestId, mihon.desktop.test.navigation.TestNavigationController.displayedExtensions.value,
        )
    }

    @Synchronized
    fun execute(action: String, params: Map<String, String> = emptyMap(), openWebsite: (String) -> Result<Unit> = {
        mihon.desktop.platform.DesktopUrlOpener.open(it)
    }): SourceExtensionActionResult = try {
        when (action) {
            "extension_suggestion_show" -> {
                if (!mihon.desktop.test.state.applicationState.testMode) fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
                navigationRequestId = mihon.desktop.test.navigation.TestNavigationController.requestExtensions()
            }
            "extension_suggestion_install" -> {
                val row = suggestion(params)
                model.installSuggestion(row.suggestion.identity) ?: fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
            }
            "extension_suggestion_website" -> {
                val row = suggestion(params)
                val sourceId = params["sourceId"]?.toLongOrNull() ?: fail(SourceExtensionActionFailureCode.MISSING_PARAMETER)
                val website = row.websites.singleOrNull { it.id == sourceId }
                    ?: fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
                if (openWebsite(website.baseUrl).isFailure) fail(SourceExtensionActionFailureCode.OPERATION_REJECTED)
            }
            "extension_suggestion_choose" -> {
                val identity = params["identity"] ?: fail(SourceExtensionActionFailureCode.MISSING_PARAMETER)
                val sourceId = params["sourceId"]?.toLongOrNull() ?: fail(SourceExtensionActionFailureCode.MISSING_PARAMETER)
                val candidate = model.suggestionPanel.state.value.choices[sourceId]
                    ?.singleOrNull { suggestionIdentityKey(it.identity) == identity }
                    ?: fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
                model.suggestionPanel.choose(sourceId, candidate.identity)
            }
            "extension_suggestion_ignore" -> {
                val row = suggestion(params)
                if (!row.canIgnore) fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
                model.suggestionPanel.ignore(row.suggestion.identity)
            }
            "extension_suggestion_undo" -> {
                if (!model.suggestionPanel.state.value.canUndo) fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
                model.suggestionPanel.undo()
            }
            "extension_suggestion_batch_stop" -> {
                val batch = model.suggestionBatch.state.value
                if ((!batch.running && batch.remaining.isEmpty()) || batch.stopping) fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
                model.suggestionBatch.stop()
                confirmation = null
            }
            "extension_suggestion_batch_replace" -> {
                val pending = requireConfirmation(params)
                val identity = params["identity"] ?: fail(SourceExtensionActionFailureCode.MISSING_PARAMETER)
                val candidate = pending.artifacts.flatMap(model::batchReplacementCandidates)
                    .singleOrNull { suggestionIdentityKey(mihon.domain.extension.suggestion.SuggestionIdentity.of(it)) == identity }
                    ?: fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
                confirmation = pending.copy(artifacts = pending.artifacts.map {
                    if (it.packageName == candidate.packageName) candidate else it
                })
            }
            "extension_suggestion_batch_dismiss" -> { requireConfirmation(params); confirmation = null }
            "extension_suggestion_batch_resume_request", "extension_suggestion_batch_retry_request" -> {
                val batch = model.suggestionBatch.state.value
                val mode = if (action.endsWith("resume_request")) ConfirmationMode.RESUME else ConfirmationMode.RETRY
                val previous = if (mode == ConfirmationMode.RESUME) batch.remaining else
                    batch.items.filter { it.result is SuggestionBatchResult.Failed }.map { it.artifact }
                if (batch.running || previous.isEmpty() || (mode == ConfirmationMode.RETRY && batch.remaining.isNotEmpty())) {
                    fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
                }
                confirmation = PendingConfirmation(java.util.UUID.randomUUID().toString(), model.updatedBatchSnapshot(previous), batch.id, mode)
            }
            "extension_suggestion_batch_request" -> {
                val batch = model.suggestionBatch.state.value
                val artifacts = model.suggestionSnapshot()
                if (batch.running || batch.remaining.isNotEmpty() || artifacts.isEmpty()) fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
                confirmation = PendingConfirmation(java.util.UUID.randomUUID().toString(), artifacts, batch.id)
            }
            "extension_suggestion_batch_confirm" -> {
                val pending = requireConfirmation(params)
                if (pending.artifacts.any { !model.isCurrentBatchArtifact(it) } || model.batchSourcesConflict(pending.artifacts, pending.mode != ConfirmationMode.START)) {
                    fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
                }
                val accepted = when (pending.mode) {
                    ConfirmationMode.START -> model.suggestionBatch.start(pending.artifacts, pending.batchId)
                    ConfirmationMode.RESUME -> model.suggestionBatch.resume(pending.artifacts, pending.batchId)
                    ConfirmationMode.RETRY -> model.suggestionBatch.retryFailed(pending.artifacts, pending.batchId)
                }
                if (!accepted) fail(SourceExtensionActionFailureCode.OPERATION_REJECTED)
                confirmation = null
            }
            "extension_suggestion_toggle" -> model.suggestionPanel.toggle()
            "extension_refresh" -> model.refresh()
            "extension_search" -> model.search(params["query"] ?: fail(SourceExtensionActionFailureCode.MISSING_PARAMETER))
            "extension_install" -> model.install(available(requirePackage(params)))
            "extension_update" -> model.update(update(requirePackage(params))) ?: fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
            "extension_retry" -> model.retry(retry(requirePackage(params))) ?: fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
            "extension_cancel" -> model.cancel(cancellable(requirePackage(params)))
            "extension_update_all" -> {
                if (model.updateAllCandidates().isEmpty()) fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
                model.updateAll()
            }
            "extension_uninstall" -> if (!model.uninstall(installed(requirePackage(params)))) fail(SourceExtensionActionFailureCode.OPERATION_REJECTED)
            "extension_trust_confirm" -> {
                requirePending(requirePackage(params)); model.confirmTrust() ?: fail(SourceExtensionActionFailureCode.NO_PENDING_TRUST)
            }
            "extension_trust_dismiss" -> {
                requirePending(requirePackage(params)); if (!model.dismissTrust()) fail(SourceExtensionActionFailureCode.OPERATION_REJECTED)
            }
            else -> fail(SourceExtensionActionFailureCode.UNSUPPORTED_ACTION)
        }
        SourceExtensionActionResult(true, snapshot())
    } catch (failure: SourceExtensionActionFailure) {
        SourceExtensionActionResult(false, snapshot(), failure.code)
    }

    private fun requireConfirmation(params: Map<String, String>): PendingConfirmation {
        val id = params["confirmationId"] ?: fail(SourceExtensionActionFailureCode.MISSING_PARAMETER)
        return confirmation?.takeIf { it.id == id && it.batchId == model.suggestionBatch.state.value.id }
            ?: fail(SourceExtensionActionFailureCode.OPERATION_REJECTED)
    }

    private fun suggestion(params: Map<String, String>) = params["identity"]?.let { identity ->
        model.suggestionPanel.state.value.rows.singleOrNull { suggestionIdentityKey(it.suggestion.identity) == identity }
            ?: fail(SourceExtensionActionFailureCode.UNKNOWN_PACKAGE)
    } ?: fail(SourceExtensionActionFailureCode.MISSING_PARAMETER)

    private fun requirePackage(params: Map<String, String>) = params["packageName"]?.takeIf(String::isNotBlank)
        ?: fail(SourceExtensionActionFailureCode.MISSING_PARAMETER)
    private fun known(packageName: String) = model.state.value.projection?.let { it.installed + it.available }.orEmpty()
        .firstOrNull { it.operationPackageName == packageName } ?: fail(SourceExtensionActionFailureCode.UNKNOWN_PACKAGE)
    private fun available(packageName: String) = known(packageName).let {
        if (!eligibility(packageName).canStart) fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
        model.state.value.presentation?.available.orEmpty().firstOrNull { item -> item.operationPackageName == packageName }
            ?: fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
    }
    private fun update(packageName: String) = known(packageName).let {
        if (!eligibility(packageName).canStart) fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
        model.state.value.presentation?.updates.orEmpty().firstOrNull { item -> item.operationPackageName == packageName }
            ?: fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
    }
    private fun retry(packageName: String): DesktopExtensionItem {
        known(packageName)
        if (!eligibility(packageName).canRetry) fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
        return (model.state.value.presentation?.updates.orEmpty() + model.state.value.presentation?.available.orEmpty())
            .firstOrNull { it.operationPackageName == packageName } ?: fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
    }
    private fun cancellable(packageName: String): String {
        known(packageName)
        if (!eligibility(packageName).canCancel) fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
        return packageName
    }
    private fun eligibility(packageName: String) = model.state.value.let {
        extensionActionEligibility(
            it.actions.installSteps[packageName],
            packageName in it.installErrors,
        )
    }
    private fun installed(packageName: String) = known(packageName).let {
        model.state.value.projection?.installed.orEmpty().firstOrNull { item -> item.operationPackageName == packageName }
            ?: fail(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE)
    }
    private fun requirePending(packageName: String) {
        val pending = model.state.value.pendingTrust ?: fail(SourceExtensionActionFailureCode.NO_PENDING_TRUST)
        if (pending.packageName != packageName) fail(SourceExtensionActionFailureCode.TRUST_PACKAGE_MISMATCH)
    }
}

object SourceExtensionTestModeBridge {
    private val value = AtomicReference<SourceExtensionTestModeController?>()
    val controller: SourceExtensionTestModeController? get() = value.get()
    fun install(controller: SourceExtensionTestModeController) { value.set(controller) }
    fun clear(expected: SourceExtensionTestModeController): Boolean = value.compareAndSet(expected, null)
}

private class SourceExtensionActionFailure(val code: SourceExtensionActionFailureCode) : IllegalStateException()
private fun fail(code: SourceExtensionActionFailureCode): Nothing = throw SourceExtensionActionFailure(code)
private fun DesktopExtensionItem.testSnapshot() = SourceExtensionTestItem(
    operationPackageName, presentation.name, presentation.language, installed != null, available != null,
    presentation.hasUpdate, presentation.sources.map { SourceExtensionTestSource(it.id, it.language, it.name, it.baseUrl) },
)

private fun ExtensionArtifact.batchSnapshot() = SourceExtensionBatchArtifact(
    packageName, name, versionName, versionCode, repository.baseUrl, repository.signingKeyFingerprint,
    suggestionIdentityKey(mihon.domain.extension.suggestion.SuggestionIdentity.of(this)),
)
