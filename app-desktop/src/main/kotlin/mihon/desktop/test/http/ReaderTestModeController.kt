package mihon.desktop.test.http

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlinx.serialization.Serializable
import mihon.domain.reader.observability.ReaderIoEvent
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

class ReaderTestModeController : ReaderIoProbe, AutoCloseable {
    private data class ScenarioEvent(val scenario: Long, val event: ReaderIoTestEvent)

    private val events = CopyOnWriteArrayList<ScenarioEvent>()
    private val scenario = AtomicLong(0L)
    private val fixtureRoot = Files.createTempDirectory("mihon-reader-test-mode-").toFile()

    override fun record(event: ReaderIoEvent) {
        record(scenario.get(), event)
    }

    private fun record(expectedScenario: Long, event: ReaderIoEvent) {
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

    fun snapshot(): List<ReaderIoTestEvent> {
        val currentScenario = scenario.get()
        return events.filter { it.scenario == currentScenario }.map(ScenarioEvent::event)
    }

    fun beginScenario() {
        scenario.incrementAndGet()
        events.clear()
    }

    fun bindScenario(): ReaderIoProbe {
        val expected = scenario.get()
        return ReaderIoProbe { event -> record(expected, event) }
    }

    fun clearEvents() = beginScenario()

    fun createFixture(
        kind: ReaderTestFixtureKind = ReaderTestFixtureKind.DOWNLOADED_DIRECTORY,
        pageCount: Int = 3,
    ): ReaderTestFixture {
        require(pageCount > 0) { "pageCount must be positive" }
        val fixtureDirectory = fixtureRoot.resolve("fixture-${System.nanoTime()}").also(File::mkdirs)
        val pages = (0 until pageCount).map { index ->
            fixtureDirectory.resolve("${(index + 1).toString().padStart(3, '0')}.png").also { page ->
                val image = BufferedImage(16, 24, BufferedImage.TYPE_INT_RGB)
                image.createGraphics().run {
                    color = Color(40 + index * 20, 80, 160)
                    fillRect(0, 0, image.width, image.height)
                    dispose()
                }
                check(ImageIO.write(image, "png", page)) { "PNG writer is unavailable" }
            }
        }
        return when (kind) {
            ReaderTestFixtureKind.DOWNLOADED_DIRECTORY -> ReaderTestFixture(
                localChapterPath = fixtureDirectory.absolutePath,
                pageCount = pageCount,
            )
            ReaderTestFixtureKind.CBZ -> {
                val archive = fixtureRoot.resolve("fixture-${System.nanoTime()}.cbz")
                ZipOutputStream(archive.outputStream().buffered()).use { output ->
                    pages.forEach { page ->
                        output.putNextEntry(ZipEntry(page.name))
                        page.inputStream().use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
                ReaderTestFixture(archive.absolutePath, pageCount)
            }
        }
    }

    override fun close() {
        fixtureRoot.deleteRecursively()
        events.clear()
    }
}

object ReaderIoTestModeBridge : ReaderIoProbe {
    private val value = AtomicReference<ReaderTestModeController?>()
    val controller: ReaderTestModeController? get() = value.get()
    override val enabled: Boolean get() = controller != null

    override fun record(event: ReaderIoEvent) {
        controller?.record(event)
    }

    override fun bind(): ReaderIoProbe = controller?.bindScenario() ?: ReaderIoProbe.None

    fun install(controller: ReaderTestModeController) = value.set(controller)

    fun beginScenario() = controller?.beginScenario()

    fun clear(expected: ReaderTestModeController): Boolean = value.compareAndSet(expected, null)
}
