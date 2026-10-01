package tachiyomi.domain.creator.model

/** Device-local source catalogue evidence; not a freshness promise or a sync fact. */
data class SourceWorkCatalogObservation(
    val sourceWork: SourceWorkNaturalKey,
    val mangaId: Long?,
    val chapterCount: Long,
    val completeness: ChapterCatalogCompleteness,
)
