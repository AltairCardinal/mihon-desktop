package mihon.desktop.source

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopSourceRepositoryTest {

    @Test
    fun `getSources returns empty list when no sources loaded`() = runBlocking {
        val repo = DesktopSourceRepository(FakeDesktopSourceManager(emptyList()), FakeHandler)
        val sources = repo.getSources().first()
        assertTrue(sources.isEmpty())
    }

    @Test
    fun `getSources maps source id and name`() = runBlocking {
        val repo = DesktopSourceRepository(FakeDesktopSourceManager(listOf(FakeSource(1L, "en", "TestSource"))), FakeHandler)
        val sources = repo.getSources().first()
        assertEquals(1, sources.size)
        assertEquals(1L, sources[0].id)
        assertEquals("TestSource", sources[0].name)
        assertEquals("en", sources[0].lang)
    }

    @Test
    fun `getOnlineSources returns only HttpSources`() = runBlocking {
        val repo = DesktopSourceRepository(
            FakeDesktopSourceManager(listOf(FakeHttpSource(2L, "en", "Http"), FakeSource(3L, "en", "Local"))),
            FakeHandler,
        )
        val sources = repo.getOnlineSources().first()
        assertEquals(1, sources.size)
        assertEquals(2L, sources[0].id)
    }
}
