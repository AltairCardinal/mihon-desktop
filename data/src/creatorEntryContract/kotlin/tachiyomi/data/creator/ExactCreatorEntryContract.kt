package tachiyomi.data.creator

import tachiyomi.data.DatabaseHandler
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.model.CreatorMentionResolution
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.manga.model.Manga

/** Same real SQL behavior contract; each platform supplies its production entry and database handler. */
suspend fun verifyExactCreatorEntry(
    handler: DatabaseHandler,
    open: suspend (ManageCreatorIdentity, Manga, CreatorMention) -> CreatorMentionResolution,
) {
    var failOnce = true
    val repository = CreatorRepositoryImpl(handler, identityMutationHook = {
        if (failOnce) {
            failOnce = false
            error("injected identity transaction failure")
        }
    })
    val manager = ManageCreatorIdentity(repository)
    val mangaRepository = MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter)
    val mangas = mangaRepository.insertNetworkManga(
        (1L..2L).map { source ->
            Manga.create().copy(
                source = source,
                url = "/uncollected",
                title = "Uncollected",
                author = "Exact",
                favorite = false,
            )
        },
    )
    val mention = ExtractCreatorsFromManga().await(mangas.first()).single()
    val failure = runCatching { open(manager, mangas.first(), mention) }.exceptionOrNull()
    check(failure?.message == "injected identity transaction failure")
    handler.await {
        check(author_archiveQueries.getArchiveCreatorIdByExactName("Exact").executeAsOneOrNull() == null)
        check(author_archiveQueries.getArchiveCreatorsForBackup().executeAsList().isEmpty())
    }
    val first = open(manager, mangas.first(), mention) as CreatorMentionResolution.Resolved
    val second = open(manager, mangas.last(), mention) as CreatorMentionResolution.Resolved
    check(first.creatorId == second.creatorId)
    handler.await {
        check(author_archiveQueries.getArchiveCreatorIdByExactName("Exact").executeAsOne() == first.creatorId)
        check(author_archiveQueries.getArchiveCreatorsForBackup().executeAsList().size == 1)
        mangas.forEach { manga ->
            val link = author_archiveQueries.getArchiveMangaLink(manga.id, first.creatorId).executeAsOne()
            check(link.role == "AUTHOR" && link.origin == "AUTOMATIC" && link.source_text == "Exact")
        }
    }
    mangas.forEach { check(!mangaRepository.getMangaById(it.id).favorite) }
}
