package eu.kanade.tachiyomi.extension

import android.os.Build
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Physical ARM gate: fixed ABI probes only, never installer, trust, database or network-setting fixtures.
 * Probes load their APKs from the isolated fork's temporary storage; no system extension is replaced.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    ExtensionV16SourceAbiInstrumentationTest::class,
    ExtensionZstdReleaseAbiInstrumentationTest::class,
)
class ExtensionArmReleaseAbiInstrumentationTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun verifyPhysicalArmRelease() {
            check(Build.HARDWARE != "ranchu" && Build.HARDWARE != "goldfish") { "Physical ARM device required" }
            check(Build.SUPPORTED_ABIS.first() in setOf("arm64-v8a", "armeabi-v7a")) { "ARM runtime required" }
            ExtensionReleaseParityInstrumentationTest.verifyReleaseArtifact()
        }
    }
}
