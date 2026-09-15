package eu.kanade.tachiyomi.extension

import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/** Original signed historical APK -> original signed v1.6 APK, through the production manager and ART. */
class ExtensionUpgradePreservationInstrumentationTest {
    @Test
    fun privateUpgradeRejectsInvalidCandidatesPreservesOldSourceThenInstallsTrustedUpdate() = verify(
        BasePreferences.ExtensionInstaller.PRIVATE,
    )

    @Test
    fun systemUpgradeRejectsInvalidCandidatesPreservesOldSourceThenInstallsTrustedUpdate() = verify(
        BasePreferences.ExtensionInstaller.PACKAGEINSTALLER,
    )

    private fun verify(installer: BasePreferences.ExtensionInstaller) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun asset(name: String) = instrumentation.context.assets.open(name).use { it.readBytes() }
        val upgrade = asset("keiyoushi-mangadex-1.6.0.apk")
        val conflicting = asset("aex04-mangadex-conflicting-signer.apk")
        val malformed = "not an APK: AEX-04 truncated download".toByteArray(Charsets.UTF_8)
        assertEquals(NEW_SHA, digest(upgrade))
        assertEquals(CONFLICT_SHA, digest(conflicting))
        val lifecycle = ExtensionV16LifecycleInstrumentationTest()
        lifecycle.runLifecycle(
            installer,
            fixture = OLD,
            additionalApks = mapOf(
                "/upgrade.apk" to upgrade,
                "/conflict.apk" to conflicting,
                "/broken.apk" to malformed,
            ),
        ) { old, url ->
            val context = instrumentation.targetContext
            val manager = Injekt.get<ExtensionManager>()
            val sources = Injekt.get<SourceManager>()
            val system = installer == BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
            fun installedApk(): File = if (system) {
                File(
                    requireNotNull(
                        ExtensionLoader.getExtensionPackageInfoFromPkgName(context, OLD.packageName)?.applicationInfo,
                    ).sourceDir,
                )
            } else {
                File(context.filesDir, "exts/${OLD.packageName}.ext")
            }
            val oldIds = old.sources.map { it.id }.toSet()
            val oldEnglish = old.sources.single { it.lang == "en" } as HttpSource
            val manga = SManga.create().apply {
                this.url = "/manga/aex04-preserved"
                title = "AEX-04 preserved"
            }
            val oldMangaUrl = oldEnglish.getMangaUrl(manga)
            assertTrue(oldMangaUrl.contains("aex04-preserved"))
            val trustFile =
                File(
                    context.filesDir,
                    "extension-install-metadata/${if (system) "system" else "private"}-${OLD.packageName}.properties",
                )
            val oldTrust = trustFile.readBytes().toList()
            val candidate = Extension.Available(
                name = "MangaDex", pkgName = OLD.packageName, versionName = "1.6.0",
                versionCode = 106000, libVersion = 1.6, lang = "all", isNsfw = true,
                sources = emptyList(), apkName = "upgrade.apk", iconUrl = "", repoUrl = url,
                repoName = "AEX-04 fixed fixture", repoFingerprint = OLD.signer,
                declaredSha256 = NEW_SHA, downloadUrl = "$url/upgrade.apk",
            )
            val failures = listOf(
                "incorrect-hash" to candidate.copy(declaredSha256 = "0".repeat(64)),
                "malformed-apk" to candidate.copy(downloadUrl = "$url/broken.apk", declaredSha256 = digest(malformed)),
                // This is a valid APK signed with the existing controlled test key, not a broken signature.
                "conflicting-signer" to candidate.copy(
                    downloadUrl = "$url/conflict.apk",
                    declaredSha256 = CONFLICT_SHA,
                ),
            )
            for ((reason, rejected) in failures) {
                assertEquals(
                    reason,
                    InstallStep.Error,
                    withTimeout(30_000) {
                        manager.installExtension(rejected).first(InstallStep::isCompleted)
                    },
                )
                assertEquals(reason, OLD.sha256, digest(installedApk().readBytes()))
                assertEquals(reason, oldTrust, trustFile.readBytes().toList())
                val retained = manager.installedExtensionsFlow.value.single { it.pkgName == OLD.packageName }
                assertEquals(OLD.versionCode, retained.versionCode)
                assertEquals(oldIds, retained.sources.map { it.id }.toSet())
                // A late OS package-added broadcast may reload the same APK after initial installation.
                // Require the current production instance, not an obsolete pre-broadcast object.
                withTimeout(15_000) {
                    sources.querySources.first { entries ->
                        retained.sources.all { source -> entries.any { it === source } }
                    }
                }
                for (source in retained.sources) assertSame(source, sources.get(source.id))
                assertEquals(oldMangaUrl, (sources.get(oldEnglish.id) as HttpSource).getMangaUrl(manga))
                println(
                    "AEX04_UPGRADE_PRESERVED installer=$installer reason=$reason sha256=${OLD.sha256} sources=${oldIds.size}",
                )
            }
            val terminal = withTimeout(60_000) {
                coroutineScope {
                    val result = async { manager.installExtension(candidate).first(InstallStep::isCompleted) }
                    // Android may approve updates by the same installer without another dialog.
                    // If it asks, use the normal confirmation; never adopt install privileges.
                    val confirmation = if (system) {
                        launch { lifecycle.clickInstallerButton(setOf("Update", "Install")) }
                    } else {
                        null
                    }
                    try {
                        result.await()
                    } finally {
                        confirmation?.cancelAndJoin()
                    }
                }
            }
            assertEquals(InstallStep.Installed, terminal)
            val updated = withTimeout(15_000) {
                manager.installedExtensionsFlow.first { entries ->
                    entries.any { it.pkgName == OLD.packageName && it.versionCode == 106000L }
                }.single { it.pkgName == OLD.packageName }
            }
            withTimeout(15_000) {
                sources.querySources.first { entries ->
                    updated.sources.all { source -> entries.any { it === source } }
                }
            }
            assertEquals(NEW_SHA, digest(installedApk().readBytes()))
            val newTrust = Properties().apply { trustFile.inputStream().use(::load) }
            assertEquals(NEW_SHA, newTrust.getProperty("artifact.sha256"))
            assertEquals(url, newTrust.getProperty("repository.baseUrl"))
            assertEquals(OLD.signer, newTrust.getProperty("repository.fingerprint"))
            assertEquals(oldIds, updated.sources.map { it.id }.toSet())
            assertEquals(1.6, updated.libVersion, 0.0)
            assertEquals(system, updated.isShared)
            val newEnglish = updated.sources.single { it.lang == "en" } as HttpSource
            assertEquals(oldEnglish.id, newEnglish.id)
            assertNotSame(oldEnglish, newEnglish)
            assertSame(newEnglish, sources.get(newEnglish.id))
            assertEquals(oldMangaUrl, newEnglish.getMangaUrl(manga))
            assertTrue(updated.sources.all { sources.get(it.id) === it })
            println(
                "AEX04_UPGRADE_INSTALLED installer=$installer old=${OLD.versionName} new=${updated.versionName} sha256=$NEW_SHA sources=${updated.sources.size}",
            )
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val NEW_SHA = "35d220b64162cb9409da47af81fbb09ae96f170ed77eaa0ffb440add65f92f35"
        const val CONFLICT_SHA = "734f3181871394486aba5e2da4842913d244ef0e3d2da6a5c022ebbfe05f5ec5"
        val OLD = LifecycleApkFixture(
            "keiyoushi-mangadex-1.4.211.apk",
            "eff4ee157380f0cd4f19a2150f93220ca7a9bcd4e5d570736f639230ef338236",
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
            "eu.kanade.tachiyomi.extension.all.mangadex",
            "MangaDex",
            "1.4.211",
            211,
            1.4,
        )
    }
}
