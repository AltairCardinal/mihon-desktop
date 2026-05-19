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
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

---

### `GET /test/screens`

获取可用屏幕列表。

**Response:**
```json
[
  {"id": "LibraryTab", "name": "LibraryTab"},
  {"id": "UpdatesTab", "name": "UpdatesTab"},
  {"id": "BrowseTab", "name": "BrowseTab"},
  {"id": "HistoryTab", "name": "HistoryTab"},
  {"id": "MoreTab", "name": "MoreTab"},
  {"id": "SettingsScreen", "name": "SettingsScreen"},
  {"id": "MigrationSearchScreen", "name": "MigrationSearchScreen"},
  {"id": "ExtensionListScreen", "name": "ExtensionListScreen"},
  {"id": "HomeScreen", "name": "HomeScreen"}
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
- `LibraryTab`
- `UpdatesTab`
- `HistoryTab`
- `BrowseTab`
- `MoreTab`
- `SettingsScreen`
- `MigrationSearchScreen`
- `ExtensionListScreen`
- `HomeScreen`

---

### `POST /test/action/{action}`

执行指定动作。

**Parameters:**
- `action` (path): 动作名称
- `body` (JSON): 动作参数

**Actions:**

#### `search`
```json
{"query": "search text"}
```

#### `filter`
```json
{"type": "unread|started|completed|clear"}
```

#### `sort`
```json
{"mode": "title|lastRead|dateAdded|unreadCount"}
```

#### `select`
```json
{"index": 0}
{"type": "category|chapter", "index": 0}
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

### `POST /test/screenshot`

捕获截图。

**Body:**
```json
{"name": "screenshot_name"}
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
    "params": {"screen": "LibraryTab"},
    "timestamp": "2026-05-19T12:00:00.000Z"
  },
  {
    "action": "search",
    "params": {"query": "One Piece"},
    "timestamp": "2026-05-19T12:00:01.000Z"
  }
]
```

---

### `POST /test/data/manga`

管理漫画数据（测试用）。

**Body:**
```json
{"operation": "add|delete_all"}
{"operation": "add", "data": {"title": "...", "url": "..."}}
```

**Response:**
```json
{
  "success": true,
  "operation": "processed",
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

---

### `POST /test/data/category`

管理分类数据（测试用）。

**Body:**
```json
{"operation": "create|delete_all", "name": "CategoryName"}
```

**Response:**
```json
{
  "success": true,
  "operation": "processed",
  "timestamp": "2026-05-19T12:00:00.000Z"
}
```

---

### `POST /test/data/setting`

管理设置数据（测试用）。

**Body:**
```json
{"operation": "set|reset", "key": "theme", "value": "dark"}
```

**Response:**
```json
{
  "success": true,
  "operation": "processed",
  "timestamp": "2026-05-19T12:00:00.000Z"
}
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

# 获取状态
curl http://localhost:8080/test/state

# 导航到库
curl -X POST http://localhost:8080/test/navigate/LibraryTab

# 搜索
curl -X POST http://localhost:8080/test/action/search \
  -H "Content-Type: application/json" \
  -d '{"query":"Naruto"}'

# 筛选
curl -X POST http://localhost:8080/test/action/filter \
  -H "Content-Type: application/json" \
  -d '{"type":"unread"}'

# 截图
curl -X POST http://localhost:8080/test/screenshot \
  -H "Content-Type: application/json" \
  -d '{"name":"test_screenshot"}'

# 重置
curl -X POST http://localhost:8080/test/reset
```
