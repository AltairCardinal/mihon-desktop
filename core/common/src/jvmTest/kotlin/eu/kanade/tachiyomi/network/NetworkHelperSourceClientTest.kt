package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class NetworkHelperSourceClientTest {

    @Test
    fun `desktop helper resolves a source scoped client`() {
        val global = OkHttpClient()
        val scoped = OkHttpClient()
        val helper = NetworkHelper(global) { sourceId ->
            if (sourceId == 42L) scoped else global
        }

        assertSame(scoped, helper.clientForSource(42L))
        assertSame(global, helper.clientForSource(1L))
    }
}
