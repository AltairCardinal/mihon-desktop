package mihon.domain.extensionrepo.service

import kotlinx.coroutines.test.runTest
import mihon.domain.extensionrepo.model.ExtensionRepo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtensionRepoServiceContractTest {
    private val old = repo("https://old.example", "FINGERPRINT")
    private val replacement = repo("https://new.example", "fingerprint")

    @Test
    fun `create maps validation conflict and failures into the shared result contract`() = runTest {
        val service = ExtensionRepoService()
        val cases = listOf(
            ExtensionRepoCreateOutcome.Success to success(ExtensionRepoAction.CREATE),
            ExtensionRepoCreateOutcome.InvalidUrl to validation(ExtensionRepoValidation.INVALID_URL),
            ExtensionRepoCreateOutcome.AlreadyExists to validation(ExtensionRepoValidation.ALREADY_EXISTS),
            ExtensionRepoCreateOutcome.RepositoryUnavailable to
                failure(ExtensionRepoFailure.REPOSITORY_UNAVAILABLE, ExtensionRepoAction.CREATE),
            ExtensionRepoCreateOutcome.InvalidRepository to
                failure(ExtensionRepoFailure.INVALID_REPOSITORY, ExtensionRepoAction.CREATE),
            ExtensionRepoCreateOutcome.Failure to failure(ExtensionRepoFailure.UNKNOWN, ExtensionRepoAction.CREATE),
        )
        cases.forEach { (outcome, expected) ->
            assertEquals(expected, service.create(old.baseUrl) { outcome })
        }
        val conflict = ExtensionRepoCreateOutcome.Conflict(old, replacement)
        val result = service.create(old.baseUrl) { conflict }
        assertEquals(ExtensionRepoActionResult.FingerprintConflict(old, replacement), result)
    }

    @Test
    fun `replace preserves fingerprint continuity and every mutation reports failures`() = runTest {
        var replaced: ExtensionRepo? = null
        var deleted: String? = null
        val service = ExtensionRepoService()
        assertEquals(success(ExtensionRepoAction.REPLACE), service.replace(old, replacement) { replaced = it })
        assertEquals(old.signingKeyFingerprint, replaced?.signingKeyFingerprint)
        val changed = replacement.copy(signingKeyFingerprint = "OTHER")
        val changedResult = validation(ExtensionRepoValidation.FINGERPRINT_CHANGED, ExtensionRepoAction.REPLACE)
        assertEquals(changedResult, service.replace(old, changed) { replaced = it })
        assertEquals(success(ExtensionRepoAction.DELETE), service.delete(old.baseUrl) { deleted = it })
        assertEquals(old.baseUrl, deleted)
        val failedCreate = service.create(old.baseUrl) { error("create") }
        assertEquals(failure(ExtensionRepoFailure.UNKNOWN, ExtensionRepoAction.CREATE), failedCreate)
        val failedReplace = service.replace(old, replacement) { error("replace") }
        assertEquals(failure(ExtensionRepoFailure.UNKNOWN, ExtensionRepoAction.REPLACE), failedReplace)
        val failedDelete = service.delete(old.baseUrl) { error("delete") }
        assertEquals(failure(ExtensionRepoFailure.UNKNOWN, ExtensionRepoAction.DELETE), failedDelete)
        assertEquals(changedResult, service.replace(old, changed) { error("replace") })
    }

    @Test
    fun `execute publishes pending before the terminal shared result`() = runTest {
        val events = mutableListOf<ExtensionRepoActionResult>()
        val service = ExtensionRepoService()
        val result = service.execute(ExtensionRepoAction.DELETE, events::add) {
            success(ExtensionRepoAction.DELETE)
        }
        assertEquals(listOf(ExtensionRepoActionResult.Pending(ExtensionRepoAction.DELETE), result), events)
    }

    private fun success(action: ExtensionRepoAction) = ExtensionRepoActionResult.Success(action)
    private fun validation(reason: ExtensionRepoValidation, action: ExtensionRepoAction = ExtensionRepoAction.CREATE) =
        ExtensionRepoActionResult.Validation(action, reason)
    private fun failure(reason: ExtensionRepoFailure, action: ExtensionRepoAction) =
        ExtensionRepoActionResult.Failure(action, reason)
    private fun repo(url: String, fingerprint: String) = ExtensionRepo(url, url, null, url, fingerprint)
}
