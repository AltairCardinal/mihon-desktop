package eu.kanade.tachiyomi.extension

import android.content.Context
import android.graphics.drawable.Drawable
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.extension.api.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.error.AppError
import mihon.domain.extension.service.ExtensionInstallFailure
import mihon.domain.extension.service.ExtensionUpdatePolicy
import mihon.domain.extension.service.SharedExtensionUpdatePolicy
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * The manager of extensions installed as another apk which extend the available sources. It handles
 * the retrieval of remotely available extensions as well as installing, updating and removing them.
 * To avoid malicious distribution, every extension must be signed and it will only be loaded if its
 * signature is trusted, otherwise the user will be prompted with a warning to trust it before being
 * loaded.
 */
class ExtensionManager internal constructor(
    private val context: Context,
    private val preferences: SourcePreferences = Injekt.get(),
    private val trustExtension: TrustExtension = Injekt.get(),
    private val updatePolicy: ExtensionUpdatePolicy = SharedExtensionUpdatePolicy,
    private val installedExtensionsLoader: suspend (Context) -> List<LoadResult> = ExtensionLoader::loadExtensions,
    private val extensionLoader: suspend (Context, String) -> LoadResult = ExtensionLoader::loadExtensionFromPkgName,
    private val availableExtensionsProvider: (suspend () -> List<Extension.Available>)? = null,
    private val installerFactory: (((suspend (String) -> Unit)) -> ExtensionInstaller)? = null,
    private val installReceiverRegistrar: (ExtensionInstallReceiver.Listener) -> Unit = { listener ->
        ExtensionInstallReceiver(listener).register(context)
    },
    val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * API where all the available extensions can be found.
     */
    private val api = ExtensionApi()

    /**
     * The installer which installs, updates and uninstalls the extensions.
     */
    private val installer by lazy {
        installerFactory?.invoke(::reloadInstalledExtension) ?: ExtensionInstaller(context, ::reloadInstalledExtension)
    }

    private val iconMap = mutableMapOf<String, Drawable>()

    private val installedExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Installed>())
    val installedExtensionsFlow = installedExtensionMapFlow.mapValues()

    private val availableExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Available>())
    val availableExtensionsFlow = availableExtensionMapFlow.mapValues()

    private val untrustedExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Untrusted>())
    val untrustedExtensionsFlow = untrustedExtensionMapFlow.mapValues()

    private val installationStateLock = Any()
    private val pendingInstallationEvents = ArrayDeque<InstallationEvent>()
    private var installationEventsLive = false

    init {
        installReceiverRegistrar(InstallationListener())
        initExtensions()
    }

    private var subLanguagesEnabledOnFirstRun = preferences.enabledLanguages().isSet()

    fun getExtensionPackage(sourceId: Long): String? {
        return installedExtensionsFlow.value.find { extension ->
            extension.sources.any { it.id == sourceId }
        }
            ?.pkgName
    }

    fun getExtensionPackageAsFlow(sourceId: Long): Flow<String?> {
        return installedExtensionsFlow.map { extensions ->
            extensions.find { extension ->
                extension.sources.any { it.id == sourceId }
            }
                ?.pkgName
        }
    }

    fun getAppIconForSource(sourceId: Long): Drawable? {
        val pkgName = getExtensionPackage(sourceId) ?: return null

        return iconMap[pkgName] ?: iconMap.getOrPut(pkgName) {
            ExtensionLoader.getExtensionPackageInfoFromPkgName(context, pkgName)!!.applicationInfo!!
                .loadIcon(context.packageManager)
        }
    }

    private var availableExtensionsSourcesData: Map<Long, StubSource> = emptyMap()

    private fun setupAvailableExtensionsSourcesDataMap(extensions: List<Extension.Available>) {
        if (extensions.isEmpty()) return
        availableExtensionsSourcesData = extensions
            .flatMap { ext -> ext.sources.map { it.toStubSource() } }
            .associateBy { it.id }
    }

    fun getSourceData(id: Long) = availableExtensionsSourcesData[id]

    /**
     * Loads and registers the installed extensions.
     */
    private fun initExtensions() {
        scope.launch {
            val extensions = installedExtensionsLoader(context)

            var installedExtensions = extensions
                .filterIsInstance<LoadResult.Success>()
                .associate { it.extension.pkgName to it.extension }
            var untrustedExtensions = extensions
                .filterIsInstance<LoadResult.Untrusted>()
                .associate { it.extension.pkgName to it.extension }

            val replayedEvents = synchronized(installationStateLock) {
                pendingInstallationEvents.forEach { event ->
                    installedExtensions = event.applyToInstalled(installedExtensions)
                    untrustedExtensions = event.applyToUntrusted(untrustedExtensions)
                }
                val replayedEvents = pendingInstallationEvents.isNotEmpty()
                pendingInstallationEvents.clear()
                installedExtensionMapFlow.value = installedExtensions
                untrustedExtensionMapFlow.value = untrustedExtensions
                installationEventsLive = true
                _isInitialized.value = true
                replayedEvents
            }
            if (replayedEvents) updatePendingUpdatesCount()
        }
    }

    /**
     * Finds the available extensions in the [api] and updates [availableExtensionMapFlow].
     */
    suspend fun findAvailableExtensions() {
        val extensions: List<Extension.Available> = try {
            availableExtensionsProvider?.invoke() ?: api.findExtensions()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            withUIContext { context.toast(MR.strings.extension_api_error) }
            return
        }

        enableAdditionalSubLanguages(extensions)

        availableExtensionMapFlow.value = extensions.associateBy { it.pkgName }
        updatedInstalledExtensionsStatuses(extensions)
        setupAvailableExtensionsSourcesDataMap(extensions)
    }

    /**
     * Enables the additional sub-languages in the app first run. This addresses
     * the issue where users still need to enable some specific languages even when
     * the device language is inside that major group. As an example, if a user
     * has a zh device language, the app will also enable zh-Hans and zh-Hant.
     *
     * If the user have already changed the enabledLanguages preference value once,
     * the new languages will not be added to respect the user enabled choices.
     */
    private fun enableAdditionalSubLanguages(extensions: List<Extension.Available>) {
        if (subLanguagesEnabledOnFirstRun || extensions.isEmpty()) {
            return
        }

        // Use the source lang as some aren't present on the extension level.
        val availableLanguages = extensions
            .flatMap(Extension.Available::sources)
            .distinctBy(Extension.Available.Source::lang)
            .map(Extension.Available.Source::lang)

        val deviceLanguage = Locale.getDefault().language
        val defaultLanguages = preferences.enabledLanguages().defaultValue()
        val languagesToEnable = availableLanguages.filter {
            it != deviceLanguage && it.startsWith(deviceLanguage)
        }

        preferences.enabledLanguages().set(defaultLanguages + languagesToEnable)
        subLanguagesEnabledOnFirstRun = true
    }

    /**
     * Sets the update field of the installed extensions with the given [availableExtensions].
     *
     * @param availableExtensions The list of extensions given by the [api].
     */
    private fun updatedInstalledExtensionsStatuses(availableExtensions: List<Extension.Available>) {
        if (availableExtensions.isEmpty()) {
            preferences.extensionUpdatesCount().set(0)
            return
        }

        installedExtensionMapFlow.update { installedExtensions ->
            installedExtensions.mapValues { (pkgName, extension) ->
                val availableExt = availableExtensions.find { it.pkgName == pkgName }

                when {
                    availableExt == null && !extension.isObsolete -> extension.copy(isObsolete = true)
                    availableExt != null -> extension.copy(
                        hasUpdate = extension.updateExists(availableExt),
                        repoUrl = availableExt.repoUrl,
                    )
                    else -> extension
                }
            }
        }
        updatePendingUpdatesCount()
    }

    /**
     * Returns a flow of the installation process for the given extension. It will complete
     * once the extension is installed or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The extension to be installed.
     */
    fun installExtension(extension: Extension.Available): Flow<InstallStep> {
        return installer.downloadAndInstall(api.getApkUrl(extension), extension)
    }

    /**
     * Returns a flow of the installation process for the given extension. It will complete
     * once the extension is updated or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The extension to be updated.
     */
    fun updateExtension(extension: Extension.Installed): Flow<InstallStep> {
        val availableExt = availableExtensionMapFlow.value[extension.pkgName] ?: return emptyFlow()
        return installExtension(availableExt)
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        installer.cancelInstall(extension.pkgName)
    }

    /**
     * Sets to "installing" status of an extension installation.
     *
     * @param transactionId The id of the install transaction.
     */
    fun setInstalling(transactionId: String) {
        installer.updateInstallStep(transactionId, InstallStep.Installing)
    }

    fun updateInstallStep(transactionId: String, step: InstallStep) {
        installer.updateInstallStep(transactionId, step)
    }

    /**
     * Uninstalls the extension that matches the given package name.
     *
     * @param extension The extension to uninstall.
     */
    fun uninstallExtension(extension: Extension) {
        installer.uninstallApk(extension.pkgName)
    }

    /**
     * Adds the given extension to the list of trusted extensions. It also loads in background the
     * now trusted extensions.
     *
     * @param extension the extension to trust
     */
    suspend fun trust(extension: Extension.Untrusted) {
        untrustedExtensionMapFlow.value[extension.pkgName] ?: return

        trustExtension.trust(extension.pkgName, extension.versionCode, extension.signatureHash)

        untrustedExtensionMapFlow.update { it - extension.pkgName }

        extensionLoader(context, extension.pkgName)
            .let { it as? LoadResult.Success }
            ?.let { registerNewExtension(it.extension) }
    }

    /**
     * Registers the given extension in this and the source managers.
     *
     * @param extension The extension to be registered.
     */
    private fun registerNewExtension(extension: Extension.Installed) {
        installedExtensionMapFlow.update { it + extension }
    }

    /**
     * Registers the given updated extension in this and the source managers previously removing
     * the outdated ones.
     *
     * @param extension The extension to be registered.
     */
    private fun registerUpdatedExtension(extension: Extension.Installed) {
        installedExtensionMapFlow.update { it + extension }
    }

    private suspend fun reloadInstalledExtension(pkgName: String) {
        when (val result = extensionLoader(context, pkgName)) {
            is LoadResult.Success -> {
                acceptInstallationEvent(InstallationEvent.Reloaded(result.extension.withUpdateCheck()))
            }
            is LoadResult.Untrusted -> {
                throw ExtensionInstallFailure(
                    AppError.Authentication(
                        IllegalStateException("Installed extension requires explicit trust confirmation: $pkgName"),
                    ),
                )
            }
            else -> throw ExtensionInstallFailure(
                AppError.MalformedData(IllegalStateException("Installed extension could not be reloaded: $pkgName")),
            )
        }
    }

    /**
     * Unregisters the extension in this and the source managers given its package name. Note this
     * method is called for every uninstalled application in the system.
     *
     * @param pkgName The package name of the uninstalled application.
     */
    private fun unregisterExtension(pkgName: String) {
        installedExtensionMapFlow.update { it - pkgName }
        untrustedExtensionMapFlow.update { it - pkgName }
    }

    /**
     * Listener which receives events of the extensions being installed, updated or removed.
     */
    private inner class InstallationListener : ExtensionInstallReceiver.Listener {

        override fun onExtensionInstalled(extension: Extension.Installed) {
            if (installer.isInstallTransactionActive(extension.pkgName)) return
            acceptInstallationEvent(InstallationEvent.Installed(extension.withUpdateCheck()))
        }

        override fun onExtensionUpdated(extension: Extension.Installed) {
            if (installer.isInstallTransactionActive(extension.pkgName)) return
            acceptInstallationEvent(InstallationEvent.Updated(extension.withUpdateCheck()))
        }

        override fun onExtensionUntrusted(extension: Extension.Untrusted) {
            if (installer.isInstallTransactionActive(extension.pkgName)) return
            acceptInstallationEvent(InstallationEvent.Untrusted(extension))
        }

        override fun onPackageUninstalled(pkgName: String) {
            if (installer.isInstallTransactionActive(pkgName)) return
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            acceptInstallationEvent(InstallationEvent.Uninstalled(pkgName))
        }
    }

    private fun acceptInstallationEvent(event: InstallationEvent) {
        val applied = synchronized(installationStateLock) {
            if (!installationEventsLive) {
                pendingInstallationEvents += event
                false
            } else {
                installedExtensionMapFlow.update(event::applyToInstalled)
                untrustedExtensionMapFlow.update(event::applyToUntrusted)
                true
            }
        }
        if (applied) {
            updatePendingUpdatesCount()
        }
    }

    private sealed interface InstallationEvent {
        fun applyToInstalled(
            extensions: Map<String, Extension.Installed>,
        ): Map<String, Extension.Installed>

        fun applyToUntrusted(
            extensions: Map<String, Extension.Untrusted>,
        ): Map<String, Extension.Untrusted>

        data class Installed(val extension: Extension.Installed) : InstallationEvent {
            override fun applyToInstalled(extensions: Map<String, Extension.Installed>) =
                extensions + (extension.pkgName to extension)
            override fun applyToUntrusted(extensions: Map<String, Extension.Untrusted>) = extensions
        }

        data class Updated(val extension: Extension.Installed) : InstallationEvent {
            override fun applyToInstalled(extensions: Map<String, Extension.Installed>) =
                extensions + (extension.pkgName to extension)
            override fun applyToUntrusted(extensions: Map<String, Extension.Untrusted>) = extensions
        }

        data class Reloaded(val extension: Extension.Installed) : InstallationEvent {
            override fun applyToInstalled(extensions: Map<String, Extension.Installed>) =
                extensions + (extension.pkgName to extension)

            override fun applyToUntrusted(extensions: Map<String, Extension.Untrusted>) =
                extensions - extension.pkgName
        }

        data class Untrusted(val extension: Extension.Untrusted) : InstallationEvent {
            override fun applyToInstalled(extensions: Map<String, Extension.Installed>) =
                extensions - extension.pkgName

            override fun applyToUntrusted(extensions: Map<String, Extension.Untrusted>) =
                extensions + (extension.pkgName to extension)
        }

        data class Uninstalled(val pkgName: String) : InstallationEvent {
            override fun applyToInstalled(extensions: Map<String, Extension.Installed>) = extensions - pkgName
            override fun applyToUntrusted(extensions: Map<String, Extension.Untrusted>) = extensions - pkgName
        }
    }

    /**
     * Extension method to set the update field of an installed extension.
     */
    private fun Extension.Installed.withUpdateCheck(): Extension.Installed {
        return if (updateExists()) {
            copy(hasUpdate = true)
        } else {
            this
        }
    }

    private fun Extension.Installed.updateExists(availableExtension: Extension.Available? = null): Boolean {
        val availableExt = availableExtension
            ?: availableExtensionMapFlow.value[pkgName]
            ?: return false

        return updatePolicy.isUpdateAvailable(
            availableVersionCode = availableExt.versionCode,
            availableLibVersion = availableExt.libVersion,
            installedVersionCode = versionCode,
            installedLibVersion = libVersion,
        )
    }

    private fun updatePendingUpdatesCount() {
        val pendingUpdateCount = installedExtensionMapFlow.value.values.count { it.hasUpdate }
        preferences.extensionUpdatesCount().set(pendingUpdateCount)
        if (pendingUpdateCount == 0) {
            ExtensionUpdateNotifier(context).dismiss()
        }
    }

    private operator fun <T : Extension> Map<String, T>.plus(extension: T) = plus(extension.pkgName to extension)
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private fun <T : Extension> StateFlow<Map<String, T>>.mapValues(): StateFlow<List<T>> {
    val source = this
    return object : StateFlow<List<T>> {
        override val replayCache: List<List<T>>
            get() = listOf(value)

        override val value: List<T>
            get() = source.value.values.toList()

        override suspend fun collect(collector: FlowCollector<List<T>>): Nothing {
            source.collect { collector.emit(it.values.toList()) }
            awaitCancellation()
        }
    }
}
