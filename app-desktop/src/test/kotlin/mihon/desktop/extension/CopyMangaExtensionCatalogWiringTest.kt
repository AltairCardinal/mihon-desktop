package mihon.desktop.extension

import java.io.File
import kotlinx.coroutines.runBlocking
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.di.isolatedDesktopPreferenceStore
import mihon.desktop.test.http.SourceExtensionTestModeController
import mihon.desktop.ui.extension.ExtensionsScreenModel
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Isolated
class CopyMangaExtensionCatalogWiringTest {

    @Test
    fun `production catalog displays CopyManga repository entry with upstream default settings`(
        @TempDir tempDir: File,
    ) = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse(body = COPY_MANGA_REPOSITORY_JSON))
            server.enqueue(MockResponse(body = COPY_MANGA_INDEX_JSON))
            val store = isolatedDesktopPreferenceStore().also {
                it.getStringSet("source_languages", emptySet()).set(setOf("zh"))
            }
            val context = initDesktopDIForTest(tempDir, store)
            try {
                Injekt.get<ExtensionRepoRepository>().insertRepo(
                    baseUrl = server.url("/").toString().removeSuffix("/"),
                    name = "CopyManga",
                    shortName = null,
                    website = "https://github.com/stevenyomi/copymanga",
                    signingKeyFingerprint = COPY_MANGA_FINGERPRINT,
                )

                Injekt.get<ExtensionsScreenModel>().refresh().join()

                val snapshot = Injekt.get<SourceExtensionTestModeController>().snapshot()
                assertTrue(snapshot.repositoryErrors.isEmpty())
                val extension = snapshot.available.single {
                    it.packageName == "eu.kanade.tachiyomi.extension.zh.copymanga"
                }
                assertEquals("拷贝漫画", extension.name)
                assertEquals("https://www.mangacopy.com", extension.sources.single().baseUrl)
            } finally {
                context.closeAndJoin()
            }
        }
    }

    @Test
    fun `production extension filter persists the upstream NSFW visibility preference`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val store = isolatedDesktopPreferenceStore()
        val preference = store.getBoolean("show_nsfw_source", true)
        val context = initDesktopDIForTest(tempDir, store)
        try {
            val model = Injekt.get<ExtensionsScreenModel>()
            assertTrue(model.state.value.options.showNsfw)

            model.setOptions(ExtensionPresentationOptions(showNsfw = false, enabledLanguages = setOf("zh")))

            assertFalse(preference.get())
        } finally {
            context.closeAndJoin()
        }
    }

    private companion object {
        const val COPY_MANGA_FINGERPRINT =
            "0cf45b9c21bb577fdd006b2de8853f070a543ed5e4710e99cfad8b272fa5af5a"
        const val COPY_MANGA_REPOSITORY_JSON =
            """{"meta":{"name":"CopyManga","website":"https://github.com/stevenyomi/copymanga","signingKeyFingerprint":"$COPY_MANGA_FINGERPRINT"}}"""
        const val COPY_MANGA_INDEX_JSON =
            """[{"name":"Tachiyomi: CopyManga","pkg":"eu.kanade.tachiyomi.extension.zh.copymanga","apk":"tachiyomi-zh.copymanga-v1.4.53.apk","lang":"zh","code":53,"version":"1.4.53","nsfw":1,"sources":[{"id":"6696312508930833206","lang":"zh","name":"拷贝漫画","baseUrl":"https://www.mangacopy.com"}]}]"""
    }
}
