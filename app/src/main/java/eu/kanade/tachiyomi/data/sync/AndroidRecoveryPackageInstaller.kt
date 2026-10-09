package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.storage.getUriCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal sealed interface AndroidRecoveryPackageVerification {
    data class Ready(val file: File, val version: String) : AndroidRecoveryPackageVerification
    data object Rejected : AndroidRecoveryPackageVerification
}

internal class AndroidRecoveryPackageInstaller(
    private val context: Context,
    private val packageName: String = BuildConfig.FORK_RELEASE_PACKAGE,
    private val certificateSha256: String = BuildConfig.FORK_RELEASE_CERTIFICATE_SHA256,
    private val currentVersionCode: Long = BuildConfig.FORK_RELEASE_VERSION_CODE.toLong(),
    private val packageUri: (File) -> Uri = { it.getUriCompat(context) },
) {
    fun verify(file: File): AndroidRecoveryPackageVerification = try {
        require(file.isFile && file.length() in 1..MAX_PACKAGE_BYTES)
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val info = requireNotNull(
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                if (modern) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
            ),
        )
        val version = if (modern) info.longVersionCode else info.versionCode.toLong()
        require(info.packageName == packageName && version > currentVersionCode)
        val signers = if (modern) {
            requireNotNull(
                info.signingInfo,
            ).apkContentsSigners
        } else {
            requireNotNull(info.signatures)
        }
        require(signers.size == 1)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray())
            .joinToString("") { "%02x".format(it) }
        require(fingerprint == certificateSha256)
        AndroidRecoveryPackageVerification.Ready(file, info.versionName ?: version.toString())
    } catch (_: Exception) {
        AndroidRecoveryPackageVerification.Rejected
    }

    /** Copy into private cache before verification, so a document provider cannot replace the selected bytes. */
    suspend fun select(uri: Uri): AndroidRecoveryPackageVerification {
        var file: File? = null
        return try {
            val directory = context.cacheDir.resolve("sync-install").apply { mkdirs() }
            val selected = directory.resolve("candidate-${UUID.randomUUID()}.apk").also { file = it }
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                selected.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= MAX_PACKAGE_BYTES)
                        output.write(buffer, 0, count)
                    }
                }
            }
            verify(selected).also { if (it == AndroidRecoveryPackageVerification.Rejected) selected.delete() }
        } catch (cancelled: CancellationException) {
            file?.let { runCatching { it.delete() } }
            throw cancelled
        } catch (_: Exception) {
            file?.let { runCatching { it.delete() } }
            AndroidRecoveryPackageVerification.Rejected
        }
    }

    suspend fun install(ready: AndroidRecoveryPackageVerification.Ready): Boolean = try {
        require(withContext(Dispatchers.IO) { verify(ready.file) } is AndroidRecoveryPackageVerification.Ready)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(packageUri(ready.file), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        withContext(Dispatchers.Main) { context.startActivity(intent) }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val MAX_PACKAGE_BYTES = 512L * 1024 * 1024
    }
}
