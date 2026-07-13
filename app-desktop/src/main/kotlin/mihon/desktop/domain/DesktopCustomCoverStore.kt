package mihon.desktop.domain

import java.io.File
import tachiyomi.domain.manga.interactor.CustomCoverStore

class DesktopCustomCoverStore(
    private val coversDir: File,
) : CustomCoverStore {
    override suspend fun write(mangaId: Long, bytes: ByteArray) {
        coversDir.mkdirs()
        coverFile(mangaId).writeBytes(bytes)
    }

    fun getCustomCoverFile(mangaId: Long): File = coverFile(mangaId)

    fun customCoverExists(mangaId: Long): Boolean = coverFile(mangaId).exists()

    fun setCustomCover(mangaId: Long, source: File) {
        coversDir.mkdirs()
        source.copyTo(coverFile(mangaId), overwrite = true)
    }

    override suspend fun delete(mangaId: Long) {
        check(deleteCustomCover(mangaId)) { "Unable to delete custom cover" }
    }

    fun deleteCustomCover(mangaId: Long): Boolean = coverFile(mangaId).let { !it.exists() || it.delete() }

    fun resolveModel(mangaId: Long, fallbackUrl: String?): String? =
        coverFile(mangaId).takeIf(File::exists)?.absolutePath ?: fallbackUrl

    private fun coverFile(mangaId: Long): File = File(coversDir, "$mangaId")
}
