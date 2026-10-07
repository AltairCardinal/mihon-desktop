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

`currentPage`、`totalPages`、`currentChapterId`、`isWebtoon` 和相邻章可用性直接读取实际 mounted
`ReaderScreenModel`。打开导航请求不等于 reader 已挂载；未挂载时页数/章 ID 为 `0`，
`mountedReader:null`，不能靠 fixture 的初始页码或 `hasNext` 影子状态执行阅读动作。

同一响应还包括以下只读观测，不能用于注入或自行重建分组：

| 字段 | 实际来源及边界 |
|---|---|
| `mountedReader` | 当前 owner 的 `chapterId`、`generation`、`layoutRevision`、`locationSequence`、`pairingStatus`；实际 `readingMode`、`dualPageMode`、`autoSpreadMatching` 及 `currentDisplayPageIndices`、`visiblePageIndices`。未挂载为 `null`。 |
| `sharedUnits` | 实际共享 owner 当前 units 中的源页索引数组，保留 UNKNOWN/临时单页等真实结果；不是 HTTP 服务端计算的预期分组。 |
| `nativeInput` | mounted pager 的实际几何与窗口/焦点信息，详见下文。 |
| `slotDrawObservation` | 显式启用的有界基础图片绘制命令记录，详见下文；默认未启用。 |

`currentDisplayPageIndices` 表示当前呈现单元，可能包含尚未绘制的伙伴；`visiblePageIndices` 来自 model 的停稳状态快照，不能作为当前像素全集。在同一定位下，无新阅读进度的后续绘制可能不刷新该快照。证明当前实际绘制时，应绑定选中的原生 presenter、它的有效 ticket／frame 和实际绘制槽，并核对应画面；不能合并历史 frame 或将上述 HTTP 数组当作像素证据。

`nativeInput` 返回 `available`、`geometryAvailable`、`windowPresent`、`windowShowing`、
`windowFocused`、`ownerFocused`、`reason` 和 `density`。`viewportPixels` 为 Compose 物理像素；
`viewportScreenPoints` 和三行三列 `tapPoints` 从实际 AWT 内容区屏幕原点及 density 换算，
`coordinateSpace` 为 `awt_screen_points`。每个点击点携带实际导航模式对应的 `action`。
pager 因条漫/加载/错误卸载后，旧几何清除；无实际窗口、有效几何、选中呈现或 reader 焦点，
以及设置面板打开时，`available:false`。离屏 Scene 可以有 Compose 几何，但不构成可输入的原生窗口。
正式键鼠工具必须核对 `available` 和本次窗口/焦点；此接口不读取屏幕像素，也不提供截图。

`productionEvents` 保留真实 Reader I/O 顺序及 page/generation。完整编码消费者记录
`OPEN_PAGE`；有界头部读取单独记录 `HEADER_READ`，`byteLimit` 为实际请求上限（目前至多
512 KiB）。其它事件的 `byteLimit` 可为 `null`；客户端应兼容缺失/未知字段，不能把
头部读取当作完整编码命中或删去它来满足首帧成本断言。

`OPEN_PAGE`／`HEADER_READ` 在内容 owner 调用实际读取前发出，证明请求已发起，不表示读取完成或尺寸／匹配事实已经可用。验证“前组及时”须核实际共享会话事实、候选／预留或有效绘制状态；不能只比较 `HEADER_READ` 与 `FIRST_PAGE_PRESENTED` 的时间差。

partial fixture 还返回以下页级观测字段：

- `route`、`currentPageIndex`、`snapshotGeneration`；
- 当前页的 `localHits`、`networkFallbacks`、`partialPageProbes`、`partialPageOpens`、
  `partialPageCopies`、`imageRequests`；
- 整个场景的 `scenarioPartialPageCopies`、`scenarioImageRequests`；
- `downloadIoLockViolations`，必须始终为 `0`。

这些计数按 Reader page identity 归属；附近页预取不会污染当前页的“本地命中/网络回退”断言。已提交页应为一次本地
probe/open/copy 且图片网络为 0；缺失页才允许一个物理图片请求。`snapshotGeneration` 是正整数，仅用于确认 Reader 与
当前下载 attempt 对齐，不是跨场景稳定 ID。

### `POST /reader/draw_observation/start?capacity=120` 与 `POST /reader/draw_observation/stop`

当前绘制接线仅在双页分页视图中安装；单页／条漫没有此绘制记录。`active` 只表示捕获开关启用，不能代替支持的视图、实际命令或呈现证据。

适用于包含此接口的 Desktop Test Mode 候选。先核对本次进程、产物和真实 reader 挂载，再启用观察；`capacity` 默认为120，允许1–512，非整数或越界返回400。无 Test Mode 或没有绑定 reader 时 start 返回409；成功返回200及当前 `slotDrawObservation` 快照。再次 start 建立新的 `captureId` 并清空旧记录；stop 关闭收集并保留本次已有记录供读取。reader 关闭／换绑定清空并使旧捕获失效，旧 model 的卸载不得清掉新 model 的捕获。

`GET /reader/state` 中的 `slotDrawObservation` 包含 `active`、`captureId`、`capacity`、`dropped`、`records`。超出容量丢弃最旧记录并增加 `dropped`；有丢弃时不能把剩余记录称为完整区间。每条记录的主要字段为：

| 字段 | 含义 |
|---|---|
| `level`、`canvasScope` | `base-image-draw-command` 为原 BitmapPainter 的实际命令，`compose-frame-geometry` 为基于最近实际命令的已应用布局读取；坐标域为 `image-clip-layer-local`。 |
| `sourcePageIndex`、`chapterId`、`generation`、`attemptGeneration` | 实际图片 holder 的源页、章节、资产代际及尝试代际；不拿当前请求代际替代旧图片代际。 |
| `readerGeneration`、`frameMatchesAssetGeneration`、`frameId` | 当前 reader 代际、ticket 与资产的代际关联，以及实际 display ticket/frame；`frameId` 可以复用，不是 vsync 编号。 |
| `locationSequence`、`ticketLocationSequence` | 当前 reader 定位与当前绘制能力的定位，二者不能自动当作同一用户请求。 |
| `nativeMountId`、`mountId`、`assetIdentity`、`contentIdentity` | 实际原生 presenter／Image 挂载和资源关联的进程内标识；不是持久化键。 |
| `sourceRect`、`destinationRect`、`clipCanvasTransform` | 原命令的基础 bitmap 像素源矩形、Painter 目标矩形、裁剪层当前仿射矩阵六值 `[m00,m10,m01,m11,m02,m12]`。 |
| `rootQuad`、`imageLayoutQuad` | 原绘制四角与 Image 布局四角映射到 Compose 根像素坐标；为未裁剪几何，可能超出视口，不是 AWT 屏幕点或系统截图。 |
| `monotonicNanos`、`sequence` | 命令记录是原命令返回后的时间，几何记录是坐标读取的时间；`sequence` 为本捕获顺序，不代表 GPU 已呈现。 |
| `coordinatePhase`、`composeFrameNanos`、`lastActualCommand`、`lastLayoutCallbackMonotonicNanos` | 命令为 `actual-painter-command`；几何为 `last-applied-layout-at-compose-clock-callback`。帧时钟仅触发读取，`lastActualCommand` 关联最近原命令的单调时间，布局回调时间单列；不能把上一已应用布局贴成本次 tick 的新 draw／vsync。 |

观察只在实际附着坐标和当前 Image 裁剪层坐标域可核对时发布；缺记录不等于没有显示。启用时重新安装 Painter 以使真实绘制链记录当前图片，此安装成本不作为自动匹配成本。基础图片命令不包含 region tile 叠层；缓存图层重放、父 pager 动画移动也不一定重新执行命令。该接口不能单独证明屏幕每帧稳定、没有黑帧或 GPU／vsync 性能，须与真实原生观察及相应帧证据组合。

旧候选404表示缺少接口能力，应记录具体候选及缺口，不能归因锁屏，也不通过 HTTP 重新打开模拟 reader 来制造绑定。普通应用启动不安装此观察器。

### 阅读器显式读取控制（Test Mode）

`POST /reader/io_control/arm` 示例：

```json
{"chapterId":98,"pageIndex":15,"generation":1,"range":"full","timeoutMillis":10000}
```

绑定当前实际 mounted reader 的内容 owner；`pageIndex` 为零基源索引，`generation` 为当前资产代际。`range` 仅允许 `full` 或 `prefix`；prefix 另需正整数 `maxBytes`，精确匹配实际请求的字节上限。`timeoutMillis` 为1–30000，参数无效返回400；owner／代际不适用、重复规则或活动容量满返回409；成功返回200及 `{"id":...}`。每个 controller 最多16活动规则、32条历史，不触发读取或改变配对／进度。

`GET /reader/io_control/state` 返回 `{"rules":[...]}`，包括 id、ownerId、chapterId、pageIndex、generation、maxBytes、status、enteredNanos、completedNanos、byteCount、attemptGeneration。ARMED 只表示规则已安装；HOLDING／enteredNanos 表示真实内容端口进入，completedNanos／byteCount 描述受控请求结束。OPEN_PAGE／HEADER_READ 仍是读取发起事件，不能作为字节完成或尺寸事实就绪证明。

`POST /reader/io_control/{id}/release` 继续真实 encoded reader；`.../fail` 在同一端口抛出 IOException。成功返回200及 success=true；无效、已结束或非 Test Mode 返回409。自身超时为 TIMED_OUT／IOException，保留原加载错误和 Retry；父作用域取消为 CANCELLED 并原样传播。规则精确区分 owner、full／prefix 与 attempt，不能借重试重新命中已结束规则。reader owner 关闭取消自身规则，另一个 owner 不受影响；尚未进入的规则到期在状态／匹配／新增检查时回收。

此接口不提供原生输入，不直接设置 Ready、KnownFact、配对、绘制或保存。旧候选404只表示缺能力，不代表锁屏；只能在包含该接线的实际产物上执行这些场景。

### `POST /reader/next_page`

在 UI EventQueue 调用当前 mounted model 的实际下一页动作。双页模式按共享 display unit 翻动，
例如实际当前组为 `[1,2]`、下一组为 `[3,4]` 时，逻辑目标到 `3`，不是源页索引加 `1`。
单页拆分和章节边界继续使用原生产导航语义；不能假定每次响应都移动一个源页。

### `POST /reader/prev_page`

调用同一 mounted model 的实际上一页动作，按当前 display unit 和阅读模式定位，
不是源页索引减 `1`。章节边界由实际导航处理。

上述动作返回 `success`、`action`、实际 `page`、`totalPages` 和
`inputKind:"application_action"`。`success` 表示原应用动作已分派，不是物理按键/鼠标送达或
动画已停稳的证据；验收翻动结果应继续读取 mounted owner、display/visible 页索引。
未挂载时返回 `success:false`，不记录成功动作。

### `POST /reader/next_chapter` / `POST /reader/prev_chapter`

调用真实 mounted reader 的相邻章 callback。没有实际 callback 时返回 `success:false`，
fixture 初始相邻章标志不能创建导航目标。

### `POST /reader/go_to_page`

```json
{"page": 5}
```

页码为 0-indexed 的源页索引，定位同一 mounted model 的逻辑目标；双页时由实际 owner
选择包含目标的单元。越界、参数无效或未挂载返回 `400 Bad Request`、`success:false`。

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
