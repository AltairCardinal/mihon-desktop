package mihon.desktop.extension

import eu.kanade.tachiyomi.network.HttpException
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.toSourceAppError
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class ExtensionHttpErrorIdentityTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `bundled extension HTTP exceptions retain shared source error semantics`() {
        val resource = "eu/kanade/tachiyomi/network/HttpException.class"
        val jar = File(tempDir, "bundled-api.jar")
        JarOutputStream(jar.outputStream()).use { output ->
            output.putNextEntry(JarEntry(resource))
            requireNotNull(javaClass.classLoader.getResourceAsStream(resource)).use { it.copyTo(output) }
            output.closeEntry()
        }
        ExtensionClassLoader(jar.toURI().toURL(), javaClass.classLoader).use { loader ->
            val type = loader.loadClass(HttpException::class.java.name)
            assertSame(HttpException::class.java, type)
            for ((code, expected) in listOf(
                401 to AppError.Authentication::class.java,
                403 to AppError.Forbidden::class.java,
                429 to AppError.RateLimited::class.java,
                500 to AppError.Server::class.java,
            )) {
                val failure = type.getConstructor(Int::class.javaPrimitiveType).newInstance(code) as Throwable
                assertInstanceOf(expected, failure.toSourceAppError())
            }
        }
    }
}
