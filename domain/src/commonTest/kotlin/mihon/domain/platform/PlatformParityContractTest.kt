package mihon.domain.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlatformParityContractTest {

    @Test
    fun `canonical fixed-main repository URI resolves to a repository action`() {
        assertEquals(
            ExternalAction.AddRepository("https://example.com/repo.json"),
            ExternalActionParser.resolve(
                ExternalActionInput.ViewUri(
                    "tachiyomi://add-repo?url=https%3A%2F%2Fexample.com%2Frepo.json",
                ),
            ),
        )
    }

    @Test
    fun `unknown malformed missing duplicate and non HTTP repository URIs are rejected`() {
        listOf(
            "unknown://add-repo?url=https%3A%2F%2Fexample.com",
            "tachiyomi://unknown?url=https%3A%2F%2Fexample.com",
            "tachiyomi://add-repo/path?url=https%3A%2F%2Fexample.com",
            "tachiyomi://add-repo",
            "tachiyomi://add-repo?url=https%3A%2F%2Fone.example&url=https%3A%2F%2Ftwo.example",
            "tachiyomi://add-repo?url=ftp%3A%2F%2Fexample.com",
            "tachiyomi://add-repo?url=https%3A%2F%2Fexample.com%2F%FF",
            "tachiyomi://add-repo?url=https%3A%2F%2Fexample.com#fragment",
            "https://source.example/manga/one",
        ).forEach { uri ->
            assertTrue(ExternalActionParser.resolve(ExternalActionInput.ViewUri(uri)) is ExternalAction.Rejected)
        }
    }

    @Test
    fun `repository parser preserves raw Unicode and never throws for malformed percent encoding`() {
        assertEquals(
            ExternalAction.AddRepository("https://例子.测试/repo"),
            ExternalActionParser.resolve(
                ExternalActionInput.ViewUri("tachiyomi://add-repo?url=https://例子.测试/repo"),
            ),
        )
        assertTrue(
            ExternalActionParser.resolve(
                ExternalActionInput.ViewUri("tachiyomi://add-repo?url=https%3A%2F%2Fexample.com%2F%"),
            ) is ExternalAction.Rejected,
        )
    }

    @Test
    fun `backup view URIs and source URL search text follow their distinct fixed-main routes`() {
        assertEquals(
            ExternalAction.RestoreBackup("file:///downloads/library.tachibk"),
            ExternalActionParser.resolve(ExternalActionInput.ViewUri("file:///downloads/library.tachibk")),
        )
        assertEquals(
            ExternalAction.RestoreBackup("content://provider/backups/library.tachibk"),
            ExternalActionParser.resolve(ExternalActionInput.ViewUri("content://provider/backups/library.tachibk")),
        )
        assertEquals(
            ExternalAction.Search("https://source.example/manga/one"),
            ExternalActionParser.resolve(ExternalActionInput.Search(primaryQuery = "https://source.example/manga/one")),
        )
    }

    @Test
    fun `search query takes precedence over fallback and send is an action rather than a URI scheme`() {
        assertEquals(
            ExternalAction.Search("query"),
            ExternalActionParser.resolve(ExternalActionInput.Search(primaryQuery = "query", fallbackText = "fallback")),
        )
        assertEquals(
            ExternalAction.Search("fallback"),
            ExternalActionParser.resolve(ExternalActionInput.Search(primaryQuery = null, fallbackText = "fallback")),
        )
        assertEquals(
            ExternalAction.NoOp,
            ExternalActionParser.resolve(ExternalActionInput.Search(primaryQuery = "", fallbackText = "fallback")),
        )
        assertEquals(
            ExternalAction.Search("  "),
            ExternalActionParser.resolve(ExternalActionInput.Search(primaryQuery = "  ", fallbackText = "fallback")),
        )
        assertEquals(
            ExternalAction.Search("shared query"),
            ExternalActionParser.resolve(ExternalActionInput.SendText("shared query")),
        )
        assertEquals(ExternalAction.NoOp, ExternalActionParser.resolve(ExternalActionInput.Search(primaryQuery = null)))
        assertEquals(ExternalAction.NoOp, ExternalActionParser.resolve(ExternalActionInput.SendText(null)))
    }

    @Test
    fun `share payload keeps HTTP text separate from content streams`() {
        assertEquals(
            SharePayload.Text("https://example.com/manga"),
            ExternalShare.fromUri("https://example.com/manga"),
        )
        assertEquals(
            SharePayload.Stream(uri = "content://provider/page/1", mimeType = "image/png", message = "page"),
            ExternalShare.fromUri("content://provider/page/1", mimeType = "image/png", message = "page"),
        )
    }
}
