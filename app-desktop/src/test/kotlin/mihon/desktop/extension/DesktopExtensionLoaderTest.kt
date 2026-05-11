package mihon.desktop.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URL

class DesktopExtensionLoaderTest {

    /** Reflective accessor to the private mustLoadFromParent method for white-box testing. */
    private fun mustLoadFromParent(cl: ExtensionClassLoader, name: String): Boolean {
        val m = ExtensionClassLoader::class.java.getDeclaredMethod("mustLoadFromParent", String::class.java)
        m.isAccessible = true
        return m.invoke(cl, name) as Boolean
    }

    private fun makeLoader(): ExtensionClassLoader =
        ExtensionClassLoader(URL("file:///dev/null"), ClassLoader.getSystemClassLoader())

    @Test
    fun `rx RxJava 1x classes must load from parent to avoid loader constraint violation`() {
        val cl = makeLoader()
        assertTrue(mustLoadFromParent(cl, "rx.Observable"))
        assertTrue(mustLoadFromParent(cl, "rx.Single"))
        assertTrue(mustLoadFromParent(cl, "rx.subjects.PublishSubject"))
    }

    @Test
    fun `source model classes must load from parent for type safety`() {
        val cl = makeLoader()
        assertTrue(mustLoadFromParent(cl, "eu.kanade.tachiyomi.source.Source"))
        assertTrue(mustLoadFromParent(cl, "eu.kanade.tachiyomi.source.model.SManga"))
        assertTrue(mustLoadFromParent(cl, "eu.kanade.tachiyomi.source.CatalogueSource"))
    }

    @Test
    fun `extension implementation classes must NOT load from parent`() {
        val cl = makeLoader()
        assertFalse(mustLoadFromParent(cl, "eu.kanade.tachiyomi.extension.zh.manhuagui.Manhuagui"))
        assertFalse(mustLoadFromParent(cl, "eu.kanade.tachiyomi.source.online.HttpSource"))
        assertFalse(mustLoadFromParent(cl, "uy.kohesive.injekt.Injekt"))
    }

    @Test
    fun `kotlinx coroutines must load from parent for suspend interop`() {
        val cl = makeLoader()
        assertTrue(mustLoadFromParent(cl, "kotlinx.coroutines.CoroutineScope"))
        assertTrue(mustLoadFromParent(cl, "kotlinx.coroutines.flow.Flow"))
        assertTrue(mustLoadFromParent(cl, "kotlinx.coroutines.Dispatchers"))
    }

    @Test
    fun `kotlinx serialization must NOT load from parent so extension Serializable classes work`() {
        val cl = makeLoader()
        // Extensions bundle their own kotlinx.serialization; generated $serializer companions
        // must share the same KSerializer interface as the runtime they call into.
        assertFalse(mustLoadFromParent(cl, "kotlinx.serialization.KSerializer"))
        assertFalse(mustLoadFromParent(cl, "kotlinx.serialization.json.Json"))
        assertFalse(mustLoadFromParent(cl, "kotlinx.serialization.Serializable"))
    }

    @Test
    fun `android compat classes must load from parent to match Page constructor signature`() {
        val cl = makeLoader()
        // Page (source.model, parent-loaded) has android.net.Uri in its constructor.
        // If android.* came from child, the Uri types would differ between Page and its callers,
        // causing NoSuchMethodException at runtime.
        assertTrue(mustLoadFromParent(cl, "android.net.Uri"))
        assertTrue(mustLoadFromParent(cl, "android.util.Base64"))
        assertTrue(mustLoadFromParent(cl, "android.content.Context"))
        assertTrue(mustLoadFromParent(cl, "android.app.Activity"))
        assertTrue(mustLoadFromParent(cl, "android.graphics.Bitmap"))
        assertTrue(mustLoadFromParent(cl, "androidx.preference.ListPreference"))
    }

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `returns empty list when extensions directory does not exist`() {
        val nonExistent = File(tempDir, "nonexistent")
        val loader = DesktopExtensionLoader(nonExistent)
        assertTrue(loader.loadExtensions().isEmpty())
    }

    @Test
    fun `returns empty list when extensions directory is empty`() {
        val loader = DesktopExtensionLoader(tempDir)
        assertTrue(loader.loadExtensions().isEmpty())
    }

    @Test
    fun `ignores non-jar files`() {
        File(tempDir, "readme.txt").writeText("not a jar")
        val loader = DesktopExtensionLoader(tempDir)
        assertTrue(loader.loadExtensions().isEmpty())
    }

    @Test
    fun `getExtensionsDirectory returns configured path`() {
        val loader = DesktopExtensionLoader(tempDir)
        assertEquals(tempDir, loader.extensionsDirectory)
    }
}
