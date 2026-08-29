---
parent-plan: 2026-06-30-mihon-desktop-refactor-roadmap.md
status: in_progress
---

# Mihon Desktop 阅读器原版语义复用与平台适配层收口 Roadmap

- 制定日期：2026-08-27
- 状态：`IN_PROGRESS`（2026-08-27 已成为父路线唯一 `active-child-plan`）
- 上级路线：[`2026-06-30-mihon-desktop-refactor-roadmap.md`](./2026-06-30-mihon-desktop-refactor-roadmap.md)
- 前一活动计划：[`2026-08-11-author-archive-discovery-corrective-roadmap.md`](./2026-08-11-author-archive-discovery-corrective-roadmap.md)（已在 `AA7-02 / AA7-03` legacy 清理门禁前安全暂停）
- 历史 Reader 计划：[`2026-08-02-reader-core-migration-and-presentation-roadmap.md`](./2026-08-02-reader-core-migration-and-presentation-roadmap.md)
- 历史纠正计划：[`2026-08-05-reader-non-upstream-capability-corrective-roadmap.md`](./2026-08-05-reader-non-upstream-capability-corrective-roadmap.md)
- 固定原版权威：`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`
- 本次上游跟踪点：`upstream/main@deb7b33118616d37536f1e5ef2ef85c8b5db0799`（2026-08-26）
- 激活基线：`main@b97487d0bb47b310b86a3d44cacf326ddd1eba34`
- 机器状态权威：[`parity-manifest.json`](../../app-desktop/src/test/resources/parity/parity-manifest.json)；本文不创建第二份 capability 状态源
- 当前进度：从第 10 节第一个未勾选顶层任务推导，不另设 `active-task`

本文于 2026-08-27 原子激活：作者归档计划在 `AA7-02 / AA7-03` legacy 观察期与清理门禁前记录安全停止点并改为 `PAUSED`，父路线唯一 `active-child-plan` 切换到本文，本文 frontmatter/正文同步改为 `IN_PROGRESS`。激活前重新 fetch 的 `upstream/main` 仍为 `deb7b33118616d37536f1e5ef2ef85c8b5db0799`，相对本文审计跟踪点没有 Reader 路径漂移。当前工作树中与本文无关的改动不属于实现证据，也不得被回滚或纳入后续功能提交。

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

激活前父路线唯一 `active-child-plan` 是作者归档纠正计划，且该计划会修改 shared domain/data、Desktop UI/DI、Test Mode 和 manifest。Reader 施工与其并行会产生共享可变状态和完成权威冲突。因此 2026-08-27 已在作者归档计划记录 `AA7-02 / AA7-03` 安全停止点，并原子切换到本文；施工期间不得并行恢复作者归档或非 Reader 计划。

- 父路线现在只指向本文；
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

上述流程已于 2026-08-27 执行；后续恢复其他计划时必须先为本文记录安全停止点，再反向原子切换，不得只修改父路线链接。

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
- [ ] `RUA-02` Desktop 下载/local/archive adapter 与无损兼容
  - [x] `RUA-02A` artifact identity、有限候选与 dual-read/single-write
  - [x] `RUA-02B` 下载 lifecycle、恢复/删除与消费者 identity
  - [ ] `RUA-02C` directory/archive adapter、惰性 entry 与 lease/generation
  - [ ] `RUA-02D` production Reader/DI 接线、离线打开与 DownloadIndex gate
- [ ] `RUA-03` 首帧 critical path 与 shared runtime owner 收口
  - [ ] `RUA-03A` session/store 启动关键路径瘦身
  - [ ] `RUA-03B` shared runner、优先级、取消与 generation
  - [ ] `RUA-03C` production TTFF、1/180 页与切章集成门禁
- [ ] `RUA-04` 唯一内容读取/解码 pipeline 与三种 presentation cutover
  - [ ] `RUA-04A` 唯一 open/materialize owner 与 single-flight
  - [ ] `RUA-04B` 唯一 decoder、decoded cache 与 transform consumers
  - [ ] `RUA-04C` Single/Dual/Webtoon presentation cutover
  - [ ] `RUA-04D` 动画/超大图/lifecycle 矩阵与 legacy owner 删除
- [ ] `RUA-05` 原版相邻章默认语义与 Desktop opt-in decorator
  - [ ] `RUA-05A` canonical last-five page-list-only 语义
  - [ ] `RUA-05B` 偏好迁移、默认值与设置 UI wiring
  - [ ] `RUA-05C` Desktop opt-in 图片预取 decorator
- [ ] `RUA-06` 假阳性测试、旧 owner、authority 与文档清理
  - [ ] `RUA-06A` 旧 owner/DI/第二链删除
  - [ ] `RUA-06B` production mutation 证据与假阳性测试替换
  - [ ] `RUA-06C` authority、manifest 与文档收口
- [ ] `RUA-07` 跨平台全量验证、Test Mode、正式构建与关闭审计
  - [ ] `RUA-07A` 跨平台测试矩阵、Spotless 与 final parity
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
- `RUA-02D` 只负责把 02A/02C 接入 production Reader/runtime/session/DI，以 production `CbzCreator` 离线打开和被 gate 的 DownloadIndex 证明 critical path 只做有限 locator 探测；同时完成跨 02A～02C 的集成验收，预计 5～9 个文件。

02A → 02B，02A + 02C → 02D；02B 与 02C 在接口冻结后可以独立推进，但同一工作树仍保持单写入 owner。每个子批次分别提交和审查；02D 的集成审查只检查跨子批次 wiring，不重新审查已经冻结的内部实现。

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
- `RUA-03C` 挂载 production fixture，统一验证 downloaded/local/online、1/180 页、cache gate、切章/关闭和实体缺失 journal；只修 integration wiring，不新增第二套调度，预计 4～8 个文件。

03A → 03B → 03C。任一子批次若需要同时重写 decoder 或 presentation，应停止并把该发现移交 RUA-04，不得扩大当前提交。

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

**预计**：父 RUA 总计约 4～6 工程日；文件和验证预算按 03A～03C 分别计算。

### `RUA-04` 唯一内容读取/解码 pipeline 与三种 presentation cutover

**子批次边界与依赖**：

- `RUA-04A` 建立唯一 page open/materialize single-flight owner 与 opaque ref 输入，先用 production runtime 证明同 page/generation 只有一次内容打开；预计 5～9 个文件。
- `RUA-04B` 在 04A 上建立唯一 decoder、有界 decoded cache，以及 edge/crop/filter/split 对同一 decoded result 的消费；动画/region 只定义带 purpose 的扩展口，不切 presentation，预计 6～10 个文件。
- `RUA-04C` 逐一把 Single、Dual、Webtoon 切到稳定 image asset/state；本子批必须同时覆盖三种 presentation，避免保留某一种 UI 私有 fetch，预计 6～12 个文件。
- `RUA-04D` 收口动画、超大图、Retry、cancel/stale generation、detach/recycle/close 与内存预算，确认矩阵全绿后删除 legacy loader/painter/preloader owner，预计 6～12 个文件。

04A → 04B → 04C → 04D。双页配对、手势和 viewport 始终不属于这些子批次；如 cutover 需要改变它们，必须单独 replan。

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

**重构/边界**：presentation identity、双页配对、手势和 viewport 不变；不得顺手重做 UI。

**focused 验证**：三 presentation × download directory/CBZ/online 的 open/decode matrix；动画、超大图、crop/split/filter、edge matcher、Retry、cancel/stale generation、内存预算。

**预计**：父 RUA 总计约 5～8 工程日；这是最高风险切点，预算按 04A～04D 分别计算，且必须在 04D 全矩阵通过后才能删除 legacy renderer 分支。

### `RUA-05` 原版相邻章默认语义与 Desktop opt-in decorator

**子批次边界与依赖**：

- `RUA-05A` 只恢复 shared canonical last-five page-list-only：第 6 页不触发、第 5 页恰好一次，且不打开下一章图片；预计 3～6 个文件。
- `RUA-05B` 只处理默认值、已有显式值保留、偏好读取/迁移和设置 UI 文案/wiring；不启动任何 prefetch job，预计 3～6 个文件。
- `RUA-05C` 在 05A/05B 上实现显式 opt-in decorator，等待首帧与 idle，以 P4 materialize encoded 内容，并覆盖抢占、quota、cancel、target switch 和无进度副作用；预计 4～8 个文件。

05A 与 05B 可在共享接口冻结后独立实现，05A + 05B → 05C。Android 不消费 05C，且 05C 不得反向改变 05A 的 canonical 默认。

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

### `RUA-06` 假阳性测试、旧 owner、authority 与文档清理

**子批次边界与依赖**：

- `RUA-06A` 根据 RUA-03/04/05 已绿的 production 路径删除旧 loader/painter/preloader/session orchestration owner、过期 DI 和不可达 helper；每次删除都由现有行为测试保护，预计 4～8 个文件。
- `RUA-06B` 用 production mutation/IO probe 证据替换源码 marker、自洽 fake 和第二链保护测试；静态 guard 只证明禁止依赖不可达，预计 4～8 个 test/fixture 文件。
- `RUA-06C` 在代码与测试冻结后更新 authority、shared-core、历史 supersede 链接、fixed-main fixture 和 manifest evidence；只做实际 capability 状态转换，预计 4～8 个 fixture/doc 文件。

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
