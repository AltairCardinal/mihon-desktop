package eu.kanade.tachiyomi.uicatalog

/** Pure, deterministic fixture state. This file has no Android, database, network or filesystem access. */
enum class Phase { READY, LOADING, EMPTY, NO_RESULTS, ERROR, RUNNING, PAUSED, DONE, MISSING }
enum class Overlay { NONE, MENU, CONFIRM, SHEET, EDIT, DISCARD, TRUST, PICKER }

data class DemoState(
    val category: Int,
    val scenario: Int = 0,
    val phase: Phase = Phase.READY,
    val query: String? = null,
    val selected: Set<Int> = emptySet(),
    val order: List<Int> = (1..6).toList(),
    val draft: String = "",
    val saved: String = "",
    val enabled: Boolean = true,
    val secondary: Boolean = false,
    val tab: Int = 0,
    val depth: Int = 0,
    val overlay: Overlay = Overlay.NONE,
    val overlayDepth: Int = 0,
    val pendingOverlay: Overlay = Overlay.NONE,
    val page: Int = 1,
    val totalPages: Int = 10,
    val progress: Int = 0,
    val starts: Int = 0,
    val commits: Int = 0,
    val filter: Int = 0,
    val descending: Boolean = false,
    val read: Set<Int> = emptySet(),
    val downloaded: Set<Int> = setOf(1, 2),
    val bookmarked: Set<Int> = emptySet(),
    val removeLibrary: Boolean = false,
    val removeDownloads: Boolean = false,
    val downloadedOnly: Boolean = false,
    val privateMode: Boolean = false,
    val historyCount: Int = 0,
    val barsVisible: Boolean = true,
    val scoringSupported: Boolean = true,
    val bound: Boolean = false,
    val trusted: Boolean = false,
    val permission: Boolean = true,
    val directorySelected: Boolean = false,
    val score: Int = 0,
    val lastEvent: String = "ready",
) {
    val canCommitName: Boolean
        get() = draft.trim().isNotEmpty() && draft.trim() !in setOf("Existing", "已有名称") && draft.trim() != saved
    val canDelete: Boolean get() = removeLibrary || removeDownloads
    val canContinue: Boolean get() = phase != Phase.MISSING && order.any { it !in read } && selected.isEmpty()
}

fun fixture(category: Int, scenario: Int = 0): DemoState {
    require(category in 1..32) { "Unknown category: $category" }
    require(scenario in 0..3) { "Unknown scenario: $scenario" }
    val s = DemoState(category, scenario)
    return when (category) {
        1 -> when (scenario) { 2 -> s.copy(phase = Phase.LOADING); 3 -> s.copy(phase = Phase.ERROR); else -> s }
        2 -> s.copy(tab = if (scenario == 1) 1 else 0, secondary = scenario == 2)
        3 -> when (scenario) { 2 -> s.copy(selected = setOf(1, 2)); 3 -> s.copy(query = ""); else -> s }
        4 -> when (scenario) {
            0 -> s.copy(depth = 1)
            1 -> s.copy(selected = setOf(1), query = "query")
            2 -> s.copy(query = "query")
            else -> s.copy(overlay = Overlay.SHEET, overlayDepth = 1)
        }
        5 -> s.copy(order = (1..30).toList(), secondary = scenario == 1)
        6 -> s.copy(draft = if (scenario > 0) "Draft" else "", saved = "", tab = if (scenario > 0) 1 else 0)
        7 -> when (scenario) {
            1 -> s.copy(read = s.order.toSet())
            2 -> s.copy(selected = setOf(1, 2))
            3 -> s.copy(phase = Phase.RUNNING, starts = 1)
            else -> s
        }
        8, 9 -> s.copy(selected = if (scenario == 3 || category == 9 && scenario == 1) setOf(1) else emptySet())
        10 -> s.copy(selected = if (scenario == 1) setOf(1) else emptySet())
        11 -> when (scenario) {
            0 -> s
            1 -> s.copy(query = "")
            2 -> s.copy(query = "sample")
            else -> s.copy(query = "none", phase = Phase.NO_RESULTS)
        }
        12 -> s.copy(filter = if (scenario == 1 || scenario == 3) 1 else 0, downloadedOnly = scenario == 3)
        13 -> s.copy(enabled = scenario != 3)
        14 -> s.copy(draft = if (scenario == 3) "Unsaved" else "")
        15 -> s.copy(enabled = scenario != 2, draft = "Original", saved = "Original")
        16 -> when (scenario) {
            1 -> s.copy(draft = "Existing")
            2 -> s.copy(draft = "New category")
            3 -> s.copy(draft = "Changed", saved = "Original")
            else -> s
        }
        17 -> s.copy(removeLibrary = scenario > 0)
        18 -> s.copy(phase = listOf(Phase.LOADING, Phase.EMPTY, Phase.NO_RESULTS, Phase.ERROR)[scenario])
        19 -> s.copy(order = if (scenario == 0) emptyList() else s.order, phase = if (scenario == 2) Phase.ERROR else if (scenario == 3) Phase.RUNNING else Phase.READY,
            starts = if (scenario == 3) 1 else 0)
        20 -> s.copy(draft = "Keep this draft", phase = if (scenario == 1 || scenario == 2) Phase.ERROR else Phase.READY)
        21 -> s.copy(downloadedOnly = scenario == 1 || scenario == 3, privateMode = scenario >= 2,
            secondary = scenario == 3, filter = if (scenario == 1 || scenario == 3) 1 else 0)
        22, 26 -> s.copy(enabled = scenario != 2, phase = if (category == 22 && scenario == 3) Phase.ERROR else Phase.READY)
        23 -> s.copy(secondary = scenario == 1)
        24 -> s.copy(order = (1..30).toList())
        25 -> s
        27 -> when (scenario) {
            1 -> s.copy(read = s.order.toSet())
            2 -> s.copy(selected = setOf(1, 2))
            3 -> s.copy(phase = Phase.MISSING)
            else -> s
        }
        28 -> s.copy(page = if (scenario == 1) 10 else if (scenario == 3) 5 else 1, descending = scenario == 2)
        29 -> when (scenario) {
            1 -> s.copy(phase = Phase.RUNNING, starts = 1, progress = 25)
            2 -> s.copy(phase = Phase.ERROR, progress = 25)
            3 -> s.copy(order = emptyList(), phase = Phase.EMPTY)
            else -> s
        }
        30 -> s.copy(trusted = scenario != 2, permission = scenario != 3,
            phase = if (scenario == 2) Phase.MISSING else Phase.READY)
        31 -> s.copy(bound = scenario > 0, scoringSupported = scenario != 2,
            phase = if (scenario == 3) Phase.ERROR else Phase.READY)
        32 -> s.copy(directorySelected = scenario > 0, permission = scenario != 2,
            phase = if (scenario == 3) Phase.ERROR else Phase.READY)
        else -> s
    }
}

sealed interface DemoAction {
    data class Scenario(val index: Int) : DemoAction
    data class Query(val value: String?) : DemoAction
    data class Draft(val value: String) : DemoAction
    data class Select(val id: Int) : DemoAction
    data class Selection(val values: Set<Int>) : DemoAction
    data class RangeSelect(val id: Int) : DemoAction
    data class Move(val id: Int, val delta: Int) : DemoAction
    data class Tab(val value: Int) : DemoAction
    data class Modal(val value: Overlay) : DemoAction
    data class Page(val value: Int) : DemoAction
    data class Score(val value: Int) : DemoAction
    data class Event(val value: String) : DemoAction
    data object SelectAll : DemoAction
    data object InvertSelection : DemoAction
    data object ClearSelection : DemoAction
    data object CommitName : DemoAction
    data object CancelEdit : DemoAction
    data object SubmitSearch : DemoAction
    data object ToggleEnabled : DemoAction
    data object ToggleSecondary : DemoAction
    data object CycleFilter : DemoAction
    data object ResetFilter : DemoAction
    data object AddFixture : DemoAction
    data object RemoveFixture : DemoAction
    data object Sort : DemoAction
    data object MarkRead : DemoAction
    data object Bookmark : DemoAction
    data object ContinueReading : DemoAction
    data object Start : DemoAction
    data object Tick : DemoAction
    data object Pause : DemoAction
    data object Fail : DemoAction
    data object Retry : DemoAction
    data object ClearQueue : DemoAction
    data object ScopeLibrary : DemoAction
    data object ScopeDownloads : DemoAction
    data object ConfirmDelete : DemoAction
    data object ToggleBars : DemoAction
    data object Nested : DemoAction
    data object ConfirmDiscard : DemoAction
    data object Trust : DemoAction
    data object PermissionGranted : DemoAction
    data object Bind : DemoAction
    data object Unbind : DemoAction
    data object ChooseDirectory : DemoAction
    data object FakeRead : DemoAction
}

fun reduce(s: DemoState, a: DemoAction): DemoState = when (a) {
    is DemoAction.Scenario -> fixture(s.category, a.index)
    is DemoAction.Query -> s.copy(query = a.value, phase = if (a.value == "none") Phase.NO_RESULTS else Phase.READY)
    is DemoAction.Draft -> s.copy(draft = a.value)
    is DemoAction.Selection -> s.copy(selected = a.values.intersect(s.order.toSet()))
    is DemoAction.Select -> if (a.id in s.order) s.copy(selected = s.selected.toggle(a.id), lastEvent = "select:${a.id}") else s
    is DemoAction.RangeSelect -> {
        val end = s.order.indexOf(a.id)
        val start = s.order.indexOf(s.selected.lastOrNull()).takeIf { it >= 0 } ?: end
        if (end < 0) s else s.copy(selected = s.selected + s.order.subList(minOf(start, end), maxOf(start, end) + 1), lastEvent = "range:${a.id}")
    }
    is DemoAction.Move -> {
        val from = s.order.indexOf(a.id)
        if (from < 0) s else {
            val to = (from + a.delta).coerceIn(0, s.order.lastIndex)
            val order = s.order.toMutableList().apply { add(to, removeAt(from)) }
            s.copy(order = order, lastEvent = "move:${a.id}:$to")
        }
    }
    is DemoAction.Tab -> if (a.value !in 0..4) s else s.copy(tab = a.value, lastEvent = if (s.tab == a.value) "reselect:${a.value}" else "tab:${a.value}")
    is DemoAction.Modal -> s.copy(overlay = a.value, overlayDepth = 0)
    is DemoAction.Page -> s.copy(page = a.value.coerceIn(1, s.totalPages), lastEvent = "page")
    is DemoAction.Score -> if (!s.scoringSupported) s else s.copy(score = a.value.coerceIn(0, 10))
    is DemoAction.Event -> s.copy(lastEvent = a.value)
    DemoAction.SelectAll -> s.copy(selected = s.order.toSet(), lastEvent = "select-all")
    DemoAction.InvertSelection -> s.copy(selected = s.order.toSet() - s.selected, lastEvent = "invert")
    DemoAction.ClearSelection -> s.copy(selected = emptySet())
    DemoAction.CommitName -> if (!s.canCommitName) s.copy(lastEvent = "invalid-name") else s.copy(saved = s.draft.trim(), draft = s.draft.trim(), commits = s.commits + 1, overlay = Overlay.NONE, lastEvent = "name-committed")
    DemoAction.CancelEdit -> s.copy(draft = s.saved, overlay = Overlay.NONE, lastEvent = "edit-cancelled")
    DemoAction.SubmitSearch -> if (s.query.isNullOrBlank()) s else s.copy(phase = if (s.query == "none") Phase.NO_RESULTS else Phase.READY, lastEvent = "search:${s.query}")
    DemoAction.ToggleEnabled -> s.copy(enabled = !s.enabled)
    DemoAction.ToggleSecondary -> s.copy(secondary = !s.secondary)
    DemoAction.ResetFilter -> if (s.downloadedOnly) s else s.copy(filter = 0)
    DemoAction.AddFixture -> s.copy(order = listOf((s.order.maxOrNull() ?: 0) + 1) + s.order)
    DemoAction.RemoveFixture -> {
        val order = s.order.drop(1)
        s.copy(order = order, selected = s.selected.intersect(order.toSet()))
    }
    DemoAction.CycleFilter -> if (s.downloadedOnly) s else s.copy(filter = (s.filter + 1) % 3)
    DemoAction.Sort -> s.copy(descending = !s.descending, order = if (!s.descending) s.order.sortedDescending() else s.order.sorted())
    DemoAction.MarkRead -> s.copy(read = s.read + s.selected, lastEvent = "read:${s.selected.sorted()}")
    DemoAction.Bookmark -> s.copy(bookmarked = s.bookmarked + s.selected, lastEvent = "bookmark:${s.selected.sorted()}")
    DemoAction.ContinueReading -> if (!s.canContinue) s else s.copy(page = 1, lastEvent = "continue:${s.order.first { it !in s.read }}")
    DemoAction.Start -> when {
        s.phase == Phase.RUNNING || s.category == 29 && s.order.isEmpty() -> s
        s.category == 32 && (!s.directorySelected || !s.permission || s.phase == Phase.ERROR) -> s.copy(lastEvent = "blocked-storage")
        s.category == 30 && (!s.trusted || !s.permission) -> s.copy(lastEvent = "blocked-extension")
        else -> s.copy(phase = Phase.RUNNING, starts = s.starts + if (s.phase == Phase.PAUSED) 0 else 1,
            progress = if (s.phase == Phase.DONE) 0 else s.progress, lastEvent = "started")
    }
    DemoAction.Tick -> if (s.phase != Phase.RUNNING) s else {
        val p = (s.progress + 25).coerceAtMost(100)
        val order = when {
            p == 100 && s.category == 19 && s.scenario == 0 -> (1..6).toList()
            p == 100 && s.category == 19 && s.scenario == 2 -> (s.order + (7..9)).distinct()
            else -> s.order
        }
        s.copy(progress = p, order = order, phase = if (p == 100) Phase.DONE else Phase.RUNNING, lastEvent = if (p == 100) "completed" else "progress")
    }
    DemoAction.Pause -> if (s.phase == Phase.RUNNING) s.copy(phase = Phase.PAUSED) else s
    DemoAction.Fail -> s.copy(phase = Phase.ERROR, lastEvent = "failed")
    DemoAction.Retry -> if (s.phase == Phase.RUNNING) s else if (s.category in setOf(19, 29)) reduce(s, DemoAction.Start) else s.copy(phase = Phase.READY, lastEvent = "retried")
    DemoAction.ClearQueue -> s.copy(order = emptyList(), selected = emptySet(), phase = Phase.EMPTY, lastEvent = "queue-cleared")
    DemoAction.ScopeLibrary -> s.copy(removeLibrary = !s.removeLibrary)
    DemoAction.ScopeDownloads -> s.copy(removeDownloads = !s.removeDownloads)
    DemoAction.ConfirmDelete -> if (s.overlay != Overlay.CONFIRM || !s.canDelete) s else {
        val targets = s.selected.ifEmpty { s.order.toSet() }
        val order = if (s.removeLibrary) s.order.filterNot { it in targets } else s.order
        s.copy(order = order, downloaded = if (s.removeDownloads) s.downloaded - targets else s.downloaded,
            selected = s.selected.intersect(order.toSet()), overlay = Overlay.NONE,
            commits = s.commits + 1, lastEvent = "removed-fixtures")
    }
    DemoAction.ToggleBars -> s.copy(barsVisible = !s.barsVisible)
    DemoAction.Nested -> s.copy(overlay = Overlay.SHEET, overlayDepth = s.overlayDepth + 1)
    DemoAction.ConfirmDiscard -> if (s.overlay != Overlay.DISCARD) s else s.copy(draft = s.saved, overlay = Overlay.NONE, overlayDepth = 0, pendingOverlay = Overlay.NONE)
    DemoAction.Trust -> if (s.overlay != Overlay.TRUST) s else s.copy(trusted = true, overlay = Overlay.NONE, phase = Phase.READY, lastEvent = "trusted-fixture")
    DemoAction.PermissionGranted -> s.copy(permission = true, lastEvent = "fixture-permission-granted")
    DemoAction.Bind -> s.copy(bound = true, phase = Phase.READY, lastEvent = "bound-fixture")
    DemoAction.Unbind -> if (s.overlay != Overlay.CONFIRM) s else s.copy(bound = false, overlay = Overlay.NONE, lastEvent = "unbound-fixture")
    DemoAction.ChooseDirectory -> s.copy(directorySelected = true, permission = true, overlay = Overlay.NONE, phase = Phase.READY, lastEvent = "fixture-directory")
    DemoAction.FakeRead -> s.copy(historyCount = s.historyCount + if (s.privateMode) 0 else 1, lastEvent = "fake-read")
}

private fun Set<Int>.toggle(id: Int): Set<Int> = if (id in this) this - id else this + id

data class BackResult(val state: DemoState, val exitCategory: Boolean = false)
fun back(s: DemoState): BackResult = when {
    s.overlay == Overlay.DISCARD -> BackResult(s.copy(overlay = s.pendingOverlay, pendingOverlay = Overlay.NONE))
    s.overlay == Overlay.SHEET && s.overlayDepth > 0 -> BackResult(s.copy(overlayDepth = s.overlayDepth - 1))
    s.overlay in setOf(Overlay.EDIT, Overlay.SHEET) && s.draft != s.saved -> BackResult(s.copy(overlay = Overlay.DISCARD, pendingOverlay = s.overlay))
    s.overlay != Overlay.NONE -> BackResult(s.copy(overlay = Overlay.NONE))
    s.selected.isNotEmpty() -> BackResult(s.copy(selected = emptySet()))
    s.query != null -> BackResult(s.copy(query = null, phase = Phase.READY))
    s.depth > 0 -> BackResult(s.copy(depth = s.depth - 1))
    s.category == 2 && s.tab != 0 -> BackResult(s.copy(tab = 0))
    else -> BackResult(s, exitCategory = true)
}

/** Versioned primitive snapshot. Used by rememberSaveable and by real JVM regression checks. */
fun DemoState.snapshot(): List<String> = listOf(
    "1", category.toString(), scenario.toString(), phase.name, if (query == null) "0" else "1", query.orEmpty(),
    selected.joinToString(","), order.joinToString(","), draft, saved, enabled.toString(), secondary.toString(),
    tab.toString(), depth.toString(), overlay.name, overlayDepth.toString(), page.toString(), totalPages.toString(),
    progress.toString(), starts.toString(), commits.toString(), filter.toString(), descending.toString(),
    read.joinToString(","), downloaded.joinToString(","), bookmarked.joinToString(","),
    removeLibrary.toString(), removeDownloads.toString(), downloadedOnly.toString(), privateMode.toString(),
    historyCount.toString(), barsVisible.toString(), scoringSupported.toString(), bound.toString(), trusted.toString(),
    permission.toString(), directorySelected.toString(), score.toString(), lastEvent, pendingOverlay.name,
)

fun restoreSnapshot(v: List<String>): DemoState? = runCatching {
    require(v.size == 40 && v[0] == "1")
    fun ids(index: Int): List<Int> = v[index].takeIf { it.isNotEmpty() }?.split(',')?.map(String::toInt) ?: emptyList()
    val s = DemoState(
        category = v[1].toInt(), scenario = v[2].toInt(), phase = Phase.valueOf(v[3]), query = if (v[4] == "0") null else v[5],
        selected = ids(6).toSet(), order = ids(7), draft = v[8], saved = v[9], enabled = v[10].toBooleanStrict(), secondary = v[11].toBooleanStrict(),
        tab = v[12].toInt(), depth = v[13].toInt(), overlay = Overlay.valueOf(v[14]), overlayDepth = v[15].toInt(),
        page = v[16].toInt(), totalPages = v[17].toInt(), progress = v[18].toInt(), starts = v[19].toInt(), commits = v[20].toInt(),
        filter = v[21].toInt(), descending = v[22].toBooleanStrict(), read = ids(23).toSet(), downloaded = ids(24).toSet(), bookmarked = ids(25).toSet(),
        removeLibrary = v[26].toBooleanStrict(), removeDownloads = v[27].toBooleanStrict(), downloadedOnly = v[28].toBooleanStrict(),
        privateMode = v[29].toBooleanStrict(), historyCount = v[30].toInt(), barsVisible = v[31].toBooleanStrict(), scoringSupported = v[32].toBooleanStrict(),
        bound = v[33].toBooleanStrict(), trusted = v[34].toBooleanStrict(), permission = v[35].toBooleanStrict(), directorySelected = v[36].toBooleanStrict(),
        score = v[37].toInt(), lastEvent = v[38], pendingOverlay = Overlay.valueOf(v[39]),
    )
    require(s.category in 1..32 && s.scenario in 0..3 && s.filter in 0..2 && s.tab in 0..4)
    require(s.totalPages > 0 && s.page in 1..s.totalPages && s.progress in 0..100 && s.score in 0..10)
    require(s.order.distinct().size == s.order.size && s.selected.all { it in s.order })
    require(s.depth >= 0 && s.overlayDepth >= 0 && s.starts >= 0 && s.commits >= 0 && s.historyCount >= 0)
    s
}.getOrNull()
