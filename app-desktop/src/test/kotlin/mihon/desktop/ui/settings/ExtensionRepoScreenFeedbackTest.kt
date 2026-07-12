package mihon.desktop.ui.settings

import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
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
    fun `create result maps to specific user-facing message`() {
        assertEquals(
            "Repository URL must be HTTPS.",
            extensionRepoCreateMessage(CreateExtensionRepo.Result.InvalidUrl),
        )
        assertEquals(
            "Could not reach repository. Check the URL or network connection.",
            extensionRepoCreateMessage(CreateExtensionRepo.Result.RepositoryUnavailable),
        )
        assertEquals(
            "Repository metadata is missing or invalid.",
            extensionRepoCreateMessage(CreateExtensionRepo.Result.InvalidRepository),
        )
    }
}
