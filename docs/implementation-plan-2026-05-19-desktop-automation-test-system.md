# Mihon Desktop 用户操作自动化测试系统实现计划

## 文档信息

- **日期**: 2026-05-19
- **版本**: 1.0
- **状态**: 草案，待审批

> 维护说明（2026-07-28）：本文是历史草案。其中 Screenshot Service、截图 HTTP API、
> Robot/视觉回归截图客户端和 `--screenshot-dir` 已从当前实现移除；不得据此恢复需要
> macOS 屏幕录制权限的能力。当前接口以 `docs/automation/API_REFERENCE.md` 为准。

---

## 一、系统架构概述

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Desktop Automation Test System                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐    │
│  │  Test Client    │      │  Test Runner    │      │  Visual        │    │
│  │  (Kotlin DSL)  │──────│  (JUnit 5)     │──────│  Regression     │    │
│  └────────┬────────┘      └────────┬────────┘      └────────┬────────┘    │
│           │                          │                          │             │
│           │ HTTP/REST                │ State Verification        │ Screenshot  │
│           │                          │                          │ Comparison  │
│           ▼                          ▼                          ▼             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Mihon Desktop Application                          │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐             │   │
│  │  │ HTTP Test    │  │ JMX MBean   │  │ Application  │             │   │
│  │  │ API Server   │  │ Controller   │  │ State        │             │   │
│  │  │ (Ktor :8080)│  │              │  │              │             │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘             │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.1 核心组件

| 组件               | 技术       | 端口 | 用途                             |
| ------------------ | ---------- | ---- | -------------------------------- |
| HTTP Test API      | Ktor       | 8080 | 控制应用状态、执行操作、查询数据 |
| JMX Controller     | JMX        | 9999 | 状态监控、性能指标               |
| Screenshot Service | 内置       | -    | 截图捕获                         |
| Test Client        | Kotlin DSL | -    | 测试编写接口                     |

---

## 二、应用端改造

### 2.1 启动参数设计

```kotlin
// Main.kt 支持的启动参数
--test-mode                    // 启用测试模式
--test-http-port=8080          // HTTP API 端口 (默认 8080)
--test-jmx-port=9999          // JMX 端口 (默认 9999)
--test-data-path=/tmp/test     // 测试数据目录
--headless                     // 无头模式（不显示窗口）
--screenshot-dir=/tmp/screens  // 截图保存目录
```

### 2.2 HTTP Test API 设计

#### 2.2.1 API 端点概览

| 方法 | 路径                        | 描述             |
| ---- | --------------------------- | ---------------- |
| GET  | `/test/state`               | 获取当前应用状态 |
| GET  | `/test/screens`             | 获取所有可用屏幕 |
| POST | `/test/navigate/{screen}`   | 导航到指定屏幕   |
| POST | `/test/action/{action}`     | 执行指定动作     |
| POST | `/test/screenshot`          | 截取当前屏幕     |
| POST | `/test/screenshot/{screen}` | 截取指定屏幕     |
| POST | `/test/reset`               | 重置测试数据     |
| POST | `/test/data/{entity}`       | 操作测试数据     |
| GET  | `/test/screenshot/compare`  | 对比截图         |

#### 2.2.2 详细 API 规范

**GET /test/state**

```json
{
  "currentScreen": "LibraryTab",
  "isLoading": false,
  "notifications": [],
  "timestamp": "2026-05-19T12:00:00Z"
}
```

**POST /test/navigate/{screen}**

```json
// Request
{ "params": {} }

// Response
{
  "success": true,
  "newScreen": "BrowseTab",
  "timestamp": "2026-05-19T12:00:01Z"
}
```

**POST /test/screenshot**

```json
// Request
{ "name": "library_before_search", "path": "/tmp/screens" }

// Response
{
  "path": "/tmp/screens/library_before_search.png",
  "width": 1024,
  "height": 768,
  "timestamp": "2026-05-19T12:00:01Z"
}
```

**POST /test/action/{action}**

```json
// Request: POST /test/action/library_search
{
  "query": "One Piece"
}

// Request: POST /test/action/manga_open
{
  "mangaId": 12345
}

// Request: POST /test/action/reader_next_page
{}

// Request: POST /test/action/setting_change
{
  "key": "theme",
  "value": "dark"
}
```

**POST /test/data/{entity}**

```json
// POST /test/data/manga
{
  "operation": "add",
  "data": {
    "title": "Test Manga",
    "url": "https://example.com/manga/1"
  }
}

// POST /test/data/manga
{
  "operation": "add_many",
  "data": [
    { "title": "Manga 1", "url": "..." },
    { "title": "Manga 2", "url": "..." }
  ]
}

// POST /test/data/manga
{
  "operation": "delete_all"
}
```

#### 2.2.3 可用导航目标 (screens)

| Screen                  | 描述     | 参数        |
| ----------------------- | -------- | ----------- |
| `LibraryTab`            | 库页面   | -           |
| `UpdatesTab`            | 更新页面 | -           |
| `HistoryTab`            | 历史页面 | -           |
| `BrowseTab`             | 浏览页面 | -           |
| `MoreTab`               | 更多页面 | -           |
| `MangaDetailScreen`     | 漫画详情 | `mangaId`   |
| `ReaderScreen`          | 阅读器   | `chapterId` |
| `SettingsScreen`        | 设置     | -           |
| `MigrationSearchScreen` | 迁移搜索 | -           |
| `ExtensionListScreen`   | 扩展列表 | -           |
| `BackupScreen`          | 备份页面 | -           |

#### 2.2.4 可用动作 (actions)

**Library**

- `library_search` - 搜索库漫画
- `library_filter_toggle` - 切换过滤器
- `library_sort_change` - 更改排序
- `library_category_select` - 选择分类

**Manga**

- `manga_open` - 打开漫画详情
- `manga_add_to_library` - 添加到库
- `manga_remove_from_library` - 从库移除
- `manga_set_category` - 设置分类
- `manga_download` - 下载漫画

**Reader**

- `reader_next_page` - 下一页
- `reader_prev_page` - 上一页
- `reader_next_chapter` - 下一章
- `reader_prev_chapter` - 上一章
- `reader_set_mode` - 设置阅读模式
- `reader_zoom` - 缩放
- `reader_bookmark` - 书签

**Settings**

- `setting_change` - 更改设置
- `setting_reset` - 重置设置

**Extensions**

- `extension_install` - 安装扩展
- `extension_uninstall` - 卸载扩展
- `extension_enable` - 启用扩展
- `extension_disable` - 禁用扩展

### 2.3 JMX MBean 设计

#### 2.3.1 MBean 接口

```kotlin
interface DesktopTestMBean {
    // 状态查询
    fun getCurrentScreen(): String
    fun getApplicationState(): String
    fun isReady(): Boolean
    fun getStartupTime(): Long
    fun getMemoryUsage(): Long

    // 测试控制
    fun enableTestMode()
    fun resetTestData()
    fun triggerGC()

    // 性能指标
    fun getAverageLoadTime(): Double
    fun getScreenTransitionCount(): Long
}
```

#### 2.3.2 通知 (Notifications)

| 类型               | 描述         |
| ------------------ | ------------ |
| `SCREEN_CHANGED`   | 屏幕切换事件 |
| `ACTION_COMPLETED` | 动作完成事件 |
| `ERROR_OCCURRED`   | 错误发生事件 |

### 2.4 需要注入的测试控制能力

#### 2.4.1 Screen Model 扩展

每个 ScreenModel 需要实现测试接口：

```kotlin
interface TestableScreenModel {
    fun getScreenName(): String
    fun getTestState(): Map<String, Any>
    fun executeTestAction(action: String, params: Map<String, Any>): Boolean
}
```

#### 2.4.2 Navigator 测试支持

```kotlin
class TestableNavigator(navigator: Navigator) {
    val currentScreen: Screen
    val screenHistory: List<Screen>

    fun navigateTo(screen: Screen)
    fun navigateBack()
    fun canNavigateBack(): Boolean
}
```

#### 2.4.3 数据层测试接口

```kotlin
interface TestDataManager {
    // Manga
    suspend fun addTestManga(manga: TestManga): Long
    suspend fun getManga(id: Long): TestManga?
    suspend fun deleteAllManga()
    suspend fun setMangaFavorite(id: Long, favorite: Boolean)

    // Chapters
    suspend fun addTestChapter(mangaId: Long, chapter: TestChapter): Long

    // Categories
    suspend fun createCategory(name: String): Long
    suspend fun deleteCategory(id: Long)

    // Settings
    suspend fun setSetting(key: String, value: Any)
    suspend fun getSetting(key: String): Any?
}
```

---

## 三、测试框架设计

### 3.1 测试客户端库

#### 3.1.1 Maven/Gradle 依赖

```kotlin
// testImplementation(project(":test-desktop"))
dependencies {
    implementation(project(":test-desktop"))
}
```

#### 3.1.2 核心 DSL

```kotlin
class MihonDesktopTest(
    val appPath: String,
    val httpPort: Int = 8080,
    val jmxPort: Int = 9999
) : AutoCloseable {

    val http: TestHttpClient
    val screenshot: ScreenshotClient
    val visualRegression: VisualRegressionClient
    val data: TestDataClient

    // 应用生命周期
    fun start(): MihonDesktopTest
    fun stop(): Unit
    fun restart(): MihonDesktopTest

    // 截图管理
    fun capture(name: String): Path
    fun assertScreenshot(name: String, tolerance: Double = 0.0)
}
```

#### 3.1.3 Screen Robot 封装

```kotlin
class LibraryRobot(test: MihonDesktopTest) {
    fun open(): LibraryRobot
    fun search(query: String): LibraryRobot
    fun filterBy(filter: LibraryFilter): LibraryRobot
    fun sortBy(sortMode: SortMode): LibraryRobot
    fun selectManga(index: Int): MangaDetailRobot
    fun getMangaCount(): Int
    fun assertMangaVisible(title: String): LibraryRobot
    fun capture(): Path
}

class MangaDetailRobot(test: MihonDesktopTest) {
    fun openChapter(index: Int): ReaderRobot
    fun addToLibrary(): MangaDetailRobot
    fun removeFromLibrary(): MangaDetailRobot
    fun setCategory(category: String): MangaDetailRobot
    fun download(): MangaDetailRobot
    fun capture(): Path
}

class ReaderRobot(test: MihonDesktopTest) {
    fun nextPage(): ReaderRobot
    fun prevPage(): ReaderRobot
    fun nextChapter(): ReaderRobot
    fun prevChapter(): ReaderRobot
    fun setMode(mode: ReadingMode): ReaderRobot
    fun zoom(factor: Float): ReaderRobot
    fun capture(): Path
}

class BrowseRobot(test: MihonDesktopTest) {
    fun selectSource(sourceName: String): BrowseRobot
    fun search(query: String): BrowseRobot
    fun openManga(index: Int): MangaDetailRobot
    fun capture(): Path
}

class SettingsRobot(test: MihonDesktopTest) {
    fun <T> set(key: String, value: T): SettingsRobot
    fun <T> get(key: String): T
    fun reset(): SettingsRobot
    fun capture(): Path
}
```

### 3.2 测试数据管理

#### 3.2.1 测试数据 Fixture

```kotlin
object TestFixtures {
    val sampleManga = TestManga(
        title = "Sample Manga",
        author = "Test Author",
        description = "A manga for testing",
        url = "https://test.example.com/manga/1",
        coverUrl = "https://test.example.com/cover/1.jpg"
    )

    val sampleChapter = TestChapter(
        name = "Chapter 1",
        url = "https://test.example.com/chapter/1",
        scanlator = "Test Group"
    )

    val sampleCategories = listOf(
        TestCategory(name = "Action"),
        TestCategory(name = "Comedy"),
        TestCategory(name = "Drama")
    )
}
```

#### 3.2.2 数据清理策略

```kotlin
@BeforeEach
fun setup() {
    testApp.start()
    testApp.data.clearAll()
    testApp.data.setupFixtures()
}

@AfterEach
fun cleanup() {
    testApp.stop()
}

@AfterAll
fun globalCleanup() {
    testApp.data.deleteAll()
}
```

### 3.3 视觉回归测试

#### 3.3.1 截图管理

```kotlin
class ScreenshotManager(
    val baselineDir: Path,
    val currentDir: Path,
    val diffDir: Path
) {
    fun capture(name: String): Path
    fun compare(name: String, tolerance: Double = 0.0): ComparisonResult
    fun approve(name: String)  // 将当前版本提升为基准
    fun generateReport(): VisualReport
}

data class ComparisonResult(
    val passed: Boolean,
    val diffPath: Path?,
    val diffPercentage: Double,
    val message: String
)
```

#### 3.3.2 截图对比算法

使用感知哈希 (Perceptual Hash) + 像素差异：

```kotlin
class ScreenshotComparator {
    fun compare(baseline: Path, current: Path): ComparisonResult {
        val baselineImage = loadImage(baseline)
        val currentImage = loadImage(current)

        // 1. 调整大小到统一尺寸
        val resized = resize(currentImage, baselineImage.size)

        // 2. 计算感知哈希
        val baselineHash = perceptualHash(baselineImage)
        val currentHash = perceptualHash(resized)

        // 3. 计算汉明距离
        val hammingDistance = hammingDistance(baselineHash, currentHash)

        // 4. 计算像素差异
        val pixelDiff = pixelDifference(baselineImage, resized)

        // 5. 综合判定
        val passed = hammingDistance <= MAX_HAMMING_DISTANCE &&
                     pixelDiff <= MAX_PIXEL_DIFFERENCE

        return ComparisonResult(passed, diffPath, pixelDiff)
    }

    fun generateDiffImage(baseline: Path, current: Path): Path {
        // 生成差异可视化图
    }
}
```

#### 3.3.3 配置

```yaml
# visual-regression.yaml
thresholds:
  hammingDistance: 10
  pixelDifference: 0.05 # 5%
  blockDifference: 0.1 # 10%

baseline:
  updateOnFailure: false
  autoApprove: false

paths:
  baseline: test-baseline/screens
  current: build/screens/current
  diff: build/screens/diff
```

---

## 四、用户操作覆盖矩阵

| 功能模块         | 操作         | 测试状态 |
| ---------------- | ------------ | -------- |
| **Library**      |              |          |
|                  | 查看库       | ✅       |
|                  | 搜索漫画     | ✅       |
|                  | 排序         | ✅       |
|                  | 筛选         | ✅       |
|                  | 分类选择     | ✅       |
|                  | 多选漫画     | ✅       |
|                  | 批量设置分类 | ✅       |
|                  | 批量移除     | ✅       |
| **Manga Detail** |              |          |
|                  | 查看详情     | ✅       |
|                  | 章节列表     | ✅       |
|                  | 添加到库     | ✅       |
|                  | 下载         | ✅       |
|                  | 开始阅读     | ✅       |
|                  | 编辑笔记     | ✅       |
| **Reader**       |              |          |
|                  | 翻页         | ✅       |
|                  | 章节切换     | ✅       |
|                  | 阅读模式     | ✅       |
|                  | 缩放         | ✅       |
|                  | 旋转         | ✅       |
|                  | 书签         | ✅       |
|                  | 跳转         | ✅       |
| **Browse**       |              |          |
|                  | 源列表       | ✅       |
|                  | 源搜索       | ✅       |
|                  | 热门漫画     | ✅       |
|                  | 最新漫画     | ✅       |
|                  | 打开漫画     | ✅       |
| **History**      |              |          |
|                  | 查看历史     | ✅       |
|                  | 清除历史     | ✅       |
|                  | 恢复阅读     | ✅       |
| **Updates**      |              |          |
|                  | 查看更新     | ✅       |
|                  | 批量刷新     | ✅       |
| **Settings**     |              |          |
|                  | 主题切换     | ✅       |
|                  | 分类管理     | ✅       |
|                  | 扩展管理     | ✅       |
|                  | 备份还原     | ✅       |
|                  | 数据导出     | ✅       |
| **Extensions**   |              |          |
|                  | 安装扩展     | ✅       |
|                  | 卸载扩展     | ✅       |
|                  | 启用禁用     | ✅       |
|                  | 扩展设置     | ✅       |
| **Migration**    |              |          |
|                  | 搜索漫画     | ✅       |
|                  | 选择章节     | ✅       |
|                  | 执行迁移     | ✅       |

---

## 五、文件结构

### 5.1 应用端新增文件

```
app-desktop/src/main/kotlin/mihon/desktop/
├── Main.kt                          # 修改：支持测试启动参数
├── test/
│   ├── TestMode.kt                  # 测试模式入口
│   ├── TestArguments.kt             # 测试启动参数解析
│   ├── http/
│   │   ├── TestHttpServer.kt        # Ktor HTTP 测试服务器
│   │   ├── TestHttpRoutes.kt        # HTTP 路由定义
│   │   ├── NavigationController.kt  # 导航控制
│   │   ├── ActionController.kt      # 动作执行
│   │   ├── ScreenshotController.kt   # 截图控制
│   │   └── DataController.kt        # 测试数据控制
│   ├── jmx/
│   │   ├── DesktopTestMBean.kt      # MBean 接口
│   │   ├── DesktopTestMBeanImpl.kt  # MBean 实现
│   │   └── JmxServer.kt            # JMX 服务器
│   ├── testable/
│   │   ├── TestableScreenModel.kt   # 可测试 ScreenModel 接口
│   │   ├── TestableNavigator.kt     # 可测试 Navigator
│   │   └── TestDataManager.kt       # 测试数据管理
│   ├── screenshot/
│   │   └── ScreenshotService.kt     # 截图服务
│   └── state/
│       ├── ApplicationState.kt       # 应用状态
│       └── StatePublisher.kt        # 状态发布
```

### 5.2 测试框架新增文件

```
test-desktop/                              # 新建测试模块
├── build.gradle.kts
└── src/
    └── main/kotlin/
        └── mihon/test/desktop/
            ├── MihonDesktopTest.kt          # 主测试客户端
            ├── config/
            │   └── TestConfig.kt           # 测试配置
            ├── http/
            │   ├── TestHttpClient.kt       # HTTP 客户端
            │   └── TestApiClient.kt        # 类型安全 API 客户端
            ├── robot/
            │   ├── LibraryRobot.kt
            │   ├── MangaDetailRobot.kt
            │   ├── ReaderRobot.kt
            │   ├── BrowseRobot.kt
            │   ├── HistoryRobot.kt
            │   ├── UpdatesRobot.kt
            │   ├── SettingsRobot.kt
            │   ├── ExtensionRobot.kt
            │   └── MigrationRobot.kt
            ├── visual/
            │   ├── ScreenshotManager.kt
            │   ├── ScreenshotComparator.kt
            │   └── VisualRegressionExtension.kt  # JUnit5 Extension
            ├── data/
            │   ├── TestDataClient.kt
            │   ├── TestFixtures.kt
            │   └── DataDsl.kt
            └── annotation/
                ├── VisualTest.kt
                ├── Screenshot.kt
                └── TestScreen.kt
```

### 5.3 测试示例文件

```
app-desktop/src/test/kotlin/mihon/desktop/e2e/
├── LibraryE2eTest.kt
├── MangaDetailE2eTest.kt
├── ReaderE2eTest.kt
├── BrowseE2eTest.kt
├── SettingsE2eTest.kt
├── ExtensionE2eTest.kt
└── MigrationE2eTest.kt

app-desktop/src/test/kotlin/mihon/desktop/visual/
├── LibraryVisualTest.kt
├── MangaDetailVisualTest.kt
├── ReaderVisualTest.kt
└── SettingsVisualTest.kt
```

---

## 六、实现顺序 (Phase Plan)

### Phase 1: 基础框架 (预计 2 天)

**目标**: 建立测试基础设施

1. **1.1 测试模块创建**
   - 创建 `test-desktop` Gradle 模块
   - 配置依赖
   - 基础 DSL 实现

2. **1.2 启动参数解析**
   - 在 `Main.kt` 添加参数解析
   - 实现 `--test-mode` 标志

3. **1.3 HTTP Test Server 骨架**
   - 集成 Ktor
   - 基础路由框架
   - 状态查询 API

**交付物**:

- `test-desktop` 模块
- `GET /test/state` API

### Phase 2: 核心功能 (预计 3 天)

**目标**: 实现完整的测试控制能力

4. **2.1 导航控制**
   - 实现 `TestableNavigator`
   - 实现 `POST /test/navigate/{screen}`
   - Screen Robot 基类

5. **2.2 动作执行**
   - 实现 Action Controller
   - ScreenModel 测试接口
   - 动作 DSL

6. **2.3 数据控制**
   - 实现 TestDataManager
   - `POST /test/data/{entity}` API
   - 测试数据 Fixtures

7. **2.4 截图服务**
   - 实现 ScreenshotService
   - `POST /test/screenshot` API
   - 截图保存逻辑

**交付物**:

- 完整的 HTTP Test API
- 所有 Screen Robot
- 基础测试数据管理

### Phase 3: 视觉回归 (预计 2 天)

**目标**: 实现视觉回归测试

8. **3.1 截图对比**
   - 实现 ScreenshotComparator
   - 感知哈希算法
   - 差异图生成

9. **3.2 Visual Regression Extension**
   - JUnit 5 Extension
   - `@VisualTest` 注解
   - 自动化截图对比

10. **3.3 基线管理**
    - 基线存储
    - 自动批准流程
    - 报告生成

**交付物**:

- 视觉回归测试框架
- 基线管理系统
- 可运行的视觉测试

### Phase 4: 完整覆盖 (预计 3 天)

**目标**: 实现所有用户操作的自动化测试

11. **4.1 Library 测试**
    - 所有 Library 操作测试
    - 视觉回归测试
    - 边界情况

12. **4.2 Reader 测试**
    - 阅读器操作测试
    - 多种阅读模式
    - 视觉回归

13. **4.3 Browse/Search 测试**
    - 源操作测试
    - 搜索功能测试

14. **4.4 Settings/Extensions 测试**
    - 设置项测试
    - 扩展管理测试

15. **4.5 Integration 测试**
    - 多步骤用户流程
    - 数据一致性验证

**交付物**:

- 完整的 E2E 测试套件
- 视觉回归测试库
- CI 集成配置

---

## 七、技术规格

### 7.1 依赖版本

```kotlin
// Ktor
val ktor = "3.0.2"

// JMX
val jmx = "1.0.0"

// Image Processing
val JDK_IMAGE = "javax.imageio:imageio:1.3"

// Testing
val assertJ = "3.25.3"
val junit5 = "5.10.2"
```

### 7.2 性能要求

| 指标             | 目标    |
| ---------------- | ------- |
| 应用启动时间     | < 5s    |
| API 响应时间     | < 100ms |
| 截图捕获时间     | < 500ms |
| 截图对比时间     | < 1s    |
| 单个测试执行时间 | < 30s   |

### 7.3 稳定性要求

- 测试成功率: > 98%
- 视觉对比误报率: < 2%
- 稳定测试不依赖外部网络

---

## 八、CI/CD 集成

### 8.1 GitHub Actions 工作流

```yaml
name: Desktop E2E Tests

on:
  push:
    branches: [main, develop]
  pull_request:

jobs:
  e2e-tests:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4

      - name: Setup JDK
        uses: actions/setup-java@v5
        with:
          java-version: "21"

      - name: Build Desktop
        run: ./gradlew :app-desktop:installDist

      - name: Run E2E Tests
        run: ./gradlew :app-desktop:jvmTest --tests "*E2eTest"

      - name: Run Visual Tests
        run: ./gradlew :app-desktop:jvmTest --tests "*VisualTest"

      - name: Upload Screenshots
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: screenshots-on-failure
          path: build/screens/diff/

      - name: Update Baselines
        if: success() && github.event_name == 'push'
        run: ./scripts/update-baselines.sh
```

### 8.2 基线更新流程

```bash
#!/bin/bash
# scripts/update-baselines.sh

# 只在 main 分支成功时更新基线
if [ "$GITHUB_REF" != "refs/heads/main" ]; then
    echo "Skipping baseline update (not on main branch)"
    exit 0
fi

# 批准新的基准截图
./gradlew approveScreenshots

# 提交更改
git add test-baseline/
git commit -m "chore: update visual test baselines"
git push
```

---

## 九、已知限制与注意事项

### 9.1 平台限制

- macOS 专用功能 (AppleScript) 在 Linux/Windows 上不可用
- 截图可能在不同 DPI 设置下不同

### 9.2 测试隔离

- 每个测试类使用独立的测试数据
- 使用 `@TempDir` 进行临时文件管理
- 测试后自动清理

### 9.3 网络依赖

- 某些测试需要网络访问 (Browse 源测试)
- 提供 Mock 模式用于离线测试

---

## 十、后续优化方向

1. **并行测试**: 使用 TestNG/Gradle 并行执行
2. **分布式测试**: 多机器执行减少 CI 时间
3. **AI 辅助**: 使用 LLM 生成测试用例
4. **录制回放**: 录制用户操作为自动化测试

---

## 附录 A: API 完整列表

| 方法 | 路径                        | 描述              | 状态 |
| ---- | --------------------------- | ----------------- | ---- |
| GET  | `/test/state`               | 应用状态          | ✅   |
| GET  | `/test/screens`             | 可用屏幕列表      | ✅   |
| GET  | `/test/actions`             | 可用动作列表      | ✅   |
| GET  | `/test/history`             | 操作历史          | ✅   |
| POST | `/test/navigate/{screen}`   | 导航              | ✅   |
| POST | `/test/action/{action}`     | 执行动作          | ✅   |
| POST | `/test/screenshot`          | 截图              | ✅   |
| POST | `/test/screenshot/{screen}` | 指定屏幕截图      | ✅   |
| POST | `/test/screenshot/compare`  | 截图对比          | ✅   |
| POST | `/test/reset`               | 重置测试数据      | ✅   |
| POST | `/test/data/manga`          | Manga 数据操作    | ✅   |
| POST | `/test/data/chapter`        | Chapter 数据操作  | ✅   |
| POST | `/test/data/category`       | Category 数据操作 | ✅   |
| POST | `/test/data/setting`        | Setting 数据操作  | ✅   |
| GET  | `/test/jmx/state`           | JMX 状态          | ✅   |

---

**文档结束**
