package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.download.CbzCreator
import mihon.desktop.download.ChapterCleanupDiagnostic
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DownloadFileOperations
import mihon.desktop.download.DownloadIoEvent
import mihon.desktop.download.DownloadIoOperation
import mihon.desktop.download.DownloadIoProbe
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DownloadPageFileNamingPolicy
import mihon.desktop.download.DownloadStatus
import mihon.desktop.download.PartialDownloadArtifactLifecycleCoordinator
import mihon.desktop.download.PartialPageReadLeaseSource
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.partial.PartialReaderPageCandidate
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

class DesktopReaderPartialRaceIntegrationTest {

    @TempDir
    lateinit var directory: File

    @Test
    fun `generation-private staging bytes remain invisible until page publication completes`(): Unit = runBlocking {
        val provider = DesktopDownloadProvider(File(directory, "staging-visibility"))
        val identity = identity("staging")
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val chapterMoveEntered = CountDownLatch(1)
        val releaseChapterMove = CountDownLatch(1)
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            workerScope = CoroutineScope(workerParent + Dispatchers.IO),
            downloadIdentityResolver = { identity },
            fileOperations = object : DownloadFileOperations by mihon.desktop.download.DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response = imageResponse(url, JPEG)

                override fun writePage(tmp: File, bytes: ByteArray) {
                    tmp.parentFile.mkdirs()
                    tmp.outputStream().use { output ->
                        output.write(bytes, 0, 2)
                        output.flush()
                        writeEntered.countDown()
                        check(releaseWrite.await(5, TimeUnit.SECONDS))
                        output.write(bytes, 2, bytes.size - 2)
                    }
                }

                override fun renameChapter(tmpDir: File, finalDir: File): Boolean {
                    chapterMoveEntered.countDown()
                    check(releaseChapterMove.await(5, TimeUnit.SECONDS))
                    return mihon.desktop.download.DefaultDownloadFileOperations.renameChapter(tmpDir, finalDir)
                }
            },
        )
        val item = item(identity, chapterId = 8_001L)
        manager.enqueue(item)
        manager.start()
        try {
            assertTrue(writeEntered.await(5, TimeUnit.SECONDS))
            val tmpDir = provider.canonicalChapterTmpDir(identity)
            val visibleFiles = tmpDir.listFiles().orEmpty()
            assertEquals(1, visibleFiles.size)
            assertEquals(0, DownloadPageFileNamingPolicy.stagingReaderOrdinal(visibleFiles.single().name))
            assertNull(manager.committedPageCandidate(item.chapterId, identity, 0, 0))
            assertFalse(provider.isChapterDownloaded(item.sourceId, identity))

            releaseWrite.countDown()
            assertTrue(chapterMoveEntered.await(5, TimeUnit.SECONDS))
            val candidate = checkNotNull(manager.committedPageCandidate(item.chapterId, identity, 0, 0))
            assertFalse(candidate.opaqueLocation.endsWith(".tmp", ignoreCase = true))
            assertArrayEquals(JPEG, File(candidate.opaqueLocation).readBytes())
        } finally {
            releaseWrite.countDown()
            releaseChapterMove.countDown()
            withTimeout(5_000) { manager.stopAndJoin() }
            withTimeout(5_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `directory rename between probe and open uses one bounded final re-probe with no network`() = runBlocking {
        val stagingDirectory = File(directory, "bounded/Chapter_tmp").apply { mkdirs() }
        val finalDirectory = File(stagingDirectory.parentFile, "Chapter")
        val source = File(stagingDirectory, "001.jpg").apply { writeBytes(JPEG) }
        val candidate = candidate(source, generation = 17L, revision = 23L)
        val ioEvents = CopyOnWriteArrayList<DownloadIoEvent>()
        val coordinator = PartialDownloadArtifactLifecycleCoordinator()
        coordinator.registerCommittedPage(chapterId = 8_002L, candidate = candidate)
        val initialProbe = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val copyPort = DesktopReaderPartialPageFileCopyPort(
            leaseSource = coordinator,
            hooks = DesktopReaderPartialPageCopyHooks(
                afterInitialProbe = {
                    initialProbe.countDown()
                    check(releaseOpen.await(5, TimeUnit.SECONDS))
                },
            ),
            ioProbe = DownloadIoProbe { event -> ioEvents += event },
        )
        val destination = File(directory, "bounded/reader-cache/page.jpg")
        val copying = async(Dispatchers.IO) { copyPort.copy(candidate, destination) }

        assertTrue(initialProbe.await(5, TimeUnit.SECONDS))
        val token = coordinator.prepareDirectoryPublish(8_002L, 17L, stagingDirectory, finalDirectory)
        Files.move(stagingDirectory.toPath(), finalDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE)
        coordinator.commitDirectoryPublish(token)
        releaseOpen.countDown()

        assertEquals(JPEG.size.toLong(), withTimeout(5_000) { copying.await() })
        assertArrayEquals(JPEG, destination.readBytes())
        assertFalse(stagingDirectory.exists())
        assertTrue(File(finalDirectory, "001.jpg").isFile)
        assertEquals(
            listOf(
                DownloadIoOperation.PARTIAL_PAGE_PROBE,
                DownloadIoOperation.PARTIAL_PAGE_OPEN,
                DownloadIoOperation.PARTIAL_PAGE_OPEN,
                DownloadIoOperation.PARTIAL_PAGE_COPY,
            ),
            ioEvents.map { it.operation },
        )
        assertTrue(ioEvents.all { event ->
            !event.locks.queueStateLocked &&
                !event.locks.indexLocked &&
                !event.locks.coordinatorLocked &&
                !event.locks.lifecycleLocked
        })
    }

    @Test
    fun `directory rename after lease acquisition but before first probe uses one final re-probe`() = runBlocking {
        val stagingDirectory = File(directory, "pre-probe/Chapter_tmp").apply { mkdirs() }
        val finalDirectory = File(stagingDirectory.parentFile, "Chapter")
        val source = File(stagingDirectory, "001.jpg").apply { writeBytes(JPEG) }
        val candidate = candidate(source, generation = 19L, revision = 25L)
        val coordinator = PartialDownloadArtifactLifecycleCoordinator()
        assertTrue(coordinator.registerCommittedPage(chapterId = 8_009L, candidate = candidate))
        val leaseAcquired = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val leaseSource = object : PartialPageReadLeaseSource {
            override fun acquire(candidate: PartialReaderPageCandidate) = coordinator.acquire(candidate)?.also {
                leaseAcquired.countDown()
                check(releaseProbe.await(5, TimeUnit.SECONDS))
            }
        }
        val destination = File(directory, "pre-probe/reader-cache/page.jpg")
        val copying = async(Dispatchers.IO) {
            DesktopReaderPartialPageFileCopyPort(leaseSource).copy(candidate, destination)
        }

        assertTrue(leaseAcquired.await(5, TimeUnit.SECONDS))
        val token = coordinator.prepareDirectoryPublish(8_009L, 19L, stagingDirectory, finalDirectory)
        Files.move(stagingDirectory.toPath(), finalDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE)
        assertTrue(coordinator.commitDirectoryPublish(token))
        releaseProbe.countDown()

        assertEquals(JPEG.size.toLong(), withTimeout(5_000) { copying.await() })
        assertArrayEquals(JPEG, destination.readBytes())
    }

    @Test
    fun `retry directory publish hands the final path to an acquired old generation lease`() = runBlocking {
        val stagingDirectory = File(directory, "cross-generation-directory/Chapter_tmp").apply { mkdirs() }
        val finalDirectory = File(stagingDirectory.parentFile, "Chapter")
        val source = File(stagingDirectory, "001.jpg").apply { writeBytes(JPEG) }
        val oldCandidate = candidate(source, generation = 21L, revision = 27L)
        val newCandidate = candidate(source, generation = 22L, revision = 28L)
        val coordinator = PartialDownloadArtifactLifecycleCoordinator()
        assertTrue(coordinator.registerCommittedPage(chapterId = 8_010L, candidate = oldCandidate))
        val leaseAcquired = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val leaseSource = object : PartialPageReadLeaseSource {
            override fun acquire(candidate: PartialReaderPageCandidate) = coordinator.acquire(candidate)?.also {
                leaseAcquired.countDown()
                check(releaseProbe.await(5, TimeUnit.SECONDS))
            }
        }
        val destination = File(directory, "cross-generation-directory/reader-cache/page.jpg")
        val copying = async(Dispatchers.IO) {
            DesktopReaderPartialPageFileCopyPort(leaseSource).copy(oldCandidate, destination)
        }

        assertTrue(leaseAcquired.await(5, TimeUnit.SECONDS))
        coordinator.retireAttempt(8_010L, 21L)
        coordinator.removeAttempt(8_010L, 21L)
        assertTrue(coordinator.registerCommittedPage(chapterId = 8_010L, candidate = newCandidate))
        val token = coordinator.prepareDirectoryPublish(8_010L, 22L, stagingDirectory, finalDirectory)
        Files.move(stagingDirectory.toPath(), finalDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE)
        assertTrue(coordinator.commitDirectoryPublish(token))
        releaseProbe.countDown()

        assertEquals(JPEG.size.toLong(), withTimeout(5_000) { copying.await() })
        assertArrayEquals(JPEG, destination.readBytes())
        assertNull(coordinator.acquire(oldCandidate))
    }

    @Test
    fun `CBZ publication waits for an acquired Reader lease before deleting private pages`() = runBlocking {
        val stagingDirectory = File(directory, "cbz-lease/Chapter_tmp").apply { mkdirs() }
        val source = File(stagingDirectory, "001.jpg").apply { writeBytes(JPEG) }
        val target = File(stagingDirectory.parentFile, "Chapter.cbz")
        val candidate = candidate(source, generation = 29L, revision = 31L)
        val coordinator = PartialDownloadArtifactLifecycleCoordinator()
        coordinator.registerCommittedPage(chapterId = 8_003L, candidate = candidate)
        val inputOpened = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val copyPort = DesktopReaderPartialPageFileCopyPort(
            leaseSource = coordinator,
            hooks = DesktopReaderPartialPageCopyHooks(
                afterInputOpened = {
                    inputOpened.countDown()
                    check(releaseCopy.await(5, TimeUnit.SECONDS))
                },
            ),
        )
        val destination = File(directory, "cbz-lease/reader-cache/page.jpg")
        val copying = async(Dispatchers.IO) { copyPort.copy(candidate, destination) }

        assertTrue(inputOpened.await(5, TimeUnit.SECONDS))
        assertTrue(CbzCreator.create(stagingDirectory, target))
        val drained = coordinator.retireAttempt(chapterId = 8_003L, attemptGeneration = 29L)
        assertFalse(drained.isCompleted, "Cleanup must wait while the Reader owns an input handle")
        assertTrue(stagingDirectory.isDirectory)
        assertTrue(target.isFile)

        releaseCopy.countDown()
        assertEquals(JPEG.size.toLong(), withTimeout(5_000) { copying.await() })
        withTimeout(5_000) { drained.await() }
        assertTrue(stagingDirectory.deleteRecursively())
        coordinator.removeAttempt(chapterId = 8_003L, attemptGeneration = 29L)
        assertArrayEquals(JPEG, destination.readBytes())
        assertTrue(target.isFile)
    }

    @Test
    fun `real manager publishes CBZ but retains private pages until Reader copy lease closes`() = runBlocking {
        val provider = DesktopDownloadProvider(File(directory, "manager-cbz-lease"))
        val identity = identity("manager-cbz-lease")
        val stagingDirectory = provider.canonicalChapterTmpDir(identity)
        val finalDirectory = provider.canonicalChapterDownloadDir(identity)
        val target = CbzCreator.defaultOutputFile(finalDirectory)
        val coordinator = PartialDownloadArtifactLifecycleCoordinator()
        val packagerEntered = CountDownLatch(1)
        val releasePackager = CountDownLatch(1)
        val inputOpened = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val downloadRequests = AtomicInteger()
        val preferences = DesktopDownloadPreferences(InMemoryPreferenceStore()).apply { downloadAsCbz.set(true) }
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            downloadPreferences = preferences,
            workerScope = CoroutineScope(workerParent + Dispatchers.IO),
            downloadIdentityResolver = { identity },
            partialArtifactLifecycleCoordinator = coordinator,
            chapterPackager = { source, output, committed ->
                packagerEntered.countDown()
                check(releasePackager.await(5, TimeUnit.SECONDS))
                CbzCreator.create(source, output, expectedImageFiles = committed)
            },
            fileOperations = object : DownloadFileOperations by mihon.desktop.download.DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response {
                    downloadRequests.incrementAndGet()
                    return imageResponse(url, JPEG)
                }
            },
        )
        val item = item(identity, chapterId = 8_005L)
        manager.enqueue(item)
        manager.start()
        try {
            assertTrue(packagerEntered.await(5, TimeUnit.SECONDS))
            val candidate = checkNotNull(manager.committedPageCandidate(item.chapterId, identity, 0, 0))
            val destination = File(directory, "manager-cbz-lease/reader-cache/page.jpg")
            val copyPort = DesktopReaderPartialPageFileCopyPort(
                leaseSource = coordinator,
                hooks = DesktopReaderPartialPageCopyHooks(
                    afterInputOpened = {
                        inputOpened.countDown()
                        check(releaseCopy.await(5, TimeUnit.SECONDS))
                    },
                ),
            )
            val copying = async(Dispatchers.IO) { copyPort.copy(candidate, destination) }
            assertTrue(inputOpened.await(5, TimeUnit.SECONDS))

            releasePackager.countDown()
            withTimeout(5_000) {
                while (!target.isFile) delay(10)
            }
            assertTrue(stagingDirectory.isDirectory)
            assertEquals(DownloadStatus.DOWNLOADING, manager.queue.value.single().status)

            releaseCopy.countDown()
            assertEquals(JPEG.size.toLong(), withTimeout(5_000) { copying.await() })
            withTimeout(5_000) {
                while (manager.queue.value.isNotEmpty()) delay(10)
            }
            assertArrayEquals(JPEG, destination.readBytes())
            assertEquals(1, downloadRequests.get(), "Reader local copy must not issue an image request")
            assertFalse(stagingDirectory.exists())
            assertTrue(target.isFile)
            assertFalse(finalDirectory.exists(), "CBZ mode must never expose a transient final directory")
        } finally {
            releasePackager.countDown()
            releaseCopy.countDown()
            withTimeout(5_000) { manager.stopAndJoin() }
            withTimeout(5_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `retry CBZ cleanup waits for an acquired old generation lease on the reused private directory`() = runBlocking {
        val provider = DesktopDownloadProvider(File(directory, "cross-generation-cbz"))
        val identity = identity("cross-generation-cbz")
        val stagingDirectory = provider.canonicalChapterTmpDir(identity)
        val target = CbzCreator.defaultOutputFile(provider.canonicalChapterDownloadDir(identity))
        val coordinator = PartialDownloadArtifactLifecycleCoordinator()
        val packageCalls = AtomicInteger()
        val requests = AtomicInteger()
        val leaseAcquired = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val preferences = DesktopDownloadPreferences(InMemoryPreferenceStore()).apply { downloadAsCbz.set(true) }
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            downloadPreferences = preferences,
            workerScope = CoroutineScope(workerParent + Dispatchers.IO),
            retryDelay = {},
            downloadIdentityResolver = { identity },
            partialArtifactLifecycleCoordinator = coordinator,
            chapterPackager = { source, output, committed ->
                if (packageCalls.incrementAndGet() == 1) {
                    throw mihon.desktop.download.CbzPackagingException("first generation fixture failure")
                }
                CbzCreator.create(source, output, expectedImageFiles = committed)
            },
            fileOperations = object : DownloadFileOperations by mihon.desktop.download.DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response {
                    requests.incrementAndGet()
                    return imageResponse(url, JPEG)
                }
            },
        )
        val item = item(identity, chapterId = 8_011L)
        manager.enqueue(item)
        manager.start()
        try {
            withTimeout(5_000) {
                while (manager.queue.value.singleOrNull()?.status != DownloadStatus.ERROR) delay(10)
            }
            val oldCandidate = checkNotNull(manager.committedPageCandidate(item.chapterId, identity, 0, 0))
            val leaseSource = object : PartialPageReadLeaseSource {
                override fun acquire(candidate: PartialReaderPageCandidate) = coordinator.acquire(candidate)?.also {
                    leaseAcquired.countDown()
                    check(releaseProbe.await(5, TimeUnit.SECONDS))
                }
            }
            val destination = File(directory, "cross-generation-cbz/reader-cache/page.jpg")
            val copying = async(Dispatchers.IO) {
                DesktopReaderPartialPageFileCopyPort(leaseSource).copy(oldCandidate, destination)
            }
            assertTrue(leaseAcquired.await(5, TimeUnit.SECONDS))

            assertTrue(manager.retryItem(item.chapterId))
            withTimeout(5_000) {
                while (!target.isFile) delay(10)
            }
            assertTrue(stagingDirectory.isDirectory, "New generation cleanup must wait for the old lease")

            releaseProbe.countDown()
            assertEquals(JPEG.size.toLong(), withTimeout(5_000) { copying.await() })
            withTimeout(5_000) {
                while (manager.queue.value.isNotEmpty()) delay(10)
            }
            assertArrayEquals(JPEG, destination.readBytes())
            assertFalse(stagingDirectory.exists())
            assertTrue(target.isFile)
            assertEquals(1, requests.get(), "Retry and Reader must reuse the committed local page")
        } finally {
            releaseProbe.countDown()
            withTimeout(5_000) { manager.stopAndJoin() }
            withTimeout(5_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `partial descriptor acquired only after directory completion still reads the final page locally`() = runBlocking {
        val provider = DesktopDownloadProvider(File(directory, "late-directory-descriptor"))
        val identity = identity("late-directory-descriptor")
        val moveEntered = CountDownLatch(1)
        val releaseMove = CountDownLatch(1)
        val coordinator = PartialDownloadArtifactLifecycleCoordinator()
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            workerScope = CoroutineScope(workerParent + Dispatchers.IO),
            downloadIdentityResolver = { identity },
            partialArtifactLifecycleCoordinator = coordinator,
            fileOperations = object : DownloadFileOperations by mihon.desktop.download.DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response = imageResponse(url, JPEG)

                override fun renameChapter(tmpDir: File, finalDir: File): Boolean {
                    moveEntered.countDown()
                    check(releaseMove.await(5, TimeUnit.SECONDS))
                    return mihon.desktop.download.DefaultDownloadFileOperations.renameChapter(tmpDir, finalDir)
                }
            },
        )
        val item = item(identity, chapterId = 8_012L)
        manager.enqueue(item)
        manager.start()
        try {
            assertTrue(moveEntered.await(5, TimeUnit.SECONDS))
            val descriptor = checkNotNull(manager.committedPageCandidate(item.chapterId, identity, 0, 0))
            releaseMove.countDown()
            withTimeout(5_000) {
                while (manager.queue.value.isNotEmpty()) delay(10)
            }

            val destination = File(directory, "late-directory-descriptor/reader-cache/page.jpg")
            assertEquals(
                JPEG.size.toLong(),
                DesktopReaderPartialPageFileCopyPort(coordinator).copy(descriptor, destination),
            )
            assertArrayEquals(JPEG, destination.readBytes())
        } finally {
            releaseMove.countDown()
            withTimeout(5_000) { manager.stopAndJoin() }
            withTimeout(5_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `partial descriptor acquired only after CBZ completion reads the published entry locally`() = runBlocking {
        val provider = DesktopDownloadProvider(File(directory, "late-cbz-descriptor"))
        val identity = identity("late-cbz-descriptor")
        val packagerEntered = CountDownLatch(1)
        val releasePackager = CountDownLatch(1)
        val coordinator = PartialDownloadArtifactLifecycleCoordinator()
        val preferences = DesktopDownloadPreferences(InMemoryPreferenceStore()).apply { downloadAsCbz.set(true) }
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            downloadPreferences = preferences,
            workerScope = CoroutineScope(workerParent + Dispatchers.IO),
            downloadIdentityResolver = { identity },
            partialArtifactLifecycleCoordinator = coordinator,
            chapterPackager = { source, output, committed ->
                packagerEntered.countDown()
                check(releasePackager.await(5, TimeUnit.SECONDS))
                CbzCreator.create(source, output, expectedImageFiles = committed)
            },
            fileOperations = object : DownloadFileOperations by mihon.desktop.download.DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response = imageResponse(url, JPEG)
            },
        )
        val item = item(identity, chapterId = 8_013L)
        manager.enqueue(item)
        manager.start()
        try {
            assertTrue(packagerEntered.await(5, TimeUnit.SECONDS))
            val descriptor = checkNotNull(manager.committedPageCandidate(item.chapterId, identity, 0, 0))
            releasePackager.countDown()
            withTimeout(5_000) {
                while (manager.queue.value.isNotEmpty()) delay(10)
            }
            assertFalse(provider.canonicalChapterTmpDir(identity).exists())
            assertTrue(CbzCreator.defaultOutputFile(provider.canonicalChapterDownloadDir(identity)).isFile)

            val destination = File(directory, "late-cbz-descriptor/reader-cache/page.jpg")
            assertEquals(
                JPEG.size.toLong(),
                DesktopReaderPartialPageFileCopyPort(coordinator).copy(descriptor, destination),
            )
            assertArrayEquals(JPEG, destination.readBytes())
        } finally {
            releasePackager.countDown()
            withTimeout(5_000) { manager.stopAndJoin() }
            withTimeout(5_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `CBZ packaging excludes and removes image files outside the committed page set`() = runBlocking {
        val provider = DesktopDownloadProvider(File(directory, "cbz-committed-authority"))
        val identity = identity("cbz-committed-authority")
        val preferences = DesktopDownloadPreferences(InMemoryPreferenceStore()).apply { downloadAsCbz.set(true) }
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            downloadPreferences = preferences,
            workerScope = CoroutineScope(workerParent + Dispatchers.IO),
            downloadIdentityResolver = { identity },
            fileOperations = object : DownloadFileOperations by mihon.desktop.download.DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response = imageResponse(url, JPEG)
            },
        )
        val item = item(identity, chapterId = 8_014L)
        manager.enqueue(item)
        val privateDirectory = provider.canonicalChapterTmpDir(identity).apply { mkdirs() }
        File(privateDirectory, "cover.jpg").writeBytes(JPEG)
        manager.start()
        try {
            withTimeout(5_000) {
                while (manager.queue.value.isNotEmpty()) delay(10)
            }
            val target = CbzCreator.defaultOutputFile(provider.canonicalChapterDownloadDir(identity))
            ZipFile(target).use { archive ->
                assertEquals(listOf("001.jpg"), archive.entries().asSequence().map { it.name }.toList())
            }
            assertFalse(privateDirectory.exists())
        } finally {
            withTimeout(5_000) { manager.stopAndJoin() }
            withTimeout(5_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `valid CBZ survives injected private directory cleanup failure with a diagnostic`() = runBlocking {
        val provider = DesktopDownloadProvider(File(directory, "cbz-cleanup-diagnostic"))
        val identity = identity("cleanup")
        val stagingDirectory = provider.canonicalChapterTmpDir(identity)
        val finalDirectory = provider.canonicalChapterDownloadDir(identity)
        val target = CbzCreator.defaultOutputFile(finalDirectory)
        val diagnostics = CopyOnWriteArrayList<ChapterCleanupDiagnostic>()
        val ioEvents = CopyOnWriteArrayList<DownloadIoEvent>()
        val preferences = DesktopDownloadPreferences(InMemoryPreferenceStore()).apply { downloadAsCbz.set(true) }
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            downloadPreferences = preferences,
            workerScope = CoroutineScope(workerParent + Dispatchers.IO),
            downloadIdentityResolver = { identity },
            artifactCleaner = { artifact ->
                if (artifact.absoluteFile == stagingDirectory.absoluteFile) false else artifact.deleteRecursively()
            },
            chapterCleanupDiagnosticObserver = diagnostics::add,
            ioProbe = DownloadIoProbe { event -> ioEvents += event },
            fileOperations = object : DownloadFileOperations by mihon.desktop.download.DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response = imageResponse(url, JPEG)
            },
        )
        manager.enqueue(item(identity, chapterId = 8_004L))
        manager.start()
        try {
            withTimeout(5_000) {
                while (manager.queue.value.isNotEmpty()) delay(10)
            }

            assertTrue(target.isFile)
            assertTrue(stagingDirectory.isDirectory)
            assertArrayEquals(JPEG, File(stagingDirectory, "001.jpg").readBytes())
            assertEquals(1, diagnostics.size)
            assertEquals(stagingDirectory.absoluteFile, diagnostics.single().artifact.absoluteFile)
            assertTrue(
                setOf(
                    DownloadIoOperation.CBZ_PACKAGE,
                    DownloadIoOperation.CBZ_VALIDATE,
                    DownloadIoOperation.CBZ_PUBLISH,
                    DownloadIoOperation.CHAPTER_CLEANUP,
                ).all { operation -> ioEvents.any { it.operation == operation } },
            )
            assertTrue(ioEvents.all { event ->
                !event.locks.queueStateLocked &&
                    !event.locks.indexLocked &&
                    !event.locks.coordinatorLocked &&
                    !event.locks.lifecycleLocked
            })
        } finally {
            withTimeout(5_000) { manager.stopAndJoin() }
            withTimeout(5_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `CBZ atomic move failure keeps retryable private pages readable through the shared lease`() = runBlocking {
        val provider = DesktopDownloadProvider(File(directory, "cbz-atomic-manager-failure"))
        val identity = identity("cbz-atomic-manager-failure")
        val coordinator = PartialDownloadArtifactLifecycleCoordinator()
        val requests = AtomicInteger()
        val preferences = DesktopDownloadPreferences(InMemoryPreferenceStore()).apply { downloadAsCbz.set(true) }
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            downloadPreferences = preferences,
            workerScope = CoroutineScope(workerParent + Dispatchers.IO),
            retryDelay = {},
            downloadIdentityResolver = { identity },
            partialArtifactLifecycleCoordinator = coordinator,
            chapterPackager = { source, output, committed ->
                CbzCreator.create(
                    sourceDir = source,
                    outputFile = output,
                    expectedImageFiles = committed,
                    atomicMove = { from, to ->
                        throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "fixture")
                    },
                )
            },
            fileOperations = object : DownloadFileOperations by mihon.desktop.download.DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response {
                    requests.incrementAndGet()
                    return imageResponse(url, JPEG)
                }
            },
        )
        val item = item(identity, chapterId = 8_008L)
        manager.enqueue(item)
        manager.start()
        try {
            withTimeout(5_000) {
                while (manager.queue.value.singleOrNull()?.status != DownloadStatus.ERROR) delay(10)
            }
            val candidate = checkNotNull(manager.committedPageCandidate(item.chapterId, identity, 0, 0))
            val destination = File(directory, "cbz-atomic-manager-failure/reader-cache/page.jpg")

            assertEquals(
                JPEG.size.toLong(),
                DesktopReaderPartialPageFileCopyPort(coordinator).copy(candidate, destination),
            )
            assertArrayEquals(JPEG, destination.readBytes())
            assertTrue(provider.canonicalChapterTmpDir(identity).isDirectory)
            assertFalse(CbzCreator.defaultOutputFile(provider.canonicalChapterDownloadDir(identity)).exists())
            assertEquals(1, requests.get())
        } finally {
            withTimeout(5_000) { manager.stopAndJoin() }
            withTimeout(5_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `cancel and same id reenqueue retire old Reader revision before new generation publishes`() = runBlocking {
        val provider = DesktopDownloadProvider(File(directory, "stale-reader-generation"))
        val identity = identity("stale-reader-generation")
        val coordinator = PartialDownloadArtifactLifecycleCoordinator()
        val firstMoveEntered = CountDownLatch(1)
        val releaseFirstMove = CountDownLatch(1)
        val secondMoveEntered = CountDownLatch(1)
        val releaseSecondMove = CountDownLatch(1)
        val moveCalls = AtomicInteger()
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            workerScope = CoroutineScope(workerParent + Dispatchers.IO),
            downloadIdentityResolver = { identity },
            partialArtifactLifecycleCoordinator = coordinator,
            fileOperations = object : DownloadFileOperations by mihon.desktop.download.DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response = imageResponse(url, JPEG)

                override fun renameChapter(tmpDir: File, finalDir: File): Boolean {
                    when (moveCalls.incrementAndGet()) {
                        1 -> {
                            firstMoveEntered.countDown()
                            check(releaseFirstMove.await(5, TimeUnit.SECONDS))
                        }
                        2 -> {
                            secondMoveEntered.countDown()
                            check(releaseSecondMove.await(5, TimeUnit.SECONDS))
                        }
                    }
                    return mihon.desktop.download.DefaultDownloadFileOperations.renameChapter(tmpDir, finalDir)
                }
            },
        )
        val first = item(identity, chapterId = 8_006L)
        manager.enqueue(first)
        manager.start()
        try {
            assertTrue(firstMoveEntered.await(5, TimeUnit.SECONDS))
            val oldCandidate = checkNotNull(manager.committedPageCandidate(first.chapterId, identity, 0, 0))
            assertNotNullLease(coordinator, oldCandidate)

            assertTrue(manager.cancel(first.chapterId))
            assertNull(coordinator.acquire(oldCandidate), "Cancellation must retire the old Reader revision immediately")
            manager.enqueue(first.copy(pageUrls = listOf("https://fixture.invalid/replacement.jpg")))
            releaseFirstMove.countDown()

            assertTrue(secondMoveEntered.await(5, TimeUnit.SECONDS))
            val newCandidate = checkNotNull(manager.committedPageCandidate(first.chapterId, identity, 0, 0))
            assertTrue(newCandidate.attemptGeneration > oldCandidate.attemptGeneration)
            assertNull(coordinator.acquire(oldCandidate))
            assertNotNullLease(coordinator, newCandidate)

            releaseSecondMove.countDown()
            withTimeout(5_000) {
                while (manager.queue.value.isNotEmpty()) delay(10)
            }
            assertArrayEquals(JPEG, File(provider.canonicalChapterDownloadDir(identity), "001.jpg").readBytes())
        } finally {
            releaseFirstMove.countDown()
            releaseSecondMove.countDown()
            withTimeout(5_000) { manager.stopAndJoin() }
            withTimeout(5_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `Reader cancellation removes only its cache staging and preserves Downloader page ownership`() = runBlocking {
        val stagingDirectory = File(directory, "reader-cancel/Chapter_tmp").apply { mkdirs() }
        val source = File(stagingDirectory, "001.jpg").apply { writeBytes(JPEG) }
        val candidate = candidate(source, generation = 41L, revision = 43L)
        val coordinator = PartialDownloadArtifactLifecycleCoordinator()
        coordinator.registerCommittedPage(chapterId = 8_007L, candidate = candidate)
        val destination = File(directory, "reader-cancel/reader-cache/001.jpg")
        val copyPort = DesktopReaderPartialPageFileCopyPort(
            leaseSource = coordinator,
            hooks = DesktopReaderPartialPageCopyHooks(
                afterInputOpened = { throw CancellationException("reader closed") },
            ),
        )

        val failure = runCatching { copyPort.copy(candidate, destination) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertArrayEquals(JPEG, source.readBytes())
        assertFalse(destination.exists())
        assertEquals(0, coordinator.activeLeaseCount(8_007L, 41L))
    }

    private fun identity(suffix: String) = DownloadChapterIdentity(
        sourceDisplayName = "Race Source $suffix",
        mangaTitle = "Race Manga $suffix",
        chapterName = "Chapter $suffix",
        scanlator = null,
        chapterUrl = "/chapter/$suffix",
        disallowNonAsciiFilenames = false,
    )

    private fun item(identity: DownloadChapterIdentity, chapterId: Long) = DownloadItem(
        sourceId = 42L,
        mangaTitle = identity.mangaTitle,
        chapterName = identity.chapterName,
        chapterId = chapterId,
        chapterUrl = identity.chapterUrl,
        pageUrls = listOf("https://fixture.invalid/001.jpg"),
    )

    private fun candidate(file: File, generation: Long, revision: Long) = PartialReaderPageCandidate(
        attemptGeneration = generation,
        readerOrdinal = 0,
        sourcePageIndex = 0,
        opaqueLocation = file.absolutePath,
        committedRevision = revision,
    )

    private fun assertNotNullLease(
        coordinator: PartialDownloadArtifactLifecycleCoordinator,
        candidate: PartialReaderPageCandidate,
    ) {
        val lease = checkNotNull(coordinator.acquire(candidate))
        lease.close()
    }

    private fun imageResponse(url: String, bytes: ByteArray): Response = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(bytes.toResponseBody())
        .build()

    private companion object {
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)
    }
}
