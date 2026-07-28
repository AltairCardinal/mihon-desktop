package mihon.test.desktop

/**
 * Example E2E Tests - Documentation Only
 *
 * These are NOT runnable tests. They serve as documentation for how to use
 * the DesktopTestClient for end-to-end testing.
 *
 * To run E2E tests:
 * 1. Start desktop app: "/Applications/Mihon Desktop.app" --test-mode
 * 2. Run tests: ./gradlew :test-desktop:test --tests "*ExampleE2E*"
 *
 * See RobotSmokeTestSuite for actual runnable tests.
 */

/*
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExampleE2ETest {

    private lateinit var client: DesktopTestClient

    @BeforeAll
    fun setup() {
        client = DesktopTestClient("localhost", 8080)
        assertThat(client.isServerRunning()).isTrue()
    }

    @AfterAll
    fun teardown() {
        client.close()
    }

    // Library Tests
    @Test
    fun `navigate to library screen`() {
        val result = client.navigate("LibraryTab")
        assertThat(result.success).isTrue()
    }

    @Test
    fun `search in library`() {
        client.library.open().search("One Piece")
        assertThat(client.getState().currentScreen).isEqualTo("LibraryTab")
    }

    @Test
    fun `filter and sort library`() {
        client.library.open()
            .filterUnread()
            .sortByLastRead()
            .clearFilters()
    }

    // Settings Tests
    @Test
    fun `navigate to settings`() {
        client.settings.open()
        assertThat(client.getState().currentScreen).isEqualTo("SettingsScreen")
    }

    // Downloads Tests
    @Test
    fun `pause downloads`() {
        client.downloads.open().pauseAll()
        assertThat(client.downloads.isPaused()).isTrue()
    }

    // Updates Tests
    @Test
    fun `filter updates`() {
        client.updates.open().filterUnread()
        assertThat(client.updates.getUpdateCount()).isGreaterThanOrEqualTo(0)
    }

    // History Tests
    @Test
    fun `search history`() {
        client.history.open().search("test")
        assertThat(client.history.getHistoryCount()).isGreaterThanOrEqualTo(0)
    }
}
*/

/**
 * Example usage of Robot pattern (not a test).
 *
 * ```
 * val client = DesktopTestClient("localhost", 8080)
 *
 * // Library Robot
 * client.library.open().search("One Piece").filterUnread().sortByTitle()
 *
 * // Reader Robot
 * client.reader.nextPage().nextChapter().setMode("webtoon")
 *
 * // Downloads Robot
 * client.downloads.open().pauseAll().resumeAll()
 *
 * // Updates Robot
 * client.updates.open().filterUnread().refresh()
 *
 * // History Robot
 * client.history.open().search("manga").selectEntry(0)
 *
 * client.close()
 * ```
 */
object ExampleE2ETestDocumentation
