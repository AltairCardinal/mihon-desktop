package eu.kanade.tachiyomi.extension

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentSanitizer
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hippo.unifile.UniFile
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.base.ExtensionInstallerPreference
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.installer.PackageInstallerInstaller
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.AndroidApk
import eu.kanade.tachiyomi.extension.util.AndroidCommitPlan
import eu.kanade.tachiyomi.extension.util.AndroidInstallLocation
import eu.kanade.tachiyomi.extension.util.AndroidInstallPort
import eu.kanade.tachiyomi.extension.util.DefaultAndroidInstallGateway
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.util.lang.Hash
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallCoordinator
import mihon.domain.extension.service.ExtensionInstallFailure
import mihon.domain.extension.service.ExtensionInstallPort
import mihon.domain.extension.service.ExtensionInstallRequest
import mihon.domain.extension.service.ExtensionInstallRollbackToken
import mihon.domain.extension.service.PreparedExtensionInstallToken
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.util.Collections
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.thread
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@OptIn(ExperimentalAtomicApi::class)
class ExtensionInstallSessionLifecycleTest {

    private val capturedStringExtras = mutableMapOf<String, String>()
    private val capturedIntExtras = mutableMapOf<String, Int>()
    private var pendingIdentity: CallbackIdentity? = null
    private var committedIdentity: CallbackIdentity? = null
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private val cancelReceivers = mutableListOf<BroadcastReceiver>()
    private val cancelIntents = mutableListOf<Intent>()

    @Suppress("PropertyName")
    private lateinit var TRANSACTION_ONE: String

    @Suppress("PropertyName")
    private lateinit var TRANSACTION_TWO: String

    @Test
    fun `production system commit reload failure restores through a distinct child session`(
        @TempDir directory: Path,
    ) = runTest {
        var harness = packageInstallerHarness()
        val cacheDirectory = directory.resolve("cache").toFile().apply(File::mkdirs)
        val filesDirectory = directory.resolve("files").toFile().apply(File::mkdirs)
        val installedSystem = directory.resolve("installed-system.apk").toFile().apply { writeText("old-system") }
        val packageManager = mockk<PackageManager>(relaxed = true) {
            every { getPackageInfo(PACKAGE_NAME, any<Int>()) } answers {
                android.content.pm.PackageInfo().apply {
                    packageName = PACKAGE_NAME
                    versionName = "1.4.1"
                    applicationInfo = android.content.pm.ApplicationInfo().apply {
                        sourceDir = installedSystem.absolutePath
                    }
                }
            }
        }
        val context = mockk<Context>(relaxed = true) {
            every { packageName } returns "eu.kanade.tachiyomi"
            every { cacheDir } returns cacheDirectory
            every { filesDir } returns filesDirectory
            every { this@mockk.packageManager } returns packageManager
        }
        val bridge = ExtensionInstaller(
            context = context,
            scope = backgroundScope,
            installPort = mockk(relaxed = true),
        )
        val preference = mockk<ExtensionInstallerPreference> {
            every { get() } returns BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
        }
        ExtensionInstaller::class.java.getDeclaredField("extensionInstaller\$delegate").apply {
            isAccessible = true
            set(bridge, lazyOf(preference))
        }
        fun connectCallbacks() {
            every { harness.manager.updateInstallStep(any(), any()) } answers {
                bridge.updateInstallStep(firstArg(), secondArg())
            }
        }
        connectCallbacks()
        val committedAttempts = mutableListOf<String>()
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { ContextCompat.startForegroundService(context, any()) } answers {
            val attempt = checkNotNull(capturedStringExtras[ExtensionInstaller.EXTRA_TRANSACTION_ID])
            committedAttempts += attempt
            harness.enqueue(attempt)
            mockk()
        }

        val metadataDirectory = filesDirectory.resolve("extension-install-metadata").apply(File::mkdirs)
        Properties().apply {
            setProperty("repository.baseUrl", REPOSITORY.baseUrl)
            setProperty("repository.name", REPOSITORY.name)
            setProperty("repository.fingerprint", REPOSITORY.signingKeyFingerprint)
            setProperty("artifact.sha256", Hash.sha256(installedSystem.readBytes()))
        }.also { values ->
            metadataDirectory.resolve("system-$PACKAGE_NAME.properties").outputStream().use { values.store(it, null) }
        }

        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse(body = "candidate-system"))
            val gateway = DefaultAndroidInstallGateway(
                context = context,
                installSystem = { transactionId, file, installer ->
                    bridge.installSystemAttempt(transactionId, file, installer)
                    file.copyTo(installedSystem, overwrite = true)
                },
                commitPlanProvider = {
                    AndroidCommitPlan(
                        AndroidInstallLocation.SYSTEM,
                        BasePreferences.ExtensionInstaller.PACKAGEINSTALLER,
                    )
                },
                apkInspector = { file ->
                    val candidate = file.readText().startsWith("candidate")
                    AndroidApk(
                        packageName = PACKAGE_NAME,
                        versionName = if (candidate) "1.4.2" else "1.4.1",
                        versionCode = if (candidate) 2 else 1,
                        signers = setOf("signer-a"),
                        isExtension = true,
                    )
                },
            )
            var reloads = 0
            val coordinator = ExtensionInstallCoordinator(
                AndroidInstallPort(
                    gateway = gateway,
                    client = OkHttpClient(),
                    runtimeReloader = {
                        if (reloads++ == 0) throw ExtensionInstallFailure(AppError.MalformedData())
                    },
                ),
                backgroundScope,
            )
            val states = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.install(
                    ExtensionInstallRequest(
                        ExtensionArtifact(
                            name = "Example",
                            packageName = PACKAGE_NAME,
                            versionName = "1.4.2",
                            versionCode = 2,
                            language = "en",
                            isNsfw = false,
                            sources = emptyList(),
                            repository = REPOSITORY,
                            downloadUrl = server.url("/example.apk").toString(),
                            iconUrl = "",
                            declaredSha256 = Hash.sha256("candidate-system".toByteArray()),
                        ),
                    ),
                ).toList()
            }

            try {
                runCurrent()
                val parentTransaction = checkNotNull(cacheDirectory.resolve("extension-installs").listFiles())
                    .single().name
                check(committedAttempts.isNotEmpty()) {
                    "system commit was not handed off; states=" +
                        if (states.isCompleted) states.await().toString() else "still-running"
                }
                harness.callback(PackageInstaller.STATUS_SUCCESS)
                harness.installer.onDestroy()
                harness = packageInstallerHarness().also { it.useSession(SESSION_TWO) }
                connectCallbacks()
                runCurrent()
                if (committedAttempts.size == 2) {
                    bridge.updateInstallStep(committedAttempts.first(), InstallStep.Installed)
                    runCurrent()
                    assertFalse(states.isCompleted, "late commit callback must not complete the restore attempt")
                    harness.callback(PackageInstaller.STATUS_SUCCESS)
                    harness.installer.onDestroy()
                    runCurrent()
                }

                val terminal = states.await().last()
                assertInstanceOf(mihon.domain.extension.service.ExtensionInstallState.Failed::class.java, terminal)
                assertEquals(2, committedAttempts.size)
                assertNotEquals(parentTransaction, committedAttempts[0])
                assertNotEquals(parentTransaction, committedAttempts[1])
                assertNotEquals(committedAttempts[0], committedAttempts[1])
                assertEquals("old-system", installedSystem.readText())
            } finally {
                unmockkStatic(FileProvider::class)
            }
        }
    }

    @Test
    fun `parent cancellation targets the active production child session and rejects its late callback`(
        @TempDir directory: Path,
    ) = runTest {
        val harness = packageInstallerHarness()
        val cacheDirectory = directory.resolve("cache").toFile().apply(File::mkdirs)
        val filesDirectory = directory.resolve("files").toFile().apply(File::mkdirs)
        val packageManager = mockk<PackageManager>(relaxed = true) {
            every { getPackageInfo(PACKAGE_NAME, any<Int>()) } throws PackageManager.NameNotFoundException()
            every { getApplicationInfo(PACKAGE_NAME, any<Int>()) } throws PackageManager.NameNotFoundException()
        }
        val context = mockk<Context>(relaxed = true) {
            every { packageName } returns "eu.kanade.tachiyomi"
            every { cacheDir } returns cacheDirectory
            every { filesDir } returns filesDirectory
            every { this@mockk.packageManager } returns packageManager
        }
        lateinit var bridge: ExtensionInstaller
        val childAttempts = mutableListOf<String>()
        val gateway = DefaultAndroidInstallGateway(
            context = context,
            installSystem = { transactionId, file, installer ->
                bridge.installSystemAttempt(transactionId, file, installer)
            },
            commitPlanProvider = {
                AndroidCommitPlan(
                    AndroidInstallLocation.SYSTEM,
                    BasePreferences.ExtensionInstaller.PACKAGEINSTALLER,
                )
            },
            apkInspector = {
                AndroidApk(PACKAGE_NAME, "1.4.2", 2, setOf("signer-a"), isExtension = true)
            },
        )
        bridge = ExtensionInstaller(
            context = context,
            runtimeReloader = {},
            scope = backgroundScope,
            gateway = gateway,
            client = OkHttpClient(),
            installerProvider = { BasePreferences.ExtensionInstaller.PACKAGEINSTALLER },
        )
        every { harness.manager.updateInstallStep(any(), any()) } answers {
            bridge.updateInstallStep(firstArg(), secondArg())
        }
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { ContextCompat.startForegroundService(context, any()) } answers {
            val child = checkNotNull(capturedStringExtras[ExtensionInstaller.EXTRA_TRANSACTION_ID])
            childAttempts += child
            harness.enqueue(child)
            mockk()
        }

        MockWebServer().also { it.start() }.use { server ->
            val candidate = "candidate-system".toByteArray()
            server.enqueue(MockResponse(body = candidate.decodeToString()))
            val extension = availableExtension(PACKAGE_NAME).copy(
                versionName = "1.4.2",
                versionCode = 2,
                repoName = REPOSITORY.name,
                repoFingerprint = REPOSITORY.signingKeyFingerprint,
                declaredSha256 = Hash.sha256(candidate),
            )
            val terminal = async {
                bridge.downloadAndInstall(server.url("/example.apk").toString(), extension)
                    .first(InstallStep::isCompleted)
            }

            try {
                runCurrent()
                val parent = activeTransactionIds(bridge).getValue(PACKAGE_NAME)
                val child = childAttempts.single()
                assertNotEquals(parent, child)

                bridge.cancelInstall(PACKAGE_NAME)
                runCurrent()

                assertEquals(child, capturedStringExtras[ExtensionInstaller.EXTRA_TRANSACTION_ID])
                assertFalse(terminal.isCompleted)
                assertTrue(activeTransactionIds(bridge).containsKey(PACKAGE_NAME))

                harness.installer.onDestroy()
                runCurrent()

                assertEquals(InstallStep.Idle, terminal.await())
                assertTrue(activeTransactionIds(bridge).isEmpty())
                assertTrue(platformResults(bridge).isEmpty())
                bridge.updateInstallStep(child, InstallStep.Installed)
                assertTrue(activeTransactionIds(bridge).isEmpty())
            } finally {
                unmockkStatic(FileProvider::class)
            }
        }
    }

    @Test
    fun `parent cancellation during child teardown remains durable and cannot publish installed`(
        @TempDir directory: Path,
    ) = runTest {
        val harness = packageInstallerHarness()
        val cacheDirectory = directory.resolve("cache").toFile().apply(File::mkdirs)
        val filesDirectory = directory.resolve("files").toFile().apply(File::mkdirs)
        val packageManager = mockk<PackageManager>(relaxed = true) {
            every { getPackageInfo(PACKAGE_NAME, any<Int>()) } throws PackageManager.NameNotFoundException()
            every { getApplicationInfo(PACKAGE_NAME, any<Int>()) } throws PackageManager.NameNotFoundException()
        }
        val context = mockk<Context>(relaxed = true) {
            every { packageName } returns "eu.kanade.tachiyomi"
            every { cacheDir } returns cacheDirectory
            every { filesDir } returns filesDirectory
            every { this@mockk.packageManager } returns packageManager
        }
        val removalStarted = CountDownLatch(1)
        val allowRemoval = CountDownLatch(1)
        val childStarted = CountDownLatch(1)
        val installerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var bridge: ExtensionInstaller
        val childAttempts = Collections.synchronizedList(mutableListOf<String>())
        val gateway = DefaultAndroidInstallGateway(
            context = context,
            installSystem = { transactionId, file, installer ->
                bridge.installSystemAttempt(transactionId, file, installer)
            },
            commitPlanProvider = {
                AndroidCommitPlan(
                    AndroidInstallLocation.SYSTEM,
                    BasePreferences.ExtensionInstaller.PACKAGEINSTALLER,
                )
            },
            apkInspector = {
                AndroidApk(PACKAGE_NAME, "1.4.2", 2, setOf("signer-a"), isExtension = true)
            },
        )
        bridge = ExtensionInstaller(
            context = context,
            runtimeReloader = {},
            scope = installerScope,
            gateway = gateway,
            client = OkHttpClient(),
            installerProvider = { BasePreferences.ExtensionInstaller.PACKAGEINSTALLER },
        )
        ExtensionInstaller::class.java.getDeclaredField("systemAttemptsByParent").apply {
            isAccessible = true
            set(bridge, BlockingSystemAttempts(removalStarted, allowRemoval))
        }
        every { harness.manager.updateInstallStep(any(), any()) } answers {
            bridge.updateInstallStep(firstArg(), secondArg())
        }
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { ContextCompat.startForegroundService(context, any()) } answers {
            childAttempts += checkNotNull(capturedStringExtras[ExtensionInstaller.EXTRA_TRANSACTION_ID])
            harness.enqueue(childAttempts.single())
            childStarted.countDown()
            mockk()
        }

        MockWebServer().also { it.start() }.use { server ->
            val candidate = "candidate-system".toByteArray()
            server.enqueue(MockResponse(body = candidate.decodeToString()))
            val extension = availableExtension(PACKAGE_NAME).copy(
                versionName = "1.4.2",
                versionCode = 2,
                repoName = REPOSITORY.name,
                repoFingerprint = REPOSITORY.signingKeyFingerprint,
                declaredSha256 = Hash.sha256(candidate),
            )
            val terminal = async(start = CoroutineStart.UNDISPATCHED) {
                bridge.downloadAndInstall(server.url("/example.apk").toString(), extension)
                    .first(InstallStep::isCompleted)
            }

            try {
                assertTrue(
                    childStarted.await(10, TimeUnit.SECONDS),
                    "terminal=${terminal.takeIf { it.isCompleted }?.await()} active=${activeTransactionIds(bridge)}",
                )
                harness.callback(PackageInstaller.STATUS_SUCCESS)
                harness.installer.onDestroy()
                assertTrue(removalStarted.await(10, TimeUnit.SECONDS))
                assertTrue(systemAttemptIds(bridge).isEmpty(), "parent-to-child mapping must already be removed")

                bridge.cancelInstall(PACKAGE_NAME)
                runCurrent()

                assertFalse(terminal.isCompleted, "cancellation must wait for rollback and cleanup")
                assertTrue(
                    activeTransactionIds(bridge).containsKey(PACKAGE_NAME),
                    "receiver gate must remain active during child teardown",
                )

                allowRemoval.countDown()

                assertEquals(InstallStep.Idle, terminal.await())
                assertTrue(activeTransactionIds(bridge).isEmpty())
                bridge.updateInstallStep(childAttempts.single(), InstallStep.Installed)
                assertTrue(activeTransactionIds(bridge).isEmpty())
            } finally {
                allowRemoval.countDown()
                installerScope.cancel()
                unmockkStatic(FileProvider::class)
            }
        }
    }

    @BeforeEach
    fun interceptLocalBroadcastManager() {
        TRANSACTION_ONE = UUID.randomUUID().toString()
        TRANSACTION_TWO = UUID.randomUUID().toString()
        capturedStringExtras.clear()
        capturedIntExtras.clear()
        pendingIdentity = null
        committedIdentity = null
        cancelReceivers.clear()
        cancelIntents.clear()
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers {
            capturedStringExtras[firstArg()] = secondArg()
            self as Intent
        }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Int>()) } answers {
            capturedIntExtras[firstArg()] = secondArg()
            self as Intent
        }
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<java.io.Serializable>())
        } answers { self as Intent }
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setDataAndType(any(), any()) } answers { self as Intent }
        every { anyConstructed<Intent>().getStringExtra(any()) } answers { capturedStringExtras[firstArg()] }
        mockkConstructor(PackageInstaller.SessionParams::class)
        every { anyConstructed<PackageInstaller.SessionParams>().setSize(any()) } just runs
        mockkStatic(PendingIntent::class)
        val pendingIntent = mockk<PendingIntent> {
            every { intentSender } returns mockk()
        }
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } answers {
            pendingIdentity = CallbackIdentity(
                transactionId = checkNotNull(capturedStringExtras[ExtensionInstaller.EXTRA_TRANSACTION_ID]),
                sessionId = checkNotNull(capturedIntExtras[PackageInstaller.EXTRA_SESSION_ID]),
            )
            pendingIntent
        }
        mockkStatic(UniFile::class)
        every { UniFile.fromUri(any(), any()) } returns mockk {
            every { length() } returns 1L
        }
        mockkStatic(LocalBroadcastManager::class)
        mockkStatic(ContextCompat::class)
        localBroadcastManager = mockk(relaxed = true)
        every { LocalBroadcastManager.getInstance(any()) } returns localBroadcastManager
        every { localBroadcastManager.registerReceiver(any<BroadcastReceiver>(), any()) } answers {
            cancelReceivers += firstArg<BroadcastReceiver>()
        }
        every { localBroadcastManager.sendBroadcast(any<Intent>()) } answers {
            cancelIntents += firstArg<Intent>()
            true
        }
        every {
            ContextCompat.registerReceiver(any(), any(), any(), ContextCompat.RECEIVER_NOT_EXPORTED)
        } returns null
    }

    @AfterEach
    fun restoreLocalBroadcastManager() {
        unmockkStatic(UniFile::class)
        unmockkStatic(PendingIntent::class)
        unmockkConstructor(PackageInstaller.SessionParams::class)
        unmockkConstructor(Intent::class)
        unmockkStatic(LocalBroadcastManager::class)
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `success error and abort each publish one terminal`() {
        listOf(
            PackageInstaller.STATUS_SUCCESS to InstallStep.Installed,
            PackageInstaller.STATUS_FAILURE to InstallStep.Error,
            PackageInstaller.STATUS_FAILURE_ABORTED to InstallStep.Idle,
        ).forEach { (status, expected) ->
            val harness = packageInstallerHarness()

            harness.enqueue(TRANSACTION_ONE)
            harness.callback(status)
            harness.callback(status)

            verify(exactly = 1) { harness.manager.updateInstallStep(TRANSACTION_ONE, expected) }
        }
    }

    @Test
    fun `pending user action is accepted only for the active transaction and session`() {
        val harness = packageInstallerHarness()
        harness.enqueue(TRANSACTION_ONE)

        harness.callback(
            PackageInstaller.STATUS_PENDING_USER_ACTION,
            transactionId = TRANSACTION_TWO,
            sessionId = SESSION_ONE,
        )
        harness.callback(
            PackageInstaller.STATUS_PENDING_USER_ACTION,
            transactionId = TRANSACTION_ONE,
            sessionId = SESSION_TWO,
        )

        verify(exactly = 0) { harness.service.startActivity(any()) }
        verify(exactly = 0) { harness.manager.updateInstallStep(any(), any()) }
        assertTrue(harness.isActive(TRANSACTION_ONE, SESSION_ONE))

        val userAction = mockk<Intent>(relaxed = true) {
            every { action } returns "android.content.pm.action.CONFIRM_INSTALL"
        }
        mockkConstructor(IntentSanitizer.Builder::class)
        val sanitizer = mockk<IntentSanitizer> {
            every { sanitizeByFiltering(any()) } returns userAction
        }
        every { anyConstructed<IntentSanitizer.Builder>().allowAction(any<String>()) } answers {
            self as IntentSanitizer.Builder
        }
        every {
            anyConstructed<IntentSanitizer.Builder>().allowExtra(
                any<String>(),
                any<androidx.core.util.Predicate<Any>>(),
            )
        } answers { self as IntentSanitizer.Builder }
        every { anyConstructed<IntentSanitizer.Builder>().allowAnyComponent() } answers {
            self as IntentSanitizer.Builder
        }
        every {
            anyConstructed<IntentSanitizer.Builder>().allowPackage(any<androidx.core.util.Predicate<String>>())
        } answers {
            self as IntentSanitizer.Builder
        }
        every { anyConstructed<IntentSanitizer.Builder>().build() } returns sanitizer
        try {
            harness.callback(PackageInstaller.STATUS_PENDING_USER_ACTION, userAction = userAction)
        } finally {
            unmockkConstructor(IntentSanitizer.Builder::class)
        }

        verify(exactly = 1) { harness.service.startActivity(userAction) }
        assertTrue(harness.isActive(TRANSACTION_ONE, SESSION_ONE))
    }

    @Test
    fun `late callback from a same-package retry cannot terminate the new transaction`() {
        val harness = packageInstallerHarness()
        harness.enqueue(TRANSACTION_ONE)
        harness.callback(PackageInstaller.STATUS_FAILURE_ABORTED)
        harness.useSession(SESSION_TWO)
        harness.enqueue(TRANSACTION_TWO)

        harness.callback(PackageInstaller.STATUS_SUCCESS, TRANSACTION_ONE, SESSION_ONE)

        verify(exactly = 0) { harness.manager.updateInstallStep(TRANSACTION_TWO, InstallStep.Installed) }
        assertTrue(harness.isActive(TRANSACTION_TWO, SESSION_TWO))
    }

    @Test
    fun `callback must match both active transaction and session`() {
        val harness = packageInstallerHarness()
        harness.enqueue(TRANSACTION_ONE)

        harness.callback(PackageInstaller.STATUS_SUCCESS, TRANSACTION_TWO, SESSION_ONE)
        harness.callback(PackageInstaller.STATUS_SUCCESS, TRANSACTION_ONE, SESSION_TWO)

        verify(exactly = 0) { harness.manager.updateInstallStep(TRANSACTION_ONE, InstallStep.Installed) }
        assertTrue(harness.isActive(TRANSACTION_ONE, SESSION_ONE))
    }

    @Test
    fun `public cancel remains a durable tombstone after the first enqueue consumes its acknowledgement`() {
        val context = mockk<Context>(relaxed = true)
        val transactionId = UUID.randomUUID().toString()

        val acknowledgement = Installer.cancelInstallQueue(context, transactionId)
        val harness = queueHarness()
        harness.installer.addToQueue(transactionId, mockk())
        harness.installer.addToQueue(transactionId, mockk())

        assertTrue(acknowledgement.isCompleted)
        assertEquals(emptyList<String>(), harness.processed)
    }

    @Test
    fun `cancelling active package session abandons it without accepting a late callback`() {
        val harness = packageInstallerHarness()
        harness.enqueue(TRANSACTION_ONE)

        harness.cancelActive()
        harness.installer.onDestroy()
        harness.callback(PackageInstaller.STATUS_SUCCESS, TRANSACTION_ONE, SESSION_ONE)

        verify(exactly = 1) { harness.packageInstaller.abandonSession(SESSION_ONE) }
        verify(exactly = 0) { harness.manager.updateInstallStep(TRANSACTION_ONE, InstallStep.Idle) }
        verify(exactly = 0) { harness.manager.updateInstallStep(TRANSACTION_ONE, InstallStep.Installed) }
    }

    @Test
    fun `cancel acknowledgement completes only after active package cleanup`() {
        val harness = packageInstallerHarness()
        harness.enqueue(TRANSACTION_ONE)

        val acknowledgement = Installer.cancelInstallQueue(harness.service, TRANSACTION_ONE)

        assertFalse(acknowledgement.isCompleted)
        harness.deliverCancellation()
        assertFalse(acknowledgement.isCompleted)
        harness.installer.onDestroy()
        assertTrue(acknowledgement.isCompleted)
        verify(exactly = 1) { harness.packageInstaller.abandonSession(SESSION_ONE) }
        verify(exactly = 1) { harness.service.unregisterReceiver(any()) }
        verify(exactly = 1) { harness.service.stopSelf() }
        assertFalse(harness.isActive(TRANSACTION_ONE, SESSION_ONE))
    }

    @Test
    fun `cancelling active package keeps callback receiver for the next queued entry`() {
        val harness = packageInstallerHarness()
        harness.enqueue(TRANSACTION_ONE)
        harness.useSession(SESSION_TWO)
        harness.installer.addToQueue(TRANSACTION_TWO, mockk())

        val acknowledgement = Installer.cancelInstallQueue(harness.service, TRANSACTION_ONE)
        harness.deliverCancellation()

        assertTrue(acknowledgement.isCompleted)
        verify(exactly = 0) { harness.service.unregisterReceiver(any()) }
        assertEquals(CallbackIdentity(TRANSACTION_TWO, SESSION_TWO), committedIdentity)
        assertTrue(harness.isActive(TRANSACTION_TWO, SESSION_TWO))

        harness.callback(PackageInstaller.STATUS_SUCCESS)
        verify(exactly = 1) { harness.manager.updateInstallStep(TRANSACTION_TWO, InstallStep.Installed) }
    }

    @Test
    fun `service destroy without callback abandons the session and publishes one error terminal`() {
        val harness = packageInstallerHarness()
        harness.enqueue(TRANSACTION_ONE)

        harness.installer.onDestroy()

        verify(exactly = 1) { harness.packageInstaller.abandonSession(SESSION_ONE) }
        verify(exactly = 1) { harness.manager.updateInstallStep(TRANSACTION_ONE, InstallStep.Error) }
    }

    @Test
    fun `transaction identity is UUID based and does not collide for Java hash collisions`() = runTest {
        val firstPackage = "extension.Aa"
        val secondPackage = "extension.BB"
        assertEquals(firstPackage.hashCode(), secondPackage.hashCode())
        val installer = ExtensionInstaller(
            context = mockk(relaxed = true),
            scope = backgroundScope,
            installPort = mockk<ExtensionInstallPort>(relaxed = true),
        )

        installer.downloadAndInstall("https://repo.example/aa.apk", availableExtension(firstPackage))
        installer.downloadAndInstall("https://repo.example/bb.apk", availableExtension(secondPackage))

        val transactions = activeTransactionIds(installer)
        val first = checkNotNull(transactions[firstPackage])
        val second = checkNotNull(transactions[secondPackage])

        UUID.fromString(first)
        UUID.fromString(second)
        assertNotEquals(first, second)
        assertFalse(first == firstPackage.hashCode().toString())
        assertFalse(second == secondPackage.hashCode().toString())
    }

    @Test
    fun `platform timeout waits for package cleanup before failing the transaction`() = runTest {
        val harness = packageInstallerHarness()
        val context = mockk<Context>(relaxed = true) {
            every { packageName } returns "eu.kanade.tachiyomi"
        }
        val installer = ExtensionInstaller(
            context = context,
            scope = backgroundScope,
            installPort = mockk<ExtensionInstallPort>(relaxed = true),
        )
        val preference = mockk<ExtensionInstallerPreference> {
            every { get() } returns BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
        }
        ExtensionInstaller::class.java.getDeclaredField("extensionInstaller\$delegate").apply {
            isAccessible = true
            set(installer, lazyOf(preference))
        }
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { ContextCompat.startForegroundService(context, any()) } answers {
            harness.enqueue(TRANSACTION_ONE)
            mockk()
        }
        val waiting = async {
            runCatching { installPrepared(installer, TRANSACTION_ONE, File("extension.apk")) }.exceptionOrNull()
        }

        try {
            runCurrent()
            advanceTimeBy(2 * 60 * 1000L)
            runCurrent()

            val earlyFailure = waiting.takeIf { it.isCompleted }?.await()
            assertFalse(
                waiting.isCompleted,
                "earlyFailure=$earlyFailure target=" +
                    "${(earlyFailure as? java.lang.reflect.InvocationTargetException)?.targetException} " +
                    "cancelIntents=${cancelIntents.size} active=" +
                    harness.isActive(TRANSACTION_ONE, SESSION_ONE),
            )
            verify(exactly = 1) { harness.packageInstaller.abandonSession(SESSION_ONE) }
            harness.installer.onDestroy()
            runCurrent()

            assertTrue(waiting.isCompleted)
            assertInstanceOf(ExtensionInstallFailure::class.java, waiting.await())
            verify(exactly = 1) { harness.packageInstaller.abandonSession(SESSION_ONE) }
            assertTrue(platformResults(installer).isEmpty())
        } finally {
            unmockkStatic(FileProvider::class)
        }
    }

    @Test
    fun `public cancellation waits for package destroy before rollback and terminal cleanup`() = runTest {
        val harness = packageInstallerHarness()
        val context = mockk<Context>(relaxed = true) {
            every { packageName } returns "eu.kanade.tachiyomi"
        }
        val calls = mutableListOf<String>()
        lateinit var installer: ExtensionInstaller
        val port = object : ExtensionInstallPort {
            override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken {
                calls += "prepare"
                return PreparedExtensionInstallToken(
                    checkNotNull(activeTransactionIds(installer)[request.artifact.packageName]),
                )
            }

            override suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken {
                calls += "validate"
                return ExtensionInstallRollbackToken(token.value)
            }

            override suspend fun commit(token: PreparedExtensionInstallToken) {
                calls += "commit"
                installPrepared(installer, token.value, File("extension.apk"))
            }

            override suspend fun reload(packageName: String) {
                calls += "reload"
            }

            override suspend fun rollback(token: ExtensionInstallRollbackToken) {
                calls += "rollback"
            }

            override suspend fun cleanup(token: PreparedExtensionInstallToken) {
                calls += "cleanup"
            }
        }
        installer = ExtensionInstaller(
            context = context,
            scope = backgroundScope,
            installPort = port,
        )
        val preference = mockk<ExtensionInstallerPreference> {
            every { get() } returns BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
        }
        ExtensionInstaller::class.java.getDeclaredField("extensionInstaller\$delegate").apply {
            isAccessible = true
            set(installer, lazyOf(preference))
        }
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { ContextCompat.startForegroundService(context, any()) } answers {
            val transactionId = activeTransactionIds(installer).values.single()
            harness.enqueue(transactionId)
            mockk()
        }
        val extension = availableExtension("extension.package.cleanup")
        val steps = installer.downloadAndInstall("https://repo.example/extension.apk", extension)
        val terminal = async { steps.first(InstallStep::isCompleted) }

        try {
            runCurrent()
            val transactionId = activeTransactionIds(installer).getValue(extension.pkgName)
            installer.cancelInstall(extension.pkgName)
            runCurrent()

            assertFalse(terminal.isCompleted)
            assertEquals(1, cancelIntents.size)
            assertEquals(listOf("prepare", "validate", "commit"), calls)
            assertTrue(activeTransactionIds(installer).containsKey(extension.pkgName))
            assertTrue(platformResults(installer).containsKey(transactionId))

            harness.installer.onDestroy()
            runCurrent()

            assertEquals(InstallStep.Idle, terminal.await())
            verify(exactly = 1) { harness.packageInstaller.abandonSession(SESSION_ONE) }
            assertTrue(platformResults(installer).isEmpty())
            assertTrue(activeTransactionIds(installer).isEmpty())
            assertTrue(activeJobs(installer).isEmpty())
            assertEquals(listOf("prepare", "validate", "commit", "rollback", "reload", "cleanup"), calls)
        } finally {
            unmockkStatic(FileProvider::class)
        }
    }

    @Test
    fun `public cancellation cannot publish terminal while platform startup handoff is blocked`() = runTest {
        val harness = packageInstallerHarness()
        val context = mockk<Context>(relaxed = true) {
            every { packageName } returns "eu.kanade.tachiyomi"
        }
        val platformRegistrationStarted = CountDownLatch(1)
        val allowPlatformRegistration = CountDownLatch(1)
        val blockedResults = BlockingPlatformResults(platformRegistrationStarted, allowPlatformRegistration)
        val cancellationLookup = CountDownLatch(1)
        val cancellationReturned = CountDownLatch(1)
        val installerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var installer: ExtensionInstaller
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val port = object : ExtensionInstallPort {
            override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken {
                calls += "prepare"
                return PreparedExtensionInstallToken(
                    checkNotNull(activeTransactionIds(installer)[request.artifact.packageName]),
                )
            }

            override suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken {
                calls += "validate"
                return ExtensionInstallRollbackToken(token.value)
            }

            override suspend fun commit(token: PreparedExtensionInstallToken) {
                calls += "commit"
                installPrepared(installer, token.value, File("extension.apk"))
            }

            override suspend fun reload(packageName: String) {
                calls += "reload"
            }

            override suspend fun rollback(token: ExtensionInstallRollbackToken) {
                calls += "rollback"
            }

            override suspend fun cleanup(token: PreparedExtensionInstallToken) {
                calls += "cleanup"
            }
        }
        installer = ExtensionInstaller(context, scope = installerScope, installPort = port)
        ExtensionInstaller::class.java.getDeclaredField("platformResults").apply {
            isAccessible = true
            set(installer, blockedResults)
        }
        ExtensionInstaller::class.java.getDeclaredField("activeTransactions").apply {
            isAccessible = true
            set(installer, CancellationObservedTransactions(cancellationLookup))
        }
        val preference = mockk<ExtensionInstallerPreference> {
            every { get() } returns BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
        }
        ExtensionInstaller::class.java.getDeclaredField("extensionInstaller\$delegate").apply {
            isAccessible = true
            set(installer, lazyOf(preference))
        }
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { ContextCompat.startForegroundService(context, any()) } answers {
            harness.enqueue(activeTransactionIds(installer).values.single())
            mockk()
        }
        val extension = availableExtension("extension.package.startup-race")
        val steps = installer.downloadAndInstall("https://repo.example/extension.apk", extension)
        val terminal = async { steps.first(InstallStep::isCompleted) }

        try {
            assertTrue(platformRegistrationStarted.await(10, TimeUnit.SECONDS))
            thread {
                installer.cancelInstall(extension.pkgName)
                cancellationReturned.countDown()
            }
            assertTrue(cancellationLookup.await(10, TimeUnit.SECONDS))
            allowPlatformRegistration.countDown()
            assertTrue(cancellationReturned.await(10, TimeUnit.SECONDS))
            runCurrent()

            assertFalse(terminal.isCompleted, "Idle must wait until the committed platform owner is destroyed")
            assertEquals(listOf("prepare", "validate", "commit"), calls)

            harness.installer.onDestroy()
            assertEquals(InstallStep.Idle, terminal.await())
            assertEquals(listOf("prepare", "validate", "commit", "rollback", "reload", "cleanup"), calls)
            assertTrue(platformResults(installer).isEmpty())
            assertTrue(activeTransactionIds(installer).isEmpty())
            assertTrue(activeJobs(installer).isEmpty())
            assertTrue(coordinatorFlights(installer).isEmpty())
        } finally {
            allowPlatformRegistration.countDown()
            installerScope.cancel()
            unmockkStatic(FileProvider::class)
        }
    }

    @Test
    fun `public cancellation after platform result waits for coordinator finishing`() = runTest {
        val harness = packageInstallerHarness()
        val context = mockk<Context>(relaxed = true) {
            every { packageName } returns "eu.kanade.tachiyomi"
        }
        val reloadStarted = CountDownLatch(1)
        val allowReload = CountDownLatch(1)
        val rollbackStarted = CountDownLatch(1)
        val allowRollback = CountDownLatch(1)
        val platformCommitted = CountDownLatch(1)
        val installerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        lateinit var installer: ExtensionInstaller
        val port = object : ExtensionInstallPort {
            override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken {
                calls += "prepare"
                return PreparedExtensionInstallToken(
                    checkNotNull(activeTransactionIds(installer)[request.artifact.packageName]),
                )
            }

            override suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken {
                calls += "validate"
                return ExtensionInstallRollbackToken(token.value)
            }

            override suspend fun commit(token: PreparedExtensionInstallToken) {
                calls += "commit"
                installPrepared(installer, token.value, File("extension.apk"))
            }

            override suspend fun reload(packageName: String) {
                calls += "reload"
                reloadStarted.countDown()
                allowReload.await(10, TimeUnit.SECONDS)
            }

            override suspend fun rollback(token: ExtensionInstallRollbackToken) {
                calls += "rollback"
                rollbackStarted.countDown()
                allowRollback.await(10, TimeUnit.SECONDS)
            }

            override suspend fun cleanup(token: PreparedExtensionInstallToken) {
                calls += "cleanup"
            }
        }
        installer = ExtensionInstaller(context, scope = installerScope, installPort = port)
        every { harness.manager.updateInstallStep(any(), any()) } answers {
            installer.updateInstallStep(firstArg(), secondArg())
        }
        val preference = mockk<ExtensionInstallerPreference> {
            every { get() } returns BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
        }
        ExtensionInstaller::class.java.getDeclaredField("extensionInstaller\$delegate").apply {
            isAccessible = true
            set(installer, lazyOf(preference))
        }
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { ContextCompat.startForegroundService(context, any()) } answers {
            harness.enqueue(activeTransactionIds(installer).values.single())
            platformCommitted.countDown()
            mockk()
        }
        val extension = availableExtension("extension.package.finishing-race")
        val terminalSteps = Collections.synchronizedList(mutableListOf<InstallStep>())
        val terminal = async {
            installer.downloadAndInstall("https://repo.example/extension.apk", extension)
                .first(InstallStep::isCompleted)
                .also(terminalSteps::add)
        }

        try {
            runCurrent()
            assertTrue(platformCommitted.await(10, TimeUnit.SECONDS))
            val transactionId = activeTransactionIds(installer).getValue(extension.pkgName)
            harness.callback(PackageInstaller.STATUS_SUCCESS)
            assertTrue(reloadStarted.await(10, TimeUnit.SECONDS))

            assertTrue(platformResults(installer).isEmpty(), "platform result must already be consumed")
            assertTrue(coordinatorFlights(installer).isNotEmpty())
            installer.cancelInstall(extension.pkgName)
            runCurrent()

            assertFalse(terminal.isCompleted, "Idle must not precede rollback/cleanup/flight completion")
            assertTrue(activeTransactionIds(installer).containsKey(extension.pkgName))
            assertTrue(activeJobs(installer).isNotEmpty())
            assertTrue(coordinatorFlights(installer).isNotEmpty())

            allowReload.countDown()
            assertTrue(rollbackStarted.await(10, TimeUnit.SECONDS))
            assertFalse(terminal.isCompleted, "Idle must not precede rollback completion")
            assertTrue(coordinatorFlights(installer).isNotEmpty())

            allowRollback.countDown()
            assertEquals(InstallStep.Idle, terminal.await())
            assertEquals(listOf(InstallStep.Idle), terminalSteps)
            assertEquals(listOf("prepare", "validate", "commit", "reload", "rollback", "reload", "cleanup"), calls)
            assertTrue(platformResults(installer).isEmpty())
            assertTrue(activeTransactionIds(installer).isEmpty())
            assertTrue(activeJobs(installer).isEmpty())
            assertTrue(coordinatorFlights(installer).isEmpty())
            assertFalse(transactionLifecycles(installer).containsKey(transactionId))
        } finally {
            allowReload.countDown()
            allowRollback.countDown()
            installerScope.cancel()
            unmockkStatic(FileProvider::class)
        }
    }

    @Test
    fun `unsubscribing during package platform await retains lifecycle until flight cleanup completes`() = runTest {
        val harness = packageInstallerHarness()
        val context = mockk<Context>(relaxed = true) {
            every { packageName } returns "eu.kanade.tachiyomi"
        }
        val rollbackStarted = CompletableDeferred<Unit>()
        val allowRollback = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        lateinit var installer: ExtensionInstaller
        val port = object : ExtensionInstallPort {
            override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken {
                calls += "prepare"
                return PreparedExtensionInstallToken(
                    checkNotNull(activeTransactionIds(installer)[request.artifact.packageName]),
                )
            }

            override suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken {
                calls += "validate"
                return ExtensionInstallRollbackToken(token.value)
            }

            override suspend fun commit(token: PreparedExtensionInstallToken) {
                calls += "commit"
                installPrepared(installer, token.value, File("extension.apk"))
            }

            override suspend fun reload(packageName: String) {
                calls += "reload"
            }

            override suspend fun rollback(token: ExtensionInstallRollbackToken) {
                calls += "rollback"
                rollbackStarted.complete(Unit)
                allowRollback.await()
            }

            override suspend fun cleanup(token: PreparedExtensionInstallToken) {
                calls += "cleanup"
                cleanupStarted.complete(Unit)
                allowCleanup.await()
            }
        }
        installer = ExtensionInstaller(context, scope = backgroundScope, installPort = port)
        val preference = mockk<ExtensionInstallerPreference> {
            every { get() } returns BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
        }
        ExtensionInstaller::class.java.getDeclaredField("extensionInstaller\$delegate").apply {
            isAccessible = true
            set(installer, lazyOf(preference))
        }
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { ContextCompat.startForegroundService(context, any()) } answers {
            harness.enqueue(activeTransactionIds(installer).values.single())
            mockk()
        }
        val extension = availableExtension("extension.package.unsubscribe-platform-await")
        val steps = installer.downloadAndInstall("https://repo.example/extension.apk", extension)
        val collector = backgroundScope.launch { steps.collect() }

        try {
            runCurrent()
            val transactionId = activeTransactionIds(installer).getValue(extension.pkgName)
            val lifecycle = transactionLifecycles(installer).getValue(transactionId)
            assertEquals("HANDED_OFF", lifecyclePhase(lifecycle))

            collector.cancelAndJoin()
            runCurrent()
            assertTrue(platformResults(installer).containsKey(transactionId))
            assertTrue(coordinatorFlights(installer).isNotEmpty())

            harness.installer.onDestroy()
            rollbackStarted.await()
            installer.updateInstallStep(transactionId, InstallStep.Idle)
            ageCompletedTransactions(installer)
            installer.updateInstallStep(UUID.randomUUID().toString(), InstallStep.Installed)

            assertEquals("FINISHING", lifecyclePhase(lifecycle))
            assertTrue(transactionLifecycles(installer).containsKey(transactionId))
            assertTrue(completedTransactions(installer).containsKey(transactionId))
            assertTrue(coordinatorFlights(installer).isNotEmpty())

            allowRollback.complete(Unit)
            cleanupStarted.await()
            ageCompletedTransactions(installer)
            installer.updateInstallStep(UUID.randomUUID().toString(), InstallStep.Installed)

            assertEquals("FINISHING", lifecyclePhase(lifecycle))
            assertTrue(completedTransactions(installer).containsKey(transactionId))
            assertTrue(coordinatorFlights(installer).isNotEmpty())

            allowCleanup.complete(Unit)
            runCurrent()

            assertEquals("COMPLETE", lifecyclePhase(lifecycle))
            assertFalse(transactionLifecycles(installer).containsKey(transactionId))
            assertTrue(platformResults(installer).isEmpty())
            assertTrue(activeTransactionIds(installer).isEmpty())
            assertTrue(activeJobs(installer).isEmpty())
            assertTrue(activeSteps(installer).isEmpty())
            assertTrue(coordinatorFlights(installer).isEmpty())
            assertEquals(listOf("prepare", "validate", "commit", "rollback", "reload", "cleanup"), calls)

            ageCompletedTransactions(installer)
            installer.updateInstallStep(UUID.randomUUID().toString(), InstallStep.Installed)
            assertFalse(completedTransactions(installer).containsKey(transactionId))
        } finally {
            allowRollback.complete(Unit)
            allowCleanup.complete(Unit)
            unmockkStatic(FileProvider::class)
        }
    }

    @Test
    fun `unsubscribing after package result retains finishing lifecycle until flight completes`() = runTest {
        val harness = packageInstallerHarness()
        val context = mockk<Context>(relaxed = true) {
            every { packageName } returns "eu.kanade.tachiyomi"
        }
        val reloadStarted = CompletableDeferred<Unit>()
        val allowReload = CompletableDeferred<Unit>()
        val rollbackStarted = CompletableDeferred<Unit>()
        val allowRollback = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        var reloadCalls = 0
        lateinit var installer: ExtensionInstaller
        val port = object : ExtensionInstallPort {
            override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken {
                calls += "prepare"
                return PreparedExtensionInstallToken(
                    checkNotNull(activeTransactionIds(installer)[request.artifact.packageName]),
                )
            }

            override suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken {
                calls += "validate"
                return ExtensionInstallRollbackToken(token.value)
            }

            override suspend fun commit(token: PreparedExtensionInstallToken) {
                calls += "commit"
                installPrepared(installer, token.value, File("extension.apk"))
            }

            override suspend fun reload(packageName: String) {
                calls += "reload"
                reloadCalls++
                if (reloadCalls == 1) {
                    reloadStarted.complete(Unit)
                    allowReload.await()
                }
            }

            override suspend fun rollback(token: ExtensionInstallRollbackToken) {
                calls += "rollback"
                rollbackStarted.complete(Unit)
                allowRollback.await()
            }

            override suspend fun cleanup(token: PreparedExtensionInstallToken) {
                calls += "cleanup"
                cleanupStarted.complete(Unit)
                allowCleanup.await()
            }
        }
        installer = ExtensionInstaller(context, scope = backgroundScope, installPort = port)
        every { harness.manager.updateInstallStep(any(), any()) } answers {
            installer.updateInstallStep(firstArg(), secondArg())
        }
        val preference = mockk<ExtensionInstallerPreference> {
            every { get() } returns BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
        }
        ExtensionInstaller::class.java.getDeclaredField("extensionInstaller\$delegate").apply {
            isAccessible = true
            set(installer, lazyOf(preference))
        }
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { ContextCompat.startForegroundService(context, any()) } answers {
            harness.enqueue(activeTransactionIds(installer).values.single())
            mockk()
        }
        val extension = availableExtension("extension.package.unsubscribe-reload")
        val steps = installer.downloadAndInstall("https://repo.example/extension.apk", extension)
        val collector = backgroundScope.launch { steps.collect() }

        try {
            runCurrent()
            val transactionId = activeTransactionIds(installer).getValue(extension.pkgName)
            val lifecycle = transactionLifecycles(installer).getValue(transactionId)
            harness.callback(PackageInstaller.STATUS_SUCCESS)
            reloadStarted.await()

            assertTrue(platformResults(installer).isEmpty())
            assertEquals("FINISHING", lifecyclePhase(lifecycle))
            assertTrue(completedTransactions(installer).containsKey(transactionId))

            collector.cancelAndJoin()
            rollbackStarted.await()
            ageCompletedTransactions(installer)
            installer.updateInstallStep(UUID.randomUUID().toString(), InstallStep.Installed)

            assertEquals("FINISHING", lifecyclePhase(lifecycle))
            assertTrue(transactionLifecycles(installer).containsKey(transactionId))
            assertTrue(completedTransactions(installer).containsKey(transactionId))
            assertTrue(coordinatorFlights(installer).isNotEmpty())

            allowRollback.complete(Unit)
            cleanupStarted.await()
            assertEquals("FINISHING", lifecyclePhase(lifecycle))
            assertTrue(coordinatorFlights(installer).isNotEmpty())

            allowCleanup.complete(Unit)
            runCurrent()

            assertEquals("COMPLETE", lifecyclePhase(lifecycle))
            assertFalse(transactionLifecycles(installer).containsKey(transactionId))
            assertTrue(platformResults(installer).isEmpty())
            assertTrue(activeTransactionIds(installer).isEmpty())
            assertTrue(activeJobs(installer).isEmpty())
            assertTrue(activeSteps(installer).isEmpty())
            assertTrue(coordinatorFlights(installer).isEmpty())
            assertEquals(listOf("prepare", "validate", "commit", "reload", "rollback", "reload", "cleanup"), calls)

            ageCompletedTransactions(installer)
            installer.updateInstallStep(UUID.randomUUID().toString(), InstallStep.Installed)
            assertFalse(completedTransactions(installer).containsKey(transactionId))
        } finally {
            allowReload.complete(Unit)
            allowRollback.complete(Unit)
            allowCleanup.complete(Unit)
            unmockkStatic(FileProvider::class)
        }
    }

    @Test
    fun `expired tombstone is retained while its transaction job is active`() = runTest {
        val prepareStarted = CompletableDeferred<Unit>()
        val allowPrepare = CompletableDeferred<Unit>()
        val port = object : ExtensionInstallPort {
            override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken {
                prepareStarted.complete(Unit)
                allowPrepare.await()
                throw CancellationException("test complete")
            }

            override suspend fun validate(token: PreparedExtensionInstallToken) = error("unexpected")
            override suspend fun commit(token: PreparedExtensionInstallToken) = error("unexpected")
            override suspend fun reload(packageName: String) = error("unexpected")
            override suspend fun rollback(token: ExtensionInstallRollbackToken) = error("unexpected")
            override suspend fun cleanup(token: PreparedExtensionInstallToken) = Unit
        }
        val installer = ExtensionInstaller(
            context = mockk(relaxed = true),
            scope = backgroundScope,
            installPort = port,
        )
        val extension = availableExtension("extension.package.active-job-pruning")
        installer.downloadAndInstall("https://repo.example/extension.apk", extension)
        prepareStarted.await()
        val transactionId = activeTransactionIds(installer).getValue(extension.pkgName)

        installer.updateInstallStep(transactionId, InstallStep.Installed)
        activeTransactions(installer).clear()
        activeSteps(installer).clear()
        transactionLifecycles(installer).clear()
        ageCompletedTransactions(installer)
        installer.updateInstallStep(UUID.randomUUID().toString(), InstallStep.Installed)

        assertTrue(completedTransactions(installer).containsKey(transactionId))
        allowPrepare.complete(Unit)
    }

    @Test
    fun `expired tombstone is retained while lifecycle is finishing`() = runTest {
        val harness = packageInstallerHarness()
        val context = mockk<Context>(relaxed = true) {
            every { packageName } returns "eu.kanade.tachiyomi"
        }
        val reloadStarted = CompletableDeferred<Unit>()
        val allowReload = CompletableDeferred<Unit>()
        lateinit var installer: ExtensionInstaller
        val port = object : ExtensionInstallPort {
            override suspend fun prepare(request: ExtensionInstallRequest) = PreparedExtensionInstallToken(
                checkNotNull(activeTransactionIds(installer)[request.artifact.packageName]),
            )

            override suspend fun validate(token: PreparedExtensionInstallToken) =
                ExtensionInstallRollbackToken(token.value)

            override suspend fun commit(token: PreparedExtensionInstallToken) {
                installPrepared(installer, token.value, File("extension.apk"))
            }

            override suspend fun reload(packageName: String) {
                reloadStarted.complete(Unit)
                allowReload.await()
            }

            override suspend fun rollback(token: ExtensionInstallRollbackToken) = Unit
            override suspend fun cleanup(token: PreparedExtensionInstallToken) = Unit
        }
        installer = ExtensionInstaller(context, scope = backgroundScope, installPort = port)
        every { harness.manager.updateInstallStep(any(), any()) } answers {
            installer.updateInstallStep(firstArg(), secondArg())
        }
        val preference = mockk<ExtensionInstallerPreference> {
            every { get() } returns BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
        }
        ExtensionInstaller::class.java.getDeclaredField("extensionInstaller\$delegate").apply {
            isAccessible = true
            set(installer, lazyOf(preference))
        }
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { ContextCompat.startForegroundService(context, any()) } answers {
            harness.enqueue(activeTransactionIds(installer).values.single())
            mockk()
        }
        val extension = availableExtension("extension.package.finishing-pruning")
        installer.downloadAndInstall("https://repo.example/extension.apk", extension)

        try {
            runCurrent()
            val transactionId = activeTransactionIds(installer).getValue(extension.pkgName)
            harness.callback(PackageInstaller.STATUS_SUCCESS)
            reloadStarted.await()
            val activeJob = activeJobsMutable(installer).remove(extension.pkgName)
            val activeTransaction = activeTransactions(installer).remove(extension.pkgName)
            val activeStep = activeSteps(installer).remove(transactionId)
            ageCompletedTransactions(installer)
            installer.updateInstallStep(UUID.randomUUID().toString(), InstallStep.Installed)

            assertTrue(completedTransactions(installer).containsKey(transactionId))

            checkNotNull(activeJob).also { activeJobsMutable(installer)[extension.pkgName] = it }
            checkNotNull(activeTransaction).also { activeTransactions(installer)[extension.pkgName] = it }
            checkNotNull(activeStep).also { activeSteps(installer)[transactionId] = it }
        } finally {
            allowReload.complete(Unit)
            unmockkStatic(FileProvider::class)
        }
    }

    @Test
    fun `completed transaction tombstones are pruned after expiry`() {
        val installer = ExtensionInstaller(
            context = mockk(relaxed = true),
            installPort = mockk(relaxed = true),
        )
        val activeTransaction = UUID.randomUUID().toString()
        val activeStep = kotlinx.coroutines.flow.MutableStateFlow(InstallStep.Pending)
        activeSteps(installer)[activeTransaction] = activeStep
        installer.updateInstallStep(activeTransaction, InstallStep.Installed)
        ageCompletedTransactions(installer)
        repeat(512) { installer.updateInstallStep(UUID.randomUUID().toString(), InstallStep.Installed) }
        ageCompletedTransactions(installer)

        val recentTransaction = UUID.randomUUID().toString()
        installer.updateInstallStep(recentTransaction, InstallStep.Installed)
        val recentCompletedAt = completedTransactions(installer).getValue(recentTransaction)
        installer.updateInstallStep(recentTransaction, InstallStep.Error)
        installer.updateInstallStep(activeTransaction, InstallStep.Installing)

        assertEquals(recentCompletedAt, completedTransactions(installer).getValue(recentTransaction))
        assertEquals(InstallStep.Pending, activeStep.value, "active tombstone must still reject duplicate updates")
        assertTrue(completedTransactions(installer).containsKey(activeTransaction))
        activeSteps(installer).remove(activeTransaction)
        ageCompletedTransactions(installer)
        installer.updateInstallStep(UUID.randomUUID().toString(), InstallStep.Installed)
        assertFalse(completedTransactions(installer).containsKey(activeTransaction))
        assertTrue(completedTransactionCount(installer) <= 2)
    }

    @Test
    fun `active Shizuku cancellation waits for its delayed callback and service destroy`() {
        val transactionId = UUID.randomUUID().toString()
        val manager = mockk<ExtensionManager>(relaxed = true)
        val service = mockk<Service>(relaxed = true) {
            every { applicationContext } returns this@mockk
        }
        val installer = object : Installer(service) {
            override var ready = true

            // Matches ShizukuInstaller: an active platform install cannot be abandoned.
            override fun cancelEntry(entry: Entry): Boolean = getActiveEntry() != entry

            fun deliverPlatformCallback() = continueQueue(InstallStep.Installed)

            fun hasActiveEntry(): Boolean = getActiveEntry() != null
        }.also {
            Installer::class.java.getDeclaredField("extensionManager\$delegate").apply {
                isAccessible = true
                set(it, lazyOf(manager))
            }
        }

        installer.addToQueue(transactionId, mockk())
        val acknowledgement = Installer.cancelInstallQueue(service, transactionId)
        cancelReceivers.last().onReceive(service, cancelIntents.removeLast())

        assertFalse(acknowledgement.isCompleted)
        verify(exactly = 0) { manager.updateInstallStep(transactionId, any()) }

        installer.deliverPlatformCallback()
        verify(exactly = 0) { manager.updateInstallStep(transactionId, any()) }

        installer.onDestroy()

        assertTrue(acknowledgement.isCompleted)
        assertFalse(installer.hasActiveEntry())
        verify(exactly = 0) { manager.updateInstallStep(transactionId, any()) }
    }

    private fun activeTransactionIds(installer: ExtensionInstaller): Map<String, String> {
        val field = ExtensionInstaller::class.java.getDeclaredField("activeTransactions").apply { isAccessible = true }
        val active = field.get(installer) as Map<*, *>
        return active.mapValues { (_, value) ->
            checkNotNull(value).javaClass.getDeclaredField("transactionId").apply { isAccessible = true }
                .get(value) as String
        }.mapKeys { (key, _) -> key as String }
    }

    @Suppress("UNCHECKED_CAST")
    private fun activeTransactions(installer: ExtensionInstaller): MutableMap<String, Any> =
        ExtensionInstaller::class.java.getDeclaredField("activeTransactions").apply { isAccessible = true }
            .get(installer) as MutableMap<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun activeJobs(installer: ExtensionInstaller): Map<String, Any> =
        ExtensionInstaller::class.java.getDeclaredField("activeJobs").apply { isAccessible = true }
            .get(installer) as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun activeJobsMutable(installer: ExtensionInstaller): MutableMap<String, Any> =
        ExtensionInstaller::class.java.getDeclaredField("activeJobs").apply { isAccessible = true }
            .get(installer) as MutableMap<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun transactionLifecycles(installer: ExtensionInstaller): MutableMap<String, Any> =
        ExtensionInstaller::class.java.getDeclaredField("transactionLifecycles").apply { isAccessible = true }
            .get(installer) as MutableMap<String, Any>

    private fun lifecyclePhase(lifecycle: Any): String =
        checkNotNull(
            lifecycle.javaClass.getDeclaredMethod("phase").apply { isAccessible = true }.invoke(lifecycle),
        ).toString()

    private suspend fun installPrepared(
        installer: ExtensionInstaller,
        transactionId: String,
        file: File,
    ): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = ExtensionInstaller::class.java.getDeclaredMethod(
            "installPrepared",
            String::class.java,
            File::class.java,
            kotlin.coroutines.Continuation::class.java,
        ).apply { isAccessible = true }
        val returned = method.invoke(installer, transactionId, file, continuation)
        if (returned === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
    }

    @Suppress("UNCHECKED_CAST")
    private fun platformResults(installer: ExtensionInstaller): Map<String, CompletableDeferred<InstallStep>> =
        ExtensionInstaller::class.java.getDeclaredField("platformResults").apply { isAccessible = true }
            .get(installer) as Map<String, CompletableDeferred<InstallStep>>

    @Suppress("UNCHECKED_CAST")
    private fun systemAttemptIds(installer: ExtensionInstaller): Map<String, String> =
        ExtensionInstaller::class.java.getDeclaredField("systemAttemptsByParent")
            .apply { isAccessible = true }
            .get(installer) as Map<String, String>

    private fun ageCompletedTransactions(installer: ExtensionInstaller) {
        val completed = ExtensionInstaller::class.java.getDeclaredField("completedTransactions")
            .apply { isAccessible = true }
            .get(installer)
        if (completed is ConcurrentHashMap<*, *>) {
            @Suppress("UNCHECKED_CAST")
            (completed as ConcurrentHashMap<String, Long>).replaceAll { _, _ -> Long.MIN_VALUE }
        }
    }

    private fun completedTransactionCount(installer: ExtensionInstaller): Int {
        val completed = ExtensionInstaller::class.java.getDeclaredField("completedTransactions")
            .apply { isAccessible = true }
            .get(installer)
        return when (completed) {
            is Map<*, *> -> completed.size
            is Set<*> -> completed.size
            else -> error("Unsupported completed transaction registry: ${completed.javaClass}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun completedTransactions(installer: ExtensionInstaller): Map<String, Long> =
        ExtensionInstaller::class.java.getDeclaredField("completedTransactions")
            .apply { isAccessible = true }
            .get(installer) as Map<String, Long>

    @Suppress("UNCHECKED_CAST")
    private fun activeSteps(
        installer: ExtensionInstaller,
    ): MutableMap<String, kotlinx.coroutines.flow.MutableStateFlow<InstallStep>> =
        ExtensionInstaller::class.java.getDeclaredField("activeSteps")
            .apply { isAccessible = true }
            .get(installer) as MutableMap<String, kotlinx.coroutines.flow.MutableStateFlow<InstallStep>>

    @Suppress("UNCHECKED_CAST")
    private fun coordinatorFlights(installer: ExtensionInstaller): Map<String, Any> {
        val coordinator = ExtensionInstaller::class.java.getDeclaredField("coordinator")
            .apply { isAccessible = true }
            .get(installer)
        return coordinator.javaClass.getDeclaredField("inFlight")
            .apply { isAccessible = true }
            .get(coordinator) as Map<String, Any>
    }

    private fun availableExtension(packageName: String) =
        Extension.Available(
            name = packageName,
            pkgName = packageName,
            versionName = "1.0",
            versionCode = 1,
            libVersion = 1.4,
            lang = "en",
            isNsfw = false,
            sources = emptyList(),
            apkName = "$packageName.apk",
            iconUrl = "https://repo.example/icon.png",
            repoUrl = "https://repo.example",
        )

    private fun packageInstallerHarness(): PackageHarness {
        val packageInstaller = mockk<PackageInstaller>(relaxed = true)
        val session = mockk<PackageInstaller.Session>(relaxed = true) {
            every { openWrite(any(), any(), any()) } returns ByteArrayOutputStream()
            every { commit(any()) } answers {
                committedIdentity = checkNotNull(pendingIdentity)
            }
        }
        every { packageInstaller.createSession(any()) } returns SESSION_ONE
        every { packageInstaller.openSession(any()) } returns session
        val packageManager = mockk<PackageManager> {
            every { this@mockk.packageInstaller } returns packageInstaller
        }
        val service = mockk<Service>(relaxed = true) {
            every { this@mockk.packageManager } returns packageManager
            every { applicationContext } returns this@mockk
            every { packageName } returns "eu.kanade.tachiyomi"
            every { contentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf(1))
        }
        val manager = mockk<ExtensionManager>(relaxed = true)
        val receiver = slot<BroadcastReceiver>()
        every {
            ContextCompat.registerReceiver(service, capture(receiver), any(), ContextCompat.RECEIVER_NOT_EXPORTED)
        } returns null
        val installer = PackageInstallerInstaller(service)
        Installer::class.java.getDeclaredField("extensionManager\$delegate").apply {
            isAccessible = true
            set(installer, lazyOf(manager))
        }
        return PackageHarness(service, packageInstaller, session, manager, installer, receiver.captured)
    }

    private fun queueHarness(): QueueHarness {
        val service = mockk<Service>(relaxed = true) {
            every { applicationContext } returns this@mockk
        }
        val processed = mutableListOf<String>()
        val installer = object : Installer(service) {
            override var ready = true

            override fun processEntry(entry: Entry) {
                processed += entry.transactionId
            }
        }
        return QueueHarness(installer, processed)
    }

    private inner class PackageHarness(
        val service: Service,
        val packageInstaller: PackageInstaller,
        private val session: PackageInstaller.Session,
        val manager: ExtensionManager,
        val installer: PackageInstallerInstaller,
        private val receiver: BroadcastReceiver,
    ) {
        private var expectedSessionId = SESSION_ONE
        private var expectedCommitCount = 0

        fun useSession(sessionId: Int) {
            expectedSessionId = sessionId
            every { packageInstaller.createSession(any()) } returns sessionId
        }

        fun enqueue(transactionId: String) {
            capturedStringExtras.clear()
            capturedIntExtras.clear()
            pendingIdentity = null
            committedIdentity = null
            installer.addToQueue(transactionId, mockk())
            expectedCommitCount++
            verify(exactly = expectedCommitCount) { session.commit(any()) }
            assertEquals(CallbackIdentity(transactionId, expectedSessionId), committedIdentity)
        }

        @Suppress("DEPRECATION")
        fun callback(
            status: Int,
            transactionId: String = checkNotNull(committedIdentity).transactionId,
            sessionId: Int = checkNotNull(committedIdentity).sessionId,
            userAction: Intent? = null,
        ) {
            val intent = mockk<Intent>(relaxed = true) {
                every {
                    getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                } returns status
                every { getIntExtra(PackageInstaller.EXTRA_SESSION_ID, any()) } returns sessionId
                every { getStringExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID) } returns transactionId
                every { getParcelableExtra<Intent>(Intent.EXTRA_INTENT) } returns userAction
            }
            receiver.onReceive(service, intent)
        }

        fun cancelActive() {
            Installer.cancelInstallQueue(service, TRANSACTION_ONE)
            deliverCancellation()
        }

        fun deliverCancellation() {
            val intent = cancelIntents.removeLast()
            cancelReceivers.last().onReceive(service, intent)
        }

        fun isActive(transactionId: String, sessionId: Int): Boolean {
            val reference = PackageInstallerInstaller::class.java.getDeclaredField("activeSession").apply {
                isAccessible = true
            }.get(installer) as AtomicReference<*>
            val active = reference.load() ?: return false
            val type = active.javaClass
            val entry = type.getDeclaredField("entry").apply { isAccessible = true }.get(active) as Installer.Entry
            val activeSessionId = type.getDeclaredField("sessionId").apply { isAccessible = true }.getInt(active)
            return entry.transactionId == transactionId && activeSessionId == sessionId
        }
    }

    private data class CallbackIdentity(val transactionId: String, val sessionId: Int)

    private class BlockingPlatformResults(
        private val registrationStarted: CountDownLatch,
        private val allowRegistration: CountDownLatch,
    ) : ConcurrentHashMap<String, CompletableDeferred<InstallStep>>() {
        override fun put(key: String, value: CompletableDeferred<InstallStep>): CompletableDeferred<InstallStep>? {
            registrationStarted.countDown()
            check(allowRegistration.await(10, TimeUnit.SECONDS))
            return super.put(key, value)
        }
    }

    private class BlockingSystemAttempts(
        private val removalStarted: CountDownLatch,
        private val allowRemoval: CountDownLatch,
    ) : ConcurrentHashMap<String, String>() {
        override fun remove(key: String, value: String): Boolean {
            val removed = super.remove(key, value)
            removalStarted.countDown()
            check(allowRemoval.await(10, TimeUnit.SECONDS))
            return removed
        }
    }

    private class CancellationObservedTransactions(
        private val cancellationLookup: CountDownLatch,
    ) : ConcurrentHashMap<String, Any>() {
        override fun get(key: String): Any? {
            return super.get(key).also { value ->
                if (value != null) cancellationLookup.countDown()
            }
        }
    }

    private class QueueHarness(
        val installer: Installer,
        val processed: List<String>,
    ) {
        fun cancel(transactionId: String) {
            Installer::class.java.getDeclaredMethod("cancelQueue", String::class.java)
                .apply { isAccessible = true }
                .invoke(installer, transactionId)
        }
    }

    private companion object {
        const val PACKAGE_NAME = "example.extension"
        val REPOSITORY = RepositoryIdentity("https://repo.example", "Official", "fingerprint")
        const val SESSION_ONE = 101
        const val SESSION_TWO = 202
    }
}
