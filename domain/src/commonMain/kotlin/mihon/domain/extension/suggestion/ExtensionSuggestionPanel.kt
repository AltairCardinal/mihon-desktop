package mihon.domain.extension.suggestion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import java.net.URI
import java.util.Base64
data class SuggestionPanelRow(
    val suggestion: ExtensionSuggestion,
    val step: ExtensionPresentationInstallStep = ExtensionPresentationInstallStep.Idle,
    val canInstall: Boolean = false,
    val canIgnore: Boolean = false,
    val websites: List<ExtensionSourceDescriptor> = emptyList(),
)

data class SuggestionPanelState(
    val expanded: Boolean = true,
    val loading: Boolean = true,
    val rows: List<SuggestionPanelRow> = emptyList(),
    val total: Int = 0,
    val choices: Map<Long, List<ExtensionSuggestion>> = emptyMap(),
    val selected: Map<Long, SuggestionIdentity> = emptyMap(),
    val unmatched: List<UnmatchedLibrarySource> = emptyList(),
    val incomplete: Boolean = false,
    val canUndo: Boolean = false,
    val activeCount: Int = 0,
)

class ExtensionSuggestionPanel(
    scope: CoroutineScope,
    suggestions: Flow<ExtensionSuggestions>,
    steps: Flow<Map<String, ExtensionPresentationInstallStep>>,
    private val preferences: ExtensionSuggestionPreferences,
) {
    private val mutableState = MutableStateFlow(SuggestionPanelState())
    val state = mutableState.asStateFlow()
    private val query = MutableStateFlow("")
    private val selection = MutableStateFlow(emptyMap<Long, SuggestionIdentity>())
    private val undoIdentity = MutableStateFlow<SuggestionIdentity?>(null)
    private var activeRows = emptyList<ExtensionSuggestion>()

    init {
        scope.launch {
            val local = combine(
                preferences.expanded.changes(),
                preferences.ignored.changes(),
                query,
                selection,
                undoIdentity,
            ) { expanded, ignored, query, selected, undo ->
                Local(expanded, ignored.lineSequence().filter(String::isNotBlank).toSet(), query, selected, undo)
            }
            combine(suggestions, steps, local) { result, progress, local ->
                val candidates = (
                    result.suggestions + activeRows.filter {
                        progress[it.artifact.packageName]?.isCompleted() == false
                    }
                    ).distinctBy { it.identity }
                activeRows = candidates.filter { progress[it.artifact.packageName]?.isCompleted() == false }
                val visible = candidates.filter {
                    suggestionIdentityKey(it.identity) !in local.ignored ||
                        it in activeRows
                }
                val choices = result.choices.mapValues { (_, ids) -> visible.filter { it.identity in ids } }
                    .filterValues { it.isNotEmpty() }
                val selected = local.selected.filter { (id, identity) ->
                    choices[id]?.any { it.identity == identity } == true
                }
                val rows = visible.mapNotNull { suggestion ->
                    val busy = progress[suggestion.artifact.packageName]?.isCompleted() == false
                    val sources = suggestion.sources.filter { source ->
                        selected[source.source.id]?.let { it == suggestion.identity } ?: true
                    }
                    if (sources.isEmpty() && !busy) return@mapNotNull null
                    val unresolved = suggestion.sources.any {
                        it.source.id in choices && selected[it.source.id] != suggestion.identity
                    }
                    val conflictsWithActive = activeRows.any { active ->
                        active.identity != suggestion.identity && active.artifact.sources.any { activeSource ->
                            suggestion.artifact.sources.any { it.id == activeSource.id }
                        }
                    }
                    val projected = suggestion.copy(sources = sources, requiresSelection = unresolved)
                    val step = progress[suggestion.artifact.packageName] ?: ExtensionPresentationInstallStep.Idle
                    SuggestionPanelRow(
                        projected,
                        step,
                        !result.isLoading && !busy && !unresolved && !conflictsWithActive &&
                            step != ExtensionPresentationInstallStep.Installed,
                        !busy,
                        sources.map { it.source }.filter { isValidSuggestionWebsite(it.baseUrl) }.distinctBy { it.id },
                    )
                }
                SuggestionPanelState(
                    expanded = local.expanded,
                    loading = result.isLoading,
                    rows = rows.filter { row ->
                        val item = row.suggestion
                        local.query.isBlank() || listOf(
                            item.artifact.name,
                            item.artifact.packageName,
                            item.artifact.repository.name,
                        ).plus(item.sources.map { it.source.name })
                            .any { it.contains(local.query.trim(), ignoreCase = true) }
                    },
                    total = rows.size,
                    choices = choices,
                    selected = selected,
                    unmatched = result.unmatched,
                    incomplete = result.catalogFailures.isNotEmpty(),
                    canUndo = local.undo != null,
                    activeCount = activeRows.size,
                )
            }.collect { mutableState.value = it }
        }
    }

    fun search(query: String) {
        this.query.value = query
    }
    fun toggle() {
        preferences.expanded.set(!preferences.expanded.get())
    }
    fun ignore(identity: SuggestionIdentity) {
        if (state.value.rows.none { it.suggestion.identity == identity && it.canIgnore }) return
        val ignored = preferences.ignored.get().lineSequence().filter(String::isNotBlank).toSet()
        preferences.ignored.set((ignored + suggestionIdentityKey(identity)).joinToString("\n"))
        undoIdentity.value = identity
    }
    fun undo() {
        val identity = undoIdentity.value ?: return
        preferences.ignored.set(
            preferences.ignored.get().lineSequence().filter {
                it != suggestionIdentityKey(identity)
            }.joinToString("\n"),
        )
        undoIdentity.value = null
    }
    fun dismissUndo() {
        undoIdentity.value = null
    }
    fun choose(sourceId: Long, identity: SuggestionIdentity) {
        if (state.value.choices[sourceId]?.none { it.identity == identity } != false) return
        val candidates = state.value.choices.values.flatten().distinctBy { it.identity }
        val candidate = candidates.first { it.identity == identity }
        val sources = candidate.artifact.sources.mapTo(mutableSetOf()) { it.id }
        if (activeRows.any {
                it.identity != identity && it.artifact.sources.any { source -> source.id in sources }
            }
        ) {
            return
        }
        val conflicts = candidates.filter {
            it.identity != identity && it.artifact.sources.any { source -> source.id in sources }
        }.mapTo(mutableSetOf()) { it.identity }
        selection.value = selection.value.filterValues { it !in conflicts } + (sourceId to identity)
    }
    fun installable(identity: SuggestionIdentity): ExtensionArtifact? = state.value.rows
        .firstOrNull { it.suggestion.identity == identity && it.canInstall }?.suggestion?.artifact

    private data class Local(
        val expanded: Boolean,
        val ignored: Set<String>,
        val query: String,
        val selected: Map<Long, SuggestionIdentity>,
        val undo: SuggestionIdentity?,
    )
}

fun suggestionIdentityKey(identity: SuggestionIdentity): String = listOf(
    identity.repositoryUrl,
    identity.signingKeyFingerprint,
    identity.packageName,
).joinToString(".") {
    Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
}

fun isValidSuggestionWebsite(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)
