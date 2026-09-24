package eu.kanade.tachiyomi.uicatalog

private var passed = 0
private var failed = 0
private fun checkCase(name: String, block: () -> Unit) {
    try { block(); passed++; println("PASS\t$name") }
    catch (e: Throwable) { failed++; println("FAIL\t$name\t${e.message}") }
}
private fun expect(condition: Boolean, message: String = "Unexpected fixture state") { check(condition) { message } }

fun main() {
    for (category in 1..32) for (scenario in 0..3) {
        checkCase("snapshot.UI-${category.toString().padStart(2, '0')}-C${(scenario + 1).toString().padStart(2, '0')}") {
            val state = fixture(category, scenario)
            expect(restoreSnapshot(state.snapshot()) == state, "Fixture snapshot did not round-trip")
        }
    }
    checkCase("back.selection-before-search") {
        val s = fixture(4, 1); val a = back(s)
        expect(a.state.selected.isEmpty() && a.state.query == "query" && !a.exitCategory)
        val b = back(a.state); expect(b.state.query == null && !b.exitCategory)
        expect(back(b.state).exitCategory)
    }
    checkCase("back.nested-sheet-before-dismiss") {
        val s = fixture(4, 3); val a = back(s)
        expect(a.state.overlay == Overlay.SHEET && a.state.overlayDepth == 0)
        expect(back(a.state).state.overlay == Overlay.NONE)
    }
    checkCase("back.dirty-sheet-cancel-preserves-origin") {
        val s = fixture(14, 3).copy(overlay = Overlay.SHEET, overlayDepth = 0)
        val a = back(s); expect(a.state.overlay == Overlay.DISCARD && a.state.pendingOverlay == Overlay.SHEET)
        val b = back(a.state); expect(b.state.overlay == Overlay.SHEET && b.state.draft == s.draft)
        val c = reduce(back(b.state).state, DemoAction.ConfirmDiscard)
        expect(c.draft == s.saved && c.overlay == Overlay.NONE)
    }
    checkCase("back.dirty-editor-protected") {
        val a = back(fixture(16, 3).copy(overlay = Overlay.EDIT)).state
        expect(a.overlay == Overlay.DISCARD)
        expect(back(a).state.overlay == Overlay.EDIT)
    }
    checkCase("back.non-library-root-restores-first-tab") {
        val a = back(fixture(2).copy(tab = 3)); expect(a.state.tab == 0 && !a.exitCategory)
    }
    checkCase("back.child-depth") {
        val a = back(fixture(4)); expect(a.state.depth == 0 && !a.exitCategory)
        expect(back(a.state).exitCategory)
    }
    checkCase("form.blank-blocked") {
        val s = reduce(fixture(16), DemoAction.Draft(" \n\t "))
        expect(!s.canCommitName && reduce(s, DemoAction.CommitName).commits == 0)
    }
    checkCase("form.duplicate-blocked") {
        for (name in listOf("Existing", "已有名称")) expect(reduce(fixture(16).copy(draft = name), DemoAction.CommitName).commits == 0)
    }
    checkCase("form.trim-and-submit-once") {
        val s = reduce(fixture(16).copy(draft = "  New  "), DemoAction.CommitName)
        expect(s.saved == "New" && s.commits == 1)
        expect(reduce(s, DemoAction.CommitName).commits == 1)
    }
    checkCase("form.rename-compares-original") {
        var s = fixture(16).copy(saved = "Original", draft = "Original")
        s = reduce(s, DemoAction.Draft("Other")); expect(s.canCommitName)
        s = reduce(s, DemoAction.Draft("Original")); expect(!s.canCommitName)
    }
    checkCase("form.cancel-no-write") {
        val s = reduce(fixture(16, 3), DemoAction.CancelEdit)
        expect(s.saved == "Original" && s.draft == "Original" && s.commits == 0)
    }
    checkCase("search.null-empty-distinction") {
        val a = reduce(fixture(11), DemoAction.Query("")); expect(a.query != null)
        expect(back(a).state.query == null)
        expect(reduce(a, DemoAction.SubmitSearch).lastEvent == "ready")
    }
    checkCase("search.clear-recovers-results") {
        val a = reduce(fixture(11, 3), DemoAction.Query(""))
        expect(a.phase == Phase.READY && a.query == "")
    }
    checkCase("selection.all-and-invert") {
        val s = reduce(fixture(9), DemoAction.SelectAll)
        expect(s.selected.size == 6 && reduce(s, DemoAction.InvertSelection).selected.isEmpty())
    }
    checkCase("selection.stable-after-sort") {
        val s = fixture(9).copy(selected = setOf(2, 5)); val a = reduce(s, DemoAction.Sort)
        expect(a.selected == s.selected && a.order == s.order.reversed())
    }
    checkCase("selection.range-on-displayed-order") {
        val s = reduce(fixture(9).copy(selected = setOf(2)), DemoAction.RangeSelect(5))
        expect(s.selected == setOf(2, 3, 4, 5))
    }
    checkCase("selection.unknown-id-ignored") { expect(reduce(fixture(9), DemoAction.Select(100)).selected.isEmpty()) }
    checkCase("selection.injected-ids-filtered") {
        expect(reduce(fixture(9), DemoAction.Selection(setOf(1, 100))).selected == setOf(1))
    }
    checkCase("selection.bulk-only-selected") {
        val s = fixture(27).copy(selected = setOf(2, 4)); expect(reduce(s, DemoAction.MarkRead).read == setOf(2, 4))
    }
    checkCase("ordering.clamped-and-stable") {
        val s = reduce(fixture(10), DemoAction.Move(1, 999))
        expect(s.order.last() == 1 && s.order.toSet() == (1..6).toSet())
        expect(reduce(s, DemoAction.Move(1, -999)).order.first() == 1)
    }
    checkCase("filter.three-states") {
        var s = fixture(12)
        for (v in listOf(1, 2, 0)) { s = reduce(s, DemoAction.CycleFilter); expect(s.filter == v) }
    }
    checkCase("filter.global-lock") { val s = fixture(12, 3); expect(reduce(s, DemoAction.CycleFilter) == s) }
    checkCase("destructive.no-scope-no-commit") {
        val s = fixture(17).copy(overlay = Overlay.CONFIRM)
        expect(reduce(s, DemoAction.ConfirmDelete) == s)
    }
    checkCase("destructive.cancel-no-side-effect") {
        val s = fixture(17, 1).copy(overlay = Overlay.CONFIRM)
        val a = back(s).state; expect(a.order == s.order && a.downloaded == s.downloaded && a.commits == 0)
    }
    checkCase("destructive.scope-and-double-confirm") {
        val s = fixture(17).copy(selected = setOf(1), removeDownloads = true, overlay = Overlay.CONFIRM)
        val a = reduce(s, DemoAction.ConfirmDelete)
        expect(a.order == s.order && a.downloaded == setOf(2) && a.commits == 1)
        expect(reduce(a, DemoAction.ConfirmDelete) == a)
    }
    checkCase("task.start-does-not-complete") {
        val s = reduce(fixture(19), DemoAction.Start); expect(s.phase == Phase.RUNNING && s.progress == 0 && s.starts == 1)
    }
    checkCase("task.double-start-deduplicated") {
        var s = fixture(19); repeat(100) { s = reduce(s, DemoAction.Start) }
        expect(s.starts == 1)
    }
    checkCase("task.pause-resume-keeps-progress-and-job") {
        val a = reduce(fixture(29, 1), DemoAction.Pause); expect(a.phase == Phase.PAUSED)
        val b = reduce(a, DemoAction.Start); expect(b.progress == 25 && b.starts == 1 && b.phase == Phase.RUNNING)
    }
    checkCase("task.completion-boundary") {
        var s = reduce(fixture(19), DemoAction.Start); repeat(8) { s = reduce(s, DemoAction.Tick) }
        expect(s.progress == 100 && s.phase == Phase.DONE)
        s = reduce(s, DemoAction.Start); expect(s.progress == 0 && s.phase == Phase.RUNNING)
    }
    checkCase("task.error-preserves-content-and-draft") {
        val a = fixture(19).copy(draft = "Keep"); val b = reduce(a, DemoAction.Fail)
        expect(b.order == a.order && b.draft == a.draft && b.phase == Phase.ERROR)
    }
    checkCase("task.empty-queue-blocked") { val s = fixture(29, 3); expect(reduce(s, DemoAction.Start) == s) }
    checkCase("reader.boundaries") {
        expect(reduce(fixture(28), DemoAction.Page(-1)).page == 1)
        expect(reduce(fixture(28), DemoAction.Page(999)).page == 10)
    }
    checkCase("reader.bar-toggle-keeps-page") {
        val s = fixture(28, 3); val a = reduce(s, DemoAction.ToggleBars)
        expect(!a.barsVisible && a.page == 5)
    }
    checkCase("continue.hidden-when-all-read") { expect(!fixture(27, 1).canContinue) }
    checkCase("continue.hidden-in-selection") { expect(!fixture(27, 2).canContinue) }
    checkCase("continue.blocked-with-missing-source") { expect(!fixture(27, 3).canContinue) }
    checkCase("privacy.fixture-history-not-written") {
        var s = fixture(21, 2); repeat(3) { s = reduce(s, DemoAction.FakeRead) }; expect(s.historyCount == 0)
        expect(reduce(fixture(21), DemoAction.FakeRead).historyCount == 1)
    }
    checkCase("extension.permission-required") {
        val s = reduce(fixture(30, 3), DemoAction.Start); expect(s.starts == 0 && s.lastEvent == "blocked-extension")
    }
    checkCase("extension.explicit-trust-required") {
        val s = fixture(30, 2)
        expect(!reduce(s, DemoAction.Trust).trusted)
        val open = reduce(s, DemoAction.Modal(Overlay.TRUST))
        expect(!back(open).state.trusted)
        expect(reduce(open, DemoAction.Trust).trusted)
    }
    checkCase("tracking.capability-gates-score") {
        val s = fixture(31, 2); expect(reduce(s, DemoAction.Score(9)).score == 0)
    }
    checkCase("tracking.unbind-requires-confirm") {
        val s = fixture(31, 1)
        expect(reduce(s, DemoAction.Unbind).bound)
        expect(!reduce(s.copy(overlay = Overlay.CONFIRM), DemoAction.Unbind).bound)
    }
    checkCase("storage.no-directory-no-backup") { expect(reduce(fixture(32), DemoAction.Start).starts == 0) }
    checkCase("storage.revoked-permission-blocks") { expect(reduce(fixture(32, 2), DemoAction.Start).starts == 0) }
    checkCase("storage.incompatible-file-blocks") { expect(reduce(fixture(32, 3), DemoAction.Start).starts == 0) }
    checkCase("storage.picker-cancel-preserves-directory") {
        val s = fixture(32).copy(overlay = Overlay.PICKER); expect(!back(s).state.directorySelected)
    }
    checkCase("snapshot.unicode-and-null-round-trip") {
        val s = fixture(6).copy(draft = "中文\n\t;,:\"🙂", saved = "", query = "")
        expect(restoreSnapshot(s.snapshot()) == s)
    }
    checkCase("snapshot.malformed-rejected") {
        expect(restoreSnapshot(listOf("garbage")) == null)
        val s = fixture(6).snapshot().toMutableList(); s[1] = "99"; expect(restoreSnapshot(s) == null)
    }
    checkCase("snapshot.invalid-geometry-rejected") {
        val s = fixture(28).snapshot().toMutableList(); s[16] = "0"; expect(restoreSnapshot(s) == null)
    }
    checkCase("snapshot.stale-selection-rejected") {
        val s = fixture(9).snapshot().toMutableList(); s[6] = "999"; expect(restoreSnapshot(s) == null)
    }
    checkCase("paging.retry-deduplicates-appended-fixtures") {
        var s = reduce(fixture(19, 2), DemoAction.Retry)
        repeat(4) { s = reduce(s, DemoAction.Tick) }
        expect(s.order == (1..9).toList())
        s = reduce(reduce(s, DemoAction.Fail), DemoAction.Retry)
        repeat(4) { s = reduce(s, DemoAction.Tick) }
        expect(s.order.size == 9 && s.order.distinct().size == 9)
    }
    checkCase("filter.reset-respects-global-lock") {
        expect(reduce(fixture(12, 1), DemoAction.ResetFilter).filter == 0)
        expect(reduce(fixture(12, 3), DemoAction.ResetFilter).filter == 1)
    }
    checkCase("animated-items.keep-stable-unique-identities") {
        var s = fixture(24); s = reduce(s, DemoAction.AddFixture)
        expect(s.order.first() == 31 && s.order.distinct().size == 31)
        s = reduce(s, DemoAction.RemoveFixture); expect(s.order == (1..30).toList())
    }
    println("TOTAL\t$passed\t$failed")
    check(failed == 0) { "$failed fixture checks failed" }
}
