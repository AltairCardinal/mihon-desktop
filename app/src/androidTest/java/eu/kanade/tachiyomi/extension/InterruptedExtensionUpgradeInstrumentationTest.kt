package eu.kanade.tachiyomi.extension

import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest

/** Host must force-stop after the durable request receipt, then invoke the verify phase. */
class InterruptedExtensionUpgradeInstrumentationTest {
    @Test
    fun interruptedDownloadPreservesInstalledExtensionAndCanRetryAfterRestart() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val phase = InstrumentationRegistry.getArguments().getString("aex05InterruptPhase")
        assumeTrue(context.packageName == "app.mihon.aex05.dev" && phase in setOf("prepare", "verify"))
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val receipt = context.getSharedPreferences("aex05-interrupted-upgrade", 0)
        val apk = File(context.filesDir, "exts/$PACKAGE.ext")
        val trust = File(context.filesDir, "extension-install-metadata/private-$PACKAGE.properties")
        val manager = Injekt.get<ExtensionManager>()
        val sourceManager = Injekt.get<SourceManager>()
        if (phase == "prepare") {
            check(!receipt.contains("pid"))
            // The preceding cross-signature fixture owns this saved repository. Its backup remains
            // outside the application; remove only this fixture row before the helper adds the same key.
            val repositories = Injekt.get<ExtensionRepoRepository>()
            repositories.getRepo("http://127.0.0.1:18965")?.let { previous ->
                check(previous.name == "AEX05 repo" && previous.signingKeyFingerprint == SIGNER)
                check(File(context.getExternalFilesDir("aex05-migration"), "library.tachibk").isFile)
                repositories.deleteRepo(previous.baseUrl)
            }
            ExtensionV16LifecycleInstrumentationTest().runLifecycle(
                BasePreferences.ExtensionInstaller.PRIVATE,
                fixture = LifecycleApkFixture(
                    "keiyoushi-mangadex-1.4.211.apk",
                    OLD_SHA,
                    SIGNER,
                    PACKAGE,
                    "MangaDex",
                    "1.4.211",
                    211,
                    1.4,
                ),
            ) { old, repository ->
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    val worker = Thread {
                        server.accept().use { socket ->
                            socket.soTimeout = 60_000
                            val input = socket.getInputStream().bufferedReader()
                            check(input.readLine().startsWith("GET /upgrade.apk "))
                            while (!input.readLine().isNullOrEmpty()) Unit
                            check(
                                receipt.edit().putInt("pid", Process.myPid())
                                    .putString("repository", repository)
                                    .putString("trustSha", digest(trust.readBytes()))
                                    .putStringSet("sourceIds", old.sources.map { it.id.toString() }.toSet())
                                    .commit(),
                            )
                            // The host kills this process while the production HTTP request is blocked.
                            println("AEX05_INTERRUPT_READY pid=${Process.myPid()}")
                            socket.getInputStream().read()
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                    try {
                        manager.installExtension(candidate(repository, server.localPort))
                            .first(InstallStep::isCompleted)
                        error("Expected host force-stop while upgrade request is in flight")
                    } finally {
                        server.close()
                        worker.join(1_000)
                    }
                }
            }
            return@runBlocking
        }
        check(receipt.contains("pid"))
        assertNotEquals(receipt.getInt("pid", -1), Process.myPid())
        assertEquals(OLD_SHA, digest(apk.readBytes()))
        assertEquals(receipt.getString("trustSha", null), digest(trust.readBytes()))
        val old = withTimeout(15_000) {
            manager.installedExtensionsFlow.first { entries -> entries.any { it.pkgName == PACKAGE } }
                .single { it.pkgName == PACKAGE }
        }
        assertEquals(211L, old.versionCode)
        assertEquals(receipt.getStringSet("sourceIds", emptySet()), old.sources.map { it.id.toString() }.toSet())
        withTimeout(15_000) {
            sourceManager.querySources.first { entries -> old.sources.all { source -> entries.any { it === source } } }
        }
        assertTrue(old.sources.all { sourceManager.get(it.id) === it })
        val bytes = instrumentation.context.assets.open("keiyoushi-mangadex-1.6.0.apk").use { it.readBytes() }
        assertEquals(NEW_SHA, digest(bytes))
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 15_000
                    val input = socket.getInputStream().bufferedReader()
                    check(input.readLine().startsWith("GET /upgrade.apk "))
                    while (!input.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().use { output ->
                        output.write(
                            "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII),
                        )
                        output.write(bytes)
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            try {
                assertEquals(
                    InstallStep.Installed,
                    withTimeout(60_000) {
                        manager.installExtension(
                            candidate(requireNotNull(receipt.getString("repository", null)), server.localPort),
                        ).first(InstallStep::isCompleted)
                    },
                )
            } finally {
                server.close()
                worker.join(5_000)
            }
        }
        val updated = withTimeout(15_000) {
            manager.installedExtensionsFlow.first { entries ->
                entries.any { it.pkgName == PACKAGE && it.versionCode == 106000L }
            }.single { it.pkgName == PACKAGE }
        }
        assertEquals(old.sources.map { it.id }.toSet(), updated.sources.map { it.id }.toSet())
        assertEquals(NEW_SHA, digest(apk.readBytes()))
        println("AEX05_INTERRUPT_RECOVERED oldPid=${receipt.getInt("pid", -1)} newPid=${Process.myPid()}")
        // Keep the owned installed fixture for subsequent release/reader checks, not user data.
    }

    private fun candidate(repository: String, port: Int) = Extension.Available(
        name = "MangaDex", pkgName = PACKAGE, versionName = "1.6.0", versionCode = 106000,
        libVersion = 1.6, lang = "all", isNsfw = true, sources = emptyList(), apkName = "upgrade.apk",
        iconUrl = "", repoUrl = repository, repoName = "AEX-04 fixed fixture", repoFingerprint = SIGNER,
        declaredSha256 = NEW_SHA, downloadUrl = "http://127.0.0.1:$port/upgrade.apk",
    )

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PACKAGE = "eu.kanade.tachiyomi.extension.all.mangadex"
        const val SIGNER = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        const val OLD_SHA = "eff4ee157380f0cd4f19a2150f93220ca7a9bcd4e5d570736f639230ef338236"
        const val NEW_SHA = "35d220b64162cb9409da47af81fbb09ae96f170ed77eaa0ffb440add65f92f35"
    }
}
