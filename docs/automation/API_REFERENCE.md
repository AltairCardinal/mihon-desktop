# HTTP API Reference

## Base URL

```
http://localhost:8080/test
```

---

## Endpoints

### `GET /test/health`

健康检查。

**Response:**

```json
{
  "status": "ok",
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

---

### `GET /test/state`

获取当前应用状态。

**Response:**

```json
{
  "currentScreen": "LibraryTab",
  "isLoading": false,
  "notifications": [],
  "screens": ["LibraryTab", "UpdatesTab", ...],
  "actions": ["search", "filter", "sort", ...],
  "testMode": true,
  "downloadQueueSize": 5,
  "downloadsPaused": false,
  "updateCount": 10,
  "hasUnreadUpdates": true,
  "historyCount": 25,
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

**State Fields:**
| Field | Type | Description |
|-------|------|-------------|
| `currentScreen` | String | 当前屏幕名称 |
| `isLoading` | Boolean | 是否正在加载 |
| `testMode` | Boolean | 是否为测试模式 |
| `downloadQueueSize` | Int | 下载队列大小 |
| `downloadsPaused` | Boolean | 下载是否暂停 |
| `updateCount` | Int | 更新数量 |
| `hasUnreadUpdates` | Boolean | 是否有未读更新 |
| `historyCount` | Int | 历史记录数量 |

---

### `GET /test/screens`

获取可用屏幕列表。

**Response:**

```json
[
  { "id": "LibraryTab", "name": "LibraryTab" },
  { "id": "UpdatesTab", "name": "UpdatesTab" },
  { "id": "BrowseTab", "name": "BrowseTab" },
  { "id": "HistoryTab", "name": "HistoryTab" },
  { "id": "MoreTab", "name": "MoreTab" },
  { "id": "SettingsScreen", "name": "SettingsScreen" },
  { "id": "MigrationSearchScreen", "name": "MigrationSearchScreen" },
  { "id": "ExtensionListScreen", "name": "ExtensionListScreen" },
  { "id": "HomeScreen", "name": "HomeScreen" }
]
```

---

### `POST /test/navigate/{screen}`

导航到指定屏幕。

**Parameters:**

- `screen` (path): 屏幕名称

**Response:**

```json
{
  "success": true,
  "newScreen": "LibraryTab",
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

**Available Screens:**

- `LibraryTab` - 漫画库
- `UpdatesTab` - 更新
- `HistoryTab` - 历史
- `BrowseTab` - 浏览
- `MoreTab` - 更多
- `SettingsScreen` - 设置
- `MigrationSearchScreen` - 迁移
- `ExtensionListScreen` - 扩展
- `HomeScreen` - 主页

---

### `POST /test/action/{action}`

执行指定动作。

**Parameters:**

- `action` (path): 动作名称
- `body` (JSON): 动作参数

#### Library Actions

```json
// 搜索
{"query": "search text"}

// 筛选
{"type": "unread|started|completed|clear"}

// 排序
{"mode": "title|lastRead|dateAdded|unreadCount"}

// 选择漫画或分类
{"index": 0}
{"type": "category|chapter", "index": 0}
```

#### Reader Actions

```json
// 翻页
{"action": "reader_next_page"}
{"action": "reader_prev_page"}

// 章节切换
{"action": "reader_next_chapter"}
{"action": "reader_prev_chapter"}

// 阅读模式
{"action": "reader_mode", "mode": "left_to_right|right_to_left|vertical|webtoon"}

// 缩放
{"action": "reader_zoom", "delta": 0.1}
```

#### Download Actions

```json
// 暂停/继续
{"action": "downloads_pause_all"}
{"action": "downloads_resume_all"}

// 取消
{"action": "downloads_cancel", "index": 0}
{"action": "downloads_cancel_all"}

// 错误处理
{"action": "downloads_clear_errors"}
{"action": "downloads_retry_errors"}

// 队列管理
{"action": "downloads_reorder", "from": 0, "to": 2}
{"action": "downloads_sort", "by": "date_added"}
{"action": "downloads_reverse"}
```

#### Updates Actions

```json
// 刷新
{"action": "updates_refresh"}

// 标记已读
{"action": "updates_mark_all_read"}

// 筛选
{"action": "updates_filter", "type": "unread|downloaded|started|bookmarked", "enabled": "true|false"}
{"action": "updates_clear_filters"}

// 选择和操作
{"action": "updates_select", "index": 0}
{"action": "updates_download", "index": 0}
{"action": "updates_mark_read", "index": 0}

// 日历
{"action": "updates_open_upcoming"}
```

#### History Actions

```json
// 搜索
{"action": "history_search", "query": "text"}

// 清除
{"action": "history_clear_all"}

// 操作
{"action": "history_remove", "index": 0}
{"action": "history_select", "index": 0}
```

#### Manga Detail Actions

```json
{"action": "addToLibrary"}
{"action": "removeFromLibrary"}
{"action": "download"}
```

#### Extension Actions

```json
{"action": "extension_select", "index": 0}
{"action": "extension_enable", "index": 0}
{"action": "extension_disable", "index": 0}
{"action": "extension_update", "index": 0}
{"action": "extension_update_all"}
{"action": "extension_search", "query": "text"}
```

#### Migration Actions

```json
{"action": "migration_search", "query": "text"}
{"action": "migration_select", "index": 0}
```

#### Backup Actions

```json
{"action": "backup_create"}
{"action": "backup_restore"}
```

**Response:**

```json
{
  "success": true,
  "action": "search",
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

---

## Reader Endpoints

### `GET /test/reader/state`

获取阅读器当前状态。

**Response:**

```json
{
  "isOpen": true,
  "currentPage": 5,
  "totalPages": 24,
  "currentChapterId": 12345,
  "isWebtoon": false,
  "mangaTitle": "One Piece",
  "chapterTitle": "Chapter 1000: The Dawn of the New Era",
  "hasNextChapter": true,
  "hasPrevChapter": true,
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

**State Fields:**
| Field | Type | Description |
|-------|------|-------------|
| `isOpen` | Boolean | 阅读器是否已打开 |
| `currentPage` | Int | 当前页码（0-indexed） |
| `totalPages` | Int | 总页数 |
| `currentChapterId` | Long | 当前章节 ID |
| `isWebtoon` | Boolean | 是否为 Webtoon 模式 |
| `mangaTitle` | String | 漫画标题 |
| `chapterTitle` | String | 章节标题 |
| `hasNextChapter` | Boolean | 是否有下一章节 |
| `hasPrevChapter` | Boolean | 是否有上一章节 |

---

### `POST /test/reader/next_page`

翻到下一页。

**Response:**

```json
{
  "success": true,
  "action": "next_page",
  "page": 6,
  "totalPages": 24,
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

**Error (Already at last page):**

```json
{
  "success": false,
  "action": "next_page",
  "error": "Already at last page",
  "page": 23,
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

---

### `POST /test/reader/prev_page`

翻到上一页。

**Response:**

```json
{
  "success": true,
  "action": "prev_page",
  "page": 4,
  "totalPages": 24,
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

---

### `POST /test/reader/go_to_page`

跳转到指定页码。

**Body:**

```json
{ "page": 10 }
```

**Response:**

```json
{
  "success": true,
  "action": "go_to_page",
  "page": 10,
  "totalPages": 24,
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

**Error (Invalid page):**

```json
{
  "success": false,
  "action": "go_to_page",
  "error": "Invalid page number",
  "requestedPage": 30,
  "validRange": "0-23",
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

---

### `POST /test/reader/next_chapter`

切换到下一章节。

**Response:**

```json
{
  "success": true,
  "action": "next_chapter",
  "hasNext": true,
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

---

### `POST /test/reader/prev_chapter`

切换到上一章节。

**Response:**

```json
{
  "success": true,
  "action": "prev_chapter",
  "hasPrev": true,
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

---

### `POST /test/reader/close`

关闭阅读器，返回上一屏幕。

**Response:**

```json
{
  "success": true,
  "action": "close_reader",
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

---

### `POST /test/screenshot`

捕获截图。

**Body:**

```json
{ "name": "screenshot_name" }
```

**Response:**

```json
{
  "success": true,
  "path": "/tmp/mihon-screens/screenshot_name-20260519-120000-123.png",
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

---

### `POST /test/reset`

重置测试状态。

**Response:**

```json
{
  "success": true,
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

---

### `GET /test/history`

获取动作历史。

**Response:**

```json
[
  {
    "action": "navigate",
    "params": { "screen": "LibraryTab" },
    "timestamp": "2026-05-19T12:00:00.000Z"
  },
  {
    "action": "search",
    "params": { "query": "One Piece" },
    "timestamp": "2026-05-19T12:00:01.000Z"
  }
]
```

---

## Error Responses

### 400 Bad Request

```json
{
  "success": false,
  "error": "Invalid parameters"
}
```

### 500 Internal Server Error

```json
{
  "success": false,
  "error": "Screenshot capture failed"
}
```

---

## cURL Examples

```bash
# 健康检查
curl http://localhost:8080/test/health

# 获取状态（包含下载、更新、历史状态）
curl http://localhost:8080/test/state

# 导航到库
curl -X POST http://localhost:8080/test/navigate/LibraryTab

# 搜索
curl -X POST http://localhost:8080/test/action/search \
  -H "Content-Type: application/json" \
  -d '{"query":"Naruto"}'

# 筛选未读
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
  -d '{"query":"One Piece"}'

# 截图
curl -X POST http://localhost:8080/test/screenshot \
  -H "Content-Type: application/json" \
  -d '{"name":"test_screenshot"}'

# 重置
curl -X POST http://localhost:8080/test/reset
```

---

## Robot 模式 API

Robot 模式提供了更高级别的测试抽象：

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
    .sortByTitle()                    // 按标题排序
    .sortByLastRead()                 // 按最近阅读排序
    .sortByDateAdded()                // 按添加日期排序
    .sortByUnreadCount()              // 按未读数量排序
    .selectCategory(0)                // 选择分类
    .selectManga(0)                  // 选择漫画 → MangaDetailRobot
```

### DownloadsRobot

```kotlin
client.downloads
    .open()                           // 打开下载页面
    .pauseAll()                       // 暂停所有
    .resumeAll()                      // 继续所有
    .cancelDownload(0)               // 取消指定下载
    .cancelAll()                      // 取消所有
    .clearErrors()                    // 清除错误
    .retryErrors()                    // 重试错误
    .reorderDownload(0, 2)          // 重新排序
    .sortQueue("date_added")          // 排序队列
    .reverseQueue()                  // 反转队列
```

### UpdatesRobot

```kotlin
client.updates
    .open()                           // 打开更新页面
    .refresh()                        // 刷新
    .markAllAsRead()                 // 全部标记已读
    .filterUnread()                  // 筛选未读
    .filterDownloaded()              // 筛选已下载
    .filterStarted()                 // 筛选已开始
    .filterBookmarked()              // 筛选已书签
    .clearFilters()                  // 清除筛选
    .openUpcoming()                  // 打开日历
    .readUpdate(0)                  // 阅读更新 → ReaderRobot
    .downloadUpdate(0)              // 下载更新
    .markAsRead(0)                 // 标记已读
```

### HistoryRobot

```kotlin
client.history
    .open()                           // 打开历史页面
    .search("query")                  // 搜索
    .clearSearch()                    // 清除搜索
    .removeEntry(0)                  // 删除条目
    .clearAll()                       // 清除全部
    .selectEntry(0)                  // 选择条目 → ReaderRobot
```

### MoreRobot

```kotlin
client.more
    .open()                           // 打开更多页面
    .openSettings()                  // 打开设置 → SettingsRobot
    .openExtensions()                // 打开扩展 → ExtensionsRobot
    .openMigration()                 // 打开迁移 → MigrationRobot
    .openAbout()                    // 打开关于 → AboutRobot
    .openBackup()                   // 打开备份 → BackupRobot
```
