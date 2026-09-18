package tachiyomi.domain.creator.service

import tachiyomi.domain.creator.model.SourceWorkArchiveVersion

class OpenCreatorWorkVersion(private val saveListed: suspend (SourceWorkArchiveVersion) -> Long) {
    suspend fun await(version: SourceWorkArchiveVersion): Long = version.mangaId ?: saveListed(version)
}
