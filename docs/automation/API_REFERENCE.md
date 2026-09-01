# Desktop Test HTTP API

基础地址：

```text
http://localhost:8080/test
```

所有响应均为 JSON。请求 body 使用标准 JSON 解析，字符串可包含 URL、冒号和逗号。

## Health

### `GET /health`

```json
{
  "status": "ok",
  "timestamp": "2026-06-01T12:00:00Z"
}
```

## State

### `GET /state`

```json
{
  "currentScreen": "LibraryTab",
  "isLoading": false,
  "notifications": [],
  "screens": [],
  "actions": [],
  "testMode": true,
  "downloadQueueSize": 0,
  "downloadsPaused": false,
  "updateCount": 0,
  "hasUnreadUpdates": false,
  "historyCount": 0,
  "timestamp": "2026-06-01T12:00:00Z"
}
```

## Navigation

### `GET /screens`

返回可导航的 tab 与嵌套 screen 列表。

### `POST /navigate/{screen}`

支持：

- `LibraryTab`
- `UpdatesTab`
- `HistoryTab`
- `BrowseTab`
- `MoreTab`
- `SettingsScreen`
- `GeneralSettingsScreen`
- `DownloadSettingsScreen`
- `BackupSettingsScreen`
- `ExtensionListScreen`
- `MigrationSearchScreen`

成功响应：

```json
{
  "success": true,
  "newScreen": "LibraryTab",
  "type": "tab",
  "timestamp": "2026-06-01T12:00:00Z"
}
```

失败响应：

```json
{
  "success": false,
  "newScreen": "UnknownScreen",
  "error": "Unknown screen: UnknownScreen",
  "timestamp": "2026-06-01T12:00:00Z"
}
```

## Actions

### `POST /action/{action}`

通用动作入口。常见动作：

- `search`
- `filter`
- `sort`
- `open_manga_detail`
- `read_chapter`
- `downloads_pause_all`
- `downloads_resume_all`
- `updates_refresh`
- `updates_mark_all_read`
- `history_search`
- `setting_change`

示例：

```bash
curl -X POST http://localhost:8080/test/action/open_manga_detail \
  -H "Content-Type: application/json" \
  -d '{"mangaId":42}'
```

`read_chapter` 支持通过 production Reader route 创建确定性 fixture：

```json
{
  "readerFixture": "partial_download",
  "mangaId": 42,
  "chapterId": 4201,
  "pageCount": 12,
  "partialPageCount": 5,
  "offline": false,
  "width": 1200,
  "height": 1800,
  "format": "JPEG"
}
```

`readerFixture` 可取 `downloaded_directory`、`downloaded_cbz`、`local_archive`、`online` 或
`partial_download`。`partialPageCount` 仅用于 partial fixture，必须在 `1 until pageCount`；`offline=true`
也只用于 partial fixture，并且会在 production 下载器已提交指定页数后才切断 fixture 图片响应，因此不会把准备失败误当作
Reader 离线行为。partial fixture 使用独占 chapter identity；`/test/reset` 和 Reader 测试控制器关闭时只取消自己创建且
identity 仍匹配的队列项。

## Reader

### `GET /reader/state`

```json
{
  "isOpen": true,
  "currentPage": 0,
  "totalPages": 20,
  "currentChapterId": 42,
  "isWebtoon": false,
  "mangaTitle": "Manga",
  "chapterTitle": "Chapter 1",
  "hasNextChapter": true,
  "hasPrevChapter": false,
  "timestamp": "2026-06-01T12:00:00Z"
}
```

partial fixture 还返回以下页级观测字段：

- `route`、`currentPageIndex`、`snapshotGeneration`；
- 当前页的 `localHits`、`networkFallbacks`、`partialPageProbes`、`partialPageOpens`、
  `partialPageCopies`、`imageRequests`；
- 整个场景的 `scenarioPartialPageCopies`、`scenarioImageRequests`；
- `downloadIoLockViolations`，必须始终为 `0`。

这些计数按 Reader page identity 归属；附近页预取不会污染当前页的“本地命中/网络回退”断言。已提交页应为一次本地
probe/open/copy 且图片网络为 0；缺失页才允许一个物理图片请求。`snapshotGeneration` 是正整数，仅用于确认 Reader 与
当前下载 attempt 对齐，不是跨场景稳定 ID。

### `POST /reader/next_page`

当前页小于最后一页时递增页码；已在最后一页时返回 `success:false`。

### `POST /reader/prev_page`

当前页大于 0 时递减页码；已在第一页时返回 `success:false`。

### `POST /reader/go_to_page`

```json
{"page": 5}
```

页码为 0-indexed。越界返回 `400 Bad Request`。

### `POST /reader/close`

关闭阅读器并触发 UI 导航返回。

## Utilities

### `POST /reset`

清空测试状态、导航 pending 状态和 reader/download/update/history 状态。

### `GET /history`

返回测试动作历史。

`POST /screenshot` 已移除并返回 `404 Not Found`。Test Mode 不提供读取桌面屏幕像素的 API。
