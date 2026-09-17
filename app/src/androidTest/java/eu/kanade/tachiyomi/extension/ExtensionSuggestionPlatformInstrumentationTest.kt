package eu.kanade.tachiyomi.extension

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.util.ExtensionInstallActivity
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.domain.extension.suggestion.SuggestionBatchPause
import mihon.domain.extension.suggestion.SuggestionBatchResult
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** Real favorites/catalog → application-owned batch → production adapters → OS confirmation. */
class ExtensionSuggestionPlatformInstrumentationTest {
    @Test fun legacyContinuousBatch() = runBatch(BasePreferences.ExtensionInstaller.LEGACY)

    @Test fun packageInstallerContinuousBatch() = runBatch(BasePreferences.ExtensionInstaller.PACKAGEINSTALLER)

    @Test fun legacyCancelThenExplicitResume() = runBatch(BasePreferences.ExtensionInstaller.LEGACY, cancel = true)

    @Test fun packageInstallerCancelThenExplicitResume() = runBatch(
        BasePreferences.ExtensionInstaller.PACKAGEINSTALLER,
        cancel = true,
    )

    @Test fun legacyStopAfterSubmissionKeepsActualResult() = runBatch(
        BasePreferences.ExtensionInstaller.LEGACY,
        stop = true,
    )

    @Test fun packageInstallerStopAfterSubmissionKeepsActualResult() = runBatch(
        BasePreferences.ExtensionInstaller.PACKAGEINSTALLER,
        stop = true,
    )

    @Test fun privateContinuousBatch() = runBatch(BasePreferences.ExtensionInstaller.PRIVATE)

    @Test fun shizukuContinuousBatch() = runBatch(BasePreferences.ExtensionInstaller.SHIZUKU)

    @Test fun shizukuUnavailablePausesWithoutSwitchingInstaller() =
        runBatch(BasePreferences.ExtensionInstaller.SHIZUKU, expectedPause = SuggestionBatchPause.SERVICE)

    @Test fun shizukuRevokedPermissionPausesWithoutSwitchingInstaller() =
        runBatch(BasePreferences.ExtensionInstaller.SHIZUKU, expectedPause = SuggestionBatchPause.PERMISSION)

    @Test fun packageInstallerBackgroundWaitsForExplicitForegroundResume() =
        runBatch(BasePreferences.ExtensionInstaller.PACKAGEINSTALLER, foregroundResume = true)

    @Test fun legacyInstallationAndRemovalWindowsSurviveActivityRecreation() =
        runBatch(BasePreferences.ExtensionInstaller.LEGACY, recreate = true)

    @Test fun legacyWindowAcrossProcessDeath() = runBlocking {
        when (InstrumentationRegistry.getArguments().getString("eisWindowPhase")) {
            "prepare" -> runBatch(BasePreferences.ExtensionInstaller.LEGACY, processDeath = true)
            "verify" -> verifyProcessWindow()
            else -> error("Explicit prepare/verify phase required")
        }
    }

    @Test fun packageInstallerWindowAcrossProcessDeath() = runBlocking {
        when (InstrumentationRegistry.getArguments().getString("eisWindowPhase")) {
            "prepare" -> runBatch(BasePreferences.ExtensionInstaller.PACKAGEINSTALLER, processDeath = true)
            "inspect", "resolve" -> {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
                check(context.packageName == "app.mihon.eis.dev")
                val sessions = context.packageManager.packageInstaller.mySessions
                sessions.forEach {
                    println(
                        "EIS_SESSION_OBSERVED id=${it.sessionId} package=${it.appPackageName} " +
                            "installer=${it.installerPackageName}",
                    )
                }
                val session = sessions.single()
                val packageName = checkNotNull(session.appPackageName)
                val receipt = context.getSharedPreferences("eis-process-window", Context.MODE_PRIVATE)
                check(receipt.getString("uuid", null) == "session:${session.sessionId}")
                check(packageName == ExtensionV16LifecycleInstrumentationTest.V16_FIXTURE.packageName)
                check(session.installerPackageName == context.packageName)

                val manager = Injekt.get<ExtensionManager>()
                withTimeout(30_000) { manager.isInitialized.first { it } }
                assertTrue(
                    "Original pending session must reserve $packageName before suggestions become ready",
                    manager.installArbiter.isBusy(packageName),
                )
                if (InstrumentationRegistry.getArguments().getString("eisWindowPhase") == "resolve") {
                    ExtensionV16LifecycleInstrumentationTest().clickInstallerButton(setOf("Install"))
                    withTimeout(30_000) {
                        while (manager.installArbiter.isBusy(packageName) || !manager.inventory.value.initialized ||
                            packageName !in manager.inventory.value.records
                        ) {
                            delay(50)
                        }
                    }
                    println("EIS_SESSION_RESOLVED id=${session.sessionId} package=$packageName inventoryPublished=true")
                    verifyProcessWindow()
                }
            }
            "verify" -> verifyProcessWindow()
            else -> error("Explicit prepare/verify phase required")
        }
    }

    private fun runBatch(
        installer: BasePreferences.ExtensionInstaller,
        cancel: Boolean = false,
        stop: Boolean = false,
        expectedPause: SuggestionBatchPause? = null,
        recreate: Boolean = false,
        foregroundResume: Boolean = false,
        processDeath: Boolean = false,
    ) = runBlocking {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Dedicated emulator required" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.mihon.eis.dev") { "Isolated EIS acceptance identity required" }
        val manager = Injekt.get<ExtensionManager>()
        val batch = Injekt.get<AndroidExtensionSuggestionBatch>()
        val repositories = Injekt.get<ExtensionRepoRepository>()
        val mangas = Injekt.get<MangaRepository>()
        val preference = Injekt.get<BasePreferences>().extensionInstaller()
        val previous = preference.get()
        val previouslySet = preference.isSet()
        val system = installer != BasePreferences.ExtensionInstaller.PRIVATE
        val confirmation =
            installer == BasePreferences.ExtensionInstaller.LEGACY ||
                installer == BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
        if (confirmation) check(context.packageManager.canRequestPackageInstalls())
        check(!batch.state.value.running)
        val fixtures = listOf(ExtensionV16LifecycleInstrumentationTest.V16_FIXTURE, V15)
        fixtures.forEach {
            check(!File(context.filesDir, "exts/${it.packageName}.ext").exists())
            check(ExtensionLoader.getExtensionPackageInfoFromPkgName(context, it.packageName) == null)
        }
        val bytes = fixtures.map { fixture ->
            instrumentation.context.assets.open(fixture.asset).use { it.readBytes() }.also {
                assertEquals(fixture.sha256, digest(it))
            }
        }
        // Probe controlled APKs for actual IDs before installation; no invented source IDs or fake suggestions flow.
        val sources = fixtures.indices.map { index ->
            val file = File.createTempFile("eis-source-probe-", ".apk", context.cacheDir)
            try {
                file.writeBytes(bytes[index])
                check(file.setReadOnly())
                val loader = ChildFirstPathClassLoader(file.absolutePath, null, checkNotNull(javaClass.classLoader))
                val entry = loader.loadClass(
                    if (index == 0) {
                        "aex00.external.v16.V16SourceFactory"
                    } else {
                        "aex00.external.v15.LegacySuspendOnlySource"
                    },
                ).getDeclaredConstructor().newInstance()
                if (entry is SourceFactory) entry.createSources() else listOf(entry as Source)
            } finally {
                check(file.delete())
            }
        }
        val ownedRows = mutableListOf<Manga>()
        val helper = ExtensionV16LifecycleInstrumentationTest()
        val serverFailure = AtomicReference<Throwable?>()
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val url = "http://127.0.0.1:${server.localPort}"
            check(repositories.getRepo(url) == null)
            val catalog = buildJsonObject {
                put("name", "EIS batch fixture")
                put("badgeLabel", "EIS")
                put("signingKey", fixtures.first().signer)
                put("contact", buildJsonObject { put("website", url) })
                put(
                    "extensionList",
                    buildJsonObject {
                        put(
                            "extensions",
                            JsonArray(
                                fixtures.mapIndexed {
                                        index,
                                        fixture,
                                    ->
                                    buildJsonObject {
                                        put("name", fixture.name)
                                        put("packageName", fixture.packageName)
                                        put(
                                            "resources",
                                            buildJsonObject {
                                                put(
                                                    "apkUrl",
                                                    "$url/$index.apk",
                                                )
                                                put(
                                                    "iconUrl",
                                                    "$url/icon.png",
                                                )
                                            },
                                        )
                                        put("extensionLib", fixture.libVersion.toString())
                                        put("versionCode", fixture.versionCode)
                                        put("versionName", fixture.versionName)
                                        put("contentWarning", "CONTENT_WARNING_SAFE")
                                        put(
                                            "sources",
                                            JsonArray(
                                                sources[index].map { source ->
                                                    buildJsonObject {
                                                        put("id", source.id)
                                                        put("name", source.name)
                                                        put("language", source.lang)
                                                    }
                                                },
                                            ),
                                        )
                                    }
                                },
                            ),
                        )
                    },
                )
            }.toString().toByteArray(Charsets.UTF_8)
            val worker = Thread {
                try {
                    while (!server.isClosed) {
                        server.accept().use { socket ->
                            socket.soTimeout = 15_000
                            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val path = reader.readLine().split(' ')[1]
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            val body = when (path) {
                                "/index_v2.json" -> catalog
                                "/0.apk" -> bytes[0]
                                "/1.apk" -> bytes[1]
                                else -> byteArrayOf()
                            }
                            socket.getOutputStream().use { output ->
                                output.write(
                                    (
                                        "HTTP/1.1 ${if (body.isEmpty()) "404 Not Found" else "200 OK"}\r\n" +
                                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                        ).toByteArray(Charsets.US_ASCII),
                                )
                                output.write(body)
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    if (!server.isClosed) serverFailure.set(failure)
                }
            }.apply {
                isDaemon = true
                start()
            }
            var originalFailure: Throwable? = null
            try {
                preference.set(installer)
                context.startActivity(
                    requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                if (confirmation) awaitForeground(true)
                withTimeout(30_000) { manager.isInitialized.first { it } }
                repositories.insertRepo(
                    ExtensionRepo(
                        url,
                        "EIS batch fixture",
                        null,
                        url,
                        fixtures.first().signer,
                        indexUrl = "$url/index_v2.json",
                    ),
                )
                sources.flatten().forEach { source ->
                    val mangaUrl = "/eis/batch/${source.id}"
                    check(mangas.getMangaByUrlAndSourceId(mangaUrl, source.id) == null)
                    ownedRows += mangas.insertNetworkManga(
                        listOf(
                            Manga.create().copy(
                                source = source.id,
                                url = mangaUrl,
                                title = "EIS batch fixture",
                                favorite = true,
                            ),
                        ),
                    ).single()
                }
                manager.findAvailableExtensions()
                val snapshot = withTimeout(30_000) {
                    batch.suggestions.first { value ->
                        !value.isLoading &&
                            fixtures.all { fixture ->
                                value.suggestions.any { it.artifact.packageName == fixture.packageName }
                            }
                    }
                }.let { value ->
                    fixtures.map { fixture ->
                        value.suggestions.single {
                            it.artifact.packageName ==
                                fixture.packageName
                        }.artifact
                    }
                }
                if (foregroundResume) {
                    awaitForeground(true)
                    instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    awaitForeground(false)
                }
                assertTrue(batch.start(snapshot))
                if (foregroundResume) {
                    val paused = withTimeout(30_000) { batch.state.first { !it.running } }
                    assertEquals("Background state: $paused", SuggestionBatchPause.APP_FOREGROUND, paused.pauseReason)
                    assertTrue(paused.items.none { it.result == SuggestionBatchResult.Installed })
                    context.startActivity(
                        requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    awaitForeground(true)
                    assertEquals(paused, batch.state.value)
                    assertTrue(batch.resume(paused.remaining))
                }
                if (confirmation) {
                    helper.awaitInstallerButton(setOf("Install"))
                    if (processDeath) {
                        var uuid: String? = null
                        if (installer == BasePreferences.ExtensionInstaller.PACKAGEINSTALLER) {
                            val session = context.packageManager.packageInstaller.mySessions.single()
                            uuid = "session:${session.sessionId}"
                        } else {
                            instrumentation.runOnMainSync {
                                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                                uuid = Stage.values()
                                    .flatMap { monitor.getActivitiesInStage(it) }
                                    .filterIsInstance<ExtensionInstallActivity>()
                                    .single { !it.isFinishing && !it.isDestroyed && it.intent.action == null }
                                    .intent.getStringExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID)
                            }
                        }
                        checkNotNull(uuid)
                        val receipt = context.getSharedPreferences("eis-process-window", Context.MODE_PRIVATE)
                        check(!receipt.contains("pid"))
                        check(
                            receipt.edit().putInt("pid", Process.myPid()).putString("uuid", uuid)
                                .putString(
                                    "repo",
                                    url,
                                ).putString(
                                    "rows",
                                    ownedRows.joinToString(",") { "${it.id}:${it.source}" },
                                )
                                .putString(
                                    "previousInstaller",
                                    previous.name,
                                ).putBoolean(
                                    "previouslySet",
                                    previouslySet,
                                ).commit(),
                        )
                        println(
                            "EIS_WINDOW_PREPARED pid=${Process.myPid()} uuid=$uuid package=${fixtures.first().packageName}",
                        )
                        kotlinx.coroutines.awaitCancellation()
                    }
                    assertEquals(
                        null,
                        ExtensionLoader.getExtensionPackageInfoFromPkgName(
                            context,
                            fixtures[1].packageName,
                        ),
                    )
                    if (recreate) recreateSystemBridge()
                    if (stop) batch.stop()
                    helper.clickInstallerButton(if (cancel) setOf("Cancel") else setOf("Install"))
                    if (cancel) {
                        val paused = withTimeout(30_000) { batch.state.first { !it.running } }
                        assertEquals(SuggestionBatchPause.CONFIRMATION_CANCELLED, paused.pauseReason)
                        assertEquals(SuggestionBatchResult.Cancelled, paused.items.first().result)
                        assertEquals(snapshot.drop(1), paused.remaining)
                        delay(750)
                        assertEquals(paused, batch.state.value)
                        assertTrue(batch.resume(paused.remaining))
                    } else {
                        withTimeout(30_000) {
                            batch.state.first {
                                it.items.first().result ==
                                    SuggestionBatchResult.Installed
                            }
                        }
                    }
                    if (!stop) helper.clickInstallerButton(setOf("Install"))
                }
                val completed = withTimeout(60_000) { batch.state.first { !it.running } }
                serverFailure.get()?.let { throw AssertionError("Fixture HTTP failed", it) }
                if (expectedPause != null) {
                    assertEquals(expectedPause, completed.pauseReason)
                    assertTrue(completed.remaining.isNotEmpty())
                    assertEquals(installer, preference.get())
                }
                val expected = if (expectedPause !=
                    null
                ) {
                    emptyList()
                } else if (cancel) {
                    listOf(fixtures[1])
                } else if (stop) {
                    listOf(fixtures[0])
                } else {
                    fixtures
                }
                assertEquals(
                    "Batch result: $completed; errors=${manager.installErrors.value}",
                    expected.size,
                    completed.items.count { it.result == SuggestionBatchResult.Installed },
                )
                for (fixture in fixtures) {
                    if (fixture !in expected) {
                        assertEquals(
                            null,
                            ExtensionLoader.getExtensionPackageInfoFromPkgName(
                                context,
                                fixture.packageName,
                            ),
                        )
                        assertFalse(File(context.filesDir, "exts/${fixture.packageName}.ext").exists())
                        continue
                    }
                    val installed = withTimeout(15_000) {
                        manager.installedExtensionsFlow.first { entries ->
                            entries.any { it.pkgName == fixture.packageName }
                        }
                    }.single { it.pkgName == fixture.packageName }
                    assertEquals(system, installed.isShared)
                    assertEquals(
                        sources[fixtures.indexOf(fixture)].map { it.id }.toSet(),
                        installed.sources.map { it.id }.toSet(),
                    )
                    val apk = if (system) {
                        File(
                            requireNotNull(
                                ExtensionLoader.getExtensionPackageInfoFromPkgName(
                                    context,

                                    fixture.packageName,
                                )?.applicationInfo,
                            ).sourceDir,
                        )
                    } else {
                        File(
                            context.filesDir,
                            "exts/${fixture.packageName}.ext",
                        )
                    }
                    assertEquals(fixture.sha256, digest(apk.readBytes()))
                }
                println(
                    "EIS_BATCH_VERIFIED installer=$installer cancel=$cancel stop=$stop " +
                        "installed=${expected.size} batch=${completed.id}",
                )
            } catch (failure: Throwable) {
                originalFailure = failure
                throw failure
            } finally {
                try {
                    check(!batch.state.value.running) {
                        "Batch still active; retain owned fixture for diagnosis instead of racing cleanup"
                    }
                    for (fixture in fixtures) {
                        manager.installedExtensionsFlow.value.singleOrNull {
                            it.pkgName ==
                                fixture.packageName
                        }?.let { installed ->
                            val apk = if (installed.isShared) {
                                File(
                                    requireNotNull(
                                        ExtensionLoader.getExtensionPackageInfoFromPkgName(
                                            context,

                                            fixture.packageName,
                                        )?.applicationInfo,
                                    ).sourceDir,
                                )
                            } else {
                                File(
                                    context.filesDir,
                                    "exts/${fixture.packageName}.ext",
                                )
                            }
                            check(digest(apk.readBytes()) == fixture.sha256)
                            manager.uninstallExtension(installed)
                            if (installed.isShared) {
                                helper.awaitInstallerButton(setOf("OK", "Uninstall"))
                                if (recreate) recreateSystemBridge(Intent.ACTION_UNINSTALL_PACKAGE)
                                helper.clickInstallerButton(setOf("OK", "Uninstall"))
                            }
                            withTimeout(15_000) {
                                manager.installedExtensionsFlow.first { entries ->
                                    entries.none {
                                        it.pkgName ==
                                            fixture.packageName
                                    }
                                }
                            }
                        }
                    }
                    val driver = Injekt.get<SqlDriver>()
                    for (row in ownedRows) {
                        Injekt.get<DatabaseHandler>().await(inTransaction = true) {
                            driver.execute(null, "DELETE FROM mangas WHERE _id = ? AND source = ? AND url = ?", 3) {
                                bindLong(0, row.id)
                                bindLong(1, row.source)
                                bindString(2, row.url)
                            }
                        }
                    }
                    repositories.deleteRepo(url)
                } catch (cleanupFailure: Throwable) {
                    if (originalFailure != null) originalFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
                } finally {
                    if (previouslySet) preference.set(previous) else preference.delete()
                    server.close()
                    worker.join(5_000)
                }
            }
        }
    }

    private suspend fun verifyProcessWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.mihon.eis.dev")
        val receipt = context.getSharedPreferences("eis-process-window", Context.MODE_PRIVATE)
        val previousPid = receipt.getInt("pid", -1)
        check(previousPid > 0 && previousPid != Process.myPid())
        val manager = Injekt.get<ExtensionManager>()
        val batch = Injekt.get<AndroidExtensionSuggestionBatch>()
        withTimeout(30_000) { manager.isInitialized.first { it } }
        withTimeout(30_000) { manager.inventory.first { it.initialized } }
        check(batch.state.value.items.isEmpty() && !batch.state.value.running) { "Process restart replayed the batch" }
        val fixture = ExtensionV16LifecycleInstrumentationTest.V16_FIXTURE
        val info = checkNotNull(ExtensionLoader.getExtensionPackageInfoFromPkgName(context, fixture.packageName))
        assertEquals(fixture.sha256, digest(File(checkNotNull(info.applicationInfo).sourceDir).readBytes()))
        assertTrue(fixture.packageName in manager.inventory.value.records)
        assertEquals(null, ExtensionLoader.getExtensionPackageInfoFromPkgName(context, V15.packageName))
        println(
            "EIS_WINDOW_VERIFIED preparePid=$previousPid verifyPid=${Process.myPid()} " +
                "uuid=${receipt.getString("uuid", null)} noReplay=true",
        )
        // Only the precise signed fixture and rows recorded by this interrupted run are owned.
        val extension = (manager.installedExtensionsFlow.value + manager.untrustedExtensionsFlow.value)
            .single { it.pkgName == fixture.packageName }
        manager.uninstallExtension(extension)
        ExtensionV16LifecycleInstrumentationTest().clickInstallerButton(setOf("OK", "Uninstall"))
        withTimeout(15_000) {
            while (ExtensionLoader.getExtensionPackageInfoFromPkgName(context, fixture.packageName) != null) delay(50)
        }
        val driver = Injekt.get<SqlDriver>()
        receipt.getString("rows", "")!!.split(',').forEach { encoded ->
            val (id, source) = encoded.split(':').map(String::toLong)
            Injekt.get<DatabaseHandler>().await(inTransaction = true) {
                driver.execute(
                    null,
                    "DELETE FROM mangas WHERE _id = ? AND source = ? AND url = ? AND title = 'EIS batch fixture'",
                    3,
                ) {
                    bindLong(0, id)
                    bindLong(1, source)
                    bindString(2, "/eis/batch/$source")
                }
            }
        }
        Injekt.get<ExtensionRepoRepository>().deleteRepo(checkNotNull(receipt.getString("repo", null)))
        val preference = Injekt.get<BasePreferences>().extensionInstaller()
        if (receipt.getBoolean("previouslySet", false)) {
            preference.set(
                BasePreferences.ExtensionInstaller.valueOf(
                    checkNotNull(
                        receipt.getString(
                            "previousInstaller",
                            null,
                        ),
                    ),
                ),
            )
        } else {
            preference.delete()
        }
        check(receipt.edit().clear().commit())
    }

    private suspend fun awaitForeground(expected: Boolean) = withTimeout(15_000) {
        while (androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) != expected
        ) {
            delay(50)
        }
    }

    private suspend fun recreateSystemBridge(expectedAction: String? = null) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        fun activities() = Stage.values()
            .filter {
                it != Stage.DESTROYED &&
                    it != Stage.PRE_ON_CREATE
            }
            .flatMap { monitor.getActivitiesInStage(it) }
            .filterIsInstance<ExtensionInstallActivity>()
            .distinct().filter { !it.isFinishing && !it.isDestroyed && it.intent.action == expectedAction }
        lateinit var original: ExtensionInstallActivity
        instrumentation.runOnMainSync {
            original = activities().single()
            original.recreate()
        }
        val id = original.intent.getStringExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID)
        withTimeout(15_000) {
            while (true) {
                var replaced = false
                instrumentation.runOnMainSync {
                    replaced = activities().any {
                        it !== original &&
                            it.intent.getStringExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID) == id
                    }
                }
                if (replaced) break
                delay(50)
            }
        }
        println("EIS_ACTIVITY_RECREATED action=${original.intent.action} transaction=$id")
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }

    private companion object {
        val V15 = LifecycleApkFixture(
            "aex00-external-v15-controlled-sample.apk",
            "caf80d849e2eb5ad8f0be5121f914d9cee1ee06c15653d15f33321602183a316",
            ExtensionV16LifecycleInstrumentationTest.SIGNER,
            "aex00.external.v15.controlled",
            "AEX-00 v1.5 controlled",
            "1.5.0",
            150000,
            1.5,
        )
    }
}
