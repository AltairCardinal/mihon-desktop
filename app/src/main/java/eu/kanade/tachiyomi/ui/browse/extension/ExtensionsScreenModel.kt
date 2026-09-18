package eu.kanade.tachiyomi.ui.browse.extension

import android.app.Application
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.extension.interactor.androidExtensionPresentationStore
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.ExtensionSuggestionBatchConfirmation
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.toAvailable
import eu.kanade.tachiyomi.extension.util.ExtensionOriginConfirmation
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.presentation.ExtensionPresentationAction
import mihon.domain.extension.presentation.ExtensionPresentationActionState
import mihon.domain.extension.presentation.ExtensionPresentationClassifier
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.presentation.ExtensionPresentationStore
import mihon.domain.extension.service.ExtensionInstallBusy
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.suggestion.ExtensionSuggestionPreferences
import mihon.domain.extension.suggestion.ExtensionSuggestions
import mihon.domain.extension.suggestion.SuggestionBatchPause
import mihon.domain.extension.suggestion.SuggestionBatchResult
import mihon.domain.extension.suggestion.SuggestionIdentity
import mihon.domain.extension.suggestion.SuggestionPanelState
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class ExtensionsScreenModel(
    private val preferences: SourcePreferences = Injekt.get(),
    basePreferences: BasePreferences = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val getExtensions: GetExtensionsByType = Injekt.get(),
    private val classifier: ExtensionPresentationClassifier<Extension> = androidExtensionPresentationStore,
    private val context: Application = Injekt.get(),
    private val actionStore: ExtensionPresentationStore<Extension> =
        androidExtensionPresentationStore,
    suggestions: Flow<ExtensionSuggestions>? = null,
    suggestionPreferences: ExtensionSuggestionPreferences =
        ExtensionSuggestionPreferences(InMemoryPreferenceStore()),
    val suggestionBatch: eu.kanade.tachiyomi.extension.AndroidExtensionSuggestionBatch? = null,
) : StateScreenModel<ExtensionsScreenModel.State>(State()) {

    private val actionState = MutableStateFlow(ExtensionPresentationActionState())
    private val installCollections = ConcurrentHashMap<String, Any>()
    private val sharedInstallSteps =
        combine(actionState, extensionManager.installArbiter.reservations) { local, reservations ->
            local.installSteps + reservations.mapValues { (_, reservation) ->
                when (val progress = reservation.progress) {
                    null, ExtensionInstallState.Queued -> ExtensionPresentationInstallStep.Pending
                    ExtensionInstallState.Preparing -> ExtensionPresentationInstallStep.Downloading
                    is ExtensionInstallState.Installed -> ExtensionPresentationInstallStep.Installed
                    is ExtensionInstallState.Failed ->
                        if (progress.error == AppError.Cancelled) {
                            ExtensionPresentationInstallStep.Idle
                        } else {
                            ExtensionPresentationInstallStep.Error
                        }
                    else -> ExtensionPresentationInstallStep.Installing
                }
            }
        }
    val suggestionPanel = mihon.domain.extension.suggestion.ExtensionSuggestionPanel(
        screenModelScope,
        state.map { it.suggestions }.distinctUntilChanged(),
        sharedInstallSteps,
        suggestionPreferences,
    )

    init {
        extensionManager.pendingSystemPauses.onEach { pauses ->
            mutableState.update { it.copy(pendingSystemPauses = pauses) }
        }.launchIn(screenModelScope)
        suggestionBatch?.state?.onEach { batch -> mutableState.update { it.copy(suggestionBatch = batch) } }
            ?.launchIn(screenModelScope)
        suggestionPanel.state.onEach { panel -> mutableState.update { it.copy(suggestionPanel = panel) } }
            .launchIn(screenModelScope)
        suggestions?.onEach { value -> mutableState.update { it.copy(suggestions = value) } }
            ?.launchIn(screenModelScope)
        val extensionMapper: (Map<String, InstallStep>) -> ((Extension) -> ExtensionUiModel.Item) = { map ->
            {
                ExtensionUiModel.Item(it, map[it.pkgName] ?: InstallStep.Idle)
            }
        }

        screenModelScope.launchIO {
            combine(
                state.map { it.searchQuery }
                    .distinctUntilChanged()
                    .debounce(SEARCH_DEBOUNCE_MILLIS)
                    .map { searchQueryPredicate(it ?: "") },
                sharedInstallSteps.map { steps ->
                    steps.mapValues { InstallStep.valueOf(it.value.name) }
                },
                getExtensions.subscribe(),
            ) { predicate, downloads, (_updates, _installed, _available, _untrusted) ->
                buildMap {
                    val updates = _updates.filter(predicate).map(extensionMapper(downloads))
                    if (updates.isNotEmpty()) {
                        put(ExtensionUiModel.Header.Resource(MR.strings.ext_updates_pending), updates)
                    }

                    val installed = _installed.filter(predicate).map(extensionMapper(downloads))
                    val untrusted = _untrusted.filter(predicate).map(extensionMapper(downloads))
                    if (installed.isNotEmpty() || untrusted.isNotEmpty()) {
                        put(ExtensionUiModel.Header.Resource(MR.strings.ext_installed), installed + untrusted)
                    }

                    val languagesWithExtensions = _available
                        .filter(predicate)
                        .groupBy { it.lang }
                        .toSortedMap(LocaleHelper.comparator)
                        .map { (lang, exts) ->
                            ExtensionUiModel.Header.Text(LocaleHelper.getSourceDisplayName(lang, context)) to
                                exts.map(extensionMapper(downloads))
                        }
                    if (languagesWithExtensions.isNotEmpty()) {
                        putAll(languagesWithExtensions)
                    }
                }
            }
                .collectLatest { items ->
                    mutableState.update { state ->
                        state.copy(
                            isLoading = false,
                            items = items,
                        )
                    }
                }
        }

        screenModelScope.launchIO { findAvailableExtensions() }

        preferences.extensionUpdatesCount().changes()
            .onEach { mutableState.update { state -> state.copy(updates = it) } }
            .launchIn(screenModelScope)

        basePreferences.extensionInstaller().changes()
            .onEach { mutableState.update { state -> state.copy(installer = it) } }
            .launchIn(screenModelScope)

        extensionManager.repositoryFailures
            .onEach { failures -> mutableState.update { it.copy(repositoryFailures = failures) } }
            .launchIn(screenModelScope)

        extensionManager.installErrors
            .onEach { errors -> mutableState.update { it.copy(installErrors = errors) } }
            .launchIn(screenModelScope)

        extensionManager.originConfirmations
            .onEach { requests -> mutableState.update { it.copy(originConfirmations = requests) } }
            .launchIn(screenModelScope)
    }

    internal fun requestSuggestionBatch(mode: BatchReviewMode = BatchReviewMode.START) {
        val batch = suggestionBatch ?: return
        val snapshot = when (mode) {
            BatchReviewMode.START ->
                suggestionPanel.state.value.rows.filter { it.canInstall }.map { it.suggestion.artifact }
            BatchReviewMode.RESUME -> batch.state.value.remaining
            BatchReviewMode.RETRY ->
                batch.state.value.items.filter { it.result is SuggestionBatchResult.Failed }.map { it.artifact }
        }
        mutableState.update {
            it.copy(
                batchReviewMode = mode,
                batchConfirmation = snapshot.takeIf { it.isNotEmpty() }?.let { artifacts ->
                    batchConfirmation(
                        artifacts,
                        mode,
                    )
                },
            )
        }
    }

    internal fun confirmSuggestionBatch(artifacts: List<ExtensionArtifact>): Boolean {
        val batch = suggestionBatch ?: return false
        val review = state.value.batchConfirmation ?: return false
        if (review.artifacts != artifacts) return false
        val mode = state.value.batchReviewMode
        val refreshed = batchConfirmation(review.artifacts, mode)
        mutableState.update { it.copy(batchConfirmation = refreshed) }
        if (refreshed.artifacts != artifacts ||
            refreshed.unavailablePackages.isNotEmpty() ||
            refreshed.hasSourceConflict
        ) {
            return false
        }
        return when (mode) {
            BatchReviewMode.START -> batch.start(artifacts)
            BatchReviewMode.RESUME -> batch.resume(artifacts)
            BatchReviewMode.RETRY -> batch.retryFailed(artifacts)
        }
    }

    internal fun selectBatchReplacement(artifact: ExtensionArtifact) {
        val review = state.value.batchConfirmation ?: return
        if (review.artifacts.none { it.packageName == artifact.packageName } ||
            artifact !in batchCandidates(artifact.packageName)
        ) {
            return
        }
        val selected = review.artifacts.map { if (it.packageName == artifact.packageName) artifact else it }
        mutableState.update { it.copy(batchConfirmation = batchConfirmation(selected, it.batchReviewMode)) }
    }

    internal fun dismissBatchReview() {
        mutableState.update { it.copy(batchConfirmation = null) }
    }

    private fun batchCandidates(packageName: String): List<ExtensionArtifact> = extensionManager.suggestionCatalog.value
        ?.entries.orEmpty().filter {
            it.artifact.packageName == packageName &&
                it.compatibility == ExtensionCompatibility.Compatible
        }
        .map { it.artifact }.groupBy { SuggestionIdentity.of(it) }.values
        .map { versions -> versions.maxWith(compareBy({ it.libVersion }, { it.versionCode })) }
        .filter { !it.isNsfw || preferences.showNsfwSource().get() }

    private fun batchConfirmation(
        artifacts: List<ExtensionArtifact>,
        mode: BatchReviewMode,
    ): ExtensionSuggestionBatchConfirmation {
        val candidates = artifacts.associate { it.packageName to batchCandidates(it.packageName) }
        val updated = artifacts.map { old ->
            candidates.getValue(old.packageName).firstOrNull { SuggestionIdentity.of(it) == SuggestionIdentity.of(old) }
                ?: old
        }
        val unavailable =
            updated.filter { it !in candidates.getValue(it.packageName) }.mapTo(mutableSetOf()) { it.packageName }
        val installed = if (mode == BatchReviewMode.START) {
            emptyList()
        } else {
            suggestionBatch?.state?.value?.items.orEmpty()
                .filter { it.result == SuggestionBatchResult.Installed }.map { it.artifact }
        }
        val sources = (updated + installed).flatMap { artifact -> artifact.sources.map { it.id }.distinct() }
        return ExtensionSuggestionBatchConfirmation(
            updated,
            unavailable,
            candidates,
            hasSourceConflict = sources.size != sources.distinct().size,
        )
    }

    enum class BatchReviewMode { START, RESUME, RETRY }

    fun searchQueryPredicate(query: String, includePackageName: Boolean = false): (Extension) -> Boolean =
        classifier.searchPredicate(query, includePackageName)

    fun search(query: String?) {
        suggestionPanel.search(query.orEmpty())
        mutableState.update {
            it.copy(searchQuery = query)
        }
    }

    fun recheckInstalledInventory() = screenModelScope.launchIO {
        extensionManager.recheckInstalledInventory().join()
    }

    fun updateAllExtensions() {
        screenModelScope.launchIO {
            state.value.items.values.flatten()
                .map { it.extension }
                .filterIsInstance<Extension.Installed>()
                .filter { it.hasUpdate }
                .forEach(::updateExtension)
        }
    }

    fun installExtension(extension: Extension.Available) {
        launchInstall(extension) { extensionManager.installExtension(extension) }
    }

    fun installSuggestion(identity: mihon.domain.extension.suggestion.SuggestionIdentity) {
        val artifact = suggestionPanel.installable(identity) ?: return
        val inventory = extensionManager.inventory.value
        if (!inventory.initialized || inventory.hasUnknownArtifacts ||
            artifact.packageName in inventory.packages
        ) {
            return
        }
        if (extensionManager.suggestionCatalog.value?.entries?.none { it.artifact == artifact } != false) return
        installExtension(artifact.toAvailable())
    }

    fun updateExtension(extension: Extension.Installed) {
        launchInstall(extension) { extensionManager.updateExtension(extension) }
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        synchronized(installCollections) {
            installCollections.remove(extension.pkgName)
            extensionManager.cancelInstallUpdateExtension(extension)
            removeDownloadState(extension)
        }
    }

    private fun addDownloadState(extension: Extension, installStep: InstallStep) {
        if (installStep != InstallStep.Error && installStep != InstallStep.Pending) {
            mutableState.update { it.copy(installErrors = it.installErrors - extension.pkgName) }
        }
        dispatch(
            ExtensionPresentationAction.InstallStepChanged(
                extension.pkgName,
                ExtensionPresentationInstallStep.valueOf(installStep.name),
            ),
        )
    }

    private fun removeDownloadState(extension: Extension) {
        dispatch(ExtensionPresentationAction.InstallFinished(extension.pkgName))
    }

    private fun dispatch(action: ExtensionPresentationAction) {
        actionState.update { actionStore.reduce(it, action) }
        mutableState.update { it.copy(isRefreshing = actionState.value.isRefreshing) }
    }

    private fun launchInstall(extension: Extension, operation: () -> Flow<InstallStep>) {
        val collection = Any()
        synchronized(installCollections) {
            if (installCollections.containsKey(extension.pkgName)) return
            installCollections[extension.pkgName] = collection
            addDownloadState(extension, InstallStep.Pending)
        }
        screenModelScope.launchIO {
            try {
                val steps = synchronized(installCollections) {
                    if (installCollections[extension.pkgName] === collection) operation() else null
                } ?: return@launchIO
                steps
                    .onEach { step ->
                        synchronized(installCollections) {
                            if (installCollections[extension.pkgName] === collection) addDownloadState(extension, step)
                        }
                    }
                    .takeWhile { step ->
                        actionStore.shouldContinue(ExtensionPresentationInstallStep.valueOf(step.name)) &&
                            !step.isCompleted()
                    }
                    .onCompletion {
                        synchronized(installCollections) {
                            if (installCollections.remove(extension.pkgName, collection) &&
                                actionState.value.installSteps[extension.pkgName] !=
                                ExtensionPresentationInstallStep.Error
                            ) {
                                removeDownloadState(extension)
                            }
                        }
                    }
                    .collect()
            } catch (busy: ExtensionInstallBusy) {
                mutableState.update { it.copy(installRequestBusy = true) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                synchronized(installCollections) {
                    if (installCollections[extension.pkgName] === collection) {
                        addDownloadState(extension, InstallStep.Error)
                        mutableState.update {
                            it.copy(installErrors = it.installErrors + (extension.pkgName to AppError.Unknown(failure)))
                        }
                    }
                }
            } finally {
                synchronized(installCollections) {
                    if (installCollections.remove(extension.pkgName, collection) &&
                        actionState.value.installSteps[extension.pkgName] != ExtensionPresentationInstallStep.Error
                    ) {
                        removeDownloadState(extension)
                    }
                }
            }
        }
    }

    fun uninstallExtension(extension: Extension) {
        extensionManager.uninstallExtension(extension)
    }

    fun findAvailableExtensions() {
        screenModelScope.launchIO {
            dispatch(ExtensionPresentationAction.RefreshStarted)

            try {
                extensionManager.findAvailableExtensions()
                // Keep refresh feedback visible for very fast responses.
                delay(1.seconds)
            } finally {
                dispatch(ExtensionPresentationAction.RefreshFinished)
            }
        }
    }

    fun trustExtension(extension: Extension.Untrusted) {
        screenModelScope.launch {
            extensionManager.trust(extension)
        }
    }

    fun answerOriginConfirmation(id: String, accepted: Boolean) {
        extensionManager.answerOriginConfirmation(id, accepted)
    }

    @Immutable
    data class State(
        val pendingSystemPauses: Map<String, SuggestionBatchPause> = emptyMap(),
        val batchReviewMode: BatchReviewMode = BatchReviewMode.START,
        val batchConfirmation: eu.kanade.presentation.browse.ExtensionSuggestionBatchConfirmation? = null,
        val suggestionBatch: mihon.domain.extension.suggestion.SuggestionBatchState =
            mihon.domain.extension.suggestion.SuggestionBatchState(),
        val suggestions: ExtensionSuggestions = ExtensionSuggestions(),
        val suggestionPanel: SuggestionPanelState = SuggestionPanelState(),
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val items: ItemGroups = mutableMapOf(),
        val updates: Int = 0,
        val installer: BasePreferences.ExtensionInstaller? = null,
        val searchQuery: String? = null,
        val repositoryFailures: List<mihon.domain.extension.model.RepositoryCatalogFailure> = emptyList(),
        val installErrors: Map<String, AppError> = emptyMap(),
        val installRequestBusy: Boolean = false,
        val originConfirmations: List<ExtensionOriginConfirmation> = emptyList(),
    ) {
        val isEmpty = items.isEmpty()
    }
}

typealias ItemGroups = Map<ExtensionUiModel.Header, List<ExtensionUiModel.Item>>

object ExtensionUiModel {
    sealed interface Header {
        data class Resource(val textRes: StringResource) : Header
        data class Text(val text: String) : Header
    }

    data class Item(
        val extension: Extension,
        val installStep: InstallStep,
    )
}
