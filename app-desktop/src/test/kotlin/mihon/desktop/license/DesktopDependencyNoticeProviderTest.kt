package mihon.desktop.license

import mihon.domain.license.model.LicenseNoticeFailureReason
import mihon.domain.license.model.LicenseNoticeResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class DesktopDependencyNoticeProviderTest {

    @Test
    fun `provider maps metadata through the notice policy and caches the result`() {
        var reads = 0
        val provider = ClasspathDependencyNoticeProvider {
            reads += 1
            """
            {
              "licenses": {
                "first-id": {
                  "content": "First license",
                  "name": "Must not be displayed",
                  "spdxId": "Must-not-be-displayed"
                },
                "second-id": {
                  "content": "Second license"
                }
              },
              "libraries": [
                {
                  "name": "zeta",
                  "website": "",
                  "licenses": ["first-id", "second-id"]
                },
                {
                  "name": "Alpha"
                }
              ]
            }
            """.trimIndent()
        }

        val first = provider.getNotices()
        val second = provider.getNotices()
        val notices = assertInstanceOf(LicenseNoticeResult.Success::class.java, first).notices

        assertSame(first, second)
        assertEquals(1, reads)
        assertEquals(listOf("Alpha", "zeta"), notices.map { it.name })
        assertNull(notices[0].website)
        assertNull(notices[0].license)
        assertNull(notices[1].website)
        assertEquals("First license", notices[1].license)
    }

    @Test
    fun `missing classpath resource is an explicit failure`() {
        assertMalformed(ClasspathDependencyNoticeProvider { null }.getNotices())
    }

    @Test
    fun `malformed JSON and schema are explicit failures`() {
        listOf(
            """{"licenses":{},"libraries":["not-an-object"]}""",
            """{"wrongRoot":[]}""",
            """{"licenses":{},"libraries":[{"website":"https://example.com"}]}""",
            """{"libraries":[]}""",
            """{"libraries":""",
        ).forEach { metadata ->
            assertMalformed(ClasspathDependencyNoticeProvider { metadata }.getNotices())
        }
    }

    @Test
    fun `blank dependency name is an explicit failure`() {
        assertMalformed(
            ClasspathDependencyNoticeProvider {
                """{"licenses":{},"libraries":[{"name":"  ","website":null,"licenses":[]}]}"""
            }.getNotices(),
        )
    }

    @Test
    fun `a contentless first license remains absent instead of falling through to another license`() {
        val result = ClasspathDependencyNoticeProvider {
            """
            {
              "licenses": {
                "first-id": {"name": "First license name", "spdxId": "first-id"},
                "second-id": {"content": "Second license content"}
              },
              "libraries": [
                {
                  "name": "Dependency",
                  "licenses": ["first-id", "second-id"]
                }
              ]
            }
            """.trimIndent()
        }.getNotices()

        val notice = assertInstanceOf(LicenseNoticeResult.Success::class.java, result).notices.single()
        assertNull(notice.license)
    }

    private fun assertMalformed(result: LicenseNoticeResult) {
        val failure = assertInstanceOf(LicenseNoticeResult.Failure::class.java, result)
        assertEquals(LicenseNoticeFailureReason.MALFORMED_METADATA, failure.reason)
    }
}
