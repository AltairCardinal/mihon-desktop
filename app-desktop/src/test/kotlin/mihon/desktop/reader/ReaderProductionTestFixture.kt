package mihon.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import mockwebserver3.MockWebServer

internal class ControllableReaderIoGate(
    private val point: ReaderIoGatePoint,
) : ReaderIoGate {
    private val entered = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()

    override suspend fun await(point: ReaderIoGatePoint) {
        if (point != this.point) return
        entered.complete(Unit)
        released.await()
    }

    suspend fun awaitEntered() = entered.await()

    val isEntered: Boolean
        get() = entered.isCompleted

    fun release() = released.complete(Unit)
}

@OptIn(ExperimentalComposeUiApi::class)
internal class ReaderProductionTestFixture(
    private val root: File,
    coroutineContext: CoroutineContext,
) : AutoCloseable {
    val pageBytes: ByteArray = pngBytes(Color.BLUE)
    val downloadedDirectory: File = root.resolve("downloads/Fixture/Chapter 1").also { directory ->
        directory.mkdirs()
        repeat(3) { index ->
            directory.resolve("${(index + 1).toString().padStart(3, '0')}.png").writeBytes(pageBytes)
        }
    }
    val cbz: File = root.resolve("downloads/Fixture/Chapter 2.cbz").also { archive ->
        archive.parentFile.mkdirs()
        ZipOutputStream(archive.outputStream().buffered()).use { output ->
            repeat(2) { index ->
                output.putNextEntry(ZipEntry("${(index + 1).toString().padStart(3, '0')}.png"))
                output.write(pageBytes)
                output.closeEntry()
            }
        }
    }
    val server = MockWebServer().also { it.start() }
    val scene = ImageComposeScene(640, 480, coroutineContext = coroutineContext) {}
    private val gates = ReaderIoGatePoint.entries.associateWith { point ->
        ControllableReaderIoGate(point)
    }

    val ioGate = ReaderIoGate { point -> gates.getValue(point).await(point) }

    fun gate(point: ReaderIoGatePoint): ControllableReaderIoGate = checkNotNull(gates[point])

    override fun close() {
        scene.close()
        server.close()
    }

    private fun pngBytes(color: Color): ByteArray {
        val image = BufferedImage(16, 24, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().run {
            this.color = color
            fillRect(0, 0, image.width, image.height)
            dispose()
        }
        return ByteArrayOutputStream().also { output ->
            check(ImageIO.write(image, "png", output)) { "PNG writer is unavailable" }
        }.toByteArray()
    }
}
