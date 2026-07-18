package mihon.domain.extension.presentation

class ExtensionPresentationStore<T>(
    private val adapter: ExtensionPresentationAdapter<T>,
) : ExtensionPresentationClassifier<T> {
    fun reduce(
        state: ExtensionPresentationActionState,
        action: ExtensionPresentationAction,
    ): ExtensionPresentationActionState = when (action) {
        ExtensionPresentationAction.RefreshStarted -> state.copy(isRefreshing = true)
        ExtensionPresentationAction.RefreshFinished -> state.copy(isRefreshing = false)
        is ExtensionPresentationAction.InstallStepChanged -> state.copy(
            installSteps = state.installSteps + (action.packageName to action.step),
        )
        is ExtensionPresentationAction.InstallFinished -> state.copy(
            installSteps = state.installSteps - action.packageName,
        )
    }

    fun shouldContinue(step: ExtensionPresentationInstallStep): Boolean =
        step != ExtensionPresentationInstallStep.Installed

    fun <S> enabledFirst(
        sources: List<S>,
        enabled: (S) -> Boolean,
        displayName: (S) -> String,
    ): List<S> = sources.sortedWith(
        compareBy<S> { !enabled(it) }
            .thenBy(displayName),
    )

    override fun classify(
        installed: List<T>,
        untrusted: List<T>,
        available: List<T>,
        options: ExtensionPresentationOptions,
    ): ExtensionPresentationResult<T> {
        val installedPackages = installed.mapTo(mutableSetOf()) { adapter.describe(it).packageName }
        val untrustedPackages = untrusted.mapTo(mutableSetOf()) { adapter.describe(it).packageName }
        val sortedInstalled = installed
            .filter { options.showNsfw || !adapter.describe(it).isNsfw }
            .sortedWith(
                compareBy<T> { !adapter.describe(it).isObsolete }
                    .thenBy { adapter.describe(it).name.lowercase() },
            )
        val (updates, remainingInstalled) = sortedInstalled.partition { adapter.describe(it).hasUpdate }
        val sortedUntrusted = untrusted.sortedBy { adapter.describe(it).name.lowercase() }
        val projectedAvailable = available
            .filter {
                val item = adapter.describe(it)
                item.packageName !in installedPackages &&
                    item.packageName !in untrustedPackages &&
                    (options.showNsfw || !item.isNsfw)
            }
            .flatMap { extension ->
                val item = adapter.describe(extension)
                if (item.sources.isEmpty()) {
                    if (item.language in options.enabledLanguages) listOf(extension) else emptyList()
                } else {
                    item.sources
                        .filter { it.language in options.enabledLanguages }
                        .map { adapter.projectAvailableSource(extension, it) }
                }
            }
            .sortedBy { adapter.describe(it).name.lowercase() }

        return ExtensionPresentationResult(updates, remainingInstalled, projectedAvailable, sortedUntrusted)
    }

    override fun searchPredicate(query: String, includePackageName: Boolean): (T) -> Boolean =
        { extension -> matches(extension, query, includePackageName) }

    fun matches(extension: T, query: String, includePackageName: Boolean = false): Boolean {
        val subqueries = query.split(',').map(String::trim).filter(String::isNotBlank)
        if (subqueries.isEmpty()) return true
        val item = adapter.describe(extension)
        return subqueries.any { subquery ->
            item.name.contains(subquery, ignoreCase = true) ||
                (includePackageName && item.packageName.contains(subquery, ignoreCase = true)) ||
                item.sources.any { source ->
                    source.name.contains(subquery, ignoreCase = true) ||
                        source.baseUrl?.contains(subquery, ignoreCase = true) == true ||
                        source.id == subquery.toLongOrNull()
                }
        }
    }
}
