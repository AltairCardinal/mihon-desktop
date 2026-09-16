package eu.kanade.tachiyomi.extension

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Suite
import java.io.File
import java.security.MessageDigest

/**
 * Public extension ABI gate for the signed, R8-optimized release artifact.
 * Lifecycle and Compose white-box contracts run on debug; release lifecycle acceptance uses external UI tests.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    ExtensionV16SourceAbiInstrumentationTest::class,
    ExtensionZstdReleaseAbiInstrumentationTest::class,
)
class ExtensionReleaseParityInstrumentationTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun verifyReleaseArtifactBeforeChangingFixtures() {
            check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") {
                "This fixture suite is restricted to a dedicated emulator; physical-device acceptance is separate"
            }
            verifyReleaseArtifact()
        }

        fun verifyReleaseArtifact() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            check(context.packageName == "app.mihon.desktop.fork") { "Not the isolated fork release host" }
            check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) { "Debug host rejected" }
            val expected = InstrumentationRegistry.getArguments().getString("aex06ReleaseSha256")
            check(expected != null && expected.matches(Regex("[0-9a-f]{64}"))) {
                "Supply the independently verified signed release APK SHA-256"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            File(context.applicationInfo.sourceDir).inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().hex()
            check(actual == expected) { "Installed host hash differs from the declared release artifact: $actual" }
            val manager = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val info = manager.getPackageInfo(context.packageName, flags)
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                checkNotNull(info.signingInfo).apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            val signers = signatures.orEmpty().map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
            }
            check(signers == listOf("bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3")) {
                "Host does not use the fork release certificate"
            }
            println(
                "AEX06_RELEASE_VERIFIED sha256=$actual api=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.first()}",
            )
        }

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
