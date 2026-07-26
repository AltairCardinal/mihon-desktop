package tachiyomi.domain.creator.interactor

import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.repository.CreatorRepository

class LinkMangaCreator(
    private val creatorRepository: CreatorRepository,
) {
    suspend fun await(
        mangaId: Long,
        name: String,
        role: CreatorRole,
    ): Long {
        val creator = creatorRepository.upsertCreator(name)
        creatorRepository.linkMangaCreator(
            mangaId = mangaId,
            creatorId = creator.id,
            role = role,
            sourceText = name,
            confidence = 1.0,
            evidence = "manga detail ${role.name.lowercase()}",
        )
        return creator.id
    }
}
