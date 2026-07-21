package mihon.desktop.update

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.yield
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseAsset
import tachiyomi.domain.release.model.ReleaseChecksum
import tachiyomi.domain.release.model.ReleasePackageType
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long?)
sealed interface DesktopUpdateDownloadResult
data class VerifiedDownload(
    val file: Path,
    val asset: ReleaseAsset,
    val sha256: String,
    val sizeBytes: Long,
) : DesktopUpdateDownloadResult
data class ManualOnly(val releasePage: String) : DesktopUpdateDownloadResult
data class DownloadFailed(val reason: DownloadFailure) : DesktopUpdateDownloadResult
enum class DownloadFailure {
    UNSAFE_TEMP_ROOT,
    REDIRECT_LIMIT,
    CROSS_SCHEME_REDIRECT,
    INVALID_REDIRECT,
    HTTP,
    TOO_LARGE,
    CONNECTION,
    STORAGE,
    CHECKSUM_MISMATCH,
}

class DesktopUpdateDownloader(
    private val networkClient: OkHttpClient,
    private val tempRoot: Path,
    private val maxBytes: Long,
    private val maxRedirects: Int,
    private val openOutput: (Path) -> java.io.OutputStream = { Files.newOutputStream(it, StandardOpenOption.TRUNCATE_EXISTING) },
    private val moveFile: (Path, Path) -> Unit = { from, to -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); Unit },
) {
    private val client = networkClient.newBuilder().cache(null).followRedirects(false).followSslRedirects(false).build()

    init {
        require(maxBytes > 0)
        require(maxRedirects >= 0)
    }

    suspend fun download(release: Release, onProgress: (DownloadProgress) -> Unit = {}): DesktopUpdateDownloadResult {
        val suffix = when (release.asset.target.packageType) {
            ReleasePackageType.MSI -> ".msi"
            ReleasePackageType.DMG -> ".dmg"
            else -> return ManualOnly(release.releaseLink)
        }
        val expectedHash = trustedChecksum(release.asset.checksum)
            ?: return ManualOnly(release.releaseLink)
        val root = secureRoot() ?: return DownloadFailed(DownloadFailure.UNSAFE_TEMP_ROOT)
        var file: Path? = null
        var finalFile: Path? = null
        var verified = false
        return try {
            file = Files.createTempFile(root, "mihon-update-", ".part")
            if (!file.toRealPath().startsWith(root)) return DownloadFailed(DownloadFailure.UNSAFE_TEMP_ROOT)
            val result = transfer(release, file, expectedHash, onProgress)
            if (result !is VerifiedDownload) return result
            val final = file.resolveSibling(file.fileName.toString().removeSuffix(".part") + suffix)
            finalFile = final
            moveFile(file, final)
            if (!final.toRealPath().startsWith(root)) return DownloadFailed(DownloadFailure.UNSAFE_TEMP_ROOT)
            verified = true
            result.copy(file = final)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            DownloadFailed(DownloadFailure.UNSAFE_TEMP_ROOT)
        } catch (error: IOException) {
            DownloadFailed(DownloadFailure.STORAGE)
        } finally {
            if (!verified) listOfNotNull(file, finalFile).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private suspend fun transfer(
        release: Release,
        file: Path,
        expectedHash: String,
        onProgress: (DownloadProgress) -> Unit,
    ): DesktopUpdateDownloadResult {
        var url = release.downloadLink.toHttpUrlOrNull()
            ?: return DownloadFailed(DownloadFailure.CONNECTION)
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val response = try {
                client.newCall(Request.Builder().url(url).get().build()).await()
            } catch (error: IOException) {
                return DownloadFailed(DownloadFailure.CONNECTION)
            }
            if (response.code in 300..399) {
                val next = response.header("Location")?.let(url::resolve)
                response.close()
                if (next == null) return DownloadFailed(DownloadFailure.INVALID_REDIRECT)
                if (next.scheme != url.scheme) return DownloadFailed(DownloadFailure.CROSS_SCHEME_REDIRECT)
                if (redirects++ >= maxRedirects) return DownloadFailed(DownloadFailure.REDIRECT_LIMIT)
                url = next
                continue
            }
            if (!response.isSuccessful) {
                response.close()
                return DownloadFailed(DownloadFailure.HTTP)
            }
            return try {
                response.use { readBody(it.body.byteStream(), it.body.contentLength(), file, release, expectedHash, onProgress) }
            } catch (error: IOException) {
                DownloadFailed(DownloadFailure.CONNECTION)
            }
        }
    }

    private suspend fun readBody(
        input: java.io.InputStream,
        contentLength: Long,
        file: Path,
        release: Release,
        expectedHash: String,
        onProgress: (DownloadProgress) -> Unit,
    ): DesktopUpdateDownloadResult {
        if (contentLength > maxBytes) return DownloadFailed(DownloadFailure.TOO_LARGE)
        val totalBytes = contentLength.takeIf { it >= 0 }
        val digest = MessageDigest.getInstance("SHA-256")
        var downloaded = 0L
        return try {
            openOutput(file).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = try {
                        runInterruptible { input.read(buffer) }
                    } catch (error: IOException) {
                        return DownloadFailed(DownloadFailure.CONNECTION)
                    }
                    if (read < 0) break
                    if (exceedsLimit(downloaded, read, maxBytes)) return DownloadFailed(DownloadFailure.TOO_LARGE)
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    downloaded += read
                    onProgress(DownloadProgress(downloaded, totalBytes))
                    yield()
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualHash != expectedHash) DownloadFailed(DownloadFailure.CHECKSUM_MISMATCH)
            else VerifiedDownload(file, release.asset, actualHash, downloaded)
        } catch (error: IOException) {
            DownloadFailed(DownloadFailure.STORAGE)
        }
    }

    private fun secureRoot(): Path? = runCatching {
        val normalized = tempRoot.toAbsolutePath().normalize()
        Files.createDirectories(normalized)
        val real = normalized.toRealPath()
        real.takeIf { it == normalized && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(normalized) }
    }.getOrNull()

    private fun trustedChecksum(checksum: ReleaseChecksum?): String? = checksum
        ?.takeIf { it.algorithm == "sha256" && SHA_256.matches(it.value) }
        ?.value
        ?.lowercase()

    companion object {
        private val SHA_256 = Regex("[0-9a-fA-F]{64}")
    }
}

internal fun exceedsLimit(downloaded: Long, read: Int, maxBytes: Long) = read.toLong() > maxBytes - downloaded
