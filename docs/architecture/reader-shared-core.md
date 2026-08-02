# 阅读器共享核心架构

## 状态与目的

本文描述 reader migration 的目标架构和当前边界。当前状态是 `MIGRATING`：

- 已共享并由 Android 生产消费：稳定 session/page 状态、page-list 与单页 materialize executor、唯一
  priority/generation scheduler、encoded store contract；Desktop `PagePreloader` 也消费同一 scheduler；
- 另已共享：解码/cache contract、宽图拆分/配对纯算法、输入导航、章节过滤和滤镜参数；
- 尚未共享：current/previous/next 章节窗口、跨章激活、进度 effect，以及 Desktop production
  materialize/session wiring；
- 当前 Android `ReaderViewModel` 与 Desktop `DesktopReaderPageLoader/ReaderScreenModel` 仍包含尚待后续
  批次迁移的运行决策，但两端不再各自解释预加载优先级或 generation。

因此 `domain/src/commonMain/kotlin/mihon/domain/reader/` 现在不是完整的唯一 reader runtime。迁移目标是让
Android 与 Desktop 消费从固定原版 Android 提取的同一个 `ReaderSessionCore`，同时把图形、文件、source、
生命周期和输入差异限制在 adapter。

固定原版行为、上游修复和 Fork 偏差以
[`reader-authority.md`](./reader-authority.md) 为准；执行顺序和门禁以
[`2026-08-02-reader-core-migration-and-presentation-roadmap.md`](../roadmap/2026-08-02-reader-core-migration-and-presentation-roadmap.md)
为准。

## 目标依赖方向

```text
Android ReaderActivity / DesktopReaderScreen
                    │
                    ▼
           presentation strategy
       Single / Webtoon / optional Dual
                    │
          DisplayUnit + VisiblePageSet
                    │
                    ▼
             ReaderSessionCore
    session / page state / scheduler / window /
      retry / adjacent policy / progress effect
                    │
                    ▼
          platform-neutral reader ports
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
    Android adapters      Desktop adapters
```

依赖只能向下：core 不知道 presentation mode；presentation 不获取内容或写进度；adapter 不决定业务状态。

## 当前已验证的窄 contract

| 文件 | 当前可引用的证据范围 | 不能据此宣称的内容 |
| --- | --- | --- |
| `ReaderPageModel.kt` | page/chapter DTO、decode/cache contract | 章节窗口、完整 session executor |
| `reader/session/`、`reader/materialize/` | 稳定逻辑页状态、page-list 与单页 materialize；Android production 已接线 | Desktop production materialize、章节窗口、进度 effect |
| `reader/scheduler/ReaderRequestScheduler.kt` | P0～P4、原版 current +4、稳定 PageId、有界并发、抢占、Retry 与 generation 拒收；Android/Desktop adapter 已接线 | 相邻章何时进入 P3/P4（由后续 window/policy 产生请求） |
| `reader/storage/EncodedPageStore.kt` | 生命周期、物理存在性、配额/淘汰结果和诊断；Android `ChapterCache` adapter 已接线 | Desktop encoded store 实现 |
| `PageTransform.kt` | 宽图尺寸/切片、纯配对算法、滤镜参数 | session core；pairing 属 presentation |
| `ReaderNavigation.kt` | tap command、inversion、章节过滤与 adjacent result | reader entry、跨章 session 激活、进度 |
| `ReadingProgressEvent` / `RecordReadingProgress` | Fork 的幂等进度事务，当前 Desktop 消费 | Android 已切到同一进度 effect |

parity manifest 9/43/44/45/47/49/51/54 通过 `readerCoreMigrationScope` 锁定这些范围；在 RD-01
关闭前，它们不能作为 canonical `ReaderSessionCore` 已接线的证据。

## Canonical session core

目标 `domain/.../reader/session/` 至少包含：

- `ReaderSessionSnapshot`：active chapter、previous/next reference、settled viewport、boundary 和一次性反馈；
- `ReaderChapterSession`：`Wait / LoadingPageList / Loaded / Error`；page list 成功前页数为 0；
- `ReaderPageSession`：稳定 `PageId` 与
  `Queued / ResolvingImage / Downloading / Ready(encodedRef) / Error`；
- intent：打开章节、报告 settled viewport、Retry 页/章、打开相邻章、关闭 session；
- effect：进度提交、可见错误、边界反馈和 adapter 副作用，且具备幂等 key；
- current/previous/next window 的 retain/release；
- 有界 priority/generation scheduler 和 adjacent policy。

页状态变化只能替换稳定 item 内的内容层，不能替换 pager/Lazy item 或 zoom container。URL 和
`EncodedPageRef` 是内容，不是身份。

## 调度与数据层边界

优先级从高到低：

1. `INTERACTIVE`：settled presentation 当前可见的所有逻辑页和显式 Retry；
2. `NEARBY`：当前章 active page 之后四页，保持固定原版默认；
3. `CURRENT_BACKGROUND`：Desktop 可选的当前章其余 encoded 内容；
4. `ADJACENT_METADATA`：距末尾不足五页时只建立相邻章 page list；
5. `ADJACENT_BACKGROUND`：Desktop 可选的下一章 encoded 内容。

`ReaderRequestScheduler` 已实现有界的当前 generation 并发、P0 抢占和 generation 迟到拒绝，请求身份使用
稳定 `ChapterId + sourcePageIndex`，不会把相邻章的同索引页合并。Android 默认 policy 保持固定原版串行
current +4，不增加正常请求的网络量；adapter 另以真实 Job completion 释放物理 permit，最多容纳一个不响应
取消的 stale 请求，所以连续快速翻页的真实 I/O 上限为“当前 policy 并发 + 1”，不会随 generation 无界增长。
Desktop adapter 可配置更高但有界的当前 generation 并发，后台请求不能饿死可见页。

`Ready` 只表示 encoded 数据可用。decoded bitmap 属于 viewport 附近的有界平台缓存，完整下一章预取
不能线性保留整章 decoded bitmap。

当前 Desktop 主 loader 与 `PagePreloader` 仍可能沿不同路径获取/解码内容；这是 RD-01 必须删除的双链，
不能描述成“已经共享同一 encoded ref”。

## Presentation SPI

`ReaderPresentationStrategy` 只做三件事：

1. 把稳定逻辑页映射为稳定 `DisplayUnit`；
2. 把 settled pager/scroll 状态转换为 `VisiblePageSet`；
3. 在 Loading/Ready/Error/Retry 间保留同一 `DisplayUnitId` 和容器。

Single、Webtoon 和 Dual 是 registry 中同级策略：

- Single：方向、宽页切片和稳定 pager key；
- Webtoon：多页可见集合、滚动锚点、auto-scroll 与 side padding；
- Dual：双槽 frame、adjacent pairing、cover-single、forced single、spread、edge matching 和 landscape
  parity。

Pairing、双槽、封面和屏幕宽度禁止进入 `ReaderSessionCore`。双页 settled 时必须上报实际可见的全部
`PageId`；只上报 `firstPage` 会让末页 pair 的进度不完整。

## Platform adapters

Core ports 使用平台无关引用，不暴露 `Context`、`File`、`InputStream`、`Bitmap`、Skia、Coil 或 Compose：

- `ReaderChapterContentPort`：online/download/local/archive 的 page descriptor；
- `ReaderPageFetchPort`：把一页 materialize 为 opaque `EncodedPageRef`；
- `ReaderEncodedPageStore`：session 生命周期、物理存在性、配额、淘汰与诊断；
- `ReaderProgressPort`：提交 core 产生的进度 effect；
- clock/diagnostics/lifecycle ports。

Android 保留 Context/Source/Download/Local、ChapterCache、Bitmap/Coil、View 与 Activity 生命周期；Desktop
保留 SourceManager/ClassLoader、download/local/archive、Skia/Compose、Voyager 和键鼠。adapter 只能映射，
不能重新实现页序、优先级、Retry、相邻章或完成规则。

较新上游 `bc7f7e70…` 的 cache journal + 实体文件存在性检查已由 Android encoded store adapter 保留；
RC-02 已恢复 cached Error 的显式 Retry 强制重抓，RC-03 又由 shared scheduler 把 Retry 提升为 P0 并
启动新 generation。encoded store 只有在 journal 与实体文件均存在后才提交逻辑索引；配额淘汰先确认
实体删除，再推进逻辑 LRU，写后异常和 session 结束期间的迟到写入会清理未索引实体。物理删除失败会
作为 storage failure 暴露，不能返回 `Stored` 或 `Ready`。由于 `ChapterCache` 自身也有物理 LRU，每次
逻辑 commit 前会对全部 tracked ref 做物理存在性 reconcile；物理层先行淘汰的 ref 会进入本次
`evictedRefs`，而不会残留 phantom diagnostics。session 启动失败同样分类为 Storage，而不是网络错误。

## 章节过渡与进度

固定原版 transition 只有 Loading、Error + Retry 和无附加控件的 Wait/Loaded；没有 Continue、Cancel 或
Dismiss。相邻章 page list 已 Loaded 且无 gap 时，adapter 把其页面 seamless 接到当前 viewer。

Desktop 目标行为是翻过末页后立即激活下一章 `LoadingPageList(pageCount = 0)`，随后一次性发布稳定 page
identity，再逐页 Ready/Error；失败显示 Retry/返回，边界显示明确结束反馈。这个产品策略属于
presentation/navigation adapter，不进入 core 文案或按钮决策。

进度只来自 settled visible logical page：

- 非末页只更新 `last_page_read`；
- 实际最后逻辑页才完成；
- prefetch、decode、创建相邻章和 dispose 都不是进度来源；
- duplicate chapter-number 标记是独立 preference；
- 阅读后序章节不能批量完成前序章节。

## 迁移门禁

| 门禁 | 完成后才允许的声明 |
| --- | --- |
| RC-01～RC-05 | shared state、materialize、scheduler、window、progress contract 完整，Android 是首个生产消费者 |
| RA-01 | Android 不再保留第二套 session/loader 决策 |
| RP-01～RP-03 | Single/Webtoon/Dual 通过同一 SPI，core 无 presentation 分支 |
| RD-01 | Desktop production 只创建 canonical session，空 URL/双 loader/Screen replace 已删除 |
| RD-02 | Desktop 完整下一章预取是可关闭的 encoded-only policy |
| RG-01 | legacy bridge/executor 删除，架构守卫与文档一致 |

只有 RD-01 的 Android、Desktop production wiring 与行为测试都齐全后，manifest 的
`canonicalSessionExecutor` 才能从 `NOT_WIRED` 改为 `WIRED`。

## 验证与失败处理

1. 产品行为严格红→绿→重构；source/HTTP 变更覆盖成功、空、403、429、500、畸形响应和 cached Retry。
2. 每个平台 adapter 使用同一 shared contract 向量；production wiring 被绕过时集成测试必须失败。
3. Compose/pager/Lazy 测试验证 stable key/container identity，而不是扫描源码字符串证明行为。
4. authority fixture 和源码边界扫描只用于 provenance/架构守卫，不能替代 production 行为验收。
5. 数据库、备份和 `last_page_read` 格式保持不变；encoded cache 是可丢弃派生数据。
6. 迁移失败时回滚入口 wiring，不恢复长期双 scheduler、双 progress policy 或 renderer 内 source fallback。
