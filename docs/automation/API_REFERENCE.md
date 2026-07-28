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
