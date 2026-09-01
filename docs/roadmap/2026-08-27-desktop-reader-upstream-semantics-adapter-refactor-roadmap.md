---
parent-plan: 2026-06-30-mihon-desktop-refactor-roadmap.md
status: paused
---

# Mihon Desktop 阅读器原版语义复用与平台适配层收口 Roadmap

- 制定日期：2026-08-27
- 状态：`PAUSED`（2026-09-01 在 `RUA-07B / RUA-07C / RUA-07D` 最终验收前记录安全停止点）
- 上级路线：[`2026-06-30-mihon-desktop-refactor-roadmap.md`](./2026-06-30-mihon-desktop-refactor-roadmap.md)
- 前一活动计划：[`2026-08-11-author-archive-discovery-corrective-roadmap.md`](./2026-08-11-author-archive-discovery-corrective-roadmap.md)（已在 `AA7-02 / AA7-03` legacy 清理门禁前安全暂停）
- 历史 Reader 计划：[`2026-08-02-reader-core-migration-and-presentation-roadmap.md`](./2026-08-02-reader-core-migration-and-presentation-roadmap.md)
- 历史纠正计划：[`2026-08-05-reader-non-upstream-capability-corrective-roadmap.md`](./2026-08-05-reader-non-upstream-capability-corrective-roadmap.md)
- 固定原版权威：`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`
- 本次上游跟踪点：`upstream/main@deb7b33118616d37536f1e5ef2ef85c8b5db0799`（2026-08-26）
- 激活基线：`main@b97487d0bb47b310b86a3d44cacf326ddd1eba34`
- 机器状态权威：[`parity-manifest.json`](../../app-desktop/src/test/resources/parity/parity-manifest.json)；本文不创建第二份 capability 状态源
- 暂停进度：从第 10 节第一个未勾选顶层任务 `RUA-07` 推导，不另设 `active-task`

本文于 2026-08-27 原子激活并完成 `RUA-00`～`RUA-06` 与 `RUA-07A`。2026-09-01 用户明确要求执行下载目录与 partial 阅读计划后，本文在工作树无 Reader 未提交改动的检查点安全暂停，父路线唯一 `active-child-plan` 原子切换到该计划。本文保留全部历史证据和未完成 checkbox，不把暂停伪装成关闭；恢复规则见 Build 20 后的安全暂停记录。

## 1. 执行裁决与最终目标

### 1.1 裁决

可以并且应该复用原版 Mihon 的阅读器语义，但不能把 Android 的 `Context`、`ContentResolver`、`Uri`、`UniFile`、`View`、`Bitmap` 或 Activity 生命周期直接搬进 Desktop。正确边界是：

1. 从原版 Android 生产实现中提取内容路由、下载 artifact 候选、页列表、页面身份/状态、调度、Retry、相邻章 metadata 和资源所有权等平台无关决策；
2. Android 先消费提取后的共享决策，证明没有依据文字说明重新发明一套“相似实现”；
3. Desktop 消费同一共享决策，只实现文件系统、归档、源调用、解码、Compose 生命周期和操作系统交互 adapter；
4. Desktop 独有能力只能作为显式 decorator 或 presentation，不得改变 canonical 默认语义、阻塞首帧、扩大默认 I/O 或恢复第二条加载链；
5. 每个逻辑页在一个 generation 内只能有一个内容 materialize owner 和一个 decode owner，UI 只能投影状态和已解码结果。

最终不是“Desktop 模仿原版”，而是“原版决策成为共享核心，Android 与 Desktop 分别提供薄适配”。

### 1.2 完成后的用户承诺

只有同时满足以下行为，本计划才能关闭：

1. 已下载目录章节打开时不等待全局 reader cache 扫描，不逐页读取文件签名；首帧关键路径只包含一次页表 metadata 枚举、当前页一次内容读取和一次解码。
2. Desktop 下载器生成的 CBZ 可在完全断网、源调用被设置为立即失败时正常打开；local directory/archive/EPUB、download directory/CBZ 与 online 的路由优先级和错误语义与原版一致。
3. Single、Dual、Webtoon 三种呈现不再同时经由 Coil、直接本地 Skia 和 `PagePreloader` 重复读取/解码同一页。
4. 默认相邻章行为恢复为原版“进入末五页才预取下一章 page list”；默认不下载下一章图片。Desktop 的首屏/整章图片预取继续可选，但只在首帧已呈现且调度空闲后运行。
5. 快速翻页、切章、Retry、关闭阅读器或回收 archive holder 时，旧 generation 结果不能回写，Job、流和归档句柄全部释放。
6. 下载目录、历史 Desktop 路径、原版 hash/scanlator 命名候选及 CBZ 都能只读兼容；不批量移动或删除用户下载，不改变数据库、备份或阅读进度格式。
7. Test Mode 使用真实 production content pipeline，能报告 reader intent、page-list、内容打开、解码和 `FIRST_PAGE_PRESENTED` 事件；源码字符串或 marker 不再作为“没有重复加载”的完成证明。
8. 共享核心变更经 Android production wiring、Desktop production wiring、真实存储/HTTP 集成测试和正式 Windows/macOS 构建验证。

## 2. 计划状态、激活与有限 supersede

### 2.1 激活前的冲突与当前裁决

激活前父路线唯一 `active-child-plan` 是作者归档纠正计划，且该计划会修改 shared domain/data、Desktop UI/DI、Test Mode 和 manifest。Reader 施工与其并行会产生共享可变状态和完成权威冲突。因此 2026-08-27 已在作者归档计划记录 `AA7-02 / AA7-03` 安全停止点，并原子切换到本文；本文处于活动施工期间不得并行恢复作者归档或非 Reader 计划。

- 2026-08-27 激活后父路线只指向本文；2026-09-01 安全暂停后改为指向下载目录与 partial 阅读计划；
- 作者归档计划与非 Reader 计划保持 `PAUSED`；
- Reader capability 的既有大范围 `VERIFIED` 状态不被粗暴清空，只精确 reopen RUA-00 已确认失真的单一 decode、decoded-budget 和 scheduler/prefetch evidence slice；
- 本次激活本身不宣称 Reader production bug 已修复，也不分配新的构建版本。

### 2.2 原子激活流程

只有用户明确决定切换活动计划，且当前活动计划已完成或记录安全暂停点后，才能在同一治理提交中完成：

1. 在当前活动计划写明最后完成任务、未完成任务、已运行验证和安全恢复入口；
2. 将其 frontmatter/正文状态改为 `DONE` 或 `PAUSED`；
3. 将父路线唯一 `active-child-plan` 改为本文；
4. 将本文 frontmatter/正文改为 `IN_PROGRESS`；
5. 对 manifest 中引用 RD-01/RD-02“单一获取链、默认完整下一章预取”的受影响证据做精确 reopen，不触碰无关 Reader capability；
6. 记录激活时的 Fork commit、固定原版权威和最新 `upstream/main` 跟踪点；若上游 Reader 语义有新变化，先做 provenance 审查再开始 RED。

不得仅把本文状态改成 `IN_PROGRESS` 而保留父路线指向其他计划，也不得让两个计划同时宣称 active。

上述流程于 2026-08-27 首次执行，并于 2026-09-01 按同一规则为本文记录安全停止点后反向切换到下载目录与 partial 阅读计划；恢复本文时仍须再次原子切换，不得只修改父路线链接。

### 2.3 对历史完成结论的有限纠正

本文激活后，只有限替换以下结论，不删除历史提交、任务或当时的验证记录：

- `RD-01 已删除 Desktop 双获取链`：当前 production 仍同时存在 preloader、Coil painter 和直接本地 Skia 路径；该结论必须由真实 I/O/decode 计数重新证明。
- `canonical encoded ref 是 presentation 的唯一输入`：当前 `ZoomablePageBox` 仍自行解析 URL/file URI 并打开内容；必须收口为唯一 page image pipeline。
- `FULL_NEXT_CHAPTER 是 canonical 默认相邻章语义`：它是 Desktop 产品增强，不是原版默认；canonical 默认必须为末五页 page-list-only，图片预取只作显式 decorator。
- `现有 Test Mode/静态 regression test 足以证明首帧与无重复加载`：现有证据没有运行真实 production page content，不足以证明此行为。

共享 session identity、generation、章节窗口、进度事务、双页 presentation、viewport 几何和已完成的输入修复不因本文自动重开；只有新的可执行测试证明 production wiring 断裂时才进入本计划范围。

## 3. 为什么此前对齐漏掉了这里

这不是“完全没有迁移”，而是完成定义在内容加载链的最后一公里失效：

| 漏洞 | 既有完成证据 | 为什么没有捕获 production 问题 | 本计划替代门禁 |
| --- | --- | --- | --- |
| 重复读取/解码 | `DesktopReaderProductRegressionTest` 检查源码字符串；`ReaderPageCacheIntegrationTest` 检查 preloader 命中后 painter model 为空 | 没有挂载真实 Composable、没有统计已经启动的 Coil/本地读取、没有从 runtime factory 走到首帧 | production-mounted fixture；每个 `(chapter,page,generation)` 的 open/decode 计数 |
| 直接本地第二链 | `LocalPageBitmapTest` 单测 `loadLocalPageBitmap` | 测试反而把第二条独立解码链固化为能力 | 删除该 helper；三呈现共享唯一 decode owner 的契约测试 |
| 已下载首帧慢 | store/page-list 各自有单元测试 | 没有“打开章节到第一张图”的 critical-path 测试；全局 cache scan 和逐文件验签分别看起来合理 | deterministic TTFF gate + 180 页 fixture + 首帧前 I/O 事件断言 |
| 下载 CBZ 回落在线 | local archive/CBZ 测试绕过 download provider | 没有用真实 `CbzCreator` 产物进入 reader route | online source fail-fast 的 download-CBZ production 集成测试 |
| 下一章过早工作 | RD-02 测试验证 `FULL_NEXT_CHAPTER` 自洽 | 把 Desktop 增强“存在且有界”误当成“适合作为默认”；下载页初始全 Ready 使它在打开时立即触发 | canonical 默认 OFF；`FIRST_PAGE_PRESENTED` 与末五页 metadata 双门禁 |
| 纠正审查未改 production | 2026-08-05 纠正计划重点检查 provenance/classification | 当时明确记录 RNC-06 “无 production diff”，只证明分类没有继续伪装成上游 | 本计划以 production behavior 为完成单位，不以分类正确替代行为正确 |

因此此前计划漏掉的根因是：共享 contract 和 provenance 已建立，但页面实际读取、解码与首帧 critical path 没有成为端到端完成门槛。

## 4. 上游语义权威与复用分类

### 4.1 两个上游基线

| 基线 | 用途 | 规则 |
| --- | --- | --- |
| 固定原版 `6fbf6df…` | 判断某行为在历史 parity 计划中是否来自原版，保持已有 blob/provenance 证据稳定 | 不能因 fetch 到新 main 自动替换 |
| 跟踪点 `deb7b331…` | 吸收固定点之后的 Reader 修复与格式变化 | 每个实现阶段开始前 fetch 一次；新语义先分类再进入共享契约 |

本次核对确认，当前上游没有改变下载定位、完整稳定页列表、页面状态、current+4 和末五页 metadata 的核心语义；同时必须保留以下较新上游修复：

- `bc7f7e70…`：cache journal 命中时仍须确认实体文件存在；
- `98bb731b…`：Reader 初始化保持 cooperative cancellation；
- `f7a1ecd25…`：初始化归属 ViewModel 生命周期，不能因 Activity 配置变化留下半初始化状态；
- 当前上游图片类型集合包含 `avif/gif/heif/jpg/jxl/png/webp/jp2/jpx`，但未知扩展仍允许一次内容探测，不能退化为纯白名单。

### 4.2 四类实现裁决

| 分类 | 定义 | 例子 |
| --- | --- | --- |
| `SHARE-DIRECT` | 已有 shared contract 与原版行为一致，直接让两端 production 消费 | `ReaderPageId`、页面状态、generation、`ReaderRequestScheduler.originalMihon()`、章节窗口、进度事务 |
| `SHARE-EXTRACT` | 决策仍嵌在 Android 类中，提取为平台无关纯策略，再让 Android/Desktop 同时消费 | 下载 artifact 命名/候选顺序、内容 route、已知扩展快路径、末五页 metadata、相邻章接入决策 |
| `PLATFORM-ADAPTER` | 受平台 API/运行时约束，只实现共享 port，不拥有业务决策 | Android SAF/Uri/UniFile、Desktop File/NIO、archive opener、SourceManager、OkHttp、Skia/Coil、Compose/View 生命周期 |
| `OPTIONAL-DECORATOR` | Desktop 独有且不属于 canonical 默认路径；必须显式启用、可取消且不能阻塞 P0 | 首屏/完整下一章图片预取、decoded cache 容量策略、edge matching、Dual pairing、自动滚动 |

“平台代码只留适配层”针对 Reader runtime 的内容/状态/调度语义；Single/Dual/Webtoon 的 Compose renderer 仍是必要的 Desktop presentation，但不得自行路由、打开源、安排预取、写进度或重新定义页面身份。

## 5. 原版契约冻结

以下契约是实现与审查的上游行为基线：

| 契约 | 原版生产入口 | 必须冻结的行为 |
| --- | --- | --- |
| 下载 artifact 名称 | `DownloadProvider.getChapterDirName` | `scanlator + chapterName` 按官方规则处理，保留 `_ + md5(chapterUrl).take(6)`；空章节名使用 `Chapter`；为 `.cbz` 和 hash 预留长度 |
| 当前/legacy/CBZ 候选 | `getLegacyChapterDirNames`、`getValidChapterDirNames`、`findChapterDir` | 当前目录 → 当前 CBZ → legacy 目录/CBZ → 另一种 non-ASCII preference 的目录/CBZ，取第一个实际存在项 |
| Reader 下载判断 | `ChapterLoader.getPageLoader` | 当前章节使用 `skipCache=true` 直接有限探测真实 artifact；全局 DownloadIndex 只服务筛选/计数，不是 Reader 启动门槛 |
| 内容 route | `ChapterLoader` | download → local directory/archive/EPUB → HTTP → missing source/unsupported 明确错误 |
| 页列表 | `ChapterLoader.loadChapter` | `Loading` 后一次性发布完整、稳定、重新编号列表；空列表为明确 Error；单页随后独立变化 |
| 目录/归档枚举 | `DownloadManager.buildPageList`、各 PageLoader | 已知扩展不打开内容；未知/无扩展只 probe 自身；排序必须保留 route 差异：download directory 使用区分大小写的文件名字典序，local directory 与 archive/CBZ 使用忽略大小写的自然序 |
| 惰性内容 | `ReaderPage.stream` 与各 PageLoader | 页表只持有 URI/entry/opaque ref；holder 真正需要该页时才打开流，并由 owner 关闭 |
| 页面状态 | `Page.State`、`ReaderPage` | 稳定 index；`Queue → LoadPage → DownloadImage → Ready` 或 `Error`；URL/stream 不是 identity |
| 当前 +4 | `HttpPageLoader` | 一个 canonical 调度队列；Retry 最高，当前页其次，只向前最多 4 页，不默认向后；Retry 强制重新获取 |
| 末五页相邻章 | Pager/Webtoon viewer | `pages.size - page.number < 5` 时只请求下一章 page list；不主动请求下一章图片 |
| 单通道 holder | Pager/Webtoon holder | 一个 holder 只有一个 load job，观察同一 page state，Ready 后从同一 stream/ref 解码；Retry 回到同一 loader |
| 生命周期 | `ReaderChapter.ref/unref`、`ViewerChapters` | 先 retain 新 current/prev/next 窗口，再 release 旧窗口；引用归零回收 loader/archive；取消必须传播 |
| cache 实体性 | `ChapterCache.isImageInCache` | journal/index 命中但实体缺失时视为 miss，不能发布 Ready |

当前上游 `WebGpuViewer` 的 decoded window 是 renderer adapter 策略；它仍消费同一 `PageLoader` 和 `ReaderPage.stream`，不能作为 Desktop 建立第二条 fetch/materialize 链的依据。

## 6. 当前 Desktop production 审计

### 6.1 首帧关键路径

```text
DesktopReaderScreen
  → DesktopReaderRuntimeFactory.createRuntime()
  → DesktopReaderSession.start()/activate()
  → ensureStoreStarted()
  → DesktopReaderEncodedPageStore.beginSession(emptySet())
  → 清理 + 扫描整个 reader-encoded + 建 LRU/驱逐
  → materializeChapter()
  → DesktopDownloadProvider.getDownloadedPages()
  → 对章节中每个已知图片读取 32-byte header
  → 发布完整页表
  → 当前页读取/解码
```

这解释了为什么“已下载”并不等于立即显示：网络可以完全不参与，但全局 cache 扫描和 O(章节页数) 的内容打开仍位于首帧之前。

### 6.2 同一页的三条读取/解码路径

```text
canonical EncodedPageRef
  ├─ ReaderSideEffects → PagePreloader → readBytes → Skia decode
  ├─ ZoomablePageBox → rememberAsyncImagePainter → Coil decode
  └─ ZoomablePageBox → loadLocalPageBitmap → readAllBytes → Skia decode
```

preloader 晚到后把 Coil model 设为空，不能撤销已经开始或已经完成的 Coil 与本地直接读取。当前同时存在 session scheduler、preloader scheduler，以及 encoded cache、decoded cache、Coil cache 三套所有权。

### 6.3 下载 CBZ 断链

`DesktopDownloadManager` 完成 CBZ 后生成与章节目录同级的 `.cbz` 并删除原目录；`DesktopDownloadProvider` 只检查章节目录。因此该 artifact 可能被当作未下载内容，随后错误回落 source/online。现有 local archive 测试直接注入 `localChapterPath`，没有经过 production download locator，因而没有捕获。

### 6.4 相邻章提前工作

Reader Screen 挂载即提供 next context；偏好默认 `FULL_NEXT_CHAPTER`。下载页描述符初始已全部 `Ready`，所以 session 可在首帧前请求下一章 page list，并对下一章目录再次逐文件验签或对 local/archive 全章 materialize。原版末五页门禁仍存在，但被默认 full-image policy 绕过。

## 7. 目标架构与依赖规则

### 7.1 目标调用图

```text
Android Reader UI                 Desktop Reader presentation
        │                          Single / Dual / Webtoon
        └──────────────┬───────────────────────┘
                       ▼
              Shared Reader Runtime
  ┌────────────────────────────────────────────────────┐
  │ Content route / artifact naming / page-list policy │
  │ Session / page state / current+4 / retry           │
  │ Chapter window / last-five metadata / progress     │
  │ Visible-content ownership / generation / cancel    │
  └────────────────────────────────────────────────────┘
                       │ opaque refs + effects
             ┌─────────┴──────────┐
             ▼                    ▼
      Android adapters      Desktop adapters
      SAF / UniFile         File / NIO
      archive / source      archive / ClassLoader/source
      Coil / View           Skia/Coil / Compose
      ViewModel lifecycle   ScreenModel lifecycle
```

### 7.2 共享合同建议

实现阶段可根据现有命名最小调整，但以下职责必须存在且只能有一个语义 owner：

| 合同 | 共享职责 | adapter 职责 |
| --- | --- | --- |
| `DownloadChapterIdentity` | source display、manga title、chapter name、scanlator、chapter URL、non-ASCII policy 的稳定输入 | 从 Android/Desktop model 组装，不自行丢字段 |
| `DownloadArtifactNamingPolicy` | 当前/hash/legacy/non-ASCII 的有序 directory/CBZ 候选 | 把逻辑候选映射到平台根目录并查询存在性 |
| `DownloadArtifactLocator` | 只定义 first-match 与 artifact 类型结果 | Android `UniFile`、Desktop `File/NIO` 实现有限探测 |
| `ReaderImageCandidatePolicy` | 已知扩展、未知格式 probe 决策、route-specific sort mode | list metadata；只为未知候选打开一次 probe 流；不得在 adapter 中把各 route 强行统一排序 |
| `ReaderChapterContentResolver` | download/local/online/missing/unsupported route、空列表错误、初始状态 | 创建具体 page-list/content port |
| `ReaderPageContentRef` | opaque identity、来源类别和生命周期，不暴露平台类型 | 打开 File/URI/archive entry/HTTP cache stream |
| `ReaderPageMaterializeController` | 一个 PageId/generation 的 P0/current+4、Retry、取消和 single-flight 所有权；只负责把未 Ready 页面变为 opaque encoded ref | 执行一次 source/cache materialize；downloaded/local 的 Ready ref 不因 current+4 被提前打开 |
| `ReaderVisibleDecodeLease` | mounted/visible PageId 与 encoded ref 的稳定 bind、迟到拒收和 single decode owner | 按 presentation viewport 打开/解码；维护平台有界 decoded cache |
| `ReaderDecodePort<T>` | decode 请求/结果/失败 taxonomy，不包含具体 bitmap | Android Bitmap/Coil 或 Desktop ImageBitmap/Skia；动画格式可选择专用 decoder，但同一请求只能选择一条 |
| `DownloadIndexPort` | 只表达异步索引状态/查询，不参与当前章 route | 平台后台扫描与持久化 |
| `ReaderIoProbe` / `ReaderMonotonicClock` | 默认 no-op 的稳定 reader intent、page-list、content open、decode、first-present、cache/adjacent I/O 事件和可测时间点 | Test Mode/counting adapter 采集，不改变调度 |

优先扩展现有 `domain/.../reader/session`、`materialize`、`scheduler`、`storage` 和 `ReaderPageModel` 契约，不先创建新 Gradle 模块。只有依赖图证明现有 domain 无法承载而不反向依赖平台时，才单独提出 ADR。

### 7.3 不可违反的依赖规则

1. shared core 不得引用 `File`、`Path`、`InputStream`、`Uri`、`UniFile`、`Bitmap`、`ImageBitmap`、Skia、Coil、Compose、View 或平台 source manager。
2. UI/presentation 不得调用 source、下载 provider、archive opener、encoded store `read()` 或自行解析 file URI。
3. adapter 不得重新决定 route 顺序、candidate 顺序、current+4、Retry、末五页或进度语义。
4. `current +4` 是未 Ready 内容的 materialize/fetch 语义，不等于提前解码 4 张 downloaded/local 图片；decoded cache 可以平台化，但只能消费 shared visibility/generation effect，不得建立第二个内容 scheduler。
5. Edge matcher、裁边、滤镜、宽图拆分和双页配对只消费 canonical decoded result；它们不能触发第二次内容打开。
6. encoded cache 是可丢弃派生数据；外部 downloaded/local ref 的首帧不能依赖 private cache index 初始化。
7. 同一 session 内不得用 feature flag 同时运行 legacy/new pipeline；开发期 seam 只能二选一，批次完成后删除 legacy 分支。

## 8. 产品与 UI 边界

### 8.1 保持不变

- 阅读器入口、Single/Dual/Webtoon 模式、方向、缩放、裁边、滤镜、右键菜单、键盘和鼠标翻页入口保持；
- 自动跨页匹配与双页几何属于 presentation，不进入本计划的内容路由语义；
- 阅读进度、末页完成、history、tracker、最前未读入口和同 Screen 切章保持现有正确行为；
- 下载目录和用户原图不做破坏性迁移。

### 8.2 用户可见调整

相邻章图片预取设置继续位于 Reader 设置入口，但语义改为：

| 档位 | 行为 | 默认 |
| --- | --- | --- |
| 关闭 | 保留原版末五页下一章 page-list，不预取图片 | 新安装/从未保存该偏好的用户默认 |
| 首屏 | 首帧已呈现且空闲后，只预取下一章首个 presentation viewport | 否 |
| 完整下一章 | 首帧已呈现且空闲后，以最低优先级预取下一章 encoded 内容，不保留整章 decoded bitmap | 否；仅保留用户已显式保存的值 |

迁移不能把已有显式 `FULL_NEXT_CHAPTER` 静默清空；未保存偏好的用户随新默认变为关闭。设置文案必须明确“图片预取”，避免与始终保留的末五页 page-list 预载混淆。

### 8.3 反馈与失败边界

- private cache reconcile/驱逐不得显示为阻塞 reader 的全屏 spinner；失败只进入诊断，不阻止 downloaded/local 当前页。
- 页表为空、artifact 缺失、坏 archive、单页 decode 失败分别进入明确 chapter/page Error；当前页提供 Retry，Retry 回到 canonical pipeline。
- source missing/unsupported 保持原版可解释错误，不能静默回落成无限 loading。
- Test Mode 的 I/O/TTFF 事件是验收接口，不新增普通用户调试 UI。

## 9. 数据、下载与回滚兼容策略

### 9.1 下载 artifact

- 不批量重命名、移动或删除既有下载。
- Desktop 根目录仍由平台 adapter 决定；共享 policy 只决定漫画/章节 artifact 的业务候选名和顺序。
- reader locator 采用 dual-read：先按共享 canonical/hash/CBZ 候选探测，再兼容当前 Desktop `<sourceId>/<mangaTitle>/<chapterName>` 目录及 sibling CBZ；确切优先顺序在 RUA-01 契约 fixture 中冻结。
- 新下载采用 single-write canonical 名称；切换前必须证明下载列表、删除、恢复、CBZ 生成和 Reader locator 消费同一个 identity。
- legacy fallback 只有在 canonical artifact 不存在时使用，不能把两个 artifact 合并成一章内容。

### 9.2 encoded/decoded cache

- encoded cache 不改数据库，也不进入备份。
- 如需改变 index 格式，使用版本化新目录；旧目录在首帧后后台清理，不成为迁移或回滚前置条件。
- journal/index 有记录但实体缺失时视为 miss；外部 downloaded/local ref 不因 private cache miss 失效。
- decoded cache 仅保存有界 viewport window，不能随整章页数线性增长。

### 9.3 偏好与进度

- 保留现有 enum 值，避免旧配置反序列化失败。
- 只改变“没有持久值时”的 next-image-prefetch 默认值；已有显式值原样保留。
- 不改 chapter/manga ID、`last_page_read`、history、tracker 或备份字段。

## 10. 实施任务与严格 TDD 顺序

每个改变产品行为的**子批次**都必须在同一内聚任务内完成 RED → GREEN → 重构 → focused 验证 → 独立审查 → 提交。RED 必须运行真实 production 实现或其 composition root；不能用源码文本扫描、复制算法或 fake-only helper 代替。`RUA-00`、`RUA-01` 保留已经完成时的顶层批次历史；从 `RUA-02` 起，父 RUA 只聚合目标与依赖，预算、审查和提交均以第一个未勾选的子批次为单位。父项只有在全部子项完成后才能勾选，不得用父项的一次审查覆盖多个独立状态机或平台 seam。

- [x] `RUA-00` 激活、authority 冻结与可观测性基础
- [x] `RUA-01` 共享 route/download/page-list 契约与两端决策接线
- [x] `RUA-02` Desktop 下载/local/archive adapter 与无损兼容
  - [x] `RUA-02A` artifact identity、有限候选与 dual-read/single-write
  - [x] `RUA-02B` 下载 lifecycle、恢复/删除与消费者 identity
  - [x] `RUA-02C` directory/archive adapter、惰性 entry 与 lease/generation
  - [x] `RUA-02D` production Reader/DI 接线、离线打开与 DownloadIndex gate
    - [x] `RUA-02D1` Reader adapter/runtime/session 接线与 generation-aware lease
    - [x] `RUA-02D2` 原子 download identity、production DI 与 consumer 行为接线
- [x] `RUA-03` 首帧 critical path 与 shared runtime owner 收口
  - [x] `RUA-03A` session/store 启动关键路径瘦身
  - [x] `RUA-03B` shared runner、优先级、取消与 generation
  - [x] `RUA-03C1` production TTFF、1/180 页、路由适用门与隐性内容 I/O 门禁
  - [x] `RUA-03C2` journal 实体缺失与非协作页表 late-result 门禁
- [x] `RUA-04` 唯一内容读取/解码 pipeline 与三种 presentation cutover
  - [x] `RUA-04A` 唯一 open/materialize owner 与 single-flight
  - [x] `RUA-04B` 唯一 decoder、decoded cache 与 transform consumers
  - [x] `RUA-04C` Single/Dual/Webtoon presentation cutover
  - [x] `RUA-04D` 动画/超大图/lifecycle 矩阵与 legacy owner 删除
    - [x] `RUA-04D1` 动画 purpose-aware decode owner
    - [x] `RUA-04D2` 超大图与 region tile owner
    - [x] `RUA-04D3` Retry/cancel/stale attempt 线性化
    - [x] `RUA-04D4` detach/recycle/close 与统一内存预算
    - [x] `RUA-04D5` legacy owner 删除与最终矩阵门禁
- [x] `RUA-05` 原版相邻章默认语义与 Desktop opt-in decorator
  - [x] `RUA-05A` canonical last-five page-list-only 语义
    - [x] `RUA-05A1` shared contract 与 Android consumer
    - [x] `RUA-05A2` Desktop adapter 消费 shared effect
  - [x] `RUA-05B` 偏好迁移、默认值与设置 UI wiring
  - [x] `RUA-05C` Desktop opt-in 图片预取 decorator
- [x] `RUA-06` 假阳性测试、旧 owner、authority 与文档清理
  - [x] `RUA-06A` 旧 owner/DI/第二链删除
  - [x] `RUA-06B` production mutation 证据与假阳性测试替换
  - [x] `RUA-06C` authority、manifest 与文档收口
- [ ] `RUA-07` 跨平台全量验证、Test Mode、正式构建与关闭审计
  - [x] `RUA-07A` 跨平台测试矩阵、Spotless 与 final parity
  - [ ] `RUA-07B` Test Mode、deterministic/Windows 性能与手动行为验收
  - [ ] `RUA-07C` Windows/macOS/Android 正式构建验收
  - [ ] `RUA-07D` manifest、路线图与唯一 active plan 关闭审计

### `RUA-00` 激活、authority 冻结与可观测性基础

**依赖**：完成第 2.2 节原子激活；没有其他活动计划修改同一范围。

**RED**：

- production runtime factory 没有可观察 `OPEN_READER_INTENT / PAGE_LIST_READY / OPEN_PAGE / DECODE / FIRST_PAGE_PRESENTED / CACHE_RECONCILE / ADJACENT_IO` 的稳定事件；断开任一 production wiring 时测试当前不能失败；
- Test Mode reader fixture 没有真实 page content，无法证明首帧。

**GREEN**：

- 增加默认 no-op 的 `ReaderIoProbe` 和 monotonic clock port，事件包含 chapter/page/generation/purpose；
- 从 production navigation/runtime、content adapter、decoder 与真正完成绘制的 presentation 回报事件；`FIRST_PAGE_PRESENTED` 只能在已接受的 decoded asset 实际参与至少一次 draw pass 后发出，不能在 page-list、Ready 或 decode 完成时冒充首帧；
- 建立真实临时下载目录、真实 CBZ、MockWebServer 与可挂载 Compose scene fixture；
- fixture 可 gate 非当前页、cache scan、adjacent I/O，并通过 `FIRST_PAGE_PRESENTED` 证明它们不在关键路径；
- 激活时精确更新 manifest 受影响证据状态，不根据描述文本自动推断 capability。

**重构/边界**：probe 不参与调度、不持有页面内容，release 默认无分配或近似零成本；不创建普通用户 UI。

**focused 验证**：probe wiring、Test Mode reader production fixture、Screen/runtime factory DI 与 Compose mount。

**预计**：2–3 工程日，约 6–10 个 production/test/doc 文件。

**完成证据（2026-08-28）**：

- domain 增加默认 disabled/no-op 的 `ReaderIoProbe`、可绑定场景的 probe view 与 monotonic clock；release disabled 时在读取 clock 和构造 event 前返回；
- Desktop production runtime/session、实际 materialize、preloader/Skia、Coil fallback 与 draw pass 已接入带 chapter/page/generation/purpose 的事件；`OPEN_PAGE / DECODE` 保留真实发生次数，只有同一 page/generation 的 `FIRST_PAGE_PRESENTED` 去重；
- Test Mode 使用真实目录/CBZ、MockWebServer、Compose scene 与按场景隔离的 production bridge；旧 runtime 的迟到事件因携带旧 scenario token 不会进入当前 snapshot；
- production-mounted Single reader 在 `CACHE_SCAN / NON_CURRENT_PAGE / ADJACENT_IO` 三个真实 gate 均保持阻塞时先到达首帧；释放后按章节、页码和 purpose 验证 background 事件确实发生；render 前明确验证不能提前报告首帧；
- focused GREEN：`ReaderIoObservationTest`、`ReaderIoProductionWiringTest`、`ReaderPageIoObserverTest`、`ReaderProductionTestFixtureTest`、`ReaderTestModeControllerTest`、`TestHttpServerJsonTest` 与 Desktop DI wiring；最终 production mount 重跑日志 `.gradle-coordinator/rua00-production-final.log` 为 `PASSED`；
- 独立审查与唯一修复复审均已完成。复审最后指出的身份化 gate 断言与 scenario TOCTOU 已按建议修复，并由上述直接测试覆盖；未进行第三轮审查；
- 本批实际涉及 production → Test Mode → Single/Dual/Webtoon presentation 的完整 wiring，超过 6–10 文件估算，但没有拆开会使 production wiring 断开后测试仍绿，因此作为一个内聚 RUA-00 批次提交；
- manifest 中激活时精确 reopen 的单一 decode、decoded budget、scheduler/prefetch evidence slice 继续保持 `IN_PROGRESS`；RUA-00 只建立真实可观测性，不提前宣称 RUA-04/RUA-05 的最终行为已验证。

### `RUA-01` 共享 route/download/page-list 契约与两端决策接线

**RED**：

- shared resolver 对抽象的 download directory、download CBZ、local directory/archive/EPUB、online、missing、unsupported facts 给出错误 route 或错误 taxonomy；
- 当前/legacy/non-ASCII/hash/scanlator/CBZ 纯候选顺序不一致；
- `1.jpg / 2.jpg / 10.jpg / A.jpg / a.jpg` fixture 没有保留 route-specific 页序：download directory 必须保持区分大小写的文件名字典序，local directory/archive/CBZ 必须保持忽略大小写的自然序；
- 180 个已知扩展图片建表会打开 180 个内容流；未知扩展 fallback 被完全取消或误伤；
- Android production 绕过 shared resolver，或 Desktop production composition root 没有把 artifact 探测委托给 `DownloadArtifactLocator` port；
- `DownloadIndex` 被误写成 shared resolver 的必要输入。

**GREEN**：

- 从 Android 原版生产决策提取第 7.2 节的 identity、naming、candidate、image policy 与 content resolver；
- Android `ChapterLoader`/download provider 先消费共享策略，平台 API 留在 adapter；
- Desktop content factory 消费同一 route 结果并把 artifact 探测委托给 locator port，不再自行排列 download/local/online；本批只用 production composition root + test locator 验证 port 消费，不宣称 Desktop 真实 File/CBZ locator 已完成；
- shared image policy 精确保留各 route 的排序模式；已知扩展只读 metadata，未知/无扩展仅 probe 自身一次；完整稳定页表一次发布。

**重构/边界**：不改 renderer，不切换 cache 生命周期；本批证明 shared 语义、Android production wiring 和 Desktop composition root 对 locator port 的真实委托。Desktop File/NIO 候选探测、directory/CBZ 分流及 offline 行为全部属于 RUA-02，不能提前宣称完成。

**focused 验证**：共享参数化 route/candidate/sort 契约、Android route production wiring、Desktop composition-root locator delegation、known/unknown extension policy、空列表/错误 taxonomy。test locator 只证明 production owner 委托，不作为 Desktop storage 行为证据。

**预计**：3–5 工程日，约 8–14 个文件；若超过范围，只记录内聚性，不拆开不能独立验收的 identity/route/candidate 契约。

**完成证据（2026-08-29）**：

- domain 已建立平台无关的 chapter route、download identity/naming/candidate/locator、image candidate 与 route-specific sort 契约；下载目录保持区分大小写的名字典序，本地目录/归档保持忽略大小写的自然序，下载 artifact 优先于 source route，且 `DownloadIndex` 不进入 resolver 输入；
- Android `DownloadProvider`、`DownloadManager.buildPageList`、`ReaderChapterContentRoute`、`DirectoryPageLoader` 与 `ArchivePageLoader` 已消费共享决策，Android API 只留在 adapter。直接 production 测试覆盖 null name、known extension 零内容打开、unknown extension 单次打开、Ready 状态与两种页序；`.jp2/.jpx` 差异性 fixture 已实际证明退回当前 Android 私有 `ImageUtil` 时测试会红；
- Desktop production composition root 已把 artifact 选择委托给共享 `DownloadArtifactLocator`，默认 adapter 仅保留当前 `<sourceId>/<mangaTitle>/<raw chapterName>` 目录的存在性兼容探测；页表支持九种上游已知扩展，并对改名后的 JPEG/HEIF/JXL/JP2 做一次内容签名探测。完整 canonical/legacy/non-ASCII/hash/scanlator 目录/CBZ、真实 File/CBZ locator、DownloadIndex gate 与 archive lease 仍明确属于 `RUA-02`；
- 严格 TDD 证据：`.gradle-coordinator/rua01-jp2-red-shared-desktop.log` 与 `rua01-jp2-red-android.log` 均按 JP2/JPX 共享语义缺失的正确原因失败；`rua01-jp2-green-shared-desktop.log`（含完整 domain 352 项）、`rua01-jp2-green-android.log` 和最终 `rua01-jp2-close-final.log` 均为 `PASSED`。最终收口包含根级 `spotlessCheck`、RUA-01 Desktop wiring、architecture guard 与 parity role-evidence contract；
- 本机 Android SDK 已按仓库 `AGENTS.md` 固定为 `D:\Android\Sdk` 并由真实 Android Gradle task 验证，Android 测试不再因缺 SDK 被跳过；机器专属 `local.properties` 保持忽略且未纳入提交；
- 用户明确授权了额外 TDD 修复以及第三、第四次只读复审。最终冻结 diff 指纹为 `acd6122e7e808d09f80d708c699ab66ed8e2ff3f76641914bc03ba1f543db06b`，第四次最终复审结论为 `PASS`，P0/P1/P2 均为零；
- 本批超过 8–14 文件估算，原因是同一共享契约必须同时包含 Android/Desktop production consumer、直接 wiring 测试、治理守卫与证据校准；拆开会允许任一平台旁路共享决策而测试仍绿，因此保持为一个内聚提交。未运行 Desktop 发布构建；完整 Desktop/Android、Test Mode、Windows/macOS 构建与运行验收仍只在 `RUA-07` 执行。

### `RUA-02` Desktop 下载/local/archive adapter 与无损兼容

**子批次边界与依赖**：

- `RUA-02A` 只建立 `DownloadChapterIdentity` 到 canonical/current/legacy/non-ASCII/hash/scanlator directory/CBZ 的有限候选、first-match locator、canonical single-write 与旧路径 dual-read；不改 worker 生命周期或 Reader session。focused 验证覆盖候选顺序、命名、存在性和无迁移兼容，预计 5～9 个 production/test 文件。
- `RUA-02B` 在 02A 之上统一 enqueue/worker/cancel/retry/recovery/delete/filter 的 identity；每次入队必须有独立 generation，旧 worker 不得修改或清理同 chapter ID 的新任务。focused 验证覆盖 active cancel → same-ID re-enqueue、失败 `_tmp` 清理、恢复及 Library/Updates/Manga detail 消费者，预计 7～13 个文件。
- `RUA-02C` 独立完成 local directory/ZIP/CBZ/EPUB/RAR 页表、opaque entry ref、逐 entry 惰性打开、archive replacement generation、并发串行和 chapter lease；不接管 route 顺序或 Reader session 调度。focused 验证覆盖同路径/同大小/恢复 mtime 的归档替换、真实 RAR、空/坏 archive、release/close，预计 4～8 个文件。
- `RUA-02D1` 只负责把 02A/02C 接入 production Reader runtime/session：共享一个 content adapter，使用 generation/close-aware lease 拒绝晚到注册，并以 production `CbzCreator` 离线打开和被 gate 的 DownloadIndex 证明 critical path 只做有限 locator 探测。focused 验证覆盖普通切章/close，以及非协作 current/adjacent/same-chapter/close 晚到，预计 7～9 个文件。
- `RUA-02D2` 在 D1 之上统一 production download identity 与 DI/consumer wiring：一次读取同一个 domain chapter 快照，原子使用其 name/url/scanlator，缺失时才整体回退 persisted item；真实 DI 必须驱动 manager canonical single-write、Reader 非 ASCII 偏好与 Library/MangaDetail/Updates canonical 查询。focused 验证覆盖 divergent metadata、DI manager/Reader 和三个 factory consumer，预计 7～10 个文件。

02A → 02B，02A + 02C → 02D1，02A + 02B + 02D1 → 02D2；02B 与 02C 在接口冻结后可以独立推进，但同一工作树仍保持单写入 owner。每个叶子子批次分别提交和审查；D1/D2 的集成审查只检查跨子批次 wiring，不重新审查已经冻结的内部实现。

**2026-08-29 中途重划状态**：现有未提交实现形成于子批次规则之前，不能据此提前勾选任何子项。已完成的两轮整体只读审查可以分别作为未变化 scope 的初审/复审证据；最新发现的 same-ID 重入竞态归 02B，同路径/同大小/恢复 mtime 的 archive replacement 归 02C，DownloadIndex 证明强度归 02D。后续先按文件和 invariant 冻结各子批次 scope，再分别完成缺失 TDD、确认和提交；不推倒已验证的 02A 实现，也不把 02B/02C 的内部返工扩成新产品范围。

**RUA-02A 完成证据（2026-08-29）**：

- Desktop provider 使用 RUA-01 共享 naming/candidate contract 有限探测 canonical/current/legacy/non-ASCII/hash/scanlator directory/CBZ，再读取旧 Desktop raw directory/sibling CBZ；新写入目标固定为 canonical，未迁移用户文件。
- `CbzCreator` 保留 shared policy 支持的全部已知图片扩展；provider/CbzCreator focused tests、相关 Reader wiring 与根级 `spotlessCheck` 已在 `.gradle-coordinator/rua02-review-close.json/.log` 同一调用中通过。
- 两轮整体只读审查对该冻结 scope 没有 P0/P1/P2；后续发现仅属于 02B 的 worker generation、02C 的 archive generation 与 02D 的 gate 证明强度，不反向扩大 02A。

**RUA-02B 完成证据（2026-08-29）**：

- enqueue、worker、cancel、retry、recovery、delete/filter 与 Library/Updates/Manga detail 消费者统一使用共享 download identity；每次入队分配独立 generation，旧 worker 在非协作 I/O 返回后不能写页、改状态、清理或完成同 chapter ID 的新任务。
- rename 与 CBZ packing 已分成不可回退的两个阶段：`_tmp` 成功改名后，即使打包失败也保留完整页面目录；旧 generation 的失败状态、持久化与通知在同一 queue lock 内线性提交，cancel/requeue 不会被迟到通知污染。
- 严格 TDD 证据：`.gradle-coordinator/rua02b-same-id-red2.log` 与 `rua02b-finalize-notify-red.log` 分别按旧 worker 越代写入、打包失败丢页/通知晚到的正确原因失败；`rua02b-same-id-green3.log`、`rua02b-finalize-notify-green.log` 与最终 `rua02b-final-close.log` 均通过。最终收口包含根级 `spotlessCheck` 以及 download manager、retry、recovery、parallel limit 和三个消费者 focused tests。
- 最终冻结 9 文件、`712+/146-`，Git 原始 diff 为 63,491 字节，SHA-256 为 `e26308dbf98d6333eba49be5027da1d0d453ebf8d0f8df67f39226e3a8184f34`；限定增量复审结论为 `PASS`，P0/P1/P2 均为零。PowerShell 文本管道会转换换行，后续 diff 指纹统一用 Python `subprocess.PIPE` 捕获 `git.exe` 原始 stdout。

**RUA-02C 完成证据（2026-08-29）**：

- Desktop directory adapter 按 route 保留下载目录区分大小写名字典序与本地目录忽略大小写自然序，并直接发布 Ready file refs；ZIP/CBZ/EPUB/RAR 统一由 SevenZip-backed lease 枚举稳定 entry 元数据，启动只解析页表及 EPUB container/OPF/spine 文档，图片仍按请求逐 entry 提取。
- opaque page ref 包含由 entry path/folder/size/packed-size/CRC/method 生成的内容 generation；同 chapter 重新索引先等待旧 lease 的活动操作结束并关闭句柄，copy 再以完整 opaque ref 校验当前 binding。旧 descriptor 不能把替换归档的新字节写入旧 encoded ref，跨 adapter 也不再受 JDK `ZipFile` path/fileKey/size/mtime 全局缓存污染。
- EPUB adapter 接受 XHTML 与 SVG content document，支持普通 `href` 及 `xlink:href`，按 URI path 解码 percent encoding、剥离 query/fragment、拒绝外部 scheme/authority，并阻止路径越过 archive root；空/坏 archive、真实 RAR 并发串行、release/close 后 Windows 句柄删除均有 production behavior tests。
- 严格 TDD 证据：`.gradle-coordinator/rua02c-archive-generation-red-final.log`、`rua02c-stale-generation-red.log`、`rua02c-cross-adapter-red.log`、`rua02c-epub-cross-adapter-red.log` 与 `rua02c-epub-review-red.log` 分别按伪装替换未失效、copy API 缺 generation、JDK cache 跨 adapter 污染及 EPUB SVG/URI 缺口的正确原因失败；最终 `rua02c-review-fix-close.log` 通过根级 Spotless、新 adapter 全测试与完整 materialize 集成类。
- 最终生产/test 两个 blob 分别为 `043edba48ed9c176f23c1a05ad58271eb39eb947` 与 `1d3d739a0832c894735c0bff3acef3eb3e6617dc`；独立审查及限定修复复审均已完成，最终结论 `PASS`、P0/P1/P2 为零。两个文件共 871 行超过初估，原因是同一个 archive lifecycle owner 必须内聚覆盖四种格式、EPUB 结构解析、generation/lease/concurrency 与真实格式 fixture；未混入 route、Reader session 或 DI wiring。

**RUA-02D1 完成证据（2026-08-29）**：

- production Reader runtime 为章节页表与逐页读取共享同一个 `DesktopReaderContentAdapter`，downloaded/local directory/archive 均经共享 route 与有限 artifact lookup 进入该 adapter；production `CbzCreator` 离线 CBZ、online source fail-fast 与被 gate 的 `DownloadIndex` 集成测试证明首帧不依赖全盘索引初始化。
- session 使用私有且单调递增的 lease generation 管理 active/adjacent/same-chapter 所有权，generation 不进入 `DesktopReaderChapterContext` 或 UI 语义；adapter 在 open/snapshot/install/close 边界校验 reservation，并保留每章生命周期级最高 generation watermark，倒序到达的旧 reserve 不能覆盖或复活已释放的新 owner。
- 严格 TDD 证据：`.gradle-coordinator/rua02d1-lease-generation-red.log` 先证明 close/同章晚到 lease API 缺失；`.gradle-coordinator/rua02d1-watermark-red.log` 再按倒序 reserve 与并发同章 activation 的正确原因失败；对应 GREEN 以及最终 `.gradle-coordinator/rua02d1-close3.log` 均通过。最终收口包含根级 Spotless、domain reader content，以及 Desktop adapter/materialize/runtime/session/章节切换相关测试，共 106 tasks。
- 限定独立复审最终结论 `PASS`、P0/P1/P2 为零；10 个 production/test 文件超过 7～9 个初估但仍低于强制重划阈值，原因是同一个 Reader production wiring 必须同时提交共享 lookup contract、adapter、runtime/session owner、离线/gate 集成证据与 content factory 接口的章节切换机械适配，未混入 D2 的下载 resolver、DI 或三个消费者行为实现。

**RUA-02D2 完成证据（2026-08-29）**：

- `DesktopDownloadIdentityResolver` 对队列项只读取一次 domain chapter：存在时从同一 snapshot 原子采用 name/url/scanlator，缺失时才整体回退 persisted name/url 且 scanlator 为 null；source label、manga title 与非 ASCII 文件名偏好继续由各自既有 authority 提供。
- production DI 把同一个 resolver 注入下载 manager、Library、Manga detail 与 Updates，并把真实 `LibraryPreferences.disallowNonAsciiFilenames()` supplier 注入 Reader runtime；管理器从旧 persisted metadata 入队后只生成 canonical artifact，三个消费者能查询同一 artifact，未删除既有 enqueue/cancel/删除等 Desktop 行为。
- 严格 TDD 证据：`.gradle-coordinator/rua02d2-identity-di-red.log` 中 resolver 与 production DI 测试按 hybrid identity 的正确原因失败；`rua02d2-identity-di-green.log` 通过；最终 `.gradle-coordinator/rua02d2-close.log` 通过根级 Spotless、完整 Desktop DI wiring、resolver 及 Library/Manga detail/Updates 模型测试，共 103 tasks。
- 独立只读审查结论 `PASS`、P0/P1/P2 为零；现有集成测试在移除 manager resolver、Reader preference supplier 或任一 factory identity callback 时均会失败。最终 7 个 production/test 文件处于 7～10 个预计范围内，未混入 RUA-03 首帧调度或 RUA-04 decoder/presentation 工作。

**RED**：

- 用 production `CbzCreator` 生成下载 CBZ、删除源目录并让 online source fail-fast 后，Reader 无法首帧；
- current Desktop 旧目录、sibling CBZ、canonical/legacy/non-ASCII/hash/scanlator 目录/CBZ 任一不可读，或 production locator 的 first-match 顺序偏离 RUA-01 共享候选；
- production DownloadIndex 正在全盘初始化或被 gate 时，Reader 当前章节不能用 locator 做有限 artifact 探测；
- CBZ 页表阶段提前抽取所有 entry，或 archive 句柄在章节 release 后泄漏；
- 下载删除/恢复/筛选与 Reader locator 使用不同 identity。

**GREEN**：

- Desktop `DownloadArtifactLocator` 实现有限 candidate 探测与 directory/CBZ 分流；
- provider 页表枚举只产生 opaque file/archive-entry refs，不读取每页内容；
- download directory 使用区分大小写的名字典序；download CBZ/local directory/archive 使用忽略大小写的自然序；
- local directory/archive/EPUB 通过相同 shared route/page-list contract；
- current Desktop 路径只读 fallback 与 canonical single-write 同时可用；不搬迁用户文件；
- archive opener 由 chapter lease 管理，逐 entry 惰性打开。

**重构/边界**：发现/浏览用途的 `LocalSourceReader` 可保留；Reader route 与 entry-open 能力拆到 adapter port，不能继续由 local helper 定义业务顺序。

**focused 验证**：真实目录/CBZ/EPUB/RAR（支持范围内）、current/legacy/non-ASCII/hash/scanlator、route-specific 页序、DownloadIndex gate、空/坏 archive、offline source fail-fast、资源关闭。

**预计**：父 RUA 总计约 4～7 工程日；文件和 Gradle 预算按 02A～02D 分别计算，不再使用一个 8～14 文件预算覆盖全部 storage/lifecycle/archive/wiring。

### `RUA-03` 首帧 critical path 与 shared runtime owner 收口

**子批次边界与依赖**：

- `RUA-03A` 只移除 session activate 前的全局 store/cache 工作，让 downloaded/local 外部 ref 直接形成稳定页表，并把 reconcile/eviction 推迟到首帧后；预计 4～8 个文件。
- `RUA-03B` 在 03A 的稳定入口上把 I/O runner、P0/background 优先级、cooperative cancellation 与 generation 发布权移入 shared owner；Desktop session 只保留 lifecycle/port binding，预计 6～10 个文件。
- `RUA-03C1` 挂载 production fixture，验证 downloaded/local/online、1/180 页、cache/background gate 与目录/归档隐性内容读取；只建立 production critical-path 证据和必要探针，不新增第二套调度，预计 4～7 个文件。
- `RUA-03C2` 独立验证 journal 记录命中但实体缺失时的 production refetch，以及非协作页表在切章/关闭后的 late-result 拒绝；它不修改 C1 的 TTFF fixture，预计 1～3 个文件。

03A → 03B → 03C1 → 03C2。任一子批次若需要同时重写 decoder 或 presentation，应停止并把该发现移交 RUA-04，不得扩大当前提交。

**RED**：

- 预置大量 `reader-encoded` 并 gate cache enumeration 时，已下载当前页不能呈现；
- 1 页与 180 页已知扩展目录的 deterministic TTFF 成本不同；
- 首帧前出现 `CACHE_RECONCILE`、非当前页 `OPEN_PAGE` 或 `ADJACENT_IO`；
- cooperative cancellation/切章后旧 materialize 仍可发布。

**GREEN**：

- `DesktopReaderSession.activate()` 不再在 page-list 前调用全局 `beginSession(emptySet())`；
- 外部 downloaded/local refs 直接进入稳定页表，private store 只在 online/archive materialize 真正需要时懒启动；
- cache reconcile/eviction 在首帧后后台运行，失败不阻塞当前外部 ref；
- session 的 I/O 编排、原版优先级和 generation 归 shared runner，Desktop session 收薄为 lifecycle/port binding；
- P0 current page 可抢占所有 background 工作，取消异常向上传播。

**重构/边界**：可保留 Desktop store adapter 与 512 MiB policy，但它不再拥有 Reader 启动顺序或 page scheduler。

**focused 验证**：downloaded/local/online critical path、store gate、1/180 页 deterministic cost、快速切章/关闭、journal 命中实体缺失。

**预计**：父 RUA 总计约 4～6 工程日；文件和验证预算按 03A、03B、03C1、03C2 分别计算。

**RUA-03A 完成证据（2026-08-29）**：

- `DesktopReaderSession` 不再在页表到达后无条件启动 encoded store 或枚举共享缓存；downloaded/local 已有稳定外部 ref 的 Ready 页面不触发 store，online/archive 只有在当前页确实需要写入时才用 `beginSessionFast` 建立可写租约，P0 当前页不等待全局 cache scan。
- production presentation observer 只在首个有效 `pagePresented` 后通知 session，随后在后台执行 gate → `CACHE_RECONCILE`；即使 I/O probe/reporting 关闭，runtime 仍安装 maintenance callback。重复呈现、旧 generation、切章和 close 后回调不会重启整理。
- reconcile 将所有活跃 session lease 聚合为 pinned refs；pinned 总量可暂时超过 512 MiB 软配额而不会删除正在显示或预加载的文件，此时新写入被拒绝。lease 释放后立即以剩余 pins 重新 trim，回收已不再使用的文件并恢复配额与写入能力；索引不靠漏记文件维持虚假的预算值。
- 严格 TDD 证据：`.gradle-coordinator/rua03a-first-frame-red2.log` 先按缺少首帧后维护入口与 observer callback 的正确原因失败；`rua03a-first-frame-green3.log` 通过。首次收口暴露旧 fixture 绕过 production presentation，修正后 `rua03a-repair-green.log` 与 `rua03a-close2.log` 通过。独立审查发现小配额双 fast-session 会误删 active ref，`rua03a-pinned-red.log` 复现失败，`rua03a-pinned-green.log`、`rua03a-domain-contract.log` 与最终 `rua03a-close3.log` 全部通过；最终收口包含根级 `spotlessCheck`、共享 store contract 以及 Desktop materialize/session/runtime/observer/production wiring 测试。
- 限定修复复审结论为 `PASS`，P0/P1/P2 均为零；最终 8 个 production/test 文件处于 4～8 个预计范围内，只修改 store/session 首帧关键路径及其 production observer 接线，未混入 RUA-03B shared scheduler、RUA-03C production TTFF fixture 或 RUA-04 decoder/presentation cutover。未运行 Desktop 发布构建；正式构建仍只在 `RUA-07` 执行。

**RUA-03B 完成证据（2026-08-30）**：

- domain 新增平台无关的 `ReaderPageMaterializeRunner`，统一持有页面 coroutine/job、物理请求 permit、pump、取消、P0/background 调度接受条件和 generation 发布权；Android `HttpPageLoader` 与 Desktop `DesktopReaderSession` 只通过 port 绑定平台 page/context/fetch/status，已删除两端各自的页面 job、semaphore 与 pump owner。
- runner 在物化前、非协作 prepare 返回后和每次发布前同时校验 active registration 与平台 scheduler/session generation；被取消的旧任务可以释放迟到的物理 I/O，但不能发布状态或占用新的逻辑并发槽。LAZY job 即使在 body 调度前被取消，也通过同一原子 `finalized` 路径恰好一次调用 `port.complete`，Android 不再泄漏旧 `scheduledPages` key。
- Desktop gate 返回后先校验 generation；可写 store fast-init 返回后，在同一个 session lock 内完成最终校验与 `OPEN_PAGE / ADJACENT_IO` 上报。切章或关闭不能插入“校验后、上报前”的过期窗口，旧 work 也不会进入 materializer；取消期间最多保留幂等的共享 store fast-init，不会产生旧页 fetch。
- 严格 TDD 证据：`.gradle-coordinator/rua03b-runner-red.log` 先因 shared runner contract 不存在而正确失败，`rua03b-runner-green.log` 通过；首次 `rua03b-close.log` 通过相关 shared/Android/Desktop suite。独立审查发现 cancel-before-dispatch completion 与 non-cooperative Desktop gate 两个 P2 后，`rua03b-review-red.log` 在旧实现上分别按 completion 缺失和 stale `OPEN_PAGE` 正确失败；最终 `rua03b-review-close.log` 为 `BUILD SUCCESSFUL in 18m 25s`，340 个 task 中 35 个执行、305 个命中缓存，包含根级 Spotless 及相关 domain、Android、Desktop production wiring 测试。
- 限定修复复审结论为 `PASS`，P0/P1/P2 均为零；冻结代码 diff 为 7 个 production/test 文件、`781+/213-`、57,186 原始字节，SHA-256 为 `644cf543c768bdc64a3e534a95e7738cb19e015ac08182d03590ac4a180a0a44`，处于 6～10 文件预计范围内。本批未修改 decoder、presentation 或 TTFF fixture；未运行 Desktop 发布构建，正式构建仍只在 `RUA-07` 执行。

**RUA-03C1 完成证据（2026-08-30）**：

- 新增挂载真实 `DesktopReaderScreen → Navigator → DesktopReaderRuntimeFactory` 的离屏 production fixture，覆盖 downloaded directory、local directory、local CBZ、online 四条路由以及 1/180 页矩阵。首帧 trace 必须以当前页 `FIRST_PAGE_PRESENTED` 收尾，首帧前不得出现 cache reconcile、adjacent I/O 或非当前页 open/decode；online 只允许一次 page-list 与当前图片请求。
- deterministic cost 改用可归因的 production 事件、内容操作、source 调用和网络请求计数，不再把并发 reporter 的时钟差值冒充成本。content operation probe 能发现已知目录逐页 signature、CBZ signature/eager entry 和 EPUB metadata 预读；默认 `None` 在构造 operation 或调用 SevenZip native identity 前返回，不给正常运行路径增加 O(N) 查询。
- fixture 在不释放 gate 的前提下证明各路由可达门已真实进入：所有路由进入首帧后 cache scan，180 页 queued 路由进入 non-current page，首帧页表已全部 Ready 的 directory 路由进入 adjacent I/O。CBZ/online 在 non-current work 未完成前不会错误地被要求进入 adjacent gate。
- 严格 TDD 与审查修复证据：初始 `.gradle-coordinator/rua03c-red.log` 暴露 presentation 仍有双 decode，该 exact-one 缺口按边界移交 `RUA-04`；C1 只要求至少一次当前页 decode。`rua03c1-close2.log` 完成 root Spotless 与 production 矩阵；独立审查指出 disabled probe 会提前求值 native identity 后，`rua03c1-probe-red.log` 因 lazy contract 缺失正确失败，最终 `rua03c1-probe-close.log` 为 `BUILD SUCCESSFUL in 16m 18s`，103 个 task 中 13 个执行、90 个命中缓存。
- 有界复审结论为 `PASS`，P0/P1/P2 均为零；复审冻结范围为 6 个 production/test/roadmap 文件、29,635 原始字节，SHA-256 为 `f720b01b42f41eaeea78c5457d96aeeb82ea32c3c9227e112901dd03157a417d`。本批没有改动 decoder 或 presentation owner；exact-one decode、decoded cache 与三种 presentation cutover 仍由 `RUA-04` 验证。未运行 Desktop 发布构建，正式构建仍只在 `RUA-07` 执行。

**RUA-03C2 完成证据（2026-08-30）**：

- production store 测试先通过真实 `store/contains` 建立并观察 journal 记录，再只删除实体文件；随后经 `CanonicalReaderMaterializeExecutor → DesktopReaderPageFetchPort` 触发一次 MockWebServer 请求，以相同 ref 和最终 bytes 证明“索引命中、实体缺失”会安全回落到 refetch，不能把 stale journal 当作可读缓存。
- production runtime 测试让旧章节页表 I/O 在 `NonCancellable` 中跨过取消屏障：切章后旧 chapter 1 的迟到结果不能发布 `PAGE_LIST_READY`，也不能覆盖已 Ready 的 chapter 2；close 后 chapter 3 的迟到结果同样不能发布。关键等待均有界，所有 deferred 在 `finally` 放行，runtime close 幂等，没有协程或句柄泄漏。
- 本批是单文件纯证据增量，没有修改 product behavior，因此复用已覆盖当前未变 diff 的验证：`.gradle-coordinator/rua03c-green.log` 精确运行两条新增测试并 `BUILD SUCCESSFUL in 2m 14s`；`rua03c-close.log` 运行完整 MaterializePorts、session/runtime/wiring/architecture 与 root Spotless，`BUILD SUCCESSFUL in 6m 3s`。
- 独立只读审查结论为 `PASS`，P0/P1/P2 均为零；冻结测试 diff 为 `158+/0-`、9,740 原始字节，SHA-256 为 `a66ce877cf47e231947be51fa88f9272fce9f7da0a23bce9b8e78b37090bcd23`。本批未运行 Desktop 发布构建，正式构建仍只在 `RUA-07` 执行。

### `RUA-04` 唯一内容读取/解码 pipeline 与三种 presentation cutover

**子批次边界与依赖**：

- `RUA-04A` 建立唯一 page open/materialize single-flight owner 与 opaque ref 输入，先用 production runtime 证明同 page/generation 只有一次内容打开；预计 5～9 个文件。
- `RUA-04B` 在 04A 上建立唯一 decoder、有界 decoded cache，以及 edge/crop/filter/split 对同一 decoded result 的消费；动画/region 只定义带 purpose 的扩展口，不切 presentation，预计 6～10 个文件。
- `RUA-04C` 逐一把 Single、Dual、Webtoon 切到稳定 image asset/state；本子批必须同时覆盖三种 presentation，避免保留某一种 UI 私有 fetch，预计 6～12 个文件。
- `RUA-04D1` 先在唯一 content/image pipeline 内建立动画 purpose dispatcher 与 frame lease，静态页不得回落到第二条 loader；预计 4～8 个文件。
- `RUA-04D2` 在 04D1 dispatcher 冻结后接入超大图 preview/region tile owner 与独立有界预算；预计 4～8 个文件。
- `RUA-04D3` 覆盖 full/frame/region 的 Retry、cancel 与 stale attempt 线性化，同 generation 的旧 attempt 不得覆盖 Retry 结果；预计 4～8 个文件。
- `RUA-04D4` 收口 detach/recycle/close、非协作迟到结果、runtime 关闭顺序与 decoded/derived/frame/tile 统一内存权威；预计 4～8 个文件。
- `RUA-04D5` 只在 04D1～04D4 全绿后删除 legacy loader/painter/preloader owner，并运行最终 presentation/architecture 矩阵；预计 4～8 个文件。

04A → 04B → 04C → 04D1 → 04D2 → 04D3 → 04D4 → 04D5。双页配对、手势和一般 viewport 几何始终不属于这些子批次；如 cutover 需要改变它们，必须单独 replan。

**RUA-04C Webtoon 首次 viewport 微重划（2026-08-30）**：production-mounted RED 证明 Webtoon 在页表异步到达、尚无 `currentDisplayUnitId` 与恢复 anchor 时，没有 viewport effect 就不会安排首个可见页，页面会永久停留在 queued。04C 因而只增加一个 provisional first-visible bootstrap：仅在 display identity 与 anchor 同时为空时，对当前 display unit 提交一次零偏移 viewport；任一恢复身份存在时禁止提交，同一 identity 不重复提交，真实 `LazyColumn` settled viewport 一到即永久接管。该例外不改变滚动几何、anchor 恢复、手势、双页配对或后续 viewport 算法，也不得扩展成第二套 viewport owner。

**RED**：

- production runtime + mounted Single/Dual/Webtoon 中，标准静态页的同一 `(chapter,page,generation)` 首帧前 `OPEN_PAGE > 1` 或 full-page `DECODE > 1`；
- 恢复 `PagePreloader` 独立 scheduler、Coil URL fetch、`loadLocalPageBitmap` 或 UI 直接 `store.read()` 任一路径时测试没有失败；
- edge matcher/crop/filter/split 再次打开源内容；
- 快速翻页后 stale decode 覆盖新 generation。

**GREEN**：

- 引入唯一 `DesktopReaderPageImagePipeline`（最终命名可按现有架构调整），只消费 shared visible-content effects 与 opaque ref；
- 标准静态页 single-flight 打开一次并选择一个 decoder，结果进入一个有界 decoded cache；动画/超大图允许同一 owner 持有源 lease 并执行有目的的 frame/region decode，必要的 region reopen 必须带 purpose 计数，但不得并行启动第二个 full-page owner/decoder；
- Compose presentation 只接收稳定 image state/asset，不接 URL、File 或 store reader；
- `EdgePixelMatcher`、裁边、滤镜和宽图拆分消费同一 decoded result/metadata；
- 删除 `loadLocalPageBitmap`、独立 preloader scheduler/read/decode 与对应 source-string 假证明；如保留 `PagePreloader` 名称，只能是 shared effect 的薄 decoded-cache adapter，不得拥有 I/O/调度；
- detach、recycle、切章和 close 释放 single-flight lease 与 archive stream。

**重构/边界**：presentation identity、双页配对、手势和一般 viewport 行为不变；唯一例外是上文已重划且受 one-shot gate 约束的 Webtoon 首次可见页 bootstrap。不得顺手重做 UI。

**focused 验证**：三 presentation × download directory/CBZ/online 的 open/decode matrix；动画、超大图、crop/split/filter、edge matcher、Retry、cancel/stale generation、内存预算。

**预计**：父 RUA 总计约 5～8 工程日；这是最高风险切点，预算按 04A～04D 分别计算，且必须在 04D 全矩阵通过后才能删除 legacy renderer 分支。

**`RUA-04A` 完成证据（2026-08-30）**：

- shared `ReaderPageContentOpenCoordinator` 以 opaque content key 提供 single-flight 与引用计数 lease；Desktop production runtime 只创建一个 `DesktopReaderPageContentOwner`，`PagePreloader` 与可见页消费者共享同一次 `store::read` 打开。
- 取消、close、非协作解码和 prompt-cancellation 均由显式 lease handoff 收口；只有外层消费者实际取得结果后才转移所有权，迟到或未认领内容会关闭且不能重新进入 active snapshot。
- TDD 证据覆盖缺 API、重复 physical open、close race、非协作解码和 child 已完成但 outer 尚未恢复的取消窗口；最终 `.gradle-coordinator/rua04a-prompt-close.log` 完成 root Spotless、shared coordinator、PagePreloader、runtime/materialize、章节切换与 architecture guard，`BUILD SUCCESSFUL in 15m 13s`。
- 独立审查先发现 2 个 P1 与 1 个 P2，第一次修复复审发现 1 个残余 prompt-cancellation P1；限定复审确认全部关闭，最终为 `PASS`。本批 9 个 product/test 文件共 `789+/35-`，超出行数估算来自同一 lease 生命周期及确定性竞态测试，未拆开不可独立验收的上下文；Desktop 正式构建仍只在 `RUA-07` 执行。

**`RUA-04B` 完成证据（2026-08-30）**：

- domain 新增稳定 `ReaderPageDecodeKey` 与 `FULL_PAGE / ANIMATION_FRAME / REGION_TILE` purpose，键显式包含 content identity、generation、decode bounds、region 和 frame；Desktop runtime 只创建一个 `DesktopReaderPageImagePipeline`，以同 key single-flight 共用唯一 content open 与 decoder，物理解码并发上限为 3。
- decoded asset 使用显式引用计数 lease；access-order LRU 同时受 7 项与 128 MiB 双预算约束。cache eviction 不会释放仍由可见 presentation 固定的 asset，换页、clear、generation 切换与 runtime close 会释放相应 lease；非协作迟到 decode 不能重新安装旧 generation。
- `PagePreloader` 已降为只注入 shared pipeline 的兼容适配器，不再创建 scope、scheduler、content owner、decoder 或 cache。edge matcher、crop、filter、split 的契约测试均消费同一 decoded asset 且保持 `OPEN=1 / DECODE=1`；实际 Single/Dual/Webtoon Compose cutover 仍属于 `RUA-04C`，manifest ID 44 因 region adapter 尚未进入 production consumer 而诚实保持 `SHARED`。
- 严格 TDD 证据包括 `rua04b-domain-red2`、`rua04b-pipeline-red`、`rua04b-runtime-red`、bounds/region/concurrency/visible-pin RED，以及对应 GREEN。独立审查发现 2 个 P1 与 1 个 P2（第二 owner、permit 生命周期、bounds/pin），限定修复复审结论为 `PASS`、P0/P1/P2 均为零。
- 首次合并收口暴露 parity role evidence 漂移，批量静态扫描并修正后 `rua04b-parity-contract-green3` 通过；第二次收口又以 production fixture 暴露 04B→04C 期间 legacy presentation/shared pipeline 的同 identity decode 时序竞态。`rua04b-critical-cost-green4` 证明该过渡重复严格有界为 2，并仅为 1/180 页结构成本比较归一化这一项；`RUA-04C` 必须删除归一化并恢复 mounted presentation `DECODE == 1`。最终 `rua04b-close3` 完成根级 Spotless、domain key contract 与 129 项 Desktop focused tests，`BUILD SUCCESSFUL in 5m 6s`。
- 本批 20 个 product/test/manifest 文件约 `2142+/838-`，超过 6～10 文件提示值的主要原因是删除 358 行 legacy preloader owner、把三处既有测试迁到 test-only factory，以及为 single-flight、双预算、并发、取消和 lease 生命周期提供同一内聚行为证据；没有切 presentation、手势、viewport 或双页配对。两次误用不存在的模块级 Spotless task 各在约 8 秒内失败且没有执行测试，后续统一只使用根级 `spotlessApply/spotlessCheck`。Desktop 正式构建仍只在 `RUA-07` 执行。

**`RUA-04C` 完成证据（2026-08-30）**：

- Single、Dual、Webtoon 三种 production presentation 均改为消费 runtime 唯一 `DesktopReaderPresentationImageOwner` 提供的稳定 image state/asset；挂载目录、CBZ 与在线三类来源时，同一 page/generation 的首帧严格保持 `OPEN_PAGE == 1`、full-page `DECODE == 1`。UI 不再接收 URL/File/store reader，也不再通过 Coil painter、`loadLocalPageBitmap` 或 legacy preloader 建立第二条读取/解码链。
- render、crop/filter/split transform 与右键菜单均持有显式 lease：runtime/holder close 不会提前释放正在绘制或复制的 bitmap，stale/非协作迟到 transform 不能覆盖新身份；真实 Skia ownership 测试确认裁边替换 source-bounds 中间 bitmap 时立即关闭 owned intermediate，Canvas/Image 也按作用域释放。右键复制/保存复用稳定 decoded asset 与精确 source bounds，不重新读取或解码内容。
- production-mounted RED 证明 Webtoon 在 page-list 异步到达且 display identity/恢复 anchor 均为空时会永久 queued，因此按本节微重划增加 one-shot provisional viewport bootstrap；真实 settled viewport 到达后永久接管，恢复 anchor、滚动几何、手势与双页配对语义不变。
- TDD 证据覆盖 presentation owner、绘制确认竞态、transform stale/close、真实 transform-chain ownership、右键菜单、Webtoon bootstrap，以及三 presentation × 三来源 mounted matrix。`.gradle-coordinator/rua04c-close.log` 完成 root Spotless 与 17 个相关测试类，`BUILD SUCCESSFUL in 5m 10s`；后续限定修复由 `rua04c-bounded-close` 与 `rua04c-transform-chain-green` 通过，最终 `.gradle-coordinator/rua04c-final-close.log` 再次通过 root Spotless 与 8 个关键测试类。首次误用 `./gradlew` 在 Windows 进程创建阶段立即失败，改用 `.\\gradlew.bat`；一次 78 项收口测试暴露并修复 encoded-store close/reconcile 的真实竞态，不作为 flaky 重跑。
- 独立审查先发现 render lease、stale transform、context-menu production 证据、acknowledgement 竞态与 Webtoon bootstrap 边界问题；修复复审进一步发现 native Skia intermediate 生命周期与 roadmap 微重划遗漏，均以有界 RED/GREEN 收口，最终限定确认结论为 `PASS`、P0/P1/P2 均为零。本批横跨 presentation/runtime/测试的文件数超过原估算，是三种 presentation 原子 cutover、删除假阳性测试和同一 asset 生命周期修复所必需；Desktop 正式构建仍只在 `RUA-07` 执行。

**`RUA-04D1` 完成证据（2026-08-30）**：

- 唯一 `DesktopReaderPageImagePipeline` 现在按 `ANIMATION_FRAME + frameIndex + generation + bounds` 解码真实 Skia 动画帧；FULL decode 在同一次 encoded open 中发现 frame metadata，挂载后的 Single/Dual 共用引用计数 animation content owner，Compose 自动按原始 duration 播放且 transform/draw lease 使用完整 frame key。静态 PNG/JPEG 仍只走一次 `FULL_PAGE`，不会启动 frame loader；动画 frame 明确不进入普通静态 LRU。
- Skia 编码循环次数按“首轮之后的额外重复次数”保留，有限循环停在末帧、无限循环持续；透明局部帧、`restoreToPrevious`、有界降采样透明清屏与真实颜色均由 production decoder fixture 验证。下一帧返回空时停止动画并保留最后成功帧，不把既有 Ready 页面重新变成 spinner/error。
- source/frame/transform 资源均以显式 lease 收口：同 key 两个 holder 并发时，首 holder detach 不会取消存活 holder；最后一个 holder、generation 前进或 runtime close 才释放共享 source。stale 非协作 frame、frame replacement、旧 draw pin与 sample allocation/异常路径均保证不提前释放且最终清理。
- 严格 TDD 证据包括 `rua04d1-red`、`rua04d1-real-red`、`rua04d1-wiring-red2`、`rua04d1-semantic-red`、`rua04d1-shared-session-red`、`rua04d1-frame-failure-red` 及对应 GREEN；`rua04d1-bounded-fix-green2` 通过 ownership、真实 GIF、透明采样与空帧生命周期。独立审查发现 shared-session 竞态、frame cache purpose、sample/native 资源和证据缺口，经限定修复复审最终为 `PASS`，P0/P1/P2/P3 均为零。
- 本批包含 6 个 product 文件、3 个 test 文件和本 roadmap，超过 4～8 文件提示值是因为 real Skia decoder/controller、mounted production autoplay 与并发 lease 证据必须原子交付；没有改变双页配对、手势、一般 viewport 或 Retry attempt 语义。最终 `.gradle-coordinator/rua04d1-close.log` 完成 root Spotless 与动画/静态回归 focused matrix，`BUILD SUCCESSFUL in 5m 30s`；Desktop 正式构建仍只在 `RUA-07` 执行。

**`RUA-04D2` 完成证据（2026-08-30）**：

- 大于 1600 万源像素的静态页现在先显示 shared pipeline 的有界 preview，再由同一 encoded source session 按真实 source bounds 解码可见 region tile；普通静态页与动画页不启动 region owner。ImageIO adapter 使用 source-region 与 subsampling 做有界 raster decode，不支持的格式直接局部降级，不回落到第二次 full-page decode。
- region tile 使用独立的 8 项/64 MiB LRU，只有 current mounted holder 同时满足 generation、requested key 与 running job admission 后才能写入；tile commit、hit 与 eviction 不推动 ordinary decoded `cacheRevision`。holder/owner/generation/pipeline close 后，即使 decoder 非协作迟到，也不能回写 state/cache，source 与 tile asset 最终精确释放；null、异常与不支持格式只保留 preview，并不阻断后续正常 tile。
- Single、Dual、Webtoon 三种 production presentation 均挂载真实 tile layer；split/crop 使用 transform 后的原始 source bounds 对齐。Webtoon 从 `LazyList` 的真实 item/viewport 交集只保留当前可见条带，离屏 item 请求为空，滚动后旧 tile draw/cache lease 释放。tile 选择、可视交集与 Canvas 绘制复用同一 `roundToInt` 整数几何，消除边界 1 px 分歧；离屏 framebuffer 测试验证三种 viewer 与 split+crop 都实际绘出 tile 像素，而非只检查请求或源码符号。
- 严格 TDD 先由 `.gradle-coordinator/rua04d2-review-red2.log` 复现 cache revision、late-result、failure isolation、Dual 像素与 Webtoon 整页请求问题；修复后 `.gradle-coordinator/rua04d2-lifecycle-green3.log` 通过四种终止方式与局部失败矩阵，最终 `.gradle-coordinator/rua04d2-close.log` 完成 root `spotlessCheck` 与 15 个 region/pipeline/animation/transform 回归类，`BUILD SUCCESSFUL in 3m 36s`。独立审查发现 3 个 P1 与 1 个 P2，限定复审最终为 `PASS`，P0/P1/P2 均为零。
- 本批包含 10 个 product 文件、7 个 test 文件和本 roadmap，超过 4～8 文件提示值是因为 bounded native decoder、source/cache/lifecycle owner、三种 mounted presentation 与真实像素/滚动证据必须作为一个可独立验收的 region-tile 能力交付；没有改变 Retry attempt、双页配对或一般手势语义。Desktop 正式构建仍只在 `RUA-07` 执行。

**`RUA-04D3` 完成证据（2026-08-30）**：

- shared reader session 为每个逻辑页增加同 chapter/session generation 内单调递增的 `attemptGeneration`。Retry 原子地只把目标页重置为 Queued、清空旧 image URL 与 encoded ref，并调度新的 P0 `EXPLICIT_RETRY + forceRefresh`；其他页及 chapter/session generation 保持不变。Desktop materialize runtime 同时校验 scheduler job key、fetch/binding attempt 与当前页 attempt，取消不协作的旧 terminal 也不能发布或覆盖新 attempt。
- full page、animation frame 与 region tile 的 content/decode identity、in-flight、ordinary/tile cache、shared content session 和 presentation holder 均显式包含 attempt。推进 attempt 只取消并驱逐同 page+generation 的旧 attempt，保留其他页；ordinary cache revision 仅在实际移除 ordinary full cache 时推进。Compose remember、preloader 以及 Single/Dual/Webtoon 三种 production presentation 全部透传 attempt；Retry 在 ref 尚未重新生成时也会立即 fence 旧 bitmap/pin。
- materialize 已 Ready 但 FULL decode 失败时，三种 viewer 不再永久转圈，而是显示现有“加载失败/重试”反馈并把精确 `ReaderPageId` 回传到 `ReaderScreenModel → runtime.session.retryPage`。animation frame 失败仍保留最后成功帧，region tile 失败仍保留 full preview，两类局部失败不会升级成整页 Retry。
- 严格 TDD 证据包括 `.gradle-coordinator/rua04d3-domain-red.log`、`rua04d3-domain-green.log`、`rua04d3-desktop-red.log`、`rua04d3-wiring-red2.log`、`rua04d3-core-green.log`、`rua04d3-decode-ui-red2.log` 与 `rua04d3-decode-ui-green.log`。首次 `rua04d3-close` 的 92 项中只有既有 Single-page Compose 单次渲染断言发生一次时序抖动；`rua04d3-single-regression-repro` 单独复现全绿，随后 `rua04d3-final-close.log` 完成 root `spotlessCheck` 与 16 个相关 Desktop production-wiring/回归类，92 tests 全绿并 `BUILD SUCCESSFUL in 4m 58s`；领域 focused matrix 也已全绿。
- 正式独立只读审查结论为 `PASS`，P0/P1/P2/P3 均为零。跨 owner 的 attempt 推进在 generation 并发时保持 fail-closed，不会接受旧身份；锁序未形成反向环，detached deferred、cache lease、shared content 与 holder close 未见双关或泄漏。本批包含 17 个 product 文件、7 个 test 文件和本 roadmap，超过 4～8 文件提示值是因为 shared/session/runtime/full-frame-region/preloader/三 presentation 的 attempt 身份必须原子对齐，拆开会产生可编译但语义失配的中间状态；没有进入 D4 的通用 close/统一预算或 D5 的 legacy owner 删除。Desktop 正式构建仍只在 `RUA-07` 执行。

**`RUA-04D4` 完成证据（2026-08-30）**：

- Desktop runtime 现在以唯一 192 MiB soft authority 统计 decoded FULL、region TILE、animation FRAME 与 presentation DERIVED 的实际 allocation；多 lease 不重复计数。ACTIVE/IN_FLIGHT pin 不会被强制回收，超预算时按跨 purpose LRU 驱逐未固定 cache retention。普通 cache 与 tile cache 只发布 authority 已接纳的 allocation identity；provisional replacement 对 snapshot/acquire 不可见，clear/replacement/eviction 也不会留下孤儿 retention。
- session generation 在 state 发布前同步 fence presentation/region/pipeline；runtime close 对并发 caller 只执行一次全部阶段，等待者取得同一 outcome，同线程重入不会自锁。PagePreloader、pipeline attempt/generation、static/animated/region holder 与 transform owner 均使用 first-plus-suppressed 的 exception-complete 回收；非协作迟到 decode、cache dispose 抛错和 derived registration 失败仍会释放所有后续资源，DERIVED native bitmap 与 FULL base 各恰好关闭一次。
- 严格 TDD 证据包括 `.gradle-coordinator/rua04d4-red2.log`、`rua04d4-green4.log`、`rua04d4-p1-red.log` 与 `rua04d4-p1-green.log`。首次相关收口暴露 throwing-disposer 的虚拟时钟等待和旧 64 MiB tile 压力 fixture，第二次暴露 Retry remount 的单帧 Compose 时序假设；对应失败类分别经 `rua04d4-close-failures-green` 与 `rua04d4-animation-remount-green` 变为确定性有界测试。最终 `rua04d4-final-close3.log` 完成 root `spotlessCheck` 与 31 个 D1～D4 production-wiring/回归类，179 tests 全绿，`BUILD SUCCESSFUL in 6m 24s`。
- 两轮独立只读审查先后发现 cache/authority 两阶段竞态、exception-complete 缺口、旧 runtime 预算断言、DERIVED production wiring，以及 provisional snapshot 与 post-registration 双 close 两个残余 P1。所有 finding 均由 allocation identity、完整资源动作矩阵和真实 transform failure RED 关闭；未发现进入 D5 legacy 删除、手势、双页配对或 viewport 语义的范围漂移。
- 本批包含 10 个 product 文件、11 个 test 文件和本 roadmap，超过 4～8 文件提示值，是因为 runtime/session、FULL/TILE/FRAME/DERIVED 四类 allocation 与 static/animated/region presentation 的 close 顺序必须作为一个生命周期矩阵交付；拆分会产生部分 owner 仍不受统一 authority 或异常中止回收的不可验收状态。Desktop 正式构建仍只在 `RUA-07` 执行。

**`RUA-04D5` 完成证据（2026-08-30）**：

- Desktop production 已删除 `PagePreloader`、`PreloadedPageBitmap`、runtime `preloader/pageIoObserver` 暴露、独立 current-page preload effect 与旧 cached-bitmap transform 重载。edge matching 只通过唯一 `DesktopReaderPresentationImageOwner` 保留 ordinary cache 中当前 attempt 的 `FULL_PAGE` lease；整轮 matcher 期间持有、成功/异常/取消时 exception-complete 释放，不会触发新的 open/decode。Gradle task2/task3 的旧测试 include、ID9/ID43 的现役 owner 路径和旧方法证据也同步迁移。
- 严格 TDD 先以 `.gradle-coordinator/rua04d5-consumer-red.log` 证明 presentation owner 缺少只读 cached lease 契约，再经 `rua04d5-green2.log`～`rua04d5-green4.log` 关闭测试迁移、真实 encoded-store ref 与 mounted I/O 时序问题。独立审查随后用可控 matcher 发现 Retry revision 期间旧配对会永久写入；`.gradle-coordinator/rua04d5-stale-match-red.log` 确认旧实现错误发布 `[(0, 1)]`，`rua04d5-stale-match-green.log` 在 `collectLatest + revision fence` 后验证只发布空结果、只剩一个 cache entry、`pinnedBytes == 0` 且 `residentBytes == cacheBytes`。
- 独立只读审查最初结论为 2 个 P1、2 个 P2：两个 P1（manifest/contract 残留与 stale edge-match 发布）全部关闭；限定修复复审为 `PASS`，没有 P0/P1。duplicate pageIndex MRU、显式 matcher throw/owner-close 和 revision check 至 callback 的理论窄窗保留为 RUA-06 production-mutation/并发证据强化，不阻塞已验证的唯一 owner 删除。
- 最终 `.gradle-coordinator/rua04d5-final-close.log` 完成 root `spotlessCheck` 与 19 个 runtime/presentation/IO/architecture 类，103 tests 全绿，`BUILD SUCCESSFUL in 8m 4s`。monolithic `DesktopProductCapabilityContractTest` 在此前 focused 运行中已通过 D5 修改的 behavior evidence，但 exact contract 继续暴露 ID45/RUA-05 默认预取与 RUA-06C authority 的既有陈旧行号；本批没有用提前改写后续状态来制造假绿，该已知门禁保留到 05 完成后的 06C 统一关闭。
- 本批包含 7 个 production/build 文件、14 个 test/fixture 文件（其中 2 个删除）和本 roadmap，共 22 个文件、约 `606+/945-`；超过 4～8 文件提示值是因为最后一个 runtime owner 的删除必须同时迁移 cache consumer、lifecycle、architecture、Gradle include 与 machine authority 证据，拆开会留下已删除类型仍被编译或治理任务引用的不可验收状态。没有改变相邻章默认语义、偏好 UI 或 opt-in decorator；Desktop 正式构建仍只在 `RUA-07` 执行。

### `RUA-05` 原版相邻章默认语义与 Desktop opt-in decorator

**子批次边界与依赖**：

- `RUA-05A1` 先在 shared 层建立 canonical last-five page-list-only policy，并让 Android Pager/Webtoon consumer 消费同一 effect：第 6 页不触发、第 5 页恰好一次，且不打开下一章图片；预计 5～9 个文件。
- `RUA-05A2` 等 05A1 shared API 冻结后，把 Desktop session 的私有阈值与 visible-set 最大页判断替换为 anchor page 对 shared effect 的消费；不引入图片预取 decorator，预计 2～4 个文件。
- `RUA-05B` 只处理默认值、已有显式值保留、偏好读取/迁移和设置 UI 文案/wiring；不启动任何 prefetch job，预计 3～6 个文件。
- `RUA-05C` 在 05A/05B 上实现显式 opt-in decorator，等待首帧与 idle，以 P4 materialize encoded 内容，并覆盖抢占、quota、cancel、target switch 和无进度副作用；预计 4～8 个文件。

05A1 → 05A2；04D 完成后，05A1/05A2 链与 05B 可并行推进，但 05C 必须等待 05A2 + 05B。Android 不消费 05C，且 05C 不得反向改变 05A 的 canonical 默认。

**RED**：

- 当前下载章页表全 Ready 时，首帧前就出现 next chapter page-list/open；
- 距末尾第 6 页触发 metadata，或进入末五页后请求下一章图片；
- 默认偏好仍是 `FULL_NEXT_CHAPTER`；
- explicit decorator 抢占 P0、写进度、保留整章 decoded bitmap或切换目标后继续运行。

**GREEN**：

- canonical shared policy 精确恢复末五页 page-list-only；第 6 页不触发，第 5 页触发一次；
- 默认图片预取为关闭；已有显式 `FIRST_VIEWPORT/FULL_NEXT_CHAPTER` 值保留；
- opt-in decorator 必须等待 `FIRST_PAGE_PRESENTED + idle`，只以最低优先级 materialize encoded 内容，P0 可抢占，切章/配额/失败可取消；
- next metadata/prefetch 不产生进度副作用；
- 更新 Reader 设置文案和 wiring 测试，清楚区分 metadata 与图片预取。

**重构/边界**：Desktop 增强不进入 Android 默认，也不改变 shared original policy。

**focused 验证**：last-five 精确边界、默认/显式偏好迁移、P0/P4、quota/cancel/target switch、首帧隔离、无 progress side effect、设置 UI wiring。

**预计**：父 RUA 总计约 2～4 工程日；文件和验证预算按 05A～05C 分别计算。

**完成证据（2026-08-31）**：

- shared `ReaderAdjacentChapterPolicy` 成为 canonical 末五页 page-list effect 的唯一语义；Android Pager、Dual Pager、Webtoon 与 transition consumer 均通过 typed `ReaderActivity` seam 消费。Robolectric production wiring 逐个验证 index 4 不触发、index 5 恰好触发一次和 transition 无条件触发，能杀死 `page.index` 误写为 `page.number` 的 off-by-one 回归。
- Desktop session 只按最后 settled anchor 消费 canonical metadata effect，不再用 visible-set 最大页；canonical page-list-only 不受首帧门禁。显式图片 decorator 默认 `OFF`，仅在当前 generation 首帧已呈现、当前章全 Ready 且 shared scheduler idle 后以 P4 进入同一 core；P0 抢占、quota、cancel、target switch、失败与 progress 隔离均由 session/runtime 行为测试覆盖。`pollNext` 在排空已 Ready 的 scheduler work 后重新评估 idle，关闭了显式 decorator 永久不启动的 liveness 缺口。
- Reader 设置标题与中/繁/英文说明改为“图片预取”，明确区分始终保留的末五页下一章页表 metadata 与显式 encoded image prefetch；新安装默认关闭，new key 优先，旧 `FIRST_VIEWPORT/FULL_NEXT_CHAPTER` 仅在新 key 未设置时迁移保留。真实设置页面点击、持久化和搜索结果均有 wiring 测试。
- RED 证据分别保存在 `.gradle-coordinator/rua05a1-domain-red3.log`、`rua05b-red.log`、`rua05a2c-red.log` 和 `rua05-android-viewer-red2.log`；focused GREEN 证据为 `rua05a1-domain-green.log`、`rua05a1-android-green.log`、`rua05b-green.log`、`rua05a2c-green3.log` 与 `rua05-android-viewer-green2.log`。最终相关矩阵 `rua05-final-domain.log`、`rua05-final-android.log`、`rua05-final-desktop2.log` 全绿，root `rua05-spotless.log` 通过；Desktop 首次矩阵暴露的两条旧默认/首帧夹具假设由 `rua05-desktop-fixture-fix.log` 定点复验关闭。
- 独立只读审查最终为 `PASS_WITH_P2`，无 P0/P1。null-binding 防御重评估、`allowPreload=false`/单页/holder retry 的补充 consumer 覆盖和 legacy 显式 `OFF` 迁移枚举登记到 RUA-06；manifest、fixed-main 与 ID45 authority 继续留给 RUA-06C，未用提前改写机器状态制造假绿。
- 本批共 24 个内聚文件（含本文）；超过子批次文件提示值来自 shared+Android 三类真实 viewer wiring、Desktop 并发/代际门控与设置 production wiring 必须在同一语义冻结点一起交付。没有执行全量发布测试或 Desktop 正式构建，它们按分层验证规则集中在 RUA-07，避免每个功能批重复消耗 Gradle/打包时间。

### `RUA-06` 假阳性测试、旧 owner、authority 与文档清理

**子批次边界与依赖**：

- `RUA-06A` 根据 RUA-03/04/05 已绿的 production 路径删除旧 loader/painter/preloader/session orchestration owner、过期 DI 和不可达 helper；每次删除都由现有行为测试保护，预计 4～8 个文件。
- `RUA-06B` 用 production mutation/IO probe 证据替换源码 marker、自洽 fake 和第二链保护测试；静态 guard 只证明禁止依赖不可达，预计 4～8 个 test/fixture 文件。
- `RUA-06C` 在代码与测试冻结后更新 authority、shared-core、历史 supersede 链接、fixed-main fixture 和 manifest evidence；只做实际 capability 状态转换，预计 4～8 个 fixture/doc 文件。
- RUA-05 审查遗留的非阻塞强化按既有边界吸收：06A 统一 null-binding/completed-without-materialization 的 idle 重评估；06B 补 `allowPreload=false`、单页快速路径、transition holder retry 与 legacy 显式 `OFF` 迁移枚举的 production/compatibility 证据；06C 才更新默认预取与 ID45 authority。

06A → 06B → 06C。06C 是纯治理子批次，不能用文档状态掩盖 06A/06B 尚未通过的 production mutation。

**RED**：

- architecture mutation 恢复 UI source fetch、第二 scheduler、直接 File/URI 打开、首帧前 store scan 或默认 full-next 时，production behavior tests/guard 任一不能失败；
- manifest/fixture 仍引用源码 marker 作为 single acquisition/TTFF 完成证据；
- architecture 文档仍宣称已删除实际仍存在的 owner。

**GREEN**：

- 删除或改写 `DesktopReaderProductRegressionTest` 的源码字符串假阳性、`LocalPageBitmapTest` 的第二链保护和只证明 preloader 自洽的完成证据；
- 静态 `ReaderArchitectureGuardTest` 只守“旧依赖不可达”，完成证明引用 production integration/IO probe；
- 删除 legacy loader/painter/preloader/session orchestration owner 和过期 DI；
- 更新 `reader-authority.md`、`reader-shared-core.md`、历史 Reader roadmap 的有限 supersede 链接、fixed-main fixture 和 manifest evidence；
- 只更新实际受影响 capability，不反向覆盖 manifest，也不把 Desktop decorator伪装成 fixed-main。

**重构/边界**：文档描述设计目的、失败处理和 adapter 边界；历史 RED/GREEN/commit 记录原样保留。

**focused 验证**：architecture mutations、authority/deviation tests、manifest JSON parse、文档链接、`git diff --check`。

**预计**：父 RUA 总计约 2～3 工程日；文件和验证预算按 06A～06C 分别计算，原则上无新的产品范围。

**完成证据（2026-08-31）**：

- 06A 删除已无 production caller 的 `DesktopPageCache`、`CropBorderScanner`、`DebugLogger`、`DualPageLayoutPolicy` 及其旧测试，把保存、source fetch、Single/Dual/Webtoon presentation 与 transform 测试迁回现役 runtime/pipeline 语义；`ReaderProgressTracker` 的 DI alias 因仍有 production consumer 而保留，没有为了清单删除现役能力。`DesktopReaderSession` 同时统一 null binding 与已 Ready completion 的 shared-idle 重评估。
- 06B 新增挂载真实 `DesktopReaderRuntimeFactory` 的 production mutation evidence：Single/Dual/Webtoon directory、Single CBZ/online 均以实际像素证明首帧恰好一次；rejecting decoder 同时杀死 File/URI/painter 绕过。Android Pager/Dual 的 `allowPreload=false`、单页 busy→idle 快速路径揭示并修复重复 adjacent dispatch；legacy 设置迁移显式枚举 `OFF/FIRST_VIEWPORT/FULL_NEXT_CHAPTER`。源码 marker、自洽 owner 数量与复制算法不再作为正向完成证据，architecture guard 只保留禁止 legacy/旁路 I/O 的职责。
- 06C 将 manifest ID 44 提升为 `VERIFIED` 并登记 bounded-region production consumer，将 ID 45 默认值、canonical metadata 与 opt-in 图片 decorator 三个 claim 关闭，同时保留 ID 9 的既有 broad capability 和 RUA slice 关闭状态；fixed-main fixture、reader authority/shared-core 文档及历史 supersede 链接均与现役行为一致，未改写 fixed-main path/blob inventory。现役 role-evidence locator 已一次性静态核对，固定上游快照定位保持不动。
- 严格 TDD 证据：`.gradle-coordinator/rua06b-mutation-red.log` 先因 mounted fixture 尚未注入 production decoder 而失败；Android `.gradle-coordinator/rua06b-android-green.log` 先暴露重复 dispatch，`rua06b-android-green2.log` 修复后通过。Desktop production/legacy/architecture focused 矩阵 `rua06ab-desktop-green.log` 通过；authority 逐项清除旧 method、marker 与 locator 后由 `rua06c-authority-green8.log` 通过最终合同类，批次收口日志统一记录最终 authority 与格式门禁。
- 独立审查发现 ID43 已删除路径与 ID44 region role 两个 P1，均已修正；限定复审结论 `PASS_WITH_P2`，P0/P1 为零。唯一非阻塞 P2 是 bounded-region 尚未加入同一 mounted mutation matrix；现有真实 region decode、attempt/generation、tile lifecycle 与 memory-budget production 测试继续承担该能力证据，最终跨平台矩阵和正式构建仍只在 `RUA-07` 执行。
- 本批跨越旧 owner 删除、production mutation、Android consumer 修复和机器 authority，文件数超过各子批次单独提示值，但三条并行工作流在接口冻结后无同文件冲突，最终作为一个可独立编译、审查和提交的清理批次收口；没有新增 Reader 产品能力，也没有提前执行发布构建。

### `RUA-07` 跨平台全量验证、Test Mode、正式构建与关闭审计

**子批次边界与依赖**：

- `RUA-07A` 在冻结提交上运行 shared domain、Android、Desktop、storage/HTTP integration、architecture/authority、`spotlessCheck` 与 `finalParityAudit`；只修确定的回归，不顺手新增能力。
- `RUA-07B` 运行 Test Mode、production-mounted deterministic TTFF、Windows 性能预算，以及 downloaded directory/CBZ/local archive/online 的手动可执行验收。
- `RUA-07C` 对同一提交运行 Windows `scripts/build-desktop.sh`、macOS production fixture/build 和 Android debug assemble，并保存真实发布产物路径与日志。
- `RUA-07D` 只在 07A～07C 全部通过且提交未漂移后更新 manifest、本文、父路线和唯一 active plan；任何构建后代码变化都会使 07A～07C 证据失效。

07A → 07B → 07C → 07D。07A～07C 是验证子批次，可各自形成证据提交或与唯一必要修复提交合并；不得为纯 checkbox 推进额外创建无内容提交。

**前置**：RUA-00～RUA-06 均已实现、独立审查、focused tests 和提交完成；没有遗留双轨 production seam。

**验证**：

- 共享 domain、Android Reader、Desktop Reader、storage/HTTP integration、Test Mode 和 architecture/authority 全量；
- `spotlessCheck`、`finalParityAudit`；
- Windows `scripts/build-desktop.sh` 正式未打包应用与 production runtime 验收；
- macOS 同提交构建和 Reader production fixture；
- Android 单元/集成测试与 debug assemble，因为 shared route/runtime 已改变；
- 第 11 节 deterministic/真实性能预算；
- 真实已下载目录、下载 CBZ、local archive、online 各一次手动可执行验收。

**关闭**：manifest、父路线、本计划、architecture 文档、构建产物和 commit 一致后，才勾选 RUA-07；若要恢复作者归档或非 Reader 计划，按第 2.2 节反向原子切换唯一 active-child-plan。

**预计**：2–4 工程日，主要成本是 Gradle、Windows/macOS 打包、Test Mode 和真实运行等待。

**RUA-07 精确提交检查点（2026-09-01，`4ffd026cc65e3d96c35fd952bc493dc106ef019e`）**：

- `RUA-07A` 已完成。同一精确提交上的 composite 验证覆盖 shared domain、data、Android unit、`test-desktop`、`spotlessCheck` 与 `finalParityAudit`，结果为 `PASS`，且 `FINAL_PARITY_AUDIT_NON_TERMINAL_IDS=` 为空；Windows official build 另行补齐完整 `app-desktop:jvmTest` 并通过。此前两次被取消的 monolithic full matrix 不计为通过证据。
- `RUA-07B` 未通过。首轮 Windows fresh-process 性能中，downloaded directory 的 P95 为 2365 ms、max 为 2742 ms，超过 1000/2000 ms 门限；downloaded CBZ 的 P95 为 1250 ms、max 为 1284 ms，满足 1500/3000 ms 门限。两条路径的当前页 I/O gate 均正确，但不能抵消 wall-clock 性能失败。后续在同一持续 x265 高负载下完成 v18→v19→v18→v19 缩样对照：directory 两个版本都出现约 1.04～1.07 秒峰值，CBZ 的 v19 反而更快；没有证据表明 lifecycle lease 造成打开路径回归或新旧 runtime 重叠。v19 关闭确认比 v18 稳定增加约 770 ms，是等待 outgoing composition 离开后再释放 runtime 的预期代价，不计入首帧事件预算。
- 性能 runner 已通过严格 TDD 增加全部 measured samples、四段 production event 时序和逐样本 I/O gate，保留既有 P95/max/末次 gate 兼容字段，并拒绝倒序 monotonic timestamp。初始 RED `rua07-perf-observability-red` 因缺少 samples 失败；审查修复 mutation RED `rua07-perf-review-red` 恰好捕获 warmup 污染与倒序时间戳两个缺口；最终 `rua07-perf-review-green` 5/5 及 `rua07-perf-observability-close`（相关 Python client contract + 根级 Spotless）通过，限定复审为 `PASS`。
- 一次计划作为空闲正式证据的 v19 1+20 在启动 5 秒后被外部 ffmpeg 视觉抽帧进程占用约 31% CPU，故其完整 42 次 fixture 结果只作为诊断样本、明确标记为 `INVALID`，不得用于关闭门禁。该样本显示 directory 慢点集中在 intent→page-list，而 CBZ 慢点集中在 page-list→open；两路 decode→present 大多稳定在约 0.5 秒，且全部 I/O gate 正确。正式 v18/v19 同时段空闲成对验证仍待外部负载结束后执行；在此之前不回滚修复、不放宽门限，也不以重复运行覆盖失败样本。
- `RUA-07C` 未通过。Windows v`0.11.19.19.4ffd026` official build 与 Android `assembleDebug` 通过；macOS exact build 通过，但 Aqua downloaded CBZ 在 10.499 秒触发默认 10 秒超时，后续 local archive 与 online 未执行，因此四路 production fixture 验收不完整。
- macOS 正式 App 的 exact provenance 已再次通过，但图形会话保持 `CGSSessionScreenIsLocked=Yes`；锁屏期间不启动新的 Aqua fixture，也不把 WindowServer 降速伪装成产品失败。解锁后必须用全新 profile、默认 10 秒门限完成四路各一次，单路失败仍继续收集其余路由。
- production GUI final-parity 的 Reader family 与全部 13 个 family 均通过；全局 runner 仍因既有 `authors-entry partial` 永久保护门禁退出 6。Reader family 通过不等于全局 final-parity runner 通过，`authors-entry` 不得据此改为 `covered`。
- 本检查点当时保持 `IN_PROGRESS`；性能归因和 macOS 四路验收完成前不得关闭 `RUA-07B`、`RUA-07C`、`RUA-07D` 或顶层 `RUA-07`。2026-09-01 后续切换属于用户明确要求下的安全暂停，不改变这些未完成结论。

**RUA-07 Build 20 后续检查点（2026-09-01，`86ad5462070cb3b779073ec6cf7bee75c4b1f787`）**：

- Windows evidence 构建此前两次都已完成 JVM 测试/发行目录，随后才因 WSL 启动的 Windows PowerShell 优先加载 Codex runtime 中不兼容的 `Microsoft.PowerShell.Utility`、导致 validator 无法解析 `Get-FileHash` 而失败；这不是 Gradle 卡死。修复以真实 production validator 合同测试执行 RED/GREEN：`rua07-runtime-validator-red` 在移除 `Get-FileHash` 后因原实现提前失败，`rua07-runtime-validator-green` 改用流式 .NET SHA-256 后到达预期摘要拒绝，根级 `rua07-validator-format` 通过；独立审查结论 `PASS`，P0/P1/P2 均为零。
- 当前精确提交的 Windows `scripts/build-desktop.sh evidence` 由 `rua07-windows-evidence-86ad54620` 完成：完整 `app-desktop:jvmTest` 在 5 分 19 秒通过，规范未打包应用在 1 分 54 秒构建成功，真实 ManHuaGui APK 安装/生产加载验收通过。版本为 `0.11.19.20.86ad546`，product-source digest 为 `c22e83d201fbf61df136f675ec176dd9a9e61adf61ea6083fea2267b556ff8b7`，发行树 373 个文件、257,690,827 字节、SHA-256 `8f16cba78126e1b258966ae9f5ed50757e62a90e756be9a839df6512dd287363`；正式 EXE 位于 `app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.20.86ad546-unpacked/Mihon Desktop.exe`，ZIP SHA-256 为 `12d7a4069c8166c9eb904b2638856f500f302c59e87a3a6ec17fa331cae6f3616`。
- 同一提交的 Android `assembleDebug` 由 `rua07-android-86ad54620` 在 1 分 16 秒通过；`app/build/outputs/apk/debug/app-x86_64-debug.apk` 为 60,189,608 字节，SHA-256 `30f237b87f195ce9cbf90d4b81a1728cb259f892363a05069f56c54a31f8b749`。
- Build 20 正式 Windows 性能门没有启动：90 秒稳定门第 0 秒即发现 Jellyfin `ffmpeg` PID 203760，故本轮按规则标记 `INVALID/BLOCKED`，未产生 summary/raw、未重跑、未停止用户媒体进程；证据为 `C:/Users/feeli/AppData/Local/Temp/mihon-rua07-build20-gate-20260901/stability-gate.txt`。因此 `RUA-07B` 继续保持未勾选。
- macOS console user 仍为 `altair`，但 `CGSSessionScreenIsLocked=Yes`；Build 20 未传输、未 checkout、未构建、未启动 GUI/Test Mode。由于 `scripts/reader-performance.py` 属于现行 provenance 的 product input，不能把 `4ffd026cc` 的 macOS provenance 冒充为 Build 20 exact evidence；解锁后仍须对 `86ad5462070cb3b779073ec6cf7bee75c4b1f787` 重新完成 build/provenance 与四路 10 秒 Aqua fixture。因此 `RUA-07C` 继续保持未勾选。
- 本检查点当时仍保持 `IN_PROGRESS`，且不把 Windows/Android 已通过证据扩大解释为 RUA-07 完成。只有取得无外部负载的性能正式样本并完成 exact macOS 四路验收，才能进入 `RUA-07D` 关闭审计。

**RUA-07 安全暂停点（2026-09-01，治理 HEAD `de0e1eee9eb5d1f836bd667130bb893567662ce8`）**：

- 最后完成任务为 `RUA-07A`；`RUA-00`～`RUA-06` 均已实现、审查并提交。未完成任务仍为 `RUA-07B`、`RUA-07C`、`RUA-07D` 与顶层 `RUA-07`，所有 checkbox 保持未勾选。
- product/evidence 基线为 `86ad5462070cb3b779073ec6cf7bee75c4b1f787`；治理 HEAD 只增加本检查点记录。Windows JVM/正式构建与 Android assemble 证据有效，Windows 无外部负载性能样本及 macOS exact 四路验收缺失。
- 切换时工作树没有 Reader 相关 diff，仅存在无关 `?? testfile/`；没有活动 Gradle coordinator、构建或性能 runner。该未跟踪项不属于本文或后续计划，不得读取、修改或提交。
- Reader runtime、唯一 materialize/decode pipeline 与 lifecycle 接口已冻结；下载目录与 partial 阅读计划可以在此基线上串行施工，不能并行恢复本文。
- 后续计划会修改 Reader runtime/materialize/lifecycle，因此恢复本文时必须在**新的集成 HEAD** 重跑 `RUA-07A`～`RUA-07C`，再执行 `RUA-07D`；或者由后续计划 `CLOSE-01` 的同提交证据逐项证明为这些门禁的严格超集后，原子关闭两份治理状态。不得把 `4ffd026cc`/`86ad54620` 的 exact-commit 证据直接冒充新 HEAD 的关闭证据。
- parity manifest 的 Reader capability 状态在暂停时不变；`authors-entry partial` 仍受原保护门禁约束。

## 11. 测试矩阵与不可替代性能门禁

### 11.1 行为矩阵

| 层级 | 必须覆盖 | 破坏后必须失败 |
| --- | --- | --- |
| shared contract | route、artifact candidates、known/unknown image policy、稳定页表、current+4、last-five、Retry、generation | 改变候选顺序、提前相邻章、把 URL 当 identity |
| Android adapter | UniFile/SAF directory/CBZ/local/online、实体存在性、production `ChapterLoader` wiring | Android 绕过 shared route 或恢复私有决策 |
| Desktop storage | current/legacy/non-ASCII/hash/scanlator、directory/CBZ、route-specific 页序、local archive/EPUB、坏/空/缺失 | CBZ 回落在线、已知扩展逐文件打开、排序被统一、句柄泄漏 |
| Desktop HTTP | MockWebServer 成功、空列表、403/429/500、畸形/截断 body | parser/fetch 旁路 canonical materialize |
| Desktop renderer | Single/Dual/Webtoon × file/CBZ/online；animation/large/crop/split/filter/edge | 标准页第二 open/full decode、动画/region 第二 owner、UI 直接读 File/store、stale result |
| lifecycle | detach/recycle、Retry、快速翻页、切章、close、取消初始化 | Job/stream/archive 泄漏、旧 generation 回写 |
| Test Mode/E2E | production fixture、first page presented、next chapter gate、settings wiring | synthetic state 仍能在 production content 断裂时通过 |

### 11.2 deterministic 首帧门禁

标准 fixture：Single presentation 下当前章 180 张 2400×3500 JPEG，下一章同规模；另有同页数 CBZ。测试使用 gate 和 fake monotonic cost，不把易抖 wall-clock 当单元测试真相。Dual/Webtoon 另按首个 presentation viewport 实际包含的逻辑 source pages 验证：每个可见 source page 各打开/解码一次，非可见页为 0。

从 `OPEN_READER_INTENT` 到 `FIRST_PAGE_PRESENTED` 前必须满足：

- 当前目录 metadata 枚举：1 次；
- 已知扩展页内容打开：仅当前页 1 次，其余 179 页 0 次；
- 当前页 decode：1 次；
- global encoded cache scan/reconcile：0 次；
- next chapter page-list/open：0 次；
- 1 页与 180 页目录的模拟 critical-path 成本相同，均不超过“一次 list + 一次 current open + 一次 decode”；
- CBZ 允许打开一次 central-directory/archive handle，但首帧前只打开当前 entry 内容一次、decode 一次，不提前抽取其他 entry；
- gate 永不释放 background cache/adjacent I/O 时，首帧仍必须到达。

### 11.3 真实 Windows 性能预算

在本机正式未打包 production EXE、应用已经运行、标准 fixture 位于本地 SSD 的条件下，先预热 1 次，再分别执行 20 次：

- downloaded directory：`OPEN_READER_INTENT → FIRST_PAGE_PRESENTED` P95 ≤ 1.0 秒，单次不得超过 2.0 秒；
- downloaded CBZ：P95 ≤ 1.5 秒，单次不得超过 3.0 秒；
- 两类都必须同时满足第 11.2 节 I/O/decode 硬门禁。

真实时间预算是发布验收，不替代 deterministic tests。若参考设备因已记录的外部条件无法达到，必须暂停并提交单独 replan，说明测量、瓶颈和新预算；不得在实现过程中静默放宽。

## 12. 验证命令与构建纪律

同一 worktree 的 Gradle 由一个协调者串行执行。批次 focused tests 使用对应 `--tests` 过滤；下列是最终命令族，具体 test class 随 RUA-00 落名：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'

python scripts/gradle-coordinator.py run --key reader-upstream-adapter-domain -- ./gradlew :domain:jvmTest
python scripts/gradle-coordinator.py run --key reader-upstream-adapter-android -- ./gradlew :app:testReleaseUnitTest
python scripts/gradle-coordinator.py run --key reader-upstream-adapter-desktop -- ./gradlew :app-desktop:jvmTest :test-desktop:test
python scripts/gradle-coordinator.py run --key reader-upstream-adapter-final -- ./gradlew :domain:jvmTest :data:jvmTest :app:testReleaseUnitTest :app-desktop:jvmTest :test-desktop:test spotlessCheck finalParityAudit
python scripts/gradle-coordinator.py run --key reader-upstream-adapter-android-assemble -- ./gradlew assembleDebug
./scripts/build-desktop.sh
```

规则：

- RED/GREEN 只跑当前行为的 focused tests；批次完成跑相关模块与格式；全量矩阵只在 RUA-07 一次；
- Desktop 正式产物只能用 `scripts/build-desktop.sh`，不能直接用 Gradle 打包部署；
- `build-only` 只在同一未提交 diff 已有等价完整 Desktop JVM 证据时使用；本计划最终 shared core 改动默认运行普通构建；
- 构建日志中的 `Final unpacked EXE:` 是 Windows 交付地址的唯一权威；必须确认文件实际存在；
- Android/macOS 若受环境阻塞，记录真实原因和已完成证据；不得把未运行写成通过，也不得仅用系统 JDK/辅助客户端替代 production 调用链。

### 12.1 实测纠正、子批次颗粒度与 Gradle 启动预算

RUA-00 的 `.gradle-coordinator/rua00-*.json/.log` 共记录 **14 次 Gradle 启动、按 coordinator wall-clock 累计 168.9 分钟，其中 9 次失败、5 次通过**；单次启动与执行耗时约 9～18 分钟。8 次调用重复携带根级 `spotlessApply`，另有一次因调用不存在的 `:app-desktop:spotlessApply` 而完整消耗 8.7 分钟。RUA-02 在尚未拆分时又形成 25 个文件、多个独立 lifecycle/archive/wiring 状态簇，并出现 26 次 Gradle 调用；这证明只给大父任务设置统一次数上限不能替代颗粒度治理。后续优化目标是减少昂贵启动与无信息量重跑，不是削减 RED、production behavior 或最终验证证据。

**颗粒度门禁**：

- 预算单位是第 10 节的可提交子批次，不是父 RUA、单个文件或单个测试类；父 RUA 只聚合依赖与完成状态。
- 一个子批次原则上只拥有一个主要状态机或 lifecycle owner、最多两个紧密耦合的 production seam，并能用一组 focused behavior tests 独立判定完成。预计 3～8 个文件最合适；9～14 个文件需要记录不可拆的内聚性理由。
- 静态影响面一旦超过 14 个文件、包含三个以上可独立验证的上下文簇，或同时改动两个互不依赖的状态机，必须在第一次 RED 前拆分。实现中途才发现超过上限时，先停止新的 Gradle，保留现有 diff，重划子批次和依赖后继续；不得等到独立审查才处理。
- 文件数只是预警，不是为了压缩格式或复制代码的硬指标。共享 contract 无法独立编译/验收时保持内聚；可以独立交付的 adapter、lifecycle、presentation 或治理证据不得因为属于同一父 RUA 而合并。
- 已声明 invariant 的缺陷、修复引入的回归和缺失测试仍属于当前子批次，不构成产品范围扩张；新增用户能力、DB/格式迁移、不可逆文件迁移、网络策略或重新设计 UI 才需要新的范围授权。

每个子批次固定采用以下流水线：

1. **零 Gradle 预检**：先完成调用图、类型签名、DI/composition root、测试 fixture、准确 Gradle task 名称与断言时序的静态检查；确认 production wiring 被断开时测试会失败。不得用 Gradle 发现源码检查即可发现的缺 import、错误作用域或不存在的 task。
2. **RED（1 次）**：把本批行为测试合并为一次最小 focused invocation，确认测试因预期缺失的 production 行为失败；只有缺少待实现 production contract 导致的编译失败才是有效 RED。
3. **实现收口**：一次完成同一内聚 contract 的 production、adapter、wiring 和 fixture 修改；不按文件逐次启动 Gradle。
4. **可选编译门禁（0～1 次）**：仅在跨模块修改 public signature、DI、generated API 或大范围 test wiring 时运行受影响模块的 compile task。它只证明类型与接线成立，不能替代 GREEN 或行为证据。
5. **GREEN（1 次）**：运行合并后的 focused behavior suite。失败后先读完整报告并完成一次静态归因；tracked diff 没有变化时禁止原样重跑。
6. **失败修复（最多 1 次 targeted rerun）**：只重跑实际失败的测试类。相同失败原因连续出现两次时暂停，重新审视测试时序、fixture 和架构假设，不能继续试错式重跑。
7. **批次收口（1 次）**：代码稳定后只执行一次格式化，并把相关模块测试、wiring tests 与 `spotlessCheck` 尽量合并为一个协调器调用；RUA-07 前不重复全量矩阵或正式构建。
8. **冻结、审查、提交**：交付独立审查后冻结本批审查范围；审查通过且该范围的 diff 指纹不变才提交。审查发现行为缺陷时必须先补 RED 再修复，不能因为预算只运行 GREEN；审查闭环按第 14 节执行。

正常子批次的 Gradle 启动预算为 **3 次**（RED、GREEN、批次收口）；跨模块编译门禁可增加到 **4 次**；确有一次失败修复或并发时序复验时上限为 **5 次**。独立审查发现 P0/P1/P2 行为问题后，修复 TDD 可增加 **2 次**（一次 targeted RED、一次合并 GREEN/close）；纯文档、格式或既有测试 fixture 纠正只增加一次对应验证。把审查修复限制为一次调用与强制 RED/GREEN 冲突，禁止再采用该旧规则。

预算是诊断和重划触发器，不是质量硬停机：即将超出时先记录每次调用的预期信息、失败根因、剩余验证与合并方案。若原因是范围过粗，立即按上述门禁拆分；若是同一失败根因连续两次、架构前提失效或出现新增产品范围，暂停并请求 replan。若复审发现的是本轮修复引入的回归，或第一次 GREEN 前已经声明但遗漏的 invariant，允许一次**有界收口纠正**：最多一组 targeted RED/GREEN、一次只核对该 delta 的确认，不重新启动全量独立审查，也无需把代理内部返工再次交给用户审批。该纠正必须在完成证据中记录根因；同一根因再次失败时停止，不能继续循环。

并发/Compose 时序测试仅在首次失败显示非确定性风险时，才允许在首次通过后额外复验一次；复验只运行该测试类。所有 Gradle 仍由同一协调者串行执行；外层等待超时不代表 Gradle 已结束，必须先查 coordinator 状态，不能启动重复进程。

### 12.2 测试设计门禁与审查工作树冻结

每个子批次第一次 GREEN 前必须完成行为测试的 mutation checklist：

- 断开 shared owner、Android/Desktop composition root 或 production DI wiring 时，测试必须失败；
- action/render/release 前先验证负断言，之后再按 chapter/page/generation/purpose 验证身份化正断言；
- 至少覆盖一次 cancellation、late event 或 generation 交错，不能只证明顺序执行的 happy path；
- fixture 只能控制真实 production seam，不能手工记录本应由 production 发出的事件来证明自身正确；
- focused tests 按共享 contract 合并调用，避免 Android、Desktop、domain 为同一根因分别启动 Gradle。
- 纯测试/证据子批次若不改变 product behavior，可以在静态 mutation 检查后直接运行一次合并的 focused + close；不得为了形式上的 RED 故意破坏 production。只要测试暴露出需要修改 behavior 的缺陷，就立即切回完整 RED → GREEN → close 流程。
- 首帧成本必须由 production 事件、实际内容操作、源调用和网络请求等可归因操作计数构成；探针时钟只用于事件排序，不能把并发 reporter 的时间戳差值当作 deterministic cost。
- I/O gate 断言按 production 路由的可达能力建立：必须证明本路由可触发的 gate 确实进入且在释放前无副作用；静态上不可达的 gate 不得为了统一矩阵而强制触发，适用集合必须写在 fixture 中并在失败时报告路由与缺失 gate。

交付独立审查前记录当前 `HEAD`、`git status --short`、当前子批次审查范围的 `git diff --stat`、diff 指纹以及所用 Gradle log key/result。从交接到审查回执期间，主代理不得修改该子批次范围内的 production、test、fixture、manifest 或文档，也不得运行会改写这些文件的 formatter；可以继续只读分析。审查者默认复用与该范围 diff 指纹一致的测试日志，不重复运行相同的重型命令，除非证据缺失或需要验证一个具体风险。

审查期间该子批次范围内的 tracked diff 一旦变化，当前结论立即失效，后续确认按第 14 节计入修复复审或有界收口纠正。不得一边审查当前子批次，一边在同一 worktree 开始下一子批次实现。一个子批次只保留一个写入 owner；子代理只承担已经冻结边界的上游核对、日志分析或独立只读审查。

### 12.3 模型档位裁决

[OpenAI 官方模型说明](https://developers.openai.com/api/docs/models)将 `gpt-5.6-sol` 定位为复杂推理与编码的旗舰模型，`gpt-5.6-terra` 定位为智能与成本的平衡，`gpt-5.6-luna` 定位为成本敏感的高吞吐工作负载。本路线图同时涉及上游语义裁决、Android/Desktop 双端 production wiring、并发调度、generation/cancellation、文件与归档生命周期、Compose 首帧和高成本真实验证；一次错误 Gradle 启动通常比增加模型推理成本更昂贵。因此，**执行本路线图最合适的主档位是 `gpt-5.6-sol / xhigh`**，而不是以中档模型承担整体架构裁决。

| 工作类型 | 推荐模型档位 | 边界 |
| --- | --- | --- |
| `RUA-01`、`RUA-03`、`RUA-04` 的共享 owner、调度、生命周期和唯一 pipeline 裁决 | `gpt-5.6-sol / xhigh` | 主实现与独立审查均不降档 |
| `RUA-02`、`RUA-05` 的平台集成、兼容迁移和失败诊断 | `gpt-5.6-sol / high` | 出现跨 owner、持久化或 cancellation 矛盾时升至 `xhigh` |
| `RUA-06` owner 删除、mutation 证据和 authority 收口 | `gpt-5.6-sol / high` | 已冻结接口下的机械删除/文档核对可交给 Terra |
| `RUA-07` 全量验证、构建与关闭审计 | `gpt-5.6-sol / high` | 无法解释的 production 失败或最终架构矛盾升至 `xhigh` |
| 已冻结接口下的单一 adapter、fixture 扩充和机械清理 | `gpt-5.6-terra / high` | 由 Sol 主代理整合，不独立改变语义 |
| 日志汇总、链接核对、checkbox/evidence 搬运、无语义格式整理 | `gpt-5.6-luna / medium` | 只读或机械任务，不承担最终裁决 |

`max` 不作为常规档位。只有 `xhigh` 完成一次静态架构审计后仍存在相互冲突的 owner/lifecycle 方案，或 `RUA-04` cutover、`RUA-07` 收口出现无法解释的 production 失败时才升级。提高模型档位不能替代 Gradle 启动预算、工作树冻结、TDD 或 production wiring 真实性门禁。

## 13. 风险、停止条件与回滚

| 风险 | 预防 | 停止/回滚条件 |
| --- | --- | --- |
| 下载命名切换导致旧文件不可见 | dual-read、single-write；真实 legacy/hash/CBZ fixture | 任一已支持旧 artifact 不可读，停止 RUA-02，不迁移文件 |
| renderer cutover 回归动画/超大图/双页 | RUA-04 全矩阵先绿再删 legacy；decoder 互斥选择 | 任一 presentation 需恢复第二 fetch 才可用，停止并重新设计 decode port |
| cache 迁移破坏回滚 | cache 派生、版本化目录、首帧后清理 | 新实现依赖不可逆 cache 格式，禁止合并 |
| archive 句柄泄漏或抢占失败 | chapter lease、close/cancel tests、non-cooperative I/O 有界 | 切章/close 后仍有 handle/job，批次不能提交 |
| Desktop decorator污染 canonical 默认 | explicit preference、FIRST_PAGE_PRESENTED + idle、Android 不消费 | 默认产生下一章图片 I/O 或进度副作用，回滚 RUA-05 |
| 静态测试再次替代行为证据 | manifest 引用 production test/事件计数；guard 只作禁止项 | production wiring 断开后测试仍绿，capability 不得关闭 |
| 与其他 active plan 冲突 | 唯一 active-child-plan、共享文件串行 | 当前计划未安全暂停，本文不得激活 |

生产回滚以批次提交为边界，不长期保留同 session 双 loader feature flag。任何需要 DB/schema、备份格式、批量文件迁移、重新设计双页匹配或扩大网络策略的发现都超出本文；先记录阻塞并请求 replan，不得静默扩张。

## 14. 独立审查规则

每个可提交子批次完成实现与 focused tests 后进行一次独立审查；父 RUA 不重复做一轮覆盖全部内部实现的审查，最后一个 integration 子批次只检查子批次之间的 production wiring。初审发现 P0/P1/P2 时允许一次修复复审，行为修复必须保留对应 RED/GREEN 证据。审查至少回答：

1. shared contract 是否来自原版 production 代码谱系，而不是 Desktop 行为描述的再实现；
2. Android 与 Desktop production composition root 是否都消费同一决策；
3. 平台 adapter 是否偷偷拥有 route、调度、Retry、相邻章或进度语义；
4. 断开 production wiring、恢复第二 open/decode 或提前 cache/adjacent 工作时，测试是否真实失败；
5. Desktop 独有能力是否仍可用且被限制为 presentation/decorator；
6. 用户下载、偏好、进度和 cache 回滚边界是否无损；
7. 文档、manifest、fixture、测试和代码是否描述同一行为。

修复复审后的裁决按以下边界处理：

- `PASS`：提交当前子批次并冻结接口。
- 修复引入的新回归，或初始任务/测试门禁已经明确要求但在初审前遗漏的 invariant：执行第 12.1 节的一次有界收口纠正，由同一审查者只确认新增 delta；这不是第三次全量审查，也不扩大产品范围。
- 与当前修复无关的新 P2，且不影响该子批次完成定义或 production 安全：记录到最早负责该证据/清理的后续子批次，不阻塞当前提交；不得借此掩盖 P0/P1。
- 新用户能力、架构前提失效、不可逆迁移，或同一根因在有界纠正后再次出现：停止当前子批次，重划任务并请求必要授权。

子批次 checkbox 只有在实现、独立审查、验证和提交全部完成后才能勾选；测试已绿但尚未审查/提交时保持未勾选。父 RUA checkbox 从全部子项推导，不另建第二套进度状态。

## 15. 完成定义

本计划只有满足以下全部条件才能改为 `DONE`：

- [ ] 第 10 节 RUA-00～RUA-07 及其全部子批次完成，并各有与自身 scope 对应的提交/evidence；
- [ ] 原版 route、artifact、页列表、current+4、Retry、last-five 与生命周期由共享核心唯一拥有；
- [ ] Android/Desktop production 都消费这些共享语义，平台只保留列明的 adapter/presentation/decorator；
- [ ] Desktop 下载目录与下载 CBZ 离线打开，legacy/current/canonical artifact 无损兼容；
- [ ] 首帧前无 global cache scan、逐页内容验签、next chapter I/O；
- [ ] 标准静态页同 page/generation 的内容打开和 full decode 各最多一次；动画/region page 只有一个 owner，所有额外 frame/region 操作有明确 purpose 且不存在并行第二条 full-decode 链；
- [ ] Single/Dual/Webtoon、动画、超大图、裁边、滤镜、edge matching 没有功能回归；
- [ ] 默认仅末五页 page-list-only；图片预取显式 opt-in 且首帧后低优先运行；
- [ ] Test Mode 真实 production content 与第 11 节 deterministic/Windows 性能预算通过；
- [ ] Android、Desktop、Test Mode、Spotless、final parity、Windows/macOS 正式构建证据完整；
- [ ] 静态字符串/marker 不再作为 single acquisition、TTFF 或 production wiring 的完成证据；
- [ ] parity manifest、fixed-main fixture、两份 Reader architecture 文档、历史有限 supersede 与父路线状态一致；
- [ ] 不存在长期双 loader/双 renderer fallback、未说明的跳过项或未提交改动。

完成时的用户验收报告必须列出实际操作路径、功能边界、自动化命令、性能结果、commit hash，并引用构建日志 `Final unpacked EXE:` 对应的可点击绝对产物路径。
