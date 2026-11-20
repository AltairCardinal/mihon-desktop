package tachiyomi.domain.manga.service

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.model.MangaDetailAction
import tachiyomi.domain.manga.model.MangaDetailLayout

class MangaDetailPreferences(private val preferenceStore: PreferenceStore) {
    // One preference write commits membership and order together; legacy comma lists remain readable.
    fun layout() = preferenceStore.getObjectFromString(
        key = "manga_detail_primary_actions",
        defaultValue = MangaDetailLayout(),
        serializer = { layout ->
            MangaDetailAction.normalize(layout.primary).joinToString(",") { it.name } + "|" +
                (layout.order + MangaDetailAction.entries).distinct().joinToString(",") { it.name }
        },
        deserializer = { value ->
            MangaDetailLayout(
                primary = MangaDetailAction.normalize(parse(value.substringBefore('|'))),
                order = (parse(value.substringAfter('|', "")) + MangaDetailAction.entries).distinct(),
            )
        },
    )

    private fun parse(value: String) = value.split(',').mapNotNull { name ->
        MangaDetailAction.entries.firstOrNull { it.name == name }
    }

    fun primaryActions() = preferenceStore.getObjectFromString(
        key = "manga_detail_primary_actions",
        defaultValue = MangaDetailAction.defaults,
        serializer = { MangaDetailAction.normalize(it).joinToString(",") { action -> action.name } },
        deserializer = { value ->
            MangaDetailAction.normalize(
                parse(value.substringBefore('|')),
            )
        },
    )
}
