package eu.kanade.tachiyomi.extension

import android.os.Bundle
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.readExtensionPackageMetadata
import mihon.domain.extension.model.ExtensionCompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExtensionPackageMetadataTest {
    @Test
    fun `explicit float protocol and name override legacy version and label`() {
        val metadata = Bundle().apply {
            putFloat("tachiyomix.extensionLib", 1.6f)
            putString("tachiyomix.name", "Modern source")
        }
        val result = readExtensionPackageMetadata(metadata, "Tachiyomi: Old label", "9.0.12")
        assertEquals("Modern source", result.name)
        assertEquals(1.6, result.libVersion)
        assertFalse(result.isNsfw)
    }

    @Test
    fun `legacy metadata retains 1_4 and controlled 1_5 compatibility`() {
        for (version in listOf("1.4.8", "1.5.2")) {
            val metadata = Bundle().apply { putInt("tachiyomi.extension.nsfw", 1) }
            val result = readExtensionPackageMetadata(metadata, "Tachiyomi: Legacy", version)
            assertEquals("Legacy", result.name)
            assertEquals(version.substringBeforeLast('.').toDouble(), result.libVersion)
            assertTrue(result.isNsfw)
        }
        assertNull(readExtensionPackageMetadata(null, "Legacy", null).libVersion)
    }

    @Test
    fun `explicit invalid protocol cannot silently fall back to a supported version name`() {
        for (raw in listOf("invalid", "NaN", "Infinity", "0", "")) {
            val metadata = Bundle().apply { putString("tachiyomix.extensionLib", raw) }
            assertNull(raw, readExtensionPackageMetadata(metadata, "Legacy", "1.4.8").libVersion)
        }
        val metadata = Bundle().apply { putString("tachiyomix.extensionLib", "1.6") }
        assertEquals(1.6, readExtensionPackageMetadata(metadata, "Legacy", "1.4.8").libVersion)
    }

    @Test
    fun `APK content warning uses its own threshold and cannot override a legacy restriction`() {
        for ((warning, legacy, expected) in listOf(
            Triple(0, 0, false),
            Triple(1, 0, true),
            Triple(2, 0, true),
            Triple(0, 1, true),
        )) {
            val metadata = Bundle().apply {
                putInt("tachiyomix.contentWarning", warning)
                putInt("tachiyomi.extension.nsfw", legacy)
            }
            assertEquals(expected, readExtensionPackageMetadata(metadata, "Source", "1.6.0").isNsfw)
        }
    }

    @Test
    fun `Android install availability accepts exact supported protocols only`() {
        for (version in listOf(1.4, 1.5, 1.6)) {
            assertEquals(ExtensionCompatibility.Compatible, available(version).compatibility)
        }
        for (version in listOf(1.3, 1.45, 1.55, 1.7, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(available(version).compatibility is ExtensionCompatibility.UnsupportedLib)
        }
    }

    private fun available(version: Double) = Extension.Available(
        name = "Fixture", pkgName = "fixture.extension", versionName = "$version.1", versionCode = 1,
        libVersion = version, lang = "en", isNsfw = false, sources = emptyList(),
        apkName = "fixture.apk", iconUrl = "", repoUrl = "https://fixture.invalid",
    )
}
