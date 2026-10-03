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

`dualPage: true` 可在隔离 Test Mode profile 中打开真实 Desktop 双页 Reader；默认仍是单页。请求中的
`mangaId` 会传入 Reader 上下文。验证章级持久化时，先在该隔离 profile 的数据库中准备相同
`mangaId`、`chapterId` 的真实章节记录；fixture 本身只准备页面内容，不替测试创建数据库章节。
`dualPage` 只接受 JSON 布尔值，其他值返回 400。

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

## 作者身份验收动作

以下动作沿用 `POST /test/action/{action}`，返回 `authors` 快照；`identities` 含根 `id`、
`revision`、`displayName` 和完整精确 `names`，`frequency` 为 `daily`、`weekly` 或 `monthly`。
参数和状态变化均经过 production repository/use case，不提供任意 SQL 或事件注入。

| action | JSON 参数 | 行为 |
|---|---|---|
| `authors_state` | `{}` | 刷新作者身份、关注和全局频率 |
| `author_resolve` | `mangaId`、`name` | 从现有漫画的真实署名提取结果中选取精确名字，经既有身份用例解析；本次响应的 `resolvedCreatorId` 返回根 ID |
| `author_add_aliases` | `creatorId`、`revision`、`selectedRevisions`、`idempotencyKey` | 使用当前身份版本合并选中的已有作者；`selectedRevisions` 是 ID 到 revision 的 JSON 对象 |
| `author_set_display_name` | `creatorId`、`revision`、`name`、`idempotencyKey` | 将已接受名称设为主名 |
| `author_set_frequency` | `frequency` | 保存全局频率，并复用实际设置保存后的调度 |
| `author_sync_fixture` | `step` | 仅在启动时显式选择并验证过的 `--test-profile` 中执行固定离线同步验收 |

版本过期、未经隔离的 fixture 调用或不满足操作条件返回 `409`；缺少必要参数或非法频率返回 `400`。
`author_resolve` 不创建漫画，不接受该漫画署名之外的名字；署名不匹配返回 `404`。

同步 fixture 只使用保留名称 `GA06 验收作者`、`GA06 验收别名`、固定旧键和空间
`ga06-author-acceptance`。建议新建空测试 profile，按 `add` → 本地添加别名 → `remove` →
`confirm_remove` → `replay` → `refollow` 顺序执行；最后通过 `author_unfollow` 取消后调用
`verify_local_cancel` 检查真实 journal 的 REMOVE。`confirm_remove` 使用实际接收端取消确认接口，
重复 ADD 不得恢复关注，真正新 ADD 可以重新关注。每步要求上一步状态，失败返回 `409`。
固定 fixture 校验真实 profile marker，拒绝其他活动同步空间；不接受外部事件 JSON、密钥、
远端 URL 或自定义空间。它不连接同步服务，也不证明跨设备在线同步或别名在线传播。

## Utilities

### `POST /reset`

清空测试状态、导航 pending 状态和 reader/download/update/history 状态。

### `GET /history`

返回测试动作历史。

`POST /screenshot` 已移除并返回 `404 Not Found`。Test Mode 不提供读取桌面屏幕像素的 API。

## 同步空间恢复（仅 Test Mode）

`GET /test/sync` 读取与原生同步面板相同的 controller 投影。新增 `recoveryReason`（无恢复问题时为 `null`，否则为固定枚举 `AUTHORIZATION_REQUIRED`、`SPACE_UNAVAILABLE`、`SPACE_DATA_INVALID` 或 `SWITCH_PENDING`）及 `recoveryBusy`（检查是否正在执行）。`SWITCH_PENDING` 表示尚未完成的本机切换意图，不证明旧远端不可访问。不返回账号、仓库 ID、远端 URL、密码、密钥或持久恢复材料。

以下 POST 入口将动作发送至该原生 panel；`202 Accepted` 只表示已入队，不能作为检查成功、切换完成或同步成功的证据，须继续读取面板状态并核对运行结果。未知动作返回 `400`，缺少运行中的 panel 返回 `503`。

| 入口 | 原生动作 |
|---|---|
| `/test/sync/open_recovery` | 打开恢复方式页 |
| `/test/sync/recheck_space` | 重新核对旧空间可访问性与身份，不启动同步 |
| `/test/sync/check_authorization` | 进入原授权恢复流程 |
| `/test/sync/connect_other_space` | 进入其他空间发现与选择流程 |
| `/test/sync/create_new_space` | 请求创建新空间的确认，不创建 GitHub 仓库，不跳过用户确认 |

检查可能访问真实 GitHub，因此真实账号操作仍须具备相应任务授权；自动化失败矩阵应使用隔离的 MockWebServer。上述接口不接受任意仓库地址、密钥或恢复材料，也不提供自动确认危险操作的入口。
