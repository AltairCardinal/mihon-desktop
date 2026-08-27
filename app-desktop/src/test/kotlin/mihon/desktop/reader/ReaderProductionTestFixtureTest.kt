package mihon.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import mihon.desktop.source.LocalChapterEntry
import mihon.desktop.source.LocalSourceReader
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class ReaderProductionTestFixtureTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `fixture supplies real content scene and independently controlled critical path gates`() = runTest {
        ReaderProductionTestFixture(tempDir, currentCoroutineContext()).use { fixture ->
            assertEquals(3, LocalSourceReader.readChapter(LocalChapterEntry("Directory", fixture.downloadedDirectory)).size)
            assertEquals(2, LocalSourceReader.readChapter(LocalChapterEntry("CBZ", fixture.cbz)).size)

            fixture.server.enqueue(MockResponse.Builder().body(Buffer().write(fixture.pageBytes)).build())
            val response = OkHttpClient().newCall(Request.Builder().url(fixture.server.url("/page.png")).build()).execute()
            response.use { assertArrayEquals(fixture.pageBytes, it.body.bytes()) }

            ReaderIoGatePoint.entries.forEach { point ->
                val gate = fixture.gate(point)
                val held = async { fixture.ioGate.await(point) }
                gate.awaitEntered()
                assertTrue(held.isActive, "$point must remain blocked until the fixture releases it")
                gate.release()
                held.await()
            }
        }
    }
}
