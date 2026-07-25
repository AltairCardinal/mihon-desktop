package mihon.desktop.ui.settings

import kotlinx.coroutines.test.runTest
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.service.ExtensionRepoAction
import mihon.domain.extensionrepo.service.ExtensionRepoActionResult
import mihon.domain.extensionrepo.service.ExtensionRepoCreateOutcome
import mihon.domain.extensionrepo.service.ExtensionRepoFailure
import mihon.domain.extensionrepo.service.ExtensionRepoValidation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtensionRepoScreenFeedbackTest {

    @Test
    fun `pending repository message is visible immediately after submit`() {
        assertEquals(
            "Checking repository...",
            extensionRepoPendingTitle("https://raw.githubusercontent.com/keiyoushi/extensions/repo"),
        )
    }

    @Test
    fun `shared results map to Desktop specific user-facing messages`() {
        val old = repo("https://old.example", "FINGERPRINT")
        val replacement = repo("https://new.example", "fingerprint")
        val cases = listOf(
            ExtensionRepoActionResult.Pending(ExtensionRepoAction.CREATE) to "Checking repository...",
            ExtensionRepoActionResult.Success(ExtensionRepoAction.CREATE) to "Completed",
            ExtensionRepoActionResult.Validation(ExtensionRepoAction.CREATE, ExtensionRepoValidation.INVALID_URL) to
                "Repository URL must be HTTPS.",
            ExtensionRepoActionResult.Validation(ExtensionRepoAction.CREATE, ExtensionRepoValidation.ALREADY_EXISTS) to
                "This repo already exists!",
            ExtensionRepoActionResult.Validation(ExtensionRepoAction.REPLACE, ExtensionRepoValidation.FINGERPRINT_CHANGED) to
                "Signing Key Fingerprint Already Exists",
            ExtensionRepoActionResult.Failure(
                ExtensionRepoAction.CREATE,
                ExtensionRepoFailure.REPOSITORY_UNAVAILABLE,
            ) to "Could not reach repository. Check the URL or network connection.",
            ExtensionRepoActionResult.Failure(
                ExtensionRepoAction.CREATE,
                ExtensionRepoFailure.INVALID_REPOSITORY,
            ) to "Repository metadata is missing or invalid.",
            ExtensionRepoActionResult.Failure(ExtensionRepoAction.CREATE, ExtensionRepoFailure.UNKNOWN) to
                "Failed to add repository.",
            ExtensionRepoActionResult.Failure(ExtensionRepoAction.DELETE, ExtensionRepoFailure.UNKNOWN) to "Unknown error",
            ExtensionRepoActionResult.FingerprintConflict(old, replacement) to
                "Signing Key Fingerprint Already Exists",
        )
        cases.forEach { (result, message) -> assertEquals(message, extensionRepoActionMessage(result)) }
    }

    @Test
    fun `production Desktop actions publish pending then shared conflict`() = runTest {
        val old = repo("https://old.example", "FINGERPRINT")
        val replacement = repo("https://new.example", "fingerprint")
        val events = mutableListOf<ExtensionRepoActionResult>()
        val actions = DesktopExtensionRepoActions(
            create = { ExtensionRepoCreateOutcome.Conflict(old, replacement) },
            replace = {},
            delete = {},
        )
        actions.create(replacement.baseUrl, events::add)
        assertEquals(ExtensionRepoActionResult.Pending(ExtensionRepoAction.CREATE), events.first())
        assertEquals(ExtensionRepoActionResult.FingerprintConflict(old, replacement), events.last())
    }

    private fun repo(url: String, fingerprint: String) = ExtensionRepo(url, url, null, url, fingerprint)
}
