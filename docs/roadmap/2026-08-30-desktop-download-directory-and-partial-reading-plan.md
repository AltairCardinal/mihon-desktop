---
parent-plan: 2026-06-30-mihon-desktop-refactor-roadmap.md
status: in_progress
date: 2026-08-30
---

# Mihon Desktop 下载目录设置与部分下载章节阅读执行方案

## 0. 文档状态与执行边界

- 状态：`IN_PROGRESS`（2026-09-01 已成为父路线唯一 `active-child-plan`）。
- 本文是同一份独立执行方案，统一覆盖“下载目录可配置”与“未完成下载章节复用已落盘页面”两项需求。
- 上一活动计划
  [`2026-08-27-desktop-reader-upstream-semantics-adapter-refactor-roadmap.md`](./2026-08-27-desktop-reader-upstream-semantics-adapter-refactor-roadmap.md)
  已在治理 HEAD `de0e1eee9eb5d1f836bd667130bb893567662ce8`、product/evidence baseline
  `86ad5462070cb3b779073ec6cf7bee75c4b1f787` 安全暂停：`RUA-00`～`RUA-06` 与 `RUA-07A` 完成，`RUA-07B/C/D`
  保持未完成。后续恢复必须在新集成 HEAD 重跑其最终验证，或由本文 `CLOSE-01` 严格超集证据接管。
- 激活时工作树没有 Reader 相关 diff，仅有无关 `?? testfile/`；Reader runtime、materialize、image pipeline 和 memory
  authority 接口已经冻结。该未跟踪项不属于本文，不得读取、修改或提交。
- 本文激活不修改 parity manifest，不把任何 capability 提前标为完成，也不分配新构建版本；产品变更从 `DDIR-01`
  开始严格执行 RED → GREEN → 重构。

## 1. 目标与用户承诺

两项能力完成后，必须同时满足以下用户承诺：

1. `设置 → 下载` 显示当前下载目录和平台默认目录；用户可选择一个实际下载根目录，也可恢复默认。
2. Windows 默认值继续是 `%LOCALAPPDATA%\Mihon\downloads`；macOS/Linux 继续使用现有
   `~/.mihon/downloads` 规则。默认值由平台路径策略动态得出，不把某台机器的绝对路径写死到偏好。
3. 目录变更采用“保存后下次启动生效”。当前进程中的下载、取消、重试、完成重命名和 Reader 查找始终使用启动时
   冻结的同一根目录，避免跨根目录竞态。
4. 首版不自动移动、复制或删除旧目录内容。设置页必须在保存前说明：先结束下载并关闭应用，再手动移动完整目录树；
   也允许直接选择一个已经包含完整目录树的目录。
5. 未完成下载章节只要至少有一页已经完成单页提交，就能从漫画详情、书架、历史或更新页按现有入口打开；已提交页
   从本地读取，缺失页才进入现有 Source/HTTP 链路。
6. 只有带版本、总页数、逐页原始索引和 `COMPLETE` 标记的 partial queue metadata，才能让打开章节不再调用
   `source.getPageList()`；旧版 `List<String>` 不能单独证明页表完整，最多允许一次在线页表 fallback，并且只有索引
   一一对应得到保守证明时才叠加旧已提交页，不能猜测或压缩页号。
7. 完整下载目录继续使用现有 `file://` 零复制路径；partial 页只在真正请求且 Reader encoded cache 未命中时按需
   物化一次，不能预先复制整章。
8. 不读取下载器仍在写入的 `NNN.tmp`，不把半页发布给 Reader，不删除或改写 Downloader 拥有的文件。
9. 不降低下载并发上限、Reader 调度窗口或完整下载/普通在线章节的打开性能；不得新增递归扫描、逐页全目录扫描或
   覆盖网络等待的大锁。
10. 页面级网络失败只影响缺失页；已经提交的本地页仍可阅读，Retry 仍先尝试有效本地页，再决定是否联网。

## 2. 已确认的当前事实

### 2.1 下载目录设置

| 事实 | Production 证据 | 结论 |
| --- | --- | --- |
| Windows 默认下载目录 | [`DesktopPlatformPaths.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopPlatformPaths.kt) 的 `resolve()` 把 `downloadsDir` 设为 `%LOCALAPPDATA%/Mihon/downloads` | 默认路径已有单一平台权威，不另造常量 |
| 启动期路径 | [`DesktopAppModule.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt) 的 `initDesktopDI()` 启动时只解析一次 `DesktopPlatformPaths` | 适合实现“下次启动生效” |
| Provider 根目录 | [`DesktopDownloadProvider.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/download/DesktopDownloadProvider.kt) 的构造参数是不可变 `private val baseDir: File` | 直接换成动态 getter 会使一次下载跨根目录 |
| DI wiring | `registerDesktopDownload()` 用 `paths.downloadsDir` 构造 provider，再把 provider、manager 注册为单例 | Manager 与 Reader 当前共享同一 provider，必须继续保持 |
| 现有设置入口 | [`DownloadSettingsScreen.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/ui/settings/DownloadSettingsScreen.kt) 已提供 CBZ、自动下载、读后删除与并行数 | 新入口追加到既有页面，不另建设置页 |
| 可复用目录选择器 | [`DesktopBackupFilePicker.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopBackupFilePicker.kt) 已在 Swing EDT 异步打开 `JFileChooser`，并支持 `DIRECTORIES_ONLY` | 提升为通用 picker，禁止再复制阻塞式 chooser |
| 可复用目录打开器 | [`DesktopDirectoryOpener.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/ui/settings/DesktopDirectoryOpener.kt) 已封装系统文件管理器与外部动作策略 | “打开目录”直接复用 |
| 共享存储偏好 | [`StoragePreferences.kt`](../../domain/src/commonMain/kotlin/tachiyomi/domain/storage/service/StoragePreferences.kt) 表示 Android 的“备份、下载、本地图源共同 storage root” | 本需求只改下载目录，不能直接复用该语义并意外迁移另外两类数据 |

### 2.2 未完成下载章节阅读

当前缺口已确认存在，但应准确表述为：**Reader 完全不复用 Downloader `_tmp` 目录中已经原子提交的完整图片；
它会把整个章节当作 ONLINE。Reader 自己的 encoded cache 命中时不会再次联网，所以不能表述为“无条件每次、每页都
重新从公网下载”。**

调用链如下：

1. [`DesktopDownloadManager.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/download/DesktopDownloadManager.kt)
   在 `_tmp` 章节目录中先写 `NNN.tmp`，写完后改名为 `NNN.<ext>`；失败时已完成的正式扩展页面保留，遗留
   `*.tmp` 在重试时删除。
2. [`DesktopDownloadProvider.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/download/DesktopDownloadProvider.kt)
   的完整下载 locator 只枚举最终目录与普通 CBZ；canonical probe、历史 Desktop fallback 和 shared candidate policy
   都不枚举带 `_tmp` 后缀的目录。
3. [`DesktopReaderMaterializePorts.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderMaterializePorts.kt)
   的 `DesktopReaderChapterContentPort.loadChapterContent()` 找不到最终 artifact 时，直接选择 ONLINE 并调用
   `source.getPageList()`。
4. 每页随后由同文件的 `DesktopReaderPageFetchPort` 先查 Reader encoded cache；未命中才通过
   [`SourcePageFetcher.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/reader/SourcePageFetcher.kt) 调用源的
   `getImage/getImageUrl` 或 OkHttp。
5. [`DesktopReaderEncodedPageStore.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderEncodedPageStore.kt)
   的缓存跨会话保留，默认上限 512 MiB；命中时不再发起**该页图片请求**。不过当前 ONLINE 章节仍会先调用一次
   `source.getPageList()` 构造 descriptor。另有 5 MiB OkHttp cache，但执行 OkHttp 调用不等于复用了下载目录。
6. 完整下载目录由
   [`DesktopReaderContentAdapter.directoryDescriptors()`](../../app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderContentAdapter.kt)
   直接生成初始 `Ready` 的 `file://` ref，不复制、不联网；新方案不能让这条快路径退化。
7. 下载队列已经在
   [`PersistentDownloadStore.kt`](../../data/src/commonMain/kotlin/tachiyomi/data/download/PersistentDownloadStore.kt)
   的 `page_urls` 字段持久化当前下载器解析出的 `List<String>`；但下载器用 `mapNotNull { it.imageUrl }` 生成它，
   `imageUrl == null` 的 Page 会被删除并压缩后续文件编号。旧格式没有原 `Page.index`、页表总数、完整性或格式版本，
   因而**不能**当作完整页表或安全的 source-index 映射。
8. 当前下载器每处理一页都会用 `tmpDir.listFiles()` 查找已完成页，页数增大时有 O(N²) 目录枚举趋势。新方案应借
   partial 索引把它改成“一次 reconcile + O(1) 查询”，不能在 Reader 侧再叠加另一套扫描。
9. 当前 `withCurrentAttempt` 会持有 `queueStateLock` 执行页文件写入、rename、签名校验、章节 rename、CBZ 压缩和目录
   删除。性能修复必须覆盖这些**现有**长临界区，不能只证明新增协调锁很短。
10. 当前完成流程会先删除既有 final directory 再 rename，并把 CBZ 直接写到最终文件后删除图片目录；这两处都存在
    数据损失/半成品可见窗口，是 partial Reader 生命周期接入前必须关闭的既有竞态。

### 2.3 与 Android 上游的关系

- Android `ChapterLoader` 同样只把最终下载 artifact 作为 Download route，Android `DownloadCache` 也排除 `_tmp`。
- 因此“阅读未完成下载的已提交页”是明确的 Desktop 产品增强，不应伪装为 Android canonical 语义。
- 共享核心只保存平台无关的 partial snapshot、page-index 合并和优先级契约；Desktop 提供文件系统、锁、复制与 CBZ
  发布 adapter。Android 不消费该增强时，既有 route 和测试必须保持不变。

## 3. 范围、复用与非目标

### 3.1 必须复用

1. 平台默认路径继续由 `DesktopPlatformPaths` 提供。
2. 偏好继续使用 `PreferenceStore`，目录 key 使用 `Preference.appStateKey(...)`，并让 Desktop backup 与 Android
   一样排除 app-state key，避免把机器绝对路径恢复到另一台设备。
3. 文件选择器从 `DesktopBackupFilePicker` 提升为通用 `DesktopFilePicker`；Backup 与 Download 共用一个 adapter。
4. 打开目录复用 `DesktopDirectoryOpener`。
5. partial 章节身份复用 `DownloadChapterIdentity`、canonical/current/legacy 有限候选和同一个 production
   `DesktopDownloadProvider`。
6. 页物化复用现有 `ReaderMaterializeExecutor`、`DesktopReaderEncodedPageStore` single-flight/atomic staging、
   `SourcePageFetcher`、scheduler 和 generation/cancellation 机制。

### 3.2 明确不做

- 不在第一版实现运行期热切换下载根目录。
- 不自动迁移、合并或删除旧下载目录，不维护无界“历史下载根目录”列表。
- 不把选中目录理解为 storage base 后再自动追加第二层 `downloads`；用户选择的就是实际下载根目录。
- 不改变自动备份目录或本地图源目录。
- 不把 partial 章节标记成“已完整下载”，不改变下载筛选、统计、读后删除和队列完成语义。
- 不读取 `*.tmp`，不实现字节级 Range 续传；当前恢复粒度仍是“完整页面”。
- 不等待 Downloader 把缺失页下载完再显示。Reader 缺失页继续立即走现有网络路径，避免拖慢漫画打开。
- 不建立第二套 Reader loader、decode owner、cache 或预取调度器。
- 不用源码字符串扫描代替 production behavior test，不用易抖动的墙钟时间作为唯一性能证据。

## 4. 目标设计

### 4.1 下载目录配置模型

新增一个启动期解析的 `DesktopDownloadDirectoryState`（名称可在实现时按现有命名统一），至少包含：

```text
defaultDirectory     平台默认下载根目录
configuredDirectory  用户保存的自定义目录；未设置时为 null
activeDirectory      当前进程冻结使用的目录
pendingDirectory     设置页修改后、下次启动将使用的目录
availability         Unknown / Available / Missing / NotDirectory / NotWritable / InvalidSyntax
restartRequired      pendingDirectory != activeDirectory
```

规则：

1. 偏好未设置时，`activeDirectory = defaultDirectory`。
2. 自定义路径保存为 absolute + normalized 形式；恢复默认调用 `Preference.delete()`，不把默认绝对路径写入偏好。
3. 选择目录时在 `Dispatchers.IO` 做真实可创建、可读写、临时文件创建/删除探针；失败不保存偏好。探针只清理自己
   创建的临时文件，不删除已经创建的用户目标目录或父目录，避免路径替换竞态误删其他 actor 的目录；因此更深层创建
   或探针失败时允许保留已经成功创建的空父目录。
4. 启动时只做有界语法解析并冻结根目录；合法路径初始为 `Unknown`，不做可移动盘/UNC I/O，也不在 UI 线程递归扫描。
5. 已保存的可移动盘/UNC 路径后来不可用时，不静默把自动下载写到默认目录，避免生成两套用户不知情的下载树；
   Provider 仍指向配置目录，下载返回现有 `Permission/Storage` 错误，设置页显示“目录不可用”。只有路径值本身
   非法、无法形成绝对安全路径时才回退默认，并显示配置修复提示。
6. 保存或恢复默认只改变 `pendingDirectory`；当前 manager/provider/reader 不重建，已有任务不受影响。
7. 进入设置页时，由 injected validator 在 `Dispatchers.IO` 对 active/pending 各执行一次有界 availability probe 并更新
   展示状态；不收集文件系统 Flow、不自动轮询 UNC/可移动盘。真实下载失败继续由 controller 把 typed Storage/Permission
   诊断反馈到队列和设置页。
8. 设置页提供：当前目录、平台默认值、选择目录、打开当前目录、恢复默认、重启生效状态、无自动迁移警告；打开失败
   必须显示可访问的 typed 反馈，不能静默吞掉异常。
9. 队列非空或存在 partial 任务时，额外提示用户先结束/取消任务并关闭应用，再移动整个目录树；不提供一键迁移。

这种设计保留 `DesktopDownloadProvider(private val baseDir: File)`，因此下载循环与 Reader 热路径没有新增偏好读取、
Flow 收集、动态锁或多根探测。

### 4.2 partial snapshot 与页面优先级

新增只读契约 `PartialDownloadSnapshotLookup`（最终命名以项目风格为准），由下载侧 production owner 实现。查询键
必须同时包含 `chapterId` 和 `DownloadChapterIdentity`，返回：

```text
chapterId + identity
download attempt generation
queue status
pageTableSchemaVersion + completeness（COMPLETE / PARTIAL / LEGACY_UNPROVEN）
totalPageCount
按 readerOrdinal 排序的 entry：readerOrdinal、原 Page.index、Page.url、可空 imageUrl
readerOrdinal -> committed page locator + 单调 committedRevision
```

章节路由保持以下顺序：

```text
最终下载目录/CBZ
        ↓ 未命中
本地漫画目录/归档/EPUB
        ↓ 未命中
有效 partial snapshot（Desktop decorator）
        ↓ 缺 metadata 或无已提交页
ONLINE source.getPageList()
```

partial 页表规则：

1. 新版 metadata 只有在 version 可识别、`COMPLETE`、entry 数等于 `totalPageCount`、`readerOrdinal` 唯一连续且保留每个
   原 `Page.index` 时，才直接构造全章稳定 descriptor，`source.getPageList()` 次数为 0。
2. `readerOrdinal` 是源页表返回顺序中的 0-based 位置，也是文件编号/Reader 顺序；原 `Page.index` 单独保存，允许非连续，
   不得因 `imageUrl == null` 删除 entry 或压缩后续 ordinal。下载某页前按现有 source contract 延迟解析其 image URL。
3. 旧版 `List<String>` 一律解码为 `LEGACY_UNPROVEN`。Reader 最多调用一次在线页表；只有返回总数一致且每个旧 URL 与
   对应 ordinal 的已解析 URL 一一匹配时才升级/叠加，任何空值、重复、长度或 `Page.index` 歧义都保守走网络且不删除
   旧 partial 文件。
4. 页表长度和索引来自新版 queue metadata/在线 source page list，不从“当前有几个文件”推断，避免页面完成过程中列表
   伸缩。`page_urls` 现有数据库列允许存放可向后解码的 tagged/versioned JSON；这属于 `PDR-01`，不要求数据库 schema
   迁移。
5. `_tmp` 目录本身不等于完整下载，`isDownloaded`、筛选和统计仍返回 false。
6. canonical 与历史 Desktop `_tmp` 只做有限候选探测，禁止扫描整个下载树。

每页物化顺序：

```text
Reader encoded cache
        ↓ miss
partial committed page snapshot
        ↓ absent / corrupt / race 后 bounded re-probe 仍失败
SourcePageFetcher / HTTP
```

未启用 CBZ 时，完成后的最终下载目录继续直接返回外部 `file://` ref，且该目录不会再被本次完成流程删除。启用 CBZ 时
不得先暴露一个稍后要删除的 transient final directory：下载器直接从私有 `_tmp` 生成同目录临时 CBZ，校验并原子发布后
才把 CBZ 暴露为完整 artifact。partial 页不能直接永久发布 `_tmp` URI；它应在 encoded cache miss 时，通过现有 store 的
私有 staging 按需复制一次，store 原子提交后 Reader 只读取自己的稳定 ref。不得在打开章节时复制全部已完成页。

encoded ref 必须携带 `Partial(committedRevision)` provenance。若后续像素 decode 才发现合法文件头之后存在截断/损坏，
既有 decode owner 应在同一个 Reader materialize attempt 中：删除/拒绝仅属于 Reader store 的失败 entry，向
`PartialPageFallbackCoordinator` 登记 `(readerAttemptGeneration, readerOrdinal, committedRevision)`，然后改走一次网络候选。
同一 revision 的 Retry 不得循环重读坏文件；Downloader 重新提交页面并递增 revision 后，本地候选才重新可用。正常本地页
不做“预验证 decode + 展示 decode”的双重解码；坏页允许一次失败 decode 后再 decode 网络结果。

### 4.3 下载侧 committed-page 索引与并发

1. 为每个活动/恢复任务建立章节级 committed-page 索引；启动或恢复时目录最多 reconcile 一次。每个候选在该次
   reconcile 最多做一次有界 header/signature probe，之后页查询不得再次验签或枚举目录。
2. 下载成功执行 `NNN.tmp → NNN.<ext>` 后 O(1) 发布该索引并递增 `committedRevision`；重试检查同一索引，替换当前
   逐页 `tmpDir.listFiles()`。
3. Reader 只查询索引，不遍历目录；索引失配时可对目标页做一次有限 re-probe，不能递归扫描。
4. 引入 generation-aware 章节提交协调器，但它和现有锁都只保护状态：网络、body read、页文件 copy/write/header
   probe/decode、页/章节 filesystem move、CBZ 压缩与校验，均不得持有 `queueStateLock`、索引锁、协调器锁或 manager
   lifecycle 锁。
5. 文件提交采用 `短锁校验 generation → generation-unique 私有 staging 锁外 I/O → 短锁 CAS 预约 publish token →
   锁外原子 move → 短锁 CAS 发布索引/queue state`。stale worker 晚到时只回收 token 明确拥有的私有/未发布 artifact，
   不能删除新 generation 文件。取消与 finalize 等待 token 通过事件/continuation 收敛，不 sleep/poll。
6. Reader 获取 `PartialPageReadLease` 时只在短锁内增加章节 ref-count/取得 locator，锁外打开并复制到自己的 staging，
   最终 `close` 释放 lease。章节 finalize 不持锁等待：有 lease 时延迟/重新调度 move 或 `_tmp` 清理；不等待网络、UI 或
   decode。locator 不得把已预约 move/cleanup 的 `_tmp` 当 direct-file ref 返回。
7. Reader 永远忽略 `NNN.tmp`、generation-private staging 和临时 CBZ。
8. **非 CBZ 模式**的 `_tmp → final` 使用同文件系统的原子 move 且不覆盖；完成发布后 final directory 不再预约删除，
   direct-file Reader ref 因而稳定。final 已存在时先验证是否为同一完整 artifact；可幂等采用则采用，否则返回 typed
   conflict 并同时保留 `_tmp` 与 existing final，禁止先 `deleteRecursively()`。原子 move 不可用时首版返回 retryable
   finalize failure，不以 copy/非原子 replace 暴露半章。
9. **CBZ 模式**不执行 `_tmp → final directory`：临时 CBZ 与最终 CBZ 位于同一目录，直接从私有 `_tmp` 写入；校验
   entry 数、名称/index、非零内容、ZIP central directory 与 CRC 后才原子 publish。publish 前 complete locator 继续 miss，
   fresh Reader 只能使用 partial snapshot；publish 后 fresh locator 只返回 CBZ，不可能取得“待删除 final directory”。
10. CBZ 原子 move 不可用或校验失败时保留 `_tmp` 和所有完整页，返回 retryable packaging failure，Reader 仍可走 partial
    route，不能降级为可观察的非原子覆盖。发布成功后等待相关 partial-copy lease 释放再清理 `_tmp`；Windows 句柄导致删除
    失败时保留 `_tmp` 并记录 cleanup 诊断，已发布 CBZ 仍有效，不能删除唯一副本或把结果改写成数据丢失状态。既有完整
    final directory 不在本批自动重打包范围内。
11. 若 `_tmp → final`（非 CBZ）或 `_tmp → published CBZ` 恰好发生在 Reader probe/open 之间，Reader 只允许一次 bounded
    re-probe：partial file → final directory page/已发布 CBZ entry → network。禁止轮询或无限重试。
12. Reader 取消、切章或 stale generation 只能删除 Reader 自己的 `.part`；不能删除下载页、CBZ 或队列状态。
13. 损坏/截断的 committed page 按其 revision 被 Reader 拒绝并退回网络，但 Reader 不删除 Downloader 文件；下载器重新
    提交后以新 revision 恢复本地优先，错误文件由下载器自己的 retry/校验处理。

### 4.4 用户入口与反馈

- 下载目录：`设置 → 下载 → 下载目录`。页面显示当前值、默认值和下次启动值；合法选择后显示“已保存，重启后
  生效；现有文件不会自动移动”，非法选择显示具体错误且偏好不变。
- partial 阅读：复用现有“打开章节”入口，不增加开关。章节进入 hybrid route 时显示一次非阻塞状态提示：
  “已下载 X/Y 页；缺失页将联网加载”。该计数来自 snapshot，不扫描文件系统。
- 本地页成功显示就是主要结果反馈；网络不可用时，已提交页继续可读，只有缺失/损坏页显示现有页面级 Error/Retry。
- 不增加整章阻塞对话框，不因 partial 状态阻止进度记录、翻页或退出。

## 5. 任务依赖与提交颗粒度

### 5.1 唯一执行进度

当前进度从第一个未勾选任务推导，不另设 `active-task`：

- [x] `ACT-00` 激活、基线与工作树冻结
- [x] `DDIR-01` 机器本地偏好与路径策略
- [x] `DDIR-02` 启动期 DI、冻结根目录与重启语义
- [ ] `DDIR-03 + DDIR-04 close gate` 通用选择器、设置 UI、兼容与文档
- [ ] `PDR-01` partial snapshot 与 O(1) committed-page 索引
- [ ] `PDR-02` partial 章节页表路由
- [ ] `PDR-03` encoded cache → committed page → network 唯一物化链
- [ ] `PDR-04` rename、CBZ、取消与 stale generation 并发矩阵
- [ ] `PDR-05` production wiring、反馈、Test Mode 与性能门禁
- [ ] `CLOSE-01` 组合回归、正式构建与关闭审计

### 5.2 依赖与提交规则

每个**实现批次**是一个可独立 RED、GREEN、审查、验证和提交的行为批次，不按单个文件拆分。`ACT-00` 是激活门禁，
`DDIR-04` 是并入 `DDIR-03` 同一提交的 close 子门禁，`CLOSE-01` 是最终收口；三者不产生纯状态/纯 checkoff 提交。
正常实现批次固定执行：

1. 零 Gradle 静态预检；
2. 一次合并 focused RED；
3. 最小 production 实现；
4. 一次合并 focused GREEN；
5. 重构后一次批次 close（相关测试 + wiring + `spotlessCheck`）；
6. 一轮独立只读审查，必要时一次有界修复复审；
7. 只提交本批 production、tests 与必要文档/checkoff。

依赖顺序：

```text
ACT-00
  ├─ DDIR-01 → DDIR-02 → DDIR-03 + DDIR-04 close gate
  └─ PDR-01  → PDR-02  → PDR-03 → PDR-04 → PDR-05（唯一 production enable）
                                                   │
DDIR-03/04 + PDR-05 ───────────────────────────────┴→ CLOSE-01
```

`DDIR-*` 与 `PDR-*` 虽然概念上可分别推进，但会共同修改 `DesktopAppModule`、`DesktopUiDependencies`、
`DesktopDownloadProvider` 和测试 fixture；同一 worktree 保持单一写入 owner，按上述顺序串行提交。不得为了占用并发槽
让两个 Agent 同时改这些文件。

`PDR-02`～`PDR-04` 的内部 port、merge/materialize 和 race 代码必须通过显式 test DI 才可达；production 一直绑定
`DisabledPartialDownloadSnapshotLookup`（恒定 miss），并有非回归测试证明章节仍按原 ONLINE route 工作。只有
`PDR-05` 在全部竞态和性能门禁通过后，才能把 composition root 原子切换为 manager-owned lookup 并显示用户反馈。
这样每个中间提交都可发布，不会暴露“有 partial descriptor、但尚无安全物化/生命周期协议”的半成品 capability。

## 6. 按测试颗粒度拆分的执行任务

### `ACT-00` 激活、基线与工作树冻结

**前置**：当前 Reader 活动计划已经完成，或已按其治理规则记录安全暂停点并原子切换唯一 `active-child-plan`。

**只读检查**：

- 记录 `HEAD`、`git status --short`、当前活动计划安全停止点；
- 确认 `DesktopReaderRuntimeFactory`、`DesktopReaderSession`、page image pipeline 和 memory authority 的最终接口；
- 重新核对 partial 缺口仍存在：完整 locator 不读 `_tmp`、Reader 找不到最终 artifact 后仍走 ONLINE；
- 冻结现有完整下载、online、local、CBZ 的 production 操作计数，作为非回归基线；
- 确认 Gradle task 与既有 anchor test 存在；计划新增类必须先写出能因 production 行为失败的测试，再运行 filter，
  `No tests found` 不算有效 RED。

**交付物**：不写功能代码、不单独创建巨大审计报告。若当前 Reader 计划已完成，则同一提交关闭它、激活本文并更新
父路线唯一活动指针；若它只是安全暂停，则同一原子文档提交必须把旧计划标成 `PAUSED`、写入精确 commit/未提交 diff/
测试/进程安全停止点，再把本文改成 `IN_PROGRESS` 并切换父指针。任一时刻不得有两个正文宣称 active。

**停止条件**：当前 Reader owner 仍有未提交改动、接口未冻结，或激活会产生两个 active child plan 时立即停止。

---

### `DDIR-01` 机器本地偏好与路径策略

**目标**：建立默认值、自定义值、恢复默认、规范化和可用性结果；不接 DI、不显示 UI。

**建议范围**：`DesktopDownloadPreferences` 或新的 machine-local preference owner、纯路径策略、Desktop backup
app-state 过滤及对应测试，约 4～7 个文件。

**RED**：

1. 未设置偏好时返回调用方提供的平台默认目录；默认路径不能被写入 preference backing store。
2. 合法绝对路径保存为 normalized 形式；恢复默认调用 `delete()` 后重新暴露动态默认值。
3. 相对路径、普通文件、无法创建、不可读写、临时探针无法清理分别返回 typed failure，失败时偏好不变。
4. 已有非空目录可选择，便于用户手动迁移完整树。
5. `Preference.appStateKey` 的目录值不进入 Desktop backup；普通用户偏好仍进入 backup。
6. UNC、可移动盘路径只做本地选择时的真实探针，不把网络 I/O 放到应用或漫画打开热路径。
7. 语法合法路径启动态 availability 为 `Unknown`；进入设置页才在 `Dispatchers.IO` 有界探测，且不自动轮询。

**GREEN**：

- 增加 `downloadDirectory(defaultPath)` 偏好或等价 owner；
- 增加纯 `DesktopDownloadDirectoryPolicy` 和 injectable I/O validator；
- Desktop backup 转换与 Android 一样排除 app-state key；
- 结果模型区分 `Unknown`、`ValidCustom`、`UseDefault`、`InvalidSyntax`、`Missing`、`NotDirectory`、`NotWritable`。

**重构门禁**：路径策略不得依赖 Compose、Injekt 或实际生产用户目录；所有测试使用 `@TempDir` 和可注入探针。

**focused tests**（计划名称）：

```powershell
python scripts/gradle-coordinator.py run --key ddir01-red -- ./gradlew :app-desktop:jvmTest --tests "mihon.desktop.download.DesktopDownloadDirectoryPreferenceTest" --tests "mihon.desktop.platform.DesktopDownloadDirectoryPolicyTest" --tests "mihon.desktop.backup.DesktopBackupMachineLocalPreferenceTest"
```

**完成条件**：RED 因缺少真实策略失败；GREEN 后默认/自定义/恢复/错误/backup 边界全绿，独立审查通过并提交。

---

### `DDIR-02` 启动期 DI、冻结根目录与重启语义

**依赖**：`DDIR-01`。

**目标**：production 在启动时解析一次活动目录，Manager、Provider、Reader 始终消费同一固定实例。

**建议范围**：`DesktopAppModule`、目录状态/controller、`DesktopUiDependencies`、DI tests，约 4～7 个文件。

**RED**：

1. 无自定义值时，真实 DI provider 的 canonical 目录位于 `DesktopPlatformPaths.downloadsDir`。
2. 自定义值有效时，真实 DI manager 写入和 Reader artifact lookup 都位于自定义根。
3. 运行中修改偏好不改变现有 provider；销毁测试 DI 并重新初始化后才切换。
4. 配置值语法损坏时安全回退默认并产生可显示诊断；语法合法但盘符暂时不可用时不静默改写默认根。
5. 移除 provider、directory state 或 RuntimeFactory 任一绑定时，production wiring test 必须失败。
6. 选择新目录不会移动、改名、删除或扫描旧根内容。

**GREEN**：

- 在 `registerDesktopDownload()` 前解析 `activeDirectory`；
- 继续用固定 `File` 构造 `DesktopDownloadProvider`；
- DI 暴露只读 directory state/controller 给设置页，不让 UI 直接构造 platform paths；
- 自动下载 worker 启动后也不收集目录偏好 Flow。

**重构门禁**：禁止把 `baseDir` 改成每次调用读取 Preferences 的动态 getter；禁止增加多根目录扫描。

**focused tests**：

```powershell
python scripts/gradle-coordinator.py run --key ddir02-red -- ./gradlew :app-desktop:jvmTest --tests "mihon.desktop.di.DesktopDownloadDirectoryDiWiringTest" --tests "mihon.desktop.platform.DesktopPlatformPathsTest" --tests "mihon.desktop.download.DownloadProviderTest"
```

**完成条件**：同一个测试 fixture 证明默认、自定义、重启、无热切换和无旧目录副作用；close 包含相关 DI wiring 与
`spotlessCheck`。

---

### `DDIR-03` 通用文件选择器与下载设置 UI

**依赖**：`DDIR-02`。

**目标**：提供可访问、可搜索、有明确结果反馈的用户入口。

**建议范围**：通用 picker、Backup 机械迁移、`DownloadSettingsScreen`、settings catalog、UI dependencies、
i18n base/zh-CN、Compose production test，约 7～12 个文件；超过估算时记录 picker + UI wiring 的不可拆内聚性。

**RED**：

1. Swing adapter 在 EDT 消费 title、initial directory、`DIRECTORIES_ONLY`；选择、取消、异常均返回 typed result。
2. Backup 设置迁移到通用 picker 后原有创建/恢复行为和反馈不变。
3. 下载设置真实渲染当前目录、默认目录和 pending 目录。
4. 点击“选择目录”向 picker 发送当前 configured/default 作为 initial directory；取消不改偏好。
5. 合法选择只更新 pending value，并显示重启/不迁移提示；当前 active value 仍不变。
6. 非法选择显示具体错误且不落盘；“恢复默认”删除偏好；“打开目录”调用既有 opener，opener 拒绝/异常时显示可访问
   的 typed 失败反馈。
7. 队列非空时显示结束任务、关闭应用、移动完整目录树的警告。
8. 搜索“下载目录/存储位置”能导航并只高亮一次；目录行和按钮具有正确 Semantics role，Enter/Space 可操作，
   路径、错误和重启提示能被辅助技术读取。

**GREEN**：

- 将 `DesktopBackupFilePicker*` 提升为通用 `DesktopFilePicker*`，Backup 与 Download 共享；
- 在 `DownloadSettingsScreen` 追加目录分组、选择、打开、恢复默认和状态反馈；
- 更新 `DesktopSettingsCatalog` anchor、i18n 和 UI dependency wiring。

**重构门禁**：不得在 Composable 内直接 new `JFileChooser` 或执行文件探针；所有平台 I/O 经注入 port 和协程。

**focused tests**：

```powershell
python scripts/gradle-coordinator.py run --key ddir03-red -- ./gradlew :app-desktop:jvmTest --tests "mihon.desktop.platform.DesktopFilePickerTest" --tests "mihon.desktop.ui.settings.DownloadSettingsDirectoryWiringTest" --tests "mihon.desktop.ui.settings.BackupSettingsProductionWiringTest" --tests "mihon.desktop.ui.settings.DesktopSettingsSearchWiringTest" --tests "mihon.desktop.ui.settings.DesktopSettingsContentAccessibilityTest"
```

**完成条件**：生产 Screen 与 picker/controller 的真实交互可观察；仅测试 fake UI 不算 wiring 证据。

---

### `DDIR-04` 目录切换兼容、失败恢复与用户文档（`DDIR-03` 同提交 close 子门禁）

**依赖**：`DDIR-03` production 与 UI 已 GREEN，但尚未提交。

**目标**：冻结“无自动迁移、下次启动生效”的边界，并证明手动迁移后现有 artifact 全部可用。

**RED**：

1. 保存新目录后，旧目录的 canonical、历史 Desktop、CBZ 和 `_tmp` 字节完全不变。
2. 关闭测试 runtime、手动复制完整目录树、以新根重启后，canonical/current/legacy/non-ASCII/hash/scanlator
   directory/CBZ 仍可被 `isDownloaded`、Reader locator 和删除动作识别。
3. 旧根有 `_tmp`、新根为空时不会误报完成，也不删除/读取旧 partial；若 source、网络与新根可用，恢复队列允许从源
   重新下载到新根。
4. 上述场景中 source/网络不可用或新根不可写时才产生对应 typed、可操作错误；不得把“用户未迁移旧 partial”本身硬编码
   成失败。
5. 自定义盘符断开后自动下载不会写入默认目录；重新连接后 Retry 仍使用配置根。
6. 恢复默认并重启后重新使用平台默认根，原自定义根不被删除。

**GREEN**：只补必要诊断、测试 fixture 和用户文档；若需要自动迁移、双根 lookup 或运行期热切换，停止并重新授权，
不能塞入本批。

**focused tests**：

```powershell
python scripts/gradle-coordinator.py run --key ddir04-red -- ./gradlew :app-desktop:jvmTest --tests "mihon.desktop.download.DesktopDownloadDirectoryCompatibilityIntegrationTest" --tests "mihon.desktop.reader.DesktopReaderContentResolverWiringTest" --tests "mihon.desktop.di.DesktopDownloadDirectoryDiWiringTest"
```

**完成条件**：文档、设置文案与 production 行为一致；用户能明确知道何时生效、如何手动迁移、失败时文件在哪里；与
`DDIR-03` production/tests 一起审查和提交，不单独产生纯测试/文档/checkoff 提交。

---

### `PDR-01` partial snapshot 契约与下载侧 O(1) committed-page 索引

**目标**：由下载 owner 暴露只读快照，既不把 `_tmp` 当完整下载，也不让 Reader 扫描目录。

**建议范围**：shared partial model/lookup port、`DownloadQueueEntry`/`PersistentDownloadStore` 向后兼容 metadata、Desktop
provider/manager、页文件命名策略、recovery/provider tests，约 8～13 个文件；这是页表正确性与索引不可拆的内聚批次。

**RED**：

1. 新版三页 metadata 完整保存 `readerOrdinal`、非连续原 `Page.index`、`Page.url`、可空 `imageUrl`、总数、version 与
   `COMPLETE`；`imageUrl == null` 不能删除 entry 或压缩后续文件编号。
2. production Manager 面对三页 source（中间页 `imageUrl == null`、原 `Page.index` 非连续）时必须下载全部三页：仅中间
   页调用一次 `source.getImageUrl()`，落盘文件保持 `001..003`，持久化表仍有三个 ordinal/原 index；不能再由
   `mapNotNull { it.imageUrl }` 丢页。
3. `_tmp/001.jpg` 已原子提交且 identity/generation/indexed metadata 匹配时，snapshot 返回 ordinal 0 和
   `committedRevision`；`isDownloaded` 仍为 false。
4. `002.tmp`、零字节、错误签名、超出总数、重复 ordinal、旧 generation 和 identity 不匹配文件全部排除。
5. 旧 `List<String>` JSON 能恢复为 `LEGACY_UNPROVEN`；新版 tagged JSON round-trip 不丢 nullable URL/原 index；混合
   新旧队列、未知 future version 和畸形 JSON 分别保守恢复/fallback，不破坏其他 queue entry。
6. ERROR 与进程恢复后的队列仍返回原 metadata 和有效 committed index；旧 metadata 不得被谎报为 `COMPLETE`。
7. canonical 与历史 Desktop `_tmp` 均做有限候选定位，不扫描 source/manga 之外的下载树。
8. 100 页恢复最多一次目录 reconcile；每个 committed candidate 最多一次有界 header/signature probe，随后所有页查询
   O(1)，下载循环不再每页调用 `listFiles()` 或验签。
9. 单页 commit 后索引 O(1) 更新且 revision 单调递增；取消/清理只撤销对应 generation，不污染同 chapterId 新任务。
10. latch/probe 证明网络、body read、page write、header probe 和 filesystem move 均不持 `queueStateLock`、索引锁、
   协调器锁或 lifecycle 锁；stale worker 的锁外写入晚到只回收自己 token 的 staging。

**GREEN**：

- 增加 versioned `PartialPageTable`、`PartialDownloadSnapshot` / `PartialDownloadSnapshotLookup`；
- 在现有 `page_urls` JSON 字段内使用可向后解码的 tagged 格式，legacy list 读为 `LEGACY_UNPROVEN`，不做数据库 schema
  迁移；
- Manager 保留 source 返回的完整 Page entry，并仅对缺少 `imageUrl` 的当前页按 source contract 延迟调用 `getImageUrl()`；
- 抽取下载页 filename/index policy，Manager 和 locator 共用；
- Manager 维护 generation/revision-aware committed index，并替换逐页全目录扫描；
- 把 `withCurrentAttempt` 内的文件 I/O 拆成 generation token + 锁外执行 + 短锁 CAS publish；
- 只读查询不暴露可变 `File` 集合或 queue 内部锁。

**重构门禁**：网络请求、body 读取、图片写入/校验/move 不持任何 manager/queue/index/coordinator/lifecycle 锁；索引不能
成为第二份持久化完成状态权威。

**focused tests**：

```powershell
python scripts/gradle-coordinator.py run --key pdr01-red -- ./gradlew :domain:jvmTest --tests "mihon.domain.reader.partial.PartialDownloadSnapshotPolicyTest" :app-desktop:jvmTest --tests "mihon.desktop.download.DownloadProviderTest" --tests "mihon.desktop.download.DownloadManagerTest" --tests "mihon.desktop.download.DesktopDownloadRecoveryIntegrationTest"
```

**完成条件**：Manager production 确实消费新索引；只给 Reader mock 一个 snapshot 而下载热路径仍 O(N²) 不算完成。

---

### `PDR-02` partial 章节页表路由

**依赖**：`PDR-01`。

**目标**：生成全章稳定页表；只有新版 metadata 可证明完整时零页表 source call；production route 仍保持 disabled。

**建议范围**：纯 page-list merge policy、`DesktopReaderChapterContentPort`、RuntimeFactory injection、相关 tests，
约 5～8 个文件。

**RED**：

1. 有 3 页 `COMPLETE` versioned metadata、仅 ordinal 0 已提交时，返回 3 个稳定 descriptor，
   `source.getPageList()` 恰为 0；非连续 `Page.index` 和中间 `imageUrl == null` 不改变顺序/长度。
2. metadata 缺失/`LEGACY_UNPROVEN` 时只调用一次 `getPageList()`；只有总数与每个 ordinal/URL/index 可一一证明时才标记
   已有本地候选，否则整页表保持安全 ONLINE，不猜测 overlay。
3. 完整 download artifact 优先于 partial；local directory/archive/EPUB 优先于 partial；无 partial 时 ONLINE 行为不变。
4. 空 entry、unknown version、总数/长度不匹配、重复 ordinal/source index 歧义和 stale generation 返回明确
   fallback/error，不发布伸缩页表。
5. 切章/同章新 generation 后旧 snapshot 结果不能写入新 session。
6. 默认 production `DisabledPartialDownloadSnapshotLookup` 下 route、source call 和 descriptor 与批次前完全一致；只有显式
   test DI 注入 lookup 时 hybrid policy 可达。

**GREEN**：

- 将 lookup 经 `DesktopReaderRuntimeFactory → DesktopReaderChapterContentPort` 注入，但 production 暂绑定 disabled miss；
- 只在 canonical route 结果本应为 ONLINE 时追加 Desktop partial decorator；
- 抽出纯 page-list merge policy，以 `readerOrdinal + 原 Page.index` 的已证明映射为依据，禁止用过滤后的 URL 列表压缩。

**重构门禁**：不得让 Android `ChapterLoader` 消费 Desktop 文件 port；共享代码只保存纯 model/priority contract。

**focused tests**：

```powershell
python scripts/gradle-coordinator.py run --key pdr02-red -- ./gradlew :domain:jvmTest --tests "mihon.domain.reader.partial.PartialReaderPageListPolicyTest" :app-desktop:jvmTest --tests "mihon.desktop.reader.DesktopReaderPartialPageListIntegrationTest" --tests "mihon.desktop.reader.DesktopReaderRuntimeFactoryTest"
```

**完成条件**：使用真实 ChapterContentPort 和 source spy 证明调用次数；手工构造最终 UI state 不算证据。

---

### `PDR-03` encoded cache → committed page → network 的唯一物化链

**依赖**：`PDR-02`，且当前 Reader image/memory authority 已冻结。

**目标**：每个请求页只经过现有 materialize owner，本地命中不联网，缺失页不等待 Downloader。

**建议范围**：`DesktopReaderPageFetchPort`、partial page input adapter、encoded store staging 接入、session/materialize
integration tests，约 5～9 个文件。

**RED**：

1. 三页章节 index 0 已提交：读 index 0 得到本地像素，图片 HTTP 请求 0；读 index 1/2 各最多一次物理请求。
2. Reader encoded cache 已命中时，partial lookup、文件打开和网络请求均为 0。
3. partial 页只在请求时复制一次到 Reader staging；预加载窗口外的已提交页不复制。
4. `NNN.tmp` 存在但正式页不存在时必须走网络，绝不读取 staging 半页。
5. 本地页损坏/截断时自动退回网络；Reader 不删除或改写该本地文件。
6. 合法图片头但像素截断时，首次 local decode failure 在同一 materialize attempt 内拒绝当前 revision、清除 Reader 自己
   的失败 cache entry 并只发一次网络请求；Retry 不循环重读同一坏 revision。
7. Downloader 重新提交同页并递增 `committedRevision` 后，本地候选重新可用；旧 Reader attempt/generation 不能解除拒绝。
8. Retry 跳过失败的 Reader cache entry，但仍优先读取未被拒绝的有效 committed revision；只有本地无效才联网。
9. 两个并发请求同一 page/generation/revision 复用 encoded store single-flight，本地复制/网络均最多一次。
10. production disabled binding 下本批代码不可达，原 ONLINE materialize 事件向量不变。

**GREEN**：

- `DesktopReaderPageFetchPort` 实现 cache → stable committed input → source；
- partial input 写入现有 store 私有 staging，继续复用 atomic commit 和 quota；
- encoded ref/state 携带 origin revision，decode owner 将失败回报给同一 attempt 的 fallback coordinator；
- 失败保持现有 typed `AppError`，generation/cancellation 向上传播。

**重构门禁**：禁止新建第二个 CoroutineScope、第二个 decoded cache 或旁路 `ReaderMaterializeExecutor` 的 UI fetch。

**focused tests**：

```powershell
python scripts/gradle-coordinator.py run --key pdr03-red -- ./gradlew :domain:jvmTest --tests "mihon.domain.reader.materialize.ReaderMaterializeExecutorTest" :app-desktop:jvmTest --tests "mihon.desktop.reader.DesktopReaderPartialMaterializationIntegrationTest" --tests "mihon.desktop.reader.DesktopReaderMaterializePortsIntegrationTest" --tests "mihon.desktop.reader.DesktopReaderSessionIntegrationTest"
```

**完成条件**：MockWebServer 真实计数、本地真实图片和 production store 同时参与；返回预设 byte array 的 fake 不足以
证明网络被绕过。

---

### `PDR-04` rename、CBZ、取消与 stale generation 并发矩阵

**依赖**：`PDR-03`。

**目标**：分别关闭非 CBZ 的 `_tmp → final` 与 CBZ 的 `_tmp → atomic CBZ` 生命周期竞态，不用大锁牺牲下载速度；
partial production route 仍保持 disabled。

**建议范围**：章节页提交协调器、`CbzCreator` atomic publish、bounded re-probe、并发 integration tests，约 6～11
个文件。

**RED**：

1. Reader 在 `NNN.tmp`/generation-private staging 写入期间不能观察到任何 partial bytes。
2. 单页 publish 与 Reader 获取 lease 并发，结果只能是完整本地页或一次明确 network fallback；lease 持有期间 copy 在锁外。
3. 非 CBZ 模式 `_tmp → final` 发生在 probe/open 之间，bounded re-probe 从 final directory 取到同 index，网络请求 0；
   publish 后 final 不会再被本流程删除。
4. final 已存在且完全相同时幂等采用；冲突/不完整时不得先删除，`_tmp` 与 existing final 都保留并返回 typed error。
5. chapter atomic move 不可用/失败时保留 `_tmp`，locator 不观察半章；stale token 不得覆盖新 generation。
6. CBZ 模式从私有 `_tmp` 直接写与目标同目录的临时 CBZ；压缩中 complete locator 持续 miss，不得出现 transient final
   directory；entry count/name/index/nonzero/central-directory/CRC 校验后才原子 publish。
7. CBZ 原子 move 不可用或校验失败时保留 `_tmp` 全部完整页并返回可重试 packaging failure；Reader partial route 仍可读，
   不得出现“半个 CBZ + 已删 `_tmp`”。
8. Reader 在 CBZ publish 前取得 partial lease 后，publish/cleanup 交错期间仍能把该页复制完成且图片网络 0；fresh locator
   在 publish 前不返回 final artifact，publish 后只返回 CBZ。active lease 尚未释放时延迟 `_tmp` 删除；Windows open handle
   或 injected delete failure 时保留 `_tmp` 和已发布 CBZ并记录 cleanup 诊断。
9. cancel/clear/delete 与 Reader copy 交错时，Reader 只清理自己的 staging；Downloader 文件所有权不越界。
10. same chapterId cancel → re-enqueue 后，旧 generation 的 commit、错误、通知和 Reader ref 全部不能污染新任务。
11. 磁盘满、权限、坏签名、Reader 取消、非协作 I/O 晚到分别保持正确错误/取消语义。
12. latch/probe 同时观察 `queueStateLock`、索引锁、协调器锁和 lifecycle 锁，证明 HTTP、body read、页 copy/write/
    header probe/decode、page/chapter move、CBZ 压缩/校验均在所有这些锁外。
13. production disabled binding 下无法从用户入口触发 hybrid Reader；显式 test DI 才能制造上述交错。

**GREEN**：

- 实现 generation/revision-aware 章节提交协调器与 publish token；
- 输入句柄 handoff 使用 ref-counted read lease，状态 CAS 使用短临界区，copy/move 均锁外；
- 非 CBZ chapter finalize 不覆盖 existing final；CBZ 从私有 `_tmp` 直接生成同目录临时输出、完整校验、原子 publish；
- 页 locator 在 race 时按模式执行一次有限 partial → final 或 partial → CBZ re-probe，绝不暴露待 cleanup 的目录。

**重构门禁**：禁止 sleep/polling、无限 retry 和全局 manager 锁；Windows 文件句柄行为必须用真实临时目录验证。

**focused tests**：

```powershell
python scripts/gradle-coordinator.py run --key pdr04-red -- ./gradlew :app-desktop:jvmTest --tests "mihon.desktop.reader.DesktopReaderPartialRaceIntegrationTest" --tests "mihon.desktop.download.DesktopDownloadRetryIntegrationTest" --tests "mihon.desktop.download.CbzCreatorTest" --tests "mihon.desktop.reader.DesktopReaderRetryAttemptLinearizationTest"
```

**完成条件**：并发测试用 latch/gate 精确制造交错，不靠概率循环；Windows 上 lease 未释放时 finalize/cleanup 不越权，
释放后可删除；另用 injected delete failure 证明删除仍失败时目录和有效 CBZ 都被保留。

---

### `PDR-05` production wiring、用户反馈、Test Mode 与性能门禁

**依赖**：`PDR-04`。

**目标**：把能力接入真实用户链路，并用可归因计数证明没有性能回退。

**建议范围**：`DesktopAppModule`、UI dependencies/Reader state、非阻塞提示、Test Mode observability、
performance fixture、i18n 与 tests，约 7～12 个文件。

**RED**：

1. 真实 DI 初始化后 RuntimeFactory 解析 manager-owned production snapshot lookup/coordinator，并由本批替换此前 disabled
   binding；移除任一 binding 或提前在 `PDR-02`～`PDR-04` 启用时测试失败。
2. 从 Manga detail/Library/History/Updates 构造真实 Reader context 后，hybrid route 可达且显示“X/Y 页”提示一次。
3. 网络断开时本地 partial 页可呈现，缺失页显示页面级 Error/Retry，章节和 Reader 不整体崩溃。
4. 100/180 页 fixture：每章/generation 目录 reconcile 最多一次、每个 committed candidate 最多一次有界 header/signature
   probe，之后每页查询 O(1)，无递归扫描或重复逐页验签。
5. 完整下载目录保持 direct-file 零复制；普通 online 无 partial 时 source/page/network 事件计数与基线一致，除了 O(1)
   内存 snapshot miss。
6. 有效 partial 当前页：最多一次本地顺序复制、一次展示 decode、图片网络 0；坏 revision 允许一次失败 local decode 后
   一次 network decode，同 revision Retry 不再读本地；缺失页每 materialize attempt 最多一次物理请求并继续受原 scheduler
   限流。
7. Downloader 的页面 HTTP 请求数、并发上限、页 commit 次数与基线一致；所有 manager/queue/index/coordinator/lifecycle
   锁都不覆盖网络、body、页 I/O/校验/move、decode 或 CBZ 压缩/校验事件。
8. Reader `maxConcurrentRequests`、nearby forward/backward 和完整下载 TTFF 事件向量不变。
9. Test Mode 报告 route、snapshot generation、local hit/network fallback、open/copy/request 次数；测试通过真实
   production controller，不伪造事件。

**GREEN**：

- 原子切换 composition root 的 disabled lookup 为 manager-owned lookup，完成 Reader state 和提示 wiring；
- operation probe 默认 `None` 时在构造事件或访问文件前短路；
- 扩展真实 Test Mode reader scenario；
- 以操作计数作为 CI 性能门禁，墙钟数据只作本机补充报告。

**重构门禁**：提示不能收集文件系统 Flow 或阻塞首帧；Test Mode 不能成为 production 行为 owner。

**focused tests**：

```powershell
python scripts/gradle-coordinator.py run --key pdr05-red -- ./gradlew :app-desktop:jvmTest --tests "mihon.desktop.di.DesktopDiWiringTest" --tests "mihon.desktop.reader.DesktopPartialReaderPerformanceContractTest" --tests "mihon.desktop.ui.reader.DesktopReaderPartialFeedbackTest" --tests "mihon.desktop.test.http.ReaderTestModeHttpTest" :test-desktop:test --tests "mihon.test.desktop.ReaderPartialDownloadE2ETest"
```

**完成条件**：production wiring、可见反馈、离线 partial、确定性性能门禁和 Test Mode 全部成立后才可声明功能完成。

---

### `CLOSE-01` 组合回归、发布构建与计划关闭

**依赖**：`DDIR-03` production 与 `DDIR-04` close gate 已在同一提交完成，`PDR-05` 已独立审查、验证并提交。

**一次性最终验证**：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'

python scripts/gradle-coordinator.py run --key download-directory-partial-final -- ./gradlew :domain:jvmTest :data:jvmTest :app:testReleaseUnitTest :app-desktop:jvmTest :test-desktop:test spotlessCheck finalParityAudit
./scripts/build-desktop.sh build-only
```

还必须完成：

- Test Mode：默认目录、自定义目录重启、partial local hit、missing network、offline/error/retry 各一条真实场景；
- Windows 正式未打包应用运行验收，并引用构建日志唯一 `Final unpacked EXE:`；
- macOS 构建/运行验收；若环境阻塞，记录真实原因，不得写成通过；
- Android 共享 route 契约回归，证明 Desktop decorator 未改变 Android 行为；
- 最终一轮只检查子批次之间 wiring 的集成审查，不重复审查已冻结内部实现；
- 更新本文状态、父路线、必要 architecture 文档、Test Mode 文档和 parity manifest evidence；不为纯 checkoff
  另建提交。

## 7. 测试矩阵

### 7.1 下载目录

| 场景 | 当前进程 | 重启后 | 文件副作用 | UI 反馈 |
| --- | --- | --- | --- | --- |
| 从未设置 | 使用平台默认 | 使用平台默认 | 沿用现有启动期创建默认目录 | 显示“默认”及绝对路径 |
| 选择合法空目录 | 仍用旧 active | 使用自定义目录 | 不移动旧目录 | 已保存、重启生效 |
| 选择合法非空目录 | 仍用旧 active | 使用该目录 | 不扫描/覆盖无关文件 | 提示目录应含完整 Mihon 下载树 |
| 取消 picker | 不变 | 不变 | 无 | 无成功提示 |
| 选择普通文件/只读目录 | 不变 | 不变 | 临时探针清理 | typed error |
| 保存后盘符断开 | 当前进程不变 | 配置根不可用，下载报错 | 不静默写默认 | 显示配置路径与错误 |
| 恢复默认 | 仍用旧 active | 使用动态平台默认 | 不删除自定义根 | 已保存、重启生效 |
| 队列/partial 非空 | 不受影响 | 由用户手动迁移结果决定 | 不自动移动 `_tmp` | 显示结束任务/关闭应用警告 |

### 7.2 partial 阅读

| artifact/metadata | 页表来源 | 已提交页 | 缺失页 | 完整下载标记 |
| --- | --- | --- | --- | --- |
| 最终目录 | directory metadata | direct `file://`，零复制 | 不适用 | true |
| 最终 CBZ | archive metadata | 按 entry 惰性提取 | 不适用 | true |
| `_tmp` + 新版 `COMPLETE` indexed metadata | versioned table，0 次 `getPageList()` | 按需复制到 encoded store | Source/HTTP | false |
| `_tmp` + legacy/缺 metadata | 最多一次 `getPageList()` | 仅一一证明后 overlay，否则联网 | Source/HTTP | false |
| 只有 `NNN.tmp` | ONLINE/fallback | 不读取 | Source/HTTP | false |
| committed 页 decode 损坏 | snapshot revision + decode 拒绝 | 不删除原文件，同 revision 不循环，退回网络 | Source/HTTP | false |
| partial → final directory（非 CBZ） | 页表保持稳定 | bounded re-probe 后本地；final 不再删除 | Source/HTTP 仅真正缺失时 | 完成后由下载 owner 更新 |
| partial → published CBZ（无 transient final） | 页表保持稳定 | lease 完成或 bounded re-probe 后 CBZ entry | Source/HTTP 仅真正缺失时 | true |
| cancel/delete 交错 | 当前 generation 保持 | 已进 Reader cache 的页可继续；其余 re-probe | Source/HTTP | false |
| 完全离线 | snapshot/本地 metadata | 可读 | 页面级 Error/Retry | 不变 |

### 7.3 并发与失败

至少覆盖以下 deterministic interleaving：

- 网络 body 尚未写完 ↔ Reader 请求同页；
- `NNN.tmp` 写完但尚未 rename ↔ Reader 请求；
- 单页 rename 前/后 ↔ Reader 打开输入；
- 非 CBZ `_tmp` 章节 rename 前/后 ↔ Reader materialize；
- final 已存在、atomic move 不可用、CBZ 从 `_tmp` 临时输出/校验/publish、fresh locator、lease 释放、Windows `_tmp`
  删除失败各边界 ↔ Reader 请求；
- cancel、retry、same-ID re-enqueue ↔ 旧下载 worker/旧 Reader generation 晚到；
- 两个 Reader session 同页 ↔ encoded store single-flight；
- 磁盘满、权限拒绝、0 字节、错误签名、可解码头但截断像素、Source 403/429/500/畸形 body。

## 8. 性能完成标准

### 8.1 CI 确定性指标

- 普通 online、local、最终 download/CBZ 没有目录全树扫描；无 partial queue entry 时只做 O(1) 内存 miss。
- 完整下载目录仍为零复制，打开章节前后的 production I/O 事件向量不得增加。
- partial 每章/generation 最多一次目录 reconcile；每个 committed candidate 最多一次有界 header/signature probe，随后
  每次页查询 O(1)，不能调用 `listFiles()` 扫全目录或重复验签。
- partial 已提交页在 encoded cache miss 时最多一次本地顺序复制；cache hit 时文件 I/O 为 0。
- partial 已提交页图片网络请求 0；缺失/损坏页每 materialize attempt 最多一个物理请求。
- 只有新版 `COMPLETE` indexed metadata 时 `getPageList()` 为 0；legacy/fallback 最多一次且不能猜测 overlay。
- Downloader 的网络、body、页 I/O/校验/move、decode、CBZ 压缩/校验阶段不持任何 manager/queue/index/coordinator/
  lifecycle 锁；请求数与并发上限不增加，当前 O(N²) 完成页检测被移除。
- Reader scheduler 参数保持既有值，P0/current page 仍能抢占 background 工作。
- 默认关闭的 operation probe 不构造事件、不访问文件、不调用 native identity。

### 8.2 本机补充测量

使用固定本地 MockWebServer、production client、固定大小/内容的 1/100/180 页图片、同一物理盘临时目录和冻结的
baseline/candidate 正式 Windows 产物。先各 warm-up 5 次，再交替顺序执行至少 30 组配对样本，报告配对差值的中位数、
P95 与置信区间：

- 完整下载首帧；
- 普通在线首帧；
- partial 当前页本地命中；
- partial 当前页缺失联网；
- 100 页下载总时长与有效吞吐，同时记录字节数、请求数、并发峰值、commit 次数及各类锁内事件。

墙钟只用于发现明显回退，不用单个易抖动阈值替代上述操作计数。真实公网只作补充观察，不参与 5% close gate。若配对
样本显示完整下载、普通在线或下载吞吐相对同机冻结基线稳定回退超过 5%，且置信区间与事件计数不能由 JIT/磁盘冷热等
噪声解释，则不得关闭计划；先用请求/字节/并发/锁事件定位额外工作。

## 9. 风险与停止条件

| 风险 | 预防 | 停止条件 |
| --- | --- | --- |
| 目录热切换让同一任务跨根 | 下次启动生效，provider 固定根 | 实现需要动态 baseDir 才能工作时重新规划 |
| 自动回退导致下载树分裂 | 合法但不可用的自定义根不静默写默认 | worker 在未提示下写入第二根目录 |
| 旧文件被迁移/删除 | 首版无自动迁移，真实副作用测试 | 任一旧根字节发生改变 |
| app-state 路径进入备份 | 对齐 Android backup filter | 跨机器恢复后写入旧机器绝对路径 |
| Reader 读到半页 | 只发布正式扩展页，忽略 `*.tmp` | 任一测试观察到 partial bytes |
| `_tmp` URI 在 rename 后失效 | partial 按需复制 + bounded re-probe | 需要轮询、无限 retry 或第二 loader |
| final 覆盖或 CBZ 半成品/`_tmp` 过早删除 | 非 CBZ 不覆盖 finalize；CBZ 不暴露 transient final，以 read lease、同目录临时 CBZ、完整校验、原子 publish 收口 | existing final 被先删，或半 CBZ 与唯一 `_tmp` 同时消失 |
| 锁拖慢下载 | 所有 manager/queue/index/coordinator/lifecycle 锁只做短状态 CAS | 任一网络/body/页 I/O/校验/move/decode/压缩事件发生在锁内 |
| partial 索引成为第二状态权威 | queue/store 仍是状态权威，索引可重建 | 索引与 queue 冲突时无法确定 generation |
| complete/online 快路径退化 | 零复制、请求/操作计数 mutation gate | 完整下载或普通 online 多一次复制/请求/扫描 |
| 与当前 Reader 活动计划冲突 | ACT-00 唯一 active plan 门禁 | 当前 Reader scope 未冻结或仍有未提交写入 |

任何发现需要自动迁移、双根长期读取、数据库 schema 迁移、运行期热切换或新的网络策略，都属于新增产品范围；先停止
当前批次并请求 replan，不得静默扩张。`PDR-01` 已明确授权在现有 `page_urls` 列中写可向后解码的 tagged/versioned JSON
并兼容旧 `List<String>`；只有需要新增/改列或破坏备份兼容时才触发该停止条件。

## 10. 完成定义与手动验收

只有以下全部满足，本文才能改为 `DONE`：

- [ ] `DDIR-01`～`DDIR-03`、`PDR-01`～`PDR-05` 均完成 RED/GREEN/重构、独立审查、close 验证和提交；
      `DDIR-04` close gate 已并入 `DDIR-03` 提交，不存在纯状态提交；
- [ ] 设置页显示当前、默认、pending 目录，选择/打开/恢复默认/错误/重启提示均可访问；
- [ ] 自定义目录只在重启后生效，旧目录无自动迁移、删除或静默扫描；
- [ ] 默认目录规则在 Windows/macOS/Linux 测试中保持不变；
- [ ] 新版 `COMPLETE` indexed metadata 时页表 source call 为 0；legacy 一次 fallback 不错配；有效已提交页图片网络 0，
      缺失/被拒绝的坏 revision 才联网；
- [ ] 完整下载保持 direct-file 零复制，普通 online/local/CBZ 行为不变；
- [ ] legacy/new metadata、rename、existing final、Windows lease、atomic CBZ、损坏 revision、cancel/retry/same-ID
      generation 和失败矩阵全部通过；
- [ ] 下载并发、Reader scheduler、操作计数和同机性能报告满足第 8 节；
- [ ] Android shared route 回归通过，Desktop partial decorator 未渗入 Android production；
- [ ] Test Mode、全量测试、Spotless、final parity、Windows/macOS 正式构建与运行证据完整；
- [ ] architecture/Test Mode/用户文档、parity manifest、计划状态、commit 和发布产物一致；
- [ ] 工作树不存在未说明或未提交的本计划改动。

面向用户的手动验收：

- [ ] `设置 → 下载` → 能看到当前下载路径和平台默认路径；
- [ ] 选择新目录 → 立即看到“重启后生效、不会自动迁移”，当前下载不跳目录；
- [ ] 重启 Mihon Desktop → 新下载进入所选目录；
- [ ] 恢复默认并重启 → 新下载回到平台默认目录；
- [ ] 下载一个多页章节，在完成数页后暂停网络 → 打开章节，已完成页立即可读；
- [ ] 翻到缺失页 → 只该页显示网络错误与 Retry，已完成页仍可返回阅读；
- [ ] 恢复网络 → 缺失页加载；已完成页不产生图片网络请求；
- [ ] 下载在阅读期间完成并打包 CBZ → 后续页面继续从本地读取，不白屏、不重复整章联网；
- [ ] 打开一个完整下载章节、一个普通在线章节和一个本地章节 → 首帧、翻页、Retry、退出行为无回归。
