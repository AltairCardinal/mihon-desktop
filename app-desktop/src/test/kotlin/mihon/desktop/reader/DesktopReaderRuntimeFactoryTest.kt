package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

class DesktopReaderRuntimeFactoryTest {

    @Test
    fun `reader preloader uses the source scoped client`() = runTest {
        val sourceId = 42L
        var globalRequests = 0
        var sourceRequests = 0
        val globalClient = OkHttpClient.Builder()
            .addInterceptor {
                globalRequests += 1
                throw IOException("global client must not be used for an extension page")
            }
            .build()
        val sourceClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                sourceRequests += 1
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(pngBytes().toResponseBody("image/png".toMediaType()))
                    .build()
            }
            .build()
        val networkHelper = NetworkHelper(globalClient) { requestedSourceId ->
            if (requestedSourceId == sourceId) sourceClient else globalClient
        }

        val preloader = buildReaderPreloader(networkHelper, sourceId)
        preloader.preload(0, listOf("https://images.example/page.png"))

        assertEquals(0, globalRequests)
        assertEquals(1, sourceRequests)
        assertNotNull(preloader.get(0))
    }

    private fun pngBytes(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, Color.RED.rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
