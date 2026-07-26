package mihon.desktop.test.http

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.ui.library.LibraryScreenModel
import mihon.desktop.ui.library.MangaDetailScreenModel
import mihon.domain.task.TaskState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class LibraryMangaTestModeControllerTest {
    @Test
    fun `detail actions use production model and report partial download failure`() = runBlocking {
        val membershipUpdates = mutableListOf<LibraryMembershipUpdate>()
        val mangaRepository = mockk<MangaRepository>()
        coEvery { mangaRepository.setMangaCategories(any(), any()) } returns Unit
        val first = chapter(11)
        val second = chapter(12)
        val manga = libraryManga(favorite = false).manga
        val detailFlow = MutableStateFlow(manga to listOf(first, second))
        val getMangaWithChapters = mockk<GetMangaWithChapters> {
            coEvery { subscribe(1, true) } returns detailFlow
        }
        val detail = MangaDetailScreenModel(
            mangaId = 1,
            getMangaWithChapters = getMangaWithChapters,
            updateLibraryMembership = UpdateLibraryMembership {
                membershipUpdates += it
                detailFlow.value = detailFlow.value.first.copy(favorite = it.favorite) to detailFlow.value.second
            },
            setMangaCategories = SetMangaCategories(mangaRepository),
            enqueueDownload = { if (it.chapterId == second.id) error("queue rejected") },
            deleteCover = { TaskState.Success(Unit) },
        )
        val getLibraryManga = mockk<GetLibraryManga> {
            every { subscribe() } returns flow {
                emit(listOf(libraryManga(favorite = false)))
                awaitCancellation()
            }
        }
        val getCategories = mockk<GetCategories> {
            coEvery { await() } returns emptyList()
        }
        val library = LibraryScreenModel(getLibraryManga = getLibraryManga, getCategories = getCategories)
        val controller = LibraryMangaTestModeController(library) { detail }

        assertTrue(controller.execute("select", mapOf("index" to "0")).success)
        assertEquals(listOf(11L, 12L), controller.detailSnapshot()!!.chapters)
        assertTrue(controller.execute("addToLibrary", emptyMap()).success)
        assertTrue(controller.detailSnapshot()!!.favorite)
        assertEquals(true, membershipUpdates.single().favorite)

        assertTrue(controller.execute("detail_categories", mapOf("categoryIds" to "3,4")).success)
        coVerify(exactly = 1) { mangaRepository.setMangaCategories(1, listOf(3, 4)) }

        assertTrue(controller.execute("detail_cover", mapOf("operation" to "delete")).success)
        assertEquals("Cover deleted", controller.detailSnapshot()!!.coverFeedback)

        val download = controller.execute("download", emptyMap())
        assertFalse(download.success)
        assertEquals(LibraryMangaActionFailureCode.PARTIAL_FAILURE, download.failureCode)
        assertEquals(listOf(11L), controller.detailSnapshot()!!.lastSucceededChapterIds)
        assertEquals(listOf(12L), controller.detailSnapshot()!!.lastFailedChapterIds)

        assertTrue(controller.execute("removeFromLibrary", emptyMap()).success)
        assertFalse(controller.detailSnapshot()!!.favorite)
        assertEquals(false, membershipUpdates.last().favorite)
        controller.closeAndJoin()
    }

    @Test
    fun `detail action without selection returns typed failure`() = runBlocking {
        val controller = LibraryMangaTestModeController(LibraryScreenModel()) {
            error("detail factory must not run")
        }

        val result = controller.execute("download", emptyMap())

        assertFalse(result.success)
        assertEquals(LibraryMangaActionFailureCode.DETAIL_NOT_OPEN, result.failureCode)
    }

    @Test
    fun `closed controller rejects mutation with typed failure`() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val model = libraryModel(
            flow {
                emit(listOf(libraryManga(favorite = true)))
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )
        val controller = LibraryMangaTestModeController(model) { error("unused") }
        LibraryMangaTestModeBridge.install(controller)
        assertTrue(controller.execute("search", mapOf("query" to "before-close")).success)
        controller.closeAndJoin()

        val result = controller.execute("search", mapOf("query" to "ignored"))

        assertTrue(cancelled.isCompleted)
        assertNull(LibraryMangaTestModeBridge.controller)
        assertFalse(result.success)
        assertEquals(LibraryMangaActionFailureCode.PORT_CLOSED, result.failureCode)
        assertEquals(OwnerLoadState.CLOSED, result.snapshot.loadState)
        assertEquals("before-close", model.state.value.searchQuery)
    }

    @Test
    fun `library observation disconnect rejects actions with typed failure`() = runBlocking {
        val controller = LibraryMangaTestModeController(
            libraryModel(flow { error("repository disconnected") }),
        ) { error("unused") }

        val result = controller.execute("search", mapOf("query" to "ignored"))

        assertFalse(result.success)
        assertEquals(LibraryMangaActionFailureCode.LIBRARY_UNAVAILABLE, result.failureCode)
        assertEquals(OwnerLoadState.FAILED, result.snapshot.loadState)
        assertEquals("repository disconnected", result.snapshot.loadError)
        controller.closeAndJoin()
    }

    @Test
    fun `detail load completion and failure are typed`() = runBlocking {
        val item = libraryManga(favorite = true)
        val library = libraryModel(
            flow {
                emit(listOf(item))
                awaitCancellation()
            },
        )
        val missing = MangaDetailScreenModel(
            mangaId = item.id,
            getMangaWithChapters = mockk {
                coEvery { subscribe(item.id, true) } returns emptyFlow()
            },
        )
        val missingController = LibraryMangaTestModeController(library) { missing }
        val missingResult = missingController.execute("select", mapOf("index" to "0"))
        assertEquals(LibraryMangaActionFailureCode.DETAIL_NOT_FOUND, missingResult.failureCode)
        missingController.closeAndJoin()

        val failedLibrary = libraryModel(
            flow {
                emit(listOf(item))
                awaitCancellation()
            },
        )
        val failed = MangaDetailScreenModel(
            mangaId = item.id,
            getMangaWithChapters = mockk {
                coEvery { subscribe(item.id, true) } returns flow { error("detail disconnected") }
            },
        )
        val failedController = LibraryMangaTestModeController(failedLibrary) { failed }
        val failedResult = failedController.execute("select", mapOf("index" to "0"))
        assertEquals(LibraryMangaActionFailureCode.DETAIL_LOAD_FAILED, failedResult.failureCode)
        assertEquals(OwnerLoadState.FAILED, failedController.detailSnapshot()!!.loadState)
        assertEquals("detail disconnected", failedController.detailSnapshot()!!.loadError)
        failedController.closeAndJoin()
    }

    @Test
    fun `detail load timeout remains observable as loading`() = runBlocking {
        val item = libraryManga(favorite = true)
        val library = libraryModel(
            flow {
                emit(listOf(item))
                awaitCancellation()
            },
        )
        val loading = MangaDetailScreenModel(
            mangaId = item.id,
            getMangaWithChapters = mockk {
                coEvery { subscribe(item.id, true) } returns flow { awaitCancellation() }
            },
        )
        val controller = LibraryMangaTestModeController(
            libraryModel = library,
            detailLoadTimeoutMillis = 10,
        ) { loading }

        val result = controller.execute("select", mapOf("index" to "0"))

        assertEquals(LibraryMangaActionFailureCode.DETAIL_LOADING, result.failureCode)
        assertEquals(OwnerLoadState.LOADING, controller.detailSnapshot()!!.loadState)
        controller.closeAndJoin()
    }

    @Test
    fun `detail actions reject stale owner after production flow fails`() = runBlocking {
        val item = libraryManga(favorite = true)
        val disconnect = CompletableDeferred<Unit>()
        val detail = MangaDetailScreenModel(
            mangaId = item.id,
            getMangaWithChapters = mockk {
                coEvery { subscribe(item.id, true) } returns flow {
                    emit(item.manga to listOf(chapter(11)))
                    disconnect.await()
                    error("detail disconnected after ready")
                }
            },
            enqueueDownload = { error("stale detail owner must not execute actions") },
        )
        val controller = LibraryMangaTestModeController(
            libraryModel(
                flow {
                    emit(listOf(item))
                    awaitCancellation()
                },
            ),
        ) { detail }

        assertTrue(controller.execute("select", mapOf("index" to "0")).success)
        disconnect.complete(Unit)
        withTimeout(1_000) {
            while (controller.detailSnapshot()?.loadState != OwnerLoadState.FAILED) yield()
        }

        val result = controller.execute("download", emptyMap())

        assertFalse(result.success)
        assertEquals(LibraryMangaActionFailureCode.DETAIL_LOAD_FAILED, result.failureCode)
        controller.closeAndJoin()
    }

    @Test
    fun `failed reselection clears previous detail owner`() = runBlocking {
        val item = libraryManga(favorite = true)
        val firstDetail = MangaDetailScreenModel(
            mangaId = item.id,
            getMangaWithChapters = mockk {
                coEvery { subscribe(item.id, true) } returns MutableStateFlow(item.manga to listOf(chapter(11)))
            },
        )
        var attempts = 0
        val controller = LibraryMangaTestModeController(
            libraryModel(
                flow {
                    emit(listOf(item))
                    awaitCancellation()
                },
            ),
        ) {
            if (attempts++ == 0) firstDetail else error("detail factory failed")
        }

        assertTrue(controller.execute("select", mapOf("index" to "0")).success)
        val reopen = controller.execute("select", mapOf("index" to "0"))
        val staleAction = controller.execute("download", emptyMap())

        assertEquals(LibraryMangaActionFailureCode.DETAIL_LOAD_FAILED, reopen.failureCode)
        assertEquals(LibraryMangaActionFailureCode.DETAIL_LOAD_FAILED, staleAction.failureCode)
        assertNull(controller.detailSnapshot())
        controller.closeAndJoin()
    }

    @Test
    fun `close during first detail emission returns typed failure`() = runBlocking {
        val item = libraryManga(favorite = true)
        val subscribed = CompletableDeferred<Unit>()
        val detail = MangaDetailScreenModel(
            mangaId = item.id,
            getMangaWithChapters = mockk {
                coEvery { subscribe(item.id, true) } returns flow {
                    subscribed.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        val controller = LibraryMangaTestModeController(
            libraryModel(
                flow {
                    emit(listOf(item))
                    awaitCancellation()
                },
            ),
        ) { detail }

        val pending = async { controller.execute("select", mapOf("index" to "0")) }
        subscribed.await()
        controller.closeAndJoin()
        val result = pending.await()

        assertFalse(result.success)
        assertEquals(LibraryMangaActionFailureCode.PORT_CLOSED, result.failureCode)
    }

    private fun libraryManga(favorite: Boolean) = LibraryManga(
        manga = Manga.create().copy(id = 1, title = "Detail", source = 7, favorite = favorite),
        categories = emptyList(),
        totalChapters = 2,
        readCount = 0,
        bookmarkCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    private fun chapter(id: Long) = Chapter.create().copy(
        id = id,
        mangaId = 1,
        url = "/chapter/$id",
        name = "Chapter $id",
    )

    private fun libraryModel(items: Flow<List<LibraryManga>>): LibraryScreenModel {
        val getLibraryManga = mockk<GetLibraryManga> { every { subscribe() } returns items }
        val getCategories = mockk<GetCategories> { coEvery { await() } returns emptyList() }
        return LibraryScreenModel(getLibraryManga = getLibraryManga, getCategories = getCategories)
    }
}
