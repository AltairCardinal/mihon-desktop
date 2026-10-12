package tachiyomi.domain.creator.service

/** Explicit device-local opt-in; source names and mirror domains never select this rule. */
class CreatorTitleAttributionPolicy(private val enabledSources: () -> Set<String>) {
    constructor(preferences: CreatorDiscoveryPreferences) : this({ preferences.titleAttributionSources().get() })

    fun isEnabled(sourceId: Long): Boolean = sourceId.toString() in enabledSources()

    companion object {
        val Disabled = CreatorTitleAttributionPolicy { emptySet() }
    }
}
