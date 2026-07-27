package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.extensionrepo.interactor.DeleteExtensionRepo
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.service.ExtensionRepoAction
import mihon.domain.extensionrepo.service.ExtensionRepoActionResult
import mihon.domain.extensionrepo.service.ExtensionRepoCreateOutcome
import mihon.domain.extensionrepo.service.ExtensionRepoFailure
import mihon.domain.extensionrepo.service.ExtensionRepoService
import mihon.domain.extensionrepo.service.ExtensionRepoValidation
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionReposScreenModel(
    private val getExtensionRepo: GetExtensionRepo = Injekt.get(),
    private val createExtensionRepo: CreateExtensionRepo = Injekt.get(),
    private val deleteExtensionRepo: DeleteExtensionRepo = Injekt.get(),
    private val replaceExtensionRepo: ReplaceExtensionRepo = Injekt.get(),
    private val updateExtensionRepo: UpdateExtensionRepo = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val extensionRepoService: ExtensionRepoService = Injekt.get(),
) : StateScreenModel<RepoScreenState>(RepoScreenState.Loading) {

    private val _events: Channel<RepoEvent> = Channel(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            getExtensionRepo.subscribeAll()
                .collectLatest { repos ->
                    mutableState.update {
                        RepoScreenState.Success(
                            repos = repos.toImmutableSet(),
                        )
                    }
                }
        }
    }

    /**
     * Creates and adds a new repo to the database.
     *
     * @param baseUrl The baseUrl of the repo to create.
     */
    fun createRepo(baseUrl: String) {
        screenModelScope.launchIO {
            publish(ExtensionRepoActionResult.Pending(ExtensionRepoAction.CREATE))
            when (val result = extensionRepoService.create(baseUrl) { createExtensionRepo.await(it).toOutcome() }) {
                is ExtensionRepoActionResult.FingerprintConflict -> {
                    showDialog(RepoDialog.Conflict(result.oldRepo, result.newRepo))
                    publish(result)
                }
                else -> publishResult(result)
            }
        }
    }

    /**
     * Inserts a repo to the database, replace a matching repo with the same signing key fingerprint if found.
     *
     * @param oldRepo The conflicting repo selected by the user
     * @param newRepo The repo to insert
     */
    fun replaceRepo(oldRepo: ExtensionRepo, newRepo: ExtensionRepo) {
        screenModelScope.launchIO {
            publish(ExtensionRepoActionResult.Pending(ExtensionRepoAction.REPLACE))
            val result = extensionRepoService.replace(oldRepo, newRepo, replaceExtensionRepo::await)
            publishResult(result)
        }
    }

    /**
     * Refreshes information for each repository.
     */
    fun refreshRepos() {
        val status = state.value

        if (status is RepoScreenState.Success) {
            screenModelScope.launchIO {
                updateExtensionRepo.awaitAll()
            }
        }
    }

    /**
     * Deletes the given repo from the database
     */
    fun deleteRepo(baseUrl: String) {
        screenModelScope.launchIO {
            publish(ExtensionRepoActionResult.Pending(ExtensionRepoAction.DELETE))
            publishResult(extensionRepoService.delete(baseUrl, deleteExtensionRepo::await))
        }
    }

    private suspend fun publishResult(result: ExtensionRepoActionResult) {
        publish(result)
        if (result is ExtensionRepoActionResult.Success) {
            extensionManager.findAvailableExtensions()
        }
    }

    private suspend fun publish(result: ExtensionRepoActionResult) {
        _events.send(RepoEvent.ActionResult(result))
    }

    fun showDialog(dialog: RepoDialog) {
        mutableState.update {
            when (it) {
                RepoScreenState.Loading -> it
                is RepoScreenState.Success -> it.copy(dialog = dialog)
            }
        }
    }

    fun dismissDialog() {
        mutableState.update {
            when (it) {
                RepoScreenState.Loading -> it
                is RepoScreenState.Success -> it.copy(dialog = null)
            }
        }
    }
}

private fun CreateExtensionRepo.Result.toOutcome() = when (this) {
    CreateExtensionRepo.Result.Success -> ExtensionRepoCreateOutcome.Success
    CreateExtensionRepo.Result.InvalidUrl -> ExtensionRepoCreateOutcome.InvalidUrl
    CreateExtensionRepo.Result.RepoAlreadyExists -> ExtensionRepoCreateOutcome.AlreadyExists
    is CreateExtensionRepo.Result.DuplicateFingerprint -> ExtensionRepoCreateOutcome.Conflict(oldRepo, newRepo)
    CreateExtensionRepo.Result.RepositoryUnavailable -> ExtensionRepoCreateOutcome.RepositoryUnavailable
    CreateExtensionRepo.Result.InvalidRepository -> ExtensionRepoCreateOutcome.InvalidRepository
    CreateExtensionRepo.Result.Error -> ExtensionRepoCreateOutcome.Failure
}

sealed class RepoEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : RepoEvent()
    data object InvalidUrl : LocalizedMessage(MR.strings.invalid_repo_name)
    data object RepoAlreadyExists : LocalizedMessage(MR.strings.error_repo_exists)
    data class ActionResult(
        val result: ExtensionRepoActionResult,
    ) : LocalizedMessage(
        when (result) {
            is ExtensionRepoActionResult.Pending -> MR.strings.ext_pending
            is ExtensionRepoActionResult.Success -> MR.strings.completed
            is ExtensionRepoActionResult.FingerprintConflict -> MR.strings.action_replace_repo_title
            is ExtensionRepoActionResult.Validation -> when (result.reason) {
                ExtensionRepoValidation.INVALID_URL -> MR.strings.invalid_repo_name
                ExtensionRepoValidation.ALREADY_EXISTS -> MR.strings.error_repo_exists
                ExtensionRepoValidation.FINGERPRINT_CHANGED -> MR.strings.action_replace_repo_title
            }
            is ExtensionRepoActionResult.Failure -> when (result.reason) {
                ExtensionRepoFailure.REPOSITORY_UNAVAILABLE -> MR.strings.desktop_extension_repo_unavailable
                ExtensionRepoFailure.INVALID_REPOSITORY -> MR.strings.desktop_extension_repo_invalid_metadata
                ExtensionRepoFailure.UNKNOWN -> MR.strings.unknown_error
            }
        },
    )
}

sealed class RepoDialog {
    data object Create : RepoDialog()
    data class Delete(val repo: String) : RepoDialog()
    data class Conflict(val oldRepo: ExtensionRepo, val newRepo: ExtensionRepo) : RepoDialog()
    data class Confirm(val url: String) : RepoDialog()
}

sealed class RepoScreenState {

    @Immutable
    data object Loading : RepoScreenState()

    @Immutable
    data class Success(
        val repos: ImmutableSet<ExtensionRepo>,
        val oldRepos: ImmutableSet<String>? = null,
        val dialog: RepoDialog? = null,
    ) : RepoScreenState() {

        val isEmpty: Boolean
            get() = repos.isEmpty()
    }
}
