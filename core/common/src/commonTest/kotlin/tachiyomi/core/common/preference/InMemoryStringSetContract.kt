package tachiyomi.core.common.preference

import kotlinx.coroutines.flow.first

object InMemoryStringSetContract {
    suspend fun verify() {
        val missing = InMemoryPreferenceStore().getStringSet("sources", setOf("default"))
        check(missing.key() == "sources")
        check(missing.defaultValue() == setOf("default"))
        check(missing.get() == setOf("default") && !missing.isSet())
        check(missing.changes().first() == setOf("default"))
        missing.set(setOf("one", "two"))
        check(missing.get() == setOf("one", "two") && missing.isSet())
        check(missing.changes().first() == setOf("one", "two"))
        missing.set(emptySet())
        check(missing.get().isEmpty() && missing.isSet())
        missing.delete()
        check(missing.get() == setOf("default") && !missing.isSet())
        check(missing.changes().first() == setOf("default"))

        val seed = InMemoryPreferenceStore.InMemoryPreference("sources", setOf("seed"), emptySet<String>())
        val seeded = InMemoryPreferenceStore(sequenceOf(seed)).getStringSet("sources", setOf("fallback"))
        check(seeded.get() == setOf("seed") && seeded.isSet())
        seeded.delete()
        check(seeded.get() == setOf("fallback") && !seeded.isSet())
        check(seed.get() == setOf("seed")) // Preserve the documented local-copy behavior.

        for (invalid in listOf<Any>("text", setOf(1), setOf("valid", 1))) {
            val store = InMemoryPreferenceStore(
                sequenceOf(InMemoryPreferenceStore.InMemoryPreference("sources", invalid, invalid)),
            )
            val value = store.getStringSet("sources", setOf("fallback"))
            check(value.get() == setOf("fallback") && !value.isSet())
        }
    }
}
