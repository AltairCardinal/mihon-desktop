package eu.kanade.presentation.more.settings.screen.browse

import eu.kanade.tachiyomi.extension.ExtensionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.extensionrepo.interactor.DeleteExtensionRepo
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.service.ExtensionRepoAction
import mihon.domain.extensionrepo.service.ExtensionRepoActionResult
import mihon.domain.extensionrepo.service.ExtensionRepoActionResult.FingerprintConflict
import mihon.domain.extensionrepo.service.ExtensionRepoActionResult.Validation
import mihon.domain.extensionrepo.service.ExtensionRepoService
import mihon.domain.extensionrepo.service.ExtensionRepoValidation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo.Result.DuplicateFingerprint as CreateConflict
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo.Result.InvalidRepository as CreateInvalidRepository
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo.Result.RepositoryUnavailable as CreateUnavailable

class ExtensionReposScreenModelWiringTest {
    private val old = repo("https://old.example", "FINGERPRINT")
    private val replacement = repo("https://new.example", "fingerprint")

    @Test
    fun `production screen model delegates mutations and maps every shared result`() = runTest {
        val fixture = Fixture()
        try {
            val create = fixture.succeeds(ExtensionRepoAction.CREATE) { createRepo(replacement.baseUrl) }
            assertEquals(listOf(MR.strings.ext_pending, MR.strings.completed), create.map { it.stringRes })
            fixture.model.showDialog(RepoDialog.Conflict(old, replacement))
            fixture.succeeds(ExtensionRepoAction.REPLACE) { replaceRepo(replacement) }
            fixture.succeeds(ExtensionRepoAction.DELETE) { deleteRepo(old.baseUrl) }
            listOf(
                CreateExtensionRepo.Result.InvalidUrl to MR.strings.invalid_repo_name,
                CreateExtensionRepo.Result.RepoAlreadyExists to MR.strings.error_repo_exists,
                CreateUnavailable to MR.strings.desktop_extension_repo_unavailable,
                CreateInvalidRepository to MR.strings.desktop_extension_repo_invalid_metadata,
                CreateExtensionRepo.Result.Error to MR.strings.unknown_error,
            )
                .forEachIndexed { index, (source, resource) ->
                    val url = "case-$index"
                    coEvery { fixture.create.await(url) } returns source
                    assertEquals(resource, fixture.terminal { createRepo(url) }.stringRes)
                }
            coEvery { fixture.create.await("conflict") } returns CreateConflict(old, replacement)
            val conflict = fixture.terminal { createRepo("conflict") }
            assertEquals(old, (conflict.result as FingerprintConflict).oldRepo)
            assertEquals(MR.strings.action_replace_repo_title, conflict.stringRes)
            val dialog = (fixture.model.state.value as RepoScreenState.Success).dialog
            assertEquals(RepoDialog.Conflict(old, replacement), dialog)
            fixture.model.showDialog(RepoDialog.Conflict(old, replacement.copy(signingKeyFingerprint = "OTHER")))
            val changed = replacement.copy(signingKeyFingerprint = "OTHER")
            val validation = fixture.terminal { replaceRepo(changed) }
            assertEquals(ExtensionRepoValidation.FINGERPRINT_CHANGED, (validation.result as Validation).reason)
            assertEquals(MR.strings.action_replace_repo_title, validation.stringRes)
            coVerify(exactly = 0) { fixture.replace.await(match { it.signingKeyFingerprint == "OTHER" }) }
            coEvery { fixture.replace.await(any()) } throws IllegalStateException("replace")
            fixture.model.showDialog(RepoDialog.Conflict(old, replacement))
            assertEquals(MR.strings.unknown_error, fixture.terminal { replaceRepo(replacement) }.stringRes)
            coEvery { fixture.delete.await(any()) } throws IllegalStateException("delete")
            assertEquals(MR.strings.unknown_error, fixture.terminal { deleteRepo(old.baseUrl) }.stringRes)
            coVerify(exactly = 7) { fixture.create.await(any()) }
            coVerify(exactly = 2) { fixture.replace.await(any()) }
            coVerify(exactly = 2) { fixture.delete.await(any()) }
            coVerify(exactly = 3) { fixture.manager.findAvailableExtensions() }
        } finally {
            fixture.close()
        }
    }

    private inner class Fixture {
        val create = mockk<CreateExtensionRepo>()
        val replace = mockk<ReplaceExtensionRepo>()
        val delete = mockk<DeleteExtensionRepo>()
        val manager = mockk<ExtensionManager>(relaxed = true)
        val model = run {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            coEvery { create.await(any()) } returns CreateExtensionRepo.Result.Success
            coEvery { replace.await(any()) } returns Unit
            coEvery { delete.await(any()) } returns Unit
            val get = mockk<GetExtensionRepo> { every { subscribeAll() } returns flowOf(emptyList()) }
            Injekt.addSingleton(ExtensionRepoService(mockk(relaxed = true), Json))
            ExtensionReposScreenModel(get, create, delete, replace, mockk<UpdateExtensionRepo>(), manager)
        }
        suspend fun results(count: Int, action: ExtensionReposScreenModel.() -> Unit): List<RepoEvent.ActionResult> {
            model.action()
            return withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) { List(count) { model.events.first() as RepoEvent.ActionResult } }
            }
        }
        suspend fun succeeds(action: ExtensionRepoAction, operation: ExtensionReposScreenModel.() -> Unit) =
            results(2, operation).also {
                val expected =
                    listOf(ExtensionRepoActionResult.Pending(action), ExtensionRepoActionResult.Success(action))
                assertEquals(expected, it.map { event -> event.result })
            }
        suspend fun terminal(action: ExtensionReposScreenModel.() -> Unit) = results(2, action).last()
        fun close() = model.onDispose().also { Dispatchers.resetMain() }
    }

    private fun repo(url: String, fingerprint: String) = ExtensionRepo(url, url, null, url, fingerprint)
}
