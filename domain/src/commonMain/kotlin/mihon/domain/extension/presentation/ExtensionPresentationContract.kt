package mihon.domain.extension.presentation

data class ExtensionPresentationSource(
    val id: Long,
    val language: String,
    val name: String,
    val baseUrl: String? = null,
)

data class ExtensionPresentationItem(
    val name: String,
    val packageName: String,
    val language: String?,
    val isNsfw: Boolean,
    val hasUpdate: Boolean = false,
    val isObsolete: Boolean = false,
    val sources: List<ExtensionPresentationSource> = emptyList(),
)

interface ExtensionPresentationAdapter<T> {
    fun describe(extension: T): ExtensionPresentationItem
    fun projectAvailableSource(extension: T, source: ExtensionPresentationSource): T
}

interface ExtensionPresentationClassifier<T> {
    fun classify(
        installed: List<T>,
        untrusted: List<T>,
        available: List<T>,
        options: ExtensionPresentationOptions,
    ): ExtensionPresentationResult<T>

    fun searchPredicate(query: String, includePackageName: Boolean = false): (T) -> Boolean
}

data class ExtensionPresentationOptions(
    val showNsfw: Boolean,
    val enabledLanguages: Set<String>,
)

data class ExtensionPresentationResult<T>(
    val updates: List<T>,
    val installed: List<T>,
    val available: List<T>,
    val untrusted: List<T>,
)

enum class ExtensionPresentationInstallStep {
    Idle,
    Pending,
    Downloading,
    Installing,
    Installed,
    Error,
}

data class ExtensionPresentationActionState(
    val isRefreshing: Boolean = false,
    val installSteps: Map<String, ExtensionPresentationInstallStep> = emptyMap(),
)

sealed interface ExtensionPresentationAction {
    data object RefreshStarted : ExtensionPresentationAction
    data object RefreshFinished : ExtensionPresentationAction
    data class InstallStepChanged(
        val packageName: String,
        val step: ExtensionPresentationInstallStep,
    ) : ExtensionPresentationAction
    data class InstallFinished(val packageName: String) : ExtensionPresentationAction
}
