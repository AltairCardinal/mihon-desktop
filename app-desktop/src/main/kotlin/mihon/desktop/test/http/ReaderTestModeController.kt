package mihon.desktop.test.http

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import mihon.desktop.download.CbzCreator
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DownloadStatus
import mihon.desktop.download.DownloadIoEvent
import mihon.desktop.download.DownloadIoOperation
import mihon.desktop.download.DownloadIoProbe
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoProbe

@Serializable
data class ReaderIoTestEvent(
    val type: String,
    val monotonicNanos: Long,
    val chapterId: Long,
    val pageIndex: Int?,
    val generation: Long,
    val purpose: String,
)

enum class ReaderTestFixtureKind {
    DOWNLOADED_DIRECTORY,
    CBZ,
}

data class ReaderTestFixture(
    val localChapterPath: String,
    val pageCount: Int,
)

enum class ReaderTestFixtureSource(val wireName: String) {
    DOWNLOADED_DIRECTORY("downloaded_directory"),
    DOWNLOADED_CBZ("downloaded_cbz"),
    LOCAL_ARCHIVE("local_archive"),
    ONLINE("online"),
    PARTIAL_DOWNLOAD("partial_download"),
    ;

    companion object {
        fun fromWireName(value: String): ReaderTestFixtureSource? = when {
            value.equals("directory", ignoreCase = true) -> DOWNLOADED_DIRECTORY
            value.equals("cbz", ignoreCase = true) -> LOCAL_ARCHIVE
            else -> entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
        }
    }
}

enum class ReaderTestImageFormat(val wireName: String, val extension: String) {
    JPEG("JPEG", "jpg"),
    ;

    companion object {
        fun fromWireName(value: String): ReaderTestImageFormat? = entries.firstOrNull {
            it.wireName.equals(value, ignoreCase = true)
        }
    }
}

data class ReaderTestFixtureSpec(
    val source: ReaderTestFixtureSource,
    val pageCount: Int,
    val width: Int,
    val height: Int,
    val format: ReaderTestImageFormat,
    val partialPageCount: Int? = null,
    val offline: Boolean = false,
) {
    init {
        require(pageCount in 1..MAX_PAGE_COUNT) { "pageCount must be between 1 and $MAX_PAGE_COUNT" }
        require(width in 1..MAX_WIDTH) { "width must be between 1 and $MAX_WIDTH" }
        require(height in 1..MAX_HEIGHT) { "height must be between 1 and $MAX_HEIGHT" }
        if (source == ReaderTestFixtureSource.PARTIAL_DOWNLOAD) {
            require(resolvedPartialPageCount() in 1 until pageCount) {
                "partialPageCount must leave at least one local and one missing page"
            }
        } else {
            require(partialPageCount == null) { "partialPageCount is only valid for partial_download" }
            require(!offline) { "offline is only valid for partial_download" }
        }
    }

    fun resolvedPartialPageCount(): Int = partialPageCount ?: minOf(5, pageCount - 1)

    companion object {
        const val MAX_PAGE_COUNT = 180
        const val MAX_WIDTH = 2400
        const val MAX_HEIGHT = 3500
    }
}

data class ReaderTestFixtureDescriptor(
    val spec: ReaderTestFixtureSpec,
    val token: String,
    val sourceId: Long,
    val mangaTitle: String,
    val chapterId: Long,
    val chapterTitle: String,
    val chapterUrl: String,
    val localChapterPath: String?,
    val partialAttemptGeneration: Long? = null,
)

class ReaderTestModeController(
    configuredDownloadProvider: DesktopDownloadProvider? = null,
    private val downloadManager: DesktopDownloadManager? = null,
    private val baseUrl: String = "http://127.0.0.1:8080",
) : ReaderIoProbe, DownloadIoProbe, AutoCloseable {
    private enum class PartialFixtureStage {
        PREPARING,
        ACTIVE,
    }
    private data class ScenarioEvent(val scenario: Long, val event: ReaderIoTestEvent)
    private data class ScenarioDownloadEvent(val scenario: Long, val event: DownloadIoEvent)
    private data class RouteCounterSnapshot(
        val scenario: Long,
        val sourcePageListCalls: Int,
        val onlineImageRequests: Int,
    )
    private data class ImageKey(val width: Int, val height: Int, val format: ReaderTestImageFormat)
    private data class CreatedDownloadArtifact(
        val chapterDirectory: File,
        val cbz: File?,
        val mangaDirectoryCreated: Boolean,
        val sourceDirectoryCreated: Boolean,
    )

    private val events = CopyOnWriteArrayList<ScenarioEvent>()
    private val downloadEvents = CopyOnWriteArrayList<ScenarioDownloadEvent>()
    private val scenario = AtomicLong(0L)
    private val fixtureRoot = Files.createTempDirectory("mihon-reader-test-mode-").toFile()
    private val downloadProvider = configuredDownloadProvider ?: DesktopDownloadProvider(fixtureRoot.resolve("downloads"))
    private val imageBytes = ConcurrentHashMap<ImageKey, ByteArray>()
    private val currentFixture = AtomicReference<ReaderTestFixtureDescriptor?>()
    private val sourcePageListCalls = AtomicInteger()
    private val onlineImageRequests = AtomicInteger()
    private val onlineImageRequestsByPage = ConcurrentHashMap<Int, AtomicInteger>()
    private val scenarioCurrentPageIndex = AtomicInteger()
    private val firstPageRouteCounters = AtomicReference<RouteCounterSnapshot?>()
    private val scenarioStateLock = Any()
    private val createdDownloadArtifacts = CopyOnWriteArrayList<CreatedDownloadArtifact>()
    private val partialFixtureStage = AtomicReference<PartialFixtureStage?>()
    private val ownedPartialDownloads = ConcurrentHashMap<Long, DownloadChapterIdentity>()

    val onlineSource = ReaderTestModeOnlineSource(this)

    override val enabled: Boolean = true

    override fun record(event: ReaderIoEvent) {
        record(scenario.get(), event)
    }

    private fun record(expectedScenario: Long, event: ReaderIoEvent) {
        synchronized(scenarioStateLock) {
            if (
                event.type == ReaderIoEventType.FIRST_PAGE_PRESENTED &&
                scenario.get() == expectedScenario &&
                firstPageRouteCounters.get()?.scenario != expectedScenario
            ) {
                firstPageRouteCounters.set(
                    RouteCounterSnapshot(
                        scenario = expectedScenario,
                        sourcePageListCalls = sourcePageListCalls.get(),
                        onlineImageRequests = onlineImageRequests.get(),
                    ),
                )
            }
            events += ScenarioEvent(
                scenario = expectedScenario,
                event = ReaderIoTestEvent(
                    type = event.type.name,
                    monotonicNanos = event.monotonicNanos,
                    chapterId = event.chapterId.value,
                    pageIndex = event.pageId?.sourcePageIndex,
                    generation = event.generation,
                    purpose = event.purpose.name,
                ),
            )
        }
    }

    override fun onIo(event: DownloadIoEvent) {
        downloadEvents += ScenarioDownloadEvent(scenario.get(), event)
    }

    fun downloadIoSnapshot(): List<DownloadIoEvent> {
        val currentScenario = scenario.get()
        return downloadEvents.filter { it.scenario == currentScenario }.map(ScenarioDownloadEvent::event)
    }

    private fun currentPageDownloadIoSnapshot(): List<DownloadIoEvent> {
        val currentPage = scenarioCurrentPageIndex.get()
        return downloadIoSnapshot().filter { it.page?.readerOrdinal == currentPage }
    }

    fun currentPageIndex(): Int = scenarioCurrentPageIndex.get()

    fun partialPageProbeCount(): Int =
        currentPageDownloadIoSnapshot().count { it.operation == DownloadIoOperation.PARTIAL_PAGE_PROBE }

    fun partialPageOpenCount(): Int =
        currentPageDownloadIoSnapshot().count { it.operation == DownloadIoOperation.PARTIAL_PAGE_OPEN }

    fun partialPageCopyCount(): Int =
        currentPageDownloadIoSnapshot().count { it.operation == DownloadIoOperation.PARTIAL_PAGE_COPY }

    fun scenarioPartialPageCopyCount(): Int =
        downloadIoSnapshot().count { it.operation == DownloadIoOperation.PARTIAL_PAGE_COPY }

    fun partialRoute(): String? = currentFixture.get()?.spec?.source?.wireName

    fun partialSnapshotGeneration(): Long? = currentFixture.get()?.partialAttemptGeneration

    fun partialLocalHitCount(): Int = partialPageCopyCount()

    fun partialNetworkFallbackCount(): Int = currentFixture.get()
        ?.takeIf { it.spec.source == ReaderTestFixtureSource.PARTIAL_DOWNLOAD }
        ?.let { onlineImageRequestsByPage[scenarioCurrentPageIndex.get()]?.get() ?: 0 }
        ?: 0

    fun rawOnlineImageRequestCount(): Int = currentFixture.get()
        ?.takeIf { it.spec.source == ReaderTestFixtureSource.PARTIAL_DOWNLOAD }
        ?.let { onlineImageRequestsByPage[scenarioCurrentPageIndex.get()]?.get() ?: 0 }
        ?: onlineImageRequests.get()

    fun scenarioOnlineImageRequestCount(): Int = onlineImageRequests.get()

    fun downloadIoLockViolationCount(): Int = downloadIoSnapshot().count { event ->
        with(event.locks) { queueStateLocked || indexLocked || coordinatorLocked || lifecycleLocked }
    }

    fun snapshot(): List<ReaderIoTestEvent> {
        val currentScenario = scenario.get()
        return events.filter { it.scenario == currentScenario }.map(ScenarioEvent::event)
    }

    fun beginScenario(currentPageIndex: Int = 0) {
        require(currentPageIndex >= 0) { "currentPageIndex must be non-negative" }
        synchronized(scenarioStateLock) {
            scenario.incrementAndGet()
            events.clear()
            downloadEvents.clear()
            sourcePageListCalls.set(0)
            onlineImageRequests.set(0)
            onlineImageRequestsByPage.clear()
            scenarioCurrentPageIndex.set(currentPageIndex)
            firstPageRouteCounters.set(null)
        }
    }

    fun bindScenario(): ReaderIoProbe {
        val expected = scenario.get()
        return ReaderIoProbe { event -> record(expected, event) }
    }

    fun clearEvents() = beginScenario()

    fun fixtureDescriptor(): ReaderTestFixtureDescriptor? = currentFixture.get()

    fun clearFixtureDescriptor() {
        currentFixture.getAndSet(null)
            ?.takeIf { it.spec.source == ReaderTestFixtureSource.PARTIAL_DOWNLOAD }
            ?.let { fixture ->
                cancelOwnedPartial(fixture.chapterId, fixture.identity())
            }
        partialFixtureStage.set(null)
    }

    private fun cancelOwnedPartial(chapterId: Long, identity: DownloadChapterIdentity) {
        if (!ownedPartialDownloads.remove(chapterId, identity)) return
        val manager = downloadManager ?: return
        val queuedIdentity = manager.queue.value.firstOrNull { it.chapterId == chapterId }?.downloadIdentity
        if (queuedIdentity == identity) {
            manager.cancel(chapterId)
        }
    }

    fun sourcePageListCallCount(): Int = synchronized(scenarioStateLock) {
        firstPageRouteCounters.get()
            ?.takeIf { it.scenario == scenario.get() }
            ?.sourcePageListCalls
            ?: sourcePageListCalls.get()
    }

    fun onlineImageRequestCount(): Int = synchronized(scenarioStateLock) {
        firstPageRouteCounters.get()
            ?.takeIf { it.scenario == scenario.get() }
            ?.onlineImageRequests
            ?: onlineImageRequests.get()
    }

    fun prepareFixture(
        spec: ReaderTestFixtureSpec,
        mangaId: Long,
        chapterId: Long,
        chapterTitle: String,
    ): ReaderTestFixtureDescriptor {
        require(spec.source != ReaderTestFixtureSource.PARTIAL_DOWNLOAD) {
            "partial_download must be prepared through prepareFixtureAwait"
        }
        clearFixtureDescriptor()
        return prepareFixtureDescriptor(spec, mangaId, chapterId, chapterTitle)
    }

    suspend fun prepareFixtureAwait(
        spec: ReaderTestFixtureSpec,
        mangaId: Long,
        chapterId: Long,
        chapterTitle: String,
    ): ReaderTestFixtureDescriptor {
        if (spec.source != ReaderTestFixtureSource.PARTIAL_DOWNLOAD) {
            return prepareFixture(spec, mangaId, chapterId, chapterTitle)
        }
        val manager = checkNotNull(downloadManager) { "partial_download requires the production download manager" }
        clearFixtureDescriptor()
        check(manager.queue.value.none { it.chapterId == chapterId }) {
            "partial_download chapterId $chapterId is already owned by another download"
        }
        partialFixtureStage.set(PartialFixtureStage.PREPARING)
        val prepared = prepareFixtureDescriptor(spec, mangaId, chapterId, chapterTitle)
        val identity = prepared.identity()
        manager.enqueue(
            DownloadItem(
                sourceId = prepared.sourceId,
                mangaTitle = prepared.mangaTitle,
                chapterName = prepared.chapterTitle,
                chapterId = prepared.chapterId,
                mangaId = mangaId,
                chapterUrl = prepared.chapterUrl,
                downloadIdentity = identity,
            ),
        )
        val enqueuedIdentity = manager.queue.value.firstOrNull { it.chapterId == chapterId }?.downloadIdentity
        if (enqueuedIdentity != identity) {
            currentFixture.compareAndSet(prepared, null)
            partialFixtureStage.set(null)
            error("partial_download chapterId $chapterId could not acquire fixture ownership")
        }
        check(ownedPartialDownloads.putIfAbsent(chapterId, identity) == null) {
            "partial_download chapterId $chapterId already has a fixture owner"
        }
        val snapshot = try {
            withTimeout(PARTIAL_FIXTURE_TIMEOUT_MS) {
                while (true) {
                    val currentSnapshot = manager.snapshot(chapterId, identity)
                    val status = manager.queue.value.firstOrNull { it.chapterId == chapterId }?.status
                    if (
                        status == DownloadStatus.ERROR &&
                        currentSnapshot?.committedPages?.size == spec.resolvedPartialPageCount()
                    ) {
                        return@withTimeout currentSnapshot
                    }
                    delay(10)
                }
                error("unreachable")
            }
        } catch (error: Exception) {
            cancelOwnedPartial(chapterId, identity)
            currentFixture.compareAndSet(prepared, null)
            partialFixtureStage.set(null)
            throw error
        }
        partialFixtureStage.set(PartialFixtureStage.ACTIVE)
        return prepared.copy(partialAttemptGeneration = snapshot.attemptGeneration).also(currentFixture::set)
    }

    private fun prepareFixtureDescriptor(
        spec: ReaderTestFixtureSpec,
        mangaId: Long,
        chapterId: Long,
        chapterTitle: String,
    ): ReaderTestFixtureDescriptor {
        val token = UUID.randomUUID().toString()
        val mangaTitle = "Mihon Test Mode $mangaId $token"
        val stableChapterTitle = chapterTitle.ifBlank { "Chapter $chapterId" }
        val chapterUrl = "/mihon-test-mode-reader/$token"
        val identity = DownloadChapterIdentity(
            sourceDisplayName = READER_TEST_SOURCE_NAME,
            mangaTitle = mangaTitle,
            chapterName = stableChapterTitle,
            scanlator = null,
            chapterUrl = chapterUrl,
            disallowNonAsciiFilenames = false,
        )
        val localChapterPath = when (spec.source) {
            ReaderTestFixtureSource.DOWNLOADED_DIRECTORY -> {
                createDownloadedDirectory(identity, spec)
                null
            }
            ReaderTestFixtureSource.DOWNLOADED_CBZ -> {
                createDownloadedCbz(identity, spec)
                null
            }
            ReaderTestFixtureSource.LOCAL_ARCHIVE -> createLocalArchive(token, spec).absolutePath
            ReaderTestFixtureSource.ONLINE -> null
            ReaderTestFixtureSource.PARTIAL_DOWNLOAD -> null
        }
        return ReaderTestFixtureDescriptor(
            spec = spec,
            token = token,
            sourceId = READER_TEST_SOURCE_ID,
            mangaTitle = mangaTitle,
            chapterId = chapterId,
            chapterTitle = stableChapterTitle,
            chapterUrl = chapterUrl,
            localChapterPath = localChapterPath,
        ).also(currentFixture::set)
    }

    /** Legacy unit-test helper. Product Test Mode uses [prepareFixture]. */
    fun createFixture(
        kind: ReaderTestFixtureKind = ReaderTestFixtureKind.DOWNLOADED_DIRECTORY,
        pageCount: Int = 3,
    ): ReaderTestFixture {
        require(pageCount > 0) { "pageCount must be positive" }
        val spec = ReaderTestFixtureSpec(
            source = ReaderTestFixtureSource.LOCAL_ARCHIVE,
            pageCount = pageCount,
            width = 16,
            height = 24,
            format = ReaderTestImageFormat.JPEG,
        )
        val fixtureDirectory = fixtureRoot.resolve("legacy-${UUID.randomUUID()}")
        writePages(fixtureDirectory, spec)
        return when (kind) {
            ReaderTestFixtureKind.DOWNLOADED_DIRECTORY -> ReaderTestFixture(
                localChapterPath = fixtureDirectory.absolutePath,
                pageCount = pageCount,
            )
            ReaderTestFixtureKind.CBZ -> {
                val archive = fixtureRoot.resolve("legacy-${UUID.randomUUID()}.cbz")
                check(CbzCreator.create(fixtureDirectory, archive)) { "Unable to create legacy reader CBZ fixture" }
                fixtureDirectory.deleteRecursively()
                ReaderTestFixture(archive.absolutePath, pageCount)
            }
        }
    }

    internal fun onlinePageUrls(chapterUrl: String): List<String> {
        val fixture = currentFixture.get()
            ?.takeIf {
                it.spec.source in setOf(ReaderTestFixtureSource.ONLINE, ReaderTestFixtureSource.PARTIAL_DOWNLOAD) &&
                    it.chapterUrl == chapterUrl
            }
            ?: error("Online reader fixture is stale or unavailable")
        sourcePageListCalls.incrementAndGet()
        return (0 until fixture.spec.pageCount).map { index ->
            if (
                fixture.spec.source == ReaderTestFixtureSource.PARTIAL_DOWNLOAD &&
                index >= fixture.spec.resolvedPartialPageCount()
            ) {
                ""
            } else {
                fixture.imageUrl(index)
            }
        }
    }

    internal fun resolveOnlineImageUrl(pageUrl: String): String? {
        val fixture = currentFixture.get()
            ?.takeIf { it.spec.source == ReaderTestFixtureSource.PARTIAL_DOWNLOAD }
            ?: return null
        val pageIndex = pageUrl.substringAfterLast('/').toIntOrNull() ?: return null
        if (pageIndex !in 0 until fixture.spec.pageCount) return null
        if (
            partialFixtureStage.get() == PartialFixtureStage.PREPARING &&
            pageIndex >= fixture.spec.resolvedPartialPageCount()
        ) {
            throw java.io.IOException("Reader Test Mode partial boundary reached")
        }
        return fixture.imageUrl(pageIndex)
    }

    fun onlineImage(token: String, pageIndex: Int): ByteArray? {
        val fixture = currentFixture.get()
            ?.takeIf {
                it.spec.source in setOf(ReaderTestFixtureSource.ONLINE, ReaderTestFixtureSource.PARTIAL_DOWNLOAD) &&
                    it.token == token
            }
            ?: return null
        if (pageIndex !in 0 until fixture.spec.pageCount) return null
        onlineImageRequests.incrementAndGet()
        onlineImageRequestsByPage.computeIfAbsent(pageIndex) { AtomicInteger() }.incrementAndGet()
        if (fixture.spec.offline && partialFixtureStage.get() == PartialFixtureStage.ACTIVE) return null
        return imageBytes(fixture.spec)
    }

    private fun ReaderTestFixtureDescriptor.imageUrl(pageIndex: Int): String =
        "${baseUrl.trimEnd('/')}/test/reader/fixture-content/$token/$pageIndex.${spec.format.extension}"

    private fun ReaderTestFixtureDescriptor.identity() = DownloadChapterIdentity(
        sourceDisplayName = READER_TEST_SOURCE_NAME,
        mangaTitle = mangaTitle,
        chapterName = chapterTitle,
        scanlator = null,
        chapterUrl = chapterUrl,
        disallowNonAsciiFilenames = false,
    )

    private fun createDownloadedDirectory(identity: DownloadChapterIdentity, spec: ReaderTestFixtureSpec) {
        writePages(prepareCanonicalArtifact(identity), spec)
    }

    private fun createDownloadedCbz(identity: DownloadChapterIdentity, spec: ReaderTestFixtureSpec) {
        val directory = prepareCanonicalArtifact(identity)
        writePages(directory, spec)
        val cbz = CbzCreator.defaultOutputFile(directory)
        check(!cbz.exists()) { "Reader Test Mode refuses to overwrite ${cbz.absolutePath}" }
        createdDownloadArtifacts.lastOrNull { it.chapterDirectory == directory }?.let { tracked ->
            createdDownloadArtifacts.remove(tracked)
            createdDownloadArtifacts += tracked.copy(cbz = cbz)
        }
        check(CbzCreator.create(directory, cbz)) { "Unable to create reader download CBZ fixture" }
        check(directory.deleteRecursively()) { "Unable to remove reader fixture staging directory" }
    }

    private fun createLocalArchive(token: String, spec: ReaderTestFixtureSpec): File {
        val directory = fixtureRoot.resolve("local-$token")
        writePages(directory, spec)
        val archive = fixtureRoot.resolve("local-$token.cbz")
        check(CbzCreator.create(directory, archive)) { "Unable to create local reader archive fixture" }
        check(directory.deleteRecursively()) { "Unable to remove local reader fixture staging directory" }
        return archive
    }

    private fun prepareCanonicalArtifact(identity: DownloadChapterIdentity): File {
        val directory = downloadProvider.canonicalChapterDownloadDir(identity)
        val cbz = CbzCreator.defaultOutputFile(directory)
        check(!directory.exists() && !cbz.exists()) {
            "Reader Test Mode refuses to overwrite an existing download artifact"
        }
        val mangaDirectory = directory.parentFile
        val sourceDirectory = mangaDirectory.parentFile
        createdDownloadArtifacts += CreatedDownloadArtifact(
            chapterDirectory = directory,
            cbz = null,
            mangaDirectoryCreated = !mangaDirectory.exists(),
            sourceDirectoryCreated = !sourceDirectory.exists(),
        )
        check(directory.mkdirs()) { "Unable to create reader download fixture directory" }
        return directory
    }

    private fun writePages(directory: File, spec: ReaderTestFixtureSpec) {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create reader fixture directory" }
        val bytes = imageBytes(spec)
        val digits = maxOf(3, spec.pageCount.toString().length)
        repeat(spec.pageCount) { index ->
            val page = directory.resolve("${(index + 1).toString().padStart(digits, '0')}.${spec.format.extension}")
            check(!page.exists()) { "Reader Test Mode refuses to overwrite ${page.absolutePath}" }
            page.writeBytes(bytes)
        }
    }

    private fun imageBytes(spec: ReaderTestFixtureSpec): ByteArray = imageBytes.computeIfAbsent(
        ImageKey(spec.width, spec.height, spec.format),
    ) { key ->
        val image = BufferedImage(key.width, key.height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().run {
            color = Color(244, 244, 244)
            fillRect(0, 0, image.width, image.height)
            color = Color(32, 72, 128)
            fillRect(0, 0, maxOf(1, image.width / 24), image.height)
            dispose()
        }
        ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "jpeg", output)) { "JPEG writer is unavailable" }
            output.toByteArray()
        }
    }

    override fun close() {
        clearFixtureDescriptor()
        val deletionFailures = mutableListOf<String>()

        fun deleteOwnedDirectory(directory: File) {
            if (!directory.exists()) return
            val deleted = runCatching(directory::deleteRecursively).getOrDefault(false)
            if (!deleted) deletionFailures += directory.absolutePath
        }

        fun deleteOwnedFile(file: File) {
            if (!file.exists()) return
            val deleted = file.isFile && runCatching(file::delete).getOrDefault(false)
            if (!deleted) deletionFailures += file.absolutePath
        }

        createdDownloadArtifacts.asReversed().forEach { artifact ->
            deleteOwnedDirectory(artifact.chapterDirectory)
            artifact.cbz?.let(::deleteOwnedFile)
            if (artifact.mangaDirectoryCreated) runCatching { artifact.chapterDirectory.parentFile.delete() }
            if (artifact.sourceDirectoryCreated) runCatching { artifact.chapterDirectory.parentFile.parentFile.delete() }
        }
        createdDownloadArtifacts.clear()
        val fixtureRootPath = fixtureRoot.toPath().toAbsolutePath().normalize()
        val failedInsideFixtureRoot = deletionFailures.any { failedPath ->
            File(failedPath).toPath().toAbsolutePath().normalize().startsWith(fixtureRootPath)
        }
        if (failedInsideFixtureRoot) {
            val deleted = !fixtureRoot.exists() || runCatching(fixtureRoot::delete).getOrDefault(false)
            if (!deleted) deletionFailures += fixtureRoot.absolutePath
        } else {
            deleteOwnedDirectory(fixtureRoot)
        }
        imageBytes.clear()
        events.clear()
        downloadEvents.clear()
        check(deletionFailures.isEmpty()) {
            "Unable to delete Reader Test Mode owned artifacts: ${deletionFailures.joinToString()}"
        }
    }

    companion object {
        const val READER_TEST_SOURCE_ID = -7_070_707_070_707L
        const val READER_TEST_SOURCE_NAME = "Mihon Test Mode Reader"
        private const val PARTIAL_FIXTURE_TIMEOUT_MS = 10_000L
    }
}

object ReaderIoTestModeBridge : ReaderIoProbe, DownloadIoProbe {
    private val value = AtomicReference<ReaderTestModeController?>()
    val controller: ReaderTestModeController? get() = value.get()
    override val enabled: Boolean get() = controller != null

    override fun record(event: ReaderIoEvent) {
        controller?.record(event)
    }

    override fun onIo(event: DownloadIoEvent) {
        controller?.onIo(event)
    }

    override fun bind(): ReaderIoProbe = controller?.bindScenario() ?: ReaderIoProbe.None

    fun install(controller: ReaderTestModeController) = value.set(controller)

    fun beginScenario(currentPageIndex: Int = 0) = controller?.beginScenario(currentPageIndex)

    fun clear(expected: ReaderTestModeController): Boolean = value.compareAndSet(expected, null)
}
