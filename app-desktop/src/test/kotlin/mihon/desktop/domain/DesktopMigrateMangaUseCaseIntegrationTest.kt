package mihon.desktop.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class DesktopMigrateMangaUseCaseIntegrationTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var fileRoot: java.io.File

    @Test
    fun `prepared migration restores finite original files before new owner starts downloads`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", 1)
            val target = f.insertManga("/target", 2)
            f.mangas.updateAtomically(LibraryMembershipUpdate(source.id, true, 40, emptyList()))
            f.chapters.addAll(
                listOf(Chapter.create().copy(mangaId = source.id, url = "/old-1", name = "One", chapterNumber = 1.0)),
            )
            val original = f.withFiles(fileRoot)
            try {
                val artifact = original.provider.chapterDownloadDir(1, source.title, "One").apply { mkdirs() }
                java.io.File(artifact, "1.jpg").writeBytes(byteArrayOf(1, 2, 3))
                original.covers.write(source.id, byteArrayOf(7, 8, 9))
                original.covers.write(target.id, byteArrayOf(4, 5, 6))
                val options = MigrationOptions(copyCustomCover = true, removeDownloads = true)
                val accepted = original.useCase.accept(source, options, true)
                val command = mihon.domain.migration.MigrationCommit(
                    source.id, source.source, source.url, target.id, target.source, target.url,
                    setOf(
                        mihon.domain.migration.models.MigrationFlag.CUSTOM_COVER,
                        mihon.domain.migration.models.MigrationFlag.REMOVE_DOWNLOAD,
                    ),
                    true, accepted.acceptedAt,
                    accepted.operationId, previousCoverVersion = 0, coverVersion = accepted.acceptedAt,
                )
                f.mangas.prepareMigration(command)
                original.files.staging(
                    accepted.operationId,
                ).prepare(requireNotNull(accepted.files), original.covers.getCustomCoverFile(target.id))
                f.mangas.markMigrationFilesReady(command)
                org.junit.jupiter.api.Assertions.assertFalse(artifact.exists())
                val reopened = f.withFiles(fileRoot)
                try {
                    reopened.useCase.recoverPreparedFiles()
                    org.junit.jupiter.api.Assertions.assertTrue(
                        artifact.isDirectory,
                        "Startup restores the accepted original before workers",
                    )
                    org.junit.jupiter.api.Assertions.assertArrayEquals(
                        byteArrayOf(4, 5, 6),
                        reopened.covers.getCustomCoverFile(target.id).readBytes(),
                    )
                    assertEquals(null, f.mangas.migrationReceipt(source.id))
                    assertEquals(true, f.mangas.getMangaById(source.id).favorite)
                    assertEquals(false, f.mangas.getMangaById(target.id).favorite)
                } finally {
                    reopened.manager.stopAndJoin()
                }
            } finally {
                original.manager.stopAndJoin()
            }
        }
    }

    @Test
    fun `committed migration cannot acknowledge missing original file sidecar`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", 1)
            val target = f.insertManga("/target", 2)
            f.mangas.updateAtomically(LibraryMembershipUpdate(source.id, true, 40, emptyList()))
            val command = mihon.domain.migration.MigrationCommit(
                source.id, source.source, source.url, target.id, target.source, target.url,
                setOf(mihon.domain.migration.models.MigrationFlag.REMOVE_DOWNLOAD), true, 100, "missing-sidecar",
            )
            f.mangas.prepareMigration(command)
            f.mangas.markMigrationFilesReady(command)
            f.mangas.commitMigration(command)
            val files = f.withFiles(fileRoot)
            try {
                assertThrows(IllegalStateException::class.java) {
                    kotlinx.coroutines.runBlocking { files.useCase.recover(source.id) }
                }
                val accepted =
                    AcceptedMigration(
                        source,
                        MigrationOptions(removeDownloads = true),
                        true,
                        command.operationId!!,
                        100,
                    )
                val serialized = kotlinx.serialization.json.Json.encodeToString(
                    AcceptedMigration.serializer(),
                    accepted,
                )
                val restored = kotlinx.serialization.json.Json.decodeFromString(
                    AcceptedMigration.serializer(),
                    serialized,
                )
                assertThrows(IllegalStateException::class.java) {
                    kotlinx.coroutines.runBlocking { files.useCase.recoverAccepted(restored) }
                }
                assertEquals(true, f.mangas.migrationReceipt(source.id)?.committed)
                assertEquals(false, f.mangas.migrationReceipt(source.id)?.filesComplete)
                assertEquals(true, f.mangas.getMangaById(target.id).favorite)
            } finally {
                files.manager.stopAndJoin()
            }
        }
    }

    @Test
    fun `prepared serialized confirmation with missing sidecar cannot cancel its recovery anchor`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", 1)
            val target = f.insertManga("/target", 2)
            val command = mihon.domain.migration.MigrationCommit(
                source.id, source.source, source.url,
                target.id, target.source, target.url,
                setOf(
                    mihon.domain.migration.models.MigrationFlag.REMOVE_DOWNLOAD,
                ),
                true, 100, "missing-prepared",
            )
            f.mangas.prepareMigration(command)
            val files = f.withFiles(fileRoot)
            try {
                val accepted =
                    AcceptedMigration(source, MigrationOptions(removeDownloads = true), true, "missing-prepared", 100)
                assertThrows(IllegalStateException::class.java) {
                    kotlinx.coroutines.runBlocking { files.useCase.cancelAccepted(accepted) }
                }
                assertEquals(false, f.mangas.migrationReceipt(source.id)?.committed)
                assertEquals(command, f.mangas.migrationReceipt(source.id)?.request)
            } finally {
                files.manager.stopAndJoin()
            }
        }
    }

    @Test
    fun `private cleanup and SQL acknowledgement refusal preserve original committed result for retry`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", 1)
            val target = f.insertManga("/target", 2)
            f.mangas.updateAtomically(LibraryMembershipUpdate(source.id, true, 40, emptyList()))
            val files = f.withFiles(fileRoot)
            try {
                files.covers.write(source.id, byteArrayOf(7, 8, 9))
                val options = MigrationOptions(copyCustomCover = true)
                val accepted = files.useCase.accept(source, options, true)
                val privateDirectory = java.io.File(requireNotNull(accepted.files?.coverSnapshot)).parentFile
                files.useCase.await(
                    source,
                    target("/target"),
                    2,
                    listOf(sourceChapter("/target-1", "One", 1.0)),
                    options,
                    true,
                    accepted,
                    deferAcknowledgement = true,
                )
                val unexpected = java.io.File(privateDirectory, "unknown-private-file").apply {
                    writeText("Preserve conflict")
                }
                assertThrows(IllegalStateException::class.java) {
                    kotlinx.coroutines.runBlocking { files.useCase.acknowledge(source.id, accepted.operationId) }
                }
                assertEquals(true, f.mangas.migrationReceipt(source.id)?.filesComplete)
                assertEquals(40, f.mangas.getMangaById(target.id).dateAdded)
                unexpected.delete()
                f.rejectMigrationAcknowledgement()
                try {
                    assertThrows(Exception::class.java) {
                        kotlinx.coroutines.runBlocking { files.useCase.acknowledge(source.id, accepted.operationId) }
                    }
                    assertEquals(true, f.mangas.migrationReceipt(source.id)?.committed)
                    org.junit.jupiter.api.Assertions.assertFalse(
                        privateDirectory.exists(),
                        "Completed capture is cleaned before durable SQL ACK",
                    )
                } finally {
                    f.allowMigrationAcknowledgement()
                }
                files.useCase.recoverAccepted(accepted.copy(files = null))
                assertEquals(null, f.mangas.migrationReceipt(source.id))
                assertEquals(40, f.mangas.getMangaById(target.id).dateAdded)
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                    byteArrayOf(7, 8, 9),
                    files.covers.getCustomCoverFile(target.id).readBytes(),
                )
                val unused = files.useCase.accept(
                    f.mangas.getMangaById(target.id),
                    MigrationOptions(copyCustomCover = true),
                    false,
                )
                val unusedDirectory = java.io.File(requireNotNull(unused.files?.coverSnapshot)).parentFile
                files.useCase.cancelAccepted(unused)
                org.junit.jupiter.api.Assertions.assertFalse(
                    unusedDirectory.exists(),
                    "Cancelled confirmation releases only its private capture",
                )
                org.junit.jupiter.api.Assertions.assertTrue(files.covers.getCustomCoverFile(target.id).exists())
            } finally {
                files.manager.stopAndJoin()
            }
        }
    }

    @Test
    fun `actual committed SQL cancellation keeps installed files and new owner finishes original cleanup`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", 1)
            val target = f.insertManga("/target", 2)
            f.mangas.updateAtomically(LibraryMembershipUpdate(source.id, true, 40, emptyList()))
            f.chapters.addAll(
                listOf(Chapter.create().copy(mangaId = source.id, url = "/old-1", name = "One", chapterNumber = 1.0)),
            )
            val cancelledRepository = object : MangaRepository by f.mangas {
                override suspend fun commitMigration(commit: mihon.domain.migration.MigrationCommit): Manga {
                    f.mangas.commitMigration(commit)
                    throw kotlinx.coroutines.CancellationException("Caller cancelled after durable SQL commit")
                }
            }
            val files = f.withFiles(fileRoot, cancelledRepository)
            try {
                val artifact = files.provider.chapterDownloadDir(1, source.title, "One").apply { mkdirs() }
                java.io.File(artifact, "1.jpg").writeBytes(byteArrayOf(1, 2, 3))
                files.covers.write(source.id, byteArrayOf(7, 8, 9))
                files.covers.write(target.id, byteArrayOf(4, 5, 6))
                val options = MigrationOptions(copyCustomCover = true, removeDownloads = true)
                val accepted = files.useCase.accept(source, options, true)
                assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        files.useCase.await(
                            source,
                            target("/target"),
                            2,
                            listOf(sourceChapter("/target-1", "One", 1.0)),
                            options,
                            true,
                            accepted,
                        )
                    }
                }
                assertEquals(false, f.mangas.getMangaById(source.id).favorite)
                assertEquals(true, f.mangas.getMangaById(target.id).favorite)
                assertEquals(true, f.mangas.migrationReceipt(source.id)?.committed)
                org.junit.jupiter.api.Assertions.assertFalse(
                    artifact.exists(),
                    "Committed files must not be rolled back",
                )
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                    byteArrayOf(7, 8, 9),
                    files.covers.getCustomCoverFile(target.id).readBytes(),
                )
                f.mangas.update(MangaUpdate(target.id, notes = "Later target note"))
                val reopened = f.withFiles(fileRoot)
                try {
                    reopened.useCase.recover(source.id)
                    assertEquals(null, f.mangas.migrationReceipt(source.id))
                    assertEquals(40, f.mangas.getMangaById(target.id).dateAdded)
                    assertEquals("Later target note", f.mangas.getMangaById(target.id).notes)
                    org.junit.jupiter.api.Assertions.assertFalse(artifact.exists())
                    org.junit.jupiter.api.Assertions.assertArrayEquals(
                        byteArrayOf(7, 8, 9),
                        reopened.covers.getCustomCoverFile(target.id).readBytes(),
                    )
                } finally {
                    reopened.manager.stopAndJoin()
                }
            } finally {
                files.manager.stopAndJoin()
            }
        }
    }

    @Test
    fun `actual migration consumes accepted files and cover without adopting HTTP late arrivals`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", 1)
            val target = f.insertManga("/target", 2)
            f.mangas.updateAtomically(LibraryMembershipUpdate(source.id, true, 40, emptyList()))
            f.chapters.addAll(
                listOf(Chapter.create().copy(mangaId = source.id, url = "/old-1", name = "One", chapterNumber = 1.0)),
            )
            val files = f.withFiles(fileRoot)
            try {
                val artifact = files.provider.chapterDownloadDir(1, source.title, "One").apply { mkdirs() }
                java.io.File(artifact, "1.jpg").writeBytes(byteArrayOf(1, 2, 3))
                files.covers.write(source.id, byteArrayOf(7, 8, 9))
                files.covers.write(target.id, byteArrayOf(4, 5, 6))
                f.mangas.update(MangaUpdate(target.id, coverLastModified = 17))
                val options = MigrationOptions(copyCustomCover = true, removeDownloads = true)
                val accepted = files.useCase.accept(source, options, true)
                val late = files.provider.chapterDownloadDir(1, source.title, "Later").apply { mkdirs() }
                java.io.File(late, "1.jpg").writeBytes(byteArrayOf(10))
                files.covers.write(source.id, byteArrayOf(12, 13))
                files.useCase.await(
                    source,
                    target("/target"),
                    2,
                    listOf(sourceChapter("/target-1", "One", 1.0)),
                    options,
                    true,
                    accepted,
                )
                org.junit.jupiter.api.Assertions.assertFalse(artifact.exists(), "Accepted original download is removed")
                org.junit.jupiter.api.Assertions.assertTrue(late.exists())
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                    byteArrayOf(7, 8, 9),
                    files.covers.getCustomCoverFile(target.id).readBytes(),
                )
                org.junit.jupiter.api.Assertions.assertTrue(f.mangas.getMangaById(target.id).coverLastModified > 17)
                assertEquals(false, f.mangas.getMangaById(source.id).favorite)
            } finally {
                files.manager.stopAndJoin()
            }
        }
    }

    @Test
    fun `actual SQL refusal restores original files and cover while same confirmation can retry`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", 1)
            val target = f.insertManga("/target", 2)
            f.mangas.updateAtomically(LibraryMembershipUpdate(source.id, true, 40, emptyList()))
            f.chapters.addAll(
                listOf(Chapter.create().copy(mangaId = source.id, url = "/old-1", name = "One", chapterNumber = 1.0)),
            )
            val files = f.withFiles(fileRoot)
            try {
                val artifact = files.provider.chapterDownloadDir(1, source.title, "One").apply { mkdirs() }
                java.io.File(artifact, "1.jpg").writeBytes(byteArrayOf(1, 2, 3))
                files.covers.write(source.id, byteArrayOf(7, 8, 9))
                files.covers.write(target.id, byteArrayOf(4, 5, 6))
                val options = MigrationOptions(copyCustomCover = true, removeDownloads = true)
                val accepted = files.useCase.accept(source, options, true)
                f.rejectSourceRemoval(source.id)
                assertThrows(Exception::class.java) {
                    kotlinx.coroutines.runBlocking {
                        files.useCase.await(
                            source,
                            target("/target"),
                            2,
                            listOf(sourceChapter("/target-1", "One", 1.0)),
                            options,
                            true,
                            accepted,
                        )
                    }
                }
                org.junit.jupiter.api.Assertions.assertTrue(artifact.isDirectory)
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                    byteArrayOf(4, 5, 6),
                    files.covers.getCustomCoverFile(target.id).readBytes(),
                )
                assertEquals(true, f.mangas.getMangaById(source.id).favorite)
                assertEquals(false, f.mangas.getMangaById(target.id).favorite)
                f.clearSourceRemovalFailure()
                files.useCase.await(
                    source,
                    target("/target"),
                    2,
                    listOf(sourceChapter("/target-1", "One", 1.0)),
                    options,
                    true,
                    accepted,
                )
                org.junit.jupiter.api.Assertions.assertFalse(
                    artifact.exists(),
                    "Retry completes the original accepted files",
                )
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                    byteArrayOf(7, 8, 9),
                    files.covers.getCustomCoverFile(target.id).readBytes(),
                )
            } finally {
                files.manager.stopAndJoin()
            }
        }
    }

    @Test
    fun `actual migration waits for accepted task checkpoint without replaying committed SQL or files`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", 1)
            val target = f.insertManga("/target", 2)
            f.mangas.updateAtomically(LibraryMembershipUpdate(source.id, true, 40, emptyList()))
            val accepted = f.useCase.accept(source, MigrationOptions(), true)
            f.useCase.await(
                source,
                target("/target"),
                2,
                listOf(sourceChapter("/target-1", "One", 1.0)),
                accepted = accepted,
                deferAcknowledgement = true,
            )
            assertEquals(
                true,
                f.mangas.migrationReceipt(source.id)?.committed,
                "A completed SQL migration remains recoverable until its original task is durably accepted",
            )
            f.mangas.update(MangaUpdate(target.id, notes = "New user note"))
            f.useCase.await(source, target("/target"), 2, emptyList(), accepted = accepted, deferAcknowledgement = true)
            assertEquals(40, f.mangas.getMangaById(target.id).dateAdded)
            assertEquals("New user note", f.mangas.getMangaById(target.id).notes)
        }
    }

    @Test
    fun `accepted migration receipt survives SQL success without reapplying source date or target state`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", 1)
            val target = f.insertManga("/target", 2)
            f.mangas.updateAtomically(LibraryMembershipUpdate(source.id, true, 40, emptyList()))
            f.mangas.update(MangaUpdate(source.id, notes = "Source note"))
            val command = mihon.domain.migration.MigrationCommit(
                source.id, source.source, source.url, target.id, target.source, target.url,
                setOf(mihon.domain.migration.models.MigrationFlag.NOTES), true, 100, "accepted-one",
            )
            f.mangas.prepareMigration(command)
            f.mangas.commitMigration(command)
            f.mangas.update(MangaUpdate(target.id, notes = "New target note"))
            f.mangas.commitMigration(command)
            assertEquals(
                40,
                f.mangas.getMangaById(target.id).dateAdded,
                "Retry restores the accepted receipt, not the now-empty source date",
            )
            assertEquals("New target note", f.mangas.getMangaById(target.id).notes)
            assertEquals(true, f.mangas.migrationReceipt(source.id)?.committed)
        }
    }

    @Test
    fun `prepared migration owns original source before any second target can be accepted`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", 1)
            val first = f.insertManga("/first", 2)
            val second = f.insertManga("/second", 3)
            val command = mihon.domain.migration.MigrationCommit(
                source.id, source.source, source.url, first.id, first.source, first.url,
                emptySet(), true, 100, "accepted-one",
            )
            f.mangas.prepareMigration(command)
            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking {
                    f.mangas.prepareMigration(
                        command.copy(
                            targetMangaId = second.id,
                            targetSourceId = second.source,
                            targetUrl = second.url,
                            operationId = "accepted-two",
                        ),
                    )
                }
            }
            assertEquals("accepted-one", f.mangas.migrationReceipt(source.id)?.request?.operationId)
        }
    }

    @Test
    fun `prepared migration phase cannot be consumed or acknowledged as an ordinary directory`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", 1)
            val target = f.insertManga("/target", 2)
            val command = mihon.domain.migration.MigrationCommit(
                source.id, source.source, source.url, target.id, target.source, target.url,
                emptySet(), false, 100, "accepted-one",
            )
            f.mangas.prepareMigration(command)
            val stored = f.chapters.pendingDirectoryPhase(target.id)
            org.junit.jupiter.api.Assertions.assertNotNull(stored, "Preparation must be persisted before any file move")
            val phase = stored!!
            assertEquals(false, phase.complete)
            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking { f.chapters.acknowledgeDirectoryPhase(phase) }
            }
            assertEquals(phase, f.chapters.pendingDirectoryPhase(target.id))
        }
    }

    @Test
    fun `membership rejection cannot leave copied target chapter state`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", sourceId = 1)
            val target = f.insertManga("/target", sourceId = 2)
            f.mangas.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(source.id, true, 40, emptyList())))
            f.chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = source.id,
                        url = "/source-1",
                        name = "Source 1",
                        chapterNumber = 1.0,
                        read = true,
                        bookmark = true,
                    ),
                    Chapter.create().copy(
                        mangaId = target.id,
                        url = "/target-1",
                        name = "Target 1",
                        chapterNumber = 1.0,
                        lastPageRead = 7,
                    ),
                ),
            )
            val before = f.chapters.getChapterByMangaId(target.id).single()
            f.rejectSourceRemoval(source.id)
            assertThrows(Exception::class.java) {
                kotlinx.coroutines.runBlocking {
                    f.useCase.await(
                        f.mangas.getMangaById(source.id),
                        target("/target"),
                        2,
                        listOf(sourceChapter("/target-1", "Target 1", 1.0)),
                        replace = true,
                    )
                }
            }
            assertEquals(
                before,
                f.chapters.getChapterByMangaId(target.id).single(),
                "Rejected membership rolls back this migration's chapter patches",
            )
            assertEquals(true, f.mangas.getMangaById(source.id).favorite)
            assertEquals(false, f.mangas.getMangaById(target.id).favorite)
        }
    }

    @Test
    fun `same source URL identity cannot migrate a work onto itself`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", sourceId = 1)
            f.mangas.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(source.id, true, 40, emptyList())))
            val current = f.mangas.getMangaById(source.id)
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking {
                    f.useCase.await(
                        current,
                        target("/source"),
                        1,
                        listOf(sourceChapter("/source-1", "Source 1", 1.0)),
                        replace = true,
                    )
                }
            }
            assertEquals(current, f.mangas.getMangaById(source.id))
            assertEquals(emptyList<Chapter>(), f.chapters.getChapterByMangaId(source.id))
        }
    }

    @Test
    fun `category failure rolls back source and target migration membership`() = runTest {
        fixture(faultMembership = true).use { f ->
            val categoryId = f.insertCategory("Action")
            val source = f.insertManga("/source")
            f.mangas.update(
                MangaUpdate(
                    id = source.id,
                    chapterFlags = 0x35L,
                    viewerFlags = 0x62L,
                    notes = "source notes",
                ),
            )
            f.mangas.updateMembershipsAtomically(
                listOf(LibraryMembershipUpdate(source.id, true, 40, listOf(categoryId))),
            )
            val favoriteSource = f.mangas.getMangaById(source.id)

            assertThrows(Exception::class.java) {
                kotlinx.coroutines.runBlocking {
                    f.useCase.await(
                        favoriteSource,
                        target("/target"),
                        2,
                        listOf(sourceChapter("/target-1", "Target 1", 1.0)),
                        replace = true,
                    )
                }
            }

            val target = requireNotNull(f.mangas.getMangaByUrlAndSourceId("/target", 2))
            assertEquals(false, target.favorite)
            assertEquals(0, target.dateAdded)
            assertEquals(0, target.chapterFlags)
            assertEquals(0, target.viewerFlags)
            assertEquals("", target.notes)
            assertEquals(emptyList<Long>(), f.categoryIds(target.id))
            assertEquals(true, f.mangas.getMangaById(source.id).favorite)
            assertEquals(40, f.mangas.getMangaById(source.id).dateAdded)
            assertEquals(listOf(categoryId), f.categoryIds(source.id))
        }
    }

    @Test
    fun `copy categories and replace move real library membership without half state`() = runTest {
        fixture().use { f ->
            val categoryId = f.insertCategory("Action")
            val source = f.insertManga("/source")
            f.mangas.updateMembershipsAtomically(
                listOf(LibraryMembershipUpdate(source.id, true, 40, listOf(categoryId))),
            )

            val target = f.useCase.await(
                source,
                target("/target"),
                2,
                listOf(sourceChapter("/target-1", "Target 1", 1.0)),
                replace = true,
            )

            assertEquals(true, f.mangas.getMangaById(target.id).favorite)
            assertEquals(listOf(categoryId), f.categoryIds(target.id))
            assertEquals(false, f.mangas.getMangaById(source.id).favorite)
            assertEquals(0, f.mangas.getMangaById(source.id).dateAdded)
            assertEquals(emptyList<Long>(), f.categoryIds(source.id))
        }
    }

    @Test
    fun `copy categories false and replace false preserve source and recalculate target date`() = runTest {
        fixture().use { f ->
            val categoryId = f.insertCategory("Action")
            val source = f.insertManga("/source")
            f.mangas.updateMembershipsAtomically(
                listOf(LibraryMembershipUpdate(source.id, true, 40, listOf(categoryId))),
            )

            val target = f.useCase.await(
                source,
                target("/target"),
                2,
                listOf(sourceChapter("/target-1", "Target 1", 1.0)),
                options = MigrationOptions(copyCategories = false),
                replace = false,
            )

            val savedTarget = f.mangas.getMangaById(target.id)
            assertEquals(true, savedTarget.favorite)
            assertEquals(true, savedTarget.dateAdded > 40)
            assertEquals(emptyList<Long>(), f.categoryIds(target.id))
            assertEquals(true, f.mangas.getMangaById(source.id).favorite)
            assertEquals(40, f.mangas.getMangaById(source.id).dateAdded)
            assertEquals(listOf(categoryId), f.categoryIds(source.id))
        }
    }

    @Test
    fun `copy categories false preserves categories already assigned to favorite target`() = runTest {
        fixture().use { f ->
            val targetCategoryId = f.insertCategory("Target category")
            val sourceCategoryId = f.insertCategory("Source category")
            val source = f.insertManga("/source")
            val target = f.insertManga("/target", sourceId = 2)
            f.mangas.updateMembershipsAtomically(
                listOf(
                    LibraryMembershipUpdate(source.id, true, 40, listOf(sourceCategoryId)),
                    LibraryMembershipUpdate(target.id, true, 20, listOf(targetCategoryId)),
                ),
            )
            f.failOnCategoryDelete(target.id)

            val migrated = f.useCase.await(
                f.mangas.getMangaById(source.id),
                target("/target"),
                2,
                listOf(sourceChapter("/target-1", "Target 1", 1.0)),
                options = MigrationOptions(copyCategories = false),
                replace = false,
            )

            assertEquals(listOf(targetCategoryId), f.categoryIds(migrated.id))
            assertEquals(listOf(sourceCategoryId), f.categoryIds(source.id))
        }
    }

    @Test
    fun `migration persists source chapter and viewer flags on target`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source")
            f.mangas.update(MangaUpdate(id = source.id, chapterFlags = 0x35L, viewerFlags = 0x62L))

            val migrated = f.useCase.await(
                f.mangas.getMangaById(source.id),
                target("/target"),
                2,
                listOf(sourceChapter("/target-1", "Target 1", 1.0)),
                replace = false,
            )

            val savedTarget = f.mangas.getMangaById(migrated.id)
            assertEquals(0x35L, savedTarget.chapterFlags)
            assertEquals(0x62L, savedTarget.viewerFlags)
        }
    }

    @Test
    fun `copy notes true replaces target notes with source notes`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source")
            val target = f.insertManga("/target", sourceId = 2)
            f.mangas.update(MangaUpdate(id = source.id, notes = "source notes"))
            f.mangas.update(MangaUpdate(id = target.id, notes = "target notes"))

            val migrated = f.useCase.await(
                f.mangas.getMangaById(source.id),
                target("/target"),
                2,
                listOf(sourceChapter("/target-1", "Target 1", 1.0)),
                options = MigrationOptions(copyNotes = true),
                replace = false,
            )

            assertEquals("source notes", f.mangas.getMangaById(migrated.id).notes)
        }
    }

    @Test
    fun `copy notes false preserves existing target notes`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source")
            val target = f.insertManga("/target", sourceId = 2)
            f.mangas.update(MangaUpdate(id = source.id, notes = "source notes"))
            f.mangas.update(MangaUpdate(id = target.id, notes = "target notes"))

            val migrated = f.useCase.await(
                f.mangas.getMangaById(source.id),
                target("/target"),
                2,
                listOf(sourceChapter("/target-1", "Target 1", 1.0)),
                options = MigrationOptions(copyNotes = false),
                replace = false,
            )

            assertEquals("target notes", f.mangas.getMangaById(migrated.id).notes)
        }
    }

    @Test
    fun `copy migration replaces existing target date with invocation time`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source")
            val target = f.insertManga("/target", sourceId = 2)
            f.mangas.updateMembershipsAtomically(
                listOf(
                    LibraryMembershipUpdate(source.id, true, 40, emptyList()),
                    LibraryMembershipUpdate(target.id, true, 5, emptyList()),
                ),
            )
            val beforeInvocation = System.currentTimeMillis()

            val migrated = f.useCase.await(
                f.mangas.getMangaById(source.id),
                target("/target"),
                2,
                listOf(sourceChapter("/target-1", "Target 1", 1.0)),
                replace = false,
            )
            val afterInvocation = System.currentTimeMillis()

            val targetDateAdded = f.mangas.getMangaById(migrated.id).dateAdded
            assertEquals(true, targetDateAdded in beforeInvocation..afterInvocation)
        }
    }

    @Test
    fun `replace migration returns the final persisted source date and flags`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source")
            f.mangas.update(MangaUpdate(id = source.id, chapterFlags = 0x35L, viewerFlags = 0x62L))
            f.mangas.updateMembershipsAtomically(
                listOf(LibraryMembershipUpdate(source.id, true, 40, emptyList())),
            )

            val returned = f.useCase.await(
                f.mangas.getMangaById(source.id),
                target("/target"),
                2,
                listOf(sourceChapter("/target-1", "Target 1", 1.0)),
                replace = true,
            )

            val persisted = f.mangas.getMangaById(returned.id)
            assertEquals(40, persisted.dateAdded)
            assertEquals(
                listOf(persisted.dateAdded, persisted.chapterFlags, persisted.viewerFlags),
                listOf(returned.dateAdded, returned.chapterFlags, returned.viewerFlags),
            )
        }
    }

    @Test
    fun `chapter adapter preserves target read progress beyond source and unknown numbers`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", sourceId = 1)
            val target = f.insertManga("/target", sourceId = 2)
            f.chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = source.id,
                        url = "/source-2",
                        name = "Source 2",
                        chapterNumber = 2.0,
                        read = true,
                    ),
                    Chapter.create().copy(
                        mangaId = target.id,
                        url = "/target-1",
                        name = "Target 1",
                        chapterNumber = 1.0,
                        read = false,
                    ),
                    Chapter.create().copy(
                        mangaId = target.id,
                        url = "/target-3",
                        name = "Target 3",
                        chapterNumber = 3.0,
                        read = true,
                    ),
                    Chapter.create().copy(
                        mangaId = target.id,
                        url = "/target-unknown",
                        name = "Target unknown",
                        chapterNumber = -1.0,
                        read = true,
                    ),
                ),
            )

            f.useCase.await(
                source,
                target("/target"),
                2,
                listOf(
                    sourceChapter("/target-1", "Target 1", 1.0),
                    sourceChapter("/target-3", "Target 3", 3.0),
                    sourceChapter("/target-unknown", "Target unknown", -1.0),
                ),
                replace = false,
            )

            val readByUrl = f.chapters.getChapterByMangaId(target.id).associate { it.url to it.read }
            assertEquals(true, readByUrl.getValue("/target-1"))
            assertEquals(true, readByUrl.getValue("/target-3"))
            assertEquals(true, readByUrl.getValue("/target-unknown"))
        }
    }

    @Test
    fun `chapter adapter copies matched metadata and preserves unmatched target metadata`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", sourceId = 1)
            val target = f.insertManga("/target", sourceId = 2)
            f.chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = source.id,
                        url = "/source-2",
                        name = "Source 2",
                        chapterNumber = 2.0,
                        bookmark = true,
                        dateFetch = 220,
                    ),
                    Chapter.create().copy(
                        mangaId = source.id,
                        url = "/source-unknown",
                        name = "Source unknown",
                        chapterNumber = -1.0,
                        bookmark = true,
                        dateFetch = 990,
                    ),
                    Chapter.create().copy(
                        mangaId = target.id,
                        url = "/target-2",
                        name = "Target 2",
                        chapterNumber = 2.0,
                        bookmark = false,
                        dateFetch = 20,
                    ),
                    Chapter.create().copy(
                        mangaId = target.id,
                        url = "/target-3",
                        name = "Target 3",
                        chapterNumber = 3.0,
                        bookmark = true,
                        dateFetch = 330,
                    ),
                    Chapter.create().copy(
                        mangaId = target.id,
                        url = "/target-unknown",
                        name = "Target unknown",
                        chapterNumber = -1.0,
                        bookmark = false,
                        dateFetch = 440,
                    ),
                ),
            )

            f.useCase.await(
                source,
                target("/target"),
                2,
                listOf(
                    sourceChapter("/target-2", "Target 2", 2.0),
                    sourceChapter("/target-3", "Target 3", 3.0),
                    sourceChapter("/target-unknown", "Target unknown", -1.0),
                ),
                replace = false,
            )

            val metadataByUrl = f.chapters.getChapterByMangaId(target.id)
                .associate { it.url to (it.bookmark to it.dateFetch) }
            assertEquals(true to 220L, metadataByUrl.getValue("/target-2"))
            assertEquals(true to 330L, metadataByUrl.getValue("/target-3"))
            assertEquals(false to 440L, metadataByUrl.getValue("/target-unknown"))
        }
    }

    private fun target(url: String) = SManga.create().apply {
        this.url = url
        title = "Target"
    }

    private fun sourceChapter(url: String, name: String, number: Double) =
        eu.kanade.tachiyomi.source.model.SChapter.create().apply {
            this.url = url
            this.name = name
            chapter_number = number.toFloat()
        }

    private fun fixture(faultMembership: Boolean = false): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val database = Database(
            driver,
            tachiyomi.data.History.Adapter(DateColumnAdapter),
            tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val handler = JvmDatabaseHandler(database, driver)
        val mangas = MangaRepositoryImpl(
            handler,
            tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter,
        )
        if (faultMembership) {
            driver.execute(
                null,
                "CREATE TRIGGER refuse_target_category BEFORE INSERT ON mangas_categories " +
                    "WHEN NEW.manga_id != (SELECT MIN(_id) FROM mangas) " +
                    "BEGIN SELECT RAISE(ABORT,'target category refused'); END",
                0,
            )
        }
        val migrationMangas: MangaRepository = mangas
        val chapters = ChapterRepositoryImpl(handler)
        val categories = CategoryRepositoryImpl(handler)
        return Fixture(
            driver,
            handler,
            mangas,
            chapters,
            DesktopMigrateMangaUseCase(
                SaveSourceMangaForDetails(NetworkToLocalManga(migrationMangas), migrationMangas, chapters),
                migrationMangas,
            ),
        )
    }

    private class Fixture(
        private val driver: JdbcSqliteDriver,
        private val handler: JvmDatabaseHandler,
        val mangas: MangaRepositoryImpl,
        val chapters: ChapterRepositoryImpl,
        val useCase: DesktopMigrateMangaUseCase,
    ) : AutoCloseable {
        data class FileFixture(
            val useCase: DesktopMigrateMangaUseCase,
            val provider: mihon.desktop.download.DesktopDownloadProvider,
            val manager: mihon.desktop.download.DesktopDownloadManager,
            val covers: DesktopCustomCoverStore,
            val files: DesktopMigrationFiles,
        )

        fun withFiles(root: java.io.File, repository: MangaRepository = mangas): FileFixture {
            val provider = mihon.desktop.download.DesktopDownloadProvider(java.io.File(root, "downloads"))
            val manager = mihon.desktop.download.DesktopDownloadManager(provider)
            val covers = DesktopCustomCoverStore(java.io.File(root, "covers"))
            val sourceManager = io.mockk.mockk<tachiyomi.domain.source.service.SourceManager>()
            io.mockk.every { sourceManager.get(any()) } returns null
            val identities = mihon.desktop.download.DesktopDownloadIdentityResolver(
                sourceManager,
                chapters,
                tachiyomi.domain.library.service.LibraryPreferences(
                    tachiyomi.core.common.preference.InMemoryPreferenceStore(),
                ),
            )
            val getChapters = GetChaptersByMangaId(chapters)
            val files = DesktopMigrationFiles(root, provider, manager, covers, identities, getChapters)
            val migration = DesktopMigrateMangaUseCase(
                SaveSourceMangaForDetails(NetworkToLocalManga(mangas), mangas, chapters),
                repository,
                migrationFiles = { files },
            )
            return FileFixture(migration, provider, manager, covers, files)
        }

        fun rejectMigrationAcknowledgement() = driver.execute(
            null,
            """CREATE TRIGGER reject_migration_ack BEFORE DELETE ON chapter_directory_phases
            BEGIN SELECT RAISE(ABORT, 'ACK refused'); END""",
            0,
        )
        fun allowMigrationAcknowledgement() = driver.execute(null, "DROP TRIGGER reject_migration_ack", 0)
        fun clearSourceRemovalFailure() = driver.execute(null, "DROP TRIGGER reject_source_removal", 0)
        suspend fun insertManga(url: String, sourceId: Long = 1): Manga = mangas.insertNetworkManga(
            listOf(Manga.create().copy(source = sourceId, url = url, title = url)),
        ).single()

        suspend fun insertCategory(name: String): Long = handler.await {
            categoriesQueries.insert(name, 0, 0)
        }.value

        suspend fun categoryIds(mangaId: Long): List<Long> = handler.awaitList {
            categoriesQueries.getCategoriesByMangaId(mangaId) { id, _, _, _ -> id }
        }

        fun rejectSourceRemoval(mangaId: Long) {
            driver.execute(
                null,
                "CREATE TRIGGER reject_source_removal BEFORE UPDATE OF favorite ON mangas " +
                    "WHEN OLD._id=$mangaId AND NEW.favorite=0 BEGIN SELECT RAISE(ABORT,'removal refused'); END",
                0,
            )
        }

        fun failOnCategoryDelete(mangaId: Long) {
            driver.execute(
                null,
                """
                CREATE TRIGGER abort_target_category_delete
                BEFORE DELETE ON mangas_categories
                WHEN OLD.manga_id = $mangaId
                BEGIN
                    SELECT RAISE(ABORT, 'target categories must not be rewritten');
                END
                """.trimIndent(),
                0,
            )
        }

        override fun close() = driver.close()
    }
}
