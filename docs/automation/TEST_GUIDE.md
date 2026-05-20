# Desktop Automation Test Guide

## 目录

- [快速开始](#快速开始)
- [测试模式](#测试模式)
- [HTTP API](#http-api)
- [Robot 模式](#robot-模式)
- [视觉回归测试](#视觉回归测试)
- [场景测试示例](#场景测试示例)
- [测试数据管理](#测试数据管理)
- [调试技巧](#调试技巧)
- [故障排除](#故障排除)

---

## 快速开始

### 1. 构建测试应用

```bash
# 构建带测试功能的桌面应用
./scripts/build-desktop.sh

# 应用将部署到 /Applications/Mihon Desktop.app
```

### 2. 运行测试

```bash
# 运行 test-desktop 模块的冒烟测试
./gradlew :test-desktop:test

# 运行单元测试
./gradlew :app-desktop:jvmTest

# 运行冒烟测试脚本（需要先启动应用）
./scripts/desktop-smoke-test.sh
```

### 3. 测试场景覆盖

| 场景       | Robot              | 关键操作                    |
| ---------- | ------------------ | --------------------------- |
| 漫画库管理 | `LibraryRobot`     | 搜索、筛选、排序、选择漫画  |
| 漫画详情   | `MangaDetailRobot` | 添加/移除库、下载、阅读章节 |
| 阅读器     | `ReaderRobot`      | 翻页、章节切换、模式切换    |
| 下载管理   | `DownloadsRobot`   | 暂停/继续、取消、重试、排序 |
| 更新管理   | `UpdatesRobot`     | 刷新、筛选、标记已读、下载  |
| 历史记录   | `HistoryRobot`     | 搜索、删除、继续阅读        |
| 设置       | `SettingsRobot`    | 修改设置、重置              |
| 浏览       | `BrowseRobot`      | 搜索、选择漫画              |

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

| 参数               | 说明          | 默认值             |
| ------------------ | ------------- | ------------------ |
| `--test-mode`      | 启用测试模式  | -                  |
| `--test-http-port` | HTTP API 端口 | 8080               |
| `--headless`       | 无 UI 模式    | false              |
| `--screenshot-dir` | 截图保存目录  | /tmp/mihon-screens |

---

## HTTP API

测试模式启动后，可以通过 HTTP API 控制应用。

### 基础 URL

```
http://localhost:8080/test
```

### 状态查询

```bash
# 获取完整应用状态
curl http://localhost:8080/test/state
```

响应包含：

- `currentScreen` - 当前屏幕
- `downloadQueueSize` - 下载队列大小
- `downloadsPaused` - 下载是否暂停
- `updateCount` - 更新数量
- `historyCount` - 历史记录数量

### 导航

```bash
# 导航到指定屏幕
curl -X POST http://localhost:8080/test/navigate/LibraryTab
curl -X POST http://localhost:8080/test/navigate/UpdatesTab
curl -X POST http://localhost:8080/test/navigate/DownloadsScreen
```

### 动作执行

```bash
# 库搜索
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

# 暂停下载
curl -X POST http://localhost:8080/test/action/downloads_pause_all

# 刷新更新
curl -X POST http://localhost:8080/test/action/updates_refresh

# 搜索历史
curl -X POST http://localhost:8080/test/action/history_search \
  -H "Content-Type: application/json" \
  -d '{"query":"manga"}'
```

### 截图

```bash
curl -X POST http://localhost:8080/test/screenshot \
  -H "Content-Type: application/json" \
  -d '{"name":"test_screenshot"}'
```

---

## Robot 模式

使用 Kotlin 测试客户端更方便地编写测试。

### 基本用法

```kotlin
import mihon.test.desktop.DesktopTestClient
import mihon.test.desktop.robot.*

fun main() {
    DesktopTestClient("localhost", 8080).use { client ->
        // 使用 Robot 进行操作
        client.library
            .open()
            .search("One Piece")
            .filterUnread()
            .sortByTitle()

        // 验证状态
        val state = client.getState()
        println("Current screen: ${state.currentScreen}")
    }
}
```

---

### LibraryRobot - 漫画库

```kotlin
client.library
    .open()                           // 打开库页面
    .search("manga title")            // 搜索
    .clearSearch()                    // 清除搜索
    .filterUnread()                   // 筛选未读
    .filterStarted()                  // 筛选已开始
    .filterCompleted()                // 筛选已完成
    .clearFilters()                   // 清除筛选
    .sortByTitle()                    // 按标题排序
    .sortByLastRead()                 // 按最近阅读排序
    .sortByDateAdded()                // 按添加日期排序
    .sortByUnreadCount()              // 按未读数量排序
    .selectCategory(0)                // 选择分类
    .selectManga(0)                   // 选择漫画 → MangaDetailRobot
```

### MangaDetailRobot - 漫画详情

```kotlin
client.library
    .open()
    .selectManga(0)                  // 选择漫画
    .readChapter(0)                  // 阅读第一章 → ReaderRobot
    .addToLibrary()                  // 添加到库
    .removeFromLibrary()             // 从库移除
    .download()                       // 下载
    .capture("manga_detail")         // 截图
```

### ReaderRobot - 阅读器

```kotlin
client.reader
    .nextPage()                      // 下一页
    .prevPage()                      // 上一页
    .nextChapter()                   // 下一章
    .prevChapter()                   // 上一章
    .setMode("left_to_right")        // 设置阅读模式
    .zoomIn()                        // 放大
    .zoomOut()                       // 缩小
    .capture("reader")               // 截图
```

### DownloadsRobot - 下载管理

```kotlin
client.downloads
    .open()                          // 打开下载页面
    .pauseAll()                       // 暂停所有
    .resumeAll()                      // 继续所有
    .cancelDownload(0)               // 取消指定下载
    .cancelAll()                      // 取消所有
    .clearErrors()                    // 清除错误
    .retryErrors()                    // 重试错误
    .reorderDownload(0, 2)          // 重新排序
    .sortQueue("date_added")         // 排序队列
    .reverseQueue()                   // 反转队列
    .capture("downloads")            // 截图
```

### UpdatesRobot - 更新管理

```kotlin
client.updates
    .open()                          // 打开更新页面
    .refresh()                       // 刷新
    .markAllAsRead()                 // 全部标记已读
    .filterUnread()                  // 筛选未读
    .filterDownloaded()              // 筛选已下载
    .filterStarted()                // 筛选已开始
    .filterBookmarked()             // 筛选已书签
    .clearFilters()                  // 清除筛选
    .openUpcoming()                  // 打开日历
    .readUpdate(0)                  // 阅读更新 → ReaderRobot
    .downloadUpdate(0)              // 下载更新
    .markAsRead(0)                  // 标记已读
    .capture("updates")             // 截图
```

### HistoryRobot - 历史记录

```kotlin
client.history
    .open()                          // 打开历史页面
    .search("query")                  // 搜索
    .clearSearch()                    // 清除搜索
    .removeEntry(0)                  // 删除条目
    .clearAll()                       // 清除全部
    .selectEntry(0)                  // 选择条目 → ReaderRobot
    .capture("history")             // 截图
```

### SettingsRobot - 设置

```kotlin
client.settings
    .open()                          // 打开设置
    .set("theme", "dark")           // 设置值
    .reset()                         // 重置
    .capture("settings")            // 截图
```

### BrowseRobot - 浏览

```kotlin
client.browse
    .open()                          // 打开浏览
    .search("jujutsu kaisen")       // 搜索
    .selectManga(0)                  // 选择漫画 → MangaDetailRobot
    .capture("browse")             // 截图
```

### MoreRobot - 更多

```kotlin
client.more
    .open()                          // 打开更多页面
    .openSettings()                  // 打开设置 → SettingsRobot
    .openExtensions()               // 打开扩展 → ExtensionsRobot
    .openMigration()                // 打开迁移 → MigrationRobot
    .openAbout()                    // 打开关于 → AboutRobot
    .openBackup()                   // 打开备份 → BackupRobot
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

## 场景测试示例

### 场景 1: 漫画库管理流程

```kotlin
@Test
fun `library management workflow`() {
    client.library
        .open()
        .search("One Piece")
        .filterUnread()
        .sortByTitle()

    // 验证搜索结果
    val state = client.getState()
    assertEquals("LibraryTab", state.currentScreen)
}
```

### 场景 2: 阅读并保存进度

```kotlin
@Test
fun `read manga and verify progress`() {
    // 从库中选择漫画并阅读
    val readerRobot = client.library
        .open()
        .selectManga(0)
        .readChapter(0)

    // 翻到第10页
    repeat(9) { readerRobot.nextPage() }

    // 切换到下一章
    readerRobot.nextChapter()

    // 验证阅读器仍在工作
    assertEquals("ReaderScreen", client.getState().currentScreen)
}
```

### 场景 3: 下载管理

```kotlin
@Test
fun `download management workflow`() {
    client.downloads
        .open()
        .pauseAll()

    assertTrue(client.downloads.isPaused())

    client.downloads
        .resumeAll()
        .cancelAll()

    assertEquals(0, client.downloads.getQueueSize())
}
```

### 场景 4: 更新检查

```kotlin
@Test
fun `updates workflow`() {
    client.updates
        .open()
        .refresh()
        .filterUnread()

    // 标记全部已读
    client.updates.markAllAsRead()

    // 验证没有未读更新
    assertFalse(client.updates.hasUnread())
}
```

### 场景 5: 历史记录管理

```kotlin
@Test
fun `history workflow`() {
    client.history
        .open()
        .search("test manga")
        .selectEntry(0)  // 继续阅读

    // 验证进入阅读器
    assertEquals("ReaderScreen", client.getState().currentScreen)
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

### 验证状态变化

```bash
# 连续查询状态
watch -n 1 'curl -s http://localhost:8080/test/state | jq .'
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

### Robot 测试失败

确保：

1. 应用在测试模式下运行
2. HTTP 服务器已启动
3. 端口 8080 可用

---

## 相关文档

- [API_REFERENCE.md](./API_REFERENCE.md) - HTTP API 端点完整文档
- [TASK_TRACKER.md](./TASK_TRACKER.md) - 开发进度追踪
- [测试模块源码](../../test-desktop/) - 测试客户端库源码
- [测试基础设施源码](../../app-desktop/src/main/kotlin/mihon/desktop/test/) - 应用内测试支持源码
