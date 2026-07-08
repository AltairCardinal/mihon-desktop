---
change: sync-desktop-missing-chapter-indicators
design-doc: docs/superpowers/specs/2026-07-06-sync-desktop-missing-chapter-indicators-design.md
base-ref: c84ed331fa0b7851b62dc44a66a8602bb3f60876
archived-with: 2026-07-06-sync-desktop-missing-chapter-indicators
---

# Sync Desktop Missing Chapter Indicators Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show original Mihon-style missing chapter indicators in Mihon Desktop manga detail pages, with a matching global setting to hide them.

**Architecture:** Keep chapter actions backed by `List<Chapter>` and add a separate desktop display-row model for chapter rows plus missing-count rows. Reuse `tachiyomi.domain.chapter.service.calculateChapterGap` and wire a `DesktopAppPreferences` boolean into Library settings and `MangaDetailScreen`.

**Tech Stack:** Kotlin/JVM, Compose Desktop, Voyager, `tachiyomi.core.common.preference.PreferenceStore`, JUnit 5, Gradle.

## Global Constraints

- 所有面向用户的交流和完成报告使用中文。
- 必须遵循 TDD：先写失败测试并确认失败，再写最小实现，再重构并重跑测试。
- 不改动与任务无关的 reader 文件；当前工作区已有旧 reader 改动，必须保留并避免混入本 change。
- 不新增数据库 schema、HTTP/API、source refresh、下载、迁移、reader 行为变更。
- 不自动提交，除非用户明确要求。
- 桌面端最终验证必须运行 `./scripts/build-desktop.sh`，并报告 `app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt` 中的部署版本。

archived-with: 2026-07-06-sync-desktop-missing-chapter-indicators
---

### Task 1: Missing Chapter Row Model

**Files:**
- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailChapterRows.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaDetailChapterRowsTest.kt`
- Read-only reference: `domain/src/commonMain/kotlin/tachiyomi/domain/chapter/service/MissingChapters.kt`

**Interfaces:**
- Produces: `sealed interface MangaDetailChapterListRow`
- Produces: `fun mangaDetailChapterRows(chapters: List<Chapter>, ascending: Boolean, hideMissingChapters: Boolean): List<MangaDetailChapterListRow>`
- Produces: `fun realChapterIds(rows: List<MangaDetailChapterListRow>): List<Long>`
- Consumes: already filtered/sorted `List<Chapter>` from `MangaDetailScreen`

- [x] **Step 1: Write failing tests**

Create `app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaDetailChapterRowsTest.kt`:

```kotlin
package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class MangaDetailChapterRowsTest {

    @Test
    fun `inserts missing row between visible chapter gaps`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(1L, 1.0), chapter(2L, 2.0), chapter(5L, 5.0)),
            ascending = true,
            hideMissingChapters = false,
        )

        assertEquals(
            listOf("chapter:1", "chapter:2", "missing:2", "chapter:5"),
            rows.labels(),
        )
    }

    @Test
    fun `inserts leading missing row before first recognized chapter`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(3L, 3.0), chapter(4L, 4.0)),
            ascending = true,
            hideMissingChapters = false,
        )

        assertEquals(listOf("missing:2", "chapter:3", "chapter:4"), rows.labels())
    }

    @Test
    fun `does not create indicators for unknown or negative chapters`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(10L, -1.0), chapter(11L, -2.0)),
            ascending = true,
            hideMissingChapters = false,
        )

        assertEquals(listOf("chapter:10", "chapter:11"), rows.labels())
    }

    @Test
    fun `descending order places missing row between adjacent displayed chapters`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(5L, 5.0), chapter(2L, 2.0), chapter(1L, 1.0)),
            ascending = false,
            hideMissingChapters = false,
        )

        assertEquals(
            listOf("chapter:5", "missing:2", "chapter:2", "chapter:1"),
            rows.labels(),
        )
    }

    @Test
    fun `hide setting returns only chapter rows`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(1L, 1.0), chapter(5L, 5.0)),
            ascending = true,
            hideMissingChapters = true,
        )

        assertEquals(listOf("chapter:1", "chapter:5"), rows.labels())
    }

    @Test
    fun `realChapterIds excludes missing rows`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(1L, 1.0), chapter(5L, 5.0)),
            ascending = true,
            hideMissingChapters = false,
        )

        assertEquals(listOf(1L, 5L), realChapterIds(rows))
        assertTrue(rows.any { it is MangaDetailChapterListRow.MissingCountRow })
    }

    private fun chapter(id: Long, chapterNumber: Double): Chapter =
        Chapter.create().copy(
            id = id,
            name = "Chapter $chapterNumber",
            chapterNumber = chapterNumber,
        )

    private fun List<MangaDetailChapterListRow>.labels(): List<String> =
        map { row ->
            when (row) {
                is MangaDetailChapterListRow.ChapterRow -> "chapter:${row.chapter.id}"
                is MangaDetailChapterListRow.MissingCountRow -> "missing:${row.count}"
            }
        }
}
```

- [x] **Step 2: Verify RED**

Run:

```bash
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.library.MangaDetailChapterRowsTest"
```

Expected: FAIL because `MangaDetailChapterListRow`, `mangaDetailChapterRows`, and `realChapterIds` do not exist.

- [x] **Step 3: Implement minimal row model**

Create `app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailChapterRows.kt`:

```kotlin
package mihon.desktop.ui.library

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.calculateChapterGap
import kotlin.math.floor

sealed interface MangaDetailChapterListRow {
    data class ChapterRow(val chapter: Chapter) : MangaDetailChapterListRow
    data class MissingCountRow(val id: String, val count: Int) : MangaDetailChapterListRow
}

fun mangaDetailChapterRows(
    chapters: List<Chapter>,
    ascending: Boolean,
    hideMissingChapters: Boolean,
): List<MangaDetailChapterListRow> {
    if (hideMissingChapters || chapters.isEmpty()) {
        return chapters.map(MangaDetailChapterListRow::ChapterRow)
    }

    return buildList {
        chapters.forEachIndexed { index, chapter ->
            val previous = chapters.getOrNull(index - 1)
            val missingCount = missingCountBefore(
                previous = previous,
                current = chapter,
                ascending = ascending,
            )
            if (missingCount > 0) {
                add(
                    MangaDetailChapterListRow.MissingCountRow(
                        id = "missing-${previous?.id ?: "start"}-${chapter.id}",
                        count = missingCount,
                    ),
                )
            }
            add(MangaDetailChapterListRow.ChapterRow(chapter))
        }
    }
}

fun realChapterIds(rows: List<MangaDetailChapterListRow>): List<Long> =
    rows.mapNotNull { row -> (row as? MangaDetailChapterListRow.ChapterRow)?.chapter?.id }

private fun missingCountBefore(previous: Chapter?, current: Chapter, ascending: Boolean): Int {
    if (!current.isRecognizedNumber) return 0
    if (previous == null) {
        return floor(current.chapterNumber).toInt().minus(1).coerceAtLeast(0)
    }

    val lower = if (ascending) previous else current
    val higher = if (ascending) current else previous
    return calculateChapterGap(higher, lower).coerceAtLeast(0)
}
```

- [x] **Step 4: Verify GREEN**

Run:

```bash
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.library.MangaDetailChapterRowsTest"
```

Expected: PASS.

- [x] **Step 5: Refactor check**

Run:

```bash
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.library.MangaChapterSortTest" --tests "mihon.desktop.ui.library.MangaDetailChapterRowsTest"
```

Expected: PASS. Do not change behavior during refactor.

### Task 2: Hide Missing Indicators Preference

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/settings/DesktopAppPreferences.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/settings/DesktopAppPreferencesTest.kt`

**Interfaces:**
- Produces: `DesktopAppPreferences.hideMissingChapterIndicators: Preference<Boolean>`
- Consumes: `PreferenceStore.getBoolean("pref_hide_missing_chapter_indicators", false)`

- [x] **Step 1: Write failing preference tests**

Append to `DesktopAppPreferencesTest`:

```kotlin
    @Test
    fun `hide missing chapter indicators defaults to false`() {
        assertFalse(prefs().hideMissingChapterIndicators.get())
    }

    @Test
    fun `hide missing chapter indicators round-trips true`() {
        val p = prefs()
        p.hideMissingChapterIndicators.set(true)
        assertEquals(true, p.hideMissingChapterIndicators.get())
    }
```

- [x] **Step 2: Verify RED**

Run:

```bash
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.settings.DesktopAppPreferencesTest"
```

Expected: FAIL because `hideMissingChapterIndicators` does not exist.

- [x] **Step 3: Implement preference**

Add to `DesktopAppPreferences` near library preferences:

```kotlin
    /** When true, manga detail lists hide missing chapter indicator rows. */
    val hideMissingChapterIndicators: Preference<Boolean> by lazy {
        store.getBoolean(key = "pref_hide_missing_chapter_indicators", defaultValue = false)
    }
```

- [x] **Step 4: Verify GREEN**

Run:

```bash
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.settings.DesktopAppPreferencesTest"
```

Expected: PASS.

### Task 3: Library Settings UI Wiring

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/LibrarySettingsScreen.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/settings/DesktopAppPreferencesTest.kt`

**Interfaces:**
- Consumes: `DesktopAppPreferences.hideMissingChapterIndicators`
- Produces: user-visible Library settings checkbox text `Hide missing chapter indicators`

- [x] **Step 1: Add a failing contract test for the setting label**

Append to `DesktopAppPreferencesTest`:

```kotlin
    @Test
    fun `hide missing chapter indicators uses original Mihon preference key`() {
        val store = InMemoryPreferenceStore()
        val p = DesktopAppPreferences(store)

        p.hideMissingChapterIndicators.set(true)

        assertEquals(true, store.getBoolean("pref_hide_missing_chapter_indicators", false).get())
    }
```

Expected failure before Task 2 implementation if run earlier; after Task 2 it should pass and locks the original key.

- [x] **Step 2: Add settings checkbox**

In `LibrarySettingsScreen`, collect the preference:

```kotlin
        val hideMissingChapterIndicators by prefs.hideMissingChapterIndicators.changes().collectAsState(
            initial = prefs.hideMissingChapterIndicators.get(),
        )
```

Add a Display section after the Update Behavior text and before category exclusions:

```kotlin
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "Display",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            prefs.hideMissingChapterIndicators.set(!hideMissingChapterIndicators)
                        }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = hideMissingChapterIndicators,
                        onCheckedChange = { checked ->
                            prefs.hideMissingChapterIndicators.set(checked)
                        },
                    )
                    Text("Hide missing chapter indicators", modifier = Modifier.padding(start = 8.dp))
                }
```

- [x] **Step 3: Verify wiring tests**

Run:

```bash
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.settings.DesktopAppPreferencesTest"
```

Expected: PASS.

### Task 4: Manga Detail Screen Integration

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailScreen.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaDetailChapterRowsTest.kt`

**Interfaces:**
- Consumes: `mangaDetailChapterRows(displayedChapters, chapterSortAscending, hideMissingChapterIndicators)`
- Consumes: `MangaDetailChapterListRow`
- Keeps: chapter count header based on real `displayedChapters.size`

- [x] **Step 1: Add failing action-scope test**

Append to `MangaDetailChapterRowsTest`:

```kotlin
    @Test
    fun `real chapter ids preserve displayed chapter order`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(5L, 5.0), chapter(2L, 2.0), chapter(1L, 1.0)),
            ascending = false,
            hideMissingChapters = false,
        )

        assertEquals(listOf(5L, 2L, 1L), realChapterIds(rows))
    }
```

Run:

```bash
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.library.MangaDetailChapterRowsTest"
```

Expected: PASS after Task 1. This test protects action scoping before the UI starts consuming mixed rows.

- [x] **Step 2: Observe preference in manga detail screen**

In `MangaDetailScreen.Content`, after `val downloadQueue by model.downloadQueueFlow().collectAsState()` add:

```kotlin
        val hideMissingChapterIndicators by LocalDesktopUiDependencies.current.appPreferences
            .hideMissingChapterIndicators
            .changes()
            .collectAsState(
                initial = LocalDesktopUiDependencies.current.appPreferences.hideMissingChapterIndicators.get(),
            )
```

- [x] **Step 3: Build mixed display rows**

After `displayedChapters` is computed, add:

```kotlin
        val chapterRows = remember(displayedChapters, chapterSortAscending, hideMissingChapterIndicators) {
            mangaDetailChapterRows(
                chapters = displayedChapters,
                ascending = chapterSortAscending,
                hideMissingChapters = hideMissingChapterIndicators,
            )
        }
```

- [x] **Step 4: Render mixed rows**

Replace `items(displayedChapters, key = { it.id }) { chapter -> ... }` with:

```kotlin
                items(
                    chapterRows,
                    key = { row ->
                        when (row) {
                            is MangaDetailChapterListRow.ChapterRow -> "chapter-${row.chapter.id}"
                            is MangaDetailChapterListRow.MissingCountRow -> row.id
                        }
                    },
                ) { row ->
                    when (row) {
                        is MangaDetailChapterListRow.MissingCountRow -> MissingChapterCountRow(row.count)
                        is MangaDetailChapterListRow.ChapterRow -> {
                            val chapter = row.chapter
                            // Keep the existing ChapterRow body unchanged inside this branch.
                        }
                    }
                }
```

Then move the current chapter-row body unchanged into the `ChapterRow` branch.

Add a local composable near other detail components or in `MangaDetailComponents.kt`:

```kotlin
@Composable
private fun MissingChapterCountRow(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = if (count == 1) "Missing 1 chapter" else "Missing $count chapters",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}
```

- [x] **Step 5: Verify targeted UI-adjacent tests**

Run:

```bash
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.library.MangaDetailChapterRowsTest" --tests "mihon.desktop.ui.library.MangaDetailScreenModelTest" --tests "mihon.desktop.ui.library.MangaDetailActionsTest"
```

Expected: PASS.

### Task 5: OpenSpec Task Sync and Final Verification

**Files:**
- Modify: `openspec/changes/sync-desktop-missing-chapter-indicators/tasks.md`
- Read: `app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt`

**Interfaces:**
- Produces: verification evidence for Comet verify phase.

- [x] **Step 1: Mark implementation tasks complete**

After each implementation task passes its tests, update `openspec/changes/sync-desktop-missing-chapter-indicators/tasks.md` by changing the corresponding `- [ ]` to `- [x]`.

- [x] **Step 2: Run targeted tests**

Run:

```bash
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.library.MangaDetailChapterRowsTest" --tests "mihon.desktop.settings.DesktopAppPreferencesTest" --tests "mihon.desktop.ui.library.MangaDetailScreenModelTest" --tests "mihon.desktop.ui.library.MangaDetailActionsTest"
```

Expected: PASS.

- [x] **Step 3: Run format check**

Run:

```bash
./gradlew spotlessCheck
```

Expected: PASS.

- [x] **Step 4: Run desktop build/deploy script**

Run:

```bash
./scripts/build-desktop.sh
```

Expected: PASS and deploys Mihon Desktop to `/Applications/Mihon Desktop.app`.

- [x] **Step 5: Record deployed version**

Run:

```bash
sed -n '1,80p' app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt
```

Report the exact Mihon Desktop version from that file in the completion report.
