package mihon.desktop.ui.extension

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.domain.extension.presentation.ExtensionPresentationAction
import mihon.domain.extension.presentation.ExtensionPresentationActionState
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.presentation.ExtensionPresentationResult

data class DesktopExtensionsState(
    val projection: DesktopExtensionProjection? = null,
    val presentation: ExtensionPresentationResult<DesktopExtensionItem>? = null,
    val actions: ExtensionPresentationActionState = ExtensionPresentationActionState(),
    val options: ExtensionPresentationOptions,
    val refreshError: Throwable? = null,
)

class ExtensionsScreenModel(
    private val port: DesktopExtensionPresentationPort,
    private val scope: CoroutineScope,
    initialOptions: ExtensionPresentationOptions,
) {
    private val options = MutableStateFlow(initialOptions)
    private val mutableState = MutableStateFlow(DesktopExtensionsState(options = initialOptions))
    val state: StateFlow<DesktopExtensionsState> = mutableState.asStateFlow()
    private var latestCatalog: DesktopExtensionCatalogState? = null

    init {
        scope.launch {
            combine(port.installedExtensions, options) { _, currentOptions -> currentOptions }
                .collect(::publish)
        }
    }

    fun refresh(): Job = scope.launch {
        dispatch(ExtensionPresentationAction.RefreshStarted)
        try {
            latestCatalog = port.refresh()
            publish(options.value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(refreshError = error) }
        } finally {
            dispatch(ExtensionPresentationAction.RefreshFinished)
        }
    }

    fun setOptions(value: ExtensionPresentationOptions) {
        options.value = value
        mutableState.update { it.copy(options = value) }
    }

    fun updateAllCandidates() = latestCatalog?.available.orEmpty().filter { candidate ->
        state.value.projection?.installed.orEmpty().any {
            it.presentation.hasUpdate && it.operationPackageName == candidate.pkgName
        }
    }

    fun uninstall(item: DesktopExtensionItem): Boolean = port.uninstall(item)

    private fun publish(currentOptions: ExtensionPresentationOptions) {
        val catalog = latestCatalog ?: return
        val projection = port.project(catalog)
        mutableState.update {
            it.copy(
                projection = projection,
                presentation = port.classify(projection, currentOptions),
                options = currentOptions,
                refreshError = null,
            )
        }
    }

    private fun dispatch(action: ExtensionPresentationAction) {
        mutableState.update { it.copy(actions = port.reduceActions(it.actions, action)) }
    }
}
