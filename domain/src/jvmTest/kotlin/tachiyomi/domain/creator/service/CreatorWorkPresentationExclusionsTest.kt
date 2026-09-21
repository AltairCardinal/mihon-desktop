package tachiyomi.domain.creator.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.library.service.LibraryPreferences
import java.util.UUID
import java.util.prefs.Preferences

class CreatorWorkPresentationExclusionsTest {
    @Test
    fun `exclusions are scoped by active creator root and support remove and recreation`() {
        val node = Preferences.userRoot().node("mihon/ax08-exclusions/${UUID.randomUUID()}")
        try {
            val preferences = LibraryPreferences(DesktopPreferenceStore(node))
            val first = CreatorWorkPresentationExclusions(preferences.creatorWorkPresentationExclusions())
            val key = SourceWorkNaturalKey(42L, "https://example.test/work?id=1")

            first.exclude(7L, key)

            CreatorWorkPresentationExclusions(preferences.creatorWorkPresentationExclusions())
                .get(7L) shouldContainExactly setOf(key)
            first.get(8L) shouldBe emptySet()
            first.remove(mapOf(7L to setOf(SourceWorkNaturalKey(99L, "/not-imported"))))
            first.get(7L) shouldContainExactly setOf(key)
            first.remove(mapOf(7L to setOf(key)))
            first.get(7L) shouldBe emptySet()
            first.restore(7L, key)
            first.get(7L) shouldBe emptySet()
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun `malformed entries are ignored and a failed write keeps the old set`() {
        val node = Preferences.userRoot().node("mihon/ax08-exclusions/${UUID.randomUUID()}")
        try {
            val preference = DesktopPreferenceStore(node).getStringSet(Preference.appStateKey("ax08-test"))
            val exclusions = CreatorWorkPresentationExclusions(preference)
            val existing = SourceWorkNaturalKey(1L, "/existing")
            exclusions.exclude(7L, existing)

            val failing = CreatorWorkPresentationExclusions(
                FailingPreference(
                    value = preference.get(),
                    delegate = preference,
                ),
            )
            runCatching { failing.exclude(7L, SourceWorkNaturalKey(2L, "/new")) }
                .isFailure shouldBe true
            failing.get(7L) shouldContainExactly setOf(existing)
            preference.set(preference.get() + "not-a-valid-entry")
            exclusions.get(7L) shouldContainExactly setOf(existing)
        } finally {
            node.removeNode()
        }
    }

    private class FailingPreference(
        private var value: Set<String>,
        private val delegate: Preference<Set<String>>,
    ) : Preference<Set<String>> by delegate {
        override fun get(): Set<String> = value
        override fun set(value: Set<String>) {
            throw IllegalStateException("disk unavailable")
        }
    }
}
