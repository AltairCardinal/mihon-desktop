package eu.kanade.tachiyomi.extension

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Process
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.model.toAvailable
import eu.kanade.tachiyomi.extension.util.ExtensionInstallActivity
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.service.ExtensionInstallBusy
import mihon.domain.extension.suggestion.ExtensionSuggestionPreferences
import mihon.domain.extension.suggestion.SuggestionBatchPause
import mihon.domain.extension.suggestion.SuggestionBatchResult
import mihon.domain.extension.suggestion.SuggestionIdentity
import mihon.domain.extension.suggestion.suggestionIdentityKey
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference

/** Real favorites/catalog → application-owned batch → production adapters → OS confirmation. */
class ExtensionSuggestionPlatformInstrumentationTest {
    @Test fun releaseUserActionsAcrossProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("eisUserActionsPhase")
        check(phase == "prepare" || phase == "verify") { "Explicit prepare/verify phase required" }
        runBatch(BasePreferences.ExtensionInstaller.PRIVATE, userActionsPhase = phase)
    }

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
        userActionsPhase: String? = null,
    ) = runBlocking {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Dedicated emulator required" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val forkReleaseAcceptance = InstrumentationRegistry.getArguments()
            .getString("eisForkReleaseAcceptance") == "true"
        if (forkReleaseAcceptance) {
            check(context.packageName == "app.mihon.desktop.fork") { "Explicit fork release identity required" }
            check(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
                "Fork acceptance requires the actual non-debuggable release host"
            }
        } else {
            check(context.packageName == "app.mihon.eis.dev") { "Isolated EIS acceptance identity required" }
        }
        val manager = Injekt.get<ExtensionManager>()
        val batch = Injekt.get<AndroidExtensionSuggestionBatch>()
        val repositories = Injekt.get<ExtensionRepoRepository>()
        val mangas = Injekt.get<MangaRepository>()
        val preference = Injekt.get<BasePreferences>().extensionInstaller()
        val installerReceipt = context.getSharedPreferences("eis-installer-acceptance", Context.MODE_PRIVATE)
        if (installerReceipt.contains("previousInstaller")) {
            check(installerReceipt.getString("host", null) == context.packageName)
            if (installerReceipt.getBoolean("previouslySet", false)) {
                val savedInstaller = checkNotNull(installerReceipt.getString("previousInstaller", null))
                preference.set(BasePreferences.ExtensionInstaller.valueOf(savedInstaller))
            } else {
                preference.delete()
            }
            check(installerReceipt.edit().clear().commit())
        }
        val previous = preference.get()
        val previouslySet = preference.isSet()
        val uiPreferences = userActionsPhase?.let { Injekt.get<ExtensionSuggestionPreferences>() }
        val uiReceipt = userActionsPhase?.let {
            check(forkReleaseAcceptance) { "User-action acceptance requires the explicit release host" }
            context.getSharedPreferences("eis-release-user-actions", Context.MODE_PRIVATE)
        }
        val verifyingUi = userActionsPhase == "verify"
        if (uiReceipt != null) {
            check(uiReceipt.contains("preparedPid") == verifyingUi) { "Unexpected user-action receipt state" }
            if (verifyingUi) check(uiReceipt.getInt("preparedPid", -1) != Process.myPid())
        }
        val originalExpanded = if (verifyingUi) {
            uiReceipt!!.getBoolean("expanded", true)
        } else {
            uiPreferences?.expanded?.get() ?: true
        }
        val originalExpandedSet = if (verifyingUi) {
            uiReceipt!!.getBoolean("expandedSet", false)
        } else {
            uiPreferences?.expanded?.isSet() ?: false
        }
        val originalIgnored = if (verifyingUi) {
            uiReceipt!!.getString("ignored", "")!!
        } else {
            uiPreferences?.ignored?.get().orEmpty()
        }
        val originalIgnoredSet = if (verifyingUi) {
            uiReceipt!!.getBoolean("ignoredSet", false)
        } else {
            uiPreferences?.ignored?.isSet() ?: false
        }
        var preserveUiStateForRestart = false
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
        val websiteRequests = ConcurrentHashMap.newKeySet<String>()
        val failSecond = AtomicBoolean(false)
        val holdFirst = AtomicBoolean(false)
        val firstRequested = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val downloads = AtomicIntegerArray(2)
        val requestedPort = if (verifyingUi) uiReceipt!!.getInt("port", -1).also { check(it in 1..65535) } else 0
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort), 4)
        }.use { server ->
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
                                                        if (userActionsPhase != null) {
                                                            put("homeUrl", "$url/website/${source.id}")
                                                        }
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
                        server.accept().use connection@{ socket ->
                            socket.soTimeout = 15_000
                            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val requestLine = reader.readLine() ?: return@connection
                            val path = requestLine.split(' ')[1]
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            if (path == "/0.apk") {
                                downloads.incrementAndGet(0)
                                if (holdFirst.compareAndSet(true, false)) {
                                    firstRequested.countDown()
                                    check(releaseFirst.await(30, TimeUnit.SECONDS)) {
                                        "Owned download barrier timed out"
                                    }
                                }
                            }
                            if (path == "/1.apk") downloads.incrementAndGet(1)
                            val controlledFailure = path == "/1.apk" && failSecond.get()
                            val contentType = if (path.startsWith(
                                    "/website/",
                                )
                            ) {
                                "text/html"
                            } else {
                                "application/octet-stream"
                            }
                            val body = when (path) {
                                "/index_v2.json" -> catalog
                                "/0.apk" -> bytes[0]
                                "/1.apk" -> bytes[1]
                                else -> if (userActionsPhase != null &&
                                    path in sources.flatten().map { "/website/${it.id}" }
                                ) {
                                    websiteRequests.add(path)
                                    "<html><title>EIS controlled website</title>Local acceptance</html>".toByteArray()
                                } else {
                                    byteArrayOf()
                                }
                            }
                            val status = when {
                                controlledFailure -> "500 Controlled Failure"
                                body.isEmpty() -> "404 Not Found"
                                else -> "200 OK"
                            }
                            socket.getOutputStream().use { output ->
                                output.write(
                                    (
                                        "HTTP/1.1 $status\r\n" +
                                            "Content-Type: $contentType\r\n" +
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
                val installerSnapshot = installerReceipt.edit()
                    .putString("host", context.packageName)
                    .putString("previousInstaller", previous.name)
                    .putBoolean("previouslySet", previouslySet)
                check(installerSnapshot.commit())
                preference.set(installer)
                context.startActivity(
                    requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                if (confirmation) awaitForeground(true)
                if (userActionsPhase != null) awaitForeground(true)
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
                val requiredFixtures = if (verifyingUi) fixtures.take(1) else fixtures
                val snapshot = withTimeout(30_000) {
                    batch.suggestions.first { value ->
                        !value.isLoading &&
                            requiredFixtures.all { fixture ->
                                value.suggestions.any { it.artifact.packageName == fixture.packageName }
                            }
                    }
                }.let { value ->
                    requiredFixtures.map { fixture ->
                        value.suggestions.single {
                            it.artifact.packageName ==
                                fixture.packageName
                        }.artifact
                    }
                }
                if (userActionsPhase != null) {
                    preserveUiStateForRestart = exerciseReleaseUserActions(
                        context, manager, batch, fixtures, sources, snapshot, checkNotNull(uiPreferences),
                        checkNotNull(uiReceipt), userActionsPhase, server.localPort, originalExpanded,
                        originalExpandedSet, originalIgnored, originalIgnoredSet, websiteRequests,
                        failSecond, holdFirst, firstRequested, releaseFirst, downloads,
                    )
                    serverFailure.get()?.let { throw AssertionError("Fixture HTTP failed", it) }
                    return@use
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
                withTimeout(15_000) {
                    batch.suggestions.first { value ->
                        !value.isLoading && value.suggestions.none { suggestion ->
                            expected.any { it.packageName == suggestion.artifact.packageName }
                        }
                    }
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
                    cleanOwnedBatch(context, manager, batch, fixtures, ownedRows, repositories, url, helper, recreate)
                } catch (cleanupFailure: Throwable) {
                    if (originalFailure != null) originalFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
                } finally {
                    releaseFirst.countDown()
                    try {
                        if (uiPreferences != null && (!preserveUiStateForRestart || originalFailure != null)) {
                            if (originalExpandedSet) {
                                uiPreferences.expanded.set(
                                    originalExpanded,
                                )
                            } else {
                                uiPreferences.expanded.delete()
                            }
                            if (originalIgnoredSet) {
                                uiPreferences.ignored.set(
                                    originalIgnored,
                                )
                            } else {
                                uiPreferences.ignored.delete()
                            }
                            if (originalFailure == null || !verifyingUi) check(uiReceipt!!.edit().clear().commit())
                        }
                    } finally {
                        try {
                            if (previouslySet) preference.set(previous) else preference.delete()
                            check(installerReceipt.edit().clear().commit())
                        } finally {
                            server.close()
                            worker.join(5_000)
                        }
                    }
                }
            }
        }
    }

    private suspend fun cleanOwnedBatch(
        context: Context,
        manager: ExtensionManager,
        batch: AndroidExtensionSuggestionBatch,
        fixtures: List<LifecycleApkFixture>,
        ownedRows: List<Manga>,
        repositories: ExtensionRepoRepository,
        url: String,
        helper: ExtensionV16LifecycleInstrumentationTest,
        recreate: Boolean,
    ) {
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
    }

    private suspend fun exerciseReleaseUserActions(
        context: Context,
        manager: ExtensionManager,
        batch: AndroidExtensionSuggestionBatch,
        fixtures: List<LifecycleApkFixture>,
        sources: List<List<Source>>,
        snapshot: List<ExtensionArtifact>,
        localPreferences: ExtensionSuggestionPreferences,
        receipt: android.content.SharedPreferences,
        userActionsPhase: String,
        serverPort: Int,
        originalExpanded: Boolean,
        originalExpandedSet: Boolean,
        originalIgnored: String,
        originalIgnoredSet: Boolean,
        websiteRequests: Set<String>,
        failSecond: AtomicBoolean,
        holdFirst: AtomicBoolean,
        firstRequested: CountDownLatch,
        releaseFirst: CountDownLatch,
        downloads: AtomicIntegerArray,
    ): Boolean {
        val verifyingUi = userActionsPhase == "verify"
        if (verifyingUi) {
            assertFalse(
                "Fold preference did not survive a real process restart",
                localPreferences.expanded.get(),
            )
            assertTrue(
                localPreferences.ignored.get().lineSequence().any {
                    it == receipt.getString("ignoredIdentity", null)
                },
            )
            assertFalse(
                batch.suggestions.value.suggestions.any {
                    it.artifact.packageName ==
                        fixtures[1].packageName
                },
            )
        }
        val rowNames = fixtures.map { it.name }.toSet()
        showSuggestionPage(context, rowNames)
        println("EIS_RELEASE_UI_PAGE phase=$userActionsPhase pid=${Process.myPid()}")
        if (userActionsPhase == "prepare") {
            if (!localPreferences.expanded.get()) clickSuggestionHeading(context, rowNames)
            exerciseSuggestionWebsites(context, fixtures, sources, websiteRequests)
            clickSuggestionRow(
                context,
                fixtures[1].name,
                context.stringResource(MR.strings.extension_suggestions_ignore),
                fixtures.map {
                    it.name
                }.toSet(),
            )
            val identity = suggestionIdentityKey(SuggestionIdentity.of(snapshot[1]))
            withTimeout(10_000) {
                while (identity !in localPreferences.ignored.get().lineSequence()) delay(50)
            }
            clickSuggestionHeading(context, rowNames)
            withTimeout(10_000) { while (localPreferences.expanded.get()) delay(50) }
            check(
                receipt.edit().putInt("preparedPid", Process.myPid()).putInt("port", serverPort)
                    .putBoolean("expanded", originalExpanded).putBoolean("expandedSet", originalExpandedSet)
                    .putString("ignored", originalIgnored).putBoolean("ignoredSet", originalIgnoredSet)
                    .putString("ignoredIdentity", identity).commit(),
            )
            println(
                "EIS_RELEASE_UI_PREPARED pid=${Process.myPid()} websites=${websiteRequests.size} " +
                    "folded=true ignored=true",
            )
        } else {
            // The mounted production page must expose the collapsed state before restoring owned test changes.
            awaitUiNode(context) {
                it.stateDescription?.toString() ==
                    context.stringResource(MR.strings.extension_suggestions_expand)
            }
            println("EIS_RELEASE_UI_COLLAPSED pid=${Process.myPid()}")
            localPreferences.ignored.set(originalIgnored)
            clickSuggestionHeading(context, rowNames)
            val restored = withTimeout(30_000) {
                batch.suggestions.first { value ->
                    !value.isLoading && fixtures.all { fixture ->
                        value.suggestions.any { it.artifact.packageName == fixture.packageName }
                    }
                }
            }
            val confirmed = fixtures.map { fixture ->
                restored.suggestions.single { it.artifact.packageName == fixture.packageName }.artifact
            }
            exerciseReleaseSingleAndBatch(
                context, manager, batch, confirmed, failSecond, holdFirst,
                firstRequested, releaseFirst, downloads,
            )
            println(
                "EIS_RELEASE_UI_VERIFIED oldPid=${receipt.getInt(
                    "preparedPid",
                    -1,
                )} pid=${Process.myPid()} " +
                    "crossProcess=true single=true partialFailure=true retry=true crossEntryDedup=true",
            )
        }
        return userActionsPhase == "prepare"
    }

    private suspend fun showSuggestionPage(context: Context, rowNames: Set<String>) {
        clickUiText(context, context.stringResource(MR.strings.browse))
        clickUiText(context, context.stringResource(MR.strings.label_extensions))
        revealSuggestionHeading(context, rowNames)
    }

    private suspend fun clickSuggestionHeading(context: Context, rowNames: Set<String>) {
        val node = revealSuggestionHeading(context, rowNames)
        check(checkNotNull(clickableParent(node)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private suspend fun exerciseSuggestionWebsites(
        context: Context,
        fixtures: List<LifecycleApkFixture>,
        sources: List<List<Source>>,
        requests: Set<String>,
    ) {
        val rowNames = fixtures.map { it.name }.toSet()
        for ((index, fixture) in fixtures.withIndex()) {
            for (source in sources[index]) {
                clickSuggestionRow(
                    context,
                    fixture.name,
                    context.stringResource(MR.strings.extension_suggestions_open_website),
                    rowNames,
                )
                if (sources[index].size > 1) {
                    awaitUiNode(context) {
                        it.text?.toString() == context.stringResource(MR.strings.extension_suggestions_website)
                    }
                    clickUiText(context, source.name)
                }
                withTimeout(20_000) { while ("/website/${source.id}" !in requests) delay(50) }
                check(
                    InstrumentationRegistry.getInstrumentation().uiAutomation
                        .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK),
                )
                // Returning preserves legitimate list offsets; bring the real page's heading back into view.
                revealSuggestionHeading(context, rowNames)
            }
        }
        assertEquals(sources.flatten().size, requests.size)
    }

    private suspend fun exerciseReleaseSingleAndBatch(
        context: Context,
        manager: ExtensionManager,
        batch: AndroidExtensionSuggestionBatch,
        artifacts: List<ExtensionArtifact>,
        failSecond: AtomicBoolean,
        holdFirst: AtomicBoolean,
        firstRequested: CountDownLatch,
        releaseFirst: CountDownLatch,
        downloads: AtomicIntegerArray,
    ) = kotlinx.coroutines.coroutineScope {
        val packages = artifacts.map { it.packageName }.toSet()
        val observer = launch {
            manager.installedExtensionsFlow.collect { entries ->
                println(
                    "EIS_RELEASE_SINGLE_MAP time=${System.currentTimeMillis()} " +
                        "packages=${entries.filter { it.pkgName in packages }.map { it.pkgName }}",
                )
            }
        }
        val inventoryObserver = launch {
            manager.inventory.collect { value ->
                println(
                    "EIS_RELEASE_SINGLE_INVENTORY time=${System.currentTimeMillis()} " +
                        "initialized=${value.initialized} records=${value.records.filterKeys { it in packages }}",
                )
            }
        }
        try {
            // This first request is a real UI click, through the mounted screen's own ScreenModel.
            clickSuggestionRow(
                context,
                artifacts[0].name,
                context.stringResource(MR.strings.ext_install),
                artifacts.map { it.name }.toSet(),
            )
            val installed = namedUiWait("single installed", 30_000) {
                manager.installedExtensionsFlow.first { list -> list.any { it.pkgName == artifacts[0].packageName } }
            }.single { it.pkgName == artifacts[0].packageName }
            assertFalse(installed.isShared)
            namedUiWait("single excluded", 15_000) {
                batch.suggestions.first { value ->
                    !value.isLoading && value.suggestions.none {
                        it.artifact.packageName == artifacts[0].packageName
                    }
                }
            }
            // Restore only this run's private fixture before exercising a fixed two-item batch.
            println("EIS_RELEASE_SINGLE_UNINSTALL before busy=${manager.installArbiter.isBusy(installed.pkgName)}")
            manager.uninstallExtension(installed)
            println("EIS_RELEASE_SINGLE_UNINSTALL dispatched")
            namedUiWait("uninstall suggestions restored", 15_000) {
                batch.suggestions.first { value ->
                    !value.isLoading && artifacts.all { artifact ->
                        value.suggestions.any { it.artifact == artifact }
                    }
                }
            }
            failSecond.set(true)
            holdFirst.set(true)
            val before = downloads.get(0)
            assertTrue(batch.start(artifacts))
            namedUiWait("batch request received", 15_000) { while (firstRequested.count != 0L) delay(50) }
            val competing = runCatching { manager.installExtension(artifacts[0].toAvailable()) }.exceptionOrNull()
            assertTrue("Ordinary entry must not replace the batch owner: $competing", competing is ExtensionInstallBusy)
            releaseFirst.countDown()
            val partial = namedUiWait("partial batch finished", 60_000) { batch.state.first { !it.running } }
            assertEquals(SuggestionBatchResult.Installed, partial.items[0].result)
            assertTrue(partial.items[1].result is SuggestionBatchResult.Failed)
            assertEquals(before + 1, downloads.get(0))
            assertTrue(manager.installedExtensionsFlow.value.any { it.pkgName == artifacts[0].packageName })
            assertFalse(manager.installedExtensionsFlow.value.any { it.pkgName == artifacts[1].packageName })
            failSecond.set(false)
            // The real UI owns the retry review and fixed one-item confirmation.
            showBatchRetry(context, artifacts.map { it.name }.toSet())
            clickUiText(context, context.stringResource(MR.strings.extension_batch_retry))
            awaitUiNode(context) { it.text?.toString() == context.stringResource(MR.strings.extension_batch_confirm) }
            clickUiText(context, context.stringResource(MR.strings.extension_batch_install_count, 1))
            namedUiWait("retry batch finished", 60_000) {
                batch.state.first {
                    !it.running && it.items.size == 2 && it.items.all { item ->
                        item.result == SuggestionBatchResult.Installed
                    }
                }
            }
            namedUiWait("final installed excluded", 15_000) {
                batch.suggestions.first { value ->
                    !value.isLoading && value.suggestions.none {
                        it.artifact.packageName in artifacts.map { artifact -> artifact.packageName }
                    }
                }
            }
        } catch (failure: Throwable) {
            println(
                "EIS_RELEASE_SINGLE_FAILED errors=${manager.installErrors.value.filterKeys { it in packages }} " +
                    "busy=${packages.associateWith { manager.installArbiter.isBusy(it) }} " +
                    "files=${packages.associateWith { File(context.filesDir, "exts/$it.ext").exists() }}",
            )
            throw failure
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                observer.cancelAndJoin()
                inventoryObserver.cancelAndJoin()
            }
        }
    }

    private suspend fun <T> namedUiWait(
        label: String,
        timeout: Long,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> T,
    ): T {
        println("EIS_RELEASE_SINGLE_WAIT begin=$label")
        return try {
            withTimeout(timeout, block).also { println("EIS_RELEASE_SINGLE_WAIT done=$label") }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out at release UI stage: $label", failure)
        }
    }

    private fun visibleUiNodes(context: Context): List<AccessibilityNodeInfo> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != context.packageName) return emptyList()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val result = mutableListOf<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && result.size < 512) {
            val node = queue.removeFirst()
            result += node
            repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
        }
        return result.filter { it.isVisibleToUser }
    }

    private suspend fun showBatchRetry(context: Context, rowNames: Set<String>) {
        val label = context.stringResource(MR.strings.extension_batch_retry)
        revealExtensionControl(context, rowNames) {
            it.text?.toString() == label && clickableParent(it) != null
        }
    }

    private suspend fun revealSuggestionHeading(context: Context, rowNames: Set<String>): AccessibilityNodeInfo {
        val label = context.stringResource(MR.strings.extension_suggestions_title)
        return revealExtensionControl(context, rowNames) {
            it.text?.toString()?.startsWith(label) == true && clickableParent(it) != null
        }
    }

    private fun isExtensionsTab(context: Context, node: AccessibilityNodeInfo): Boolean =
        node.text?.toString() == context.stringResource(MR.strings.label_extensions) &&
            generateSequence(node) { it.parent }.take(6).any { it.isSelected }

    private fun isVerticalList(node: AccessibilityNodeInfo): Boolean = node.isScrollable &&
        (
            node.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id ||
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            } || node.collectionInfo?.let { it.columnCount == 1 && it.rowCount != 1 } == true
            )

    private suspend fun revealExtensionControl(
        context: Context,
        rowNames: Set<String>,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        // A page may be correctly restored while its header or batch controls are above the viewport.
        // Confirm the selected production tab before scrolling; never scroll the horizontal pager.
        awaitUiNode(context) { isExtensionsTab(context, it) }
        var attempts = 0
        try {
            return withTimeout(25_000) {
                while (true) {
                    val nodes = visibleUiNodes(context)
                    if (nodes.any { isExtensionsTab(context, it) }) {
                        nodes.firstOrNull(predicate)?.let { return@withTimeout it }
                        val owned = nodes.firstOrNull { it.text?.toString() in rowNames }
                        val outer = owned?.let {
                            generateSequence(it.parent) { parent -> parent.parent }
                                .take(32).lastOrNull(::isVerticalList)
                        } ?: nodes.filter(::isVerticalList)
                            .maxByOrNull { Rect().also(it::getBoundsInScreen).height() }
                        if (outer != null && attempts < 6) {
                            outer.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                            attempts++
                        }
                    }
                    // A Flow terminal state can precede Compose's accessibility update.
                    delay(150)
                }
                error("Unreachable")
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val nodes = visibleUiNodes(context).map {
                "${it.text}; selected=${it.isSelected}; vertical=${isVerticalList(it)}"
            }
            throw AssertionError("Extensions control missing after $attempts bounded scrolls: $nodes", error)
        }
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val candidate = current ?: return null
            if (candidate.isClickable && candidate.isEnabled) return candidate
            current = candidate.parent
        }
        return null
    }

    private suspend fun awaitUiNode(
        context: Context,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        try {
            return withTimeout(25_000) {
                while (true) {
                    visibleUiNodes(context).firstOrNull(predicate)?.let { return@withTimeout it }
                    delay(100)
                }
                error("Unreachable")
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val nodes = visibleUiNodes(context).map {
                "text=${it.text}; description=${it.contentDescription}; state=${it.stateDescription}"
            }
            throw AssertionError("Expected UI node missing: $nodes", error)
        }
    }

    private suspend fun clickUiText(context: Context, label: String) {
        println("EIS_RELEASE_UI_CLICK label=$label")
        val node = awaitUiNode(context) {
            (it.text?.toString() == label || it.contentDescription?.toString() == label) && clickableParent(it) != null
        }
        check(checkNotNull(clickableParent(node)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private suspend fun clickSuggestionRow(context: Context, name: String, label: String, rowNames: Set<String>) {
        println("EIS_RELEASE_UI_ROW name=$name action=$label")
        revealSuggestionHeading(context, rowNames)
        awaitUiNode(context) { it.text?.toString() in rowNames }
        var firstNodes: List<String>? = null
        var lastNodes = emptyList<String>()
        var gestures = 0
        var reportedViewport = false
        try {
            withTimeout(25_000) {
                while (true) {
                    val nodes = visibleUiNodes(context)
                    lastNodes = nodes.map {
                        "${it.text}; enabled=${it.isEnabled}; ${Rect().also(it::getBoundsInScreen)}"
                    }
                    if (firstNodes == null) firstNodes = lastNodes
                    val title = nodes.firstOrNull { it.text?.toString() == name }
                    val titleBounds = Rect().also { title?.getBoundsInScreen(it) }
                    val nextTop = nodes.filter { it.text?.toString() in rowNames && it.text?.toString() != name }
                        .map { Rect().also(it::getBoundsInScreen).top }.filter { it > titleBounds.top }.minOrNull()
                        ?: Int.MAX_VALUE
                    val action = if (title == null) {
                        null
                    } else {
                        nodes.filter {
                            it.text?.toString() == label && clickableParent(it) != null
                        }.map { it to Rect().also(it::getBoundsInScreen) }
                            .filter { (_, bounds) -> bounds.top >= titleBounds.top && bounds.top < nextTop }
                            .minByOrNull { (_, bounds) -> bounds.top }?.first
                    }
                    if (action != null) {
                        check(checkNotNull(clickableParent(action)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        return@withTimeout
                    }
                    val ownedTitle = title ?: nodes.firstOrNull { it.text?.toString() in rowNames }
                    if (ownedTitle != null && gestures < 12) {
                        // A previous action may leave the nested list below the requested row.
                        // Sweep both directions, using only the current owned viewport.
                        val forward = title != null || gestures % 8 < 4
                        if (scrollSuggestionViewport(context, nodes, ownedTitle, forward, !reportedViewport)) {
                            gestures++
                            delay(300)
                            revealSuggestionHeading(context, rowNames)
                        } else {
                            reportedViewport = true
                            delay(150)
                        }
                    } else {
                        delay(150)
                    }
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Missing row $name action $label; first=$firstNodes; last=$lastNodes", error)
        }
    }

    private fun scrollSuggestionViewport(
        context: Context,
        nodes: List<AccessibilityNodeInfo>,
        title: AccessibilityNodeInfo,
        forward: Boolean,
        reportMissing: Boolean,
    ): Boolean {
        val titleBounds = Rect().also(title::getBoundsInScreen)
        val heading = nodes.firstOrNull {
            it.text?.toString()?.startsWith(context.stringResource(MR.strings.extension_suggestions_title)) == true
        }
        val containers = generateSequence(title.parent) { it.parent }.take(32).toList()
        if (heading == null) {
            if (reportMissing) println("EIS_RELEASE_UI_NO_VIEWPORT heading=absent title=$titleBounds")
            return false
        }
        val headingBounds = Rect().also(heading::getBoundsInScreen)
        val viewport = containers.map { Rect().also(it::getBoundsInScreen) }.filter {
            it.top >= headingBounds.bottom && it.contains(titleBounds.centerX(), titleBounds.centerY()) &&
                it.width() >= context.resources.displayMetrics.widthPixels / 2 &&
                it.height() > titleBounds.height() * 3
        }.minByOrNull { it.height() }
        if (viewport == null || viewport.left < 0 || viewport.top < 0 ||
            viewport.right > context.resources.displayMetrics.widthPixels ||
            viewport.bottom > context.resources.displayMetrics.heightPixels
        ) {
            if (reportMissing) {
                val bounds = containers.map {
                    "${Rect().also(it::getBoundsInScreen)} scroll=${it.isScrollable}"
                }
                println("EIS_RELEASE_UI_NO_VIEWPORT heading=$headingBounds title=$titleBounds ancestors=$bounds")
            }
            return false
        }
        println("EIS_RELEASE_UI_VIEWPORT title=$titleBounds viewport=$viewport forward=$forward")
        // Nested Compose lists can expose only the outer accessibility scroll action.
        // A real gesture inside the owned suggestion viewport targets its actual touch receiver.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = android.os.SystemClock.uptimeMillis()
        val x = viewport.exactCenterX()
        val padding = minOf(24f, viewport.height() / 8f)
        val start = if (forward) viewport.bottom - padding else viewport.top + padding
        val end = if (forward) {
            maxOf(viewport.top + padding, start - viewport.height() / 2f)
        } else {
            minOf(viewport.bottom - padding, start + viewport.height() / 2f)
        }
        for (step in 0..12) {
            val action = when (step) {
                0 -> android.view.MotionEvent.ACTION_DOWN
                12 -> android.view.MotionEvent.ACTION_UP
                else -> android.view.MotionEvent.ACTION_MOVE
            }
            val event = android.view.MotionEvent.obtain(
                down,
                android.os.SystemClock.uptimeMillis(),
                action,
                x,
                start + (end - start) * step / 12,
                0,
            )
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try {
                check(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            if (step < 12) android.os.SystemClock.sleep(16)
        }
        return true
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
