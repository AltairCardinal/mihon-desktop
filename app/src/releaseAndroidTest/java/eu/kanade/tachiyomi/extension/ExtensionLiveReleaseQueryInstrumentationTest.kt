package eu.kanade.tachiyomi.extension

import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in live diagnosis; not part of the offline release ABI gate. Does not install or trust code. */
class ExtensionLiveReleaseQueryInstrumentationTest {
    @Test
    fun installedPrivateMangaPlusSearchUsesProductionSource() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val codecOnly = arguments.getString("aex06MangaPlusCodecOnly") == "true"
        assumeTrue(codecOnly || arguments.getString("aex06LiveMangaPlus") == "true")
        ExtensionReleaseParityInstrumentationTest.verifyReleaseArtifactBeforeChangingFixtures()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extension = File(context.filesDir, "exts/eu.kanade.tachiyomi.extension.all.mangaplus.ext")
        check(extension.isFile) { "Install the trusted MangaPlus fixture through production UI first" }
        val digest = MessageDigest.getInstance("SHA-256")
        extension.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val installedHash = digest.digest().joinToString("") { "%02x".format(it) }
        println("AEX06_LIVE_QUERY_EXTENSION sha256=$installedHash")
        val loader = ChildFirstPathClassLoader(extension.absolutePath, null, checkNotNull(javaClass.classLoader))
        if (codecOnly) {
            // Obfuscated symbols belong exclusively to this independently verified published APK.
            assertEquals("e9511110525f81f30139704bda07b0a910e5100e42326570c5c9870a7529f94b", installedHash)
            val serializer = loader.loadClass("o1").getField("a").get(null)
            val decoder = loader.loadClass("d2").getMethod(
                "a",
                BufferedSource::class.java,
                loader.loadClass("kotlinx.serialization.DeserializationStrategy"),
            )
            // Protobuf field 1 = success, field 2 = error; each contains an empty nested message.
            listOf("0a00" to "a", "1200" to "b").forEach { (frame, expectedField) ->
                Buffer().write(frame.decodeHex()).use { input ->
                    val decoded = decoder.invoke(null, input, serializer)
                    assertNotNull("The published decoder must retain field $expectedField", decoded)
                    assertNotNull(decoded.javaClass.getField(expectedField).get(decoded))
                    val absentField = if (expectedField == "a") "b" else "a"
                    assertNull(decoded.javaClass.getField(absentField).get(decoded))
                }
            }
            println("AEX06_MANGAPLUS_CODEC fixedSuccessAndErrorFrames=true networkRequests=0")
            return@runBlocking
        }
        val factory = loader.loadClass(
            "keiyoushi.source.Generated",
        ).getDeclaredConstructor().newInstance() as SourceFactory
        val source = factory.createSources().single { it.lang == "en" }
        val observeResponse = arguments.getString("aex06MangaPlusObserveResponse") == "true"
        val clientField = if (observeResponse) {
            assertEquals("e9511110525f81f30139704bda07b0a910e5100e42326570c5c9870a7529f94b", installedHash)
            loader.loadClass("c0").getField("a").apply { isAccessible = true }
        } else {
            null
        }
        val originalDelegate = clientField?.get(source)
        if (clientField != null) {
            val builder = (source as HttpSource).client.newBuilder()
            // Outermost observer: retain the extension's production interceptors, proxy, TLS and cache.
            builder.interceptors().add(
                0,
                Interceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (response.request.url.host == "jumpg-webapi.tokyo-cdn.com" &&
                        response.request.url.encodedPath == "/api/title_list/allV2"
                    ) {
                        val bytes = response.peekBody(1024 * 1024).bytes()
                        val encoding = response.header("Content-Encoding").let {
                            if (it in listOf("gzip", "zstd", "br", null)) it ?: "none" else "other"
                        }
                        println(
                            "AEX06_RESPONSE status=${response.code} encoding=$encoding " +
                                "peekBytes=${bytes.size} firstByte=${bytes.firstOrNull()?.toInt()?.and(255)} " +
                                "network=${response.networkResponse != null} cache=${response.cacheResponse != null}",
                        )
                    }
                    response
                },
            )
            clientField.set(source, lazyOf(builder.build()))
        }
        val result = try {
            withTimeout(45_000) { source.getSearchManga(1, "One Piece", source.getFilterList()) }
        } catch (error: Throwable) {
            // Do not print exception messages: third-party code may include URLs, credentials or response bodies.
            val seen = mutableSetOf<Throwable>()
            var cause: Throwable? = error
            while (cause != null && seen.add(cause)) {
                println("AEX06_LIVE_QUERY_ERROR type=${cause.javaClass.name}")
                // Only fixed labels leave this process. Matches are hints, not verified server error codes.
                val message = cause.message.orEmpty().take(4096).lowercase()
                val hints = mapOf(
                    "update" to ("update" in message || "version" in message),
                    "maintenance" to ("maintenance" in message),
                    "region" to ("region" in message || "country" in message),
                    "rate_limit" to ("too many" in message || "rate limit" in message),
                    "unknown" to ("unknown error" in message),
                )
                println("AEX06_LIVE_QUERY_MESSAGE_HINTS $hints")
                if (cause is HttpException) println("AEX06_LIVE_QUERY_HTTP status=${cause.code}")
                cause.stackTrace.take(16).forEach { println("AEX06_LIVE_QUERY_FRAME $it") }
                cause = cause.cause
            }
            throw AssertionError("Production source query failed; see sanitized diagnostic frames")
        } finally {
            clientField?.set(source, originalDelegate)
        }
        println("AEX06_LIVE_QUERY_RESULT count=${result.mangas.size} hasNextPage=${result.hasNextPage}")
        assertTrue("The live query must return an actual result", result.mangas.isNotEmpty())
    }
}
