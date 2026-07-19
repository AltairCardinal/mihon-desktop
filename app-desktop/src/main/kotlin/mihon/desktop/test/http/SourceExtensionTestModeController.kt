package mihon.desktop.test.http

import kotlinx.serialization.Serializable
import mihon.desktop.ui.extension.DesktopExtensionItem
import mihon.desktop.ui.extension.ExtensionsScreenModel
import mihon.desktop.ui.extension.toExtensionListUiProjection
import mihon.domain.error.StoredAppError
import mihon.domain.error.toStoredAppError
import mihon.domain.extension.presentation.extensionActionEligibility
import java.util.concurrent.atomic.AtomicReference

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
@Serializable data class SourceExtensionTestSnapshot(
    val searchQuery: String, val refreshing: Boolean, val installed: List<SourceExtensionTestItem>,
    val available: List<SourceExtensionTestItem>, val updates: List<SourceExtensionTestItem>,
    val installSteps: Map<String, String>, val errors: Map<String, StoredAppError>,
    val repositoryErrors: List<SourceExtensionRepositoryError>, val pendingTrust: SourceExtensionTrustSnapshot?,
)
@Serializable enum class SourceExtensionActionFailureCode {
    MISSING_PARAMETER, UNKNOWN_PACKAGE, ACTION_UNAVAILABLE, NO_PENDING_TRUST, TRUST_PACKAGE_MISMATCH,
    OPERATION_REJECTED, UNSUPPORTED_ACTION,
}
@Serializable data class SourceExtensionActionResult(
    val success: Boolean, val snapshot: SourceExtensionTestSnapshot, val failureCode: SourceExtensionActionFailureCode? = null,
)

class SourceExtensionTestModeController(private val model: ExtensionsScreenModel) {
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
        )
    }

    fun execute(action: String, params: Map<String, String> = emptyMap()): SourceExtensionActionResult = try {
        when (action) {
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
