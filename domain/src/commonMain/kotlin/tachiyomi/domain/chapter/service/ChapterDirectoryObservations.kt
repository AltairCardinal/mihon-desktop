package tachiyomi.domain.chapter.service

import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.SourceDateField
import tachiyomi.domain.creator.model.SourceDateObservation
import tachiyomi.domain.creator.model.SourceDatePrecision
import tachiyomi.domain.creator.model.SourceDateQualityIdentity
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.repository.CreatorArchiveRepository

/** Retries retain the response's original clock and extension identity, rather than creating a new observation. */
suspend fun CreatorArchiveRepository.observeDirectoryPhase(phase: ChapterDirectoryPhase) {
    val original = phase.effects
    recordSourceDateQualityObservations(
        original.dates.map { chapter ->
            val value = chapter.date.takeIf { it > 0 }
            SourceDateObservation(
                identity = SourceDateQualityIdentity(
                    original.extensionPackage,
                    original.extensionVersion,
                    original.sourceId,
                    SourceDateField.CHAPTER_UPDATED,
                ),
                workNaturalKey = original.workNaturalKey,
                chapterNaturalKey = chapter.url,
                rawValue = value?.toString(),
                valueAt = value,
                precision = value?.let { SourceDatePrecision.DAY } ?: SourceDatePrecision.UNKNOWN,
                observedAt = original.observedAt,
                reason = value?.let { null } ?: "missing-date",
            )
        },
        now = original.observedAt,
    )
    updateSourceWorkCatalog(
        SourceWorkNaturalKey(original.sourceId, original.workNaturalKey),
        original.dates.size.toLong(),
        ChapterCatalogCompleteness.COMPLETE,
        original.dates.map { it.date }.filter { it > 0 }.maxOrNull(),
        original.observedAt,
        phase.mangaId,
    )
}
