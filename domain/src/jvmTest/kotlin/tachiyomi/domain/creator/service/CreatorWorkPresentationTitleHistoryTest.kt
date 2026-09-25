package tachiyomi.domain.creator.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.LanguageProjectionContract
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkPresentationGroup
import tachiyomi.domain.library.service.LibraryPreferences
import java.util.UUID
import java.util.prefs.Preferences

class CreatorWorkPresentationTitleHistoryTest {
    @Test
    fun `preference read and write failures keep presentation usable in memory`() {
        val delegate = InMemoryPreferenceStore()
        val failingStore = object : PreferenceStore by delegate {
            override fun getString(key: String, defaultValue: String): Preference<String> =
                object : Preference<String> by delegate.getString(key, defaultValue) {
                    override fun get(): String = error("read failed")
                    override fun set(value: String): Unit = error("write failed")
                }
        }
        val history = CreatorWorkPresentationTitleHistory(
            LibraryPreferences(failingStore),
            { 7L },
            WorkTitleNormalizer.DisplayScript.TRADITIONAL,
        )

        assertEquals(null, history.get("title:诡谲屋"))
        history.remember(listOf(group("title:诡谲屋", "詭譎屋")))
        assertEquals("詭譎屋", history.get("title:诡谲屋"))
    }

    @Test
    fun `retains title across model reconstruction within same script`() {
        val node = Preferences.userRoot().node("mihon/title-history/${UUID.randomUUID()}")
        try {
            val preferences = LibraryPreferences(DesktopPreferenceStore(node))
            val first = CreatorWorkPresentationTitleHistory(
                preferences,
                { 7L },
                WorkTitleNormalizer.DisplayScript.TRADITIONAL,
            )
            first.remember(listOf(group("title:诡谲屋", "詭譎屋")))

            val reopened = CreatorWorkPresentationTitleHistory(
                LibraryPreferences(DesktopPreferenceStore(node)),
                { 7L },
                WorkTitleNormalizer.DisplayScript.TRADITIONAL,
            )
            assertEquals("詭譎屋", reopened.get("title:诡谲屋"))
            assertEquals(
                null,
                CreatorWorkPresentationTitleHistory(
                    preferences,
                    { 7L },
                    WorkTitleNormalizer.DisplayScript.SIMPLIFIED,
                ).get("title:诡谲屋"),
            )
            assertEquals(
                null,
                CreatorWorkPresentationTitleHistory(
                    preferences,
                    { 8L },
                    WorkTitleNormalizer.DisplayScript.TRADITIONAL,
                ).get("title:诡谲屋"),
            )
        } finally {
            node.removeNode()
        }
    }

    private fun group(key: String, title: String): WorkPresentationGroup {
        val member = SourceWorkArchiveVersion(
            sourceWorkId = 1L,
            naturalKey = SourceWorkNaturalKey(1L, "/work"),
            mangaId = null,
            title = title,
            readingLanguage = LanguageProjectionContract(
                LanguageDimension.READING,
                "zh-Hant",
                LanguageCertainty.CONFIRMED,
                LanguageEvidenceKind.MANUAL,
            ),
            chapterCount = 0L,
            inLibrary = false,
            detailsFetchedAt = null,
            lastSeenAt = 0L,
            decision = null,
        )
        return WorkPresentationGroup(key, title, listOf(member), null, member)
    }
}
