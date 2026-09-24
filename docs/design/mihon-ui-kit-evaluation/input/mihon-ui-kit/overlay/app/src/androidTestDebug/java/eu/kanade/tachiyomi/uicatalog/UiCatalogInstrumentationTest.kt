package eu.kanade.tachiyomi.uicatalog

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import org.junit.Rule
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/** Native catalog tests. These do not test the production reader, filesystem, extension installer or trackers. */
class UiCatalogInstrumentationTest {
    @get:Rule val compose = createAndroidComposeRule<UiCatalogActivity>()

    private fun open(category: Int) {
        compose.onNodeWithTag("catalog-list").performScrollToNode(hasTestTag("open:$category"))
        compose.onNodeWithTag("open:$category").performClick()
        compose.onNodeWithTag("page:$category").assertIsDisplayed()
    }
    private fun scenario(index: Int) {
        compose.onNodeWithTag("choose-case").performClick()
        compose.onNodeWithTag("case:$index").performClick()
    }
    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    @Test fun all32CategoryEntriesAreReachable() {
        for (id in 1..32) {
            open(id)
            compose.onNodeWithTag("appbar").assertIsDisplayed()
            compose.onNodeWithTag("exit-category").performClick()
            compose.onNodeWithTag("catalog-list").assertIsDisplayed()
        }
    }

    @Test fun subpageRetainsRealTitleBarAndUpAcrossDataStates() {
        for (index in 0..3) {
            open(1); scenario(index)
            compose.onNodeWithTag("appbar").assertIsDisplayed()
            val up = compose.activity.stringResource(MR.strings.action_bar_up_description)
            compose.onNodeWithContentDescription(up).assertIsDisplayed().performClick()
            compose.onNodeWithTag("catalog-list").assertIsDisplayed()
        }
    }

    @Test fun systemBackClearsSelectionThenSearch() {
        open(4); scenario(1)
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.onNodeWithTag("page:4").assertIsDisplayed()
        compose.onNodeWithTag("back-state").assertTextContains("query=query", substring = true)
        Espresso.pressBack()
        compose.onNodeWithTag("back-state").assertTextContains("query=null", substring = true)
        Espresso.pressBack()
        compose.onNodeWithTag("catalog-list").assertIsDisplayed()
    }

    @Test fun formBlocksInvalidAndUsesSameImeSubmission() {
        open(16)
        compose.onNodeWithTag("commit-name").assertIsNotEnabled()
        compose.onNodeWithTag("draft").performScrollTo().performTextReplacement("New UI name")
        compose.onNodeWithTag("draft").performImeAction()
        compose.onNodeWithTag("saved-value").performScrollTo().assertTextContains("New UI name", substring = true)
        compose.onNodeWithTag("commit-name").assertIsNotEnabled()
    }

    @Test fun cancellationDoesNotCommitFixtureDraft() {
        open(16); scenario(3)
        click("cancel-edit")
        compose.onNodeWithTag("saved-value").assertTextContains("Original", substring = true)
        compose.onNodeWithTag("draft").assertTextContains("Original", substring = true)
    }

    @Test fun removalRequiresScopeAndCancellationPreservesRows() {
        open(17); click("open-remove")
        compose.onNodeWithTag("confirm-delete").assertIsNotEnabled()
        compose.onNodeWithTag("dialog-cancel").performClick()
        compose.onNodeWithTag("row:1").performScrollTo().assertIsDisplayed()
    }

    @Test fun runningTaskCannotBeStartedAgain() {
        open(19); click("start-task")
        compose.onNodeWithTag("start-task").assertIsNotEnabled()
        click("tick-task")
        compose.onNodeWithTag("progress").assertTextContains("25", substring = true)
        click("pause-task"); click("start-task")
        compose.onNodeWithTag("progress").assertTextContains("25", substring = true)
    }

    @Test fun activityRecreationRestoresCatalogDraft() {
        open(6)
        compose.onNodeWithTag("draft").performScrollTo().performTextReplacement("Saved through recreation")
        Espresso.closeSoftKeyboard(); compose.waitForIdle()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("page:6").assertIsDisplayed()
        compose.onNodeWithTag("draft").performScrollTo().assertTextContains("Saved through recreation", substring = true)
    }

    @Test fun readerFixtureHasBoundaryButtons() {
        open(28)
        compose.onNodeWithTag("reader-prev").assertIsNotEnabled()
        click("reader-next")
        compose.onNodeWithTag("page-counter").assertTextContains("2", substring = true)
        scenario(1)
        compose.onNodeWithTag("reader-next").assertIsNotEnabled()
    }

    @Test fun disabledAccessibleToggleCannotBeActivated() {
        open(26); scenario(2)
        compose.onNodeWithTag("accessible-toggle").assertIsNotEnabled()
        compose.onNodeWithTag("accessible-action").assertIsNotEnabled()
    }
}
