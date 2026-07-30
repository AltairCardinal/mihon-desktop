package mihon.desktop.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PluginDomainRulesTest {

    @Test
    fun `normalization keeps exact www host and removes URL details`() {
        assertEquals("www.example.org", normalizePluginHost("HTTPS://WWW.Example.Org:8443/path?q=secret#fragment"))
        assertEquals("example.org", normalizePluginHost("https://example.org/"))
        assertNull(normalizePluginHost("not a URL"))
    }

    @Test
    fun `normalization exports international hosts as punycode and keeps IP literals`() {
        assertEquals("xn--fsqu00a.xn--0zwm56d", normalizePluginHost("https://例子.测试/path"))
        assertEquals("127.0.0.1", normalizePluginHost("http://127.0.0.1:8080/path"))
        assertEquals("2001:db8::1", normalizePluginHost("https://[2001:db8::1]/path"))
    }

    @Test
    fun `plain and Mihomo exports are deterministic exact host rules`() {
        val domains = setOf("www.example.org", "example.org", "api.example.org")

        assertEquals(
            "api.example.org\nexample.org\nwww.example.org",
            exportPluginDomains(domains, PluginDomainExportFormat.PLAIN),
        )
        assertEquals(
            """
            - DOMAIN,api.example.org,漫画源
            - DOMAIN,example.org,漫画源
            - DOMAIN,www.example.org,漫画源
            """.trimIndent(),
            exportPluginDomains(domains, PluginDomainExportFormat.MIHOMO_DOMAIN, "漫画源"),
        )
        assertEquals(
            """
            - DOMAIN-SUFFIX,api.example.org,漫画源
            - DOMAIN-SUFFIX,example.org,漫画源
            - DOMAIN-SUFFIX,www.example.org,漫画源
            """.trimIndent(),
            exportPluginDomains(domains, PluginDomainExportFormat.MIHOMO_SUFFIX, "漫画源"),
        )
    }

    @Test
    fun `sing box and Xray exports use hostnames without scheme path or port`() {
        val domains = setOf("api.example.org", "example.org")

        assertEquals(
            """
            {
              "domain": [
                "api.example.org",
                "example.org"
              ],
              "action": "route",
              "outbound": "proxy"
            }
            """.trimIndent(),
            exportPluginDomains(domains, PluginDomainExportFormat.SING_BOX, "proxy"),
        )
        assertEquals(
            "domain:api.example.org\ndomain:example.org",
            exportPluginDomains(domains, PluginDomainExportFormat.XRAY),
        )
    }
}
