package mihon.desktop.ui.authors

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.CreatorDiscoveryScheduler
import mihon.desktop.domain.DesktopCustomCoverStore
import mihon.desktop.image.DesktopSourceImage
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.LanguageProjectionContract
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorDiscoveryResult
import java.nio.file.Path

@OptIn(ExperimentalComposeUiApi::class)
class AuthorParityUiContractTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `mounted production author cover renders Android loading and error placeholders`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val server = mockwebserver3.MockWebServer().apply {
            dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                    entered.complete(Unit)
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    return mockwebserver3.MockResponse.Builder().code(500).build()
                }
            }
            start()
        }
        val original = coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
        val client = okhttp3.OkHttpClient()
        val loader = mihon.desktop.image.createDesktopImageLoader(coil3.PlatformContext.INSTANCE, client, {
            client
        }, { null })
        coil3.SingletonImageLoader.setUnsafe(loader)
        val dependencies = mockk<DesktopUiDependencies> {
            every { customCoverStore } returns DesktopCustomCoverStore(directory.resolve("covers").toFile())
        }
        val version = SourceWorkArchiveVersion(
            sourceWorkId = 3L,
            naturalKey = SourceWorkNaturalKey(
                42L,
                "/placeholder",
            ),
            mangaId = null, title = "Loading",
            thumbnailUrl = server.url("/cover.png").toString(),
            readingLanguage = LanguageProjectionContract(
                LanguageDimension.READING,
                "en",
                LanguageCertainty.CONFIRMED,
                LanguageEvidenceKind.STRUCTURED_METADATA,
            ),
            chapterCount = 0L, inLibrary = false, detailsFetchedAt = 1L, lastSeenAt = 1L, decision = null,
        )
        val scene = ImageComposeScene(320, 200, coroutineContext = coroutineContext) {}
        fun frame() =
            javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(checkNotNull(scene.render().encodeToData()).bytes))
        try {
            scene.setContent {
                MaterialTheme {
                    androidx.compose.material3.Surface(color = androidx.compose.ui.graphics.Color.White) {
                        CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                            CreatorArchiveWorkCard(
                                version.title, version, null, false, false, "placeholder",
                                tachiyomi.domain.library.model.LibraryDisplayMode.List,
                                androidx.compose.ui.focus.FocusRequester(), emptyList(), {},
                            )
                        }
                    }
                }
            }
            scene.render()
            withTimeout(5_000) { entered.await() }
            val loading = frame()
            val gray = loading.getRGB(5, 20)
            org.junit.jupiter.api.Assertions.assertTrue(
                kotlin.math.abs(((gray ushr 16) and 0xFF) - 241) <= 1,
                "Loading uses Android's #1F888888 over white",
            )
            assertEquals((gray ushr 16) and 0xFF, (gray ushr 8) and 0xFF)
            release.countDown()
            withTimeout(5_000) {
                while (true) {
                    val error = frame()
                    val symbolPixels = (20..44).any { x ->
                        (35..78).any { y ->
                            ((error.getRGB(x, y) ushr 16) and 0xFF) <
                                235
                        }
                    }
                    if (symbolPixels) {
                        assertEquals(gray, error.getRGB(5, 20), "Error retains the same gray background")
                        break
                    }
                    kotlinx.coroutines.delay(20)
                }
            }
        } finally {
            release.countDown()
            scene.close()
            server.close()
            coil3.SingletonImageLoader.setUnsafe(original)
            loader.shutdown()
        }
    }

    @Test
    fun `checking new works keeps existing work opening available`() = runBlocking {
        val creator = Creator(7L, "Author", "author", null, emptyList(), 1L, 1L)
        val repository = mockk<CreatorRepository> {
            every { getCreatorsAsFlow() } returns flowOf(listOf(creator))
            every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
            coEvery { getCreator(7L) } returns creator
            coEvery { getDiscoveryCandidatesForCreator(7L) } returns emptyList()
            coEvery { getMangaCreatorsForCreator(7L) } returns emptyList()
        }
        val identity = mockk<CreatorArchiveRepository>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduler = CreatorDiscoveryScheduler(
            DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("tasks.json"))),
            discoverDue = { CreatorDiscoveryResult(0, 0, emptyList()) },
            discoverCreator = {
                entered.complete(Unit)
                release.await()
                CreatorDiscoveryResult(0, 0, emptyList())
            },
        )
        val model = AuthorDetailScreenModel(
            7L,
            false,
            GetCreatorDetails(repository),
            GetCreators(repository),
            SetCreatorFollow(repository),
            scheduler,
            null,
            AuthorIdentityActions(ManageCreatorIdentity(identity)),
        )
        val version = SourceWorkArchiveVersion(
            sourceWorkId = 3L, naturalKey = SourceWorkNaturalKey(42L, "/existing"), mangaId = 321L, title = "Existing",
            readingLanguage = LanguageProjectionContract(
                LanguageDimension.READING,
                "en",
                LanguageCertainty.CONFIRMED,
                LanguageEvidenceKind.STRUCTURED_METADATA,
            ),
            chapterCount = 0L, inLibrary = true, detailsFetchedAt = 1L, lastSeenAt = 1L, decision = null,
        )
        val opened = async(start = CoroutineStart.UNDISPATCHED) {
            model.effects.first { it is AuthorDetailEffect.OpenManga }
        }
        try {
            model.refreshDiscovery()
            withTimeout(5_000) { entered.await() }
            model.openVersion(version).join()
            assertEquals(321L, (withTimeout(2_000) { opened.await() } as AuthorDetailEffect.OpenManga).mangaId)
            assertEquals(true, model.state.value.actionRunning, "Opening does not finish the active scan")
        } finally {
            opened.cancel()
            release.complete(Unit)
            model.closeAndJoin()
            scheduler.stopAndJoin()
        }
    }

    @Test
    fun `production author date preserves full date when the label does not fit`() = runBlocking {
        val scene = ImageComposeScene(320, 200, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                MaterialTheme {
                    Column(Modifier.width(90.dp)) {
                        CreatorWorkDate(
                            CreatorWorkDateLabel("First seen in the archive: 2026-10-12", "2026-10-12"),
                            "date",
                            MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            scene.render()
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            val text = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                .single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-work-first-seen-date" }
                .config[SemanticsProperties.Text].single().text
            assertEquals("2026-10-12", text)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `production author cover uses manga identity custom cover source and version keys`() = runBlocking {
        val covers = DesktopCustomCoverStore(directory.resolve("covers").toFile())
        covers.write(7L, byteArrayOf(1, 2, 3))
        val dependencies = mockk<DesktopUiDependencies> {
            every { customCoverStore } returns covers
        }
        val version = SourceWorkArchiveVersion(
            sourceWorkId = 3L,
            naturalKey = SourceWorkNaturalKey(42L, "/work"), mangaId = 7L, title = "Cover",
            thumbnailUrl = "https://example.invalid/cover.jpg",
            readingLanguage = LanguageProjectionContract(
                LanguageDimension.READING,
                "en",
                LanguageCertainty.CONFIRMED,
                LanguageEvidenceKind.STRUCTURED_METADATA,
            ),
            chapterCount = 0, inLibrary = true, detailsFetchedAt = 123L, lastSeenAt = 1, decision = null,
        )
        val scene = ImageComposeScene(320, 200, coroutineContext = coroutineContext) {}
        var request: coil3.request.ImageRequest? = null
        fun render(item: SourceWorkArchiveVersion) {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    request = rememberCreatorWorkCoverRequest(item)
                }
            }
            scene.render()
        }
        try {
            render(version)
            assertEquals(covers.getCustomCoverFile(7L).absolutePath, request!!.data)
            val firstKey = request!!.memoryCacheKey
            assertEquals("manga-cover:7:123:${covers.getCustomCoverFile(7L).absolutePath}", firstKey)
            assertEquals(firstKey, request!!.diskCacheKey)
            render(version.copy(detailsFetchedAt = 124L))
            assertNotEquals(firstKey, request!!.memoryCacheKey)
            render(version.copy(mangaId = null))
            assertEquals(DesktopSourceImage(version.thumbnailUrl!!, 42L), request!!.data)
            assertEquals("manga-cover:-3:123:${version.thumbnailUrl}", request!!.memoryCacheKey)
        } finally {
            scene.close()
        }
    }
}
