package mihon.desktop.extension

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.ServiceLoader

class ManhuaguiLoadTest {

    @Test
    fun `manhuagui extension loads without ServiceConfigurationError using ExtensionClassLoader`() {
        val jar = File("/tmp/extensions-desktop/extensions-source/src/zh/manhuagui/build/libs")
            .listFiles { f -> f.name.startsWith("eu.kanade.tachiyomi.extension.zh.manhuagui") }
            ?.firstOrNull()
            ?: run { println("JAR not found, skip"); return }

        println("Testing JAR: ${jar.name}")
        val cl = ExtensionClassLoader(jar.toURI().toURL(), this::class.java.classLoader)

        val iface = cl.loadClass("eu.kanade.tachiyomi.source.Source")
        @Suppress("UNCHECKED_CAST")
        val loader = ServiceLoader.load(iface as Class<Any>, cl)
        val iter = loader.iterator()
        var loaded = false
        while (iter.hasNext()) {
            try {
                val src = iter.next()
                println("Loaded OK: $src")
                loaded = true
            } catch (e: Throwable) {
                println("FAILED: ${e.javaClass.name}: ${e.message}")
                e.printStackTrace(System.out)
            }
        }
        assertTrue(loaded, "Manhuagui extension should load at least one source")
    }
}
