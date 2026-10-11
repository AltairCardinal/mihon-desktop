package eu.kanade.tachiyomi.ui.reader

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Original installed extension, actual loader feedback and its existing public UI recovery path. */
@RunWith(Parameterized::class)
class InstalledSharedReaderSourceAcceptanceTest(private val kind: String, private val vector: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-vector{1}")
        fun vectors(): List<Array<Any>> = listOf("manhuagui", "copymanga").flatMap { kind ->
            (-1..5).map { vector -> arrayOf<Any>(kind, vector) }
        }
    }

    @Test
    fun originalInstalledSourceParsesHttpAndRecoversThroughChapterLoader() {
        HostReaderArtFixture(kind).use { fixture ->
            fixture.cold()
            if (vector < 0) {
                fixture.launch()
                fixture.await("$kind original successful parser response must paint current page") {
                    fixture.paintedColor(Color.CYAN)
                }
                assertTrue(fixture.chapterRequests.get() > 0)
                assertTrue(fixture.imageRequests.get() > 0)
                return@use
            }
            val original = fixture.success.toString(Charsets.UTF_8)
            val shapes = if (kind == "copymanga") {
                listOf("empty", "missing", "malformed").map { fixture.staged("copy/$it.json") }
            } else {
                listOf(
                    original.replace("\"files\":[\"001.jpg\"]", "\"files\":[]").toByteArray(),
                    original.replace("\"files\":[\"001.jpg\"],", "").toByteArray(),
                    original.take(original.length / 2).toByteArray(),
                )
            }
            val failures = shapes.map { 200 to it } + listOf(403, 429, 500).map { it to fixture.success }
            if (vector >= 0) {
                val index = vector
                val response = failures[index]
                val identity = "source_${index}_${android.os.SystemClock.uptimeMillis()}"
                val recovery = if (kind ==
                    "copymanga"
                ) {
                    fixture.successWithImageIdentity(identity)
                } else {
                    fixture.success
                }
                val requests = fixture.chapterRequests.get()
                val images = fixture.imageRequests.get()
                fixture.response.set(if (response.first == 200) response else response.first to recovery)
                fixture.launch()
                fixture.awaitStable("$kind error vector $index must expose actual loader feedback") {
                    fixture.hasChapterError() || (kind == "copymanga" && fixture.paintedVirtualError())
                }
                assertTrue(fixture.chapterRequests.get() > requests)
                assertEquals("Invalid chapter must not fetch image", images, fixture.imageRequests.get())
                fixture.response.set(200 to recovery)
                if (fixture.hasChapterError()) {
                    fixture.retry()
                } else {
                    assertTrue(
                        "Original Copy text interceptor image must be currently painted",
                        fixture.paintedVirtualError(),
                    )
                    fixture.clearChapterCacheThroughSettings()
                    fixture.launch()
                }
                fixture.await("$kind vector $index real Retry must recover current rendered page") {
                    fixture.paintedColor(Color.CYAN)
                }
                assertTrue(fixture.chapterRequests.get() > requests + 1)
                assertTrue(fixture.imageRequests.get() > images)
            }
        }
    }
}
