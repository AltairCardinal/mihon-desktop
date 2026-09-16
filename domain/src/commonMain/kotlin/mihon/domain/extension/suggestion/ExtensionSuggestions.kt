package mihon.domain.extension.suggestion

import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.SharedExtensionUpdatePolicy
import mihon.domain.extensionrepo.model.normalizedSigningKeyFingerprint

data class LibrarySourceCount(val sourceId: Long, val count: Long)

enum class ExtensionPresence { PRESENT, UNTRUSTED, LOAD_FAILED, UNKNOWN }
enum class ExtensionInventoryLocation { ANDROID_SHARED, ANDROID_PRIVATE, DESKTOP }

data class ExtensionInventoryRecord(
    val presence: ExtensionPresence,
    val locations: Set<ExtensionInventoryLocation> = emptySet(),
    val repositoriesByLocation: Map<ExtensionInventoryLocation, RepositoryIdentity> = emptyMap(),
    /** Null means existence was inspected without trying to load the runtime. */
    val runtimeLoaded: Boolean? = null,
) {
    val repository: RepositoryIdentity? get() = repositoriesByLocation.values.distinct().singleOrNull()
}

/** Absence is meaningful only after a complete scan; unknown files must never look uninstalled. */
data class ExtensionInventory(
    val initialized: Boolean = false,
    val hasUnknownArtifacts: Boolean = false,
    val records: Map<String, ExtensionInventoryRecord> = emptyMap(),
) {
    constructor(initialized: Boolean, packages: Map<String, ExtensionPresence>, hasUnknownArtifacts: Boolean = false) :
        this(initialized, hasUnknownArtifacts, packages.mapValues { ExtensionInventoryRecord(it.value) })

    val packages: Map<String, ExtensionPresence> = records.mapValues { it.value.presence }
}

data class SuggestionIdentity(val repositoryUrl: String, val signingKeyFingerprint: String, val packageName: String) {
    companion object {
        fun of(artifact: ExtensionArtifact) = SuggestionIdentity(
            artifact.repository.baseUrl.trim().trimEnd('/'),
            artifact.repository.signingKeyFingerprint.normalizedSigningKeyFingerprint(),
            artifact.packageName,
        )
    }
}

enum class SuggestionProblem {
    NOT_IN_CATALOG,
    INCOMPATIBLE,
    CONTENT_RESTRICTED,
    INSTALLED_UNAVAILABLE,
    INVENTORY_UNKNOWN,
}

data class UnmatchedLibrarySource(val sourceId: Long, val count: Long, val problem: SuggestionProblem)
data class SuggestedSource(val source: ExtensionSourceDescriptor, val count: Long)
data class ExtensionSuggestion(
    val identity: SuggestionIdentity,
    val artifact: ExtensionArtifact,
    val sources: List<SuggestedSource>,
    val requiresSelection: Boolean,
) {
    val mangaCount: Long get() = sources.sumOf { it.count }
}

data class ExtensionSuggestions(
    val isLoading: Boolean = true,
    val suggestions: List<ExtensionSuggestion> = emptyList(),
    val choices: Map<Long, List<SuggestionIdentity>> = emptyMap(),
    val unmatched: List<UnmatchedLibrarySource> = emptyList(),
    val catalogFailures: List<RepositoryCatalogFailure> = emptyList(),
)

class ExtensionSuggestionEngine {
    fun project(
        library: List<LibrarySourceCount>,
        availableSourceIds: Set<Long>,
        catalog: ExtensionCatalogResult?,
        inventory: ExtensionInventory,
        ignored: Set<SuggestionIdentity> = emptySet(),
        showNsfw: Boolean = true,
    ): ExtensionSuggestions {
        if (catalog == null || !inventory.initialized) return ExtensionSuggestions()
        // Choose an update only within one repository/signing-key/package identity, before indexing
        // descriptors. Otherwise a source removed by the update could survive through its old row.
        val candidates = catalog.entries.groupBy { SuggestionIdentity.of(it.artifact) }.values.map { versions ->
            versions.sortedWith(compareBy({ it.artifact.libVersion }, { it.artifact.versionCode }))
                .reduce { current, next ->
                    if (SharedExtensionUpdatePolicy.isUpdateAvailable(
                            next.artifact.versionCode,
                            next.artifact.libVersion,
                            current.artifact.versionCode,
                            current.artifact.libVersion,
                        )
                    ) {
                        next
                    } else {
                        current
                    }
                }
        }
        val index = candidates.flatMap { entry -> entry.artifact.sources.map { it.id to entry } }
            .groupBy({ it.first }, { it.second })
        val sourcesByCandidate = linkedMapOf<SuggestionIdentity, MutableList<SuggestedSource>>()
        val artifacts = linkedMapOf<SuggestionIdentity, ExtensionArtifact>()
        val choices = linkedMapOf<Long, List<SuggestionIdentity>>()
        val unmatched = mutableListOf<UnmatchedLibrarySource>()
        library.filter { it.sourceId != 0L && it.count > 0 && it.sourceId !in availableSourceIds }.forEach { source ->
            val entries = index[source.sourceId].orEmpty().distinctBy { SuggestionIdentity.of(it.artifact) }
            val visible = entries.filter { SuggestionIdentity.of(it.artifact) !in ignored }
            // Explicitly dismissing every candidate also dismisses the unresolved reminder.
            if (entries.isNotEmpty() && visible.isEmpty()) return@forEach
            val installed = entries.any {
                inventory.packages[it.artifact.packageName]?.let { it != ExtensionPresence.UNKNOWN } == true
            }
            val unknownInventory = inventory.hasUnknownArtifacts || visible.any {
                inventory.packages[it.artifact.packageName] == ExtensionPresence.UNKNOWN
            }
            val compatible = visible.filter { it.compatibility == ExtensionCompatibility.Compatible }
            val allowed = compatible.filter { showNsfw || !it.artifact.isNsfw }
            val problem = when {
                installed -> SuggestionProblem.INSTALLED_UNAVAILABLE
                unknownInventory ->
                    SuggestionProblem.INVENTORY_UNKNOWN
                entries.isEmpty() -> SuggestionProblem.NOT_IN_CATALOG
                compatible.isEmpty() -> SuggestionProblem.INCOMPATIBLE
                allowed.isEmpty() -> SuggestionProblem.CONTENT_RESTRICTED
                else -> null
            }
            if (problem != null) {
                unmatched += UnmatchedLibrarySource(source.sourceId, source.count, problem)
            } else {
                choices[source.sourceId] = allowed.map { SuggestionIdentity.of(it.artifact) }
                allowed.forEach { entry ->
                    val artifact = entry.artifact
                    val identity = SuggestionIdentity.of(artifact)
                    artifacts[identity] = artifact
                    sourcesByCandidate.getOrPut(identity) { mutableListOf() } += SuggestedSource(
                        artifact.sources.first { it.id == source.sourceId },
                        source.count,
                    )
                }
            }
        }
        return ExtensionSuggestions(
            isLoading = false,
            suggestions = sourcesByCandidate.map { (identity, sources) ->
                ExtensionSuggestion(
                    identity,
                    artifacts.getValue(identity),
                    sources,
                    sources.any { choices.getValue(it.source.id).size > 1 },
                )
            },
            choices = choices.filterValues { it.size > 1 },
            unmatched = unmatched,
            catalogFailures = catalog.failures,
        )
    }
}
