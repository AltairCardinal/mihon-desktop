package mihon.test.desktop

import mihon.test.desktop.robot.BrowseRobot
import mihon.test.desktop.robot.LibraryRobot
import mihon.test.desktop.robot.ReaderRobot
import mihon.test.desktop.robot.SettingsRobot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * 示例 E2E 测试，展示如何使用 DesktopTestClient 进行自动化测试。
 *
 * 运行方式:
 * 1. 启动桌面应用: "/Applications/Mihon Desktop.app" --test-mode
 * 2. 运行测试: ./gradlew :test-desktop:test --tests "*ExampleE2E*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExampleE2ETest {

    private lateinit var client: DesktopTestClient

    /**
     * 测试前准备 - 启动应用或连接到已运行的应用
     */
    @BeforeAll
    fun setup() {
        // 方式1: 启动新应用实例 (headless 模式)
        client = DesktopTestClient("localhost", 8080)
        // client.start(appPath = "/Applications/Mihon Desktop.app", headless = true)

        // 方式2: 直接连接到已运行的应用
        // 确保应用已启动: "/Applications/Mihon Desktop.app" --test-mode
        assertThat(client.isServerRunning()).isTrue()
    }

    /**
     * 测试后清理
     */
    @AfterAll
    fun teardown() {
        client.close()
    }

    // ═══════════════════════════════════════════════════════════════
    // Library Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `navigate to library screen`() {
        // 导航到库
        val result = client.navigate("LibraryTab")

        // 验证
        assertThat(result.success).isTrue()
        assertThat(result.newScreen).isEqualTo("LibraryTab")
    }

    @Test
    fun `search in library`() {
        // 使用 Robot 执行搜索
        client.library
            .open()
            .search("One Piece")

        // 验证状态
        val state = client.getState()
        assertThat(state.currentScreen).isEqualTo("LibraryTab")
    }

    @Test
    fun `filter library by unread`() {
        client.library
            .open()
            .filterUnread()

        // 验证加载状态变化
        val state = client.getState()
        assertThat(state.screens).isNotEmpty()
    }

    @Test
    fun `sort library by title`() {
        client.library
            .open()
            .sortByTitle()

        // 验证动作历史
        val history = client.getState()
        assertThat(history.screens).isNotEmpty()
    }

    @Test
    fun `filter and sort library`() {
        client.library
            .open()
            .filterUnread()
            .sortByLastRead()
            .clearFilters()

        // 链式调用验证
        assertThat(client.getState().currentScreen).isEqualTo("LibraryTab")
    }

    // ═══════════════════════════════════════════════════════════════
    // Settings Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `navigate to settings`() {
        client.settings.open()

        val state = client.getState()
        assertThat(state.currentScreen).isEqualTo("SettingsScreen")
    }

    @Test
    fun `change setting value`() {
        client.settings
            .open()
            .set("theme", "dark")

        // 验证设置已应用
        val state = client.getState()
        assertThat(state.currentScreen).isEqualTo("SettingsScreen")
    }

    // ═══════════════════════════════════════════════════════════════
    // Browse Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `navigate to browse and search`() {
        client.browse
            .open()
            .search("Naruto")

        assertThat(client.getState().currentScreen).isEqualTo("BrowseTab")
    }

    // ═══════════════════════════════════════════════════════════════
    // Screenshot Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `capture screenshot`() {
        client.library.open()

        val result = client.screenshot("library_test")

        assertThat(result.success).isTrue()
        assertThat(result.path).isNotNull()
        assertThat(result.path).endsWith(".png")
    }

    // ═══════════════════════════════════════════════════════════════
    // State Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `get application state`() {
        val state = client.getState()

        // 验证状态结构
        assertThat(state.testMode).isTrue()
        assertThat(state.screens).isNotEmpty()
        assertThat(state.actions).isNotEmpty()
    }

    @Test
    fun `get available screens`() {
        val screens = client.getScreens()

        assertThat(screens).isNotEmpty()
        assertThat(screens.map { it.name }).contains("LibraryTab")
    }

    @Test
    fun `reset test state`() {
        // 执行一些操作
        client.library.open()
        client.library.search("test")

        // 重置
        client.reset()

        // 验证重置成功
        val state = client.getState()
        assertThat(state.testMode).isTrue()
    }

    // ═══════════════════════════════════════════════════════════════
    // Action Execution Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `execute search action`() {
        val result = client.executeAction("search", mapOf("query" to "test manga"))

        assertThat(result.success).isTrue()
        assertThat(result.action).isEqualTo("search")
    }

    @Test
    fun `execute filter action`() {
        val result = client.executeAction("filter", mapOf("type" to "unread"))

        assertThat(result.success).isTrue()
        assertThat(result.action).isEqualTo("filter")
    }

    @Test
    fun `execute sort action`() {
        val result = client.executeAction("sort", mapOf("mode" to "title"))

        assertThat(result.success).isTrue()
        assertThat(result.action).isEqualTo("sort")
    }

    // ═══════════════════════════════════════════════════════════════
    // Data Management Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `clear test data`() {
        // 清除所有测试数据
        client.data.clearAll()

        // 验证（具体验证取决于实现）
        val state = client.getState()
        assertThat(state.testMode).isTrue()
    }

    @Test
    fun `setup test fixtures`() {
        // 设置标准测试数据
        client.data.setupFixtures()

        // 验证数据已设置
        val state = client.getState()
        assertThat(state.screens).isNotEmpty()
    }
}

/**
 * 独立的 Robot 使用示例（非 JUnit 测试）
 */
fun standaloneRobotExamples() {
    val client = DesktopTestClient("localhost", 8080)

    // ── Library Robot ──────────────────────────────────────────
    client.library
        .open() // 打开库
        .search("One Piece") // 搜索
        .filterUnread() // 筛选未读
        .sortByTitle() // 排序
        .selectManga(0) // 选择漫画
        .capture("manga_detail") // 截图

    // ── Reader Robot ───────────────────────────────────────────
    client.reader
        .nextPage() // 下一页
        .prevPage() // 上一页
        .nextChapter() // 下一章
        .prevChapter() // 上一章
        .setMode("left_to_right") // 设置模式
        .zoomIn() // 放大
        .zoomOut() // 缩小
        .capture("reader") // 截图

    // ── Settings Robot ─────────────────────────────────────────
    client.settings
        .open() // 打开设置
        .set("theme", "dark") // 设置值
        .reset() // 重置
        .capture("settings") // 截图

    // ── Browse Robot ───────────────────────────────────────────
    client.browse
        .open() // 打开浏览
        .search("Naruto") // 搜索
        .selectManga(0) // 选择漫画

    // ── Visual Testing ─────────────────────────────────────────
    client.visual.setBaselineDir(java.nio.file.Path.of("test-baseline"))
    client.visual.setDiffDir(java.nio.file.Path.of("build/screens/diff"))

    client.library.open()
    val matches = client.visual.assertMatchesBaseline("library_main")

    if (!matches) {
        println("Visual regression detected!")
    }

    // ── Data Management ────────────────────────────────────────
    client.data.setupFixtures()
    client.data.clearAll()

    client.close()
}
