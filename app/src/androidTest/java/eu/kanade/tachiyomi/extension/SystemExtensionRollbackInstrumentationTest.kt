package eu.kanade.tachiyomi.extension

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.util.AndroidCommitPlan
import eu.kanade.tachiyomi.extension.util.AndroidInstallLocation
import eu.kanade.tachiyomi.extension.util.AndroidInstallPort
import eu.kanade.tachiyomi.extension.util.AndroidInstalledPackage
import eu.kanade.tachiyomi.extension.util.DefaultAndroidInstallGateway
import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.InstalledExtensionTrustRecord
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallCoordinator
import mihon.domain.extension.service.ExtensionInstallFailure
import mihon.domain.extension.service.ExtensionInstallRequest
import mihon.domain.extension.service.ExtensionInstallState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class SystemExtensionRollbackInstrumentationTest {

    @Test
    fun reloadFailureUninstallsCandidateAndRestoresOlderSystemPackage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val v2 = fixture(instrumentation.context, context, 2)
        val v3 = fixture(instrumentation.context, context, 3)
        val downloadRequests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            downloadRequests.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(v3.readBytes().toResponseBody(APK_MEDIA_TYPE))
                .build()
        }.build()
        val installer = BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
        val gateway = DefaultAndroidInstallGateway(
            context = context,
            installSystem = { _, apk, _ -> installPackage(context, apk) },
            commitPlanProvider = { AndroidCommitPlan(AndroidInstallLocation.SYSTEM, installer) },
        )
        val v2Apk = checkNotNull(gateway.inspect(v2))
        val trust = InstalledExtensionTrustRecord(REPOSITORY, Hash.sha256(v2.readBytes()))
        var reloads = 0

        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            Manifest.permission.INSTALL_PACKAGES,
            Manifest.permission.DELETE_PACKAGES,
        )
        try {
            runCatching { gateway.removeSystem(PACKAGE_NAME) }
            gateway.installSystem(
                parentTransactionId = "fixture-v2",
                file = v2,
                metadata = AndroidInstalledPackage(
                    v2,
                    v2Apk.versionName,
                    v2Apk.versionCode,
                    v2Apk.signers,
                    trust,
                ),
                installer = installer,
            )
            val terminal = ExtensionInstallCoordinator(
                port = AndroidInstallPort(
                    gateway = gateway,
                    client = client,
                    runtimeReloader = {
                        reloads++
                        val installed = installedPackage(context)
                        if (reloads == 1) {
                            assertEquals(3, PackageInfoCompat.getLongVersionCode(installed))
                            throw ExtensionInstallFailure(
                                AppError.Storage(IllegalStateException("forced reload failure")),
                            )
                        }
                        assertEquals(2, PackageInfoCompat.getLongVersionCode(installed))
                        assertEquals(v2Apk.signers, signers(installed))
                    },
                ),
                scope = this,
            ).install(
                ExtensionInstallRequest(
                    ExtensionArtifact(
                        name = "Rollback fixture",
                        packageName = PACKAGE_NAME,
                        versionName = "1.4.3",
                        versionCode = 3,
                        language = "en",
                        isNsfw = false,
                        sources = emptyList(),
                        repository = REPOSITORY,
                        downloadUrl = "https://fixture.invalid/v3.apk",
                        iconUrl = "",
                        declaredSha256 = Hash.sha256(v3.readBytes()),
                    ),
                ),
            ).last()

            assertTrue(terminal is ExtensionInstallState.Failed)
            assertEquals(1, downloadRequests.get())
            assertEquals(2, reloads)
            val restored = installedPackage(context)
            assertEquals(2, PackageInfoCompat.getLongVersionCode(restored))
            File(context.cacheDir, "system-rollback-evidence.txt")
                .writeText("package=$PACKAGE_NAME version=2 signers=${signers(restored)}")
        } finally {
            runCatching { gateway.removeSystem(PACKAGE_NAME) }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun fixture(assetContext: Context, fileContext: Context, version: Int): File =
        File(fileContext.cacheDir, "extension-rollback-v$version.apk").apply {
            parentFile?.mkdirs()
            val encoded = Properties().apply {
                assetContext.assets.open("extension-rollback-apks.properties").use(::load)
            }.getProperty("v$version")
            writeBytes(Base64.decode(encoded, Base64.DEFAULT))
        }

    private suspend fun installPackage(context: Context, apk: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            setSize(apk.length())
        }
        val sessionId = packageInstaller.createSession(params)
        val action = "${context.packageName}.ROLLBACK_FIXTURE.${UUID.randomUUID()}"
        val result = CompletableDeferred<Pair<Int, String?>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                result.complete(
                    intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) to
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                )
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite("fixture.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val sender = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ).intentSender
                session.commit(sender)
            }
            val (status, message) = withTimeout(30_000) { result.await() }
            if (status != PackageInstaller.STATUS_SUCCESS) {
                throw ExtensionInstallFailure(AppError.Storage(IllegalStateException("status=$status $message")))
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun installedPackage(context: Context): PackageInfo =
        context.packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES)

    private fun signers(info: PackageInfo): Set<String> =
        checkNotNull(info.signingInfo).apkContentsSigners.map { it.toCharsString() }.toSet()

    private companion object {
        const val PACKAGE_NAME = "example.extension.rollback"
        val APK_MEDIA_TYPE = "application/vnd.android.package-archive".toMediaType()
        val REPOSITORY = RepositoryIdentity("https://example.invalid/repo", "Fixture", "fixture-key")
    }
}
