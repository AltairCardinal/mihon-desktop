package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidRecoveryPackageInstallerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    @Config(sdk = [26])
    fun `API26 formal candidate uses legacy OS metadata with the same signature and upgrade gate`() {
        val signature = Signature(byteArrayOf(1, 3, 5))
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val info = PackageInfo().apply {
            packageName = "app.mihon.desktop.fork"
            versionCode = 66
            signatures = arrayOf(signature)
        }
        val manager = mockk<PackageManager>()
        val context = mockk<Context> { every { packageManager } returns manager }
        val archive = temporary.newFile("formal-26.apk").apply { writeBytes(byteArrayOf(1)) }
        every { manager.getPackageArchiveInfo(archive.absolutePath, PackageManager.GET_SIGNATURES) } returns info
        val installer = AndroidRecoveryPackageInstaller(context, "app.mihon.desktop.fork", fingerprint, 65)
        assertTrue(installer.verify(archive) is AndroidRecoveryPackageVerification.Ready)
        info.versionCode = 65
        assertEquals(AndroidRecoveryPackageVerification.Rejected, installer.verify(archive))
        info.versionCode = 66
        info.signatures = arrayOf(Signature(byteArrayOf(7)))
        assertEquals(AndroidRecoveryPackageVerification.Rejected, installer.verify(archive))
    }

    @Test
    @Config(sdk = [31])
    fun `local recovery package requires formal fork identity current signer and increasing version`() {
        val signature = Signature(byteArrayOf(4, 8, 15, 16, 23, 42))
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val info = PackageInfo().apply {
            packageName = "app.mihon.desktop.fork"
            versionName = "candidate"
            setLongVersionCode(66)
            signingInfo = mockk<SigningInfo> {
                every { apkContentsSigners } returns arrayOf(signature)
            }
        }
        val manager = mockk<PackageManager>()
        val context = mockk<Context> {
            every { packageManager } returns manager
        }
        val archive = temporary.newFile("candidate.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        every { manager.getPackageArchiveInfo(archive.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) } returns
            info
        val installer = AndroidRecoveryPackageInstaller(context, "app.mihon.desktop.fork", fingerprint, 65)
        assertTrue(installer.verify(archive) is AndroidRecoveryPackageVerification.Ready)
        info.packageName = "app.mihon.desktop.fork.dev"
        assertEquals(AndroidRecoveryPackageVerification.Rejected, installer.verify(archive))
        info.packageName = "app.mihon.desktop.fork"
        info.setLongVersionCode(65)
        assertEquals(AndroidRecoveryPackageVerification.Rejected, installer.verify(archive))
        info.setLongVersionCode(66)
        every { info.signingInfo!!.apkContentsSigners } returns arrayOf(Signature(byteArrayOf(9)))
        assertEquals(AndroidRecoveryPackageVerification.Rejected, installer.verify(archive))
    }

    @Test
    @Config(sdk = [31])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `verified local candidate reaches the system installer but changed identity cannot hand off`() = runBlocking {
        val signature = Signature(byteArrayOf(2, 4, 6))
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
        val info = PackageInfo().apply {
            packageName = "app.mihon.desktop.fork"
            setLongVersionCode(66)
            signingInfo = mockk<SigningInfo> { every { apkContentsSigners } returns arrayOf(signature) }
        }
        val manager = mockk<PackageManager>()
        val intents = mutableListOf<Intent>()
        val context = mockk<Context> {
            every { packageManager } returns manager
            every { startActivity(any()) } answers { intents += firstArg<Intent>() }
        }
        val archive = temporary.newFile("install.apk").apply { writeBytes(byteArrayOf(1)) }
        every { manager.getPackageArchiveInfo(archive.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) } returns
            info
        val installer = AndroidRecoveryPackageInstaller(
            context,
            "app.mihon.desktop.fork",
            fingerprint,
            65,
            packageUri = { Uri.parse("content://fixture/verified.apk") },
        )
        val ready = installer.verify(archive) as AndroidRecoveryPackageVerification.Ready
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            assertTrue(installer.install(ready))
            assertEquals(Intent.ACTION_VIEW, intents.single().action)
            assertEquals("application/vnd.android.package-archive", intents.single().type)
            assertTrue(intents.single().flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            info.packageName = "wrong.identity"
            assertEquals(false, installer.install(ready))
            assertEquals("a changed package must not reach the OS installer", 1, intents.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `unreadable or missing archive is rejected without opening system installer`() {
        val manager = mockk<PackageManager> {
            every { getPackageArchiveInfo(any(), any<Int>()) } returns null
        }
        val context = mockk<Context> { every { packageManager } returns manager }
        val installer = AndroidRecoveryPackageInstaller(context, "app.mihon.desktop.fork", "0".repeat(64), 65)
        assertEquals(AndroidRecoveryPackageVerification.Rejected, installer.verify(temporary.newFile("broken.apk")))
        assertEquals(
            AndroidRecoveryPackageVerification.Rejected,
            installer.verify(temporary.root.resolve("missing.apk")),
        )
    }
}
