package eu.kanade.domain.manga.interactor

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import mihon.domain.reader.ReaderModeFlags
import mihon.domain.reader.ReaderSettings
import mihon.domain.reader.ReaderSettingsCodec
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class SetMangaViewerFlags(
    private val mangaRepository: MangaRepository,
) {

    suspend fun awaitSetReadingMode(id: Long, flag: Long) {
        val manga = mangaRepository.getMangaById(id)
        mangaRepository.update(
            MangaUpdate(
                id = id,
                viewerFlags = ReaderModeFlags.write(manga.viewerFlags, flag),
            ),
        )
    }

    suspend fun awaitSetOrientation(id: Long, flag: Long) {
        val manga = mangaRepository.getMangaById(id)
        mangaRepository.update(
            MangaUpdate(
                id = id,
                viewerFlags = manga.viewerFlags.setFlag(flag, ReaderOrientation.MASK.toLong()),
            ),
        )
    }

    suspend fun awaitSetReaderSettings(
        id: Long,
        settings: ReaderSettings?,
        followingDefault: Boolean,
        defaults: ReaderSettings,
    ) {
        val manga = mangaRepository.getMangaById(id)
        val flags = when {
            followingDefault -> ReaderSettingsCodec.followDefault(manga.viewerFlags, defaults)
            settings != null -> ReaderSettingsCodec.setCustom(manga.viewerFlags, settings)
            else -> ReaderSettingsCodec.restoreCustom(manga.viewerFlags, defaults)
        }
        mangaRepository.update(MangaUpdate(id = id, viewerFlags = flags))
    }

    private fun Long.setFlag(flag: Long, mask: Long): Long {
        return this and mask.inv() or (flag and mask)
    }
}
