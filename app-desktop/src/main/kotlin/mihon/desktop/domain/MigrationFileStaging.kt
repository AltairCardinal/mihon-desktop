package mihon.desktop.domain

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Same-parent, reversible moves for one accepted confirmation. No directory is rescanned on retry. */
class MigrationFileStaging(
    private val operationDirectory: File,
    private val beforeMove: (File, File) -> Unit = { _, _ -> },
) {
    @Serializable
    data class Artifact(val original: String, val staged: String, val digest: String)

    @Serializable
    data class Cover(val target: String, val backup: String, val previousDigest: String?, val installedDigest: String)

    @Serializable
    data class Snapshot(
        val downloads: List<Artifact>,
        val coverSnapshot: String?,
        val cover: Cover? = null,
        val chapterIds: Set<Long> = emptySet(),
    )

    private val record get() = File(operationDirectory, "files.json")

    fun discardPrivateRecord() {
        File(operationDirectory, "accepted-cover").let { check(!it.exists() || it.delete()) }
        check(!record.exists() || record.delete())
        check(!operationDirectory.exists() || operationDirectory.delete()) {
            "Migration capture contains unexpected recovery files"
        }
    }

    fun load(): Snapshot? = record.takeIf(File::isFile)?.readText(Charsets.UTF_8)?.let {
        Json.decodeFromString<Snapshot>(it)
    }

    fun capture(downloads: List<File>, customCover: File?, chapterIds: Set<Long> = emptySet()): Snapshot {
        check(!record.exists()) { "An accepted migration already owns this file snapshot" }
        Files.createDirectories(operationDirectory.toPath())
        val cover = customCover?.takeIf(File::isFile)?.let {
            File(operationDirectory, "accepted-cover").apply { writeBytes(it.readBytes()) }.absolutePath
        }
        val suffix = operationDirectory.name
        val snapshot = Snapshot(
            downloads.distinctBy(File::getAbsolutePath).mapIndexed { index, file ->
                check(file.exists()) { "Accepted download no longer exists" }
                Artifact(
                    file.absolutePath,
                    File(file.parentFile, ".mihon-migration-$suffix-$index").absolutePath,
                    digest(file),
                )
            },
            cover,
            chapterIds = chapterIds,
        )
        save(snapshot)
        return snapshot
    }

    fun prepare(snapshot: Snapshot, targetCover: File?): Snapshot {
        var pending = load() ?: snapshot
        if (pending.cover == null && pending.coverSnapshot != null && targetCover != null) {
            val cover = Cover(
                targetCover.absolutePath,
                File(targetCover.parentFile, ".mihon-migration-${operationDirectory.name}-cover").absolutePath,
                targetCover.takeIf(File::exists)?.let(::digest),
                digest(File(pending.coverSnapshot)),
            )
            pending = pending.copy(cover = cover)
            save(pending)
        }
        pending.downloads.forEach { artifact ->
            val original = File(artifact.original)
            val staged = File(artifact.staged)
            if (staged.exists()) {
                check(!original.exists() && digest(staged) == artifact.digest) {
                    "Staged download conflicts with a later file"
                }
            } else {
                check(original.exists() && digest(original) == artifact.digest) { "Accepted download changed" }
                move(original, staged)
            }
        }
        pending.cover?.let { cover ->
            val target = File(cover.target)
            val backup = File(cover.backup)
            if (backup.exists()) {
                check(digest(backup) == cover.previousDigest) { "Cover backup changed" }
                check(!target.exists() || digest(target) == cover.installedDigest) { "Target cover changed" }
            } else if (cover.previousDigest != null) {
                check(target.exists() && digest(target) == cover.previousDigest) { "Target cover changed" }
                move(target, backup)
            } else {
                check(!target.exists() || digest(target) == cover.installedDigest) { "Target acquired a new cover" }
            }
            if (!target.exists()) {
                Files.createDirectories(target.parentFile.toPath())
                val temporary = Files.createTempFile(target.parentFile.toPath(), ".migration-cover-", ".tmp")
                try {
                    Files.copy(
                        File(requireNotNull(pending.coverSnapshot)).toPath(),
                        temporary,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    move(temporary.toFile(), target)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
        }
        return pending
    }

    fun rollback(snapshot: Snapshot) {
        val pending = load() ?: snapshot
        pending.cover?.let { cover ->
            val target = File(cover.target)
            val backup = File(cover.backup)
            if ((backup.exists() || cover.previousDigest == null) &&
                target.exists() && digest(target) == cover.installedDigest
            ) {
                check(target.delete()) { "Unable to restore target cover" }
            } else {
                check(!target.exists() || (!backup.exists() && digest(target) == cover.previousDigest)) {
                    "A later cover prevents rollback; original backup is retained"
                }
            }
            if (backup.exists()) {
                check(!target.exists() && digest(backup) == cover.previousDigest) { "Cover rollback conflicts" }
                move(backup, target)
            }
        }
        pending.downloads.asReversed().forEach { artifact ->
            val original = File(artifact.original)
            val staged = File(artifact.staged)
            if (staged.exists()) {
                check(!original.exists() && digest(staged) == artifact.digest) { "A later download prevents rollback" }
                move(staged, original)
            }
        }
    }

    fun complete(snapshot: Snapshot) {
        val pending = load() ?: snapshot
        pending.downloads.forEach { artifact ->
            val staged = File(artifact.staged)
            if (staged.exists()) {
                check(digest(staged) == artifact.digest) { "Staged download changed" }
                check(staged.deleteRecursively()) { "Unable to clean committed migration download" }
            }
        }
        pending.cover?.let { cover ->
            val backup = File(cover.backup)
            if (backup.exists()) {
                check(digest(backup) == cover.previousDigest) { "Cover backup changed" }
                check(backup.delete()) { "Unable to clean committed cover backup" }
            }
        }
    }

    suspend fun <T> execute(snapshot: Snapshot, targetCover: File?, commit: suspend () -> T): T {
        val result = try {
            prepare(snapshot, targetCover)
            commit()
        } catch (error: Throwable) {
            withContext(NonCancellable) { rollback(snapshot) }
            throw error
        }
        complete(snapshot)
        return result
    }

    private fun save(snapshot: Snapshot) {
        val temporary = Files.createTempFile(operationDirectory.toPath(), ".files-", ".tmp")
        try {
            Files.writeString(temporary, Json.encodeToString(snapshot), Charsets.UTF_8)
            Files.move(temporary, record.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun move(source: File, target: File) {
        check(!target.exists()) { "Migration file destination is occupied" }
        beforeMove(source, target)
        mihon.desktop.platform.DesktopMigrationFileMove.move(source, target)
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun update(item: File, name: String) {
            check(!Files.isSymbolicLink(item.toPath())) { "Migration cannot stage symbolic links" }
            hash.update(name.toByteArray(Charsets.UTF_8))
            if (item.isDirectory) {
                hash.update(0.toByte())
                val children = item.listFiles() ?: error("Unable to read accepted download")
                children.sortedBy(File::getName).forEach { update(it, "$name/${it.name}") }
            } else {
                hash.update(1.toByte())
                item.inputStream().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        hash.update(buffer, 0, count)
                    }
                }
            }
        }
        update(file, "")
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
