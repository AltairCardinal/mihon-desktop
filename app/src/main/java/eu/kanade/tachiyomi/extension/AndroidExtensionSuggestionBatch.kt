package eu.kanade.tachiyomi.extension

import eu.kanade.domain.base.BasePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.service.ExtensionInstallInvalidation
import mihon.domain.extension.suggestion.ExtensionSuggestionBatchController
import mihon.domain.extension.suggestion.ExtensionSuggestionPreferences
import mihon.domain.extension.suggestion.ExtensionSuggestions
import mihon.domain.extension.suggestion.SuggestionIdentity
import mihon.domain.extension.suggestion.suggestionIdentityKey

/** Application-owned Android adapter for the shared batch transaction. */
class AndroidExtensionSuggestionBatch(
    private val manager: ExtensionManager,
    suggestions: Flow<ExtensionSuggestions>,
    private val preferences: ExtensionSuggestionPreferences,
    private val currentInstaller: () -> BasePreferences.ExtensionInstaller,
) {
    private val lock = Any()
    private var confirmationGeneration = 0L
    private var reconfirmation: mihon.domain.extension.suggestion.SuggestionBatchPause? = null

    @Volatile
    private var confirmedInstaller = currentInstaller()
    val suggestions = suggestions.stateIn(manager.scope, SharingStarted.Eagerly, ExtensionSuggestions())
    private val controller = ExtensionSuggestionBatchController(
        manager.scope,
        manager.installArbiter,
        { lease, progress -> install(lease, progress) },
        ::eligibility,
    )
    val state = controller.state

    fun start(confirmed: List<ExtensionArtifact>): Boolean = confirm { controller.start(confirmed) }
    fun resume(confirmed: List<ExtensionArtifact>): Boolean = confirm { controller.resume(confirmed) }
    fun retryFailed(confirmed: List<ExtensionArtifact>): Boolean = confirm { controller.retryFailed(confirmed) }
    fun stop() = controller.stop()

    private suspend fun install(
        lease: mihon.domain.extension.service.ExtensionInstallLease,
        progress: (Long, mihon.domain.extension.service.ExtensionInstallState) -> Unit,
    ): mihon.domain.extension.suggestion.SuggestionBatchResult {
        val generation = synchronized(lock) {
            reconfirmation?.let { return mihon.domain.extension.suggestion.SuggestionBatchResult.Paused(it) }
            confirmationGeneration
        }
        return manager.installReservedObserved(lease, progress) { reason ->
            synchronized(lock) {
                if (confirmationGeneration == generation) reconfirmation = reconfirmation ?: reason
            }
        }
    }

    private fun eligibility(artifact: ExtensionArtifact): ExtensionInstallInvalidation? {
        if (confirmedInstaller != currentInstaller()) return ExtensionInstallInvalidation.INSTALLER_CHANGED
        val identity = SuggestionIdentity.of(artifact)
        if (suggestionIdentityKey(identity) in preferences.ignored.get().lineSequence()) {
            return ExtensionInstallInvalidation.IGNORED
        }
        val inventory = manager.inventory.value
        if (!inventory.initialized ||
            inventory.hasUnknownArtifacts
        ) {
            return ExtensionInstallInvalidation.INVENTORY_UNKNOWN
        }
        if (artifact.packageName in inventory.records) return ExtensionInstallInvalidation.PRESENT
        val current = manager.suggestionCatalog.value?.entries.orEmpty()
            .filter { SuggestionIdentity.of(it.artifact) == identity }
            .maxWithOrNull(compareBy({ it.artifact.libVersion }, { it.artifact.versionCode }))?.artifact
        if (current != artifact) return ExtensionInstallInvalidation.CATALOG_CHANGED
        val latest = suggestions.value
        if (latest.isLoading) return ExtensionInstallInvalidation.INVENTORY_UNKNOWN
        if (latest.suggestions.none { it.identity == identity }) return ExtensionInstallInvalidation.INELIGIBLE
        return null
    }

    private fun confirm(action: () -> Boolean): Boolean = synchronized(lock) {
        if (state.value.running) return@synchronized false
        val previous = confirmedInstaller
        val previousGeneration = confirmationGeneration
        val previousReason = reconfirmation
        confirmedInstaller = currentInstaller()
        confirmationGeneration++
        reconfirmation = null
        action().also {
            if (!it) {
                confirmedInstaller = previous
                confirmationGeneration = previousGeneration
                reconfirmation = previousReason
            }
        }
    }
}
