package eu.kanade.tachiyomi.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.Properties
import java.util.jar.JarFile

/** The same independent binary calls run on the Android and Desktop JVM test targets. */
class CoroutineBinaryCompatibilityTest {

    @Test
    fun `old independently compiled binary preserves blocking contract`() = checkBinary("old", "ec.old.BlockingProbe")

    @Test
    fun `new independently compiled binary preserves blocking contract`() = checkBinary(
        "new",
        "ec.newabi.BlockingProbe",
    )

    private fun checkBinary(name: String, className: String) {
        val fixture = requireNotNull(javaClass.getResource("/extensions/ec-coroutines-$name.jar"))
        val provenance = Properties().apply {
            requireNotNull(
                this@CoroutineBinaryCompatibilityTest.javaClass.getResourceAsStream(
                    "/extensions/ec-coroutines.provenance.properties",
                ),
            ).use(::load)
        }
        val hash = fixture.openStream().use { input ->
            MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) }
        }
        assertEquals(provenance.getProperty("$name.sha256"), hash)
        JarFile(java.io.File(fixture.toURI())).use { jar ->
            assertTrue(
                jar.entries().asSequence().none {
                    it.name.startsWith("kotlin/") || it.name.startsWith("kotlinx/") ||
                        it.name.startsWith("eu/kanade/")
                },
                "The external probe must not bundle host APIs or coroutine implementations",
            )
        }
        URLClassLoader(arrayOf(fixture), javaClass.classLoader).use { loader ->
            val probe = loader.loadClass(className)
            assertEquals(loader, probe.classLoader)
            assertEquals("default-value", invoke(probe, "defaultValue"))
            assertEquals("external", invoke(probe, "explicitContext"))
            val failure = assertThrows(IllegalStateException::class.java) { invoke(probe, "failure") }
            assertEquals("external-failure", failure.message)
            val cancelled = Job().apply { cancel(CancellationException("external-cancel")) }
            val cancellation = assertThrows(CancellationException::class.java) {
                invoke(probe, "cancelled", cancelled)
            }
            assertInstanceOf(CancellationException::class.java, cancellation)
            assertTrue(cancelled.isCancelled)
        }
    }

    private fun invoke(probe: Class<*>, name: String, vararg arguments: Any): Any? = try {
        val method = if (arguments.isEmpty()) probe.getMethod(name) else probe.getMethod(name, Job::class.java)
        method.invoke(null, *arguments)
    } catch (wrapped: InvocationTargetException) {
        throw wrapped.targetException
    }
}
