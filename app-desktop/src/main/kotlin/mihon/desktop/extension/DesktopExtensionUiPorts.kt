package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.suggestion.ExtensionInventory
import java.io.File

interface DesktopExtensionPresentationService {
    val installedExtensions: StateFlow<List<InstalledExtension>>
    val runtimeSnapshot: StateFlow<DesktopExtensionRuntimeSnapshot>
        get() = installedExtensions.projectRuntimeState { DesktopExtensionRuntimeSnapshot(installed = it) }
    val extensionsDirectory: File

    fun installExtensionStates(
        artifact: ExtensionArtifact,
        beforeCommit: (() -> Unit)? = null,
    ): Flow<ExtensionInstallState>

    fun removeExtensionWithMeta(extension: InstalledExtension): Boolean

    fun reloadAll()
}

data class DesktopExtensionRuntimeSnapshot(
    val installed: List<InstalledExtension> = emptyList(),
    val loadFailures: List<ExtensionLoadDiagnostic> = emptyList(),
    val inventory: ExtensionInventory = ExtensionInventory(),
)

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal fun <T, R> StateFlow<T>.projectRuntimeState(transform: (T) -> R): StateFlow<R> {
    val source = this
    return object : StateFlow<R> {
        override val value: R get() = transform(source.value)
        override val replayCache: List<R> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<R>): Nothing {
            source.map(transform).distinctUntilChanged().collect { collector.emit(it) }
            awaitCancellation()
        }
    }
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
