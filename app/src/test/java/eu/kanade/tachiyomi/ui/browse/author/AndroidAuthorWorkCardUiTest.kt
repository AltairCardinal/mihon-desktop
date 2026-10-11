package eu.kanade.tachiyomi.ui.browse.author

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.LanguageProjectionContract
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
class AndroidAuthorWorkCardUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `compact cover image and title scrim share the full book bounds`() = withActivity {
        card(LibraryDisplayMode.CompactGrid)
        compose.waitForIdle()
        val cover = compose.onNodeWithTag("creator-cover-fixture", true).fetchSemanticsNode().boundsInRoot
        val image = compose.onNodeWithTag("creator-cover-image-fixture", true).fetchSemanticsNode().boundsInRoot
        val scrim = compose.onNodeWithTag("creator-cover-scrim-fixture", true).fetchSemanticsNode().boundsInRoot
        assertEquals(2f / 3f, cover.width / cover.height, 0.005f)
        assertEquals(cover, image)
        assertEquals(cover, scrim)
    }

    @Test
    fun `narrow date shows complete date and restores its label when widened`() = withActivity {
        val width = mutableStateOf(112.dp)
        setContent { MaterialTheme { Box(Modifier.width(width.value)) { workCard(LibraryDisplayMode.CompactGrid) } } }
        compose.onNodeWithTag("creator-work-first-seen-fixture", true).assertTextEquals("2026-10-11")
        compose.runOnIdle { width.value = 360.dp }
        compose.onNodeWithTag("creator-work-first-seen-fixture", true).assertTextEquals("首次发现：2026-10-11")
    }

    @Test
    fun `larger fonts use the date alone in comfortable grid and list`() = withActivity {
        val fontScale = mutableStateOf(1f)
        val mode = mutableStateOf<LibraryDisplayMode>(LibraryDisplayMode.ComfortableGrid)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale.value)) {
                    Box(Modifier.width(240.dp)) { workCard(mode.value) }
                }
            }
        }
        listOf(LibraryDisplayMode.ComfortableGrid, LibraryDisplayMode.List).forEach {
            compose.runOnIdle {
                mode.value = it
                fontScale.value = 1f
            }
            compose.onNodeWithTag("creator-work-first-seen-fixture", true).assertTextEquals("首次发现：2026-10-11")
            compose.runOnIdle { fontScale.value = 2f }
            compose.onNodeWithTag("creator-work-first-seen-fixture", true).assertTextEquals("2026-10-11")
        }
    }

    @Test
    fun `pending work has no correction menu in any display mode`() = withActivity {
        val mode = mutableStateOf<LibraryDisplayMode>(LibraryDisplayMode.CompactGrid)
        setContent { MaterialTheme { Box(Modifier.width(180.dp)) { workCard(mode.value) } } }
        listOf(
            LibraryDisplayMode.CompactGrid,
            LibraryDisplayMode.ComfortableGrid,
            LibraryDisplayMode.CoverOnlyGrid,
            LibraryDisplayMode.List,
        ).forEach {
            compose.runOnIdle { mode.value = it }
            compose.onNodeWithTag("creator-pending-more-7", true).assertDoesNotExist()
            compose.onNodeWithTag("creator-pending-correct-7", true).assertDoesNotExist()
        }
    }

    @Test
    fun `Chinese alias action is localized`() = withActivity {
        assertEquals("关联别名", getString(MR.strings.desktop_ui_add_author_alias.resourceId))
    }

    private fun ComponentActivity.card(mode: LibraryDisplayMode) {
        setContent { MaterialTheme { Box(Modifier.width(130.dp)) { workCard(mode) } } }
    }

    @Composable
    private fun workCard(mode: LibraryDisplayMode) {
        val version = SourceWorkArchiveVersion(
            sourceWorkId = 7L,
            naturalKey = SourceWorkNaturalKey(42L, "/fixture"),
            mangaId = null,
            title = "作品名称",
            readingLanguage = LanguageProjectionContract(
                LanguageDimension.READING,
                "und",
                LanguageCertainty.UNKNOWN,
                LanguageEvidenceKind.UNKNOWN,
            ),
            chapterCount = 0L,
            inLibrary = false,
            detailsFetchedAt = null,
            lastSeenAt = 0L,
            decision = null,
            firstSeenDate = "2026-10-11",
        )
        CreatorArchiveWorkCard(
            title = version.title,
            version = version,
            dateLabel = CreatorWorkDateLabel("首次发现：2026-10-11", "2026-10-11"),
            favorite = false,
            unread = false,
            key = "fixture",
            mode = mode,
            focusRequester = FocusRequester(),
            sourceNames = listOf("Fixture source"),
            onClick = {},
        )
    }

    private fun withActivity(block: ComponentActivity.() -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().block()
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
