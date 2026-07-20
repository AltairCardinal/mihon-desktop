package mihon.desktop.extension

import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.jar.JarFile
import mihon.domain.error.AppError
import mihon.domain.extension.service.ExtensionInstallFailure

fun interface DesktopArtifactAuthenticator {
    fun authenticate(file: File, repositoryFingerprint: String, isApk: Boolean)
}

internal object DefaultDesktopArtifactAuthenticator : DesktopArtifactAuthenticator {
    override fun authenticate(file: File, repositoryFingerprint: String, isApk: Boolean) {
        val expected = repositoryFingerprint.normalizedFingerprint()
        if (expected.length != SHA256_HEX_LENGTH || expected.any { it !in HEX_DIGITS }) {
            failAuthentication("Extension repository has no valid signing-key fingerprint")
        }
        val signerFingerprints = if (isApk) apkSignerFingerprints(file) else jarSignerFingerprints(file, expected)
        if (expected !in signerFingerprints) {
            failAuthentication("Extension artifact signer does not match repository identity")
        }
    }

    private fun apkSignerFingerprints(file: File): Set<String> {
        val result = try {
            ApkVerifier.Builder(file).build().verify()
        } catch (error: Exception) {
            failAuthentication("Extension APK signature could not be verified", error)
        }
        if (!result.isVerified || result.signerCertificates.isEmpty()) {
            failAuthentication("Extension APK is unsigned or has an invalid signature")
        }
        return result.signerCertificates.mapTo(mutableSetOf()) { certificate ->
            certificate.sha256Fingerprint()
        }
    }

    private fun jarSignerFingerprints(file: File, expected: String): Set<String> {
        val fingerprints = mutableSetOf<String>()
        var payloadEntries = 0
        try {
            JarFile(file, true).use { jar ->
                jar.entries().asSequence()
                    .filterNot { it.isDirectory || it.name.isJarSignatureMetadata() }
                    .forEach { entry ->
                        payloadEntries++
                        jar.getInputStream(entry).use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
                        val entryFingerprints = entry.codeSigners.orEmpty()
                            .mapNotNull { it.signerCertPath.certificates.firstOrNull() as? X509Certificate }
                            .mapTo(mutableSetOf()) { certificate -> certificate.sha256Fingerprint() }
                        if (expected !in entryFingerprints) {
                            failAuthentication("Extension JAR contains unsigned or differently signed payload")
                        }
                        fingerprints += entryFingerprints
                    }
            }
        } catch (error: ExtensionInstallFailure) {
            throw error
        } catch (error: Exception) {
            failAuthentication("Extension JAR signature could not be verified", error)
        }
        if (payloadEntries == 0 || fingerprints.isEmpty()) {
            failAuthentication("Extension JAR has no signed payload")
        }
        return fingerprints
    }

    private fun X509Certificate.sha256Fingerprint(): String =
        MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }

    private fun String.normalizedFingerprint() = replace(":", "").trim().lowercase()

    private fun String.isJarSignatureMetadata(): Boolean {
        val normalized = uppercase()
        if (normalized == "META-INF/MANIFEST.MF") return true
        if (!normalized.startsWith("META-INF/")) return false
        val fileName = normalized.substringAfterLast('/')
        return fileName.startsWith("SIG-") ||
            fileName.endsWith(".SF") ||
            fileName.endsWith(".RSA") ||
            fileName.endsWith(".DSA") ||
            fileName.endsWith(".EC")
    }

    private fun failAuthentication(message: String, cause: Throwable? = null): Nothing =
        throw ExtensionInstallFailure(AppError.Authentication(IllegalArgumentException(message, cause)))

    private const val SHA256_HEX_LENGTH = 64
    private val HEX_DIGITS = '0'..'9' union 'a'..'f'
}
