package mihon.desktop.ui.browse

import mihon.desktop.source.FakeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DesktopSourceListProjectorTest {
    @Test
    fun `last used is a first-group copy while pinned and language originals keep fixed-main order`() {
        val alpha = FakeSource(1, "en", "alpha")
        val lastUsed = FakeSource(2, "fr", "Zulu")
        val pinned = FakeSource(3, "en", "Pinned")
        val languageUnknown = FakeSource(4, "", "Unknown")
        val bravo = FakeSource(5, "en", "bravo")
        val groups = DesktopSourceListProjector.project(
            sources = listOf(lastUsed, languageUnknown, pinned, bravo, alpha),
            pinnedSourceIds = setOf(pinned.id.toString()),
            lastUsedSourceId = lastUsed.id,
        )
        assertEquals(
            listOf(
                DesktopSourceGroupKey.LastUsed,
                DesktopSourceGroupKey.Pinned,
                DesktopSourceGroupKey.Language("en"),
                DesktopSourceGroupKey.Language("fr"),
                DesktopSourceGroupKey.Language(""),
            ),
            groups.map { it.key },
        )
        assertEquals(listOf(lastUsed.id), groups[0].items.map { it.source.id })
        assertEquals(listOf(pinned.id), groups[1].items.map { it.source.id })
        assertEquals(listOf(alpha.id, bravo.id), groups[2].items.map { it.source.id })
        assertEquals(listOf(lastUsed.id), groups[3].items.map { it.source.id })
        assertEquals(2, groups.flatMap { it.items }.count { it.source.id == lastUsed.id })
        assertEquals(listOf(true, false), groups.flatMap { it.items }.filter { it.source.id == lastUsed.id }.map { it.isUsedLast })
    }

    @Test
    fun `pinned last used keeps originals in both fixed-main priority groups`() {
        val source = FakeSource(11, "en", "Pinned last used")
        val groups = DesktopSourceListProjector.project(
            sources = listOf(source),
            pinnedSourceIds = setOf(source.id.toString()),
            lastUsedSourceId = source.id,
        )

        assertEquals(listOf(DesktopSourceGroupKey.LastUsed, DesktopSourceGroupKey.Pinned), groups.map { it.key })
        assertEquals(listOf(source.id, source.id), groups.flatMap { it.items }.map { it.source.id })
    }
}
