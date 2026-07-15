package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.isExtensionUpdateAvailable
import mihon.domain.extension.model.toIdentity
import mihon.domain.extension.service.ExtensionCatalogService
import mihon.domain.extension.service.RepositoryFetchResult
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.service.ExtensionRepoIndexEntryDto
import mihon.domain.extensionrepo.service.toCatalogEntry
import okhttp3.OkHttpClient
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import kotlin.time.Duration.Companion.days

internal class ExtensionApi(
    private val client: OkHttpClient? = null,
    private val json: Json? = null,
    private val repositories: (suspend () -> List<ExtensionRepo>)? = null,
    private val catalogService: ExtensionCatalogService = ExtensionCatalogService(),
) {

    private val networkService: NetworkHelper by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()
    private val getExtensionRepo: GetExtensionRepo by injectLazy()
    private val updateExtensionRepo: UpdateExtensionRepo by injectLazy()
    private val extensionManager: ExtensionManager by injectLazy()
    private val injectedJson: Json by injectLazy()

    private val lastExtCheck: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_ext_check"), 0)
    }

    suspend fun findExtensions(): List<Extension.Available> {
        return refreshCatalog().entries
            .filter { it.compatibility == ExtensionCompatibility.Compatible }
            .map { entry ->
                val artifact = entry.artifact
                Extension.Available(
                    name = artifact.name,
                    pkgName = artifact.packageName,
                    versionName = artifact.versionName,
                    versionCode = artifact.versionCode,
                    libVersion = artifact.libVersion,
                    lang = artifact.language,
                    isNsfw = artifact.isNsfw,
                    sources = artifact.sources.map {
                        Extension.Available.Source(
                            id = it.id,
                            lang = it.language,
                            name = it.name,
                            baseUrl = it.baseUrl,
                        )
                    },
                    apkName = artifact.downloadUrl.substringAfterLast('/'),
                    iconUrl = artifact.iconUrl,
                    repoUrl = artifact.repository.baseUrl,
                )
            }
    }

    suspend fun refreshCatalog(): ExtensionCatalogResult = withIOContext {
        val extensionRepositories = repositories?.invoke() ?: getExtensionRepo.getAll()
        catalogService.refresh(extensionRepositories, ::fetchRepository)
    }

    private suspend fun fetchRepository(repository: ExtensionRepo): RepositoryFetchResult {
        val response = (client ?: networkService.client)
            .newCall(GET("${repository.baseUrl}/index.min.json"))
            .awaitSuccess()
        val entries = with(json ?: injectedJson) {
            response.parseAs<List<ExtensionRepoIndexEntryDto>>()
                .map { it.toCatalogEntry(repository) }
        }
        return RepositoryFetchResult.Success(repository.toIdentity(), entries)
    }

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<Extension.Installed>? {
        // Limit checks to once a day at most
        if (!fromAvailableExtensionList &&
            Instant.now().toEpochMilli() < lastExtCheck.get() + 1.days.inWholeMilliseconds
        ) {
            return null
        }

        // Update extension repo details
        updateExtensionRepo.awaitAll()

        val extensions = if (fromAvailableExtensionList) {
            extensionManager.availableExtensionsFlow.value
        } else {
            findExtensions().also { lastExtCheck.set(Instant.now().toEpochMilli()) }
        }

        val installedExtensions = ExtensionLoader.loadExtensions(context)
            .filterIsInstance<LoadResult.Success>()
            .map { it.extension }

        val extensionsWithUpdate = mutableListOf<Extension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
            val hasUpdate = isExtensionUpdateAvailable(
                availableVersionCode = availableExt.versionCode,
                availableLibVersion = availableExt.libVersion,
                installedVersionCode = installedExt.versionCode,
                installedLibVersion = installedExt.libVersion,
            )
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
        }

        return extensionsWithUpdate
    }

    fun getApkUrl(extension: Extension.Available): String {
        return "${extension.repoUrl}/apk/${extension.apkName}"
    }
}
