package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.service.ExtensionInstallState
import java.io.File

interface DesktopExtensionPresentationService {
    val installedExtensions: StateFlow<List<InstalledExtension>>
    val extensionsDirectory: File

    fun installExtensionStates(artifact: ExtensionArtifact): Flow<ExtensionInstallState>

    fun removeExtensionWithMeta(extension: InstalledExtension): Boolean

    fun reloadAll()
}

fun interface DesktopSourceExtensionLookup {
    fun getExtensionPackage(sourceId: Long): String?
}

fun interface DesktopSourceArtifactStatusLookup {
    /** True when the source belongs to an APK artifact created by an obsolete converter. */
    fun requiresApkReconversion(sourceId: Long): Boolean
}

internal class DesktopExtensionSourceLookup(
    private val extensionManager: DesktopExtensionManager,
) : DesktopSourceExtensionLookup, DesktopSourceArtifactStatusLookup {
    override fun getExtensionPackage(sourceId: Long): String? = extensionManager.getExtensionPackage(sourceId)

    override fun requiresApkReconversion(sourceId: Long): Boolean =
        extensionManager.requiresApkReconversion(sourceId)
}

object DesktopSourcePreferenceContextFactory {
    fun create(source: Source): Any? = source::class.java.classLoader
        ?.loadClass("android.content.Context")
        ?.getDeclaredConstructor()
        ?.newInstance()
}
