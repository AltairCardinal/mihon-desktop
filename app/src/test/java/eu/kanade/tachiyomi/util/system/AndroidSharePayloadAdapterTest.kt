package eu.kanade.tachiyomi.util.system

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import eu.kanade.tachiyomi.ui.deeplink.forwardToMainActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AndroidSharePayloadAdapterTest {
    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `HTTP share sends only shared text payload and keeps Android chooser grants`() {
        val value = "https://example.org/page.jpg"
        val fixture = shareFixture(value)
        assertSame(fixture.chooser, fixture.uri.buildShareIntent("image/png", "ignored", "Share"))
        verify(exactly = 1) { anyConstructed<Intent>().putExtra(Intent.EXTRA_TEXT, value) }
        verify(exactly = 0) { anyConstructed<Intent>().putExtra(Intent.EXTRA_STREAM, any<Parcelable>()) }
        fixture.verifyAndroidEnvelope("image/png")
    }

    @Test
    fun `content share keeps message stream chooser ClipData and read permission`() {
        val fixture = shareFixture("content://mihon/page/1")
        assertSame(fixture.chooser, fixture.uri.buildShareIntent("image/webp", "Chapter 1", "Share"))
        verify { anyConstructed<Intent>().putExtra(Intent.EXTRA_TEXT, "Chapter 1") }
        verify { anyConstructed<Intent>().putExtra(Intent.EXTRA_STREAM, fixture.uri) }
        fixture.verifyAndroidEnvelope("image/webp")
    }

    @Test
    fun `unsupported share URI is rejected by shared classification`() {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///tmp/page.jpg"
        assertThrows(IllegalArgumentException::class.java) {
            uri.buildShareIntent("image/*", null, "Share")
        }
    }

    @Test
    fun `deep link forwarding preserves intent and adds fixed main activity flags`() {
        val originalFlags = 0x20
        val intent = mockk<Intent>(relaxed = true) { every { flags } returns originalFlags }
        assertSame(intent, intent.forwardToMainActivity(mockk(relaxed = true)))
        verify { intent.flags = originalFlags or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK }
        verify { intent.setClass(any(), eu.kanade.tachiyomi.ui.main.MainActivity::class.java) }
    }
    private fun shareFixture(value: String): ShareFixture {
        mockkConstructor(Intent::class)
        mockkStatic(Intent::class)
        mockkStatic(ClipData::class)
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Parcelable>()) } answers { self as Intent }
        every { anyConstructed<Intent>().setType(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setClipData(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setFlags(any()) } answers { self as Intent }
        val uri = mockk<Uri>()
        every { uri.toString() } returns value
        val clipData = mockk<ClipData>()
        val chooser = mockk<Intent>(relaxed = true)
        every { ClipData.newRawUri(null, uri) } returns clipData
        every { Intent.createChooser(any(), "Share") } returns chooser
        return ShareFixture(uri, clipData, chooser)
    }
    private data class ShareFixture(
        val uri: Uri,
        val clipData: ClipData,
        val chooser: Intent,
    ) {
        fun verifyAndroidEnvelope(type: String) {
            verify { anyConstructed<Intent>().setClipData(clipData) }
            verify { anyConstructed<Intent>().setType(type) }
            verify { anyConstructed<Intent>().setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            verify { Intent.createChooser(any(), "Share") }
            verify { chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        }
    }
}
