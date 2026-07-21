package eu.kanade.tachiyomi.util.system

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import eu.kanade.tachiyomi.ui.deeplink.forwardToMainActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import mihon.domain.platform.ExternalShare
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class AndroidSharePayloadAdapterTest {
    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `shared payloads keep fixed main Android extras and envelopes`() {
        val file = "file:///tmp/page.jpg"
        val (uri, chooser) = shareFixture(file)
        assertSame(chooser, uri.buildShareIntent("image/*", null, "Share"))
        verify(exactly = 0) { anyConstructed<Intent>().putExtra(Intent.EXTRA_TEXT, any<String>()) }
        verify(exactly = 0) { anyConstructed<Intent>().putExtra(Intent.EXTRA_STREAM, any<Parcelable>()) }
        verify { ExternalShare.fromUri(file, "image/*", null) }
        verifyAndroidEnvelope(uri, chooser, "image/*")
        val http = "https://example.org/page.jpg"
        every { uri.toString() } returns http
        assertSame(chooser, uri.buildShareIntent("image/png", "ignored", "Share"))
        verify { anyConstructed<Intent>().putExtra(Intent.EXTRA_TEXT, http) }
        verifyAndroidEnvelope(uri, chooser, "image/png")
        every { uri.toString() } returns "content://mihon/page/1"
        assertSame(chooser, uri.buildShareIntent("image/webp", "Chapter 1", "Share"))
        verify { anyConstructed<Intent>().putExtra(Intent.EXTRA_TEXT, "Chapter 1") }
        verify { anyConstructed<Intent>().putExtra(Intent.EXTRA_STREAM, uri) }
        verifyAndroidEnvelope(uri, chooser, "image/webp")
        verify(exactly = 3) { ExternalShare.fromUri(any(), any(), any()) }
    }

    @Test
    fun `deep link forwarding preserves intent and adds fixed main activity flags`() {
        val originalFlags = 0x20
        val data = mockk<Uri>()
        val intent = mockk<Intent>(relaxed = true) {
            every { flags } returns originalFlags
            every { action } returns Intent.ACTION_VIEW
            every { this@mockk.data } returns data
            every { getStringExtra("marker") } returns "value"
        }
        assertSame(intent, intent.forwardToMainActivity(mockk(relaxed = true)))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertSame(data, intent.data)
        assertEquals("value", intent.getStringExtra("marker"))
        verify { intent.flags = originalFlags or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK }
        verify { intent.setClass(any(), eu.kanade.tachiyomi.ui.main.MainActivity::class.java) }
        verify(exactly = 0) { intent.setAction(any()) }
        verify(exactly = 0) { intent.setData(any()) }
        verify(exactly = 0) { intent.removeExtra(any()) }
    }
    private fun shareFixture(value: String): Pair<Uri, Intent> {
        mockkConstructor(Intent::class)
        mockkStatic(Intent::class)
        mockkStatic(ClipData::class)
        mockkObject(ExternalShare)
        every { ExternalShare.fromUri(any(), any(), any()) } answers { callOriginal() }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Parcelable>()) } answers { self as Intent }
        every { anyConstructed<Intent>().setType(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setClipData(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setFlags(any()) } answers { self as Intent }
        val uri = mockk<Uri>()
        every { uri.toString() } returns value
        val chooser = mockk<Intent>(relaxed = true)
        every { ClipData.newRawUri(null, uri) } returns mockk()
        every { Intent.createChooser(any(), "Share") } returns chooser
        return uri to chooser
    }
    private fun verifyAndroidEnvelope(uri: Uri, chooser: Intent, type: String) {
        verify { ClipData.newRawUri(null, uri) }
        verify { anyConstructed<Intent>().setClipData(any()) }
        verify { anyConstructed<Intent>().setType(type) }
        verify { anyConstructed<Intent>().setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        verify { chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK }
    }
}
