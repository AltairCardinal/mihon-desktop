# Desktop Automation Test Guide

## 目录
- [快速开始](#快速开始)
- [测试模式](#测试模式)
- [HTTP API](#http-api)
- [Robot 模式](#robot-模式)
- [视觉回归测试](#视觉回归测试)
- [示例测试](#示例测试)

---

## 快速开始

### 1. 构建测试应用

```bash
# 构建带测试功能的桌面应用
./scripts/build-desktop.sh

# 应用将部署到 /Applications/Mihon Desktop.app
```

### 2. 运行冒烟测试

```bash
# 运行所有冒烟测试
./scripts/desktop-smoke-test.sh

# 仅编译（不运行）
./gradlew :app-desktop:compileKotlinJvm

# 运行单元测试
./gradlew :app-desktop:jvmTest
```

### 3. 运行测试模块测试

```bash
./gradlew :test-desktop:test
```

---

## 测试模式

### 启动参数

启动桌面应用时使用以下参数：

```bash
"/Applications/Mihon Desktop.app" \
  --test-mode \
  --test-http-port=8080 \
  --headless \
  --screenshot-dir=/tmp/mihon-screens
```

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `--test-mode` | 启用测试模式 | - |
| `--test-http-port` | HTTP API 端口 | 8080 |
| `--headless` | 无 UI 模式 | false |
| `--screenshot-dir` | 截图保存目录 | /tmp/mihon-screens |

### Kotlin 代码中启用

```kotlin
fun main(args: Array<String>) {
    val testArgs = TestArguments.parse(args)
    
    if (testArgs.testMode) {
        TestMode.start(testArgs)
    }
    
    // 正常启动应用...
}
```

---

## HTTP API

测试模式启动后，可以通过 HTTP API 控制应用。

### 基础 URL

```
http://localhost:8080/test/
```

### API 端点

#### 健康检查

```bash
curl http://localhost:8080/test/health
```

响应：
```json
{"status": "ok", "timestamp": "2026-05-19T12:00:00Z"}
```

#### 获取应用状态

```bash
curl http://localhost:8080/test/state
```

响应：
```json
{
  "currentScreen": "LibraryTab",
  "isLoading": false,
  "notifications": [],
  "screens": ["LibraryTab", "UpdatesTab", ...],
  "actions": ["search", "filter", "sort", ...],
  "testMode": true
}
```

#### 获取可用屏幕列表

```bash
curl http://localhost:8080/test/screens
```

响应：
```json
[
  {"id": "LibraryTab", "name": "LibraryTab"},
  {"id": "UpdatesTab", "name": "UpdatesTab"},
  {"id": "BrowseTab", "name": "BrowseTab"}
]
```

#### 导航到屏幕

```bash
curl -X POST http://localhost:8080/test/navigate/LibraryTab
```

响应：
```json
{"success": true, "newScreen": "LibraryTab", "timestamp": "..."}
```

#### 执行动作

```bash
# 搜索
curl -X POST http://localhost:8080/test/action/search \
  -H "Content-Type: application/json" \
  -d '{"query":"One Piece"}'

# 筛选
curl -X POST http://localhost:8080/test/action/filter \
  -H "Content-Type: application/json" \
  -d '{"type":"unread"}'

# 排序
curl -X POST http://localhost:8080/test/action/sort \
  -H "Content-Type: application/json" \
  -d '{"mode":"title"}'
```

响应：
```json
{"success": true, "action": "search", "timestamp": "..."}
```

#### 截图

```bash
curl -X POST http://localhost:8080/test/screenshot \
  -H "Content-Type: application/json" \
  -d '{"name":"library_before_action"}'
```

响应：
```json
{"success": true, "path": "/tmp/mihon-screens/library_before_action-20260519-120000-123.png"}
```

#### 重置测试状态

```bash
curl -X POST http://localhost:8080/test/reset
```

#### 获取动作历史

```bash
curl http://localhost:8080/test/history
```

---

## Robot 模式

使用 Kotlin 测试客户端更方便地编写测试。

### 添加依赖

```kotlin
// test-desktop 模块已包含所有依赖
dependencies {
    implementation(project(":test-desktop"))
}
```

### 基本用法

```kotlin
import mihon.test.desktop.DesktopTestClient
import mihon.test.desktop.robot.LibraryRobot

fun main() {
    DesktopTestClient("localhost", 8080).use { client ->
        // 启动应用（可选，如已手动启动）
        // client.start()
        
        // 使用 Robot 进行操作
        client.library
            .open()
            .search("One Piece")
            .filterUnread()
            .sortByTitle()
        
        // 验证状态
        val state = client.getState()
        assert(state.currentScreen == "LibraryTab")
    }
}
```

### LibraryRobot

```kotlin
client.library
    .open()                           // 打开库页面
    .search("manga title")            // 搜索
    .clearSearch()                    // 清除搜索
    .filterUnread()                   // 筛选未读
    .filterStarted()                  // 筛选已开始
    .filterCompleted()                // 筛选已完成
    .clearFilters()                   // 清除筛选
    .sortByTitle()                   // 按标题排序
    .sortByLastRead()                // 按最近阅读排序
    .sortByDateAdded()               // 按添加日期排序
    .selectCategory(0)               // 选择分类
    .selectManga(0)                  // 选择漫画（返回 MangaDetailRobot）
```

### MangaDetailRobot

```kotlin
client.library
    .open()
    .selectManga(0)                  // 选择漫画
    .readChapter(0)                  // 阅读第一章（返回 ReaderRobot）
    .addToLibrary()                  // 添加到库
    .removeFromLibrary()             // 从库移除
    .download()                      // 下载
    .capture("manga_detail")        // 截图
```

### ReaderRobot

```kotlin
client.reader
    .nextPage()                      // 下一页
    .prevPage()                     //上一页
    .nextChapter()                  // 下一章
    .prevChapter()                  // 上一章
    .setMode("left_to_right")       // 设置阅读模式
    .zoomIn()                       // 放大
    .zoomOut()                      // 缩小
    .capture("reader")              // 截图
```

### SettingsRobot

```kotlin
client.settings
    .open()                          // 打开设置
    .set("theme", "dark")           // 设置值
    .reset()                         // 重置
    .capture("settings")             // 截图
```

### BrowseRobot

```kotlin
client.browse
    .open()                          // 打开浏览
    .search("jujutsu kaisen")        // 搜索
    .selectManga(0)                 // 选择漫画
```

---

## 视觉回归测试

### 基本用法

```kotlin
val client = DesktopTestClient("localhost", 8080)

client.visual.setBaselineDir(Path.of("test-baseline/screens"))
client.visual.setDiffDir(Path.of("build/screens/diff"))

// 导航并截图
client.library.open()
val screenshot = client.screenshot("library-main")

// 比较与基线
val result = client.visual.assertMatchesBaseline("library-main")

assert(result) { "Visual regression detected!" }
```

### 配置阈值

```kotlin
client.visual.hammingDistanceThreshold = 10  // 感知哈希阈值
client.visual.pixelDifferenceThreshold = 0.05  // 像素差异阈值 (5%)
```

---

## 示例测试

### JUnit 5 测试示例

```kotlin
package mihon.test.desktop

import mihon.test.desktop.robot.LibraryRobot
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LibraryE2ETest {
    
    private lateinit var client: DesktopTestClient
    
    @BeforeAll
    fun setup() {
        client = DesktopTestClient("localhost", 8080)
        client.start(headless = false)
    }
    
    @AfterAll
    fun teardown() {
        client.close()
    }
    
    @Test
    fun `library search filters results`() {
        // 打开库
        client.library.open()
        
        // 搜索
        client.library.search("One Piece")
        
        // 验证
        val state = client.getState()
        assert(state.currentScreen == "LibraryTab")
    }
    
    @Test
    fun `filter unread shows only unread manga`() {
        client.library
            .open()
            .filterUnread()
        
        // 截图对比
        val result = client.visual.assertMatchesBaseline("library-unread-filter")
        assert(result)
    }
    
    @Test
    fun `navigation to detail screen works`() {
        val detailRobot = client.library
            .open()
            .selectManga(0)
        
        assert(detailRobot != null)
    }
}
```

### 自定义测试注解

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class VisualTest(
    val name: String = "",
    val tolerance: Double = 0.05
)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)  
annotation class Screenshot(val name: String)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TestScreen(val screen: String)
```

使用注解：

```kotlin
@TestScreen("LibraryTab")
@VisualTest(name = "library_sorted")
@Test
fun `sort by title updates display`() {
    client.library
        .open()
        .sortByTitle()
        .capture("library_sorted")
}
```

---

## 测试数据管理

### 使用 TestDataClient

```kotlin
// 添加测试漫画
client.data.addManga(TestManga(
    title = "Test Manga",
    url = "https://example.com/manga/1",
    author = "Test Author"
))

// 创建分类
client.data.createCategory("Action")

// 设置配置
client.data.setSetting("theme", "dark")

// 清除所有测试数据
client.data.clearAll()

// 设置标准测试数据
client.data.setupFixtures()
```

---

## 调试技巧

### 查看截图

```bash
# 截图保存在配置目录
ls /tmp/mihon-screens/

# 实时查看最新截图
ls -lt /tmp/mihon-screens/ | head
```

### 查看动作历史

```bash
curl http://localhost:8080/test/history | jq
```

### 检查应用日志

```bash
# 应用日志通常在
~/Library/Logs/Mihon Desktop/
```

---

## 故障排除

### HTTP 服务器未启动

```bash
# 检查端口是否被占用
lsof -i :8080

# 检查应用是否在测试模式启动
curl http://localhost:8080/test/health
```

### 截图失败

```bash
# 确保截图目录存在且可写
mkdir -p /tmp/mihon-screens
chmod 755 /tmp/mihon-screens
```

### 测试超时

```kotlin
// 增加超时时间
client.http.config {
    install(HttpTimeout) {
        requestTimeoutMillis = 60000
    }
}
```

---

## 相关文档

- [TASK_TRACKER.md](./TASK_TRACKER.md) - 开发进度追踪
- [测试模块源码](../../test-desktop/) - 测试客户端库源码
- [测试基础设施源码](../../app-desktop/src/main/kotlin/mihon/desktop/test/) - 应用内测试支持源码
