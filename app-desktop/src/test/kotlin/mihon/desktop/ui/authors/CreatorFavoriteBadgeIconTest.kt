package mihon.desktop.ui.authors

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CreatorFavoriteBadgeIconTest {
    @Test
    fun `favorite badge uses the outlined bookmark shown on Android`() {
        assertSame(Icons.Outlined.CollectionsBookmark, creatorFavoriteBadgeIcon())
    }
}
