package mihon.desktop.ui.browse

import mihon.desktop.source.FakeSource
import mihon.domain.source.model.SourceScreenContent
import mihon.domain.source.model.SourceScreenState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.Pins
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import java.util.Locale

class DesktopSourceListProjectorTest {
    @Test
    fun `last used is a first-group copy while pinned and language originals keep fixed-main order`() {
        val alpha = FakeSource(1, "en", "alpha")
        val lastUsed = FakeSource(2, "fr", "Zulu")
        val pinned = FakeSource(3, "en", "Pinned")
        val languageUnknown = FakeSource(4, "", "Unknown")
        val bravo = FakeSource(5, "en", "Bravo")
        val groups = DesktopSourceListProjector.project(
            sourceState = sourceState(
                alpha.domain(),
                lastUsed.domain(),
                lastUsed.domain(isUsedLast = true),
                pinned.domain(pin = Pins.pinned),
                languageUnknown.domain(),
                bravo.domain(),
            ),
            catalogueSources = listOf(lastUsed, languageUnknown, pinned, bravo, alpha),
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
            sourceState = sourceState(
                source.domain(pin = Pins.pinned),
                source.domain(isUsedLast = true),
            ),
            catalogueSources = listOf(source),
        )

        assertEquals(listOf(DesktopSourceGroupKey.LastUsed, DesktopSourceGroupKey.Pinned), groups.map { it.key })
        assertEquals(listOf(source.id, source.id), groups.flatMap { it.items }.map { it.source.id })
    }

    @Test
    fun `source group labels follow fixed-main localized language names`() {
        val defaultLocale = Locale.ENGLISH

        assertEquals(
            MR.strings.last_used_source.localized(defaultLocale),
            DesktopSourceGroupLabeler.displayName(DesktopSourceGroupKey.LastUsed, defaultLocale),
        )
        assertEquals(
            MR.strings.pinned_sources.localized(defaultLocale),
            DesktopSourceGroupLabeler.displayName(DesktopSourceGroupKey.Pinned, defaultLocale),
        )
        assertEquals(
            MR.strings.other_source.localized(defaultLocale),
            DesktopSourceGroupLabeler.displayName(DesktopSourceGroupKey.Language("other"), defaultLocale),
        )
        assertEquals(
            MR.strings.multi_lang.localized(defaultLocale),
            DesktopSourceGroupLabeler.displayName(DesktopSourceGroupKey.Language("all"), defaultLocale),
        )
        assertEquals(
            Locale.forLanguageTag("zh-Hans").selfDisplayName(),
            DesktopSourceGroupLabeler.displayName(DesktopSourceGroupKey.Language("zh-CN"), defaultLocale),
        )
        assertEquals(
            Locale.forLanguageTag("zh-Hant").selfDisplayName(),
            DesktopSourceGroupLabeler.displayName(DesktopSourceGroupKey.Language("zh-TW"), defaultLocale),
        )
        assertEquals(
            Locale.FRENCH.selfDisplayName(),
            DesktopSourceGroupLabeler.displayName(DesktopSourceGroupKey.Language("fr"), defaultLocale),
        )
        val defaultLanguage = DesktopSourceGroupLabeler.displayName(DesktopSourceGroupKey.Language(""), defaultLocale)
        assertEquals(defaultLocale.selfDisplayName(), defaultLanguage)
        assertFalse(defaultLanguage.isBlank())
    }

    private fun sourceState(vararg sources: Source) =
        SourceScreenState(content = SourceScreenContent.Content(sources.toList()))

    private fun FakeSource.domain(pin: Pins = Pins.unpinned, isUsedLast: Boolean = false) =
        Source(id, lang, name, supportsLatest, isStub = false, pin = pin, isUsedLast = isUsedLast)

    private fun Locale.selfDisplayName() = getDisplayName(this).replaceFirstChar { it.uppercase(this) }
}
