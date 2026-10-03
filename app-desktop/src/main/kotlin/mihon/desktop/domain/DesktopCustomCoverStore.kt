package mihon.desktop.domain

import tachiyomi.domain.manga.interactor.CustomCoverStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DesktopCustomCoverStore internal constructor(
    private val coversDir: File,
    private val writeBytes: (File, ByteArray) -> Unit,
) : CustomCoverStore {
    constructor(coversDir: File) : this(coversDir, { file, bytes -> file.writeBytes(bytes) })

    private val coverLock = Any()
    private val migrationCovers = mutableSetOf<Long>()

    fun reserveMigrationCovers(mangaIds: Set<Long>): AutoCloseable {
        synchronized(coverLock) {
            check(mangaIds.none { it in migrationCovers }) { "Cover already has a migration reservation" }
            migrationCovers.addAll(mangaIds)
        }
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(coverLock) { migrationCovers.removeAll(mangaIds) }
        }
    }

    override suspend fun write(mangaId: Long, bytes: ByteArray) {
        replaceCover(mangaId, bytes)
    }

    fun getCustomCoverFile(mangaId: Long): File = coverFile(mangaId)

    fun customCoverExists(mangaId: Long): Boolean = coverFile(mangaId).exists()

    fun setCustomCover(mangaId: Long, source: File) {
        replaceCover(mangaId, source.readBytes())
    }

    private fun replaceCover(mangaId: Long, bytes: ByteArray) = synchronized(coverLock) {
        check(mangaId !in migrationCovers) { "Cover is being migrated" }
        Files.createDirectories(coversDir.toPath())
        val staging = Files.createTempFile(coversDir.toPath(), ".$mangaId-", ".tmp")
        try {
            writeBytes(staging.toFile(), bytes)
            // Unsupported atomic replacement fails without falling back to destructive overwrite.
            Files.move(
                staging,
                coverFile(mangaId).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    override suspend fun delete(mangaId: Long) {
        check(deleteCustomCover(mangaId)) { "Unable to delete custom cover" }
    }

    fun deleteCustomCover(mangaId: Long): Boolean = synchronized(coverLock) {
        check(mangaId !in migrationCovers) { "Cover is being migrated" }
        coverFile(mangaId).let { !it.exists() || it.delete() }
    }

    fun resolveModel(mangaId: Long, fallbackUrl: String?): String? =
        coverFile(mangaId).takeIf(File::exists)?.absolutePath ?: fallbackUrl

    private fun coverFile(mangaId: Long): File = File(coversDir, "$mangaId")
}
